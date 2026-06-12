"""
Seed Xtream credentials into MongoDB.

Reads a JSON file with the schema:
    [
        {
            "dns": "http://example.com:8080",
            "username": "...",
            "password": "...",
            "label": "...",      # optional, defaults to username
            "expires_at": "...", # optional ISO 8601, e.g. "2026-12-31T23:59:59"
            "status": "active",  # optional, default "active" ("active"|"disabled")
            "notes": "..."       # optional free-text
        },
        ...
    ]

Usage:
    cd /app/backend && python scripts/seed_xtream_accounts.py \
        --file /app/backend/scripts/xtream_accounts.json \
        [--replace]

Without `--replace`, existing usernames are skipped.
With `--replace`, existing usernames are updated in-place.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

from dotenv import load_dotenv
from motor.motor_asyncio import AsyncIOMotorClient


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--file",
        default=str(Path(__file__).parent / "xtream_accounts.json"),
        help="Path to JSON file with the account list",
    )
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Replace existing accounts (matching by username) instead of skipping",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print what would be done without touching MongoDB",
    )
    args = parser.parse_args()

    # Load env.
    backend_root = Path(__file__).resolve().parent.parent
    load_dotenv(backend_root / ".env")

    mongo_url = os.environ.get("MONGO_URL")
    db_name = os.environ.get("DB_NAME")
    if not mongo_url or not db_name:
        print("ERROR: MONGO_URL / DB_NAME not set in backend/.env", file=sys.stderr)
        return 2

    path = Path(args.file)
    if not path.exists():
        print(f"ERROR: file not found: {path}", file=sys.stderr)
        return 2

    try:
        with path.open("r", encoding="utf-8") as f:
            entries = json.load(f)
    except json.JSONDecodeError as exc:
        print(f"ERROR: invalid JSON: {exc}", file=sys.stderr)
        return 2

    if not isinstance(entries, list):
        print("ERROR: top-level JSON must be a list", file=sys.stderr)
        return 2

    print(f"Loaded {len(entries)} entries from {path}")

    if args.dry_run:
        print("[dry-run] Would seed (no DB writes):")
        for e in entries:
            print(f"  - {e.get('username')}  @  {e.get('dns')}  (label={e.get('label')})")
        return 0

    client = AsyncIOMotorClient(mongo_url)
    db = client[db_name]

    inserted = 0
    updated = 0
    skipped = 0
    errors: list[str] = []

    for entry in entries:
        if not isinstance(entry, dict):
            errors.append(f"Skipped non-object: {entry!r}")
            continue
        username = (entry.get("username") or "").strip()
        password = entry.get("password") or ""
        dns = (entry.get("dns") or "").strip()
        if not (username and password and dns):
            errors.append(f"Missing fields for entry: {entry!r}")
            continue

        existing = await db.xtream_accounts.find_one({"username": username}, {"_id": 0})
        if existing:
            if args.replace:
                patch = {
                    "dns":        dns,
                    "password":   password,
                    "label":      (entry.get("label") or username),
                    "status":     (entry.get("status") or "active").lower(),
                    "expires_at": entry.get("expires_at"),
                    "notes":      entry.get("notes") or "",
                }
                await db.xtream_accounts.update_one(
                    {"username": username}, {"$set": patch}
                )
                updated += 1
            else:
                skipped += 1
            continue

        doc = {
            "id":         f"xa_{uuid.uuid4().hex[:16]}",
            "dns":        dns,
            "username":   username,
            "password":   password,
            "label":      (entry.get("label") or username),
            "status":     (entry.get("status") or "active").lower(),
            "expires_at": entry.get("expires_at"),
            "notes":      entry.get("notes") or "",
            "created_at": _now_iso(),
        }
        await db.xtream_accounts.insert_one(doc)
        inserted += 1

    # Ensure indexes (idempotent).
    try:
        await db.xtream_accounts.create_index("username", unique=True)
        await db.xtream_accounts.create_index("id", unique=True, sparse=True)
    except Exception as exc:  # noqa: BLE001
        print(f"WARN: index ensure failed: {exc}", file=sys.stderr)

    print(f"Done. inserted={inserted}  updated={updated}  skipped={skipped}")
    if errors:
        print("Errors:")
        for e in errors:
            print(f"  - {e}")

    total = await db.xtream_accounts.count_documents({})
    print(f"Total accounts in MongoDB: {total}")
    client.close()
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
