"""Iteration 8 polish: networks/logos w300 + by-genre limit=20."""
import os
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL").rstrip("/")
API = f"{BASE_URL}/api"


@pytest.fixture(scope="module")
def s():
    return requests.Session()


# /api/networks/logos — every logo must be w300
def test_networks_logos_uses_w300(s):
    r = s.get(f"{API}/networks/logos", timeout=30)
    assert r.status_code == 200, r.text
    body = r.json()
    assert "data" in body
    entries = body["data"]
    # Endpoint returns a dict {slug: {logo, name}, ...}
    if isinstance(entries, dict):
        items = [(slug, v) for slug, v in entries.items()]
    else:
        items = [(e.get("slug"), e) for e in entries]
    assert len(items) > 0

    logos = [(slug, v) for slug, v in items if v.get("logo")]
    assert len(logos) >= 1, "no entries had a logo"
    for slug, v in logos:
        url = v["logo"]
        assert "/t/p/w300/" in url, f"{slug} logo not w300: {url}"
        assert "/t/p/original/" not in url, f"{slug} still original: {url}"


# /api/tmdb/by-genre/movie/28?limit=20 — returns up to 20 shaped items
def test_by_genre_movie_action_limit_20(s):
    r = s.get(f"{API}/tmdb/by-genre/movie/28", params={"limit": 20}, timeout=30)
    assert r.status_code == 200, r.text
    body = r.json()
    assert "data" in body
    items = body["data"]
    assert isinstance(items, list)
    # TMDB first-page typically returns 20 results
    assert len(items) >= 10, f"only {len(items)} items"
    assert len(items) <= 20
    first = items[0]
    for key in ("tmdb_id", "title", "poster"):
        assert key in first, f"missing key {key} in {first}"
    assert isinstance(first["tmdb_id"], int)
    assert isinstance(first["title"], str) and first["title"]
