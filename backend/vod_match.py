"""
"ON NOW TV Direct" — match a Vesper movie against the Xtream VOD
catalogue the same login already pays for, and hand back a direct
`/movie/USER/PASS/ID.ext` link so autoplay uses it first.

  GET /api/vod/match?imdb_id=tt…[&title=&year=]      (Bearer optional)

Catalogue (18k titles, ~23 MB) is pulled from the managed provider
every 6 h, indexed by normalised title, kept in memory and mirrored
to Mongo (`xtream_vod_index`) so a restart doesn't cost a refetch.
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import time
import unicodedata
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

import httpx
import jwt
from fastapi import APIRouter, Header, Query

import instant_bundle as _ib
from auth_router import JWT_ALG, _jwt_secret

log = logging.getLogger("vod_match")
router = APIRouter(prefix="/api/vod", tags=["vod"])

CATALOG_TTL = 6 * 3600
_db = None
_tmdb_get = None
_index: Dict[str, List[Dict[str, Any]]] = {}
_fetched_at: float = 0.0
_lock = asyncio.Lock()
_refreshing = False


def configure_vod(db, tmdb_get) -> None:
    global _db, _tmdb_get
    _db = db
    _tmdb_get = tmdb_get


# ---------------------------------------------------------------------------
# Title normalisation
# ---------------------------------------------------------------------------
_YEAR_RE = re.compile(r"\s*[\(\[]\s*(19|20)\d{2}\s*[\)\]]\s*$")
_ROMAN = {"ii": "2", "iii": "3", "iv": "4", "v": "5", "vi": "6", "vii": "7", "viii": "8", "ix": "9"}


def _norm(title: str) -> str:
    t = unicodedata.normalize("NFKD", title or "").encode("ascii", "ignore").decode()
    t = t.lower().replace("&", " and ")
    t = re.sub(r"[''`]", "", t)
    t = re.sub(r"[^a-z0-9]+", " ", t).strip()
    words = [(_ROMAN.get(w, w)) for w in t.split()]
    if words and words[0] in ("the", "a", "an"):
        words = words[1:]
    return " ".join(words)


def _split_name(name: str):
    m = _YEAR_RE.search(name or "")
    year = int(re.search(r"(19|20)\d{2}", m.group(0)).group(0)) if m else None
    title = _YEAR_RE.sub("", name or "").strip()
    return title, year


# ---------------------------------------------------------------------------
# Catalogue refresh
# ---------------------------------------------------------------------------
def _provider() -> Dict[str, Any]:
    return _ib._provider_from_env()


async def _fetch_catalog() -> Dict[str, List[Dict[str, Any]]]:
    p = _provider()
    base = _ib._base_url(p)
    async with httpx.AsyncClient(timeout=90.0, verify=False, headers={"User-Agent": "ONNowTV/1.0"}) as c:
        r = await c.get(
            f"{base}/player_api.php",
            params={"username": p["username"], "password": p["password"], "action": "get_vod_streams"},
        )
        r.raise_for_status()
        rows = r.json()
    if not isinstance(rows, list):
        raise RuntimeError("get_vod_streams did not return a list")
    idx: Dict[str, List[Dict[str, Any]]] = {}
    seen: set = set()
    for row in rows:
        sid = str(row.get("stream_id") or "")
        if not sid or sid in seen:
            continue
        seen.add(sid)
        title, year = _split_name(row.get("name") or "")
        key = _norm(title)
        if not key:
            continue
        try:
            rating = float(row.get("rating") or 0)
        except (TypeError, ValueError):
            rating = 0.0
        idx.setdefault(key, []).append({
            "stream_id": sid,
            "name": row.get("name") or title,
            "title": title,
            "year": year,
            "ext": (row.get("container_extension") or "mkv").strip(".") or "mkv",
            "rating": rating,
            "added": int(row.get("added") or 0),
            "run_secs": int(row.get("episode_run_time") or 0),
        })
    return idx


async def _refresh(force: bool = False) -> None:
    global _index, _fetched_at, _refreshing
    if _refreshing:
        return
    _refreshing = True
    try:
        idx = await _fetch_catalog()
        _index = idx
        _fetched_at = time.time()
        if _db is not None:
            await _db.xtream_vod_index.update_one(
                {"_id": "managed"},
                {"$set": {"index": idx, "fetched_at": _fetched_at, "titles": len(idx)}},
                upsert=True,
            )
        log.info("VOD catalogue refreshed: %d titles", len(idx))
    except Exception as e:  # noqa: BLE001
        log.warning("VOD catalogue refresh failed: %s", e)
    finally:
        _refreshing = False


async def _ensure_catalog() -> None:
    global _index, _fetched_at
    if _index and time.time() - _fetched_at < CATALOG_TTL:
        return
    async with _lock:
        if not _index and _db is not None:
            doc = await _db.xtream_vod_index.find_one({"_id": "managed"})
            if doc and doc.get("index"):
                _index = doc["index"]
                _fetched_at = float(doc.get("fetched_at") or 0)
        if not _index:
            await _refresh()
        elif time.time() - _fetched_at >= CATALOG_TTL:
            asyncio.create_task(_refresh())


# ---------------------------------------------------------------------------
# Title lookup (IMDb → TMDB find, cached 7 d in memory)
# ---------------------------------------------------------------------------
_find_cache: Dict[str, Any] = {}


async def _titles_for(imdb_id: str, title: str, year: Optional[int]):
    titles = [t for t in [title] if t]
    if imdb_id and _tmdb_get is not None:
        hit = _find_cache.get(imdb_id)
        if hit is None or time.time() - hit["at"] > 7 * 86400:
            try:
                data = await _tmdb_get(f"/find/{imdb_id}", {"external_source": "imdb_id"})
                mv = (data.get("movie_results") or [None])[0] or {}
                hit = {
                    "at": time.time(),
                    "titles": [t for t in [mv.get("title"), mv.get("original_title")] if t],
                    "year": int(mv["release_date"][:4]) if mv.get("release_date") else None,
                }
            except Exception:  # noqa: BLE001
                hit = {"at": time.time(), "titles": [], "year": None}
            _find_cache[imdb_id] = hit
        titles = hit["titles"] + [t for t in titles if t not in hit["titles"]]
        year = year or hit["year"]
    return titles, year


def _account_from_bearer(authorization: Optional[str]) -> Optional[str]:
    if not authorization or not authorization.lower().startswith("bearer "):
        return None
    try:
        payload = jwt.decode(authorization.split(" ", 1)[1].strip(), _jwt_secret(), algorithms=[JWT_ALG])
        return payload.get("sub") or None
    except Exception:  # noqa: BLE001
        return None


def _fmt_run(secs: int) -> str:
    if not secs:
        return ""
    h, m = divmod(secs // 60, 60)
    return f"{h}h {m:02d}m" if h else f"{m}m"


# ---------------------------------------------------------------------------
# Endpoint
# ---------------------------------------------------------------------------
@router.get("/match")
async def vod_match(
    imdb_id: str = Query(default=""),
    title: str = Query(default=""),
    year: Optional[int] = Query(default=None),
    authorization: Optional[str] = Header(default=None),
) -> Dict[str, Any]:
    await _ensure_catalog()
    titles, year = await _titles_for(imdb_id.strip(), title.strip(), year)
    if not titles:
        return {"matches": [], "streams": []}

    cands: List[Dict[str, Any]] = []
    seen: set = set()
    for t in titles:
        for e in _index.get(_norm(t), []):
            if e["stream_id"] in seen:
                continue
            if year and e["year"] and abs(e["year"] - year) > 1:
                continue
            seen.add(e["stream_id"])
            cands.append(e)
    if not cands:
        return {"matches": [], "streams": []}
    cands.sort(key=lambda e: (0 if (year and e["year"] == year) else 1, -e["rating"], -e["added"]))
    cands = cands[:3]

    # Whose credentials go in the URL — the caller's own Xtream mapping
    # when the Vesper account has one, else the managed subscription.
    p = _provider()
    user, pwd = p["username"], p["password"]
    acct_id = _account_from_bearer(authorization)
    if acct_id and _db is not None:
        row = await _db.vesper_accounts.find_one({"id": acct_id}, {"_id": 0, "xtream_username": 1, "xtream_password": 1})
        if row and row.get("xtream_username") and row.get("xtream_password"):
            user, pwd = row["xtream_username"], row["xtream_password"]
    base = _ib._base_url(p)

    streams = []
    for i, e in enumerate(cands):
        url = f"{base}/movie/{user}/{pwd}/{e['stream_id']}.{e['ext']}"
        bits = [b for b in ["HD", _fmt_run(e["run_secs"]), e["ext"].upper(), f"★ {e['rating']:.1f}" if e["rating"] else ""] if b]
        label = "ON NOW TV Direct" if i == 0 else f"ON NOW TV Direct · Alt {i + 1}"
        meta = ("Recommended · " if i == 0 else "") + "Direct from your ON NOW TV subscription"
        streams.append({
            "name": label,
            # line 1 = heading shown in the picker, line 2 = meta strip
            "title": f"{label}\n{meta}\n{e['name']}" + (" · " + " · ".join(bits) if bits else ""),
            "url": url,
            "_onnow_direct": True,
            "_addon_id": "onnowtv-direct",
            "_addon_name": "ON NOW TV Direct",
            "_addon_source": "ON NOW TV",
            "_recommended": i == 0,
            "_quality_label": "HD",
            "_is_english": True,
            "_english_strict": True,
            "behaviorHints": {"notWebReady": False},
        })
    return {
        "matches": [{"stream_id": e["stream_id"], "name": e["name"], "year": e["year"]} for e in cands],
        "streams": streams,
        "catalog_titles": len(_index),
        "catalog_age_secs": int(time.time() - _fetched_at) if _fetched_at else None,
    }


@router.get("/status")
async def vod_status() -> Dict[str, Any]:
    return {
        "titles": len(_index),
        "fetched_at": datetime.fromtimestamp(_fetched_at, tz=timezone.utc).isoformat() if _fetched_at else None,
        "ttl_secs": CATALOG_TTL,
        "provider_host": os.environ.get("LIVETV_HOST", ""),
    }
