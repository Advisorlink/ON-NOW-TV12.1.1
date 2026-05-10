"""Backend tests for TMDB-powered networks + tmdb→imdb endpoints."""
import os
import pytest
import requests

BASE_URL = os.environ.get(
    "REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com"
).rstrip("/")
API = f"{BASE_URL}/api"


@pytest.fixture(scope="module")
def s():
    return requests.Session()


# ----- /api/networks/{slug} -----
def test_netflix_tv(s):
    r = s.get(f"{API}/networks/netflix", params={"type": "tv", "page": 1}, timeout=30)
    assert r.status_code == 200, r.text
    body = r.json()
    assert "data" in body
    data = body["data"]
    assert data["network"] == "Netflix"
    assert data["type"] == "tv"
    assert data["total_results"] > 1000, data["total_results"]
    assert data["total_pages"] > 50, data["total_pages"]
    results = data["results"]
    assert len(results) >= 10
    first = results[0]
    for key in ("tmdb_id", "type", "title", "poster", "year", "rating"):
        assert key in first, key
    assert first["type"] == "series"
    assert isinstance(first["tmdb_id"], int)
    assert isinstance(first["title"], str) and first["title"]
    if first["poster"]:
        assert first["poster"].startswith("https://image.tmdb.org/t/p")
    assert isinstance(first["year"], str)
    assert isinstance(first["rating"], (int, float))


def test_netflix_movie(s):
    # Aggregate first 5 pages to ensure 100+ movies
    total = 0
    for p in range(1, 6):
        r = s.get(
            f"{API}/networks/netflix", params={"type": "movie", "page": p}, timeout=30
        )
        assert r.status_code == 200, r.text
        results = r.json()["data"]["results"]
        for it in results:
            assert it["type"] == "movie"
        total += len(results)
        if total >= 100:
            break
    assert total >= 100, f"only {total} netflix movies aggregated"


def test_disney_tv(s):
    r = s.get(
        f"{API}/networks/disney-plus", params={"type": "tv", "page": 1}, timeout=30
    )
    assert r.status_code == 200, r.text
    data = r.json()["data"]
    assert data["total_results"] >= 50, data["total_results"]
    assert len(data["results"]) >= 1


def test_hbo_includes_marquee_titles(s):
    """HBO Max should expose at least *some* of its marquee titles within
    the first 5 popularity-sorted pages.  Titles trend in/out of popularity
    so we accept any one of a broad set."""
    found = set()
    targets = {
        "game of thrones",
        "succession",
        "the last of us",
        "house of the dragon",
        "true detective",
        "the white lotus",
        "euphoria",
        "westworld",
    }
    for p in range(1, 6):
        r = s.get(f"{API}/networks/hbo", params={"type": "tv", "page": p}, timeout=30)
        assert r.status_code == 200
        for it in r.json()["data"]["results"]:
            t = (it["title"] or "").lower()
            for tgt in targets:
                if tgt in t:
                    found.add(tgt)
        if found:
            break
    assert found, f"Expected at least one HBO marquee title, got none in 5 pages"


def test_network_cache(s):
    # Use prime-video to avoid races with previous tests
    p1 = s.get(
        f"{API}/networks/prime-video", params={"type": "tv", "page": 1}, timeout=30
    )
    assert p1.status_code == 200
    p2 = s.get(
        f"{API}/networks/prime-video", params={"type": "tv", "page": 1}, timeout=30
    )
    assert p2.status_code == 200
    assert p2.json().get("cached") is True


def test_unknown_network_404(s):
    r = s.get(f"{API}/networks/unknown-slug", timeout=15)
    assert r.status_code == 404


def test_invalid_type_400(s):
    r = s.get(
        f"{API}/networks/netflix", params={"type": "invalid"}, timeout=15
    )
    assert r.status_code == 400


# ----- /api/tmdb/imdb/{type}/{tmdb_id} -----
def test_tmdb_imdb_stranger_things(s):
    r = s.get(f"{API}/tmdb/imdb/tv/66732", timeout=30)
    assert r.status_code == 200, r.text
    body = r.json()
    assert body.get("imdb_id") == "tt4574334"


def test_tmdb_imdb_invalid_type(s):
    r = s.get(f"{API}/tmdb/imdb/foo/123", timeout=15)
    assert r.status_code == 400
