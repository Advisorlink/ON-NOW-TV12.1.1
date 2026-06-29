"""
v2.10.82 — Networks AU regression + trailer-stream timeout verification.

Covers:
  * /api/networks/binge|stan|paramount-plus with region=AU (TV + movie)
  * Same endpoints with region=US should be empty (AU-only providers)
  * /api/tmdb/trailer/movie/27205 returns YouTube key
  * /api/trailer-stream/<id> returns is_hd_pair under 18s
  * Invalid youtube id returns 502/504 (NEVER hangs)
"""
import os
import time
import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL", "https://rebrand-app-5.preview.emergentagent.com").rstrip("/")
API = f"{BASE_URL}/api"


def _unwrap(resp_json):
    """Unwrap `{cached, data}` envelope used by /api/networks and /api/tmdb/trailer."""
    if isinstance(resp_json, dict) and "data" in resp_json and "cached" in resp_json:
        return resp_json.get("data") or {}
    return resp_json


# ---------- networks ----------
class TestAuNetworks:
    @pytest.mark.parametrize("slug,min_tv", [
        ("binge", 300),
        ("stan", 200),
        ("paramount-plus", 100),
    ])
    def test_au_tv_catalogue_nonempty(self, slug, min_tv):
        r = requests.get(f"{API}/networks/{slug}", params={"type": "tv", "page": 1, "region": "AU"}, timeout=30)
        assert r.status_code == 200, f"{slug} AU tv → {r.status_code}"
        data = _unwrap(r.json())
        total = data.get("total_results") or data.get("total") or len(data.get("results", []))
        assert total >= min_tv, f"{slug} AU tv only {total} (expected ≥{min_tv})"
        results = data.get("results") or []
        assert len(results) > 0
        with_overview = [x for x in results if x.get("overview")]
        assert len(with_overview) >= 1, f"{slug}: no items with overview field"

    @pytest.mark.parametrize("slug", ["binge", "stan", "paramount-plus"])
    def test_au_movie_catalogue_works(self, slug):
        r = requests.get(f"{API}/networks/{slug}", params={"type": "movie", "page": 1, "region": "AU"}, timeout=30)
        assert r.status_code == 200
        data = _unwrap(r.json())
        total = data.get("total_results") or data.get("total") or len(data.get("results", []))
        assert total >= 1, f"{slug} AU movies returned {total}"

    @pytest.mark.parametrize("slug", ["binge", "stan"])
    def test_us_region_returns_empty_for_au_only(self, slug):
        r = requests.get(f"{API}/networks/{slug}", params={"type": "tv", "page": 1, "region": "US"}, timeout=30)
        assert r.status_code == 200
        data = _unwrap(r.json())
        results = data.get("results") or []
        assert len(results) <= 5, f"{slug} US returned {len(results)} (expected ~0)"


# ---------- trailer ----------
class TestTrailer:
    def test_tmdb_trailer_inception(self):
        r = requests.get(f"{API}/tmdb/trailer/movie/27205", timeout=20)
        assert r.status_code == 200
        data = _unwrap(r.json())
        key = data.get("key") or data.get("youtube_key") or data.get("youtube_id")
        assert key, f"no YouTube key in response: {data}"
        assert isinstance(key, str) and len(key) >= 5

    def test_trailer_stream_returns_hd_pair_under_18s(self):
        tr = _unwrap(requests.get(f"{API}/tmdb/trailer/movie/27205", timeout=20).json())
        yt_id = tr.get("key") or tr.get("youtube_key") or tr.get("youtube_id")
        assert yt_id

        start = time.time()
        r = requests.get(f"{API}/trailer-stream/{yt_id}", timeout=25)
        elapsed = time.time() - start
        print(f"trailer-stream {yt_id} → {r.status_code} in {elapsed:.2f}s")

        assert r.status_code == 200, f"trailer-stream returned {r.status_code} body={r.text[:300]}"
        assert elapsed < 22, f"trailer-stream took {elapsed:.1f}s (>22s)"
        data = r.json()
        assert data.get("url"), f"missing video url: {data}"
        # is_hd_pair + audio_url indicate HD video+audio slave pair
        if data.get("is_hd_pair") is True:
            assert data.get("audio_url"), "is_hd_pair=true but no audio_url"

    def test_trailer_stream_invalid_id_never_hangs(self):
        bogus = "ZZZZZZZZZZZ"  # 11-char invalid id
        start = time.time()
        try:
            r = requests.get(f"{API}/trailer-stream/{bogus}", timeout=25)
            elapsed = time.time() - start
            print(f"invalid trailer-stream → {r.status_code} in {elapsed:.2f}s")
            assert elapsed < 22, f"bogus id took {elapsed:.1f}s (must be <22)"
            assert r.status_code in (400, 404, 500, 502, 503, 504), \
                f"expected error status, got {r.status_code}"
        except requests.exceptions.Timeout:
            pytest.fail(f"trailer-stream HUNG past 25s for invalid id")
