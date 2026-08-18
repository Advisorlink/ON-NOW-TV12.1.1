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


async def _stream_quality(cli: httpx.AsyncClient, mid: int,
                          imdb: str | None) -> str | None:
    """Check the REAL stream links for the title — 'hd' when a proper
    web/bluray copy exists, 'cam' when only cam copies are out there,
    None when nothing playable is found."""
    if not imdb:
        try:
            r = await cli.get(f"{_TMDB}/movie/{mid}/external_ids")
            if r.status_code == 200:
                imdb = r.json().get("imdb_id") or None
        except Exception:  # noqa: BLE001
            return None
    if not imdb or not imdb.startswith("tt"):
        return None
    import server  # lazy — avoids circular import at module load
    try:
        data = await asyncio.wait_for(
            server.streams_aggregate("movie", imdb), timeout=25)
    except Exception:  # noqa: BLE001
        return None
    hd = cam = False
    for s in (data or {}).get("streams") or []:
        if not isinstance(s, dict):
            continue
        if not (s.get("url") or s.get("infoHash")):
            continue  # rent/buy junk — not a real copy
        bh = s.get("behaviorHints") or {}
        txt = " ".join([
            str(s.get("title") or ""), str(s.get("name") or ""),
            str(s.get("description") or ""),
            str(bh.get("filename") or "") if isinstance(bh, dict) else "",
        ]).lower().replace(".", " ").replace("_", " ").replace("-", " ")
        if _CAM_RE.search(txt):
            cam = True
        elif _HD_RE.search(txt):
            hd = True
        elif s.get("_addon_source") == "EASYNEWS" or \
                s.get("_addon_id") == "com.stremio.torrentio.addon":
            # the user's Torrentio config already filters cam/scr
            # qualities out — any playable link from it is a good copy
            hd = True
    if hd:
        return "hd"  # any good copy beats stray cam rips
    if cam:
        return "cam"
    return None


async def _status_for(cli: httpx.AsyncClient, key: str) -> dict | None:
    imdb: str | None = None
    if key.startswith("tmdb:"):
        mid = int(key.split(":", 1)[1])
    else:
        imdb = key
        r = await cli.get(f"{_TMDB}/find/{key}",
                          params={"external_source": "imdb_id"})
        if r.status_code != 200:
            return None
        movie = (r.json().get("movie_results") or [None])[0]
        if not movie:
            return None
        mid = int(movie["id"])

    cinema = mid in await _now_playing_ids(cli)

    r2 = await cli.get(f"{_TMDB}/movie/{mid}/release_dates")
    theatrical = None
    digital = None
    if r2.status_code == 200:
        for country in r2.json().get("results") or []:
            for rd in country.get("release_dates") or []:
                raw = rd.get("release_date")
                if not raw:
                    continue
                try:
                    dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
                except ValueError:
                    continue
                t = rd.get("type")
                if t == 3:
                    theatrical = min(theatrical, dt) if theatrical else dt
                elif t in (4, 5):
                    digital = min(digital, dt) if digital else dt

    now = datetime.now(timezone.utc)
    if not cinema:
        if not theatrical or theatrical > now:
            return None  # unreleased — never tag
        if (now - theatrical).days > 240:
            return None  # old title
        if digital and (now - digital).days > 45:
            return None  # good copy long out — normal catalogue title

    quality = await _stream_quality(cli, mid, imdb)
    if cinema:
        return {"cinema": True, "quality": quality}
    return {"cinema": False, "quality": "cam"} if quality == "cam" else None


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
