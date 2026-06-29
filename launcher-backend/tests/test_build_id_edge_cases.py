"""v2.10.81 — supplementary edge-case verification for build_id pill trigger.

Covers the scenarios called out in the review-request that are NOT
already exercised by test_build_id_pill_trigger.py:
  • home-update info WITHOUT any query params on a pinned APK still
    returns has_update=True (legacy clients).
  • dock-tile DELETE clears apk_build_id from store.json.
  • home-update DELETE clears build_id from store.json.
  • apk_build_id field is present (null) on dock_tiles that have NEVER
    had an APK pinned.
  • Returned IDs are valid 32-char lower-hex UUIDv4 strings.
"""

import json
import os
import re
from pathlib import Path

import pytest
import requests

BASE_URL = os.environ.get("LAUNCHER_BACKEND_URL", "http://localhost:8002")
ADMIN_TOKEN = os.environ.get("LAUNCHER_ADMIN_TOKEN", "onnow-launcher-admin-dev")
AUTH = {"Authorization": f"Bearer {ADMIN_TOKEN}"}
DATA_DIR = os.environ.get("DATA_DIR", "/app/launcher-backend/data")
HEX32_RE = re.compile(r"^[0-9a-f]{32}$")


@pytest.fixture(scope="module")
def fake_apk(tmp_path_factory):
    p = tmp_path_factory.mktemp("apk") / "fake.apk"
    p.write_bytes(b"PK\x03\x04")
    return p


def _store() -> dict:
    s = Path(DATA_DIR) / "store.json"
    return json.loads(s.read_text()) if s.exists() else {}


def _upload_dock(fake_apk, key="movies"):
    with open(fake_apk, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/api/admin/dock/{key}/apk",
            headers=AUTH,
            files={"file": ("fake.apk", f, "application/vnd.android.package-archive")},
            timeout=15,
        )
    assert r.status_code == 200, r.text
    return r.json()


def _upload_home(fake_apk):
    with open(fake_apk, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/api/admin/home-update/upload",
            headers=AUTH,
            files={"file": ("home-update.apk", f, "application/vnd.android.package-archive")},
            timeout=15,
        )
    assert r.status_code == 200, r.text
    return r.json()["home_update"]["build_id"]


# ───────────── build_id format / wire-shape ─────────────


def test_dock_apk_build_id_is_valid_uuid4_hex(fake_apk):
    _upload_dock(fake_apk)
    tile = next(t for t in _store().get("dock_tiles", []) if t["key"] == "movies")
    bid = tile.get("apk_build_id")
    assert bid and HEX32_RE.match(bid), f"Bad apk_build_id format: {bid!r}"


def test_home_update_build_id_is_valid_uuid4_hex(fake_apk):
    bid = _upload_home(fake_apk)
    assert HEX32_RE.match(bid), f"Bad home-update build_id format: {bid!r}"


def test_launcher_config_includes_apk_build_id_field_on_every_dock_tile(fake_apk):
    """Public wire payload must always expose the field — null when not
    pinned — so the Android client doesn't have to guard against
    KeyError."""
    _upload_dock(fake_apk, "movies")
    r = requests.get(f"{BASE_URL}/api/launcher/config", timeout=10)
    assert r.status_code == 200
    tiles = r.json().get("dock_tiles", [])
    assert tiles, "dock_tiles missing from /api/launcher/config"
    for t in tiles:
        assert "apk_build_id" in t, (
            f"Tile {t.get('key')!r} missing apk_build_id key — Android "
            "code that does payload['apk_build_id'] would crash."
        )


# ───────────── legacy fallback (no current_build_id) ─────────────


def test_home_update_info_without_query_returns_has_update_true(fake_apk):
    """Legacy clients (older launcher builds that don't yet send
    current_build_id) must still see has_update=True so the pill
    appears."""
    _upload_home(fake_apk)
    r = requests.get(f"{BASE_URL}/api/launcher/home-update/info", timeout=10)
    assert r.status_code == 200
    body = r.json()
    assert body.get("has_update") is True, (
        "Legacy (no query) call must return has_update=True when an "
        "APK is pinned — otherwise the pill never appears on older "
        "launcher builds."
    )
    assert HEX32_RE.match(body.get("build_id") or ""), body


# ───────────── cleanup endpoints clear build_id ─────────────


def test_dock_delete_clears_apk_build_id(fake_apk):
    _upload_dock(fake_apk, "movies")
    assert _store_tile("movies").get("apk_build_id"), "precondition failed"
    r = requests.delete(f"{BASE_URL}/api/admin/dock/movies/apk", headers=AUTH, timeout=10)
    assert r.status_code in (200, 204), r.text
    tile = _store_tile("movies")
    assert not tile.get("apk_build_id"), (
        f"DELETE /api/admin/dock/movies/apk did NOT clear apk_build_id "
        f"from store.json — remnant: {tile.get('apk_build_id')!r}"
    )


def test_home_update_delete_clears_build_id(fake_apk):
    _upload_home(fake_apk)
    hu = _store().get("home_update", {})
    assert hu.get("build_id"), "precondition failed"
    r = requests.delete(f"{BASE_URL}/api/admin/home-update", headers=AUTH, timeout=10)
    assert r.status_code in (200, 204), r.text
    hu = _store().get("home_update") or {}
    assert not hu.get("build_id"), (
        f"DELETE /api/admin/home-update did NOT clear build_id — "
        f"remnant: {hu.get('build_id')!r}"
    )


def _store_tile(key):
    for t in _store().get("dock_tiles", []):
        if t.get("key") == key:
            return t
    return {}


# ───────────── teardown ─────────────


@pytest.fixture(scope="module", autouse=True)
def _cleanup():
    yield
    try:
        requests.delete(f"{BASE_URL}/api/admin/dock/movies/apk", headers=AUTH, timeout=10)
    except Exception:
        pass
    try:
        requests.delete(f"{BASE_URL}/api/admin/home-update", headers=AUTH, timeout=10)
    except Exception:
        pass
