"""Sports Hub — ESPN public JSON normalised for the ON NOW TV Live TV app.

    GET /api/sportshub/leagues                 → sports → leagues
    GET /api/sportshub/scoreboard?league=eng.1&sport=soccer[&date=YYYYMMDD]
    GET /api/sportshub/live                    → every live/upcoming-today game across all leagues (+ venue lat/lon)
    GET /api/sportshub/game?sport=&league=&id= → header, situation, linescore, plays (with field coords), win prob, players
    GET /api/sportshub/standings?sport=&league=
"""
from __future__ import annotations

import asyncio
import logging
import re
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple

import httpx
from fastapi import APIRouter, HTTPException, Query

from espn import ESPN_LEAGUES

log = logging.getLogger("sportshub")
router = APIRouter(prefix="/api/sportshub")
from sports_official_sources import router as official_sources_router
router.include_router(official_sources_router)
from sports_catchup import router as catchup_router
router.include_router(catchup_router)

ESPN = "https://site.api.espn.com/apis/site/v2/sports"
ESPN_V2 = "https://site.api.espn.com/apis/v2/sports"

# Extra leagues on top of espn.py's list → 13 sports / 67 leagues.
EXTRA_LEAGUES: List[Dict[str, Any]] = [
    {"sport": "soccer", "league": "aus.1", "name": "A-League Men", "label": "Soccer", "tier": 2},
    {"sport": "soccer", "league": "sco.1", "name": "Scottish Premiership", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "bel.1", "name": "Belgian Pro League", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "arg.1", "name": "Liga Profesional", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "bra.1", "name": "Brasileirão", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "jpn.1", "name": "J1 League", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "fifa.world", "name": "FIFA World Cup", "label": "Soccer", "tier": 1},
    {"sport": "soccer", "league": "uefa.euro", "name": "UEFA Euro", "label": "Soccer", "tier": 1},
    {"sport": "soccer", "league": "conmebol.libertadores", "name": "Copa Libertadores", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "usa.nwsl", "name": "NWSL", "label": "Soccer", "tier": 3},
    {"sport": "soccer", "league": "eng.w.1", "name": "Women's Super League", "label": "Soccer", "tier": 3},
    {"sport": "basketball", "league": "wnba", "name": "WNBA", "label": "Basketball", "tier": 2},
    {"sport": "basketball", "league": "mens-college-basketball", "name": "NCAA Men's Basketball", "label": "Basketball", "tier": 2},
    {"sport": "basketball", "league": "womens-college-basketball", "name": "NCAA Women's Basketball", "label": "Basketball", "tier": 3},
    {"sport": "basketball", "league": "nbl", "name": "NBL (Australia)", "label": "Basketball", "tier": 3},
    {"sport": "football", "league": "college-football", "name": "NCAA Football", "label": "American Football", "tier": 2},
    {"sport": "baseball", "league": "college-baseball", "name": "NCAA Baseball", "label": "Baseball", "tier": 3},
    {"sport": "hockey", "league": "mens-college-hockey", "name": "NCAA Hockey", "label": "Ice Hockey", "tier": 3},
    {"sport": "cricket", "league": "8039", "name": "Indian Premier League", "label": "Cricket", "tier": 1},
    {"sport": "cricket", "league": "8048", "name": "Big Bash League", "label": "Cricket", "tier": 2},
    {"sport": "cricket", "league": "8676", "name": "The Hundred", "label": "Cricket", "tier": 3},
    {"sport": "cricket", "league": "19430", "name": "ICC Test Championship", "label": "Cricket", "tier": 2},
    {"sport": "rugby-league", "league": "3", "name": "NRL", "label": "Rugby League", "tier": 1},
    {"sport": "rugby-league", "league": "1", "name": "Super League", "label": "Rugby League", "tier": 2},
    {"sport": "rugby-league", "league": "4", "name": "State of Origin", "label": "Rugby League", "tier": 1},
    {"sport": "lacrosse", "league": "pll", "name": "Premier Lacrosse League", "label": "Lacrosse", "tier": 3},
    {"sport": "volleyball", "league": "mens-college-volleyball", "name": "NCAA Volleyball", "label": "Volleyball", "tier": 3},
]


def _all_leagues() -> List[Dict[str, Any]]:
    seen = set()
    out = []
    for l in list(ESPN_LEAGUES) + EXTRA_LEAGUES:
        k = (l["sport"], l["league"])
        if k in seen:
            continue
        seen.add(k)
        out.append(l)
    return out


LEAGUES = _all_leagues()
LEAGUE_BY_KEY = {(l["sport"], l["league"]): l for l in LEAGUES}

# Venue city → (lat, lon) for the live world map (ESPN rarely ships coords).
CITIES: Dict[str, Tuple[float, float]] = {
    "london": (51.51, -0.13), "manchester": (53.48, -2.24), "liverpool": (53.41, -2.98), "birmingham": (52.49, -1.89),
    "newcastle": (54.98, -1.61), "leeds": (53.80, -1.55), "glasgow": (55.86, -4.25), "madrid": (40.42, -3.70),
    "barcelona": (41.39, 2.17), "sevilla": (37.39, -5.98), "seville": (37.39, -5.98), "valencia": (39.47, -0.38),
    "bilbao": (43.26, -2.93), "milan": (45.46, 9.19), "milano": (45.46, 9.19), "rome": (41.90, 12.50), "roma": (41.90, 12.50),
    "turin": (45.07, 7.69), "torino": (45.07, 7.69), "naples": (40.85, 14.27), "napoli": (40.85, 14.27), "munich": (48.14, 11.58),
    "münchen": (48.14, 11.58), "dortmund": (51.51, 7.47), "berlin": (52.52, 13.41), "leipzig": (51.34, 12.37),
    "paris": (48.86, 2.35), "marseille": (43.30, 5.37), "lyon": (45.76, 4.84), "amsterdam": (52.37, 4.90),
    "lisbon": (38.72, -9.14), "lisboa": (38.72, -9.14), "porto": (41.15, -8.61), "istanbul": (41.01, 28.98),
    "riyadh": (24.71, 46.68), "jeddah": (21.49, 39.19), "doha": (25.29, 51.53), "dubai": (25.20, 55.27),
    "mexico city": (19.43, -99.13), "guadalajara": (20.66, -103.35), "monterrey": (25.69, -100.32),
    "buenos aires": (-34.60, -58.38), "rio de janeiro": (-22.91, -43.17), "são paulo": (-23.55, -46.63), "sao paulo": (-23.55, -46.63),
    "tokyo": (35.68, 139.69), "mumbai": (19.08, 72.88), "delhi": (28.61, 77.21), "new delhi": (28.61, 77.21), "kolkata": (22.57, 88.36),
    "chennai": (13.08, 80.27), "bengaluru": (12.97, 77.59), "bangalore": (12.97, 77.59), "hyderabad": (17.39, 78.49), "ahmedabad": (23.02, 72.57),
    "sydney": (-33.87, 151.21), "melbourne": (-37.81, 144.96), "brisbane": (-27.47, 153.03), "perth": (-31.95, 115.86),
    "adelaide": (-34.93, 138.60), "canberra": (-35.28, 149.13), "gold coast": (-28.02, 153.40), "newcastle, australia": (-32.93, 151.78),
    "townsville": (-19.26, 146.82), "auckland": (-36.85, 174.76), "wellington": (-41.29, 174.78), "christchurch": (-43.53, 172.64),
    "new york": (40.71, -74.01), "bronx": (40.84, -73.87), "brooklyn": (40.68, -73.94), "queens": (40.73, -73.79), "flushing": (40.76, -73.83),
    "boston": (42.36, -71.06), "foxborough": (42.09, -71.26), "philadelphia": (39.95, -75.17), "pittsburgh": (40.44, -80.00),
    "baltimore": (39.29, -76.61), "washington": (38.91, -77.04), "landover": (38.93, -76.86), "charlotte": (35.23, -80.84),
    "atlanta": (33.75, -84.39), "miami": (25.76, -80.19), "miami gardens": (25.94, -80.24), "tampa": (27.95, -82.46), "orlando": (28.54, -81.38),
    "jacksonville": (30.33, -81.66), "nashville": (36.16, -86.78), "memphis": (35.15, -90.05), "new orleans": (29.95, -90.07),
    "houston": (29.76, -95.37), "dallas": (32.78, -96.80), "arlington": (32.74, -97.11), "san antonio": (29.42, -98.49), "austin": (30.27, -97.74),
    "oklahoma city": (35.47, -97.52), "kansas city": (39.10, -94.58), "st. louis": (38.63, -90.20), "chicago": (41.88, -87.63),
    "milwaukee": (43.04, -87.91), "green bay": (44.51, -88.02), "minneapolis": (44.98, -93.27), "detroit": (42.33, -83.05),
    "cleveland": (41.50, -81.69), "cincinnati": (39.10, -84.51), "columbus": (39.96, -83.00), "indianapolis": (39.77, -86.16),
    "denver": (39.74, -104.99), "salt lake city": (40.76, -111.89), "phoenix": (33.45, -112.07), "glendale": (33.54, -112.19),
    "las vegas": (36.17, -115.14), "paradise": (36.10, -115.15), "los angeles": (34.05, -118.24), "inglewood": (33.96, -118.35),
    "anaheim": (33.84, -117.91), "san diego": (32.72, -117.16), "san francisco": (37.77, -122.42), "santa clara": (37.35, -121.95),
    "oakland": (37.80, -122.27), "sacramento": (38.58, -121.49), "san jose": (37.34, -121.89), "seattle": (47.61, -122.33),
    "portland": (45.52, -122.68), "toronto": (43.65, -79.38), "montreal": (45.50, -73.57), "vancouver": (49.28, -123.12),
    "calgary": (51.04, -114.07), "edmonton": (53.55, -113.49), "ottawa": (45.42, -75.70), "winnipeg": (49.90, -97.14),
    "buffalo": (42.89, -78.88), "orchard park": (42.77, -78.74), "raleigh": (35.78, -78.64), "sunrise": (26.16, -80.26),
    "east rutherford": (40.81, -74.07), "cardiff": (51.48, -3.18), "dublin": (53.35, -6.26), "edinburgh": (55.95, -3.19),
    "johannesburg": (-26.20, 28.05), "cape town": (-33.92, 18.42), "karachi": (24.86, 67.01), "lahore": (31.55, 74.34),
    "colombo": (6.93, 79.85), "dhaka": (23.81, 90.41), "abu dhabi": (24.45, 54.38), "monte carlo": (43.74, 7.43), "monaco": (43.74, 7.43),
    "silverstone": (52.07, -1.02), "spa": (50.49, 5.86), "suzuka": (34.84, 136.54), "singapore": (1.35, 103.82), "sakhir": (26.03, 50.51),
    "melbourne park": (-37.82, 144.98), "wimbledon": (51.43, -0.21), "augusta": (33.47, -82.01),
}


def _geo(venue: Dict[str, Any]) -> Optional[Dict[str, float]]:
    addr = (venue or {}).get("address") or {}
    for key in (f"{addr.get('city', '')}, {addr.get('country', '')}".lower(), (addr.get("city") or "").lower()):
        if key in CITIES:
            lat, lon = CITIES[key]
            return {"lat": lat, "lon": lon}
    return None


_cache: Dict[str, Tuple[float, Any]] = {}


def _cget(key: str, ttl: float):
    v = _cache.get(key)
    if v and time.time() - v[0] < ttl:
        return v[1]
    return None


def _cput(key: str, data: Any):
    _cache[key] = (time.time(), data)
    if len(_cache) > 600:
        for k in sorted(_cache, key=lambda k: _cache[k][0])[:200]:
            _cache.pop(k, None)


async def _get(url: str, params: Optional[Dict[str, Any]] = None, ttl: float = 20) -> Dict[str, Any]:
    key = url + "?" + "&".join(f"{k}={v}" for k, v in sorted((params or {}).items()))
    hit = _cget(key, ttl)
    if hit is not None:
        return hit
    async with httpx.AsyncClient(timeout=12) as client:
        r = await client.get(url, params=params, headers={"User-Agent": "Mozilla/5.0 ONNOWTV"})
        r.raise_for_status()
        data = r.json()
    _cput(key, data)
    return data


def _ms(iso: str) -> int:
    try:
        return int(datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp() * 1000)
    except Exception:
        return 0


def _team(c: Dict[str, Any]) -> Dict[str, Any]:
    t = c.get("team") or c.get("athlete") or {}
    recs = c.get("records") or []
    return {
        "id": str(t.get("id") or c.get("id") or ""),
        "name": t.get("displayName") or t.get("name") or t.get("shortDisplayName") or "",
        "short": t.get("shortDisplayName") or t.get("abbreviation") or t.get("name") or "",
        "abbr": t.get("abbreviation") or (t.get("shortDisplayName") or "")[:3].upper(),
        "logo": t.get("logo") or ((t.get("logos") or [{}])[0].get("href") if t.get("logos") else None),
        "color": t.get("color"),
        "altColor": t.get("alternateColor"),
        "score": c.get("score") if not isinstance(c.get("score"), dict) else (c.get("score") or {}).get("displayValue"),
        "record": recs[0].get("summary") if recs else None,
        "winner": bool(c.get("winner")),
        "home": c.get("homeAway") == "home",
    }


def _broadcasts(comp: Dict[str, Any]) -> List[str]:
    """Scoreboard names[] and summary media.shortName; never treat market/type as channels."""
    found: List[str] = []
    seen = set()

    def add(value) -> bool:
        if isinstance(value, str):
            name = " ".join(value.split())
            key = name.casefold()
            if not name or key in {"null", "none", "undefined", "unknown", "n/a", "na", "n-a", "tbd", "not available", "-", "--"}:
                return False
            if key not in seen:
                seen.add(key)
                found.append(name)
            return True
        return False

    for key in ("broadcasts", "geoBroadcasts"):
        rows = comp.get(key) or []
        if not isinstance(rows, list):
            continue
        for row in rows:
            if isinstance(row, str):
                add(row)
                continue
            if not isinstance(row, dict):
                continue
            names = row.get("names") or []
            for name in names if isinstance(names, list) else [names]:
                add(name)
            media = row.get("media") or {}
            for outlet in media if isinstance(media, list) else [media]:
                if isinstance(outlet, dict):
                    for field in ("shortName", "name", "displayName"):
                        if add(outlet.get(field)):
                            break
                else:
                    add(outlet)
    return sorted(found, key=str.casefold)


def _event(ev: Dict[str, Any], meta: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    comps = ev.get("competitions") or []
    if not comps:
        return None
    comp = comps[0]
    cs = comp.get("competitors") or []
    if len(cs) < 2:
        return None
    home = next((c for c in cs if c.get("homeAway") == "home"), cs[0])
    away = next((c for c in cs if c.get("homeAway") == "away"), cs[1] if cs[1] is not home else cs[0])
    st = (ev.get("status") or comp.get("status") or {})
    stt = st.get("type") or {}
    state = stt.get("state") or "pre"
    venue = comp.get("venue") or {}
    return {
        "id": str(ev.get("id")),
        "sport": meta["sport"],
        "league": meta["league"],
        "leagueName": meta["name"],
        "sportLabel": meta["label"],
        "name": ev.get("name") or "",
        "shortName": ev.get("shortName") or "",
        "startMs": _ms(ev.get("date") or comp.get("date") or ""),
        "state": state,
        "completed": bool(stt.get("completed")),
        "statusDetail": stt.get("shortDetail") or stt.get("detail") or "",
        "clock": st.get("displayClock") or "",
        "period": st.get("period") or 0,
        "home": _team(home),
        "away": _team(away),
        "venue": {"name": venue.get("fullName") or "", "city": ((venue.get("address") or {}).get("city") or ""), "geo": _geo(venue)},
        "broadcasts": _broadcasts(comp),
    }


@router.get("/leagues")
async def leagues():
    sports: Dict[str, Dict[str, Any]] = {}
    for l in LEAGUES:
        s = sports.setdefault(l["label"], {"sport": l["label"], "espnSport": l["sport"], "leagues": []})
        s["leagues"].append({"sport": l["sport"], "league": l["league"], "name": l["name"], "tier": l.get("tier", 3)})
    for s in sports.values():
        s["leagues"].sort(key=lambda x: (x["tier"], x["name"]))
    order = ["Soccer", "American Football", "Basketball", "Baseball", "Ice Hockey", "Cricket", "Rugby League", "Rugby Union",
             "Australian Football", "Tennis", "Golf", "Motorsport", "MMA", "Boxing", "Lacrosse", "Volleyball"]
    out = sorted(sports.values(), key=lambda s: order.index(s["sport"]) if s["sport"] in order else 99)
    return {"sports": out, "sportCount": len(out), "leagueCount": len(LEAGUES)}


async def _scoreboard(sport: str, league: str, date: Optional[str]) -> List[Dict[str, Any]]:
    meta = LEAGUE_BY_KEY.get((sport, league))
    if not meta:
        raise HTTPException(404, "unknown league")
    params = {"dates": date} if date else None
    try:
        data = await _get(f"{ESPN}/{sport}/{league}/scoreboard", params, ttl=15)
    except Exception as e:  # noqa: BLE001
        log.warning("scoreboard %s/%s failed: %s", sport, league, e)
        raise HTTPException(502, "Scores are temporarily unavailable for this league") from e
    out = []
    for ev in data.get("events") or []:
        n = _event(ev, meta)
        if n:
            out.append(n)
    return out


@router.get("/scoreboard")
async def scoreboard(sport: str, league: str, date: Optional[str] = None):
    games = await _scoreboard(sport, league, date)
    games.sort(key=lambda g: ({"in": 0, "pre": 1, "post": 2}.get(g["state"], 3), g["startMs"]))
    return {"games": games, "league": LEAGUE_BY_KEY[(sport, league)]["name"], "status": "ok" if games else "empty"}


@router.get("/live")
async def live(tier: int = Query(3, ge=1, le=3), date: Optional[str] = None):
    """Every game in play now (plus today's upcoming) across all leagues — fuels the hub's Live Now row and the world map."""
    if date:
        try:
            if len(date) != 8 or not date.isdigit():
                raise ValueError()
            datetime.strptime(date, "%Y%m%d")
        except ValueError:
            raise HTTPException(422, "Date must be a valid YYYYMMDD calendar date")
    cache_key = f"live:{tier}" + (f":{date}" if date else "")
    hit = _cget(cache_key, 25)
    if hit is not None:
        return hit
    metas = [l for l in LEAGUES if l.get("tier", 3) <= tier]
    sem = asyncio.Semaphore(12)

    async def one(m):
        async with sem:
            return await _scoreboard(m["sport"], m["league"], date)

    tasks = {asyncio.create_task(one(m)): m for m in metas}
    try:
        # A few unavailable leagues must not hold the whole TV hub for a minute.
        done, pending = await asyncio.wait(tasks, timeout=14)
        games = []
        failed = []
        for task in done:
            meta = tasks[task]
            try:
                games.extend(task.result())
            except Exception:
                failed.append({"sport": meta["sport"], "league": meta["league"]})
        for task in pending:
            meta = tasks[task]
            failed.append({"sport": meta["sport"], "league": meta["league"]})
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    now = int(time.time() * 1000)
    if not date:
        games = [g for g in games if g["state"] == "in" or (g["state"] == "pre" and 0 <= g["startMs"] - now <= 36 * 3600_000)
                 or (g["state"] == "post" and now - g["startMs"] <= 6 * 3600_000)]
    games.sort(key=lambda g: ({"in": 0, "pre": 1, "post": 2}.get(g["state"], 3), g["startMs"]))
    status = "unavailable" if len(failed) == len(metas) else ("partial" if failed else ("ok" if games else "empty"))
    out = {"games": games, "liveCount": sum(1 for g in games if g["state"] == "in"), "generatedMs": now,
           "status": status, "failedLeagues": failed}
    _cput(cache_key, out)
    return out


def _coord(p: Dict[str, Any]) -> Dict[str, Any]:
    c = p.get("coordinate") or {}
    out: Dict[str, Any] = {}
    if isinstance(c, dict) and ("x" in c or "y" in c):
        out["x"], out["y"] = c.get("x"), c.get("y")
    hc = p.get("hitCoordinate") or {}
    if isinstance(hc, dict) and "x" in hc:
        out["hx"], out["hy"] = hc.get("x"), hc.get("y")
    pc = p.get("pitchCoordinate") or {}
    if isinstance(pc, dict) and "x" in pc:
        out["px"], out["py"] = pc.get("x"), pc.get("y")
    if "fieldPositionX" in p:
        out["fx"], out["fy"] = p.get("fieldPositionX"), p.get("fieldPositionY")
    return out


_OUTCOME = [
    (re.compile(r"home ?run", re.I), "homerun"), (re.compile(r"\b(single|double|triple)\b|hit by|reached on", re.I), "hit"),
    (re.compile(r"\b(out|strikeout|struck out|flied|grounded|lined|popped|forceout|double play)\b", re.I), "out"),
    (re.compile(r"\bgoal\b|scores!|scored", re.I), "goal"), (re.compile(r"\b(saved|blocked|missed|off target|wide|post|bar)\b", re.I), "miss"),
    (re.compile(r"\bshot\b|attempt", re.I), "shot"), (re.compile(r"\bmakes?\b|good\b|dunk|layup", re.I), "made"),
    (re.compile(r"\bmiss", re.I), "miss"), (re.compile(r"touchdown", re.I), "touchdown"), (re.compile(r"field goal|try\b", re.I), "score"),
]


def _outcome(p: Dict[str, Any]) -> str:
    if p.get("scoringPlay"):
        t = (p.get("type") or {}).get("text", "")
        if re.search(r"home ?run", (p.get("text") or "") + t, re.I):
            return "homerun"
        return "score"
    if p.get("shootingPlay") is True and p.get("scoreValue", 0) > 0:
        return "made"
    txt = f"{(p.get('type') or {}).get('text', '')} {p.get('text') or ''}"
    for rx, name in _OUTCOME:
        if rx.search(txt):
            return name
    return "play"


def _players(box: Dict[str, Any], side_id: str) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    for grp in box.get("players") or []:
        if str((grp.get("team") or {}).get("id")) != side_id:
            continue
        for stats in grp.get("statistics") or []:
            keys = stats.get("keys") or stats.get("names") or []
            labels = stats.get("labels") or stats.get("names") or keys
            for a in stats.get("athletes") or []:
                ath = a.get("athlete") or {}
                vals = a.get("stats") or []
                if not vals or all(v in ("", "0", "-", "--") for v in vals):
                    continue
                line = " · ".join(f"{labels[i]} {vals[i]}" for i in range(min(len(labels), len(vals), 4)) if vals[i] not in ("", "-"))
                out.append({
                    "id": str(ath.get("id") or ""), "teamId": side_id, "name": ath.get("shortName") or ath.get("displayName") or "",
                    "pos": (ath.get("position") or {}).get("abbreviation") or "", "headshot": (ath.get("headshot") or {}).get("href") if isinstance(ath.get("headshot"), dict) else ath.get("headshot"),
                    "line": line, "group": stats.get("name") or stats.get("type") or "",
                    "active": bool(a.get("active", True)) and not a.get("didNotPlay"),
                })
    return out[:40]


def _active_player(situation, role, player_rows):
    """Join portraits by ESPN athlete ID only, never by a guessed name."""
    entry = situation.get(role) or {}
    if not isinstance(entry, dict):
        return None
    athlete = entry.get("athlete") or {}
    identity = str(athlete.get("id") or "")
    if not identity:
        return None
    known = next((p for p in player_rows if p.get("id") == identity), {})
    shot = athlete.get("headshot")
    if isinstance(shot, dict):
        shot = shot.get("href")
    return {"id": identity, "name": athlete.get("shortName") or athlete.get("displayName") or known.get("name"),
            "headshot": shot or known.get("headshot"), "line": entry.get("summary") or known.get("line"),
            "teamId": str((athlete.get("team") or {}).get("id") or known.get("teamId") or "")}


@router.get("/game")
async def game(sport: str, league: str, id: str):
    meta = LEAGUE_BY_KEY.get((sport, league))
    if not meta:
        raise HTTPException(404, "unknown league")
    try:
        data = await _get(f"{ESPN}/{sport}/{league}/summary", {"event": id}, ttl=10)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(502, f"espn summary failed: {e}")
    header = data.get("header") or {}
    comp = (header.get("competitions") or [{}])[0]
    ev_like = {"id": header.get("id") or id, "name": "", "shortName": "", "date": comp.get("date"), "status": comp.get("status"), "competitions": [comp]}
    hdr = _event(ev_like, meta) or {}
    if not hdr:
        raise HTTPException(502, "Game details are temporarily unavailable")
    if not hdr.get("name"):
        hdr["name"] = f'{hdr["away"]["name"]} at {hdr["home"]["name"]}'
        hdr["shortName"] = f'{hdr["away"]["abbr"]} @ {hdr["home"]["abbr"]}'
    gi = data.get("gameInfo") or {}
    if gi.get("venue"):
        hdr["venue"] = {"name": (gi["venue"].get("fullName") or ""), "city": ((gi["venue"].get("address") or {}).get("city") or ""), "geo": _geo(gi["venue"])}
    home_id, away_id = hdr.get("home", {}).get("id", ""), hdr.get("away", {}).get("id", "")

    raw_plays: List[Dict[str, Any]] = list(data.get("plays") or data.get("commentary") or [])
    drives = data.get("drives") or {}
    if not raw_plays and drives:  # NFL / college football nest plays inside drives
        for d in (drives.get("previous") or []) + ([drives["current"]] if drives.get("current") else []):
            for pl in d.get("plays") or []:
                pl.setdefault("team", (pl.get("start") or {}).get("team") or d.get("team"))
                raw_plays.append(pl)
    plays = []
    at_bat_text: Dict[str, Dict[str, Any]] = {}
    for p in raw_plays[-400:]:
        if not isinstance(p, dict):
            continue
        # MLB: the hit/pitch coordinates ride on the empty "End Batter/Pitcher"
        # marker; borrow the result text from the same at-bat so it plots.
        ab = p.get("atBatId")
        if ab and (p.get("text") or "").strip():
            at_bat_text[ab] = p
        elif ab and not (p.get("text") or "").strip() and ab in at_bat_text:
            src = at_bat_text[ab]
            p = {**p, "text": src.get("text"), "scoringPlay": src.get("scoringPlay"), "type": src.get("type"), "team": p.get("team") or src.get("team")}
        team_id = str((p.get("team") or {}).get("id") or "")
        yl = (p.get("end") or {}).get("yardsToEndzone")
        plays.append({
            "id": str(p.get("id") or p.get("sequenceNumber") or len(plays)),
            "text": p.get("text") or p.get("shortText") or (p.get("play") or {}).get("text") or "",
            "period": (p.get("period") or {}).get("number") if isinstance(p.get("period"), dict) else p.get("period"),
            "clock": (p.get("clock") or {}).get("displayValue") if isinstance(p.get("clock"), dict) else (p.get("clock") or ""),
            "side": "home" if team_id and team_id == home_id else ("away" if team_id and team_id == away_id else ""),
            "type": (p.get("type") or {}).get("text") or "",
            "scoring": bool(p.get("scoringPlay")),
            "outcome": _outcome(p),
            "homeScore": p.get("homeScore"), "awayScore": p.get("awayScore"),
            "yardsToGo": yl, "downDistance": (p.get("start") or {}).get("shortDownDistanceText"),
            **_coord(p),
        })

    wp = []
    for w in data.get("winprobability") or []:
        hp = w.get("homeWinPercentage")
        if hp is None:
            continue
        wp.append({"playId": str(w.get("playId") or ""), "home": round(float(hp) * 100, 1)})

    box = data.get("boxscore") or {}
    sit = data.get("situation") or (comp.get("situation") if isinstance(comp.get("situation"), dict) else {}) or {}
    if not sit and drives.get("current"):
        last = ((drives["current"].get("plays") or [{}])[-1]).get("end") or {}
        sit = {"downDistanceText": last.get("downDistanceText"), "possessionText": last.get("possessionText")}
    player_rows = {"home": _players(box, home_id), "away": _players(box, away_id)}
    all_players = player_rows["home"] + player_rows["away"]
    situation = {
        "pitcherPlayer": _active_player(sit, "pitcher", all_players),
        "batterPlayer": _active_player(sit, "batter", all_players),
        "balls": sit.get("balls"), "strikes": sit.get("strikes"), "outs": sit.get("outs"),
        "onFirst": bool(sit.get("onFirst")), "onSecond": bool(sit.get("onSecond")), "onThird": bool(sit.get("onThird")),
        "pitcher": ((sit.get("pitcher") or {}).get("athlete") or {}).get("shortName") if isinstance(sit.get("pitcher"), dict) else None,
        "pitcherLine": (sit.get("pitcher") or {}).get("summary") if isinstance(sit.get("pitcher"), dict) else None,
        "batter": ((sit.get("batter") or {}).get("athlete") or {}).get("shortName") if isinstance(sit.get("batter"), dict) else None,
        "batterLine": (sit.get("batter") or {}).get("summary") if isinstance(sit.get("batter"), dict) else None,
        "downDistance": sit.get("downDistanceText") or sit.get("shortDownDistanceText"),
        "possession": sit.get("possessionText"),
        "lastPlay": (sit.get("lastPlay") or {}).get("text") if isinstance(sit.get("lastPlay"), dict) else None,
    }
    linescore = []
    for side in ("home", "away"):
        c = next((x for x in comp.get("competitors") or [] if x.get("homeAway") == side), {})
        linescore.append({"side": side, "periods": [str(ls.get("displayValue") or ls.get("value") or "") for ls in c.get("linescores") or []]})
    leaders = []
    for lg in data.get("leaders") or []:
        tid = str((lg.get("team") or {}).get("id") or "")
        for cat in lg.get("leaders") or []:
            for a in (cat.get("leaders") or [])[:1]:
                ath = a.get("athlete") or {}
                leaders.append({"side": "home" if tid == home_id else "away", "cat": cat.get("displayName") or cat.get("name"),
                                "name": ath.get("shortName") or ath.get("displayName"), "value": a.get("displayValue"),
                                "headshot": (ath.get("headshot") or {}).get("href") if isinstance(ath.get("headshot"), dict) else None})
    return {
        **hdr,
        "situation": situation,
        "linescore": linescore,
        "plays": plays,
        "winProbability": wp,
        "players": player_rows,
        "leaders": leaders[:12],
        "lastPlayId": plays[-1]["id"] if plays else None,
    }


@router.get("/teams")
async def teams(sport: str, league: str):
    """Every club in a league (for My Teams → Add Team)."""
    if (sport, league) not in LEAGUE_BY_KEY:
        raise HTTPException(404, "unknown league")
    try:
        data = await _get(f"{ESPN}/{sport}/{league}/teams", None, ttl=86400)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(502, f"espn teams failed: {e}")
    rows = []
    for row in ((data.get("sports") or [{}])[0].get("leagues") or [{}])[0].get("teams") or []:
        t = row.get("team") or {}
        if not t.get("id"):
            continue
        rows.append({"id": str(t["id"]), "name": t.get("displayName") or t.get("name"), "abbr": t.get("abbreviation"),
                     "logo": ((t.get("logos") or [{}])[0].get("href")), "color": t.get("color")})
    rows.sort(key=lambda r: (r["name"] or "").lower())
    return {"league": LEAGUE_BY_KEY[(sport, league)]["name"], "sport": sport, "leagueKey": league, "teams": rows}


@router.get("/standings")
async def standings(sport: str, league: str):
    if (sport, league) not in LEAGUE_BY_KEY:
        raise HTTPException(404, "unknown league")
    try:
        data = await _get(f"{ESPN_V2}/{sport}/{league}/standings", None, ttl=600)
    except Exception as e:  # noqa: BLE001
        raise HTTPException(502, f"espn standings failed: {e}")
    groups = []

    def walk(node: Dict[str, Any], title: str):
        st = node.get("standings") or {}
        entries = st.get("entries") or []
        if entries:
            rows = []
            for e in entries:
                t = e.get("team") or {}
                stats = {s.get("name") or s.get("type"): (s.get("displayValue") or s.get("value")) for s in e.get("stats") or []}
                rows.append({
                    "team": t.get("displayName") or t.get("name"), "abbr": t.get("abbreviation"), "logo": ((t.get("logos") or [{}])[0].get("href")),
                    "id": str(t.get("id") or ""),
                    "played": stats.get("gamesPlayed"), "wins": stats.get("wins"), "losses": stats.get("losses"), "ties": stats.get("ties"),
                    "points": stats.get("points"), "gd": stats.get("pointDifferential"), "pct": stats.get("winPercent"), "gb": stats.get("gamesBehind"),
                    "streak": stats.get("streak"), "rank": stats.get("rank") or e.get("rank"),
                })
            groups.append({"title": title or node.get("name") or "", "rows": rows})
        for ch in node.get("children") or []:
            walk(ch, ch.get("name") or ch.get("abbreviation") or title)

    walk(data, data.get("name") or LEAGUE_BY_KEY[(sport, league)]["name"])
    return {"league": LEAGUE_BY_KEY[(sport, league)]["name"], "groups": groups}
