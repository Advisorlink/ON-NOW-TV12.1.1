"""Contract checks for native V2-dependent backend endpoints (real preview API)."""

import os

import httpx
import pytest


BASE_URL = os.environ.get("REACT_APP_BACKEND_URL")


@pytest.fixture(scope="module")
def backend_url():
    if not BASE_URL:
        pytest.skip("REACT_APP_BACKEND_URL is not set")
    return BASE_URL.rstrip("/")


@pytest.mark.asyncio
async def test_tmdb_credits_contract(backend_url):
    async with httpx.AsyncClient(timeout=25) as client:
        response = await client.get(f"{backend_url}/api/tmdb/credits/movie/157336")
    assert response.status_code == 200, response.text
    data = response.json()
    assert isinstance(data.get("cast"), list)
    assert len(data["cast"]) > 0
    first = data["cast"][0]
    assert isinstance(first.get("id"), int)
    assert isinstance(first.get("name"), str) and first["name"]


@pytest.mark.asyncio
async def test_tmdb_person_contract_from_cast_member(backend_url):
    async with httpx.AsyncClient(timeout=25) as client:
        credits = await client.get(f"{backend_url}/api/tmdb/credits/movie/157336")
        assert credits.status_code == 200, credits.text
        cast = credits.json().get("cast", [])
        assert cast
        person_id = cast[0]["id"]
        person = await client.get(f"{backend_url}/api/tmdb/person/{person_id}")
    assert person.status_code == 200, person.text
    data = person.json()
    assert data.get("id") == person_id
    assert isinstance(data.get("filmography"), list)
    assert isinstance(data.get("name"), str) and data["name"]


@pytest.mark.asyncio
async def test_tmdb_recommendations_contract(backend_url):
    async with httpx.AsyncClient(timeout=25) as client:
        response = await client.get(f"{backend_url}/api/tmdb/recommendations/movie/157336")
    assert response.status_code == 200, response.text
    data = response.json()
    assert isinstance(data.get("results"), list)
    if data["results"]:
        first = data["results"][0]
        assert first.get("media_type") in ("movie", "tv")
        assert isinstance(first.get("title"), str)


@pytest.mark.asyncio
async def test_collection_contract_star_wars(backend_url):
    async with httpx.AsyncClient(timeout=25) as client:
        response = await client.get(f"{backend_url}/api/collections/star-wars", params={"type": "movie", "page": 1})
    assert response.status_code == 200, response.text
    data = response.json().get("data", {})
    assert isinstance(data.get("results"), list)
    assert isinstance(data.get("total_pages"), int)


@pytest.mark.asyncio
async def test_network_contract_netflix(backend_url):
    async with httpx.AsyncClient(timeout=25) as client:
        response = await client.get(f"{backend_url}/api/networks/netflix", params={"type": "tv", "page": 1})
    assert response.status_code == 200, response.text
    data = response.json().get("data", {})
    assert data.get("type") == "tv"
    assert data.get("network")
    assert isinstance(data.get("results"), list)


@pytest.mark.asyncio
async def test_upcoming_movies_contract(backend_url):
    async with httpx.AsyncClient(timeout=25) as client:
        response = await client.get(f"{backend_url}/api/tmdb/upcoming-movies", params={"limit": 5})
    assert response.status_code == 200, response.text
    data = response.json()
    assert isinstance(data.get("data"), list)


@pytest.mark.asyncio
async def test_trailer_contract_and_stream_contract(backend_url):
    async with httpx.AsyncClient(timeout=40) as client:
        trailer = await client.get(f"{backend_url}/api/tmdb/trailer/movie/157336")
        assert trailer.status_code == 200, trailer.text
        key = trailer.json().get("data", {}).get("key")
        assert key
        stream = await client.get(f"{backend_url}/api/trailer-stream/{key}")
    assert stream.status_code == 200, stream.text
    payload = stream.json()
    assert isinstance(payload.get("video_url"), str) and payload["video_url"].startswith("https://")
    assert isinstance(payload.get("audio_url"), str)
