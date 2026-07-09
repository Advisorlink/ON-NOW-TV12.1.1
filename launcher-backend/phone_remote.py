"""
Phone Remote — turn a phone into a full remote for ONE specific ON NOW
TV box.

Flow
====
1. The user clicks the "Remote" icon on the launcher dock.  The box
   calls `POST /api/remote/host/register` and gets back a fresh
   session_id + 6-digit code + a QR image URL.  The box shows the QR
   (which encodes the phone web-remote URL with the session id) and
   the 6-digit code big on the TV.

2. The user scans the QR with their phone.  It opens the web remote
   app (`/#/remote?s=<session_id>`), which asks for the 6-digit code.
   Entering the correct code calls `POST /api/remote/pair` and unlocks
   the full-screen remote.  Re-pair is required every session (short
   TTL), so a stolen QR photo is useless minutes later.

3. Every button press on the phone posts to
   `POST /api/remote/input/{session_id}` (guarded by the code).  The
   box long-polls `GET /api/remote/host/poll/{session_id}` and injects
   each queued action as a system-wide `input keyevent` / `input text`
   via the existing RootInputDispatcher — so it drives the launcher,
   Vesper, and every side-loaded app on THAT box only.

4. The active player (Vesper etc.) can push a "now playing" card
   (`POST /api/remote/host/state/{session_id}`) that the phone renders
   with poster + synopsis + progress; the phone polls it via
   `GET /api/remote/state/{session_id}`.

Everything is in-memory + single process, mirroring support_session.py.
"""

from __future__ import annotations

import asyncio
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from fastapi import APIRouter, HTTPException

# 5 minutes to scan + enter the code before the box must mint a new one.
PAIRING_TTL_SECONDS = 5 * 60
# Once paired, reaped after 30 min of no input + no poll.
IDLE_TTL_SECONDS = 30 * 60
MAX_SESSIONS = 64
# Keep at most this many un-consumed inputs (drop oldest) so a phone
# that machine-guns the D-pad while the box is offline can't blow up.
MAX_PENDING_INPUTS = 200

# Allowed key names — mirrors RootInputDispatcher.KEY_ALIAS on Android.
ALLOWED_KEYS = {
    "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "DPAD_CENTER",
    "OK", "BACK", "HOME", "RECENTS", "MENU", "VOL_UP", "VOL_DOWN",
    "POWER", "DEL", "ENTER", "SEARCH", "MUTE", "PAGE_UP", "PAGE_DOWN",
    "ASSIST",
    "MEDIA_PLAY_PAUSE", "MEDIA_PLAY", "MEDIA_PAUSE",
    "MEDIA_FAST_FORWARD", "MEDIA_REWIND", "MEDIA_NEXT", "MEDIA_PREVIOUS",
    "MEDIA_STOP",
}
ALLOWED_ACTIONS = {"key", "text", "longpress"}


@dataclass
class RemoteSession:
    session_id: str
    code: str
    device_id: Optional[str]
    created_at: float = field(default_factory=time.time)
    paired_at: Optional[float] = None
    last_input_at: Optional[float] = None
    last_host_poll_at: Optional[float] = None
    pending_inputs: list = field(default_factory=list)
    input_seq: int = 0
    now_playing: dict | None = None
    now_playing_at: Optional[float] = None

    def is_expired(self, now: Optional[float] = None) -> bool:
        now = now if now is not None else time.time()
        if self.paired_at is None:
            return (now - self.created_at) > PAIRING_TTL_SECONDS
        last = self.last_input_at or self.last_host_poll_at or self.paired_at
        return (now - last) > IDLE_TTL_SECONDS

    def summary(self) -> dict:
        now = time.time()
        return {
            "session_id": self.session_id,
            "code": self.code,
            "device_id": self.device_id,
            "paired": self.paired_at is not None,
            "age_seconds": int(now - self.created_at),
            "pending": len(self.pending_inputs),
            "now_playing": bool(self.now_playing),
        }


_sessions: dict[str, RemoteSession] = {}
_code_to_session: dict[str, str] = {}
_lock = asyncio.Lock()

# Wakes the box's input long-poll the instant a new input is queued.
_input_events: dict[str, asyncio.Event] = {}


def _input_event_for(sid: str) -> asyncio.Event:
    ev = _input_events.get(sid)
    if ev is None:
        ev = asyncio.Event()
        _input_events[sid] = ev
    return ev


def _generate_code() -> str:
    return f"{secrets.randbelow(900_000) + 100_000:06d}"


async def _reap_expired() -> None:
    async with _lock:
        now = time.time()
        dead = [s for s in _sessions.values() if s.is_expired(now)]
        for s in dead:
            _sessions.pop(s.session_id, None)
            _code_to_session.pop(s.code, None)
            _input_events.pop(s.session_id, None)


router = APIRouter(prefix="/api/remote", tags=["phone-remote"])

# Bound at register-time from main.py so we don't circular-import.
_public_base_url = "http://localhost:8002"
_remote_web_url = "http://localhost:3000"
_qr_writer = None  # callable(out_path: Path, payload: str) -> None
_qr_dir = None     # pathlib.Path


def configure(public_base_url: str, remote_web_url: str, qr_writer, qr_dir) -> None:
    """Called once from main.py to inject config + the QR PNG writer."""
    global _public_base_url, _remote_web_url, _qr_writer, _qr_dir
    _public_base_url = public_base_url.rstrip("/")
    _remote_web_url = remote_web_url.rstrip("/")
    _qr_writer = qr_writer
    _qr_dir = qr_dir
    if _qr_dir is not None:
        _qr_dir.mkdir(parents=True, exist_ok=True)


def _require(sid: str) -> RemoteSession:
    sess = _sessions.get(sid)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    return sess


def _check_code(sess: RemoteSession, code: str) -> None:
    if not secrets.compare_digest(sess.code, (code or "").strip()):
        raise HTTPException(403, "bad_code")


# ─────────────────────────  Box (host) side  ──────────────────────

@router.post("/host/register")
async def host_register(payload: dict = None):
    """Box mints a new remote session + code + QR."""
    await _reap_expired()
    device_id = (payload or {}).get("device_id")
    async with _lock:
        if len(_sessions) >= MAX_SESSIONS:
            raise HTTPException(503, "too_many_sessions")
        for _ in range(8):
            code = _generate_code()
            if code not in _code_to_session:
                break
        else:
            raise HTTPException(503, "code_space_exhausted")
        sid = uuid.uuid4().hex
        sess = RemoteSession(session_id=sid, code=code, device_id=device_id)
        _sessions[sid] = sess
        _code_to_session[code] = sid

    # QR encodes the phone web-remote URL with the session id.  The
    # 6-digit code is shown separately on the TV and typed on the phone.
    qr_target = f"{_remote_web_url}/remote?s={sid}"
    qr_image_url = None
    if _qr_writer is not None and _qr_dir is not None:
        try:
            _qr_writer(_qr_dir / f"{sid}.png", qr_target)
            qr_image_url = f"{_public_base_url}/assets/remote_qr/{sid}.png"
        except Exception:
            qr_image_url = None
    return {
        "session_id": sid,
        "code": code,
        "ttl_seconds": PAIRING_TTL_SECONDS,
        "qr_target": qr_target,
        "qr_image_url": qr_image_url,
    }


@router.post("/host/cancel")
async def host_cancel(payload: dict = None):
    sid = (payload or {}).get("session_id")
    if not sid:
        raise HTTPException(400, "missing_session_id")
    async with _lock:
        sess = _sessions.pop(sid, None)
        if sess:
            _code_to_session.pop(sess.code, None)
            _input_events.pop(sid, None)
    return {"ok": True}


@router.get("/host/poll/{session_id}")
async def host_poll(session_id: str, since: int = 0, wait: float = 25.0):
    """Box long-polls for queued phone inputs.  Returns as soon as
    there's a newer input than `since`, or after `wait` seconds."""
    sess = _require(session_id)
    sess.last_host_poll_at = time.time()
    wait = max(0.1, min(wait, 25.0))
    deadline = time.time() + wait
    ev = _input_event_for(session_id)
    while True:
        fresh = [i for i in sess.pending_inputs if i["seq"] > since]
        if fresh:
            return {
                "paired": sess.paired_at is not None,
                "inputs": fresh,
                "seq": fresh[-1]["seq"],
            }
        remaining = deadline - time.time()
        if remaining <= 0:
            return {
                "paired": sess.paired_at is not None,
                "inputs": [],
                "seq": sess.input_seq,
            }
        ev.clear()
        try:
            await asyncio.wait_for(ev.wait(), timeout=remaining)
        except asyncio.TimeoutError:
            return {
                "paired": sess.paired_at is not None,
                "inputs": [],
                "seq": sess.input_seq,
            }


@router.post("/host/state/{session_id}")
async def host_state(session_id: str, payload: dict = None):
    """The active player pushes a now-playing card (or clears it)."""
    sess = _require(session_id)
    body = payload or {}
    np = body.get("now_playing")
    if np in (None, {}, ""):
        sess.now_playing = None
    else:
        sess.now_playing = {
            "title": str(np.get("title", ""))[:200],
            "synopsis": str(np.get("synopsis", ""))[:1200],
            "poster": str(np.get("poster", ""))[:1000],
            "backdrop": str(np.get("backdrop", ""))[:1000],
            "year": str(np.get("year", ""))[:16],
            "runtime": str(np.get("runtime", ""))[:24],
            "rating": str(np.get("rating", ""))[:16],
            "position_ms": int(np.get("position_ms", 0) or 0),
            "duration_ms": int(np.get("duration_ms", 0) or 0),
            "playing": bool(np.get("playing", True)),
        }
    sess.now_playing_at = time.time()
    return {"ok": True}


# ─────────────────────────  Phone (controller) side  ─────────────

@router.post("/pair")
async def pair(payload: dict = None):
    """Phone submits {session_id, code}.  Unlocks the remote."""
    await _reap_expired()
    body = payload or {}
    sid = (body.get("session_id") or "").strip()
    code = (body.get("code") or "").strip()
    sess = _require(sid)
    _check_code(sess, code)
    if sess.paired_at is None:
        sess.paired_at = time.time()
    return {
        "ok": True,
        "session_id": sid,
        "device_id": sess.device_id,
        "now_playing": sess.now_playing,
    }


@router.post("/input/{session_id}")
async def send_input(session_id: str, payload: dict = None):
    """Phone posts a single input.  Guarded by the pairing code."""
    sess = _require(session_id)
    body = payload or {}
    _check_code(sess, body.get("code"))
    if sess.paired_at is None:
        raise HTTPException(409, "not_paired")
    action = (body.get("action") or "").lower()
    if action not in ALLOWED_ACTIONS:
        raise HTTPException(400, "bad_action")
    entry_payload: dict
    if action in ("key", "longpress"):
        key = (body.get("key") or "").upper()
        if key not in ALLOWED_KEYS:
            raise HTTPException(400, "bad_key")
        entry_payload = {"action": action, "key": key}
    else:  # text
        chars = str(body.get("chars", ""))[:500]
        if not chars:
            raise HTTPException(400, "empty_text")
        entry_payload = {"action": "text", "chars": chars}

    sess.input_seq += 1
    sess.last_input_at = time.time()
    sess.pending_inputs.append({"seq": sess.input_seq, "payload": entry_payload})
    if len(sess.pending_inputs) > MAX_PENDING_INPUTS:
        sess.pending_inputs = sess.pending_inputs[-MAX_PENDING_INPUTS:]
    ev = _input_events.get(session_id)
    if ev is not None:
        ev.set()
    return {"ok": True, "seq": sess.input_seq}


@router.get("/state/{session_id}")
async def get_state(session_id: str, code: str = "", since: float = 0.0):
    """Phone polls session liveness + the now-playing card."""
    sess = _require(session_id)
    _check_code(sess, code)
    return {
        "paired": sess.paired_at is not None,
        "device_id": sess.device_id,
        "now_playing": sess.now_playing,
        "now_playing_at": sess.now_playing_at,
    }


def register_admin(router_dep) -> None:
    """Optional admin listing — bound from main.py."""

    @router.get("/sessions", dependencies=[router_dep])
    async def list_sessions():
        await _reap_expired()
        return {"sessions": [s.summary() for s in _sessions.values()]}
