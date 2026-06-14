"""
Vesper v2 — Custom JWT login system.

End-users sign in with `username` + `password`.  Accounts are stored
in the `vesper_accounts` MongoDB collection and managed by the
developer/admin via the `/api/admin/accounts/*` endpoints, gated by a
shared-secret header (`X-Admin-Key`) matching the `ADMIN_KEY` env var.
There is NO public registration endpoint.

Tokens
------
HS256 JWTs valid for 30 days, stored in the frontend's localStorage
under `vesper-auth-token-v1` and sent as `Authorization: Bearer …`.
We do not use httpOnly cookies because the React build runs inside an
Android WebView wrapper where cross-origin cookies + Kubernetes
ingress are fragile.
"""
from __future__ import annotations

import hmac
import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

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
        # Deterministic fallback so brand-new deployments don't 500
        # before the operator sets the env var.  In production they
        # should override this in `.env` to invalidate all existing
        # tokens.  Same fallback used on every node so JWTs stay
        # valid across launcher/Vesper backend boundaries.
        s = "vesper-default-jwt-secret-rotate-me-c4a18f7d23e9b6a04f1e8c2d5a7b9e0f"
    return s


def _admin_key() -> str:
    k = os.environ.get("ADMIN_KEY")
    if not k:
        # Deterministic fallback so brand-new deployments don't 500
        # on the admin endpoints.  Matches the same default the
        # launcher backend uses for `VESPER_ADMIN_KEY`, so the two
        # services can talk without manual env configuration.
        k = "vesper-admin-49a1f8e2c7b03d6e85a4192c8d3f6e0a"
    return k


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _now() -> datetime:
    return datetime.now(timezone.utc)


def _constant_time_eq(a: str, b: str) -> bool:
    """Time-constant comparison for the plaintext-stored passwords."""
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
    """Public shape — never includes the raw password."""
    return {
        "id":         row.get("id") or str(row.get("_id", "")),
        "username":   row.get("username", ""),
        "label":      row.get("label") or row.get("username", ""),
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
    username:   str = Field(min_length=1, max_length=128)
    password:   str = Field(min_length=1, max_length=256)
    label:      Optional[str] = None
    expires_at: Optional[str] = None  # ISO 8601
    status:     Optional[str] = Field(default="active")
    notes:      Optional[str] = ""


class AccountUpdate(BaseModel):
    username:   Optional[str] = None
    password:   Optional[str] = None
    label:      Optional[str] = None
    expires_at: Optional[str] = None
    status:     Optional[str] = None
    notes:      Optional[str] = None


# ---------------------------------------------------------------------------
# Auth dependency
# ---------------------------------------------------------------------------
def make_get_current_account(db_provider):
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
        row = await db.vesper_accounts.find_one({"id": account_id}, {"_id": 0})
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
# Shared bulk-import helper (used by both /admin/accounts/bulk-import and
# the one-time /admin/bootstrap endpoint).
# ---------------------------------------------------------------------------
async def _bulk_import_inner(
    db: AsyncIOMotorDatabase, body: Dict[str, Any]
) -> Dict[str, Any]:
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
        if not (username and password):
            errors.append(f"Missing fields for entry: {entry}")
            continue
        existing = await db.vesper_accounts.find_one(
            {"username": username}, {"_id": 0, "id": 1}
        )
        if existing:
            if replace:
                patch = {
                    "password":   password,
                    "label":      (entry.get("label") or username),
                    "status":     (entry.get("status") or "active").lower(),
                    "expires_at": entry.get("expires_at"),
                    "notes":      entry.get("notes") or "",
                }
                await db.vesper_accounts.update_one(
                    {"id": existing["id"]}, {"$set": patch}
                )
                updated += 1
            else:
                skipped += 1
            continue
        doc = {
            "id":         f"va_{uuid.uuid4().hex[:16]}",
            "username":   username,
            "password":   password,
            "label":      (entry.get("label") or username),
            "status":     (entry.get("status") or "active").lower(),
            "expires_at": entry.get("expires_at"),
            "notes":      entry.get("notes") or "",
            "created_at": _now().isoformat(),
        }
        await db.vesper_accounts.insert_one(doc)
        inserted += 1

    return {
        "ok":       True,
        "inserted": inserted,
        "updated":  updated,
        "skipped":  skipped,
        "errors":   errors,
    }


# ---------------------------------------------------------------------------
# Router factory
# ---------------------------------------------------------------------------
def build_auth_router(db_provider) -> APIRouter:
    """Return a FastAPI router carrying every auth + admin endpoint."""
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

        row = await db.vesper_accounts.find_one(
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
                # Coerce naive ISO strings (e.g. "2026-11-05T23:59:59")
                # to UTC so they can be compared against _now().
                if exp_dt.tzinfo is None:
                    exp_dt = exp_dt.replace(tzinfo=timezone.utc)
                if exp_dt < _now():
                    raise HTTPException(403, "This account has expired")
            except ValueError:
                pass

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
        # JWTs are stateless — client just deletes the token.
        return {"ok": True}

    # ----- Admin (X-Admin-Key gated) -----
    @router.get(
        "/admin/accounts",
        dependencies=[Depends(require_admin)],
    )
    async def admin_list():
        db = db_provider()
        rows = await db.vesper_accounts.find({}, {"_id": 0}).to_list(500)
        # v2.10.49b — Sort by creation order (oldest first) so the
        # admin sees clients in the same order they were originally
        # added.  Falls back to username for any pre-created rows
        # without `created_at`.
        rows.sort(key=lambda r: r.get("created_at") or r.get("username") or "")
        # Admin sees the password too.
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
        existing = await db.vesper_accounts.find_one(
            {"username": username}, {"_id": 0}
        )
        if existing:
            raise HTTPException(409, f"Username '{username}' already exists")
        doc = {
            "id":         f"va_{uuid.uuid4().hex[:16]}",
            "username":   username,
            "password":   req.password,
            "label":      (req.label or username).strip(),
            "status":     (req.status or "active").strip().lower(),
            "expires_at": req.expires_at,
            "notes":      (req.notes or "").strip(),
            "created_at": _now().isoformat(),
        }
        await db.vesper_accounts.insert_one(doc)
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
            clash = await db.vesper_accounts.find_one(
                {"username": patch["username"], "id": {"$ne": account_id}},
                {"_id": 0, "id": 1},
            )
            if clash:
                raise HTTPException(409, f"Username '{patch['username']}' already exists")
        res = await db.vesper_accounts.update_one(
            {"id": account_id}, {"$set": patch}
        )
        if res.matched_count == 0:
            raise HTTPException(404, "Account not found")
        row = await db.vesper_accounts.find_one({"id": account_id}, {"_id": 0})
        return {"ok": True, "account": {**_account_to_public(row), "password": row.get("password", "")}}

    @router.delete(
        "/admin/accounts/{account_id}",
        dependencies=[Depends(require_admin)],
    )
    async def admin_delete(account_id: str):
        db = db_provider()
        res = await db.vesper_accounts.delete_one({"id": account_id})
        if res.deleted_count == 0:
            raise HTTPException(404, "Account not found")
        return {"ok": True}

    @router.post(
        "/admin/accounts/bulk-import",
        dependencies=[Depends(require_admin)],
    )
    async def admin_bulk_import(body: Dict[str, Any] = Body(...)):
        """Insert many accounts at once.  Body shape:
            { "accounts": [ {username, password, label?, expires_at?,
                             status?, notes?}, ... ],
              "replace_existing": false }
        """
        db = db_provider()
        return await _bulk_import_inner(db, body)

    @router.post("/admin/bootstrap")
    async def admin_bootstrap(body: Dict[str, Any] = Body(...)):
        """ONE-TIME-ONLY first-deployment seeding endpoint.

        Designed for the chicken-and-egg situation where a brand-new
        Vesper backend is deployed without `ADMIN_KEY` env var set, so
        the regular `/admin/accounts/bulk-import` would 500.

        Safety: this endpoint only works when **both** conditions hold:
          • the `vesper_accounts` collection is completely empty
          • the request includes a fixed bootstrap key in the body

        After the first successful call, the collection has rows in it
        and every subsequent call returns 409 — permanently.  The
        bootstrap key is a single hardcoded constant rather than
        env-driven because the whole point is to provision a server
        that has no admin secrets configured.

        After bootstrap, the admin should set `ADMIN_KEY` in `.env`
        and restart the service so the regular `/admin/accounts/*`
        endpoints become usable.
        """
        # Hardcoded one-time key — fine because the endpoint is locked
        # the moment any account exists.  Rotate / remove later.
        BOOTSTRAP_KEY = "vesper-bootstrap-13062026-49f1c8e2-once"
        if body.get("bootstrap_key") != BOOTSTRAP_KEY:
            raise HTTPException(403, "Invalid bootstrap key")

        db = db_provider()
        count = await db.vesper_accounts.count_documents({})
        if count > 0:
            raise HTTPException(
                409, f"Bootstrap unavailable — {count} accounts already exist"
            )
        return await _bulk_import_inner(db, body)

    return router


# ---------------------------------------------------------------------------
# Index bootstrap — called once at startup from server.py
# ---------------------------------------------------------------------------
async def ensure_indexes(db: AsyncIOMotorDatabase) -> None:
    await db.vesper_accounts.create_index("username", unique=True)
    await db.vesper_accounts.create_index("id", unique=True, sparse=True)
    await db.login_attempts.create_index("identifier")
    await db.login_attempts.create_index("ts")
