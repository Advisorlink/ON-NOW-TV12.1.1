"""v2.13.11 — Backend tests for _detect_pm_cached / _detect_pm_uncached tag
helpers and /api/streams/movie/{imdb} response tagging."""
import os
import sys
import pytest
import requests

sys.path.insert(0, "/app/backend")
from server import _detect_pm_cached, _detect_pm_uncached, _tag_addon_quality_premium  # noqa: E402

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "").rstrip("/")
if not BASE_URL:
    with open("/app/frontend/.env") as f:
        for ln in f:
            if ln.startswith("REACT_APP_BACKEND_URL="):
                BASE_URL = ln.split("=", 1)[1].strip().rstrip("/")


# ---------------------------------------------------------------------------
# Unit tests: tag helpers
# ---------------------------------------------------------------------------
class TestPmTagHelpers:
    def test_pm_download_is_uncached(self):
        s = {
            "name": "[PM download] Torrentio\n1080p",
            "title": "Movie.2024.1080p",
            "url": "https://fake.example/x.mkv",
            "_addon_id": "com.stremio.torrentio.addon",
            "_addon_name": "Torrentio PM",
        }
        assert _detect_pm_uncached(s) is True
        assert _detect_pm_cached(s) is False

    def test_pm_plus_direct_https_is_cached(self):
        s = {
            "name": "[PM+] Torrentio\n1080p",
            "title": "Movie.2024.1080p",
            "url": "https://fake.example/y.mkv",
            "_addon_id": "com.stremio.torrentio.addon",
            "_addon_name": "Torrentio PM",
        }
        assert _detect_pm_uncached(s) is False
        assert _detect_pm_cached(s) is True

    def test_infohash_is_neither(self):
        s = {
            "name": "Torrentio 1080p",
            "infoHash": "abc123",
            "_addon_id": "com.stremio.torrentio.addon",
            "_addon_name": "Torrentio",
        }
        assert _detect_pm_uncached(s) is False
        assert _detect_pm_cached(s) is False

    def test_non_torrent_family_is_neither(self):
        s = {
            "name": "[PM download] WatchHub",
            "url": "https://foo/bar.mkv",
            "_addon_id": "com.watchhub.addon",
            "_addon_name": "WatchHub",
        }
        assert _detect_pm_uncached(s) is False
        assert _detect_pm_cached(s) is False

    def test_tag_addon_quality_premium_writes_both_fields(self):
        s = {
            "name": "[PM download] Torrentio 1080p",
            "url": "https://fake/x.mkv",
            "_addon_id": "com.stremio.torrentio.addon",
            "_addon_name": "Torrentio PM",
        }
        _tag_addon_quality_premium(s)
        assert s["_pm_uncached"] is True
        assert s["_pm_cached"] is False
        assert "_addon_source" in s
        assert "_quality_label" in s


# ---------------------------------------------------------------------------
# Integration: /api/streams/movie/tt1375666 returns tagged streams
# ---------------------------------------------------------------------------
class TestStreamsEndpointTagging:
    def test_all_streams_have_pm_flag_fields(self):
        assert BASE_URL, "REACT_APP_BACKEND_URL missing"
        r = requests.get(f"{BASE_URL}/api/streams/movie/tt1375666", timeout=60)
        assert r.status_code == 200, r.text[:300]
        data = r.json()
        assert "streams" in data
        streams = data["streams"]
        # Preview pod: Torrentio is CF-blocked so only watchhub externals
        # may come through — but every stream must still carry the tag keys.
        for s in streams:
            assert "_pm_cached" in s, f"missing _pm_cached in {s.get('name')!r}"
            assert "_pm_uncached" in s, f"missing _pm_uncached in {s.get('name')!r}"
            assert isinstance(s["_pm_cached"], bool)
            assert isinstance(s["_pm_uncached"], bool)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
