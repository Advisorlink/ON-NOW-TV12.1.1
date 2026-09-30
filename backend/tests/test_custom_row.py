"""Backend tests for /api/tmdb/custom-row (Vesper Home custom category rail)."""
import os
import pytest
import requests

BASE_URL = os.environ.get('REACT_APP_BACKEND_URL', 'https://rebrand-app-5.preview.emergentagent.com').rstrip('/')


@pytest.fixture(scope='module')
def api():
    s = requests.Session()
    s.headers.update({'Content-Type': 'application/json'})
    return s


class TestCustomRow:
    def test_horror_and_comedy(self, api):
        r = api.get(f"{BASE_URL}/api/tmdb/custom-row", params={'q': 'horror and comedy', 'limit': 8}, timeout=30)
        assert r.status_code == 200, r.text
        data = r.json()
        assert data.get('label') == 'Horror & Comedy', data
        matched = data.get('matched') or {}
        assert set(matched.get('genres') or []) == {'Horror', 'Comedy'}, matched
        assert matched.get('media') == ['movie'], matched
        items = data.get('data') or data.get('items') or []
        assert len(items) == 8, f"expected 8 items got {len(items)}"
        for it in items:
            assert 'tmdb_id' in it or 'id' in it
            assert 'type' in it or 'media_type' in it
            assert 'title' in it or 'name' in it
            assert 'poster' in it or 'poster_path' in it

    def test_superhero_series(self, api):
        r = api.get(f"{BASE_URL}/api/tmdb/custom-row", params={'q': 'superhero series', 'limit': 8}, timeout=30)
        assert r.status_code == 200, r.text
        matched = r.json().get('matched') or {}
        assert matched.get('media') == ['tv'], matched

    def test_no_results(self, api):
        r = api.get(f"{BASE_URL}/api/tmdb/custom-row", params={'q': 'zzqxjv', 'limit': 8}, timeout=30)
        assert r.status_code == 200, r.text
        items = r.json().get('data') or r.json().get('items') or []
        assert items == [], items

    def test_empty_q_422(self, api):
        r = api.get(f"{BASE_URL}/api/tmdb/custom-row", params={'q': ''}, timeout=30)
        assert r.status_code == 422, r.status_code

    def test_sports_movies(self, api):
        r = api.get(f"{BASE_URL}/api/tmdb/custom-row", params={'q': 'sports movies', 'limit': 8}, timeout=30)
        assert r.status_code == 200, r.text
        data = r.json()
        # Label should collapse to "Sports"
        assert data.get('label') == 'Sports', data.get('label')
