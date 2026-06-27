"""v2.10.71 — Bulk-install manifest endpoint contract test.

Verifies `GET /api/bulk/manifest`:
  • 200 + JSON shape (`apks`, `generation`, `count`)
  • Every entry has the keys the launcher's BulkInstallActivity needs
    (key, label, package_id, version, apk_url, apk_filename,
    icon_url, size_bytes) — no nulls, all strings/ints/bools
  • Only tiles with a pinned APK are listed
  • Public endpoint (no admin auth required)
  • `apk_url` is an absolute URL the launcher can hit
"""
import os
import pytest
import requests

BASE_URL = os.environ.get("LAUNCHER_BACKEND_URL", "http://localhost:8002")


def test_manifest_returns_200_with_expected_shape():
    r = requests.get(f"{BASE_URL}/api/bulk/manifest", timeout=10)
    assert r.status_code == 200, r.text
    data = r.json()
    assert isinstance(data, dict)
    assert "apks" in data and isinstance(data["apks"], list)
    assert "generation" in data and isinstance(data["generation"], int)
    assert "count" in data and isinstance(data["count"], int)
    assert data["count"] == len(data["apks"])


def test_manifest_no_admin_required():
    """Endpoint must be reachable without the admin Bearer token —
    the launcher itself has no admin credentials and must still be
    able to drive the bulk install queue on a fresh box."""
    r = requests.get(f"{BASE_URL}/api/bulk/manifest", timeout=10)
    assert r.status_code == 200


def test_every_apk_entry_has_installer_fields():
    r = requests.get(f"{BASE_URL}/api/bulk/manifest", timeout=10)
    assert r.status_code == 200
    for entry in r.json()["apks"]:
        # Required string-ish fields
        for k in ("key", "label", "package_id", "version", "apk_url",
                  "apk_filename"):
            assert k in entry, f"missing '{k}' in entry: {entry}"
            assert isinstance(entry[k], str), f"'{k}' not str in {entry}"
        # apk_url MUST be absolute so the Kotlin OkHttp client can
        # hit it without local path-resolution shenanigans.
        assert entry["apk_url"].startswith(("http://", "https://")), entry
        # icon_url is allowed to be null when the operator hasn't
        # uploaded a tile image yet.
        assert "icon_url" in entry
        # size_bytes must be a non-negative int (0 = unknown).
        assert isinstance(entry["size_bytes"], int)
        assert entry["size_bytes"] >= 0


def test_manifest_only_lists_tiles_with_pinned_apks():
    """A dock tile without `apk_url` must NOT show up in the manifest."""
    r = requests.get(f"{BASE_URL}/api/bulk/manifest", timeout=10)
    assert r.status_code == 200
    for entry in r.json()["apks"]:
        assert entry["apk_url"], f"empty apk_url leaked through: {entry}"
