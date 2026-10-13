"""Sports Hub API real endpoint + regression tests."""

from __future__ import annotations

import os
import sys
import time
from pathlib import Path

import pytest
import requests
from fastapi import HTTPException


def _base_url() -> str:
    """Read preview URL from env or frontend/.env without hardcoded defaults."""
    direct = os.environ.get("REACT_APP_BACKEND_URL", "").strip()
    if direct:
        return direct.rstrip("/")

    env_file = Path("/app/frontend/.env")
    if not env_file.exists():
        pytest.skip("frontend/.env missing; cannot resolve REACT_APP_BACKEND_URL")

    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key.strip() == "REACT_APP_BACKEND_URL" and value.strip():
            return value.strip().rstrip("/")

    pytest.skip("REACT_APP_BACKEND_URL not configured")


BASE_URL = _base_url()


@pytest.fixture(scope="module")
def api_client() -> requests.Session:
    session = requests.Session()
    session.headers.update({"Accept": "application/json"})
    return session


@pytest.fixture(scope="module")
def sample_game(api_client: requests.Session) -> dict:
    """Pick a dynamic real game id from live feeds (tier1 first, then tier3)."""
    for tier in (1, 3):
        resp = api_client.get(f"{BASE_URL}/api/sportshub/live", params={"tier": tier}, timeout=25)
        if resp.status_code != 200:
            continue
        payload = resp.json()
        games = payload.get("games") or []
        if games:
            return games[0]
    pytest.skip("No live/today games available from tier1/tier3 in current feed")


# Real external preview endpoint validation
def test_leagues_real_endpoint_structure(api_client: requests.Session):
    response = api_client.get(f"{BASE_URL}/api/sportshub/leagues", timeout=20)
    assert response.status_code == 200
    data = response.json()
    assert isinstance(data.get("sports"), list)
    assert data.get("sportCount", 0) > 0
    assert data.get("leagueCount", 0) > 0
    first = data["sports"][0]
    assert "espnSport" in first and "leagues" in first


def test_live_tier1_real_feed_not_demo(api_client: requests.Session):
    response = api_client.get(f"{BASE_URL}/api/sportshub/live", params={"tier": 1}, timeout=25)
    assert response.status_code == 200
    data = response.json()
    assert data.get("status") in {"ok", "partial", "empty", "unavailable"}
    assert isinstance(data.get("games"), list)
    assert "failedLeagues" in data
    for game in data.get("games", [])[:3]:
        assert game.get("sport")
        assert game.get("league")
        assert "sample" not in (game.get("name") or "").lower()


def test_live_tier3_real_feed_not_demo(api_client: requests.Session):
    response = api_client.get(f"{BASE_URL}/api/sportshub/live", params={"tier": 3}, timeout=25)
    assert response.status_code == 200
    data = response.json()
    assert data.get("status") in {"ok", "partial", "empty", "unavailable"}
    assert isinstance(data.get("games"), list)
    assert isinstance(data.get("liveCount"), int)


def test_scoreboard_selected_league_dynamic(api_client: requests.Session, sample_game: dict):
    response = api_client.get(
        f"{BASE_URL}/api/sportshub/scoreboard",
        params={"sport": sample_game["sport"], "league": sample_game["league"]},
        timeout=25,
    )
    assert response.status_code == 200
    data = response.json()
    assert data.get("status") in {"ok", "empty"}
    assert isinstance(data.get("games"), list)
    if data["games"]:
        row = data["games"][0]
        assert row.get("id")
        assert row.get("home", {}).get("name")
        assert row.get("away", {}).get("name")


def test_game_summary_and_plays_dynamic(api_client: requests.Session, sample_game: dict):
    response = api_client.get(
        f"{BASE_URL}/api/sportshub/game",
        params={"sport": sample_game["sport"], "league": sample_game["league"], "id": sample_game["id"]},
        timeout=30,
    )
    assert response.status_code == 200
    data = response.json()
    assert data.get("id") == sample_game["id"]
    assert "plays" in data and isinstance(data["plays"], list)
    assert "winProbability" in data and isinstance(data["winProbability"], list)
    if data["plays"]:
        assert "null" != str(data["plays"][0].get("text", "")).strip().lower()


def test_standings_dynamic(api_client: requests.Session, sample_game: dict):
    response = api_client.get(
        f"{BASE_URL}/api/sportshub/standings",
        params={"sport": sample_game["sport"], "league": sample_game["league"]},
        timeout=25,
    )
    if response.status_code == 502:
        pytest.skip("Upstream standings unavailable for current dynamic league")
    assert response.status_code == 200
    data = response.json()
    assert "groups" in data
    assert isinstance(data["groups"], list)


def test_invalid_sport_league_and_id_errors(api_client: requests.Session):
    sb = api_client.get(
        f"{BASE_URL}/api/sportshub/scoreboard",
        params={"sport": "invalid-sport", "league": "invalid-league"},
        timeout=20,
    )
    assert sb.status_code == 404

    st = api_client.get(
        f"{BASE_URL}/api/sportshub/standings",
        params={"sport": "invalid-sport", "league": "invalid-league"},
        timeout=20,
    )
    assert st.status_code == 404

    gm = api_client.get(
        f"{BASE_URL}/api/sportshub/game",
        params={"sport": "soccer", "league": "eng.1", "id": "definitely-invalid-id"},
        timeout=20,
    )
    assert gm.status_code in {404, 502}


# Local regression tests via monkeypatch
sys.path.insert(0, "/app/backend")
import sportshub  # noqa: E402


@pytest.fixture(autouse=True)
def _clear_local_cache():
    sportshub._cache.clear()


@pytest.mark.asyncio
async def test_scoreboard_upstream_failure_returns_502(monkeypatch):
    async def boom(*args, **kwargs):  # noqa: ANN002, ANN003
        raise HTTPException(502, "Scores are temporarily unavailable for this league")

    monkeypatch.setattr(sportshub, "_scoreboard", boom)
    with pytest.raises(HTTPException) as err:
        await sportshub.scoreboard("soccer", "eng.1")
    assert err.value.status_code == 502


@pytest.mark.asyncio
async def test_scoreboard_empty_returns_status_empty(monkeypatch):
    async def empty(*args, **kwargs):  # noqa: ANN002, ANN003
        return []

    monkeypatch.setattr(sportshub, "_scoreboard", empty)
    payload = await sportshub.scoreboard("soccer", "eng.1")
    assert payload["status"] == "empty"
    assert payload["games"] == []


@pytest.mark.asyncio
async def test_live_all_failures_status_unavailable(monkeypatch):
    monkeypatch.setattr(sportshub, "LEAGUES", [{"sport": "soccer", "league": "eng.1", "tier": 1, "name": "EPL", "label": "Soccer"}])

    async def fail(*args, **kwargs):  # noqa: ANN002, ANN003
        raise HTTPException(502, "upstream down")

    monkeypatch.setattr(sportshub, "_scoreboard", fail)
    payload = await sportshub.live(tier=1)
    assert payload["status"] == "unavailable"
    assert payload["games"] == []
    assert payload["failedLeagues"]


@pytest.mark.asyncio
async def test_live_mixed_partial_and_failed_leagues(monkeypatch):
    monkeypatch.setattr(
        sportshub,
        "LEAGUES",
        [
            {"sport": "soccer", "league": "eng.1", "tier": 1, "name": "EPL", "label": "Soccer"},
            {"sport": "soccer", "league": "ita.1", "tier": 1, "name": "Serie A", "label": "Soccer"},
        ],
    )

    async def mixed(sport: str, league: str, date):
        if league == "eng.1":
            return [{"id": "1", "state": "in", "startMs": int(time.time() * 1000), "sport": sport, "league": league}]
        raise HTTPException(502, "failed")

    monkeypatch.setattr(sportshub, "_scoreboard", mixed)
    payload = await sportshub.live(tier=1)
    assert payload["status"] == "partial"
    assert len(payload["games"]) == 1
    assert len(payload["failedLeagues"]) == 1


@pytest.mark.asyncio
async def test_game_provider_empty_summary_returns_502(monkeypatch):
    async def fake_get(*args, **kwargs):  # noqa: ANN002, ANN003
        return {"header": {"id": "x", "competitions": []}}

    monkeypatch.setattr(sportshub, "_get", fake_get)
    with pytest.raises(HTTPException) as err:
        await sportshub.game("soccer", "eng.1", "x")
    assert err.value.status_code == 502


@pytest.mark.asyncio
async def test_game_name_fallback_from_teams(monkeypatch):
    async def fake_get(*args, **kwargs):  # noqa: ANN002, ANN003
        return {
            "header": {
                "id": "abc",
                "competitions": [
                    {
                        "date": "2026-02-01T10:00:00Z",
                        "status": {"type": {"state": "pre", "shortDetail": "Scheduled"}},
                        "competitors": [
                            {"homeAway": "away", "team": {"id": "1", "displayName": "Away FC", "abbreviation": "AFC"}},
                            {"homeAway": "home", "team": {"id": "2", "displayName": "Home FC", "abbreviation": "HFC"}},
                        ],
                    }
                ],
            },
            "plays": [],
            "winprobability": [],
            "boxscore": {},
        }

    monkeypatch.setattr(sportshub, "_get", fake_get)
    payload = await sportshub.game("soccer", "eng.1", "abc")
    assert payload["name"] == "Away FC at Home FC"
    assert payload["shortName"] == "AFC @ HFC"
