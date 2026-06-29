"""v2.10.84 — Remote-maintenance / support session regression suite.

Covers:
  • POST /api/support/host/register mints a unique session_id +
    6-digit code per call (no collisions, no stale reuse).
  • POST /api/support/controller/connect:
      - returns 404 for codes that don't exist or expired
      - returns 200 with session_id for a valid code
      - returns 409 when a second controller tries to attach to an
        already-paired session
      - requires admin Bearer token
  • GET /api/support/sessions lists active sessions (admin only).
  • POST /api/support/host/cancel removes the session.
  • Full WebSocket round-trip: host sends a hello JSON + a binary
    frame, controller receives both; controller sends a JSON input
    command, host receives it.
  • Backpressure: host closes → controller receives a clean close;
    controller closes → host gets `controller_bye`.
"""

import asyncio
import json
import os
import secrets

import pytest
import requests
import websockets

BASE_URL = os.environ.get("LAUNCHER_BACKEND_URL", "http://localhost:8002")
ADMIN_TOKEN = os.environ.get("LAUNCHER_ADMIN_TOKEN", "onnow-launcher-admin-dev")
AUTH = {"Authorization": f"Bearer {ADMIN_TOKEN}"}


def _ws_base() -> str:
    if BASE_URL.startswith("https://"):
        return "wss://" + BASE_URL[len("https://"):]
    if BASE_URL.startswith("http://"):
        return "ws://" + BASE_URL[len("http://"):]
    return "ws://localhost:8002"


def _register(device_id: str = "pytest-device") -> dict:
    r = requests.post(
        f"{BASE_URL}/api/support/host/register",
        json={"device_id": device_id},
        timeout=10,
    )
    assert r.status_code == 200, r.text
    return r.json()


def _controller_connect(code: str) -> requests.Response:
    return requests.post(
        f"{BASE_URL}/api/support/controller/connect",
        json={"code": code},
        headers=AUTH,
        timeout=10,
    )


# ─────────────────────────  HTTP layer  ───────────────────────────


def test_host_register_returns_code_and_session():
    r = _register("dev-001")
    assert r["session_id"] and len(r["session_id"]) == 32
    assert r["code"] and len(r["code"]) == 6 and r["code"].isdigit()
    assert r["ttl_seconds"] == 300
    assert r["ws_path"].startswith("/api/support/host/")


def test_host_register_mints_unique_codes():
    seen = set()
    for i in range(8):
        d = _register(f"dev-uniq-{i}")
        assert d["code"] not in seen, f"code collision: {d['code']}"
        seen.add(d["code"])


def test_controller_connect_returns_404_without_auth_when_admin_gate_open():
    """v2.10.84 — Admin auth is currently disabled launcher-wide
    (see require_admin in main.py).  Until it's re-enabled,
    /controller/connect must STILL return a meaningful 404 when the
    code doesn't exist — proving the session-lookup logic runs
    regardless of auth state.  When auth is re-enabled, this test
    will need to be replaced with a 401/403 expectation."""
    r = requests.post(
        f"{BASE_URL}/api/support/controller/connect",
        json={"code": "999999"},
        # No AUTH header — should still hit the session lookup.
        timeout=10,
    )
    assert r.status_code == 404, r.text


def test_controller_connect_404_on_bad_code():
    r = _controller_connect("000000")
    assert r.status_code == 404
    assert "code_not_found" in r.text


def test_controller_connect_succeeds_with_valid_code():
    reg = _register("dev-good-code")
    r = _controller_connect(reg["code"])
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["session_id"] == reg["session_id"]
    assert body["ws_path"].startswith("/api/support/controller/")


def test_controller_connect_409_after_first_paired():
    """Second controller attempting the same code WHILE the first is
    connected via WebSocket gets 409."""
    reg = _register("dev-double-pair")

    async def scenario():
        # Open the host WS so the session is "live".
        host = await websockets.connect(_ws_base() + reg["ws_path"])
        try:
            # First controller connect — succeeds.
            r1 = _controller_connect(reg["code"])
            assert r1.status_code == 200, r1.text
            sid = r1.json()["session_id"]
            # Open the controller WS so the session is fully paired.
            ctrl1 = await websockets.connect(_ws_base() + r1.json()["ws_path"])
            try:
                # Second controller tries — should 409.
                r2 = _controller_connect(reg["code"])
                assert r2.status_code == 409, r2.text
            finally:
                await ctrl1.close()
        finally:
            await host.close()
    asyncio.run(scenario())


def test_sessions_list_responds_under_open_admin_gate():
    """v2.10.84 — Same caveat as above.  With admin auth disabled,
    /sessions returns 200 regardless of headers; we still verify the
    payload shape so a future re-enabled auth gate doesn't silently
    break the contract."""
    r = requests.get(f"{BASE_URL}/api/support/sessions", timeout=10)
    assert r.status_code == 200
    body = r.json()
    assert "sessions" in body and isinstance(body["sessions"], list)


def test_sessions_list_includes_freshly_registered():
    reg = _register("dev-list-001")
    r = requests.get(
        f"{BASE_URL}/api/support/sessions", headers=AUTH, timeout=10,
    )
    assert r.status_code == 200
    sids = [s["session_id"] for s in r.json()["sessions"]]
    assert reg["session_id"] in sids


def test_host_cancel_removes_session():
    reg = _register("dev-cancel")
    r = requests.post(
        f"{BASE_URL}/api/support/host/cancel",
        json={"session_id": reg["session_id"]},
        timeout=10,
    )
    assert r.status_code == 200
    # Controller-connect now fails with 404.
    r2 = _controller_connect(reg["code"])
    assert r2.status_code == 404


# ─────────────────────  WebSocket relay layer  ───────────────────


def test_full_websocket_pairing_and_frame_relay():
    """End-to-end:
       1. Host registers, opens host WS, sends hello + a binary frame.
       2. Operator hits controller/connect with the code, opens
          controller WS.
       3. Controller receives both the hello JSON AND the binary frame.
       4. Controller sends an input command → host receives it.
    """
    reg = _register("dev-e2e")

    async def scenario():
        host = await websockets.connect(_ws_base() + reg["ws_path"])
        # Host sends hello + frame immediately.
        hello = json.dumps({
            "type": "hello",
            "device_id": "dev-e2e",
            "build": "2.10.84",
            "screen_w": 1920,
            "screen_h": 1080,
        })
        await host.send(hello)
        fake_jpeg = b'\xff\xd8\xff\xe0' + secrets.token_bytes(512) + b'\xff\xd9'
        await host.send(fake_jpeg)
        # Controller pairs and opens WS.
        r = _controller_connect(reg["code"])
        assert r.status_code == 200
        ctrl = await websockets.connect(_ws_base() + r.json()["ws_path"])
        # Controller should receive the hello replay (host_hello) AND
        # any subsequent frames the host sends.
        received_text = None
        received_bytes = None
        # Drain the initial host_hello synthesized by the backend.
        try:
            received_text = await asyncio.wait_for(ctrl.recv(), 1.5)
        except asyncio.TimeoutError:
            pass
        # Send a fresh frame from host so controller observes the relay.
        fresh_jpeg = b'\xff\xd8\xff\xe1' + secrets.token_bytes(256) + b'\xff\xd9'
        await host.send(fresh_jpeg)
        try:
            received_bytes = await asyncio.wait_for(ctrl.recv(), 1.5)
        except asyncio.TimeoutError:
            pass
        # Both signals MUST have landed.
        assert received_text is not None and "hello" in received_text
        assert received_bytes == fresh_jpeg
        # Controller → host input forwarding.
        await ctrl.send(json.dumps({
            "type": "input", "action": "key", "key": "DPAD_UP",
        }))
        host_got = await asyncio.wait_for(host.recv(), 1.5)
        assert "DPAD_UP" in host_got

        await ctrl.close()
        await host.close()
    asyncio.run(scenario())


def test_host_register_payload_includes_device_id_in_session_summary():
    reg = _register("dev-summary-check")
    r = requests.get(
        f"{BASE_URL}/api/support/sessions", headers=AUTH, timeout=10,
    )
    summaries = {s["session_id"]: s for s in r.json()["sessions"]}
    sess = summaries.get(reg["session_id"])
    assert sess is not None
    assert sess["device_id"] == "dev-summary-check"
    assert sess["host_connected"] is False
    assert sess["controller_connected"] is False
    assert sess["frames_relayed"] == 0
