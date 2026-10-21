"""One-time copy of live (box) accounts + their cloud snapshots into this DB.

Reads only from the live server: admin account list, then each account's
own sync snapshot via a normal sign-in. Usage: python scripts/copy_prod_accounts.py
"""
import asyncio
import os
from pathlib import Path

import httpx
from dotenv import load_dotenv
from motor.motor_asyncio import AsyncIOMotorClient

load_dotenv(Path(__file__).resolve().parent.parent / ".env")
LIVE = "https://onnowhub.com"
FIELDS = ("id", "username", "password", "label", "status", "expires_at",
          "created_at", "notes", "xtream_username", "xtream_password")


async def main():
    db = AsyncIOMotorClient(os.environ["MONGO_URL"])[os.environ["DB_NAME"]]
    async with httpx.AsyncClient(timeout=30) as http:
        r = await http.get(f"{LIVE}/api/admin/accounts", headers={"X-Admin-Key": os.environ["ADMIN_KEY"]})
        r.raise_for_status()
        accounts = r.json()["accounts"]
        copied = synced = 0
        for a in accounts:
            doc = {k: a.get(k) for k in FIELDS}
            await db.vesper_accounts.delete_many({"username": doc["username"], "id": {"$ne": doc["id"]}})
            await db.vesper_accounts.update_one({"id": doc["id"]}, {"$set": doc}, upsert=True)
            copied += 1
            if (doc["status"] or "active") == "disabled":
                continue
            login = await http.post(f"{LIVE}/api/auth/login", json={"username": doc["username"], "password": doc["password"]})
            if login.status_code != 200:
                continue
            pull = await http.get(f"{LIVE}/api/vesper/sync/pull",
                                  headers={"Authorization": f"Bearer {login.json()['access_token']}"})
            snap = pull.json() if pull.status_code == 200 else {}
            if not snap.get("found"):
                continue
            await db.vesper_sync.update_one(
                {"account_id": doc["id"]},
                {"$set": {"account_id": doc["id"], "username": doc["username"], "data": snap.get("data") or {},
                          "updated_at": snap.get("updated_at"), "approx_bytes": snap.get("approx_bytes"),
                          "key_count": snap.get("key_count")}},
                upsert=True,
            )
            synced += 1
        print(f"accounts copied: {copied} / {len(accounts)}; snapshots copied: {synced}")


asyncio.run(main())
