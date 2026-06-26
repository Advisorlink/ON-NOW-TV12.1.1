"""
v2.10.53-b synonym-matcher tests for /api/system-deps/{name}.apkm Path 3.

Validates that the new _SYNONYM_GROUPS in main.py correctly maps the
WebView 138 dependency request to the various App Store naming
conventions an admin might use (raw APKMirror Chrome filename,
standalone System WebView label, etc.).

Mutates /app/launcher-backend/data/store.json + apks/ + system-deps/
during execution and restores them byte-perfect via shutil.copy2 from
a snapshot taken in setup_module().  Pre-baked webview-138.apkm is
RENAMED (not deleted) so we can verify Path 3 alone, then renamed
back so the final state is identical to the start.
"""
import json
import os
import shutil
from pathlib import Path

import pytest
import requests

BASE_URL = os.environ.get("LAUNCHER_BACKEND_URL", "http://localhost:8002")
DATA_DIR = Path("/app/launcher-backend/data")
STORE_PATH = DATA_DIR / "store.json"
APKS_DIR = DATA_DIR / "apks"
SYSDEPS_DIR = DATA_DIR / "system-deps"
PREBAKED = SYSDEPS_DIR / "webview-138.apkm"
PREBAKED_BAK = SYSDEPS_DIR / "webview-138.apkm.iter52bak"

STORE_BACKUP = STORE_PATH.with_suffix(".iter52bak")
DUMMY_APK_NAMES: list[str] = []


def _read_store() -> dict:
    return json.loads(STORE_PATH.read_text())


def _write_store(d: dict) -> None:
    STORE_PATH.write_text(json.dumps(d, indent=2))


def _add_apk_entry(name: str, file_label: str) -> str:
    """Add a fake apk entry referencing a dummy 64-byte file.  Returns
    the apk_id so callers can clean it up."""
    import uuid
    apk_id = uuid.uuid4().hex[:12]
    fname = f"{apk_id}_{file_label}.apk"
    fpath = APKS_DIR / fname
    fpath.write_bytes(b"\x00" * 64)
    DUMMY_APK_NAMES.append(fname)
    store = _read_store()
    store.setdefault("apks", []).append({
        "id": apk_id,
        "name": name,
        "apk_url": f"/assets/apks/{fname}",
        "version_name": "138.0.7204.180",
    })
    _write_store(store)
    return apk_id


@pytest.fixture(scope="module", autouse=True)
def snapshot_state():
    """Snapshot store.json + rename pre-baked file.  Restore at teardown."""
    # Sanity: backend must be up and pre-baked must exist beforehand.
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, stream=True, timeout=5)
    r.close()
    assert r.status_code == 200, "pre-baked GET must be 200 before mutation"
    assert PREBAKED.exists(), "pre-baked file missing — abort"

    shutil.copy2(STORE_PATH, STORE_BACKUP)
    PREBAKED.rename(PREBAKED_BAK)
    yield
    # Restore.
    PREBAKED_BAK.rename(PREBAKED)
    shutil.copy2(STORE_BACKUP, STORE_PATH)
    STORE_BACKUP.unlink(missing_ok=True)
    for fname in DUMMY_APK_NAMES:
        (APKS_DIR / fname).unlink(missing_ok=True)


@pytest.fixture(autouse=True)
def clean_store_between_tests():
    """Each test wipes the apks list back to the snapshot so its
    synonym candidate set is the only matcher input."""
    shutil.copy2(STORE_BACKUP, STORE_PATH)
    # purge any dummy files from a previous test in this module
    for fname in list(DUMMY_APK_NAMES):
        (APKS_DIR / fname).unlink(missing_ok=True)
        DUMMY_APK_NAMES.remove(fname)
    yield


# ─── synonym matcher tests ────────────────────────────────────────────

def test_path3_matches_raw_chrome_apkmirror_filename():
    """com.android.chrome_138.0.7204.180-...apkmirror.com should
    satisfy a webview-138 request via the new synonym group."""
    _add_apk_entry(
        name="com.android.chrome_138.0.7204.180-720418027_24lang_2feat_8be1d6ffed5c7c318377a0b26632107f_apkmirror.com",
        file_label="chrome138",
    )
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=10)
    assert r.status_code == 200, f"expected 200 for chrome-138 synonym; got {r.status_code} body={r.text[:200]}"
    assert r.headers.get("content-type", "").startswith("application/vnd.android.package-archive")
    assert len(r.content) == 64


def test_path3_matches_android_system_webview_138():
    """Standalone 'Android System WebView 138' label should hit."""
    _add_apk_entry(name="Android System WebView 138", file_label="aswv138")
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=10)
    assert r.status_code == 200, f"expected 200 for ASWV-138; got {r.status_code} body={r.text[:200]}"
    assert len(r.content) == 64


def test_path3_does_not_match_unrelated_app():
    """'On Now Tv Live' must NOT be returned as a WebView dependency."""
    _add_apk_entry(name="On Now Tv Live", file_label="onnowlive")
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=10)
    assert r.status_code == 404, f"expected 404 negative control; got {r.status_code} body={r.text[:200]}"


def test_path3_matches_webview_138_plain():
    """Plain 'Webview 138' (legacy convention) still works."""
    _add_apk_entry(name="Webview 138", file_label="wv138plain")
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=10)
    assert r.status_code == 200, f"expected 200 for plain webview-138; got {r.status_code}"


def test_path3_matches_googlewebview_138():
    _add_apk_entry(name="GoogleWebView 138", file_label="gwv138")
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=10)
    assert r.status_code == 200, f"expected 200 for googlewebview-138; got {r.status_code}"


def test_path3_matches_comandroidchrome_138():
    _add_apk_entry(name="com.android.chrome 138", file_label="cac138")
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=10)
    assert r.status_code == 200, f"expected 200 for com.android.chrome-138; got {r.status_code}"
