"""v2.19.8 — Movie release-window tags for poster covers.

`GET /api/release-status?ids=tt1,tmdb:2` →
    { "tt1": {"cinema": bool, "quality": "hd"|"cam"|None} | null }

Accuracy rules (user spec — no more hallucinated tags):
  • CINEMA  — the movie is on TMDB's *now_playing* list (AU/US), i.e.
    genuinely showing in cinemas right now.  Same source that feeds
    the "In Cinema" shelf, so shelf + tag always agree.
  • HD/CAM  — decided from the ACTUAL stream links available for the
    title (Torrentio/EasyNews release names carry the source token:
    HDTS/CAM/TeleSync vs WEB-DL/BluRay/1080p).  We never guess from
    TMDB dates alone.
  • Unreleased titles (no past theatrical date, not now playing) get
    NO tag whatsoever.
  • Old / normally-released titles (digital copy out > 45 days, or
    theatrical > 240 days ago) get NO tag.
  • A recent theatrical-only title that is NOT in cinemas any more
    only gets a tag when the only copies out there are cams → "CAM".
Cached in-process 6h.
"""
import asyncio
import logging
import os
import re
import time
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Query

log = logging.getLogger("release_status")
router = APIRouter(prefix="/api")

_TMDB = "https://api.themoviedb.org/3"
_CACHE: dict[str, tuple[float, dict | None]] = {}
_TTL = 6 * 3600

# Release-name tokens.  Haystack is lowercased with ./_ turned into
# spaces first, so plain word boundaries are enough.
_CAM_RE = re.compile(
    r"\b(cam|camrip|hdcam|hd cam|hqcam|ts|hdts|hd ts|tsrip|telesync|"
    r"tele sync|tc|hdtc|telecine|dvdscr|screener|scr)\b")
_HD_RE = re.compile(
    r"\b(web dl|webdl|webrip|web|bluray|blu ray|bdrip|brrip|remux|"
    r"hdrip|dvdrip|hdtv|2160p|1080p|720p|4k|uhd)\b")

# ---- TMDB now_playing id set (AU + US), refreshed every 12h ---------
_NP: dict = {"ts": 0.0, "ids": set()}


async def _now_playing_ids(cli: httpx.AsyncClient) -> set:
    if _NP["ids"] and time.time() - _NP["ts"] < 12 * 3600:
        return _NP["ids"]
    reqs = [cli.get(f"{_TMDB}/movie/now_playing",
                    params={"page": p, "region": r})
            for r in ("AU", "US") for p in (1, 2, 3)]
    ids: set = set()
    for r in await asyncio.gather(*reqs, return_exceptions=True):
        if isinstance(r, Exception) or r.status_code != 200:
            continue
        for m in r.json().get("results") or []:
            if m.get("id"):
                ids.add(int(m["id"]))
    if ids:
        _NP["ts"] = time.time()
        _NP["ids"] = ids
    return _NP["ids"]


async def _status_for(cli: httpx.AsyncClient, key: str) -> dict | None:
    """Only tag whether the title is currently showing in cinemas
    (TMDB now_playing AU/US) — per user spec the poster cover shows
    nothing else.  No stream-quality / HD / CAM work is done."""
    if key.startswith("tmdb:"):
        mid = int(key.split(":", 1)[1])
    else:
        r = await cli.get(f"{_TMDB}/find/{key}",
                          params={"external_source": "imdb_id"})
        if r.status_code != 200:
            return None
        movie = (r.json().get("movie_results") or [None])[0]
        if not movie:
            return None
        mid = int(movie["id"])

    if mid in await _now_playing_ids(cli):
        return {"cinema": True}
    return None


@router.get("/release-status")
async def release_status(ids: str = Query("")):
    now = time.time()
    out: dict[str, dict | None] = {}
    todo: list[str] = []
    for raw in ids.split(","):
        i = raw.strip()
        if not (i.startswith("tt") or i.startswith("tmdb:")):
            continue
        hit = _CACHE.get(i)
        if hit and now - hit[0] < _TTL:
            out[i] = hit[1]
        elif i not in todo:
            todo.append(i)
    todo = todo[:60]
    token = os.environ.get("TMDB_BEARER_TOKEN")
    if todo and token:
        sem = asyncio.Semaphore(8)

        async def work(cli: httpx.AsyncClient, i: str) -> None:
            async with sem:
                try:
                    st = await _status_for(cli, i)
                except Exception:  # noqa: BLE001
                    st = None
                _CACHE[i] = (now, st)
                out[i] = st

        async with httpx.AsyncClient(
                timeout=10, headers={"Authorization": f"Bearer {token}"}) as cli:
            await asyncio.gather(*(work(cli, i) for i in todo))
    return out
