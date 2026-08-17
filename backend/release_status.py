"""v2.19.4 — Movie release-window tags for poster covers.

`GET /api/release-status?ids=tt1,tt2` → { "tt1": "cinema" | "cam" | null }

Logic (TMDB release_dates, cached in-process 12h):
  - theatrical released, no digital/physical release yet:
      · ≤ 28 days since theatrical → "cinema"  (still in cinemas)
      · 29-210 days               → "cam"     (only cam copies out there)
  - digital/physical release out (a good copy exists) → no tag
  - unreleased / older titles / unknown → no tag
"""
import asyncio
import logging
import os
import time
from datetime import datetime, timezone

import httpx
from fastapi import APIRouter, Query

log = logging.getLogger("release_status")
router = APIRouter(prefix="/api")

_TMDB = "https://api.themoviedb.org/3"
_CACHE: dict[str, tuple[float, str | None]] = {}
_TTL = 12 * 3600


async def _status_for(cli: httpx.AsyncClient, imdb_id: str) -> str | None:
    r = await cli.get(f"{_TMDB}/find/{imdb_id}", params={"external_source": "imdb_id"})
    if r.status_code != 200:
        return None
    movie = (r.json().get("movie_results") or [None])[0]
    if not movie:
        return None
    r2 = await cli.get(f"{_TMDB}/movie/{movie['id']}/release_dates")
    if r2.status_code != 200:
        return None
    theatrical = None
    limited = None
    digital = None
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
            if t == 3:  # wide theatrical
                theatrical = min(theatrical, dt) if theatrical else dt
            elif t == 2:  # limited / festival — fallback only
                limited = min(limited, dt) if limited else dt
            elif t in (4, 5):  # digital / physical
                digital = min(digital, dt) if digital else dt
    # Prefer the wide-theatrical date: festival premieres (type 2) can
    # be months before general release and would mis-age the window.
    if theatrical is None:
        theatrical = limited
    now = datetime.now(timezone.utc)
    if not theatrical or theatrical > now:
        return None
    if digital and digital <= now:
        return None  # good copy exists
    days = (now - theatrical).days
    if days <= 28:
        return "cinema"
    if days <= 210:
        return "cam"
    return None  # old title with incomplete TMDB data — don't tag


@router.get("/release-status")
async def release_status(ids: str = Query("")):
    now = time.time()
    out: dict[str, str | None] = {}
    todo: list[str] = []
    for raw in ids.split(","):
        i = raw.strip()
        if not i.startswith("tt"):
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
