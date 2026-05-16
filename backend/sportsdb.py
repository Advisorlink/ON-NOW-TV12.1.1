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

# ---------------------------------------------------------------------------
# League-to-broadcaster heuristics
# ---------------------------------------------------------------------------
#
# TheSportsDB's free tier doesn't return broadcast info, but for most
# top-flight leagues the channels are stable enough that a curated
# static mapping is more useful than the empty list we currently
# ship.  These are the UK + USA + AU channels that carry each league —
# the user's setup is UK/AU so we lead with those.  The frontend can
# show the FIRST entry as "Watch on …" with the rest as a tooltip.
#
# Match keys are matched against the lowercased league name with
# partial-substring matching (so "English Premier League" and
# "Premier League" both hit).  Generic catch-alls live in the
# _SPORT_BROADCAST table below for everything outside a known
# league.
_LEAGUE_BROADCASTS: Dict[str, List[str]] = {
    # Football / soccer
    "premier league":            ["Sky Sports", "TNT Sports", "Amazon Prime", "NBC Sports"],
    "english premier league":    ["Sky Sports", "TNT Sports", "Amazon Prime", "NBC Sports"],
    "championship":              ["Sky Sports", "ESPN+"],
    "league one":                ["Sky Sports"],
    "league two":                ["Sky Sports"],
    "fa cup":                    ["BBC One", "ITV", "ESPN+"],
    "efl cup":                   ["Sky Sports"],
    "uefa champions league":     ["TNT Sports", "Paramount+"],
    "uefa europa league":        ["TNT Sports", "Paramount+"],
    "uefa conference league":    ["TNT Sports", "Paramount+"],
    "la liga":                   ["Premier Sports", "ESPN+", "Paramount+"],
    "serie a":                   ["TNT Sports", "Paramount+"],
    "bundesliga":                ["Sky Sports", "ESPN+"],
    "ligue 1":                   ["TNT Sports", "Paramount+"],
    "eredivisie":                ["Premier Sports", "ESPN+"],
    "scottish premiership":      ["Sky Sports", "Premier Sports"],
    "mls":                       ["Apple TV+"],
    "a-league":                  ["Channel 10", "Paramount+", "Network 10"],
    "fifa world cup":            ["BBC One", "ITV", "FOX", "Telemundo"],
    "fifa club world cup":       ["DAZN", "TNT Sports"],

    # American football
    "nfl":                       ["Sky Sports", "DAZN", "CBS", "FOX", "NBC", "ESPN"],
    "college football":          ["ESPN", "ABC", "CBS"],

    # Basketball
    "nba":                       ["Sky Sports", "TNT Sports", "ESPN", "TNT", "ABC"],
    "wnba":                      ["ESPN", "ABC"],
    "ncaa basketball":           ["CBS", "TNT Sports", "TBS"],

    # Baseball
    "mlb":                       ["BT Sport", "TNT Sports", "ESPN", "FOX", "TBS"],

    # Hockey
    "nhl":                       ["ESPN", "ABC", "TNT Sports", "TNT"],

    # Cricket
    "icc cricket world cup":     ["Sky Sports", "Star Sports"],
    "the ashes":                 ["Sky Sports", "BBC Test Match Special"],
    "ipl":                       ["Sky Sports", "Star Sports", "Disney+ Hotstar"],
    "indian premier league":     ["Sky Sports", "Star Sports", "Disney+ Hotstar"],
    "big bash":                  ["BT Sport", "Channel 7", "Foxtel"],
    "cricket":                   ["Sky Sports", "BT Sport"],

    # Rugby
    "six nations":               ["BBC", "ITV"],
    "premiership rugby":         ["TNT Sports"],
    "rugby world cup":           ["ITV"],
    "super rugby":               ["Sky Sport NZ"],
    "j1 league":                 ["Premier Sports", "DAZN Japan"],
    "j-league":                  ["Premier Sports", "DAZN Japan"],
    "brasileirão":               ["Premier Sports", "FOX Deportes"],
    "brasileirao":               ["Premier Sports", "FOX Deportes"],
    "campeonato brasileiro":     ["Premier Sports", "FOX Deportes"],
    "primeira liga":             ["Sky Sports", "GOL TV"],
    "süper lig":                 ["S Sport", "beIN Sports"],
    "super lig":                 ["S Sport", "beIN Sports"],
    "saudi pro league":          ["DAZN", "SSC"],
    "chinese super league":      ["CCTV-5"],
    "argentine primera":         ["ESPN", "TyC Sports"],
    "liga mx":                   ["TUDN", "ESPN Deportes"],
    "ncaa baseball":             ["ESPN", "SEC Network"],
    "ncaa football":             ["ESPN", "ABC", "CBS"],

    # Motorsport
    "formula 1":                 ["Sky Sports F1", "Channel 4"],
    "formula one":               ["Sky Sports F1", "Channel 4"],
    "f1":                        ["Sky Sports F1", "Channel 4"],
    "motogp":                    ["BT Sport", "TNT Sports"],
    "nascar":                    ["FOX Sports", "NBC Sports"],
    "indycar":                   ["NBC Sports"],

    # Tennis
    "wimbledon":                 ["BBC One", "BBC Two", "ESPN"],
    "us open":                   ["Sky Sports", "ESPN"],
    "australian open":           ["Channel 9", "Eurosport"],
    "french open":               ["Eurosport", "TNT Sports", "NBC Sports"],
    "atp tour":                  ["Sky Sports", "Tennis Channel", "Eurosport"],
    "atp":                       ["Sky Sports", "Tennis Channel", "Eurosport"],
    "wta tour":                  ["Sky Sports", "Tennis Channel", "Eurosport"],
    "wta":                       ["Sky Sports", "Tennis Channel", "Eurosport"],

    # Aussie rules
    "afl":                       ["Channel 7", "Foxtel", "Kayo Sports"],
    "nrl":                       ["Channel 9", "Foxtel", "Kayo Sports"],

    # Boxing / UFC
    "ufc":                       ["TNT Sports", "BT Sport", "ESPN+"],
    "boxing":                    ["DAZN", "Sky Sports Box Office"],

    # Golf
    "the masters":               ["Sky Sports Golf", "CBS"],
    "the open":                  ["Sky Sports Golf", "NBC"],
    "pga tour":                  ["Sky Sports Golf", "NBC", "CBS"],
}

# Catch-all broadcasters per sport — used when the league name didn't
# match any specific entry above.  Less precise but better than the
# empty list we used to ship.
_SPORT_BROADCAST: Dict[str, List[str]] = {
    "soccer":         ["Sky Sports", "TNT Sports"],
    "american football": ["Sky Sports", "DAZN"],
    "basketball":     ["Sky Sports", "TNT Sports"],
    "baseball":       ["TNT Sports"],
    "ice hockey":     ["Premier Sports"],
    "rugby":          ["TNT Sports"],
    "cricket":        ["Sky Sports"],
    "tennis":         ["Sky Sports", "Eurosport"],
    "motorsport":     ["Sky Sports F1"],
    "fighting":       ["TNT Sports"],
    "golf":           ["Sky Sports Golf"],
}


def _broadcasts_for(league_name: str, sport: str) -> List[str]:
    """Return a best-guess list of broadcasters for the given league.

    Strategy: substring-match against `_LEAGUE_BROADCASTS` first
    (most specific), fall back to `_SPORT_BROADCAST` for the sport
    category.  Empty list if neither resolved.
    """
    league_lower = (league_name or "").lower()
    if league_lower:
        for key, channels in _LEAGUE_BROADCASTS.items():
            if key in league_lower:
                return channels
    sport_lower = (sport or "").lower()
    if sport_lower:
        for key, channels in _SPORT_BROADCAST.items():
            if key in sport_lower:
                return channels
    return []


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
    # Rugby Union ----------------------------------------------------------
    {"id": "4414", "name": "English Prem Rugby",       "sport": "Rugby Union",      "country": "England",   "season": "2025-2026"},
    # Rugby League ---------------------------------------------------------
    {"id": "4416", "name": "NRL",                      "sport": "Rugby League",     "country": "Australia", "season": "2025"},
    {"id": "4415", "name": "Super League",             "sport": "Rugby League",     "country": "England",   "season": "2025"},
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
    {"id": "4392", "name": "WTA Tour",              "sport": "Tennis",            "country": "International","season": "2025"},
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
    "Rugby League":         {"emoji": "🏉", "color": "#FF6BCB"},
    "Rugby Union":          {"emoji": "🏉", "color": "#7AE2A8"},
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
# In-memory TTL cache + disk persistence (survives backend restarts).
# ---------------------------------------------------------------------------
_CACHE: Dict[str, tuple[float, Any]] = {}
_DISK_CACHE_PATH = "/tmp/onnowtv-sportsdb-cache.json"


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
    # Best-effort disk persistence so cold-start serves data instantly.
    try:
        import json as _json
        snapshot = {
            k: {"expires_at": exp, "value": v}
            for k, (exp, v) in _CACHE.items()
        }
        with open(_DISK_CACHE_PATH, "w") as fh:
            _json.dump(snapshot, fh)
    except Exception as exc:
        logger.debug("sportsdb cache disk-write failed: %s", exc)


def _load_disk_cache() -> None:
    """Hydrate in-memory cache from disk on import."""
    try:
        import json as _json
        import os
        if not os.path.exists(_DISK_CACHE_PATH):
            return
        with open(_DISK_CACHE_PATH) as fh:
            snapshot = _json.load(fh)
        nowSec = time.time()
        loaded = 0
        for k, entry in (snapshot or {}).items():
            exp = entry.get("expires_at", 0)
            if exp > nowSec:
                _CACHE[k] = (exp, entry.get("value"))
                loaded += 1
        if loaded:
            logger.info("sportsdb: loaded %d cached entries from disk.", loaded)
    except Exception as exc:
        logger.debug("sportsdb cache disk-load failed: %s", exc)


_load_disk_cache()


# ---------------------------------------------------------------------------
# HTTP helper — concurrency-limited, 429-aware, soft-fail.
# Throttled to 2 concurrent + 400ms pacing to stay well under TheSportsDB's
# free-tier limit (~30 requests / minute).
# A separate, slower lock is used by the background enrichment task so it
# never starves foreground requests of fetch slots.
# ---------------------------------------------------------------------------
_SEM = asyncio.Semaphore(2)
_BG_SEM = asyncio.Semaphore(1)
_FETCH_STATS = {"ok": 0, "fail": 0, "rate_limited": 0}


async def _fetch(client: httpx.AsyncClient, url: str, *, bg: bool = False) -> Optional[Dict[str, Any]]:
    sem = _BG_SEM if bg else _SEM
    pace = 1.2 if bg else 0.40
    async with sem:
        await asyncio.sleep(pace)
        try:
            r = await client.get(url, timeout=10.0)
            if r.status_code == 429:
                _FETCH_STATS["rate_limited"] += 1
                logger.warning("sportsdb 429 throttled: %s", url[:120])
                return None
            if r.status_code != 200:
                _FETCH_STATS["fail"] += 1
                logger.warning("sportsdb non-200 %s for %s", r.status_code, url[:120])
                return None
            _FETCH_STATS["ok"] += 1
            return r.json()
        except Exception as exc:
            _FETCH_STATS["fail"] += 1
            logger.warning("sportsdb fetch failed url=%s err=%s", url[:120], exc)
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


def _classify_rugby(sport: str, league: str) -> str:
    """TheSportsDB lumps Rugby League and Rugby Union under `Rugby`.  Split
    them so the UI can colour-code and filter them as distinct sports."""
    if sport != "Rugby":
        return sport
    lg = (league or "").lower()
    league_keywords = ("rugby league", "nrl", "super league",
                       "state of origin", "challenge cup")
    if any(k in lg for k in league_keywords):
        return "Rugby League"
    return "Rugby Union"


def _normalise_event(ev: Dict[str, Any]) -> Dict[str, Any]:
    """Project an event into the lean shape the frontend wants."""
    ts = _to_ts(ev.get("dateEvent"), ev.get("strTime"))
    home = (ev.get("strHomeTeam") or "").strip()
    away = (ev.get("strAwayTeam") or "").strip()
    title = ev.get("strEvent") or (f"{home} vs {away}" if home and away else "")
    status = (ev.get("strStatus") or "").strip()
    # Treat anything with both scores set and status "Finished/Final" as final.
    finished = bool(status and any(w in status.lower() for w in ("finish", "final", "ended", "ft")))
    sport_raw = ev.get("strSport") or ""
    league_name = ev.get("strLeague") or ""
    sport = _classify_rugby(sport_raw, league_name)
    return {
        "id":          str(ev.get("idEvent") or ""),
        "title":       title,
        "league":      league_name,
        "leagueId":    str(ev.get("idLeague") or ""),
        "leagueBadge": ev.get("strLeagueBadge") or "",
        "sport":       sport,
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

    Returns ~30-100 events sorted by kickoff time, grouped by sport+league.
    Caches 30 minutes (TheSportsDB rate-limits the free key heavily).

    Lean fan-out to stay under TheSportsDB free-tier rate-limits:
      • eventsnextleague per curated league (next fixtures)  — 35 calls
      • eventsday no-sport-filter × 3 days                    — 3 calls
      • eventsday × 3 days × <sport name> for the top 8 sports — 24 calls
    Total ≤ 62 outbound requests, throttled by a Semaphore to 6 concurrent.

    Stale-while-revalidate: if the fan-out fails to produce events (rate
    limit / network), serve the previously cached payload rather than
    poisoning the cache with an empty body.

    Shape:
      {
        cached: bool,
        fetched_at: <unix sec>,
        events: [ <normalised event>, … ],
        bySport: {Soccer: [<event>], Basketball: […], …},
        byLeague: {<leagueId>: {name, badge, sport, events:[…]}, …},
        upstream: {ok:int, fail:int, rate_limited:int}
      }
    """
    cache_key = "sportsdb:fixtures:v9"
    survivor_key = "sportsdb:survivor:v1"
    cached = _cache_get(cache_key)

    # Seed the survivor cache from the main cache's TheSportsDB events.
    # This protects NRL/IPL from disappearing during rate-limit storms — they
    # live in the survivor cache for 24h, longer than the 30 min main TTL.
    if cached and not _cache_get(survivor_key):
        prior_sportsdb = [e for e in (cached.get("events") or [])
                          if e.get("source") == "sportsdb"]
        if prior_sportsdb:
            _cache_set(survivor_key, prior_sportsdb, 24 * 3600)
            logger.info("sportsdb: seeded survivor cache with %d events.", len(prior_sportsdb))

    if not refresh and cached:
        return {**cached, "cached": True}

    # Reset fetch stats for this call (best-effort, racy under concurrent loads)
    _FETCH_STATS.update({"ok": 0, "fail": 0, "rate_limited": 0})

    # Cold-load fan-out — stays under TheSportsDB's free-tier rate-limit
    # (~30 requests/minute) by capping volume to ~20 calls total.
    #   • eventsday × 3 days, no sport filter        — 3 calls   (broad)
    #   • eventsday × 3 days × top 4 sports          — 12 calls  (per-sport breadth)
    #   • eventsnextleague for ~10 marquee leagues   — see MARQUEE_FETCH below
    # Total ≤ 25 outbound, completes in ≤ 15s with 2-concurrent + 400ms pacing.
    today0 = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    date_strs = [(today0 + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(0, 3)]
    primary_sports = ["Soccer", "Basketball", "Ice Hockey", "American Football"]

    # 11 marquee leagues — top global revenue / global reach + Australian
    # Rugby League (NRL) at the user's request.
    MARQUEE_FETCH = ["4328", "4335", "4332", "4331", "4480",      # EPL/LaLiga/SerieA/Bund/UCL
                     "4391", "4387", "4380", "4424", "4370",      # NFL/NBA/NHL/MLB/F1
                     "4416"]                                       # NRL (AU Rugby League)

    async with httpx.AsyncClient(
        headers={"User-Agent": "VesperTV/2.0 (sports-guide)"},
        http2=False,
    ) as client:
        next_tasks = [
            _fetch(client, f"{SPORTSDB_BASE}/eventsnextleague.php?id={lid}")
            for lid in MARQUEE_FETCH
        ]
        day_tasks = [
            _fetch(client, f"{SPORTSDB_BASE}/eventsday.php?d={d}")
            for d in date_strs
        ]
        sport_tasks = []
        for d in date_strs:
            for sport in primary_sports:
                s_enc = sport.replace(' ', '%20')
                sport_tasks.append(
                    _fetch(client, f"{SPORTSDB_BASE}/eventsday.php?d={d}&s={s_enc}")
                )
        # Kick off ESPN in parallel — ESPN has no rate-limit + gives us live
        # scores + dozens of additional leagues (no-NRL/no-IPL gaps are
        # backfilled by TheSportsDB above).
        from espn import fetch_all_events as _fetch_espn_events
        espn_future = asyncio.create_task(_fetch_espn_events(days_forward=3))
        sportsdb_results = await asyncio.gather(
            *next_tasks, *day_tasks, *sport_tasks, return_exceptions=True
        )
        try:
            espn_events = await espn_future
        except Exception as exc:
            logger.warning("espn fan-out failed: %s", exc)
            espn_events = []

    nowSec = int(time.time())
    horizon = nowSec + 14 * 24 * 3600   # 14 days forward
    past_window = nowSec - 6 * 3600     # show events that started in last 6 h (live)

    seen_ids: set[str] = set()
    seen_titles: set[tuple] = set()
    events: List[Dict[str, Any]] = []

    def _title_key(e: Dict[str, Any]) -> Optional[tuple]:
        # Round timestamp to nearest 30 min so slightly-different sources still
        # collide on the same fixture.
        ts_bucket = (e["ts"] // 1800) * 1800 if e.get("ts") else 0
        title = (e.get("title") or "").strip().lower()
        home = (e.get("home") or "").strip().lower()
        away = (e.get("away") or "").strip().lower()
        # Skip TBD or empty placeholder fixtures.
        if home in ("tbd", "", "tba") and away in ("tbd", "", "tba"):
            if not title:
                return None
            return ("title", title, ts_bucket)
        if home and away:
            return ("teams", tuple(sorted([home, away])), ts_bucket)
        if title:
            return ("title", title, ts_bucket)
        return None

    # 1) ESPN first — preferred source (has live scores, fresh team logos).
    for raw in espn_events:
        if not raw.get("id") or raw["id"] in seen_ids:
            continue
        if not raw.get("ts"):
            continue
        if raw["ts"] < past_window or raw["ts"] > horizon:
            continue
        # Filter out placeholder TBD-at-TBD entries entirely.
        h = (raw.get("home") or "").strip().lower()
        a = (raw.get("away") or "").strip().lower()
        if (h in ("tbd", "tba", "")) and (a in ("tbd", "tba", "")) and not raw.get("title"):
            continue
        tkey = _title_key(raw)
        if tkey and tkey in seen_titles:
            continue
        raw.setdefault("source", "espn")
        # Backfill broadcasts on ESPN events too — ESPN's broadcasts
        # array is sometimes empty for niche fixtures (AFL, ATP, WTA,
        # J-League).  Our static league→channels mapping fills the
        # gap so the user always sees "Watch on …" info.
        if not raw.get("broadcasts"):
            raw["broadcasts"] = _broadcasts_for(
                raw.get("league", "") or "",
                raw.get("sport", "") or "",
            )
        seen_ids.add(raw["id"])
        if tkey:
            seen_titles.add(tkey)
        events.append(raw)

    # 2) TheSportsDB — supplemental.  Used for sports ESPN doesn't expose
    # (NRL, IPL, etc.) + as a fallback when ESPN returned nothing.
    # De-dupe against ESPN by team-pair match (BOTH directions, since
    # ESPN labels "X at Y" while TheSportsDB labels "Y vs X" for the same
    # fixture).
    espn_pairs: Dict[tuple, Dict[str, Any]] = {}
    for e in events:
        if e.get("home") and e.get("away"):
            key1 = (e["home"].lower(), e["away"].lower())
            key2 = (e["away"].lower(), e["home"].lower())
            espn_pairs[key1] = e
            espn_pairs[key2] = e

    # Build the list of TheSportsDB events from this fresh fan-out + merge
    # with the long-TTL "survivor" cache.  This is what protects NRL / IPL
    # from disappearing during rate-limit storms.
    sportsdb_events_fresh: List[Dict[str, Any]] = []
    for r in sportsdb_results:
        if not isinstance(r, dict):
            continue
        for raw in (r.get("events") or []):
            n = _normalise_event(raw)
            if not n.get("id") or not n.get("ts"):
                continue
            n["source"] = "sportsdb"
            n["homeShort"] = ""
            n["awayShort"] = ""
            n["statusShort"] = n.get("status", "")
            n["state"] = ("post" if n.get("finished")
                          else ("in" if (n["ts"] <= nowSec and not n.get("finished")) else "pre"))
            n["live"] = n["state"] == "in"
            # League/sport-driven broadcaster guess.  TheSportsDB's
            # free tier returns empty `broadcasts` for everything,
            # so we substitute a curated UK/US/AU channel list per
            # league.  Frontend will surface the first entry as
            # "Watch on …".
            n["broadcasts"] = _broadcasts_for(
                n.get("league", "") or "",
                n.get("sport", "") or "",
            )
            sportsdb_events_fresh.append(n)

    # Merge fresh + survivor cache.  Survivor wins ONLY if fresh is missing.
    survivor = _cache_get(survivor_key) or []
    survivor_by_id = {e["id"]: e for e in survivor}
    for n in sportsdb_events_fresh:
        survivor_by_id[n["id"]] = n   # fresh overrides any stale survivor
    # Update survivor cache (24h TTL).  Drop events whose ts is now in the
    # deep past so it doesn't grow forever.
    survivor_pruned = [e for e in survivor_by_id.values()
                       if e.get("ts") and e["ts"] >= nowSec - 24 * 3600]
    if survivor_pruned:
        _cache_set(survivor_key, survivor_pruned, 24 * 3600)
    sportsdb_to_merge = survivor_pruned or sportsdb_events_fresh

    # Backfill broadcasts on EVERY event in the merge pool — old
    # survivor-cached events have empty broadcasts because the
    # static mapping didn't exist when they were first written.
    # Cheap (one dict lookup) and ensures the table is always
    # in-sync with the latest leagues we add.
    for n in sportsdb_to_merge:
        if not n.get("broadcasts"):
            n["broadcasts"] = _broadcasts_for(
                n.get("league", "") or "",
                n.get("sport", "") or "",
            )

    for n in sportsdb_to_merge:
        if n["id"] in seen_ids:
            continue
        if n["ts"] < past_window or n["ts"] > horizon:
            continue
        # De-dupe against ESPN by team-pair match within ±2 h.
        pair = (n["home"].lower(), n["away"].lower())
        dup = espn_pairs.get(pair)
        if dup and abs(dup["ts"] - n["ts"]) < 7200:
            continue
        # Fuzzy match: any home/away token in ESPN matching.
        home_words = set((n["home"] or "").lower().split())
        away_words = set((n["away"] or "").lower().split())
        fuzzy_dup = False
        for k, v in espn_pairs.items():
            if abs(v["ts"] - n["ts"]) > 7200:
                continue
            k_words = set(k[0].split()) | set(k[1].split())
            if len(home_words & k_words) >= 1 and len(away_words & k_words) >= 1:
                fuzzy_dup = True
                break
        if fuzzy_dup:
            continue
        tkey = _title_key(n)
        if tkey and tkey in seen_titles:
            continue
        seen_ids.add(n["id"])
        if tkey:
            seen_titles.add(tkey)
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
        "upstream":    dict(_FETCH_STATS),
    }

    # Cache-poisoning protection — if we got nothing back from upstream (full
    # rate-limit or upstream outage), serve the previously cached payload
    # rather than overwriting it with an empty body.
    if not events and cached:
        logger.warning(
            "sportsdb: empty fan-out (ok=%d, fail=%d, 429=%d); serving stale cache.",
            _FETCH_STATS["ok"], _FETCH_STATS["fail"], _FETCH_STATS["rate_limited"],
        )
        return {**cached, "cached": True, "stale": True}

    # Only cache non-empty payloads, so the next call has a chance to refresh.
    if events:
        _cache_set(cache_key, payload, 30 * 60)  # 30 min
        # Fire-and-forget background enrichment so future cache hits are richer
        # (more leagues + more sports), without blocking this response.
        try:
            asyncio.create_task(_enrich_cache(cache_key, payload))
        except RuntimeError:
            pass
    return payload


async def _enrich_cache(cache_key: str, base_payload: Dict[str, Any]) -> None:
    """Background fan-out: fetch remaining curated leagues + sports and merge
    them into the cache.  Best-effort: skips silently if the cache has been
    replaced or the run errors.

    Sleeps 70 s before starting so TheSportsDB's per-minute rate-limit window
    resets after the foreground fan-out has finished.
    """
    try:
        await asyncio.sleep(70)
        # Compute the leagues NOT included in the cold fan-out + the secondary
        # sport set for additional breadth.
        already = {"4328", "4335", "4332", "4331", "4480",
                   "4391", "4387", "4380", "4424", "4370", "4416"}
        extra_leagues = [lg["id"] for lg in TOP_LEAGUES if lg["id"] not in already]
        extra_sports = ["Baseball", "Motorsport", "Cricket", "Rugby",
                        "MMA", "Boxing", "Tennis", "Golf", "Australian Football"]
        today0 = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
        date_strs = [(today0 + timedelta(days=i)).strftime("%Y-%m-%d") for i in range(0, 3)]

        async with httpx.AsyncClient(
            headers={"User-Agent": "VesperTV/2.0 (sports-guide-enrich)"},
            http2=False,
        ) as client:
            next_tasks = [
                _fetch(client, f"{SPORTSDB_BASE}/eventsnextleague.php?id={lid}", bg=True)
                for lid in extra_leagues
            ]
            sport_tasks = []
            for d in date_strs:
                for sport in extra_sports:
                    s_enc = sport.replace(' ', '%20')
                    sport_tasks.append(
                        _fetch(client, f"{SPORTSDB_BASE}/eventsday.php?d={d}&s={s_enc}", bg=True)
                    )
            extra_results = await asyncio.gather(
                *next_tasks, *sport_tasks, return_exceptions=True
            )

        nowSec = int(time.time())
        horizon = nowSec + 14 * 24 * 3600
        past_window = nowSec - 6 * 3600
        seen_ids: set[str] = {e["id"] for e in base_payload["events"] if e.get("id")}
        new_events: List[Dict[str, Any]] = list(base_payload["events"])
        for r in extra_results:
            if not isinstance(r, dict):
                continue
            for raw in (r.get("events") or []):
                n = _normalise_event(raw)
                if not n["id"] or n["id"] in seen_ids or not n["ts"]:
                    continue
                if n["ts"] < past_window or n["ts"] > horizon:
                    continue
                seen_ids.add(n["id"])
                new_events.append(n)

        new_events.sort(key=lambda e: e["ts"])

        by_sport: Dict[str, List[Dict[str, Any]]] = {}
        by_league: Dict[str, Dict[str, Any]] = {}
        for e in new_events:
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
                if not bl["badge"] and e["leagueBadge"]:
                    bl["badge"] = e["leagueBadge"]
                bl["events"].append(e)

        enriched = {
            "cached":     False,
            "fetched_at": base_payload["fetched_at"],
            "events":     new_events,
            "bySport":    by_sport,
            "byLeague":   by_league,
            "sportsMeta": [
                {"name": s, **SPORT_ICONS.get(s, {"emoji": "🏆", "color": "#5DC8FF"}),
                 "count": len(by_sport.get(s, []))}
                for s in sorted(by_sport.keys(), key=str.lower)
            ],
            "upstream":   dict(_FETCH_STATS),
            "enriched":   True,
        }
        _cache_set(cache_key, enriched, 30 * 60)
        logger.info(
            "sportsdb: background enrichment done — events=%d (+%d), sports=%d",
            len(new_events), len(new_events) - len(base_payload["events"]),
            len(by_sport),
        )
    except Exception as exc:
        logger.warning("sportsdb enrich task failed: %s", exc)


@router.get("/livescores")
async def get_livescores():
    """Lightweight live-score poll endpoint.

    Returns the subset of `bySport` events that are currently `live: true`,
    with their current scores + status detail.  The frontend polls this
    every ~30 s while the user is on `/sports` so live games tick up in
    real-time.

    Cached 25 s (just under the polling interval) so concurrent users
    don't fan out N times.
    """
    cache_key = "sportsdb:livescores:v1"
    hit = _cache_get(cache_key)
    if hit:
        return {**hit, "cached": True}
    try:
        from espn import fetch_all_events as _fetch_espn_events
        evs = await _fetch_espn_events(days_forward=0)
    except Exception as exc:
        logger.warning("livescore espn fetch failed: %s", exc)
        evs = []
    # Keep ONLY live games + recently finished (last 2 h).
    nowSec = int(time.time())
    live = []
    for e in evs:
        if e.get("live") or (e.get("finished") and abs(e["ts"] - nowSec) < 2 * 3600):
            live.append({
                "id":          e["id"],
                "homeScore":   e["homeScore"],
                "awayScore":   e["awayScore"],
                "status":      e["status"],
                "statusShort": e["statusShort"],
                "state":       e["state"],
                "finished":    e["finished"],
                "live":        e["live"],
            })
    payload = {"cached": False, "fetched_at": nowSec, "scores": live}
    _cache_set(cache_key, payload, 25)
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
    # Don't cache empty results — let the next call retry the upstream API.
    if events:
        _cache_set(cache_key, payload, 6 * 3600)  # 6 h
    return payload
