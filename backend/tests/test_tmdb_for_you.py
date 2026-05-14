"""Backend tests for the new TMDB endpoints introduced in iteration 7:
- /api/tmdb/genres/{media}
- /api/tmdb/by-genre/{media}/{genre_id}
- /api/tmdb/for-you
"""
import os
import requests
import pytest


def _base_url():
    # Read from frontend .env to match what the UI actually hits.
    env_path = "/app/frontend/.env"
    if os.path.exists(env_path):
        with open(env_path) as fh:
            for line in fh:
                if line.startswith("REACT_APP_BACKEND_URL="):
                    return line.split("=", 1)[1].strip().rstrip("/")
    raise RuntimeError("REACT_APP_BACKEND_URL not found")


BASE = _base_url()


# ---- /tmdb/genres/{media} -----------------------------------------------
class TestTmdbGenres:
    def test_movie_genres(self):
        r = requests.get(f"{BASE}/api/tmdb/genres/movie", timeout=20)
        assert r.status_code == 200
        body = r.json()
        assert "cached" in body
        assert isinstance(body["data"], list) and len(body["data"]) > 0
        first = body["data"][0]
        assert "id" in first and "name" in first
        assert isinstance(first["id"], int)
        assert isinstance(first["name"], str)

    def test_tv_genres(self):
        r = requests.get(f"{BASE}/api/tmdb/genres/tv", timeout=20)
        assert r.status_code == 200
        body = r.json()
        assert isinstance(body["data"], list) and len(body["data"]) > 0
        names = [g["name"] for g in body["data"]]
        # TV genres usually include "Drama"
        assert any("Drama" in n for n in names)

    def test_invalid_media(self):
        r = requests.get(f"{BASE}/api/tmdb/genres/blah", timeout=10)
        assert r.status_code == 400


# ---- /tmdb/by-genre/{media}/{genre_id} ----------------------------------
class TestTmdbByGenre:
    def test_movie_action_limit_5(self):
        r = requests.get(f"{BASE}/api/tmdb/by-genre/movie/28?limit=5", timeout=20)
        assert r.status_code == 200
        body = r.json()
        items = body["data"]
        assert isinstance(items, list)
        assert len(items) == 5
        for it in items:
            assert it.get("type") == "movie"
            for k in ("tmdb_id", "title", "poster", "year"):
                assert k in it, f"missing {k} in {it}"

    def test_tv_drama_limit_5(self):
        r = requests.get(f"{BASE}/api/tmdb/by-genre/tv/18?limit=5", timeout=20)
        assert r.status_code == 200
        body = r.json()
        items = body["data"]
        assert isinstance(items, list)
        assert len(items) == 5
        for it in items:
            assert it.get("type") == "series"
            assert "tmdb_id" in it and "title" in it

    def test_invalid_media(self):
        r = requests.get(f"{BASE}/api/tmdb/by-genre/blah/28", timeout=10)
        assert r.status_code == 400


# ---- /tmdb/for-you -------------------------------------------------------
class TestTmdbForYou:
    def test_mixed_movies_and_tv(self):
        r = requests.get(
            f"{BASE}/api/tmdb/for-you?movie_genres=28,878&tv_genres=18&limit=10",
            timeout=25,
        )
        assert r.status_code == 200
        body = r.json()
        items = body["data"]
        assert isinstance(items, list)
        assert len(items) <= 10
        assert len(items) > 0
        types = {it.get("type") for it in items}
        assert "movie" in types
        assert "series" in types

    def test_empty_params(self):
        r = requests.get(f"{BASE}/api/tmdb/for-you", timeout=10)
        assert r.status_code == 200
        body = r.json()
        assert body["data"] == []

    def test_only_movies(self):
        r = requests.get(
            f"{BASE}/api/tmdb/for-you?movie_genres=28&limit=5", timeout=20
        )
        assert r.status_code == 200
        items = r.json()["data"]
        assert len(items) <= 5
        assert all(it["type"] == "movie" for it in items)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
