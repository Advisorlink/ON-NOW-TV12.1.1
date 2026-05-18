"""
Integration test for the new Watch-Together stream-URL sharing flow.

Validates:
  1. Host can broadcast `stream` message → server stashes + broadcasts to all.
  2. Loading timeout watchdog force-advances to `countdown` after 25s.
  3. `stream_error` broadcasts gracefully.
"""
import asyncio
import json
import os
import sys

import httpx
import pytest
import websockets

BACKEND_URL = os.environ.get("REACT_APP_BACKEND_URL_FOR_TEST") or (
    "http://localhost:8001"
)
WS_URL = BACKEND_URL.replace("http://", "ws://").replace("https://", "wss://")


async def _new_code() -> str:
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.post(f"{BACKEND_URL}/api/watch-party/create")
        return r.json()["code"]


async def _hello(ws, role: str, name: str = "Test"):
    await ws.send(json.dumps({
        "type": "hello", "role": role, "name": name, "avatar": "a1",
    }))


async def _recv_until(ws, predicate, timeout: float = 5.0):
    """Read messages until the predicate returns True or we time out."""
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        remaining = deadline - asyncio.get_event_loop().time()
        if remaining <= 0:
            raise TimeoutError("predicate not satisfied")
        raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        msg = json.loads(raw)
        if predicate(msg):
            return msg


@pytest.mark.asyncio
async def test_host_stream_broadcast_reaches_guest():
    """Host sends `stream` → guest receives `state.stream.url`."""
    code = await _new_code()
    host_uri = f"{WS_URL}/api/watch-party/ws/{code}"
    guest_uri = host_uri
    async with websockets.connect(host_uri) as host, websockets.connect(guest_uri) as guest:
        await _hello(host, "host", "Host")
        await _hello(guest, "guest", "Guest")
        # Drain initial state broadcasts
        await asyncio.sleep(0.3)

        # Host picks movie
        await host.send(json.dumps({
            "type": "pick",
            "payload": {"tmdb_id": "1", "media_type": "movie", "title": "Movie"},
        }))
        # Host broadcasts stream URL
        await host.send(json.dumps({
            "type": "stream",
            "payload": {
                "url": "https://example.com/x.mp4",
                "title": "Movie",
                "type": "movie",
                "imdb_id": "tt0000001",
                "subtitle_url": "https://example.com/sub.srt",
                "position_ms": 0,
            },
        }))

        # Guest should receive a state broadcast with stream set
        state = await _recv_until(
            guest,
            lambda m: m.get("type") == "state"
            and m.get("stream")
            and m["stream"].get("url") == "https://example.com/x.mp4",
            timeout=3,
        )
        assert state["stream"]["title"] == "Movie"
        assert state["stream"]["imdb_id"] == "tt0000001"
        assert state["stream"]["subtitle_url"] == "https://example.com/sub.srt"
        assert state["status"] == "loading"  # auto-flipped from lobby


@pytest.mark.asyncio
@pytest.mark.slow
async def test_loading_watchdog_force_advances():
    """Server flips loading→countdown after the 25s timeout (slow test).

    The watchdog runs inside the LIVE backend process, so we can't
    monkey-patch the constant from this test process.  We accept the
    real wait time so we know the production behaviour is correct.
    """
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"
    async with websockets.connect(uri) as host, websockets.connect(uri) as guest:
        await _hello(host, "host")
        await _hello(guest, "guest")
        await asyncio.sleep(0.2)
        # Host triggers loading (via play)
        await host.send(json.dumps({"type": "play", "lead_ms": 2000}))
        # Wait up to 30 s for the watchdog to fire.
        state = await _recv_until(
            guest,
            lambda m: m.get("type") == "state"
            and m.get("status") == "countdown",
            timeout=30,
        )
        assert state["at_ms"] > 0
        assert state["status"] == "countdown"


@pytest.mark.asyncio
async def test_stream_error_broadcasts():
    """Host stream_error → state.stream_error is set, status back to lobby."""
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"
    async with websockets.connect(uri) as host, websockets.connect(uri) as guest:
        await _hello(host, "host")
        await _hello(guest, "guest")
        await asyncio.sleep(0.2)
        await host.send(json.dumps({
            "type": "stream_error", "reason": "no_streams",
        }))
        state = await _recv_until(
            guest,
            lambda m: m.get("type") == "state"
            and m.get("stream_error") == "no_streams",
            timeout=3,
        )
        assert state["stream_error"] == "no_streams"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
