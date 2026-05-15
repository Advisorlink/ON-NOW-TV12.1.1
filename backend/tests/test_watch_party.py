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
    async def test_host_play_transitions_to_loading(self):
        """Iter25 contract: play → status='loading' (NOT countdown yet).
        Countdown only fires after all members send `ready`.
        """
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
            st = await _recv_until(guest, lambda m: m.get("type") == "state" and m.get("status") == "loading")
            assert st["status"] == "loading"
            assert st["at_ms"] == 0, "at_ms must be 0 during loading (set only after all-ready)"

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

# -------- Iter 25 — READY handshake (status: loading → countdown) -----------

class TestWatchPartyReadyHandshake:
    """Verifies the 2-stage play handshake:
       host play → status='loading' → each member 'ready' → status='countdown' with at_ms in future.
    """

    def _new_code(self):
        return requests.post(f"{BASE_URL}/api/watch-party/create", timeout=10).json()["code"]

    @pytest.mark.asyncio
    async def test_play_emits_loading_then_countdown_after_all_ready(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            # hello both
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            host_joined = await _recv_until(host, lambda m: m.get("type") == "joined")
            host_id = host_joined["member_id"]
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "G"}))
            guest_joined = await _recv_until(guest, lambda m: m.get("type") == "joined")
            guest_id = guest_joined["member_id"]

            # pick
            await host.send(json.dumps({"type": "pick", "payload": {"tmdb_id": "1", "media_type": "movie", "title": "T"}}))
            await _drain(host); await _drain(guest)

            # play — must transition to 'loading' (NOT countdown yet)
            await host.send(json.dumps({"type": "play", "lead_ms": 3000, "position_ms": 0}))
            loading_state = await _recv_until(guest, lambda m: m.get("type") == "state" and m.get("status") == "loading")
            assert loading_state["status"] == "loading"
            assert loading_state["at_ms"] == 0, "at_ms must be 0 during loading"
            # All members should have ready=False
            assert all(mm["ready"] is False for mm in loading_state["members"])
            await _drain(host)

            # Only host ready → still loading (NOT countdown)
            await host.send(json.dumps({"type": "ready", "member_id": host_id}))
            partial = await _recv_until(host, lambda m: m.get("type") == "state")
            assert partial["status"] == "loading", "must remain in loading until ALL members ready"
            host_member = next(mm for mm in partial["members"] if mm["name"] == "H")
            assert host_member["ready"] is True
            guest_member = next(mm for mm in partial["members"] if mm["name"] == "G")
            assert guest_member["ready"] is False
            await _drain(guest)

            # Guest ready → flip to countdown with at_ms in the future
            await guest.send(json.dumps({"type": "ready", "member_id": guest_id}))
            cd = await _recv_until(guest, lambda m: m.get("type") == "state" and m.get("status") == "countdown")
            assert cd["at_ms"] > cd["server_ms"], "at_ms must be in the future"
            assert 500 < (cd["at_ms"] - cd["server_ms"]) < 10000
            assert all(mm["ready"] is True for mm in cd["members"])

    @pytest.mark.asyncio
    async def test_ready_outside_loading_does_not_flip_status(self):
        """A stray ready in 'lobby' must NOT cause a countdown."""
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            j = await _recv_until(host, lambda m: m.get("type") == "joined")
            await _drain(host)
            await host.send(json.dumps({"type": "ready", "member_id": j["member_id"]}))
            st = await _recv_until(host, lambda m: m.get("type") == "state")
            assert st["status"] == "lobby"
            assert st["at_ms"] == 0


# -------- Iter 25 — Guest join via REST -------------------------------------

class TestWatchPartyGuestJoin:
    def test_join_existing_code_via_state_endpoint(self):
        """Guest flow verifies code via GET /state/{code} (the join endpoint per design)."""
        c = requests.post(f"{BASE_URL}/api/watch-party/create", timeout=10).json()["code"]
        r = requests.get(f"{BASE_URL}/api/watch-party/state/{c}", timeout=10)
        assert r.status_code == 200
        j = r.json()
        assert j.get("code") == c
        assert j.get("status") == "lobby"

    def test_join_invalid_code_returns_not_found(self):
        r = requests.get(f"{BASE_URL}/api/watch-party/state/ABCDEF", timeout=10)
        assert r.status_code == 200
        assert r.json() == {"error": "not_found"}


# -------- Iter 26 — Emoji reaction broadcast / whitelist / rate-limit -------

class TestWatchPartyReactions:
    """Verifies the new {type:'reaction'} handler:
       - host reaction broadcasts to host + guest with {emoji, member, ts}
       - server-side whitelist drops disallowed emojis silently (no broadcast)
       - same-member rate-limit: 2 reactions <800ms apart → only first broadcast
       - after the 800ms cooldown, next reaction IS broadcast
    """

    def _new_code(self):
        return requests.post(f"{BASE_URL}/api/watch-party/create", timeout=10).json()["code"]

    @pytest.mark.asyncio
    async def test_reaction_broadcasts_to_host_and_guest(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "Hostie", "avatar": "a1"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "Friendo", "avatar": "a2"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await _drain(host); await _drain(guest)

            await host.send(json.dumps({"type": "reaction", "emoji": "\u2764\ufe0f"}))

            host_r = await _recv_until(host, lambda m: m.get("type") == "reaction", timeout=3.0)
            guest_r = await _recv_until(guest, lambda m: m.get("type") == "reaction", timeout=3.0)

            for r in (host_r, guest_r):
                assert r["emoji"] == "\u2764\ufe0f"
                assert r["member"]["name"] == "Hostie"
                assert r["member"]["avatar"] == "a1"
                assert isinstance(r.get("ts"), int)

    @pytest.mark.asyncio
    async def test_reaction_whitelist_drops_disallowed_emoji(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "G"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await _drain(host); await _drain(guest)

            # Disallowed: 🎉 (party popper) — must NOT broadcast
            await host.send(json.dumps({"type": "reaction", "emoji": "\U0001F389"}))

            # Drain briefly — expect zero reaction messages
            drained = await _drain(guest, timeout=0.8)
            reactions = [m for m in drained if m.get("type") == "reaction"]
            assert reactions == [], f"disallowed emoji should be dropped, got: {reactions}"

            # Now send an allowed one — confirms the socket is still alive
            await host.send(json.dumps({"type": "reaction", "emoji": "\U0001F606"}))
            ok = await _recv_until(guest, lambda m: m.get("type") == "reaction", timeout=3.0)
            assert ok["emoji"] == "\U0001F606"

    @pytest.mark.asyncio
    async def test_reaction_rate_limit_800ms_per_member(self):
        code = self._new_code()
        url = f"{WS_BASE}/api/watch-party/ws/{code}"
        async with websockets.connect(url) as host, websockets.connect(url) as guest:
            await host.send(json.dumps({"type": "hello", "role": "host", "name": "H"}))
            await _recv_until(host, lambda m: m.get("type") == "joined")
            await guest.send(json.dumps({"type": "hello", "role": "guest", "name": "G"}))
            await _recv_until(guest, lambda m: m.get("type") == "joined")
            await _drain(host); await _drain(guest)

            # Two reactions back-to-back from same member within 800ms
            await host.send(json.dumps({"type": "reaction", "emoji": "\u2764\ufe0f"}))
            await host.send(json.dumps({"type": "reaction", "emoji": "\U0001F631"}))

            # Collect all reactions for ~0.7s (still inside cooldown window)
            collected = []
            try:
                while True:
                    raw = await asyncio.wait_for(guest.recv(), timeout=0.7)
                    m = json.loads(raw)
                    if m.get("type") == "reaction":
                        collected.append(m)
            except asyncio.TimeoutError:
                pass
            assert len(collected) == 1, f"expected exactly 1 broadcast within cooldown, got {len(collected)}: {collected}"
            assert collected[0]["emoji"] == "\u2764\ufe0f", "first reaction wins"

            # Wait past cooldown, then a third reaction SHOULD broadcast
            await asyncio.sleep(0.9)
            await host.send(json.dumps({"type": "reaction", "emoji": "\U0001F62D"}))
            after = await _recv_until(guest, lambda m: m.get("type") == "reaction", timeout=3.0)
            assert after["emoji"] == "\U0001F62D"

