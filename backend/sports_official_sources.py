"""Reviewed official full-game uploads via supported embeds; no external handoff/media extraction."""
import asyncio
import json
import os
import re
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit, urlencode
import httpx
from fastapi import APIRouter, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

router = APIRouter()

class OfficialReplay(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9-]{1,70}$")
    sport: str
    title: str
    channel: str
    channel_handle: str
    video_id: str = Field(pattern=r"^[A-Za-z0-9_-]{11}$")
    event_date: date | None = None
    event_start_ms: int | None = None
    event_end_ms: int | None = None
    event_completed: bool = False
    kind: Literal["official_embed"] = "official_embed"
    provider: Literal["youtube"] = "youtube"
    verified_full_match: Literal[True] = True

class ReplayDirectory(BaseModel):
    replays: list[OfficialReplay]
    status: Literal["ready", "partial", "unavailable", "not_connected", "empty"]
    notice: str = "Recent completed matches only. Official full-match availability depends on the publisher."

_cache: tuple[float, list[OfficialReplay], bool] | None = None
_lock = asyncio.Lock()

def recent_completed_event(row: OfficialReplay, now: datetime | None = None) -> bool:
    now = now or datetime.now(timezone.utc)
    if not row.event_completed or row.event_date is None:
        return False
    if not now.date() - timedelta(days=6) <= row.event_date <= now.date():
        return False
    if row.event_start_ms is not None and row.event_start_ms > int(now.timestamp() * 1000):
        return False
    if row.event_end_ms is not None and row.event_end_ms > int(now.timestamp() * 1000):
        return False
    # Today's event needs a confirmed end, not just a scheduled date or upload timestamp.
    if row.event_date == now.date() and (row.event_end_ms is None or row.event_end_ms <= 0):
        return False
    return True

def configuration():
    try:
        rows = [OfficialReplay.model_validate(row) for row in json.loads(os.environ["SPORTS_OFFICIAL_REPLAYS_JSON"])]
        origin = os.environ["SPORTS_EMBED_ORIGIN"].rstrip("/")
        embed = os.environ["SPORTS_YOUTUBE_EMBED_ORIGIN"].rstrip("/")
        api = os.environ["SPORTS_YOUTUBE_IFRAME_API_URL"]
        oembed = os.environ["SPORTS_YOUTUBE_OEMBED_URL"]
        watch = os.environ["SPORTS_YOUTUBE_WATCH_URL"]
        assert urlsplit(origin).scheme == "https" and urlsplit(origin).netloc and not urlsplit(origin).username and not urlsplit(origin).path and not urlsplit(origin).query
        assert urlsplit(embed).scheme == "https" and urlsplit(embed).netloc == "www.youtube-nocookie.com"
        assert all(urlsplit(u).scheme == "https" and urlsplit(u).hostname == "www.youtube.com" for u in (api, oembed, watch))
        assert all(row.sport in {"afl", "nrl", "rugby", "nfl", "soccer", "nba", "nhl", "mlb", "tennis", "cricket", "golf", "f1", "motorsport", "cycling", "athletics", "mma", "wwe", "other"} for row in rows)
        assert len({row.id for row in rows}) == len(rows) and len(rows) <= 60
        return rows, origin, embed, api, oembed, watch
    except (KeyError, ValueError, TypeError, AssertionError):
        raise HTTPException(503, "Official in-app replays are not configured correctly")

async def directory() -> ReplayDirectory:
    global _cache
    rows, _, _, _, oembed, watch = configuration()
    if not rows:
        return ReplayDirectory(replays=[], status="not_connected", notice="Automatic recent-match discovery is not connected. No classic or demo games are listed; IPTV recordings are unaffected.")
    rows = [row for row in rows if recent_completed_event(row)]
    if not rows:
        return ReplayDirectory(replays=[], status="empty", notice="No verified recent full-match replay is currently available from the connected official sources.")
    async with _lock:
        if _cache and _cache[0] > time.monotonic():
            accepted, failed = _cache[1], _cache[2]
        else:
            async with httpx.AsyncClient(timeout=8.0, follow_redirects=False) as client:
                async def check(row):
                    try:
                        response = await client.get(oembed, params={"url": watch + "?" + urlencode({"v": row.video_id}), "format": "json"})
                        response.raise_for_status(); data = response.json()
                        author = urlsplit(data.get("author_url", ""))
                        # Reviewed IDs AND uploader identity AND full-game title must still match.
                        if author.hostname != "www.youtube.com" or author.path.lower().rstrip("/") != row.channel_handle.lower(): return None
                        if not re.search(r"full\s+(?:match|game)", data.get("title", ""), re.I) or not data.get("html"): return None
                        return row
                    except (httpx.HTTPError, ValueError, TypeError):
                        return None
                checked = await asyncio.gather(*(check(row) for row in rows))
            accepted = [row for row in checked if row is not None]; failed = len(accepted) != len(rows)
            _cache = (time.monotonic() + (60 if failed else 300), accepted, failed)
    accepted = [row for row in accepted if recent_completed_event(row)]
    return ReplayDirectory(replays=accepted, status="unavailable" if not accepted else "partial" if failed else "ready")

@router.get("/official-replays", response_model=ReplayDirectory)
async def official_replays():
    return await directory()

@router.get("/official-replays/{source_id}/player", response_class=HTMLResponse)
async def official_player(source_id: str):
    row = next((item for item in (await directory()).replays if item.id == source_id), None)
    if row is None: raise HTTPException(404, "This official replay is unavailable inside ON NOW")
    _, origin, embed, api, _, _ = configuration()
    payload = json.dumps({"videoId": row.video_id, "title": row.title, "host": embed, "origin": origin, "api": api}).replace("<", "\\u003c")
    html = Path(__file__).with_name("official_replay_player.html").read_text().replace("__REPLAY_CONFIG__", payload)
    return HTMLResponse(html, headers={"Referrer-Policy": "strict-origin-when-cross-origin", "Cache-Control": "no-store", "Content-Security-Policy": "default-src 'self'; script-src 'self' 'unsafe-inline' https://www.youtube.com https://www.youtube-nocookie.com; frame-src https://www.youtube.com https://www.youtube-nocookie.com; style-src 'unsafe-inline'; img-src 'self' data: https://i.ytimg.com; connect-src 'self' https://www.youtube.com https://www.youtube-nocookie.com; object-src 'none'; base-uri 'none'"})