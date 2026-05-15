"""Tests for /api/sportsdb/* endpoints + quick smoke checks on existing routes."""

import os
import time

import pytest
import requests

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL")
if not BASE_URL:
    # Fall back to frontend/.env so tests still run when the variable isn't exported.
    env_path = "/app/frontend/.env"
    if os.path.exists(env_path):
        with open(env_path) as f:
            for line in f:
                if line.startswith("REACT_APP_BACKEND_URL="):
                    BASE_URL = line.strip().split("=", 1)[1]
                    break
BASE_URL = (BASE_URL or "").rstrip("/")
assert BASE_URL, "REACT_APP_BACKEND_URL must be set"


# ---------------------------------------------------------------------------
# /api/sportsdb/leagues
# ---------------------------------------------------------------------------
class TestSportsdbLeagues:
    def test_leagues_shape(self):
        r = requests.get(f"{BASE_URL}/api/sportsdb/leagues", timeout=15)
        assert r.status_code == 200, r.text
        data = r.json()
        assert "leagues" in data
        assert "sports" in data
        leagues = data["leagues"]
        sports = data["sports"]
        assert isinstance(leagues, list) and len(leagues) >= 20, f"expected curated leagues, got {len(leagues)}"
        # Every league must have id, name, sport, country, season
        for lg in leagues:
            for key in ("id", "name", "sport", "country", "season"):
                assert key in lg, f"league missing {key}: {lg}"
        # Sports must cover the icon map
        sport_names = {s["name"] for s in sports}
        assert {"Soccer", "Basketball", "Ice Hockey", "Baseball", "Motorsport"}.issubset(sport_names)
        for s in sports:
            assert "emoji" in s and "color" in s


# ---------------------------------------------------------------------------
# /api/sportsdb/fixtures
# ---------------------------------------------------------------------------
class TestSportsdbFixtures:
    def test_fixtures_aggregated_shape_and_volume(self):
        # The agent-to-agent note says cache is likely already populated. Try cached
        # first (fast), and only refresh if cached payload looks too small.
        r = requests.get(f"{BASE_URL}/api/sportsdb/fixtures", timeout=60)
        assert r.status_code == 200, r.text
        data = r.json()

        # Required top-level keys
        for key in ("cached", "fetched_at", "events", "bySport", "byLeague", "sportsMeta"):
            assert key in data, f"missing top-level key {key}"
        assert isinstance(data["fetched_at"], int) and data["fetched_at"] > 0
        events = data["events"]
        by_sport = data["bySport"]

        # If hitting a cold cache somehow, allow one refresh+retry
        if len(events) < 100 or len(by_sport.keys()) < 8:
            r2 = requests.get(f"{BASE_URL}/api/sportsdb/fixtures?refresh=1", timeout=120)
            assert r2.status_code == 200, r2.text
            data = r2.json()
            events = data["events"]
            by_sport = data["bySport"]

        assert len(events) >= 100, f"expected >=100 events, got {len(events)}"
        assert len(by_sport.keys()) >= 8, f"expected events across >=8 sports, got {list(by_sport.keys())}"

        # Each event should have the normalised fields the frontend expects
        sample = events[0]
        for key in (
            "id", "title", "ts", "home", "away",
            "homeBadge", "awayBadge", "leagueBadge",
            "venue", "league", "sport",
        ):
            assert key in sample, f"event missing field {key}: {list(sample.keys())}"
        assert isinstance(sample["ts"], int) and sample["ts"] > 0
        # Events should be sorted ascending by ts
        ts_list = [e["ts"] for e in events]
        assert ts_list == sorted(ts_list), "events not sorted by ts"

        # sportsMeta has count per sport and matches bySport
        meta_names = {s["name"] for s in data["sportsMeta"]}
        for s_name, evs in by_sport.items():
            assert s_name in meta_names, f"sportsMeta missing {s_name}"
            assert isinstance(evs, list) and len(evs) > 0

        # byLeague structure
        for lid, lg in data["byLeague"].items():
            assert "name" in lg and "sport" in lg and "events" in lg
            assert isinstance(lg["events"], list) and len(lg["events"]) > 0

    def test_fixtures_cache_behaviour(self):
        # Bypass cache → cached must be False
        r1 = requests.get(f"{BASE_URL}/api/sportsdb/fixtures?refresh=1", timeout=120)
        assert r1.status_code == 200, r1.text
        d1 = r1.json()
        assert d1.get("cached") is False, f"refresh=1 should not be cached, got cached={d1.get('cached')}"

        # Immediately after, a normal call must be cached
        time.sleep(0.5)
        r2 = requests.get(f"{BASE_URL}/api/sportsdb/fixtures", timeout=30)
        assert r2.status_code == 200, r2.text
        d2 = r2.json()
        assert d2.get("cached") is True, f"second call should be cached, got cached={d2.get('cached')}"
        # fetched_at should be identical (it's the same cached payload)
        assert d1["fetched_at"] == d2["fetched_at"]


# ---------------------------------------------------------------------------
# /api/sportsdb/league-season
# ---------------------------------------------------------------------------
class TestSportsdbLeagueSeason:
    def test_league_season_epl_and_cache(self):
        # First call
        r1 = requests.get(
            f"{BASE_URL}/api/sportsdb/league-season",
            params={"league_id": "4328"},
            timeout=30,
        )
        assert r1.status_code == 200, r1.text
        d1 = r1.json()
        assert d1.get("league_id") == "4328"
        assert d1.get("season")  # default season picked from TOP_LEAGUES
        assert "events" in d1
        assert isinstance(d1["events"], list)
        # EPL typically has many events; allow empty if SportsDB sample is sparse but log it.
        # cached should be False on first miss (or True if already cached from earlier test)
        assert "cached" in d1

        # Second call → should be cached
        r2 = requests.get(
            f"{BASE_URL}/api/sportsdb/league-season",
            params={"league_id": "4328"},
            timeout=30,
        )
        assert r2.status_code == 200, r2.text
        d2 = r2.json()
        assert d2.get("cached") is True, f"second league-season call should be cached, got {d2.get('cached')}"

        # If we got events, each must be normalised
        if d1["events"]:
            ev = d1["events"][0]
            for key in ("id", "title", "ts", "home", "away", "leagueBadge"):
                assert key in ev


# ---------------------------------------------------------------------------
# Smoke checks on existing endpoints
# ---------------------------------------------------------------------------
class TestExistingEndpointsSmoke:
    def test_sports_find_alive(self):
        # LLM concierge endpoint — just verify it responds (200 or 4xx, not 5xx)
        r = requests.get(f"{BASE_URL}/api/sports/find", params={"q": "manchester"}, timeout=30)
        # Some implementations require POST; accept either route style without 5xx
        assert r.status_code < 500, f"/api/sports/find returned {r.status_code}: {r.text[:200]}"

    def test_tmdb_search_alive(self):
        r = requests.get(f"{BASE_URL}/api/tmdb/search", params={"q": "inception"}, timeout=30)
        assert r.status_code < 500, f"tmdb search returned {r.status_code}: {r.text[:200]}"

    def test_img_proxy_alive(self):
        # Pull a real, currently-valid league badge from the live fixtures payload,
        # so this is a true endpoint smoke test (not a stale-URL test).
        fx = requests.get(f"{BASE_URL}/api/sportsdb/fixtures", timeout=30).json()
        badge = ""
        for e in fx.get("events", []):
            badge = e.get("leagueBadge") or e.get("homeBadge") or e.get("awayBadge")
            if badge:
                break
        if not badge:
            pytest.skip("No badge URL available in fixtures payload to test img-proxy")
        r = requests.get(f"{BASE_URL}/api/img-proxy", params={"url": badge}, timeout=30)
        assert r.status_code < 500, f"img-proxy returned {r.status_code}: {r.text[:200]}"
