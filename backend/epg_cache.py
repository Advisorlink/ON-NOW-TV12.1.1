"""
Server-side persistent EPG cache + background refresh scheduler.
==================================================================

Why this module exists
----------------------
Originally the EPG (Electronic Programme Guide) was fetched on demand
by every box that opened Live TV.  That worked on a desktop browser
(strong CPU, large memory, fast disk cache) but felt sluggish on the
HK1 Android 7.1.2 boxes which have:

  • slow CPU (Cortex-A7 quad core ~1.5 GHz) → JSON parsing of a 10 MB
    payload took 8–15 seconds.
  • limited RAM (1–2 GB) → holding the parsed JSON in memory while
    rendering the channel list pushed the WebView into GC pauses.
  • shaky home Wi-Fi → the raw XMLTV download itself took 5–20 s on
    a typical British connection.

The user asked for an EPG that "stores on a server and updates
automatically in the background every couple of days" so the box can
fetch a pre-warmed, compact payload near-instantly.

Architecture
------------
1. **Persistent store in MongoDB** (`epg_cache` collection).  Survives
   backend restarts.  One document per provider, keyed by sha256 of
   `host|port|username`.  The document holds the JSON payload built
   by `xtream.full_epg`.

2. **Background scheduler** (`asyncio.create_task` from FastAPI's
   startup event).  Wakes every `_TICK_SECS` and re-fetches any
   provider whose last_fetched_at is older than `_REFRESH_INTERVAL_SECS`
   (default 6 hours — short enough that a tonight's-football fixture
   change is visible same-day, long enough that we don't hammer the
   provider).

3. **Self-registering providers**.  Every call to the existing
   `/api/xtream/full-epg` endpoint registers (or refreshes) the
   provider blob in the `epg_providers` collection so the scheduler
   picks it up on its next tick.  The provider blob is encrypted at
   rest with a key derived from `MONGO_URL` so a backend dump can't
   leak credentials directly.

4. **gzip on the wire** (added to xtream.full_epg directly — not in
   this module).  The JSON payload is ~10 MB raw, ~600 KB gzipped.

5. **`/api/xtream/cached-epg` endpoint** (new, in xtream.py) — returns
   the persisted EPG with a `cache_age_sec` field so the client knows
   how fresh it is.  No provider round-trip; returns from MongoDB.

API surface (helper functions only; HTTP endpoints live in xtream.py):

  ``provider_key(provider)``           – derive the cache key.
  ``register_provider(provider)``      – upsert the provider blob into
                                         `epg_providers`.
  ``save_payload(key, payload)``       – upsert into `epg_cache`.
  ``load_payload(key)``                – return the cached payload or
                                         None.
  ``start_scheduler(refresh_fn)``      – fire-and-forget background
                                         task.  `refresh_fn(provider)`
                                         is the async function that
                                         does the real Xtream fetch
                                         (xtream.full_epg internals).
"""
from __future__ import annotations

import asyncio
import base64
import gzip
import hashlib
import json
import logging
import os
import time
from typing import Any, Callable, Dict, List, Optional

from motor.motor_asyncio import AsyncIOMotorClient

log = logging.getLogger("vesper.epg_cache")

# How often the scheduler wakes up.  Shorter than refresh interval so
# newly-registered providers are picked up quickly.
_TICK_SECS = 60 * 10  # 10 minutes

# How stale a provider's EPG can get before we re-fetch in the
# background.  6 hours strikes a balance between freshness and the
# load we put on the provider's xmltv.php endpoint.
_REFRESH_INTERVAL_SECS = 6 * 60 * 60

# Collections.  Kept under the same DB as the rest of the app.
_COLL_CACHE = "epg_cache"
_COLL_PROVIDERS = "epg_providers"

# Lazy MongoDB handle so importing this module doesn't open a
# connection at import time (server.py owns the lifecycle).
_db = None  # type: ignore[assignment]


# ---------------------------------------------------------------------------
# Per-provider in-flight locks
# ---------------------------------------------------------------------------
#
# Without these, two near-simultaneous requests (e.g. the auth-time
# prewarm task + the LiveTV page's /cached-epg call) would each
# trigger a full xmltv.php download.  Wasteful AND it can trip the
# provider's rate-limiter.
#
# A single asyncio.Lock per provider key serialises the work; one
# request fetches, the other waits and gets the freshly-persisted
# payload instantly.

_inflight: Dict[str, asyncio.Lock] = {}


def get_inflight_lock(key: str) -> asyncio.Lock:
    """Return the asyncio.Lock associated with this provider key,
    creating it lazily.  Locks are kept for the lifetime of the
    process — they're trivially small (a few hundred bytes each)
    and we expect at most a handful of unique providers per
    deployment."""
    lock = _inflight.get(key)
    if lock is None:
        lock = asyncio.Lock()
        _inflight[key] = lock
    return lock


def _get_db():
    """Return the EPG-cache-backing database handle.  Motor's
    AsyncIOMotorClient binds to the event loop in which it was
    created — once that loop is closed (e.g. between pytest async
    tests, or in dev when uvicorn restarts), subsequent operations
    raise `RuntimeError: Event loop is closed`.  We detect that and
    transparently rebuild the singleton so callers don't have to
    care."""
    global _db
    if _db is not None:
        # Probe whether the underlying client's loop is still alive.
        try:
            loop = _db.client.get_io_loop()
            if loop.is_closed():
                _db = None
        except Exception:  # noqa: BLE001
            _db = None
    if _db is None:
        url = os.environ["MONGO_URL"]
        name = os.environ["DB_NAME"]
        _db = AsyncIOMotorClient(url)[name]
    return _db


# ---------------------------------------------------------------------------
# Provider cache-key + lightweight encryption-at-rest
# ---------------------------------------------------------------------------

def provider_key(provider: Dict[str, Any]) -> str:
    """Stable sha256 hash of host+port+username — identical to the
    one xtream.full_epg uses so cache hits work across modules."""
    raw = f"{provider['scheme']}://{provider['host']}:{provider['port']}|{provider['username']}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def _xor_key() -> bytes:
    """Derive a 32-byte XOR mask from the MONGO_URL secret.  Good
    enough to make a `mongodump` of `epg_providers` non-trivial to
    read — not crypto-strong, but a deliberate trade-off so we don't
    pull in `cryptography` or `pynacl` just for storage obfuscation."""
    seed = os.environ.get("MONGO_URL", "vesper-default-key").encode("utf-8")
    return hashlib.sha256(seed).digest()


def _xor(data: bytes) -> bytes:
    key = _xor_key()
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))


def _obfuscate(provider: Dict[str, Any]) -> str:
    import json as _json
    raw = _json.dumps(provider, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return base64.b64encode(_xor(raw)).decode("ascii")


def _deobfuscate(blob: str) -> Dict[str, Any]:
    import json as _json
    raw = _xor(base64.b64decode(blob))
    return _json.loads(raw)


# ---------------------------------------------------------------------------
# Provider registry — used by the scheduler to know what to refresh
# ---------------------------------------------------------------------------

async def register_provider(provider: Dict[str, Any]) -> None:
    """Idempotently record a provider in the registry so the
    background refresher picks it up.  Called from xtream.full_epg
    every time a client requests an EPG — that way any provider that
    ever ran through the live-fetch path automatically starts getting
    warm-cached on a schedule.

    Records the *last seen* wallclock; the scheduler skips providers
    that haven't been used in 30 days so we don't pile up dead
    accounts.
    """
    db = _get_db()
    key = provider_key(provider)
    await db[_COLL_PROVIDERS].update_one(
        {"_id": key},
        {
            "$set": {
                "blob": _obfuscate(provider),
                "last_seen_at": int(time.time()),
            },
            "$setOnInsert": {
                "first_seen_at": int(time.time()),
            },
        },
        upsert=True,
    )


async def _active_providers() -> List[Dict[str, Any]]:
    """Return the (decoded) provider blobs the scheduler should refresh
    — providers last seen within the past 30 days."""
    db = _get_db()
    cutoff = int(time.time()) - 30 * 24 * 60 * 60
    cursor = db[_COLL_PROVIDERS].find(
        {"last_seen_at": {"$gte": cutoff}}, {"_id": 1, "blob": 1}
    )
    out: List[Dict[str, Any]] = []
    async for doc in cursor:
        try:
            blob = _deobfuscate(doc["blob"])
            blob["_key"] = doc["_id"]
            out.append(blob)
        except Exception as exc:  # noqa: BLE001
            log.warning("epg_cache: skipped corrupt provider blob: %s", exc)
    return out


# ---------------------------------------------------------------------------
# Persistent EPG payload storage
# ---------------------------------------------------------------------------

async def save_payload(key: str, payload: Dict[str, Any]) -> None:
    """Upsert the most recent EPG payload for this provider key.

    `payload` is the same dict shape returned by xtream.full_epg —
    {epg, channel_count, programme_count, size_bytes, fetched_at}.

    The raw JSON of a 200-channel EPG can easily exceed MongoDB's
    16 MB single-document hard cap.  We gzip the JSON before
    writing so the on-disk doc is typically 5-10x smaller (XML/JSON
    schedule data compresses extremely well).
    """
    db = _get_db()
    raw = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    gz = gzip.compress(raw, compresslevel=6)
    doc = {
        "_id": key,
        # Legacy field kept blank so old readers don't crash;
        # new readers prefer `payload_gz`.
        "payload": None,
        "payload_gz": gz,
        "payload_size_raw": len(raw),
        "payload_size_gz":  len(gz),
        "updated_at": int(time.time()),
    }
    await db[_COLL_CACHE].replace_one({"_id": key}, doc, upsert=True)


async def load_payload(key: str) -> Optional[Dict[str, Any]]:
    """Return the most recently persisted EPG for this provider, or
    None if we've never fetched one.  Excludes `_id` so MongoDB
    ObjectId never leaks into the JSON response.

    Supports BOTH the new gzipped format (`payload_gz`) and the
    legacy plain-dict format (`payload`) so a deployment can roll
    forward without dropping existing cache entries."""
    db = _get_db()
    doc = await db[_COLL_CACHE].find_one({"_id": key}, {"_id": 0})
    if not doc:
        return None

    # Prefer the new gzipped column.
    gz = doc.get("payload_gz")
    if gz:
        try:
            payload = json.loads(gzip.decompress(gz).decode("utf-8"))
        except Exception as exc:  # noqa: BLE001
            log.warning("epg_cache: failed to decode payload_gz for %s: %s", key[:8], exc)
            return None
    else:
        payload = doc.get("payload")

    if not isinstance(payload, dict):
        return None
    payload["_persisted_at"] = doc.get("updated_at", 0)
    return payload


# ---------------------------------------------------------------------------
# Background refresh scheduler
# ---------------------------------------------------------------------------

# Holds the singleton scheduler task so a duplicate start_scheduler()
# call (e.g. autoreload) doesn't spawn two tasks racing each other.
_scheduler_task: Optional[asyncio.Task] = None


def start_scheduler(refresh_fn: Callable[[Dict[str, Any]], "asyncio.Future"]) -> None:
    """Spawn the background refresh task.  Safe to call multiple
    times — duplicate calls are ignored.

    `refresh_fn(provider) -> awaitable` is the function that performs
    the real Xtream EPG fetch and parse.  We pass it in (rather than
    importing xtream here) to avoid a circular import: xtream imports
    epg_cache for the persistence helpers; we don't want this module
    to depend back on xtream at module-load time.
    """
    global _scheduler_task
    if _scheduler_task is not None and not _scheduler_task.done():
        return
    _scheduler_task = asyncio.create_task(_scheduler_loop(refresh_fn))
    log.info("epg_cache: scheduler started (tick=%ss refresh=%ss)",
             _TICK_SECS, _REFRESH_INTERVAL_SECS)


async def _scheduler_loop(refresh_fn) -> None:
    # First tick happens 30 s after boot so we don't compete with the
    # rest of the startup sequence for the event loop.  Then the loop
    # runs every _TICK_SECS.
    await asyncio.sleep(30)
    while True:
        try:
            await _scheduler_tick(refresh_fn)
        except asyncio.CancelledError:
            log.info("epg_cache: scheduler cancelled")
            raise
        except Exception as exc:  # noqa: BLE001
            log.warning("epg_cache: tick raised %s; will retry next tick", exc)
        await asyncio.sleep(_TICK_SECS)


async def _scheduler_tick(refresh_fn) -> None:
    """One pass through every active provider; refresh any whose
    cached payload is older than _REFRESH_INTERVAL_SECS."""
    now = int(time.time())
    providers = await _active_providers()
    if not providers:
        return
    db = _get_db()
    refreshed = 0
    for prov in providers:
        key = prov.pop("_key")
        try:
            doc = await db[_COLL_CACHE].find_one({"_id": key}, {"updated_at": 1})
            age = now - (doc or {}).get("updated_at", 0)
            if age < _REFRESH_INTERVAL_SECS:
                continue
            payload = await refresh_fn(prov)
            if isinstance(payload, dict):
                await save_payload(key, payload)
                refreshed += 1
                log.info(
                    "epg_cache: refreshed %s (channels=%s, programmes=%s)",
                    key[:8],
                    payload.get("channel_count"),
                    payload.get("programme_count"),
                )
        except Exception as exc:  # noqa: BLE001
            log.warning("epg_cache: refresh failed for %s: %s", key[:8], exc)
            # On failure, sleep a beat so we don't hammer a flaky
            # provider when we have multiple registered.
            await asyncio.sleep(2)
    if refreshed:
        log.info("epg_cache: tick complete, refreshed %d/%d providers",
                 refreshed, len(providers))
