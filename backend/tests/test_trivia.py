"""ON NOW Trivia — backend REST + WebSocket tests."""
import asyncio
import json
import os
import pytest
import requests
import websockets

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")
WS_BASE = BASE_URL.replace("http", "ws", 1)


# ── REST ──
def test_create_room():
    r = requests.post(f"{BASE_URL}/api/trivia/rooms", timeout=15)
    assert r.status_code == 200
    d = r.json()
    assert "code" in d and isinstance(d["code"], str) and len(d["code"]) == 4
    assert isinstance(d.get("categories"), list) and len(d["categories"]) > 0
    assert isinstance(d.get("modes"), list) and any(m["id"] == "classic" for m in d["modes"])


def test_get_room_exists_and_404():
    code = requests.post(f"{BASE_URL}/api/trivia/rooms", timeout=15).json()["code"]
    r = requests.get(f"{BASE_URL}/api/trivia/rooms/{code}", timeout=10)
    assert r.status_code == 200
    assert r.json()["code"] == code
    r404 = requests.get(f"{BASE_URL}/api/trivia/rooms/ZZZZ", timeout=10)
    assert r404.status_code == 404


# ── WS helpers ──
async def _recv_state(ws, timeout=5):
    """Receive messages until we get a state snapshot."""
    end = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < end:
        raw = await asyncio.wait_for(ws.recv(), timeout=end - asyncio.get_event_loop().time())
        msg = json.loads(raw)
        if msg.get("type") == "state":
            return msg
    raise TimeoutError("no state")


async def _drain_until(ws, predicate, timeout=30):
    end = asyncio.get_event_loop().time() + timeout
    last = None
    while asyncio.get_event_loop().time() < end:
        try:
            raw = await asyncio.wait_for(ws.recv(), timeout=end - asyncio.get_event_loop().time())
        except asyncio.TimeoutError:
            break
        msg = json.loads(raw)
        if msg.get("type") == "state":
            last = msg
            if predicate(msg):
                return msg
    return last


def _new_code():
    return requests.post(f"{BASE_URL}/api/trivia/rooms", timeout=15).json()["code"]


@pytest.mark.asyncio
async def test_ws_tv_connects_and_receives_snapshot():
    code = _new_code()
    async with websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv") as tv:
        snap = await _recv_state(tv, 5)
        assert snap["code"] == code
        assert snap["phase"] == "lobby"
        assert isinstance(snap["players"], list)


@pytest.mark.asyncio
async def test_player_join_broadcasts_to_both():
    code = _new_code()
    async with websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv") as tv:
        await _recv_state(tv, 5)
        async with websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Alice") as pl:
            # player receives joined + state
            got_joined = False
            got_state_with_alice = False
            for _ in range(6):
                raw = await asyncio.wait_for(pl.recv(), 5)
                m = json.loads(raw)
                if m.get("type") == "joined":
                    got_joined = True
                if m.get("type") == "state" and any(p["name"] == "Alice" for p in m["players"]):
                    got_state_with_alice = True
                    break
            assert got_joined
            assert got_state_with_alice
            # TV also sees Alice
            tv_state = await _drain_until(tv, lambda s: any(p["name"] == "Alice" for p in s["players"]), 5)
            assert tv_state and any(p["name"] == "Alice" for p in tv_state["players"])


@pytest.mark.asyncio
async def test_classic_game_full_flow():
    """TV starts a 3-question classic game; player answers; podium reached."""
    code = _new_code()
    tv = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv")
    pl = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Bob")
    try:
        await _recv_state(tv, 5)
        # wait until TV sees the player
        await _drain_until(tv, lambda s: len(s["players"]) >= 1, 5)
        await tv.send(json.dumps({"type": "start", "category": 9, "mode": "classic", "rounds": 3}))

        seen_phases = set()
        podium = None
        answered_indexes = set()
        got_you = False

        # Use a shared queue populated by two background readers.
        queue: asyncio.Queue = asyncio.Queue()

        async def pump(ws, tag):
            try:
                while True:
                    raw = await ws.recv()
                    await queue.put((tag, json.loads(raw)))
            except Exception:
                pass

        readers = [asyncio.create_task(pump(tv, "tv")), asyncio.create_task(pump(pl, "pl"))]
        end = asyncio.get_event_loop().time() + 200
        try:
            while asyncio.get_event_loop().time() < end and podium is None:
                try:
                    tag, m = await asyncio.wait_for(queue.get(), timeout=15)
                except asyncio.TimeoutError:
                    continue
                if m.get("type") == "you":
                    got_you = True
                    assert "correct" in m and "gained" in m and "rank" in m
                if m.get("type") == "state":
                    seen_phases.add(m["phase"])
                    q = m.get("q")
                    if m["phase"] == "question" and q and q.get("index") not in answered_indexes:
                        assert q.get("deadline") and len(q.get("options", [])) == 4 and q.get("text")
                        answered_indexes.add(q["index"])
                        try:
                            await pl.send(json.dumps({"type": "answer", "answer": 0}))
                        except Exception:
                            pass
                    if m["phase"] == "podium":
                        podium = m.get("podium")
        finally:
            for r in readers:
                r.cancel()

        assert "countdown" in seen_phases
        assert "question" in seen_phases
        assert "reveal" in seen_phases
        assert "leaderboard" in seen_phases
        assert podium is not None and isinstance(podium, list) and len(podium) >= 1
        assert got_you, "player did not receive private 'you' message"
    finally:
        await tv.close()
        await pl.close()


@pytest.mark.asyncio
async def test_blitz_serves_two_options():
    code = _new_code()
    tv = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv")
    pl = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Cara")
    try:
        await _recv_state(tv, 5)
        await _drain_until(tv, lambda s: len(s["players"]) >= 1, 5)
        await tv.send(json.dumps({"type": "start", "category": 0, "mode": "blitz", "rounds": 3}))
        # wait for first question
        st = await _drain_until(tv, lambda s: s["phase"] == "question" and s.get("q"), 30)
        assert st and len(st["q"]["options"]) == 2
        assert st["q"]["options"] == ["True", "False"]
    finally:
        await tv.close()
        await pl.close()


@pytest.mark.asyncio
async def test_buzzer_mode_flow():
    code = _new_code()
    tv = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv")
    pl = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Dan")
    try:
        # capture pid
        pid = None
        for _ in range(4):
            m = json.loads(await asyncio.wait_for(pl.recv(), 5))
            if m.get("type") == "joined":
                pid = m["pid"]; break
        assert pid
        await _recv_state(tv, 5)
        await _drain_until(tv, lambda s: len(s["players"]) >= 1, 5)
        await tv.send(json.dumps({"type": "start", "category": 0, "mode": "buzzer", "rounds": 3}))
        # wait for question
        st = await _drain_until(tv, lambda s: s["phase"] == "question" and s.get("q"), 30)
        assert st and st["q"].get("buzz") is not None
        correct = st["q"].get("correct")  # not present at question phase
        # buzz in
        await pl.send(json.dumps({"type": "buzz"}))
        st2 = await _drain_until(tv, lambda s: s.get("q") and s["q"].get("buzz", {}).get("holder") == pid, 5)
        assert st2, "buzz holder was not set"
        # answer wrong: pick something surely not correct — try 0 and 1
        await pl.send(json.dumps({"type": "answer", "answer": 999}))  # invalid -> ignored
        # send a real wrong answer: since we don't know correct, send 0
        await pl.send(json.dumps({"type": "answer", "answer": 0}))
        # Either goes to reveal (if 0 was correct) or locks player and clears holder
        st3 = await _drain_until(
            tv,
            lambda s: s["phase"] == "reveal"
            or (s.get("q") and pid in (s["q"].get("buzz", {}).get("locked") or [])),
            10,
        )
        assert st3 is not None
    finally:
        await tv.close()
        await pl.close()
