"""Backend tests for v2.10.53 in-house APKM installer bundle.

Covers:
  - Launcher backend health
  - /api/system-deps/{name}.apkm cold-start 404
  - /api/system-deps/{name}.apkm 200 after pinning
  - /api/admin/system-deps/{name} auth + happy path + bad apk_id
  - /api/admin/apks/upload accepts .apkm, rejects .exe
"""
import io
import json
import os
import shutil
import time
import uuid
import zipfile
from pathlib import Path

import pytest
import requests

BASE = "http://localhost:8002"
ADMIN_TOKEN = "onnow-launcher-admin-dev"
DATA_DIR = Path("/app/launcher-backend/data")
APKS_DIR = DATA_DIR / "apks"
STORE_PATH = DATA_DIR / "store.json"


def _admin_headers():
    return {"Authorization": f"Bearer {ADMIN_TOKEN}"}


# ── Health ──────────────────────────────────────────────────────────
def test_launcher_root_alive():
    r = requests.get(f"{BASE}/", timeout=10)
    assert r.status_code == 200
    # Returns HTML banner page
    assert "Launcher" in r.text or "OnNow" in r.text


# ── /api/system-deps cold state ─────────────────────────────────────
def test_system_deps_cold_state_404():
    """Before any pinning + with no name-prefix app store match,
    endpoint must return 404 + JSON detail."""
    # Use a name guaranteed not to match any store entries
    name = f"unregistered-{uuid.uuid4().hex[:6]}"
    r = requests.get(f"{BASE}/api/system-deps/{name}.apkm", timeout=10)
    assert r.status_code == 404
    body = r.json()
    assert "detail" in body
    assert name in body["detail"]
    assert "not registered" in body["detail"]


# ── End-to-end: pin a dummy bundle then download ────────────────────
class TestSystemDepDownload:
    """Seed store with a dummy .apkm, hit the public endpoint, restore."""

    dummy_filename = "test_dummy.apkm"
    dummy_id = "testdummy123"
    dep_name = "webview-138-test"  # don't clobber the real key

    @pytest.fixture(autouse=True)
    def _seed_and_restore(self):
        # Backup store
        original = STORE_PATH.read_text()
        store = json.loads(original)

        # Write 64-byte dummy file
        APKS_DIR.mkdir(parents=True, exist_ok=True)
        dummy_path = APKS_DIR / self.dummy_filename
        dummy_path.write_bytes(b"\x00" * 64)

        # Inject apks + system_deps entries
        store.setdefault("apks", []).append({
            "id": self.dummy_id,
            "name": "TEST_WebView 138 dummy",
            "apk_url": f"/assets/apks/{self.dummy_filename}",
            "version_name": "138.0.0.0",
            "added_at": int(time.time()),
        })
        store["system_deps"] = {
            self.dep_name: {
                "apk_id": self.dummy_id,
                "apk_url": f"/assets/apks/{self.dummy_filename}",
            }
        }
        STORE_PATH.write_text(json.dumps(store))

        # Give the FastAPI process time to pick up the file on next read
        time.sleep(0.2)
        yield
        # Cleanup
        STORE_PATH.write_text(original)
        try:
            dummy_path.unlink()
        except FileNotFoundError:
            pass

    def test_get_pinned_bundle_returns_200(self):
        r = requests.get(
            f"{BASE}/api/system-deps/{self.dep_name}.apkm",
            timeout=10,
        )
        assert r.status_code == 200, r.text
        assert r.headers.get("content-type", "").startswith(
            "application/vnd.android.package-archive"
        )
        assert len(r.content) == 64


# ── /api/admin/system-deps auth + happy path + bad id ───────────────
class TestAdminSetSystemDep:

    dummy_id = "admindummy456"
    apk_filename = "test_admin_dummy.apkm"
    dep_name = "webview-138-admintest"

    @pytest.fixture(autouse=True)
    def _seed_and_restore(self):
        original = STORE_PATH.read_text()
        store = json.loads(original)
        # Add target apk row
        store.setdefault("apks", []).append({
            "id": self.dummy_id,
            "name": "TEST_AdminDummy",
            "apk_url": f"/assets/apks/{self.apk_filename}",
            "version_name": "138.0.0.0",
            "added_at": int(time.time()),
        })
        STORE_PATH.write_text(json.dumps(store))
        APKS_DIR.mkdir(parents=True, exist_ok=True)
        (APKS_DIR / self.apk_filename).write_bytes(b"\x00" * 16)
        time.sleep(0.2)
        yield
        STORE_PATH.write_text(original)
        try:
            (APKS_DIR / self.apk_filename).unlink()
        except FileNotFoundError:
            pass

    def test_admin_endpoint_currently_open(self):
        """v2.8.126 — `require_admin` is a no-op per code comment
        ('Auth temporarily disabled per operator request').  This test
        codifies that behavior so a future re-enable causes a CLEAR
        breakage signal.  When auth is re-enabled, flip to 401/403."""
        r = requests.post(
            f"{BASE}/api/admin/system-deps/{self.dep_name}",
            json={"apk_id": self.dummy_id},
            timeout=10,
        )
        # Currently passes through; SHOULD BE 401/403 once auth is re-enabled.
        assert r.status_code == 200, (
            f"Unexpected status {r.status_code}; "
            "if auth was re-enabled this test must flip to 401/403"
        )

    def test_admin_pin_happy_path(self):
        r = requests.post(
            f"{BASE}/api/admin/system-deps/{self.dep_name}",
            json={"apk_id": self.dummy_id},
            headers=_admin_headers(),
            timeout=10,
        )
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["ok"] is True
        assert body["dep"]["apk_id"] == self.dummy_id
        assert body["dep"]["apk_url"] == f"/assets/apks/{self.apk_filename}"

        # And now the public endpoint must return 200 too
        r2 = requests.get(
            f"{BASE}/api/system-deps/{self.dep_name}.apkm",
            timeout=10,
        )
        assert r2.status_code == 200

    def test_admin_pin_with_nonexistent_apk_id_returns_404(self):
        r = requests.post(
            f"{BASE}/api/admin/system-deps/{self.dep_name}",
            json={"apk_id": "does-not-exist-zzz"},
            headers=_admin_headers(),
            timeout=10,
        )
        assert r.status_code == 404, r.text


# ── /api/admin/apks/upload accepts .apkm, rejects .exe ──────────────
class TestUploadApkm:

    @pytest.fixture
    def fake_apkm_bytes(self):
        """A ZIP with a single empty 'base.apk' entry → valid .apkm."""
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("base.apk", b"\x00" * 100)
        return buf.getvalue()

    def test_upload_apkm_succeeds(self, fake_apkm_bytes):
        files = {
            "file": ("test.apkm", fake_apkm_bytes,
                     "application/vnd.android.package-archive"),
        }
        data = {"name": "TEST_Bundle"}
        r = requests.post(
            f"{BASE}/api/admin/apks/upload",
            files=files,
            data=data,
            headers=_admin_headers(),
            timeout=30,
        )
        assert r.status_code == 200, r.text
        body = r.json()
        assert body.get("ok") is True
        assert body["apk"]["apk_url"].endswith(".apkm")
        aid = body["apk"]["id"]
        # Cleanup: delete the apk
        requests.delete(
            f"{BASE}/api/admin/apks/{aid}",
            headers=_admin_headers(),
            timeout=10,
        )

    def test_upload_exe_is_rejected(self):
        files = {"file": ("bad.exe", b"MZ\x00\x00", "application/octet-stream")}
        r = requests.post(
            f"{BASE}/api/admin/apks/upload",
            files=files,
            data={"name": "TEST_Bad"},
            headers=_admin_headers(),
            timeout=10,
        )
        assert r.status_code == 400, r.text
        assert "Unsupported" in r.text or "unsupported" in r.text.lower()
