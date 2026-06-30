"""
Iteration 63 lightweight backend regression:
- POST /api/auth/login with testuser/testpass123 returns JWT
- GET /api/streams/movie/tt0111161 returns non-empty streams array
"""
import os
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")


@pytest.fixture(scope="module")
def session():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    return s


def test_auth_login_returns_jwt(session):
    r = session.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": "testuser", "password": "testpass123"},
        timeout=15,
    )
    assert r.status_code == 200, f"expected 200, got {r.status_code}: {r.text[:300]}"
    data = r.json()
    # Tolerant of slight schema variations - look for a token field
    token = data.get("token") or data.get("access_token") or data.get("jwt")
    assert token, f"no token in response: {data}"
    assert isinstance(token, str) and len(token) > 20
    # JWT format check (header.payload.signature)
    assert token.count(".") == 2, f"not a JWT shape: {token[:40]}"


def test_streams_movie_shawshank_nonempty(session):
    r = session.get(f"{BASE_URL}/api/streams/movie/tt0111161", timeout=60)
    assert r.status_code == 200, f"expected 200, got {r.status_code}: {r.text[:300]}"
    data = r.json()
    streams = data.get("streams")
    assert isinstance(streams, list), f"streams is not a list: {type(streams)}"
    assert len(streams) > 0, "streams array is empty"
    # Each stream should have at least a name or url/infoHash
    sample = streams[0]
    assert ("url" in sample) or ("infoHash" in sample) or ("name" in sample), f"unexpected stream shape: {sample}"
