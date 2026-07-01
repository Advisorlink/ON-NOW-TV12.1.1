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
from fastapi import APIRouter, Body, FastAPI, HTTPException, Query, Request
from fastapi.responses import Response, HTMLResponse, JSONResponse, StreamingResponse
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
# Stream-aggregation gets a tighter timeout so a single slow addon
# (e.g. cold Torrentio scraping a fresh title) doesn't hold up the
# whole pipeline — the user sees streams within ~8 s at worst.
# v2.7.53 — Lowered 8s → 5s per user feedback ("autoplay button takes
# too long to show").  Most healthy addons respond in 1–3 s; the 8 s
# ceiling was just making us wait for the slowest dead addon.  5 s is
# still plenty for normal latency + still catches the fast addons.
STREAM_FETCH_TIMEOUT = 5.0

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
# v2.7.33 — STREAM LANGUAGE FILTER
# ---------------------------------------------------------------------------
# The user installed their own Torrentio addon with a personal Premiumize
# key but Torrentio's config UI doesn't let them whitelist English only —
# only EXCLUDE foreign languages.  We do the final English-only pass here
# so EVERY stream addon (Torrentio, MediaFusion, anything custom) is
# automatically filtered.  Also tags each stream with `_is_english: True`
# so the UI can render a 🇬🇧 chip.
#
# Heuristic:
#   • Has 🇷🇺/🇫🇷/🇪🇸/etc. flag emoji OR a foreign language word
#     (Russian / French / RUS / FRA / Hindi / 國語 / ...) → EXCLUDE.
#   • Otherwise → INCLUDE.  Mark as English if there's an explicit
#     English signal (ENG / English / 🇬🇧 / 🇺🇸) OR the title is
#     untagged for any language (typical western release).
# ---------------------------------------------------------------------------

# Foreign-language flag emojis we want to EXCLUDE.  English-speaking
# flags (🇬🇧 🇺🇸 🇦🇺 🇨🇦 🇳🇿 🇮🇪) are kept.
#
# Each flag emoji is a SURROGATE PAIR of two regional-indicator chars,
# so we keep them as standalone 2-char strings and check membership
# via plain `in` substring tests — avoids the ambiguity of putting the
# individual regional indicators in a character class (e.g. "🇬" alone
# matches both 🇬🇧 UK and 🇬🇷 Greece).
_FOREIGN_FLAGS_LIST = [
    "\U0001F1F7\U0001F1FA",  # 🇷🇺 Russia
    "\U0001F1EB\U0001F1F7",  # 🇫🇷 France
    "\U0001F1EA\U0001F1F8",  # 🇪🇸 Spain
    "\U0001F1EE\U0001F1F9",  # 🇮🇹 Italy
    "\U0001F1E9\U0001F1EA",  # 🇩🇪 Germany
    "\U0001F1F5\U0001F1F9",  # 🇵🇹 Portugal
    "\U0001F1E7\U0001F1F7",  # 🇧🇷 Brazil
    "\U0001F1F2\U0001F1FD",  # 🇲🇽 Mexico
    "\U0001F1F5\U0001F1F1",  # 🇵🇱 Poland
    "\U0001F1EE\U0001F1F3",  # 🇮🇳 India
    "\U0001F1EF\U0001F1F5",  # 🇯🇵 Japan
    "\U0001F1F0\U0001F1F7",  # 🇰🇷 Korea
    "\U0001F1E8\U0001F1F3",  # 🇨🇳 China
    "\U0001F1F9\U0001F1FC",  # 🇹🇼 Taiwan
    "\U0001F1F9\U0001F1F7",  # 🇹🇷 Turkey
    "\U0001F1F8\U0001F1E6",  # 🇸🇦 Saudi (Arabic)
    "\U0001F1EA\U0001F1EC",  # 🇪🇬 Egypt (Arabic)
    "\U0001F1F3\U0001F1F1",  # 🇳🇱 Netherlands
    "\U0001F1E9\U0001F1F0",  # 🇩🇰 Denmark
    "\U0001F1F8\U0001F1EA",  # 🇸🇪 Sweden
    "\U0001F1F3\U0001F1F4",  # 🇳🇴 Norway
    "\U0001F1EB\U0001F1EE",  # 🇫🇮 Finland
    "\U0001F1E8\U0001F1FF",  # 🇨🇿 Czech
    "\U0001F1ED\U0001F1FA",  # 🇭🇺 Hungary
    "\U0001F1EC\U0001F1F7",  # 🇬🇷 Greece
    "\U0001F1F9\U0001F1ED",  # 🇹🇭 Thailand
    "\U0001F1FB\U0001F1F3",  # 🇻🇳 Vietnam
    "\U0001F1EE\U0001F1E9",  # 🇮🇩 Indonesia
    "\U0001F1EE\U0001F1F1",  # 🇮🇱 Israel (Hebrew)
    "\U0001F1FA\U0001F1E6",  # 🇺🇦 Ukraine
    "\U0001F1F7\U0001F1F4",  # 🇷🇴 Romania
    "\U0001F1ED\U0001F1F0",  # 🇭🇰 Hong Kong
]

# English / English-speaking flag emojis.
_ENGLISH_FLAGS_LIST = [
    "\U0001F1EC\U0001F1E7",  # 🇬🇧 UK
    "\U0001F1FA\U0001F1F8",  # 🇺🇸 USA
    "\U0001F1E6\U0001F1FA",  # 🇦🇺 Australia
    "\U0001F1E8\U0001F1E6",  # 🇨🇦 Canada
    "\U0001F1F3\U0001F1FF",  # 🇳🇿 New Zealand
    "\U0001F1EE\U0001F1EA",  # 🇮🇪 Ireland
]

# Word-level foreign-language tokens.  Word boundaries matter — `\bGER\b`
# avoids matching "MERGER", `\bENG\b` is whitelist-safe.
_FOREIGN_LANG_RE = re.compile(
    r"\b("
    r"russian|francais|french|spanish|espanol|español|italian|italiano|"
    r"german|deutsch|portuguese|portugues|português|polish|polski|"
    r"hindi|tamil|telugu|malayalam|kannada|marathi|punjabi|bengali|"
    r"korean|japanese|nihongo|chinese|mandarin|cantonese|turkish|"
    r"arabic|farsi|persian|urdu|dutch|nederlands|danish|swedish|svenska|"
    r"norwegian|finnish|suomi|czech|cesky|hungarian|magyar|greek|"
    r"thai|vietnamese|indonesian|hebrew|ukrainian|romanian|romana|"
    r"bulgarian|serbian|croatian|slovak|slovenian|catalan|estonian|"
    r"latvian|lithuanian|filipino|tagalog|"
    r"rus|fra|fre|spa|esp|ita|ger|deu|jpn|jap|kor|chn|por|pol|hin|tam|"
    r"tel|ara|chi|nld|swe|nor|fin|cze|hun|gre|tha|vie|ind|heb|"
    r"ukr|rom|bul|srb|hrv|slk|slv|cat|est|lav|lit"
    r")\b",
    re.IGNORECASE,
)

_ENGLISH_TOKEN_RE = re.compile(r"\b(english|eng)\b", re.IGNORECASE)

# v2.7.37 — STREAM SIZE PARSER
# Extracts the file size in GB from a stream's metadata blob.
# Stremio addons (esp. Torrentio) embed sizes like:
#   "💾 12.4 GB", "👤 6 💾 23.17 GB", "Movie.2024.1080p [4.7GB]", "850 MB"
# Returns None when no size can be inferred — we never DROP streams
# for missing size, just skip the size-based gating.  Captures the
# LAST numeric+unit pair on the line because Torrentio puts the size
# AFTER seeders/peers (`👤 12 💾 4.7 GB`).
_SIZE_RE = re.compile(
    r"(?P<n>\d+(?:[.,]\d+)?)\s*(?P<u>GB|MB|TB)\b",
    re.IGNORECASE,
)


def _parse_size_gb(s: Dict[str, Any]) -> Optional[float]:
    txt = _stream_haystack(s)
    if not txt:
        return None
    matches = list(_SIZE_RE.finditer(txt))
    if not matches:
        return None
    # Take the LAST match — addons format `seeders 👤 N 💾 SIZE`.
    m = matches[-1]
    try:
        n = float(m.group("n").replace(",", "."))
    except ValueError:
        return None
    u = m.group("u").upper()
    if u == "TB":
        return n * 1024.0
    if u == "GB":
        return n
    if u == "MB":
        return n / 1024.0
    return None


# Non-Latin script ranges — Cyrillic, Arabic, CJK, Japanese, Korean,
# Devanagari (Hindi), Hebrew, Greek, Thai.  Detected by counting
# matching codepoints in the haystack; >= 4 chars means the title
# is meaningfully foreign-script (catches stream titles that have
# only the foreign-name + nothing English-tagged, like Cyrillic-only
# Russian Torrentio listings).
_NON_LATIN_RE = re.compile(
    r"["
    r"\u0400-\u04FF"   # Cyrillic
    r"\u0500-\u052F"   # Cyrillic Supplement
    r"\u0600-\u06FF"   # Arabic
    r"\u0900-\u097F"   # Devanagari (Hindi)
    r"\u0590-\u05FF"   # Hebrew
    r"\u0370-\u03FF"   # Greek
    r"\u0E00-\u0E7F"   # Thai
    r"\u3040-\u309F"   # Hiragana
    r"\u30A0-\u30FF"   # Katakana
    r"\u4E00-\u9FFF"   # CJK Unified Ideographs
    r"\uAC00-\uD7AF"   # Hangul Syllables
    r"]"
)


def _has_any_substring(text: str, needles: List[str]) -> bool:
    for n in needles:
        if n in text:
            return True
    return False


def _stream_haystack(s: Dict[str, Any]) -> str:
    """Build the searchable text blob for a stream — title + name +
    description + behavior hints — for language detection."""
    parts = [
        s.get("title", "") or "",
        s.get("name", "") or "",
        s.get("description", "") or "",
    ]
    bh = s.get("behaviorHints") or {}
    if isinstance(bh, dict):
        parts.append(str(bh.get("filename", "") or ""))
    return " ".join(parts)


def _is_foreign_language_stream(s: Dict[str, Any]) -> bool:
    """True when the stream's metadata contains a foreign-language
    flag emoji, word token, or substantial non-Latin script chars."""
    txt = _stream_haystack(s)
    if not txt:
        return False
    if _has_any_substring(txt, _FOREIGN_FLAGS_LIST):
        return True
    if _FOREIGN_LANG_RE.search(txt):
        return True
    # 2+ non-Latin codepoints — catches Cyrillic-only Russian titles,
    # CJK, Arabic, Devanagari, etc. that have no Latin lang token.
    # 2 is a tight threshold: catches even short foreign titles like
    # "电影" (Chinese: "movie") while still letting through stray
    # single special chars (em-dashes, smart quotes, etc.).
    if len(_NON_LATIN_RE.findall(txt)) >= 2:
        return True
    return False


def _is_english_stream(s: Dict[str, Any]) -> bool:
    """True when stream EITHER has explicit English signal OR is
    untagged for any language (which we treat as English by default —
    typical for western releases)."""
    txt = _stream_haystack(s)
    if not txt:
        return True  # no metadata → don't penalise
    if _has_any_substring(txt, _ENGLISH_FLAGS_LIST):
        return True
    if _ENGLISH_TOKEN_RE.search(txt):
        return True
    # No foreign-language signal anywhere → assume English
    has_foreign = (
        _has_any_substring(txt, _FOREIGN_FLAGS_LIST)
        or _FOREIGN_LANG_RE.search(txt)
        or len(_NON_LATIN_RE.findall(txt)) >= 2
    )
    if not has_foreign:
        return True
    return False


def _filter_and_tag_english(streams: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Drop foreign-language streams and tag the rest with
    `_is_english: True` (broad — used to render a flag chip) AND
    `_english_strict: True` (narrow — used by autoplay to pick the
    safest English-only stream).  Multi-lang releases that explicitly
    list English in the title (e.g., "Eng.Fre.Ger.Ita") are KEPT but
    marked `_english_strict: False` since libVLC may pick the
    wrong default audio track on them.  Cyrillic / CJK / Arabic-
    titled streams are dropped UNLESS they contain an explicit
    English word token. Idempotent."""
    out: List[Dict[str, Any]] = []
    for s in streams:
        if not isinstance(s, dict):
            continue
        txt = _stream_haystack(s)
        non_latin_count = len(_NON_LATIN_RE.findall(txt))
        has_english_token = bool(_ENGLISH_TOKEN_RE.search(txt))
        has_english_flag = _has_any_substring(txt, _ENGLISH_FLAGS_LIST)
        has_foreign_flag = _has_any_substring(txt, _FOREIGN_FLAGS_LIST)
        has_foreign_token = bool(_FOREIGN_LANG_RE.search(txt))
        any_foreign = has_foreign_flag or has_foreign_token or non_latin_count >= 2

        # Decision matrix:
        # 1. Title contains substantial non-Latin script → REQUIRE
        #    an explicit English TOKEN (ENG / English).  Flag alone
        #    isn't enough because it's usually a subtitle indicator.
        # 2. Title has foreign-language word/flag but ALSO English
        #    token or English flag → multi-lang release with English
        #    audio → KEEP (but NOT strict-English).
        # 3. Title has foreign signal but NO English signal → DROP.
        # 4. Title has no foreign signal → KEEP and tag as strict-English.
        if non_latin_count >= 2:
            if not has_english_token:
                continue
            s["_is_english"] = True
            s["_english_strict"] = False  # multi-lang, risky for autoplay
            s["_size_gb"] = _parse_size_gb(s)
            _tag_addon_quality_premium(s)
            out.append(s)
            continue
        english = has_english_token or has_english_flag or not any_foreign
        if any_foreign and not english:
            continue
        s["_is_english"] = english
        # v2.7.36 — strict English: zero foreign signals anywhere.
        s["_english_strict"] = (not any_foreign)
        # v2.7.37 — parse the file size in GB (None when unknown).
        # Frontend autoplay uses this to cap fallback streams at
        # 3 GB so flaky huge-rip CDNs don't kill playback within
        # the first 30 seconds.
        s["_size_gb"] = _parse_size_gb(s)
        # v2.7.39 — addon source / quality label / Premiumize cache flag.
        _tag_addon_quality_premium(s)
        out.append(s)
    return out


# ---------------------------------------------------------------------------
# v2.7.39 — Stream tagging: addon source, quality label, Premiumize cache
# ---------------------------------------------------------------------------

_ADDON_SOURCE_MAP = [
    ("plexio",     "PLEXIO"),
    ("ep-strem",   "PLEXIO"),
    ("torrentio",  "TORRENTIO"),
    ("watchhub",   "WATCHHUB"),
    ("opensub",    "OPENSUBS"),
    ("cinemeta",   "CINEMETA"),
    ("mediafusion","MEDIAFUSION"),
    ("aiostreams", "AIO"),
    ("jackett",    "JACKETT"),
    ("orion",      "ORION"),
    # v2.10.74 — Easynews family (Usenet aggregator).  Includes
    # the classic stremio-easynews-addon + EasyNews++.  Surfaced
    # as the "EASYNEWS" source chip in the StreamPickerModal so
    # the operator can see at a glance which back-end resolved
    # the stream.
    ("easynews",   "EASYNEWS"),
    ("easy-news",  "EASYNEWS"),
    ("easy_news",  "EASYNEWS"),
]


def _detect_addon_source(s: Dict[str, Any]) -> str:
    blob = (
        (s.get("_addon_id") or "")
        + " " + (s.get("_addon_name") or "")
        + " " + (s.get("name") or "")
    ).lower()
    for needle, label in _ADDON_SOURCE_MAP:
        if needle in blob:
            return label
    raw = s.get("_addon_name") or "STREAM"
    first = str(raw).split()[0][:12].upper() if raw else "STREAM"
    return first


def _detect_quality(s: Dict[str, Any]) -> str:
    txt = _stream_haystack(s).lower()
    if "2160p" in txt or " 4k" in txt or "uhd" in txt:
        return "4K"
    if "1080p" in txt:
        return "1080p"
    if "720p" in txt:
        return "720p"
    if "480p" in txt or " sd " in txt or "cam" in txt or "scr" in txt:
        return "SD"
    return ""


def _detect_pm_cached(s: Dict[str, Any]) -> bool:
    """Heuristic for Premiumize/Real-Debrid cached streams.

    Torrent addons return either:
      • direct https:// URL → debrid-cached → plays instantly.
      • magnet: URI or infoHash field → raw torrent → buffer hell.
    We only mark `_pm_cached:true` for the torrent-addon family.
    """
    src = _detect_addon_source(s)
    if src not in ("TORRENTIO", "MEDIAFUSION", "AIO", "JACKETT", "ORION"):
        return False
    if s.get("infoHash"):
        return False
    url = (s.get("url") or "").lower()
    return url.startswith(("http://", "https://"))


def _tag_addon_quality_premium(s: Dict[str, Any]) -> None:
    s["_addon_source"]  = _detect_addon_source(s)
    s["_quality_label"] = _detect_quality(s)
    s["_pm_cached"]     = _detect_pm_cached(s)


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
        # v2.7.33 — apply English filter even to cached payloads so
        # the rollout doesn't have to wait for cache expiry.
        return {"cached": True, "streams": _filter_and_tag_english(cached)}

    addons = await db.addons.find(
        {"user_id": DEFAULT_USER, "active": True}, {"_id": 0}
    ).to_list(100)

    out: List[Dict[str, Any]] = []
    async with httpx.AsyncClient(timeout=STREAM_FETCH_TIMEOUT) as client:
        async def fetch(a: Dict[str, Any]):
            m = a["manifest"]
            if not _resource_supported(m, "stream"):
                return []
            if not _id_prefix_match(m, item_id):
                return []
            url = f"{a['url']}/stream/{type_}/{item_id}.json"
            try:
                # Per-addon hard cap — even if the AsyncClient's
                # default were higher, no single addon can stall the
                # aggregate response.
                data = await asyncio.wait_for(
                    _fetch_json(client, url), timeout=STREAM_FETCH_TIMEOUT
                )
            except (HTTPException, asyncio.TimeoutError):
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

    # v2.7.33 — drop foreign-language streams + tag English ones.
    out = _filter_and_tag_english(out)

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
@api.get("/tmdb/find-by-imdb/{imdb_id}")
async def tmdb_find_by_imdb(imdb_id: str):
    """Resolve `imdb_id` (tt-prefixed) → TMDB id + media_type.

    Used by the Detail page to drive the cast row + recommendations
    row without forcing the front-end to know the TMDB id.

    v2.8.5 — Now also fetches the US certification (rating) so the
    Kids profile in Vesper can hard-block adult titles even if a
    kid pastes a /title/ URL directly.  Rating is null on failure
    so callers can decide their own fallback.

    Cached for 7 days (the IMDB↔TMDB mapping is rock-stable).
    """
    if not imdb_id.startswith("tt"):
        raise HTTPException(400, "imdb_id must start with 'tt'")
    cache_key = f"find_by_imdb:{imdb_id}:v3"  # v3 = includes overview + art
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, **cached}

    data = await _tmdb_get(
        f"/find/{imdb_id}", {"external_source": "imdb_id"}
    )
    out: Dict[str, Any] = {
        "tmdb_id": None,
        "media_type": None,
        "rating": None,
        # v2.10.77 — Synopsis + art fallbacks for the player loading
        # screen.  Some addons (EasyNews++ in particular) hand back
        # streams whose IMDB id doesn't resolve in Cinemeta, so
        # `Vesper.getMeta()` returns null and Detail.jsx ends up
        # passing an EMPTY synopsis to the native player loading
        # card.  The /find/ endpoint already returns the full movie /
        # tv result objects in the same response — extracting them
        # here costs us zero extra TMDB calls and gives every player
        # launch a baseline synopsis + poster + backdrop fallback.
        "overview": None,
        "poster_url": None,
        "backdrop_url": None,
        "title": None,
        "year": None,
    }
    hit = None
    if data.get("movie_results"):
        hit = data["movie_results"][0]
        out["tmdb_id"] = hit.get("id")
        out["media_type"] = "movie"
    elif data.get("tv_results"):
        hit = data["tv_results"][0]
        out["tmdb_id"] = hit.get("id")
        out["media_type"] = "tv"
    if hit:
        out["overview"] = (hit.get("overview") or "").strip() or None
        out["title"] = hit.get("title") or hit.get("name") or None
        poster_path = hit.get("poster_path")
        if poster_path:
            out["poster_url"] = f"https://image.tmdb.org/t/p/w780{poster_path}"
        backdrop_path = hit.get("backdrop_path")
        if backdrop_path:
            out["backdrop_url"] = f"https://image.tmdb.org/t/p/w1280{backdrop_path}"
        date = hit.get("release_date") or hit.get("first_air_date") or ""
        if date and len(date) >= 4:
            out["year"] = date[:4]

    # ── Pull US certification — only one extra TMDB call. ──
    if out["tmdb_id"]:
        try:
            if out["media_type"] == "movie":
                rel = await _tmdb_get(
                    f"/movie/{out['tmdb_id']}/release_dates", {},
                )
                for entry in rel.get("results", []) or []:
                    if entry.get("iso_3166_1") == "US":
                        for rd in entry.get("release_dates", []) or []:
                            if rd.get("certification"):
                                out["rating"] = rd["certification"]
                                break
                    if out["rating"]:
                        break
            elif out["media_type"] == "tv":
                rel = await _tmdb_get(
                    f"/tv/{out['tmdb_id']}/content_ratings", {},
                )
                for entry in rel.get("results", []) or []:
                    if entry.get("iso_3166_1") == "US" and entry.get("rating"):
                        out["rating"] = entry["rating"]
                        break
        except Exception:  # noqa: BLE001
            # Cert lookup is best-effort — never block the underlying
            # IMDB→TMDB resolution if TMDB hiccups on the second call.
            pass

    if out["tmdb_id"]:
        await cache.set(cache_key, out, CACHE_TTL_TMDB_IMDB)
    return {"cached": False, **out}


# ── v2.10.35 — Title-logo lookup ───────────────────────────────
# The native ExoPlayer overlay renders the show / movie's official
# logo above the title text in the bottom control dock so the user
# can see exactly what's playing at a glance.  Source-of-truth is
# TMDB's `/images` endpoint, which returns multiple language-tagged
# logo PNGs per title.  We pick the best English one (prefer
# `iso_639_1=en`, then `iso_639_1=null` for transparent logos that
# work in any locale, then the first available).
@api.get("/tmdb/logo/{type_}/{imdb_id}")
async def tmdb_logo(type_: str, imdb_id: str):
    """Return the best English title-logo URL for a movie or TV show.

    Cached for 30 days — TMDB logo paths are extremely stable.
    Returns `{"logo_url": null}` when no logo is available rather
    than 404 so the client can branch cheaply on null without
    error-handling.
    """
    if not imdb_id.startswith("tt"):
        raise HTTPException(400, "imdb_id must start with 'tt'")
    if type_ not in ("movie", "series", "tv"):
        raise HTTPException(400, "type must be 'movie', 'series' or 'tv'")
    # Normalise the type → TMDB's URL slug.
    tmdb_type = "movie" if type_ == "movie" else "tv"
    cache_key = f"tmdb_logo:{tmdb_type}:{imdb_id}:v1"
    cached = await cache.get(cache_key)
    if cached is not None:
        return {"cached": True, "logo_url": cached or None}

    # Step 1: IMDB → TMDB.  Re-uses the same /find endpoint as the
    # rest of the backend so the cache layer hits for repeat lookups.
    find = await _tmdb_get(
        f"/find/{imdb_id}", {"external_source": "imdb_id"}
    )
    hits = (
        find.get("movie_results") if tmdb_type == "movie"
        else find.get("tv_results")
    ) or []
    if not hits:
        # 30-day cache the negative result so we don't hammer TMDB
        # on every player launch for a title that genuinely has no
        # match (rare).
        await cache.set(cache_key, "", 30 * 24 * 3600)
        return {"cached": False, "logo_url": None}
    tmdb_id = hits[0]["id"]

    # Step 2: /images?include_image_language=en,null surfaces both
    # English logos AND the language-agnostic transparent ones
    # (which TMDB tags with `iso_639_1=null` and which look great
    # for shows with stylised wordmarks like "Breaking Bad").
    try:
        images = await _tmdb_get(
            f"/{tmdb_type}/{tmdb_id}/images",
            {"include_image_language": "en,null"},
        )
    except HTTPException:
        await cache.set(cache_key, "", 30 * 24 * 3600)
        return {"cached": False, "logo_url": None}

    logos = images.get("logos") or []
    if not logos:
        await cache.set(cache_key, "", 30 * 24 * 3600)
        return {"cached": False, "logo_url": None}

    # Sort: English first, then null-language, then by vote_count
    # (TMDB's quality signal) descending.  PNGs are preferred over
    # SVGs for native rendering since Coil doesn't ship an SVG
    # decoder by default.
    def _score(logo: Dict[str, Any]) -> tuple:
        lang = logo.get("iso_639_1") or ""
        lang_rank = 0 if lang == "en" else (1 if lang == "" else 2)
        is_svg = (logo.get("file_path") or "").lower().endswith(".svg")
        return (lang_rank, 1 if is_svg else 0, -(logo.get("vote_count") or 0))

    logos.sort(key=_score)
    picked = logos[0]
    path = picked.get("file_path")
    if not path:
        await cache.set(cache_key, "", 30 * 24 * 3600)
        return {"cached": False, "logo_url": None}

    # w500 — TMDB's "wide enough for any TV overlay, light enough
    # for the network".  Original PNGs can be 2-4 MB which is
    # absurd for an overlay glyph.
    logo_url = f"{TMDB_IMG}/w500{path}"
    await cache.set(cache_key, logo_url, 30 * 24 * 3600)
    return {"cached": False, "logo_url": logo_url}


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


# ----- App version check (FTA Native) ---------------------------------------
#
# Same shape as `/api/app/latest-version` (Vesper / WebView build) but reads
# the `fta-native-latest` release tag so the in-app update gate on the new
# native FTA app can self-update.  Cached separately for 5 min.

@api.get("/app/latest-version-fta-native")
async def app_latest_version_fta_native():
    cache_key = "github:fta-native-latest:v1"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, **cached}

    owner_repo = os.environ.get("APK_GITHUB_REPO", "andrewbailey-uk/onnowtv-v2")
    url = f"https://api.github.com/repos/{owner_repo}/releases/tags/fta-native-latest"
    try:
        async with httpx.AsyncClient(timeout=HTTP_TIMEOUT) as client:
            r = await client.get(
                url,
                headers={
                    "Accept": "application/vnd.github+json",
                    "User-Agent": "fta-native-update-check",
                },
            )
            if r.status_code == 404:
                empty = {
                    "version": None, "tag_name": None, "name": None,
                    "published_at": None, "notes": "",
                    "apk_url": None, "html_url": None,
                }
                await cache.set(cache_key, empty, 60)
                return {"cached": False, **empty}
            r.raise_for_status()
            data = r.json()
    except httpx.HTTPStatusError as exc:
        raise HTTPException(502, f"GitHub release lookup failed: {exc.response.status_code} {exc.response.text[:200]}") from None
    except httpx.RequestError as exc:
        raise HTTPException(504, f"GitHub network error: {exc}") from None

    # Pick the *-debug.apk asset (matches build-fta-native.yml output).
    apk_url = None
    for asset in (data.get("assets") or []):
        name = (asset.get("name") or "").lower()
        if name.endswith("-debug.apk") or name == "fta-native-debug.apk":
            apk_url = asset.get("browser_download_url")
            break
    if not apk_url:
        # Last-resort fallback: any .apk asset.
        for asset in (data.get("assets") or []):
            if (asset.get("name") or "").lower().endswith(".apk"):
                apk_url = asset.get("browser_download_url")
                break

    # The workflow names releases like "FTA Native 0.2.0 (build 5)" —
    # parse the semver out of that string.
    name_str = data.get("name") or ""
    body = data.get("body") or ""
    import re as _re
    semver = None
    m = _re.search(r"\b(\d+\.\d+\.\d+)\b", name_str) or _re.search(r"\b(\d+\.\d+\.\d+)\b", body)
    if m:
        semver = m.group(1)

    out = {
        "version":      semver,
        "tag_name":     data.get("tag_name"),
        "name":         name_str,
        "published_at": data.get("published_at"),
        "notes":        body,
        "apk_url":      apk_url,
        "html_url":     data.get("html_url"),
    }
    await cache.set(cache_key, out, 300)
    return {"cached": False, **out}


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

KIDS_TV_NETWORKS = "13|44|56|2697|3919|4674"  # Nick, Disney Channel, Cartoon Network, Disney Jr, Disney+, Nick Jr (OR-joined per TMDB syntax)
# v2.8.42 — Massively expanded the preschool whitelist so the
# Babies tier no longer returns only 11 titles.  Adds the major
# international preschool networks the original v2.8.13 list
# omitted: Cartoonito (Cartoon Network's preschool block), BBC
# Kids / CBeebies, ABC Kids (AU), Treehouse (CA), Discovery
# Kids, Universal Kids, Sprout, Nick Jr Too, JimJam, Boomerang
# Kids, Baby TV, Boomerang Pre-school.  Each TMDB network ID was
# resolved against /search/network.  The TV-Y / TV-Y7 content-
# rating post-filter is still applied AFTER this list, so adult
# animations slip-through is impossible.
#
#   2697  Disney Junior
#   3919  Nick Jr.
#     14  PBS
#    277  PBS Kids
#   3938  ABC Kids (AU)
#   4654  Sprout
#   5074  Universal Kids
#   2575  CBeebies
#   4151  BBC Kids
#   5085  Cartoonito
#   2700  Boomerang
#   2691  Treehouse
#   2722  Discovery Kids
#   3924  Baby TV
#   2697  Disney Jr (dup-safe)
KIDS_PRESCHOOL_NETWORKS = (
    "2697|3919|14|277|3938|4654|5074|2575|4151|5085|2700|2691|2722|3924"
)
KIDS_MOVIE_CERTS = "G|PG"
KIDS_MOVIE_GENRES = "10751,16"  # Family, Animation
KIDS_FAMILY_GENRE = "10751"      # Family alone
KIDS_ANIMATION_GENRE = "16"      # Animation alone

# v2.8.11 — TV shows lack a /discover-time certification filter, so the
# previous Family-genre gate let adult animations through (South Park,
# Rick & Morty, Family Guy, BoJack Horseman are all tagged "Animation"
# on TMDB).  We now hardcode the EXACT allowed US content_ratings per
# tier and post-filter every TV result against TMDB's
# /tv/{id}/content_ratings endpoint.  Shows missing a US rating are
# excluded at strict tiers (TV-Y, TV-Y7, TV-G).
TV_ALLOWED_RATINGS = {
    # v2.8.42 — TV-Y was previously {"TV-Y"} alone, which capped the
    # Babies tier at ~10 titles because modern preschool hits (Bluey,
    # PAW Patrol, Peppa Pig, Cocomelon) are TV-Y7-rated.  Babies (0-2)
    # are perfectly safe with TV-Y7 content — it's still the "younger
    # children" rating with no scary/violent themes.  Combined with
    # our preschool-network whitelist this gives the user a rich
    # Babies catalog without any compromise on safety.
    "TV-Y":  {"TV-Y", "TV-Y7"},
    "TV-Y7": {"TV-Y", "TV-Y7"},
    "TV-G":  {"TV-Y", "TV-Y7", "TV-G"},
    "TV-PG": {"TV-Y", "TV-Y7", "TV-G", "TV-PG"},
    "TV-14": {"TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14"},
    "M15":   None,  # M15 = no cert gate (only banned-genre filter)
}
# Strict tiers REQUIRE a known US rating.  Higher tiers accept
# unrated shows on faith (they've already passed the genre gate).
TV_STRICT_TIERS = {"TV-Y", "TV-Y7", "TV-G"}


# ---------------------------------------------------------------------------
# Rating-driven kid filter levels
# ---------------------------------------------------------------------------
# The Settings page now exposes the user's strictness preference via two
# values: maxRatingMovie ∈ {G, PG, M, PG-13, M15} and maxRatingSeries ∈
# {TV-Y, TV-Y7, TV-G, TV-PG, TV-14, M15}.  These map onto TMDB queries
# below.  v2.8.12 — Added the Australian M tier (PG-13-equivalent) so
# parents have full tier coverage between PG and M15.  Nothing higher
# than M15 (no R18+ / NC-17 / Adult) is EVER exposed.

MOVIE_CERT_FILTER = {
    "G":     "G",
    "PG":    "PG",
    "M":     "PG-13",  # AU M ≈ US PG-13 (teens, mild themes)
    "PG-13": "PG-13",
    "M15":   "R",      # AU M15 ≈ US R (not NC-17, not Adult)
}

# Banned genres per movie strictness tier.  Higher tiers permit more
# nuanced content (drama, sci-fi) but adult-only categories stay banned.
MOVIE_BANNED = {
    "G":     {27, 53, 80, 10752, 18, 9648},          # +Drama +Mystery
    "PG":    {27, 53, 80, 10752},                    # Horror Thriller Crime War
    "M":     {27, 80, 10752},                        # Horror Crime War (= PG-13)
    "PG-13": {27, 80, 10752},                        # Horror Crime War
    "M15":   {27, 10752},                            # Horror, War only
}

# Required genre set (must contain at least one of these).  Looser at
# higher tiers to permit more variety (Adventure, Comedy).
MOVIE_REQUIRED = {
    "G":     {16, 10751},                            # Animation/Family
    "PG":    {16, 10751},
    "M":     {16, 10751, 12, 35},                    # +Adventure/Comedy
    "PG-13": {16, 10751, 12, 35},                    # +Adventure/Comedy
    "M15":   None,                                   # no genre gate
}

# TV strictness: TMDB doesn't accept certification for /discover/tv,
# so we encode the level by combining genre + network + language rules
# at the discover layer, THEN post-filter against /tv/{id}/content_ratings
# to enforce the actual US TV-Y / TV-Y7 / TV-G / TV-PG ceiling — which
# is the ONLY way to stop adult animations (Family Guy, Rick & Morty)
# that are tagged Family+Animation on TMDB from leaking through.
TV_LEVEL_PARAMS = {
    # v2.8.42 — Babies tier (TV-Y).  Removed the strict
    # `with_genres=10751,16` constraint that was forcing both
    # Family AND Animation — many preschool hits are live-action
    # (Sesame Street, Mr Rogers, Daniel Tiger's Neighborhood is
    # animated but Family-only-tagged on TMDB).  The network
    # whitelist + the {TV-Y, TV-Y7} content-rating post-filter
    # together still guarantee only true preschool content.
    # Also dropped the `with_original_language=en` so Bluey (AU),
    # Peppa Pig (UK), Hey Duggee (UK) and JoJo & Gran Gran (UK)
    # finally surface.
    "TV-Y":   {"with_networks": KIDS_PRESCHOOL_NETWORKS},
    # v2.8.42 — TV-Y7 (older preschool/early elementary).  Same
    # treatment — drop the rigid Animation genre lock and the
    # English-only filter so international preschool/kid shows
    # are visible.  Keeps the broader kids-network whitelist.
    "TV-Y7":  {"with_networks": KIDS_TV_NETWORKS},
    "TV-G":   {"with_genres": "10751,16", "with_networks": KIDS_TV_NETWORKS, "with_original_language": "en"},
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


# v2.8.13 — Per user spec: "if we're showing Babies, we wouldn't be
# showing The Lion King".  ONLY the Babies (TV-Y) tier forces the
# movie cap down — every other tier respects the parent's
# explicit `maxRatingMovie` setting exactly as chosen (so picking
# G or PG at the intro screen shows G/PG cascade as expected,
# picking PG-13 stays at PG-13, etc.).
TV_TO_MOVIE_CAP = {
    "TV-Y":   "G",   # babies: G movies only (and movies hidden on home)
    "TV-Y7":  None,  # respect user choice
    "TV-G":   None,
    "TV-PG":  None,
    "TV-14":  None,
    "M15":    None,
}

# Movie tier ordering — used to compute the EFFECTIVE cap when the
# user's chosen movie cert is more permissive than the TV-implied cap.
_MOVIE_ORDER = ["G", "PG", "M", "PG-13", "M15"]


def _effective_movie_cap(movie_cert: str, tv_level: str) -> str:
    """v2.8.14 — Return the stricter of the user's chosen movie tier
    and the TV tier's implicit cap.  The cap is None for every
    tier EXCEPT TV-Y (babies), so G/PG/M/PG-13 picks at all other
    tiers behave EXACTLY as the parent set them in Settings."""
    user = movie_cert if movie_cert in _MOVIE_ORDER else "PG"
    tv_cap = TV_TO_MOVIE_CAP.get(tv_level)
    if tv_cap is None:
        return user
    user_rank = _MOVIE_ORDER.index(user)
    cap_rank = _MOVIE_ORDER.index(tv_cap) if tv_cap in _MOVIE_ORDER else 4
    return _MOVIE_ORDER[min(user_rank, cap_rank)]



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


async def _tmdb_tv_us_rating(tv_id: int) -> Optional[str]:
    """Returns the US `content_rating` string for a TV show, or None
    if TMDB has no US classification on file.  Cached aggressively
    (24h) since TV ratings essentially never change."""
    if tv_id is None:
        return None
    cache_key = f"tmdb_tv_us_rating:{tv_id}"
    cached = await cache.get(cache_key)
    if cached is not None:
        return cached if cached != "__NONE__" else None
    data = await _tmdb_get(f"/tv/{tv_id}/content_ratings", {})
    rating: Optional[str] = None
    for r in (data or {}).get("results") or []:
        if r.get("iso_3166_1") == "US":
            rating = (r.get("rating") or "").upper().strip() or None
            break
    await cache.set(cache_key, rating or "__NONE__", 24 * 3600)
    return rating


async def _filter_tv_by_us_rating(
    items: List[Dict[str, Any]],
    tv_level: str,
) -> List[Dict[str, Any]]:
    """v2.8.11 — Hard post-filter: keep ONLY TV shows whose US
    content rating is within the allowed ceiling for this tier.
    This is what stops adult-animation leaks the genre/network
    gate alone can't catch.  Runs in parallel for throughput."""
    allowed = TV_ALLOWED_RATINGS.get(tv_level)
    if allowed is None:  # M15 — no cert gate
        return items
    strict = tv_level in TV_STRICT_TIERS
    ratings = await asyncio.gather(
        *[_tmdb_tv_us_rating(it.get("tmdb_id")) for it in items],
        return_exceptions=True,
    )
    out: List[Dict[str, Any]] = []
    for it, r in zip(items, ratings):
        rating = r if isinstance(r, str) else None
        if rating is None:
            # Unrated shows are allowed only at TV-PG and above.
            if strict:
                continue
            out.append(it)
            continue
        if rating in allowed:
            out.append(it)
    return out


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
        # v2.8.42 — Relax the vote-count floor for the youngest tiers
        # so international preschool hits (Bluey, Peppa Pig, JoJo &
        # Gran Gran, Hey Duggee) aren't filtered out for having fewer
        # US-centric TMDB ratings than their American counterparts.
        "vote_count.gte": 5 if tv_level in {"TV-Y", "TV-Y7"} else 30,
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
    # v2.8.11 — TV-specific post-filter against US content_ratings to
    # catch adult animations (South Park, Rick & Morty, Family Guy)
    # that are tagged Animation/Family on TMDB and survive the
    # /discover gate.
    if media == "tv":
        out = await _filter_tv_by_us_rating(out, tv_level)
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
    # v2.8.13 — Cap the effective movie cert by the TV tier so the
    # Babies pick can't accidentally surface PG-13 movies.
    movie_cert = _effective_movie_cap(movie_cert, tv_level)
    cache_key = f"tmdb_kids_shelves:v11:{movie_cert}:{tv_level}"
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

    # v2.8.42 — A permanent "Just For Babies" shelf for the youngest
    # tiers ONLY (TV-Y and TV-Y7).  We REFUSE to surface it for older-
    # kid tiers (TV-G+, TV-PG, TV-14, M15) — per user direction:
    # "I don't want baby stuff to be showing up if teenagers have
    # their profile selected."  Pulls preschool-network content with
    # an extra-deep page span (5 pages × 20 results = up to 100 raw
    # candidates before dedupe/cap) so the Babies tier becomes rich.
    babies_visible = tv_level in {"TV-Y", "TV-Y7"}

    async def babies_shelf():
        # Force the preschool whitelist regardless of the user's
        # selected tier (defensive — _tmdb_discover_kids already
        # applies it for TV-Y / TV-Y7, but being explicit costs
        # nothing and protects against a future tier-table edit).
        return await shelf(
            "just-for-babies",
            "Just For Babies",
            "tv",
            {
                "with_networks": KIDS_PRESCHOOL_NETWORKS,
                "sort_by": "popularity.desc",
            },
            pages=5,
        )

    # v2.8.42 — Bumped TV-Y / TV-Y7 page-counts (was 1-2 → now 3-5)
    # so the strict-preschool filter cone returns ~150-200 titles
    # for the Babies tier instead of the previous 11.
    pages_boost = 2 if tv_level in {"TV-Y", "TV-Y7"} else 0

    queries = []
    if babies_visible:
        queries.append(babies_shelf())
    queries.extend([
        shelf("family-favorites", "Family Favourites", "movie", {"sort_by": "popularity.desc"}, pages=3 + pages_boost),
        shelf("animated-magic",  "Animated Magic",    "movie", {"with_genres": "16,10751", "sort_by": "popularity.desc"}, pages=3 + pages_boost),
        shelf("top-rated-family","Top-Rated Family",  "movie", {"sort_by": "vote_average.desc", "vote_count.gte": 500}, pages=3 + pages_boost),
        shelf("adventure-time",  "Adventure Time",    "movie", {"with_genres": "10751,12", "sort_by": "popularity.desc"}, pages=2 + pages_boost),
        shelf("animated-series", "Animated Shows",    "tv",    {"sort_by": "popularity.desc"}, pages=3 + pages_boost),
        shelf("top-cartoons",    "Top-Rated Cartoons","tv",    {"sort_by": "vote_average.desc", "vote_count.gte": 100}, pages=3 + pages_boost),
        shelf("recent-family",   "New for the Family","movie", {"sort_by": "primary_release_date.desc", "vote_count.gte": 100, "primary_release_date.lte": datetime.now(timezone.utc).date().isoformat()}, pages=2 + pages_boost),
        shelf("musical-magic",   "Sing-Alongs",       "movie", {"with_genres": "10751,10402", "sort_by": "popularity.desc"}, pages=2 + pages_boost),
        shelf("comedy-films",    "Family Comedies",   "movie", {"with_genres": "10751,35", "sort_by": "popularity.desc"}, pages=2 + pages_boost),
        shelf("fantasy-films",   "Fantasy Adventures","movie", {"with_genres": "10751,14", "sort_by": "popularity.desc"}, pages=2 + pages_boost),
        shelf("classic-toons",   "Classic Cartoons",  "tv",    {"sort_by": "first_air_date.asc", "first_air_date.gte": "1990-01-01", "vote_count.gte": 50}, pages=2 + pages_boost),
        shelf("new-tv",          "Just-Aired Shows",  "tv",    {"sort_by": "first_air_date.desc", "first_air_date.lte": datetime.now(timezone.utc).date().isoformat(), "vote_count.gte": 30}, pages=2 + pages_boost),
    ])

    results = await asyncio.gather(*queries, return_exceptions=True)
    out = [
        r for r in results
        if isinstance(r, dict) and r.get("items")
    ]

    # v2.8.13 — At the Babies tier (TV-Y) the user explicitly does
    # NOT want movies on the Home screen ("if we're showing Babies
    # we wouldn't be showing The Lion King").  Babies watch short
    # preschool episodes, not feature films.  Drop ALL movie
    # shelves at this tier — the Movies tab can still surface
    # G-rated content if the parent navigates there directly.
    if tv_level == "TV-Y":
        out = [s for s in out if s.get("type") != "movie"]
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
    # v2.8.13 — Same TV→movie cap as the shelves endpoint.
    movie_cert = _effective_movie_cap(movie_cert, tv_level)
    q = q.strip()
    if not q:
        return {"data": []}

    cache_key = f"kids_search:v3:{q.lower()}:{movie_cert}:{tv_level}"
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

    # v2.8.11 — Apply the same hard TV-rating gate to search results
    # so adult animations can't sneak in via a name lookup.
    tv_out = await _filter_tv_by_us_rating(tv_out, tv_level)

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
    cache_key = f"tmdb_upcoming:v2:{limit}:{days}:en-us-gb"
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
                # v2.7.16 — user reports the trailer rail STILL
                # scrolls chunky on the HK1.  Drop /w780 → /w500
                # (~3× smaller image: ~50 KB vs ~150 KB).  At 380px
                # card width on a 1080p TV this is still visually
                # crisp — the source res is 281×500, displayed at
                # ~380×214 which is fine.
                f"{TMDB_IMG}/w500{item['backdrop_path']}"
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
    # v2.11.6 — Also expose ALL candidates in ranked order so the
    # frontend TrailerModal can auto-advance on YouTube Error 153
    # (embed-restricted video).  Common on major-studio trailers:
    # 9 of 9 Spider-Noir candidates were officially uploaded but
    # only 3-4 actually allow iframe embedding.  With candidates
    # in hand the modal just tries the next one — user never sees
    # "Watch on YouTube · Error 153".
    all_candidates = [
        {
            "key": v.get("key"),
            "name": v.get("name") or "Trailer",
            "site": "YouTube",
            "type": v.get("type") or "Trailer",
        }
        for v in youtube
        if v.get("key")
    ]
    out = {
        "key": best.get("key"),
        "name": best.get("name") or "Trailer",
        "site": "YouTube",
        "type": best.get("type") or "Trailer",
        "candidates": all_candidates,
    }
    await cache.set(cache_key, out, 60 * 60 * 6)
    return {"cached": False, "data": out}


@api.get("/trailer/streailer/{type_}/{imdb_id}")
async def streailer_trailer(type_: str, imdb_id: str, language: str = "en-US"):
    """v2.11.4 — Streailer Stremio addon proxy.  Multi-language trailer
    provider with a smarter TMDB→YouTube fallback chain than TMDB's
    raw /videos endpoint.  Catches trailers the built-in `/tmdb/trailer`
    misses (e.g. titles TMDB knows but doesn't have a linked YT video
    for).  Returns the same `{data:{key,name,site,type}}` shape so
    Detail.jsx can swap sources with zero UI changes.

    Args:
        type_: "movie" or "series"
        imdb_id: IMDB tt-id (e.g. "tt0111161")
        language: TMDB language tag (default en-US)

    Streailer contract (verified 01-Jul-2026):
        GET https://streailer.elfhosted.com/language={lang}/stream/{type}/{id}.json
        → { streams: [{ ytId, title, name, behaviorHints }] }

    We cache the ytId per (imdb, lang) for 6 h to keep TMDB roll
    behaviour under control (Streailer already caches internally).
    """
    if type_ not in ("movie", "series"):
        raise HTTPException(400, "type must be 'movie' or 'series'")
    imdb_id = (imdb_id or "").strip()
    if not imdb_id.startswith("tt"):
        raise HTTPException(400, "imdb_id must start with 'tt'")
    lang = (language or "en-US").strip() or "en-US"
    cache_key = f"streailer:{type_}:{imdb_id}:{lang}:v1"
    cached = await cache.get(cache_key)
    if cached is not None:
        return {"cached": True, "data": cached}

    url = f"https://streailer.elfhosted.com/language={lang}/stream/{type_}/{imdb_id}.json"
    try:
        async with httpx.AsyncClient(timeout=6.0) as client:
            r = await client.get(url, follow_redirects=True)
            if r.status_code != 200:
                await cache.set(cache_key, None, 60 * 60 * 6)
                return {"cached": False, "data": None}
            payload = r.json()
    except Exception as e:
        # Streailer down / DNS / timeout — negative-cache 10 min so we
        # don't hammer it; Detail.jsx will fall back to /tmdb/trailer.
        await cache.set(cache_key, None, 60 * 10)
        return {"cached": False, "data": None, "error": str(e)[:120]}

    streams = payload.get("streams") or []
    all_candidates = []
    for s in streams:
        cand = (s.get("ytId") or "").strip()
        if not cand:
            continue
        all_candidates.append({
            "key": cand,
            "name": s.get("title") or s.get("name") or "Trailer",
            "site": "YouTube",
            "type": "Trailer",
        })

    if not all_candidates:
        await cache.set(cache_key, None, 60 * 60 * 6)
        return {"cached": False, "data": None}

    best = all_candidates[0]
    out = {
        "key": best["key"],
        "name": best["name"],
        "site": "YouTube",
        "type": "Trailer",
        "source": "streailer",
        # v2.11.6 — Return the full candidate list so the modal can
        # auto-advance on YouTube Error 153 (embed-restricted).
        # Streailer rarely returns more than 1-2 candidates but we
        # forward them anyway so the frontend can uniformly cycle
        # through both TMDB and Streailer candidates.
        "candidates": all_candidates,
    }
    await cache.set(cache_key, out, 60 * 60 * 6)
    return {"cached": False, "data": out}


@api.get("/trailer-stream/{youtube_id}")
async def trailer_stream(youtube_id: str, combined: int = 0):
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

    v2.11.5 — `?combined=1` query param.  HTML5 `<video>` can't merge
    separate video-only + audio-only streams without MediaSource
    Extensions plumbing.  When the frontend requests `combined=1`, we
    force the yt-dlp format selector to only pick PROGRESSIVE MP4s
    (typically 360p, both audio and video multiplexed).  Returned
    `url` is guaranteed playable in a bare `<video>` element inside
    the WebView — no iframe, no MSE, no YouTube configuration errors.
    This is the failure-proof path for the trailer modal.

    Googlevideo URLs are signed with a ~6 h TTL.  We cache for 1 h
    to absorb repeat playback within the same session.
    """
    safe_id = re.sub(r"[^A-Za-z0-9_-]", "", youtube_id)[:24]
    if not safe_id:
        raise HTTPException(400, "invalid youtube_id")
    # v2.11.5 — cache separately for combined-mode responses so a web
    # client and a native client can share the pool without stomping
    # each other's format selection.
    variant = "combined" if combined else "hd"
    cache_key = f"trailer_stream:{safe_id}:{variant}:v3"
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
        #
        # v2.10.97 — Robust extract.  YouTube routinely breaks ONE
        # player_client's signature at a time (mostly `web` and
        # `android`).  We cycle through 4 clients per request so a
        # transient break in any single client never blocks the
        # user.  `tv_embedded` is tried FIRST because it's the most
        # permissive — works for age-gated trailers, doesn't get
        # geo-stamped on most VPS IPs, and isn't subject to the
        # current rolling `web` signature changes.  Each client is
        # given a 4 s hard cap so the total wall budget stays
        # within the outer 18 s timeout (4×4 = 16 s + extract
        # overhead).
        client_chain = ["tv_embedded", "android", "web", "ios"]
        last_err: Exception | None = None
        # v2.11.5 — Format selector branches on `combined` flag.
        # Web clients (bare HTML5 <video>) can't merge DASH pairs, so
        # they ask for combined progressive MP4 (typically 360p).
        # Native clients (libVLC input-slave) prefer the HD pair.
        if combined:
            fmt_selector = (
                "best[ext=mp4][acodec!=none][vcodec!=none][height<=720]/"
                "best[ext=mp4][acodec!=none][vcodec!=none]/"
                "best[acodec!=none][vcodec!=none]/"
                "best"
            )
        else:
            fmt_selector = (
                "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/"
                "best[ext=mp4][height<=720]/"
                "best[height<=720]/best"
            )
        for client in client_chain:
            ydl_opts = {
                "format": fmt_selector,
                "noplaylist": True,
                "quiet": True,
                "no_warnings": True,
                "skip_download": True,
                "extractor_args": {
                    "youtube": {
                        "player_client": [client],
                    },
                },
                "user_agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/120.0.0.0 Safari/537.36"
                ),
                "socket_timeout": 8,
            }
            try:
                with YoutubeDL(ydl_opts) as ydl:
                    info = ydl.extract_info(
                        f"https://www.youtube.com/watch?v={safe_id}",
                        download=False,
                    )
                # Success — return immediately.  Note: we accept
                # info without checking for HD pair here; the
                # caller will validate and either return the HD
                # pair OR fall back to the progressive URL.
                if info:
                    return info
            except Exception as e:                                  # noqa: BLE001
                last_err = e
                logger.info(
                    "yt-dlp client=%s failed for %s: %s",
                    client, safe_id, str(e)[:200],
                )
                continue
        # All clients failed — re-raise the last error so the outer
        # handler can return a 502 with a helpful message.
        raise last_err or RuntimeError("all yt-dlp clients failed")

    try:
        # v2.10.82 — Hard timeout on the yt-dlp call.  Without it,
        # yt-dlp can occasionally hang for 60+ seconds behind a
        # YouTube signature change or transient geo block, leaving
        # the trailer modal stuck on "Loading trailer in HD…".
        # 18 s is comfortably above the typical 4-8 s extract time
        # while still failing fast enough that the frontend can
        # gracefully fall back to the YouTube-app Intent.
        info = await asyncio.wait_for(
            loop.run_in_executor(None, _extract),
            timeout=18.0,
        )
    except asyncio.TimeoutError:
        logger.warning("yt-dlp extract timeout for %s", safe_id)
        raise HTTPException(504, "trailer_extract_timeout")
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


@api.get("/trailer/proxy/{youtube_id}")
async def trailer_proxy(youtube_id: str, request: Request):
    """v2.11.6 — Server-side MP4 proxy for browser trailer playback.
    Googlevideo signs its progressive URLs with the requesting IP
    baked in (`&ip=<extractor_ip>`); the URL 403s when fetched from
    any other IP.  Since our backend is what extracted the URL via
    yt-dlp, we're the only IP that can actually pull the bytes.

    This endpoint proxies the video bytes through our server so a
    plain `<video src="/api/trailer/proxy/{id}">` element on the
    client works uniformly (no IP binding surprises).  It preserves
    Range requests so `<video>` seek + preload behave normally.

    Cache is per-video: `trailer_stream_url:{id}` holds the current
    signed googlevideo URL (TTL 55 min, refreshed 5 min before the
    6 h googlevideo expiry).

    Bandwidth: 360p trailers are ~5-10 MB each, cached client-side
    after first fetch — small per-user footprint.
    """
    safe_id = re.sub(r"[^A-Za-z0-9_-]", "", youtube_id)[:24]
    if not safe_id:
        raise HTTPException(400, "invalid youtube_id")

    # Re-use the trailer_stream endpoint's cache to avoid a second
    # yt-dlp extract when a client just polled `/trailer-stream` a
    # second earlier.
    cache_key = f"trailer_stream:{safe_id}:combined:v3"
    entry = await cache.get(cache_key)
    if not entry:
        # Nothing cached — extract now.  Same yt-dlp path as
        # /trailer-stream to avoid divergence; we call the endpoint
        # handler directly so the extraction happens exactly once
        # and populates the cache for both endpoints.
        try:
            fresh = await trailer_stream(safe_id, combined=1)
        except HTTPException:
            raise
        # `trailer_stream` returns a dict wrapping (cached flag +
        # data); pull data straight out via the freshly-written
        # cache key.
        entry = await cache.get(cache_key)
        if not entry:
            entry = fresh  # fresh already unwrapped in return

    upstream_url = (entry or {}).get("url") or (entry or {}).get("progressive_url")
    if not upstream_url:
        raise HTTPException(502, "trailer_no_playable_url")

    # Forward Range header so HTML5 <video> seek works.
    upstream_headers: dict[str, str] = {
        "User-Agent": (
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            "AppleWebKit/537.36 (KHTML, like Gecko) "
            "Chrome/120.0.0.0 Safari/537.36"
        ),
    }
    range_hdr = request.headers.get("range")
    if range_hdr:
        upstream_headers["Range"] = range_hdr

    # Stream directly — no HEAD probe.  googlevideo signs the URL
    # for the extractor's IP; a GET from the same host succeeds.
    # We do NOT follow redirects here because the redirect target
    # can invalidate signature params.  If the upstream returns
    # 302 (which googlevideo occasionally does for regional
    # relocation), we forward it to the client and let the client
    # follow it — the client's <video> element handles 302 natively.
    async def _iterate(response):
        try:
            async for chunk in response.aiter_bytes(chunk_size=64 * 1024):
                if chunk:
                    yield chunk
        except Exception as e:  # noqa: BLE001
            logger.info("trailer_proxy stream err for %s: %s", safe_id, e)
            return

    timeout = httpx.Timeout(30.0, connect=8.0, read=30.0)
    client = httpx.AsyncClient(timeout=timeout, follow_redirects=True)
    try:
        req = client.build_request("GET", upstream_url, headers=upstream_headers)
        upstream = await client.send(req, stream=True)
    except Exception as e:  # noqa: BLE001
        await client.aclose()
        logger.info("trailer_proxy send failed for %s: %s", safe_id, e)
        raise HTTPException(502, "trailer_proxy_send_failed")

    if upstream.status_code >= 400:
        status = upstream.status_code
        await upstream.aclose()
        await client.aclose()
        raise HTTPException(status, f"trailer_proxy_upstream_{status}")

    resp_headers: dict[str, str] = {
        "Content-Type": upstream.headers.get("content-type", "video/mp4"),
        "Accept-Ranges": "bytes",
        "Cache-Control": "public, max-age=1800",
    }
    if upstream.headers.get("content-length"):
        resp_headers["Content-Length"] = upstream.headers["content-length"]
    if upstream.headers.get("content-range"):
        resp_headers["Content-Range"] = upstream.headers["content-range"]

    async def _stream_and_cleanup():
        try:
            async for chunk in upstream.aiter_bytes(chunk_size=64 * 1024):
                if chunk:
                    yield chunk
        finally:
            await upstream.aclose()
            await client.aclose()

    return StreamingResponse(
        _stream_and_cleanup(),
        status_code=upstream.status_code,
        headers=resp_headers,
        media_type=resp_headers["Content-Type"],
    )


@api.get("/tmdb/by-genres/{media}")
async def tmdb_by_genres(
    media: str,
    genre_ids: str = Query("", description="Comma-separated TMDB genre IDs"),
    limit: int = Query(50, ge=1, le=100),
):
    """Combined top popular titles across MULTIPLE genres.  Used
    by the viewing-style picker once the user has selected a set
    of genres — we union the most popular results across all of
    them, dedupe, and return the top `limit` by overall popularity.

    v2.10.24 — Also accepts SYNTHETIC negative-ID sentinels that
    translate to TMDB `with_keywords` discover queries instead of
    `with_genres`.  Currently:
      • -1 → "based on true story" (keyword 9672)
      • -2 → "biography"           (keyword 5565)
    """
    if media not in ("movie", "tv"):
        raise HTTPException(400, "media must be 'movie' or 'tv'")
    # Split into positive (real TMDB genre IDs) and negative
    # (synthetic keyword sentinels).  Drop anything non-numeric.
    raw_ids = [g.strip() for g in (genre_ids or "").split(",") if g.strip()]
    pos_ids: List[str] = []
    neg_ids: List[str] = []
    for g in raw_ids:
        if g.lstrip("-").isdigit():
            (neg_ids if g.startswith("-") else pos_ids).append(g)
    SYNTHETIC_KEYWORDS = {"-1": "9672", "-2": "5565"}
    if not pos_ids and not neg_ids:
        return {"cached": False, "data": []}
    # Cache key includes both buckets, sorted, so different
    # orderings of the same selection hit the same cache entry.
    key = ",".join(sorted(pos_ids + neg_ids))
    cache_key = f"tmdb_by_genres:{media}:{key}:{limit}:v2"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    seen: Dict[Any, Dict[str, Any]] = {}

    async def _pull_genre(genre_id: str, page: int):
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

    async def _pull_keyword(keyword_id: str, page: int):
        return await _tmdb_get(
            f"/discover/{media}",
            {
                "with_keywords": keyword_id,
                "sort_by": "popularity.desc",
                "include_adult": "false",
                "vote_count.gte": "200",
                "page": str(page),
            },
        )

    pages_per_genre = max(1, math.ceil(limit / 20))
    tasks = []
    for gid in pos_ids:
        for p in range(1, pages_per_genre + 1):
            tasks.append(_pull_genre(gid, p))
    for nid in neg_ids:
        kw = SYNTHETIC_KEYWORDS.get(nid)
        if not kw:
            continue
        for p in range(1, pages_per_genre + 1):
            tasks.append(_pull_keyword(kw, p))
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


# ─── EPG programme art lookup (used by native Live Guide overlay) ─────────────
import re as _re_epg_art

_EPG_ART_NORMALIZE = _re_epg_art.compile(r"\s*\([^)]*\)\s*|\s*\[[^]]*]\s*")

def _normalize_epg_title(title: str) -> str:
    """Strip parens/brackets, trailing season/episode hints, common
    suffixes like 'Live', 'New' so the TMDB query is cleaner."""
    t = _EPG_ART_NORMALIZE.sub(" ", title)
    t = _re_epg_art.sub(r"\s+S\d{1,2}\s*E\d{1,3}\b.*$", "", t, flags=_re_epg_art.IGNORECASE)
    t = _re_epg_art.sub(r"\s+\b(LIVE|NEW)\b.*$", "", t, flags=_re_epg_art.IGNORECASE)
    return t.strip()


@api.get("/epg/art")
async def epg_art(
    title: str = Query("", min_length=1),
    year: str = Query("", description="Optional release year for tighter match"),
):
    """Resolve a programme title to TMDB artwork.

    Used by the native Live TV Guide overlay (Android Kotlin Compose)
    to populate the right-side cinematic backdrop + the "Up Next"
    strip thumbnails.  Cached aggressively (7 days) per
    (normalized_title, year) since EPG titles repeat constantly.

    Returns:
        { backdrop: "https://image.tmdb.org/.../w1280/abc.jpg" | "",
          poster:   "https://image.tmdb.org/.../w500/def.jpg" | "",
          media_type: "movie" | "tv" | "",
          tmdb_id: 12345 | 0,
          tmdb_title: "Project Hail Mary" }
    """
    norm = _normalize_epg_title(title)
    if not norm:
        return {"backdrop": "", "poster": "", "media_type": "", "tmdb_id": 0, "tmdb_title": ""}
    cache_key = f"epg_art:{norm.lower()}:{(year or '').strip()}:v1"
    cached = await cache.get(cache_key)
    if cached:
        return cached
    # TMDB multi-search and pick first non-person hit; if year was
    # supplied, prefer a result whose release/first-air year matches.
    try:
        data = await _tmdb_get(
            "/search/multi",
            {"query": norm, "include_adult": "false", "page": "1"},
        )
    except Exception:
        return {"backdrop": "", "poster": "", "media_type": "", "tmdb_id": 0, "tmdb_title": ""}
    results = (data or {}).get("results") or []
    candidates = [
        r for r in results
        if r.get("media_type") in ("movie", "tv")
        and (r.get("backdrop_path") or r.get("poster_path"))
    ]
    if not candidates:
        out = {"backdrop": "", "poster": "", "media_type": "", "tmdb_id": 0, "tmdb_title": ""}
        await cache.set(cache_key, out, 60 * 60 * 24 * 7)
        return out

    def _year_of(r: Dict[str, Any]) -> str:
        d = r.get("release_date") or r.get("first_air_date") or ""
        return d[:4] if len(d) >= 4 else ""

    pick = None
    if year and year.strip():
        for r in candidates:
            if _year_of(r) == year.strip():
                pick = r
                break
    if pick is None:
        pick = candidates[0]

    backdrop_path = pick.get("backdrop_path") or ""
    poster_path = pick.get("poster_path") or ""
    out = {
        "backdrop": f"{TMDB_IMG}/w1280{backdrop_path}" if backdrop_path else "",
        "poster":   f"{TMDB_IMG}/w500{poster_path}"   if poster_path   else "",
        "media_type": pick.get("media_type") or "",
        "tmdb_id":  pick.get("id") or 0,
        "tmdb_title": (pick.get("title") or pick.get("name") or "").strip(),
    }
    await cache.set(cache_key, out, 60 * 60 * 24 * 7)
    return out




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


# ============================================================================
# v2.7.85 — Launcher Admin proxy (preview-URL convenience)
# ----------------------------------------------------------------------------
# The launcher backend runs as a separate FastAPI service on port 8002.  In
# production it lives at https://launcher-onnowtv.duckdns.org behind nginx,
# but the pod preview only routes /api/* to this backend.  So we expose the
# launcher admin UI via a transparent reverse proxy at
# /api/launcher-admin/* that strips the prefix and forwards to
# localhost:8002/*.  HTML + JS responses are rewritten on the fly so the
# admin's hard-coded absolute URLs (`/admin/static/...`, `/api/admin/...`,
# `/api/launcher/...`) keep working from inside the proxied namespace.
#
# This is preview-only — when deploying the launcher backend to the VPS,
# point devices at the real subdomain (DEPLOY.md step 5).
# ============================================================================
import httpx as _httpx_launcher

_LAUNCHER_BACKEND = "http://localhost:8002"
_LAUNCHER_PROXY_PREFIX = "/api/launcher-admin"


def _rewrite_admin_html(body: bytes) -> bytes:
    """Rewrite absolute paths in the admin index so static assets resolve
    through the proxy namespace."""
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        return body
    text = text.replace('href="/admin/', 'href="/api/launcher-admin/admin/')
    text = text.replace('src="/admin/', 'src="/api/launcher-admin/admin/')
    return text.encode("utf-8")


def _rewrite_admin_js(body: bytes) -> bytes:
    """Rewrite fetch URLs in app.js so admin API calls go through the
    proxy namespace.  Covers both single-quoted string literals AND
    template-literal backticks (used in the per-tile asset endpoints
    like `/api/admin/dock/${key}/image`)."""
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        return body
    text = text.replace("'/api/admin/", "'/api/launcher-admin/api/admin/")
    text = text.replace("'/api/launcher/", "'/api/launcher-admin/api/launcher/")
    text = text.replace("`/api/admin/", "`/api/launcher-admin/api/admin/")
    text = text.replace("`/api/launcher/", "`/api/launcher-admin/api/launcher/")
    return text.encode("utf-8")


def _rewrite_admin_json(body: bytes, absolute_base: Optional[str] = None) -> bytes:
    """Rewrite asset URLs in JSON responses so the user's browser
    (which can't reach pod-internal localhost) can load uploaded
    images / wallpapers / APK icons through the proxy.

    Two forms appear in the upstream JSON:
      (a) Absolute URLs prefixed with `PUBLIC_BASE_URL` (the public
          config endpoint runs assets through _abs() before returning)
      (b) Bare relative `/assets/...` paths (the admin /store endpoint
          returns the raw on-disk paths).

    `absolute_base` — when supplied (e.g. for Android launcher clients
    that need absolute URLs because they don't have a document origin
    to resolve against), the rewrite emits FULLY-QUALIFIED URLs.
    Browser clients get relative `/api/launcher-admin/assets/...`
    paths because they resolve those against the page origin
    automatically.
    """
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError:
        return body

    if absolute_base:
        # Strip any trailing slash from absolute_base so we don't
        # produce `https://x//api/launcher-admin/assets/...`.
        base = absolute_base.rstrip("/")
        replacement_prefix = f'"{base}/api/launcher-admin/assets/'
    else:
        replacement_prefix = '"/api/launcher-admin/assets/'

    text = text.replace(
        f'"{_LAUNCHER_BACKEND}/assets/',
        replacement_prefix,
    )
    text = text.replace(
        '"/assets/',
        replacement_prefix,
    )
    return text.encode("utf-8")


@app.api_route(
    "/api/launcher-admin/{full_path:path}",
    methods=["GET", "POST", "PUT", "DELETE", "PATCH"],
    include_in_schema=False,
)
async def launcher_admin_proxy(full_path: str, request: Request):
    """Transparent reverse proxy → launcher backend on :8002.

    Strips the `/api/launcher-admin/` prefix and forwards everything.
    HTML + JS responses are rewritten so the admin's absolute URLs
    (`/admin/static/...`, `/api/admin/...`, `/api/launcher/...`) keep
    resolving when accessed through this proxy."""
    target_url = f"{_LAUNCHER_BACKEND}/{full_path}"
    body = await request.body()
    # Drop hop-by-hop headers + the original Host header.
    skip_req = {"host", "content-length", "connection", "accept-encoding"}
    fwd_headers = {
        k: v for k, v in request.headers.items()
        if k.lower() not in skip_req
    }
    try:
        async with _httpx_launcher.AsyncClient(timeout=30.0) as client:
            upstream = await client.request(
                request.method,
                target_url,
                params=dict(request.query_params),
                content=body,
                headers=fwd_headers,
                follow_redirects=False,
            )
    except _httpx_launcher.ConnectError:
        return JSONResponse(
            {"error": "Launcher backend unreachable on :8002"},
            status_code=502,
        )

    response_body = upstream.content
    ctype = upstream.headers.get("content-type", "").lower()
    if "text/html" in ctype:
        response_body = _rewrite_admin_html(response_body)
    elif "javascript" in ctype or full_path.endswith(".js"):
        response_body = _rewrite_admin_js(response_body)
    elif "application/json" in ctype:
        # If the caller is a non-browser client (e.g. the OkHttp-based
        # Android launcher), rewrite to ABSOLUTE URLs since it can't
        # resolve relative paths against a document origin.  Browsers
        # send "Mozilla/..." UAs and get relative paths; everything
        # else gets fully-qualified URLs.
        ua = (request.headers.get("user-agent") or "").lower()
        is_browser = "mozilla" in ua
        absolute_base: Optional[str] = None
        if not is_browser:
            # Reconstruct the public URL we were just called on so the
            # rewritten asset URLs point back here.  Honour
            # X-Forwarded-Proto / Host from the ingress so https stays
            # https through the rewrite.
            scheme = request.headers.get("x-forwarded-proto") or request.url.scheme or "https"
            host = request.headers.get("x-forwarded-host") or request.headers.get("host") or ""
            if host:
                absolute_base = f"{scheme}://{host}"
        response_body = _rewrite_admin_json(response_body, absolute_base=absolute_base)

    # Filter response headers — drop the ones that no longer match the
    # rewritten body, and rewrite the Location header on redirects so it
    # stays inside our proxy namespace.
    skip_resp = {
        "content-encoding", "transfer-encoding", "content-length",
        "connection",
    }
    resp_headers = {}
    for k, v in upstream.headers.items():
        kl = k.lower()
        if kl in skip_resp:
            continue
        if kl == "location" and v.startswith("/"):
            v = f"{_LAUNCHER_PROXY_PREFIX}{v}"
        resp_headers[k] = v

    return Response(
        content=response_body,
        status_code=upstream.status_code,
        headers=resp_headers,
        media_type=upstream.headers.get("content-type"),
    )


# ----- App wiring ----------------------------------------------------------
# v2.8.43 — Mount the standalone Music API so its endpoints surface
# under /api/music/* without polluting Vesper's home server.py file.
from music_api import music_api as _music_api_router
api.include_router(_music_api_router)

# v2.8.74 — Karaoke Party (group karaoke with QR-code guest join,
# song queue, random mode, challenges).  Routes are prefixed
# `/api/karaoke/...` via the parent `api` router (which adds `/api`).
from karaoke_party import karaoke_party_router
api.include_router(karaoke_party_router)

app.include_router(api)


# ============================================================================
# v2.7.28 — Admin addon manager (token-protected HTML page)
# ----------------------------------------------------------------------------
# Routed under /admin/addons (NOT /api/) so it serves an HTML page directly
# without colliding with the API namespace.  Token comes from .env via
# ADMIN_TOKEN (fallback to legacy XTREAM_ADMIN_TOKEN so operators don't have
# to rotate keys).  Usage:
#   https://onnowtv.duckdns.org/admin/addons?token=onnowtv-admin-7b2f9e1c
# Paste a manifest URL → click Install → addon is added to MongoDB and every
# APK picks it up on the next home-screen reload.
# ============================================================================
def _admin_token_ok(token: Optional[str]) -> bool:
    expected = (
        os.environ.get("ADMIN_TOKEN")
        or os.environ.get("XTREAM_ADMIN_TOKEN")
        or ""
    ).strip()
    if not expected:
        return False
    return (token or "").strip() == expected


_ADMIN_ADDONS_HTML = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ON NOW TV V2 — Addon manager</title>
<style>
  :root {
    --bg-0: #06080F;
    --bg-1: #0E1626;
    --bg-2: rgba(15,22,38,0.92);
    --text: #E6EAF2;
    --text-2: #A6AFC0;
    --text-3: #7C8497;
    --cyan: #5DC8FF;
    --cyan-bright: #7FDCFF;
    --line: rgba(255,255,255,0.08);
    --danger: #FF6B6B;
  }
  *,*::before,*::after { box-sizing: border-box; }
  html,body { margin:0; padding:0; background:var(--bg-0); color:var(--text);
    font-family: 'Geist', system-ui, -apple-system, sans-serif;
    line-height: 1.55;
  }
  body { min-height: 100vh; padding: 48px 24px 80px;
    background:
      radial-gradient(ellipse 1200px 700px at 50% -200px, rgba(93,200,255,0.08) 0%, transparent 60%),
      var(--bg-0);
  }
  .wrap { max-width: 880px; margin: 0 auto; }
  header { display:flex; align-items:baseline; justify-content:space-between;
    margin-bottom: 36px; padding-bottom: 18px;
    border-bottom: 1px solid var(--line);
  }
  h1 { margin: 0; font-size: 28px; letter-spacing: -0.02em; font-weight: 700; }
  .eyebrow { color: var(--cyan); font-size: 11px; letter-spacing: 0.22em;
    text-transform: uppercase; margin-bottom: 6px; font-weight: 600;
  }
  .pill {
    font-size: 11px; letter-spacing: 0.14em; text-transform: uppercase;
    padding: 4px 10px; border-radius: 4px; background: rgba(93,200,255,0.16);
    color: var(--cyan-bright); border: 1px solid rgba(93,200,255,0.35);
    font-weight: 700;
  }
  section { margin-top: 28px; }
  .card { background: var(--bg-2); border: 1px solid var(--line);
    border-radius: 18px; padding: 24px 26px;
    box-shadow: 0 18px 50px rgba(0,0,0,0.45);
  }
  label { display:block; font-size: 12px; color: var(--text-3);
    letter-spacing: 0.1em; text-transform: uppercase; margin-bottom: 8px;
    font-weight: 600;
  }
  input[type=text] { width: 100%; padding: 14px 16px; border-radius: 12px;
    background: rgba(6,8,15,0.65); color: var(--text);
    border: 1px solid var(--line); font-size: 14px;
    font-family: 'JetBrains Mono', ui-monospace, monospace;
  }
  input[type=text]:focus { outline: 2px solid var(--cyan);
    outline-offset: 2px; border-color: transparent;
  }
  button.cta { margin-top: 14px; padding: 12px 28px; border-radius: 999px;
    background: var(--cyan); color: var(--bg-0); border: 0;
    font-weight: 700; font-size: 14px; letter-spacing: 0.02em;
    cursor: pointer; transition: transform 80ms ease;
  }
  button.cta:hover { transform: translateY(-1px); }
  button.cta:disabled { opacity: 0.5; cursor: not-allowed; }
  button.danger { padding: 6px 14px; border-radius: 999px;
    background: rgba(255,107,107,0.12); color: var(--danger);
    border: 1px solid rgba(255,107,107,0.35);
    font-weight: 600; font-size: 12px; cursor: pointer;
  }
  button.danger:hover { background: rgba(255,107,107,0.22); }
  ul.addons { list-style: none; margin: 0; padding: 0; }
  ul.addons li { display: flex; align-items: center; justify-content: space-between;
    padding: 14px 18px; border-radius: 12px; background: rgba(13,18,28,0.65);
    border: 1px solid var(--line); margin-bottom: 10px;
  }
  .addon-name { font-weight: 600; }
  .addon-url { font-family: 'JetBrains Mono', ui-monospace, monospace;
    font-size: 11px; color: var(--text-3); margin-top: 3px;
    overflow-wrap: anywhere;
  }
  .toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%);
    padding: 14px 24px; border-radius: 999px; font-weight: 600; font-size: 13px;
    background: rgba(13,18,28,0.95); color: var(--text);
    border: 1px solid var(--cyan); box-shadow: 0 10px 30px rgba(0,0,0,0.55);
    transition: opacity 200ms ease; opacity: 0; pointer-events: none;
  }
  .toast.show { opacity: 1; }
  .toast.error { border-color: var(--danger); color: var(--danger); }
  .helper { font-size: 12px; color: var(--text-3); margin-top: 10px;
    line-height: 1.6;
  }
  .helper code { background: rgba(255,255,255,0.06); padding: 2px 6px;
    border-radius: 4px; font-size: 11px;
  }
  .row-meta { min-width: 0; flex: 1; }
</style>
</head><body>
<div class="wrap">
  <header>
    <div>
      <div class="eyebrow">On Now TV V2</div>
      <h1>Addon manager</h1>
    </div>
    <span class="pill">Admin</span>
  </header>

  <section>
    <div class="card">
      <label>Quick-install — popular addons</label>
      <div style="display:flex; gap:10px; align-items:stretch; flex-wrap:wrap">
        <select id="curated-select" style="flex:1; min-width:240px; padding:14px 16px;
          border-radius:12px; background:rgba(6,8,15,0.65); color:var(--text);
          border:1px solid var(--line); font-size:14px;">
          <option value="">Choose an addon…</option>
          <option value="https://v3-cinemeta.strem.io/manifest.json">Cinemeta — TMDB metadata + posters (recommended)</option>
          <option value="https://opensubtitles-v3.strem.io/manifest.json">OpenSubtitles v3 — auto subtitles</option>
          <option value="https://mediafusion.elfhosted.com/manifest.json">MediaFusion — Real-Debrid sources (alternative to Torrentio)</option>
          <option value="https://aiostreams.elfhosted.com/manifest.json">AIO Streams — meta-addon (combines many sources)</option>
          <option value="https://94c8cb9f702d-tmdb-addon.baby-beamup.club/c/eyJsYW5ndWFnZSI6ImVuLVVTIn0%3D/manifest.json">TMDB Addon — full TMDB metadata + ratings</option>
          <option value="https://watchhub.strem.io/manifest.json">WatchHub — free streaming services search</option>
          <option value="https://stremio-jackett.elfhosted.com/manifest.json">Stremio Jackett — public + private trackers</option>
          <option value="https://anime-kitsu.strem.fun/manifest.json">Anime Kitsu — anime catalogue + sources</option>
          <option value="https://7a82163c306e-stremio-thepiratebay-plus.baby-beamup.club/manifest.json">ThePirateBay+ — public torrent index</option>
          <option value="https://orion-stremio-addon.web.app/manifest.json">Orion — premium scraper (paid)</option>
          <option value="https://stremio.juanftv.com/manifest.json">JuanFTV — Latin / Spanish channels</option>
          <option value="https://www.strem.io/twitch-stremio-v2/manifest.json">Twitch — live streamers</option>
          <option value="https://7a82163c306e-stremio-public-domain.baby-beamup.club/manifest.json">Public Domain — classic films</option>
        </select>
        <button class="cta" id="curated-install" style="margin:0; white-space:nowrap">Install selected</button>
      </div>
      <div class="helper">
        Pick one of the well-known addons above to install with a single click —
        the manifest URL fills in automatically.  For addons that need a config
        (Torrentio with your debrid key, TMDB with language, etc.) visit the
        addon's <code>/configure</code> page first, copy the URL the
        configurator gives you, then paste it in the box below.
      </div>
    </div>
  </section>

  <section>
    <div class="card">
      <label>Manifest URL — paste your own</label>
      <input id="manifest-url" type="text"
             placeholder="https://your-addon.example.com/manifest.json"
             autocomplete="off" autocorrect="off" autocapitalize="off"
             spellcheck="false">
      <button class="cta" id="install-btn">Install addon</button>
      <div class="helper">
        Paste a Stremio addon manifest URL. <code>stremio://...</code> deep
        links work too — they're auto-converted to <code>https://</code>.
        Once installed, every device running the APK picks it up on the next
        home reload. No app restart needed.
      </div>
    </div>
  </section>

  <section>
    <label style="padding-left:4px">Installed addons</label>
    <ul class="addons" id="addons-list"></ul>
  </section>
</div>

<div class="toast" id="toast"></div>

<script>
(function() {
  var TOKEN = new URLSearchParams(location.search).get('token') || '';
  var listEl = document.getElementById('addons-list');
  var btn = document.getElementById('install-btn');
  var input = document.getElementById('manifest-url');
  var toast = document.getElementById('toast');

  function showToast(msg, isError) {
    toast.textContent = msg;
    toast.classList.toggle('error', !!isError);
    toast.classList.add('show');
    clearTimeout(showToast._t);
    showToast._t = setTimeout(function() { toast.classList.remove('show'); }, 3000);
  }

  function escapeHtml(s) {
    return String(s || '').replace(/[&<>"']/g, function(c) {
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }

  async function fetchAddons() {
    listEl.innerHTML = '<li style="opacity:0.6">Loading…</li>';
    try {
      var r = await fetch('/api/addons');
      if (!r.ok) throw new Error('HTTP ' + r.status);
      var data = await r.json();
      var rows = data.addons || data || [];
      if (!rows.length) {
        listEl.innerHTML = '<li style="opacity:0.6">No addons installed yet.</li>';
        return;
      }
      listEl.innerHTML = rows.map(function(a) {
        var name = a.name || (a.manifest && a.manifest.name) || a.id || a.addon_id || '(unnamed)';
        var url = a.url || '';
        var id = a.id || a.addon_id || '';
        return '<li>' +
          '<div class="row-meta">' +
            '<div class="addon-name">' + escapeHtml(name) + '</div>' +
            '<div class="addon-url">' + escapeHtml(url) + '</div>' +
          '</div>' +
          '<button class="danger" data-remove="' + escapeHtml(id) + '">Remove</button>' +
          '</li>';
      }).join('');
      Array.prototype.forEach.call(
        listEl.querySelectorAll('[data-remove]'),
        function(b) { b.addEventListener('click', onRemove); }
      );
    } catch (e) {
      listEl.innerHTML = '<li style="color:var(--danger)">Failed to load: ' + escapeHtml(e.message) + '</li>';
    }
  }

  async function onInstall() {
    var url = (input.value || '').trim();
    if (!url) { showToast('Paste a manifest URL first', true); return; }
    if (url.indexOf('stremio://') === 0) {
      url = 'https://' + url.slice(10);
    }
    btn.disabled = true;
    btn.textContent = 'Installing…';
    try {
      var r = await fetch('/api/addons/install', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: url }),
      });
      var data = await r.json().catch(function() { return {}; });
      if (!r.ok) throw new Error(data.detail || ('HTTP ' + r.status));
      showToast('Installed: ' + (data.addon && data.addon.name || 'addon'));
      input.value = '';
      await fetchAddons();
    } catch (e) {
      showToast('Install failed: ' + e.message, true);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Install addon';
    }
  }

  async function onRemove(e) {
    var id = e.target.getAttribute('data-remove');
    if (!id) return;
    if (!confirm('Remove "' + id + '"?')) return;
    try {
      var r = await fetch('/api/addons/' + encodeURIComponent(id), { method: 'DELETE' });
      if (!r.ok) {
        var data = await r.json().catch(function() { return {}; });
        throw new Error(data.detail || ('HTTP ' + r.status));
      }
      showToast('Removed');
      await fetchAddons();
    } catch (err) {
      showToast('Remove failed: ' + err.message, true);
    }
  }

  btn.addEventListener('click', onInstall);
  input.addEventListener('keydown', function(e) {
    if (e.key === 'Enter') onInstall();
  });

  // Curated quick-install dropdown.
  var curatedSelect = document.getElementById('curated-select');
  var curatedBtn = document.getElementById('curated-install');
  curatedBtn.addEventListener('click', function() {
    var u = curatedSelect.value;
    if (!u) { showToast('Pick an addon from the dropdown first', true); return; }
    input.value = u;
    onInstall();
  });

  fetchAddons();
})();
</script>
</body></html>
"""


@app.get("/admin/addons", response_class=HTMLResponse)
@app.get("/api/admin/addons", response_class=HTMLResponse)
async def admin_addons_page(token: Optional[str] = None):
    if not _admin_token_ok(token):
        # Generic 404 instead of 401 so probers can't tell the page exists.
        raise HTTPException(404, "Not Found")
    return HTMLResponse(_ADMIN_ADDONS_HTML)


# ════════════════════════════════════════════════════════════════════
#  Admin · YouTube cookies for the Music app
# ════════════════════════════════════════════════════════════════════
_ADMIN_MUSIC_COOKIES_HTML = r"""<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ON NOW TV TUNES · YouTube cookies</title>
<style>
  :root {
    --bg-0:#06080F; --bg-1:#0E1626; --panel:rgba(15,22,38,0.92);
    --text:#E6EAF2; --text-2:#A6AFC0; --text-3:#7C8497;
    --cyan:#5DC8FF; --cyan-soft:rgba(93,200,255,.16);
    --green:#5BE39A; --red:#FF6B6B; --amber:#FFC46B;
    --line:rgba(255,255,255,.08); --radius:14px;
  }
  *{box-sizing:border-box}
  html,body{margin:0;background:radial-gradient(ellipse at top,#162033 0%,var(--bg-0) 70%);color:var(--text);font:15px/1.5 -apple-system,BlinkMacSystemFont,'SF Pro Text','Segoe UI',Roboto,sans-serif;min-height:100vh}
  .wrap{max-width:980px;margin:0 auto;padding:48px 24px 96px}
  header{display:flex;align-items:center;justify-content:space-between;margin-bottom:32px;gap:24px;flex-wrap:wrap}
  h1{font-size:28px;font-weight:600;letter-spacing:-.01em;margin:0}
  h1 .accent{color:var(--cyan)}
  .sub{color:var(--text-2);font-size:14px;margin-top:6px;max-width:560px}
  .chip{display:inline-flex;align-items:center;gap:8px;padding:8px 14px;background:var(--cyan-soft);color:var(--cyan);border-radius:99px;font-size:13px;font-weight:500}
  .card{background:var(--panel);border:1px solid var(--line);border-radius:var(--radius);padding:28px;margin-bottom:24px;backdrop-filter:blur(20px)}
  .card h2{font-size:16px;font-weight:600;margin:0 0 16px;letter-spacing:.01em;color:var(--text)}
  .card h2 .count{color:var(--text-3);font-weight:400;margin-left:8px}
  .drop{display:block;border:1.5px dashed rgba(93,200,255,.45);border-radius:var(--radius);padding:48px 24px;text-align:center;cursor:pointer;transition:all .15s ease;background:rgba(93,200,255,.04)}
  .drop:hover,.drop.active{border-color:var(--cyan);background:rgba(93,200,255,.08)}
  .drop-title{font-size:18px;font-weight:600;color:var(--text);margin-bottom:8px}
  .drop-hint{color:var(--text-2);font-size:13px;line-height:1.6}
  .drop input{display:none}
  .row{display:flex;gap:12px;align-items:center;flex-wrap:wrap}
  .files{display:flex;flex-direction:column;gap:8px;margin-top:16px}
  .file-card{display:flex;align-items:center;gap:14px;padding:14px 16px;background:rgba(255,255,255,.025);border:1px solid var(--line);border-radius:10px}
  .file-icon{width:36px;height:36px;display:flex;align-items:center;justify-content:center;background:var(--cyan-soft);border-radius:8px;font-size:18px}
  .file-info{flex:1;min-width:0}
  .file-name{font-weight:600;font-size:14px;display:flex;align-items:center;gap:8px}
  .badge{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;border-radius:99px;font-size:11px;font-weight:500;letter-spacing:.02em;text-transform:uppercase}
  .badge.ok{background:rgba(91,227,154,.16);color:var(--green)}
  .badge.warn{background:rgba(255,196,107,.16);color:var(--amber)}
  .badge.fail{background:rgba(255,107,107,.16);color:var(--red)}
  .file-meta{font-size:12px;color:var(--text-3);margin-top:4px;font-family:'SF Mono',ui-monospace,Menlo,monospace}
  .file-actions{display:flex;gap:8px}
  button{font:inherit;cursor:pointer;border:none;border-radius:8px;padding:8px 14px;font-size:13px;font-weight:500;transition:all .15s ease}
  button.primary{background:var(--cyan);color:#001423}
  button.primary:hover{background:#74D2FF}
  button.ghost{background:rgba(255,255,255,.05);color:var(--text-2)}
  button.ghost:hover{background:rgba(255,255,255,.1);color:var(--text)}
  button.danger{background:rgba(255,107,107,.15);color:var(--red)}
  button.danger:hover{background:rgba(255,107,107,.3)}
  button:disabled{opacity:.4;cursor:not-allowed}
  .empty{text-align:center;color:var(--text-3);padding:32px 12px;font-size:14px}
  details{margin-top:16px}
  details summary{cursor:pointer;color:var(--text-2);font-size:13px;font-weight:500;padding:8px 0;user-select:none}
  details summary:hover{color:var(--text)}
  details ol{padding-left:22px;color:var(--text-2);font-size:13px;line-height:1.7}
  details code{background:rgba(255,255,255,.06);padding:2px 6px;border-radius:4px;font-family:'SF Mono',ui-monospace,Menlo,monospace;font-size:12px;color:var(--cyan)}
  .test-row{display:flex;gap:8px;align-items:center;margin-top:14px;flex-wrap:wrap}
  .test-row input{background:rgba(255,255,255,.04);border:1px solid var(--line);border-radius:8px;padding:8px 12px;color:var(--text);font:inherit;font-size:13px;min-width:120px}
  .test-result{margin-top:12px;padding:12px;border-radius:10px;font-size:13px;font-family:'SF Mono',ui-monospace,Menlo,monospace;display:none;white-space:pre-wrap;word-break:break-all}
  .test-result.ok{display:block;background:rgba(91,227,154,.08);border:1px solid rgba(91,227,154,.3);color:var(--green)}
  .test-result.fail{display:block;background:rgba(255,107,107,.08);border:1px solid rgba(255,107,107,.3);color:var(--red)}
  .toast{position:fixed;bottom:24px;left:50%;transform:translateX(-50%);padding:12px 20px;border-radius:99px;background:var(--cyan);color:#001423;font-weight:600;font-size:14px;box-shadow:0 8px 24px rgba(0,0,0,.4);opacity:0;transition:opacity .2s,transform .2s;pointer-events:none}
  .toast.show{opacity:1;transform:translateX(-50%) translateY(-4px)}
  .toast.fail{background:var(--red);color:#fff}
</style>
</head><body>
<div class="wrap">
  <header>
    <div>
      <h1>ON NOW TV <span class="accent">TUNES</span> · YouTube cookies</h1>
      <div class="sub">Drop signed-in YouTube <code>cookies.txt</code> files here so the Music app can resolve full-length tracks. Bytes still stream direct from YouTube's CDN — your VPS only resolves the URL.</div>
    </div>
    <span class="chip" id="dirChip">cookies dir…</span>
  </header>

  <div class="card">
    <h2>1. Upload cookies</h2>
    <label class="drop" id="dropZone" for="fileInput" data-testid="cookies-dropzone">
      <div class="drop-title">Drop <code>cookies.txt</code> here or click to browse</div>
      <div class="drop-hint">Max 1 MiB · Netscape format · From a signed-in YouTube session.<br/>Upload 2–3 files (different accounts) for automatic round-robin failover.</div>
      <input type="file" id="fileInput" accept=".txt,text/plain" multiple data-testid="cookies-file-input"/>
    </label>

    <details>
      <summary>How do I get a cookies.txt? (step-by-step)</summary>
      <ol>
        <li>Create or sign into a <strong>throwaway</strong> Google account — never your personal one.</li>
        <li>Install the Chrome extension <strong>"Get cookies.txt LOCALLY"</strong> (make sure it's the "LOCALLY" one).</li>
        <li>Open <code>https://youtube.com</code> in that browser session.</li>
        <li>Click the extension icon → <strong>Export As</strong>: choose <strong>Netscape</strong> → save as <code>account-1.txt</code>.</li>
        <li>Drag the file into the drop zone above.</li>
        <li>Repeat with a second / third account to enable round-robin (recommended).</li>
        <li>Rotate every 2–4 weeks — sign out / sign in / re-export.</li>
      </ol>
    </details>
  </div>

  <div class="card">
    <h2>2. Loaded cookies <span class="count" id="countLabel"></span></h2>
    <div id="filesContainer" class="files"></div>
  </div>

  <div class="card">
    <h2>3. Test a track</h2>
    <div class="sub" style="margin-bottom:8px">Verifies cookies are healthy by doing a real YouTube resolve. Returns a signed CDN URL on success.</div>
    <div class="test-row">
      <input id="testArtist" value="Adele" data-testid="test-artist-input"/>
      <input id="testTitle" value="Hello" data-testid="test-title-input"/>
      <button class="primary" id="testBtn" data-testid="test-resolve-btn">Resolve →</button>
    </div>
    <div id="testResult" class="test-result"></div>
  </div>
</div>
<div class="toast" id="toast"></div>

<script>
(function(){
  const qs = new URLSearchParams(location.search);
  const TOKEN = qs.get('token') || '';
  const API = location.pathname.replace(/\/admin\/music-cookies\/?$/, '').replace(/\/$/, '');
  // The route lives at both `/admin/music-cookies` and `/api/admin/music-cookies`.
  // Music API routes always live under `/api/music/admin/cookies/…` regardless
  // of how the admin page itself was reached.
  const ROOT = API.endsWith('/api') ? API.replace(/\/api$/, '') : API;
  const BASE = ROOT + '/api/music/admin/cookies';

  const $ = (s) => document.querySelector(s);
  const dirChip = $('#dirChip');
  const filesEl = $('#filesContainer');
  const countLabel = $('#countLabel');
  const toast = $('#toast');

  function showToast(msg, fail) {
    toast.textContent = msg;
    toast.classList.toggle('fail', !!fail);
    toast.classList.add('show');
    setTimeout(() => toast.classList.remove('show'), 2200);
  }
  function fmtTs(ts) {
    if (!ts) return '—';
    const dt = new Date(ts * 1000);
    return dt.toLocaleString();
  }
  function fmtBytes(n) {
    if (!n) return '0 B';
    if (n < 1024) return n + ' B';
    if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KiB';
    return (n / 1024 / 1024).toFixed(1) + ' MiB';
  }

  async function refresh() {
    try {
      const r = await fetch(`${BASE}/status?token=${encodeURIComponent(TOKEN)}`);
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      const data = await r.json();
      dirChip.textContent = '📁 ' + data.cookies_dir;
      countLabel.textContent = `· ${data.count} file${data.count === 1 ? '' : 's'}`;
      if (!data.files.length) {
        filesEl.innerHTML = '<div class="empty">No cookies uploaded yet. Drop a <code>cookies.txt</code> above to get started.</div>';
        return;
      }
      filesEl.innerHTML = data.files.map(f => {
        const total = (f.used || 0);
        const failRate = total > 0 ? (f.fail / total) : 0;
        let badge, hint = '';
        if (!f.looks_signed_in) {
          badge = '<span class="badge fail">not signed into youtube</span>';
          hint = '<br/><span style="color:var(--amber);font-size:12px">⚠ Open youtube.com, click SIGN IN top-right, then re-export.</span>';
        } else if (total === 0) {
          badge = '<span class="badge ok">ready</span>';
        } else if (failRate > 0.5) {
          badge = '<span class="badge fail">failing</span>';
        } else {
          badge = '<span class="badge ok">healthy</span>';
        }
        const stats = total > 0
          ? `${f.success}/${total} ok · last ok ${fmtTs(f.last_success_ts)}` + (f.last_error ? ` · err: ${f.last_error}` : '')
          : `uploaded ${fmtTs(f.uploaded_at)} · ${fmtBytes(f.size_bytes)}`;
        return `
          <div class="file-card" data-testid="cookie-file-${f.name}">
            <div class="file-icon">🍪</div>
            <div class="file-info">
              <div class="file-name">${f.name} ${badge}</div>
              <div class="file-meta">${stats}${hint}</div>
            </div>
            <div class="file-actions">
              <button class="danger" data-del="${f.name}" data-testid="delete-cookie-${f.name}">Delete</button>
            </div>
          </div>
        `;
      }).join('');
      filesEl.querySelectorAll('[data-del]').forEach(b => {
        b.addEventListener('click', () => del(b.dataset.del));
      });
    } catch (e) {
      dirChip.textContent = '⚠️ failed to load — wrong token?';
      filesEl.innerHTML = `<div class="empty" style="color:var(--red)">Status check failed: ${e.message}</div>`;
    }
  }

  async function upload(files) {
    if (!files || !files.length) return;
    for (const file of files) {
      const fd = new FormData();
      fd.append('token', TOKEN);
      fd.append('file', file);
      fd.append('name', file.name);
      try {
        const r = await fetch(`${BASE}/upload`, { method: 'POST', body: fd });
        const data = await r.json().catch(() => ({}));
        if (!r.ok) throw new Error(data.detail || `HTTP ${r.status}`);
        showToast('✓ Uploaded ' + file.name);
      } catch (e) {
        showToast('Upload failed: ' + e.message, true);
      }
    }
    refresh();
  }

  async function del(name) {
    if (!confirm(`Delete ${name}?`)) return;
    try {
      const r = await fetch(`${BASE}/${encodeURIComponent(name)}?token=${encodeURIComponent(TOKEN)}`, { method: 'DELETE' });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(data.detail || `HTTP ${r.status}`);
      showToast('Deleted ' + name);
    } catch (e) {
      showToast('Delete failed: ' + e.message, true);
    }
    refresh();
  }

  // Drag-and-drop
  const dropZone = $('#dropZone');
  const fileInput = $('#fileInput');
  ['dragenter', 'dragover'].forEach(ev => {
    dropZone.addEventListener(ev, e => { e.preventDefault(); dropZone.classList.add('active'); });
  });
  ['dragleave', 'drop'].forEach(ev => {
    dropZone.addEventListener(ev, e => { e.preventDefault(); dropZone.classList.remove('active'); });
  });
  dropZone.addEventListener('drop', e => upload(e.dataTransfer.files));
  fileInput.addEventListener('change', e => upload(e.target.files));

  // Test resolve
  $('#testBtn').addEventListener('click', async () => {
    const btn = $('#testBtn');
    const out = $('#testResult');
    const artist = $('#testArtist').value || 'Adele';
    const title = $('#testTitle').value || 'Hello';
    btn.disabled = true; btn.textContent = 'Resolving…';
    out.className = 'test-result';
    try {
      const fd = new FormData();
      fd.append('token', TOKEN);
      fd.append('artist', artist);
      fd.append('title', title);
      const r = await fetch(`${BASE}/test`, { method: 'POST', body: fd });
      const data = await r.json().catch(() => ({}));
      if (r.ok && data.ok) {
        out.className = 'test-result ok';
        out.textContent = `✓ ${data.title}\n  by ${data.uploader}\n  yt_id: ${data.yt_id}\n  duration: ${data.duration}s\n  resolved in ${data.elapsed_ms}ms\n  url: ${data.preview_url}`;
      } else {
        out.className = 'test-result fail';
        out.textContent = `✗ ${(data && data.error) || 'resolve failed'} (${data && data.elapsed_ms}ms)`;
      }
    } catch (e) {
      out.className = 'test-result fail';
      out.textContent = '✗ network error: ' + e.message;
    } finally {
      btn.disabled = false; btn.textContent = 'Resolve →';
      refresh();
    }
  });

  refresh();
})();
</script>
</body></html>
"""


@app.get("/admin/music-cookies", response_class=HTMLResponse)
@app.get("/api/admin/music-cookies", response_class=HTMLResponse)
async def admin_music_cookies_page(token: Optional[str] = None):
    if not _admin_token_ok(token):
        # Generic 404 instead of 401 so probers can't tell the page exists.
        raise HTTPException(404, "Not Found")
    return HTMLResponse(_ADMIN_MUSIC_COOKIES_HTML)


# Xtream Codes IPTV proxy (auth, categories, streams, EPG)
from xtream import router as xtream_router  # noqa: E402
app.include_router(xtream_router)

from watch_party import router as watch_party_router  # noqa: E402
app.include_router(watch_party_router)
from stt import router as stt_router  # noqa: E402
app.include_router(stt_router)

# v2.8.90 — ON NOW V2 Free-to-Air router (Brisbane AU FTA + EPG).
from fta import router as fta_router  # noqa: E402
app.include_router(fta_router)

from sportsdb import router as sportsdb_router  # noqa: E402
app.include_router(sportsdb_router)

from backup import router as backup_router  # noqa: E402
app.include_router(backup_router)

from library import router as library_router  # noqa: E402
app.include_router(library_router)

from instant_bundle import router as instant_bundle_router  # noqa: E402
app.include_router(instant_bundle_router)

# v2.10.47 — Custom JWT login system (Xtream-credential vault).
from auth_router import build_auth_router, ensure_indexes as ensure_auth_indexes  # noqa: E402
app.include_router(build_auth_router(lambda: db))


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
async def _ensure_auth_indexes() -> None:
    """Create MongoDB indexes for the auth (xtream_accounts +
    login_attempts) collections.  Idempotent — safe on every boot."""
    try:
        await ensure_auth_indexes(db)
        logger.info("Auth indexes ensured (vesper_accounts.username uniq)")
    except Exception as exc:  # noqa: BLE001
        logger.warning("Failed to create auth indexes: %s", exc)


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
    # v2.7.27 — `language=...` filter EXCLUDES the listed foreign
    # languages so only English-language (and language-agnostic)
    # releases reach the source list.  Torrentio's `language` param
    # is an exclusion filter, NOT an "include only english" filter.
    # The list below covers every language Torrentio scrapers
    # currently classify; English-language and untagged releases
    # are not in it and therefore stay.
    foreign_langs = (
        "russian,french,spanish,italian,german,portuguese,polish,"
        "hindi,tamil,telugu,malayalam,korean,japanese,chinese,"
        "turkish,arabic,dutch,danish,swedish,norwegian,finnish,"
        "czech,hungarian,greek,thai,vietnamese,indonesian,hebrew,"
        "ukrainian,romanian"
    )
    premiumize_key = (os.environ.get("PREMIUMIZE_API_KEY") or "").strip()
    if premiumize_key:
        torrentio_url = (
            "https://torrentio.strem.fun/"
            "sort=qualitysize%7Cqualityfilter=scr,cam,unknown,480p,720p"
            f"%7Clanguage={foreign_langs}"
            f"%7Cpremiumize={premiumize_key}/manifest.json"
        )
    else:
        # No Debrid key — fall back to magnet-only Torrentio so the
        # user still gets a catalogue, but warn so the operator
        # knows playback won't work on libVLC Android.
        torrentio_url = (
            "https://torrentio.strem.fun/"
            "sort=qualitysize%7Cqualityfilter=scr,cam,unknown,480p,720p"
            f"%7Clanguage={foreign_langs}"
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
                expected_base = s["url"].rsplit("/manifest.json", 1)[0]

                # v2.7.27 — special-case Torrentio: Cloudflare often
                # 403s our datacentre IP on the FIRST manifest fetch
                # so we never reach the URL-update path.  If the
                # existing row's URL has drifted (e.g. because the
                # operator updated the language filter), force the
                # URL update even when manifest fetch fails — we
                # keep the OLD manifest as a fallback.
                manifest: Any = None
                try:
                    async with httpx.AsyncClient(timeout=20) as client:
                        manifest = await _fetch_json(client, manifest_url)
                except Exception:
                    manifest = None

                if isinstance(manifest, dict):
                    addon_id = manifest.get("id")
                    if not addon_id:
                        continue
                    existing = existing_addons.get(addon_id)
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
                else:
                    # Manifest fetch failed.  If this is Torrentio
                    # AND we already have it in the DB AND the URL
                    # drifted, force-update the URL but keep the
                    # cached manifest.
                    if s.get("name") == "Torrentio":
                        for row in existing_addons.values():
                            row_url = row.get("url", "")
                            if (
                                row_url.startswith("https://torrentio.strem.fun")
                                and row_url != expected_base
                            ):
                                now = datetime.now(timezone.utc).isoformat()
                                await db.addons.update_one(
                                    {"user_id": DEFAULT_USER, "addon_id": row.get("addon_id")},
                                    {
                                        "$set": {
                                            "url": expected_base,
                                            "updated_at": now,
                                        }
                                    },
                                )
                                updated.append(f"{s['name']} (URL only)")
                                break
                    continue
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
    """Live TV plumbing has been disabled per user request.  The
    instant-bundle scheduler is no longer started; the /live-tv UI
    route now renders a 'Coming Soon' placeholder and the new
    native Android launcher will own the Live TV experience going
    forward."""
    return
