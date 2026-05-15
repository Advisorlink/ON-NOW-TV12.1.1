"""
ESPN scoreboard integration — broad, free, no-key, no-rate-limits.

ESPN's hidden public scoreboard API returns:
  - live scores (period/quarter/minute counter)
  - team logos (well-curated, 500x500)
  - league badges
  - status (live / final / scheduled)
  - venue, broadcast info, odds, season/week
  - covers 30+ leagues across 10+ sports

Endpoint pattern:
  https://site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard
  ?dates=YYYYMMDD   (optional — defaults to today)

Notes:
  • NOT documented officially.  Backed by ESPN's website, so it's stable.
  • No rate-limit observed at reasonable usage (≤100 req/min).
  • Cricket and Rugby League / NRL are NOT in ESPN's API; we backfill those
    via TheSportsDB in `sportsdb.py`.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional

import httpx

logger = logging.getLogger("vesper.espn")

ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports"

# ---------------------------------------------------------------------------
# Curated league universe.
#   (sport_slug, league_slug, display_name, our_sport_label, accent_priority)
#   accent_priority — 1 = top marquee (always fetch), 2 = secondary,
#                     3 = niche / region-specific.
# ---------------------------------------------------------------------------
ESPN_LEAGUES: List[Dict[str, Any]] = [
    # SOCCER ----------------------------------------------------------------
    {"sport":"soccer","league":"eng.1",            "name":"Premier League",         "label":"Soccer","tier":1},
    {"sport":"soccer","league":"esp.1",            "name":"La Liga",                "label":"Soccer","tier":1},
    {"sport":"soccer","league":"ita.1",            "name":"Serie A",                "label":"Soccer","tier":1},
    {"sport":"soccer","league":"ger.1",            "name":"Bundesliga",             "label":"Soccer","tier":1},
    {"sport":"soccer","league":"fra.1",            "name":"Ligue 1",                "label":"Soccer","tier":1},
    {"sport":"soccer","league":"uefa.champions",   "name":"UEFA Champions League",  "label":"Soccer","tier":1},
    {"sport":"soccer","league":"uefa.europa",      "name":"UEFA Europa League",     "label":"Soccer","tier":2},
    {"sport":"soccer","league":"uefa.europa.conf", "name":"UEFA Conference League", "label":"Soccer","tier":2},
    {"sport":"soccer","league":"eng.2",            "name":"EFL Championship",       "label":"Soccer","tier":2},
    {"sport":"soccer","league":"eng.league_cup",   "name":"EFL Cup",                "label":"Soccer","tier":2},
    {"sport":"soccer","league":"eng.fa",           "name":"FA Cup",                 "label":"Soccer","tier":2},
    {"sport":"soccer","league":"esp.copa_del_rey", "name":"Copa del Rey",           "label":"Soccer","tier":2},
    {"sport":"soccer","league":"ned.1",            "name":"Eredivisie",             "label":"Soccer","tier":3},
    {"sport":"soccer","league":"por.1",            "name":"Primeira Liga",          "label":"Soccer","tier":3},
    {"sport":"soccer","league":"tur.1",            "name":"Süper Lig",              "label":"Soccer","tier":3},
    {"sport":"soccer","league":"ksa.1",            "name":"Saudi Pro League",       "label":"Soccer","tier":3},
    {"sport":"soccer","league":"usa.1",            "name":"MLS",                    "label":"Soccer","tier":2},
    {"sport":"soccer","league":"mex.1",            "name":"Liga MX",                "label":"Soccer","tier":3},
    {"sport":"soccer","league":"bra.1",            "name":"Brasileirão Série A",    "label":"Soccer","tier":3},
    {"sport":"soccer","league":"arg.1",            "name":"Argentine Primera",      "label":"Soccer","tier":3},
    {"sport":"soccer","league":"jpn.1",            "name":"J1 League",              "label":"Soccer","tier":3},
    {"sport":"soccer","league":"kor.1",            "name":"K League 1",             "label":"Soccer","tier":3},
    {"sport":"soccer","league":"chn.1",            "name":"Chinese Super League",   "label":"Soccer","tier":3},
    {"sport":"soccer","league":"aus.1",            "name":"A-League",               "label":"Soccer","tier":2},
    {"sport":"soccer","league":"conmebol.libertadores","name":"Copa Libertadores",  "label":"Soccer","tier":2},
    {"sport":"soccer","league":"conmebol.sudamericana","name":"Copa Sudamericana",  "label":"Soccer","tier":3},
    {"sport":"soccer","league":"concacaf.champions",   "name":"Concacaf Champions Cup","label":"Soccer","tier":3},
    {"sport":"soccer","league":"fifa.worldq.uefa", "name":"World Cup Qualifying - UEFA","label":"Soccer","tier":2},
    {"sport":"soccer","league":"fifa.world",       "name":"FIFA World Cup",         "label":"Soccer","tier":1},
    # AMERICAN FOOTBALL -----------------------------------------------------
    {"sport":"football","league":"nfl",                       "name":"NFL",                  "label":"American Football","tier":1},
    {"sport":"football","league":"college-football",          "name":"College Football",     "label":"American Football","tier":2},
    # BASKETBALL ------------------------------------------------------------
    {"sport":"basketball","league":"nba",                     "name":"NBA",                  "label":"Basketball","tier":1},
    {"sport":"basketball","league":"wnba",                    "name":"WNBA",                 "label":"Basketball","tier":2},
    {"sport":"basketball","league":"mens-college-basketball", "name":"College Basketball",   "label":"Basketball","tier":2},
    {"sport":"basketball","league":"womens-college-basketball","name":"Women's NCAA",         "label":"Basketball","tier":3},
    # BASEBALL --------------------------------------------------------------
    {"sport":"baseball","league":"mlb",                       "name":"MLB",                  "label":"Baseball","tier":1},
    {"sport":"baseball","league":"college-baseball",          "name":"NCAA Baseball",        "label":"Baseball","tier":3},
    # ICE HOCKEY ------------------------------------------------------------
    {"sport":"hockey","league":"nhl",                         "name":"NHL",                  "label":"Ice Hockey","tier":1},
    # AUSTRALIAN FOOTBALL ---------------------------------------------------
    {"sport":"australian-football","league":"afl",            "name":"AFL",                  "label":"Australian Football","tier":1},
    # MMA / COMBAT ----------------------------------------------------------
    {"sport":"mma","league":"ufc",                            "name":"UFC",                  "label":"MMA","tier":1},
    {"sport":"mma","league":"pfl",                            "name":"PFL",                  "label":"MMA","tier":3},
    {"sport":"mma","league":"bellator",                       "name":"Bellator",             "label":"MMA","tier":3},
    # BOXING ----------------------------------------------------------------
    {"sport":"boxing","league":"boxing",                      "name":"Boxing",               "label":"Boxing","tier":2},
    # MOTORSPORT ------------------------------------------------------------
    {"sport":"racing","league":"f1",                          "name":"Formula 1",            "label":"Motorsport","tier":1},
    {"sport":"racing","league":"nascar-premier",              "name":"NASCAR Cup",           "label":"Motorsport","tier":2},
    {"sport":"racing","league":"irl",                         "name":"IndyCar",              "label":"Motorsport","tier":3},
    # TENNIS ----------------------------------------------------------------
    {"sport":"tennis","league":"atp",                         "name":"ATP Tour",             "label":"Tennis","tier":1},
    {"sport":"tennis","league":"wta",                         "name":"WTA Tour",             "label":"Tennis","tier":1},
    # GOLF ------------------------------------------------------------------
    {"sport":"golf","league":"pga",                           "name":"PGA Tour",             "label":"Golf","tier":1},
    {"sport":"golf","league":"lpga",                          "name":"LPGA Tour",            "label":"Golf","tier":2},
    {"sport":"golf","league":"champions-tour",                "name":"Champions Tour",       "label":"Golf","tier":3},
    {"sport":"golf","league":"liv",                           "name":"LIV Golf",             "label":"Golf","tier":2},
    # RUGBY (Union — ESPN's `/rugby/{league}/` doesn't expose NRL).  Test
    # NRL via TheSportsDB instead.
    {"sport":"rugby","league":"",                             "name":"Rugby (Union)",        "label":"Rugby Union","tier":2},
]


# ---------------------------------------------------------------------------
# Normalisation
# ---------------------------------------------------------------------------
def _parse_iso(s: str) -> int:
    try:
        # Format: "2026-05-15T19:00Z"
        dt = datetime.fromisoformat(s.replace("Z", "+00:00"))
        return int(dt.timestamp())
    except Exception:
        return 0


def _normalise(ev: Dict[str, Any], league_meta: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Convert one ESPN scoreboard event into our common fixture shape."""
    try:
        comp = (ev.get("competitions") or [{}])[0]
        competitors = comp.get("competitors") or []
        home = next((c for c in competitors if c.get("homeAway") == "home"), {})
        away = next((c for c in competitors if c.get("homeAway") == "away"), {})
        status = (ev.get("status") or {}).get("type") or {}
        state = status.get("state", "")               # "pre" / "in" / "post"
        short_detail = status.get("shortDetail", "")   # e.g. "Q3 5:23" or "Final"
        long_detail  = status.get("detail", "")        # e.g. "9:00 PM ET"

        ts = _parse_iso(ev.get("date") or "")
        venue = (comp.get("venue") or {}).get("fullName", "")
        venue_address = (comp.get("venue") or {}).get("address") or {}
        city = venue_address.get("city", "")
        country = venue_address.get("country", "")
        # Broadcast networks
        bcasts = comp.get("broadcasts") or []
        broadcast_names = [b for net in bcasts for b in (net.get("names") or [])]

        home_team = home.get("team") or {}
        away_team = away.get("team") or {}
        home_logo = home_team.get("logo") or ""
        away_logo = away_team.get("logo") or ""
        # ESPN gives strings for score; coerce safely.
        home_score = home.get("score", "")
        away_score = away.get("score", "")
        if state == "pre":
            home_score = ""
            away_score = ""

        return {
            "id":          f"espn-{ev.get('id') or comp.get('id') or ''}",
            "source":      "espn",
            "title":       ev.get("name") or f"{home_team.get('displayName','')} vs {away_team.get('displayName','')}",
            "shortTitle":  ev.get("shortName") or "",
            "league":      league_meta["name"],
            "leagueId":    f"espn-{league_meta['sport']}-{league_meta['league']}",
            "leagueBadge": "",
            "sport":       league_meta["label"],
            "ts":          ts,
            "season":      str((ev.get("season") or {}).get("year") or ""),
            "round":       str((ev.get("week") or {}).get("number") or ""),
            "home":        home_team.get("displayName") or home_team.get("name") or "",
            "homeShort":   home_team.get("abbreviation") or "",
            "homeBadge":   home_logo,
            "homeColor":   home_team.get("color") or "",
            "away":        away_team.get("displayName") or away_team.get("name") or "",
            "awayShort":   away_team.get("abbreviation") or "",
            "awayBadge":   away_logo,
            "awayColor":   away_team.get("color") or "",
            "homeScore":   str(home_score) if home_score != "" else "",
            "awayScore":   str(away_score) if away_score != "" else "",
            "venue":       venue,
            "city":        city,
            "country":     country,
            "thumb":       "",
            "poster":      "",
            "status":      long_detail or short_detail,
            "statusShort": short_detail,
            "state":       state,                # 'pre' / 'in' / 'post'
            "finished":    state == "post",
            "live":        state == "in",
            "broadcasts":  broadcast_names,
            "video":       "",
        }
    except Exception as exc:
        logger.debug("espn normalise failed: %s", exc)
        return None


# ---------------------------------------------------------------------------
# Fetchers
# ---------------------------------------------------------------------------
_ESPN_SEM = asyncio.Semaphore(8)


async def _fetch_one(
    client: httpx.AsyncClient,
    league_meta: Dict[str, Any],
    date_str: str = "",
) -> List[Dict[str, Any]]:
    async with _ESPN_SEM:
        sport = league_meta["sport"]
        league = league_meta["league"]
        if league:
            url = f"{ESPN_BASE}/{sport}/{league}/scoreboard"
        else:
            url = f"{ESPN_BASE}/{sport}/scoreboard"
        if date_str:
            url += f"?dates={date_str}"
        try:
            r = await client.get(url, timeout=8.0)
            if r.status_code != 200:
                logger.debug("espn %s/%s -> %d", sport, league, r.status_code)
                return []
            data = r.json()
            evs = data.get("events") or []
            out: List[Dict[str, Any]] = []
            # ESPN sometimes returns league logo under `leagues[0].logos`.
            leagues_payload = data.get("leagues") or [{}]
            league_logo = ""
            for logo in (leagues_payload[0].get("logos") or []):
                if logo.get("href"):
                    league_logo = logo["href"]
                    break
            for raw in evs:
                norm = _normalise(raw, league_meta)
                if not norm:
                    continue
                if league_logo and not norm["leagueBadge"]:
                    norm["leagueBadge"] = league_logo
                out.append(norm)
            return out
        except Exception as exc:
            logger.debug("espn fetch err %s/%s: %s", sport, league, exc)
            return []


async def fetch_all_events(days_forward: int = 3) -> List[Dict[str, Any]]:
    """Fan-out to every curated ESPN league.  Returns deduped, normalised
    events for today + the next `days_forward` days.

    Soccer/NFL/etc. scoreboard endpoints default to "today's events" only;
    most return ~1-3 fixtures.  We hit each league once today and once for
    each future day to pull tomorrow's schedule too.
    """
    today = datetime.now(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0)
    date_strs = [(today + timedelta(days=i)).strftime("%Y%m%d") for i in range(0, days_forward + 1)]

    async with httpx.AsyncClient(
        headers={"User-Agent": "VesperTV/2.0 (sports-guide-espn)"},
        http2=False,
    ) as client:
        tasks = []
        for league in ESPN_LEAGUES:
            # Tier-1 leagues: fetch every date in the window.
            # Tier-2 & 3: only fetch today + tomorrow (saves ~80 calls).
            ds = date_strs if league["tier"] == 1 else date_strs[:2]
            for d in ds:
                tasks.append(_fetch_one(client, league, d))
        results = await asyncio.gather(*tasks, return_exceptions=True)

    seen: set[str] = set()
    out: List[Dict[str, Any]] = []
    for r in results:
        if not isinstance(r, list):
            continue
        for ev in r:
            if not ev.get("id") or ev["id"] in seen:
                continue
            seen.add(ev["id"])
            out.append(ev)
    return out
