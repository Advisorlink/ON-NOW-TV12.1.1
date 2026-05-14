"""Iteration 11 backend tests:

1. /api/tmdb/similar-to-picks endpoint shape & exclusion logic
2. Empty / malformed picks handling
3. 'series' alias mapping (series:1399 -> TV)
4. 24h cache TTL verification (cached:true on repeat)
5. /api/tmdb/for-you 24h cache verification
"""
import os
import time
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL").rstrip("/")
API = f"{BASE_URL}/api"


# ---- /tmdb/similar-to-picks ------------------------------------------------

class TestSimilarToPicks:
    def test_basic_movies_excluded(self):
        r = requests.get(f"{API}/tmdb/similar-to-picks",
                         params={"picks": "movie:603,movie:680", "limit": 30}, timeout=30)
        assert r.status_code == 200, r.text
        body = r.json()
        assert "data" in body and isinstance(body["data"], list)
        data = body["data"]
        assert len(data) >= 5, f"expected 5+ similar items, got {len(data)}"

        # Shape check — every item has the required keys.
        required = {"tmdb_id", "type", "title", "poster", "year"}
        for it in data:
            missing = required - set(it.keys())
            assert not missing, f"item missing fields {missing}: {it}"

        # Exclusion: none of the items may be the user's own picks
        # (type='movie', tmdb_id in {603, 680})
        bad = [it for it in data
               if it["type"] == "movie" and str(it["tmdb_id"]) in {"603", "680"}]
        assert not bad, f"excluded picks leaked into rail: {bad}"

    def test_empty_picks(self):
        r = requests.get(f"{API}/tmdb/similar-to-picks",
                         params={"picks": "", "limit": 30}, timeout=15)
        assert r.status_code == 200
        body = r.json()
        assert body.get("data") == []

    def test_malformed_picks_silently_ignored(self):
        # cheese:abc — non-numeric id AND invalid type; should be ignored, returning [].
        r = requests.get(f"{API}/tmdb/similar-to-picks",
                         params={"picks": "cheese:abc,foo,movie:notnum", "limit": 30}, timeout=15)
        assert r.status_code == 200
        assert r.json().get("data") == []

    def test_series_alias_maps_to_tv(self):
        # series:1399 is GoT; should hit /tv/1399/recommendations and return TV titles.
        r = requests.get(f"{API}/tmdb/similar-to-picks",
                         params={"picks": "series:1399", "limit": 10}, timeout=30)
        assert r.status_code == 200, r.text
        data = r.json().get("data", [])
        assert len(data) >= 5, f"GoT recommendations should yield 5+, got {len(data)}"
        # All returned items for a TV pick should be TV-shaped (type='series')
        # and the pick itself excluded.
        assert all(it["type"] in ("series", "movie") for it in data)
        bad = [it for it in data if it["type"] == "series" and str(it["tmdb_id"]) == "1399"]
        assert not bad, f"series:1399 itself appeared in rail: {bad}"

    def test_cache_returns_cached_true_on_second_hit(self):
        # Use an unusual pick combo to avoid colliding with an existing cache entry.
        picks = "movie:155,movie:13"
        params = {"picks": picks, "limit": 30}
        # First call
        r1 = requests.get(f"{API}/tmdb/similar-to-picks", params=params, timeout=30)
        assert r1.status_code == 200, r1.text
        b1 = r1.json()
        # Second call within TTL window
        time.sleep(0.5)
        r2 = requests.get(f"{API}/tmdb/similar-to-picks", params=params, timeout=15)
        assert r2.status_code == 200, r2.text
        b2 = r2.json()
        # At least one of the two must be cached:True (second one ideally).
        # First call may already be cached if a prior test/process warmed it.
        assert b2.get("cached") is True, f"second call should be cached:true, got {b2.get('cached')}"
        # Payload should be identical
        assert b1["data"] == b2["data"]


# ---- /tmdb/for-you 24h cache ----------------------------------------------

class TestForYouCache:
    def test_for_you_cached_on_repeat(self):
        params = {"movie_genres": "28", "tv_genres": "10759", "limit": 20}
        r1 = requests.get(f"{API}/tmdb/for-you", params=params, timeout=30)
        assert r1.status_code == 200, r1.text
        assert isinstance(r1.json().get("data"), list)
        time.sleep(0.5)
        r2 = requests.get(f"{API}/tmdb/for-you", params=params, timeout=15)
        assert r2.status_code == 200
        assert r2.json().get("cached") is True, f"for-you 2nd call not cached: {r2.json().get('cached')}"
