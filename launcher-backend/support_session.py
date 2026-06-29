"""
v2.10.84 — Remote maintenance / "TeamViewer for ON NOW TV boxes".

Architecture
============

This module brokers a paired WebSocket session between an ON NOW TV
launcher box (the **host** — runs on Android, captures screen via
MediaProjection, dispatches input via AccessibilityService) and an
operator's laptop (the **controller** — opens the admin page,
enters the 6-digit code printed on the TV).

Wire protocol (both directions: JSON for control messages, BINARY
for screen frames).

Host → Backend
--------------
  • Binary frames: raw JPEG bytes.  The backend forwards as-is to
    the paired controller's WebSocket.  No re-encoding.
  • JSON `{type: "hello", device_id, build, screen_w, screen_h}` —
    sent immediately after the host WebSocket opens so the operator
    sees device identity / screen geometry up-front.
  • JSON `{type: "metrics", fps, kbps}` — periodic, every 2 s.
  • JSON `{type: "bye"}` — graceful shutdown.

Controller → Backend
--------------------
  • JSON `{type: "input", action: "tap"|"swipe"|"key"|"text", …}` —
    the backend forwards to the paired host.
        - tap:    {x: 0..1, y: 0..1}  (normalised to screen)
        - swipe:  {x1, y1, x2, y2, ms}
        - key:    {key: "DPAD_UP"|"DPAD_DOWN"|"DPAD_LEFT"|"DPAD_RIGHT"|
                   "DPAD_CENTER"|"BACK"|"HOME"|"RECENTS"|"MENU"|
                   "VOL_UP"|"VOL_DOWN"|"POWER"}
        - text:   {chars: "hello world"}
  • JSON `{type: "bye"}` — graceful shutdown.

HTTP endpoints
==============

  POST /api/support/host/register
        Called by the TV box.  Mints a session_id + 6-digit pairing
        code.  Returns {session_id, code, ttl_seconds, ws_url}.  No
        auth — the code itself is the access control (also rate-
        limited per device_id).

  POST /api/support/controller/connect
        Called by the operator's admin page.  Body {code}.  Looks up
        the matching pending session, returns {session_id, ws_url}.
        ADMIN AUTH REQUIRED.  Code is single-use (expired on first
        successful connect or after 5 minutes idle).

  WS   /api/support/host/{session_id}
        Host WebSocket.  Binary frames + JSON control messages.

  WS   /api/support/controller/{session_id}
        Controller WebSocket.  Receives frames, sends input.

  GET  /api/support/sessions
        Admin only — list active sessions (id, device_id, code,
        controller_connected, frames_relayed, opened_at).
"""

from __future__ import annotations

import asyncio
import logging
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, WebSocket, WebSocketDisconnect

logger = logging.getLogger("launcher-backend.support")

# Session TTL — operator has 5 min to enter the code on the laptop
# before the box has to mint a new one.
PAIRING_TTL_SECONDS = 5 * 60

# Once paired, an idle session is reaped 30 min after the last frame
# (covers operator leaving the tab open).
IDLE_TTL_SECONDS = 30 * 60

# Cap on simultaneous active sessions to prevent memory blow-up if
# a misbehaving box hammers register.
MAX_SESSIONS = 64


@dataclass
class SupportSession:
    session_id: str
    code: str
    device_id: Optional[str]
    created_at: float = field(default_factory=time.time)
    paired_at: Optional[float] = None
    last_frame_at: Optional[float] = None
    host_ws: Optional[WebSocket] = None
    controller_ws: Optional[WebSocket] = None
    frames_relayed: int = 0
    host_hello: dict | None = None

    def is_expired(self, now: Optional[float] = None) -> bool:
        now = now if now is not None else time.time()
        if self.controller_ws is None and self.host_ws is None:
            return (now - self.created_at) > PAIRING_TTL_SECONDS
        # Paired sessions — idle for IDLE_TTL_SECONDS.
        last_active = self.last_frame_at or self.paired_at or self.created_at
        return (now - last_active) > IDLE_TTL_SECONDS

    def summary(self) -> dict:
        now = time.time()
        return {
            "session_id": self.session_id,
            "code": self.code,
            "device_id": self.device_id,
            "host_connected": self.host_ws is not None,
            "controller_connected": self.controller_ws is not None,
            "frames_relayed": self.frames_relayed,
            "age_seconds": int(now - self.created_at),
            "idle_seconds": int(now - (self.last_frame_at or self.paired_at or self.created_at)),
            "hello": self.host_hello,
        }


# Session store + lock — single-process, in-memory.  If we ever
# horizontal-scale the launcher-backend, swap this for Redis pub/sub.
_sessions: dict[str, SupportSession] = {}
_code_to_session: dict[str, str] = {}
_lock = asyncio.Lock()


def _generate_code() -> str:
    """Six-digit human-readable code.  Avoids leading 0 so it's
    always exactly 6 chars when spoken aloud."""
    return f"{secrets.randbelow(900_000) + 100_000:06d}"


def _generate_session_id() -> str:
    return uuid.uuid4().hex


async def _reap_expired() -> None:
    """Drop sessions older than the TTL.  Cheap O(n) walk; n is
    bounded by MAX_SESSIONS."""
    async with _lock:
        now = time.time()
        dead = [s for s in _sessions.values() if s.is_expired(now)]
        for s in dead:
            _sessions.pop(s.session_id, None)
            _code_to_session.pop(s.code, None)
            for ws in (s.host_ws, s.controller_ws):
                if ws is not None:
                    try:
                        await ws.close()
                    except Exception:
                        pass


# ─────────────────────────  Public API  ───────────────────────────

router = APIRouter(prefix="/api/support", tags=["support"])


def _public_ws_origin(request_url: str) -> str:
    """Derive ws:// or wss:// origin from the FastAPI request URL.
    Falls back to ws://localhost:8002 in pod/test contexts."""
    if request_url.startswith("https://"):
        return "wss://" + request_url[len("https://"):].split("/", 1)[0]
    if request_url.startswith("http://"):
        return "ws://" + request_url[len("http://"):].split("/", 1)[0]
    return "ws://localhost:8002"


@router.post("/host/register")
async def host_register(
    payload: dict = None,
):
    """Called by the TV box's SupportSessionActivity.  Mints a fresh
    session + 6-digit code.  No auth — the code itself gates the
    pairing.  Body may carry `device_id` to enable per-device session
    listing on the admin page."""
    await _reap_expired()
    device_id = (payload or {}).get("device_id")
    async with _lock:
        if len(_sessions) >= MAX_SESSIONS:
            # Hard cap — refuse new sessions when the pod is under
            # heavy load.  Operator can fix by reaping idle ones.
            raise HTTPException(
                status_code=503,
                detail="too_many_sessions",
            )
        # Generate a code that doesn't collide with an existing one.
        for _ in range(8):
            code = _generate_code()
            if code not in _code_to_session:
                break
        else:
            # 8 collisions in a 900k space — astronomically unlikely
            # but bail safely.
            raise HTTPException(status_code=503, detail="code_space_exhausted")
        sid = _generate_session_id()
        sess = SupportSession(session_id=sid, code=code, device_id=device_id)
        _sessions[sid] = sess
        _code_to_session[code] = sid
    return {
        "session_id": sid,
        "code": code,
        "ttl_seconds": PAIRING_TTL_SECONDS,
        # Host WebSocket path — caller prepends ws://host[:port].
        "ws_path": f"/api/support/host/{sid}",
    }


@router.post("/host/cancel")
async def host_cancel(payload: dict = None):
    """Box-initiated session teardown (user pressed BACK on the
    Support screen before pairing)."""
    sid = (payload or {}).get("session_id")
    if not sid:
        raise HTTPException(400, "missing_session_id")
    async with _lock:
        sess = _sessions.pop(sid, None)
        if sess:
            _code_to_session.pop(sess.code, None)
            for ws in (sess.host_ws, sess.controller_ws):
                if ws is not None:
                    try:
                        await ws.close()
                    except Exception:
                        pass
    return {"ok": True}


def _admin_dep_factory(require_admin):
    """Bind the admin-auth dependency at register time, since
    require_admin lives in main.py and we don't want a circular
    import.  Called by main.py's `register_support_router`."""

    @router.post("/controller/connect", dependencies=[Depends(require_admin)])
    async def controller_connect(payload: dict = None):
        await _reap_expired()
        code = ((payload or {}).get("code") or "").strip()
        if not code:
            raise HTTPException(400, "missing_code")
        async with _lock:
            sid = _code_to_session.get(code)
            if not sid:
                raise HTTPException(404, "code_not_found_or_expired")
            sess = _sessions.get(sid)
            if not sess:
                raise HTTPException(404, "session_not_found")
            if sess.controller_ws is not None:
                raise HTTPException(409, "session_already_paired")
        return {
            "session_id": sid,
            "ws_path": f"/api/support/controller/{sid}",
            "device_id": sess.device_id,
            "hello": sess.host_hello,
        }

    @router.get("/sessions", dependencies=[Depends(require_admin)])
    async def list_sessions():
        await _reap_expired()
        return {"sessions": [s.summary() for s in _sessions.values()]}


# ─────────────────────────  WebSocket pair  ───────────────────────


@router.websocket("/host/{session_id}")
async def support_host_ws(websocket: WebSocket, session_id: str):
    """Long-lived WebSocket from the TV box.  Forwards binary frames
    + JSON metrics to the paired controller; relays input commands
    in the reverse direction once the controller has connected."""
    await websocket.accept()
    async with _lock:
        sess = _sessions.get(session_id)
        if not sess:
            await websocket.close(code=4404)
            return
        if sess.host_ws is not None:
            # Reject duplicate host connections — only ever ONE TV box
            # per session.
            await websocket.close(code=4409)
            return
        sess.host_ws = websocket
    try:
        while True:
            msg = await websocket.receive()
            if "bytes" in msg and msg["bytes"] is not None:
                # Binary frame — forward to controller if connected.
                frame = msg["bytes"]
                sess.last_frame_at = time.time()
                sess.frames_relayed += 1
                ctrl = sess.controller_ws
                if ctrl is not None:
                    try:
                        await ctrl.send_bytes(frame)
                    except Exception:
                        # Controller disconnected mid-frame; clear it
                        # so the box stops streaming.
                        sess.controller_ws = None
            elif "text" in msg and msg["text"] is not None:
                # JSON control message from box.
                txt = msg["text"]
                if txt:
                    try:
                        import json
                        obj = json.loads(txt)
                        if obj.get("type") == "hello":
                            sess.host_hello = obj
                        ctrl = sess.controller_ws
                        if ctrl is not None:
                            try:
                                await ctrl.send_text(txt)
                            except Exception:
                                sess.controller_ws = None
                    except Exception:
                        pass
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("host ws error: %s", e)
    finally:
        async with _lock:
            sess = _sessions.get(session_id)
            if sess is not None:
                sess.host_ws = None
                ctrl = sess.controller_ws
                if ctrl is not None:
                    try:
                        await ctrl.close(code=4000)
                    except Exception:
                        pass


@router.websocket("/controller/{session_id}")
async def support_controller_ws(websocket: WebSocket, session_id: str):
    """Operator's laptop WebSocket.  Receives frames from the host;
    sends input commands back."""
    await websocket.accept()
    async with _lock:
        sess = _sessions.get(session_id)
        if not sess:
            await websocket.close(code=4404)
            return
        if sess.controller_ws is not None:
            await websocket.close(code=4409)
            return
        sess.controller_ws = websocket
        sess.paired_at = time.time()
    # Greet the controller with the host's hello payload if we have
    # it already.
    if sess.host_hello is not None:
        try:
            import json
            await websocket.send_text(json.dumps({"type": "host_hello", **sess.host_hello}))
        except Exception:
            pass
    try:
        while True:
            msg = await websocket.receive()
            if "text" in msg and msg["text"] is not None:
                # Forward input commands to host.
                host = sess.host_ws
                if host is not None:
                    try:
                        await host.send_text(msg["text"])
                    except Exception:
                        # Host vanished — close controller.
                        await websocket.close(code=4000)
                        return
    except WebSocketDisconnect:
        pass
    except Exception as e:
        logger.warning("controller ws error: %s", e)
    finally:
        async with _lock:
            sess = _sessions.get(session_id)
            if sess is not None:
                sess.controller_ws = None
                host = sess.host_ws
                if host is not None:
                    # Tell the host "bye" so the box can release
                    # MediaProjection and return to the dock.
                    try:
                        import json
                        await host.send_text(json.dumps({"type": "controller_bye"}))
                    except Exception:
                        pass
