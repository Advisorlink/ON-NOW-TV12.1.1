"""
Profile Backup & Restore — save your full Vesper state to a short
6-character code protected by a 4-digit PIN, then restore it on any
device by entering the code + PIN.

The backup payload is the entire `localStorage` snapshot from the
frontend.  It includes:
  • Profiles (avatars, names, kids-mode flag)
  • Continue Watching for every profile
  • Library / favourites / watchlist for every profile
  • Live TV favourites, recents, reminders, EPG cache
  • Theme + UI preferences
  • Stream-source / addon selections

Storage:
  • Backups live in a Mongo collection `profile_backups`.
  • TTL index on `expires_at` (90 days from save) — any backup not
    refreshed in 90 days is auto-deleted.
  • PIN stored only as a salted SHA-256 hash; the raw PIN is never
    persisted.

Endpoints:
  • POST /api/backup/save        { payload, pin }   → { code }
  • POST /api/backup/restore     { code, pin }      → { payload }
  • POST /api/backup/refresh     { code, pin }      → { expires_at }
"""

from __future__ import annotations

import hashlib
import os
import secrets
import string
import time
from datetime import datetime, timedelta, timezone
from typing import Any, Dict

import pymongo
from fastapi import APIRouter, HTTPException
from motor.motor_asyncio import AsyncIOMotorClient
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/backup")

# ---------------------------------------------------------------------------
# Mongo — reuse the existing connection string from .env.
# ---------------------------------------------------------------------------
_MONGO_URL = os.environ["MONGO_URL"]
_DB_NAME = os.environ["DB_NAME"]
_client = AsyncIOMotorClient(_MONGO_URL)
_db = _client[_DB_NAME]
_col = _db["profile_backups"]


async def _ensure_indexes():
    """Idempotent: create the indexes we rely on."""
    try:
        await _col.create_index("code", unique=True)
    except pymongo.errors.OperationFailure:
        pass
    try:
        # TTL index — Mongo will remove documents `expires_at` seconds
        # after the field's value.  Setting expireAfterSeconds=0 means
        # the field IS the deletion deadline.
        await _col.create_index("expires_at", expireAfterSeconds=0)
    except pymongo.errors.OperationFailure:
        pass


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
_CODE_ALPHABET = string.ascii_uppercase + string.digits
# Remove visually confusable characters: 0/O, 1/I, etc.
_CODE_ALPHABET = _CODE_ALPHABET.replace("O", "").replace("0", "")\
                                .replace("I", "").replace("1", "")\
                                .replace("L", "").replace("U", "")

CODE_LEN = 6
PIN_LEN = 4
TTL_DAYS = 90
PAYLOAD_BYTES_MAX = 12 * 1024 * 1024  # 12 MB cap.  Boxes with the
                                       # full EPG cached in localStorage
                                       # blew through the old 2 MB cap.
                                       # MongoDB's BSON limit is 16 MB
                                       # so 12 MB leaves headroom for
                                       # the wrapper document.


def _generate_code() -> str:
    return "".join(secrets.choice(_CODE_ALPHABET) for _ in range(CODE_LEN))


def _hash_pin(pin: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{pin}".encode("utf-8")).hexdigest()


def _normalise_pin(pin: str) -> str:
    pin = (pin or "").strip()
    if len(pin) != PIN_LEN or not pin.isdigit():
        raise HTTPException(status_code=400, detail="PIN must be 4 digits.")
    return pin


def _normalise_code(code: str) -> str:
    code = (code or "").strip().upper()
    if len(code) != CODE_LEN:
        raise HTTPException(status_code=400, detail="Backup code must be 6 characters.")
    return code


# ---------------------------------------------------------------------------
# Payloads
# ---------------------------------------------------------------------------
class SaveReq(BaseModel):
    payload: Dict[str, Any] = Field(..., description="Full localStorage snapshot.")
    pin: str = Field(..., min_length=PIN_LEN, max_length=PIN_LEN)


class RestoreReq(BaseModel):
    code: str
    pin: str


# ---------------------------------------------------------------------------
# Routes
# ---------------------------------------------------------------------------
@router.post("/save")
async def save_backup(req: SaveReq):
    await _ensure_indexes()
    pin = _normalise_pin(req.pin)

    # Size check — refuse silly-sized payloads early.
    import json as _json
    try:
        body = _json.dumps(req.payload).encode("utf-8")
    except Exception:
        raise HTTPException(status_code=400, detail="Backup payload could not be serialised.")
    if len(body) > PAYLOAD_BYTES_MAX:
        raise HTTPException(status_code=413, detail=f"Backup too large ({len(body)} bytes).")

    # Make a fresh unique code.  Retry up to 8 times to avoid the
    # vanishingly rare collision against the unique index.
    last_err = None
    for _ in range(8):
        code = _generate_code()
        salt = secrets.token_hex(16)
        pin_hash = _hash_pin(pin, salt)
        now = datetime.now(timezone.utc)
        try:
            await _col.insert_one({
                "code":         code,
                "payload":      req.payload,
                "size_bytes":   len(body),
                "pin_salt":     salt,
                "pin_hash":     pin_hash,
                "created_at":   now,
                "expires_at":   now + timedelta(days=TTL_DAYS),
                "restore_count": 0,
                "last_restore_at": None,
            })
            return {
                "code":       code,
                "expires_at": (now + timedelta(days=TTL_DAYS)).isoformat(),
                "size_bytes": len(body),
            }
        except pymongo.errors.DuplicateKeyError as e:
            last_err = e
            continue
    raise HTTPException(status_code=500, detail=f"Could not allocate a unique code: {last_err}")


@router.post("/restore")
async def restore_backup(req: RestoreReq):
    code = _normalise_code(req.code)
    pin = _normalise_pin(req.pin)
    doc = await _col.find_one({"code": code}, projection={"_id": 0})
    if not doc:
        raise HTTPException(status_code=404, detail="Backup code not found.")
    # Verify the PIN.
    expected = doc.get("pin_hash")
    salt = doc.get("pin_salt", "")
    if not expected or _hash_pin(pin, salt) != expected:
        raise HTTPException(status_code=403, detail="Incorrect PIN.")
    # Bump restore counter (best-effort).
    try:
        await _col.update_one(
            {"code": code},
            {"$inc": {"restore_count": 1},
             "$set": {"last_restore_at": datetime.now(timezone.utc)}},
        )
    except Exception:
        pass
    return {
        "payload":    doc["payload"],
        "created_at": doc["created_at"].isoformat() if doc.get("created_at") else None,
        "size_bytes": doc.get("size_bytes", 0),
    }


@router.post("/refresh")
async def refresh_backup(req: RestoreReq):
    """Bump the expiry on an existing backup so it survives another 90 days."""
    code = _normalise_code(req.code)
    pin = _normalise_pin(req.pin)
    doc = await _col.find_one({"code": code}, projection={"pin_hash": 1, "pin_salt": 1})
    if not doc:
        raise HTTPException(status_code=404, detail="Backup code not found.")
    if _hash_pin(pin, doc.get("pin_salt", "")) != doc.get("pin_hash"):
        raise HTTPException(status_code=403, detail="Incorrect PIN.")
    new_exp = datetime.now(timezone.utc) + timedelta(days=TTL_DAYS)
    await _col.update_one({"code": code}, {"$set": {"expires_at": new_exp}})
    return {"expires_at": new_exp.isoformat()}
