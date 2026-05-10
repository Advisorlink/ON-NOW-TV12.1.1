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
from fastapi import APIRouter, FastAPI, HTTPException
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
            headers={"User-Agent": "Vesper/0.2 (+https://vesper.tv)"},
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
app = FastAPI(title="Vesper")
api = APIRouter(prefix="/api")


@api.get("/")
async def root():
    return {"app": "Vesper", "version": "0.2.0"}


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
