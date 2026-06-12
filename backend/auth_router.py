"""
Vesper v2 — Custom JWT login system backed by `xtream_accounts`.

The end-user signs in with `username` + `password` ONLY (no DNS field
shown to them).  Behind the scenes each row in `xtream_accounts` carries
the DNS / Xtream-Codes server URL that the rest of the stack uses to
fetch live TV / VOD.

There is NO public registration endpoint.  All accounts are seeded /
edited by the admin (developer) via `/api/admin/accounts/*` which is
gated by a shared-secret header (`X-Admin-Key`) matching the
`ADMIN_KEY` env var.

Tokens
------
We issue HS256 JWTs valid for 30 days.  The frontend stores them in
`localStorage` (key: `vesper-auth-token-v1`) and sends them on every
request as `Authorization: Bearer …`.  We do NOT use httpOnly cookies
because the React build runs inside an Android WebView wrapper where
cross-origin cookies + Kubernetes ingress are fragile.
"""
from __future__ import annotations

import hmac
import os
import secrets
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

import bcrypt
import jwt
from fastapi import APIRouter, Body, Depends, Header, HTTPException, Request
from motor.motor_asyncio import AsyncIOMotorDatabase
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Config (loaded lazily so this module is import-safe even if env is unset)
# ---------------------------------------------------------------------------
JWT_ALG = "HS256"
ACCESS_TTL_DAYS = 30
LOCKOUT_THRESHOLD = 5
LOCKOUT_WINDOW_MIN = 15


def _jwt_secret() -> str:
    s = os.environ.get("JWT_SECRET")
    if not s:
        raise HTTPException(500, "Server JWT_SECRET not configured")
    return s


def _admin_key() -> str:
    k = os.environ.get("ADMIN_KEY")
    if not k:
        raise HTTPException(500, "Server ADMIN_KEY not configured")
    return k


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(dt: datetime) -> str:
    return dt.isoformat()


def _constant_time_eq(a: str, b: str) -> bool:
    """Time-constant comparison for plaintext Xtream passwords."""
    return hmac.compare_digest(a.encode("utf-8"), b.encode("utf-8"))


def _create_access_token(account_id: str, username: str) -> str:
    payload = {
        "sub": account_id,
        "username": username,
        "exp": _now() + timedelta(days=ACCESS_TTL_DAYS),
        "iat": _now(),
        "type": "access",
    }
    return jwt.encode(payload, _jwt_secret(), algorithm=JWT_ALG)


def _account_to_public(row: Dict[str, Any]) -> Dict[str, Any]:
    """Public shape — never includes the raw Xtream password.  The DNS
    stays in this payload because the frontend needs it to compose
    Live TV / VOD requests, BUT the user never sees it (UI hides it)."""
    return {
        "id":         row.get("id") or str(row.get("_id", "")),
        "username":   row.get("username", ""),
        "label":      row.get("label") or row.get("username", ""),
        "dns":        row.get("dns", ""),
        "status":     row.get("status", "active"),
        "expires_at": row.get("expires_at"),
        "created_at": row.get("created_at"),
        "notes":      row.get("notes", ""),
    }


# ---------------------------------------------------------------------------
# Brute-force lockout
# ---------------------------------------------------------------------------
async def _is_locked_out(db: AsyncIOMotorDatabase, identifier: str) -> bool:
    cutoff = _now() - timedelta(minutes=LOCKOUT_WINDOW_MIN)
    n = await db.login_attempts.count_documents({
        "identifier": identifier,
        "ts": {"$gte": cutoff.isoformat()},
        "success": False,
    })
    return n >= LOCKOUT_THRESHOLD


async def _record_attempt(
    db: AsyncIOMotorDatabase, identifier: str, success: bool
) -> None:
    await db.login_attempts.insert_one({
        "identifier": identifier,
        "ts":         _now().isoformat(),
        "success":    success,
    })
    if success:
        # Clear failed attempts on successful login.
        await db.login_attempts.delete_many({
            "identifier": identifier, "success": False,
        })


# ---------------------------------------------------------------------------
# Pydantic schemas
# ---------------------------------------------------------------------------
class LoginRequest(BaseModel):
    username: str = Field(min_length=1, max_length=128)
    password: str = Field(min_length=1, max_length=256)


class AccountCreate(BaseModel):
    dns:        str = Field(min_length=1, max_length=512)
    username:   str = Field(min_length=1, max_length=128)
    password:   str = Field(min_length=1, max_length=256)
    label:      Optional[str] = None
    expires_at: Optional[str] = None  # ISO 8601
    status:     Optional[str] = Field(default="active")
    notes:      Optional[str] = ""


class AccountUpdate(BaseModel):
    dns:        Optional[str] = None
    username:   Optional[str] = None
    password:   Optional[str] = None
    label:      Optional[str] = None
    expires_at: Optional[str] = None
    status:     Optional[str] = None
    notes:      Optional[str] = None


# ---------------------------------------------------------------------------
# Auth dependency — verifies bearer token and resolves the account.
# ---------------------------------------------------------------------------
def make_get_current_account(db_provider):
    """db_provider is a callable returning the Motor DB.  Wrapped this
    way so server.py can pass its already-initialised `db` in without
    creating circular imports."""

    async def get_current_account(
        authorization: Optional[str] = Header(default=None),
    ) -> Dict[str, Any]:
        if not authorization or not authorization.lower().startswith("bearer "):
            raise HTTPException(401, "Not authenticated")
        token = authorization.split(" ", 1)[1].strip()
        try:
            payload = jwt.decode(token, _jwt_secret(), algorithms=[JWT_ALG])
        except jwt.ExpiredSignatureError:
            raise HTTPException(401, "Token expired")
        except jwt.InvalidTokenError:
            raise HTTPException(401, "Invalid token")
        if payload.get("type") != "access":
            raise HTTPException(401, "Invalid token type")
        account_id = payload.get("sub") or ""
        db = db_provider()
        row = await db.xtream_accounts.find_one({"id": account_id}, {"_id": 0})
        if not row:
            raise HTTPException(401, "Account no longer exists")
        if row.get("status") == "disabled":
            raise HTTPException(401, "Account is disabled")
        return row

    return get_current_account


def require_admin(
    x_admin_key: Optional[str] = Header(default=None, alias="X-Admin-Key"),
) -> None:
    if not x_admin_key or not _constant_time_eq(x_admin_key, _admin_key()):
        raise HTTPException(403, "Admin key required")


# ---------------------------------------------------------------------------
# Router factory
# ---------------------------------------------------------------------------
def build_auth_router(db_provider) -> APIRouter:
    """Return a FastAPI router carrying every auth + admin endpoint.

    `db_provider` is a 0-arg callable that returns the live Motor DB.
    We call it lazily inside each request so server.py can mount the
    router before the `db` global is fully initialised.
    """
    router = APIRouter(prefix="/api")
    get_current_account = make_get_current_account(db_provider)

    # ----- Public auth -----
    @router.post("/auth/login")
    async def login(req: LoginRequest, request: Request):
        db = db_provider()
        username = req.username.strip()
        if not username:
            raise HTTPException(400, "Username required")

        ip = (request.client.host if request.client else "?") or "?"
        identifier = f"{ip}:{username.lower()}"

        if await _is_locked_out(db, identifier):
            raise HTTPException(
                429,
                f"Too many failed attempts. Try again in {LOCKOUT_WINDOW_MIN} minutes.",
            )

        row = await db.xtream_accounts.find_one(
            {"username": username}, {"_id": 0}
        )
        if not row:
            await _record_attempt(db, identifier, success=False)
            raise HTTPException(401, "Invalid username or password")

        stored_password = row.get("password") or ""
        if not _constant_time_eq(req.password, stored_password):
            await _record_attempt(db, identifier, success=False)
            raise HTTPException(401, "Invalid username or password")

        if row.get("status") == "disabled":
            raise HTTPException(403, "This account is disabled")

        # Optional expiry gate — skipped when not set.
        expires_at = row.get("expires_at")
        if expires_at:
            try:
                exp_dt = datetime.fromisoformat(expires_at.replace("Z", "+00:00"))
                if exp_dt < _now():
                    raise HTTPException(403, "This account has expired")
            except ValueError:
                pass  # malformed expiry — let it through

        await _record_attempt(db, identifier, success=True)
        token = _create_access_token(row["id"], row["username"])
        return {
            "access_token": token,
            "token_type":   "bearer",
            "expires_in":   ACCESS_TTL_DAYS * 24 * 3600,
            "account":      _account_to_public(row),
        }

    @router.get("/auth/me")
    async def me(account: Dict[str, Any] = Depends(get_current_account)):
        return {"account": _account_to_public(account)}

    @router.post("/auth/logout")
    async def logout(
        account: Dict[str, Any] = Depends(get_current_account),
    ):
        # JWTs are stateless — client just deletes the token.  We
        # return 200 so the frontend can wire a confirmation toast.
        return {"ok": True}

    # ----- Admin (X-Admin-Key gated) -----
    @router.get(
        "/admin/accounts",
        dependencies=[Depends(require_admin)],
    )
    async def admin_list():
        db = db_provider()
        rows = await db.xtream_accounts.find({}, {"_id": 0}).to_list(500)
        # Sort by label/username.
        rows.sort(key=lambda r: (r.get("label") or r.get("username") or "").lower())
        # Admin gets the password too (needed to manage).
        return {
            "accounts": [
                {**_account_to_public(r), "password": r.get("password", "")}
                for r in rows
            ],
            "count": len(rows),
        }

    @router.post(
        "/admin/accounts",
        dependencies=[Depends(require_admin)],
    )
    async def admin_create(req: AccountCreate):
        db = db_provider()
        username = req.username.strip()
        existing = await db.xtream_accounts.find_one(
            {"username": username}, {"_id": 0}
        )
        if existing:
            raise HTTPException(409, f"Username '{username}' already exists")
        doc = {
            "id":         f"xa_{uuid.uuid4().hex[:16]}",
            "dns":        req.dns.strip(),
            "username":   username,
            "password":   req.password,
            "label":      (req.label or username).strip(),
            "status":     (req.status or "active").strip().lower(),
            "expires_at": req.expires_at,
            "notes":      (req.notes or "").strip(),
            "created_at": _now().isoformat(),
        }
        await db.xtream_accounts.insert_one(doc)
        doc.pop("_id", None)
        return {"ok": True, "account": {**_account_to_public(doc), "password": doc["password"]}}

    @router.patch(
        "/admin/accounts/{account_id}",
        dependencies=[Depends(require_admin)],
    )
    async def admin_update(account_id: str, req: AccountUpdate):
        db = db_provider()
        patch = {k: v for k, v in req.model_dump().items() if v is not None}
        if not patch:
            raise HTTPException(400, "No fields to update")
        if "username" in patch:
            # Check uniqueness if username is changing.
            clash = await db.xtream_accounts.find_one(
                {"username": patch["username"], "id": {"$ne": account_id}},
                {"_id": 0, "id": 1},
            )
            if clash:
                raise HTTPException(409, f"Username '{patch['username']}' already exists")
        res = await db.xtream_accounts.update_one(
            {"id": account_id}, {"$set": patch}
        )
        if res.matched_count == 0:
            raise HTTPException(404, "Account not found")
        row = await db.xtream_accounts.find_one({"id": account_id}, {"_id": 0})
        return {"ok": True, "account": {**_account_to_public(row), "password": row.get("password", "")}}

    @router.delete(
        "/admin/accounts/{account_id}",
        dependencies=[Depends(require_admin)],
    )
    async def admin_delete(account_id: str):
        db = db_provider()
        res = await db.xtream_accounts.delete_one({"id": account_id})
        if res.deleted_count == 0:
            raise HTTPException(404, "Account not found")
        return {"ok": True}

    @router.post(
        "/admin/accounts/bulk-import",
        dependencies=[Depends(require_admin)],
    )
    async def admin_bulk_import(body: Dict[str, Any] = Body(...)):
        """Insert many accounts at once.  Body shape:
        { "accounts": [ {dns, username, password, label?, expires_at?,
                         status?, notes?}, ... ],
          "replace_existing": false }
        """
        db = db_provider()
        accounts = body.get("accounts") or []
        if not isinstance(accounts, list):
            raise HTTPException(400, "accounts must be a list")
        replace = bool(body.get("replace_existing", False))

        inserted, updated, skipped = 0, 0, 0
        errors: List[str] = []
        for entry in accounts:
            if not isinstance(entry, dict):
                errors.append("Skipped non-object entry")
                continue
            username = (entry.get("username") or "").strip()
            password = entry.get("password") or ""
            dns      = (entry.get("dns") or "").strip()
            if not (username and password and dns):
                errors.append(f"Missing fields for entry: {entry}")
                continue
            existing = await db.xtream_accounts.find_one(
                {"username": username}, {"_id": 0, "id": 1}
            )
            if existing:
                if replace:
                    patch = {
                        "dns":        dns,
                        "password":   password,
                        "label":      (entry.get("label") or username),
                        "status":     (entry.get("status") or "active").lower(),
                        "expires_at": entry.get("expires_at"),
                        "notes":      entry.get("notes") or "",
                    }
                    await db.xtream_accounts.update_one(
                        {"id": existing["id"]}, {"$set": patch}
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
                "created_at": _now().isoformat(),
            }
            await db.xtream_accounts.insert_one(doc)
            inserted += 1

        return {
            "ok":       True,
            "inserted": inserted,
            "updated":  updated,
            "skipped":  skipped,
            "errors":   errors,
        }

    return router


# ---------------------------------------------------------------------------
# Index bootstrap — called once at startup from server.py
# ---------------------------------------------------------------------------
async def ensure_indexes(db: AsyncIOMotorDatabase) -> None:
    await db.xtream_accounts.create_index("username", unique=True)
    await db.xtream_accounts.create_index("id", unique=True, sparse=True)
    await db.login_attempts.create_index("identifier")
    await db.login_attempts.create_index("ts")
