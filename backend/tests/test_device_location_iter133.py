# Iteration 133: exact box location (presence/device-location), account shape + admin Live join
import os
import pytest
import requests
from dotenv import load_dotenv

load_dotenv("/app/frontend/.env")
load_dotenv("/app/backend/.env")
BASE_URL = os.environ.get("REACT_APP_BACKEND_URL").rstrip("/")
ADMIN_KEY = os.environ.get("ADMIN_KEY")
INGEST = "onnow-presence-ingest-3f9c2a71b8de405e9047ac1d6f8b3e5c"
LOC_BODY = {"client_key": "testuser", "device_id": "qa-dev", "device_model": "QA Box",
            "lat": -37.8136, "lon": 144.9631, "accuracy_m": 30, "provider": "network"}


@pytest.fixture(scope="module")
def s():
    return requests.Session()


# --- device-location endpoint ---
class TestDeviceLocation:
    def test_post_location_ok(self, s):
        r = s.post(f"{BASE_URL}/api/presence/device-location", json=LOC_BODY,
                   headers={"X-Presence-Key": INGEST}, timeout=30)
        assert r.status_code == 200, r.text
        d = r.json()
        assert d["ok"] is True
        assert isinstance(d["address"], str)
        print("address:", d["address"])
        assert "Melbourne" in d["address"] or len(d["address"]) > 0

    def test_without_key_401(self, s):
        r = s.post(f"{BASE_URL}/api/presence/device-location", json=LOC_BODY, timeout=30)
        assert r.status_code == 401

    def test_wrong_key_401(self, s):
        r = s.post(f"{BASE_URL}/api/presence/device-location", json=LOC_BODY,
                   headers={"X-Presence-Key": "bad"}, timeout=30)
        assert r.status_code == 401

    def test_lat_out_of_range_422(self, s):
        r = s.post(f"{BASE_URL}/api/presence/device-location", json={**LOC_BODY, "lat": 123},
                   headers={"X-Presence-Key": INGEST}, timeout=30)
        assert r.status_code == 422

    def test_lon_out_of_range_422(self, s):
        r = s.post(f"{BASE_URL}/api/presence/device-location", json={**LOC_BODY, "lon": -500},
                   headers={"X-Presence-Key": INGEST}, timeout=30)
        assert r.status_code == 422

    def test_missing_client_key_422(self, s):
        b = dict(LOC_BODY); b.pop("client_key")
        r = s.post(f"{BASE_URL}/api/presence/device-location", json=b,
                   headers={"X-Presence-Key": INGEST}, timeout=30)
        assert r.status_code == 422


# --- admin accounts shape ---
class TestAdminAccount:
    def test_account_has_location(self, s):
        r = s.get(f"{BASE_URL}/api/admin/accounts", headers={"X-Admin-Key": ADMIN_KEY}, timeout=30)
        assert r.status_code == 200, r.text
        data = r.json()
        rows = data if isinstance(data, list) else (data.get("accounts") or data.get("items") or [])
        tu = next((a for a in rows if a.get("username") == "testuser"), None)
        assert tu is not None
        loc = tu.get("location")
        assert loc, tu
        for k in ("lat", "lon", "address", "map_url", "accuracy_m", "updated_at"):
            assert k in loc, k
        assert loc["lat"] == -37.8136 and loc["lon"] == 144.9631
        assert loc["accuracy_m"] == 30
        assert loc["map_url"] == "https://www.google.com/maps?q=-37.8136,144.9631"
        assert "_id" not in tu

    def test_admin_accounts_requires_key(self, s):
        r = s.get(f"{BASE_URL}/api/admin/accounts", timeout=30)
        assert r.status_code in (401, 403)


# --- heartbeat + admin active join ---
class TestActiveJoin:
    def test_heartbeat_and_active(self, s):
        hb = {"session_id": "qa-sess-1", "app": "movies", "content_kind": "movie",
              "content_title": "QA Movie", "client_key": "testuser"}
        r = s.post(f"{BASE_URL}/api/presence/heartbeat", json=hb,
                   headers={"X-Presence-Key": INGEST}, timeout=30)
        assert r.status_code == 200 and r.json()["ok"] is True
        try:
            a = s.get(f"{BASE_URL}/api/presence/admin/active", headers={"X-Admin-Key": ADMIN_KEY}, timeout=30)
            assert a.status_code == 200
            sess = next((x for x in a.json()["sessions"] if x["session_id"] == "qa-sess-1"), None)
            assert sess is not None
            el = sess["exact_location"]
            assert el and el.get("address") is not None and el.get("map_url")
            assert isinstance(el["lat"], float)
            assert "_id" not in el
            print("exact_location device:", el.get("device_id"), el.get("address"))
        finally:
            e = s.post(f"{BASE_URL}/api/presence/end", json={"session_id": "qa-sess-1", "app": "movies"},
                       headers={"X-Presence-Key": INGEST}, timeout=30)
            assert e.status_code == 200

    def test_session_gone_after_end(self, s):
        a = s.get(f"{BASE_URL}/api/presence/admin/active", headers={"X-Admin-Key": ADMIN_KEY}, timeout=30)
        assert not any(x["session_id"] == "qa-sess-1" for x in a.json()["sessions"])

    def test_admin_active_requires_key(self, s):
        r = s.get(f"{BASE_URL}/api/presence/admin/active", timeout=30)
        assert r.status_code == 403
