"""
ON NOW TV Tunes — Karaoke Party backend (v2.8.74).

Provides room-based group karaoke:
  • POST   /api/karaoke/party                            — create new party (host)
  • GET    /api/karaoke/party/{code}                     — full party state
  • POST   /api/karaoke/party/{code}/join                — guest joins with name
  • POST   /api/karaoke/party/{code}/song                — guest adds a song
  • DELETE /api/karaoke/party/{code}/song/{song_id}      — remove a queued song
  • POST   /api/karaoke/party/{code}/mode                — host sets queue mode
  • POST   /api/karaoke/party/{code}/advance             — host moves to next entry
  • POST   /api/karaoke/party/{code}/challenge           — set/clear active challenge
  • GET    /api/karaoke/party/{code}/poll?since={ts}     — long-poll for changes

State persists in-memory only (one host = one party) — no MongoDB writes,
no auth.  Parties auto-expire 8 h after creation.  This is intentionally
simple: a karaoke party is ephemeral and lives in RAM.

The QR code data URL handed back by /party is just a JSON object with the
join URL — the frontend renders the actual QR using `qrcode.react`.
"""
from __future__ import annotations

import os
import random
import secrets
import string
import time
from dataclasses import dataclass, field, asdict
from threading import Lock
from typing import Dict, List, Optional

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

from karaoke_guest_page import render_guest_join_page

karaoke_party_router = APIRouter(prefix="/karaoke", tags=["karaoke-party"])

# -- in-memory store ------------------------------------------------
PARTY_TTL_SECONDS = 8 * 60 * 60
_PARTIES: Dict[str, "Party"] = {}
_LOCK = Lock()


# -- data classes ---------------------------------------------------
@dataclass
class Member:
    id: str
    name: str
    avatar: str = ""
    is_host: bool = False
    joined_at: float = field(default_factory=time.time)
    points: int = 0


@dataclass
class QueueEntry:
    id: str                        # internal queue-entry id
    member_id: str                 # who added it
    member_name: str
    track_id: str                  # music engine track id
    title: str
    artist: str
    cover: str = ""
    added_at: float = field(default_factory=time.time)


@dataclass
class Party:
    code: str
    host_id: str
    mode: str = "normal"           # "normal" | "random"
    members: Dict[str, Member] = field(default_factory=dict)
    queue: List[QueueEntry] = field(default_factory=list)
    history: List[QueueEntry] = field(default_factory=list)
    current: Optional[QueueEntry] = None
    challenge: Optional[str] = None  # active challenge id e.g. "silent-spotlight"
    # v2.8.82 — Phone-as-microphone (WebRTC).  When the TV is about
    # to start the next queue entry, `current_singer_id` is set to
    # the member who added that song, and a "mic_armed" flag goes
    # up so their phone shows the "Turn on your mic" screen.  Once
    # they tap it, WebRTC SDP offer/answer + ICE candidates are
    # appended to `signals` (bounded to last 80 entries) and the TV
    # pulls them out via the existing /poll endpoint.
    current_singer_id: Optional[str] = None
    mic_armed: bool = False
    signals: List[dict] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)

    def touch(self) -> None:
        self.updated_at = time.time()

    def to_dict(self) -> dict:
        return {
            "code": self.code,
            "host_id": self.host_id,
            "mode": self.mode,
            "members": [asdict(m) for m in self.members.values()],
            "queue": [asdict(q) for q in self.queue],
            "history": [asdict(q) for q in self.history[-10:]],
            "current": asdict(self.current) if self.current else None,
            "challenge": self.challenge,
            "current_singer_id": self.current_singer_id,
            "mic_armed": self.mic_armed,
            # Send only the most-recent signals so the JSON doesn't
            # balloon.  Each client filters by `to` field.
            "signals": list(self.signals[-40:]),
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "join_url": _join_url(self.code),
        }


# -- helpers --------------------------------------------------------
def _backend_public_url() -> str:
    # The user-facing URL that guests will hit when scanning the QR
    # code.  Read from env so dev/prod can differ; default to
    # `https://onnowtv.duckdns.org` which is the live VPS frontend.
    return os.environ.get("PUBLIC_FRONTEND_URL", "https://onnowtv.duckdns.org").rstrip("/")


def _join_url(code: str) -> str:
    # v2.8.75 — Point QR codes at the BACKEND's self-contained
    # mobile guest page (`/api/karaoke/join/{code}`).  This is
    # reachable on the live VPS regardless of whether the React
    # frontend is deployed there, so the QR works the instant the
    # host opens the lobby on the TV.  The static page only uses
    # endpoints that are already in production: /api/karaoke/* and
    # /api/music/search.
    return f"{_backend_public_url()}/api/karaoke/join/{code}"


def _gen_code() -> str:
    # 4-digit party code so QR + manual entry are both easy.
    digits = "".join(secrets.choice(string.digits) for _ in range(4))
    return f"KARAOKE-{digits}"


def _new_id(prefix: str = "") -> str:
    return f"{prefix}{secrets.token_urlsafe(6)}"


def _gc() -> None:
    cutoff = time.time() - PARTY_TTL_SECONDS
    dead = [c for c, p in _PARTIES.items() if p.updated_at < cutoff]
    for c in dead:
        _PARTIES.pop(c, None)


def _require_party(code: str) -> Party:
    p = _PARTIES.get(code.upper())
    if not p:
        raise HTTPException(404, "Party not found or expired")
    return p


# -- request models -------------------------------------------------
class CreateParty(BaseModel):
    host_name: str = Field(..., min_length=1, max_length=40)
    host_avatar: str = ""


class JoinParty(BaseModel):
    name: str = Field(..., min_length=1, max_length=40)
    avatar: str = ""


class AddSong(BaseModel):
    member_id: str
    track_id: str
    title: str
    artist: str
    cover: str = ""


class SetMode(BaseModel):
    mode: str  # "normal" or "random"


class SetChallenge(BaseModel):
    challenge: Optional[str] = None


# -- endpoints ------------------------------------------------------
@karaoke_party_router.post("/party")
def create_party(body: CreateParty):
    """Create a new karaoke party.  Returns the party state + the host
    member ID (which the client persists in localStorage so subsequent
    requests identify them).  TV box → calls this when the user picks
    'Friends Sing Along' from Party Mode."""
    with _LOCK:
        _gc()
        # ensure uniqueness — retry up to 5 times
        for _ in range(5):
            code = _gen_code()
            if code not in _PARTIES:
                break
        else:
            raise HTTPException(503, "Could not allocate party code, try again")

        host = Member(
            id=_new_id("m_"),
            name=body.host_name.strip()[:40],
            avatar=body.host_avatar,
            is_host=True,
        )
        party = Party(code=code, host_id=host.id)
        party.members[host.id] = host
        _PARTIES[code] = party
        return {"party": party.to_dict(), "host_id": host.id}


@karaoke_party_router.get("/party/{code}")
def get_party(code: str):
    party = _require_party(code)
    return {"party": party.to_dict()}


@karaoke_party_router.post("/party/{code}/join")
def join_party(code: str, body: JoinParty):
    """Guest joins a party by code.  Returns the guest's member id +
    full party state.  Mobile join page → calls this after the guest
    types their name."""
    with _LOCK:
        party = _require_party(code)
        # Reject duplicate names (case-insensitive) so the queue stays clear
        name_clean = body.name.strip()[:40]
        if not name_clean:
            raise HTTPException(400, "Name cannot be blank")
        existing = next(
            (m for m in party.members.values()
             if m.name.lower() == name_clean.lower()),
            None,
        )
        if existing:
            # Allow the guest to update their avatar if they rejoin
            # with a different photo selection.
            if body.avatar and body.avatar != existing.avatar:
                existing.avatar = body.avatar
                party.touch()
            return {"member_id": existing.id, "party": party.to_dict()}
        member = Member(id=_new_id("m_"), name=name_clean, avatar=body.avatar)
        party.members[member.id] = member
        party.touch()
        return {"member_id": member.id, "party": party.to_dict()}


@karaoke_party_router.post("/party/{code}/song")
def add_song(code: str, body: AddSong):
    with _LOCK:
        party = _require_party(code)
        member = party.members.get(body.member_id)
        if not member:
            raise HTTPException(403, "Not in this party")
        # Reject duplicate (same member queueing same song again)
        for q in party.queue:
            if q.member_id == body.member_id and str(q.track_id) == str(body.track_id):
                return {"party": party.to_dict()}
        entry = QueueEntry(
            id=_new_id("q_"),
            member_id=body.member_id,
            member_name=member.name,
            track_id=str(body.track_id),
            title=body.title,
            artist=body.artist,
            cover=body.cover,
        )
        party.queue.append(entry)
        party.touch()
        return {"party": party.to_dict()}


@karaoke_party_router.delete("/party/{code}/song/{song_id}")
def remove_song(code: str, song_id: str, member_id: str):
    with _LOCK:
        party = _require_party(code)
        member = party.members.get(member_id)
        if not member:
            raise HTTPException(403, "Not in this party")
        # member can only remove their own songs; host can remove any
        before = len(party.queue)
        party.queue = [
            q for q in party.queue
            if not (q.id == song_id and (member.is_host or q.member_id == member_id))
        ]
        if len(party.queue) == before:
            raise HTTPException(404, "Song not found / not yours")
        party.touch()
        return {"party": party.to_dict()}


@karaoke_party_router.post("/party/{code}/mode")
def set_mode(code: str, body: SetMode):
    with _LOCK:
        party = _require_party(code)
        if body.mode not in ("normal", "random"):
            raise HTTPException(400, "mode must be 'normal' or 'random'")
        party.mode = body.mode
        party.touch()
        return {"party": party.to_dict()}


@karaoke_party_router.post("/party/{code}/advance")
def advance_queue(code: str):
    """Host triggers 'next song' — pops the next entry from the queue
    (or randomly picks one in random mode) and makes it current.  TV
    box should call this when the current song ends."""
    with _LOCK:
        party = _require_party(code)
        if party.current:
            party.history.append(party.current)
        if not party.queue:
            party.current = None
        elif party.mode == "random":
            idx = random.randint(0, len(party.queue) - 1)
            party.current = party.queue.pop(idx)
        else:
            party.current = party.queue.pop(0)
        # award the previous singer 100 pts (purely fun, no real
        # gameplay)
        if party.history and party.history[-1].member_id in party.members:
            party.members[party.history[-1].member_id].points += 100
        # v2.8.82 — Phone-as-microphone: arm the new singer's mic.
        # Their phone is polling and will switch to the "Turn on your
        # mic" screen as soon as `current_singer_id == myMemberId AND
        # mic_armed == True`.  When they tap "Turn on", they send
        # an `offer` signal and we flip `mic_armed` off via the
        # /mic-on endpoint.
        if party.current:
            party.current_singer_id = party.current.member_id
            party.mic_armed = True
        else:
            party.current_singer_id = None
            party.mic_armed = False
        # Fresh signal channel for the new singer.
        party.signals = []
        party.touch()
        return {"party": party.to_dict()}


# =================================================================
# v2.8.82 — Phone-as-microphone (WebRTC signaling)
# =================================================================
class MicSignal(BaseModel):
    from_id: str = Field(..., description="member_id of the sender; 'tv' for TV side")
    to_id: str = Field(..., description="member_id of the recipient; 'tv' for TV side")
    kind: str = Field(..., description="'offer' | 'answer' | 'ice' | 'bye'")
    payload: dict = Field(default_factory=dict)


@karaoke_party_router.post("/party/{code}/mic/signal")
def post_mic_signal(code: str, body: MicSignal):
    """Append a WebRTC signaling message to the party's signal queue.
    Both the phone (offer + ICE) and the TV (answer + ICE) use this.
    Recipients filter by their own id."""
    with _LOCK:
        party = _require_party(code)
        party.signals.append({
            "id": _new_id("sig_"),
            "from_id": body.from_id,
            "to_id": body.to_id,
            "kind": body.kind,
            "payload": body.payload,
            "at": time.time(),
        })
        # Cap the signal buffer so very chatty ICE doesn't bloat memory.
        if len(party.signals) > 80:
            party.signals = party.signals[-80:]
        party.touch()
        return {"ok": True}


@karaoke_party_router.post("/party/{code}/mic/on")
def mic_on(code: str):
    """Phone signals "mic active — start the song".  TV reads
    `mic_armed == False AND current != null` as the cue to begin
    playback (or just immediately if `mic_armed` was already off)."""
    with _LOCK:
        party = _require_party(code)
        party.mic_armed = False
        party.touch()
        return {"party": party.to_dict()}


@karaoke_party_router.post("/party/{code}/mic/arm")
def mic_arm(code: str):
    """Host can manually re-arm the mic for the current singer (e.g.
    if the singer reloaded their phone and lost the WebRTC peer)."""
    with _LOCK:
        party = _require_party(code)
        if party.current:
            party.current_singer_id = party.current.member_id
            party.mic_armed = True
            party.signals = []
            party.touch()
        return {"party": party.to_dict()}


@karaoke_party_router.post("/party/{code}/challenge")
def set_challenge(code: str, body: SetChallenge):
    """Set the currently-active challenge for the singing entry.
    `null` clears.  Valid IDs: 'silent-spotlight', 'blank-beat',
    'genre-flip', 'sip-and-sing', 'random'.  When 'random', the
    server picks one of the first four."""
    with _LOCK:
        party = _require_party(code)
        ch = body.challenge
        if ch == "random":
            ch = random.choice(["silent-spotlight", "blank-beat", "genre-flip", "sip-and-sing"])
        if ch is not None and ch not in (
            "silent-spotlight", "blank-beat", "genre-flip", "sip-and-sing",
        ):
            raise HTTPException(400, f"Unknown challenge: {ch}")
        party.challenge = ch
        party.touch()
        return {"party": party.to_dict()}


@karaoke_party_router.get("/party/{code}/poll")
def poll_party(code: str, since: float = 0.0):
    """Lightweight long-poll endpoint.  Client passes the last
    `updated_at` they saw.  Returns immediately if the party state
    has changed, otherwise waits up to ~25 s.  Cheaper than
    WebSocket plumbing for the small-scale group karaoke use case."""
    deadline = time.time() + 25
    while True:
        party = _PARTIES.get(code.upper())
        if not party:
            raise HTTPException(404, "Party not found")
        if party.updated_at > since:
            return {"party": party.to_dict()}
        if time.time() >= deadline:
            return {"party": party.to_dict(), "unchanged": True}
        time.sleep(0.6)



# -- Self-contained mobile guest join page --------------------------
@karaoke_party_router.get("/join/{code}")
def guest_join_page(code: str):
    """v2.8.75 — Serve the standalone mobile guest join HTML page.
    This is what the QR code points to.  Vanilla HTML + JS, no React
    dependency, uses only the existing backend endpoints.  Works on
    any phone browser the instant the host opens the lobby.

    Note we don't check whether the party exists here — the page's
    JS will hit /party/{code} on load and render a friendly
    "couldn't load this party" screen if it 404s.  That way the
    user gets the same look-and-feel for both the happy path and
    the error case."""
    return render_guest_join_page(code)
