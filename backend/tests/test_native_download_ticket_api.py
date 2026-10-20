"""Native APK ticket API + range/stream contract tests over public URL and unit helpers."""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import time
from pathlib import Path

import pytest
import requests

from native_download_ticket import TICKET_TTL, TicketLogFilter, valid_ticket


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


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def _sign(body: str, capability: str) -> str:
    key = hashlib.sha256(capability.encode()).digest()
    return _b64(hmac.new(key, body.encode("ascii"), hashlib.sha256).digest())


def _make_ticket(payload: dict, capability: str) -> str:
    body = _b64(json.dumps(payload, separators=(",", ":")).encode())
    return f"{body}.{_sign(body, capability)}"


def _read_content_length_values(response: requests.Response) -> list[str]:
    # Coverage area: protect against duplicate Content-Length header variants.
    raw = response.raw.headers
    if hasattr(raw, "getlist"):
        return list(raw.getlist("Content-Length"))
    value = response.headers.get("Content-Length")
    return [value] if value is not None else []


@pytest.fixture(scope="module")
def http_session():
    # Module coverage: external /api/download endpoint contract over public URL.
    if not BASE_URL:
        pytest.skip("REACT_APP_BACKEND_URL missing in /app/frontend/.env")
    session = requests.Session()
    session.headers.update({"Accept": "*/*"})
    return session


@pytest.fixture(scope="module")
def capability_token() -> str:
    if not TOKEN:
        pytest.skip("APK_DOWNLOAD_CAPABILITY_TOKEN missing in /app/backend/.env")
    return TOKEN


@pytest.fixture(scope="module")
def local_apk_bytes() -> bytes:
    if not APK_PATH.exists():
        pytest.skip("Local debug APK missing")
    return APK_PATH.read_bytes()


def test_ticket_post_missing_token_404(http_session):
    response = http_session.post(f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket", timeout=30)
    assert response.status_code == 404
    assert "no-store" in response.headers.get("Cache-Control", "")


def test_ticket_post_wrong_token_404(http_session):
    response = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": "wrong-token"},
        timeout=30,
    )
    assert response.status_code == 404
    assert "no-store" in response.headers.get("Cache-Control", "")


def test_original_header_get_works_query_only_and_anonymous_404(http_session, capability_token):
    base = f"{BASE_URL.rstrip('/')}/api/download/vesper-native-apk"
    anon = http_session.get(base, timeout=30)
    assert anon.status_code == 404
    by_query = http_session.get(f"{base}?token=non-secret-query-test", timeout=30)
    assert by_query.status_code == 404
    by_header = http_session.get(base, headers={"X-Download-Token": capability_token}, timeout=120)
    assert by_header.status_code == 200
    assert by_header.headers.get("Content-Type", "").startswith("application/vnd.android.package-archive")


def test_ticket_post_returns_small_no_store_metadata(http_session, capability_token):
    response = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token, "Accept": "application/json"},
        timeout=30,
    )
    assert response.status_code == 200
    assert len(response.content) < 1024
    assert "no-store" in response.headers.get("Cache-Control", "")
    payload = response.json()
    assert payload["url"].startswith("/api/download/vesper-native-file?ticket=")
    assert int(payload["expiresIn"]) == TICKET_TTL == 3600
    assert isinstance(payload["bytes"], int) and payload["bytes"] > 1024


def test_ticket_get_and_head_contract_headers(http_session, capability_token):
    ticket_payload = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()
    file_url = f"{BASE_URL.rstrip('/')}{ticket_payload['url']}"

    get_resp = http_session.get(file_url, timeout=120)
    assert get_resp.status_code == 200
    assert get_resp.headers.get("Content-Disposition", "").startswith("attachment;")
    assert "no-store" in get_resp.headers.get("Cache-Control", "")
    assert get_resp.headers.get("Referrer-Policy") == "no-referrer"

    head_resp = http_session.head(file_url, timeout=120)
    assert head_resp.status_code == 200
    assert head_resp.headers.get("Content-Disposition", "").startswith("attachment;")
    assert head_resp.headers.get("Content-Length") == get_resp.headers.get("Content-Length")
    assert head_resp.content == b""


def test_ticket_replay_allowed_within_ttl(http_session, capability_token):
    ticket_url = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()["url"]
    full = f"{BASE_URL.rstrip('/')}{ticket_url}"
    first = http_session.get(full, timeout=120)
    second = http_session.get(full, timeout=120)
    assert first.status_code == 200
    assert second.status_code == 200


def test_range_prefix_and_single_content_length_header(http_session, capability_token, local_apk_bytes):
    ticket_url = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()["url"]
    response = http_session.get(
        f"{BASE_URL.rstrip('/')}{ticket_url}",
        headers={"Range": "bytes=0-1023"},
        timeout=120,
    )
    assert response.status_code == 206
    assert response.content == local_apk_bytes[:1024]
    assert response.headers.get("Content-Range", "").startswith("bytes 0-1023/")
    content_lengths = _read_content_length_values(response)
    assert len(content_lengths) == 1
    assert content_lengths[0] == "1024"


def test_range_suffix_and_open_ended(http_session, capability_token, local_apk_bytes):
    ticket_url = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()["url"]
    full_url = f"{BASE_URL.rstrip('/')}{ticket_url}"
    suffix = http_session.get(full_url, headers={"Range": "bytes=-1024"}, timeout=120)
    assert suffix.status_code == 206
    assert suffix.content == local_apk_bytes[-1024:]
    opened = http_session.get(full_url, headers={"Range": "bytes=1024-"}, timeout=120)
    assert opened.status_code == 206
    assert opened.content == local_apk_bytes[1024:]


@pytest.mark.parametrize(
    "range_header",
    ["bytes=", "bytes=-0", "bytes=999999999-", "bytes=0-2,4-8", "bytes=abc-def"],
)
def test_invalid_ranges_return_416(http_session, capability_token, range_header):
    ticket_url = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()["url"]
    response = http_session.get(
        f"{BASE_URL.rstrip('/')}{ticket_url}",
        headers={"Range": range_header},
        timeout=120,
    )
    assert response.status_code == 416
    assert response.headers.get("Content-Range", "").startswith("bytes */")


def test_if_range_mismatch_returns_full_200_matching_etag_returns_206(http_session, capability_token):
    ticket_url = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()["url"]
    full_url = f"{BASE_URL.rstrip('/')}{ticket_url}"
    full = http_session.get(full_url, timeout=120)
    etag = full.headers.get("ETag")
    mismatch = http_session.get(
        full_url,
        headers={"Range": "bytes=0-127", "If-Range": '"not-matching"'},
        timeout=120,
    )
    assert mismatch.status_code == 200
    assert len(mismatch.content) == len(full.content)
    matched = http_session.get(full_url, headers={"Range": "bytes=0-127", "If-Range": etag}, timeout=120)
    assert matched.status_code == 206
    assert len(matched.content) == 128


def test_ticket_get_head_no_body_and_content_length_matches_local(http_session, capability_token, local_apk_bytes):
    ticket_url = http_session.post(
        f"{BASE_URL.rstrip('/')}/api/download/vesper-native-ticket",
        headers={"X-Download-Token": capability_token},
        timeout=30,
    ).json()["url"]
    head = http_session.head(f"{BASE_URL.rstrip('/')}{ticket_url}", timeout=120)
    assert head.status_code == 200
    assert head.content == b""
    assert int(head.headers["Content-Length"]) == len(local_apk_bytes)


def test_ticket_rejects_tampered_expired_future_wrong_aud_artifact_and_bad_signature(monkeypatch, capability_token, local_apk_bytes):
    monkeypatch.setenv("APK_DOWNLOAD_CAPABILITY_TOKEN", capability_token)
    stat_stub = type("Stat", (), {"st_size": len(local_apk_bytes), "st_mtime_ns": 123456789})
    now = int(time.time())
    good_payload = {
        "v": 1,
        "aud": "vesper-native-private-apk",
        "exp": now + 60,
        "artifact": f"{len(local_apk_bytes)}:123456789",
        "jti": "test-jti",
    }
    good_ticket = _make_ticket(good_payload, capability_token)
    assert valid_ticket(good_ticket, stat_stub)

    body, sig = good_ticket.split(".")
    tampered = body + "." + ("A" if sig[0] != "A" else "B") + sig[1:]
    assert not valid_ticket(tampered, stat_stub)

    expired = dict(good_payload, exp=now - 1)
    assert not valid_ticket(_make_ticket(expired, capability_token), stat_stub)

    too_future = dict(good_payload, exp=now + TICKET_TTL + 30)
    assert not valid_ticket(_make_ticket(too_future, capability_token), stat_stub)

    wrong_aud = dict(good_payload, aud="wrong-audience")
    assert not valid_ticket(_make_ticket(wrong_aud, capability_token), stat_stub)

    wrong_artifact = dict(good_payload, artifact=f"{len(local_apk_bytes)}:987654321")
    assert not valid_ticket(_make_ticket(wrong_artifact, capability_token), stat_stub)


def test_ticket_invalid_when_artifact_changes(monkeypatch, capability_token, local_apk_bytes):
    monkeypatch.setenv("APK_DOWNLOAD_CAPABILITY_TOKEN", capability_token)
    now = int(time.time())
    payload = {
        "v": 1,
        "aud": "vesper-native-private-apk",
        "exp": now + 120,
        "artifact": f"{len(local_apk_bytes)}:123",
        "jti": "abc",
    }
    ticket = _make_ticket(payload, capability_token)
    original = type("Stat", (), {"st_size": len(local_apk_bytes), "st_mtime_ns": 123})
    changed = type("Stat", (), {"st_size": len(local_apk_bytes), "st_mtime_ns": 124})
    assert valid_ticket(ticket, original)
    assert not valid_ticket(ticket, changed)


def test_ticket_missing_secret_rejected(monkeypatch, local_apk_bytes):
    monkeypatch.delenv("APK_DOWNLOAD_CAPABILITY_TOKEN", raising=False)
    payload = {
        "v": 1,
        "aud": "vesper-native-private-apk",
        "exp": int(time.time()) + 10,
        "artifact": f"{len(local_apk_bytes)}:1",
        "jti": "none",
    }
    ticket = _make_ticket(payload, "placeholder")
    stat_stub = type("Stat", (), {"st_size": len(local_apk_bytes), "st_mtime_ns": 1})
    assert not valid_ticket(ticket, stat_stub)


def test_access_log_filter_redacts_only_ticket_query_and_preserves_other_targets():
    filter_ = TicketLogFilter()
    rec_ticket = type("Record", (), {"args": ("127.0.0.1", "GET", "/api/download/vesper-native-file?ticket=abc", "HTTP/1.1", 200)})
    rec_other = type("Record", (), {"args": ("127.0.0.1", "GET", "/api/download/vesper-native?foo=bar", "HTTP/1.1", 200)})
    rec_legacy = type("Record", (), {"args": ("127.0.0.1", "GET", "/api/download/vesper-native-apk?token=test-only", "HTTP/1.1", 404)})
    filter_.filter(rec_ticket)
    filter_.filter(rec_other)
    filter_.filter(rec_legacy)
    assert rec_ticket.args[2] == "/api/download/vesper-native-file?[redacted]"
    assert rec_other.args[2] == "/api/download/vesper-native?foo=bar"
    assert rec_legacy.args[2] == "/api/download/vesper-native-apk?[redacted]"
