"""Native private APK download security and contract tests."""

from __future__ import annotations

import hashlib
import importlib
import os
from pathlib import Path

import pytest
import requests
from fastapi import FastAPI
from fastapi.testclient import TestClient


def _env_value(path: Path, key: str) -> str | None:
    if not path.exists():
        return None
    prefix = f"{key}="
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or not line.startswith(prefix):
            continue
        return line.split("=", 1)[1].strip().strip('"').strip("'")
    return None


BASE_URL = _env_value(Path("/app/frontend/.env"), "REACT_APP_BACKEND_URL")
TOKEN = _env_value(Path("/app/backend/.env"), "APK_DOWNLOAD_CAPABILITY_TOKEN")
APK_PATH = Path("/app/android/vesper-native/app/build/outputs/apk/debug/app-debug.apk")


@pytest.fixture(scope="module")
def http_session():
    # Module coverage: /api/download private APK contract over public URL.
    if not BASE_URL:
        pytest.skip("REACT_APP_BACKEND_URL missing in /app/frontend/.env")
    session = requests.Session()
    session.headers.update({"Accept": "*/*"})
    return session


def test_apk_download_rejects_missing_token(http_session):
    response = http_session.get(f"{BASE_URL.rstrip('/')}/api/download/vesper-native-apk", timeout=30)
    assert response.status_code == 404
    assert "no-store" in response.headers.get("Cache-Control", "")
    assert "" == response.text


def test_apk_download_rejects_wrong_header_token(http_session):
    response = http_session.get(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-apk",
        headers={"X-Download-Token": "definitely-wrong-token"},
        timeout=30,
    )
    assert response.status_code == 404
    assert "no-store" in response.headers.get("Cache-Control", "")


def test_apk_download_rejects_query_token_only(http_session):
    response = http_session.get(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-apk?token=wrong",
        timeout=30,
    )
    assert response.status_code == 404
    assert "no-store" in response.headers.get("Cache-Control", "")


def test_apk_download_valid_token_returns_apk_and_matches_local_hash(http_session):
    if not TOKEN:
        pytest.skip("APK_DOWNLOAD_CAPABILITY_TOKEN missing in /app/backend/.env")
    if not APK_PATH.exists():
        pytest.skip("Local debug APK missing; build artifact unavailable")

    response = http_session.get(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-apk",
        headers={"X-Download-Token": TOKEN},
        timeout=120,
    )

    assert response.status_code == 200
    assert response.headers.get("Content-Type", "").startswith("application/vnd.android.package-archive")
    assert "no-store" in response.headers.get("Cache-Control", "")

    remote_sha = hashlib.sha256(response.content).hexdigest()
    local_sha = hashlib.sha256(APK_PATH.read_bytes()).hexdigest()
    assert remote_sha == local_sha


def test_private_download_helper_page_security_headers(http_session):
    response = http_session.get(f"{BASE_URL.rstrip('/')}/api/download/vesper-native", timeout=30)
    html = response.text
    assert response.status_code == 200
    assert "history.replaceState" in html
    assert "window.location.hash.slice(1)" in html
    assert "localStorage" not in html
    assert "document.cookie" not in html
    assert "X-Download-Token" in html
    assert "token=" not in html


def test_native_download_module_fails_closed_when_env_missing(monkeypatch):
    # Module coverage: direct router import with missing env and absent token.
    monkeypatch.delenv("APK_DOWNLOAD_CAPABILITY_TOKEN", raising=False)
    module = importlib.import_module("native_download")
    module = importlib.reload(module)

    app = FastAPI()
    app.include_router(module.router)
    with TestClient(app) as client:
        response = client.get("/api/download/vesper-native-apk")
        assert response.status_code == 404
        assert "no-store" in response.headers.get("cache-control", "")
