"""
Production-grade simulation: host & guest each follow the FULL UI flow
(WatchTogether lobby → Detail page → Player) using just WebSocket
scripts.  This validates the entire end-to-end happy path one more
time, to give the user maximum confidence the fix actually works.
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
async def test_complete_production_flow_4_sockets():
    """
    Simulates EXACTLY what happens in production:
      1. Host opens WatchTogether → connects WS (lobby_host).
      2. Guest joins via code → connects WS (lobby_guest).
      3. Host picks movie, clicks Start → sends `play`.
      4. Both close lobby WS, navigate to Detail page.
      5. Both reconnect to WS in Detail (detail_host, detail_guest).
      6. Host's Detail picks stream → sends `stream` message.
      7. Guest's Detail receives stream URL.
      8. Both close detail WS, navigate to /play.
      9. Both reconnect in Player (player_host, player_guest).
     10. Both send `ready`.
     11. Server flips to countdown.
     12. Both fire mediaPlayer.play() at the same wallclock.
    """
    code = await _new_code()
    uri = f"{WS_URL}/api/watch-party/ws/{code}"

    # ---- PHASE 1: LOBBY ----
    lobby_host = await websockets.connect(uri)
    lobby_guest = await websockets.connect(uri)
    try:
        await _hello(lobby_host, "host", "HostUser")
        host_joined = await _recv_until(lobby_host, lambda m: m.get("type") == "joined", timeout=2)
        host_member_id = host_joined["member_id"]
        await _hello(lobby_guest, "guest", "GuestUser")
        guest_joined = await _recv_until(lobby_guest, lambda m: m.get("type") == "joined", timeout=2)
        guest_member_id = guest_joined["member_id"]
        # Drain pending states
        await asyncio.sleep(0.3)

        # Host picks Interstellar
        await lobby_host.send(json.dumps({
            "type": "pick",
            "payload": {
                "tmdb_id": "157336", "media_type": "movie",
                "title": "Interstellar", "year": "2014",
            },
        }))
        # Host clicks Start → `play` flips status to loading
        await lobby_host.send(json.dumps({"type": "play", "lead_ms": 2000}))
        # Both should receive the loading state
        await _recv_until(lobby_host, lambda m: m.get("status") == "loading", timeout=3)
        await _recv_until(lobby_guest, lambda m: m.get("status") == "loading", timeout=3)
    finally:
        await lobby_host.close()
        await lobby_guest.close()

    # ---- PHASE 2: DETAIL ----
    # Both members navigate to Detail page → reconnect WS with same member_id
    detail_host = await websockets.connect(uri)
    detail_guest = await websockets.connect(uri)
    try:
        await detail_host.send(json.dumps({
            "type": "hello", "role": "host", "member_id": host_member_id,
            "name": "HostUser", "avatar": "a1",
        }))
        await detail_guest.send(json.dumps({
            "type": "hello", "role": "guest", "member_id": guest_member_id,
            "name": "GuestUser", "avatar": "a1",
        }))
        await asyncio.sleep(0.3)

        # Host's Detail picks best stream → sends `stream` message
        chosen_url = "https://torrentio.example.com/interstellar-1080p-x264.mp4"
        await detail_host.send(json.dumps({
            "type": "stream",
            "payload": {
                "url": chosen_url,
                "title": "Interstellar",
                "type": "movie",
                "imdb_id": "tt0816692",
                "subtitle_url": "https://example.com/eng.srt",
                "poster": "https://example.com/poster.jpg",
                "backdrop": "https://example.com/backdrop.jpg",
                "synopsis": "A team of explorers travel through a wormhole.",
                "year": "2014",
                "rating": "8.7",
                "runtime": "169 min",
                "position_ms": 0,
            },
        }))

        # Guest's Detail receives state with the host's chosen URL
        guest_stream_state = await _recv_until(
            detail_guest,
            lambda m: m.get("type") == "state"
            and m.get("stream")
            and m["stream"].get("url") == chosen_url,
            timeout=3,
        )
        assert guest_stream_state["stream"]["title"] == "Interstellar"
        assert guest_stream_state["stream"]["subtitle_url"] == "https://example.com/eng.srt"
        assert guest_stream_state["stream"]["imdb_id"] == "tt0816692"
        assert guest_stream_state["stream"]["poster"] == "https://example.com/poster.jpg"
    finally:
        await detail_host.close()
        await detail_guest.close()

    # ---- PHASE 3: PLAYER ----
    # Both navigate to /play with HOST's URL → reconnect WS again
    player_host = await websockets.connect(uri)
    player_guest = await websockets.connect(uri)
    try:
        await player_host.send(json.dumps({
            "type": "hello", "role": "host", "member_id": host_member_id,
            "name": "HostUser", "avatar": "a1",
        }))
        await player_guest.send(json.dumps({
            "type": "hello", "role": "guest", "member_id": guest_member_id,
            "name": "GuestUser", "avatar": "a1",
        }))
        await asyncio.sleep(0.3)

        # Both players send `ready` once buffered
        await player_host.send(json.dumps({"type": "ready"}))
        await player_guest.send(json.dumps({"type": "ready"}))

        # Server flips to countdown
        countdown = await _recv_until(
            player_host,
            lambda m: m.get("status") == "countdown",
            timeout=3,
        )
        assert countdown["at_ms"] > 0
        # Critical: stream URL is STILL the same on countdown
        assert countdown["stream"]["url"] == chosen_url

        # Host can also send `playing_now` heartbeat once playback started
        await asyncio.sleep(0.2)
        await player_host.send(json.dumps({
            "type": "playing_now", "position_ms": 1500,
        }))
        playing = await _recv_until(
            player_guest,
            lambda m: m.get("status") == "playing",
            timeout=3,
        )
        assert playing["position_ms"] >= 1500
        assert playing["stream"]["url"] == chosen_url
    finally:
        await player_host.close()
        await player_guest.close()

