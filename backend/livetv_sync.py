"""
Live TV user-data sync (v2.16.12).

Silent cloud backup for the native ON NOW TV Live TV app keyed by
Xtream credentials.  Every add/remove of a favourite / collection /
reminder pushes an opaque JSON snapshot up; every fresh login pulls
the last snapshot down.  MongoDB collection `livetv_sync` holds one
document per user hash (`user_key`).

Two endpoints:
  • POST /api/livetv/sync/push   — upserts `{data, updated_at}` for
    the caller's `user_key`.  Body is deliberately opaque so we can
    extend the client snapshot shape without touching the backend.
  • GET  /api/livetv/sync/pull   — returns `{found, data, updated_at}`
    for `user_key`.  404-esque `{found: false}` when no backup exists.

`user_key` is the SHA-256 hex of `HOST + "|" + username`, computed
client-side.  We never see the raw password.  Host is baked in as
`AuthStore.HOST` on the Android side.
"""

from __future__ import annotations

import time
from typing import Any, Dict, Optional

from fastapi import APIRouter, Body, HTTPException, Query, Request
from pydantic import BaseModel, Field


router = APIRouter(prefix="/api/livetv/sync", tags=["livetv-sync"])

# Populated by server.py at boot via ``configure_livetv_sync(db)``.
_DB = {"handle": None}


def configure_livetv_sync(db) -> None:
    """Bind the MongoDB handle from server.py's already-constructed
    Motor client.  Called once at boot after ``mongo`` is built."""
    _DB["handle"] = db


def _coll():
    db = _DB["handle"]
    if db is None:
        raise HTTPException(status_code=503, detail="sync store not initialised")
    return db["livetv_sync"]


# ── models ──────────────────────────────────────────────────────────

class PushRequest(BaseModel):
    user_key: str = Field(..., min_length=8, max_length=128)
    data: Dict[str, Any]
    # Client wall clock; used purely for observability + tie-breaking
    # if two devices push near-simultaneously.  Server always records
    # its OWN authoritative timestamp separately.
    client_updated_at: Optional[int] = None


class PushResponse(BaseModel):
    ok: bool
    updated_at: int
    bytes: int


class PullResponse(BaseModel):
    found: bool
    data: Optional[Dict[str, Any]] = None
    updated_at: Optional[int] = None


# ── endpoints ───────────────────────────────────────────────────────

@router.post("/push", response_model=PushResponse)
async def push(req: Request, body: PushRequest = Body(...)) -> PushResponse:
    """Upsert the user's snapshot.  Body kept minimal on purpose so
    changing the client blob shape never breaks the API."""
    if not body.user_key.strip():
        raise HTTPException(status_code=400, detail="user_key required")
    now_ms = int(time.time() * 1000)
    doc = {
        "user_key": body.user_key,
        "data": body.data,
        "updated_at": now_ms,
        "client_updated_at": body.client_updated_at,
    }
    coll = _coll()
    await coll.update_one(
        {"user_key": body.user_key},
        {"$set": doc},
        upsert=True,
    )
    # Rough size estimate — helps callers monitor bloat without
    # doing an extra round-trip.
    try:
        import json
        approx_bytes = len(json.dumps(body.data, ensure_ascii=False))
    except Exception:
        approx_bytes = 0
    return PushResponse(ok=True, updated_at=now_ms, bytes=approx_bytes)


@router.get("/pull", response_model=PullResponse)
async def pull(user_key: str = Query(..., min_length=8, max_length=128)) -> PullResponse:
    if not user_key.strip():
        raise HTTPException(status_code=400, detail="user_key required")
    coll = _coll()
    doc = await coll.find_one(
        {"user_key": user_key},
        {"_id": 0, "data": 1, "updated_at": 1},
    )
    if not doc:
        return PullResponse(found=False)
    return PullResponse(
        found=True,
        data=doc.get("data") or {},
        updated_at=doc.get("updated_at"),
    )
