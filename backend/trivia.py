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
import os
import random
import re
import string
import time
import uuid
from urllib.parse import quote

import httpx
from fastapi import APIRouter, HTTPException, WebSocket, WebSocketDisconnect

from trivia_banks import (
    ANAGRAM_BANK, ANIMAL_BANK, EMOJI_BANK, FLAG_BANK, LANDMARK_BANK, NUMBER_BANK,
)

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

# Picture rounds — image-based multiple choice.  Flags render straight
# from flagcdn; movies come from TMDB posters; animals/landmarks from
# Wikipedia lead images (resolved + cached at game start).
PICTURE_CATEGORIES = [
    {"id": "pic_flags",     "name": "Flags of the World", "tag": "picture", "picture": True},
    {"id": "pic_movies",    "name": "Movie Posters",      "tag": "picture", "picture": True},
    {"id": "pic_animals",   "name": "Animal Pics",        "tag": "picture", "picture": True},
    {"id": "pic_landmarks", "name": "Landmarks",          "tag": "picture", "picture": True},
]
PICTURE_IDS = {c["id"] for c in PICTURE_CATEGORIES}
CATEGORIES = CATEGORIES + PICTURE_CATEGORIES

MODES = {
    "classic": {"label": "Classic Quiz", "seconds": 20},
    "blitz":   {"label": "True/False Blitz", "seconds": 8},
    "buzzer":  {"label": "Fastest Finger", "seconds": 25},
    "puzzle":  {"label": "Puzzle Party", "seconds": 20},
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
            "kind": q.get("kind", "text"),
            "qtype": q.get("qtype", "mc"),
        }
        for extra in ("image", "hint", "unit", "blur"):
            if q.get(extra) is not None:
                out[extra] = q[extra]
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
    base = f"https://opentdb.com/api.php?amount={count}&type={qtype}"
    if category:
        base += f"&category={category}"
    out: list[dict] = []
    try:
        async with httpx.AsyncClient(timeout=12) as cli:
            # v2.19.4 — user spec: "questions a little bit easier so
            # more people know them" → ask for the easy pool first and
            # only fall back to mixed difficulty if OpenTDB is short.
            r = await cli.get(base + "&difficulty=easy")
            data = r.json()
            if len(data.get("results") or []) < max(3, count // 2):
                r = await cli.get(base)
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


# ─────────────────────────────── picture rounds

def _mc(text: str, correct: str, wrongs: list[str], **extra) -> dict:
    options = list(wrongs) + [correct]
    random.shuffle(options)
    return {"text": text, "options": options, "correct": options.index(correct), **extra}


def _flag_questions(count: int) -> list[dict]:
    pool = random.sample(FLAG_BANK, min(count, len(FLAG_BANK)))
    out = []
    for name, iso in pool:
        wrongs = random.sample([n for n, _ in FLAG_BANK if n != name], 3)
        out.append(_mc("Which country does this flag belong to?", name, wrongs,
                       kind="picture", image=f"https://flagcdn.com/w640/{iso}.png"))
    return out


_TMDB_CACHE: dict = {"ts": 0.0, "pool": []}


async def _tmdb_pool() -> list[dict]:
    if _TMDB_CACHE["pool"] and time.time() - _TMDB_CACHE["ts"] < 6 * 3600:
        return _TMDB_CACHE["pool"]
    token = os.environ.get("TMDB_BEARER_TOKEN")
    if not token:
        return _TMDB_CACHE["pool"]
    urls = [f"https://api.themoviedb.org/3/movie/popular?page={p}" for p in (1, 2, 3)] + \
           [f"https://api.themoviedb.org/3/movie/top_rated?page={p}" for p in (1, 2, 3)] + \
           [f"https://api.themoviedb.org/3/movie/now_playing?page={p}" for p in (1, 2)]
    pool: dict[str, str] = {}
    try:
        async with httpx.AsyncClient(
                timeout=10, headers={"Authorization": f"Bearer {token}"}) as cli:
            for r in await asyncio.gather(*(cli.get(u) for u in urls), return_exceptions=True):
                if isinstance(r, Exception) or r.status_code != 200:
                    continue
                for m in r.json().get("results") or []:
                    title, poster = m.get("title"), m.get("poster_path")
                    if title and poster and title not in pool:
                        pool[title] = f"https://image.tmdb.org/t/p/w780{poster}"
    except Exception as t:  # noqa: BLE001
        log.warning("tmdb pool fetch failed: %s", t)
    if len(pool) >= 12:
        _TMDB_CACHE["pool"] = [{"title": k, "poster": v} for k, v in pool.items()]
        _TMDB_CACHE["ts"] = time.time()
    return _TMDB_CACHE["pool"]


_RECENT_POSTERS: list[str] = []  # titles used in recent games — rotate covers


async def _movie_questions(count: int) -> list[dict]:
    pool = await _tmdb_pool()
    if len(pool) < 8:
        return []
    # Rotation: exclude titles used in recent games so back-to-back
    # sessions don't keep showing the same posters (user spec).
    fresh = [m for m in pool if m["title"] not in _RECENT_POSTERS]
    if len(fresh) < count + 4:
        _RECENT_POSTERS.clear()
        fresh = pool
    picks = random.sample(fresh, min(count, len(fresh)))
    _RECENT_POSTERS.extend(m["title"] for m in picks)
    del _RECENT_POSTERS[:-90]
    out = []
    for m in picks:
        wrongs = random.sample([x["title"] for x in pool if x["title"] != m["title"]], 3)
        out.append(_mc("Which movie is this poster from?", m["title"], wrongs,
                       kind="picture", image=m["poster"], blur=True))
    return out


_WIKI_CACHE: dict[str, str | None] = {}


async def _wiki_thumb(cli: httpx.AsyncClient, title: str) -> str | None:
    if title in _WIKI_CACHE:
        return _WIKI_CACHE[title]
    url = None
    try:
        r = await cli.get(
            f"https://en.wikipedia.org/api/rest_v1/page/summary/{quote(title)}",
            headers={"User-Agent": "OnNowTrivia/1.0 (https://onnowhub.com; contact@onnowhub.com) httpx"},
            follow_redirects=True)
        if r.status_code == 200:
            j = r.json()
            orig = j.get("originalimage") or {}
            th = (j.get("thumbnail") or {}).get("source")
            if orig.get("source") and (orig.get("width") or 0) and orig["width"] <= 1800:
                url = orig["source"]
            elif th:
                if (orig.get("width") or 0) > 640:
                    url = re.sub(r"/(\d+)px-", "/640px-", th, count=1)
                else:
                    url = th
    except Exception:  # noqa: BLE001
        pass
    _WIKI_CACHE[title] = url
    return url


async def _wiki_questions(bank: list[dict], count: int, text: str) -> list[dict]:
    picks = random.sample(bank, min(count + 5, len(bank)))
    async with httpx.AsyncClient(timeout=8) as cli:
        thumbs = await asyncio.gather(*(_wiki_thumb(cli, e["title"]) for e in picks))
    answers = [e["answer"] for e in bank]
    out = []
    for e, img in zip(picks, thumbs):
        if not img or len(out) >= count:
            continue
        wrongs = random.sample([a for a in answers if a != e["answer"]], 3)
        out.append(_mc(text, e["answer"], wrongs, kind="picture", image=img))
    return out


async def _picture_questions(cat: str, count: int) -> list[dict]:
    if cat == "pic_flags":
        out = _flag_questions(count)
    elif cat == "pic_movies":
        out = await _movie_questions(count)
    elif cat == "pic_animals":
        out = await _wiki_questions(ANIMAL_BANK, count, "What animal is this?")
    elif cat == "pic_landmarks":
        out = await _wiki_questions(LANDMARK_BANK, count, "Where in the world is this landmark?")
    else:
        out = []
    if len(out) < count:
        # Flags never need the network — always able to top up.
        out.extend(_flag_questions(count - len(out)))
    random.shuffle(out)
    return out[:count]


# ─────────────────────────────── Puzzle Party

def _puzzle_questions(count: int) -> list[dict]:
    per = max(1, count // 3)
    out: list[dict] = []
    for e in random.sample(ANAGRAM_BANK, min(per, len(ANAGRAM_BANK))):
        word = e["word"].upper()
        letters = list(word)
        for _ in range(24):
            random.shuffle(letters)
            if "".join(letters) != word:
                break
        out.append(_mc("".join(letters), word, [w.upper() for w in e["wrong"]],
                       kind="anagram", hint=e["hint"]))
    for e in random.sample(EMOJI_BANK, min(per, len(EMOJI_BANK))):
        out.append(_mc(e["emoji"], e["answer"], e["wrong"], kind="emoji", hint=e["hint"]))
    n_num = max(0, count - len(out))
    for e in random.sample(NUMBER_BANK, min(n_num, len(NUMBER_BANK))):
        out.append({
            "text": e["q"], "options": [], "correct": e["answer"],
            "kind": "number", "qtype": "number", "unit": e.get("unit", ""),
        })
    random.shuffle(out)
    return out[:count]


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
    if q.get("qtype") == "number":
        # Closest-number showdown: rank every guess by distance from
        # the target; ties share the same rung of the points ladder.
        target = float(q["correct"])
        ordered = sorted(
            ((pid, float(a["answer"])) for pid, a in room.answers.items()
             if pid in room.players),
            key=lambda kv: abs(kv[1] - target),
        )
        ladder = [1000, 750, 550, 425, 325, 250, 200, 160, 130, 100]
        gains: dict[str, int] = {}
        winners: set[str] = set()
        prev_diff: float | None = None
        rank_idx = 0
        for i, (apid, val) in enumerate(ordered):
            diff = abs(val - target)
            if prev_diff is not None and diff > prev_diff:
                rank_idx = i
            prev_diff = diff
            gains[apid] = ladder[min(rank_idx, len(ladder) - 1)]
            if rank_idx == 0:
                winners.add(apid)
        for pid, p in room.players.items():
            a = room.answers.get(pid)
            gained = gains.get(pid, 0)
            p["score"] = max(0, p["score"] + gained)
            results.append({
                "id": pid,
                "name": p["name"],
                "color": p["color"],
                "correct": pid in winners,
                "gained": gained,
                "answer": a["answer"] if a else None,
            })
    else:
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
                    cat = msg.get("category", 0)
                    if not (isinstance(cat, str) and cat in PICTURE_IDS):
                        try:
                            cat = int(cat)
                        except (TypeError, ValueError):
                            cat = 0
                    mode = msg.get("mode", "classic")
                    mode = mode if mode in MODES else "classic"
                    if isinstance(cat, str) and mode == "blitz":
                        mode = "classic"  # picture rounds are multiple-choice
                    room.settings["category"] = cat
                    room.settings["mode"] = mode
                    room.settings["rounds"] = max(3, min(20, int(msg.get("rounds", 10))))
                    for p in room.players.values():
                        p["score"] = 0
                    room.podium = []
                    room.phase = "loading"
                    await room.broadcast()
                    if mode == "puzzle":
                        room.questions = _puzzle_questions(room.settings["rounds"])
                    elif isinstance(cat, str):
                        room.questions = await _picture_questions(cat, room.settings["rounds"])
                    else:
                        room.questions = await _fetch_questions(cat, mode, room.settings["rounds"])
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
                    qcur = (room.questions[room.qindex]
                            if 0 <= room.qindex < len(room.questions) else None)
                    if qcur is not None and qcur.get("qtype") == "number":
                        if isinstance(idx, bool) or not isinstance(idx, (int, float)):
                            continue
                    elif isinstance(idx, bool) or not isinstance(idx, int):
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
