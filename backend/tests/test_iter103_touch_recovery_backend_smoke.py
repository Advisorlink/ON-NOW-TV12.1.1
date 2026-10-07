import os
import pytest
import requests

# Module scope: backend auth/session smoke supporting native touch-recovery regression testing.

BASE_URL = os.environ.get("REACT_APP_BACKEND_URL")


@pytest.fixture(scope="module")
def api_base_url():
    if not BASE_URL:
        pytest.skip("REACT_APP_BACKEND_URL is required")
    return BASE_URL.rstrip("/")


def test_api_root_ok(api_base_url):
    response = requests.get(f"{api_base_url}/api/", timeout=20)
    assert response.status_code == 200
    data = response.json()
    assert isinstance((data.get("name") or data.get("app")), str)
    assert isinstance(data.get("version"), str)


def test_auth_login_success(api_base_url):
    response = requests.post(
        f"{api_base_url}/api/auth/login",
        json={"username": "testuser", "password": "testpass123"},
        timeout=20,
    )
    assert response.status_code == 200
    data = response.json()
    token = data.get("access_token") or data.get("token")
    account = data.get("account") or data.get("user")
    assert isinstance(token, str) and len(token) > 10
    assert isinstance(account, dict)
    assert account.get("username") == "testuser"


def test_auth_me_with_token(api_base_url):
    login = requests.post(
        f"{api_base_url}/api/auth/login",
        json={"username": "testuser", "password": "testpass123"},
        timeout=20,
    )
    assert login.status_code == 200
    login_data = login.json()
    token = login_data.get("access_token") or login_data.get("token")
    assert isinstance(token, str) and token

    me = requests.get(
        f"{api_base_url}/api/auth/me",
        headers={"Authorization": f"Bearer {token}"},
        timeout=20,
    )
    assert me.status_code == 200
    me_data = me.json()
    account = me_data.get("account") or me_data
    assert isinstance(account, dict)
    assert account.get("username") == "testuser"
