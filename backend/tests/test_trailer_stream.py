"""
Verify the new /api/trailer-stream/{youtube_id} endpoint returns
HD video + matching audio URLs so the native libVLC player can
merge them for HD trailer playback.
"""
import os

import httpx
import pytest

BACKEND_URL = os.environ.get(
    "REACT_APP_BACKEND_URL_FOR_TEST"
) or os.environ.get("REACT_APP_BACKEND_URL") or "http://localhost:8001"


@pytest.mark.asyncio
async def test_trailer_stream_returns_hd_pair():
    """A modern YouTube video should yield a 1080p video_url AND a
    matching audio_url so libVLC can merge them via input-slave."""
    # Pick Interstellar's official trailer from TMDB id 157336.
    async with httpx.AsyncClient(timeout=40) as c:
        r = await c.get(f"{BACKEND_URL}/api/tmdb/trailer/movie/157336")
        assert r.status_code == 200, r.text
        key = r.json()["data"]["key"]
        assert key
        r2 = await c.get(f"{BACKEND_URL}/api/trailer-stream/{key}")
        assert r2.status_code == 200, r2.text
        data = r2.json()
    assert data["height"] >= 720, f"expected HD height, got {data['height']}"
    assert data["video_url"], "video_url missing"
    assert data["audio_url"], "audio_url missing"
    assert data["is_hd_pair"] is True
    assert data["url"].startswith("https://"), "primary url malformed"


@pytest.mark.asyncio
async def test_trailer_stream_handles_invalid_id():
    """An invalid YouTube id (only-special-chars after sanitisation)
    must 400 cleanly, not crash."""
    async with httpx.AsyncClient(timeout=10) as c:
        r = await c.get(f"{BACKEND_URL}/api/trailer-stream/!!!")
        assert r.status_code == 400
