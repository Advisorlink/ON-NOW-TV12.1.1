"""Official replay backend tests: not-connected default, date policy, and player safety."""

from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta, timezone

import pytest
import requests
from fastapi import HTTPException

sys.path.insert(0, "/app/backend")
import sports_official_sources as src  # noqa: E402


def _base_url() -> str:
    value = os.environ.get("REACT_APP_BACKEND_URL", "").strip()
    if value:
        return value.rstrip("/")
    env_path = "/app/frontend/.env"
    if not os.path.exists(env_path):
        pytest.skip("frontend/.env missing; cannot resolve REACT_APP_BACKEND_URL")
    with open(env_path, "r", encoding="utf-8") as handle:
        for raw in handle:
            if raw.strip().startswith("REACT_APP_BACKEND_URL="):
                return raw.split("=", 1)[1].strip().rstrip("/")
    pytest.skip("REACT_APP_BACKEND_URL not configured")


BASE_URL = _base_url()


@pytest.fixture(scope="module")
def api_client() -> requests.Session:
    session = requests.Session()
    session.headers.update({"Accept": "application/json"})
    return session


# Directory endpoint contract: official discovery intentionally not connected by default.
def test_official_replays_returns_not_connected_empty_directory(api_client: requests.Session):
    response = api_client.get(f"{BASE_URL}/api/sportshub/official-replays", timeout=25)
    assert response.status_code == 200
    data = response.json()
    assert data.get("status") == "not_connected"
    assert isinstance(data.get("replays"), list)
    assert data.get("replays") == []
    assert "not connected" in (data.get("notice") or "").lower()
    assert api_client.get(f"{BASE_URL}/api/sportshub/official-replays/nrl-origin-2012-game-3/player", timeout=20).status_code == 404


def test_unknown_source_player_returns_404(api_client: requests.Session):
    response = api_client.get(
        f"{BASE_URL}/api/sportshub/official-replays/unknown-source/player",
        timeout=20,
    )
    assert response.status_code == 404
    assert "unavailable" in response.text.lower()


def test_player_for_legacy_curated_id_returns_404_when_not_connected(api_client: requests.Session):
    response = api_client.get(
        f"{BASE_URL}/api/sportshub/official-replays/rugby-england-new-zealand-2025/player",
        timeout=25,
    )
    assert response.status_code == 404


@pytest.mark.asyncio
async def test_configuration_missing_or_malformed_raises_503(monkeypatch):
    keys = [
        "SPORTS_OFFICIAL_REPLAYS_JSON",
        "SPORTS_EMBED_ORIGIN",
        "SPORTS_YOUTUBE_EMBED_ORIGIN",
        "SPORTS_YOUTUBE_IFRAME_API_URL",
        "SPORTS_YOUTUBE_OEMBED_URL",
        "SPORTS_YOUTUBE_WATCH_URL",
    ]
    for key in keys:
        monkeypatch.delenv(key, raising=False)
    with pytest.raises(HTTPException) as err:
        src.configuration()
    assert err.value.status_code == 503

    monkeypatch.setenv("SPORTS_OFFICIAL_REPLAYS_JSON", "not-json")
    monkeypatch.setenv("SPORTS_EMBED_ORIGIN", "https://rebrand-app-5.preview.emergentagent.com")
    monkeypatch.setenv("SPORTS_YOUTUBE_EMBED_ORIGIN", "https://www.youtube-nocookie.com")
    monkeypatch.setenv("SPORTS_YOUTUBE_IFRAME_API_URL", "https://www.youtube.com/iframe_api")
    monkeypatch.setenv("SPORTS_YOUTUBE_OEMBED_URL", "https://www.youtube.com/oembed")
    monkeypatch.setenv("SPORTS_YOUTUBE_WATCH_URL", "https://www.youtube.com/watch")
    with pytest.raises(HTTPException) as err2:
        src.configuration()
    assert err2.value.status_code == 503


@pytest.mark.asyncio
async def test_recent_completed_event_filters_old_future_unknown_and_requires_completion():
    now = datetime.now(timezone.utc)
    now_ms = int(now.timestamp() * 1000)

    base = dict(
        sport="mlb",
        title="Recent full game",
        channel="MLB",
        channel_handle="/MLB",
        video_id="Tpmb1y50dl4",
    )

    good_recent = src.OfficialReplay(
        id="recent-ok",
        event_date=now.date() - timedelta(days=2),
        event_start_ms=now_ms - 3_600_000,
        event_end_ms=now_ms - 60_000,
        event_completed=True,
        **base,
    )
    too_old = src.OfficialReplay(
        id="old",
        event_date=now.date() - timedelta(days=8),
        event_start_ms=now_ms - 9 * 86_400_000,
        event_end_ms=now_ms - 9 * 86_400_000 + 3_600_000,
        event_completed=True,
        **base,
    )
    future = src.OfficialReplay(
        id="future",
        event_date=now.date() + timedelta(days=1),
        event_start_ms=now_ms + 86_400_000,
        event_end_ms=now_ms + 87_000_000,
        event_completed=True,
        **base,
    )
    not_completed = src.OfficialReplay(
        id="not-complete",
        event_date=now.date() - timedelta(days=1),
        event_start_ms=now_ms - 7_200_000,
        event_end_ms=now_ms - 3_600_000,
        event_completed=False,
        **base,
    )

    assert src.recent_completed_event(good_recent, now)
    assert not src.recent_completed_event(too_old, now)
    assert not src.recent_completed_event(future, now)
    assert not src.recent_completed_event(not_completed, now)


@pytest.mark.asyncio
async def test_recent_completed_event_today_requires_past_end_timestamp():
    now = datetime.now(timezone.utc)
    now_ms = int(now.timestamp() * 1000)

    base = dict(
        sport="mlb",
        title="Today game",
        channel="MLB",
        channel_handle="/MLB",
        video_id="Tpmb1y50dl4",
        event_date=now.date(),
        event_start_ms=now_ms - 7_200_000,
        event_completed=True,
    )

    missing_end = src.OfficialReplay(id="today-missing-end", event_end_ms=None, **base)
    future_end = src.OfficialReplay(id="today-future-end", event_end_ms=now_ms + 60_000, **base)
    ended = src.OfficialReplay(id="today-ended", event_end_ms=now_ms - 60_000, **base)

    assert not src.recent_completed_event(missing_end, now)
    assert not src.recent_completed_event(future_end, now)
    assert src.recent_completed_event(ended, now)


@pytest.mark.asyncio
async def test_directory_filters_unverified_or_non_full_match(monkeypatch):
    src._cache = None
    now = datetime.now(timezone.utc)
    start_ms = int((now - timedelta(hours=3)).timestamp() * 1000)
    end_ms = int((now - timedelta(hours=1)).timestamp() * 1000)
    event_day = now.date().isoformat()
    rows = [
        src.OfficialReplay(
            id="ok-full-game",
            sport="rugby",
            title="England v New Zealand Full Game",
            channel="All Blacks",
            channel_handle="/AllBlacks",
            video_id="Tpmb1y50dl4",
            event_date=event_day,
            event_start_ms=start_ms,
            event_end_ms=end_ms,
            event_completed=True,
        ),
        src.OfficialReplay(
            id="bad-highlight",
            sport="rugby",
            title="England Highlights",
            channel="All Blacks",
            channel_handle="/AllBlacks",
            video_id="AAAAAAAAAAA",
            event_date=event_day,
            event_start_ms=start_ms,
            event_end_ms=end_ms,
            event_completed=True,
        ),
        src.OfficialReplay(
            id="bad-uploader",
            sport="rugby",
            title="England Full Match",
            channel="Random",
            channel_handle="/AllBlacks",
            video_id="BBBBBBBBBBB",
            event_date=event_day,
            event_start_ms=start_ms,
            event_end_ms=end_ms,
            event_completed=True,
        ),
    ]

    def fake_config():
        return (
            rows,
            "https://rebrand-app-5.preview.emergentagent.com",
            "https://www.youtube-nocookie.com",
            "https://www.youtube.com/iframe_api",
            "https://www.youtube.com/oembed",
            "https://www.youtube.com/watch",
        )

    class FakeResponse:
        def __init__(self, payload):
            self._payload = payload

        def raise_for_status(self):
            return None

        def json(self):
            return self._payload

    class FakeClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def get(self, url, params=None):
            vid = (params or {}).get("url", "")
            if "Tpmb1y50dl4" in vid:
                return FakeResponse({"author_url": "https://www.youtube.com/AllBlacks", "title": "All Blacks v England | FULL GAME", "html": "<iframe></iframe>"})
            if "AAAAAAAAAAA" in vid:
                return FakeResponse({"author_url": "https://www.youtube.com/AllBlacks", "title": "All Blacks v England | Highlights", "html": "<iframe></iframe>"})
            return FakeResponse({"author_url": "https://www.youtube.com/OtherUploader", "title": "All Blacks v England | FULL MATCH", "html": "<iframe></iframe>"})

    monkeypatch.setattr(src, "configuration", fake_config)
    monkeypatch.setattr(src.httpx, "AsyncClient", lambda **kwargs: FakeClient())

    directory = await src.directory()
    assert directory.status == "partial"
    assert [item.id for item in directory.replays] == ["ok-full-game"]


@pytest.mark.asyncio
async def test_player_payload_escapes_injected_markup_and_keeps_trusted_hosts(monkeypatch):
    src._cache = None
    now = datetime.now(timezone.utc)
    start_ms = int((now - timedelta(hours=3)).timestamp() * 1000)
    end_ms = int((now - timedelta(hours=1)).timestamp() * 1000)
    malicious = src.OfficialReplay(
        id="safe-test-id",
        sport="rugby",
        title="<script>alert(1)</script> full game",
        channel="All Blacks",
        channel_handle="/AllBlacks",
        video_id="Tpmb1y50dl4",
        event_date=now.date().isoformat(),
        event_start_ms=start_ms,
        event_end_ms=end_ms,
        event_completed=True,
    )

    async def fake_directory():
        return src.ReplayDirectory(replays=[malicious], status="ready")

    def fake_config():
        return (
            [malicious],
            "https://rebrand-app-5.preview.emergentagent.com",
            "https://www.youtube-nocookie.com",
            "https://www.youtube.com/iframe_api",
            "https://www.youtube.com/oembed",
            "https://www.youtube.com/watch",
        )

    monkeypatch.setattr(src, "directory", fake_directory)
    monkeypatch.setattr(src, "configuration", fake_config)

    response = await src.official_player("safe-test-id")
    html = response.body.decode("utf-8")
    assert "\\u003cscript>" in html
    assert "<script>alert(1)</script>" not in html
    assert "https://www.youtube.com/iframe_api" in html
    assert "youtube-nocookie.com" in html
    assert "javascript:" not in html.lower()