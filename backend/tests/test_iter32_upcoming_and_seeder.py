"""Iter 32 — Backend tests for:
1. GET /api/tmdb/upcoming-movies (limit, days, fields, imdb_id best-effort)
2. GET /api/streams/{type}/{id} (notify-scanner endpoint must not 500)
3. GET /api/addons (Cinemeta + OpenSubtitles + WatchHub seeded; Torrentio optional in preview)
"""
import os
import re
from datetime import date

import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "").rstrip("/")
assert BASE_URL, "REACT_APP_BACKEND_URL must be set in env"
API = f"{BASE_URL}/api"


@pytest.fixture(scope="module")
def s():
    sess = requests.Session()
    sess.headers.update({"Content-Type": "application/json"})
    return sess


# ----- /api/tmdb/upcoming-movies -----
class TestUpcomingMovies:
    def test_basic_shape(self, s):
        r = s.get(f"{API}/tmdb/upcoming-movies", params={"limit": 5, "days": 60}, timeout=30)
        assert r.status_code == 200, r.text
        body = r.json()
        assert "data" in body and isinstance(body["data"], list), body
        assert len(body["data"]) > 0, "expected at least 1 upcoming title"

        today = date.today()
        for item in body["data"]:
            for k in ("tmdb_id", "type", "title", "poster", "year", "release_date", "synopsis"):
                assert k in item, f"missing key {k} in {item}"
            assert item["type"] == "movie"
            assert item["poster"].startswith("http"), item["poster"]
            # backdrop may be None — just assert key exists
            assert "backdrop" in item
            # release_date ISO and within +60 days
            assert re.match(r"^\d{4}-\d{2}-\d{2}$", item["release_date"]), item["release_date"]
            rel = date.fromisoformat(item["release_date"])
            assert rel >= today, f"release_date {rel} is in the past"
            # year matches
            assert item["year"] == item["release_date"][:4]

    def test_imdb_id_field_exists(self, s):
        # imdb_id is best-effort. Field must exist (None allowed).
        r = s.get(f"{API}/tmdb/upcoming-movies", params={"limit": 10, "days": 60}, timeout=30)
        assert r.status_code == 200
        body = r.json()
        assert len(body["data"]) > 0
        for item in body["data"]:
            assert "imdb_id" in item, f"imdb_id key missing from {item}"
            # Either None or starts with 'tt'
            if item["imdb_id"] is not None:
                assert isinstance(item["imdb_id"], str)
                assert item["imdb_id"].startswith("tt"), item["imdb_id"]

    def test_limit_honoured(self, s):
        r = s.get(f"{API}/tmdb/upcoming-movies", params={"limit": 20, "days": 60}, timeout=30)
        assert r.status_code == 200
        body = r.json()
        assert len(body["data"]) <= 20, len(body["data"])


# ----- /api/streams/{type}/{id} notify-scanner endpoint -----
class TestStreamsEndpoint:
    @pytest.mark.parametrize("imdb_id", ["tt15239678", "tt0111161"])
    def test_streams_no_500(self, s, imdb_id):
        r = s.get(f"{API}/streams/movie/{imdb_id}", timeout=60)
        assert r.status_code == 200, f"{r.status_code} {r.text[:300]}"
        body = r.json()
        assert "streams" in body and isinstance(body["streams"], list), body


# ----- /api/addons seeded list -----
class TestAddons:
    def test_default_addons_present(self, s):
        r = s.get(f"{API}/addons", timeout=15)
        assert r.status_code == 200, r.text
        addons = r.json()
        assert isinstance(addons, list), addons
        ids = {a.get("manifest_id") or a.get("id") or "" for a in addons}
        names = {(a.get("name") or "").lower() for a in addons}

        # Cinemeta + OpenSubtitles + WatchHub must be present.
        # Torrentio may be missing in preview (Cloudflare 403) — that's documented.
        assert any("cinemeta" in n for n in names) or any("cinemeta" in i for i in ids), names
        assert any("opensubtitles" in n or "subtitle" in n for n in names) or \
               any("opensubtitles" in i for i in ids), names
        assert any("watchhub" in n for n in names) or any("watchhub" in i for i in ids), names
