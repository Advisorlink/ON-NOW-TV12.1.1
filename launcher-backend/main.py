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
(DATA_DIR / "tile_images").mkdir(exist_ok=True)
(DATA_DIR / "tile_apks").mkdir(exist_ok=True)
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
    # v0.2 — Per-tile imagery.  Each tile owns:
    #   • image_url     : JPEG card art shown ON the dock tile
    #   • wallpaper_url : JPEG fullscreen background shown BEHIND the
    #                     dock when this tile is the focused one
    # Both are absolute URLs returned to the device (or null = use the
    # platform default).  Admins upload them as JPEGs via
    # /api/admin/dock/{key}/image and /api/admin/dock/{key}/wallpaper.
    image_url: Optional[str]     = None
    wallpaper_url: Optional[str] = None
    # v0.3 — Per-tile APK.  When the user taps the tile, the launcher
    # first tries to launch `target_package` if installed; if not, and
    # `apk_url` is set, the launcher downloads + sideloads the APK
    # before launching.  `apk_package_id` and `apk_version` are
    # optional metadata the admin enters so the launcher can do
    # version-bump checks without parsing the APK itself.
    apk_url: Optional[str]        = None
    apk_filename: Optional[str]   = None   # original upload filename, for display
    apk_package_id: Optional[str] = None
    apk_version: Optional[str]    = None
    # Deprecated.  Older client builds (pre-v0.2) read `icon_url`; we
    # keep returning it (always null) so JSON parsing doesn't fail on
    # those clients.
    icon_url: Optional[str] = None
    target_package: Optional[str] = None  # Android package name to launch
    target_url: Optional[str]     = None  # http(s):// URL to open in browser
    accent: Optional[str]         = None  # "#RRGGBB" hex — accent for the section


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
    # Deprecated as of v0.2 — wallpapers are now per-tile.  Kept in
    # the schema (always null) so older Android builds don't crash on
    # JSON parse.
    active_wallpaper_url: Optional[str] = None
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
            {"key": "movies",   "label": "Movies & TV Shows", "sub": "Stream and enjoy",     "image_url": None, "wallpaper_url": None,
             "target_package": "tv.onnowtv.app", "target_url": None, "accent": "#38B8FF"},
            {"key": "music",    "label": "Music",             "sub": "Listen and enjoy",    "image_url": None, "wallpaper_url": None,
             "target_package": None,             "target_url": None, "accent": "#38B8FF"},
            {"key": "livetv",   "label": "Live TV",           "sub": "Watch live channels", "image_url": None, "wallpaper_url": None,
             "target_package": "tv.onnowtv.app", "target_url": None, "accent": "#2BB6FF"},
            {"key": "apps",     "label": "Apps",              "sub": "All your apps",       "image_url": None, "wallpaper_url": None,
             "target_package": None,             "target_url": None, "accent": "#2EEAC2"},
            {"key": "browser",  "label": "Browser",           "sub": "Surf the web",        "image_url": None, "wallpaper_url": None,
             "target_package": None,             "target_url": None, "accent": "#38C2FF"},
            {"key": "settings", "label": "Settings",          "sub": "System preferences",  "image_url": None, "wallpaper_url": None,
             "target_package": None,             "target_url": None, "accent": "#5BC5FF"},
        ],
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


def _save_store_silent(store: dict) -> None:
    """v0.4 — Persist the store without bumping `generation`.  Used by
    high-frequency endpoints (device heartbeat on every config poll)
    that just need to record state without triggering all devices to
    re-render."""
    with _lock:
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
app.mount("/assets/icons",       StaticFiles(directory=str(DATA_DIR / "icons")),       name="icons")
app.mount("/assets/wallpapers",  StaticFiles(directory=str(DATA_DIR / "wallpapers")),  name="wallpapers")
app.mount("/assets/tile_images", StaticFiles(directory=str(DATA_DIR / "tile_images")), name="tile-images")
app.mount("/assets/tile_apks",   StaticFiles(directory=str(DATA_DIR / "tile_apks")),   name="tile-apks")
app.mount("/assets/apks",        StaticFiles(directory=str(DATA_DIR / "apks")),        name="apks")

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


def _build_config(store: dict) -> LauncherConfig:
    nt = now_ts()
    return LauncherConfig(
        dock_tiles=[
            DockTile(
                key=t["key"], label=t["label"], sub=t["sub"],
                image_url=_abs(t.get("image_url")),
                wallpaper_url=_abs(t.get("wallpaper_url")),
                apk_url=_abs(t.get("apk_url")),
                apk_filename=t.get("apk_filename"),
                apk_package_id=t.get("apk_package_id"),
                apk_version=t.get("apk_version"),
                icon_url=None,  # deprecated — see DockTile docstring
                target_package=t.get("target_package"),
                target_url=t.get("target_url"),
                accent=t.get("accent"),
            )
            for t in store["dock_tiles"]
        ],
        active_wallpaper_url=None,   # deprecated as of v0.2 (per-tile wallpapers)
        apks=[
            ApkEntry(
                id=a["id"], name=a["name"], package_id=a.get("package_id"),
                version_name=a.get("version_name"),
                icon_url=_abs(a.get("icon_url")),
                apk_url=_abs(a["apk_url"]) or a["apk_url"],
                description=a.get("description"), added_at=a["added_at"],
            )
            for a in store.get("apks", [])
        ],
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
def get_launcher_config(device_id: Optional[str] = None) -> LauncherConfig:
    """Polled by every launcher device every few seconds.  Returns
    the full snapshot.  Clients can compare `generation` against
    their last-seen number and skip the rest of the body if equal.

    v0.4 — When the client sends `device_id`, we record the device's
    `last_seen` timestamp + the generation it just pulled.  Powers
    the admin UI's "Connected devices" panel."""
    store = _load_store()
    if device_id:
        devices = store.setdefault("devices", {})
        devices[device_id] = {
            "device_id": device_id,
            "last_seen": now_ts(),
            "last_generation": int(store.get("generation", 0)),
        }
        _save_store_silent(store)
    return _build_config(store)


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
    """v0.5 — Variable tile count (1..12).  Was fixed at exactly 6."""
    if not 1 <= len(dock_tiles) <= 12:
        raise HTTPException(400, "Between 1 and 12 dock tiles allowed")
    # Keys must be unique within the dock.
    keys = [t.key for t in dock_tiles]
    if len(set(keys)) != len(keys):
        raise HTTPException(400, "Tile keys must be unique")
    store = _load_store()
    # Preserve per-tile image_url / wallpaper_url / apk_url across this
    # save — those are managed by separate upload endpoints and the
    # dock text-fields form never reaches them.  Without this merge,
    # every form save would wipe the uploaded JPEGs / APKs.
    existing_assets: dict[str, dict] = {
        t["key"]: {
            "image_url": t.get("image_url"),
            "wallpaper_url": t.get("wallpaper_url"),
            "apk_url": t.get("apk_url"),
            "apk_filename": t.get("apk_filename"),
        }
        for t in store.get("dock_tiles", [])
    }
    new_dock = []
    for t in dock_tiles:
        d = t.model_dump()
        prev = existing_assets.get(d["key"], {})
        d["image_url"]    = prev.get("image_url")
        d["wallpaper_url"] = prev.get("wallpaper_url")
        d["apk_url"]      = prev.get("apk_url")
        d["apk_filename"] = prev.get("apk_filename")
        d.pop("icon_url", None)  # deprecated; never persist
        new_dock.append(d)
    store["dock_tiles"] = new_dock
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


# ── v0.5 — Add / Remove individual tiles ─────────────────────────
def _gen_unique_key(existing_keys: set[str], base: str = "tile") -> str:
    if base not in existing_keys:
        return base
    i = 2
    while f"{base}-{i}" in existing_keys:
        i += 1
    return f"{base}-{i}"


@app.post("/api/admin/dock/add", dependencies=[Depends(require_admin)])
def add_dock_tile(payload: dict | None = None) -> dict:
    """Append a fresh tile to the end of the dock.  Optional payload:
       { key, label, sub, target_package, target_url, accent }.
       Any field can be omitted — sensible defaults applied."""
    store = _load_store()
    tiles = store.get("dock_tiles", [])
    if len(tiles) >= 12:
        raise HTTPException(400, "Dock is full (max 12 tiles)")
    existing_keys = {t["key"] for t in tiles}
    p = payload or {}
    label = (p.get("label") or "New Tile").strip() or "New Tile"
    # Derive a stable key from the requested key or from the label.
    requested = (p.get("key") or label).strip().lower().replace(" ", "-")
    safe = "".join(c for c in requested if c.isalnum() or c in {"-", "_"}) or "tile"
    key = _gen_unique_key(existing_keys, safe)
    new_tile = {
        "key": key,
        "label": label,
        "sub": (p.get("sub") or "").strip(),
        "image_url": None,
        "wallpaper_url": None,
        "apk_url": None,
        "apk_filename": None,
        "apk_package_id": None,
        "apk_version": None,
        "target_package": (p.get("target_package") or "").strip() or None,
        "target_url": (p.get("target_url") or "").strip() or None,
        "accent": (p.get("accent") or "").strip() or None,
    }
    tiles.append(new_tile)
    store["dock_tiles"] = tiles
    _save_store(store)
    return {"ok": True, "tile": new_tile, "generation": store["generation"]}


@app.delete("/api/admin/dock/{key}", dependencies=[Depends(require_admin)])
def delete_dock_tile(key: str) -> dict:
    """Remove a single tile from the dock by key.  Also cleans up the
    tile's uploaded JPEG / wallpaper / APK files from disk."""
    store = _load_store()
    tiles = store.get("dock_tiles", [])
    if len(tiles) <= 1:
        raise HTTPException(400, "Dock must have at least 1 tile")
    tile = next((t for t in tiles if t["key"] == key), None)
    if tile is None:
        raise HTTPException(404, f"unknown tile key: {key}")
    # Delete any uploaded assets for this tile.
    _delete_tile_asset_file(tile.get("image_url"))
    _delete_tile_asset_file(tile.get("wallpaper_url"))
    _delete_tile_asset_file(tile.get("apk_url"))
    store["dock_tiles"] = [t for t in tiles if t["key"] != key]
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


# ── Dock — per-tile assets + reorder ─────────────────────────────
def _find_tile(store: dict, key: str) -> dict:
    for t in store.get("dock_tiles", []):
        if t["key"] == key:
            return t
    raise HTTPException(404, f"unknown tile key: {key}")


async def _save_tile_asset(file: UploadFile, kind: str, key: str) -> str:
    """Persist a tile image (kind='image') or wallpaper (kind='wallpaper')
    JPEG to disk and return the public `/assets/...` path."""
    if kind not in {"image", "wallpaper"}:
        raise HTTPException(400, "kind must be image or wallpaper")
    if not file.filename:
        raise HTTPException(400, "filename missing")
    ext = Path(file.filename).suffix.lower() or ".jpg"
    if ext not in {".jpg", ".jpeg", ".png", ".webp"}:
        raise HTTPException(400, f"unsupported image format: {ext}")
    folder_name = "tile_images" if kind == "image" else "wallpapers"
    # Tag the file with the tile key + a short random suffix so the new
    # upload doesn't shadow the old one in the browser's HTTP cache.
    fname = f"tile-{key}-{uuid.uuid4().hex[:8]}{ext}"
    target = DATA_DIR / folder_name / fname
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)
    return f"/assets/{folder_name}/{fname}"


def _delete_tile_asset_file(public_path: Optional[str]) -> None:
    if not public_path:
        return
    if not public_path.startswith("/assets/"):
        return
    rel = public_path.removeprefix("/assets/")
    file_path = DATA_DIR / rel
    try:
        file_path.unlink()
    except FileNotFoundError:
        pass


@app.post("/api/admin/dock/{key}/image", dependencies=[Depends(require_admin)])
async def upload_tile_image(key: str, file: UploadFile = File(...)) -> dict:
    """Upload the tile-art JPEG that shows ON the dock tile."""
    store = _load_store()
    tile = _find_tile(store, key)
    new_path = await _save_tile_asset(file, "image", key)
    # Drop the previous image file so we don't accumulate orphans.
    _delete_tile_asset_file(tile.get("image_url"))
    tile["image_url"] = new_path
    _save_store(store)
    return {"ok": True, "image_url": _abs(new_path), "generation": store["generation"]}


@app.delete("/api/admin/dock/{key}/image", dependencies=[Depends(require_admin)])
def clear_tile_image(key: str) -> dict:
    store = _load_store()
    tile = _find_tile(store, key)
    _delete_tile_asset_file(tile.get("image_url"))
    tile["image_url"] = None
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


@app.post("/api/admin/dock/{key}/wallpaper", dependencies=[Depends(require_admin)])
async def upload_tile_wallpaper(key: str, file: UploadFile = File(...)) -> dict:
    """Upload the fullscreen wallpaper JPEG shown behind the dock when
    this tile is the focused one."""
    store = _load_store()
    tile = _find_tile(store, key)
    new_path = await _save_tile_asset(file, "wallpaper", key)
    _delete_tile_asset_file(tile.get("wallpaper_url"))
    tile["wallpaper_url"] = new_path
    _save_store(store)
    return {"ok": True, "wallpaper_url": _abs(new_path), "generation": store["generation"]}


@app.delete("/api/admin/dock/{key}/wallpaper", dependencies=[Depends(require_admin)])
def clear_tile_wallpaper(key: str) -> dict:
    store = _load_store()
    tile = _find_tile(store, key)
    _delete_tile_asset_file(tile.get("wallpaper_url"))
    tile["wallpaper_url"] = None
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


# ── Per-tile APK ─────────────────────────────────────────────────
@app.post("/api/admin/dock/{key}/apk", dependencies=[Depends(require_admin)])
async def upload_tile_apk(
    key: str,
    file: UploadFile = File(...),
    apk_package_id: Optional[str] = Form(None),
    apk_version: Optional[str] = Form(None),
) -> dict:
    """Upload the APK that gets sideloaded when the user taps this
    tile and `target_package` is not yet installed on the device.
    Admin can optionally supply `apk_package_id` and `apk_version` so
    the launcher can do version-bump checks without parsing the APK
    itself."""
    store = _load_store()
    tile = _find_tile(store, key)
    if not file.filename:
        raise HTTPException(400, "filename missing")
    ext = Path(file.filename).suffix.lower() or ".apk"
    if ext != ".apk":
        raise HTTPException(400, f"unsupported APK format: {ext} (must be .apk)")
    safe_name = f"tile-{key}-{uuid.uuid4().hex[:8]}.apk"
    target = DATA_DIR / "tile_apks" / safe_name
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)
    # Drop the previous APK file so we don't accumulate orphans.
    _delete_tile_asset_file(tile.get("apk_url"))
    tile["apk_url"]      = f"/assets/tile_apks/{safe_name}"
    tile["apk_filename"] = file.filename
    if apk_package_id:
        tile["apk_package_id"] = apk_package_id.strip() or None
    if apk_version:
        tile["apk_version"] = apk_version.strip() or None
    _save_store(store)
    return {
        "ok": True,
        "apk_url": _abs(tile["apk_url"]),
        "apk_filename": tile["apk_filename"],
        "apk_package_id": tile.get("apk_package_id"),
        "apk_version": tile.get("apk_version"),
        "generation": store["generation"],
    }


@app.delete("/api/admin/dock/{key}/apk", dependencies=[Depends(require_admin)])
def clear_tile_apk(key: str) -> dict:
    store = _load_store()
    tile = _find_tile(store, key)
    _delete_tile_asset_file(tile.get("apk_url"))
    tile["apk_url"]        = None
    tile["apk_filename"]   = None
    tile["apk_package_id"] = None
    tile["apk_version"]    = None
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


@app.post("/api/admin/dock/{key}/apk-meta", dependencies=[Depends(require_admin)])
def set_tile_apk_meta(key: str, payload: dict) -> dict:
    """Update apk_package_id / apk_version on a tile that already has
    an APK uploaded.  Useful when the admin forgets to set them at
    upload time."""
    store = _load_store()
    tile = _find_tile(store, key)
    if "apk_package_id" in payload:
        v = (payload.get("apk_package_id") or "").strip()
        tile["apk_package_id"] = v or None
    if "apk_version" in payload:
        v = (payload.get("apk_version") or "").strip()
        tile["apk_version"] = v or None
    _save_store(store)
    return {"ok": True, "generation": store["generation"]}


@app.post("/api/admin/dock/reorder", dependencies=[Depends(require_admin)])
def reorder_dock(payload: dict) -> dict:
    """Reorder dock tiles.  Payload: { order: [key1, key2, ...] } — must
    contain exactly the set of existing keys, no additions or removals."""
    order = payload.get("order")
    if not isinstance(order, list):
        raise HTTPException(400, "order must be a list of tile keys")
    store = _load_store()
    existing = store.get("dock_tiles", [])
    if sorted(order) != sorted(t["key"] for t in existing):
        raise HTTPException(400, "order must list every existing tile key exactly once")
    by_key = {t["key"]: t for t in existing}
    store["dock_tiles"] = [by_key[k] for k in order]
    _save_store(store)
    return {"ok": True, "generation": store["generation"], "order": order}


# ── Device heartbeats / force-republish ──────────────────────────
@app.get("/api/admin/devices", dependencies=[Depends(require_admin)])
def list_devices() -> dict:
    """v0.4 — Return the list of devices that have polled the config
    endpoint, with their last-seen timestamp + the generation number
    they last received.  Powers the admin UI's "Connected devices"
    panel so admins can verify their changes actually landed."""
    store = _load_store()
    current_gen = int(store.get("generation", 0))
    devices_dict = store.get("devices", {})
    nt = now_ts()
    devices = []
    for d in devices_dict.values():
        last_seen = int(d.get("last_seen", 0))
        last_gen = int(d.get("last_generation", 0))
        age_seconds = nt - last_seen
        devices.append({
            "device_id": d.get("device_id"),
            "last_seen": last_seen,
            "last_seen_age_seconds": age_seconds,
            "last_generation": last_gen,
            "current_generation": current_gen,
            "in_sync": last_gen >= current_gen,
            "online": age_seconds <= 90,  # device polls every 30s; allow 3 missed beats
        })
    # Sort: online first, then most recent.
    devices.sort(key=lambda d: (not d["online"], -d["last_seen"]))
    return {
        "devices": devices,
        "current_generation": current_gen,
        "server_time": nt,
    }


@app.post("/api/admin/republish", dependencies=[Depends(require_admin)])
def force_republish() -> dict:
    """v0.4 — Force every device to re-pull the latest config on its
    next poll by bumping the generation number.  Used when the admin
    has uploaded fresh assets and wants to confirm they reach the
    box.  No content changes — just the generation bump."""
    store = _load_store()
    _save_store(store)  # _save_store bumps generation automatically
    return {
        "ok": True,
        "generation": store["generation"],
        "message": (
            "Config republished.  Connected devices will pick it up within "
            "30 seconds on their next poll."
        ),
    }



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
