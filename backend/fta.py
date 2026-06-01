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
# v2.8.91 — Per-city support.  The Stremio addon ships a sibling
# endpoint for each AU capital city under the same host; channels +
# EPG share the same `mjh-*` ids but the streams differ by region.
SUPPORTED_CITIES: Dict[str, str] = {
    "Brisbane":  "https://kangaroostreams.hayd.uk/Brisbane",
    "Sydney":    "https://kangaroostreams.hayd.uk/Sydney",
    "Melbourne": "https://kangaroostreams.hayd.uk/Melbourne",
    "Adelaide":  "https://kangaroostreams.hayd.uk/Adelaide",
    "Perth":     "https://kangaroostreams.hayd.uk/Perth",
    "Hobart":    "https://kangaroostreams.hayd.uk/Hobart",
    "Darwin":    "https://kangaroostreams.hayd.uk/Darwin",
    "Canberra":  "https://kangaroostreams.hayd.uk/Canberra",
}
DEFAULT_CITY = "Brisbane"

def _catalog_url(city: str) -> str:
    base = SUPPORTED_CITIES.get(city, SUPPORTED_CITIES[DEFAULT_CITY])
    return f"{base}/catalog/tv/iptv-channels-{city}.json"

def _epg_url(city: str) -> str:
    return f"https://i.mjh.nz/au/{city}/epg.xml.gz"

# Channel ordering reference (Brisbane).  Translated per-city below.
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

# v2.8.91 — Unified channel-logo map (`tv-logo/tv-logos` GitHub repo,
# the de-facto standard set used by Tivimate / Kodi PVR / Linux IPTV
# launchers).  Every PNG is transparent-bg, consistently sized, crisp
# — fixes the mismatched-logo grab-bag from the Stremio addon.
_TV_LOGO_BASE = (
    "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/australia"
)
CHANNEL_LOGOS: Dict[str, str] = {
    "mjh-seven-bri":      f"{_TV_LOGO_BASE}/seven-au.png",
    "mjh-7two-bri":       f"{_TV_LOGO_BASE}/7two-au.png",
    "mjh-7mate-bri":      f"{_TV_LOGO_BASE}/7mate-au.png",
    "mjh-7flix-bri":      f"{_TV_LOGO_BASE}/7flix-au.png",
    "mjh-7bravo-fast":    f"{_TV_LOGO_BASE}/7bravo-au.png",
    "mjh-channel-9-qld":  f"{_TV_LOGO_BASE}/nine-au.png",
    "mjh-gem-qld":        f"{_TV_LOGO_BASE}/nine-gem-au.png",
    "mjh-go-qld":         f"{_TV_LOGO_BASE}/nine-go-au.png",
    "mjh-life-qld":       f"{_TV_LOGO_BASE}/nine-life-au.png",
    "mjh-rush-qld":       f"{_TV_LOGO_BASE}/nine-rush-au.png",
    "mjh-10-qld":         f"{_TV_LOGO_BASE}/10-au.png",
    "mjh-10peach-qld":    f"{_TV_LOGO_BASE}/10-peach-au.png",
    "mjh-10bold-qld":     f"{_TV_LOGO_BASE}/10-bold-au.png",
    "mjh-abc-qld":        f"{_TV_LOGO_BASE}/abc-au.png",
    "mjh-abc-tv-plus":    f"{_TV_LOGO_BASE}/abc-family-au.png",
    "mjh-abc-me":         f"{_TV_LOGO_BASE}/abc-entertains-au.png",
    "mjh-abc-kids":       f"{_TV_LOGO_BASE}/abc-kids-au.png",
    "mjh-abc-news":       f"{_TV_LOGO_BASE}/abc-news-au.png",
    "mjh-sbs-sbst":       f"{_TV_LOGO_BASE}/sbs-au.png",
    "mjh-sbs-5nsw":       f"{_TV_LOGO_BASE}/nitv-au.png",
    "mjh-sbs-sbs-radio-4": f"{_TV_LOGO_BASE}/sbs-au.png",
}


def _city_to_state_suffix(city: str) -> str:
    return {
        "Brisbane": "qld", "Sydney": "nsw", "Melbourne": "vic",
        "Adelaide": "sa", "Perth": "wa", "Hobart": "tas",
        "Darwin": "nt", "Canberra": "act",
    }.get(city, "qld")


def _city_to_seven_code(city: str) -> str:
    return {
        "Brisbane": "bri", "Sydney": "syd", "Melbourne": "mel",
        "Adelaide": "ade", "Perth": "per", "Hobart": "hob",
        "Darwin": "drw", "Canberra": "cbr",
    }.get(city, "bri")


def _ids_for_city(city: str) -> List[str]:
    if city == "Brisbane":
        return list(DEFAULT_FTA_IDS)
    seven = _city_to_seven_code(city)
    suf = _city_to_state_suffix(city)
    return [
        f"mjh-seven-{seven}", f"mjh-7two-{seven}", f"mjh-7mate-{seven}",
        f"mjh-7flix-{seven}", "mjh-7bravo-fast",
        f"mjh-channel-9-{suf}", f"mjh-go-{suf}", f"mjh-gem-{suf}",
        f"mjh-life-{suf}", f"mjh-rush-{suf}",
        f"mjh-10-{suf}", f"mjh-10peach-{suf}", f"mjh-10bold-{suf}",
        f"mjh-abc-{suf}",
        "mjh-abc-tv-plus", "mjh-abc-me", "mjh-abc-kids", "mjh-abc-news",
        "mjh-sbs-sbst", "mjh-sbs-5nsw", "mjh-sbs-sbs-radio-4",
    ]


def _logo_for_id(channel_id: str) -> Optional[str]:
    """Return the unified `tv-logos` URL, suffix-tolerant."""
    if channel_id in CHANNEL_LOGOS:
        return CHANNEL_LOGOS[channel_id]
    parts = channel_id.rsplit("-", 1)
    if len(parts) == 2:
        for stand_in in ("qld", "bri", "fast"):
            probe = f"{parts[0]}-{stand_in}"
            if probe in CHANNEL_LOGOS:
                return CHANNEL_LOGOS[probe]
    return None

# ---------------------------------------------------------------- caches
# v2.8.91 — Keyed by city to support per-city EPG + channel sets.
_channel_cache: Dict[str, Dict[str, Any]] = {}
_epg_cache: Dict[str, Dict[str, Any]] = {}
_epg_meta_cache: Dict[str, Dict[str, Any]] = {}
_stream_cache: Dict[str, Dict[str, Any]] = {}      # key = "{city}:{channel_id}"
_CHANNEL_TTL_S = 3600
_EPG_TTL_S = 1800
_STREAM_TTL_S = 300


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
async def _load_channels(city: str = DEFAULT_CITY) -> List[Dict[str, Any]]:
    """Stremio addon catalog → trimmed to our default FTA set for `city`.

    Returns `[{id, name, logo, lcn}, ...]`.
    """
    now = time.time()
    bucket = _channel_cache.setdefault(city, {"ts": 0.0, "data": None})
    if bucket["data"] and now - bucket["ts"] < _CHANNEL_TTL_S:
        return bucket["data"]

    r = await _http_get(_catalog_url(city))
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
            # v2.8.91 — Always prefer the unified `tv-logos` PNG.
            "logo": _logo_for_id(mjh) or m.get("poster") or m.get("logo") or "",
            "lcn": None,
        }

    epg = await _load_epg(city=city, force_channel_only=True)
    for ch in epg.get("channels", []):
        cid = ch.get("id")
        if cid in by_mjh:
            by_mjh[cid]["name"] = ch.get("name") or by_mjh[cid]["name"]
            if ch.get("lcn"):
                by_mjh[cid]["lcn"] = ch["lcn"]
            # NOTE: we do NOT overwrite the logo here — `_logo_for_id`
            # has already set a clean unified logo when one exists.
            # Falling back to the EPG's auto-extracted PNG only happens
            # when there's no unified logo available.
            if not by_mjh[cid]["logo"] and ch.get("icon"):
                by_mjh[cid]["logo"] = ch["icon"]

    out: List[Dict[str, Any]] = []
    for mjh in _ids_for_city(city):
        if mjh in by_mjh:
            out.append(by_mjh[mjh])

    bucket["data"] = out
    bucket["ts"] = now
    return out


# ---------------------------------------------------------------- epg
async def _load_epg(*, city: str = DEFAULT_CITY, force_channel_only: bool = False) -> Dict[str, Any]:
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
    # v2.8.91 — Per-city, per-mode cache buckets.
    target_dict = _epg_meta_cache if force_channel_only else _epg_cache
    target_cache = target_dict.setdefault(city, {"ts": 0.0, "data": None})
    cached = target_cache.get("data")
    if cached and now - target_cache["ts"] < _EPG_TTL_S:
        return cached

    r = await _http_get(_epg_url(city), timeout=25.0)
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
async def _resolve_stream(channel_id: str, city: str = DEFAULT_CITY) -> Optional[str]:
    """Call the city-specific Stremio addon's stream endpoint and
    return the first playable URL.  Cached per (city, channel) for 5 min.
    """
    now = time.time()
    cache_key = f"{city}:{channel_id}"
    hit = _stream_cache.get(cache_key)
    if hit and now - hit["ts"] < _STREAM_TTL_S:
        return hit["url"]

    base = SUPPORTED_CITIES.get(city, SUPPORTED_CITIES[DEFAULT_CITY])
    stremio_id = f"au|{city}|{channel_id}|tv"
    encoded = stremio_id.replace("|", "%7C")
    url = f"{base}/stream/tv/{encoded}.json"
    try:
        r = await _http_get(url, timeout=8.0)
        streams = (r.json() or {}).get("streams") or []
        if not streams:
            return None
        first = streams[0].get("url")
        if first:
            _stream_cache[cache_key] = {"ts": now, "url": first}
        return first
    except Exception as exc:  # noqa: BLE001
        log.warning("stream resolve failed for %s/%s: %s", city, channel_id, exc)
        return None


def _normalize_city(name: Optional[str]) -> str:
    if not name:
        return DEFAULT_CITY
    cap = name.strip().capitalize()
    return cap if cap in SUPPORTED_CITIES else DEFAULT_CITY


# ---------------------------------------------------------------- routes
@router.get("/cities")
async def fta_cities() -> Dict[str, Any]:
    """List of supported AU capital cities for the city selector."""
    return {
        "cities": list(SUPPORTED_CITIES.keys()),
        "default": DEFAULT_CITY,
    }


@router.get("/channels")
async def fta_channels(
    city: str = Query(DEFAULT_CITY, description="AU capital city"),
) -> Dict[str, Any]:
    """Channel list for the EPG grid (left rail of logos)."""
    city = _normalize_city(city)
    try:
        channels = await _load_channels(city=city)
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"UPSTREAM_FAIL: {exc}") from exc
    return {"city": city, "channels": channels, "count": len(channels)}


@router.get("/streams/{channel_id}")
async def fta_stream(
    channel_id: str,
    city: str = Query(DEFAULT_CITY, description="AU capital city"),
) -> Dict[str, Any]:
    """Resolve one channel's HLS URL."""
    city = _normalize_city(city)
    url = await _resolve_stream(channel_id, city=city)
    if not url:
        raise HTTPException(404, "STREAM_NOT_FOUND")
    return {"channel_id": channel_id, "city": city, "url": url}


@router.get("/epg")
async def fta_epg(
    channels_only: bool = Query(False),
    city: str = Query(DEFAULT_CITY, description="AU capital city"),
) -> Dict[str, Any]:
    """Next ~24 h of programmes, keyed by channel id."""
    city = _normalize_city(city)
    try:
        data = await _load_epg(city=city, force_channel_only=channels_only)
    except httpx.HTTPError as exc:
        raise HTTPException(502, f"EPG_UPSTREAM_FAIL: {exc}") from exc
    return {**data, "city": city}


@router.get("/health")
async def fta_health() -> Dict[str, Any]:
    return {
        "ok": True,
        "supported_cities": list(SUPPORTED_CITIES.keys()),
        "channels_cached_cities": list(_channel_cache.keys()),
        "epg_cached_cities": list(_epg_cache.keys()),
    }
