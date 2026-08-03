"""
Phone Remote v2 — turn a phone into a full remote for ONE specific
ON NOW TV box, with near-zero latency.

Flow
====
1. The launcher shows a STATIC QR (same URL for every box —
   `{REMOTE_WEB_URL}/remote`) plus a per-session 6-digit code.  The
   phone user can therefore save the web remote to their home screen;
   only the code changes per box/session.

2. The phone opens the web remote and enters the 6-digit code.
   `POST /api/remote/pair {code}` resolves the box session and returns
   the box's LAN ip/port (if its embedded server is up) plus a
   `same_network` hint (public-IP match).  When both are true the page
   redirects itself to `http://<box-lan-ip>:<port>/remote?...` — the
   box serves the same page over plain http and the phone talks to it
   over a SAME-ORIGIN WebSocket → ~5-20 ms per press.

3. Cloud fallback: the phone keeps a WebSocket to
   `/api/remote/ws/phone/{sid}` and the box keeps one to
   `/api/remote/ws/host/{sid}`.  Inputs are relayed instantly between
   the two sockets.  If either socket is down we fall back to the v1
   HTTP queue + long-poll.

4. The box pushes state (now-playing card + "keyboard needed" flag)
   over its WebSocket (or `POST /host/state/{sid}`); the phone renders
   it live (poster, progress w/ draggable seek, auto keyboard sheet).

Everything is in-memory + single process, mirroring support_session.py.
"""

from __future__ import annotations

import asyncio
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional

from fastapi import APIRouter, HTTPException, Request, WebSocket, WebSocketDisconnect

# 5 minutes to scan + enter the code before the box must mint a new one.
PAIRING_TTL_SECONDS = 5 * 60
# Once paired, reaped after 12h of no input + no host contact (a box
# left on all day keeps its phone remote alive).
IDLE_TTL_SECONDS = 12 * 60 * 60
MAX_SESSIONS = 64
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
ALLOWED_ACTIONS = {
    "key", "text", "longpress", "seek", "next_episode",
    # v2.13.22 — Phone trackpad ("on-screen mouse control").
    "mouse_move", "mouse_tap", "mouse_longpress",
    # v2.14.4 — Two-finger scroll on the trackpad = mouse wheel.
    "mouse_scroll",
    # v2.18.0 — Companion app: play/open content on the box.
    "companion_play", "companion_open",
}

# Companion targets the box knows how to launch.
COMPANION_TARGETS = {"vesper", "tunes", "livetv"}


@dataclass
class RemoteSession:
    session_id: str
    code: str
    device_id: Optional[str]
    # Persistent device sessions (QR-on-launcher flow): code is the
    # box's long-lived secret token; short_code is a 6-digit alias for
    # manual entry.  Never pairing-TTL'd.
    short_code: Optional[str] = None
    persistent: bool = False
    created_at: float = field(default_factory=time.time)
    paired_at: Optional[float] = None
    last_input_at: Optional[float] = None
    last_host_poll_at: Optional[float] = None
    pending_inputs: list = field(default_factory=list)
    input_seq: int = 0
    now_playing: dict | None = None
    now_playing_at: Optional[float] = None
    keyboard: bool = False
    local_ip: Optional[str] = None
    local_port: Optional[int] = None
    host_public_ip: Optional[str] = None
    host_ws: Any = None
    phone_ws: set = field(default_factory=set)

    def is_expired(self, now: Optional[float] = None) -> bool:
        now = now if now is not None else time.time()
        if self.host_ws is not None or self.phone_ws:
            return False
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
            "keyboard": self.keyboard,
            "local_ip": self.local_ip,
            "host_ws": self.host_ws is not None,
            "phone_ws": len(self.phone_ws),
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


def _client_ip(request: Request) -> str:
    fwd = request.headers.get("x-forwarded-for", "")
    if fwd:
        return fwd.split(",")[0].strip()
    return request.client.host if request.client else ""


async def _reap_expired() -> None:
    async with _lock:
        now = time.time()
        dead = [s for s in _sessions.values() if s.is_expired(now)]
        for s in dead:
            _sessions.pop(s.session_id, None)
            _code_to_session.pop(s.code, None)
            if s.short_code:
                _code_to_session.pop(s.short_code, None)
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


def _code_ok(sess: RemoteSession, code: str) -> bool:
    c = (code or "").strip()
    if secrets.compare_digest(sess.code, c):
        return True
    return bool(sess.short_code) and secrets.compare_digest(sess.short_code, c)


def _check_code(sess: RemoteSession, code: str) -> None:
    if not _code_ok(sess, code):
        raise HTTPException(403, "bad_code")


def _validate_input(body: dict) -> dict:
    """Validate a raw phone input and return the canonical payload."""
    action = (body.get("action") or "").lower()
    if action not in ALLOWED_ACTIONS:
        raise HTTPException(400, "bad_action")
    if action in ("key", "longpress"):
        key = (body.get("key") or "").upper()
        if key not in ALLOWED_KEYS:
            raise HTTPException(400, "bad_key")
        return {"action": action, "key": key}
    if action == "seek":
        try:
            pos = max(0, int(body.get("position_ms", 0)))
        except (TypeError, ValueError):
            raise HTTPException(400, "bad_position")
        return {"action": "seek", "position_ms": pos}
    if action == "next_episode":
        return {"action": "next_episode"}
    if action == "mouse_move":
        # v2.13.22 — Trackpad payload has two shapes:
        #   • {dx, dy}         — relative motion (preferred, HID-mouse
        #                         injection path)
        #   • {x, y} in [0..1] — absolute normalised (legacy /
        #                         fallback for the cmd-input path)
        # Accept either; validator preserves whichever the phone sent
        # so the box can pick the best dispatch path.
        if "dx" in body or "dy" in body:
            try:
                dx = int(body.get("dx", 0))
                dy = int(body.get("dy", 0))
            except (TypeError, ValueError):
                raise HTTPException(400, "bad_mouse_delta")
            # Clamp so a runaway JS bug can't fire a 10 000-pixel jump.
            dx = min(max(dx, -400), 400)
            dy = min(max(dy, -400), 400)
            return {"action": "mouse_move", "dx": dx, "dy": dy}
        try:
            x = float(body.get("x", 0.5))
            y = float(body.get("y", 0.5))
        except (TypeError, ValueError):
            raise HTTPException(400, "bad_mouse_coord")
        x = min(max(x, 0.0), 1.0)
        y = min(max(y, 0.0), 1.0)
        return {"action": "mouse_move", "x": x, "y": y}
    if action in ("mouse_tap", "mouse_longpress"):
        return {"action": action}
    if action == "companion_open":
        target = str(body.get("target") or "").lower()
        if target not in COMPANION_TARGETS:
            raise HTTPException(400, "bad_target")
        return {"action": "companion_open", "target": target}
    if action == "companion_play":
        target = str(body.get("target") or "").lower()
        if target not in COMPANION_TARGETS:
            raise HTTPException(400, "bad_target")
        out = {"action": "companion_play", "target": target}
        if target == "vesper":
            title = str(body.get("title") or "").strip()[:200]
            if not title:
                raise HTTPException(400, "missing_title")
            mt = str(body.get("media_type") or "movie").lower()
            out["title"] = title
            out["media_type"] = "series" if mt in ("series", "tv") else "movie"
            imdb = str(body.get("imdb") or "").strip()[:24]
            if imdb:
                out["imdb"] = imdb
            profile = str(body.get("profile") or "").strip()[:64]
            if profile:
                out["profile"] = profile
        elif target == "tunes":
            route = str(body.get("route") or "").strip()[:400]
            track_id = str(body.get("track_id") or "").strip()[:32]
            if track_id and not track_id.isdigit():
                raise HTTPException(400, "bad_track_id")
            if not route and not track_id:
                raise HTTPException(400, "missing_route")
            if route and not route.startswith("/music"):
                raise HTTPException(400, "bad_route")
            if route:
                out["route"] = route
            if track_id:
                out["track_id"] = track_id
        else:  # livetv
            stream_id = str(body.get("stream_id") or "").strip()[:32]
            if not stream_id.isdigit():
                raise HTTPException(400, "bad_stream_id")
            out["stream_id"] = stream_id
            name = str(body.get("name") or "").strip()[:120]
            if name:
                out["name"] = name
        return out
    if action == "mouse_scroll":
        # v2.14.4 — Two-finger scroll payload: {dx, dy} in scroll
        # STEPS (not pixels).  Positive dy = scroll down.  Clamp to a
        # sane range so a runaway JS bug can't flood the box with
        # 10 000-step scrolls.
        try:
            dx = int(body.get("dx", 0))
            dy = int(body.get("dy", 0))
        except (TypeError, ValueError):
            raise HTTPException(400, "bad_scroll_delta")
        dx = min(max(dx, -50), 50)
        dy = min(max(dy, -50), 50)
        return {"action": "mouse_scroll", "dx": dx, "dy": dy}
    chars = str(body.get("chars", ""))[:500]
    if not chars:
        raise HTTPException(400, "empty_text")
    return {"action": "text", "chars": chars}


def _sanitize_now_playing(np: dict) -> dict:
    return {
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
        "has_next": bool(np.get("has_next", False)),
        # v2.18.0 — Companion context: which app is playing (vesper /
        # tunes / livetv) + music artist / live channel labels so the
        # phone renders the right controls.
        "source": str(np.get("source", ""))[:16],
        "artist": str(np.get("artist", ""))[:160],
        "channel": str(np.get("channel", ""))[:120],
        "live": bool(np.get("live", False)),
    }


def _state_message(sess: RemoteSession) -> dict:
    return {
        "type": "state",
        "now_playing": sess.now_playing,
        "keyboard": sess.keyboard,
        "paired": sess.paired_at is not None,
    }


async def _push_phone_state(sess: RemoteSession) -> None:
    dead = []
    for ws in list(sess.phone_ws):
        try:
            await ws.send_json(_state_message(sess))
        except Exception:
            dead.append(ws)
    for ws in dead:
        sess.phone_ws.discard(ws)


async def _deliver_input(sess: RemoteSession, payload: dict) -> None:
    """Send an input to the box: instantly over its WebSocket when
    connected, else via the v1 queue + long-poll wakeup."""
    sess.last_input_at = time.time()
    ws = sess.host_ws
    if ws is not None:
        try:
            await ws.send_json({"type": "input", "payload": payload})
            return
        except Exception:
            sess.host_ws = None
    sess.input_seq += 1
    # v2.14.1 — Coalesce queued trackpad deltas.  When the box socket
    # is down, motion frames pile up in pending_inputs and the long-
    # poll later replays EVERY stale delta — the cursor "catches up"
    # seconds after the finger stopped and overshoots.  Summing
    # consecutive relative moves into one entry keeps the queue tiny
    # and the replayed motion equal to the NET finger travel.
    if (
        payload.get("action") == "mouse_move"
        and "dx" in payload
        and sess.pending_inputs
    ):
        last = sess.pending_inputs[-1]["payload"]
        if last.get("action") == "mouse_move" and "dx" in last:
            last["dx"] = max(-2000, min(2000, last["dx"] + payload["dx"]))
            last["dy"] = max(-2000, min(2000, last["dy"] + payload["dy"]))
            ev = _input_events.get(sess.session_id)
            if ev is not None:
                ev.set()
            return
    # v2.14.4 — Same coalescing pattern for two-finger scroll frames.
    # A fast flick generates dozens of scroll events; replaying every
    # one from the queue would multiply the intended scroll distance.
    if (
        payload.get("action") == "mouse_scroll"
        and sess.pending_inputs
    ):
        last = sess.pending_inputs[-1]["payload"]
        if last.get("action") == "mouse_scroll":
            last["dx"] = max(-200, min(200, last.get("dx", 0) + payload.get("dx", 0)))
            last["dy"] = max(-200, min(200, last.get("dy", 0) + payload.get("dy", 0)))
            ev = _input_events.get(sess.session_id)
            if ev is not None:
                ev.set()
            return
    sess.pending_inputs.append({"seq": sess.input_seq, "payload": payload})
    if len(sess.pending_inputs) > MAX_PENDING_INPUTS:
        sess.pending_inputs = sess.pending_inputs[-MAX_PENDING_INPUTS:]
    ev = _input_events.get(sess.session_id)
    if ev is not None:
        ev.set()


def _apply_host_state(sess: RemoteSession, body: dict) -> None:
    if "now_playing" in body:
        np = body.get("now_playing")
        sess.now_playing = None if np in (None, {}, "") else _sanitize_now_playing(np)
        sess.now_playing_at = time.time()
    if "keyboard" in body:
        sess.keyboard = bool(body.get("keyboard"))


# ─────────────────────────  Box (host) side  ──────────────────────

@router.post("/host/register")
async def host_register(request: Request, payload: dict = None):
    """Box mints/refreshes its remote session.

    Persistent flow (QR always on the launcher): the box sends its
    stable device_id + a long-lived secret token.  The session id is
    derived from the device id, so scanning the box's QR (or opening a
    saved home-screen shortcut) auto-connects with NO code entry.  A
    6-digit short_code is still minted as a manual-entry fallback.
    """
    await _reap_expired()
    body = payload or {}
    device_id = body.get("device_id")
    token = str(body.get("token") or "").strip()

    if token and device_id:
        import re as _re
        sid = "dev" + _re.sub(r"[^A-Za-z0-9_-]", "", str(device_id))[:48]
        async with _lock:
            sess = _sessions.get(sid)
            if sess is None:
                short = None
                for _ in range(8):
                    cand = _generate_code()
                    if cand not in _code_to_session:
                        short = cand
                        break
                sess = RemoteSession(
                    session_id=sid, code=token, device_id=device_id,
                    short_code=short, persistent=True,
                )
                sess.paired_at = time.time()
                _sessions[sid] = sess
                if short:
                    _code_to_session[short] = sid
            else:
                if sess.code != token:
                    sess.code = token
                sess.persistent = True
                if sess.paired_at is None:
                    sess.paired_at = time.time()
            sess.host_public_ip = _client_ip(request)
            sess.last_host_poll_at = time.time()
            lip = str(body.get("local_ip") or "").strip()
            if lip:
                sess.local_ip = lip[:64]
                try:
                    sess.local_port = int(body.get("local_port") or 0) or None
                except (TypeError, ValueError):
                    sess.local_port = None

        qr_target = f"{_remote_web_url}/remote?s={sid}&c={token}"
        qr_image_url = None
        if _qr_writer is not None and _qr_dir is not None:
            try:
                _qr_writer(_qr_dir / f"{sid}.png", qr_target)
                qr_image_url = f"{_public_base_url}/assets/remote_qr/{sid}.png"
            except Exception:
                qr_image_url = None
        return {
            "session_id": sid,
            "code": token,
            "short_code": sess.short_code,
            "persistent": True,
            "ttl_seconds": 0,
            "qr_target": qr_target,
            "qr_image_url": qr_image_url,
        }

    # ── Legacy one-shot flow (6-digit code, static QR) ──
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
        sess.host_public_ip = _client_ip(request)
        lip = str(body.get("local_ip") or "").strip()
        if lip:
            sess.local_ip = lip[:64]
            try:
                sess.local_port = int(body.get("local_port") or 0) or None
            except (TypeError, ValueError):
                sess.local_port = None
        _sessions[sid] = sess
        _code_to_session[code] = sid

    qr_target = f"{_remote_web_url}/remote"
    qr_image_url = None
    if _qr_writer is not None and _qr_dir is not None:
        try:
            static_png = _qr_dir / "remote-static.png"
            if not static_png.exists():
                _qr_writer(static_png, qr_target)
            qr_image_url = f"{_public_base_url}/assets/remote_qr/remote-static.png"
        except Exception:
            qr_image_url = None
    return {
        "session_id": sid,
        "code": code,
        "ttl_seconds": PAIRING_TTL_SECONDS,
        "qr_target": qr_target,
        "qr_image_url": qr_image_url,
    }


@router.post("/host/local/{session_id}")
async def host_local(session_id: str, payload: dict = None):
    """Box reports its LAN ip + embedded server port once the local
    web-remote server is actually listening."""
    sess = _require(session_id)
    body = payload or {}
    lip = str(body.get("local_ip") or "").strip()
    sess.local_ip = lip[:64] or None
    try:
        sess.local_port = int(body.get("local_port") or 0) or None
    except (TypeError, ValueError):
        sess.local_port = None
    return {"ok": True}


@router.post("/host/cancel")
async def host_cancel(payload: dict = None):
    sid = (payload or {}).get("session_id")
    if not sid:
        raise HTTPException(400, "missing_session_id")
    async with _lock:
        sess = _sessions.pop(sid, None)
        if sess:
            _code_to_session.pop(sess.code, None)
            if sess.short_code:
                _code_to_session.pop(sess.short_code, None)
            _input_events.pop(sid, None)
    return {"ok": True}


@router.get("/host/poll/{session_id}")
async def host_poll(session_id: str, since: int = 0, wait: float = 25.0):
    """v1 fallback: box long-polls for queued phone inputs."""
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
    """The box pushes a now-playing card and/or a keyboard flag."""
    sess = _require(session_id)
    _apply_host_state(sess, payload or {})
    await _push_phone_state(sess)
    return {"ok": True}


@router.websocket("/ws/host/{session_id}")
async def ws_host(ws: WebSocket, session_id: str):
    """Persistent box↔cloud socket.  Inputs from the phone are pushed
    down instantly; the box pushes state (now-playing/keyboard) up."""
    sess = _sessions.get(session_id)
    if sess is None:
        await ws.close(code=4404)
        return
    await ws.accept()
    sess.host_ws = ws
    sess.last_host_poll_at = time.time()
    try:
        while True:
            msg = await ws.receive_json()
            t = (msg.get("type") or "").lower()
            if t == "state":
                _apply_host_state(sess, msg)
                await _push_phone_state(sess)
            elif t == "ping":
                sess.last_host_poll_at = time.time()
                await ws.send_json({"type": "pong"})
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        if sess.host_ws is ws:
            sess.host_ws = None


# ─────────────────────────  Phone (controller) side  ─────────────

@router.post("/pair")
async def pair(request: Request, payload: dict = None):
    """Phone submits {code} (session_id optional).  Unlocks the remote
    and returns the box's LAN address + same-network hint so the page
    can switch to a direct local connection."""
    await _reap_expired()
    body = payload or {}
    code = (body.get("code") or "").strip()
    sid = (body.get("session_id") or "").strip()
    if not sid:
        sid = _code_to_session.get(code, "")
    sess = _require(sid)
    _check_code(sess, code)
    if sess.paired_at is None:
        sess.paired_at = time.time()
    phone_ip = _client_ip(request)
    same_network = bool(
        phone_ip and sess.host_public_ip and phone_ip == sess.host_public_ip
    )
    return {
        "ok": True,
        "session_id": sid,
        # Canonical code — for short-code manual pairs this hands the
        # phone the long-lived token so LAN-direct auth works too.
        "code": sess.code,
        "device_id": sess.device_id,
        "now_playing": sess.now_playing,
        "keyboard": sess.keyboard,
        "local_ip": sess.local_ip,
        "local_port": sess.local_port,
        "same_network": same_network,
    }


@router.post("/input/{session_id}")
async def send_input(session_id: str, payload: dict = None):
    """HTTP fallback: phone posts a single input."""
    sess = _require(session_id)
    body = payload or {}
    _check_code(sess, body.get("code"))
    if sess.paired_at is None:
        raise HTTPException(409, "not_paired")
    entry = _validate_input(body)
    await _deliver_input(sess, entry)
    return {"ok": True, "seq": sess.input_seq}


@router.get("/state/{session_id}")
async def get_state(session_id: str, code: str = "", since: float = 0.0):
    """HTTP fallback: phone polls session liveness + state."""
    sess = _require(session_id)
    _check_code(sess, code)
    return {
        "paired": sess.paired_at is not None,
        "device_id": sess.device_id,
        "now_playing": sess.now_playing,
        "now_playing_at": sess.now_playing_at,
        "keyboard": sess.keyboard,
        "host_online": sess.host_ws is not None
        or (
            sess.last_host_poll_at is not None
            and (time.time() - sess.last_host_poll_at) < 40
        ),
    }


@router.websocket("/ws/phone/{session_id}")
async def ws_phone(ws: WebSocket, session_id: str):
    """Persistent phone↔cloud socket.  Each message is an input that
    gets relayed to the box instantly; state updates stream back."""
    sess = _sessions.get(session_id)
    code = ws.query_params.get("code", "")
    if sess is None or not _code_ok(sess, code):
        await ws.close(code=4403)
        return
    await ws.accept()
    if sess.paired_at is None:
        sess.paired_at = time.time()
    sess.phone_ws.add(ws)
    try:
        await ws.send_json(_state_message(sess))
    except Exception:
        pass
    try:
        while True:
            msg = await ws.receive_json()
            t = (msg.get("action") or "").lower()
            if t == "ping":
                await ws.send_json({"type": "pong", "t": msg.get("t")})
                continue
            try:
                entry = _validate_input(msg)
            except HTTPException:
                continue
            await _deliver_input(sess, entry)
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        sess.phone_ws.discard(ws)


def register_admin(router_dep) -> None:
    """Optional admin listing — bound from main.py."""

    @router.get("/sessions", dependencies=[router_dep])
    async def list_sessions():
        await _reap_expired()
        return {"sessions": [s.summary() for s in _sessions.values()]}
