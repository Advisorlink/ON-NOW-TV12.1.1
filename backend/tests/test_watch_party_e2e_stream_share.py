"""
End-to-end Watch-Together test simulating the new full flow:
  1. Host clicks Start → server flips to `loading`.
  2. Both members (lobby WS) navigate based on `loading` state.
  3. Host's Detail page sends `stream` message with URL.
  4. Guest receives `stream` in state → uses HOST's URL.
  5. Both send `ready` → server flips to `countdown`.
  6. Both `play` synchronously.

This is the comprehensive test for the v2.6.64 watch-party fix.
"""
import asyncio
import json
import os

import httpx
import pytest
import websockets

BACKEND_URL = os.environ.get("REACT_APP_BACKEND_URL_FOR_TEST") or "http://localhost:8001"
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
async def test_full_watch_party_flow():
    """Host & guest watch the SAME stream URL via new architecture."""
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"

    async with websockets.connect(uri) as host, websockets.connect(uri) as guest:
        await _hello(host, "host", "Host")
        await _hello(guest, "guest", "Guest")
        await asyncio.sleep(0.3)

        # Host picks movie
        await host.send(json.dumps({
            "type": "pick",
            "payload": {
                "tmdb_id": "157336",
                "media_type": "movie",
                "title": "Interstellar",
                "poster": "https://example.com/p.jpg",
                "year": "2014",
            },
        }))

        # Host (Detail) broadcasts stream URL
        host_stream_url = "https://example.com/interstellar-1080p.mp4"
        await host.send(json.dumps({
            "type": "stream",
            "payload": {
                "url": host_stream_url,
                "title": "Interstellar",
                "type": "movie",
                "imdb_id": "tt0816692",
                "subtitle_url": "https://example.com/sub.srt",
                "position_ms": 0,
            },
        }))

        # GUEST: should receive state with stream.url set to host's URL
        guest_state = await _recv_until(
            guest,
            lambda m: m.get("type") == "state"
            and m.get("stream")
            and m["stream"].get("url") == host_stream_url,
            timeout=3,
        )
        assert guest_state["status"] == "loading"
        assert guest_state["stream"]["title"] == "Interstellar"
        assert guest_state["stream"]["type"] == "movie"
        assert guest_state["stream"]["imdb_id"] == "tt0816692"

        # Both members send `ready` (simulating their players have buffered).
        await host.send(json.dumps({"type": "ready"}))
        await guest.send(json.dumps({"type": "ready"}))

        # Server should flip to countdown for both
        countdown = await _recv_until(
            host,
            lambda m: m.get("type") == "state" and m.get("status") == "countdown",
            timeout=3,
        )
        assert countdown["at_ms"] > 0
        assert countdown["status"] == "countdown"
        assert countdown["stream"]["url"] == host_stream_url

        guest_cd = await _recv_until(
            guest,
            lambda m: m.get("type") == "state" and m.get("status") == "countdown",
            timeout=3,
        )
        assert guest_cd["status"] == "countdown"
        # Critical: guest's URL == host's URL
        assert guest_cd["stream"]["url"] == host_stream_url


@pytest.mark.asyncio
async def test_loading_advances_with_only_host_ready_after_timeout():
    """Even if guest never sends `ready`, host can still play after 25s."""
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"

    async with websockets.connect(uri) as host, websockets.connect(uri) as guest:
        await _hello(host, "host", "Host")
        await _hello(guest, "guest", "Guest")
        await asyncio.sleep(0.2)

        # Host clicks Start (triggers loading + watchdog)
        await host.send(json.dumps({"type": "play", "lead_ms": 2000}))

        # Only host sends ready (simulating guest stuck buffering).
        await host.send(json.dumps({"type": "ready"}))

        # Wait up to 30s for watchdog to fire countdown anyway
        countdown = await _recv_until(
            host,
            lambda m: m.get("type") == "state" and m.get("status") == "countdown",
            timeout=30,
        )
        assert countdown["status"] == "countdown"
        # Both members should be marked ready (forced by watchdog)
        members = countdown.get("members", [])
        assert all(m.get("ready") for m in members), \
            f"Expected all members ready after watchdog, got: {members}"


@pytest.mark.asyncio
async def test_new_pick_resets_stream():
    """Host re-picking a movie resets the stream/ready/error state."""
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"

    async with websockets.connect(uri) as host:
        await _hello(host, "host", "Host")
        await asyncio.sleep(0.2)

        # First pick + stream
        await host.send(json.dumps({
            "type": "pick",
            "payload": {"tmdb_id": "1", "media_type": "movie", "title": "Movie A"},
        }))
        await host.send(json.dumps({
            "type": "stream",
            "payload": {"url": "https://example.com/a.mp4", "title": "Movie A", "type": "movie"},
        }))
        await _recv_until(host, lambda m: m.get("stream") and m["stream"]["url"] == "https://example.com/a.mp4", timeout=3)

        # Second pick — stream should reset
        await host.send(json.dumps({
            "type": "pick",
            "payload": {"tmdb_id": "2", "media_type": "movie", "title": "Movie B"},
        }))
        state = await _recv_until(
            host,
            lambda m: m.get("type") == "state"
            and m.get("movie", {}).get("title") == "Movie B",
            timeout=3,
        )
        assert state["stream"] is None
        assert state["status"] == "lobby"
