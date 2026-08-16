"""ON NOW Trivia — real-time party trivia backend.

TV screen hosts a room (QR + 4-letter code); unlimited phones join as
players over WebSocket and become the answer pads / buzzers.

Game modes:
  • classic — 4-option multiple choice, speed scoring (500-1000 pts)
  • blitz   — rapid-fire True/False, 8 s per question
  • buzzer  — first buzz gets an exclusive answer window; wrong answer
              locks that player out and reopens the buzzers

Questions come from the Open Trivia DB (no key needed) with a local
fallback bank so a game can always start offline.
"""

from __future__ import annotations

import asyncio
import html
import json
import logging
import random
import string
import time
import uuid

import httpx
from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect

log = logging.getLogger("trivia")

router = APIRouter(prefix="/api/trivia", tags=["trivia"])

CATEGORIES = [
    {"id": 0,  "name": "Mixed Bag",       "tag": "everything"},
    {"id": 9,  "name": "General",         "tag": "knowledge"},
    {"id": 11, "name": "Movies",          "tag": "film"},
    {"id": 12, "name": "Music",           "tag": "music"},
    {"id": 14, "name": "Television",      "tag": "tv"},
    {"id": 17, "name": "Science",         "tag": "nature"},
    {"id": 21, "name": "Sport",           "tag": "sport"},
    {"id": 22, "name": "Geography",       "tag": "world"},
    {"id": 23, "name": "History",         "tag": "history"},
    {"id": 27, "name": "Animals",         "tag": "animals"},
]

MODES = {
    "classic": {"label": "Classic Quiz", "seconds": 20},
    "blitz":   {"label": "True/False Blitz", "seconds": 8},
    "buzzer":  {"label": "Fastest Finger", "seconds": 25},
}

AVATAR_COLORS = [
    "#00F0FF", "#FF007A", "#E1FF00", "#00FF66", "#FF6A00",
    "#B36BFF", "#FF3B30", "#33D2FF", "#FFC300", "#FF71CE",
    "#01FFC3", "#FFA0F0",
]

FALLBACK_QUESTIONS = [
    {"q": "Which planet is known as the Red Planet?", "correct": "Mars", "wrong": ["Venus", "Jupiter", "Mercury"]},
    {"q": "How many strings does a standard guitar have?", "correct": "Six", "wrong": ["Four", "Five", "Seven"]},
    {"q": "What is the largest ocean on Earth?", "correct": "Pacific", "wrong": ["Atlantic", "Indian", "Arctic"]},
    {"q": "Which country invented pizza?", "correct": "Italy", "wrong": ["France", "Greece", "Spain"]},
    {"q": "What is the capital of Australia?", "correct": "Canberra", "wrong": ["Sydney", "Melbourne", "Perth"]},
    {"q": "How many players are on a soccer team on the field?", "correct": "Eleven", "wrong": ["Nine", "Ten", "Twelve"]},
    {"q": "Which animal is the tallest in the world?", "correct": "Giraffe", "wrong": ["Elephant", "Ostrich", "Camel"]},
    {"q": "What gas do plants absorb from the air?", "correct": "Carbon dioxide", "wrong": ["Oxygen", "Nitrogen", "Helium"]},
    {"q": "In which sport would you perform a slam dunk?", "correct": "Basketball", "wrong": ["Tennis", "Golf", "Cricket"]},
    {"q": "How many continents are there?", "correct": "Seven", "wrong": ["Five", "Six", "Eight"]},
    {"q": "Which famous ship sank in 1912?", "correct": "Titanic", "wrong": ["Lusitania", "Britannic", "Endeavour"]},
    {"q": "What is the hardest natural substance?", "correct": "Diamond", "wrong": ["Gold", "Iron", "Quartz"]},
    {"q": "Which superhero is known as the Dark Knight?", "correct": "Batman", "wrong": ["Superman", "Spider-Man", "Thor"]},
    {"q": "What is the smallest prime number?", "correct": "Two", "wrong": ["One", "Three", "Zero"]},
    {"q": "Which country hosts the Wimbledon tennis tournament?", "correct": "England", "wrong": ["France", "Australia", "USA"]},
]

FALLBACK_TF = [
    {"q": "The Great Wall of China is visible from the Moon.", "correct": False},
    {"q": "Sharks are mammals.", "correct": False},
    {"q": "Venus is the hottest planet in the solar system.", "correct": True},
    {"q": "There are 88 keys on a standard piano.", "correct": True},
    {"q": "Sydney is the capital of Australia.", "correct": False},
    {"q": "An octopus has three hearts.", "correct": True},
    {"q": "Gold is heavier than lead.", "correct": True},
    {"q": "The human body has four lungs.", "correct": False},
    {"q": "Mount Everest is the tallest mountain on Earth.", "correct": True},
    {"q": "Bats are blind.", "correct": False},
    {"q": "Lightning never strikes the same place twice.", "correct": False},
    {"q": "Honey never spoils.", "correct": True},
]


# ─────────────────────────────── room state

class Room:
    def __init__(self, code: str):
        self.code = code
        self.created = time.time()
        self.last_activity = time.time()
        self.phase = "lobby"
        self.tv_sockets: set[WebSocket] = set()
        self.players: dict[str, dict] = {}          # pid → {name, color, score, ws}
        self.settings = {"category": 0, "mode": "classic", "rounds": 10}
        self.questions: list[dict] = []
        self.qindex = -1
        self.deadline = 0.0
        self.answers: dict[str, dict] = {}          # pid → {answer, at}
        self.reveal: dict = {}
        self.buzz_holder: str | None = None
        self.buzz_deadline = 0.0
        self.buzz_locked: set[str] = set()
        self.loop_task: asyncio.Task | None = None
        self.podium: list[dict] = []

    # ── serialisation ──
    def player_list(self) -> list[dict]:
        return [
            {
                "id": pid,
                "name": p["name"],
                "color": p["color"],
                "score": p["score"],
                "answered": pid in self.answers,
                "connected": p.get("ws") is not None,
            }
            for pid, p in self.players.items()
        ]

    def question_payload(self, with_correct: bool) -> dict | None:
        if self.qindex < 0 or self.qindex >= len(self.questions):
            return None
        q = self.questions[self.qindex]
        out = {
            "index": self.qindex + 1,
            "total": len(self.questions),
            "text": q["text"],
            "options": q["options"],
            "mode": self.settings["mode"],
            "deadline": self.deadline,
            "duration": MODES[self.settings["mode"]]["seconds"],
        }
        if self.settings["mode"] == "buzzer":
            holder = self.players.get(self.buzz_holder or "")
            out["buzz"] = {
                "holder": self.buzz_holder,
                "holder_name": holder["name"] if holder else None,
                "deadline": self.buzz_deadline,
                "locked": sorted(self.buzz_locked),
            }
        if with_correct:
            out["correct"] = q["correct"]
            out["results"] = self.reveal.get("results", [])
        return out

    def snapshot(self, with_correct: bool = False) -> dict:
        return {
            "type": "state",
            "phase": self.phase,
            "code": self.code,
            "players": self.player_list(),
            "settings": self.settings,
            "categories": CATEGORIES,
            "modes": [{"id": k, **v} for k, v in MODES.items()],
            "q": self.question_payload(with_correct or self.phase in ("reveal", "leaderboard")),
            "podium": self.podium if self.phase == "podium" else [],
            "now": time.time(),
        }

    async def broadcast(self):
        snap = json.dumps(self.snapshot())
        dead_tv = []
        for ws in list(self.tv_sockets):
            try:
                await ws.send_text(snap)
            except Exception:
                dead_tv.append(ws)
        for ws in dead_tv:
            self.tv_sockets.discard(ws)
        for pid, p in list(self.players.items()):
            ws = p.get("ws")
            if ws is None:
                continue
            try:
                await ws.send_text(snap)
            except Exception:
                p["ws"] = None

    async def send_private(self, pid: str, msg: dict):
        p = self.players.get(pid)
        ws = p and p.get("ws")
        if ws is None:
            return
        try:
            await ws.send_text(json.dumps(msg))
        except Exception:
            p["ws"] = None


ROOMS: dict[str, Room] = {}


def _new_code() -> str:
    alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ"  # no I/L/O — QR-friendly
    for _ in range(60):
        code = "".join(random.choices(alphabet, k=4))
        if code not in ROOMS:
            return code
    return "".join(random.choices(string.ascii_uppercase, k=6))


# ─────────────────────────────── questions

async def _fetch_questions(category: int, mode: str, count: int) -> list[dict]:
    qtype = "boolean" if mode == "blitz" else "multiple"
    url = f"https://opentdb.com/api.php?amount={count}&type={qtype}"
    if category:
        url += f"&category={category}"
    out: list[dict] = []
    try:
        async with httpx.AsyncClient(timeout=12) as cli:
            r = await cli.get(url)
            data = r.json()
        for it in data.get("results") or []:
            text = html.unescape(it.get("question") or "")
            correct = html.unescape(it.get("correct_answer") or "")
            if qtype == "boolean":
                options = ["True", "False"]
                out.append({
                    "text": text,
                    "options": options,
                    "correct": options.index(correct) if correct in options else 0,
                })
            else:
                wrong = [html.unescape(w) for w in it.get("incorrect_answers") or []]
                options = wrong + [correct]
                random.shuffle(options)
                out.append({
                    "text": text,
                    "options": options,
                    "correct": options.index(correct),
                })
    except Exception as t:  # noqa: BLE001
        log.warning("opentdb fetch failed: %s", t)
    if len(out) >= max(3, count // 2):
        return out[:count]
    # Fallback bank — a game must always be able to start.
    out = []
    if qtype == "boolean":
        pool = random.sample(FALLBACK_TF, min(count, len(FALLBACK_TF)))
        for f in pool:
            out.append({
                "text": f["q"],
                "options": ["True", "False"],
                "correct": 0 if f["correct"] else 1,
            })
    else:
        pool = random.sample(FALLBACK_QUESTIONS, min(count, len(FALLBACK_QUESTIONS)))
        for f in pool:
            options = f["wrong"] + [f["correct"]]
            random.shuffle(options)
            out.append({
                "text": f["q"],
                "options": options,
                "correct": options.index(f["correct"]),
            })
    return out


# ─────────────────────────────── game loop

def _mode_seconds(room: Room) -> int:
    return MODES[room.settings["mode"]]["seconds"]


async def _run_game(room: Room):
    try:
        for i in range(len(room.questions)):
            room.qindex = i
            room.answers = {}
            room.reveal = {}
            room.buzz_holder = None
            room.buzz_locked = set()
            room.phase = "countdown"
            room.deadline = time.time() + 3
            await room.broadcast()
            await asyncio.sleep(3)

            room.phase = "question"
            room.deadline = time.time() + _mode_seconds(room)
            await room.broadcast()

            # Wait until every connected player answered or time is up.
            while time.time() < room.deadline:
                await asyncio.sleep(0.25)
                if room.settings["mode"] != "buzzer":
                    live = [pid for pid, p in room.players.items() if p.get("ws")]
                    if live and all(pid in room.answers for pid in live):
                        break
                else:
                    if room.reveal.get("done"):
                        break
                    # Buzz answer window expired → lock holder out, reopen.
                    if room.buzz_holder and time.time() > room.buzz_deadline:
                        room.buzz_locked.add(room.buzz_holder)
                        room.buzz_holder = None
                        await room.broadcast()

            await _reveal(room)
            await asyncio.sleep(5)

            room.phase = "leaderboard"
            await room.broadcast()
            await asyncio.sleep(4 if i < len(room.questions) - 1 else 2)

        ranked = sorted(room.players.values(), key=lambda p: -p["score"])
        room.podium = [
            {"name": p["name"], "color": p["color"], "score": p["score"]}
            for p in ranked[:3]
        ]
        room.phase = "podium"
        room.qindex = -1
        await room.broadcast()
    except asyncio.CancelledError:
        raise
    except Exception as t:  # noqa: BLE001
        log.warning("trivia game loop crashed: %s", t)
        room.phase = "lobby"
        await room.broadcast()


async def _reveal(room: Room):
    q = room.questions[room.qindex]
    mode = room.settings["mode"]
    seconds = _mode_seconds(room)
    results = []
    for pid, p in room.players.items():
        a = room.answers.get(pid)
        correct = a is not None and a["answer"] == q["correct"]
        gained = 0
        if correct:
            if mode == "buzzer":
                gained = 800
            else:
                base = 400 if mode == "blitz" else 500
                remain = max(0.0, room.deadline - a["at"])
                gained = int(base + base * min(1.0, remain / seconds))
        elif mode == "buzzer" and a is not None:
            gained = -200
        p["score"] = max(0, p["score"] + gained)
        results.append({
            "id": pid,
            "name": p["name"],
            "color": p["color"],
            "correct": correct,
            "gained": gained,
            "answer": a["answer"] if a else None,
        })
    room.reveal = {"results": results, "done": True}
    room.phase = "reveal"
    await room.broadcast()
    ranked = sorted(room.players.items(), key=lambda kv: -kv[1]["score"])
    ranks = {pid: i + 1 for i, (pid, _) in enumerate(ranked)}
    for r in results:
        await room.send_private(r["id"], {
            "type": "you",
            "correct": r["correct"],
            "gained": r["gained"],
            "rank": ranks.get(r["id"], 0),
            "score": room.players[r["id"]]["score"],
        })


# ─────────────────────────────── REST

@router.post("/rooms")
async def create_room() -> dict:
    code = _new_code()
    ROOMS[code] = Room(code)
    # Opportunistic cleanup of rooms idle for 3 h.
    now = time.time()
    for c, r in list(ROOMS.items()):
        if now - r.last_activity > 3 * 3600:
            if r.loop_task:
                r.loop_task.cancel()
            ROOMS.pop(c, None)
    return {"code": code, "categories": CATEGORIES,
            "modes": [{"id": k, **v} for k, v in MODES.items()]}


@router.get("/rooms/{code}")
async def room_exists(code: str) -> dict:
    room = ROOMS.get(code.upper())
    if not room:
        raise HTTPException(404, "room not found")
    return {"code": room.code, "phase": room.phase, "players": len(room.players)}


# ─────────────────────────────── WebSocket

@router.websocket("/ws/{code}")
async def trivia_ws(ws: WebSocket, code: str):
    await ws.accept()
    room = ROOMS.get(code.upper())
    if not room:
        await ws.send_text(json.dumps({"type": "error", "error": "room_not_found"}))
        await ws.close()
        return
    role = ws.query_params.get("role", "player")
    pid: str | None = None

    if role == "tv":
        room.tv_sockets.add(ws)
    else:
        name = (ws.query_params.get("name") or "").strip()[:18] or "Player"
        pid = ws.query_params.get("pid") or uuid.uuid4().hex[:10]
        existing = room.players.get(pid)
        if existing:
            existing["ws"] = ws
            existing["name"] = name or existing["name"]
        else:
            room.players[pid] = {
                "name": name,
                "color": AVATAR_COLORS[len(room.players) % len(AVATAR_COLORS)],
                "score": 0,
                "ws": ws,
            }
        await ws.send_text(json.dumps({"type": "joined", "pid": pid}))

    room.last_activity = time.time()
    await ws.send_text(json.dumps(room.snapshot()))
    await room.broadcast()

    try:
        while True:
            raw = await ws.receive_text()
            room.last_activity = time.time()
            try:
                msg = json.loads(raw)
            except ValueError:
                continue
            mtype = msg.get("type")

            if role == "tv":
                if mtype == "start" and room.phase in ("lobby", "podium"):
                    room.settings["category"] = int(msg.get("category", 0))
                    mode = msg.get("mode", "classic")
                    room.settings["mode"] = mode if mode in MODES else "classic"
                    room.settings["rounds"] = max(3, min(20, int(msg.get("rounds", 10))))
                    for p in room.players.values():
                        p["score"] = 0
                    room.podium = []
                    room.phase = "loading"
                    await room.broadcast()
                    room.questions = await _fetch_questions(
                        room.settings["category"],
                        room.settings["mode"],
                        room.settings["rounds"],
                    )
                    if room.loop_task:
                        room.loop_task.cancel()
                    room.loop_task = asyncio.create_task(_run_game(room))
                elif mtype == "back_to_lobby":
                    if room.loop_task:
                        room.loop_task.cancel()
                        room.loop_task = None
                    room.phase = "lobby"
                    room.qindex = -1
                    room.podium = []
                    await room.broadcast()

            else:  # player
                if mtype == "answer" and room.phase == "question" and pid:
                    mode = room.settings["mode"]
                    idx = msg.get("answer")
                    if not isinstance(idx, int):
                        continue
                    if mode == "buzzer":
                        # Only the buzz holder may answer.
                        if pid != room.buzz_holder:
                            continue
                        room.answers[pid] = {"answer": idx, "at": time.time()}
                        q = room.questions[room.qindex]
                        if idx == q["correct"]:
                            room.reveal["done"] = True
                        else:
                            room.buzz_locked.add(pid)
                            room.buzz_holder = None
                            await room.broadcast()
                    else:
                        if pid not in room.answers and time.time() <= room.deadline:
                            room.answers[pid] = {"answer": idx, "at": time.time()}
                            await room.broadcast()
                elif mtype == "buzz" and room.phase == "question" and pid:
                    if (room.settings["mode"] == "buzzer"
                            and room.buzz_holder is None
                            and pid not in room.buzz_locked
                            and pid not in room.answers):
                        room.buzz_holder = pid
                        room.buzz_deadline = time.time() + 6
                        await room.broadcast()
                elif mtype == "rename" and pid and room.phase == "lobby":
                    nm = (msg.get("name") or "").strip()[:18]
                    if nm:
                        room.players[pid]["name"] = nm
                        await room.broadcast()
    except WebSocketDisconnect:
        pass
    except Exception:  # noqa: BLE001
        pass
    finally:
        if role == "tv":
            room.tv_sockets.discard(ws)
        elif pid and pid in room.players:
            room.players[pid]["ws"] = None
        try:
            await room.broadcast()
        except Exception:  # noqa: BLE001
            pass
