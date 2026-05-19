"""Iter 39 — verify /api/tmdb/upcoming-movies returns English-language popular
items with `trailer_key` field."""
import os
import re

import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "").rstrip("/")
if not BASE_URL:
    # fall back to frontend/.env (rare — supervisor usually exports it)
    try:
        with open("/app/frontend/.env") as f:
            for line in f:
                if line.startswith("REACT_APP_BACKEND_URL="):
                    BASE_URL = line.split("=", 1)[1].strip().strip('"').rstrip("/")
                    break
    except Exception:
        pass


@pytest.fixture(scope="module")
def upcoming_resp():
    url = f"{BASE_URL}/api/tmdb/upcoming-movies?limit=8"
    r = requests.get(url, timeout=30)
    return r


def test_upcoming_status_200(upcoming_resp):
    assert upcoming_resp.status_code == 200, upcoming_resp.text


def test_upcoming_has_data_array(upcoming_resp):
    body = upcoming_resp.json()
    assert "data" in body and isinstance(body["data"], list)
    assert len(body["data"]) > 0, "expected at least 1 upcoming movie"


def test_upcoming_items_have_trailer_key_field(upcoming_resp):
    body = upcoming_resp.json()
    for item in body["data"]:
        # field must exist (may be None if TMDB had no video — that's allowed)
        assert "trailer_key" in item, f"missing trailer_key in {item.get('title')}"


def test_upcoming_at_least_one_trailer_key_present(upcoming_resp):
    """At least one item out of 8 should have a real YouTube key (string of
    ~11 chars).  This sanity-checks the trailer resolution path actually
    runs."""
    body = upcoming_resp.json()
    with_keys = [i for i in body["data"] if i.get("trailer_key")]
    assert len(with_keys) > 0, "expected ≥1 item to have a resolved trailer_key"
    # Validate format
    for item in with_keys:
        key = item["trailer_key"]
        assert isinstance(key, str) and re.fullmatch(r"[\w-]{8,16}", key), key


def test_upcoming_popularity_threshold(upcoming_resp):
    body = upcoming_resp.json()
    for item in body["data"]:
        pop = item.get("popularity") or 0
        assert pop >= 6, f"{item.get('title')} popularity={pop} < 6"


def test_upcoming_items_have_required_fields(upcoming_resp):
    body = upcoming_resp.json()
    for item in body["data"]:
        for f in ("tmdb_id", "type", "title", "poster", "release_date"):
            assert f in item, f"missing {f} in item"
        assert item["type"] == "movie"
        assert item["title"]
        assert item["poster"].startswith("http")


def test_upcoming_limit_respected(upcoming_resp):
    body = upcoming_resp.json()
    assert len(body["data"]) <= 8


def test_upcoming_titles_look_english():
    """Sanity heuristic — fetch a fresh (non-cached) variant and ensure the
    bulk of titles use ASCII Latin characters (no CJK / Cyrillic / Devanagari
    blocks).  We pass a slightly different `days` to bypass the cache key.
    """
    url = f"{BASE_URL}/api/tmdb/upcoming-movies?limit=8&days=61"
    r = requests.get(url, timeout=30)
    assert r.status_code == 200
    titles = [i.get("title", "") for i in r.json().get("data", [])]
    non_latin = []
    for t in titles:
        for ch in t:
            o = ord(ch)
            if o > 0x024F and not (0x2000 <= o <= 0x206F):
                non_latin.append(t)
                break
    # Allow at most 1 (occasional English film with accented co-title)
    assert len(non_latin) <= 1, f"non-latin titles found: {non_latin}"
