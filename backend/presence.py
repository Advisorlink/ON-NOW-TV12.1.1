"""
Live-presence analytics for the ON NOW TV suite.

Every client app (Vesper movies, Vesper FTA, Live TV, Kids, Tunes,
FTA-native) pings this router every ~30 s while a user is watching
something.  Each ping is upserted into the `presence_sessions`
MongoDB collection keyed by (client_key, app), so a session is a
"who is doing what on which app" tuple that stays alive as long as
the client keeps heart-beating.

The admin panel in the launcher-backend reads this data via a small
proxy layer to show:
    * who's live RIGHT NOW (last_heartbeat within 90 s)
    * per-user 7-day history when the operator clicks a row

Two auth flavours are accepted:
    * `Authorization: Bearer <JWT>` — Vesper accounts (movies, FTA
      that runs inside Vesper, and the Kids/Tunes WebView shells
      which share the Vesper React frontend).
    * `X-Presence-Key: <PRESENCE_INGEST_KEY>` + `client_key` in body
      — Live TV / FTA-native / any native client that doesn't have
      a Vesper JWT.  The presence-ingest key is a shared secret so
      only our apps can post; the `client_key` field carries the
      Xtream / device username so the admin sees who's watching.

Read (admin) endpoints are gated on the same `X-Admin-Key` that the
rest of the Vesper admin API uses.
"""
from __future__ import annotations

import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Body, Depends, Header, HTTPException, Query
from motor.motor_asyncio import AsyncIOMotorDatabase
from pydantic import BaseModel, Field

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
ACTIVE_WINDOW_SECS = 90        # how recent last_heartbeat must be to count as "live"
HISTORY_TTL_SECS   = 7 * 24 * 3600  # keep session docs for 7 days, then auto-purge
DEFAULT_HISTORY_DAYS = 7


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _admin_key() -> str:
    k = os.environ.get("ADMIN_KEY")
    if not k:
        # Same default the launcher backend uses for VESPER_ADMIN_KEY,
        # so the two services authenticate without manual setup.
        k = "vesper-admin-49a1f8e2c7b03d6e85a4192c8d3f6e0a"
    return k


def _presence_ingest_key() -> str:
    k = os.environ.get("PRESENCE_INGEST_KEY")
    if not k:
        # Shared with native clients; deterministic default so brand-new
        # deployments don't 401 every heartbeat.  Rotate in .env when
        # you go multi-tenant.
        k = "onnow-presence-ingest-3f9c2a71b8de405e9047ac1d6f8b3e5c"
    return k


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------
class HeartbeatBody(BaseModel):
    session_id: str = Field(..., description="Client-generated UUID, stable for the life of one watching session")
    app: str = Field(..., description="One of: movies, livetv, tunes, kids, fta")
    content_kind: str = Field(..., description="movie | series | live_channel | fta_channel | music_track | kids_show")
    content_id: Optional[str] = None
    content_title: str = Field(..., description="Human-readable title shown in the admin table")
    content_meta: Optional[Dict[str, Any]] = None
    client_key: Optional[str] = Field(None, description="Only for non-JWT clients (Live TV, FTA native) — device username")
    device_hint: Optional[str] = Field(None, description="Free-form label, e.g. 'Living Room Box'")


class EndBody(BaseModel):
    session_id: str
    app: str


# ---------------------------------------------------------------------------
# Router
# ---------------------------------------------------------------------------
router = APIRouter(prefix="/api/presence", tags=["presence"])

_db: Optional[AsyncIOMotorDatabase] = None


def configure_presence(db: AsyncIOMotorDatabase) -> None:
    """Bind the router to the Motor db and ensure the required indexes.

    Called from `server.py` after `include_router`, mirroring the same
    pattern used by `livetv_sync` and `vesper_sync`.
    """
    global _db
    _db = db

    async def _ensure_indexes() -> None:
        try:
            await db.presence_sessions.create_index("last_heartbeat_at")
            await db.presence_sessions.create_index([("username", 1), ("started_at", -1)])
            await db.presence_sessions.create_index([("app", 1), ("last_heartbeat_at", -1)])
            # TTL index — sessions auto-purge 7 days after last heartbeat.
            await db.presence_sessions.create_index(
                "last_heartbeat_at",
                name="presence_ttl",
                expireAfterSeconds=HISTORY_TTL_SECS,
            )
        except Exception:
            # Index creation is best-effort; a running app must never
            # fail to import over a transient Mongo hiccup.
            pass

    import asyncio
    try:
        loop = asyncio.get_event_loop()
        if loop.is_running():
            loop.create_task(_ensure_indexes())
        else:
            loop.run_until_complete(_ensure_indexes())
    except Exception:
        pass


def _require_db() -> AsyncIOMotorDatabase:
    if _db is None:
        raise HTTPException(500, "Presence router not configured")
    return _db


# ---------------------------------------------------------------------------
# Auth helpers
# ---------------------------------------------------------------------------
def _decode_jwt(token: str) -> Optional[Dict[str, Any]]:
    """Lightweight JWT decode used to identify the reporting user.

    We tolerate failures silently and fall through to the ingest-key
    path so a stale token never bricks presence reporting.
    """
    try:
        import jwt as _jwt
        # Reuse the same secret + algorithm as auth_router so JWTs
        # issued by /api/auth/login validate here without a re-check.
        secret = os.environ.get("JWT_SECRET") or \
            "vesper-default-jwt-secret-rotate-me-c4a18f7d23e9b6a04f1e8c2d5a7b9e0f"
        return _jwt.decode(token, secret, algorithms=["HS256"])
    except Exception:
        return None


def _identify_reporter(
    authorization: Optional[str],
    x_presence_key: Optional[str],
    body_client_key: Optional[str],
    *,
    require_client_key: bool = True,
) -> Dict[str, str]:
    """Return `{username, account_id, source}` identifying who's reporting.

    Order of preference:
    1. JWT (Vesper account) → username + account_id from the token.
    2. X-Presence-Key + body.client_key → the client_key IS the username
       (Xtream login for Live TV, device username for FTA-native).

    When `require_client_key=False` (used by `/end`), the ingest-key
    path accepts an empty client_key — the session_id alone is enough
    to identify which row to end.
    """
    if authorization and authorization.lower().startswith("bearer "):
        tok = authorization.split(None, 1)[1].strip()
        claims = _decode_jwt(tok)
        if claims and claims.get("username"):
            return {
                "username": str(claims["username"]),
                "account_id": str(claims.get("sub") or ""),
                "source": "jwt",
            }
    if x_presence_key and x_presence_key == _presence_ingest_key():
        if body_client_key or not require_client_key:
            return {
                "username": (body_client_key or "").strip(),
                "account_id": "",
                "source": "ingest_key",
            }
    raise HTTPException(401, "Presence reporting requires a valid JWT or the X-Presence-Key header + client_key")


def _require_admin(x_admin_key: Optional[str]) -> None:
    if not x_admin_key or x_admin_key != _admin_key():
        raise HTTPException(403, "Admin key required")


# ---------------------------------------------------------------------------
# Endpoints — client-facing
# ---------------------------------------------------------------------------
@router.post("/heartbeat")
async def heartbeat(
    body: HeartbeatBody,
    authorization: Optional[str] = Header(default=None, alias="Authorization"),
    x_presence_key: Optional[str] = Header(default=None, alias="X-Presence-Key"),
):
    """Idempotent upsert; call every ~30 s while the user is watching."""
    who = _identify_reporter(authorization, x_presence_key, body.client_key)
    db = _require_db()
    now = _now()
    key = {"session_id": body.session_id, "app": body.app}
    set_fields: Dict[str, Any] = {
        "session_id":       body.session_id,
        "app":              body.app,
        "username":         who["username"],
        "account_id":       who["account_id"],
        "content_kind":     body.content_kind,
        "content_id":       body.content_id or "",
        "content_title":    body.content_title,
        "content_meta":     body.content_meta or {},
        "device_hint":      body.device_hint or "",
        "last_heartbeat_at": now,
        "reporter_source":  who["source"],
    }
    set_on_insert = {
        "id":         f"ps_{uuid.uuid4().hex[:20]}",
        "started_at": now,
        "ended_at":   None,
    }
    await db.presence_sessions.update_one(
        key,
        {"$set": set_fields, "$setOnInsert": set_on_insert},
        upsert=True,
    )
    return {"ok": True, "server_time": now.isoformat()}


@router.post("/end")
async def end_session(
    body: EndBody,
    authorization: Optional[str] = Header(default=None, alias="Authorization"),
    x_presence_key: Optional[str] = Header(default=None, alias="X-Presence-Key"),
):
    """Explicit end — client sends this when the user stops watching."""
    # We identify the reporter mostly for auth; we don't verify that
    # the session belongs to them, so a rogue client can only end
    # sessions whose UUID it already knows.
    _identify_reporter(authorization, x_presence_key, None, require_client_key=False)
    db = _require_db()
    now = _now()
    await db.presence_sessions.update_one(
        {"session_id": body.session_id, "app": body.app},
        {"$set": {"ended_at": now}},
    )
    return {"ok": True}


# ---------------------------------------------------------------------------
# Endpoints — admin
# ---------------------------------------------------------------------------
def _shape_session(doc: Dict[str, Any], now: datetime) -> Dict[str, Any]:
    def _aware(dt: Any) -> Optional[datetime]:
        """Mongo returns naive UTC datetimes; retag them so we can
        subtract them from a `_now()` that IS tz-aware."""
        if not isinstance(dt, datetime):
            return None
        return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)

    started = _aware(doc.get("started_at"))
    last_hb = _aware(doc.get("last_heartbeat_at"))
    ended   = _aware(doc.get("ended_at"))
    duration_secs = 0
    if started:
        end_at = ended if ended else (last_hb or now)
        try:
            duration_secs = int((end_at - started).total_seconds())
        except Exception:
            duration_secs = 0
    return {
        "id":               doc.get("id") or "",
        "session_id":       doc.get("session_id") or "",
        "app":              doc.get("app") or "",
        "username":         doc.get("username") or "",
        "account_id":       doc.get("account_id") or "",
        "content_kind":     doc.get("content_kind") or "",
        "content_id":       doc.get("content_id") or "",
        "content_title":    doc.get("content_title") or "",
        "content_meta":     doc.get("content_meta") or {},
        "device_hint":      doc.get("device_hint") or "",
        "started_at":       started.isoformat() if started else None,
        "last_heartbeat_at": last_hb.isoformat() if last_hb else None,
        "ended_at":         ended.isoformat() if ended else None,
        "duration_secs":    duration_secs,
        "is_live":          (last_hb is not None and (now - last_hb).total_seconds() <= ACTIVE_WINDOW_SECS
                             and ended is None),
    }


@router.get("/admin/active")
async def admin_active(
    x_admin_key: Optional[str] = Header(default=None, alias="X-Admin-Key"),
):
    """Everyone whose last heartbeat is within the active window."""
    _require_admin(x_admin_key)
    db = _require_db()
    now = _now()
    cutoff = now - timedelta(seconds=ACTIVE_WINDOW_SECS)
    rows = await db.presence_sessions.find(
        {"last_heartbeat_at": {"$gte": cutoff}, "ended_at": None},
        {"_id": 0},
    ).sort("last_heartbeat_at", -1).to_list(500)
    return {
        "count":    len(rows),
        "sessions": [_shape_session(r, now) for r in rows],
        "as_of":    now.isoformat(),
    }


@router.get("/admin/user/{username}/history")
async def admin_user_history(
    username: str,
    days: int = Query(default=DEFAULT_HISTORY_DAYS, ge=1, le=30),
    x_admin_key: Optional[str] = Header(default=None, alias="X-Admin-Key"),
):
    """Every session a given user had in the last N days.

    Username lookup is case-sensitive on purpose — the values that
    land in `username` are user IDs that we never touch server-side,
    so exact match matches what the admin sees in the live table.
    """
    _require_admin(x_admin_key)
    db = _require_db()
    now = _now()
    cutoff = now - timedelta(days=days)
    rows = await db.presence_sessions.find(
        {"username": username, "started_at": {"$gte": cutoff}},
        {"_id": 0},
    ).sort("started_at", -1).to_list(500)
    return {
        "username": username,
        "days":     days,
        "count":    len(rows),
        "sessions": [_shape_session(r, now) for r in rows],
        "as_of":    now.isoformat(),
    }
