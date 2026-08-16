"""ON NOW Trivia iter-83: pictures, puzzle party, sounds regression."""
import asyncio
import json
import os
import pytest
import requests
import websockets

BASE_URL = (os.environ.get("REACT_APP_BACKEND_URL")
            or open("/app/frontend/.env").read().split("REACT_APP_BACKEND_URL=", 1)[1].split("\n")[0]
            ).strip().rstrip("/")
WS_BASE = BASE_URL.replace("http", "ws", 1)


def _new_code():
    return requests.post(f"{BASE_URL}/api/trivia/rooms", timeout=15).json()["code"]


async def _recv_state(ws, timeout=5):
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


# ── REST: 14 categories & 4 modes ──
def test_room_has_14_categories_and_4_modes():
    d = requests.post(f"{BASE_URL}/api/trivia/rooms", timeout=15).json()
    cats = d["categories"]
    assert len(cats) == 14, f"expected 14 categories got {len(cats)}"
    pic_ids = {c["id"] for c in cats if c.get("picture")}
    assert pic_ids == {"pic_flags", "pic_movies", "pic_animals", "pic_landmarks"}
    mode_ids = {m["id"] for m in d["modes"]}
    assert mode_ids == {"classic", "blitz", "buzzer", "puzzle"}


# ── Sound files served (200 audio/wav) ──
@pytest.mark.parametrize("name", [
    "lobby", "tick", "whoosh", "buzz", "correct", "wrong", "fanfare", "click",
])
def test_sound_files_served(name):
    r = requests.get(f"{BASE_URL}/sounds/trivia/{name}.wav", timeout=15)
    assert r.status_code == 200, f"{name}.wav returned {r.status_code}"
    ctype = r.headers.get("content-type", "").lower()
    assert "wav" in ctype or "audio" in ctype, f"unexpected content-type {ctype} for {name}"
    assert len(r.content) > 200


# ── Picture round: flags always work (no network dep) ──
@pytest.mark.asyncio
async def test_picture_flags_serves_images():
    code = _new_code()
    tv = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv")
    pl = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Pix")
    try:
        await _recv_state(tv, 5)
        await _drain_until(tv, lambda s: len(s["players"]) >= 1, 5)
        await tv.send(json.dumps({"type": "start", "category": "pic_flags", "mode": "classic", "rounds": 3}))
        st = await _drain_until(tv, lambda s: s["phase"] == "question" and s.get("q"), 30)
        assert st, "never got a question"
        q = st["q"]
        assert q.get("kind") == "picture"
        assert q.get("image", "").startswith("https://flagcdn.com/")
        assert len(q["options"]) == 4
    finally:
        await tv.close(); await pl.close()


# ── Picture + Blitz coercion → classic ──
@pytest.mark.asyncio
async def test_blitz_plus_picture_coerces_to_classic():
    code = _new_code()
    tv = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv")
    pl = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Bx")
    try:
        await _recv_state(tv, 5)
        await _drain_until(tv, lambda s: len(s["players"]) >= 1, 5)
        await tv.send(json.dumps({"type": "start", "category": "pic_flags", "mode": "blitz", "rounds": 3}))
        st = await _drain_until(tv, lambda s: s["settings"]["mode"] != "blitz" or s["phase"] not in ("lobby", "loading"), 15)
        assert st is not None
        assert st["settings"]["mode"] == "classic", f"got {st['settings']['mode']}"
        assert st["settings"]["category"] == "pic_flags"
    finally:
        await tv.close(); await pl.close()


# ── Puzzle Party: mixed kinds + number scoring ──
@pytest.mark.asyncio
async def test_puzzle_party_mixed_kinds_and_number_scoring():
    code = _new_code()
    tv = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=tv")
    pl1 = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Ava")
    pl2 = await websockets.connect(f"{WS_BASE}/api/trivia/ws/{code}?role=player&name=Bea")

    async def get_pid(ws):
        for _ in range(6):
            m = json.loads(await asyncio.wait_for(ws.recv(), 5))
            if m.get("type") == "joined":
                return m["pid"]
        return None

    try:
        pid1 = await get_pid(pl1)
        pid2 = await get_pid(pl2)
        assert pid1 and pid2
        await _recv_state(tv, 5)
        await _drain_until(tv, lambda s: len(s["players"]) >= 2, 6)
        await tv.send(json.dumps({"type": "start", "category": 0, "mode": "puzzle", "rounds": 6}))

        kinds_seen = set()
        number_reveal = None
        answered = set()

        queue: asyncio.Queue = asyncio.Queue()

        async def pump(ws, tag):
            try:
                while True:
                    raw = await ws.recv()
                    await queue.put((tag, json.loads(raw)))
            except Exception:
                pass

        readers = [asyncio.create_task(pump(tv, "tv")),
                   asyncio.create_task(pump(pl1, "p1")),
                   asyncio.create_task(pump(pl2, "p2"))]
        end = asyncio.get_event_loop().time() + 240
        try:
            while asyncio.get_event_loop().time() < end:
                try:
                    tag, m = await asyncio.wait_for(queue.get(), timeout=20)
                except asyncio.TimeoutError:
                    continue
                if m.get("type") == "state":
                    q = m.get("q")
                    if m["phase"] == "question" and q:
                        kinds_seen.add(q.get("kind", "text"))
                        key = (q.get("index"), q.get("qtype"))
                        if key not in answered:
                            answered.add(key)
                            if q.get("qtype") == "number":
                                # Ava guesses target-5, Bea guesses target+500
                                target = None
                                # We don't know target — just guess something
                                await pl1.send(json.dumps({"type": "answer", "answer": 42}))
                                await pl2.send(json.dumps({"type": "answer", "answer": 10000}))
                            else:
                                await pl1.send(json.dumps({"type": "answer", "answer": 0}))
                                await pl2.send(json.dumps({"type": "answer", "answer": 1}))
                    if m["phase"] == "reveal" and q and q.get("qtype") == "number":
                        number_reveal = q
                    if m["phase"] == "podium":
                        break
        finally:
            for r in readers:
                r.cancel()

        assert "anagram" in kinds_seen or "emoji" in kinds_seen or "number" in kinds_seen, (
            f"no puzzle kinds seen: {kinds_seen}")
        # We ran 6 rounds — should have hit at least a number type
        assert "number" in kinds_seen, f"never saw number question: {kinds_seen}"
        if number_reveal:
            gains = [r["gained"] for r in number_reveal.get("results", [])]
            # closest gets 1000
            assert 1000 in gains, f"closest didn't get 1000: {gains}"
    finally:
        await tv.close(); await pl1.close(); await pl2.close()
