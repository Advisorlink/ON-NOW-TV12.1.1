"""
Watch-party CLOCK SYNC: verify the new ping/pong handshake works
correctly so clients can measure their local-to-server offset and
correct for clock skew between host and guest.
"""
import asyncio
import json
import os

import httpx
import pytest
import websockets

BACKEND_URL = os.environ.get(
    "REACT_APP_BACKEND_URL_FOR_TEST"
) or os.environ.get("REACT_APP_BACKEND_URL") or "http://localhost:8001"
WS_URL = BACKEND_URL.replace("http://", "ws://").replace("https://", "wss://")


async def _new_code() -> str:
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.post(f"{BACKEND_URL}/api/watch-party/create")
        return r.json()["code"]


@pytest.mark.asyncio
async def test_ping_pong_returns_server_time_and_echoes_t1():
    """A `ping {t1: X}` MUST get back a `pong {t1: X, server_ms: <now>}`."""
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"
    async with websockets.connect(uri) as ws:
        # Don't even need to say hello first.  Ping is unauthenticated
        # so clients can measure clock offset BEFORE joining a party.
        my_t1 = 123_456_789
        await ws.send(json.dumps({"type": "ping", "t1": my_t1}))
        raw = await asyncio.wait_for(ws.recv(), timeout=2)
        msg = json.loads(raw)
        assert msg["type"] == "pong"
        assert msg["t1"] == my_t1, "server must echo t1 unchanged"
        assert isinstance(msg["server_ms"], int)
        # server_ms should be a realistic wallclock (year 2025-2030)
        assert msg["server_ms"] > 1_700_000_000_000
        assert msg["server_ms"] < 2_000_000_000_000


@pytest.mark.asyncio
async def test_ping_pong_during_party_does_not_disturb_state():
    """Pings should be silent — they don't trigger `state` broadcasts."""
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"
    async with websockets.connect(uri) as host, websockets.connect(uri) as guest:
        await host.send(json.dumps({
            "type": "hello", "role": "host", "name": "H", "avatar": "a1",
        }))
        await guest.send(json.dumps({
            "type": "hello", "role": "guest", "name": "G", "avatar": "a1",
        }))
        # drain initial state msgs from hello handshake
        async def _drain(s):
            try:
                while True:
                    await asyncio.wait_for(s.recv(), timeout=0.15)
            except asyncio.TimeoutError:
                pass
        await _drain(host)
        await _drain(guest)
        # Fire a burst of pings; ensure no state broadcasts result
        for i in range(5):
            await host.send(json.dumps({"type": "ping", "t1": 1000 + i}))
            await guest.send(json.dumps({"type": "ping", "t1": 2000 + i}))
        await asyncio.sleep(0.4)
        # Drain everything on guest and confirm we ONLY see pongs
        guest_msgs = []
        try:
            while True:
                raw = await asyncio.wait_for(guest.recv(), timeout=0.15)
                guest_msgs.append(json.loads(raw))
        except asyncio.TimeoutError:
            pass
        types = [m.get("type") for m in guest_msgs]
        assert types.count("pong") == 5
        # Critically, no `state` broadcasts as a side-effect of pings.
        assert "state" not in types
