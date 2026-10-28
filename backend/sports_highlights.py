"""Game highlights from official league / team / broadcaster YouTube channels, played via the official embed."""
import asyncio
import json
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode

import httpx
import yt_dlp
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import HTMLResponse

router = APIRouter()

OFFICIAL = {
    "nba", "nfl", "mlb", "nhl", "wnba", "mls", "premier league", "afl", "nrl", "nrl.com", "espn", "espn fc", "sky sports", "sky sports football",
    "sky sports premier league", "nbc sports", "cbs sports", "cbs sports golazo", "fox sports", "fox sports australia", "fox league", "fox footy",
    "kayo sports", "optus sport", "bein sports", "tnt sports", "laliga easports", "laliga", "bundesliga", "serie a", "ligue 1 mcdonald's", "uefa",
    "a-leagues", "nbl", "big bash league", "cricket.com.au", "formula 1", "ufc", "wwe", "super league", "rugby league world cup", "mlb network",
    "nfl network", "nba on tnt", "ussf", "concacaf", "seven network", "7afl", "7news australia", "9wide world of sport", "wide world of sports",
}
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


def _official(channel: str, home: str, away: str) -> bool:
    c = _norm(channel)
    return c in {_norm(o) for o in OFFICIAL} or any(c == _norm(n) or (len(_norm(n)) > 4 and _norm(n) in c) for n in (home, away))


def _search(query: str) -> list:
    with yt_dlp.YoutubeDL({"quiet": True, "extract_flat": True, "skip_download": True, "noplaylist": True}) as y:
        return (y.extract_info(f"ytsearch15:{query}", download=False) or {}).get("entries") or []


async def _embeddable(client: httpx.AsyncClient, vid: str) -> bool:
    try:
        r = await client.get(os.environ["SPORTS_YOUTUBE_OEMBED_URL"],
                             params={"url": os.environ["SPORTS_YOUTUBE_WATCH_URL"] + "?" + urlencode({"v": vid}), "format": "json"})
        return r.status_code == 200 and bool(r.json().get("html"))
    except (httpx.HTTPError, ValueError):
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
    except Exception:  # noqa: BLE001
        raise HTTPException(502, "highlight search failed")
    picks = []
    for e in entries:
        title, channel, dur, vid = e.get("title") or "", e.get("channel") or e.get("uploader") or "", e.get("duration") or 0, e.get("id") or ""
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", vid) or not (60 <= dur <= 2400):
            continue
        if not re.search(r"highlight|recap|extended", title, re.I) or re.search(r"\b(reaction|preview|prediction|live stream|fifa \d+|madden|nba 2k|simulation)\b", title, re.I):
            continue
        if not (_mentions(title, home) and _mentions(title, away)) or not _official(channel, home, away):
            continue
        picks.append({"videoId": vid, "title": title, "channel": channel, "durationSec": int(dur),
                      "thumbnail": f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg"})
    async with httpx.AsyncClient(timeout=8.0) as client:
        ok = await asyncio.gather(*(_embeddable(client, p["videoId"]) for p in picks[:5]))
    videos = [p for p, good in zip(picks[:5], ok) if good][:2]
    for v in videos:
        _allowed[v["videoId"]] = v["title"]
    out = {"videos": videos}
    _cache[key] = (time.time() + (1800 if videos else 600), out)
    return out


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
