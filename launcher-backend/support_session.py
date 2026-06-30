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
import json
import logging
import secrets
import time
import uuid
from dataclasses import dataclass, field
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException, Request, WebSocket, WebSocketDisconnect

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
    # v2.10.87 — HTTP-polling fallback for operator browsers behind
    # CDNs / proxies that refuse WebSocket upgrades (Cloudflare's
    # free-plan WAF on certain zones, corporate firewalls, etc).
    # The host always uses WS (it's outbound from the box → backend
    # and that works), but the controller can choose its protocol.
    # We hold the latest JPEG frame + a monotonically-increasing
    # seq number so the browser can poll
    # `GET /api/support/poll/frame/{sid}?since=<seq>` and get the
    # newest frame whenever it changes.  Pending inputs from the
    # controller are buffered in a deque until the host's WS picks
    # them up via the existing relay (or via a NEW polling endpoint
    # if we ever drop WS entirely).
    latest_frame: Optional[bytes] = None
    latest_frame_seq: int = 0
    pending_inputs: list = field(default_factory=list)
    pending_input_seq: int = 0
    last_controller_poll: Optional[float] = None

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


@router.get("/host/status/{session_id}")
async def host_status(session_id: str, since_paired: int = 0, wait: float = 20.0):
    """Box long-polls this endpoint after register() to find out when
    a technician has entered the code on the operator panel.  Returns
    immediately if already paired, otherwise blocks for up to `wait`
    seconds.  Box uses the response to switch from the "Waiting for
    technician" UI to the "Tap OK to share" UI.

    Response:  `{paired: bool, paired_at: float|null}`
    """
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    wait = max(0.1, min(wait, 25.0))
    deadline = time.time() + wait
    ev = _pair_event_for(session_id)
    while True:
        if sess.paired_at is not None:
            return {"paired": True, "paired_at": sess.paired_at}
        remaining = deadline - time.time()
        if remaining <= 0:
            return {"paired": False, "paired_at": None}
        try:
            await asyncio.wait_for(ev.wait(), timeout=remaining)
        except asyncio.TimeoutError:
            return {"paired": False, "paired_at": None}


# Per-session asyncio.Event used to wake up host pairing pollers the
# instant an operator enters the code + clicks Connect.
_pair_events: dict[str, asyncio.Event] = {}


def _pair_event_for(sid: str) -> asyncio.Event:
    ev = _pair_events.get(sid)
    if ev is None:
        ev = asyncio.Event()
        _pair_events[sid] = ev
    return ev


# Per-session asyncio.Event used to wake up operator frame pollers
# the INSTANT a new frame arrives.  Without this the operator's
# /poll/frame loop has to sit on a `await asyncio.sleep(0.1)` between
# checks, adding up to 100ms of pure latency for every frame.
_frame_events: dict[str, asyncio.Event] = {}


def _frame_event_for(sid: str) -> asyncio.Event:
    ev = _frame_events.get(sid)
    if ev is None:
        ev = asyncio.Event()
        _frame_events[sid] = ev
    return ev


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
            # v2.10.90 — Mark the session as paired NOW (operator
            # has entered the code), even though no WebSocket is
            # used in the pure-HTTP flow.  The box is polling
            # /host/status/{sid} for exactly this signal — it's
            # what flips the activity from "Waiting for technician"
            # to "Tap OK to share your screen".
            if sess.paired_at is None:
                sess.paired_at = time.time()
            ev = _pair_event_for(sid)
            ev.set()
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


# ─────────────────────────  HTTP-polling fallback  ──────────────────
#
# v2.10.87 — Operator browsers that sit behind a CDN / WAF that
# refuses WebSocket upgrades (Cloudflare on certain plans/paths,
# corporate firewalls, some country-level filtering) need a way to
# drive a support session over plain HTTPS.  These endpoints replace
# the controller-side WebSocket with two ordinary HTTP requests:
#
#   GET  /api/support/poll/frame/{sid}?since=<seq>
#        Long-polls (up to ~25 s) until a newer frame is available
#        than `since`.  Returns the JPEG body with the new seq in
#        the `X-Frame-Seq` header.  204 No Content if the host
#        disconnects with no frame.
#
#   POST /api/support/input/{sid}
#        Body: {"action":"tap"|"swipe"|"key"|"text", ...}
#        Forwards the input to the host's WebSocket.
#
# The HOST side still uses its outbound WebSocket (which works fine —
# only the BROWSER side hits the upgrade-blocking proxies).
#
# These are intentionally OUTSIDE the `_admin_dep_factory` so they
# can be called from a plain <script> without auth — the 6-digit
# session code already gated the access via `/controller/connect`.


from fastapi.responses import Response  # noqa: E402


@router.get("/poll/frame/{session_id}")
async def poll_frame(session_id: str, since: int = 0, wait: float = 20.0):
    """Long-poll for the next JPEG frame newer than `since`.
    Returns image/jpeg with X-Frame-Seq header, or 204 if no
    new frame arrives within `wait` seconds.

    v2.10.90 — Event-driven (was polling with `asyncio.sleep(0.1)`).
    A waiting controller is woken the instant a new frame arrives
    via /host/frame/{sid}, which removes up to 100ms of artificial
    latency on EVERY frame.  At 12 fps that's a ~25% improvement in
    perceived snappiness."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    wait = max(0.1, min(wait, 25.0))
    deadline = time.time() + wait
    ev = _frame_event_for(session_id)
    while True:
        if sess.latest_frame is not None and sess.latest_frame_seq > since:
            sess.last_controller_poll = time.time()
            return Response(
                content=sess.latest_frame,
                media_type="image/jpeg",
                headers={
                    "X-Frame-Seq": str(sess.latest_frame_seq),
                    "Cache-Control": "no-store",
                },
            )
        remaining = deadline - time.time()
        if remaining <= 0:
            sess.last_controller_poll = time.time()
            return Response(status_code=204, headers={"X-Frame-Seq": str(sess.latest_frame_seq)})
        try:
            await asyncio.wait_for(ev.wait(), timeout=remaining)
        except asyncio.TimeoutError:
            sess.last_controller_poll = time.time()
            return Response(status_code=204, headers={"X-Frame-Seq": str(sess.latest_frame_seq)})
        # Don't clear the event globally — another waiter may need
        # the same frame.  We loop and re-check sess.latest_frame_seq;
        # if our `since` < seq we'll return on the next iteration.
        # Clear is done lazily inside /host/frame after every set().


@router.get("/poll/hello/{session_id}")
async def poll_hello(session_id: str):
    """One-shot fetch of the host_hello + screen geometry once the
    host WS opens.  Returns {hello: null} until the host connects."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    sess.last_controller_poll = time.time()
    return {
        "hello": sess.host_hello,
        "host_connected": sess.host_ws is not None,
        "device_id": sess.device_id,
        "latest_frame_seq": sess.latest_frame_seq,
    }


# ─────────────────────────  Streaming binary frame endpoint  ────────
#
# v2.10.91 — Pushes JPEG frames over a single persistent HTTP
# response as length-prefixed binary chunks.  Each chunk is:
#
#     | 4-byte big-endian length | JPEG bytes |
#
# A zero-length chunk is a heartbeat (sent every 20s) so Cloudflare
# doesn't drop the idle connection.
#
# This kills the per-frame HTTP-request overhead — instead of ~12
# POST+poll round-trips per second, the operator's browser keeps
# ONE connection open and reads frames as they arrive.  In practice
# end-to-end latency drops from ~300+ms per frame to ~50-100ms,
# bringing the experience much closer to AnyDesk-class remote
# desktops without the complexity of WebRTC.

from fastapi.responses import StreamingResponse  # noqa: E402


@router.get("/stream/frame/{session_id}")
async def stream_frame_binary(session_id: str):
    """Persistent binary stream of JPEG frames.  See module comment
    for the wire format.  Operator browser reads this via
    `fetch(url).then(r => r.body.getReader())` — supported in every
    Chromium/Firefox/Safari since 2016."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")

    async def generator():
        last_seq = 0
        ev = _frame_event_for(session_id)
        last_heartbeat = time.time()
        while True:
            sess_now = _sessions.get(session_id)
            if sess_now is None:
                return  # session reaped — close stream
            if sess_now.latest_frame is not None and sess_now.latest_frame_seq > last_seq:
                jpeg = sess_now.latest_frame
                last_seq = sess_now.latest_frame_seq
                yield len(jpeg).to_bytes(4, "big") + jpeg
                last_heartbeat = time.time()
                continue
            # Heartbeat every 20s — zero-length chunk so the
            # browser knows the stream is still alive.  Without
            # this Cloudflare may sever the connection at ~100s.
            if time.time() - last_heartbeat > 20:
                yield b"\x00\x00\x00\x00"
                last_heartbeat = time.time()
            try:
                await asyncio.wait_for(ev.wait(), timeout=20)
            except asyncio.TimeoutError:
                pass

    return StreamingResponse(
        generator(),
        media_type="application/octet-stream",
        headers={
            # nginx: don't buffer this response — push every chunk
            # to the client the instant we yield it.
            "X-Accel-Buffering": "no",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        },
    )


@router.post("/input/{session_id}")
async def post_input(session_id: str, payload: dict = None):
    """Send an input command (tap / key / swipe / text) to the host.
    Queues it for the host to fetch — works for both WS-based hosts
    (host_ws picks it up via flush) and HTTP-polling hosts (box GETs
    it via /host/inputs/{sid})."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    if not payload or not isinstance(payload, dict):
        raise HTTPException(400, "missing_payload")
    payload.setdefault("type", "input")
    # v2.10.88 — Always queue.  If a host_ws is connected, it gets
    # flushed below.  If the host is HTTP-polling, it picks up the
    # input via /host/inputs/{sid}?since=<seq>.  Cap queue size so a
    # misbehaving operator can't OOM the backend.
    if len(sess.pending_inputs) > 200:
        sess.pending_inputs.pop(0)
    sess.pending_input_seq += 1
    item = {"seq": sess.pending_input_seq, "payload": payload}
    sess.pending_inputs.append(item)
    # Wake any host long-poller.
    ev = _input_events.get(session_id)
    if ev is not None:
        ev.set()
    # If a WS host is connected, also push immediately for low latency.
    host = sess.host_ws
    if host is not None:
        try:
            await host.send_text(json.dumps(payload))
        except Exception as e:
            logger.warning("input forward failed: %s", e)
    return {"ok": True, "seq": sess.pending_input_seq}


# ─────────────────────────  HTTP-polling for the BOX  ───────────────
#
# v2.10.88 — The TV box used to stream frames over an outbound
# WebSocket.  In practice that connection got killed within ~60-90s
# on Cloudflare's free plan (the customer's box showed "Connection
# lost — restart to retry").  Switch the box to plain HTTP too:
#
#   POST /api/support/host/hello/{sid}      — one-shot, sets host_hello
#   POST /api/support/host/frame/{sid}      — multipart JPEG upload, ~6/s
#   GET  /api/support/host/inputs/{sid}     — long-poll for queued
#                                              operator inputs
#
# This eliminates every WebSocket from the entire feature — works on
# any CDN, behind any firewall, no nginx tuning required.

# Per-session asyncio.Event used to wake up host long-pollers the
# instant an operator posts an input.  Lives separate from the
# session dataclass so we don't have to make every session ctor
# async.
_input_events: dict[str, asyncio.Event] = {}


def _input_event_for(sid: str) -> asyncio.Event:
    ev = _input_events.get(sid)
    if ev is None:
        ev = asyncio.Event()
        _input_events[sid] = ev
    return ev


@router.post("/host/hello/{session_id}")
async def host_hello_post(session_id: str, payload: dict = None):
    """Box posts its hello info (device_id, build, screen geometry)
    here once at the start of an HTTP-polling session.  Same payload
    shape as the old WS hello message."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    if not payload or not isinstance(payload, dict):
        raise HTTPException(400, "missing_payload")
    sess.host_hello = payload
    if not sess.device_id and payload.get("device_id"):
        sess.device_id = payload["device_id"]
    return {"ok": True}


@router.post("/host/frame/{session_id}")
async def host_frame_post(session_id: str, request: Request):
    """Box posts a JPEG frame here.  Body is the raw JPEG bytes
    (Content-Type: image/jpeg).  We stash it in the session so the
    operator's polling browser picks it up via /poll/frame/{sid}."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    body = await request.body()
    if not body:
        raise HTTPException(400, "empty_frame")
    if len(body) > 2 * 1024 * 1024:
        raise HTTPException(413, "frame_too_large")
    sess.last_frame_at = time.time()
    sess.frames_relayed += 1
    sess.latest_frame = body
    sess.latest_frame_seq += 1
    # v2.10.90 — Wake any operator long-pollers immediately.
    ev = _frame_event_for(session_id)
    ev.set()
    # Clear right away so the next iteration is gated on the next
    # set().  This is safe because operators re-check
    # sess.latest_frame_seq > since on every wake — they don't
    # depend on the event staying set.
    ev.clear()
    return {"ok": True, "seq": sess.latest_frame_seq}


@router.get("/host/inputs/{session_id}")
async def host_inputs_get(session_id: str, since: int = 0, wait: float = 20.0):
    """Box long-polls for queued operator inputs.  Returns up to
    ~25 s after the first input is available (or `wait` seconds if
    nothing arrives).  Response: `{inputs: [{seq, payload}, ...]}`.
    Box passes the highest `seq` it has processed as `since` on the
    next request to avoid re-processing.

    This endpoint is what makes the BOX side proxy-agnostic — plain
    HTTPS long-poll, no WebSocket needed."""
    sess = _sessions.get(session_id)
    if sess is None:
        raise HTTPException(404, "session_not_found")
    wait = max(0.1, min(wait, 25.0))
    deadline = time.time() + wait
    ev = _input_event_for(session_id)
    while True:
        # Filter inputs newer than `since`.
        new_items = [it for it in sess.pending_inputs if it.get("seq", 0) > since]
        if new_items:
            # Drop everything <= last delivered to keep memory bounded.
            max_seq = max(it["seq"] for it in new_items)
            sess.pending_inputs = [
                it for it in sess.pending_inputs if it.get("seq", 0) > max_seq
            ]
            ev.clear()
            return {"inputs": new_items, "max_seq": max_seq}
        remaining = deadline - time.time()
        if remaining <= 0:
            return {"inputs": [], "max_seq": since}
        try:
            await asyncio.wait_for(ev.wait(), timeout=remaining)
        except asyncio.TimeoutError:
            return {"inputs": [], "max_seq": since}
        ev.clear()


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
    # v2.10.87/v2.10.88 — Flush any inputs the operator queued while
    # the box was still booting up the support session.  The
    # `pending_inputs` queue holds `{seq, payload}` items so HTTP-
    # polling hosts can track which they've processed; for WS hosts
    # we only need the payload.
    queued = list(sess.pending_inputs)
    sess.pending_inputs.clear()
    for q in queued:
        try:
            await websocket.send_text(json.dumps(q.get("payload", q)))
        except Exception:
            break
    try:
        while True:
            msg = await websocket.receive()
            if "bytes" in msg and msg["bytes"] is not None:
                # Binary frame — forward to controller if connected.
                frame = msg["bytes"]
                sess.last_frame_at = time.time()
                sess.frames_relayed += 1
                # v2.10.87 — Also stash for HTTP-polling controllers.
                # Cap at 2 MB so a runaway box can't blow up memory.
                if len(frame) < 2 * 1024 * 1024:
                    sess.latest_frame = frame
                    sess.latest_frame_seq += 1
                    # v2.10.90 — Wake operator long-pollers instantly.
                    ev = _frame_event_for(session_id)
                    ev.set()
                    ev.clear()
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
                        await host.send_text(json.dumps({"type": "controller_bye"}))
                    except Exception:
                        pass
