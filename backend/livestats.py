"""
Live Sports Match Centre — API-Sports proxy (api-football.com direct key).

One key covers every api-sports.io product.  Free plan = 100 req/day
PER SPORT, so this router is built around aggressive shared caching:

  • live-game lists are cached 120 s and shared by every box/user
  • football statistics are cached 240 s per fixture
  • the client is told how fast to poll via `nextRefreshSecs`, which
    stretches automatically as the daily quota drains (read from the
    `x-ratelimit-requests-remaining` response header).

Free-plan reality check (verified 2026-07-11):
  ✅ football (v3 live=all), baseball, basketball, hockey, rugby, nfl
  ❌ afl + formula-1 → current season is plan-locked ("try 2022-2024");
     those sports return found=false / reason=plan_locked until the
     account is upgraded — the client renders a friendly notice.
"""

from __future__ import annotations

import logging
import os
import re
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional, Tuple

import httpx
from fastapi import APIRouter, HTTPException, Query

logger = logging.getLogger("vesper.livestats")

router = APIRouter(prefix="/api/livestats")

APISPORTS_KEY = os.environ.get("APISPORTS_KEY", "")

LIST_TTL = 120       # seconds — shared live-game list per sport
STATS_TTL = 240      # seconds — football statistics / f1 rankings

# Bucket IDs match LiveSportsClassifier on the Android side.
SPORTS: Dict[str, Dict[str, str]] = {
    "soccer": {"host": "https://v3.football.api-sports.io", "label": "Football", "kind": "football"},
    "afl":    {"host": "https://v1.afl.api-sports.io", "label": "AFL", "kind": "afl"},
    "nba":    {"host": "https://v1.basketball.api-sports.io", "label": "Basketball", "kind": "basketball"},
    "mlb":    {"host": "https://v1.baseball.api-sports.io", "label": "Baseball", "kind": "baseball"},
    "nhl":    {"host": "https://v1.hockey.api-sports.io", "label": "Ice Hockey", "kind": "hockey"},
    "rugby":  {"host": "https://v1.rugby.api-sports.io", "label": "Rugby Union", "kind": "rugby"},
    "nrl":    {"host": "https://v1.rugby.api-sports.io", "label": "Rugby League", "kind": "rugby"},
    "nfl":    {"host": "https://v1.american-football.api-sports.io", "label": "NFL", "kind": "nfl"},
    "f1":     {"host": "https://v1.formula-1.api-sports.io", "label": "Formula 1", "kind": "f1"},
    "mma":    {"host": "https://v1.mma.api-sports.io", "label": "MMA", "kind": "mma"},
}

LIVE_SHORTS: Dict[str, set] = {
    "football":   {"1H", "2H", "HT", "ET", "BT", "P", "LIVE", "INT"},
    "afl":        {"Q1", "Q2", "Q3", "Q4", "QT", "HT", "ER", "OT"},
    "basketball": {"Q1", "Q2", "Q3", "Q4", "OT", "BT", "HT"},
    "hockey":     {"P1", "P2", "P3", "OT", "PT", "BT", "SO"},
    "rugby":      {"1H", "2H", "HT", "ET", "BT", "PT", "GP"},
    "nfl":        {"Q1", "Q2", "Q3", "Q4", "OT", "HT"},
    "mma":        {"LIVE", "EOR"},
}

SUPERSCRIPT_LIVE = "\u1d38\u1da6\u1d5b\u1d49"

_STOP = {
    "live", "the", "vs", "v", "at", "and", "of", "on", "in", "round",
    "week", "match", "game", "games", "cup", "league", "rugby", "union",
    "football", "soccer", "hockey", "baseball", "basketball", "afl",
    "nfl", "nba", "nhl", "mlb", "mma", "ufc", "session", "night", "day",
    "fc", "womens", "women", "mens", "men",
}

_cache: Dict[str, Tuple[float, Any]] = {}
_quota: Dict[str, Optional[int]] = {}


def _cache_get(key: str, ttl: int) -> Any:
    hit = _cache.get(key)
    if not hit:
        return None
    ts, data = hit
    if time.time() - ts > ttl:
        return None
    return data


def _cache_put(key: str, data: Any) -> None:
    _cache[key] = (time.time(), data)


async def _api_get(host: str, path: str, params: Dict[str, Any]) -> Dict[str, Any]:
    async with httpx.AsyncClient(timeout=12.0) as client:
        r = await client.get(host + path, params=params,
                             headers={"x-apisports-key": APISPORTS_KEY})
    rem = r.headers.get("x-ratelimit-requests-remaining")
    if rem is not None:
        try:
            _quota[host] = int(rem)
        except ValueError:
            pass
    r.raise_for_status()
    return r.json()


def _plan_error(payload: Dict[str, Any]) -> Optional[str]:
    errs = payload.get("errors")
    if isinstance(errs, dict):
        for k in ("plan", "rateLimit", "requests", "token"):
            if errs.get(k):
                return str(errs[k])
        if errs:
            return "; ".join(str(v) for v in errs.values())
    return None


def _is_live(kind: str, g: Dict[str, Any]) -> bool:
    gg = g.get("game") or g
    st = ((gg.get("status") or {}).get("short") or "")
    st = str(st).upper()
    if kind == "baseball":
        return st.startswith("IN") or st == "LIVE"
    return st in LIVE_SHORTS.get(kind, set())


def _f1_is_live(r: Dict[str, Any]) -> bool:
    return str(r.get("status") or "").lower() in ("live", "in progress")


async def _live_games(sport: str) -> Dict[str, Any]:
    cfg = SPORTS[sport]
    key = f"live:{cfg['host']}"
    cached = _cache_get(key, LIST_TTL)
    if cached is not None:
        return cached
    kind, host = cfg["kind"], cfg["host"]
    games: List[dict] = []
    plan_error: Optional[str] = None
    try:
        if kind == "football":
            data = await _api_get(host, "/fixtures", {"live": "all"})
            plan_error = _plan_error(data)
            games = data.get("response") or []
        elif kind == "nfl":
            data = await _api_get(host, "/games", {"live": "all"})
            plan_error = _plan_error(data)
            games = data.get("response") or []
        elif kind == "f1":
            year = datetime.now(timezone.utc).year
            data = await _api_get(host, "/races", {"season": year, "type": "Race"})
            plan_error = _plan_error(data)
            games = [r for r in (data.get("response") or []) if _f1_is_live(r)]
        else:
            now = datetime.now(timezone.utc)
            dates = [now.strftime("%Y-%m-%d")]
            if now.hour < 6:
                dates.append((now - timedelta(days=1)).strftime("%Y-%m-%d"))
            path = "/fights" if kind == "mma" else "/games"
            merged: List[dict] = []
            for d in dates:
                data = await _api_get(host, path, {"date": d})
                pe = _plan_error(data)
                if pe:
                    plan_error = pe
                    break
                merged.extend(data.get("response") or [])
            games = [g for g in merged if _is_live(kind, g)]
    except Exception as exc:
        logger.warning("livestats %s fetch failed: %s", sport, exc)
        stale = _cache.get(key)
        if stale:
            return stale[1]
        return {"games": [], "plan_error": None, "fetch_error": str(exc)}
    result = {"games": games, "plan_error": plan_error}
    _cache_put(key, result)
    return result


# ── programme-title → live-game fuzzy matcher ────────────────────────

def _norm(s: str) -> str:
    s = s.replace(SUPERSCRIPT_LIVE, " ")
    s = re.sub(r"[^a-z0-9 ]+", " ", s.lower())
    return re.sub(r"\s+", " ", s).strip()


def _tokens(s: str) -> set:
    return {t for t in _norm(s).split() if len(t) > 1 and t not in _STOP}


def _team_names(kind: str, g: Dict[str, Any]) -> Tuple[str, str]:
    if kind == "mma":
        f = g.get("fighters") or {}
        return ((f.get("first") or {}).get("name") or "",
                (f.get("second") or {}).get("name") or "")
    if kind == "f1":
        return ((g.get("competition") or {}).get("name") or "", "")
    t = g.get("teams") or {}
    return ((t.get("home") or {}).get("name") or "",
            (t.get("away") or {}).get("name") or "")


def _match_score(title_tokens: set, title_norm: str, home: str, away: str) -> Tuple[float, int]:
    score, sides = 0.0, 0
    for name in (home, away):
        if not name:
            continue
        nn = _norm(name)
        if nn and nn in title_norm:
            score += 3
            sides += 1
            continue
        overlap = _tokens(name) & title_tokens
        if any(len(t) >= 3 for t in overlap):
            score += 1 + 0.5 * max(0, len(overlap) - 1)
            sides += 1
    return score, sides


def _resolve(sport: str, title: str, games: List[dict]) -> Tuple[Optional[dict], str]:
    kind = SPORTS[sport]["kind"]
    tn, tt = _norm(title), _tokens(title)
    best, best_score, best_sides = None, 0.0, 0
    for g in games:
        h, a = _team_names(kind, g)
        s, sides = _match_score(tt, tn, h, a)
        if s > best_score:
            best, best_score, best_sides = g, s, sides
    if best is not None and (best_sides >= 2 or best_score >= 3):
        return best, "matched"
    if len(games) == 1 or (kind == "f1" and games):
        return games[0], "only_live"
    return None, "no_match"


# ── normalized board payload builders ────────────────────────────────

def _shape(sport: str, found: bool = True, **kw: Any) -> Dict[str, Any]:
    cfg = SPORTS[sport]
    remaining = _quota.get(cfg["host"])
    refresh = 120
    if remaining is not None:
        if remaining < 8:
            refresh = 600
        elif remaining < 20:
            refresh = 300
    base: Dict[str, Any] = {
        "found": found,
        "sport": sport,
        "sportLabel": cfg["label"],
        "nextRefreshSecs": refresh,
        "quotaRemaining": remaining,
    }
    base.update(kw)
    return base


def _period_row(label: str, hv: Any, av: Any) -> Dict[str, str]:
    return {
        "label": label,
        "home": "-" if hv is None else str(hv),
        "away": "-" if av is None else str(av),
    }


def _num(x: Any) -> float:
    if x is None:
        return 0.0
    if isinstance(x, (int, float)):
        return float(x)
    try:
        return float(str(x).replace("%", "").strip())
    except ValueError:
        return 0.0


def _bar(label: str, hv: Any, av: Any) -> Dict[str, Any]:
    h, a = _num(hv), _num(av)
    total = h + a
    pct = 50 if total <= 0 else int(round(h * 100 / total))
    return {
        "label": label,
        "home": "0" if hv is None else str(hv),
        "away": "0" if av is None else str(av),
        "homePct": max(2, min(98, pct)),
    }


def _pp(v: Any) -> Tuple[Any, Any]:
    """Period value → (home, away).  Handles dicts and 'h-a' strings."""
    if isinstance(v, dict):
        return v.get("home"), v.get("away")
    if isinstance(v, str) and "-" in v:
        a, b = v.split("-", 1)
        return a.strip(), b.strip()
    return None, None


FOOTBALL_STAT_KEYS = [
    ("Ball Possession", "Possession"),
    ("Total Shots", "Shots"),
    ("Shots on Goal", "On Target"),
    ("Corner Kicks", "Corners"),
    ("Fouls", "Fouls"),
    ("Yellow Cards", "Yellow Cards"),
    ("Goalkeeper Saves", "Saves"),
]


async def _football_stats(fixture_id: Any, home_id: Any) -> List[Dict[str, Any]]:
    if fixture_id is None:
        return []
    key = f"fstats:{fixture_id}"
    cached = _cache_get(key, STATS_TTL)
    if cached is not None:
        return cached
    try:
        data = await _api_get(SPORTS["soccer"]["host"], "/fixtures/statistics",
                              {"fixture": fixture_id})
        resp = data.get("response") or []
    except Exception as exc:
        logger.warning("football stats failed: %s", exc)
        return []
    home_map: Dict[str, Any] = {}
    away_map: Dict[str, Any] = {}
    for side in resp:
        target = home_map if (side.get("team") or {}).get("id") == home_id else away_map
        for st in side.get("statistics") or []:
            target[str(st.get("type"))] = st.get("value")
    bars = []
    for api_key, label in FOOTBALL_STAT_KEYS:
        if api_key in home_map or api_key in away_map:
            bars.append(_bar(label, home_map.get(api_key), away_map.get(api_key)))
    _cache_put(key, bars)
    return bars


async def _board_football(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    fx = g.get("fixture") or {}
    st = fx.get("status") or {}
    teams = g.get("teams") or {}
    home_t, away_t = teams.get("home") or {}, teams.get("away") or {}
    goals = g.get("goals") or {}
    ht = (g.get("score") or {}).get("halftime") or {}
    periods: List[Dict[str, str]] = []
    if ht.get("home") is not None:
        periods.append(_period_row("1H", ht.get("home"), ht.get("away")))
        if (st.get("short") or "") not in ("1H", "HT") and goals.get("home") is not None:
            periods.append(_period_row(
                "2H",
                (goals.get("home") or 0) - (ht.get("home") or 0),
                (goals.get("away") or 0) - (ht.get("away") or 0),
            ))
    events = []
    for ev in (g.get("events") or [])[-40:]:
        t = ev.get("time") or {}
        elapsed, extra = t.get("elapsed"), t.get("extra")
        minute = ""
        if elapsed is not None:
            minute = f"{elapsed}'" + (f"+{extra}" if extra else "")
        side = "home" if (ev.get("team") or {}).get("id") == home_t.get("id") else "away"
        events.append({
            "time": minute,
            "team": side,
            "type": str(ev.get("type") or "").upper(),
            "player": (ev.get("player") or {}).get("name") or "",
            "detail": ev.get("detail") or "",
        })
    stats = await _football_stats(fx.get("id"), home_t.get("id"))
    venue = (fx.get("venue") or {}).get("name") or ""
    league = g.get("league") or {}
    return _shape(
        sport,
        fixtureId=fx.get("id"),
        league=" · ".join(x for x in [league.get("name"), league.get("round")] if x),
        venue=venue,
        status={
            "short": st.get("short") or "",
            "long": st.get("long") or "",
            "clock": f"{st.get('elapsed')}'" if st.get("elapsed") is not None else "",
            "live": True,
        },
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": goals.get("home")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": goals.get("away")},
        periods=periods,
        stats=stats,
        events=list(reversed(events)),
    )


def _generic_header(g: Dict[str, Any]) -> Tuple[dict, dict, dict, str, str]:
    gg = g.get("game") or g
    st = gg.get("status") or {}
    teams = g.get("teams") or {}
    league = g.get("league") or {}
    venue = gg.get("venue") or g.get("venue") or ""
    if isinstance(venue, dict):
        venue = venue.get("name") or ""
    return (teams.get("home") or {}, teams.get("away") or {}, st,
            str(league.get("name") or ""), str(venue))


async def _board_basketball(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    home_t, away_t, st, league, venue = _generic_header(g)
    s = g.get("scores") or {}
    h, a = s.get("home") or {}, s.get("away") or {}
    periods = []
    for key, label in [("quarter_1", "Q1"), ("quarter_2", "Q2"),
                       ("quarter_3", "Q3"), ("quarter_4", "Q4"), ("over_time", "OT")]:
        if h.get(key) is not None or a.get(key) is not None:
            periods.append(_period_row(label, h.get(key), a.get(key)))
    return _shape(
        sport, fixtureId=(g.get("game") or g).get("id"), league=league, venue=venue,
        status={"short": st.get("short") or "", "long": st.get("long") or "",
                "clock": str(st.get("timer") or ""), "live": True},
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": h.get("total")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": a.get("total")},
        periods=periods, stats=[], events=[],
    )


async def _board_baseball(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    home_t, away_t, st, league, venue = _generic_header(g)
    s = g.get("scores") or {}
    h, a = s.get("home") or {}, s.get("away") or {}
    hi, ai = h.get("innings") or {}, a.get("innings") or {}
    periods = []
    for i in range(1, 10):
        hv = hi.get(str(i), hi.get(i))
        av = ai.get(str(i), ai.get(i))
        if hv is not None or av is not None:
            periods.append(_period_row(str(i), hv, av))
    if hi.get("extra") is not None or ai.get("extra") is not None:
        periods.append(_period_row("EX", hi.get("extra"), ai.get("extra")))
    stats = []
    if h.get("hits") is not None or a.get("hits") is not None:
        stats.append(_bar("Hits", h.get("hits"), a.get("hits")))
    if h.get("errors") is not None or a.get("errors") is not None:
        stats.append(_bar("Errors", h.get("errors"), a.get("errors")))
    short = str(st.get("short") or "")
    clock = f"INN {short[2:]}" if short.startswith("IN") and len(short) > 2 else ""
    return _shape(
        sport, fixtureId=(g.get("game") or g).get("id"), league=league, venue=venue,
        status={"short": short, "long": st.get("long") or "", "clock": clock, "live": True},
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": h.get("total")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": a.get("total")},
        periods=periods, stats=stats, events=[],
    )


async def _board_hockey(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    home_t, away_t, st, league, venue = _generic_header(g)
    scores = g.get("scores") or {}
    per = g.get("periods") or {}
    periods = []
    for key, label in [("first", "P1"), ("second", "P2"), ("third", "P3"),
                       ("overtime", "OT"), ("penalties", "SO")]:
        hv, av = _pp(per.get(key))
        if hv is not None or av is not None:
            periods.append(_period_row(label, hv, av))
    return _shape(
        sport, fixtureId=(g.get("game") or g).get("id"), league=league, venue=venue,
        status={"short": st.get("short") or "", "long": st.get("long") or "",
                "clock": str(g.get("timer") or st.get("timer") or ""), "live": True},
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": scores.get("home")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": scores.get("away")},
        periods=periods, stats=[], events=[],
    )


async def _board_rugby(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    home_t, away_t, st, league, venue = _generic_header(g)
    scores = g.get("scores") or {}
    per = g.get("periods") or {}
    periods = []
    for key, label in [("first", "1H"), ("second", "2H"),
                       ("overtime", "ET"), ("second_overtime", "ET2")]:
        hv, av = _pp(per.get(key))
        if hv is not None or av is not None:
            periods.append(_period_row(label, hv, av))
    return _shape(
        sport, fixtureId=(g.get("game") or g).get("id"), league=league, venue=venue,
        status={"short": st.get("short") or "", "long": st.get("long") or "",
                "clock": str(st.get("timer") or ""), "live": True},
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": scores.get("home")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": scores.get("away")},
        periods=periods, stats=[], events=[],
    )


async def _board_nfl(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    home_t, away_t, st, league, venue = _generic_header(g)
    s = g.get("scores") or {}
    h, a = s.get("home") or {}, s.get("away") or {}
    periods = []
    for key, label in [("quarter_1", "Q1"), ("quarter_2", "Q2"),
                       ("quarter_3", "Q3"), ("quarter_4", "Q4"), ("overtime", "OT")]:
        if h.get(key) is not None or a.get(key) is not None:
            periods.append(_period_row(label, h.get(key), a.get(key)))
    return _shape(
        sport, fixtureId=(g.get("game") or g).get("id"), league=league, venue=venue,
        status={"short": st.get("short") or "", "long": st.get("long") or "",
                "clock": str(st.get("timer") or ""), "live": True},
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": h.get("total")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": a.get("total")},
        periods=periods, stats=[], events=[],
    )


async def _board_afl(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    home_t, away_t, st, league, venue = _generic_header(g)
    s = g.get("scores") or {}
    h, a = s.get("home") or {}, s.get("away") or {}
    stats = []
    if h.get("goals") is not None or a.get("goals") is not None:
        stats.append(_bar("Goals", h.get("goals"), a.get("goals")))
    if h.get("behinds") is not None or a.get("behinds") is not None:
        stats.append(_bar("Behinds", h.get("behinds"), a.get("behinds")))
    return _shape(
        sport, fixtureId=(g.get("game") or g).get("id"),
        league=league or "AFL", venue=venue,
        status={"short": st.get("short") or "", "long": st.get("long") or "",
                "clock": str(st.get("timer") or ""), "live": True},
        home={"name": home_t.get("name") or "", "logo": home_t.get("logo") or "",
              "score": h.get("score")},
        away={"name": away_t.get("name") or "", "logo": away_t.get("logo") or "",
              "score": a.get("score")},
        periods=[], stats=stats, events=[],
    )


async def _f1_rankings(race_id: Any) -> List[Dict[str, Any]]:
    if race_id is None:
        return []
    key = f"f1rank:{race_id}"
    cached = _cache_get(key, STATS_TTL)
    if cached is not None:
        return cached
    try:
        data = await _api_get(SPORTS["f1"]["host"], "/rankings/races", {"race": race_id})
        rows = []
        for r in (data.get("response") or [])[:12]:
            rows.append({
                "pos": r.get("position"),
                "name": (r.get("driver") or {}).get("name") or "",
                "team": (r.get("team") or {}).get("name") or "",
                "detail": str(r.get("time") or ""),
            })
        _cache_put(key, rows)
        return rows
    except Exception as exc:
        logger.warning("f1 rankings failed: %s", exc)
        return []


async def _board_f1(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    comp = (g.get("competition") or {}).get("name") or "Grand Prix"
    circuit = g.get("circuit") or {}
    laps = g.get("laps") or {}
    clock = ""
    if laps.get("current"):
        clock = f"LAP {laps.get('current')}/{laps.get('total') or '?'}"
    leaderboard = await _f1_rankings(g.get("id"))
    return _shape(
        sport, fixtureId=g.get("id"), league=comp,
        venue=circuit.get("name") or "",
        status={"short": str(g.get("status") or ""), "long": str(g.get("status") or ""),
                "clock": clock, "live": True},
        home={"name": comp, "logo": circuit.get("image") or "", "score": None},
        away={"name": "", "logo": "", "score": None},
        periods=[], stats=[], events=[], leaderboard=leaderboard,
    )


async def _board_mma(sport: str, g: Dict[str, Any]) -> Dict[str, Any]:
    f = g.get("fighters") or {}
    first, second = f.get("first") or {}, f.get("second") or {}
    st = g.get("status") or {}
    return _shape(
        sport, fixtureId=g.get("id"),
        league=str(g.get("category") or "MMA"), venue="",
        status={"short": st.get("short") or "", "long": st.get("long") or "",
                "clock": "", "live": True},
        home={"name": first.get("name") or "", "logo": first.get("logo") or "", "score": None},
        away={"name": second.get("name") or "", "logo": second.get("logo") or "", "score": None},
        periods=[], stats=[], events=[],
    )


_KIND_BUILDERS = {
    "football": _board_football,
    "basketball": _board_basketball,
    "baseball": _board_baseball,
    "hockey": _board_hockey,
    "rugby": _board_rugby,
    "nfl": _board_nfl,
    "afl": _board_afl,
    "f1": _board_f1,
    "mma": _board_mma,
}


@router.get("/board")
async def board(
    sport: str = Query(..., description="Sport bucket id from the WhatsOn hub"),
    title: str = Query(..., min_length=2, description="EPG programme title"),
):
    sport = sport.lower().strip()
    if sport not in SPORTS:
        raise HTTPException(400, f"unsupported sport '{sport}'")
    if not APISPORTS_KEY:
        raise HTTPException(500, "APISPORTS_KEY not configured")

    live = await _live_games(sport)
    label = SPORTS[sport]["label"]
    if live.get("plan_error"):
        return _shape(sport, found=False, reason="plan_locked",
                      message=f"{label} live data needs an API-Sports plan upgrade "
                              f"— {live['plan_error']}")
    games = live.get("games") or []
    if not games:
        if live.get("fetch_error"):
            return _shape(sport, found=False, reason="fetch_error",
                          message="Can't reach the live data feed right now — retrying")
        return _shape(sport, found=False, reason="no_live_games",
                      message=f"No live {label} match in the data feed right now")

    g, how = _resolve(sport, title, games)
    if g is None:
        return _shape(sport, found=False, reason="no_match", liveCount=len(games),
                      message=f"{len(games)} live {label} matches in the feed — "
                              f"none matched this programme yet")

    payload = await _KIND_BUILDERS[SPORTS[sport]["kind"]](sport, g)
    payload["matched"] = how
    return payload


@router.get("/quota")
async def quota():
    """In-memory view of the daily quota per sport (no API calls)."""
    return {"quota": {s: _quota.get(cfg["host"]) for s, cfg in SPORTS.items()}}
