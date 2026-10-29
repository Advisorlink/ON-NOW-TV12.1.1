"""Game highlights from official league / team / broadcaster YouTube channels, played via the official embed."""
import asyncio
import html
import json
import logging
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode

import httpx
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import HTMLResponse

router = APIRouter()
log = logging.getLogger("sports_highlights")

OFFICIAL = {
    "nba", "nfl", "mlb", "nhl", "wnba", "mls", "premier league", "afl", "nrl", "nrl.com", "espn", "espn fc", "sky sports", "sky sports football",
    "sky sports premier league", "nbc sports", "cbs sports", "cbs sports golazo", "fox sports", "fox sports australia", "fox league", "fox footy",
    "kayo sports", "optus sport", "bein sports", "tnt sports", "laliga easports", "laliga", "bundesliga", "serie a", "ligue 1 mcdonald's", "uefa",
    "a-leagues", "nbl", "big bash league", "cricket.com.au", "formula 1", "ufc", "wwe", "super league", "rugby league world cup", "mlb network",
    "nfl network", "nba on tnt", "ussf", "concacaf", "seven network", "7afl", "7news australia", "9wide world of sport", "wide world of sports",
}
# Official league channels: matched by id (names vary, e.g. "NRL - National Rugby League") and used for the RSS fallback.
LEAGUE_CHANNELS = {
    "nfl": "UCDVYQ4Zhbm3S2dlz7P1GBDg", "nba": "UCWJ2lWNubArHWmf3FIHbfcQ", "mlb": "UCoLrcjPV5PbUrUyXq5mjc_A", "nhl": "UCqFMzb-4AUf6WAIbl132QKA",
    "premier league": "UCG5qGWdu8nIRZqJ_GgDwQ-w", "afl": "UCxAeHdLDAatFV_vt95MSO-g", "nrl": "UC33-OkQ6VCYk8xtml8Pk4-g",
    "formula 1": "UCB_qr75-ydFVKSF9Dmo6izg", "ufc": "UCvgfXK4nTYKudb0rFR6noLA",
}
RSS = "https://www.youtube.com/feeds/videos.xml"
_STOP = {"fc", "afc", "cf", "sc", "the", "city", "united", "club", "de"}
_cache: dict = {}
_allowed: dict = {}


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", (s or "").lower()).strip()


def _keys(name: str) -> set:
    words = [w for w in _norm(name).split() if w not in _STOP]
    keys = {" ".join(words)} if words else set()
    if words:
        keys.add(words[-1])
    if len(words) >= 2:
        keys.add(" ".join(words[-2:]))
    return {k for k in keys if len(k) >= 3}


def _mentions(title: str, name: str) -> bool:
    t = f" {_norm(title)} "
    return any(f" {k} " in t for k in _keys(name))


def _official(channel: str, home: str, away: str, channel_id: str = "") -> bool:
    c = _norm(channel)
    if channel_id in LEAGUE_CHANNELS.values() or c in {_norm(o) for o in OFFICIAL}:
        return True
    if any(c.startswith(_norm(o) + " ") for o in OFFICIAL if len(o) >= 3):
        return True
    return any(c == _norm(n) or (len(_norm(n)) > 4 and _norm(n) in c) for n in (home, away))


async def _rss(client: httpx.AsyncClient, league: str) -> list:
    """Latest ~15 uploads of the league's own channel; works even when YouTube search is unavailable from the server."""
    cid = LEAGUE_CHANNELS.get(_norm(league)) or next((v for k, v in LEAGUE_CHANNELS.items() if k in _norm(league)), None)
    if not cid:
        return []
    try:
        r = await client.get(RSS, params={"channel_id": cid})
        r.raise_for_status()
    except httpx.HTTPError:
        return []
    out = []
    for entry in re.findall(r"<entry>(.*?)</entry>", r.text, re.S):
        vid = re.search(r"<yt:videoId>([^<]+)</yt:videoId>", entry); title = re.search(r"<title>([^<]+)</title>", entry)
        if vid and title:
            out.append({"id": vid.group(1), "title": html.unescape(title.group(1)), "channel": league.upper(), "channel_id": cid, "duration": None})
    return out


def _search(query: str) -> list:
    import yt_dlp  # lazy: ~32 MB resident, only paid when a highlight lookup actually runs
    with yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True, "skip_download": True, "noplaylist": True}) as y:
        return (y.extract_info(f"ytsearch15:{query}", download=False) or {}).get("entries") or []


async def _embeddable(client: httpx.AsyncClient, vid: str) -> bool:
    try:
        r = await client.get(os.environ["SPORTS_YOUTUBE_OEMBED_URL"],
                             params={"url": os.environ["SPORTS_YOUTUBE_WATCH_URL"] + "?" + urlencode({"v": vid}), "format": "json"})
        return r.status_code == 200 and bool(r.json().get("html"))
    except Exception as e:  # noqa: BLE001 - one bad check must not fail the whole lookup
        log.warning("oembed check failed for %s: %s", vid, e)
        return False


@router.get("/highlights")
async def highlights(home: str, away: str, start_ms: int = Query(..., alias="startMs"), league: str = ""):
    """Up to 2 official highlight videos for one finished game (best first)."""
    key = (_norm(home), _norm(away), start_ms // 86_400_000)
    hit = _cache.get(key)
    if hit and hit[0] > time.time():
        return hit[1]
    day = datetime.fromtimestamp(start_ms / 1000 - 6 * 3600, tz=timezone.utc)
    query = f"{home} vs {away} highlights {day.strftime('%B')} {day.day} {day.year} {league}".strip()
    try:
        entries = await asyncio.to_thread(_search, query)
    except Exception as e:  # noqa: BLE001 - YouTube search can be blocked from datacenter IPs; fall back to channel RSS
        log.warning("highlight search failed (%s): %s", query, e)
        entries = []
    async with httpx.AsyncClient(timeout=8.0) as client:
        picks = _pick(entries, home, away)
        if not picks:
            picks = _pick(await _rss(client, league), home, away)
        ok = await asyncio.gather(*(_embeddable(client, p["videoId"]) for p in picks[:5]))
    videos = [p for p, good in zip(picks[:5], ok) if good][:2]
    for v in videos:
        _allowed[v["videoId"]] = v["title"]
    out = {"videos": videos}
    _cache[key] = (time.time() + (1800 if videos else 600), out)
    return out


def _pick(entries: list, home: str, away: str) -> list:
    picks = []
    for e in entries:
        title, channel, vid = e.get("title") or "", e.get("channel") or e.get("uploader") or "", e.get("id") or ""
        dur = e.get("duration")
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", vid) or (dur is not None and not (60 <= dur <= 2400)) or "#shorts" in title.lower():
            continue
        if not re.search(r"highlight|recap|extended", title, re.I) or re.search(r"\b(reaction|preview|prediction|live stream|fifa \d+|madden|nba 2k|simulation)\b", title, re.I):
            continue
        if not (_mentions(title, home) and _mentions(title, away)) or not _official(channel, home, away, e.get("channel_id") or ""):
            continue
        picks.append({"videoId": vid, "title": title, "channel": channel, "durationSec": int(dur or 0),
                      "thumbnail": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"})
    return picks


@router.get("/highlights/{video_id}/player", response_class=HTMLResponse)
async def highlight_player(video_id: str):
    if video_id not in _allowed:
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", video_id):
            raise HTTPException(404, "This highlight is unavailable inside ON NOW")
        async with httpx.AsyncClient(timeout=8.0) as client:
            r = await client.get(os.environ["SPORTS_YOUTUBE_OEMBED_URL"], params={"url": os.environ["SPORTS_YOUTUBE_WATCH_URL"] + "?" + urlencode({"v": video_id}), "format": "json"})
        title = r.json().get("title", "") if r.status_code == 200 else ""
        if not re.search(r"highlight|recap|extended", title, re.I):
            raise HTTPException(404, "This highlight is unavailable inside ON NOW")
        _allowed[video_id] = title
    payload = json.dumps({"videoId": video_id, "title": _allowed[video_id], "host": os.environ["SPORTS_YOUTUBE_EMBED_ORIGIN"].rstrip("/"),
                          "origin": os.environ["SPORTS_EMBED_ORIGIN"].rstrip("/"), "api": os.environ["SPORTS_YOUTUBE_IFRAME_API_URL"]}).replace("<", "\\u003c")
    html = Path(__file__).with_name("official_replay_player.html").read_text().replace("__REPLAY_CONFIG__", payload)
    return HTMLResponse(html, headers={"Referrer-Policy": "strict-origin-when-cross-origin", "Cache-Control": "no-store", "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline' https://www.youtube.com https://www.youtube-nocookie.com; frame-src https://www.youtube.com https://www.youtube-nocookie.com; style-src 'unsafe-inline'; img-src 'self' data: https://i.ytimg.com; connect-src 'self' https://www.youtube.com https://www.youtube-nocookie.com; object-src 'none'; base-uri 'none'"})
