"""Sports Catch Up: turns archive programme titles into team-vs-team cards with crests (no scores)."""
from __future__ import annotations

import asyncio
import logging
import re
import time
import unicodedata
from typing import Any, Dict, List, Optional, Tuple

import httpx
from fastapi import APIRouter
from pydantic import BaseModel

log = logging.getLogger("sports_catchup")
router = APIRouter()

ESPN = "https://site.api.espn.com/apis/site/v2/sports"
SPORTSDB = "https://www.thesportsdb.com/api/v1/json/123"

BUCKET_LEAGUES: Dict[str, List[Tuple[str, str]]] = {
    "soccer": [("soccer", l) for l in ["eng.1", "eng.2", "esp.1", "ita.1", "ger.1", "fra.1", "aus.1", "usa.1",
                                        "sco.1", "ned.1", "por.1", "uefa.champions", "fifa.world"]],
    "afl": [("australian-football", "afl")],
    "nrl": [("rugby-league", "3"), ("rugby-league", "1")],
    "rugby": [("rugby", "242041"), ("rugby", "164205")],
    "nfl": [("football", "nfl")],
    "nba": [("basketball", "nba"), ("basketball", "nbl"), ("basketball", "wnba")],
    "nhl": [("hockey", "nhl")],
    "mlb": [("baseball", "mlb")],
}
SPORTSDB_SPORT = {"soccer": "Soccer", "afl": "Australian Football", "nrl": "Rugby", "rugby": "Rugby",
                  "nfl": "American Football", "nba": "Basketball", "nhl": "Ice Hockey", "mlb": "Baseball",
                  "cricket": "Cricket"}
AT_SPORTS = {"nfl", "nba", "nhl", "mlb"}

_teams: Dict[str, Tuple[float, List[Dict[str, Any]]]] = {}
_teams_lock = asyncio.Lock()
_results: Dict[str, Dict[str, Any]] = {}
_sportsdb: Dict[str, Optional[Dict[str, Any]]] = {}

_NOISE = re.compile(r"\b(live|replay|rpt|delayed|full match|full game|encore|repeat|new|hd|uhd|4k)\b")
_SCORE = re.compile(r"\s\d{1,3}\s*[-–:]\s*\d{1,3}\s")
_PAREN_SCORE = re.compile(r"\(\s*\d+[^)]*\)")


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s or "").encode("ascii", "ignore").decode().lower()
    return re.sub(r"\s+", " ", re.sub(r"[^a-z0-9]+", " ", s)).strip()


async def _team_index(bucket: str) -> List[Dict[str, Any]]:
    hit = _teams.get(bucket)
    if hit and time.time() - hit[0] < 86_400:
        return hit[1]
    async with _teams_lock:
        hit = _teams.get(bucket)
        if hit and time.time() - hit[0] < 86_400:
            return hit[1]
        out: List[Dict[str, Any]] = []
        async with httpx.AsyncClient(timeout=10, headers={"User-Agent": "Mozilla/5.0 ONNOWTV"}) as client:
            async def one(sport: str, league: str):
                try:
                    r = await client.get(f"{ESPN}/{sport}/{league}/teams")
                    r.raise_for_status()
                    return r.json()["sports"][0]["leagues"][0].get("teams") or []
                except Exception as exc:
                    log.warning("espn teams %s/%s failed: %s", sport, league, exc)
                    return []
            lists = await asyncio.gather(*(one(s, l) for s, l in BUCKET_LEAGUES.get(bucket, [])))
        for rows in lists:
            for row in rows:
                t = row.get("team") or {}
                display = t.get("displayName") or ""
                if not display:
                    continue
                keys = {norm(display), norm(t.get("shortDisplayName") or "")}
                nick = norm(t.get("name") or "")
                if len(nick) >= 4:
                    keys.add(nick)
                if t.get("location") and t.get("name"):
                    keys.add(norm(f"{t['location']} {t['name']}"))
                logos = t.get("logos") or []
                out.append({
                    "name": display,
                    "logo": logos[0].get("href") if logos else None,
                    "color": f"#{t['color']}" if t.get("color") else None,
                    "keys": [k for k in keys if len(k) >= 3],
                })
        _teams[bucket] = (time.time(), out)
        return out


def _match_team(side: str, index: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    padded = f" {norm(side)} "
    best, best_len = None, 0
    for team in index:
        for k in team["keys"]:
            if len(k) > best_len and f" {k} " in padded:
                best, best_len = team, len(k)
    return best


async def _sportsdb_team(name: str, bucket: str, client: httpx.AsyncClient) -> Optional[Dict[str, Any]]:
    key = f"{bucket}|{norm(name)}"
    if key in _sportsdb:
        return _sportsdb[key]
    found = None
    try:
        r = await client.get(f"{SPORTSDB}/searchteams.php", params={"t": name})
        if r.status_code == 200:
            for t in (r.json() or {}).get("teams") or []:
                if t.get("strSport") == SPORTSDB_SPORT.get(bucket) and t.get("strBadge"):
                    found = {"name": t.get("strTeam") or name, "logo": t["strBadge"],
                             "color": t.get("strColour1") or None}
                    break
        if r.status_code in (200, 404):
            _sportsdb[key] = found
    except Exception as exc:
        log.debug("sportsdb search failed %s: %s", name, exc)
    return found


def split_title(title: str, bucket: str) -> Optional[Tuple[str, str, bool]]:
    """Returns (left, right, right_is_home) or None when the title is not a two-team fixture."""
    t = _SCORE.sub(" v ", _PAREN_SCORE.sub(" ", f" {title or ''} "))
    seps = r"v|vs|v\.|vs\.|versus|@" + (r"|at" if bucket in AT_SPORTS else "")
    m = re.search(rf"\s+({seps})\s+", t, re.I)
    if not m:
        return None
    left, right = t[:m.start()], t[m.end():]
    left = re.split(r":|\s[-–|]\s|\|", left)[-1]
    right = re.split(r":|\s[-–|]\s|\||\(|,|\s-\s", right)[0]
    left = re.sub(r"\s+", " ", re.sub(_NOISE.pattern, " ", left, flags=re.I)).strip(" -–")
    right = re.sub(r"\s+", " ", re.sub(_NOISE.pattern, " ", right, flags=re.I)).strip(" -–")
    if not (2 <= len(left) <= 48 and 2 <= len(right) <= 48):
        return None
    if not (re.search(r"[A-Za-z]", left) and re.search(r"[A-Za-z]", right)):
        return None
    return left, right, m.group(1).lower() in ("@", "at")


def _side(raw: str, team: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if team:
        name = team["name"]
        n, r = norm(name), norm(raw)
        if f" {n} " in f" {r} " and len(r.split()) <= len(n.split()) + 1:
            name = raw.strip()
        return {"name": name, "logo": team.get("logo"), "color": team.get("color")}
    return {"name": raw.strip().title() if raw.islower() else raw.strip(), "logo": None, "color": None}


async def describe(title: str, bucket: str, client: httpx.AsyncClient, budget: List[int]) -> Dict[str, Any]:
    key = f"{bucket}|{title}"
    if key in _results:
        return _results[key]
    parts = split_title(title, bucket)
    if not parts:
        res = {"match": False}
        _results[key] = res
        return res
    left, right, right_home = parts
    index = await _team_index(bucket) if bucket in BUCKET_LEAGUES else []
    teams = []
    for raw in (left, right):
        team = _match_team(raw, index)
        if team is None and bucket in SPORTSDB_SPORT and budget[0] > 0:
            budget[0] -= 1
            team = await _sportsdb_team(raw, bucket, client)
        teams.append(_side(raw, team))
    home, away = (teams[1], teams[0]) if right_home else (teams[0], teams[1])
    res = {"match": True, "home": home, "away": away}
    if home["logo"] or away["logo"] or not index:
        _results[key] = res
    if len(_results) > 5000:
        _results.clear()
    return res


class CatchUpItem(BaseModel):
    title: str
    sport: str


class CatchUpRequest(BaseModel):
    items: List[CatchUpItem]


@router.post("/catchup-matches")
async def catchup_matches(body: CatchUpRequest):
    items = body.items[:200]
    budget = [8]
    async with httpx.AsyncClient(timeout=8, headers={"User-Agent": "Mozilla/5.0 ONNOWTV"}) as client:
        results = [await describe(i.title, i.sport, client, budget) for i in items]
    return {"items": results}
