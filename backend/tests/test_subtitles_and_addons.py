"""P0 backend tests: subtitles aggregator and OpenSubtitles install."""
import os
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")
API = f"{BASE_URL}/api"

OPENSUBS_URL = "https://opensubtitles-v3.strem.io/manifest.json"
CINEMETA_URL = "https://v3-cinemeta.strem.io/manifest.json"


@pytest.fixture(scope="module")
def s():
    return requests.Session()


def test_install_opensubtitles_v3(s):
    """Install OpenSubtitles v3 (idempotent — backend may already have it)."""
    r = s.post(f"{API}/addons/install", json={"url": OPENSUBS_URL}, timeout=30)
    assert r.status_code in (200, 409), r.text
    if r.status_code == 200:
        d = r.json()
        assert d.get("ok") is True
        assert "opensubtitles" in d["addon"]["id"].lower()


def test_install_cinemeta_idempotent(s):
    """Cinemeta should also be installable / already present."""
    r = s.post(f"{API}/addons/install", json={"url": CINEMETA_URL}, timeout=30)
    assert r.status_code in (200, 409), r.text


def test_addons_list_contains_both_required(s):
    """After installs, GET /api/addons must contain Cinemeta + OpenSubtitles v3."""
    r = s.get(f"{API}/addons", timeout=15)
    assert r.status_code == 200
    arr = r.json()
    ids = [a["id"] for a in arr]
    assert any("cinemeta" in i.lower() for i in ids), f"Cinemeta missing. ids={ids}"
    assert any("opensubtitles" in i.lower() for i in ids), f"OpenSubtitles v3 missing. ids={ids}"


def test_subtitles_aggregator_shawshank(s):
    """GET /api/subtitles/movie/tt0111161 should yield >0 subtitles, English present."""
    r = s.get(f"{API}/subtitles/movie/tt0111161", timeout=45)
    assert r.status_code == 200, r.text
    d = r.json()
    assert "subtitles" in d
    subs = d["subtitles"]
    assert isinstance(subs, list)
    assert len(subs) > 0, f"No subtitles returned: {d}"
    # Check at least one English subtitle
    eng = [x for x in subs if (x.get("lang") or "").lower().startswith(("en", "eng"))]
    assert len(eng) >= 1, f"No English subtitles. Languages: {[x.get('lang') for x in subs[:10]]}"
    # Each entry should have a url
    assert all(x.get("url") for x in subs[:5])


def test_subtitles_caching(s):
    """Second hit should return cached=true."""
    s.get(f"{API}/subtitles/movie/tt0111161", timeout=45)
    r2 = s.get(f"{API}/subtitles/movie/tt0111161", timeout=15)
    assert r2.status_code == 200
    assert r2.json().get("cached") is True
