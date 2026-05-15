"""
TheSportsDB integration — fixtures, badges, venues, posters.

Strategy:
  • Free TheSportsDB test key (123) returns SAMPLE data per call.  Each
    league endpoint returns ~15 events; each `eventsday` call returns
    ~3 events per sport.  We compensate by aggressively fan-out fetching
    a curated list of top leagues + the daily endpoint per sport, then
    caching the merged result for 30 minutes.

  • Returned shape is purposely structured for direct render — every
    field that the UI needs (badges, kickoff timestamp, venue, score)
    is normalised here so the frontend stays dumb-fast.

  • All endpoints are cached server-side; the response includes
    `cached: true|false` so we can verify in DevTools.
"""

from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional

import httpx
from fastapi import APIRouter, Query

logger = logging.getLogger("vesper.sportsdb")

router = APIRouter(prefix="/api/sportsdb")

SPORTSDB_BASE = "https://www.thesportsdb.com/api/v1/json/123"

# ---------------------------------------------------------------------------
# Curated top leagues per sport.  Hand-picked to maximise marquee coverage.
# `season` is the most recent active season slug TheSportsDB expects.
# ---------------------------------------------------------------------------
TOP_LEAGUES: List[Dict[str, Any]] = [
    # Soccer ---------------------------------------------------------------
    {"id": "4328", "name": "Premier League",        "sport": "Soccer",            "country": "England",   "season": "2025-2026"},
    {"id": "4335", "name": "La Liga",               "sport": "Soccer",            "country": "Spain",     "season": "2025-2026"},
    {"id": "4332", "name": "Serie A",               "sport": "Soccer",            "country": "Italy",     "season": "2025-2026"},
    {"id": "4331", "name": "Bundesliga",            "sport": "Soccer",            "country": "Germany",   "season": "2025-2026"},
    {"id": "4334", "name": "Ligue 1",               "sport": "Soccer",            "country": "France",    "season": "2025-2026"},
    {"id": "4480", "name": "Champions League",      "sport": "Soccer",            "country": "Europe",    "season": "2025-2026"},
    {"id": "4481", "name": "Europa League",         "sport": "Soccer",            "country": "Europe",    "season": "2025-2026"},
    {"id": "4346", "name": "MLS",                   "sport": "Soccer",            "country": "USA",       "season": "2025"},
    {"id": "4329", "name": "Championship",          "sport": "Soccer",            "country": "England",   "season": "2025-2026"},
    {"id": "4396", "name": "FA Cup",                "sport": "Soccer",            "country": "England",   "season": "2025-2026"},
    # American Football ----------------------------------------------------
    {"id": "4391", "name": "NFL",                   "sport": "American Football", "country": "USA",       "season": "2025"},
    # Basketball -----------------------------------------------------------
    {"id": "4387", "name": "NBA",                   "sport": "Basketball",        "country": "USA",       "season": "2025-2026"},
    {"id": "4607", "name": "WNBA",                  "sport": "Basketball",        "country": "USA",       "season": "2025"},
    {"id": "4434", "name": "Australian NBL",        "sport": "Basketball",        "country": "Australia", "season": "2025-2026"},
    {"id": "4423", "name": "EuroLeague",            "sport": "Basketball",        "country": "Europe",    "season": "2025-2026"},
    # Ice Hockey -----------------------------------------------------------
    {"id": "4380", "name": "NHL",                   "sport": "Ice Hockey",        "country": "USA",       "season": "2025-2026"},
    # Baseball -------------------------------------------------------------
    {"id": "4424", "name": "MLB",                   "sport": "Baseball",          "country": "USA",       "season": "2025"},
    {"id": "4830", "name": "NPB",                   "sport": "Baseball",          "country": "Japan",     "season": "2025"},
    # Rugby ----------------------------------------------------------------
    {"id": "4502", "name": "Rugby Union: Premiership",  "sport": "Rugby",         "country": "England",   "season": "2025-2026"},
    {"id": "4446", "name": "Rugby League: NRL",         "sport": "Rugby",         "country": "Australia", "season": "2025"},
    {"id": "4574", "name": "Rugby League: Super League","sport": "Rugby",         "country": "England",   "season": "2025"},
    # AFL ------------------------------------------------------------------
    {"id": "4449", "name": "AFL",                   "sport": "Australian Football","country": "Australia","season": "2025"},
    # Cricket --------------------------------------------------------------
    {"id": "4548", "name": "Indian Premier League", "sport": "Cricket",           "country": "India",     "season": "2026"},
    {"id": "4795", "name": "ICC Test Matches",      "sport": "Cricket",           "country": "International","season": "2025"},
    {"id": "4823", "name": "Big Bash League",       "sport": "Cricket",           "country": "Australia", "season": "2025-2026"},
    # Motorsport -----------------------------------------------------------
    {"id": "4370", "name": "Formula 1",             "sport": "Motorsport",        "country": "International","season": "2025"},
    {"id": "4393", "name": "MotoGP",                "sport": "Motorsport",        "country": "International","season": "2025"},
    {"id": "4453", "name": "NASCAR Cup Series",     "sport": "Motorsport",        "country": "USA",       "season": "2025"},
    {"id": "4407", "name": "IndyCar",               "sport": "Motorsport",        "country": "USA",       "season": "2025"},
    # Combat ---------------------------------------------------------------
    {"id": "4443", "name": "UFC",                   "sport": "MMA",               "country": "International","season": "2025"},
    {"id": "4630", "name": "Boxing",                "sport": "Boxing",            "country": "International","season": "2025"},
    # Tennis ---------------------------------------------------------------
    {"id": "4464", "name": "ATP Tour",              "sport": "Tennis",            "country": "International","season": "2025"},
    {"id": "4391", "name": "WTA Tour",              "sport": "Tennis",            "country": "International","season": "2025"},
    # Golf -----------------------------------------------------------------
    {"id": "4425", "name": "PGA Tour",              "sport": "Golf",              "country": "USA",       "season": "2025"},
    {"id": "4596", "name": "DP World Tour",         "sport": "Golf",              "country": "International","season": "2025"},
]

# Sport icon hints — frontend uses these to pick a lucide icon.
SPORT_ICONS = {
    "Soccer":               {"emoji": "⚽", "color": "#5DC8FF"},
    "American Football":    {"emoji": "🏈", "color": "#FF8855"},
    "Basketball":           {"emoji": "🏀", "color": "#FFA844"},
    "Ice Hockey":           {"emoji": "🏒", "color": "#8DC9FF"},
    "Baseball":             {"emoji": "⚾", "color": "#FFE08A"},
    "Rugby":                {"emoji": "🏉", "color": "#7AE2A8"},
    "Australian Football":  {"emoji": "🏉", "color": "#FF6B7A"},
    "Cricket":              {"emoji": "🏏", "color": "#A7F0BA"},
    "Motorsport":           {"emoji": "🏁", "color": "#FF4D5E"},
    "MMA":                  {"emoji": "🥊", "color": "#FF4D5E"},
    "Boxing":               {"emoji": "🥊", "color": "#FFC850"},
    "Tennis":               {"emoji": "🎾", "color": "#D7FF6B"},
    "Golf":                 {"emoji": "⛳", "color": "#A7F0BA"},
}

# ---------------------------------------------------------------------------
# In-memory TTL cache
# ---------------------------------------------------------------------------
_CACHE: Dict[str, tuple[float, Any]] = {}

def _cache_get(key: str) -> Any | None:
    v = _CACHE.get(key)
    if not v:
        return None
    exp, val = v
    if time.time() > exp:
        _CACHE.pop(key, None)
        return None
    return val

def _cache_set(key: str, val: Any, ttl: int) -> None:
    _CACHE[key] = (time.time() + ttl, val)


# ---------------------------------------------------------------------------
# HTTP helper
# ---------------------------------------------------------------------------
async def _fetch(client: httpx.AsyncClient, url: str) -> Optional[Dict[str, Any]]:
    try:
        r = await client.get(url, timeout=8.0)
        if r.status_code != 200:
            return None
        return r.json()
    except Exception as exc:
        logger.debug("sportsdb fetch failed url=%s err=%s", url, exc)
        return None


# ---------------------------------------------------------------------------
# Normalisation
# ---------------------------------------------------------------------------
def _to_ts(date_str: Optional[str], time_str: Optional[str]) -> int:
    """Convert TheSportsDB date+time (UTC) into a unix timestamp."""
    if not date_str:
        return 0
    try:
        if time_str and time_str != "00:00:00":
            dt = datetime.strptime(f"{date_str} {time_str}", "%Y-%m-%d %H:%M:%S")
        else:
            dt = datetime.strptime(date_str, "%Y-%m-%d")
        return int(dt.replace(tzinfo=timezone.utc).timestamp())
    except Exception:
        return 0


def _normalise_event(ev: Dict[str, Any]) -> Dict[str, Any]:
    """Project an event into the lean shape the frontend wants."""
    ts = _to_ts(ev.get("dateEvent"), ev.get("strTime"))
    home = (ev.get("strHomeTeam") or "").strip()
    away = (ev.get("strAwayTeam") or "").strip()
    title = ev.get("strEvent") or (f"{home} vs {away}" if home and away else "")
    status = (ev.get("strStatus") or "").strip()
    # Treat anything with both scores set and status "Finished/Final" as final.
    finished = bool(status and any(w in status.lower() for w in ("finish", "final", "ended", "ft")))
    return {
        "id":          str(ev.get("idEvent") or ""),
        "title":       title,
        "league":      ev.get("strLeague") or "",
        "leagueId":    str(ev.get("idLeague") or ""),
        "leagueBadge": ev.get("strLeagueBadge") or "",
        "sport":       ev.get("strSport") or "",
        "ts":          ts,
        "season":      ev.get("strSeason") or "",
        "round":       ev.get("intRound") or "",
        "home":        home,
        "homeBadge":   ev.get("strHomeTeamBadge") or "",
        "away":        away,
        "awayBadge":   ev.get("strAwayTeamBadge") or "",
        "homeScore":   ev.get("intHomeScore") or "",
        "awayScore":   ev.get("intAwayScore") or "",
        "venue":       ev.get("strVenue") or "",
        "country":     ev.get("strCountry") or "",
        "city":        ev.get("strCity") or "",
        "thumb":       ev.get("strThumb") or "",
        "poster":      ev.get("strPoster") or "",
        "status":      status,
        "finished":    finished,
        "video":       ev.get("strVideo") or "",
    }


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.get("/leagues")
async def get_leagues():
    """Returns the curated top-leagues list + the supported sport icon map."""
    return {
        "leagues": TOP_LEAGUES,
        "sports": [
            {"name": s, **SPORT_ICONS[s]}
            for s in sorted({lg["sport"] for lg in TOP_LEAGUES}, key=str.lower)
        ],
    }


@router.get("/fixtures")
async def get_fixtures(refresh: int = Query(0, description="set 1 to bypass cache")):
    """
    Combined upcoming fixtures across all curated leagues.

    Returns ~30-60 events sorted by kickoff time, grouped by sport+league.
    Caches 30 minutes (TheSportsDB rate-limits the free key heavily).

    Shape:
      {
        cached: bool,
        fetched_at: <unix sec>,
        events: [ <normalised event>, … ],
        bySport: {Soccer: [<event>], Basketball: […], …},
        byLeague: {<leagueId>: {name, badge, sport, events:[…]}, …},
      }
    """
    cache_key = "sportsdb:fixtures:v3"
    if not refresh:
        hit = _cache_get(cache_key)
        if hit:
            return {**hit, "cached": True}

    # Build a 5-day window of date strings + the curated sport list.
    today0 = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    date_strs = [(today0 + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(0, 5)]
    distinct_sports = sorted({lg["sport"] for lg in TOP_LEAGUES})

    async with httpx.AsyncClient(
        headers={"User-Agent": "VesperTV/2.0 (sports-guide)"},
    ) as client:
        # Fan-out:
        #   1. eventsnextleague per league   — up to 15 events each (next fixtures).
        #   2. eventsseason per league        — up to 15 events each (whole season sample).
        #   3. eventsday × 5 days × per sport — up to 3 events each (cross-sport breadth).
        # Each free-tier call returns SAMPLE data so we fan-out wide and dedupe later.
        next_tasks = [
            _fetch(client, f"{SPORTSDB_BASE}/eventsnextleague.php?id={lg['id']}")
            for lg in TOP_LEAGUES
        ]
        season_tasks = [
            _fetch(client, f"{SPORTSDB_BASE}/eventsseason.php?id={lg['id']}&s={lg['season']}")
            for lg in TOP_LEAGUES
        ]
        day_tasks = []
        for date_str in date_strs:
            # No-sport-filter once per day to catch random globally-popular events.
            day_tasks.append(_fetch(client, f"{SPORTSDB_BASE}/eventsday.php?d={date_str}"))
            for sport in distinct_sports:
                # URL-encode space → %20
                s_enc = sport.replace(' ', '%20')
                day_tasks.append(_fetch(client, f"{SPORTSDB_BASE}/eventsday.php?d={date_str}&s={s_enc}"))
        results = await asyncio.gather(
            *next_tasks, *season_tasks, *day_tasks, return_exceptions=True
        )

    nowSec = int(time.time())
    horizon = nowSec + 14 * 24 * 3600   # 14 days forward
    past_window = nowSec - 6 * 3600     # show events that started in last 6 h (live)

    seen_ids: set[str] = set()
    events: List[Dict[str, Any]] = []
    for r in results:
        if not isinstance(r, dict):
            continue
        evs = r.get("events") or []
        for raw in evs:
            n = _normalise_event(raw)
            if not n["id"] or n["id"] in seen_ids:
                continue
            # Skip events with no kickoff timestamp (no date data = unusable).
            if not n["ts"]:
                continue
            if n["ts"] < past_window:   # too old
                continue
            if n["ts"] > horizon:
                continue
            seen_ids.add(n["id"])
            events.append(n)

    events.sort(key=lambda e: e["ts"])

    # Group
    by_sport: Dict[str, List[Dict[str, Any]]] = {}
    by_league: Dict[str, Dict[str, Any]] = {}
    for e in events:
        s = e["sport"] or "Other"
        by_sport.setdefault(s, []).append(e)
        lid = e["leagueId"]
        if lid:
            bl = by_league.setdefault(lid, {
                "id": lid,
                "name": e["league"],
                "badge": e["leagueBadge"],
                "sport": s,
                "events": [],
            })
            bl["events"].append(e)

    payload = {
        "cached":      False,
        "fetched_at":  nowSec,
        "events":      events,
        "bySport":     by_sport,
        "byLeague":    by_league,
        "sportsMeta": [
            {"name": s, **SPORT_ICONS.get(s, {"emoji": "🏆", "color": "#5DC8FF"}),
             "count": len(by_sport.get(s, []))}
            for s in sorted(by_sport.keys(), key=str.lower)
        ],
    }
    _cache_set(cache_key, payload, 30 * 60)  # 30 min
    return payload


@router.get("/league-season")
async def get_league_season(
    league_id: str = Query(..., min_length=1),
    season: str = Query("", description="optional explicit season e.g. 2025-2026"),
):
    """Deeper dive into a single league for season-long fixtures."""
    if not season:
        # Look up default season from the curated list.
        for lg in TOP_LEAGUES:
            if lg["id"] == league_id:
                season = lg["season"]
                break
    cache_key = f"sportsdb:season:{league_id}:{season}"
    hit = _cache_get(cache_key)
    if hit:
        return {**hit, "cached": True}

    async with httpx.AsyncClient(
        headers={"User-Agent": "VesperTV/2.0 (sports-guide)"},
    ) as client:
        data = await _fetch(client, f"{SPORTSDB_BASE}/eventsseason.php?id={league_id}&s={season}")

    events_raw = (data or {}).get("events") or []
    events = [_normalise_event(e) for e in events_raw if e.get("idEvent")]
    events = [e for e in events if e["ts"]]  # filter no-timestamp
    events.sort(key=lambda e: e["ts"])

    payload = {
        "cached":     False,
        "league_id":  league_id,
        "season":     season,
        "events":     events,
    }
    _cache_set(cache_key, payload, 6 * 3600)  # 6 h
    return payload
