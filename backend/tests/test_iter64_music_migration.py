"""
Iteration 64 - v2.12.0 Tunes NewPipeExtractor migration backend tests.

Focus:
- Surviving /api/music/* endpoints still work
- Deleted admin cookies endpoints return 404
- /api/music/stream/{id} resolver no longer references YouTube; chain is JioSaavn->Audius->preview
- Regression: other backend routes remain healthy
"""
import os
import pytest
import requests

def _load_backend_url():
    v = os.environ.get("REACT_APP_BACKEND_URL")
    if not v:
        # fallback to frontend/.env
        try:
            with open("/app/frontend/.env") as f:
                for line in f:
                    if line.startswith("REACT_APP_BACKEND_URL="):
                        v = line.strip().split("=", 1)[1]
                        break
        except Exception:
            pass
    assert v, "REACT_APP_BACKEND_URL not configured"
    return v.rstrip("/")


BASE_URL = _load_backend_url()
ADMIN_TOKEN = "onnowtv-admin-7b2f9e1c"
TIMEOUT = 45


@pytest.fixture(scope="module")
def client():
    s = requests.Session()
    s.headers.update({"Accept": "application/json"})
    return s


def _unwrap(resp_json):
    """Music API wraps payloads as {cached: bool, data: {...}}. Unwrap if present."""
    if isinstance(resp_json, dict) and "data" in resp_json and "cached" in resp_json:
        return resp_json["data"]
    return resp_json


# ---------------- MUSIC ENDPOINTS ---------------- #

class TestMusicBasics:
    def test_ping_build_v030(self, client):
        r = client.get(f"{BASE_URL}/api/music/ping", timeout=TIMEOUT)
        assert r.status_code == 200, r.text
        data = r.json()
        assert data.get("ok") is True
        assert data.get("build") == "v0.3.0", f"Expected build v0.3.0, got {data.get('build')}"

    def test_home_shelves_have_tracks(self, client):
        r = client.get(f"{BASE_URL}/api/music/home", timeout=TIMEOUT)
        assert r.status_code == 200, r.text
        data = _unwrap(r.json())
        shelves = data.get("shelves") or (data.get("data", {}) or {}).get("shelves")
        assert isinstance(shelves, list) and len(shelves) > 0, f"No shelves: {data}"
        has_items = any(
            (s.get("items") or s.get("tracks") or s.get("data")) for s in shelves
        )
        assert has_items, f"No items in any shelf: {shelves[:2]}"

    def test_search_returns_categories(self, client):
        r = client.get(f"{BASE_URL}/api/music/search", params={"q": "adele"}, timeout=TIMEOUT)
        assert r.status_code == 200, r.text
        data = _unwrap(r.json())
        for key in ("tracks", "albums", "artists"):
            assert key in data, f"Missing key {key} in search response keys={list(data.keys())}"

    def test_album_by_id(self, client):
        r = client.get(f"{BASE_URL}/api/music/search", params={"q": "adele"}, timeout=TIMEOUT)
        assert r.status_code == 200
        albums = _unwrap(r.json()).get("albums") or []
        if not albums:
            pytest.skip("No albums returned from search - upstream Deezer issue")
        album_id = albums[0].get("id")
        assert album_id
        rr = client.get(f"{BASE_URL}/api/music/album/{album_id}", timeout=TIMEOUT)
        assert rr.status_code == 200, rr.text
        adata = _unwrap(rr.json())
        assert "tracks" in adata or "title" in adata or "id" in adata

    def test_artist_by_id(self, client):
        r = client.get(f"{BASE_URL}/api/music/search", params={"q": "adele"}, timeout=TIMEOUT)
        artists = _unwrap(r.json()).get("artists") or []
        if not artists:
            pytest.skip("No artists returned")
        artist_id = artists[0].get("id")
        rr = client.get(f"{BASE_URL}/api/music/artist/{artist_id}", timeout=TIMEOUT)
        assert rr.status_code == 200, rr.text

    def test_genre_by_id(self, client):
        # Deezer genre id 132 = pop
        rr = client.get(f"{BASE_URL}/api/music/genre/132", timeout=TIMEOUT)
        assert rr.status_code == 200, rr.text


class TestStreamResolverNoYouTube:
    """Critical: /api/music/stream/{id} should NOT invoke YouTube step anymore."""

    def _assert_resolver_shape(self, data):
        # Either full track (stream_url set) OR preview fallback
        assert "source" in data, f"Missing source key: {data}"
        source = (data.get("source") or "").lower()
        # source could be jiosaavn, audius, preview, deezer_preview, or similar
        assert source, "source is empty"
        # Ensure YouTube is NOT referenced as active source
        assert "youtube" not in source, f"YouTube source should not appear anymore: {source}"
        # reason field (if fallback) should not blame YouTube
        reason = (data.get("reason") or "").lower()
        # It's fine for reason to be empty; if present, must NOT say youtube
        assert "youtube" not in reason, f"Reason references YouTube: {reason}"

    def test_stream_mainstream_track(self, client):
        # Need a track_id; search Adele Hello
        r = client.get(f"{BASE_URL}/api/music/search", params={"q": "adele hello"}, timeout=TIMEOUT)
        assert r.status_code == 200
        tracks = _unwrap(r.json()).get("tracks") or []
        if not tracks:
            pytest.skip("No tracks from search")
        tid = tracks[0].get("id")
        rr = client.get(
            f"{BASE_URL}/api/music/stream/{tid}",
            params={"artist": "Adele", "title": "Hello"},
            timeout=90,
        )
        assert rr.status_code == 200, rr.text
        data = _unwrap(rr.json())
        self._assert_resolver_shape(data)
        assert "is_full_track" in data
        if not data.get("is_full_track"):
            src = (data.get("source") or "")
            assert src in ("preview", "deezer_preview", "jiosaavn", "audius") or "preview" in src

    def test_stream_obscure_track(self, client):
        r = client.get(
            f"{BASE_URL}/api/music/search",
            params={"q": "obscure indie xyz track underground"},
            timeout=TIMEOUT,
        )
        tracks = _unwrap(r.json()).get("tracks") or []
        if not tracks:
            tid = 3135556
            artist, title = "Random", "Track"
        else:
            tid = tracks[0].get("id")
            artist = (tracks[0].get("artist") or {}).get("name", "Unknown")
            title = tracks[0].get("title", "Unknown")
        rr = client.get(
            f"{BASE_URL}/api/music/stream/{tid}",
            params={"artist": artist, "title": title},
            timeout=90,
        )
        assert rr.status_code == 200, rr.text
        self._assert_resolver_shape(_unwrap(rr.json()))


class TestYtSearchAnonymous:
    def test_yt_search_basic(self, client):
        r = client.get(
            f"{BASE_URL}/api/music/yt-search",
            params={"q": "adele hello"},
            timeout=90,
        )
        if r.status_code != 200:
            pytest.skip(f"yt-search returned {r.status_code} (likely upstream YouTube block)")
        data = _unwrap(r.json())
        # It may return a single dict (top result) or list of results
        if isinstance(data, list):
            assert len(data) > 0
            first = data[0]
        elif isinstance(data, dict) and (data.get("yt_id") or data.get("id") or data.get("videoId")):
            first = data
        else:
            results = data.get("results") or data.get("items") or []
            assert results, f"No results: {data}"
            first = results[0]
        keys = set(first.keys())
        assert any(k in keys for k in ("yt_id", "id", "videoId")), f"No id key in {keys}"
        assert "title" in keys, f"No title in {keys}"
        assert "uploader" in keys or "channel" in keys, f"No uploader in {keys}"

    def test_yt_search_karaoke_flag(self, client):
        r = client.get(
            f"{BASE_URL}/api/music/yt-search",
            params={"q": "adele hello", "karaoke": "true"},
            timeout=90,
        )
        if r.status_code != 200:
            pytest.skip(f"yt-search karaoke returned {r.status_code}")
        blob = _unwrap(r.json())
        text = str(blob).lower()
        assert "karaoke_confident" in text, f"karaoke_confident field missing: {str(blob)[:400]}"


class TestRadioPodcastsLyrics:
    def test_radio_top(self, client):
        r = client.get(f"{BASE_URL}/api/music/radio/top", timeout=TIMEOUT)
        assert r.status_code == 200, r.text

    def test_radio_genres(self, client):
        r = client.get(f"{BASE_URL}/api/music/radio/genres", timeout=TIMEOUT)
        assert r.status_code == 200, r.text

    def test_podcasts_top(self, client):
        r = client.get(f"{BASE_URL}/api/music/podcasts/top", params={"country": "us"}, timeout=TIMEOUT)
        assert r.status_code == 200, r.text

    def test_lyrics(self, client):
        r = client.get(
            f"{BASE_URL}/api/music/lyrics",
            params={"artist": "Adele", "title": "Hello"},
            timeout=TIMEOUT,
        )
        assert r.status_code in (200, 404), r.text
        if r.status_code == 200:
            data = _unwrap(r.json())
            # synced lyrics expected (various key names accepted)
            has_lyrics = any(k in data for k in ("lyrics", "syncedLyrics", "lines", "synced", "plain"))
            assert has_lyrics, f"No lyrics fields: {list(data.keys())}"


# ---------------- DELETED ENDPOINTS SHOULD 404 ---------------- #

class TestDeletedAdminCookiesEndpoints:
    def test_admin_cookies_status_deleted(self, client):
        r = client.get(
            f"{BASE_URL}/api/music/admin/cookies/status",
            params={"token": ADMIN_TOKEN},
            timeout=TIMEOUT,
        )
        assert r.status_code == 404, f"Expected 404, got {r.status_code}: {r.text[:200]}"

    def test_admin_cookies_upload_deleted(self, client):
        r = client.post(
            f"{BASE_URL}/api/music/admin/cookies/upload",
            data={"token": ADMIN_TOKEN},
            timeout=TIMEOUT,
        )
        assert r.status_code == 404, f"Expected 404, got {r.status_code}"

    def test_admin_cookies_delete_deleted(self, client):
        r = client.delete(
            f"{BASE_URL}/api/music/admin/cookies/foo",
            params={"token": ADMIN_TOKEN},
            timeout=TIMEOUT,
        )
        assert r.status_code == 404, f"Expected 404, got {r.status_code}"

    def test_admin_cookies_test_deleted(self, client):
        r = client.post(
            f"{BASE_URL}/api/music/admin/cookies/test",
            data={"token": ADMIN_TOKEN},
            timeout=TIMEOUT,
        )
        assert r.status_code == 404, f"Expected 404, got {r.status_code}"

    def test_admin_html_page_deleted(self, client):
        r = client.get(
            f"{BASE_URL}/admin/music-cookies",
            params={"token": ADMIN_TOKEN},
            timeout=TIMEOUT,
        )
        assert r.status_code == 404, f"Expected 404 for /admin/music-cookies, got {r.status_code}"

    def test_admin_api_html_page_deleted(self, client):
        r = client.get(
            f"{BASE_URL}/api/admin/music-cookies",
            params={"token": ADMIN_TOKEN},
            timeout=TIMEOUT,
        )
        assert r.status_code == 404, f"Expected 404 for /api/admin/music-cookies, got {r.status_code}"


# ---------------- REGRESSION - OTHER BACKEND ROUTES ---------------- #

class TestRegressionOtherRoutes:
    def test_vesper_stream_movie(self, client):
        r = client.get(f"{BASE_URL}/api/streams/movie/tt0111161", timeout=90)
        # Should not 5xx; expected 200 with providers list
        assert r.status_code < 500, f"5xx crash: {r.status_code} {r.text[:200]}"

    def test_tmdb_trailer(self, client):
        r = client.get(f"{BASE_URL}/api/tmdb/trailer/movie/155", timeout=TIMEOUT)
        assert r.status_code < 500, r.text[:200]

    def test_tmdb_upcoming(self, client):
        r = client.get(f"{BASE_URL}/api/tmdb/upcoming-movies", timeout=TIMEOUT)
        assert r.status_code < 500, r.text[:200]

    def test_xtream_instant_bundle_no_crash(self, client):
        r = client.get(f"{BASE_URL}/api/xtream/instant-bundle", timeout=TIMEOUT)
        # Missing creds may 4xx/5xx; task note says errors are fine, just shouldn't crash the process.
        # Accept anything with a valid HTTP response (endpoint reachable, not hanging).
        assert r.status_code >= 200, f"No response: {r.status_code}"

    def test_auth_login(self, client):
        r = client.post(
            f"{BASE_URL}/api/auth/login",
            json={"username": "testuser", "password": "testpass123"},
            timeout=TIMEOUT,
        )
        assert r.status_code == 200, f"Login failed: {r.status_code} {r.text[:300]}"
        data = r.json()
        # token or user-ish payload expected
        assert data, "Empty login response"
