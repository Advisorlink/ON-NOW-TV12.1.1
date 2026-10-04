import os
import requests
import pytest
from dotenv import dotenv_values


# Auth endpoints smoke tests for release-popup/session readiness validation support
# Contract: username/password login -> Bearer JWT, /auth/me -> {account:{...}}
def _base_url_from_env() -> str | None:
    direct = os.environ.get("REACT_APP_BACKEND_URL")
    if direct:
        return direct
    # Fallback for local pytest runs where frontend env isn't exported.
    env_path = "/app/frontend/.env"
    if os.path.exists(env_path):
        parsed = dotenv_values(env_path)
        return parsed.get("REACT_APP_BACKEND_URL")
    return None


BASE_URL = _base_url_from_env()


@pytest.fixture(scope="module")
def api_client():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    return s


@pytest.fixture(scope="module")
def base_url():
    if not BASE_URL:
        pytest.skip("REACT_APP_BACKEND_URL is not set")
    return BASE_URL.rstrip("/")


def test_auth_login_success_shape(api_client, base_url):
    response = api_client.post(
        f"{base_url}/api/auth/login",
        json={"username": "testuser", "password": "testpass123"},
        timeout=20,
    )
    assert response.status_code == 200

    data = response.json()
    assert data.get("token_type") == "bearer"
    assert isinstance(data.get("access_token"), str) and len(data["access_token"]) > 20
    assert isinstance(data.get("account"), dict)
    assert data["account"].get("username") == "testuser"

def test_auth_me_with_real_token(api_client, base_url):
    login_response = api_client.post(
        f"{base_url}/api/auth/login",
        json={"username": "testuser", "password": "testpass123"},
        timeout=20,
    )
    assert login_response.status_code == 200
    token = login_response.json()["access_token"]

    me_response = api_client.get(
        f"{base_url}/api/auth/me",
        headers={"Authorization": f"Bearer {token}"},
        timeout=20,
    )
    assert me_response.status_code == 200
    me_data = me_response.json()
    assert isinstance(me_data.get("account"), dict)
    assert me_data["account"].get("username") == "testuser"
    assert me_data["account"].get("status") == "active"


def test_auth_me_rejects_invalid_token(api_client, base_url):
    response = api_client.get(
        f"{base_url}/api/auth/me",
        headers={"Authorization": "Bearer invalid.token.value"},
        timeout=20,
    )
    assert response.status_code in (401, 403)