"""
ON NOW TV TUNES — Music backend API
======================================================================

A single FastAPI router that powers the standalone Music app
(`tv.onnowtv.tunes`).  Mounted by `server.py` under the existing
`/api` prefix so the music endpoints surface as `/api/music/*`.

Content sources (all 100 % free, no auth required):

  • **Deezer Public API**   — catalog/search/charts/new-releases/album-tracks
                              with 30-second preview streams for every track.
                              Real Spotify-quality artwork + metadata.
  • **Radio Browser API**   — 50 000+ live radio stations worldwide.
                              Returns direct streamable URLs (MP3/AAC/Vorbis).
  • **iTunes Search API**   — Podcast discovery + 1 000 000+ shows.
                              Each podcast carries an RSS `feedUrl`.
  • **feedparser**          — Standard RSS parsing for podcast episodes.
  • **Jamendo (planned)**   — Full-length Creative-Commons music for the
                              "play full track" use case.  Stub included
                              but not wired into the home shelves yet.

Design choices:

  • Everything is cached aggressively (1 h for catalog, 6 h for charts,
    24 h for top podcasts) — the same `cache` module Vesper uses.
  • Every response is a plain Pydantic-shaped dict.  No `_id`, no
    BSON cruft.
  • Search is "fan-out" — a single user query hits Deezer + Radio
    Browser + iTunes concurrently and returns a unified shape.
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional
from urllib.parse import quote

import httpx
from fastapi import APIRouter, HTTPException, Query


# ════════════════════════════════════════════════════════════════════
#  Tiny in-process TTL cache (self-contained — no external module)
# ════════════════════════════════════════════════════════════════════
class _TTLCache:
    """Simple async-safe in-memory TTL cache.  Music endpoints are
    cheap to recompute, so we don't need MongoDB persistence — the
    process restart on the VPS clears the cache, which is exactly
    what we want for a freshness reset."""

    def __init__(self):
        self._store: Dict[str, Any] = {}
        self._exp: Dict[str, float] = {}
        self._lock = asyncio.Lock()

    async def get(self, key: str) -> Any | None:
        async with self._lock:
            exp = self._exp.get(key)
            if exp is None:
                return None
            if datetime.now(timezone.utc).timestamp() > exp:
                self._store.pop(key, None)
                self._exp.pop(key, None)
                return None
            return self._store.get(key)

    async def set(self, key: str, value: Any, ttl_seconds: int) -> None:
        async with self._lock:
            self._store[key] = value
            self._exp[key] = datetime.now(timezone.utc).timestamp() + ttl_seconds


cache = _TTLCache()
log = logging.getLogger("music-api")
music_api = APIRouter(prefix="/music", tags=["music"])


# ════════════════════════════════════════════════════════════════════
#  Constants + helpers
# ════════════════════════════════════════════════════════════════════
DEEZER_BASE = "https://api.deezer.com"
ITUNES_BASE = "https://itunes.apple.com"
# Radio Browser uses a DNS-based load balancer — fetch a fresh server
# every 15 minutes so we don't hammer a single mirror.
_RADIO_SERVER_CACHE: Dict[str, Any] = {"server": None, "ts": 0.0}


async def _get_radio_server() -> str:
    """Pick a healthy Radio Browser mirror; cache 15 min."""
    now = datetime.now(timezone.utc).timestamp()
    if _RADIO_SERVER_CACHE["server"] and (now - _RADIO_SERVER_CACHE["ts"]) < 900:
        return _RADIO_SERVER_CACHE["server"]
    # Pick a random hostname from the SRV-style HTTP endpoint.
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            r = await client.get("https://all.api.radio-browser.info/json/servers")
            r.raise_for_status()
            servers = [f"https://{x['name']}" for x in r.json() if x.get("name")]
            if not servers:
                raise RuntimeError("no servers")
            # Round-robin via current epoch second so different boxes
            # land on different mirrors.
            chosen = servers[int(now) % len(servers)]
            _RADIO_SERVER_CACHE["server"] = chosen
            _RADIO_SERVER_CACHE["ts"] = now
            return chosen
    except Exception:
        # Hard fallback — fr1.api.radio-browser.info is reliable.
        return "https://fr1.api.radio-browser.info"


def _shape_track(t: Dict[str, Any]) -> Dict[str, Any]:
    """Normalize a Deezer track to our Music API shape."""
    album = t.get("album") or {}
    artist = t.get("artist") or {}
    return {
        "id": str(t.get("id")),
        "title": t.get("title") or t.get("title_short") or "Unknown",
        "duration": int(t.get("duration") or 0),
        "preview_url": t.get("preview"),  # 30-second MP3
        "artist": {
            "id": str(artist.get("id") or ""),
            "name": artist.get("name") or "Unknown",
            "picture": artist.get("picture_xl") or artist.get("picture_big"),
        },
        "album": {
            "id": str(album.get("id") or ""),
            "title": album.get("title") or "",
            "cover": album.get("cover_xl") or album.get("cover_big") or album.get("cover_medium"),
        },
        "explicit": bool(t.get("explicit_lyrics") or False),
    }


def _shape_album(a: Dict[str, Any]) -> Dict[str, Any]:
    artist = a.get("artist") or {}
    return {
        "id": str(a.get("id")),
        "title": a.get("title") or "Untitled",
        "cover": a.get("cover_xl") or a.get("cover_big") or a.get("cover_medium"),
        "release_date": a.get("release_date") or "",
        "nb_tracks": int(a.get("nb_tracks") or 0),
        "duration": int(a.get("duration") or 0),
        "artist": {
            "id": str(artist.get("id") or ""),
            "name": artist.get("name") or "Unknown",
        },
    }


def _shape_artist(a: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": str(a.get("id")),
        "name": a.get("name") or "Unknown",
        "picture": a.get("picture_xl") or a.get("picture_big"),
        "nb_album": int(a.get("nb_album") or 0),
        "nb_fan": int(a.get("nb_fan") or 0),
    }


# ════════════════════════════════════════════════════════════════════
#  Deezer (music catalog + previews)
# ════════════════════════════════════════════════════════════════════
async def _deezer_get(path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """GET against Deezer Public API; 10-s timeout; returns JSON."""
    url = f"{DEEZER_BASE}{path}"
    async with httpx.AsyncClient(timeout=10) as client:
        r = await client.get(url, params=params)
        r.raise_for_status()
        return r.json()


@music_api.get("/home")
async def music_home():
    """Returns the curated Music home shelves: hero + new-releases +
    charts + featured playlists.  Cached 1 h.

    v2.8.63 — Source switched to **iTunes US Top Songs / Top Albums**
    (Apple Music RSS feeds for the US store) instead of Deezer's
    global chart, which was returning French content because Deezer
    is FR-centric.  For each iTunes chart item we run a Deezer search
    to get the playable preview_url + cover_xl + matching album/
    artist IDs, so the user still gets the same shape they had before
    but with **mainstream American** artists (Taylor Swift, Drake,
    Sabrina Carpenter, Bruno Mars, Billie Eilish, etc.) instead of
    Jul, Ninho, GIMS et al.
    """
    cached = await cache.get("music:home:v6")
    if cached:
        return {"cached": True, "data": cached}

    async def _itunes_us(kind: str, limit: int = 25):
        """kind: 'topsongs' | 'topalbums' | 'newmusic'."""
        url = f"https://itunes.apple.com/us/rss/{kind}/limit={limit}/json"
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                r = await client.get(url, headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"})
                r.raise_for_status()
                entries = (r.json().get("feed") or {}).get("entry") or []
                out = []
                for e in entries:
                    title = ((e.get("im:name") or {}).get("label") or "").strip()
                    artist = ((e.get("im:artist") or {}).get("label") or "").strip()
                    if not title or not artist:
                        continue
                    out.append({"title": title, "artist": artist})
                return out
        except Exception as exc:
            log.warning("itunes %s failed: %s", kind, exc)
            return []

    async def _deezer_first_track(query: str):
        try:
            d = await _deezer_get("/search/track", {"q": query, "limit": 1})
            data = d.get("data") or []
            return _shape_track(data[0]) if data else None
        except Exception:
            return None

    async def _deezer_first_album(query: str):
        try:
            d = await _deezer_get("/search/album", {"q": query, "limit": 1})
            data = d.get("data") or []
            return _shape_album(data[0]) if data else None
        except Exception:
            return None

    async def _deezer_first_artist(name: str):
        try:
            d = await _deezer_get("/search/artist", {"q": name, "limit": 1})
            data = d.get("data") or []
            return _shape_artist(data[0]) if data else None
        except Exception:
            return None

    async def fetch_charts():
        items = await _itunes_us("topsongs", 25)
        results = await asyncio.gather(
            *[_deezer_first_track(f"{it['artist']} {it['title']}") for it in items[:25]]
        )
        return [t for t in results if t]

    async def fetch_new_releases():
        items = await _itunes_us("topalbums", 25)
        results = await asyncio.gather(
            *[_deezer_first_album(f"{it['artist']} {it['title']}") for it in items[:25]]
        )
        return [a for a in results if a]

    async def fetch_top_artists():
        # Dedupe artists from US Top Songs.
        items = await _itunes_us("topsongs", 50)
        seen = []
        seen_set = set()
        for it in items:
            a = it["artist"]
            if a.lower() in seen_set:
                continue
            seen.append(a)
            seen_set.add(a.lower())
            if len(seen) >= 20:
                break
        results = await asyncio.gather(*[_deezer_first_artist(a) for a in seen])
        return [a for a in results if a]

    async def fetch_genres():
        try:
            d = await _deezer_get("/genre", None)
            out = []
            for g in d.get("data") or []:
                gid = g.get("id")
                if gid in (None, 0):
                    continue
                out.append({
                    "id": str(gid),
                    "name": g.get("name") or "Unknown",
                    "picture": g.get("picture_xl") or g.get("picture_big"),
                })
            return out[:15]
        except Exception as exc:
            log.warning("genres fetch failed: %s", exc)
            return []

    charts, new_releases, top_artists, genres = await asyncio.gather(
        fetch_charts(), fetch_new_releases(), fetch_top_artists(),
        fetch_genres(),
    )

    data = {
        "shelves": [
            {"id": "top-tracks",  "title": "Top US Charts",       "type": "tracks",  "items": charts},
            {"id": "new-releases","title": "New Releases (US)",   "type": "albums",  "items": new_releases},
            {"id": "top-artists", "title": "Trending US Artists", "type": "artists", "items": top_artists},
        ],
        "genres": genres,
    }
    await cache.set("music:home:v6", data, ttl_seconds=3600)
    return {"cached": False, "data": data}


@music_api.get("/search")
async def music_search(q: str = Query(..., min_length=1, max_length=120)):
    """Fan-out search: hits Deezer (tracks/albums/artists) + Radio
    Browser + iTunes podcasts in parallel.  Returns a unified shape."""
    q_clean = q.strip()

    async def search_tracks():
        try:
            d = await _deezer_get("/search/track", {"q": q_clean, "limit": 25})
            return [_shape_track(t) for t in d.get("data") or []]
        except Exception:
            return []

    async def search_albums():
        try:
            d = await _deezer_get("/search/album", {"q": q_clean, "limit": 20})
            return [_shape_album(a) for a in d.get("data") or []]
        except Exception:
            return []

    async def search_artists():
        try:
            d = await _deezer_get("/search/artist", {"q": q_clean, "limit": 15})
            return [_shape_artist(a) for a in d.get("data") or []]
        except Exception:
            return []

    async def search_radio():
        try:
            server = await _get_radio_server()
            async with httpx.AsyncClient(timeout=8) as client:
                r = await client.get(
                    f"{server}/json/stations/search",
                    params={"name": q_clean, "limit": 15, "hidebroken": "true", "order": "votes", "reverse": "true"},
                    headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"},
                )
                r.raise_for_status()
                return [_shape_radio(s) for s in r.json()]
        except Exception:
            return []

    async def search_podcasts():
        try:
            async with httpx.AsyncClient(timeout=8) as client:
                r = await client.get(
                    f"{ITUNES_BASE}/search",
                    params={"term": q_clean, "media": "podcast", "limit": 15},
                )
                r.raise_for_status()
                return [_shape_podcast(p) for p in (r.json().get("results") or [])]
        except Exception:
            return []

    tracks, albums, artists, radio, podcasts = await asyncio.gather(
        search_tracks(), search_albums(), search_artists(),
        search_radio(), search_podcasts(),
    )

    return {
        "q": q_clean,
        "tracks": tracks,
        "albums": albums,
        "artists": artists,
        "radio": radio,
        "podcasts": podcasts,
    }


@music_api.get("/album/{album_id}")
async def music_album(album_id: str):
    """Album detail + full track list (with preview URLs)."""
    cache_key = f"music:album:{album_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    try:
        d = await _deezer_get(f"/album/{album_id}")
    except Exception as exc:
        raise HTTPException(404, f"album not found: {exc}")
    if d.get("error"):
        raise HTTPException(404, d["error"].get("message", "album not found"))
    tracks_raw = (d.get("tracks") or {}).get("data") or []
    # Album-level tracks sometimes omit album/artist nest — splice it in.
    album_short = {
        "id": d.get("id"),
        "title": d.get("title"),
        "cover_xl": d.get("cover_xl"),
        "cover_big": d.get("cover_big"),
        "cover_medium": d.get("cover_medium"),
    }
    artist_short = d.get("artist") or {}
    enriched = []
    for t in tracks_raw:
        t["album"] = album_short
        t["artist"] = artist_short
        enriched.append(_shape_track(t))
    out = {
        **_shape_album(d),
        "tracks": enriched,
    }
    await cache.set(cache_key, out, ttl_seconds=3600)
    return {"cached": False, "data": out}


@music_api.get("/artist/{artist_id}")
async def music_artist(artist_id: str):
    """Artist detail + top tracks + albums."""
    cache_key = f"music:artist:{artist_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    async def get_artist():
        return await _deezer_get(f"/artist/{artist_id}")

    async def get_top():
        d = await _deezer_get(f"/artist/{artist_id}/top", {"limit": 20})
        return [_shape_track(t) for t in d.get("data") or []]

    async def get_albums():
        d = await _deezer_get(f"/artist/{artist_id}/albums", {"limit": 30})
        return [_shape_album(a) for a in d.get("data") or []]

    try:
        a, top_tracks, albums = await asyncio.gather(get_artist(), get_top(), get_albums())
    except Exception as exc:
        raise HTTPException(404, f"artist not found: {exc}")
    if a.get("error"):
        raise HTTPException(404, a["error"].get("message", "artist not found"))
    out = {
        **_shape_artist(a),
        "top_tracks": top_tracks,
        "albums": albums,
    }
    await cache.set(cache_key, out, ttl_seconds=3600)
    return {"cached": False, "data": out}


@music_api.get("/genre/{genre_id}")
async def music_genre(genre_id: str):
    """All artists + radios in a genre — for the genre tiles."""
    cache_key = f"music:genre:{genre_id}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    async def get_artists():
        d = await _deezer_get(f"/genre/{genre_id}/artists")
        return [_shape_artist(a) for a in d.get("data") or []][:20]

    async def get_radios():
        d = await _deezer_get(f"/genre/{genre_id}/radios")
        return [{"id": str(r.get("id")), "title": r.get("title"), "cover": r.get("picture_xl") or r.get("picture_big")} for r in d.get("data") or []]

    try:
        artists, radios = await asyncio.gather(get_artists(), get_radios())
    except Exception:
        artists, radios = [], []
    out = {"genre_id": genre_id, "artists": artists, "radios": radios}
    await cache.set(cache_key, out, ttl_seconds=3600)
    return {"cached": False, "data": out}


# ════════════════════════════════════════════════════════════════════
#  Radio Browser (live radio stations worldwide)
# ════════════════════════════════════════════════════════════════════
def _shape_radio(s: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": s.get("stationuuid") or s.get("changeuuid"),
        "name": s.get("name") or "Unknown Station",
        "stream_url": s.get("url_resolved") or s.get("url"),
        "favicon": s.get("favicon") or None,
        "country": s.get("country") or "",
        "country_code": s.get("countrycode") or "",
        "language": s.get("language") or "",
        "tags": [t.strip() for t in (s.get("tags") or "").split(",") if t.strip()][:8],
        "codec": s.get("codec") or "",
        "bitrate": int(s.get("bitrate") or 0),
        "votes": int(s.get("votes") or 0),
        "homepage": s.get("homepage") or None,
    }


@music_api.get("/radio/top")
async def radio_top(country: Optional[str] = Query(None), limit: int = Query(50, le=200)):
    """Most-voted stations globally or by country code (e.g. AU, US, GB)."""
    cache_key = f"music:radio:top:{country or 'global'}:{limit}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    server = await _get_radio_server()
    params = {"limit": limit, "hidebroken": "true", "order": "votes", "reverse": "true"}
    path = "/json/stations/topvote"
    if country:
        path = f"/json/stations/bycountrycodeexact/{country.lower()}"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(f"{server}{path}", params=params, headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"})
            r.raise_for_status()
            # v2.8.63 — Filter to HTTPS-only streams.  Android WebView
            # blocks mixed-content (HTTP audio on an HTTPS page) so any
            # `http://…` stream would silently fail on the HK1 box —
            # the user reports stations "don't work".  This filter
            # guarantees every returned station is playable in the
            # WebView without proxying.  Also prefers stations whose
            # country code matches the requested country.
            stations_raw = r.json()
            def _is_https(s):
                url = s.get("url_resolved") or s.get("url") or ""
                return url.startswith("https://")
            stations = [_shape_radio(s) for s in stations_raw if _is_https(s)]
    except Exception as exc:
        log.warning("radio top failed: %s", exc)
        stations = []
    await cache.set(cache_key, stations, ttl_seconds=3600)
    return {"cached": False, "data": stations}


@music_api.get("/radio/genres")
async def radio_genres():
    """Returns the canonical list of radio genres (tags) with station counts."""
    cache_key = "music:radio:genres"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    server = await _get_radio_server()
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(f"{server}/json/tags", params={"order": "stationcount", "reverse": "true", "limit": 60, "hidebroken": "true"}, headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"})
            r.raise_for_status()
            out = [{"name": t.get("name"), "count": t.get("stationcount", 0)} for t in r.json() if t.get("name")]
    except Exception:
        out = []
    await cache.set(cache_key, out, ttl_seconds=86400)
    return {"cached": False, "data": out}


@music_api.get("/radio/by-tag/{tag}")
async def radio_by_tag(tag: str, limit: int = Query(50, le=200)):
    """Stations by genre/tag."""
    cache_key = f"music:radio:tag:{tag}:{limit}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    server = await _get_radio_server()
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(
                f"{server}/json/stations/bytagexact/{quote(tag)}",
                params={"limit": limit, "hidebroken": "true", "order": "votes", "reverse": "true"},
                headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"},
            )
            r.raise_for_status()
            # HTTPS-only filter — same reason as /radio/top.
            stations = [_shape_radio(s) for s in r.json()
                        if (s.get("url_resolved") or s.get("url") or "").startswith("https://")]
    except Exception:
        stations = []
    await cache.set(cache_key, stations, ttl_seconds=3600)
    return {"cached": False, "data": stations}


@music_api.get("/radio/countries")
async def radio_countries():
    """List of countries with station counts."""
    cache_key = "music:radio:countries"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    server = await _get_radio_server()
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(f"{server}/json/countries", params={"order": "stationcount", "reverse": "true", "limit": 50, "hidebroken": "true"}, headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"})
            r.raise_for_status()
            out = [{"name": c.get("name"), "code": c.get("iso_3166_1"), "count": c.get("stationcount", 0)} for c in r.json() if c.get("iso_3166_1")]
    except Exception:
        out = []
    await cache.set(cache_key, out, ttl_seconds=86400)
    return {"cached": False, "data": out}


@music_api.post("/radio/click/{station_id}")
async def radio_click(station_id: str):
    """Tell Radio Browser a station was played — drives their voting/popularity."""
    server = await _get_radio_server()
    try:
        async with httpx.AsyncClient(timeout=5) as client:
            await client.get(f"{server}/json/url/{station_id}", headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"})
    except Exception:
        pass
    return {"ok": True}


# ════════════════════════════════════════════════════════════════════
#  Lyrics — LRCLIB (free, open, synced lyrics)
# ════════════════════════════════════════════════════════════════════
#
# v2.8.60 — Powers the V2 Karaoke screen.  LRCLIB hosts the largest
# free database of community-contributed synced lyrics (`syncedLyrics`
# field, Netflix-style `[mm:ss.xx]Line` format).  Falls back to plain
# lyrics when no synced version exists — the karaoke UI auto-detects
# and shows a non-synced scrolling view instead.
#
# API: https://lrclib.net/docs

def _parse_lrc(synced: str) -> List[Dict[str, Any]]:
    """Parse `[mm:ss.xx]Line text\n…` into a sorted list of
    `{ "t": <seconds>, "text": "…" }` entries."""
    out: List[Dict[str, Any]] = []
    pattern = re.compile(r"\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?\](.*)")
    for raw in (synced or "").splitlines():
        m = pattern.match(raw.strip())
        if not m:
            continue
        mm, ss, frac, text = m.group(1), m.group(2), m.group(3), m.group(4)
        try:
            seconds = int(mm) * 60 + int(ss)
            if frac:
                # Pad to 3 digits so "5" → 500 ms, not 5 ms.
                f = int(frac.ljust(3, "0")[:3])
                seconds += f / 1000.0
        except ValueError:
            continue
        out.append({"t": round(seconds, 2), "text": text.strip()})
    out.sort(key=lambda e: e["t"])
    return out


@music_api.get("/lyrics")
async def music_lyrics(
    artist: str = Query(..., min_length=1, max_length=200),
    title: str  = Query(..., min_length=1, max_length=200),
    album: Optional[str] = Query(None),
    duration: Optional[int] = Query(None, description="Track length in seconds (helps disambiguate)"),
):
    """Returns synced + plain lyrics for an (artist, title) pair.

    Response shape:
        {
            "synced": [ { "t": 0.42, "text": "Hello, it's me" }, ... ],
            "plain":  "Hello, it's me\\nI was wondering...",
            "instrumental": false,
            "source": "lrclib"
        }
    `synced` is empty when LRCLIB doesn't have a synced copy; the
    karaoke UI degrades gracefully.
    """
    norm = f"{artist.strip().lower()}|{title.strip().lower()}"
    cache_key = f"music:lyrics:v1:{norm}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    # LRCLIB's `/api/get` endpoint matches against artist + track +
    # optional album + duration.  Returns 404 when nothing's found.
    params = {"artist_name": artist, "track_name": title}
    if album:
        params["album_name"] = album
    if duration:
        params["duration"] = int(duration)

    record = None
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(
                "https://lrclib.net/api/get",
                params=params,
                headers={"User-Agent": "ON-NOW-TV-Tunes/1.0 (+https://onnowtv.duckdns.org)"},
            )
            if r.status_code == 200:
                record = r.json()
            elif r.status_code == 404:
                # Fall back to the broader search endpoint — picks
                # the highest-rated match for the artist/title pair
                # without the duration constraint.
                rs = await client.get(
                    "https://lrclib.net/api/search",
                    params={"artist_name": artist, "track_name": title},
                    headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"},
                )
                if rs.status_code == 200:
                    results = rs.json() or []
                    if results:
                        record = results[0]
    except Exception as exc:  # noqa: BLE001
        log.warning("LRCLIB fetch failed for %s — %s: %s", artist, title, exc)

    if not record:
        result = {
            "synced": [],
            "plain": "",
            "instrumental": False,
            "source": "lrclib",
            "error": "not_found",
        }
        # Cache the miss for 1 h so we don't hammer LRCLIB for tracks
        # that consistently come up empty.
        await cache.set(cache_key, result, ttl_seconds=3600)
        return {"cached": False, "data": result}

    synced_raw = record.get("syncedLyrics") or ""
    plain_raw  = record.get("plainLyrics")  or ""
    is_instrumental = bool(record.get("instrumental"))

    result = {
        "synced":       _parse_lrc(synced_raw),
        "plain":        plain_raw,
        "instrumental": is_instrumental,
        "source":       "lrclib",
        "track_id":     record.get("id"),
    }
    # 7 days — lyrics rarely change once contributed.
    await cache.set(cache_key, result, ttl_seconds=7 * 24 * 3600)
    return {"cached": False, "data": result}


# ════════════════════════════════════════════════════════════════════
#  Podcasts (iTunes Search + RSS)
# ════════════════════════════════════════════════════════════════════
def _shape_podcast(p: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "id": str(p.get("collectionId") or p.get("trackId")),
        "title": p.get("collectionName") or p.get("trackName") or "Unknown",
        "artist": p.get("artistName") or "",
        "artwork": p.get("artworkUrl600") or p.get("artworkUrl100"),
        "feed_url": p.get("feedUrl"),
        "genre": p.get("primaryGenreName") or "",
        "episodes_count": int(p.get("trackCount") or 0),
        "country": p.get("country") or "",
    }


@music_api.get("/podcasts/top")
async def podcasts_top(country: str = Query("us"), genre: Optional[str] = None):
    """iTunes top podcasts in a country.  Returns up to 50."""
    cache_key = f"music:podcasts:top:{country}:{genre or 'all'}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    # iTunes top-podcasts RSS feed is the canonical way.
    url = f"https://itunes.apple.com/{country}/rss/toppodcasts/limit=50"
    if genre:
        url += f"/genre={genre}"
    url += "/json"
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(url)
            r.raise_for_status()
            feed = r.json()
            entries = ((feed.get("feed") or {}).get("entry") or [])
            # Look up each entry against the iTunes search endpoint to
            # get the canonical feedUrl that the RSS feed doesn't expose.
            lookup_ids = []
            shaped: List[Dict[str, Any]] = []
            for e in entries:
                pid = ((e.get("id") or {}).get("attributes") or {}).get("im:id")
                if pid:
                    lookup_ids.append(pid)
            if lookup_ids:
                lookup_url = f"{ITUNES_BASE}/lookup"
                params = {"id": ",".join(lookup_ids[:50]), "entity": "podcast"}
                lr = await client.get(lookup_url, params=params)
                if lr.status_code == 200:
                    for p in lr.json().get("results") or []:
                        if p.get("feedUrl"):
                            shaped.append(_shape_podcast(p))
    except Exception as exc:
        log.warning("podcasts/top failed: %s", exc)
        shaped = []
    await cache.set(cache_key, shaped, ttl_seconds=86400)
    return {"cached": False, "data": shaped}


@music_api.get("/podcasts/search")
async def podcasts_search(q: str = Query(..., min_length=1), limit: int = Query(20, le=50)):
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(
                f"{ITUNES_BASE}/search",
                params={"term": q, "media": "podcast", "limit": limit},
            )
            r.raise_for_status()
            return {"data": [_shape_podcast(p) for p in (r.json().get("results") or [])]}
    except Exception as exc:
        raise HTTPException(502, f"podcast search failed: {exc}")


@music_api.get("/podcasts/episodes")
async def podcast_episodes(feed_url: str = Query(...)):
    """Parse a podcast RSS feed and return episodes."""
    try:
        import feedparser  # lazy import — only loaded for podcasts
    except ImportError:
        raise HTTPException(500, "feedparser not installed")
    cache_key = f"music:podcast:episodes:{hash(feed_url)}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}
    try:
        async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
            r = await client.get(feed_url, headers={"User-Agent": "ON-NOW-TV-Tunes/1.0"})
            r.raise_for_status()
            text = r.text
    except Exception as exc:
        raise HTTPException(502, f"feed fetch failed: {exc}")
    parsed = feedparser.parse(text)
    podcast = {
        "title": parsed.feed.get("title") or "Unknown Podcast",
        "description": parsed.feed.get("description") or parsed.feed.get("subtitle") or "",
        "artwork": (parsed.feed.get("image") or {}).get("href") or (parsed.feed.get("itunes_image") or {}).get("href"),
        "author": parsed.feed.get("author") or parsed.feed.get("itunes_author") or "",
    }
    episodes = []
    for e in parsed.entries[:100]:
        audio_url = None
        for enc in (e.get("enclosures") or []):
            href = enc.get("href") or enc.get("url")
            ctype = (enc.get("type") or "").lower()
            if href and ("audio" in ctype or href.endswith((".mp3", ".m4a", ".aac"))):
                audio_url = href
                break
        if not audio_url:
            continue
        episodes.append({
            "id": e.get("id") or e.get("guid") or audio_url,
            "title": e.get("title") or "Untitled episode",
            "subtitle": e.get("itunes_subtitle") or e.get("subtitle") or "",
            "description": (e.get("summary") or e.get("description") or "")[:1500],
            "published": e.get("published") or "",
            "duration": e.get("itunes_duration") or "",
            "audio_url": audio_url,
            "artwork": (e.get("image") or {}).get("href") if isinstance(e.get("image"), dict) else None,
        })
    out = {"podcast": podcast, "episodes": episodes}
    await cache.set(cache_key, out, ttl_seconds=1800)
    return {"cached": False, "data": out}


# ════════════════════════════════════════════════════════════════════
#  YouTube full-track resolution — now on-device via NewPipeExtractor
# ════════════════════════════════════════════════════════════════════
#
# v2.12.0 (Feb 2026) — YouTube resolution moved 100 % onto the box.
#
# The Tunes Android app (`tv.onnowtv.tunes`) ships NewPipeExtractor
# and exposes a `window.OnNowTV.resolveYouTubeAudio(artist, title,
# cbId)` JS bridge that runs anonymous, cookieless scrapes from the
# box's residential IP.  Bytes stream direct from googlevideo.com
# CDN to the HTML5 `<audio>` element with zero backend involvement.
#
# Consequences:
#   • No more `YOUTUBE_COOKIES_DIR` / Netscape cookies.txt files.
#   • No more `yt-dlp` YouTube resolution on the backend.
#   • No more `/api/music/admin/cookies/*` endpoints.
#   • The `/api/music/stream/{track_id}` endpoint below now serves
#     only as a fallback for clients WITHOUT the native bridge
#     (browser preview, karaoke-mode retries, legacy APKs).  It
#     chains JioSaavn → Audius → Deezer preview.  YouTube is
#     handled exclusively by the native bridge on the Tunes app.


# ════════════════════════════════════════════════════════════════════
#  JioSaavn full-track resolver
# ════════════════════════════════════════════════════════════════════
#
# JioSaavn (https://www.jiosaavn.com) is a major Indian streaming
# service with a massive **mainstream catalog** — Adele, Taylor
# Swift, Drake, Bad Bunny, Coldplay, etc. — and a public API that
# returns DES-encrypted CDN URLs.  Decryption uses the well-known
# fixed key `38346591` in DES-ECB + PKCS5 padding.  The decrypted
# URL is in `_96.mp4` form; we upgrade to `_320.mp4` for HQ audio
# (m4a / AAC, ~8 MB per song).
#
# Bytes stream direct from `saavncdn.com` to the client — our VPS
# only resolves the URL.  No bandwidth burden, no proxy.

_SAAVN_KEY = b"38346591"


def _saavn_decrypt(emu: str) -> Optional[str]:
    """DES-ECB decrypt a JioSaavn encrypted_media_url to a playable URL."""
    try:
        import base64
        from Crypto.Cipher import DES  # pycryptodome
    except ImportError:
        return None
    try:
        raw = base64.b64decode(emu)
        if len(raw) % 8:
            return None
        cipher = DES.new(_SAAVN_KEY, DES.MODE_ECB)
        plain = cipher.decrypt(raw)
        # PKCS5 unpad if the last byte looks like padding
        p = plain[-1]
        if 0 < p <= 8 and plain.endswith(bytes([p]) * p):
            plain = plain[:-p]
        return plain.decode("utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        return None


async def _jiosaavn_resolve(artist: str, title: str) -> Optional[Dict[str, Any]]:
    """Search JioSaavn for the track and return a streamable URL."""
    q = f"{artist} {title}".strip()
    if not q:
        return None
    params = {
        "p": "1",
        "q": q,
        "_format": "json",
        "_marker": "0",
        "api_version": "4",
        "ctx": "web6dot0",
        "__call": "search.getResults",
    }
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                      "AppleWebKit/537.36 (KHTML, like Gecko) "
                      "Chrome/120.0.0.0 Safari/537.36",
        "Accept": "application/json",
        "Referer": "https://www.jiosaavn.com/",
    }
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(
                "https://www.jiosaavn.com/api.php",
                params=params,
                headers=headers,
            )
            r.raise_for_status()
            data = r.json()
    except Exception as exc:  # noqa: BLE001
        log.warning("JioSaavn search failed: %s", exc)
        return None

    results = (data.get("results") or [])
    if not results:
        return None

    # Pick the best match: prefer hits with the artist name in the
    # "primary_artists" field AND a clean title (no Nightcore/Karaoke
    # /Tribute/Cover variants).  JioSaavn ranks by popularity but
    # remix/karaoke uploads sometimes outrank the original on
    # less-popular tracks.
    BAD_VARIANT_TAGS = (
        "nightcore", "karaoke", "instrumental", "tribute",
        "originally performed by", "originally perfomed by",
        "in the style of", "made famous by", "tributo a",
        "lo-fi", "lofi", "8d audio", "slowed", "reverb",
        "tribute version", "melody karaoke", "remix",
        "(cover)", " cover)", "cover version",
        "acoustic version", "acapella",
    )
    artist_l = (artist or "").lower().strip()
    title_l  = (title  or "").lower().strip()

    chosen = None
    for r in results[:15]:
        mi = r.get("more_info") or {}
        # JioSaavn primary artists live under `more_info.artistMap.primary_artists[].name`
        # NOT under `more_info.primary_artists` (which is often empty).
        am = mi.get("artistMap") or {}
        pa_list = am.get("primary_artists") or []
        pa_l = " ".join(
            str(a.get("name") or "").lower() for a in pa_list
        ) if isinstance(pa_list, list) else ""
        # Also check the `subtitle` field — JioSaavn sometimes
        # includes the artist there as a free-form string.
        sub_l = (r.get("subtitle") or "").lower()
        r_title = (r.get("title") or "").lower()

        # Reject variant uploads.
        if any(tag in r_title for tag in BAD_VARIANT_TAGS):
            continue
        if any(tag in sub_l for tag in BAD_VARIANT_TAGS):
            continue

        artist_ok = (not artist_l) or any(
            (w in pa_l or w in sub_l)
            for w in artist_l.split() if len(w) > 2
        )
        title_ok = (not title_l) or all(
            w in r_title for w in title_l.split() if len(w) > 2
        )
        if artist_ok and title_ok:
            chosen = r
            break

    # No clean variant matched — bail to preview (better to play 30 s
    # of the real song than 4 min of a Karaoke version).
    if chosen is None:
        return None

    mi = chosen.get("more_info") or {}
    emu = mi.get("encrypted_media_url")
    if not emu:
        return None

    url96 = _saavn_decrypt(emu)
    if not url96 or "saavncdn" not in url96:
        return None
    # Upgrade default _96.mp4 → _320.mp4 for HQ streaming.
    url320 = url96.replace("_96.mp4", "_320.mp4")

    # Pull artist + image from the canonical artistMap field.
    am = mi.get("artistMap") or {}
    pa_list = am.get("primary_artists") or []
    if isinstance(pa_list, list) and pa_list:
        primary_artist = ", ".join(
            str(a.get("name") or "") for a in pa_list if a.get("name")
        )
    else:
        primary_artist = chosen.get("subtitle") or ""

    return {
        "url": url320,
        "duration": int(mi.get("duration") or 0) or None,
        "title": chosen.get("title"),
        "primary_artist": primary_artist,
        "image": chosen.get("image"),
    }


# ════════════════════════════════════════════════════════════════════
#  Full-track stream resolver (browser/legacy fallback)
# ════════════════════════════════════════════════════════════════════
#
# Resolver chain (v2.12.0, Feb 2026):
#
#   0. **YouTube via native NewPipeExtractor bridge** — handled
#      client-side by the Tunes APK (`window.OnNowTV.resolveYouTubeAudio`).
#      The React `musicResolver.js` tries this FIRST and never
#      reaches this endpoint for YouTube resolution.  The endpoint
#      below is what the client calls when the bridge is absent
#      (browser preview / legacy APK / karaoke iframe path).
#
#   1. **JioSaavn** — Indian streaming service with a surprisingly
#      broad Western mainstream catalog at 320 kbps m4a.  Bytes
#      stream direct from saavncdn.com.
#
#   2. **Audius** — decentralized music network.  Mostly indie /
#      remix / electronic.  Quality-gated so covers / karaoke /
#      "lyrics" uploads are rejected.
#
#   3. **Deezer 30-second preview** — the client already has this
#      URL on every track; we return `source: "preview"` to signal it
#      should play the preview instead.
#
# Bytes always stream DIRECT from the source CDN to the client — our
# VPS only resolves the URL.  Zero bandwidth burden on Contabo.
@music_api.get("/stream/{track_id}")
async def music_stream(
    track_id: str,
    artist: str = Query(..., min_length=1, max_length=200),
    title: str = Query(..., min_length=1, max_length=200),
):
    norm = f"{artist.strip().lower()}|{title.strip().lower()}"
    cache_key = f"music:stream:v5:{norm}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    # 1) JioSaavn — mainstream western + Indian catalog, direct CDN.
    jiosaavn_url = await _jiosaavn_resolve(artist, title)
    if jiosaavn_url:
        result = {
            "stream_url": jiosaavn_url["url"],
            "duration": jiosaavn_url.get("duration"),
            "title": jiosaavn_url.get("title"),
            "uploader": jiosaavn_url.get("primary_artist"),
            "artwork": jiosaavn_url.get("image"),
            "source": "jiosaavn",
            "is_full_track": True,
        }
        await cache.set(cache_key, result, ttl_seconds=4 * 3600)
        return {"cached": False, "data": result}

    # 2) Audius — last-ditch indie fallback (quality-gated below).
    audius_host = "https://api.audius.co"
    search_q = f"{artist} {title}"

    async with httpx.AsyncClient(timeout=10, follow_redirects=False) as client:
        try:
            r = await client.get(
                f"{audius_host}/v1/tracks/search",
                params={"query": search_q, "app_name": "ONNOWTUNES"},
            )
            r.raise_for_status()
            results = (r.json() or {}).get("data") or []
        except Exception as exc:  # noqa: BLE001
            log.warning("Audius search failed: %s", exc)
            results = []

    if results:
        # Quality gate: Audius hosts a lot of covers / remixes /
        # bootleg uploads.  We only present a result as a "Full
        # track" if it looks like the OFFICIAL version (artist name
        # matches the uploader and the title isn't tagged as a
        # cover/remix/etc.).  Otherwise we fall through to the
        # preview-only response so the user doesn't get tricked
        # into thinking they're hearing the real Adele.
        BLOCKED_TAGS = ("cover", "remix", "freestyle", "bootleg",
                        "mashup", "karaoke", "instrumental", "tribute",
                        "(lyrics)", "(visualizer)", "type beat")
        artist_l = artist.strip().lower()
        title_l = title.strip().lower()

        official = None
        for t in results[:20]:
            t_title = (t.get("title") or "").lower()
            t_user  = ((t.get("user") or {}).get("name") or "").lower()
            t_verified = bool((t.get("user") or {}).get("is_verified"))
            # Reject obvious covers/remixes
            if any(tag in t_title for tag in BLOCKED_TAGS):
                continue
            # Title must contain (most of) the requested track title
            # — single-word search terms are too noisy.
            title_match = title_l in t_title or all(
                w in t_title for w in title_l.split() if len(w) > 2
            )
            if not title_match:
                continue
            # Bonus: uploader name should somehow relate to the
            # requested artist.  Be lenient — strict equality is too
            # restrictive (cdg/CDG label uploads, etc.).
            artist_match = (
                t_verified
                or artist_l in t_user
                or t_user in artist_l
                or any(w in t_user for w in artist_l.split() if len(w) > 2)
            )
            if artist_match:
                official = t
                break

        if official:
            result = {
                "stream_url": (
                    f"{audius_host}/v1/tracks/{official['id']}/stream"
                    f"?app_name=ONNOWTUNES"
                ),
                "duration": official.get("duration"),
                "title": official.get("title"),
                "uploader": (official.get("user") or {}).get("name"),
                "artwork": (official.get("artwork") or {}).get("480x480"),
                "source": "audius",
                "is_full_track": True,
            }
            await cache.set(cache_key, result, ttl_seconds=4 * 3600)
            return {"cached": False, "data": result}

    # 4) No full-track match anywhere — explicit signal so the client
    # falls back to its already-loaded 30-second Deezer preview.
    # Cache briefly so we don't hammer the upstream APIs for queries
    # that consistently come up empty.
    result = {
        "stream_url": None,
        "source": "preview",
        "is_full_track": False,
        "reason": "no full-track match found on JioSaavn / Audius",
    }
    await cache.set(cache_key, result, ttl_seconds=15 * 60)
    return {"cached": False, "data": result}


# ════════════════════════════════════════════════════════════════════
#  (Removed v2.12.0) Admin — YouTube cookies management
# ════════════════════════════════════════════════════════════════════
#
# Cookie-based YouTube resolution was replaced by the on-device
# NewPipeExtractor bridge in the Tunes APK (v2.12.0).  The following
# endpoints no longer exist:
#   • GET    /api/music/admin/cookies/status
#   • POST   /api/music/admin/cookies/upload
#   • DELETE /api/music/admin/cookies/{name}
#   • POST   /api/music/admin/cookies/test
# The `_youtube_resolve`, `_pick_cookie_file`, `_cookie_file_summary`
# and related helpers were removed along with the `YOUTUBE_COOKIES_DIR`
# environment variable + `youtube-cookies/` directory usage.


# ============================================================================
# Public YouTube SEARCH endpoint — no cookies required
# ----------------------------------------------------------------------------
# Returns the top YouTube video ID for a given query.  The IFrame Player in
# the React frontend can then play it (full track, may show ads on free
# accounts, but works WITHOUT needing the user's YouTube cookies on the VPS).
#
# This is the reliable Karaoke playback path that works on EVERY platform
# (desktop, mobile, HK1 APK) regardless of whether the native NewPipe
# bridge is present.
# ============================================================================

@music_api.get("/yt-search")
async def yt_search(q: str = Query(..., min_length=1), karaoke: bool = False):
    """Returns `{ yt_id, title?, uploader? }` for the top search hit.

    Uses yt-dlp's `extract_flat=True` mode so we only need the video ID,
    not the underlying signed CDN URL — which means **no cookies are
    required** and the request is reliably fast (~1-2 s).

    The frontend plays the returned `yt_id` via the YouTube IFrame
    Player API in `MiniPlayer`/`KaraokeStage`.

    v2.8.87 — Added `karaoke=true` flag that biases result selection
    toward known karaoke-only channels (Sing King, KaraFun, Musisi
    Karaoke, etc.).  YouTube's default ranking is "studio original
    first" even when "karaoke" / "instrumental" is in the query, so
    without this filter the user keeps hearing vocals.
    """
    cache_key = f"yt-search:{q}:karaoke={int(karaoke)}"
    cached = await cache.get(cache_key)
    if cached:
        return {"cached": True, "data": cached}

    try:
        import yt_dlp  # type: ignore
    except ImportError:
        return {"cached": False, "data": {"yt_id": None, "error": "yt-dlp not installed"}}

    # Channels that ONLY upload instrumental karaoke versions.  When
    # any of these names appear in the uploader / channel field, the
    # entry is treated as a high-confidence karaoke hit.
    KARAOKE_CHANNELS = (
        "sing king", "karafun", "musisi karaoke", "stingray karaoke",
        "zoom karaoke", "atomic karaoke", "atomic karoke", "ameritz",
        "karaoke version", "karaoke mugen", "the karaoke channel",
        "piano karaoke", "ekaraoke", "kfn karaoke",
    )
    # Title tokens that signal an instrumental track when paired with
    # the song name.  Used as a softer secondary filter.
    KARAOKE_TITLE_TOKENS = (
        "karaoke", "instrumental", "minus one", "backing track",
        "off vocal", "no vocals",
    )

    def _is_karaoke_hit(entry: dict) -> bool:
        title = (entry.get("title") or "").lower()
        uploader = (entry.get("uploader") or entry.get("channel") or "").lower()
        if any(ch in uploader for ch in KARAOKE_CHANNELS):
            return True
        # Must mention an instrumental token AND not be the official
        # video.  This catches "Hello (Karaoke Version)" by random
        # uploaders while excluding "Hello (Live)" / "Hello (Lyrics)"
        # which often still have vocals.
        if any(tok in title for tok in KARAOKE_TITLE_TOKENS):
            if "official" not in title and "live" not in title:
                return True
        return False

    def search_blocking():
        opts = {
            "quiet": True,
            "skip_download": True,
            "no_warnings": True,
            "extract_flat": True,
            "socket_timeout": 8,
            "geo_bypass": True,
        }
        # Karaoke mode pulls the top 12 results then re-ranks; regular
        # mode just grabs the top 1 (fast path).
        search_term = f"ytsearch{12 if karaoke else 1}:{q}"
        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(search_term, download=False)
                if not info:
                    return None
                entries = info.get("entries") or [info]
                if not entries:
                    return None
                pick = entries[0]
                if karaoke:
                    # Prefer the first hit from a known karaoke channel;
                    # otherwise the first hit with a karaoke-y title;
                    # otherwise just the top-ranked result.
                    channel_hit = next(
                        (e for e in entries
                         if any(ch in ((e.get("uploader") or e.get("channel") or "").lower())
                                for ch in KARAOKE_CHANNELS)),
                        None,
                    )
                    if channel_hit:
                        pick = channel_hit
                    else:
                        soft_hit = next((e for e in entries if _is_karaoke_hit(e)), None)
                        if soft_hit:
                            pick = soft_hit
                return {
                    "yt_id": pick.get("id"),
                    "title": pick.get("title"),
                    "uploader": pick.get("uploader") or pick.get("channel"),
                    "duration": pick.get("duration"),
                    "karaoke_confident": _is_karaoke_hit(pick) if karaoke else False,
                }
        except Exception as exc:
            log.warning("yt-search failed for %s: %s", q, exc)
            return {"yt_id": None, "error": str(exc)[:200]}

    result = await asyncio.to_thread(search_blocking)
    if result and result.get("yt_id"):
        await cache.set(cache_key, result, ttl_seconds=86400)  # 24 h
    return {"cached": False, "data": result or {"yt_id": None}}


# ════════════════════════════════════════════════════════════════════
#  Health
# ════════════════════════════════════════════════════════════════════
@music_api.get("/ping")
async def music_ping():
    return {"ok": True, "service": "music_api", "build": "v0.3.0"}

