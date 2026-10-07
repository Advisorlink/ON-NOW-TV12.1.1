import os
import requests


BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "").rstrip("/")


def _api(path: str) -> str:
    if not BASE_URL:
        return ""
    return f"{BASE_URL}{path}"


def test_base_url_present_for_public_testing():
    # env wiring: public preview base URL must come from REACT_APP_BACKEND_URL
    assert BASE_URL, "REACT_APP_BACKEND_URL is required for smoke tests"


def test_health_root_ok_and_version_present():
    # backend smoke: root endpoint responds with app/version payload
    r = requests.get(_api("/api/"), timeout=20)
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)
    assert "app" in data
    assert "version" in data


def test_matrix_meta_contains_core_fields():
    # metadata flow: imdb -> detail metadata used by Detail page
    r = requests.get(_api("/api/meta/movie/tt0133093"), timeout=40)
    assert r.status_code == 200
    data = r.json()
    meta = data.get("meta") or data.get("data", {}).get("meta")
    assert isinstance(meta, dict)
    assert meta.get("id") == "tt0133093"
    assert isinstance(meta.get("name"), str) and len(meta.get("name")) > 0


def test_matrix_streams_payload_shape():
    # stream flow: movie streams endpoint returns stream list shape
    r = requests.get(_api("/api/streams/movie/tt0133093"), timeout=60)
    assert r.status_code == 200
    data = r.json()
    assert isinstance(data, dict)
    assert "streams" in data
    assert isinstance(data.get("streams"), list)


def test_tmdb_find_by_imdb_and_credits():
    # tmdb enrich flow: imdb -> tmdb id -> credits used by CastRow
    find_r = requests.get(_api("/api/tmdb/find-by-imdb/tt0133093"), timeout=30)
    assert find_r.status_code == 200
    find_data = find_r.json()
    assert isinstance(find_data, dict)
    tmdb_id = find_data.get("tmdb_id")
    media_type = find_data.get("media_type")
    assert isinstance(tmdb_id, int)
    assert media_type in {"movie", "tv"}

    credits_r = requests.get(_api(f"/api/tmdb/credits/{media_type}/{tmdb_id}"), timeout=30)
    assert credits_r.status_code == 200
    credits_data = credits_r.json()
    assert isinstance(credits_data, dict)
    assert "cast" in credits_data
    assert isinstance(credits_data.get("cast"), list)
