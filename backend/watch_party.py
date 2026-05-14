"""
Watch-Together coordinator.

In-memory, ephemeral party rooms identified by 6-char codes.  Each
party has a host (the first member, immutable until they leave) and
zero or more guests.  All members keep a single WebSocket open to
`/api/watch-party/ws/{code}` and receive broadcast messages whenever
any member emits one.

Wire protocol (JSON over WebSocket):

  Client → server:
    { type: 'hello',      role: 'host' | 'guest', name: str, avatar: str }
    { type: 'pick',       payload: { tmdb_id, media_type, title, poster, year } }
    { type: 'ready',      member_id: str }
    { type: 'play',       at_ms: int (wallclock target), position_ms: int }
    { type: 'pause',      position_ms: int }
    { type: 'seek',       position_ms: int }
    { type: 'chat',       text: str }

  Server → client (broadcast):
    { type: 'state',      members: [...], movie: {...}, status: 'lobby|countdown|playing|paused', position_ms: int, at_ms: int }
    { type: 'chat',       member: {id,name,avatar}, text: str, ts: int }
    { type: 'kicked',     reason: str }
"""
from __future__ import annotations

import asyncio
import json
import logging
import random
import string
import time
from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set

from fastapi import APIRouter, WebSocket, WebSocketDisconnect

log = logging.getLogger("watch_party")

# A code is 6 chars from an alphabet that avoids look-alikes (no
# 0/O, 1/I) so it stays readable on a TV screen when shared.
_ALPHA = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"


def _new_code(existing: Set[str]) -> str:
    while True:
        c = "".join(random.choices(_ALPHA, k=6))
        if c not in existing:
            return c


@dataclass
class Member:
    id: str
    name: str
    avatar: str
    is_host: bool = False
    socket: Optional[WebSocket] = None
    ready: bool = False


@dataclass
class Party:
    code: str
    created_at: float = field(default_factory=time.time)
    members: Dict[str, Member] = field(default_factory=dict)
    # Current selection — None until the host picks a movie/show.
    movie: Optional[Dict] = None
    # State machine: 'lobby' (still picking / waiting) → 'countdown'
    # (3-2-1) → 'playing' / 'paused'.
    status: str = "lobby"
    # Wallclock (ms since epoch) when playback should resume.  Used
    # by the countdown logic — clients compute "T-minus" relative to
    # this and start their players when the difference hits zero.
    at_ms: int = 0
    # Last known position in the movie (ms).  Authoritative source
    # for late-joiners and for resume after pause.
    position_ms: int = 0
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    def public_state(self) -> Dict:
        return {
            "type": "state",
            "code": self.code,
            "members": [
                {
                    "id": m.id,
                    "name": m.name,
                    "avatar": m.avatar,
                    "is_host": m.is_host,
                    "ready": m.ready,
                }
                for m in self.members.values()
            ],
            "movie": self.movie,
            "status": self.status,
            "at_ms": self.at_ms,
            "position_ms": self.position_ms,
            "server_ms": int(time.time() * 1000),
        }


class WatchPartyHub:
    """Per-process registry.  Sufficient for a single backend pod —
    if we ever scale horizontally we'd swap this for Redis pub/sub."""

    def __init__(self) -> None:
        self.parties: Dict[str, Party] = {}
        # Reaper thread — every 60 s evict parties older than 6h or
        # with zero connected members for > 5 min.
        self._reaper_task: Optional[asyncio.Task] = None

    def create_party(self) -> Party:
        code = _new_code(set(self.parties.keys()))
        party = Party(code=code)
        self.parties[code] = party
        return party

    def get_party(self, code: str) -> Optional[Party]:
        return self.parties.get(code.upper())

    async def broadcast(self, party: Party, payload: Dict) -> None:
        dead: List[str] = []
        for mid, m in party.members.items():
            if not m.socket:
                continue
            try:
                await m.socket.send_text(json.dumps(payload))
            except Exception:
                dead.append(mid)
        for mid in dead:
            party.members.pop(mid, None)

    async def start_reaper(self) -> None:
        if self._reaper_task and not self._reaper_task.done():
            return
        self._reaper_task = asyncio.create_task(self._reap_loop())

    async def _reap_loop(self) -> None:
        while True:
            try:
                await asyncio.sleep(60)
                now = time.time()
                to_del: List[str] = []
                for code, p in self.parties.items():
                    live = sum(1 for m in p.members.values() if m.socket)
                    age = now - p.created_at
                    if live == 0 and age > 300:
                        to_del.append(code)
                    elif age > 6 * 3600:
                        to_del.append(code)
                for code in to_del:
                    self.parties.pop(code, None)
            except Exception:  # noqa: BLE001
                log.exception("watch-party reaper crashed")


hub = WatchPartyHub()
router = APIRouter()


# ----- HTTP helper endpoints ------------------------------------------------

@router.post("/api/watch-party/create")
async def create_party() -> Dict:
    """Host kick-off.  Returns a fresh party code the host then uses
    when opening the WebSocket as `?role=host`."""
    await hub.start_reaper()
    p = hub.create_party()
    return {"code": p.code}


@router.get("/api/watch-party/state/{code}")
async def party_state(code: str) -> Dict:
    p = hub.get_party(code)
    if not p:
        return {"error": "not_found"}
    return p.public_state()


# ----- WebSocket --------------------------------------------------------------

@router.websocket("/api/watch-party/ws/{code}")
async def party_ws(websocket: WebSocket, code: str) -> None:
    await websocket.accept()
    party = hub.get_party(code)
    if not party:
        await websocket.send_text(json.dumps({"type": "error", "reason": "not_found"}))
        await websocket.close()
        return

    member: Optional[Member] = None
    try:
        while True:
            raw = await websocket.receive_text()
            try:
                msg = json.loads(raw)
            except Exception:  # noqa: BLE001
                continue
            mtype = msg.get("type")

            if mtype == "hello":
                # First message from a connecting client.  Registers
                # the member into the party and broadcasts the state.
                role = msg.get("role", "guest")
                async with party.lock:
                    is_host = role == "host" and not any(
                        m.is_host for m in party.members.values()
                    )
                    member = Member(
                        id=msg.get("member_id") or _new_code(
                            {m.id for m in party.members.values()}
                        ),
                        name=msg.get("name", "Guest"),
                        avatar=msg.get("avatar", "a1"),
                        is_host=is_host,
                        socket=websocket,
                    )
                    party.members[member.id] = member
                await websocket.send_text(
                    json.dumps({"type": "joined", "member_id": member.id})
                )
                await hub.broadcast(party, party.public_state())
                continue

            if not member:
                continue

            if mtype == "pick" and member.is_host:
                async with party.lock:
                    party.movie = msg.get("payload")
                    party.status = "lobby"
                    party.position_ms = 0
                    for m in party.members.values():
                        m.ready = False
                await hub.broadcast(party, party.public_state())

            elif mtype == "ready":
                async with party.lock:
                    member.ready = True
                await hub.broadcast(party, party.public_state())

            elif mtype == "play" and member.is_host:
                # Schedule playback ~3 s in the future so all clients
                # have time to fire their countdown.
                lead_ms = int(msg.get("lead_ms", 3000))
                position_ms = int(msg.get("position_ms", party.position_ms or 0))
                async with party.lock:
                    party.at_ms = int(time.time() * 1000) + lead_ms
                    party.position_ms = position_ms
                    party.status = "countdown"
                await hub.broadcast(party, party.public_state())

            elif mtype == "pause":
                position_ms = int(msg.get("position_ms", party.position_ms))
                async with party.lock:
                    party.position_ms = position_ms
                    party.status = "paused"
                    party.at_ms = 0
                await hub.broadcast(party, party.public_state())

            elif mtype == "resume" and member.is_host:
                lead_ms = int(msg.get("lead_ms", 1500))
                async with party.lock:
                    party.at_ms = int(time.time() * 1000) + lead_ms
                    party.position_ms = int(msg.get("position_ms", party.position_ms))
                    party.status = "countdown"
                await hub.broadcast(party, party.public_state())

            elif mtype == "seek" and member.is_host:
                async with party.lock:
                    party.position_ms = int(msg.get("position_ms", 0))
                await hub.broadcast(party, party.public_state())

            elif mtype == "playing_now":
                # Host emits this each second once playback has
                # actually started so we keep position_ms fresh
                # for late-joiners.
                if member.is_host:
                    async with party.lock:
                        party.position_ms = int(msg.get("position_ms", 0))
                        party.status = "playing"

            elif mtype == "chat":
                text = (msg.get("text") or "").strip()[:280]
                if text:
                    await hub.broadcast(
                        party,
                        {
                            "type": "chat",
                            "member": {
                                "id": member.id,
                                "name": member.name,
                                "avatar": member.avatar,
                            },
                            "text": text,
                            "ts": int(time.time() * 1000),
                        },
                    )
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        log.exception("watch-party ws crashed")
    finally:
        if member:
            party.members.pop(member.id, None)
            try:
                await hub.broadcast(party, party.public_state())
            except Exception:  # noqa: BLE001
                pass
