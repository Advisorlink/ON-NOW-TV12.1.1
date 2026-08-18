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
_CACHE: dict[str, tuple[float, dict | None]] = {}
_TTL = 6 * 3600


async def _status_for(cli: httpx.AsyncClient, key: str) -> dict | None:
    if key.startswith("tmdb:"):
        mid = key.split(":", 1)[1]
    else:
        r = await cli.get(f"{_TMDB}/find/{key}", params={"external_source": "imdb_id"})
        if r.status_code != 200:
            return None
        movie = (r.json().get("movie_results") or [None])[0]
        if not movie:
            return None
        mid = movie["id"]
    r2 = await cli.get(f"{_TMDB}/movie/{mid}/release_dates")
    if r2.status_code != 200:
        return None
    # v2.19.7 — accuracy pass (user: some tags were wrong):
    #   • only WIDE theatrical (type 3) counts as "in cinema" — festival
    #     / limited premieres no longer trigger the tag.
    #   • streaming/rent/buy availability (TMDB watch providers) counts
    #     as a good HD copy even when the digital date is missing.
    theatrical = None
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
            if t == 3:
                theatrical = min(theatrical, dt) if theatrical else dt
            elif t in (4, 5):
                digital = min(digital, dt) if digital else dt
    now = datetime.now(timezone.utc)
    if not theatrical or theatrical > now:
        return None
    days = (now - theatrical).days
    hd = bool(digital and digital <= now)
    if not hd:
        try:
            r3 = await cli.get(f"{_TMDB}/movie/{mid}/watch/providers")
            if r3.status_code == 200:
                for region in (r3.json().get("results") or {}).values():
                    if any(region.get(k) for k in ("flatrate", "rent", "buy", "free", "ads")):
                        hd = True
                        break
        except Exception:  # noqa: BLE001
            pass
    if days <= 45:
        return {"cinema": True, "quality": "hd" if hd else "cam"}
    if not hd and days <= 210:
        return {"cinema": False, "quality": "cam"}
    return None  # good copy out / old title — no tag


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
