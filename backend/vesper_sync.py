"""
Vesper user-data cloud sync (v2.16.18).

Silent per-account cloud backup for the Vesper Movies/TV app.  Every
change to the user's Continue Watching, Library, Profiles, Live TV
favourites, EPG reminders, theme, etc. is quietly pushed up as an
opaque JSON snapshot; on a fresh install / new device the client
pulls the snapshot down and prompts the user to restore.

Keyed on the authenticated Vesper `account.id` (extracted server-side
from the JWT) so the client never has to hash usernames or manage a
separate key.

MongoDB collection: `vesper_sync` — one document per account.

Endpoints (all require a valid Vesper JWT in `Authorization`):
  • POST /api/vesper/sync/push   { data, client_updated_at? }
  • GET  /api/vesper/sync/pull
  • GET  /api/vesper/sync/meta

`meta` returns just `{found, updated_at, key_count, approx_bytes}` —
handy for a "Last synced …" indicator without shipping the whole
payload down the wire.
"""

from __future__ import annotations

import json
import time
from typing import Any, Callable, Dict, Optional

from fastapi import APIRouter, Body, Header, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/vesper/sync", tags=["vesper-sync"])

# Populated at boot by server.py via ``configure_vesper_sync(db, auth_fn)``.
# `auth_fn` is an async callable ``(authorization: str|None) -> account_dict``
# that raises HTTPException(401) on any failure.  We store it here rather
# than using FastAPI's Depends() indirection because the JWT dependency is
# a closure produced at boot by `make_get_current_account(db_provider)`.
_STATE: Dict[str, Any] = {"db": None, "auth_fn": None}


def configure_vesper_sync(db, auth_fn: Callable) -> None:
    _STATE["db"] = db
    _STATE["auth_fn"] = auth_fn


def _coll():
    db = _STATE["db"]
    if db is None:
        raise HTTPException(status_code=503, detail="vesper sync store not initialised")
    return db["vesper_sync"]


async def _account(authorization: Optional[str]) -> Dict[str, Any]:
    fn = _STATE["auth_fn"]
    if fn is None:
        raise HTTPException(503, "vesper sync auth not wired")
    acc = await fn(authorization=authorization)
    if not isinstance(acc, dict) or not acc.get("id"):
        raise HTTPException(401, "Not authenticated")
    return acc


# ── models ──────────────────────────────────────────────────────────

class PushRequest(BaseModel):
    data: Dict[str, Any] = Field(default_factory=dict)
    client_updated_at: Optional[int] = None


class PushResponse(BaseModel):
    ok: bool
    updated_at: int
    bytes: int
    key_count: int


class PullResponse(BaseModel):
    found: bool
    data: Optional[Dict[str, Any]] = None
    updated_at: Optional[int] = None
    key_count: Optional[int] = None
    approx_bytes: Optional[int] = None


class MetaResponse(BaseModel):
    found: bool
    updated_at: Optional[int] = None
    key_count: Optional[int] = None
    approx_bytes: Optional[int] = None


# ── helpers ─────────────────────────────────────────────────────────

def _approx_bytes(data: Dict[str, Any]) -> int:
    try:
        return len(json.dumps(data, ensure_ascii=False))
    except Exception:
        return 0


# ── endpoints ───────────────────────────────────────────────────────

@router.post("/push", response_model=PushResponse)
async def push(
    body: PushRequest = Body(...),
    authorization: Optional[str] = Header(default=None),
) -> PushResponse:
    account = await _account(authorization)
    account_id = str(account["id"]).strip()
    now_ms = int(time.time() * 1000)
    approx = _approx_bytes(body.data)
    key_count = len(body.data or {})
    doc = {
        "account_id": account_id,
        "username": account.get("username"),
        "data": body.data,
        "updated_at": now_ms,
        "client_updated_at": body.client_updated_at,
        "approx_bytes": approx,
        "key_count": key_count,
    }
    await _coll().update_one(
        {"account_id": account_id},
        {"$set": doc},
        upsert=True,
    )
    return PushResponse(
        ok=True,
        updated_at=now_ms,
        bytes=approx,
        key_count=key_count,
    )


@router.get("/pull", response_model=PullResponse)
async def pull(
    authorization: Optional[str] = Header(default=None),
) -> PullResponse:
    account = await _account(authorization)
    account_id = str(account["id"]).strip()
    doc = await _coll().find_one(
        {"account_id": account_id},
        {"_id": 0, "data": 1, "updated_at": 1, "approx_bytes": 1, "key_count": 1},
    )
    if not doc:
        return PullResponse(found=False)
    return PullResponse(
        found=True,
        data=doc.get("data") or {},
        updated_at=doc.get("updated_at"),
        key_count=doc.get("key_count"),
        approx_bytes=doc.get("approx_bytes"),
    )


@router.get("/meta", response_model=MetaResponse)
async def meta(
    authorization: Optional[str] = Header(default=None),
) -> MetaResponse:
    account = await _account(authorization)
    account_id = str(account["id"]).strip()
    doc = await _coll().find_one(
        {"account_id": account_id},
        {"_id": 0, "updated_at": 1, "approx_bytes": 1, "key_count": 1},
    )
    if not doc:
        return MetaResponse(found=False)
    return MetaResponse(
        found=True,
        updated_at=doc.get("updated_at"),
        key_count=doc.get("key_count"),
        approx_bytes=doc.get("approx_bytes"),
    )
