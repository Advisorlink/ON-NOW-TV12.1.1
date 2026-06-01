"""
ON NOW V2 Free-to-Air backend router (v2.8.90).

Three endpoints, all under `/api/fta/...`:

  GET  /channels        → channel list (logo + LCN + name + id)
  GET  /streams/{id}    → playable HLS URL for one channel
  GET  /epg             → next 24 h of programmes per channel (slim JSON)

Data sources:
  • Channels + streams: AU IPTV Brisbane Stremio addon
        https://kangaroostreams.hayd.uk/Brisbane/...
  • EPG:                Matt Huntley's xmltv mirror
        https://i.mjh.nz/au/Brisbane/epg.xml.gz

Every response is heavily cached so the EPG grid renders instantly on
the HK1 box.  Channel logos come straight from the Stremio addon's
GitHub-hosted PNGs (and are cached by the WebView).
"""

from __future__ import annotations

import asyncio
import gzip
import io
import logging
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timezone, timedelta
from typing import Any, Dict, List, Optional

import httpx
from fastapi import APIRouter, HTTPException, Query

log = logging.getLogger("fta")

router = APIRouter(prefix="/api/fta", tags=["fta"])

# ---------------------------------------------------------------- config
STREMIO_BASE = "https://kangaroostreams.hayd.uk/Brisbane"
CATALOG_URL = f"{STREMIO_BASE}/catalog/tv/iptv-channels-Brisbane.json"
EPG_URL = "https://i.mjh.nz/au/Brisbane/epg.xml.gz"

# Channels we explicitly want on the Free-to-Air grid (Brisbane FTA
# only — strips the regional Seven mirrors and non-AU bonus channels
# the Stremio addon ships).  Order = LCN order so the grid renders
# 7 / 9 / 10 / ABC / SBS top-to-bottom like a real EPG.
DEFAULT_FTA_IDS = [
    # Seven network
    "mjh-seven-bri", "mjh-7two-bri", "mjh-7mate-bri", "mjh-7flix-bri",
    "mjh-7bravo-fast",
    # Nine network (Brisbane / QLD)
    "mjh-channel-9-qld", "mjh-go-qld", "mjh-gem-qld", "mjh-life-qld",
    "mjh-rush-qld",
    # 10 network (Brisbane / QLD)
    "mjh-10-qld", "mjh-10peach-qld", "mjh-10bold-qld",
    # ABC
    "mjh-abc-qld", "mjh-abc-tv-plus", "mjh-abc-me", "mjh-abc-kids",
    "mjh-abc-news",
    # SBS
    "mjh-sbs-sbst", "mjh-sbs-5nsw", "mjh-sbs-sbs-radio-4",
]

# ---------------------------------------------------------------- caches
_channel_cache: Dict[str, Any] = {"ts": 0.0, "data": None}
_epg_cache: Dict[str, Any] = {"ts": 0.0, "data": None}
# Separate cache for the channel-metadata-only slice of the EPG so we
# don't pollute the full EPG cache when `_load_channels()` runs first.
_epg_meta_cache: Dict[str, Any] = {"ts": 0.0, "data": None}
_stream_cache: Dict[str, Dict[str, Any]] = {}
_CHANNEL_TTL_S = 3600          # 1 h
_EPG_TTL_S = 1800              # 30 min
_STREAM_TTL_S = 300            # 5 min


# ---------------------------------------------------------------- helpers
def _parse_xmltv_time(s: str) -> Optional[int]:
    """XMLTV uses `YYYYMMDDhhmmss +TTTT`.  Return Unix ms or None."""
    if not s:
        return None
    try:
        # Replace TZ space so strptime sees it as one token.
        s = s.strip()
        if len(s) >= 14:
            dt = datetime.strptime(s[:14], "%Y%m%d%H%M%S")
            # tz offset (e.g. "+1000")
            tz_part = s[15:] if len(s) > 14 else "+0000"
            sign = 1 if tz_part[0] == "+" else -1
            try:
                hh = int(tz_part[1:3])
                mm = int(tz_part[3:5])
                offset_min = sign * (hh * 60 + mm)
            except Exception:        # noqa: BLE001
                offset_min = 0
            dt = dt.replace(tzinfo=timezone.utc) - timedelta(minutes=offset_min)
            return int(dt.timestamp() * 1000)
    except Exception:                # noqa: BLE001
        return None
    return None


async def _http_get(url: str, *, timeout: float = 15.0) -> httpx.Response:
    async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as cli:
        r = await cli.get(url, headers={"user-agent": "OnNowV2-FTA/1.0"})
        r.raise_for_status()
        return r


# ---------------------------------------------------------------- channels
async def _load_channels() -> List[Dict[str, Any]]:
    """Stremio addon catalog → trimmed to our default FTA set.

    Returns one dict per channel: `{id, name, logo, lcn}`.  The
    addon's `id` looks like `au|Brisbane|mjh-seven-bri|tv` — we pull
    the third segment (the `mjh-*` id) and use it directly.  LCN /
    proper display names are merged in from the EPG channel list so
    Seven shows as "Seven" with LCN 71 instead of the addon's name.
    """
    now = time.time()
    if _channel_cache["data"] and now - _channel_cache["ts"] < _CHANNEL_TTL_S:
        return _channel_cache["data"]

    r = await _http_get(CATALOG_URL)
    catalog = (r.json() or {}).get("metas") or []
    by_mjh: Dict[str, Dict[str, Any]] = {}
    for m in catalog:
        mid = m.get("id") or ""
        parts = mid.split("|")
        if len(parts) < 4:
            continue
        mjh = parts[2]
        by_mjh[mjh] = {
            "id": mjh,
            "stremio_id": mid,
            "name": m.get("name") or mjh,
            "logo": m.get("poster") or m.get("logo") or "",
            "lcn": None,
        }

    # Merge in EPG channel metadata for display names + LCN
    epg = await _load_epg(force_channel_only=True)
    for ch in epg.get("channels", []):
        cid = ch.get("id")
        if cid in by_mjh:
            by_mjh[cid]["name"] = ch.get("name") or by_mjh[cid]["name"]
            if ch.get("lcn"):
                by_mjh[cid]["lcn"] = ch["lcn"]
            if ch.get("icon"):
                # Prefer the EPG's logo (the auto-extracted PNG is
                # usually crisper than the Stremio addon's GitHub
                # tile).
                by_mjh[cid]["logo"] = ch["icon"]

    # Filter to our default FTA set and preserve that order
    out: List[Dict[str, Any]] = []
    for mjh in DEFAULT_FTA_IDS:
        if mjh in by_mjh:
            out.append(by_mjh[mjh])

    _channel_cache["data"] = out
    _channel_cache["ts"] = now
    return out


# ---------------------------------------------------------------- epg
async def _load_epg(*, force_channel_only: bool = False) -> Dict[str, Any]:
    """Download + parse the Brisbane XMLTV feed.

    Returns:
      {
        "channels": [{id, name, icon, lcn}, ...],
        "programmes": {
            "mjh-seven-bri": [
                {"title": str, "desc": str, "start": ms, "stop": ms,
                 "rating": str, "category": str},
                ...
            ],
            ...
        },
        "fetched_at": unix_ms,
      }

    Programmes are filtered to the window [now - 30 min, now + 24 h]
    so the JSON payload sent to the WebView stays compact (~150 KB).
    """
    now = time.time()
    # v2.8.90 — pick the appropriate cache.  channels_only payloads
    # are MUCH smaller (no programmes) and MUST NOT be stored in the
    # main `_epg_cache` because subsequent full-EPG callers would
    # then see an empty `programmes` map.  Two separate buckets.
    target_cache = _epg_meta_cache if force_channel_only else _epg_cache
    cached = target_cache.get("data")
    if cached and now - target_cache["ts"] < _EPG_TTL_S:
        return cached

    r = await _http_get(EPG_URL, timeout=25.0)
    raw = r.content
    if r.headers.get("content-encoding", "").lower() == "gzip" or raw[:2] == b"\x1f\x8b":
        try:
            raw = gzip.decompress(raw)
        except Exception:  # noqa: BLE001
            pass

    # Stream-parse so a 9 MB file doesn't peak memory.
    channels: List[Dict[str, Any]] = []
    programmes: Dict[str, List[Dict[str, Any]]] = {}
    window_start_ms = int((now - 30 * 60) * 1000)
    window_end_ms = int((now + 24 * 3600) * 1000)

    try:
        ctx = ET.iterparse(io.BytesIO(raw), events=("end",))
        for _, el in ctx:
            tag = el.tag
            if tag == "channel":
                cid = el.attrib.get("id") or ""
                if not cid:
                    el.clear()
                    continue
                name_el = el.find("display-name")
                icon_el = el.find("icon")
                lcn_el = el.find("lcn")
                channels.append({
                    "id": cid,
                    "name": (name_el.text or cid) if name_el is not None else cid,
                    "icon": icon_el.attrib.get("src") if icon_el is not None else "",
                    "lcn": (lcn_el.text or None) if lcn_el is not None else None,
                })
                el.clear()
            elif tag == "programme":
                if force_channel_only:
                    el.clear()
                    continue
                start = _parse_xmltv_time(el.attrib.get("start", ""))
                stop = _parse_xmltv_time(el.attrib.get("stop", ""))
                if not start or not stop:
                    el.clear()
                    continue
                # Trim to our render window
                if stop < window_start_ms or start > window_end_ms:
                    el.clear()
                    continue
                cid = el.attrib.get("channel") or ""
                title_el = el.find("title")
                desc_el = el.find("desc")
                rating_el = el.find("rating")
                cat_el = el.find("category")
                prog = {
                    "title": (title_el.text or "") if title_el is not None else "",
                    "desc": (desc_el.text or "") if desc_el is not None else "",
                    "start": start,
                    "stop": stop,
                    "rating": (
                        (rating_el.find("value").text or "")
                        if rating_el is not None and rating_el.find("value") is not None
                        else ""
                    ),
                    "category": (
                        (cat_el.text or "") if cat_el is not None else ""
                    ),
                }
                programmes.setdefault(cid, []).append(prog)
                el.clear()
    except ET.ParseError as exc:
        log.exception("EPG XML parse failed")
        raise HTTPException(502, f"EPG_PARSE_FAILED: {exc}") from exc

    # Sort each channel's programme list ascending by start.
    for cid in programmes:
        programmes[cid].sort(key=lambda p: p["start"])

    data = {
        "channels": channels,
        "programmes": programmes,
        "fetched_at": int(now * 1000),
    }
    target_cache["data"] = data
    target_cache["ts"] = now
    return data


# ---------------------------------------------------------------- streams
async def _resolve_stream(channel_id: str) -> Optional[str]:
    """Call the Stremio addon's stream endpoint and return the first
    playable URL.  Cached per channel for 5 min.
    """
    now = time.time()
    hit = _stream_cache.get(channel_id)
    if hit and now - hit["ts"] < _STREAM_TTL_S:
        return hit["url"]

    stremio_id = f"au|Brisbane|{channel_id}|tv"
    # Stremio's URL spec percent-encodes the `|` characters.
    encoded = stremio_id.replace("|", "%7C")
    url = f"{STREMIO_BASE}/stream/tv/{encoded}.json"
    try:
        r = await _http_get(url, timeout=8.0)
        streams = (r.json() or {}).get("streams") or []
        if not streams:
            return None
        first = streams[0].get("url")
        if first:
            _stream_cache[channel_id] = {"ts": now, "url": first}
        return first
    except Exception as exc:  # noqa: BLE001
        log.warning("stream resolve failed for %s: %s", channel_id, exc)
        return None


# ---------------------------------------------------------------- routes
@router.get("/channels")
async def fta_channels() -> Dict[str, Any]:
    """Channel list for the EPG grid (left rail of logos)."""
    try:
        channels = await _load_channels()
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"UPSTREAM_FAIL: {exc}") from exc
    return {"channels": channels, "count": len(channels)}


@router.get("/streams/{channel_id}")
async def fta_stream(channel_id: str) -> Dict[str, Any]:
    """Resolve one channel's HLS URL."""
    url = await _resolve_stream(channel_id)
    if not url:
        raise HTTPException(404, "STREAM_NOT_FOUND")
    return {"channel_id": channel_id, "url": url}


@router.get("/epg")
async def fta_epg(
    channels_only: bool = Query(False, description="Return channel metadata only (no programmes)."),
) -> Dict[str, Any]:
    """Next ~24 h of programmes, keyed by channel id."""
    try:
        data = await _load_epg(force_channel_only=channels_only)
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"EPG_UPSTREAM_FAIL: {exc}") from exc
    return data


@router.get("/health")
async def fta_health() -> Dict[str, Any]:
    """Light health probe — fast even on a cold cache."""
    return {
        "ok": True,
        "channels_cached": bool(_channel_cache.get("data")),
        "epg_cached": bool(_epg_cache.get("data")),
        "epg_age_s": (
            int(time.time() - _epg_cache["ts"]) if _epg_cache.get("ts") else None
        ),
    }
