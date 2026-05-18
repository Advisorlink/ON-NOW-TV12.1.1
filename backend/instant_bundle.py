"""
Instant Bundle — server-side pre-warmed Live TV everything.

Why this module exists
----------------------
The user runs a closed client list — nobody enters their own Xtream
credentials.  Every box on first launch should land on a Live TV
guide that is FULLY POPULATED: categories, channels, and the next
6 hours of EPG, with no provider round-trip from the device.

This module:
  • Reads the single managed provider from backend `.env`
    (LIVETV_HOST / LIVETV_PORT / LIVETV_USERNAME / LIVETV_PASSWORD).
  • On startup, fetches categories + channels + EPG once and
    persists to MongoDB (`xtream_bundle` collection).
  • Schedules background refreshes:
      • Channels + categories — every 6 hours.
      • EPG                   — every 2 hours.
  • Serves the bundled payload (channels with pre-built stream URLs
    + EPG trimmed to the next 12 hours) at `/api/xtream/instant-bundle`
    as a gzipped JSON response.

Security note: client never sees the Xtream username / password.
The stream URLs we serve are pre-built so the client just plays
them — but it doesn't know how to forge new ones.
"""
from __future__ import annotations

import asyncio
import gzip
import hashlib
import json
import logging
import os
import time
from typing import Any, Dict, List, Optional

import httpx
from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import Response
from motor.motor_asyncio import AsyncIOMotorCollection

log = logging.getLogger("instant_bundle")
log.setLevel(logging.INFO)

# ---------------------------------------------------------------------------
# Tunables — kept module-level so they're easy to override from tests.
# ---------------------------------------------------------------------------
CHANNELS_REFRESH_SECS = 6 * 3600   # 6 hours
EPG_REFRESH_SECS      = 2 * 3600   # 2 hours
TICK_SECS             = 60         # how often the scheduler wakes up
HTTP_TIMEOUT          = httpx.Timeout(connect=8.0, read=60.0, write=10.0, pool=10.0)
EPG_HORIZON_SECS      = 72 * 3600  # 3 days of EPG so users can browse "what's on Saturday"

# ---------------------------------------------------------------------------
# Module-level state
# ---------------------------------------------------------------------------
_state: Dict[str, Any] = {
    "categories": [],
    "channels":   [],
    "epg":        {},
    "generated_at":       0,
    "channels_fetched_at": 0,
    "epg_fetched_at":      0,
    "last_error":          None,
    # Pre-built gzipped response payload, rebuilt once per
    # refresh.  Saves an OOM-inducing ~50 MB allocation churn
    # on every /instant-bundle request when many clients hit
    # the endpoint at once.
    "cached_payload_gz":   None,
    "cached_payload_at":   0,
}
_state_lock = asyncio.Lock()
_collection: Optional[AsyncIOMotorCollection] = None
_admin_token: str = ""

router = APIRouter(prefix="/api/xtream", tags=["xtream-bundle"])


# ---------------------------------------------------------------------------
# Provider config
# ---------------------------------------------------------------------------
def _provider_from_env() -> Optional[Dict[str, Any]]:
    """Load the managed provider from backend `.env`.  Returns None if
    not configured — the scheduler then becomes a no-op."""
    host = os.environ.get("LIVETV_HOST", "").strip()
    user = os.environ.get("LIVETV_DEFAULT_USERNAME", "").strip()
    pw = os.environ.get("LIVETV_DEFAULT_PASSWORD", "").strip()
    if not (host and user and pw):
        return None
    return {
        "id":       _provider_key(host, user),
        "scheme":   os.environ.get("LIVETV_SCHEME", "https").strip() or "https",
        "host":     host,
        "port":     os.environ.get("LIVETV_PORT", "443").strip() or "443",
        "username": user,
        "password": pw,
    }


def _provider_key(host: str, username: str) -> str:
    """Stable provider ID so the client can keep its own settings keyed
    to this provider (favorites, last-watched, etc.)."""
    raw = f"{host}|{username}"
    return "managed-" + hashlib.sha1(raw.encode()).hexdigest()[:12]


def _base_url(p: Dict[str, Any]) -> str:
    port_part = ""
    if p["port"] and p["port"] not in ("80", "443"):
        port_part = ":" + p["port"]
    return f"{p['scheme']}://{p['host']}{port_part}"


def _build_stream_url(p: Dict[str, Any], stream_id: Any, ext: str = "ts") -> str:
    """Build the canonical Xtream live stream URL for a given stream_id.
    Client receives pre-built URLs — never sees creds."""
    return (
        f"{_base_url(p)}/live/{p['username']}/{p['password']}/{stream_id}.{ext}"
    )


# ---------------------------------------------------------------------------
# Provider HTTP helpers
# ---------------------------------------------------------------------------
_http_client: Optional[httpx.AsyncClient] = None


def _http() -> httpx.AsyncClient:
    global _http_client
    if _http_client is None:
        _http_client = httpx.AsyncClient(
            timeout=HTTP_TIMEOUT, follow_redirects=True, verify=False
        )
    return _http_client


async def _player_api(p: Dict[str, Any], action: str) -> Any:
    url = f"{_base_url(p)}/player_api.php"
    params = {
        "username": p["username"],
        "password": p["password"],
        "action":   action,
    }
    resp = await _http().get(url, params=params)
    resp.raise_for_status()
    return resp.json()


# ---------------------------------------------------------------------------
# Mongo persistence
# ---------------------------------------------------------------------------
def attach_collection(col: AsyncIOMotorCollection) -> None:
    """Wire up the Motor collection used to persist the bundle so the
    backend survives a restart with a warm cache."""
    global _collection
    _collection = col


async def _rebuild_cached_payload() -> None:
    """Serialise the current `_state` to gzipped JSON and stash it on
    `_state["cached_payload_gz"]`.  Called at the end of every
    refresh (channels + EPG) so the /instant-bundle GET endpoint
    can just stream the bytes — never allocating multi-MB Python
    structures per request.

    Must run under `_state_lock` for atomicity."""
    p = _provider_from_env() or {}
    payload = {
        "provider": {
            "id":     p.get("id", "managed"),
            "name":   "On Now TV",
            "host":   p.get("host", ""),
            "port":   p.get("port", ""),
            "scheme": p.get("scheme", "https"),
        },
        "categories":          _state["categories"],
        "channels":            _state["channels"],
        "epg":                 _state["epg"],
        "generated_at":        _state["generated_at"],
        "channels_fetched_at": _state["channels_fetched_at"],
        "epg_fetched_at":      _state["epg_fetched_at"],
    }
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    gz = gzip.compress(body, compresslevel=6)
    _state["cached_payload_gz"] = gz
    _state["cached_payload_at"] = int(time.time())
    log.info(
        "instant_bundle: cached payload built (gz=%d KB, raw=%d KB)",
        len(gz) // 1024, len(body) // 1024,
    )
    # Body bytes go out of scope here; only the gz bytes (and the
    # underlying _state dicts) remain in memory.


async def _persist() -> None:
    """Persist the current bundle to MongoDB.

    Channels + EPG together can be > 16 MB raw (the MongoDB single-
    document hard cap), so we gzip the heavy payload columns
    individually.  Compression ratio on schedule/JSON data is
    typically 5-10x so the on-disk doc lands comfortably under
    the limit.
    """
    if _collection is None:
        return
    chans_blob = gzip.compress(
        json.dumps(_state["channels"], separators=(",", ":")).encode("utf-8"),
        compresslevel=6,
    )
    epg_blob = gzip.compress(
        json.dumps(_state["epg"], separators=(",", ":")).encode("utf-8"),
        compresslevel=6,
    )
    cats_blob = gzip.compress(
        json.dumps(_state["categories"], separators=(",", ":")).encode("utf-8"),
        compresslevel=6,
    )
    doc = {
        "_id":                  "v1",
        # Gzipped columns (the new path).
        "categories_gz":        cats_blob,
        "channels_gz":          chans_blob,
        "epg_gz":               epg_blob,
        # Legacy keys cleared so old readers fall through to the
        # _gz path.  We don't write the plain dicts any more.
        "categories":           [],
        "channels":             [],
        "epg":                  {},
        "generated_at":         _state["generated_at"],
        "channels_fetched_at":  _state["channels_fetched_at"],
        "epg_fetched_at":       _state["epg_fetched_at"],
    }
    try:
        await _collection.replace_one({"_id": "v1"}, doc, upsert=True)
    except Exception as exc:  # noqa: BLE001
        log.warning(
            "instant_bundle: persist failed (cats_gz=%dKB chans_gz=%dKB epg_gz=%dKB): %s",
            len(cats_blob) // 1024, len(chans_blob) // 1024, len(epg_blob) // 1024, exc,
        )


def _maybe_decode(doc: Dict[str, Any], key: str, default: Any) -> Any:
    """Return `doc[key + '_gz']` decoded if present, else `doc[key]`."""
    gz = doc.get(key + "_gz")
    if gz:
        try:
            return json.loads(gzip.decompress(gz).decode("utf-8"))
        except Exception as exc:  # noqa: BLE001
            log.warning("instant_bundle: failed to decode %s_gz: %s", key, exc)
    return doc.get(key) or default


async def _restore_from_db() -> None:
    if _collection is None:
        return
    try:
        doc = await _collection.find_one({"_id": "v1"})
    except Exception as exc:  # noqa: BLE001
        log.warning("instant_bundle: restore failed: %s", exc)
        return
    if not doc:
        return
    async with _state_lock:
        _state["categories"]          = _maybe_decode(doc, "categories", [])
        _state["channels"]            = _maybe_decode(doc, "channels",   [])
        _state["epg"]                 = _maybe_decode(doc, "epg",        {})
        _state["generated_at"]        = int(doc.get("generated_at")        or 0)
        _state["channels_fetched_at"] = int(doc.get("channels_fetched_at") or 0)
        _state["epg_fetched_at"]      = int(doc.get("epg_fetched_at")      or 0)
        # Build the gzipped response cache once now so the very
        # first /instant-bundle request after a backend restart
        # doesn't have to do it on the request thread.
        if _state["channels"]:
            await _rebuild_cached_payload()
    log.info(
        "instant_bundle: warm-started from MongoDB (channels=%d categories=%d epg=%d)",
        len(_state["channels"]), len(_state["categories"]), len(_state["epg"]),
    )


# ---------------------------------------------------------------------------
# Refresh tasks
# ---------------------------------------------------------------------------
async def _refresh_channels(p: Dict[str, Any]) -> None:
    """Fetch categories + live streams and cache normalised records."""
    log.info("instant_bundle: refreshing channels…")
    cats_raw = await _player_api(p, "get_live_categories")
    streams_raw = await _player_api(p, "get_live_streams")

    categories = [
        {
            "id":   str(c.get("category_id")),
            "name": str(c.get("category_name") or "").strip(),
        }
        for c in (cats_raw or [])
        if c and c.get("category_id") is not None
    ]
    channels = []
    for s in (streams_raw or []):
        if not s or s.get("stream_id") is None:
            continue
        sid = str(s.get("stream_id"))
        channels.append({
            "stream_id":      sid,
            "name":           str(s.get("name") or "").strip(),
            "logo":           str(s.get("stream_icon") or ""),
            "category_id":    str(s.get("category_id") or ""),
            "epg_channel_id": str(s.get("epg_channel_id") or ""),
            "tv_archive":     int(s.get("tv_archive") or 0),
            "stream_url":     _build_stream_url(p, sid, ext="ts"),
        })

    async with _state_lock:
        _state["categories"]          = categories
        _state["channels"]            = channels
        _state["channels_fetched_at"] = int(time.time())
        _state["generated_at"]        = int(time.time())
        _state["last_error"]          = None
        # Invalidate the cached gz payload — the channel list
        # just changed.  Rebuild it eagerly so the next request
        # streams straight from the cache.
        await _rebuild_cached_payload()
    await _persist()
    log.info(
        "instant_bundle: channels OK (%d channels in %d categories)",
        len(channels), len(categories),
    )


async def _refresh_epg(p: Dict[str, Any]) -> None:
    """Fetch the XMLTV EPG and trim it to the next 72 hours per channel.

    Streams the response straight to a temp file so we don't hold
    the entire 150 MB XMLTV blob in RAM — that single allocation
    was a major contributor to the production pod OOM-kills."""
    log.info("instant_bundle: refreshing EPG…")
    url = f"{_base_url(p)}/xmltv.php"
    params = {"username": p["username"], "password": p["password"]}

    import tempfile
    import os as _os
    import xml.etree.ElementTree as ET
    from datetime import datetime, timezone

    def _parse_ts(s: str) -> int:
        """Parse a single XMLTV timestamp attribute into a unix
        timestamp.  Handles BOTH common formats:
          • '20260517 091500 +0000'   (date space time space tz)
          • '20260516010000 +0100'    (date+time joined, then space tz)
        Returns 0 if the timestamp can't be parsed.
        """
        if not s:
            return 0
        s = s.strip()
        if len(s) >= 14 and " " in s and s[:14].isdigit():
            head, _, tz = s.partition(" ")
            try:
                dt = datetime.strptime(head + " " + tz, "%Y%m%d%H%M%S %z")
                return int(dt.timestamp())
            except ValueError:
                pass
        try:
            dt = datetime.strptime(s, "%Y%m%d %H%M%S %z")
            return int(dt.timestamp())
        except ValueError:
            pass
        try:
            parts = s.split(" ")
            if len(parts) >= 2:
                dt = datetime.strptime(parts[0] + " " + parts[1], "%Y%m%d %H%M%S")
                dt = dt.replace(tzinfo=timezone.utc)
                return int(dt.timestamp())
        except (ValueError, IndexError):
            pass
        if len(s) >= 14 and s[:14].isdigit():
            try:
                dt = datetime.strptime(s[:14], "%Y%m%d%H%M%S")
                dt = dt.replace(tzinfo=timezone.utc)
                return int(dt.timestamp())
            except ValueError:
                pass
        return 0

    epg_by_channel: Dict[str, List[Dict[str, Any]]] = {}

    tmp = tempfile.NamedTemporaryFile(prefix="vesper-epg-", suffix=".xml", delete=False)
    tmp_path = tmp.name
    try:
        # Stream the download to disk — 150 MB XMLTV payloads no
        # longer hit RAM all at once.
        async with _http().stream("GET", url, params=params) as resp:
            resp.raise_for_status()
            async for chunk in resp.aiter_bytes(chunk_size=1024 * 1024):
                tmp.write(chunk)
        tmp.flush()
        tmp.close()

        # One-pass iter parser straight off the temp file — peak
        # memory is one programme element at a time, not the whole
        # DOM.
        try:
            for _event, el in ET.iterparse(tmp_path, events=("end",)):
                if el.tag != "programme":
                    continue
                ch = (el.attrib.get("channel") or "").strip()
                if not ch:
                    el.clear()
                    continue
                # Many providers (including ours) include direct
                # unix-timestamp attributes alongside the human
                # timestamps.  Prefer those — they're parse-free.
                ts_raw_start = el.attrib.get("start_timestamp")
                ts_raw_stop  = el.attrib.get("stop_timestamp")
                if ts_raw_start and ts_raw_stop:
                    try:
                        start_ts = int(ts_raw_start)
                        stop_ts  = int(ts_raw_stop)
                    except (TypeError, ValueError):
                        start_ts = _parse_ts(el.attrib.get("start", ""))
                        stop_ts  = _parse_ts(el.attrib.get("stop", ""))
                else:
                    start_ts = _parse_ts(el.attrib.get("start", ""))
                    stop_ts  = _parse_ts(el.attrib.get("stop", ""))
                title_el = el.find("title")
                desc_el  = el.find("desc")
                cat_el   = el.find("category")
                programme = {
                    "title":           (title_el.text or "").strip() if title_el is not None else "",
                    "desc":            (desc_el.text or "").strip() if desc_el is not None else "",
                    "category":        (cat_el.text or "").strip() if cat_el is not None else "",
                    "startTimestamp":  start_ts,
                    "stopTimestamp":   stop_ts,
                }
                epg_by_channel.setdefault(ch, []).append(programme)
                el.clear()
        except Exception as exc:  # noqa: BLE001
            log.warning("instant_bundle: XMLTV parse failed: %s", exc)
    finally:
        # The temp XMLTV file is no longer needed; remove it so
        # we don't fill /tmp on long-running pods.
        try:
            _os.unlink(tmp_path)
        except Exception:  # noqa: BLE001
            pass

    # Sort + trim to next 72 hours (now → now+72 h).
    now_sec = int(time.time())
    horizon = now_sec + EPG_HORIZON_SECS
    trimmed: Dict[str, List[Dict[str, Any]]] = {}
    for ch_id, lst in epg_by_channel.items():
        lst.sort(key=lambda p: p["startTimestamp"])
        keep = [
            p for p in lst
            if p["stopTimestamp"] >= now_sec and p["startTimestamp"] <= horizon
        ]
        if keep:
            trimmed[ch_id] = keep

    async with _state_lock:
        _state["epg"]            = trimmed
        _state["epg_fetched_at"] = int(time.time())
        _state["generated_at"]   = int(time.time())
        _state["last_error"]     = None
        # Rebuild the cached gz payload now (atomically under the
        # lock) so the next /instant-bundle request streams the
        # fresh data without re-allocating 30+ MB.
        await _rebuild_cached_payload()
    await _persist()
    log.info(
        "instant_bundle: EPG OK (%d channels with EPG, ~%d programmes total)",
        len(trimmed), sum(len(v) for v in trimmed.values()),
    )


# ---------------------------------------------------------------------------
# Scheduler
# ---------------------------------------------------------------------------
async def _scheduler_loop() -> None:
    """Background task: every TICK_SECS, refresh whatever is stale."""
    while True:
        try:
            p = _provider_from_env()
            if p:
                now_sec = int(time.time())
                # First-run / channels stale → refresh channels.
                if now_sec - _state["channels_fetched_at"] >= CHANNELS_REFRESH_SECS:
                    try:
                        await _refresh_channels(p)
                    except Exception as exc:  # noqa: BLE001
                        log.warning("instant_bundle: channels refresh failed: %s", exc)
                        _state["last_error"] = f"channels: {exc}"
                # EPG stale → refresh EPG.
                if now_sec - _state["epg_fetched_at"] >= EPG_REFRESH_SECS:
                    try:
                        await _refresh_epg(p)
                    except Exception as exc:  # noqa: BLE001
                        log.warning("instant_bundle: EPG refresh failed: %s", exc)
                        _state["last_error"] = f"epg: {exc}"
        except Exception as exc:  # noqa: BLE001
            log.warning("instant_bundle: scheduler tick failed: %s", exc)
        await asyncio.sleep(TICK_SECS)


def start_scheduler(admin_token: str = "") -> None:
    """Kick off the background scheduler.  Called from FastAPI startup."""
    global _admin_token
    _admin_token = admin_token or os.environ.get("XTREAM_ADMIN_TOKEN", "")

    async def _boot() -> None:
        await _restore_from_db()
        await _scheduler_loop()

    asyncio.create_task(_boot())
    log.info("instant_bundle: scheduler started")


# ---------------------------------------------------------------------------
# HTTP endpoints
# ---------------------------------------------------------------------------
@router.get("/instant-bundle")
async def instant_bundle() -> Response:
    """Return the pre-warmed bundle as gzipped JSON.

    Clients call this on app boot to populate Live TV with no
    per-device Xtream round-trip.  Includes ALL channels +
    categories + the next 72 h of EPG with pre-built stream URLs.

    The gzipped payload is built once per refresh and cached on
    `_state["cached_payload_gz"]` so every request just streams
    the bytes.  This was the OOM-inducing endpoint in production
    (each request used to allocate ~50 MB of transient Python
    objects to re-serialise the bundle from scratch)."""
    p = _provider_from_env()
    if not p:
        raise HTTPException(503, "Managed Xtream provider not configured on backend.")

    # First boot — fetch channels synchronously so the client
    # doesn't get an empty bundle on the very first request.
    if not _state["channels"]:
        try:
            await _refresh_channels(p)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(502, f"Provider unreachable: {exc}") from None

    # If the cache wasn't built yet (or got invalidated), build it
    # now under the lock.
    if not _state.get("cached_payload_gz"):
        async with _state_lock:
            if not _state.get("cached_payload_gz"):
                await _rebuild_cached_payload()

    gz = _state["cached_payload_gz"]
    return Response(
        content=gz,
        media_type="application/json",
        headers={
            "Content-Encoding": "gzip",
            "Cache-Control":    "no-store",
        },
    )


@router.get("/instant-bundle/meta")
async def instant_bundle_meta() -> Dict[str, Any]:
    """Lightweight metadata endpoint — used by the client to decide
    whether to bother re-downloading the full bundle (compares
    `generated_at` against its local cache)."""
    return {
        "provider_id":          _provider_from_env()["id"] if _provider_from_env() else "",
        "channels_count":       len(_state["channels"]),
        "categories_count":     len(_state["categories"]),
        "epg_channels":         len(_state["epg"]),
        "generated_at":         _state["generated_at"],
        "channels_fetched_at":  _state["channels_fetched_at"],
        "epg_fetched_at":       _state["epg_fetched_at"],
        "last_error":           _state["last_error"],
    }


@router.post("/instant-bundle/refresh")
async def admin_refresh(
    token: str = Query(..., description="Admin token (matches XTREAM_ADMIN_TOKEN env)"),
    target: str = Query("all", regex="^(all|channels|epg)$"),
) -> Dict[str, Any]:
    """Admin-only endpoint to FORCE a refresh of the bundle.

    Use case: provider just pushed a big EPG change and you want it
    live for clients NOW instead of waiting for the 2-hour scheduler
    tick.  Token must match the `XTREAM_ADMIN_TOKEN` backend env var.
    """
    expected = _admin_token or os.environ.get("XTREAM_ADMIN_TOKEN", "")
    if not expected or token != expected:
        raise HTTPException(401, "Invalid admin token.")
    p = _provider_from_env()
    if not p:
        raise HTTPException(503, "Managed Xtream provider not configured.")
    out: Dict[str, Any] = {"refreshed": []}
    if target in ("all", "channels"):
        await _refresh_channels(p)
        out["refreshed"].append("channels")
    if target in ("all", "epg"):
        await _refresh_epg(p)
        out["refreshed"].append("epg")
    out["generated_at"] = _state["generated_at"]
    return out
