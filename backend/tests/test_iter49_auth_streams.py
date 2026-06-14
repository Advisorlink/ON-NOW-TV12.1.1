"""Iteration 49 — verify auth + streams endpoints still healthy after v2.10.52 frontend refactor."""
import os
import requests
import pytest

BASE = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")


@pytest.fixture(scope="module")
def token():
    r = requests.post(f"{BASE}/api/auth/login",
                      json={"username": "testuser", "password": "testpass123"},
                      timeout=15)
    assert r.status_code == 200, f"login failed: {r.status_code} {r.text}"
    data = r.json()
    tok = data.get("access_token") or data.get("token")
    assert tok and "account" in data
    assert data["account"]["username"] == "testuser"
    return tok


def test_login_ok(token):
    assert isinstance(token, str) and len(token) > 50


def test_login_bad_creds():
    r = requests.post(f"{BASE}/api/auth/login",
                      json={"username": "testuser", "password": "wrong-xyz-789"},
                      timeout=15)
    assert r.status_code == 401, r.text


def test_auth_me(token):
    r = requests.get(f"{BASE}/api/auth/me",
                     headers={"Authorization": f"Bearer {token}"}, timeout=15)
    assert r.status_code == 200, r.text
    data = r.json()
    # /me returns account directly (no wrapper)
    acc = data.get("account", data)
    assert acc.get("username") == "testuser"


def test_streams_movie():
    r = requests.get(f"{BASE}/api/streams/movie/tt0111161", timeout=45)
    assert r.status_code == 200, r.text
    data = r.json()
    streams = data.get("streams", data) if isinstance(data, dict) else data
    assert isinstance(streams, list)
    assert len(streams) >= 1, f"expected >=1 stream, got {len(streams)}"


def test_streams_series_colon_format():
    # The frontend normalizes tt:s1e1 → tt:1:1; backend expects colon format
    r = requests.get(f"{BASE}/api/streams/series/tt0903747:1:1", timeout=45)
    assert r.status_code == 200, r.text
    data = r.json()
    streams = data.get("streams", data) if isinstance(data, dict) else data
    assert isinstance(streams, list)
    assert len(streams) >= 1, f"expected >=1 stream, got {len(streams)}"
