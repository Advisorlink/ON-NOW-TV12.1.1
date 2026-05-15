"""
Iter 29 — Profile Backup & Restore endpoint tests.
Covers acceptance criteria [A]–[G] from the review request.
"""
import os
import re
import pytest
import requests

BASE_URL = os.environ["REACT_APP_BACKEND_URL"].rstrip("/")
SAVE = f"{BASE_URL}/api/backup/save"
RESTORE = f"{BASE_URL}/api/backup/restore"
REFRESH = f"{BASE_URL}/api/backup/refresh"

SAMPLE_PAYLOAD = {
    "onnowtv-profiles-v1": [{"id": "p1", "name": "Tester", "avatar": "🦊"}],
    "onnowtv-active-profile-v1": "p1",
    "onnowtv-continue-watching:p1": [{"imdbId": "tt0816692", "position_ms": 12345}],
    "onnowtv-library:p1": ["tt0816692", "tt0903747"],
    "onnowtv-theme": "dark",
    "vesper-live-favourites": ["1001", "1042"],
}


# ---------- [A] BACKUP SAVE ----------
class TestBackupSave:
    def test_save_returns_code_and_metadata(self):
        r = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        assert r.status_code == 200, r.text
        data = r.json()
        assert "code" in data and "expires_at" in data and "size_bytes" in data
        assert isinstance(data["code"], str)
        assert len(data["code"]) == 6
        # Alphanumeric uppercase (the impl excludes 0/O/1/I/L/U but result is still subset of [A-Z0-9])
        assert re.fullmatch(r"[A-Z0-9]{6}", data["code"]), f"bad code: {data['code']}"
        assert isinstance(data["size_bytes"], int) and data["size_bytes"] > 0

    def test_two_saves_return_different_codes(self):
        r1 = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        r2 = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        assert r1.status_code == 200 and r2.status_code == 200
        assert r1.json()["code"] != r2.json()["code"]


# ---------- [B][C][D] BACKUP RESTORE ----------
class TestBackupRestore:
    @pytest.fixture(scope="class")
    def saved_code(self):
        r = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        assert r.status_code == 200
        return r.json()["code"]

    def test_restore_wrong_pin_returns_403(self, saved_code):
        r = requests.post(RESTORE, json={"code": saved_code, "pin": "0000"}, timeout=15)
        assert r.status_code == 403, r.text
        body = r.json()
        assert "Incorrect PIN" in (body.get("detail") or body.get("message") or "")

    def test_restore_right_pin_returns_payload_verbatim(self, saved_code):
        r = requests.post(RESTORE, json={"code": saved_code, "pin": "1234"}, timeout=15)
        assert r.status_code == 200, r.text
        data = r.json()
        assert "payload" in data and "created_at" in data and "size_bytes" in data
        # Payload matches verbatim
        assert data["payload"] == SAMPLE_PAYLOAD

    def test_restore_bad_code_returns_404(self):
        # Use a code shape that passes length-6 validation but does not exist.
        r = requests.post(RESTORE, json={"code": "ZZZZZZ", "pin": "1234"}, timeout=15)
        assert r.status_code == 404, r.text


# ---------- [E] BACKUP REFRESH ----------
class TestBackupRefresh:
    def test_refresh_returns_new_expiry(self):
        r = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        code = r.json()["code"]
        original_exp = r.json()["expires_at"]
        # Refresh
        rr = requests.post(REFRESH, json={"code": code, "pin": "1234"}, timeout=15)
        assert rr.status_code == 200, rr.text
        new_exp = rr.json().get("expires_at")
        assert new_exp is not None
        # New expiry should be >= original (could be equal if clock resolution is identical,
        # but should generally be later).
        assert new_exp >= original_exp

    def test_refresh_wrong_pin_403(self):
        r = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        code = r.json()["code"]
        rr = requests.post(REFRESH, json={"code": code, "pin": "9999"}, timeout=15)
        assert rr.status_code == 403


# ---------- [F] PIN VALIDATION ----------
class TestPinValidation:
    @pytest.mark.parametrize("bad_pin", ["abcd", "12a4", "12345", "123", "    "])
    def test_save_rejects_bad_pin(self, bad_pin):
        r = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": bad_pin}, timeout=15)
        # Either pydantic-level 422 (length mismatch) or our custom 400 (non-digit).
        # The brief says 400; accept 422 too because pydantic min_length=4/max_length=4
        # will produce 422 BEFORE our validator runs.
        assert r.status_code in (400, 422), f"got {r.status_code}: {r.text}"

    def test_restore_rejects_non_digit_pin(self):
        # Need a real code first so validation order doesn't short-circuit on missing record.
        sr = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        code = sr.json()["code"]
        r = requests.post(RESTORE, json={"code": code, "pin": "abcd"}, timeout=15)
        assert r.status_code in (400, 422)

    def test_restore_rejects_wrong_length_pin(self):
        sr = requests.post(SAVE, json={"payload": SAMPLE_PAYLOAD, "pin": "1234"}, timeout=15)
        code = sr.json()["code"]
        r = requests.post(RESTORE, json={"code": code, "pin": "12345"}, timeout=15)
        assert r.status_code in (400, 422)


# ---------- [G] CODE VALIDATION ----------
class TestCodeValidation:
    @pytest.mark.parametrize("bad_code", ["ABC", "ABCDEFG", "", "ABCDE"])
    def test_restore_rejects_malformed_code(self, bad_code):
        r = requests.post(RESTORE, json={"code": bad_code, "pin": "1234"}, timeout=15)
        assert r.status_code == 400, f"got {r.status_code}: {r.text}"
