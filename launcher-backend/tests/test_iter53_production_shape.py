"""
v2.10.53-c live-curl tests for the four production-shape scenarios:

  1. Absolute apk_url + prebaked .apkm moved out → 200 + filename=webview-138.apk
  2. Prebaked .apkm present                         → 200 + filename=webview-138.apkm
  3. Prebaked .apk (dot-apk extension) present      → 200 + filename=webview-138.apk
  4. No prebaked + only unrelated App Store entry   → 404 (negative control)

Byte-perfect restore of every mutated file at module teardown.
"""
import hashlib
import json
import os
import shutil
from pathlib import Path

import pytest
import requests

BASE_URL = os.environ.get("LAUNCHER_BACKEND_URL", "http://localhost:8002")
DATA_DIR = Path("/app/launcher-backend/data")
STORE_PATH = DATA_DIR / "store.json"
SYSDEPS_DIR = DATA_DIR / "system-deps"
APKS_DIR = DATA_DIR / "apks"
PREBAKED = SYSDEPS_DIR / "webview-138.apkm"
PREBAKED_BAK = SYSDEPS_DIR / "_bak_iter53.apkm"  # same directory → no x-device move
PREBAKED_APK_TMP = SYSDEPS_DIR / "webview-138.apk"
PREBAKED_MD5 = "6170eb405fb191d5bee4d6abd77e067e"

STORE_BACKUP_PATH = STORE_PATH.with_suffix(".iter53bak")


def _md5(p: Path) -> str:
    h = hashlib.md5()
    with p.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


@pytest.fixture(scope="module", autouse=True)
def snapshot_and_restore():
    # Pre-flight sanity
    assert PREBAKED.exists(), "pre-baked .apkm missing — abort"
    assert _md5(PREBAKED) == PREBAKED_MD5, "pre-baked md5 drift before tests"
    shutil.copy2(STORE_PATH, STORE_BACKUP_PATH)
    # Snapshot the apks/ directory contents so we can restore byte-perfectly
    # after _set_store creates dummy files for the test stores.
    apks_snapshot = {p.name for p in APKS_DIR.iterdir() if p.is_file()}
    yield
    # Teardown: restore everything byte-perfect.
    if PREBAKED_BAK.exists():
        if PREBAKED.exists():
            PREBAKED.unlink()
        PREBAKED_BAK.rename(PREBAKED)
    if PREBAKED_APK_TMP.exists():
        PREBAKED_APK_TMP.unlink()
    shutil.copy2(STORE_BACKUP_PATH, STORE_PATH)
    STORE_BACKUP_PATH.unlink(missing_ok=True)
    # Remove any dummy files that _set_store created during tests so the
    # next iteration sees a clean apks/ directory.
    for p in APKS_DIR.iterdir():
        if p.is_file() and p.name not in apks_snapshot:
            p.unlink(missing_ok=True)
    # Hard-assert md5 unchanged
    assert PREBAKED.exists()
    assert _md5(PREBAKED) == PREBAKED_MD5, "pre-baked md5 drifted after tests"


def _move_prebaked_out():
    if PREBAKED.exists() and not PREBAKED_BAK.exists():
        PREBAKED.rename(PREBAKED_BAK)


def _restore_prebaked():
    if PREBAKED_BAK.exists() and not PREBAKED.exists():
        PREBAKED_BAK.rename(PREBAKED)


def _set_store(apks: list[dict], create_dummy_files: bool = True):
    """Replace just the 'apks' array in store.json with the supplied list.

    When `create_dummy_files=True` (default), also write a small dummy
    file at the on-disk location each apk's `apk_url` resolves to.
    Stops the matcher from short-circuiting on `target_path.exists()`.
    """
    from pathlib import Path as _P
    from urllib.parse import urlparse as _urlparse
    apks_dir = _P("/app/launcher-backend/data/apks")
    store = json.loads(STORE_BACKUP_PATH.read_text())
    store["apks"] = apks
    STORE_PATH.write_text(json.dumps(store, indent=2))
    if not create_dummy_files:
        return
    for a in apks:
        rel = a.get("apk_url") or ""
        if not rel:
            continue
        path = _urlparse(rel).path if rel.startswith("http") else rel
        fn = _P(path).name
        if not fn:
            continue
        target = apks_dir / fn
        if not target.exists():
            target.write_bytes(b"x" * 1024)


# ─── Tests ──────────────────────────────────────────────────────────────

def test_1_absolute_url_resolves_dot_apk_filename():
    """Production-shape: apk_url is an ABSOLUTE URL, file on disk is .apk,
    prebaked moved out → matcher must Path-3 resolve via _resolve_apk_url
    and the response Content-Disposition must carry filename=webview-138.apk
    (not .apkm — preserve the real suffix)."""
    _move_prebaked_out()
    _set_store([{
        "id": "prod1",
        "name": "com.android.chrome_138.0.7204.180-720418027_24lang_2feat_8be1d6ffed5c7c318377a0b26632107f_apkmirror.com",
        "apk_url": "https://onnowtv.duckdns.org/launcher/assets/apks/d22b455c094b_com.android.chrome_138.0.7204.180-720418027_24lang_2feat_chrome.apk",
        "version_name": "138.0.7204.180",
    }])
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, timeout=15)
    assert r.status_code == 200, f"expected 200; got {r.status_code} body={r.text[:200]}"
    cd = r.headers.get("content-disposition", "")
    assert 'filename="webview-138.apk"' in cd, f"want .apk filename, got cd={cd!r}"
    assert "webview-138.apkm" not in cd, f"must NOT be .apkm, got cd={cd!r}"
    # Tiny dummy file is 1024 bytes
    assert len(r.content) == 1024


def test_2_prebaked_apkm_present_returns_apkm_filename():
    """Restore prebaked .apkm — endpoint should serve it with .apkm name."""
    _restore_prebaked()
    _set_store([])  # only Path-1 should fire
    r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                     allow_redirects=False, stream=True, timeout=15)
    try:
        assert r.status_code == 200
        cd = r.headers.get("content-disposition", "")
        assert 'filename="webview-138.apkm"' in cd, f"want .apkm filename, got cd={cd!r}"
    finally:
        r.close()


def test_3_prebaked_dot_apk_returns_apk_filename():
    """If admin drops a webview-138.apk (single APK) into system-deps/,
    Path-1 fallback should serve it with the real .apk suffix."""
    _move_prebaked_out()  # rename .apkm → _bak_iter53.apkm
    PREBAKED_APK_TMP.write_bytes(b"\x00" * 128)
    try:
        _set_store([])
        r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                         allow_redirects=False, timeout=15)
        assert r.status_code == 200, f"expected 200; got {r.status_code} body={r.text[:200]}"
        cd = r.headers.get("content-disposition", "")
        assert 'filename="webview-138.apk"' in cd, f"want .apk filename, got cd={cd!r}"
        assert len(r.content) == 128
    finally:
        PREBAKED_APK_TMP.unlink(missing_ok=True)
        _restore_prebaked()


def test_4_no_prebaked_no_match_returns_404():
    """Negative control — must not false-positive on an unrelated app."""
    _move_prebaked_out()
    _set_store([{
        "id": "abc",
        "name": "On Now Tv Live",
        "apk_url": "/assets/apks/6878bf6fc5d6_On Now Tv Live.apk",
        "version_name": "1.0",
    }])
    try:
        r = requests.get(f"{BASE_URL}/api/system-deps/webview-138.apkm",
                         allow_redirects=False, timeout=15)
        assert r.status_code == 404, (
            f"expected 404 for unrelated-only store; got {r.status_code} "
            f"body={r.text[:200]}"
        )
    finally:
        _restore_prebaked()
