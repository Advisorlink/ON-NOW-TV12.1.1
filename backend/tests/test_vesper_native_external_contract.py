import os
from pathlib import Path

import pytest
import requests


# External read-only contract smoke for vesper-native foundation checkpoint.
def _frontend_backend_url() -> str:
    env_path = Path("/app/frontend/.env")
    if not env_path.exists():
        pytest.skip("frontend/.env missing; cannot resolve external backend URL")
    for line in env_path.read_text(encoding="utf-8").splitlines():
        if line.startswith("REACT_APP_BACKEND_URL="):
            value = line.split("=", 1)[1].strip().strip('"').strip("'")
            if value:
                return value.rstrip("/")
    pytest.skip("REACT_APP_BACKEND_URL missing in frontend/.env")


BASE_URL = _frontend_backend_url()
TEST_USERNAME = os.environ.get("VESPER_TEST_USERNAME")
TEST_PASSWORD = os.environ.get("VESPER_TEST_PASSWORD")


@pytest.fixture(scope="module")
def session():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json", "Accept": "application/json"})
    return s


@pytest.fixture(scope="module")
def auth_bundle(session):
    if not TEST_USERNAME or not TEST_PASSWORD:
        pytest.fail("Missing VESPER_TEST_USERNAME/VESPER_TEST_PASSWORD environment variables")
    response = session.post(
        f"{BASE_URL}/api/auth/login",
        json={"username": TEST_USERNAME, "password": TEST_PASSWORD, "client_id": "tv.onnowtv.vespernative"},
        timeout=30,
    )
    assert response.status_code == 200, f"Login contract failed: HTTP {response.status_code}"
    data = response.json()
    token = data.get("access_token")
    account = data.get("account", {})
    assert isinstance(token, str) and token
    assert account.get("username") == TEST_USERNAME
    return {"token": token, "account": account}


def test_login_contract_envelope(session, auth_bundle):
    assert isinstance(auth_bundle["token"], str)
    assert auth_bundle["account"].get("id")
    assert auth_bundle["account"].get("username") == TEST_USERNAME


def test_auth_me_with_bearer(session, auth_bundle):
    response = session.get(
        f"{BASE_URL}/api/auth/me",
        headers={"Authorization": f"Bearer {auth_bundle['token']}"},
        timeout=30,
    )
    assert response.status_code == 200
    data = response.json()
    account = data.get("account", {})
    assert account.get("username") == TEST_USERNAME
    assert account.get("id") == auth_bundle["account"].get("id")


def test_addons_catalog_meta_and_search_read_only_contract(session, auth_bundle):
    auth = {"Authorization": f"Bearer {auth_bundle['token']}"}

    addons_res = session.get(f"{BASE_URL}/api/addons", headers=auth, timeout=30)
    assert addons_res.status_code == 200
    addons = addons_res.json()
    assert isinstance(addons, list)
    assert len(addons) > 0

    selected = None
    for addon in addons:
        catalogs = addon.get("catalogs") or []
        for catalog in catalogs:
            if catalog.get("type") in {"movie", "series"} and catalog.get("id"):
                selected = (addon, catalog)
                break
        if selected:
            break
    assert selected is not None

    addon, catalog = selected
    addon_id = addon["id"]
    media_type = catalog["type"]
    catalog_id = catalog["id"]

    catalog_res = session.get(
        f"{BASE_URL}/api/addons/{addon_id}/catalog/{media_type}/{catalog_id}",
        params={"skip": 0},
        headers=auth,
        timeout=45,
    )
    assert catalog_res.status_code == 200
    catalog_data = catalog_res.json()
    metas = ((catalog_data.get("data") or {}).get("metas") or [])
    assert isinstance(metas, list)

    if metas:
        first = metas[0]
        title_id = first.get("id")
        assert isinstance(title_id, str) and title_id

        meta_res = session.get(
            f"{BASE_URL}/api/meta/{media_type}/{title_id}",
            headers=auth,
            timeout=45,
        )
        assert meta_res.status_code == 200
        meta_payload = meta_res.json()
        meta = ((meta_payload.get("data") or {}).get("meta") or {})
        assert meta.get("id") == title_id

    searchable_catalog = None
    for cat in addon.get("catalogs", []):
        extra = cat.get("extra") or []
        if any((entry or {}).get("name") == "search" for entry in extra) and cat.get("type") in {"movie", "series"}:
            searchable_catalog = cat
            break
    if searchable_catalog:
        search_res = session.get(
            f"{BASE_URL}/api/addons/{addon_id}/catalog/{searchable_catalog['type']}/{searchable_catalog['id']}",
            params={"search": "star"},
            headers=auth,
            timeout=45,
        )
        assert search_res.status_code == 200
        search_json = search_res.json()
        assert "data" in search_json


def test_vesper_sync_pull_shape_authenticated_read_only(session, auth_bundle):
    response = session.get(
        f"{BASE_URL}/api/vesper/sync/pull",
        headers={"Authorization": f"Bearer {auth_bundle['token']}"},
        timeout=30,
    )
    assert response.status_code == 200
    payload = response.json()
    assert isinstance(payload.get("found"), bool)

    if payload["found"]:
        assert isinstance(payload.get("data"), dict)
        if payload.get("updated_at") is not None:
            assert isinstance(payload.get("updated_at"), (int, float))
    else:
        # For not-found pull, backend should not ship snapshot data.
        assert payload.get("data") in (None, {})


def test_vesper_sync_pull_missing_bearer_rejected(session):
    response = session.get(f"{BASE_URL}/api/vesper/sync/pull", timeout=30)
    assert response.status_code in {401, 403}
