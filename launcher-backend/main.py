"""
ON NOW TV V2 Launcher — Admin Backend
─────────────────────────────────────
Completely separate FastAPI service that drives:
  • The 6 dock tiles (label, sub, icon, target intent) on every launcher
  • The wallpaper / background image
  • The APK manifest (sideloadable apps the launcher offers to install)
  • Popup notifications broadcast to every launcher

Storage is a JSON file on disk (`data/store.json`) — no database
dependency.  Replace with MongoDB / Postgres later if you need
multi-instance HA.  Single-instance is fine for thousands of
launcher clients polling every few minutes.

Run locally:
    cd /app/launcher-backend
    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8002 --reload

Run in production:
    docker build -t onnow-launcher-api .
    docker run -d --restart=always -p 8002:8002 \
        -e ADMIN_TOKEN=$(openssl rand -hex 32) \
        -v /opt/onnow-launcher-data:/data \
        onnow-launcher-api
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import shutil
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Optional

import aiofiles
import jwt
from fastapi import (
    Depends,
    FastAPI,
    File,
    Form,
    HTTPException,
    Request,
    UploadFile,
    status,
)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

# ════════════════════════════════════════════════════════════════════
#  Config + bootstrap
# ════════════════════════════════════════════════════════════════════
log = logging.getLogger("launcher-api")
logging.basicConfig(
    level=os.environ.get("LOG_LEVEL", "INFO"),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)

DATA_DIR = Path(os.environ.get("DATA_DIR", "/app/launcher-backend/data"))
DATA_DIR.mkdir(parents=True, exist_ok=True)
(DATA_DIR / "icons").mkdir(exist_ok=True)
(DATA_DIR / "wallpapers").mkdir(exist_ok=True)
(DATA_DIR / "apks").mkdir(exist_ok=True)

STORE_FILE = DATA_DIR / "store.json"

# Admin token comes from env; auto-generated on first launch if not set
# (the generated token is printed to the log so the operator can copy it).
ADMIN_TOKEN: str = os.environ.get("ADMIN_TOKEN") or ""
if not ADMIN_TOKEN:
    ADMIN_TOKEN = hashlib.sha256(os.urandom(32)).hexdigest()
    log.warning(
        "ADMIN_TOKEN was not set in the environment; generated a "
        "transient token for this run:  %s",
        ADMIN_TOKEN,
    )
    log.warning(
        "Set ADMIN_TOKEN as an env var BEFORE production to keep it "
        "stable across restarts."
    )

JWT_SECRET = hashlib.sha256(ADMIN_TOKEN.encode()).hexdigest()
JWT_ALG = "HS256"

# Public base URL — used to build absolute asset URLs returned in
# /api/launcher/config so device clients can fetch icons / wallpapers
# / APK files directly.  Default = same host, port 8002.
PUBLIC_BASE_URL = os.environ.get("PUBLIC_BASE_URL", "http://localhost:8002").rstrip("/")


# ════════════════════════════════════════════════════════════════════
#  Models
# ════════════════════════════════════════════════════════════════════
class DockTile(BaseModel):
    key: str
    label: str
    sub: str
    icon_url: Optional[str] = None        # absolute URL or null → use built-in
    target_package: Optional[str] = None  # Android package name to launch
    target_url: Optional[str]     = None  # http(s):// URL to open in browser
    accent: Optional[str]         = None  # "#RRGGBB" hex — accent for the section


class Wallpaper(BaseModel):
    id: str
    filename: str
    url: str
    uploaded_at: int                       # unix timestamp


class ApkEntry(BaseModel):
    id: str
    name: str
    package_id: Optional[str] = None       # for de-dupe / version check
    version_name: Optional[str] = None
    icon_url: Optional[str] = None
    apk_url: str                           # either /assets/apks/x.apk or remote https
    description: Optional[str] = None
    added_at: int


class Notification(BaseModel):
    id: str
    title: str
    body: str
    image_url: Optional[str] = None
    created_at: int
    expires_at: int                        # unix timestamp; clients ignore if past
    seen_by: list[str] = Field(default_factory=list)


class LauncherConfig(BaseModel):
    """Single document returned by /api/launcher/config that the
    launcher device polls every few minutes."""
    dock_tiles: list[DockTile]
    active_wallpaper_url: Optional[str]
    apks: list[ApkEntry]
    notifications: list[Notification]      # un-expired notifications only
    generation: int                        # bumps every time admin saves anything
    server_time: int


# ════════════════════════════════════════════════════════════════════
#  Storage
# ════════════════════════════════════════════════════════════════════
import json
from threading import RLock

_lock = RLock()


def _default_store() -> dict:
    """Seed defaults so a brand-new launcher install renders the
    intended 6-tile design out of the box."""
    return {
        "generation": 1,
        "dock_tiles": [
            {"key": "movies",   "label": "Movies & TV Shows", "sub": "Stream and enjoy",     "icon_url": None,
             "target_package": "tv.onnowtv.app", "target_url": None, "accent": "#38B8FF"},
            {"key": "music",    "label": "Music",             "sub": "Listen and enjoy",    "icon_url": None,
             "target_package": None,             "target_url": None, "accent": "#38B8FF"},
            {"key": "livetv",   "label": "Live TV",           "sub": "Watch live channels", "icon_url": None,
             "target_package": "tv.onnowtv.app", "target_url": None, "accent": "#2BB6FF"},
            {"key": "apps",     "label": "Apps",              "sub": "All your apps",       "icon_url": None,
             "target_package": None,             "target_url": None, "accent": "#2EEAC2"},
            {"key": "browser",  "label": "Browser",           "sub": "Surf the web",        "icon_url": None,
             "target_package": None,             "target_url": None, "accent": "#38C2FF"},
            {"key": "settings", "label": "Settings",          "sub": "System preferences",  "icon_url": None,
             "target_package": None,             "target_url": None, "accent": "#5BC5FF"},
        ],
        "active_wallpaper_id": None,
        "wallpapers": [],
        "apks": [],
        "notifications": [],
    }


def _load_store() -> dict:
    with _lock:
        if not STORE_FILE.exists():
            store = _default_store()
            STORE_FILE.write_text(json.dumps(store, indent=2))
            return store
        try:
            return json.loads(STORE_FILE.read_text())
        except Exception:
            log.exception("Corrupt store.json — re-seeding defaults")
            store = _default_store()
            STORE_FILE.write_text(json.dumps(store, indent=2))
            return store


def _save_store(store: dict) -> None:
    with _lock:
        store["generation"] = int(store.get("generation", 0)) + 1
        # Write atomic: write to tmp then rename.
        tmp = STORE_FILE.with_suffix(".tmp")
        tmp.write_text(json.dumps(store, indent=2))
        tmp.replace(STORE_FILE)


def now_ts() -> int:
    return int(datetime.now(timezone.utc).timestamp())


# ════════════════════════════════════════════════════════════════════
#  Auth
# ════════════════════════════════════════════════════════════════════
def require_admin(request: Request) -> None:
    """Two ways to authenticate:
      1. `Authorization: Bearer <ADMIN_TOKEN>` header (machine clients).
      2. `admin_session` cookie holding a JWT (browser admin UI).
    Either passes."""
    auth = request.headers.get("authorization", "")
    if auth.startswith("Bearer "):
        if auth.removeprefix("Bearer ").strip() == ADMIN_TOKEN:
            return
    # Try session cookie
    cookie = request.cookies.get("admin_session", "")
    if cookie:
        try:
            jwt.decode(cookie, JWT_SECRET, algorithms=[JWT_ALG])
            return
        except jwt.InvalidTokenError:
            pass
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Admin token required")


# ════════════════════════════════════════════════════════════════════
#  FastAPI app
# ════════════════════════════════════════════════════════════════════
app = FastAPI(
    title="ON NOW TV V2 Launcher API",
    description="Admin-driven config, wallpapers, APKs, and popup notifications for the OnNow TV V2 Android TV launcher.",
    version="0.1.0",
)

# CORS — admin UI fetches relative paths but the API may eventually be
# embedded in other dashboards.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Static asset routes
app.mount("/assets/icons",      StaticFiles(directory=str(DATA_DIR / "icons")),      name="icons")
app.mount("/assets/wallpapers", StaticFiles(directory=str(DATA_DIR / "wallpapers")), name="wallpapers")
app.mount("/assets/apks",       StaticFiles(directory=str(DATA_DIR / "apks")),       name="apks")

# Static admin UI
ADMIN_DIR = Path(__file__).parent / "admin"
if ADMIN_DIR.exists():
    app.mount("/admin/static", StaticFiles(directory=str(ADMIN_DIR / "static")), name="admin-static")


# ─────────────────────────────────────────────────────────────────
#  Public launcher endpoints (read-only, no auth)
# ─────────────────────────────────────────────────────────────────
def _abs(path: Optional[str]) -> Optional[str]:
    if not path:
        return None
    if path.startswith(("http://", "https://")):
        return path
    return f"{PUBLIC_BASE_URL}{path}"


def _active_wallpaper_url(store: dict) -> Optional[str]:
    active_id = store.get("active_wallpaper_id")
    if not active_id:
        return None
    for w in store.get("wallpapers", []):
        if w["id"] == active_id:
            return _abs(w["url"])
    return None


def _build_config(store: dict) -> LauncherConfig:
    nt = now_ts()
    return LauncherConfig(
        dock_tiles=[
            DockTile(
                key=t["key"], label=t["label"], sub=t["sub"],
                icon_url=_abs(t.get("icon_url")),
                target_package=t.get("target_package"),
                target_url=t.get("target_url"),
                accent=t.get("accent"),
            )
            for t in store["dock_tiles"]
        ],
        active_wallpaper_url=_active_wallpaper_url(store),
        apks=[ApkEntry(**a, apk_url=_abs(a["apk_url"])) if False else  # noqa: E501  (placeholder block, real conversion below)
              ApkEntry(
                  id=a["id"], name=a["name"], package_id=a.get("package_id"),
                  version_name=a.get("version_name"),
                  icon_url=_abs(a.get("icon_url")),
                  apk_url=_abs(a["apk_url"]) or a["apk_url"],
                  description=a.get("description"), added_at=a["added_at"],
              )
              for a in store.get("apks", [])],
        notifications=[
            Notification(**n) for n in store.get("notifications", [])
            if int(n.get("expires_at", 0)) > nt
        ],
        generation=int(store.get("generation", 0)),
        server_time=nt,
    )


@app.get("/api/launcher/health")
def health() -> dict:
    return {"ok": True, "ts": now_ts(), "version": app.version}


@app.get("/api/launcher/config", response_model=LauncherConfig)
def get_launcher_config() -> LauncherConfig:
    """Polled by every launcher device every few minutes.  Returns
    the full snapshot.  Clients can compare `generation` against
    their last-seen number and skip the rest of the body if equal."""
    return _build_config(_load_store())


@app.get("/api/launcher/notifications/pending")
def pending_notifications(device_id: str) -> dict:
    """Returns un-expired notifications this device hasn't seen yet."""
    store = _load_store()
    nt = now_ts()
    pending = []
    for n in store.get("notifications", []):
        if int(n.get("expires_at", 0)) <= nt:
            continue
        if device_id in n.get("seen_by", []):
            continue
        pending.append(n)
    return {"notifications": pending}


@app.post("/api/launcher/ack-notification")
def ack_notification(payload: dict) -> dict:
    """Device marks a notification as seen so it doesn't show again."""
    notif_id = payload.get("id")
    device_id = payload.get("device_id")
    if not notif_id or not device_id:
        raise HTTPException(400, "id + device_id required")
    store = _load_store()
    changed = False
    for n in store.get("notifications", []):
        if n["id"] == notif_id:
            seen = set(n.get("seen_by", []))
            if device_id not in seen:
                seen.add(device_id)
                n["seen_by"] = list(seen)
                changed = True
    if changed:
        _save_store(store)
    return {"ok": True}


# ─────────────────────────────────────────────────────────────────
#  Admin endpoints (token / cookie protected)
# ─────────────────────────────────────────────────────────────────
@app.post("/api/admin/login")
def admin_login(token: str = Form(...)) -> JSONResponse:
    if token != ADMIN_TOKEN:
        raise HTTPException(401, "Invalid token")
    jwt_token = jwt.encode(
        {"sub": "admin", "iat": now_ts()},
        JWT_SECRET, algorithm=JWT_ALG,
    )
    resp = JSONResponse({"ok": True})
    resp.set_cookie(
        "admin_session", jwt_token,
        httponly=True, samesite="strict",
        max_age=60 * 60 * 24 * 7,           # 7 days
    )
    return resp


@app.post("/api/admin/logout", dependencies=[Depends(require_admin)])
def admin_logout() -> JSONResponse:
    resp = JSONResponse({"ok": True})
    resp.delete_cookie("admin_session")
    return resp


@app.get("/api/admin/store", dependencies=[Depends(require_admin)])
def get_full_store() -> dict:
    return _load_store()


@app.post("/api/admin/dock", dependencies=[Depends(require_admin)])
def set_dock(dock_tiles: list[DockTile]) -> dict:
    if len(dock_tiles) != 6:
        raise HTTPException(400, "Exactly 6 dock tiles required")
    store = _load_store()
    store["dock_tiles"] = [t.model_dump() for t in dock_tiles]
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


# ── Wallpapers ───────────────────────────────────────────────────
@app.post("/api/admin/wallpapers", dependencies=[Depends(require_admin)])
async def upload_wallpaper(file: UploadFile = File(...)) -> dict:
    if not file.filename:
        raise HTTPException(400, "filename missing")
    ext = Path(file.filename).suffix.lower() or ".jpg"
    if ext not in {".jpg", ".jpeg", ".png", ".webp"}:
        raise HTTPException(400, f"unsupported wallpaper format: {ext}")
    wid = uuid.uuid4().hex[:12]
    target = DATA_DIR / "wallpapers" / f"{wid}{ext}"
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)
    store = _load_store()
    entry = {
        "id": wid,
        "filename": target.name,
        "url": f"/assets/wallpapers/{target.name}",
        "uploaded_at": now_ts(),
    }
    store.setdefault("wallpapers", []).append(entry)
    _save_store(store)
    return {"ok": True, "wallpaper": entry}


@app.post("/api/admin/wallpapers/active", dependencies=[Depends(require_admin)])
def set_active_wallpaper(payload: dict) -> dict:
    wallpaper_id = payload.get("id")    # null → revert to default aurora
    store = _load_store()
    if wallpaper_id is not None:
        if not any(w["id"] == wallpaper_id for w in store.get("wallpapers", [])):
            raise HTTPException(404, "unknown wallpaper id")
    store["active_wallpaper_id"] = wallpaper_id
    _save_store(store)
    return {"ok": True}


@app.delete("/api/admin/wallpapers/{wid}", dependencies=[Depends(require_admin)])
def delete_wallpaper(wid: str) -> dict:
    store = _load_store()
    kept = [w for w in store.get("wallpapers", []) if w["id"] != wid]
    removed = [w for w in store.get("wallpapers", []) if w["id"] == wid]
    for w in removed:
        try:
            (DATA_DIR / "wallpapers" / w["filename"]).unlink()
        except FileNotFoundError:
            pass
    store["wallpapers"] = kept
    if store.get("active_wallpaper_id") == wid:
        store["active_wallpaper_id"] = None
    _save_store(store)
    return {"ok": True, "removed": len(removed)}


# ── APKs ─────────────────────────────────────────────────────────
@app.post("/api/admin/apks", dependencies=[Depends(require_admin)])
async def add_apk_from_url(
    name: str = Form(...),
    apk_url: str = Form(...),
    package_id: Optional[str] = Form(None),
    version_name: Optional[str] = Form(None),
    icon_url: Optional[str] = Form(None),
    description: Optional[str] = Form(None),
) -> dict:
    """Add an APK by remote URL.  Launcher devices will download
    directly from `apk_url` when the user picks it from the dock."""
    aid = uuid.uuid4().hex[:12]
    entry = {
        "id": aid, "name": name,
        "package_id": package_id, "version_name": version_name,
        "icon_url": icon_url, "apk_url": apk_url,
        "description": description, "added_at": now_ts(),
    }
    store = _load_store()
    store.setdefault("apks", []).append(entry)
    _save_store(store)
    return {"ok": True, "apk": entry}


@app.post("/api/admin/apks/upload", dependencies=[Depends(require_admin)])
async def upload_apk(
    name: str = Form(...),
    file: UploadFile = File(...),
    package_id: Optional[str] = Form(None),
    version_name: Optional[str] = Form(None),
    icon_url: Optional[str] = Form(None),
    description: Optional[str] = Form(None),
) -> dict:
    """Add an APK by uploading the file directly to this backend.
    The launcher downloads from `/assets/apks/{filename}`."""
    if not file.filename:
        raise HTTPException(400, "filename missing")
    aid = uuid.uuid4().hex[:12]
    safe_name = f"{aid}_{Path(file.filename).name}"
    target = DATA_DIR / "apks" / safe_name
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)
    entry = {
        "id": aid, "name": name,
        "package_id": package_id, "version_name": version_name,
        "icon_url": icon_url,
        "apk_url": f"/assets/apks/{safe_name}",
        "description": description, "added_at": now_ts(),
    }
    store = _load_store()
    store.setdefault("apks", []).append(entry)
    _save_store(store)
    return {"ok": True, "apk": entry}


@app.delete("/api/admin/apks/{aid}", dependencies=[Depends(require_admin)])
def delete_apk(aid: str) -> dict:
    store = _load_store()
    target = next((a for a in store.get("apks", []) if a["id"] == aid), None)
    if target and target["apk_url"].startswith("/assets/apks/"):
        try:
            (DATA_DIR / "apks" / Path(target["apk_url"]).name).unlink()
        except FileNotFoundError:
            pass
    store["apks"] = [a for a in store.get("apks", []) if a["id"] != aid]
    _save_store(store)
    return {"ok": True}


# ── Notifications ─────────────────────────────────────────────────
@app.post("/api/admin/notify", dependencies=[Depends(require_admin)])
def push_notification(payload: dict) -> dict:
    title = payload.get("title", "").strip()
    body  = payload.get("body", "").strip()
    if not title or not body:
        raise HTTPException(400, "title + body required")
    image_url   = payload.get("image_url")
    ttl_seconds = int(payload.get("ttl_seconds", 3600))    # default 1 hour
    nid = uuid.uuid4().hex[:12]
    entry = {
        "id": nid, "title": title, "body": body,
        "image_url": image_url,
        "created_at": now_ts(),
        "expires_at": now_ts() + ttl_seconds,
        "seen_by": [],
    }
    store = _load_store()
    store.setdefault("notifications", []).append(entry)
    _save_store(store)
    return {"ok": True, "notification": entry}


@app.delete("/api/admin/notify/{nid}", dependencies=[Depends(require_admin)])
def delete_notification(nid: str) -> dict:
    store = _load_store()
    store["notifications"] = [n for n in store.get("notifications", []) if n["id"] != nid]
    _save_store(store)
    return {"ok": True}


# ─────────────────────────────────────────────────────────────────
#  Admin dashboard (single-page HTML)
# ─────────────────────────────────────────────────────────────────
@app.get("/admin", response_class=HTMLResponse)
def admin_index() -> HTMLResponse:
    """Serve the static admin dashboard."""
    html_path = ADMIN_DIR / "index.html"
    if not html_path.exists():
        return HTMLResponse("<h1>Admin UI not deployed</h1>", status_code=500)
    return HTMLResponse(html_path.read_text(encoding="utf-8"))


@app.get("/", response_class=HTMLResponse)
def root() -> HTMLResponse:
    return HTMLResponse(f"""
<!doctype html><html><head><title>OnNow TV V2 Launcher API</title>
<style>body{{font-family:system-ui;background:#04060B;color:#F4F7FB;padding:48px;max-width:720px;margin:0 auto;line-height:1.6;}}
a{{color:#2BB6FF;}}h1{{font-size:36px;margin-bottom:8px;}}code{{background:#0E1A2C;padding:2px 8px;border-radius:6px;}}</style>
</head><body>
<h1>OnNow TV V2 — Launcher API</h1>
<p>Status: <strong style="color:#2EEAC2;">running</strong> · v{app.version}</p>
<ul>
  <li><a href="/admin">Admin dashboard</a></li>
  <li><a href="/api/launcher/config">/api/launcher/config</a> — public read-only launcher config</li>
  <li><a href="/api/launcher/health">/api/launcher/health</a> — health check</li>
  <li><a href="/docs">/docs</a> — full OpenAPI reference</li>
</ul>
</body></html>""")
