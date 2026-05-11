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
    "hbo": {"id": 1899, "label": "HBO Max"},
    "disney-plus": {"id": 337, "label": "Disney Plus"},
    "prime-video": {"id": 9, "label": "Amazon Prime Video"},
    "apple-tv": {"id": 350, "label": "Apple TV Plus"},
    "hulu": {"id": 15, "label": "Hulu"},
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
