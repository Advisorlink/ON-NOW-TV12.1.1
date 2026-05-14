"""Iter 18 — Watch Together backend tests (REST + WebSocket).

Covers:
- POST /api/watch-party/create returns a 6-char code
- GET /api/watch-party/state/{code} returns full party state
- GET /api/watch-party/state/INVALID returns {error: 'not_found'}
- WebSocket /api/watch-party/ws/{code}:
    hello (host + guest), pick, play, pause, chat, disconnect broadcast
"""
import asyncio
import json
import os
import re

import pytest
import requests
import websockets

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "").rstrip("/")
assert BASE_URL, "REACT_APP_BACKEND_URL not set"
WS_BASE = re.sub(r"^http", "ws", BASE_URL)

CODE_RE = re.compile(r"^[A-HJ-NP-Z2-9]{6}$")


# -------- REST --------------------------------------------------------------

class TestWatchPartyRest:
    def test_create_returns_6char_code(self):
        r = requests.post(f"{BASE_URL}/api/watch-party/create", timeout=10)
        assert r.status_code == 200
        j = r.json()
        assert "code" in j
        assert isinstance(j["code"], str)
        assert len(j["code"]) == 6
        assert CODE_RE.match(j["code"]), f"code shape unexpected: {j['code']}"

    def test_state_returns_party_for_existing_code(self):
        c = requests.post(f"{BASE_URL}/api/watch-party/create", timeout=10).json()["code"]
        r = requests.get(f"{BASE_URL}/api/watch-party/state/{c}", timeout=10)
        assert r.status_code == 200
        j = r.json()
        assert j.get("type") == "state"
        assert j.get("code") == c
        assert j.get("members") == []
        assert j.get("status") == "lobby"
        assert j.get("movie") is None
        assert j.get("position_ms") == 0

    def test_state_invalid_returns_not_found(self):
        r = requests.get(f"{BASE_URL}/api/watch-party/state/ZZZZZZ", timeout=10)
        assert r.status_code == 200
        assert r.json() == {"error": "not_found"}


# -------- WebSocket helpers -------------------------------------------------

async def _recv_until(ws, predicate, timeout=5.0):
    """Receive messages until predicate(msg) returns True, return that msg."""
    end = asyncio.get_event_loop().time() + timeout
    while True:
        remaining = end - asyncio.get_event_loop().time()
        if remaining <= 0:
            raise asyncio.TimeoutError("recv_until timed out")
        raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
        msg = json.loads(raw)
        if predicate(msg):
            return msg


async def _drain(ws, timeout=0.4):
    """Drain any queued messages."""
    out = []
    try:
        while True:
            raw = await asyncio.wait_for(ws.recv(), timeout=timeout)
            out.append(json.loads(raw))
    except asyncio.TimeoutError:
        pass
    return out


# -------- WebSocket ---------------------------------------------------------

class TestWatchPartyWS:
    def _new_code(self):
        return requests.post(f"{BASE_URL}/api/watch-party/create", timeout=10).json()["code"]

    @pytest.mark.asyncio
    async def test_hello_host_and_guest_state_broadcast(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"

        async with websockets.connect(url) as host:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "Hostie", "avatar": "a1"}))
            joined = await _recv_until(host, lambda m: m.get("type") == "joined")
            assert "member_id" in joined
            host_state = await _recv_until(host, lambda m: m.get("type") == "state")
            assert len(host_state["members"]) == 1
            assert host_state["members"][0]["is_host"] is True

            async with websockets.connect(url) as guest:
                await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "Friendo", "avatar": "a2"}))
                await _recv_until(guest, lambda m: m.get("type") == "joined")
                guest_state = await _recv_until(guest, lambda m: m.get("type") == "state")
                assert len(guest_state["members"]) == 2
                roles = {m["name"]: m["is_host"] for m in guest_state["members"]}
                assert roles == {"Hostie": True, "Friendo": False}

                # Host should also have been broadcast the 2-member state
                host_state2 = await _recv_until(host, lambda m: m.get("type") == "state" and len(m["members"]) == 2)
                assert any(mm["name"] == "Friendo" for mm in host_state2["members"])

    @pytest.mark.asyncio
    async def test_host_pick_broadcasts_movie(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "G"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await _drain(host); await _drain(guest)

            movie = {"tmdb_id": "808", "media_type": "movie", "title": "Shrek", "poster": "x", "year": "2001"}
            await host.send(json.dumps({"type": "pick", "payload": movie}))

            gst = await _recv_until(guest, lambda m: m.get("type") == "state" and m.get("movie"))
            assert gst["movie"]["title"] == "Shrek"
            assert gst["movie"]["tmdb_id"] == "808"
            assert gst["status"] == "lobby"

    @pytest.mark.asyncio
    async def test_host_play_sets_countdown_future_at_ms(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "G"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await host.send(json.dumps({"type": "pick", "payload": {"tmdb_id": "1", "media_type": "movie", "title": "T"}}))
            await _drain(host); await _drain(guest)

            await host.send(json.dumps({"type": "play", "lead_ms": 3000, "position_ms": 0}))
            st = await _recv_until(guest, lambda m: m.get("type") == "state" and m.get("status") == "countdown")
            assert st["at_ms"] > st["server_ms"], "at_ms should be in the future relative to server clock"
            # lead_ms ~ 3000, allow generous tolerance
            assert 500 < (st["at_ms"] - st["server_ms"]) < 10000

    @pytest.mark.asyncio
    async def test_host_pause_updates_position_and_status(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "G"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await _drain(host); await _drain(guest)

            await host.send(json.dumps({"type": "pause", "position_ms": 12345}))
            st = await _recv_until(guest, lambda m: m.get("type") == "state" and m.get("status") == "paused")
            assert st["position_ms"] == 12345
            assert st["at_ms"] == 0

    @pytest.mark.asyncio
    async def test_chat_message_broadcast_with_sender_info(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "Hosty", "avatar": "a3"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "Guesty", "avatar": "a4"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await _drain(host); await _drain(guest)

            await host.send(json.dumps({"type": "chat", "text": "hello world"}))
            msg = await _recv_until(guest, lambda m: m.get("type") == "chat")
            assert msg["text"] == "hello world"
            assert msg["member"]["name"] == "Hosty"
            assert msg["member"]["avatar"] == "a3"
            assert "ts" in msg

    @pytest.mark.asyncio
    async def test_disconnect_broadcasts_fresh_state(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await _drain(host)

            guest = await websockets.connect(url)
            try:
                await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "Bye"}))
                await _recv_until(guest, lambda m: m.get("type") == "joined")
                # Host sees 2-member state
                await _recv_until(host, lambda m: m.get("type") == "state" and len(m["members"]) == 2)
            finally:
                await guest.close()

            # After guest closes, host should receive a state with only itself
            st = await _recv_until(host, lambda m: m.get("type") == "state" and len(m["members"]) == 1, timeout=6.0)
            assert st["members"][0]["name"] == "H"
