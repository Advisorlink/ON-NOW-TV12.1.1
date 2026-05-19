"""
Vesper backend
==============
A FastAPI server providing:
  • Stremio addon protocol *client* endpoints (consume public addons)
  • Per-user installed-addon storage in MongoDB
  • Lightweight proxy + cache for catalog / meta / stream calls

Single-user mode for now: a fixed user_id "default" is used.  Will be
swapped for proper auth in a later iteration.
"""

from __future__ import annotations

import os
import logging
import math
import re
import time
import uuid
import io
import asyncio
from datetime import datetime, timezone, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import quote

import httpx
from dotenv import load_dotenv
from fastapi import APIRouter, Body, FastAPI, HTTPException, Query
from fastapi.responses import Response
from PIL import Image, UnidentifiedImageError
from motor.motor_asyncio import AsyncIOMotorClient
from pydantic import BaseModel, Field
from starlette.middleware.cors import CORSMiddleware

ROOT_DIR = Path(__file__).parent
load_dotenv(ROOT_DIR / ".env")

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
DEFAULT_USER = "default"  # single-user mode for v1
CACHE_TTL_CATALOG = 600  # 10 min
CACHE_TTL_META = 24 * 3600  # 24 h
CACHE_TTL_STREAM = 300  # 5 min
HTTP_TIMEOUT = 15.0

# Curated default addons – Cinemeta is the IMDB-id metadata backbone of the
# Stremio ecosystem and is offered here as the suggested first install.
SUGGESTED_ADDONS = [
    {
        "name": "Cinemeta",
        "url": "https://v3-cinemeta.strem.io/manifest.json",
        "description": "IMDB-style catalogues + rich metadata for movies & series",
    },
    {
        "name": "OpenSubtitles v3",
        "url": "https://opensubtitles-v3.strem.io/manifest.json",
        "description": "Subtitle search across the OpenSubtitles database",
    },
    # Torrentio URL is computed at boot from PREMIUMIZE_API_KEY (.env)
    # so we get HTTPS Debrid streams instead of magnets, and so the
    # cam / SCR / 480p / 720p tiers never reach the source list.
    # The seeder pulls the built URL via `_torrentio_manifest_url()`.
    {
        "name": "Torrentio",
        "url": "PLACEHOLDER_TORRENTIO_URL",
        "description": "Stream resolver — Debrid-powered HTTPS streams via Premiumize · 1080p / 4K only",
    },
    {
        "name": "WatchHub",
        "url": "https://watchhub.strem.io/manifest.json",
        "description": "Streams from legal/free sources (no piracy) — built into Stremio's official ecosystem",
    },
]

# ---------------------------------------------------------------------------
# Mongo
# ---------------------------------------------------------------------------
mongo_url = os.environ["MONGO_URL"]
mongo = AsyncIOMotorClient(mongo_url)
db = mongo[os.environ["DB_NAME"]]

# ---------------------------------------------------------------------------
# In-memory cache (small, simple)
# ---------------------------------------------------------------------------


class TTLCache:
    def __init__(self) -> None:
        self._d: Dict[str, tuple[float, Any]] = {}
        self._lock = asyncio.Lock()

    async def get(self, key: str) -> Any | None:
        async with self._lock:
            v = self._d.get(key)
            if not v:
                return None
            expiry, value = v
            if time.time() > expiry:
                self._d.pop(key, None)
                return None
            return value

    async def set(self, key: str, value: Any, ttl: int) -> None:
        async with self._lock:
            self._d[key] = (time.time() + ttl, value)

    async def clear(self) -> None:
        async with self._lock:
            self._d.clear()


cache = TTLCache()

# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------


class StatusCheck(BaseModel):
    id: str = Field(default_factory=lambda: str(uuid.uuid4()))
    client_name: str
    timestamp: datetime = Field(
        default_factory=lambda: datetime.now(timezone.utc)
    )


class StatusCheckCreate(BaseModel):
    client_name: str


class AddonInstallRequest(BaseModel):
    url: str
    # Optional pre-fetched manifest.  When the frontend can fetch the
    # addon directly (residential IP, no Cloudflare bot wall), it sends
    # the parsed manifest along and we skip the server-side HTTP fetch.
    manifest: Optional[Dict[str, Any]] = None


class AddonOut(BaseModel):
    id: str
    url: str
    name: str
    description: str
    version: str
    logo: Optional[str] = None
    types: List[str] = []
    resources: List[Any] = []
    catalogs: List[Dict[str, Any]] = []
    id_prefixes: List[str] = []
    installed_at: datetime


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _normalize_manifest_url(url: str) -> tuple[str, str]:
    """Return (base_url, manifest_url)."""
    url = url.strip()
    if not url:
        raise HTTPException(400, "Empty URL")
    if not url.startswith(("http://", "https://")):
        url = "https://" + url
    if url.endswith("/manifest.json"):
        manifest = url
        base = url[: -len("/manifest.json")]
    else:
        base = url.rstrip("/")
        manifest = base + "/manifest.json"
    return base, manifest


def _resource_supported(manifest: Dict[str, Any], resource: str) -> bool:
    for r in manifest.get("resources", []):
        if isinstance(r, str) and r == resource:
            return True
        if isinstance(r, dict) and r.get("name") == resource:
            return True
    return False


def _id_prefix_match(manifest: Dict[str, Any], item_id: str) -> bool:
    prefixes = manifest.get("idPrefixes") or manifest.get("id_prefixes") or []
    if not prefixes:
        return True
    return any(item_id.startswith(p) for p in prefixes)


async def _fetch_json(client: httpx.AsyncClient, url: str) -> Any:
    try:
        r = await client.get(
            url,
            timeout=HTTP_TIMEOUT,
            headers={"User-Agent": "OnNowTV/1.0 (+https://onnowtv.app)"},
            follow_redirects=True,
        )
        r.raise_for_status()
        return r.json()
    except httpx.HTTPStatusError as e:
        raise HTTPException(e.response.status_code, f"Upstream error: {e}")
    except httpx.RequestError as e:
        raise HTTPException(504, f"Upstream timeout / network error: {e}")
    except ValueError:
        raise HTTPException(502, "Upstream did not return JSON")


def _build_extra_path(extra: Optional[Dict[str, str]]) -> str:
    if not extra:
        return ""
    parts = [f"{quote(k)}={quote(str(v))}" for k, v in extra.items() if v]
    return ("/" + "&".join(parts)) if parts else ""


# ---------------------------------------------------------------------------
# FastAPI app
# ---------------------------------------------------------------------------
app = FastAPI(title="ON NOW TV V2")
api = APIRouter(prefix="/api")


@api.get("/")
async def root():
    return {"app": "ON NOW TV V2", "version": "1.0.0"}


@api.post("/status", response_model=StatusCheck)
async def create_status(input: StatusCheckCreate):
    obj = StatusCheck(client_name=input.client_name)
    doc = obj.model_dump()
    doc["timestamp"] = doc["timestamp"].isoformat()
    await db.status_checks.insert_one(doc)
    return obj


@api.get("/status", response_model=List[StatusCheck])
async def list_status():
    rows = await db.status_checks.find({}, {"_id": 0}).to_list(1000)
    for r in rows:
        if isinstance(r.get("timestamp"), str):
            r["timestamp"] = datetime.fromisoformat(r["timestamp"])
    return rows


# ----- Addons --------------------------------------------------------------


@api.get("/addons/suggested")
async def addons_suggested():
    return {"suggested": SUGGESTED_ADDONS}


@api.get("/addons", response_model=List[AddonOut])
async def addons_list():
    rows = await db.addons.find(
        {"user_id": DEFAULT_USER, "active": True}, {"_id": 0}
    ).to_list(1000)
    out: List[AddonOut] = []
    for r in rows:
        m = r.get("manifest", {})
        out.append(
            AddonOut(
                id=m.get("id") or r["_id_str"],
                url=r["url"],
                name=m.get("name", "Unknown"),
                description=m.get("description", ""),
                version=m.get("version", "0.0.0"),
                logo=m.get("logo"),
                types=m.get("types", []),
                resources=m.get("resources", []),
                catalogs=m.get("catalogs", []),
                id_prefixes=m.get("idPrefixes")
                or m.get("id_prefixes")
                or [],
                installed_at=datetime.fromisoformat(r["installed_at"])
                if isinstance(r.get("installed_at"), str)
                else r["installed_at"],
            )
        )
    return out


@api.post("/addons/install")
async def addons_install(req: AddonInstallRequest):
    base, manifest_url = _normalize_manifest_url(req.url)

    # Prefer the manifest the client supplied (it could fetch from a
    # residential IP that the upstream allows; our datacentre IP often
    # gets bot-walled by Cloudflare).  Otherwise fetch ourselves.
    if req.manifest and isinstance(req.manifest, dict):
        manifest = req.manifest
    else:
        async with httpx.AsyncClient() as client:
            manifest = await _fetch_json(client, manifest_url)

    if not isinstance(manifest, dict):
        raise HTTPException(400, "Manifest is not an object")
    for required in ("id", "name", "version"):
        if required not in manifest:
            raise HTTPException(400, f"Manifest missing required field '{required}'")
    if not manifest.get("resources"):
        raise HTTPException(400, "Manifest declares no resources")

    addon_id = manifest["id"]
    now = datetime.now(timezone.utc).isoformat()
    await db.addons.update_one(
        {"user_id": DEFAULT_USER, "addon_id": addon_id},
        {
            "$set": {
                "user_id": DEFAULT_USER,
                "addon_id": addon_id,
                "_id_str": addon_id,
                "url": base,
                "manifest": manifest,
                "active": True,
                "installed_at": now,
                "updated_at": now,
            }
        },
        upsert=True,
    )
    await cache.clear()
    return {
        "ok": True,
        "addon": {
            "id": addon_id,
            "name": manifest.get("name"),
            "description": manifest.get("description"),
            "version": manifest.get("version"),
            "logo": manifest.get("logo"),
            "url": base,
            "types": manifest.get("types", []),
            "catalogs": manifest.get("catalogs", []),
        },
    }


@api.delete("/addons/{addon_id}")
async def addons_remove(addon_id: str):
    res = await db.addons.update_one(
        {"user_id": DEFAULT_USER, "addon_id": addon_id},
        {"$set": {"active": False}},
    )
    if res.matched_count == 0:
        raise HTTPException(404, "Addon not installed")
    await cache.clear()
    return {"ok": True}


async def _addon_doc(addon_id: str) -> Dict[str, Any]:
    doc = await db.addons.find_one(
        {"user_id": DEFAULT_USER, "addon_id": addon_id, "active": True},
        {"_id": 0},
    )
    if not doc:
        raise HTTPException(404, f"Addon '{addon_id}' not installed")
    return doc


@api.get("/addons/{addon_id}/catalog/{type_}/{catalog_id}")
async def addons_catalog(
    addon_id: str,
    type_: str,
    catalog_id: str,
    search: Optional[str] = None,
    skip: Optional[int] = None,
    genre: Optional[str] = None,
):
    doc = await _addon_doc(addon_id)
    if not _resource_supported(doc["manifest"], "catalog"):
        raise HTTPException(400, "Addon does not provide catalog resource")

    extra: Dict[str, str] = {}
    if search:
        extra["search"] = search
    if skip:
        extra["skip"] = str(skip)
    if genre:
        extra["genre"] = genre

    cache_key = f"cat:{addon_id}:{type_}:{catalog_id}:{search or ''}:{skip or 0}:{genre or ''}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    extra_path = _build_extra_path(extra)
    url = f"{doc['url']}/catalog/{type_}/{catalog_id}{extra_path}.json"
    async with httpx.AsyncClient() as client:
        data = await _fetch_json(client, url)

    await cache.set(cache_key, data, CACHE_TTL_CATALOG)
    return {"cached": False, "data": data}


@api.get("/meta/{type_}/{item_id}")
async def meta_aggregate(type_: str, item_id: str):
    """Try every installed addon that supports meta + the id prefix.  First
    successful result wins (Cinemeta first if present)."""
    cache_key = f"meta:{type_}:{item_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    addons = await db.addons.find(
        {"user_id": DEFAULT_USER, "active": True}, {"_id": 0}
    ).to_list(100)
    # Cinemeta first
    addons.sort(
        key=lambda a: 0 if "cinemeta" in (a.get("addon_id") or "").lower() else 1
    )

    async with httpx.AsyncClient() as client:
        for a in addons:
            m = a["manifest"]
            if not _resource_supported(m, "meta"):
                continue
            if not _id_prefix_match(m, item_id):
                continue
            url = f"{a['url']}/meta/{type_}/{item_id}.json"
            try:
                data = await _fetch_json(client, url)
                if isinstance(data, dict) and data.get("meta"):
                    await cache.set(cache_key, data, CACHE_TTL_META)
                    return {"cached": False, "data": data, "source": a["addon_id"]}
            except HTTPException:
                continue
        # Last-resort fallback to public Cinemeta even if not installed
        try:
            url = f"https://v3-cinemeta.strem.io/meta/{type_}/{item_id}.json"
            data = await _fetch_json(client, url)
            await cache.set(cache_key, data, CACHE_TTL_META)
            return {"cached": False, "data": data, "source": "cinemeta-fallback"}
        except HTTPException:
            pass
    raise HTTPException(404, "No metadata available from any installed addon")


@api.get("/streams/{type_}/{item_id}")
async def streams_aggregate(type_: str, item_id: str):
    """Aggregate streams from every installed addon that provides the
    stream resource and matches the id prefix."""
    cache_key = f"str:{type_}:{item_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "streams": cached}

    addons = await db.addons.find(
        {"user_id": DEFAULT_USER, "active": True}, {"_id": 0}
    ).to_list(100)

    out: List[Dict[str, Any]] = []
    async with httpx.AsyncClient() as client:
        async def fetch(a: Dict[str, Any]):
            m = a["manifest"]
            if not _resource_supported(m, "stream"):
                return []
            if not _id_prefix_match(m, item_id):
                return []
            url = f"{a['url']}/stream/{type_}/{item_id}.json"
            try:
                data = await _fetch_json(client, url)
            except HTTPException:
                return []
            streams = data.get("streams", []) if isinstance(data, dict) else []
            tagged: List[Dict[str, Any]] = []
            for s in streams:
                if not isinstance(s, dict):
                    continue
                tagged.append(
                    {
                        **s,
                        "_addon_id": a["addon_id"],
                        "_addon_name": m.get("name", a["addon_id"]),
                    }
                )
            return tagged

        results = await asyncio.gather(
            *[fetch(a) for a in addons], return_exceptions=True
        )

    for r in results:
        if isinstance(r, list):
            out.extend(r)

    await cache.set(cache_key, out, CACHE_TTL_STREAM)
    return {"cached": False, "streams": out}


@api.get("/subtitles/{type_}/{item_id}")
async def subtitles_aggregate(type_: str, item_id: str):
    """Aggregate subtitle tracks from every installed addon that
    provides the subtitles resource (e.g. OpenSubtitles v3)."""
    cache_key = f"sub:{type_}:{item_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "subtitles": cached}

    addons = await db.addons.find(
        {"user_id": DEFAULT_USER, "active": True}, {"_id": 0}
    ).to_list(100)

    out: List[Dict[str, Any]] = []
    async with httpx.AsyncClient() as client:
        async def fetch(a: Dict[str, Any]):
            m = a["manifest"]
            if not _resource_supported(m, "subtitles"):
                return []
            url = f"{a['url']}/subtitles/{type_}/{item_id}.json"
            try:
                data = await _fetch_json(client, url)
            except HTTPException:
                return []
            subs = data.get("subtitles", []) if isinstance(data, dict) else []
            tagged: List[Dict[str, Any]] = []
            for s in subs:
                if not isinstance(s, dict) or not s.get("url"):
                    continue
                tagged.append(
                    {
                        **s,
                        "_addon_id": a["addon_id"],
                        "_addon_name": m.get("name", a["addon_id"]),
                    }
                )
            return tagged

        results = await asyncio.gather(
            *[fetch(a) for a in addons], return_exceptions=True
        )

    for r in results:
        if isinstance(r, list):
            out.extend(r)

    await cache.set(cache_key, out, CACHE_TTL_META)
    return {"cached": False, "subtitles": out}


# ----- TMDB networks (full library per streaming network) -----------------
TMDB_BEARER = os.environ.get("TMDB_BEARER_TOKEN", "")
TMDB_BASE = "https://api.themoviedb.org/3"
TMDB_IMG = "https://image.tmdb.org/t/p"

# Slug → TMDB *watch provider* id (works for both /discover/tv and
# /discover/movie via with_watch_providers).  This is what TMDB
# considers "currently streamable on the platform" rather than just
# "produced by", which is what users actually mean.
NETWORK_PROVIDERS: Dict[str, Dict[str, Any]] = {
    "netflix": {"id": 8, "label": "Netflix"},
    "hbo": {"id": 1899, "label": "Max"},
    "disney-plus": {"id": 337, "label": "Disney Plus"},
    "prime-video": {"id": 9, "label": "Amazon Prime Video"},
    "apple-tv": {"id": 350, "label": "Apple TV Plus"},
    "paramount-plus": {"id": 531, "label": "Paramount Plus"},
    "hulu": {"id": 15, "label": "Hulu"},
    "binge": {"id": 385, "label": "BINGE"},
    "stan": {"id": 21, "label": "Stan"},
}

CACHE_TTL_NETWORK = 3600          # 1 hour — TMDB's discover updates daily
CACHE_TTL_TMDB_IMDB = 7 * 24 * 3600  # 7 days — external_id mappings are stable


async def _tmdb_get(path: str, params: Optional[Dict[str, Any]] = None) -> Any:
    if not TMDB_BEARER:
        raise HTTPException(503, "TMDB integration not configured")
    headers = {
        "Authorization": f"Bearer {TMDB_BEARER}",
        "accept": "application/json",
    }
    async with httpx.AsyncClient(timeout=HTTP_TIMEOUT) as client:
        try:
            r = await client.get(
                f"{TMDB_BASE}{path}",
                headers=headers,
                params=params or {},
            )
            r.raise_for_status()
            return r.json()
        except httpx.HTTPStatusError as e:
            raise HTTPException(
                e.response.status_code, f"TMDB error: {e.response.text[:200]}"
            )
        except httpx.RequestError as e:
            raise HTTPException(504, f"TMDB network error: {e}")


@api.get("/networks/logos")
async def network_logos():
    """Return TMDB-hosted logo URLs + brand colors for every network
    we expose under /networks/:slug.  Cached aggressively (24 h) since
    TMDB watch-provider logo paths are extremely stable."""
    cache_key = "networks:logos:v2"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    # Pull every watch provider TMDB knows about (movies + tv), build
    # an id → logo_path map.
    movie = await _tmdb_get("/watch/providers/movie")
    tv = await _tmdb_get("/watch/providers/tv")
    by_id: Dict[int, str] = {}
    for entry in (movie.get("results") or []) + (tv.get("results") or []):
        pid = entry.get("provider_id")
        lp = entry.get("logo_path")
        if pid and lp and pid not in by_id:
            by_id[pid] = lp

    out: Dict[str, Dict[str, Optional[str]]] = {}
    for slug, cfg in NETWORK_PROVIDERS.items():
        lp = by_id.get(cfg["id"])
        out[slug] = {
            "name": cfg["label"],
            # Use w300 instead of `original` — TMDB wordmark logos
            # are sharp at this size and roughly 6-10x smaller than
            # the originals, which makes the Browse-by-Network rail
            # render noticeably faster on low-power Android boxes.
            "logo": f"{TMDB_IMG}/w300{lp}" if lp else None,
        }

    await cache.set(cache_key, out, 24 * 3600)
    return {"cached": False, "data": out}


@api.get("/networks/{slug}")
async def network_titles(
    slug: str,
    type_: str = Query("tv", alias="type"),
    page: int = 1,
    region: str = "US",
):
    """Discover titles streamable on a given network via TMDB."""
    cfg = NETWORK_PROVIDERS.get(slug)
    if not cfg:
        raise HTTPException(404, f"Unknown network '{slug}'")
    if type_ not in ("tv", "movie"):
        raise HTTPException(400, "type must be 'tv' or 'movie'")
    if page < 1 or page > 500:
        raise HTTPException(400, "page out of range")

    cache_key = f"net:{slug}:{type_}:{region}:{page}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    data = await _tmdb_get(
        f"/discover/{type_}",
        {
            "with_watch_providers": cfg["id"],
            "watch_region": region,
            "page": page,
            "sort_by": "popularity.desc",
        },
    )

    is_tv = type_ == "tv"
    results: List[Dict[str, Any]] = []
    for item in data.get("results", []) or []:
        results.append(
            {
                "tmdb_id": item.get("id"),
                "type": "series" if is_tv else "movie",
                "title": item.get("name") if is_tv else item.get("title"),
                "poster": (
                    f"{TMDB_IMG}/w500{item['poster_path']}"
                    if item.get("poster_path")
                    else None
                ),
                "backdrop": (
                    f"{TMDB_IMG}/w1280{item['backdrop_path']}"
                    if item.get("backdrop_path")
                    else None
                ),
                "overview": item.get("overview"),
                "year": (
                    item.get("first_air_date") if is_tv else item.get("release_date")
                ) or "",
                "rating": item.get("vote_average") or 0,
            }
        )

    out = {
        "page": data.get("page") or page,
        "total_pages": min(data.get("total_pages") or 1, 500),
        "total_results": data.get("total_results") or len(results),
        "network": cfg["label"],
        "type": type_,
        "results": results,
    }
    await cache.set(cache_key, out, CACHE_TTL_NETWORK)
    return {"cached": False, "data": out}


@api.get("/tmdb/imdb/{type_}/{tmdb_id}")
async def tmdb_to_imdb(type_: str, tmdb_id: int):
    """Resolve a TMDB id → IMDB id so the front-end can route into the
    existing /title/{type}/{imdb_id} detail page."""
    if type_ not in ("tv", "movie"):
        raise HTTPException(400, "type must be 'tv' or 'movie'")

    cache_key = f"tmdb_imdb:{type_}:{tmdb_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "imdb_id": cached}

    data = await _tmdb_get(f"/{type_}/{tmdb_id}/external_ids")
    imdb = data.get("imdb_id") or None
    if imdb:
        await cache.set(cache_key, imdb, CACHE_TTL_TMDB_IMDB)
    return {"cached": False, "imdb_id": imdb}


# ----- Detail-page extras: cast, recommendations, person -----------------------

@api.get("/tmdb/find-by-imdb/{imdb_id}")
async def tmdb_find_by_imdb(imdb_id: str):
    """Resolve `imdb_id` (tt-prefixed) → TMDB id + media_type.

    Used by the Detail page to drive the cast row + recommendations
    row without forcing the front-end to know the TMDB id.

    Cached for 7 days (the IMDB↔TMDB mapping is rock-stable).
    """
    if not imdb_id.startswith("tt"):
        raise HTTPException(400, "imdb_id must start with 'tt'")
    cache_key = f"find_by_imdb:{imdb_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, **cached}

    data = await _tmdb_get(
        f"/find/{imdb_id}", {"external_source": "imdb_id"}
    )
    out: Dict[str, Any] = {"tmdb_id": None, "media_type": None}
    if data.get("movie_results"):
        out["tmdb_id"] = data["movie_results"][0].get("id")
        out["media_type"] = "movie"
    elif data.get("tv_results"):
        out["tmdb_id"] = data["tv_results"][0].get("id")
        out["media_type"] = "tv"
    if out["tmdb_id"]:
        await cache.set(cache_key, out, CACHE_TTL_TMDB_IMDB)
    return {"cached": False, **out}


@api.get("/tmdb/credits/{type_}/{tmdb_id}")
async def tmdb_credits(type_: str, tmdb_id: int):
    """Return the top-billed cast for a movie or TV show.

    Shape:
        {
          cast: [
            { id, name, character, profile_path, order },
            ...
          ]
        }

    Only the first 20 billed cast members are returned — that's
    plenty for the horizontal Cast row on the Detail page and keeps
    the payload small (~3 KB).  Cached for 7 days because cast lists
    don't change between releases.
    """
    if type_ not in ("tv", "movie"):
        raise HTTPException(400, "type must be 'tv' or 'movie'")
    cache_key = f"credits:{type_}:{tmdb_id}:v1"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "cast": cached}

    data = await _tmdb_get(f"/{type_}/{tmdb_id}/credits")
    raw = data.get("cast") or []
    raw.sort(key=lambda c: c.get("order", 999))
    cast: List[Dict[str, Any]] = []
    for c in raw[:20]:
        profile = c.get("profile_path")
        cast.append({
            "id":           c.get("id"),
            "name":         c.get("name") or "",
            "character":    c.get("character") or "",
            "profile_path": profile or "",
            "profile":      f"{TMDB_IMG}/w342{profile}" if profile else "",
            "order":        c.get("order", 999),
        })
    await cache.set(cache_key, cast, 7 * 24 * 3600)
    return {"cached": False, "cast": cast}


@api.get("/tmdb/recommendations/{type_}/{tmdb_id}")
async def tmdb_recommendations(type_: str, tmdb_id: int):
    """Return TMDB's "More like this" recommendations.

    Picks the recommendations endpoint (collaborative-filtering style
    "users who liked X also liked Y") not /similar (which is just
    genre overlap and tends to surface lower-quality matches).

    Falls back to /similar when /recommendations is empty (common
    for obscure titles).  Capped at 20 items so the Detail page row
    renders fast.  Cached for 24 h.
    """
    if type_ not in ("tv", "movie"):
        raise HTTPException(400, "type must be 'tv' or 'movie'")
    cache_key = f"recs:{type_}:{tmdb_id}:v2"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "results": cached}

    results: List[Dict[str, Any]] = []
    for path in (
        f"/{type_}/{tmdb_id}/recommendations",
        f"/{type_}/{tmdb_id}/similar",
    ):
        try:
            data = await _tmdb_get(path)
        except HTTPException:
            continue
        for r in (data.get("results") or [])[:20]:
            poster = r.get("poster_path")
            backdrop = r.get("backdrop_path")
            results.append({
                "tmdb_id":    r.get("id"),
                "media_type": type_,
                "title":      r.get("title") or r.get("name") or "",
                "year":       (r.get("release_date") or r.get("first_air_date") or "")[:4],
                "rating":     round(r.get("vote_average") or 0, 1) or None,
                "overview":   (r.get("overview") or "").strip(),
                "poster":     f"{TMDB_IMG}/w342{poster}" if poster else "",
                "backdrop":   f"{TMDB_IMG}/w1280{backdrop}" if backdrop else "",
            })
        if results:
            break  # don't bother with /similar if /recommendations had hits
    await cache.set(cache_key, results, 24 * 3600)
    return {"cached": False, "results": results}


@api.get("/tmdb/person/{person_id}")
async def tmdb_person(person_id: int):
    """Return the actor profile page payload — bio, headshot, age,
    birthplace, and filmography.

    Shape:
        {
          id, name, profile, biography, birthday, deathday, age,
          place_of_birth, known_for_department,
          filmography: [
            { tmdb_id, media_type, title, character, year, poster,
              rating, popularity }
          ]
        }

    The filmography is sorted by popularity descending so the most
    recognisable roles surface first.  Cached for 7 days.
    """
    cache_key = f"person:{person_id}:v4"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, **cached}

    # /person/{id}?append_to_response=combined_credits gets us bio +
    # filmography in a single request — saves an extra round-trip.
    data = await _tmdb_get(
        f"/person/{person_id}",
        {"append_to_response": "combined_credits"},
    )
    profile = data.get("profile_path")
    # Compute age (or age-at-death).
    age: Optional[int] = None
    bday = data.get("birthday") or ""
    dday = data.get("deathday") or ""
    try:
        from datetime import datetime as _dt
        if bday:
            born = _dt.strptime(bday, "%Y-%m-%d")
            end = _dt.strptime(dday, "%Y-%m-%d") if dday else _dt.utcnow()
            age = int((end - born).days // 365.25)
    except Exception:  # noqa: BLE001
        age = None

    combined = data.get("combined_credits") or {}
    raw_cast = combined.get("cast") or []
    # De-duplicate by tmdb_id+media_type; keep the most popular role.
    # Also filter out the noise that bloats raw TMDB combined_credits:
    #   • Talk-show / news / award-show appearances where the actor
    #     plays themselves (genre 10767 = Talk, 10763 = News;
    #     character contains "self").
    #   • Documentaries and shorts where they're not the lead.
    #   • Zero-popularity / unreleased / no-poster entries.
    # Bumps the accuracy of the "Known for" grid noticeably — these
    # were producing the "some movies don't even have that actor in
    # them" complaint.
    NOISE_GENRE_IDS = {10767, 10763}   # Talk + News
    seen: Dict[str, Dict[str, Any]] = {}
    for r in raw_cast:
        mt = r.get("media_type")
        if mt not in ("movie", "tv"):
            continue
        character = (r.get("character") or "").strip().lower()
        # "Self" / "Self - Host" / "(uncredited)" / "Himself" etc.
        if (not character
                or character in {"self", "himself", "herself", "themselves"}
                or character.startswith("self ")
                or character.startswith("self - ")
                or "uncredited" in character):
            continue
        genres = set(r.get("genre_ids") or [])
        if genres & NOISE_GENRE_IDS:
            continue
        popularity = r.get("popularity") or 0
        if popularity < 0.5:           # tiny obscure entries
            continue
        if not (r.get("poster_path") or "").strip():
            continue                   # no poster → almost always noise
        # Episode-count gate for TV: 1-episode appearances are
        # typically guest spots, not roles people associate with the
        # actor.  Skip them.
        episode_count = r.get("episode_count")
        if mt == "tv" and isinstance(episode_count, int) and episode_count < 2:
            continue

        key = f"{mt}:{r.get('id')}"
        prev = seen.get(key)
        if prev and prev.get("popularity", 0) >= popularity:
            continue
        poster = r.get("poster_path")
        backdrop = r.get("backdrop_path")
        seen[key] = {
            "tmdb_id":    r.get("id"),
            "media_type": mt,
            "title":      r.get("title") or r.get("name") or "",
            "character":  r.get("character") or "",
            "year":       (r.get("release_date") or r.get("first_air_date") or "")[:4],
            "rating":     round(r.get("vote_average") or 0, 1) or None,
            "overview":   (r.get("overview") or "").strip(),
            "poster":     f"{TMDB_IMG}/w342{poster}" if poster else "",
            "backdrop":   f"{TMDB_IMG}/w1280{backdrop}" if backdrop else "",
            "popularity": popularity,
        }
    filmography = sorted(seen.values(), key=lambda r: -r.get("popularity", 0))

    out = {
        "id":                   data.get("id"),
        "name":                 data.get("name") or "",
        "profile":              f"{TMDB_IMG}/w780{profile}" if profile else "",
        "biography":            data.get("biography") or "",
        "birthday":             bday,
        "deathday":             dday,
        "age":                  age,
        "place_of_birth":       data.get("place_of_birth") or "",
        "known_for_department": data.get("known_for_department") or "",
        "filmography":          filmography,
    }
    await cache.set(cache_key, out, 7 * 24 * 3600)
    return {"cached": False, **out}


# ----- App version check (forced in-app update gate) ------------------------

@api.get("/app/latest-version")
async def app_latest_version():
    """Return the latest published APK release from GitHub.

    Used by the in-app forced-update gate.  The frontend compares
    `version` against its own bundled `versionName` (from
    `app.json` / a constant) and shows a fullscreen "Update
    required" prompt with a direct APK download URL.

    Shape:
        {
          version: "2.5.8",           # tag_name without leading "v"
          tag_name: "v2.5.8",         # raw tag
          name: "ON NOW TV V2 …",     # human-readable release name
          published_at: "...",        # ISO timestamp
          notes: "markdown body",     # release notes
          apk_url: "https://…/onnowtv-v2-debug.apk",
          html_url: "https://github.com/…/releases/tag/v2.5.8"
        }

    Cached for 5 min so we don't blow through GitHub's 60-req/hour/IP
    unauthenticated rate limit when many boxes check at once.  We
    use the `apk-latest` tag as the canonical "current" release —
    the workflow rolls this tag forward on every push, which is the
    UX you said you want ("just one link that always works").
    """
    cache_key = "github:app-latest:v2"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, **cached}

    owner_repo = os.environ.get("APK_GITHUB_REPO", "")
    if not owner_repo:
        # Fall back to a default that matches the workflow we set up
        # earlier.  The user can override via the env var.
        owner_repo = "andrewbailey-uk/onnowtv-v2"

    url = f"https://api.github.com/repos/{owner_repo}/releases/tags/apk-latest"
    try:
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT) as client:
            r = await client.get(
                url,
                headers={
                    "Accept": "application/vnd.github+json",
                    "User-Agent": "vesper-onnowtv-update-check",
                },
            )
            # Treat 404 as "no release yet" — return a soft empty
            # response instead of a 502 so the gate just doesn't show.
            if r.status_code == 404:
                empty = {
                    "version":      None,
                    "tag_name":     None,
                    "name":         None,
                    "published_at": None,
                    "notes":        "",
                    "apk_url":      None,
                    "html_url":     None,
                }
                # ⚠️ Short TTL on negative responses — if GitHub is
                # temporarily unavailable, the repo was just made
                # public, or the release hasn't been published yet,
                # we don't want to lock in the null answer for the
                # full 5 min positive-response window.  60 s keeps
                # the chain self-healing without hammering GitHub.
                await cache.set(cache_key, empty, 60)
                return {"cached": False, **empty}
            r.raise_for_status()
            data = r.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(
            502,
            f"GitHub release lookup failed: {exc.response.status_code} {exc.response.text[:200]}",
        ) from None
    except httpx.RequestError as exc:
        raise HTTPException(504, f"GitHub network error: {exc}") from None

    # Extract the APK asset URL (workflow always uploads exactly one
    # file: `onnowtv-v2-debug.apk`).  Pick the first asset whose
    # `name` ends with `.apk`.
    apk_url = None
    for asset in data.get("assets") or []:
        name = (asset.get("name") or "").lower()
        if name.endswith(".apk"):
            apk_url = asset.get("browser_download_url")
            break

    # Tag is e.g. "apk-latest"; we want the semver from the release
    # NAME instead.  The workflow names releases like
    # "ON NOW TV V2 — latest debug build" and the actual version is
    # parsed out of the release body's first **v…** line.
    body = data.get("body") or ""
    semver = _parse_semver_from_body(body)
    if not semver:
        # Fallback: scan the body for any vX.Y.Z token.
        import re as _re
        m = _re.search(r"\bv?(\d+\.\d+\.\d+)\b", body)
        if m:
            semver = m.group(1)

    # Minimum-version gate.  When a device's installed versionName
    # is BELOW `min_version`, the UpdateGate silently stays off —
    # older test devices won't get an auto-update prompt for
    # v2.6.25+ because their installed build was signed with a
    # different keystore and can't be upgraded in-place anyway.
    # Set via `APK_MIN_AUTO_UPDATE` env var; defaults to v2.6.25
    # (the first build with the stable keystore + working installer
    # baseline).
    min_version = os.environ.get("APK_MIN_AUTO_UPDATE", "2.6.25")
    out = {
        "version":      semver,
        "tag_name":     data.get("tag_name"),
        "name":         data.get("name") or "",
        "published_at": data.get("published_at"),
        "notes":        body,
        "apk_url":      apk_url,
        "html_url":     data.get("html_url"),
        "min_version":  min_version,
    }
    await cache.set(cache_key, out, 300)
    return {"cached": False, **out}


def _parse_semver_from_body(body: str) -> Optional[str]:
    """Look at the release-notes markdown for the first **vX.Y.Z**
    pattern (our workflow renders the version this way as the first
    bullet header)."""
    import re as _re
    m = _re.search(r"\*\*v(\d+\.\d+\.\d+)", body)
    return m.group(1) if m else None




@api.post("/tmdb/upcoming-episodes")
async def upcoming_episodes(body: Dict[str, Any] = Body(...)):
    """Given a list of IMDB ids of TV shows the user has added to
    their library, return upcoming episodes for each — up to 120 days
    ahead.  Used by the Library calendar view.

    Body: { "imdb_ids": ["tt1234567", "tt2345678", ...] }
    Response:
      {
        "shows": [
          {
            "imdb_id": "tt1234567",
            "tmdb_id": 1234,
            "name": "Bluey",
            "poster_path": "/abc.jpg",
            "backdrop_path": "/def.jpg",
            "network": "Disney+",
            "status": "Returning Series",
            "episodes": [
              {"season": 4, "episode": 1, "name": "...",
               "air_date": "2026-03-12", "overview": "...",
               "still_path": "/xyz.jpg"}
            ]
          }
        ]
      }
    Each show is omitted entirely if no upcoming episodes were found
    (cancelled / between seasons / no calendar data on TMDB).
    """
    imdb_ids = body.get("imdb_ids") or []
    if not isinstance(imdb_ids, list):
        return {"shows": []}

    now = datetime.now(timezone.utc)
    today_iso = now.date().isoformat()
    horizon_iso = (now + timedelta(days=120)).date().isoformat()

    async def fetch_one(imdb_id: str) -> Optional[Dict[str, Any]]:
        try:
            # Step 1: resolve imdb -> tmdb via /find (cached).
            cache_key = f"upcoming_imdb_to_tmdb:{imdb_id}"
            tmdb_id = await cache.get(cache_key)
            if not tmdb_id:
                find = await _tmdb_get(
                    f"/find/{imdb_id}", {"external_source": "imdb_id"}
                )
                hits = find.get("tv_results") or []
                if not hits:
                    return None
                tmdb_id = hits[0]["id"]
                await cache.set(cache_key, tmdb_id, CACHE_TTL_TMDB_IMDB)

            # Step 2: fetch the show shell — gives us next_episode_to_air.
            show = await _tmdb_get(f"/tv/{tmdb_id}")
            next_ep = show.get("next_episode_to_air") or {}

            # Step 3: pull the season containing the next episode so
            # we surface ALL upcoming episodes, not just the next one
            # (some shows have weekly drops scheduled out 8-12 weeks).
            episodes: List[Dict[str, Any]] = []
            if next_ep and next_ep.get("air_date"):
                season_num = next_ep.get("season_number")
                try:
                    season = await _tmdb_get(
                        f"/tv/{tmdb_id}/season/{season_num}"
                    )
                    for ep in season.get("episodes") or []:
                        ad = ep.get("air_date") or ""
                        if not ad or ad < today_iso or ad > horizon_iso:
                            continue
                        episodes.append({
                            "season": ep.get("season_number"),
                            "episode": ep.get("episode_number"),
                            "name": ep.get("name"),
                            "air_date": ad,
                            "overview": ep.get("overview") or "",
                            "still_path": ep.get("still_path"),
                        })
                except Exception:
                    # Fall back to just the next_episode_to_air payload.
                    if next_ep.get("air_date") >= today_iso:
                        episodes.append({
                            "season": next_ep.get("season_number"),
                            "episode": next_ep.get("episode_number"),
                            "name": next_ep.get("name"),
                            "air_date": next_ep.get("air_date"),
                            "overview": next_ep.get("overview") or "",
                            "still_path": next_ep.get("still_path"),
                        })

            if not episodes:
                return None

            networks = show.get("networks") or []
            return {
                "imdb_id": imdb_id,
                "tmdb_id": tmdb_id,
                "name": show.get("name"),
                "poster_path": show.get("poster_path"),
                "backdrop_path": show.get("backdrop_path"),
                "network": networks[0].get("name") if networks else None,
                "status": show.get("status"),
                "episodes": episodes,
            }
        except Exception:
            return None

    # Throttle to 60 shows so the calendar request never times out for
    # users with huge libraries.
    capped = imdb_ids[:60]
    results = await asyncio.gather(*[fetch_one(i) for i in capped])
    shows = [r for r in results if r]
    return {"shows": shows}



# ----- Kid-safe TMDB curation -------------------------------------------------
# We never rely on Stremio addons reporting a `certification` field — almost
# none do.  Instead we go straight to TMDB's `discover` with hard filters:
#   movies → certification_country=US, certification.lte=PG, genre family/animation
#   tv     → animation/family genres + kids-network whitelist (Disney, Nick, etc.)
# Adult flag is force-excluded.  The result is a curated, predictable set of
# shelves and heroes that the parent can hand to a child with confidence.

KIDS_TV_NETWORKS = "13,44,56,2697,3919,4674"  # Nick, Disney Channel, Cartoon Network, Disney Jr, Disney+, Nick Jr
KIDS_MOVIE_CERTS = "G|PG"
KIDS_MOVIE_GENRES = "10751,16"  # Family, Animation
KIDS_FAMILY_GENRE = "10751"      # Family alone
KIDS_ANIMATION_GENRE = "16"      # Animation alone


# ---------------------------------------------------------------------------
# Rating-driven kid filter levels
# ---------------------------------------------------------------------------
# The Settings page now exposes the user's strictness preference via two
# values: maxRatingMovie ∈ {G, PG, PG-13, M15} and maxRatingSeries ∈
# {TV-Y, TV-Y7, TV-G, TV-PG, TV-14, M15}.  These map onto TMDB queries
# below.  Note "M15" is the Australian classification commonly used for
# 15+ teen content; we treat it as "no kid filter — only block adult".

MOVIE_CERT_FILTER = {
    "G":     "G",
    "PG":    "PG",
    "PG-13": "PG-13",
    "M15":   "R",      # AU M15 ≈ US R (not NC-17, not Adult)
}

# Banned genres per movie strictness tier.  Higher tiers permit more
# nuanced content (drama, sci-fi) but adult-only categories stay banned.
MOVIE_BANNED = {
    "G":     {27, 53, 80, 10752, 18, 9648},          # +Drama +Mystery
    "PG":    {27, 53, 80, 10752},                    # Horror Thriller Crime War
    "PG-13": {27, 80, 10752},                        # Horror Crime War
    "M15":   {27, 10752},                            # Horror, War only
}

# Required genre set (must contain at least one of these).  Looser at
# higher tiers to permit more variety (Adventure, Comedy).
MOVIE_REQUIRED = {
    "G":     {16, 10751},                            # Animation/Family
    "PG":    {16, 10751},
    "PG-13": {16, 10751, 12, 35},                    # +Adventure/Comedy
    "M15":   None,                                   # no genre gate
}

# TV strictness: TMDB doesn't accept certification for /discover/tv,
# so we encode the level by combining genre + language + network rules.
TV_LEVEL_PARAMS = {
    "TV-Y":   {"with_genres": "10751,16", "with_original_language": "en"},
    "TV-Y7":  {"with_genres": "10751,16", "with_original_language": "en"},
    "TV-G":   {"with_genres": "10751,16", "with_original_language": "en"},
    "TV-PG":  {"with_genres": "10751",    "with_original_language": "en"},
    "TV-14":  {"with_genres": "10751"},
    "M15":    {},   # no enforced family gate; only banned-genre filter
}

TV_BANNED = {
    "TV-Y":   "10759,10763,10764,10767,10768,18,80,9648,53,27,10766,10752",
    "TV-Y7":  "10763,10764,10767,10768,18,80,9648,53,27,10766,10752",
    "TV-G":   "10763,10764,10767,10768,18,80,9648,53,27,10766,10752",
    "TV-PG":  "10763,10764,10767,10768,18,80,9648,53,27,10766,10752",
    "TV-14":  "10763,10764,10767,10768,80,9648,27,10766,10752",
    "M15":    "10763,10764,10767,10768,27,10766,10752",
}


def _resolve_movie_level(cert: Optional[str]) -> str:
    return cert if cert in MOVIE_CERT_FILTER else "PG"


def _resolve_tv_level(level: Optional[str]) -> str:
    return level if level in TV_LEVEL_PARAMS else "TV-PG"



def _shape_tmdb_item(item: Dict[str, Any], media: str) -> Optional[Dict[str, Any]]:
    if not item.get("poster_path"):
        return None
    is_tv = media == "tv"
    title = item.get("name") if is_tv else item.get("title")
    if not title:
        return None
    year_raw = (
        item.get("first_air_date") if is_tv else item.get("release_date")
    ) or ""
    return {
        "tmdb_id": item.get("id"),
        "type": "series" if is_tv else "movie",
        "title": title,
        "poster": f"{TMDB_IMG}/w500{item['poster_path']}",
        "backdrop": (
            f"{TMDB_IMG}/w1280{item['backdrop_path']}"
            if item.get("backdrop_path")
            else None
        ),
        "year": year_raw[:4] if year_raw else "",
        "rating": (
            round(item.get("vote_average"), 1)
            if isinstance(item.get("vote_average"), (int, float))
            else None
        ),
        "synopsis": item.get("overview") or "",
    }


async def _tmdb_discover_kids(
    media: str,
    *,
    sort: str = "popularity.desc",
    page: int = 1,
    extra: Optional[Dict[str, Any]] = None,
    movie_cert: str = "PG",
    tv_level: str = "TV-PG",
) -> List[Dict[str, Any]]:
    """Discover with hardened kid-safe filters, driven by the user's
    Settings ratings."""
    base: Dict[str, Any] = {
        "include_adult": "false",
        "sort_by": sort,
        "page": page,
        "vote_count.gte": 30,  # filter out long-tail low-quality
    }
    if media == "movie":
        cert = MOVIE_CERT_FILTER.get(movie_cert, "PG")
        base.update(
            {
                "certification_country": "US",
                "certification.lte": cert,
                "with_genres": KIDS_MOVIE_GENRES,
            }
        )
    else:  # tv
        params = TV_LEVEL_PARAMS.get(tv_level, TV_LEVEL_PARAMS["TV-PG"])
        base.update(params)
        base["without_genres"] = TV_BANNED.get(tv_level, TV_BANNED["TV-PG"])
    if extra:
        base.update(extra)
    data = await _tmdb_get(f"/discover/{media}", base)
    out: List[Dict[str, Any]] = []
    for it in data.get("results") or []:
        shaped = _shape_tmdb_item(it, media)
        if shaped:
            out.append(shaped)
    return out


@api.get("/tmdb/kids/shelves")
async def tmdb_kids_shelves(
    movie_cert: str = Query("PG"),
    tv_level: str = Query("TV-PG"),
):
    """Returns a curated set of kid-safe shelves with TMDB data.

    Strictness is driven by the user's Settings preferences:
      movie_cert: G | PG | PG-13 | M15
      tv_level:   TV-Y | TV-Y7 | TV-G | TV-PG | TV-14 | M15
    """
    movie_cert = _resolve_movie_level(movie_cert)
    tv_level = _resolve_tv_level(tv_level)
    cache_key = f"tmdb_kids_shelves:v6:{movie_cert}:{tv_level}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    async def shelf(id_, title, media, params, pages=1):
        # Pull multiple pages and merge — gives kids a deeper catalog
        # without leaving the kid-safe filter cone.  Pages are pulled
        # in parallel; results dedupe by tmdb_id.
        seen: Dict[int, Dict[str, Any]] = {}
        results = await asyncio.gather(
            *[
                _tmdb_discover_kids(
                    media,
                    extra={**params, "page": p + 1},
                    movie_cert=movie_cert,
                    tv_level=tv_level,
                )
                for p in range(pages)
            ],
            return_exceptions=True,
        )
        for r in results:
            if not isinstance(r, list):
                continue
            for it in r:
                tid = it.get("tmdb_id")
                if tid is not None and tid not in seen:
                    seen[tid] = it
        items = list(seen.values())
        return {
            "id": id_,
            "title": title,
            "eyebrow": "Movies" if media == "movie" else "Cartoons",
            "type": "movie" if media == "movie" else "series",
            "items": items[:60],
        }

    queries = [
        shelf("family-favorites", "Family Favourites", "movie", {"sort_by": "popularity.desc"}, pages=3),
        shelf("animated-magic",  "Animated Magic",    "movie", {"with_genres": "16,10751", "sort_by": "popularity.desc"}, pages=3),
        shelf("top-rated-family","Top-Rated Family",  "movie", {"sort_by": "vote_average.desc", "vote_count.gte": 500}, pages=3),
        shelf("adventure-time",  "Adventure Time",    "movie", {"with_genres": "10751,12", "sort_by": "popularity.desc"}, pages=2),
        shelf("animated-series", "Animated Shows",    "tv",    {"sort_by": "popularity.desc"}, pages=3),
        shelf("top-cartoons",    "Top-Rated Cartoons","tv",    {"sort_by": "vote_average.desc", "vote_count.gte": 100}, pages=3),
        shelf("recent-family",   "New for the Family","movie", {"sort_by": "primary_release_date.desc", "vote_count.gte": 100, "primary_release_date.lte": datetime.now(timezone.utc).date().isoformat()}, pages=2),
        shelf("musical-magic",   "Sing-Alongs",       "movie", {"with_genres": "10751,10402", "sort_by": "popularity.desc"}, pages=2),
        shelf("comedy-films",    "Family Comedies",   "movie", {"with_genres": "10751,35", "sort_by": "popularity.desc"}, pages=2),
        shelf("fantasy-films",   "Fantasy Adventures","movie", {"with_genres": "10751,14", "sort_by": "popularity.desc"}, pages=2),
        shelf("classic-toons",   "Classic Cartoons",  "tv",    {"sort_by": "first_air_date.asc", "first_air_date.gte": "1990-01-01", "vote_count.gte": 50}, pages=2),
        shelf("new-tv",          "Just-Aired Shows",  "tv",    {"sort_by": "first_air_date.desc", "first_air_date.lte": datetime.now(timezone.utc).date().isoformat(), "vote_count.gte": 30}, pages=2),
    ]

    results = await asyncio.gather(*queries, return_exceptions=True)
    out = [
        r for r in results
        if isinstance(r, dict) and r.get("items")
    ]
    await cache.set(cache_key, out, 6 * 3600)
    return {"cached": False, "data": out}


@api.get("/tmdb/kids/search")
async def tmdb_kids_search(
    q: str = Query(..., min_length=1, max_length=80),
    movie_cert: str = Query("PG"),
    tv_level: str = Query("TV-PG"),
):
    """Kid-safe search, strictness driven by user's Settings.

    Movies: require allowed genre set, drop banned genres, then verify
    each candidate's actual US MPAA certification ≤ chosen cert.

    TV: require Family (and Animation, at strict levels), English at
    strict levels, drop banned-genres.
    """
    movie_cert = _resolve_movie_level(movie_cert)
    tv_level = _resolve_tv_level(tv_level)
    q = q.strip()
    if not q:
        return {"data": []}

    cache_key = f"kids_search:{q.lower()}:{movie_cert}:{tv_level}"
    cached = await cache.get(cache_key)
    if cached:
        return {"data": cached}

    # Pull 2 pages of multi-search in parallel for a deeper result set.
    page_results = await asyncio.gather(
        *[
            _tmdb_get(
                "/search/multi",
                {"query": q, "include_adult": "false", "page": p + 1},
            )
            for p in range(2)
        ],
        return_exceptions=True,
    )
    raw_items: List[Dict[str, Any]] = []
    seen_ids: set = set()
    for pr in page_results:
        if not isinstance(pr, dict):
            continue
        for it in pr.get("results") or []:
            tid = it.get("id")
            if tid is None or tid in seen_ids:
                continue
            seen_ids.add(tid)
            raw_items.append(it)

    banned_movie = MOVIE_BANNED.get(movie_cert, MOVIE_BANNED["PG"])
    required_movie = MOVIE_REQUIRED.get(movie_cert)
    banned_tv = {
        int(x) for x in TV_BANNED.get(tv_level, TV_BANNED["TV-PG"]).split(",") if x
    }
    tv_params = TV_LEVEL_PARAMS.get(tv_level, TV_LEVEL_PARAMS["TV-PG"])
    # Whether to enforce strict family-animation gate (lowest tiers).
    tv_strict_genre = tv_params.get("with_genres") == "10751,16"
    tv_require_family = "with_genres" in tv_params
    tv_english_only = "with_original_language" in tv_params

    movie_candidates: List[Dict[str, Any]] = []
    tv_out: List[Dict[str, Any]] = []
    for item in raw_items:
        if item.get("adult"):
            continue
        mt = item.get("media_type")
        gids = set(item.get("genre_ids") or [])
        if mt == "movie":
            if required_movie and not (gids & required_movie):
                continue
            if gids & banned_movie:
                continue
            shaped = _shape_tmdb_item(item, "movie")
            if shaped:
                movie_candidates.append(shaped)
        elif mt == "tv":
            if tv_strict_genre and not (16 in gids and 10751 in gids):
                continue
            if tv_require_family and not tv_strict_genre and 10751 not in gids:
                continue
            if gids & banned_tv:
                continue
            if tv_english_only:
                orig = (item.get("original_language") or "").lower()
                if orig and orig != "en":
                    continue
            shaped = _shape_tmdb_item(item, "tv")
            if shaped:
                tv_out.append(shaped)

    # Verify MPAA cert for movie candidates against the chosen ceiling.
    allowed_certs = []
    for c in ("G", "PG", "PG-13", "R"):
        allowed_certs.append(c)
        if MOVIE_CERT_FILTER[movie_cert] == c:
            break
    allowed_certs_set = set(allowed_certs)

    async def cert_ok(m: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        try:
            rel = await _tmdb_get(f"/movie/{m['tmdb_id']}/release_dates")
        except HTTPException:
            return None
        for entry in rel.get("results") or []:
            if entry.get("iso_3166_1") != "US":
                continue
            for d in entry.get("release_dates") or []:
                cert = (d.get("certification") or "").strip().upper()
                if cert in allowed_certs_set:
                    return m
            return None
        # No US release info → only accept at the most permissive tier.
        return m if movie_cert == "M15" else None

    # Verify MPAA cert on every movie candidate (cap at 60 to keep
    # latency reasonable on shaky kid-friendly connections).
    verified = await asyncio.gather(*[cert_ok(m) for m in movie_candidates[:60]])
    movie_out = [m for m in verified if m]

    out = movie_out + tv_out
    await cache.set(cache_key, out, 6 * 3600)
    return {"data": out}


@api.get("/tmdb/kids/heroes")
async def tmdb_kids_heroes(movie_cert: str = Query("PG")):
    """Curated hero billboard for Kids mode.  Strictness driven by
    user's Settings maxRatingMovie."""
    movie_cert = _resolve_movie_level(movie_cert)
    cache_key = f"tmdb_kids_heroes:v5:{movie_cert}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    items = await _tmdb_discover_kids(
        "movie",
        extra={"sort_by": "popularity.desc", "vote_count.gte": 500},
        movie_cert=movie_cert,
    )
    out: List[Dict[str, Any]] = []
    for it in items:
        if not it.get("backdrop") or not it.get("synopsis") or len(it["synopsis"]) < 60:
            continue
        out.append(
            {
                "id": f"tmdb-{it['tmdb_id']}",
                "title": it["title"],
                "eyebrow": "Family Pick",
                "year": it.get("year", ""),
                "runtime": "",
                "rating": f"★ {it['rating']}" if it.get("rating") else "",
                "genres": [],
                "synopsis": it["synopsis"],
                "backdrop": it["backdrop"],
                "sources": ["TMDB"],
                "routePath": f"/resolve/movie/{it['tmdb_id']}",
            }
        )
        if len(out) >= 6:
            break

    await cache.set(cache_key, out, 6 * 3600)
    return {"cached": False, "data": out}


@api.get("/tmdb/trending")
async def tmdb_trending(
    window: str = Query("week", regex="^(day|week)$"),
    media: str = Query("all", regex="^(all|movie|tv)$"),
):
    """TMDB trending feed — used as the home-screen hero billboard
    when no addons are installed.  Returns a curated set of items
    with a high-res backdrop, synopsis, and meta — i.e. exactly the
    shape the HeroBillboard expects."""
    cache_key = f"tmdb_trend:{media}:{window}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    data = await _tmdb_get(f"/trending/{media}/{window}")
    out: List[Dict[str, Any]] = []
    for item in (data.get("results") or [])[:20]:
        # Skip items without a usable backdrop — the hero
        # needs a wide cinematic image.
        if not item.get("backdrop_path"):
            continue
        is_tv = (item.get("media_type") == "tv") or (
            media == "tv" and item.get("media_type") is None
        )
        title = item.get("name") if is_tv else item.get("title")
        year_raw = (
            item.get("first_air_date") if is_tv else item.get("release_date")
        ) or ""
        out.append(
            {
                "tmdb_id": item.get("id"),
                "type": "series" if is_tv else "movie",
                "title": title,
                "backdrop": f"{TMDB_IMG}/original{item['backdrop_path']}",
                "poster": (
                    f"{TMDB_IMG}/w500{item['poster_path']}"
                    if item.get("poster_path")
                    else None
                ),
                "year": year_raw[:4] if year_raw else "",
                "rating": (
                    round(item.get("vote_average"), 1)
                    if isinstance(item.get("vote_average"), (int, float))
                    else None
                ),
                "synopsis": item.get("overview"),
            }
        )
        if len(out) >= 8:
            break

    await cache.set(cache_key, out, 1800)  # 30 min — TMDB trending updates daily
    return {"cached": False, "data": out}


@api.get("/tmdb/party-picks")
async def tmdb_party_picks(limit: int = Query(5, ge=1, le=12)):
    """Curated "What do you want to watch?" picks for the Watch
    Together host stage.  Returns the latest theatrical / now-playing
    movies filtered down to titles with `vote_average >= 6.0` so the
    host always sees something worth picking.  Per user spec
    (v2.6.75): "Top 5 new release movies with over a 6 rating."

    Cached 30 min — TMDB now_playing rotates roughly weekly but we
    refresh more often so the user sees new releases promptly.
    """
    cache_key = f"tmdb_party_picks:{limit}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    # Pull a couple of pages so we have enough candidates after the
    # rating filter knocks out the duds.  TMDB returns ~20/page.
    raw: List[Dict[str, Any]] = []
    for page in (1, 2):
        data = await _tmdb_get("/movie/now_playing", params={"page": page})
        raw.extend(data.get("results") or [])
        if len(raw) >= limit * 6:
            break
    # Filter + dedupe + rank
    seen: set = set()
    out: List[Dict[str, Any]] = []
    for item in raw:
        if not item.get("poster_path"):
            continue
        rating = item.get("vote_average")
        if not isinstance(rating, (int, float)) or rating < 6.0:
            continue
        # Skip titles with too few votes — TMDB's "vote_average" for a
        # day-1 release with 12 votes isn't reliable.  >= 40 keeps the
        # bar reasonable without being too strict.
        votes = item.get("vote_count") or 0
        if votes < 40:
            continue
        tmdb_id = item.get("id")
        if tmdb_id in seen:
            continue
        seen.add(tmdb_id)
        rel = item.get("release_date") or ""
        out.append({
            "tmdb_id": tmdb_id,
            "type": "movie",
            "title": item.get("title") or item.get("name") or "",
            "poster": (
                f"{TMDB_IMG}/w500{item['poster_path']}"
                if item.get("poster_path") else None
            ),
            "backdrop": (
                f"{TMDB_IMG}/original{item['backdrop_path']}"
                if item.get("backdrop_path") else None
            ),
            "year": rel[:4] if rel else "",
            "rating": round(rating, 1),
            "synopsis": item.get("overview") or "",
        })
    # Sort by rating then vote count so the very best top the list.
    out.sort(
        key=lambda x: (
            x.get("rating") or 0,
            -len(x.get("synopsis") or ""),
        ),
        reverse=True,
    )
    out = out[:limit]
    await cache.set(cache_key, out, 1800)
    return {"cached": False, "data": out}


@api.get("/tmdb/upcoming-movies")
async def tmdb_upcoming_movies(
    limit: int = Query(20, ge=1, le=40),
    days: int = Query(60, ge=7, le=180),
):
    """Theatrical / streaming releases dropping in the next `days`
    days.  Powers the "Upcoming" rail at the bottom of Home — users
    click a tile to land on the Detail page, watch the trailer, or
    add the title to their notify list.

    We pull `/movie/upcoming` (TMDB's marketing-friendly window —
    usually ~2 weeks out) **plus** `/movie/popular` filtered to
    future release dates, then dedupe so the row stays fresh even
    when /upcoming runs thin.

    Each item carries the resolved `imdb_id` so the front-end can
    route directly into the existing /title/movie/{imdb_id} Detail
    page.  IMDB lookup is best-effort + cached — entries without an
    IMDB id are still returned (Detail page falls back to TMDB).
    """
    cache_key = f"tmdb_upcoming:{limit}:{days}:en-us-gb"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    today = datetime.now(timezone.utc).date()
    end_date = today + timedelta(days=days)

    raw: List[Dict[str, Any]] = []
    try:
        # TMDB /movie/upcoming for the US gives the canonical
        # English-language theatrical/streaming slate.  We do NOT
        # request region=GB additionally because it would dupe
        # against the US slate for almost every major title.
        for page in (1, 2):
            data = await _tmdb_get(
                "/movie/upcoming",
                params={"page": page, "region": "US"},
            )
            raw.extend(data.get("results") or [])
    except Exception as exc:  # noqa: BLE001
        logger.warning("tmdb upcoming fetch failed: %s", exc)

    # Second source: /discover/movie restricted to English-language
    # titles releasing in the US or UK in the next `days` days,
    # sorted by popularity so the rail leads with titles the user
    # has actually heard of.  Excludes non-English (Korean, Japanese,
    # Hindi, Chinese, etc.) per user spec — they only want big
    # English/Western releases in the Upcoming row.
    try:
        for page in (1, 2):
            data = await _tmdb_get(
                "/discover/movie",
                params={
                    "page": page,
                    "sort_by": "popularity.desc",
                    "primary_release_date.gte": today.isoformat(),
                    "primary_release_date.lte": end_date.isoformat(),
                    "with_release_type": "2|3|4|5",
                    "with_original_language": "en",
                    "region": "US",
                    "include_adult": "false",
                    "vote_count.gte": "5",  # filter out obscure indies
                },
            )
            raw.extend(data.get("results") or [])
    except Exception as exc:  # noqa: BLE001
        logger.warning("tmdb discover upcoming fetch failed: %s", exc)

    seen: set = set()
    out: List[Dict[str, Any]] = []
    for item in raw:
        tmdb_id = item.get("id")
        if not tmdb_id or tmdb_id in seen:
            continue
        # Must release within the requested window.
        rel = item.get("release_date") or ""
        if not rel:
            continue
        try:
            rel_date = datetime.fromisoformat(rel).date()
        except Exception:  # noqa: BLE001
            continue
        if rel_date < today or rel_date > end_date:
            continue
        if not item.get("poster_path"):
            continue
        # Restrict to English-language titles per user spec —
        # "no overseas / international stuff, just the big English /
        # US new releases".  TMDB exposes the canonical original
        # language via `original_language`.
        if (item.get("original_language") or "").lower() != "en":
            continue
        # Drop very-low-popularity items (Hallmark-tier obscurities,
        # unreleased indies with no TMDB metadata yet).  Threshold
        # picked empirically: anything below 6 popularity has zero
        # name recognition for an English-speaking audience.
        if (item.get("popularity") or 0) < 6:
            continue
        seen.add(tmdb_id)
        out.append({
            "tmdb_id": tmdb_id,
            "type": "movie",
            "title": item.get("title") or item.get("name") or "",
            "poster": f"{TMDB_IMG}/w500{item['poster_path']}",
            "backdrop": (
                f"{TMDB_IMG}/original{item['backdrop_path']}"
                if item.get("backdrop_path") else None
            ),
            "year": rel[:4] if rel else "",
            "release_date": rel,
            "synopsis": item.get("overview") or "",
            "popularity": round(item.get("popularity") or 0, 1),
            "rating": (
                round(item.get("vote_average"), 1)
                if isinstance(item.get("vote_average"), (int, float))
                else None
            ),
            "imdb_id": None,  # filled below, best-effort
            "trailer_key": None,  # YouTube key, filled below, best-effort
        })

    # Sort by popularity descending — user wants "the big titles"
    # leading the rail.  Date is the tie-breaker so equally-popular
    # films lead with whatever's coming out soonest.
    out.sort(key=lambda x: (-(x.get("popularity") or 0), x.get("release_date") or "9999"))
    out = out[:limit]

    # Best-effort IMDB id + YouTube trailer key resolution (parallel,
    # 8 concurrent).  Items that fail still ship.
    sem = asyncio.Semaphore(8)

    async def _resolve(item: Dict[str, Any]) -> None:
        tmdb_id = item.get("tmdb_id")
        if not tmdb_id:
            return
        # IMDB id (cached)
        ck = f"tmdb_imdb:movie:{tmdb_id}"
        cached_id = await cache.get(ck)
        if cached_id:
            item["imdb_id"] = cached_id
        else:
            async with sem:
                try:
                    data = await _tmdb_get(f"/movie/{tmdb_id}/external_ids")
                    imdb = data.get("imdb_id") or None
                    if imdb:
                        item["imdb_id"] = imdb
                        await cache.set(ck, imdb, CACHE_TTL_TMDB_IMDB)
                except Exception:  # noqa: BLE001
                    pass
        # YouTube trailer key — TMDB /videos endpoint.  Pick the
        # first official YouTube trailer.  Cache 24 h since trailers
        # rarely change once uploaded.
        tk = f"tmdb_trailer:movie:{tmdb_id}"
        cached_trailer = await cache.get(tk)
        if cached_trailer:
            item["trailer_key"] = cached_trailer
            return
        async with sem:
            try:
                vids = await _tmdb_get(f"/movie/{tmdb_id}/videos")
                results = vids.get("results") or []
                # Prefer official YouTube trailers, then teasers.
                yt = [v for v in results if v.get("site") == "YouTube" and v.get("key")]
                trailer = (
                    next((v for v in yt if v.get("type") == "Trailer" and v.get("official")), None)
                    or next((v for v in yt if v.get("type") == "Trailer"), None)
                    or next((v for v in yt if v.get("type") == "Teaser"), None)
                )
                if trailer:
                    key = trailer.get("key")
                    item["trailer_key"] = key
                    await cache.set(tk, key, 60 * 60 * 24)
            except Exception:  # noqa: BLE001
                pass

    await asyncio.gather(*[_resolve(it) for it in out])

    # Cache 1 h — TMDB upcoming refreshes daily but we don't need to
    # be that aggressive; 60 min keeps the rail fresh enough.
    await cache.set(cache_key, out, 60 * 60)
    return {"cached": False, "data": out}


@api.get("/tmdb/genres/{media}")
async def tmdb_genres(media: str):
    """Return the TMDB master genre list for movies or TV.

    The Profile-creation 'Viewing Style' step calls this once to
    render the genre picker grid; the response is cached for 7
    days because TMDB rarely changes its genre list.
    """
    if media not in ("movie", "tv"):
        raise HTTPException(400, "media must be 'movie' or 'tv'")
    cache_key = f"tmdb_genres:{media}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    data = await _tmdb_get(f"/genre/{media}/list")
    items = [
        {"id": g.get("id"), "name": g.get("name")}
        for g in (data.get("genres") or [])
        if g.get("id") and g.get("name")
    ]
    await cache.set(cache_key, items, 60 * 60 * 24 * 7)
    return {"cached": False, "data": items}


@api.get("/tmdb/by-genre/{media}/{genre_id}")
async def tmdb_by_genre(
    media: str,
    genre_id: int,
    limit: int = Query(10, ge=1, le=100),
):
    """Top popular titles in a genre — used by the viewing-style
    picker to show the top N movies / TV shows once the user picks
    a genre tile.  Pulls multiple pages from TMDB when `limit` is
    larger than a single page can return (20 results per page)."""
    if media not in ("movie", "tv"):
        raise HTTPException(400, "media must be 'movie' or 'tv'")
    cache_key = f"tmdb_by_genre:{media}:{genre_id}:{limit}:v2"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    out: List[Dict[str, Any]] = []
    page = 1
    max_pages = 5            # safety net (5 × 20 = 100)
    while len(out) < limit and page <= max_pages:
        data = await _tmdb_get(
            f"/discover/{media}",
            {
                "with_genres": str(genre_id),
                "sort_by": "popularity.desc",
                "include_adult": "false",
                "vote_count.gte": "200",
                "page": str(page),
            },
        )
        results = data.get("results") or []
        if not results:
            break
        for item in results:
            shaped = _shape_tmdb_item(item, media)
            if shaped:
                out.append(shaped)
            if len(out) >= limit:
                break
        page += 1
    await cache.set(cache_key, out, 60 * 60 * 6)
    return {"cached": False, "data": out}


@api.get("/tmdb/trailer/{type_}/{tmdb_id}")
async def tmdb_trailer(type_: str, tmdb_id: int):
    """Return the best YouTube trailer for a movie/series.
    Picks Trailer > Teaser, Official > anything, newest first."""
    if type_ not in ("movie", "tv"):
        raise HTTPException(400, "type must be 'movie' or 'tv'")
    cache_key = f"trailer:{type_}:{tmdb_id}:v1"
    cached = await cache.get(cache_key)
    if cached is not None:
        return {"cached": True, "data": cached}
    data = await _tmdb_get(
        f"/{type_}/{tmdb_id}/videos", {"language": "en-US"}
    )
    videos = data.get("results") or []
    youtube = [v for v in videos if (v.get("site") or "").lower() == "youtube"]
    def rank(v):
        type_score = 3 if v.get("type") == "Trailer" else (2 if v.get("type") == "Teaser" else 1)
        official = 1 if v.get("official") else 0
        return (type_score, official, v.get("published_at") or "")
    youtube.sort(key=rank, reverse=True)
    if not youtube:
        await cache.set(cache_key, None, 60 * 60 * 6)
        return {"cached": False, "data": None}
    best = youtube[0]
    out = {
        "key": best.get("key"),
        "name": best.get("name") or "Trailer",
        "site": "YouTube",
        "type": best.get("type") or "Trailer",
    }
    await cache.set(cache_key, out, 60 * 60 * 6)
    return {"cached": False, "data": out}


@api.get("/trailer-stream/{youtube_id}")
async def trailer_stream(youtube_id: str):
    """Extract a direct, playable URL for a YouTube video so the
    frontend can play it natively — no embedded YouTube iframe, no
    Android intent redirect to the YouTube app.

    YouTube serves combined audio+video MP4 only up to 360p.  For HD
    they use DASH (separate video-only + audio-only streams).  To
    deliver HD trailers we therefore return BOTH:

        - `url`            the BEST progressive MP4 we can find
                           (combined audio+video, ≤ 360p typically)
        - `video_url`      a 1080p video-only stream  (only if HD)
        - `audio_url`      the matching m4a audio track (only if HD)

    Native players (libVLC on Android) can play the HD pair via
    `Media.addSlave(SLAVE_TYPE_AUDIO, audio_url)` and merge them on
    the fly.  Web players that don't support input slaves fall back
    to the combined progressive URL.

    Googlevideo URLs are signed with a ~6 h TTL.  We cache for 1 h
    to absorb repeat playback within the same session.
    """
    safe_id = re.sub(r"[^A-Za-z0-9_-]", "", youtube_id)[:24]
    if not safe_id:
        raise HTTPException(400, "invalid youtube_id")
    cache_key = f"trailer_stream:{safe_id}:v3"  # v3: 720p cap for HK1 smoothness
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, **cached}
    loop = asyncio.get_event_loop()

    def _extract():
        from yt_dlp import YoutubeDL
        # Strategy: ask for "bestvideo[≤720] + bestaudio".  We
        # deliberately cap at 720p (not 1080p) for trailers — the
        # HK1 Android TV box's H.264 hardware decoder + the
        # input-slave audio pairing leaves limited headroom, and the
        # user reported frame-skipping at 1080p.  720p plays
        # smoothly while still looking great on a 1080p TV (and is
        # essentially indistinguishable on the 10-foot UI).  Falls
        # back to combined progressive 360p if no 720p available.
        ydl_opts = {
            "format": (
                "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/"
                "best[ext=mp4][height<=720]/"
                "best[height<=720]/best"
            ),
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "skip_download": True,
            "user_agent": (
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                "AppleWebKit/537.36 (KHTML, like Gecko) "
                "Chrome/120.0.0.0 Safari/537.36"
            ),
        }
        with YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(
                f"https://www.youtube.com/watch?v={safe_id}",
                download=False,
            )
        return info

    try:
        info = await loop.run_in_executor(None, _extract)
    except Exception as e:                                       # noqa: BLE001
        logger.exception("yt-dlp extract failed: %s", e)
        raise HTTPException(502, f"trailer_extract_failed: {e}")

    # Walk the requested_formats / formats arrays to find what we
    # actually got.  When yt-dlp resolves a `videoFmt+audioFmt`
    # selector it populates `requested_formats` with the two
    # half-streams.  Otherwise it returns a single progressive
    # `formats[0]`.
    video_url = None
    audio_url = None
    progressive_url = info.get("url") if (
        info.get("acodec") and info.get("acodec") != "none"
    ) else None
    height = info.get("height") or 0

    requested = info.get("requested_formats") or []
    for fmt in requested:
        vcodec = fmt.get("vcodec") or "none"
        acodec = fmt.get("acodec") or "none"
        url = fmt.get("url")
        if not url:
            continue
        if vcodec != "none" and acodec == "none":
            video_url = url
            height = fmt.get("height") or height
        elif acodec != "none" and vcodec == "none":
            audio_url = url

    # If we got HD video+audio pair, that's our preferred output.
    # Otherwise look through all formats for the best progressive
    # MP4 (combined a/v) as a fallback for clients that can't merge.
    if not (video_url and audio_url) and not progressive_url:
        formats = info.get("formats") or []
        progressive = [
            f for f in formats
            if f.get("vcodec") and f.get("vcodec") != "none"
            and f.get("acodec") and f.get("acodec") != "none"
            and f.get("ext") == "mp4"
            and f.get("url")
        ]
        progressive.sort(key=lambda f: f.get("height") or 0, reverse=True)
        if progressive:
            progressive_url = progressive[0].get("url")
            height = progressive[0].get("height") or height

    # The `url` field is what older clients have been reading.
    # Prefer the HD video stream if available so native players that
    # CAN merge get HD; the progressive URL stays available for web
    # fallback.
    primary_url = video_url or progressive_url
    if not primary_url:
        raise HTTPException(502, "trailer_no_playable_format")

    out = {
        "url": primary_url,
        "video_url": video_url or "",
        "audio_url": audio_url or "",
        "progressive_url": progressive_url or "",
        "is_hd_pair": bool(video_url and audio_url),
        "title": info.get("title") or "",
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
        "height": height,
        "ext": info.get("ext") or "mp4",
    }
    await cache.set(cache_key, out, 60 * 60)
    return {"cached": False, **out}


@api.get("/tmdb/by-genres/{media}")
async def tmdb_by_genres(
    media: str,
    genre_ids: str = Query("", description="Comma-separated TMDB genre IDs"),
    limit: int = Query(50, ge=1, le=100),
):
    """Combined top popular titles across MULTIPLE genres.  Used
    by the viewing-style picker once the user has selected a set
    of genres — we union the most popular results across all of
    them, dedupe, and return the top `limit` by overall popularity."""
    if media not in ("movie", "tv"):
        raise HTTPException(400, "media must be 'movie' or 'tv'")
    ids = [g.strip() for g in (genre_ids or "").split(",") if g.strip().isdigit()]
    if not ids:
        return {"cached": False, "data": []}
    key = ",".join(sorted(ids))
    cache_key = f"tmdb_by_genres:{media}:{key}:{limit}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    seen: Dict[Any, Dict[str, Any]] = {}
    # Pull a few pages PER GENRE in parallel so we have enough
    # candidate titles to dedupe + sort.
    async def _pull(genre_id: str, page: int):
        return await _tmdb_get(
            f"/discover/{media}",
            {
                "with_genres": genre_id,
                "sort_by": "popularity.desc",
                "include_adult": "false",
                "vote_count.gte": "200",
                "page": str(page),
            },
        )
    pages_per_genre = max(1, math.ceil(limit / 20))
    tasks = [
        _pull(gid, p)
        for gid in ids
        for p in range(1, pages_per_genre + 1)
    ]
    pages = await asyncio.gather(*tasks, return_exceptions=True)
    for resp in pages:
        if isinstance(resp, Exception) or not resp:
            continue
        for item in (resp.get("results") or []):
            shaped = _shape_tmdb_item(item, media)
            if not shaped:
                continue
            key2 = (shaped.get("type"), shaped.get("tmdb_id"))
            if key2 in seen:
                continue
            seen[key2] = {**shaped, "popularity": item.get("popularity") or 0}
    ranked = sorted(seen.values(), key=lambda r: -r.get("popularity", 0))[:limit]
    for r in ranked:
        r.pop("popularity", None)
    await cache.set(cache_key, ranked, 60 * 60 * 6)
    return {"cached": False, "data": ranked}


@api.get("/tmdb/for-you")
async def tmdb_for_you(
    movie_genres: str = Query("", description="Comma-separated TMDB movie genre IDs"),
    tv_genres: str = Query("", description="Comma-separated TMDB TV genre IDs"),
    limit: int = Query(20, ge=1, le=60),
):
    """For-You feed — newest popular titles matching the user's
    liked genres.  Movies and TV are pulled in parallel, mixed
    with movies first, and deduped by (type, tmdb_id).  Empty
    genre params return an empty list so the Home shelf can hide
    itself cleanly."""
    m_ids = [g.strip() for g in (movie_genres or "").split(",") if g.strip().isdigit()]
    t_ids = [g.strip() for g in (tv_genres or "").split(",") if g.strip().isdigit()]
    if not m_ids and not t_ids:
        return {"cached": False, "data": []}

    cache_key = f"tmdb_for_you:m={','.join(sorted(m_ids))}:t={','.join(sorted(t_ids))}:{limit}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    async def _pull(media: str, ids: List[str]) -> List[Dict[str, Any]]:
        if not ids:
            return []
        params = {
            "with_genres": "|".join(ids),
            "sort_by": "popularity.desc",
            "include_adult": "false",
            "vote_count.gte": "100",
            "page": "1",
        }
        try:
            data = await _tmdb_get(f"/discover/{media}", params)
        except Exception:
            return []
        out: List[Dict[str, Any]] = []
        for item in (data.get("results") or []):
            shaped = _shape_tmdb_item(item, media)
            if shaped:
                out.append(shaped)
        return out

    movies, tv = await asyncio.gather(_pull("movie", m_ids), _pull("tv", t_ids))

    # Interleave so the rail isn't all-movie or all-TV.
    mixed: List[Dict[str, Any]] = []
    i = 0
    while (i < len(movies) or i < len(tv)) and len(mixed) < limit:
        if i < len(movies):
            mixed.append(movies[i])
        if i < len(tv) and len(mixed) < limit:
            mixed.append(tv[i])
        i += 1

    await cache.set(cache_key, mixed, 60 * 60 * 24)  # 24h — refreshes daily
    return {"cached": False, "data": mixed}


@api.get("/tmdb/search")
async def tmdb_search(q: str = Query("", min_length=1)):
    """Plain multi-search wrapper — returns shaped movie + tv items
    plus PEOPLE so a viewer searching "Tom Hanks" sees his actor
    card first and can jump straight to the Person page."""
    if not q.strip():
        return {"data": []}
    cache_key = f"tmdb_search:{q.lower()}:v2"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    data = await _tmdb_get(
        "/search/multi", {"query": q, "include_adult": "false", "page": "1"}
    )
    out: List[Dict[str, Any]] = []
    for it in data.get("results") or []:
        mt = it.get("media_type")
        if mt in ("movie", "tv"):
            shaped = _shape_tmdb_item(it, mt)
            if shaped:
                out.append(shaped)
        elif mt == "person":
            profile = it.get("profile_path")
            name = (it.get("name") or "").strip()
            if not name:
                continue
            # Pull one known_for poster for fallback context.
            known_for = it.get("known_for") or []
            known_titles = [
                (k.get("title") or k.get("name") or "").strip()
                for k in known_for
            ]
            known_titles = [t for t in known_titles if t][:2]
            out.append({
                "id": it.get("id"),
                "media_type": "person",
                "name": name,
                "title": name,
                "profile": f"{TMDB_IMG}/w342{profile}" if profile else "",
                "poster":  f"{TMDB_IMG}/w342{profile}" if profile else "",
                "known_for": ", ".join(known_titles),
                "popularity": it.get("popularity") or 0,
            })
    await cache.set(cache_key, out, 60 * 60)
    return {"cached": False, "data": out}


# ----- Image proxy ------------------------------------------------------------

_IMG_PROXY_CACHE: Dict[str, bytes] = {}
_IMG_PROXY_CACHE_KEYS: List[str] = []
_IMG_PROXY_MAX = 512  # LRU cap


@api.get("/img-proxy")
async def img_proxy(
    url: str = Query(..., description="Source image URL"),
    w: int = Query(64, ge=16, le=2048),
    q: int = Query(70, ge=20, le=95),
):
    """Down-sample and re-encode an image so the Android WebView only
    has to decode a tiny WebP instead of a multi-MB PNG.

    Used by the Live TV channel-row logos and any other high-density
    grid where the source image is much larger than the display slot.
    `w` is the target width in CSS pixels; height is computed to
    preserve aspect ratio.  Output is WebP for ~70% size reduction
    vs PNG at the same quality.

    Cached in-memory per (url, w, q) with an LRU eviction cap so a
    typical 200-channel list keeps every logo in RAM after the first
    pass.  No persistence needed — restart wipes the cache and the
    next view rebuilds it within a few seconds.
    """
    key = f"{url}|{w}|{q}"
    cached = _IMG_PROXY_CACHE.get(key)
    if cached is not None:
        return Response(
            cached,
            media_type="image/webp",
            headers={"Cache-Control": "public, max-age=86400"},
        )
    try:
        async with httpx.AsyncClient(timeout=8.0, follow_redirects=True) as client:
            r = await client.get(url, headers={"User-Agent": "VesperTV/1.0"})
            r.raise_for_status()
            src = r.content
    except Exception as e:
        raise HTTPException(status_code=502, detail=f"Could not fetch source image: {e}")
    try:
        with Image.open(io.BytesIO(src)) as im:
            im = im.convert("RGBA") if im.mode in ("P", "RGBA", "LA") else im.convert("RGB")
            scale = w / max(im.width, 1)
            target_h = max(8, int(round(im.height * scale)))
            im = im.resize((w, target_h), Image.LANCZOS)
            buf = io.BytesIO()
            im.save(buf, format="WEBP", quality=q, method=4)
            data = buf.getvalue()
    except UnidentifiedImageError:
        raise HTTPException(status_code=415, detail="Unsupported image format")
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Resize failed: {e}")
    # LRU insert
    _IMG_PROXY_CACHE[key] = data
    _IMG_PROXY_CACHE_KEYS.append(key)
    while len(_IMG_PROXY_CACHE_KEYS) > _IMG_PROXY_MAX:
        old = _IMG_PROXY_CACHE_KEYS.pop(0)
        _IMG_PROXY_CACHE.pop(old, None)
    return Response(
        data,
        media_type="image/webp",
        headers={"Cache-Control": "public, max-age=86400"},
    )


# ----- Live TV — TMDB backdrop lookup for hero --------------------------------

@api.get("/tmdb/livetv-backdrop")
async def tmdb_livetv_backdrop(q: str = Query("", min_length=1)):
    """Look up a TMDB backdrop for the currently-airing programme on
    a live channel.  We hit /search/multi with the EPG title and
    return the first movie/tv hit's backdrop_path + poster_path.
    Cached aggressively (15 min) since EPG titles only change every
    ~30 min and identical titles air on many channels.
    """
    if not q.strip():
        return {"backdrop": None, "poster": None, "title": None}
    cache_key = f"livetv_backdrop:{q.strip().lower()}"
    cached = await cache.get(cache_key)
    if cached is not None:
        return cached
    try:
        data = await _tmdb_get(
            "/search/multi", {"query": q, "include_adult": "false", "page": "1"}
        )
    except Exception:
        return {"backdrop": None, "poster": None, "title": None}
    backdrop = None
    poster = None
    title = None
    for it in data.get("results") or []:
        mt = it.get("media_type")
        if mt not in ("movie", "tv"):
            continue
        backdrop = it.get("backdrop_path")
        poster = it.get("poster_path")
        title = it.get("title") or it.get("name")
        if backdrop:
            break
    out = {"backdrop": backdrop, "poster": poster, "title": title}
    await cache.set(cache_key, out, 15 * 60)
    return out



@api.get("/tmdb/similar-to-picks")
async def tmdb_similar_to_picks(
    picks: str = Query("", description="Comma-separated 'type:tmdb_id' pairs"),
    limit: int = Query(30, ge=1, le=60),
):
    """For-You "similar to what you picked" rail.

    Takes the user's hand-picked titles from the viewing-style step
    and returns the top similar / recommended titles across all of
    them, **excluding** the user's own picks (we don't want the
    rail to surface things they've explicitly chosen — they already
    know about those).  Cached for 24h so the rail refreshes daily.
    """
    raw = [p.strip() for p in (picks or "").split(",") if ":" in p]
    parsed: List[Dict[str, Any]] = []
    excluded: set[tuple[str, str]] = set()
    for token in raw:
        try:
            t, id_str = token.split(":", 1)
            t = t.strip().lower()
            if t == "series":
                t = "tv"
            if t not in ("movie", "tv"):
                continue
            int(id_str)  # validate numeric
            parsed.append({"type": t, "tmdb_id": id_str.strip()})
            excluded.add((t, id_str.strip()))
        except (ValueError, IndexError):
            continue
    if not parsed:
        return {"cached": False, "data": []}

    cache_key = "tmdb_similar:" + ",".join(
        sorted(f"{p['type']}-{p['tmdb_id']}" for p in parsed)
    ) + f":{limit}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    async def _pull_recs(media: str, tmdb_id: str) -> List[Dict[str, Any]]:
        # TMDB's "recommendations" mixes user-pattern similarity
        # with collaborative signals — better than raw "similar" for
        # a "For You" surface.  Falls back to /similar if the
        # primary call yields nothing.
        out: List[Dict[str, Any]] = []
        for path in (f"/{media}/{tmdb_id}/recommendations", f"/{media}/{tmdb_id}/similar"):
            try:
                data = await _tmdb_get(path)
            except Exception:
                continue
            for item in (data.get("results") or []):
                shaped = _shape_tmdb_item(item, media)
                if shaped:
                    out.append(shaped)
            if out:
                break
        return out

    # Pull recommendations for every pick in parallel.
    results = await asyncio.gather(
        *[_pull_recs(p["type"], p["tmdb_id"]) for p in parsed],
        return_exceptions=False,
    )

    # Round-robin merge so every pick contributes; drop excluded
    # picks (the user's own choices) and dedupe by (type, tmdb_id).
    seen: set[tuple[str, Any]] = set()
    merged: List[Dict[str, Any]] = []
    max_len = max((len(r) for r in results), default=0)
    for col in range(max_len):
        if len(merged) >= limit:
            break
        for r in results:
            if col >= len(r):
                continue
            item = r[col]
            key = (item.get("type") or "", item.get("tmdb_id"))
            # map app-shape 'series' -> tmdb 'tv' for excluded check
            excl_key = (
                "tv" if key[0] == "series" else key[0],
                str(key[1]),
            )
            if excl_key in excluded:
                continue
            if key in seen:
                continue
            seen.add(key)
            merged.append(item)
            if len(merged) >= limit:
                break

    await cache.set(cache_key, merged, 60 * 60 * 24)  # 24h refresh
    return {"cached": False, "data": merged}


# ---------------------------------------------------------------------------
# Sports Guide — LLM-powered natural-language EPG search
# ---------------------------------------------------------------------------

class SportsQueryItem(BaseModel):
    streamId: int | str
    channelName: str
    title: str
    description: str = ""
    startTs: int = 0
    stopTs: int = 0


class SportsQueryRequest(BaseModel):
    query: str
    candidates: List[SportsQueryItem]


@api.post("/sports/find")
async def sports_find(req: SportsQueryRequest):
    """
    Given a natural-language sports query and a list of EPG candidate
    programmes from sports-tagged channels, return the indices of the
    candidates that best match the query, ranked.

    Why server-side?  The query is best answered by an LLM that can
    understand "Cowboys game tonight" ≈ "NFL Dallas Cowboys ...", and
    we don't want to ship the LLM key into the client.
    """
    q = (req.query or "").strip()
    if not q:
        return {"matches": []}
    if not req.candidates:
        return {"matches": []}

    # Cap inputs so the prompt stays small + cheap.
    cand_list = req.candidates[:80]
    serialised = "\n".join(
        f"[{i}] {c.channelName} — {c.title}" + (f" ({c.description[:120]})" if c.description else "")
        for i, c in enumerate(cand_list)
    )

    try:
        from emergentintegrations.llm.chat import LlmChat, UserMessage
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=f"LLM library missing: {exc}")

    api_key = os.environ.get("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(status_code=500, detail="EMERGENT_LLM_KEY not configured")

    system_message = (
        "You are a sports concierge.  The user asks for a sport, team, "
        "match, or fighter.  You are given a numbered list of upcoming "
        "or currently-airing TV programmes from sports channels.  Pick "
        "the indices of the programmes that best match the user's "
        "request, ranked from most-relevant first.  Return AT MOST 5 "
        "indices.  Respond with ONLY a JSON array of integers, e.g. "
        "[3, 0, 7].  If nothing matches, respond with []."
    )

    chat = LlmChat(
        api_key=api_key,
        session_id=f"sports-{uuid.uuid4().hex[:8]}",
        system_message=system_message,
    ).with_model("gemini", "gemini-2.0-flash")

    user_text = f"Query: {q}\n\nProgrammes:\n{serialised}"

    try:
        response = await chat.send_message(UserMessage(text=user_text))
    except Exception as exc:
        logger.exception("Sports LLM call failed")
        raise HTTPException(status_code=502, detail=f"LLM call failed: {exc}")

    # Parse the JSON array out of the response.
    import re
    import json
    text = (response or "").strip()
    m = re.search(r"\[\s*[\d,\s]*\]", text)
    indices: List[int] = []
    if m:
        try:
            arr = json.loads(m.group(0))
            for x in arr:
                if isinstance(x, int) and 0 <= x < len(cand_list):
                    indices.append(x)
        except Exception:
            pass

    return {
        "query": q,
        "matches": [
            {
                "index": i,
                "streamId": cand_list[i].streamId,
                "channelName": cand_list[i].channelName,
                "title": cand_list[i].title,
                "description": cand_list[i].description,
                "startTs": cand_list[i].startTs,
                "stopTs": cand_list[i].stopTs,
            }
            for i in indices
        ],
    }


# ----- App wiring ----------------------------------------------------------
app.include_router(api)

# Xtream Codes IPTV proxy (auth, categories, streams, EPG)
from xtream import router as xtream_router  # noqa: E402
app.include_router(xtream_router)

from watch_party import router as watch_party_router  # noqa: E402
app.include_router(watch_party_router)

from sportsdb import router as sportsdb_router  # noqa: E402
app.include_router(sportsdb_router)

from backup import router as backup_router  # noqa: E402
app.include_router(backup_router)

from instant_bundle import router as instant_bundle_router  # noqa: E402
app.include_router(instant_bundle_router)


app.add_middleware(
    CORSMiddleware,
    allow_credentials=True,
    allow_origins=os.environ.get("CORS_ORIGINS", "*").split(","),
    allow_methods=["*"],
    allow_headers=["*"],
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger("vesper")


@app.on_event("shutdown")
async def shutdown():
    mongo.close()


@app.on_event("startup")
async def _seed_default_addons() -> None:
    """Auto-install the SUGGESTED_ADDONS list on first boot.

    Why?  Whenever the backend mongo is fresh (new VPS, dropped
    database, etc.) the user's app would show TMDB metadata fine but
    have **no streams** because the content addons (Torrentio etc.)
    aren't in the database.  This seeder closes the gap silently.

    Idempotent: each addon is installed only if its `addon_id`
    isn't already present.  Manifest is fetched server-side; if
    Cloudflare bot-walls our datacentre IP for a particular addon,
    that one is skipped and logged — the user can still install it
    manually from a browser later (their residential IP succeeds).
    """
    # Build the Torrentio URL from .env so swapping debrid keys /
    # quality filters is a redeploy, not a code change.  Quality
    # filter strips CAM / SCR / TS / unknown / 480p / 720p so only
    # 1080p HD and 4K reach the source list (user spec).
    premiumize_key = (os.environ.get("PREMIUMIZE_API_KEY") or "").strip()
    if premiumize_key:
        torrentio_url = (
            "https://torrentio.strem.fun/"
            "sort=qualitysize%7Cqualityfilter=scr,cam,unknown,480p,720p"
            f"%7Cpremiumize={premiumize_key}/manifest.json"
        )
    else:
        # No Debrid key — fall back to magnet-only Torrentio so the
        # user still gets a catalogue, but warn so the operator
        # knows playback won't work on libVLC Android.
        torrentio_url = (
            "https://torrentio.strem.fun/"
            "sort=qualitysize%7Cqualityfilter=scr,cam,unknown,480p,720p"
            "/manifest.json"
        )
        logger.warning(
            "PREMIUMIZE_API_KEY not set — Torrentio will return magnets only"
        )

    # Materialise SUGGESTED_ADDONS with the runtime URL for Torrentio.
    seed_list = []
    for s in SUGGESTED_ADDONS:
        if s.get("name") == "Torrentio":
            seed_list.append({**s, "url": torrentio_url})
        else:
            seed_list.append(s)

    try:
        existing_addons: Dict[str, Dict[str, Any]] = {}
        async for row in db.addons.find(
            {"user_id": DEFAULT_USER, "active": True},
            {"addon_id": 1, "url": 1, "name": 1, "manifest": 1},
        ):
            existing_addons[row.get("addon_id")] = row

        missing = []
        updated = []
        for s in seed_list:
            try:
                _, manifest_url = _normalize_manifest_url(s["url"])
                async with httpx.AsyncClient(timeout=20) as client:
                    manifest = await _fetch_json(client, manifest_url)
                if not isinstance(manifest, dict):
                    continue
                addon_id = manifest.get("id")
                if not addon_id:
                    continue
                expected_base = s["url"].rsplit("/manifest.json", 1)[0]
                existing = existing_addons.get(addon_id)
                # Re-upsert the row when the base URL drifted (e.g.
                # operator rotated PREMIUMIZE_API_KEY).  This keeps
                # the Torrentio config in lockstep with .env without
                # the user having to drop the row by hand.
                if existing and existing.get("url") == expected_base:
                    continue
                now = datetime.now(timezone.utc).isoformat()
                await db.addons.update_one(
                    {"user_id": DEFAULT_USER, "addon_id": addon_id},
                    {
                        "$set": {
                            "user_id": DEFAULT_USER,
                            "addon_id": addon_id,
                            "_id_str": addon_id,
                            "url": expected_base,
                            "manifest": manifest,
                            "active": True,
                            "installed_at": now,
                            "updated_at": now,
                            "auto_seeded": True,
                        }
                    },
                    upsert=True,
                )
                if existing:
                    updated.append(s["name"])
                else:
                    missing.append(s["name"])
            except Exception as inner:  # noqa: BLE001
                logger.warning(
                    "Failed to auto-seed addon %s: %s", s.get("name"), inner
                )
        if missing:
            logger.info("Auto-seeded %d default addons: %s",
                        len(missing), ", ".join(missing))
        if updated:
            logger.info("Auto-updated %d existing addons: %s",
                        len(updated), ", ".join(updated))
    except Exception as exc:  # noqa: BLE001
        logger.warning("Default-addon seeder failed: %s", exc)


@app.on_event("startup")
async def _start_epg_scheduler():
    """Kick off the background EPG refresh scheduler.  Self-registers
    every provider that has ever hit /full-epg or /cached-epg and
    re-fetches their XMLTV every ~6 h so the persisted MongoDB copy
    stays warm.  Runs in the same event loop as the API server."""
    try:
        import epg_cache
        from xtream import scheduler_refresh
        epg_cache.start_scheduler(scheduler_refresh)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to start EPG scheduler: %s", exc)


@app.on_event("startup")
async def _start_instant_bundle() -> None:
    """Boot the Instant-Bundle scheduler that keeps the Live TV
    channels/categories/EPG warm and ready to serve in one gzipped
    payload.  Reads the managed Xtream provider from `.env` (the
    `LIVETV_*` keys) — clients never need to enter credentials."""
    try:
        import instant_bundle
        instant_bundle.attach_collection(db["xtream_bundle"])
        instant_bundle.start_scheduler()
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to start instant_bundle scheduler: %s", exc)
