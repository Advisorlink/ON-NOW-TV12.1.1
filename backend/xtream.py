"""
Xtream Codes IPTV proxy
=======================

Thin async proxy around an Xtream-Codes provider's player_api.php +
xmltv.php endpoints.

Why proxy:
  • The TV-box WebView can't easily handle CORS variability across
    providers, especially on shaky preview backends.
  • Xtream APIs are unauthenticated transports (user/pass go in the
    query-string), so we want a single trusted hop where we can log,
    cache, and rate-limit.
  • EPG payloads are typically large XML — we'd rather convert once,
    cache, and ship JSON to the client.

Endpoints exposed (mounted under /api/xtream/* by `xtream.routes`):
  POST  /api/xtream/auth          – validate creds, return user_info
  GET   /api/xtream/categories    – ?provider=&type=live|vod|series
  GET   /api/xtream/streams       – ?provider=&type=live|vod|series[&category_id=]
  GET   /api/xtream/series-info   – ?provider=&series_id=
  GET   /api/xtream/short-epg     – ?provider=&stream_id=&limit=
  GET   /api/xtream/now-next      – ?provider=&stream_id=

The "provider" param is a JSON-encoded credential blob the client
attaches per request: {host, port, scheme, username, password}.
We never persist credentials server-side — the client is the source
of truth.  Cache keys are derived from a sha256 hash of the blob so
two boxes pointing at the same provider share cached responses.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import logging
import time
from typing import Any, Dict, Optional

import httpx
from fastapi import APIRouter, HTTPException, Query, Body
from fastapi.responses import Response

import epg_cache

log = logging.getLogger("vesper.xtream")

# Re-use a single httpx client across requests for connection pooling.
_client: Optional[httpx.AsyncClient] = None


def _http() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            timeout=15.0,
            follow_redirects=True,
            headers={"User-Agent": "ONNowTV/1.0"},
        )
    return _client


# ---------------------------------------------------------------------------
# Credentials parsing
# ---------------------------------------------------------------------------

def _parse_provider(blob: str) -> Dict[str, Any]:
    """Provider blob is a JSON object the client encodes verbatim.

    Required keys: host, port, username, password.  Optional: scheme
    (defaults to "http"), name.  We accept either an http(s):// URL
    in `host` (preferred — the user just pastes their DNS) and parse
    it for them, or `host`+`port` as separate fields.
    """
    try:
        p = json.loads(blob) if isinstance(blob, str) else dict(blob)
    except Exception as e:  # noqa: BLE001 — accept anything malformed
        raise HTTPException(400, f"Invalid provider blob: {e}") from None

    host = (p.get("host") or "").strip()
    port = str(p.get("port") or "").strip()
    scheme = (p.get("scheme") or "").strip().lower()
    user = (p.get("username") or "").strip()
    pwd = (p.get("password") or "").strip()

    # If host looks like a full URL, split it.
    if host.startswith(("http://", "https://")):
        from urllib.parse import urlparse
        u = urlparse(host)
        scheme = scheme or u.scheme
        host = u.hostname or host
        if u.port and not port:
            port = str(u.port)
    # If host has :port embedded, split.
    if ":" in host and not port:
        host, _, port = host.partition(":")

    scheme = scheme or "http"
    if not host or not user or not pwd:
        raise HTTPException(400, "Provider requires host, username, password.")

    return {
        "scheme": scheme,
        "host": host,
        "port": port or ("443" if scheme == "https" else "80"),
        "username": user,
        "password": pwd,
        "name": p.get("name") or host,
    }


def _base_url(p: Dict[str, Any]) -> str:
    return f"{p['scheme']}://{p['host']}:{p['port']}"


def _provider_id(p: Dict[str, Any]) -> str:
    """Stable hash of credentials for cache keying."""
    key = f"{p['host']}:{p['port']}:{p['username']}:{p['password']}"
    return hashlib.sha256(key.encode()).hexdigest()[:16]


# ---------------------------------------------------------------------------
# Low-level player_api.php call
# ---------------------------------------------------------------------------

async def _player_api(
    p: Dict[str, Any],
    action: Optional[str] = None,
    **extra: Any,
) -> Any:
    """Call player_api.php on the provider and return parsed JSON."""
    url = f"{_base_url(p)}/player_api.php"
    params: Dict[str, Any] = {
        "username": p["username"],
        "password": p["password"],
    }
    if action:
        params["action"] = action
    for k, v in extra.items():
        if v is not None:
            params[k] = v

    try:
        resp = await _http().get(url, params=params)
    except httpx.HTTPError as e:
        log.warning("Xtream call failed: %s %s -> %s", url, action, e)
        raise HTTPException(502, f"Provider unreachable: {e}") from None

    if resp.status_code != 200:
        raise HTTPException(
            502, f"Provider returned HTTP {resp.status_code}"
        )
    try:
        return resp.json()
    except ValueError:
        # Some providers return an HTML login page on bad creds.
        return None


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------

router = APIRouter(prefix="/api/xtream", tags=["xtream"])


@router.post("/auth")
async def auth(payload: Dict[str, Any] = Body(...)) -> Dict[str, Any]:
    """Validate the provider by hitting player_api.php with no action.

    Returns the user_info + server_info block on success, raises on
    failure.  Client persists the validated credentials to
    localStorage and uses them on all subsequent requests.

    Side-effect: on a successful auth we IMMEDIATELY kick off the
    full XMLTV EPG fetch + parse + persist as a fire-and-forget
    background task.  By the time the user navigates from the login
    screen → Home → Live TV (typically 5-15 s of human UI input),
    the server has the entire EPG cached for that provider.  The
    LiveTV boot splash then hits `/cached-epg` and gets a sub-
    second gzipped response instead of paying the cold-fetch cost.

    The task is fire-and-forget on purpose — we don't await it,
    because the login response shouldn't block on a 10-MB XMLTV
    download.  If the fetch fails the user just gets the legacy
    per-channel fallback when they reach Live TV.
    """
    import asyncio as _asyncio
    p = _parse_provider(json.dumps(payload))
    data = await _player_api(p)
    if (
        not isinstance(data, dict)
        or "user_info" not in data
        or data.get("user_info", {}).get("auth") in (0, "0")
    ):
        raise HTTPException(401, "Invalid Xtream credentials.")

    async def _prewarm_epg(prov: Dict[str, Any]) -> None:
        """Background task — fetches + parses + persists the full
        EPG so the user's first Live TV visit is instant.  Skips
        the fetch if a fresh persisted copy already exists (< 6 h
        old) so we don't hammer the provider's xmltv.php endpoint
        when the same user logs in repeatedly.

        Uses the per-provider asyncio.Lock from epg_cache so that
        if `/cached-epg` arrives mid-prewarm the second request
        just awaits the lock and serves the result instantly when
        the prewarm completes (no duplicate xmltv.php hit)."""
        key = epg_cache.provider_key(prov)
        lock = epg_cache.get_inflight_lock(key)
        async with lock:
            try:
                await epg_cache.register_provider(prov)
                persisted = await epg_cache.load_payload(key)
                if persisted:
                    age = int(time.time()) - persisted.get("_persisted_at", 0)
                    if age < 6 * 60 * 60:
                        log.info(
                            "xtream.auth prewarm: skipping fetch for %s "
                            "(persisted %d s ago)",
                            key[:8], age,
                        )
                        return
                log.info("xtream.auth prewarm: fetching full EPG for %s", key[:8])
                payload = await _fetch_and_parse_xmltv(prov)
                await epg_cache.save_payload(key, payload)
                log.info(
                    "xtream.auth prewarm: persisted EPG for %s "
                    "(channels=%s, programmes=%s)",
                    key[:8],
                    payload.get("channel_count"),
                    payload.get("programme_count"),
                )
            except Exception as exc:  # noqa: BLE001
                log.warning("xtream.auth prewarm failed: %s", exc)

    # Fire-and-forget.  Note: we don't `await` this — we return the
    # auth response immediately and let the task run in the same
    # event loop in parallel with whatever the user does next.
    _asyncio.create_task(_prewarm_epg(p))

    return {
        "ok": True,
        "providerId": _provider_id(p),
        "user_info": data.get("user_info"),
        "server_info": data.get("server_info"),
        # Diagnostic — the frontend can show "EPG prewarming…" so
        # the user understands what's happening if they're really
        # fast and beat the background task to the Live TV page.
        "epg_prewarm_started": True,
    }


@router.get("/categories")
async def categories(
    provider: str = Query(...),
    type: str = Query("live", regex="^(live|vod|series)$"),
) -> Any:
    p = _parse_provider(provider)
    action = {
        "live": "get_live_categories",
        "vod": "get_vod_categories",
        "series": "get_series_categories",
    }[type]
    data = await _player_api(p, action=action) or []
    return {"categories": data}


@router.get("/streams")
async def streams(
    provider: str = Query(...),
    type: str = Query("live", regex="^(live|vod|series)$"),
    category_id: Optional[str] = Query(None),
) -> Any:
    p = _parse_provider(provider)
    action = {
        "live": "get_live_streams",
        "vod": "get_vod_streams",
        "series": "get_series",
    }[type]
    data = await _player_api(p, action=action, category_id=category_id) or []
    return {"streams": data, "providerId": _provider_id(p)}


@router.get("/series-info")
async def series_info(
    provider: str = Query(...),
    series_id: str = Query(...),
) -> Any:
    p = _parse_provider(provider)
    data = await _player_api(p, action="get_series_info", series_id=series_id)
    return data or {}


@router.get("/short-epg")
async def short_epg(
    provider: str = Query(...),
    stream_id: str = Query(...),
    limit: int = Query(4),
) -> Any:
    p = _parse_provider(provider)
    data = await _player_api(
        p,
        action="get_short_epg",
        stream_id=stream_id,
        limit=limit,
    ) or {}
    return data


@router.get("/now-next")
async def now_next(
    provider: str = Query(...),
    stream_id: str = Query(...),
) -> Any:
    """Return only the current + next listings, decoded base64 titles."""
    import base64
    p = _parse_provider(provider)
    data = await _player_api(
        p,
        action="get_short_epg",
        stream_id=stream_id,
        limit=2,
    ) or {}
    listings = data.get("epg_listings") or []
    out = []
    for item in listings[:2]:
        title = item.get("title", "")
        desc = item.get("description", "")
        try:
            title = base64.b64decode(title).decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            pass
        try:
            desc = base64.b64decode(desc).decode("utf-8", "replace")
        except Exception:  # noqa: BLE001
            pass
        out.append({
            "title": title,
            "description": desc,
            "start": item.get("start"),
            "end": item.get("end"),
            "startTimestamp": item.get("start_timestamp"),
            "stopTimestamp": item.get("stop_timestamp"),
        })
    return {"items": out}


@router.get("/stream-url")
async def stream_url(
    provider: str = Query(...),
    type: str = Query("live", regex="^(live|movie|series)$"),
    stream_id: str = Query(...),
    container_extension: str = Query("ts"),
) -> Dict[str, str]:
    """Return the direct stream URL the native player should hit.

    For live the URL is `/live/USER/PASS/STREAM_ID.ts`; movies use
    `/movie/USER/PASS/STREAM_ID.<ext>`; series episodes use
    `/series/USER/PASS/EPISODE_ID.<ext>`.
    """
    p = _parse_provider(provider)
    ext = container_extension.strip(".") or "ts"
    url = (
        f"{_base_url(p)}/{type}/"
        f"{p['username']}/{p['password']}/{stream_id}.{ext}"
    )
    return {"url": url}


# ---------------------------------------------------------------------------
# Full XMLTV EPG endpoint
# ---------------------------------------------------------------------------
#
# Xtream-Codes providers expose a single endpoint that returns the
# entire EPG for ALL channels in one shot: `xmltv.php?username=...&
# password=...`.  The payload is XMLTV XML (often 5–50 MB) and the
# server gzip-compresses it on the wire when we ask for it.  This is
# DRAMATICALLY faster than hammering `get_short_epg` 14 000 times — a
# single ~3 MB compressed download instead of 14 000 small JSON ones.
#
# We stream-parse the XML (memory-bounded) and return a compact JSON
# index keyed by EPG channel id:
#   {
#     "<epg_channel_id>": [
#       {title, description, start, stop, startTimestamp, stopTimestamp},
#       …
#     ],
#     …
#   }
# The frontend joins that against each channel's `epg_channel_id` (a
# field already present on the channel record from `get_live_streams`).

from datetime import datetime, timezone
import time as _time_mod
import xml.etree.ElementTree as _ET
from io import BytesIO

# In-memory cache: {provider_hash: (expires_at, payload)}
_XMLTV_CACHE: Dict[str, tuple[float, Dict[str, Any]]] = {}
_XMLTV_TTL = 30 * 60  # 30 min


def _xmltv_url(p: Dict[str, Any]) -> str:
    return (
        f"{_base_url(p)}/xmltv.php"
        f"?username={p['username']}&password={p['password']}"
    )


def _parse_xmltv_time(raw: Optional[str]) -> int:
    """XMLTV times look like "20260515063000 +0000".  Return unix sec."""
    if not raw:
        return 0
    raw = raw.strip()
    try:
        # Strip the optional " +HHMM" tz suffix (we treat everything as
        # UTC — programme guides are always in absolute time anyway).
        if " " in raw:
            raw = raw.split()[0]
        dt = datetime.strptime(raw[:14], "%Y%m%d%H%M%S")
        return int(dt.replace(tzinfo=timezone.utc).timestamp())
    except Exception:
        return 0


@router.get("/full-epg")
async def full_epg(
    provider: str = Query(...),
    refresh: int = Query(0, description="set 1 to bypass cache"),
) -> Dict[str, Any]:
    """Return the FULL XMLTV EPG for this provider as a JSON index.

    Heavy endpoint — easily 10 MB of response.  Caches 30 min per
    provider so subsequent calls are near-instant.  The cache key is
    the provider blob's sha256 so two devices sharing the same
    credentials share the cache.

    Response:
      {
        cached:  bool,
        fetched_at: unix_seconds,
        channel_count: int,
        programme_count: int,
        size_bytes: int,                # compressed transport size
        epg: { <epg_channel_id>: [ {title, description, start, stop,
                                    startTimestamp, stopTimestamp},
                                  … ], … }
      }
    """
    p = _parse_provider(provider)
    cache_key = hashlib.sha256(
        f"{p['scheme']}://{p['host']}:{p['port']}|{p['username']}".encode()
    ).hexdigest()

    # Register this provider so the background scheduler will keep
    # the persisted MongoDB cache warm in the future (no-op when the
    # MongoDB write fails — we never block the user on that path).
    try:
        await epg_cache.register_provider(p)
    except Exception as exc:  # noqa: BLE001
        log.debug("xtream.full_epg: provider registration failed: %s", exc)

    if not refresh:
        hit = _XMLTV_CACHE.get(cache_key)
        if hit and hit[0] > _time_mod.time():
            return {**hit[1], "cached": True}

    payload = await _fetch_and_parse_xmltv(p)
    _XMLTV_CACHE[cache_key] = (_time_mod.time() + _XMLTV_TTL, payload)
    # Persist for cross-restart durability and so /cached-epg can
    # serve instantly.  We don't await this on the hot path beyond
    # the swallowed exception below — the user already has their
    # payload in hand.
    try:
        await epg_cache.save_payload(cache_key, payload)
    except Exception as exc:  # noqa: BLE001
        log.debug("xtream.full_epg: persist failed: %s", exc)
    return payload


async def _fetch_and_parse_xmltv(p: Dict[str, Any]) -> Dict[str, Any]:
    """Internal helper used by /full-epg AND the background
    scheduler — does the network fetch + streaming XML parse + JSON
    shape.  Raising httpx errors propagate so callers can distinguish
    transient failures from parse failures.  Always returns a payload
    dict on success.
    """
    url = _xmltv_url(p)
    started = _time_mod.time()
    client = _http()
    # Send Accept-Encoding so the provider gzips the XML on the
    # wire — typically a 10× reduction.  httpx will transparently
    # decompress before yielding bytes.
    r = await client.get(
        url,
        headers={"Accept-Encoding": "gzip, deflate"},
        timeout=60.0,
    )
    if r.status_code != 200:
        raise HTTPException(
            status_code=502,
            detail=f"xmltv.php returned HTTP {r.status_code}",
        )
    body = r.content

    epg: Dict[str, list] = {}
    programme_count = 0
    channel_count_seen: set[str] = set()

    # iterparse for memory-bounded streaming through giant XML.
    # We only care about <programme> elements (channels are an index
    # we don't need server-side — the frontend can derive names).
    try:
        for event, elem in _ET.iterparse(BytesIO(body), events=("end",)):
            tag = elem.tag.lower()
            if tag == "programme":
                ch_id = (elem.get("channel") or "").strip()
                if not ch_id:
                    elem.clear()
                    continue
                start_ts = _parse_xmltv_time(elem.get("start"))
                stop_ts = _parse_xmltv_time(elem.get("stop"))
                if not start_ts:
                    elem.clear()
                    continue
                title_el = elem.find("title")
                desc_el = elem.find("desc")
                title = (title_el.text or "").strip() if title_el is not None else ""
                desc = (desc_el.text or "").strip() if desc_el is not None else ""
                epg.setdefault(ch_id, []).append({
                    "title":          title[:200],
                    "description":    desc[:600],
                    "start":          elem.get("start") or "",
                    "stop":           elem.get("stop") or "",
                    "startTimestamp": start_ts,
                    "stopTimestamp":  stop_ts,
                })
                channel_count_seen.add(ch_id)
                programme_count += 1
                # Free memory as we go.
                elem.clear()
    except _ET.ParseError as exc:
        raise HTTPException(status_code=502, detail=f"xmltv parse failed: {exc}") from None

    # Sort each channel's programmes by start time.
    for ch_id in epg:
        epg[ch_id].sort(key=lambda x: x["startTimestamp"])

    return {
        "cached":          False,
        "fetched_at":      int(started),
        "channel_count":   len(channel_count_seen),
        "programme_count": programme_count,
        "size_bytes":      len(body),
        "epg":             epg,
    }


# Public entry-point used by the scheduler.  Keep this signature
# (`async (provider) -> payload`) stable — `epg_cache.start_scheduler`
# calls us through it.
async def scheduler_refresh(provider: Dict[str, Any]) -> Dict[str, Any]:
    """Background scheduler entry-point.  Used by `epg_cache` to
    refresh a provider's persisted EPG.  Logs errors and returns an
    empty dict on failure — the scheduler will skip-and-retry on the
    next tick rather than aborting the whole pass."""
    try:
        return await _fetch_and_parse_xmltv(provider)
    except Exception as exc:  # noqa: BLE001
        log.warning("xtream.scheduler_refresh failed: %s", exc)
        return {}


@router.get("/cached-epg")
async def cached_epg(provider: str = Query(...)) -> Response:
    """Return the MOST-RECENT persisted EPG for this provider with
    near-zero latency — no provider round-trip.  The background
    scheduler keeps the persisted store warm.

    Gzipped response body.  10 MB JSON → ~600 KB on the wire — huge
    win for the HK1 boxes' shaky Wi-Fi.  We compress in-process
    instead of relying on FastAPI's GZipMiddleware so the response
    is gzipped EVERY time (avoiding the middleware's content-type +
    min-size heuristics, both of which silently disable compression
    in many real-world request shapes).

    On a cache miss, falls back to a synchronous full-EPG fetch so
    the very first request from a never-before-seen provider still
    works — albeit slowly.  Subsequent requests are then instant.
    """
    p = _parse_provider(provider)
    cache_key = epg_cache.provider_key(p)

    # Re-register so this provider stays in the active set.
    try:
        await epg_cache.register_provider(p)
    except Exception as exc:  # noqa: BLE001
        log.debug("xtream.cached_epg: provider registration failed: %s", exc)

    payload = await epg_cache.load_payload(cache_key)

    if not payload:
        # Cache miss.  Acquire the per-provider lock so that if a
        # parallel prewarm task is already fetching, we just wait for
        # it instead of issuing a duplicate xmltv.php download.  Once
        # we have the lock, re-check the persisted store (the
        # prewarm may have populated it while we were waiting); if
        # still empty, we do the live fetch ourselves.
        lock = epg_cache.get_inflight_lock(cache_key)
        async with lock:
            payload = await epg_cache.load_payload(cache_key)
            if not payload:
                try:
                    payload = await _fetch_and_parse_xmltv(p)
                    await epg_cache.save_payload(cache_key, payload)
                except HTTPException:
                    raise
                except Exception as exc:  # noqa: BLE001
                    raise HTTPException(
                        status_code=502,
                        detail=f"cached-epg miss & live fetch failed: {exc}",
                    ) from None

    persisted_at = payload.pop("_persisted_at", payload.get("fetched_at", 0))
    cache_age = max(0, int(time.time() - persisted_at)) if persisted_at else None

    body = {
        **payload,
        "cached":         True,
        "persisted_at":   persisted_at,
        "cache_age_sec":  cache_age,
    }
    raw = json.dumps(body, separators=(",", ":")).encode("utf-8")
    compressed = gzip.compress(raw, compresslevel=6)
    return Response(
        content=compressed,
        media_type="application/json",
        headers={
            "Content-Encoding": "gzip",
            # Hint to the WebView's HTTP cache: serve from disk for
            # 5 min before re-fetching even when our scheduler hasn't
            # written a fresh payload yet.  Lets the box re-open the
            # Live TV page instantly when bouncing between tabs.
            "Cache-Control": "private, max-age=300",
            "X-Cache-Age-Sec": str(cache_age if cache_age is not None else -1),
            "X-Channel-Count": str(payload.get("channel_count", 0)),
            "X-Programme-Count": str(payload.get("programme_count", 0)),
        },
    )
