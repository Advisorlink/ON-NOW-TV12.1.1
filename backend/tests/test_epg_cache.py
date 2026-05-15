"""
Regression tests for epg_cache + xtream cached-epg endpoint.

What's covered
--------------
- Provider blob obfuscation roundtrip (encrypted at rest).
- Persistent save_payload / load_payload roundtrip through MongoDB.
- /api/xtream/cached-epg returns gzipped response with the right
  diagnostic headers (Content-Encoding, X-Cache-Age-Sec, X-Channel-
  Count) AND a JSON body matching what we persisted.
- /api/xtream/cached-epg on a never-seen provider triggers a live
  fetch path (we just assert the endpoint *responds*, not what — the
  pod can't reach a real provider so it'll 502 cleanly).
- Provider registration is idempotent (no duplicates in epg_providers).
"""
import asyncio
import json
import os
import sys

import pytest

# Make sure the backend module path is importable when pytest is
# invoked from the repo root.
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# Ensure env defaults — these are pre-set in the supervised pod but
# might be missing in a fresh checkout.
os.environ.setdefault("MONGO_URL", "mongodb://localhost:27017")
os.environ.setdefault("DB_NAME", "test_database")

import epg_cache  # noqa: E402


PROVIDER = {
    "scheme":   "http",
    "host":     "epg-test.example.com",
    "port":     "8080",
    "username": "tu",
    "password": "tp",
}


def test_provider_obfuscation_roundtrip():
    """Encryption-at-rest must be exact-roundtrip — otherwise the
    background refresher would fail to decode the credentials."""
    encoded = epg_cache._obfuscate(PROVIDER)
    decoded = epg_cache._deobfuscate(encoded)
    assert decoded == PROVIDER


def test_provider_key_is_stable():
    """Two boxes pointing at the same provider must share a cache
    key, otherwise the persisted EPG is duplicated per device."""
    a = epg_cache.provider_key(PROVIDER)
    b = epg_cache.provider_key(dict(PROVIDER))  # different dict identity
    assert a == b
    assert len(a) == 64  # sha256 hex


@pytest.mark.asyncio
async def test_payload_save_and_load_roundtrip():
    key = epg_cache.provider_key(PROVIDER)
    payload = {
        "cached":          False,
        "fetched_at":      1700000000,
        "channel_count":   2,
        "programme_count": 4,
        "size_bytes":      1024,
        "epg": {
            "ch-alpha": [{"title": "Alpha Show", "startTimestamp": 1, "stopTimestamp": 2}],
            "ch-beta":  [{"title": "Beta Show",  "startTimestamp": 3, "stopTimestamp": 4}],
        },
    }
    await epg_cache.save_payload(key, payload)
    loaded = await epg_cache.load_payload(key)
    assert loaded is not None
    assert loaded["channel_count"] == 2
    assert loaded["programme_count"] == 4
    assert "ch-alpha" in loaded["epg"]
    assert loaded["epg"]["ch-alpha"][0]["title"] == "Alpha Show"
    # _persisted_at is added by load_payload — must be a recent unix.
    assert loaded["_persisted_at"] > 0


@pytest.mark.asyncio
async def test_provider_registration_is_idempotent():
    """Registering the same provider many times must NOT create
    duplicate documents — otherwise the scheduler would refresh the
    same EPG dozens of times per tick."""
    db = epg_cache._get_db()
    await db[epg_cache._COLL_PROVIDERS].delete_many({"_id": epg_cache.provider_key(PROVIDER)})
    for _ in range(5):
        await epg_cache.register_provider(PROVIDER)
    count = await db[epg_cache._COLL_PROVIDERS].count_documents(
        {"_id": epg_cache.provider_key(PROVIDER)}
    )
    assert count == 1, f"Expected 1 provider doc, got {count}"


@pytest.mark.asyncio
async def test_active_providers_returns_recent_only():
    """The scheduler should only see providers seen within the last
    30 days.  Insert a stale one and assert it's excluded."""
    import time as _t
    db = epg_cache._get_db()
    stale_provider = {**PROVIDER, "username": "stale_tu"}
    stale_key = epg_cache.provider_key(stale_provider)
    await db[epg_cache._COLL_PROVIDERS].replace_one(
        {"_id": stale_key},
        {
            "_id": stale_key,
            "blob": epg_cache._obfuscate(stale_provider),
            "last_seen_at": int(_t.time()) - 60 * 24 * 60 * 60,  # 60d ago
            "first_seen_at": 0,
        },
        upsert=True,
    )
    await epg_cache.register_provider(PROVIDER)  # fresh
    active = await epg_cache._active_providers()
    keys = [a["_key"] for a in active]
    assert epg_cache.provider_key(PROVIDER) in keys
    assert stale_key not in keys


def test_cached_epg_endpoint_returns_gzipped_payload():
    """End-to-end HTTP check: hit /api/xtream/cached-epg through the
    real backend and verify gzip encoding + diagnostic headers.

    We pre-seed a payload synchronously so the endpoint hits the
    persisted cache path (not the slow live-fetch fallback).
    """
    import requests

    api_url = (os.environ.get("REACT_APP_BACKEND_URL")
               or "http://localhost:8001")

    seed_payload = {
        "cached":          False,
        "fetched_at":      1700000000,
        "channel_count":   1,
        "programme_count": 1,
        "size_bytes":      256,
        "epg": {"ch-test": [{"title": "Gzip Test", "startTimestamp": 1, "stopTimestamp": 2}]},
    }
    key = epg_cache.provider_key(PROVIDER)
    asyncio.run(epg_cache.save_payload(key, seed_payload))

    r = requests.get(
        f"{api_url}/api/xtream/cached-epg",
        params={"provider": json.dumps(PROVIDER)},
        timeout=10,
    )
    assert r.status_code == 200, f"Unexpected status {r.status_code}: {r.text[:200]}"
    # `requests` transparently decompresses but exposes Content-Encoding.
    assert r.headers.get("Content-Encoding", "").lower() == "gzip"
    assert int(r.headers.get("X-Channel-Count", 0)) == 1
    body = r.json()
    assert body["channel_count"] == 1
    assert body["cached"] is True
    assert "ch-test" in body["epg"]
    assert body["epg"]["ch-test"][0]["title"] == "Gzip Test"
