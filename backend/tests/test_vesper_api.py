"""Backend tests for Vesper Stremio addon API."""
import os
import time
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")
API = f"{BASE_URL}/api"

CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"
WATCHHUB_URL = "https://watchhub.strem.io/manifest.json"
OPENSUBS_URL = "https://opensubtitles-v3.strem.io/manifest.json"


@pytest.fixture(scope="module")
def s():
    return requests.Session()


# ----- Root & suggested -----
def test_root(s):
    r = s.get(f"{API}/")
    assert r.status_code == 200
    d = r.json()
    assert d.get("app") == "Vesper"
    assert "version" in d


def test_suggested(s):
    r = s.get(f"{API}/addons/suggested")
    assert r.status_code == 200
    sg = r.json().get("suggested", [])
    assert len(sg) == 3
    names = {x["name"] for x in sg}
    assert {"Cinemeta", "OpenSubtitles", "WatchHub"}.issubset(names)


# ----- Cleanup any pre-existing addons -----
def test_cleanup_preexisting(s):
    r = s.get(f"{API}/addons")
    assert r.status_code == 200
    for a in r.json():
        s.delete(f"{API}/addons/{a['id']}")


# ----- Install addons -----
def test_install_cinemeta(s):
    r = s.post(f"{API}/addons/install", json={"url": CINEMETA_URL}, timeout=30)
    assert r.status_code == 200, r.text
    d = r.json()
    assert d.get("ok") is True
    assert d["addon"]["name"] == "Cinemeta"


def test_install_invalid(s):
    r = s.post(f"{API}/addons/install", json={"url": "https://invalid-nonexistent-domain-x9z2.example/manifest.json"}, timeout=30)
    assert 400 <= r.status_code < 600 and r.status_code != 200
    d = r.json()
    assert "detail" in d


def test_install_watchhub(s):
    r = s.post(f"{API}/addons/install", json={"url": WATCHHUB_URL}, timeout=30)
    assert r.status_code == 200, r.text
    assert r.json()["addon"]["name"].lower().startswith("watchhub") or "watchhub" in r.json()["addon"]["name"].lower()


# ----- List addons -----
def test_list_addons(s):
    r = s.get(f"{API}/addons")
    assert r.status_code == 200
    arr = r.json()
    assert isinstance(arr, list)
    ids = [a["id"] for a in arr]
    assert any("cinemeta" in i.lower() for i in ids)
    cinemeta = next(a for a in arr if "cinemeta" in a["id"].lower())
    for f in ("id", "name", "version", "catalogs", "types"):
        assert f in cinemeta


# ----- Catalog -----
def test_catalog_top_movies(s):
    r = s.get(f"{API}/addons/com.linvo.cinemeta/catalog/movie/top", timeout=30)
    assert r.status_code == 200, r.text
    d = r.json()
    metas = d.get("data", {}).get("metas", [])
    assert len(metas) >= 40


def test_catalog_search(s):
    r = s.get(f"{API}/addons/com.linvo.cinemeta/catalog/movie/top", params={"search": "batman"}, timeout=30)
    assert r.status_code == 200
    metas = r.json().get("data", {}).get("metas", [])
    assert len(metas) >= 1
    assert any("batman" in (m.get("name", "").lower()) for m in metas)


def test_catalog_cache(s):
    # First call -- might already be cached from previous tests
    s.get(f"{API}/addons/com.linvo.cinemeta/catalog/series/top", timeout=30)
    r2 = s.get(f"{API}/addons/com.linvo.cinemeta/catalog/series/top", timeout=30)
    assert r2.status_code == 200
    assert r2.json().get("cached") is True


# ----- Meta -----
def test_meta(s):
    r = s.get(f"{API}/meta/movie/tt0032138", timeout=30)
    assert r.status_code == 200, r.text
    meta = r.json().get("data", {}).get("meta", {})
    assert "Wizard of Oz" in meta.get("name", "")


# ----- Streams -----
def test_streams(s):
    r = s.get(f"{API}/streams/movie/tt0096874", timeout=45)
    assert r.status_code == 200, r.text
    assert "streams" in r.json()
    assert isinstance(r.json()["streams"], list)


# ----- Delete -----
def test_delete_addon(s):
    r = s.get(f"{API}/addons")
    addons = r.json()
    target = next((a for a in addons if "watchhub" in a["id"].lower()), None)
    assert target is not None
    rd = s.delete(f"{API}/addons/{target['id']}")
    assert rd.status_code == 200
    r2 = s.get(f"{API}/addons")
    assert all(a["id"] != target["id"] for a in r2.json())
