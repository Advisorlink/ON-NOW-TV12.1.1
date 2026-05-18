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
    # Server-side rate-limit timestamp for emoji reactions (one
    # reaction per 800 ms per member).  Float unix seconds.
    last_reaction_at: float = 0.0


@dataclass
class Party:
    code: str
    created_at: float = field(default_factory=time.time)
    members: Dict[str, Member] = field(default_factory=dict)
    # Current selection — None until the host picks a movie/show.
    movie: Optional[Dict] = None
    # State machine: 'lobby' (still picking / waiting) → 'loading'
    # (every member is pre-buffering — we wait here until each
    # reports `ready`) → 'countdown' (3-2-1) → 'playing' / 'paused'.
    status: str = "lobby"
    # Wallclock (ms since epoch) when playback should resume.  Used
    # by the countdown logic — clients compute "T-minus" relative to
    # this and start their players when the difference hits zero.
    at_ms: int = 0
    # Last known position in the movie (ms).  Authoritative source
    # for late-joiners and for resume after pause.
    position_ms: int = 0
    # When the host hits "play" we stash the requested lead time
    # here.  It only becomes the actual `at_ms` once every member
    # has buffered enough to fire `ready`.
    pending_lead_ms: int = 3000
    loading_started_at: float = 0.0
    # The host's chosen STREAM (url, type, imdb_id, title, etc.).
    # Stashed when the host sends a `stream` message from Detail
    # AFTER picking the best stream from the resolved streams list.
    # Guests read this from the broadcast `state` payload and use
    # the SAME url so every member watches the EXACT same file.
    # Without this, host and guest each ran their own stream
    # resolution → could pick different URLs → desync + hangs.
    stream: Optional[Dict] = None
    # Last error broadcast (e.g. "no streams found").  Cleared on
    # next pick/stream.  Surfaced to clients so they can show a
    # human message instead of spinning forever.
    stream_error: Optional[str] = None
    # Async task that force-advances `loading` → `countdown` if
    # not all members report `ready` within the timeout.  Required
    # because torrenting members can stall indefinitely, hanging
    # the entire party.  Cancelled when status changes naturally.
    loading_watchdog_task: Optional[asyncio.Task] = None
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
            "stream": self.stream,
            "stream_error": self.stream_error,
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


# ----- Loading watchdog helpers ------------------------------------------

# Maximum time the party is allowed to sit in `loading` waiting for
# every member to fire `ready`.  After this, the server force-flips
# to `countdown` so slow / stuck members don't hang the whole party.
# 25 s is enough for a healthy direct stream + slow phone to buffer,
# but short enough that the user doesn't lose patience.
_LOADING_TIMEOUT_SEC = 25.0


def _cancel_loading_watchdog(party: "Party") -> None:
    t = party.loading_watchdog_task
    if t and not t.done():
        t.cancel()
    party.loading_watchdog_task = None


def _start_loading_watchdog(party: "Party") -> None:
    """Schedule the force-advance task.  Idempotent — cancels any
    existing watchdog first so multiple `play` clicks don't stack."""
    _cancel_loading_watchdog(party)

    async def _watchdog(p: "Party") -> None:
        try:
            await asyncio.sleep(_LOADING_TIMEOUT_SEC)
        except asyncio.CancelledError:
            return
        # If we're still in loading when the timer fires, force the
        # countdown.  Any members who still aren't ready will catch
        # up via the regular drift-correction logic on the client.
        async with p.lock:
            if p.status != "loading":
                return
            p.at_ms = int(time.time() * 1000) + max(2_000, p.pending_lead_ms)
            p.status = "countdown"
            # Force-mark every member ready so the next `ready`
            # branch doesn't re-trigger anything.
            for m in p.members.values():
                m.ready = True
        log.info(
            "watch-party %s force-advanced to countdown after %ss timeout",
            p.code,
            _LOADING_TIMEOUT_SEC,
        )
        await hub.broadcast(p, p.public_state())

    try:
        loop = asyncio.get_running_loop()
        party.loading_watchdog_task = loop.create_task(_watchdog(party))
    except RuntimeError:
        # No running loop — should never happen in FastAPI but
        # defensive so we don't crash.
        party.loading_watchdog_task = None


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
                    # Reset stream choice — host will pick a new
                    # one once their Detail page resolves streams.
                    party.stream = None
                    party.stream_error = None
                    for m in party.members.values():
                        m.ready = False
                    _cancel_loading_watchdog(party)
                await hub.broadcast(party, party.public_state())

            elif mtype == "stream" and member.is_host:
                # New: host has resolved the best stream URL from
                # their Detail page and is broadcasting it to every
                # member of the party.  Critical for sync — without
                # this, host & guest each ran their own stream
                # resolution and could pick different URLs, causing
                # one or both to never reach `ready` and hanging the
                # party in `loading` forever.  Now every member uses
                # the EXACT same file.
                payload = msg.get("payload") or {}
                async with party.lock:
                    party.stream = {
                        "url": str(payload.get("url") or ""),
                        "title": str(payload.get("title") or ""),
                        "type": str(payload.get("type") or "movie"),
                        "imdb_id": str(payload.get("imdb_id") or ""),
                        "subtitle_url": str(payload.get("subtitle_url") or ""),
                        "poster": str(payload.get("poster") or ""),
                        "backdrop": str(payload.get("backdrop") or ""),
                        "synopsis": str(payload.get("synopsis") or ""),
                        "year": str(payload.get("year") or ""),
                        "rating": str(payload.get("rating") or ""),
                        "runtime": str(payload.get("runtime") or ""),
                        "season": payload.get("season"),
                        "episode": payload.get("episode"),
                        "episode_title": str(payload.get("episode_title") or ""),
                        "cw_id": str(payload.get("cw_id") or ""),
                        "position_ms": int(payload.get("position_ms") or 0),
                    }
                    party.stream_error = None
                    # If we were still in lobby (no `play` fired
                    # yet), flip to loading so guests navigate.
                    if party.status == "lobby":
                        party.status = "loading"
                        party.position_ms = party.stream["position_ms"]
                        party.loading_started_at = time.time()
                        for m in party.members.values():
                            m.ready = False
                        _start_loading_watchdog(party)
                await hub.broadcast(party, party.public_state())

            elif mtype == "stream_error" and member.is_host:
                # Host's stream resolution failed (e.g. no streams
                # available from any addon).  Surface a human
                # message so guests can leave gracefully instead of
                # staring at the joining screen forever.
                reason = str(msg.get("reason") or "no_streams")[:140]
                async with party.lock:
                    party.stream_error = reason
                    party.status = "lobby"
                    party.stream = None
                    _cancel_loading_watchdog(party)
                await hub.broadcast(party, party.public_state())

            elif mtype == "ready":
                async with party.lock:
                    member.ready = True
                    # If we're in the pre-play "loading" stage and
                    # every connected member has now buffered to
                    # frame 0, kick off the synchronized countdown.
                    # This is the key invariant that guarantees
                    # everyone fires play() at the exact same
                    # wallclock instant — no member is still mid-
                    # buffer when the timer expires.
                    if party.status == "loading":
                        all_ready = len(party.members) > 0 and all(
                            m.ready for m in party.members.values()
                        )
                        if all_ready:
                            party.at_ms = int(time.time() * 1000) + party.pending_lead_ms
                            party.status = "countdown"
                            _cancel_loading_watchdog(party)
                await hub.broadcast(party, party.public_state())

            elif mtype == "play" and member.is_host:
                # Stage 1 — tell every member to load the stream.
                # We DON'T fire the countdown yet; instead we wait
                # for each member to send `ready` (i.e. their
                # libVLC has buffered enough to render frame 0).
                # Only once everyone is loaded does the server flip
                # status='countdown' (handled in the 'ready' branch
                # above).  Without this two-stage handshake the
                # member with the slowest network always lags
                # several seconds behind the host.
                lead_ms = int(msg.get("lead_ms", 3000))
                position_ms = int(msg.get("position_ms", party.position_ms or 0))
                async with party.lock:
                    party.position_ms = position_ms
                    party.pending_lead_ms = lead_ms
                    party.status = "loading"
                    party.at_ms = 0
                    party.loading_started_at = time.time()
                    for m in party.members.values():
                        m.ready = False
                    _start_loading_watchdog(party)
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
                # for late-joiners.  We also re-broadcast the
                # state so guests can detect drift and re-sync
                # if they fall more than the tolerance behind.
                if member.is_host:
                    async with party.lock:
                        party.position_ms = int(msg.get("position_ms", 0))
                        party.status = "playing"
                        _cancel_loading_watchdog(party)
                    await hub.broadcast(party, party.public_state())

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

            elif mtype == "reaction":
                # Floating emoji broadcast.  Any party member can send.
                # We rate-limit per member to one reaction every 800 ms
                # so a stuck D-pad doesn't spam the room.  Allowed
                # emojis are an explicit whitelist — the frontend only
                # ever sends from this set.
                allowed = {"\u2764\ufe0f", "\U0001F62D", "\U0001F606", "\U0001F631"}
                emoji = msg.get("emoji") or ""
                if emoji not in allowed:
                    continue
                now_ts = time.time()
                if now_ts - (member.last_reaction_at or 0) < 0.8:
                    continue
                member.last_reaction_at = now_ts
                await hub.broadcast(
                    party,
                    {
                        "type": "reaction",
                        "emoji": emoji,
                        "member": {
                            "id": member.id,
                            "name": member.name,
                            "avatar": member.avatar,
                        },
                        "ts": int(now_ts * 1000),
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
