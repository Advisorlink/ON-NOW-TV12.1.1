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
    charts + featured playlists.  Cached 1 h."""
    cached = await cache.get("music:home:v2")
    if cached:
        return {"cached": True, "data": cached}

    async def fetch_charts():
        try:
            d = await _deezer_get("/chart/0/tracks", {"limit": 25})
            return [_shape_track(t) for t in d.get("data") or []]
        except Exception as exc:
            log.warning("charts fetch failed: %s", exc)
            return []

    async def fetch_new_releases():
        try:
            d = await _deezer_get("/editorial/0/releases", {"limit": 25})
            return [_shape_album(a) for a in d.get("data") or []]
        except Exception as exc:
            log.warning("new releases fetch failed: %s", exc)
            return []

    async def fetch_top_artists():
        try:
            d = await _deezer_get("/chart/0/artists", {"limit": 20})
            return [_shape_artist(a) for a in d.get("data") or []]
        except Exception as exc:
            log.warning("top artists fetch failed: %s", exc)
            return []

    async def fetch_top_albums():
        try:
            d = await _deezer_get("/chart/0/albums", {"limit": 20})
            return [_shape_album(a) for a in d.get("data") or []]
        except Exception as exc:
            log.warning("top albums fetch failed: %s", exc)
            return []

    async def fetch_genres():
        try:
            d = await _deezer_get("/genre", None)
            out = []
            for g in d.get("data") or []:
                gid = g.get("id")
                if gid in (None, 0):
                    continue  # skip "All" genre
                out.append({
                    "id": str(gid),
                    "name": g.get("name") or "Unknown",
                    "picture": g.get("picture_xl") or g.get("picture_big"),
                })
            return out[:15]
        except Exception as exc:
            log.warning("genres fetch failed: %s", exc)
            return []

    charts, new_releases, top_artists, top_albums, genres = await asyncio.gather(
        fetch_charts(), fetch_new_releases(), fetch_top_artists(),
        fetch_top_albums(), fetch_genres(),
    )

    data = {
        "shelves": [
            {"id": "top-tracks",  "title": "Top Charts",     "type": "tracks",  "items": charts},
            {"id": "new-releases","title": "New Releases",   "type": "albums",  "items": new_releases},
            {"id": "top-artists", "title": "Trending Artists","type": "artists", "items": top_artists},
            {"id": "top-albums",  "title": "Top Albums",     "type": "albums",  "items": top_albums},
        ],
        "genres": genres,
    }
    await cache.set("music:home:v2", data, ttl_seconds=3600)
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
            stations = [_shape_radio(s) for s in r.json() if s.get("url_resolved") or s.get("url")]
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
            stations = [_shape_radio(s) for s in r.json() if s.get("url_resolved") or s.get("url")]
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
#  Health
# ════════════════════════════════════════════════════════════════════
@music_api.get("/ping")
async def music_ping():
    return {"ok": True, "service": "music_api", "build": "v0.1.0"}
