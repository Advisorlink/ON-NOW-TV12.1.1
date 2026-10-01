"""Backend tests for Vesper v1.5.0 Anime hub (iteration 97).

Covers:
- GET /api/tmdb/anime (rails hub, cached:true on second call)
- GET /api/tmdb/by-genres/{media_type}?genre_ids=-8 (synthetic anime discover)
- GET /api/tmdb/custom-row?q=anime (synthetic row with matched.media [movie,tv])
"""
import os
import time
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")

REQUIRED_RAIL_IDS = {
    "trending-series", "popular-movies", "airing-now", "top-series", "top-movies",
    "ghibli", "action", "comedy", "kids", "classics",
}


@pytest.fixture(scope="module")
def anime_hub_first():
    # Cold call can take up to ~12s per request spec
    r = requests.get(f"{BASE_URL}/api/tmdb/anime", params={"limit": 40}, timeout=90)
    return r


def test_anime_hub_200(anime_hub_first):
    assert anime_hub_first.status_code == 200, anime_hub_first.text[:500]


def test_anime_hub_shape(anime_hub_first):
    data = anime_hub_first.json()
    assert "rails" in data and isinstance(data["rails"], list)
    assert len(data["rails"]) >= 14, f"got {len(data['rails'])} rails"


def test_anime_hub_rail_ids(anime_hub_first):
    rails = anime_hub_first.json()["rails"]
    ids = {r.get("id") for r in rails}
    missing = REQUIRED_RAIL_IDS - ids
    assert not missing, f"missing rail ids: {missing}; got {ids}"


def test_anime_hub_rail_fields(anime_hub_first):
    rails = anime_hub_first.json()["rails"]
    for rail in rails:
        assert rail.get("id"), rail
        assert rail.get("title"), rail
        assert rail.get("eyebrow"), rail
        data = rail.get("data") or []
        assert len(data) >= 6, f"rail {rail.get('id')} has {len(data)} items"
        for item in data[:3]:
            assert item.get("tmdb_id"), item
            assert item.get("type") in ("movie", "tv", "series"), item
            assert item.get("title"), item
            assert "poster" in item, item


def test_anime_hub_cached_second_call():
    t0 = time.time()
    r = requests.get(f"{BASE_URL}/api/tmdb/anime", params={"limit": 40}, timeout=30)
    elapsed = time.time() - t0
    assert r.status_code == 200
    data = r.json()
    assert data.get("cached") is True, f"second call not cached: keys={list(data.keys())[:6]}"
    assert elapsed < 10, f"cached call took {elapsed:.1f}s"


def test_by_genres_tv_anime_sentinel():
    r = requests.get(f"{BASE_URL}/api/tmdb/by-genres/tv",
                     params={"genre_ids": "-8", "limit": 40}, timeout=60)
    assert r.status_code == 200, r.text[:300]
    data = r.json()
    items = data.get("data") if isinstance(data, dict) else data
    assert isinstance(items, list)
    assert len(items) >= 20, f"only {len(items)} tv anime items"


def test_by_genres_movie_anime_sentinel_has_spirited_away():
    r = requests.get(f"{BASE_URL}/api/tmdb/by-genres/movie",
                     params={"genre_ids": "-8", "limit": 40}, timeout=60)
    assert r.status_code == 200
    data = r.json()
    items = data.get("data") if isinstance(data, dict) else data
    assert isinstance(items, list) and len(items) >= 20
    titles = " | ".join((it.get("title") or it.get("name") or "") for it in items[:60]).lower()
    assert "spirited" in titles, f"Spirited Away not in first 60 anime movies; sample: {titles[:400]}"


def test_custom_row_anime_synthetic():
    r = requests.get(f"{BASE_URL}/api/tmdb/custom-row", params={"q": "anime"}, timeout=30)
    assert r.status_code == 200, r.text[:300]
    data = r.json()
    assert data.get("label", "").lower() == "anime", data.get("label")
    matched = data.get("matched") or {}
    media = set(matched.get("media") or [])
    assert {"movie", "tv"}.issubset(media), f"matched.media={media}"
