"""Round-96: /api/tmdb/custom-row synthetic categories (Hallmark/Christmas/Bollywood) + regression."""
import os
import pytest
import requests

BASE_URL = os.environ.get('REACT_APP_BACKEND_URL', 'https://rebrand-app-5.preview.emergentagent.com').rstrip('/')


@pytest.fixture(scope="module")
def api():
    s = requests.Session()
    return s


def _get(api, q, limit=12):
    r = api.get(f"{BASE_URL}/api/tmdb/custom-row", params={"q": q, "limit": limit}, timeout=30)
    return r


def _items(data):
    return data.get("items") or data.get("data") or []


def test_hallmark(api):
    r = _get(api, "Hallmark")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data.get("label") == "Hallmark", data
    matched = data.get("matched") or {}
    assert "Hallmark" in (matched.get("genres") or []), matched
    assert len(_items(data)) >= 8, f"only {len(data.get('items') or [])} items"


def test_christmas(api):
    r = _get(api, "Christmas")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data.get("label") == "Christmas", data
    matched = data.get("matched") or {}
    assert "movie" in (matched.get("media") or []), matched
    assert len(_items(data)) >= 8


def test_christmas_movies_alias(api):
    r = _get(api, "christmas movies")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data.get("label") == "Christmas", data
    assert len(_items(data)) >= 8


def test_bollywood(api):
    r = _get(api, "Bollywood")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data.get("label") == "Bollywood", data
    assert len(_items(data)) >= 1


def test_horror_comedy_regression(api):
    r = _get(api, "horror comedy")
    assert r.status_code == 200, r.text
    data = r.json()
    assert data.get("label") == "Horror & Comedy", data
