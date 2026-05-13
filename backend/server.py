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
import time
import uuid
import asyncio
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional
from urllib.parse import quote

import httpx
from dotenv import load_dotenv
from fastapi import APIRouter, FastAPI, HTTPException, Query
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
        "name": "OpenSubtitles",
        "url": "https://opensubtitles-v3.strem.io/manifest.json",
        "description": "Subtitle search across the OpenSubtitles database",
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
    cache_key = "networks:logos:v1"
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
            "logo": f"{TMDB_IMG}/original{lp}" if lp else None,
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
    cache_key = f"tmdb_kids_shelves:v5:{movie_cert}:{tv_level}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    async def shelf(id_, title, media, params):
        items = await _tmdb_discover_kids(
            media, extra=params, movie_cert=movie_cert, tv_level=tv_level
        )
        return {
            "id": id_,
            "title": title,
            "eyebrow": "Movies" if media == "movie" else "Cartoons",
            "type": "movie" if media == "movie" else "series",
            "items": items[:24],
        }

    queries = [
        shelf("family-favorites", "Family Favourites", "movie", {"sort_by": "popularity.desc"}),
        shelf("animated-magic",  "Animated Magic",    "movie", {"with_genres": "16,10751", "sort_by": "popularity.desc"}),
        shelf("top-rated-family","Top-Rated Family",  "movie", {"sort_by": "vote_average.desc", "vote_count.gte": 500}),
        shelf("adventure-time",  "Adventure Time",    "movie", {"with_genres": "10751,12", "sort_by": "popularity.desc"}),
        shelf("animated-series", "Animated Shows",    "tv",    {"sort_by": "popularity.desc"}),
        shelf("top-cartoons",    "Top-Rated Cartoons","tv",    {"sort_by": "vote_average.desc", "vote_count.gte": 100}),
        shelf("recent-family",   "New for the Family","movie", {"sort_by": "primary_release_date.desc", "vote_count.gte": 100, "primary_release_date.lte": datetime.now(timezone.utc).date().isoformat()}),
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

    data = await _tmdb_get(
        "/search/multi",
        {"query": q, "include_adult": "false", "page": 1},
    )

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
    for item in data.get("results") or []:
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

    verified = await asyncio.gather(*[cert_ok(m) for m in movie_candidates[:16]])
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


# ----- App wiring ----------------------------------------------------------
app.include_router(api)

# Xtream Codes IPTV proxy (auth, categories, streams, EPG)
from xtream import router as xtream_router  # noqa: E402
app.include_router(xtream_router)

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
