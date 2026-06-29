"""v2.10.81 — build_id-driven UPDATE pill regression suite.

Reproduces the operator-reported bug where re-uploading an APK with
the SAME versionName silently failed to trigger the launcher's
UPDATE pill on the TV box.

Fix architecture:
  • Backend mints a fresh UUIDv4 hex `build_id` on every APK upload
    (per-tile + home-update).
  • Launcher caches `installed_build_id_<pkg>` in SharedPreferences
    after each verified install.
  • Pill / has_update fires when the cached build_id differs from
    the backend-pinned one — regardless of versionName.

This file covers the BACKEND half:
  1. Dock-tile upload mints `apk_build_id`, returned in
     /api/launcher/config.
  2. Re-uploading the same APK rotates the build_id.
  3. Home-update upload mints `build_id`, returned in
     /api/launcher/home-update/info.
  4. `?current_build_id=<matching>` → has_update=False.
  5. `?current_build_id=<stale>` → has_update=True.
  6. Re-upload rotates the home-update build_id and a stale device
     correctly sees has_update=True.

The Android Launcher half is verified by static review (Kotlin
compile happens in CI on Save to GitHub).
"""

import os
import pytest
import requests

BASE_URL = os.environ.get("LAUNCHER_BACKEND_URL", "http://localhost:8002")
ADMIN_TOKEN = os.environ.get("LAUNCHER_ADMIN_TOKEN", "onnow-launcher-admin-dev")
AUTH = {"Authorization": f"Bearer {ADMIN_TOKEN}"}


@pytest.fixture(scope="module")
def fake_apk(tmp_path_factory):
    """Tiny binary that passes the .apk suffix check.  Real manifest
    parsing fails gracefully — we only need the build_id stamping
    path to exercise."""
    p = tmp_path_factory.mktemp("apk") / "fake.apk"
    p.write_bytes(b"PK\x03\x04")  # zip magic, enough to satisfy the suffix gate
    return p


def _upload_dock_apk(fake_apk_path, tile_key: str = "movies") -> dict:
    with open(fake_apk_path, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/api/admin/dock/{tile_key}/apk",
            headers=AUTH,
            files={"file": ("fake.apk", f, "application/vnd.android.package-archive")},
            timeout=15,
        )
    assert r.status_code == 200, r.text
    return r.json()


def _config_for_tile(tile_key: str) -> dict | None:
    r = requests.get(f"{BASE_URL}/api/launcher/config", timeout=10)
    assert r.status_code == 200
    for t in r.json().get("dock_tiles", []):
        if t.get("key") == tile_key:
            return t
    return None


def _store_for_tile(tile_key: str) -> dict | None:
    """Direct read of store.json — confirms persistence layer holds
    the build_id even if the wire format ever drops it."""
    import json
    from pathlib import Path
    data_dir = os.environ.get("DATA_DIR", "/app/launcher-backend/data")
    store = Path(data_dir) / "store.json"
    if not store.exists():
        return None
    with store.open() as f:
        s = json.load(f)
    for t in s.get("dock_tiles", []):
        if t.get("key") == tile_key:
            return t
    return None


# ──────────────────────  Dock-tile build_id  ──────────────────────


def test_dock_apk_upload_mints_build_id(fake_apk):
    """First upload — apk_build_id materialises in store.json AND in
    the public /api/launcher/config payload."""
    _upload_dock_apk(fake_apk)
    stored = _store_for_tile("movies")
    assert stored is not None
    bid = stored.get("apk_build_id")
    assert bid and len(bid) == 32, f"Expected 32-char UUID hex, got {bid!r}"

    wire = _config_for_tile("movies")
    assert wire is not None
    assert wire.get("apk_build_id") == bid, (
        "apk_build_id missing from /api/launcher/config — Android can't "
        "trigger the UPDATE pill without it on the wire."
    )


def test_dock_apk_reupload_rotates_build_id(fake_apk):
    """The bug fix — re-uploading the SAME bytes mints a NEW build_id."""
    _upload_dock_apk(fake_apk)
    bid1 = _store_for_tile("movies").get("apk_build_id")
    _upload_dock_apk(fake_apk)
    bid2 = _store_for_tile("movies").get("apk_build_id")
    assert bid1 != bid2, (
        "Re-uploading the same APK must mint a new build_id so the "
        "Android pill fires.  This is the v2.10.81 regression."
    )


# ──────────────────────  Home-update build_id  ────────────────────


def _upload_home_update(fake_apk_path) -> str:
    with open(fake_apk_path, "rb") as f:
        r = requests.post(
            f"{BASE_URL}/api/admin/home-update/upload",
            headers=AUTH,
            files={"file": ("home-update.apk", f, "application/vnd.android.package-archive")},
            timeout=15,
        )
    assert r.status_code == 200, r.text
    payload = r.json()
    bid = payload.get("home_update", {}).get("build_id")
    assert bid and len(bid) == 32
    return bid


def test_home_update_upload_mints_build_id(fake_apk):
    bid = _upload_home_update(fake_apk)
    r = requests.get(f"{BASE_URL}/api/launcher/home-update/info", timeout=10)
    assert r.status_code == 200
    body = r.json()
    assert body.get("has_update") is True
    assert body.get("build_id") == bid


def test_home_update_info_returns_no_update_when_build_id_matches(fake_apk):
    bid = _upload_home_update(fake_apk)
    r = requests.get(
        f"{BASE_URL}/api/launcher/home-update/info",
        params={"current_build_id": bid},
        timeout=10,
    )
    body = r.json()
    assert body.get("has_update") is False, (
        "Device reporting the same build_id as the backend must NOT "
        "receive has_update=True — that would loop the install dialog."
    )


def test_home_update_info_fires_when_build_id_stale(fake_apk):
    _upload_home_update(fake_apk)
    r = requests.get(
        f"{BASE_URL}/api/launcher/home-update/info",
        params={"current_build_id": "stale_build_id_from_old_install"},
        timeout=10,
    )
    body = r.json()
    assert body.get("has_update") is True, (
        "Stale device build_id must trigger has_update=True so the "
        "HOME UPDATE pill appears."
    )


def test_home_update_reupload_rotates_build_id_and_pill_refires(fake_apk):
    """End-to-end: stale device sees has_update=True even after the
    operator re-uploads (the rotated build_id is still different)."""
    bid1 = _upload_home_update(fake_apk)
    # Device "installs" build1 — reports it back; should be in sync.
    sync = requests.get(
        f"{BASE_URL}/api/launcher/home-update/info",
        params={"current_build_id": bid1},
        timeout=10,
    ).json()
    assert sync.get("has_update") is False

    # Operator re-uploads with NO versionName change.
    bid2 = _upload_home_update(fake_apk)
    assert bid1 != bid2

    # Device polls again with its old cached build_id — pill must fire.
    poll = requests.get(
        f"{BASE_URL}/api/launcher/home-update/info",
        params={"current_build_id": bid1},
        timeout=10,
    ).json()
    assert poll.get("has_update") is True
    assert poll.get("build_id") == bid2


# ─────────────────────────  Teardown  ────────────────────────────


@pytest.fixture(scope="module", autouse=True)
def _cleanup_after_suite():
    yield
    # Best-effort cleanup so subsequent test runs (and the live admin
    # UI) start from a clean state.
    try:
        requests.delete(f"{BASE_URL}/api/admin/dock/movies/apk", headers=AUTH, timeout=10)
    except Exception:
        pass
    try:
        requests.delete(f"{BASE_URL}/api/admin/home-update", headers=AUTH, timeout=10)
    except Exception:
        pass
