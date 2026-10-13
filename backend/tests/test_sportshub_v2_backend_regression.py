"""Sports Hub backend regression tests for date/caching and parser behavior."""

from __future__ import annotations

import time

import pytest
from fastapi import HTTPException

import sys

sys.path.insert(0, "/app/backend")
import instant_bundle  # noqa: E402
import sportshub  # noqa: E402


# instant_bundle parser safety
def test_archive_days_preserves_positive_zero_and_unknown_none():
    assert instant_bundle._archive_days("7") == 7
    assert instant_bundle._archive_days(0) == 0
    assert instant_bundle._archive_days("-1") is None
    assert instant_bundle._archive_days("abc") is None
    assert instant_bundle._archive_days(None) is None


def test_active_player_joins_by_exact_id_only():
    rows = [
        {"id": "123", "headshot": "exact.png", "line": "line-a", "teamId": "t1", "name": "Exact"},
        {"id": "999", "headshot": "other.png", "line": "line-b", "teamId": "t2", "name": "Other"},
    ]
    situation = {
        "pitcher": {
            "athlete": {
                "id": "123",
                "shortName": "Pitcher",
                "team": {"id": "t1"},
            },
            "summary": "7 K",
        }
    }
    out = sportshub._active_player(situation, "pitcher", rows)
    assert out["id"] == "123"
    assert out["headshot"] == "exact.png"
    assert out["teamId"] == "t1"


def test_active_player_missing_identity_returns_none():
    situation = {"pitcher": {"athlete": {"shortName": "No id"}, "summary": "x"}}
    assert sportshub._active_player(situation, "pitcher", []) is None


@pytest.mark.asyncio
async def test_live_invalid_calendar_date_422():
    with pytest.raises(HTTPException) as err:
        await sportshub.live(date="20260230")
    assert err.value.status_code == 422


@pytest.mark.asyncio
async def test_live_date_passed_to_scoreboards_and_cache_isolated_by_date(monkeypatch):
    calls = []
    sportshub._cache.clear()
    monkeypatch.setattr(
        sportshub,
        "LEAGUES",
        [{"sport": "soccer", "league": "eng.1", "tier": 1, "name": "EPL", "label": "Soccer"}],
    )

    async def fake_scoreboard(sport: str, league: str, date: str | None):
        calls.append((sport, league, date))
        state = "post" if date == "20260101" else "pre"
        return [{"id": f"{date or 'today'}-1", "state": state, "startMs": int(time.time() * 1000), "sport": sport, "league": league}]

    monkeypatch.setattr(sportshub, "_scoreboard", fake_scoreboard)

    first = await sportshub.live(tier=1, date="20260101")
    second = await sportshub.live(tier=1, date="20260102")
    repeat_first = await sportshub.live(tier=1, date="20260101")

    assert first["games"][0]["id"].startswith("20260101")
    assert second["games"][0]["id"].startswith("20260102")
    assert repeat_first["games"][0]["id"].startswith("20260101")
    assert ("soccer", "eng.1", "20260101") in calls
    assert ("soccer", "eng.1", "20260102") in calls
    # Repeated same date should hit cache and not produce a third backend scoreboard call.
    assert calls.count(("soccer", "eng.1", "20260101")) == 1
