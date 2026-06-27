"""Iteration 56 regression tests for Kids-bug fixes.

Verifies that the backend regressed nothing while we fixed the Kids
APK BACK-button / Vesper kids-leak issues on the frontend.
"""
import os
import pytest
import requests

BASE_URL = os.environ.get('REACT_APP_BACKEND_URL', 'https://rebrand-app-5.preview.emergentagent.com').rstrip('/')


@pytest.fixture
def api_client():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    return s


# Auth regression — testuser/testpass123 should issue a JWT
class TestAuthRegression:
    def test_login_testuser(self, api_client):
        r = api_client.post(f"{BASE_URL}/api/auth/login", json={
            "username": "testuser",
            "password": "testpass123",
        }, timeout=20)
        assert r.status_code == 200, f"login failed: {r.status_code} {r.text[:300]}"
        data = r.json()
        # token field name may be `token` or `access_token`
        token = data.get("token") or data.get("access_token")
        assert token and isinstance(token, str) and len(token) > 20
        # JWT shape: 3 dot-segments
        assert token.count(".") == 2


# Streams regression — Shawshank should return streams
class TestStreamsRegression:
    def test_streams_shawshank(self, api_client):
        r = api_client.get(f"{BASE_URL}/api/streams/movie/tt0111161", timeout=45)
        assert r.status_code == 200, f"streams failed: {r.status_code} {r.text[:300]}"
        data = r.json()
        # Response shape: {"streams": [...]} or list
        streams = data.get("streams") if isinstance(data, dict) else data
        assert isinstance(streams, list)
        assert len(streams) > 0, "expected at least one stream for tt0111161"
