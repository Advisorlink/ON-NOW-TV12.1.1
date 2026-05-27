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
import re
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
# v1.9 — Where extracted APK icons land (one PNG per APK).
(DATA_DIR / "apk_icons").mkdir(exist_ok=True)
# v2.0 — App Store hero image (single file, admin-uploadable).
(DATA_DIR / "appstore").mkdir(exist_ok=True)

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

# v2.8.8 — Per direct user requirement: with 500+ trusted clients,
# manually approving each new device registration is not practical.
# When AUTO_APPROVE_DEVICES is truthy (default), every brand-new
# device that hits /api/launcher/register lands as status="active"
# straight away — no admin click required.  Existing device records
# keep whatever status the admin has set (idempotent re-register).
# Set AUTO_APPROVE_DEVICES=0 in the env to revert to the legacy
# "pending until admin approves" gate.
_AUTO_APPROVE_RAW = os.environ.get("AUTO_APPROVE_DEVICES", "1").strip().lower()
AUTO_APPROVE_DEVICES: bool = _AUTO_APPROVE_RAW not in ("0", "false", "no", "off", "")


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
    # v0.9 — Featured-panel content shown OVER the wallpaper when this
    # tile is focused.  All optional — when blank, the
    # launcher just hides the panel and shows the wallpaper raw.
    heading: Optional[str]        = None  # big title (default Montserrat Bold)
    subheading: Optional[str]     = None  # mid-size accent line between heading + description
    description: Optional[str]    = None  # supporting text under heading
    cta_label: Optional[str]      = None  # button label (defaults to "ENTER")


class ApkEntry(BaseModel):
    id: str
    name: str
    package_id: Optional[str] = None       # for de-dupe / version check
    version_name: Optional[str] = None
    icon_url: Optional[str] = None
    apk_url: str                           # either /assets/apks/x.apk or remote https
    description: Optional[str] = None
    # v2.0 — User-facing category shown on the launcher App Store
    # tile (e.g. "Entertainment", "Music", "Games", "Movies & TV").
    # Optional; if unset, the tile just shows "Apps" as a default.
    category: Optional[str] = None
    added_at: int


class AppStoreMeta(BaseModel):
    """v2.0 — On-launcher App Store branding.  v2.8.10 added a second
    fullscreen background image that sits BEHIND the apps grid.
    v2.8.18 added admin-editable tile colors.  v2.8.20 adds:
      • logo_image_url        — drag-drop topbar logo (replaces the
        bundled text wordmark + play tile when set).
      • topbar_btn_bg_color   — top-bar pill background (resting).
      • topbar_btn_text_color — top-bar pill text + icon tint.
    Tile colors are stored as 8-char "#AARRGGBB" hex strings.  None
    falls back to the launcher's built-in defaults."""
    hero_image_url: Optional[str]         = None
    background_image_url: Optional[str]   = None
    logo_image_url: Optional[str]         = None
    tile_bg_color: Optional[str]          = None  # "#CC0F1B30" default
    tile_text_color: Optional[str]        = None  # "#FFF4F7FB" default
    topbar_btn_bg_color: Optional[str]    = None  # "#33203A5C" default
    topbar_btn_text_color: Optional[str]  = None  # "#FFF4F7FB" default


class Notification(BaseModel):
    id: str
    title: str
    body: str
    image_url: Optional[str] = None
    created_at: int
    expires_at: int                        # unix timestamp; clients ignore if past
    seen_by: list[str] = Field(default_factory=list)


class LayoutSettings(BaseModel):
    """v1.0 — Per-deployment layout customisation.  Lets the admin
    resize tiles, move the dock, shift the featured panel, and toggle
    the top bar — all from the admin dashboard without recompiling
    the launcher APK.  All values are in DP (text in SP)."""
    tile_width_dp: int           = 300
    tile_height_dp: int          = 168
    dock_margin_bottom_dp: int   = -16   # negative = bleed off bottom edge
    dock_margin_horizontal_dp: int = 20
    featured_margin_start_dp: int  = 48
    featured_margin_bottom_dp: int = 36
    topbar_visible: bool = True
    # v1.1 — Per-element typography controls (heading / subheading /
    # description / cta-button label).  Values are intentionally
    # plain strings so the admin UI can pass any new font/weight
    # without a schema migration.  The Android side maps unknown
    # values to safe defaults.
    featured_show_button: bool = True
    featured_align: str = "start"           # "start" | "center" | "end"

    featured_heading_size_sp: int       = 56
    featured_heading_font: str          = "montserrat"
    featured_heading_weight: str        = "bold"
    featured_heading_color: str         = "#FFFFFF"

    featured_subheading_size_sp: int    = 22
    featured_subheading_font: str       = "montserrat"
    featured_subheading_weight: str     = "semibold"
    featured_subheading_color: str      = "#F0F4FA"

    featured_description_size_sp: int   = 17
    featured_description_font: str      = "montserrat"
    featured_description_weight: str    = "regular"
    featured_description_color: str     = "#D8E2EF"

    featured_button_size_sp: int        = 13
    featured_button_font: str           = "montserrat"
    featured_button_weight: str         = "bold"
    featured_button_text_color: str     = "#04060B"
    # v1.5 — Vertical gaps between featured-panel elements (dp).
    # Negative values are allowed so the admin can pull elements
    # TOWARD each other when the natural font baselines leave too
    # much whitespace (e.g. tall display fonts).
    featured_gap_after_heading_dp: int      = 6
    featured_gap_after_subheading_dp: int   = 10
    featured_gap_after_description_dp: int  = 22
    # v1.5 — Letter spacing (em hundredths — 0 = none, 4 = +0.04em).
    featured_heading_letter_spacing: int    = -1   # -0.01em (tight)
    featured_subheading_letter_spacing: int = 2    # +0.02em (slightly open)
    featured_description_letter_spacing: int = 0
    featured_button_letter_spacing: int     = 18   # +0.18em (caps look)
    # v1.5 — Description line height multiplier (100 = 1.0).
    featured_description_line_height_pct: int = 140
    # v1.6 — Per-element show/hide toggles.  Lets the admin hide
    # entire blocks of the featured panel without having to clear
    # the per-tile content fields.
    featured_show_heading: bool     = True
    featured_show_subheading: bool  = True
    featured_show_description: bool = True
    # v1.6 — Heading-as-image.  When set, the launcher REPLACES the
    # heading text with this image (useful for brand logos).  The
    # image is rendered at the configured heading height so it
    # composites cleanly with the rest of the panel.
    featured_heading_image_url: Optional[str] = None
    featured_heading_image_height_dp: int     = 80
    # v1.8 — Group offset.  Lets the admin nudge the ENTIRE featured
    # panel (heading + subheading + description + CTA) as a single
    # block, horizontally or vertically, AFTER the per-element sizes
    # and gaps have settled.  Use this to nudge the whole block up
    # 30 dp or right 20 dp without disturbing the relative typography.
    # Negative values move the block left / up; positive values move
    # right / down.  Applied via View.translationX / translationY on
    # the featuredPanel — does NOT affect the underlying layout
    # measurement so adjacent elements stay put.
    featured_group_offset_x_dp: int = 0
    featured_group_offset_y_dp: int = 0


class RegisteredDevice(BaseModel):
    """v1.7 — Persistent registration record for a TV box.

    Lifecycle: launcher first boot → user enters name → POST
    /api/launcher/register creates this with status="pending".  Admin
    then approves (status="active") or blocks (status="blocked").
    Status surfaced back to the device via /api/launcher/activation.
    """
    id: str                              # uuid4 generated by the launcher
    name: str                            # human-typed nickname
    model: str                           # `${Build.MANUFACTURER} ${Build.MODEL}`
    status: str = "pending"              # "pending" | "active" | "blocked"
    registered_at: int                   # epoch ms
    last_seen_at: Optional[int] = None   # bumped on every activation poll
    last_ip: Optional[str] = None        # last IP we saw it from


class RegisterRequest(BaseModel):
    id: str
    name: str
    model: str


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
    # v1.0 — Admin-controlled layout overrides.  When omitted, the
    # launcher uses the platform defaults baked into the APK.
    layout: LayoutSettings = LayoutSettings()
    # v2.0 — App Store branding (hero image).  Always present so
    # older Android builds don't crash on JSON parse.
    appstore: AppStoreMeta = AppStoreMeta()


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
            store = json.loads(STORE_FILE.read_text())
        except Exception:
            log.exception("Corrupt store.json — re-seeding defaults")
            store = _default_store()
            STORE_FILE.write_text(json.dumps(store, indent=2))
            return store
        # v1.0 migration — ensure the layout block exists with defaults
        # so older stores keep working after the upgrade.
        if "layout" not in store or not isinstance(store["layout"], dict):
            store["layout"] = LayoutSettings().model_dump()
        else:
            # Top-up any newly-introduced fields with defaults without
            # clobbering admin-set values.
            defaults = LayoutSettings().model_dump()
            for k, v in defaults.items():
                store["layout"].setdefault(k, v)
        # v1.7 migration — registered device list (keyed by device id).
        if "registered_devices" not in store or not isinstance(store["registered_devices"], dict):
            store["registered_devices"] = {}
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
app.mount("/assets/apk_icons",   StaticFiles(directory=str(DATA_DIR / "apk_icons")),   name="apk-icons")
app.mount("/assets/appstore",    StaticFiles(directory=str(DATA_DIR / "appstore")),    name="appstore")

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
                heading=t.get("heading"),
                subheading=t.get("subheading"),
                description=t.get("description"),
                cta_label=t.get("cta_label"),
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
                category=a.get("category"),
            )
            for a in store.get("apks", [])
        ],
        notifications=[
            Notification(**n) for n in store.get("notifications", [])
            if int(n.get("expires_at", 0)) > nt
        ],
        generation=int(store.get("generation", 0)),
        server_time=nt,
        layout=LayoutSettings(**store.get("layout", {})),
        appstore=AppStoreMeta(
            hero_image_url=_abs(
                (store.get("appstore") or {}).get("hero_image_url")
            ),
            background_image_url=_abs(
                (store.get("appstore") or {}).get("background_image_url")
            ),
            logo_image_url=_abs(
                (store.get("appstore") or {}).get("logo_image_url")
            ),
            tile_bg_color=(store.get("appstore") or {}).get("tile_bg_color"),
            tile_text_color=(store.get("appstore") or {}).get("tile_text_color"),
            topbar_btn_bg_color=(store.get("appstore") or {}).get("topbar_btn_bg_color"),
            topbar_btn_text_color=(store.get("appstore") or {}).get("topbar_btn_text_color"),
        ),
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
#  Device registration + activation gate  (v1.7)
# ─────────────────────────────────────────────────────────────────
@app.post("/api/launcher/register")
def register_device(req: RegisterRequest, request: Request) -> dict:
    """Called by the launcher on first-time setup.  Creates a
    `pending` registration record if the device id doesn't exist
    yet, otherwise refreshes the existing record's metadata.

    No auth — every box can register itself.  Admin still has to
    approve before the box becomes usable."""
    name  = (req.name or "").strip()
    model = (req.model or "").strip() or "Unknown"
    if not req.id or not name:
        raise HTTPException(400, "id and name required")
    store = _load_store()
    devices = store.setdefault("registered_devices", {})
    existing = devices.get(req.id)
    now = now_ts()
    ip = request.client.host if request.client else None
    if existing:
        # Re-registration — update the metadata but DO NOT change
        # status (admin already decided).
        existing["name"] = name
        existing["model"] = model
        existing["last_seen_at"] = now
        existing["last_ip"] = ip
    else:
        # v2.8.8 — Brand-new device.  Default to "active" when
        # AUTO_APPROVE_DEVICES is on (which it is by default) so the
        # operator doesn't have to manually approve every box his
        # 500+ trusted clients install.  The admin can still BLOCK
        # any device at any time from the admin UI; a blocked
        # status is preserved on re-register.
        initial_status = "active" if AUTO_APPROVE_DEVICES else "pending"
        devices[req.id] = {
            "id": req.id,
            "name": name,
            "model": model,
            "status": initial_status,
            "registered_at": now,
            "last_seen_at": now,
            "last_ip": ip,
        }
    _save_store(store)
    return {"ok": True, "status": devices[req.id]["status"]}


@app.get("/api/launcher/activation")
def activation_status(device_id: str, request: Request) -> dict:
    """Polled by the launcher every ~10 s while in the blocked screen
    (and on every boot) so it can dismiss the popup the moment admin
    flips status to `active`.

    Returns status="unregistered" if the device id isn't known yet —
    that's the launcher's cue to show the registration form."""
    if not device_id:
        raise HTTPException(400, "device_id required")
    store = _load_store()
    devices = store.get("registered_devices", {}) or {}
    d = devices.get(device_id)
    if not d:
        return {"status": "unregistered"}
    # Touch last_seen_at + ip so admin can see live activity even on
    # blocked devices.
    now = now_ts()
    d["last_seen_at"] = now
    if request.client:
        d["last_ip"] = request.client.host
    _save_store_silent(store)
    return {
        "status": d.get("status", "pending"),
        "name":   d.get("name"),
        "model":  d.get("model"),
    }


@app.get("/api/admin/registered-devices", dependencies=[Depends(require_admin)])
def admin_list_registered_devices() -> dict:
    store = _load_store()
    devices = list((store.get("registered_devices", {}) or {}).values())
    # Sort newest registration first so the admin sees fresh ones at the top.
    devices.sort(key=lambda d: d.get("registered_at", 0), reverse=True)
    return {"devices": devices}


@app.post("/api/admin/registered-devices/{device_id}/status",
          dependencies=[Depends(require_admin)])
def admin_set_device_status(device_id: str, payload: dict) -> dict:
    new_status = (payload.get("status") or "").strip().lower()
    if new_status not in ("pending", "active", "blocked"):
        raise HTTPException(400, "status must be pending|active|blocked")
    store = _load_store()
    devices = store.setdefault("registered_devices", {})
    d = devices.get(device_id)
    if not d:
        raise HTTPException(404, "device not found")
    d["status"] = new_status
    _save_store(store)
    return {"ok": True, "status": new_status}


@app.delete("/api/admin/registered-devices/{device_id}",
            dependencies=[Depends(require_admin)])
def admin_delete_device(device_id: str) -> dict:
    store = _load_store()
    devices = store.setdefault("registered_devices", {})
    if device_id in devices:
        del devices[device_id]
        _save_store(store)
        return {"ok": True}
    raise HTTPException(404, "device not found")


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


@app.post("/api/admin/layout", dependencies=[Depends(require_admin)])
def set_layout(layout: LayoutSettings) -> dict:
    """v1.0 — Persist admin-edited Layout Editor values."""
    store = _load_store()
    store["layout"] = layout.model_dump()
    _save_store(store)
    return {"ok": True, "generation": store["generation"], "layout": store["layout"]}


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
        "heading": (p.get("heading") or "").strip() or None,
        "subheading": (p.get("subheading") or "").strip() or None,
        "description": (p.get("description") or "").strip() or None,
        "cta_label": (p.get("cta_label") or "").strip() or None,
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
    name: Optional[str] = Form(None),
    file: UploadFile = File(...),
    package_id: Optional[str] = Form(None),
    version_name: Optional[str] = Form(None),
    icon_url: Optional[str] = Form(None),
    description: Optional[str] = Form(None),
) -> dict:
    """Add an APK by uploading the file directly to this backend.
    The launcher downloads from `/assets/apks/{filename}`.

    v1.9 — Auto-introspects the APK with `pyaxmlparser`: package id,
    version name, app label and icon are extracted from the APK's
    own AndroidManifest + resource table.  Any form field the admin
    leaves blank is filled in from the APK; admin-supplied values
    always win (so they can rename "ON NOW TV V2" → "ON NOW TV"
    etc.).  The icon is saved as a 256-px PNG and served from
    /assets/apk_icons/{aid}.png.
    """
    if not file.filename:
        raise HTTPException(400, "filename missing")
    aid = uuid.uuid4().hex[:12]
    safe_name = f"{aid}_{Path(file.filename).name}"
    target = DATA_DIR / "apks" / safe_name
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)

    # Introspect the APK that just landed.  Runs in a thread pool —
    # pyaxmlparser is blocking I/O.
    from apk_meta import inspect_apk
    meta = await asyncio.to_thread(
        inspect_apk,
        target,
        DATA_DIR / "apk_icons",
        aid,
    )

    icon_url_final = icon_url
    if not icon_url_final and meta.get("icon_path"):
        icon_url_final = f"/assets/apk_icons/{aid}.png"

    entry = {
        "id": aid,
        # Admin-supplied name wins; fall back to APK label; final
        # fallback is the bare file stem so the row isn't blank.
        "name": (name or meta.get("app_name") or
                 Path(file.filename).stem),
        "package_id":   package_id   or meta.get("package_id"),
        "version_name": version_name or meta.get("version_name"),
        "icon_url":     icon_url_final,
        "apk_url":      f"/assets/apks/{safe_name}",
        "description":  description,
        "added_at":     now_ts(),
    }
    store = _load_store()
    store.setdefault("apks", []).append(entry)
    _save_store(store)
    return {"ok": True, "apk": entry, "auto_detected": meta}


@app.delete("/api/admin/apks/{aid}", dependencies=[Depends(require_admin)])
def delete_apk(aid: str) -> dict:
    store = _load_store()
    target = next((a for a in store.get("apks", []) if a["id"] == aid), None)
    if target and target["apk_url"].startswith("/assets/apks/"):
        try:
            (DATA_DIR / "apks" / Path(target["apk_url"]).name).unlink()
        except FileNotFoundError:
            pass
    # v1.9 — Also clean up the extracted icon file if we own it.
    if target:
        icon_url_str = target.get("icon_url") or ""
        if icon_url_str.startswith("/assets/apk_icons/"):
            try:
                (DATA_DIR / "apk_icons" / Path(icon_url_str).name).unlink()
            except FileNotFoundError:
                pass
    store["apks"] = [a for a in store.get("apks", []) if a["id"] != aid]
    _save_store(store)
    return {"ok": True}


# ── v1.9 — App Store drag/drop helpers ─────────────────────────────
@app.post("/api/admin/apks/inspect", dependencies=[Depends(require_admin)])
async def inspect_apk_endpoint(file: UploadFile = File(...)) -> dict:
    """Preview-only endpoint: drop an APK onto the admin to see what
    `pyaxmlparser` extracts BEFORE committing the upload.  Used by
    the App Store tab's drag-zone to populate the edit drawer's
    fields and show the detected icon — no `apks` entry is created.
    """
    if not file.filename:
        raise HTTPException(400, "filename missing")
    # Save to a temp location, inspect, delete.
    tmp_id = uuid.uuid4().hex[:12]
    tmp_path = DATA_DIR / "apks" / f"_inspect_{tmp_id}.apk"
    icon_dir = DATA_DIR / "apk_icons"
    async with aiofiles.open(tmp_path, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)
    try:
        from apk_meta import inspect_apk
        meta = await asyncio.to_thread(
            inspect_apk, tmp_path, icon_dir, f"_preview_{tmp_id}",
        )
        # Return a URL the admin UI can <img src=…> directly.
        icon_url = (
            f"/assets/apk_icons/_preview_{tmp_id}.png"
            if meta.get("icon_path") else None
        )
        return {
            "ok": True,
            "package_id":   meta.get("package_id"),
            "version_name": meta.get("version_name"),
            "version_code": meta.get("version_code"),
            "app_name":     meta.get("app_name"),
            "icon_url":     icon_url,
            "preview_token": tmp_id,
        }
    finally:
        # Keep the preview icon around so the admin UI can display it;
        # a 1-hour-old _preview_* file is fine garbage.
        try:
            tmp_path.unlink()
        except FileNotFoundError:
            pass


@app.patch("/api/admin/apks/{aid}", dependencies=[Depends(require_admin)])
def update_apk_meta(aid: str, payload: dict) -> dict:
    """Edit name / description / version / package id on an existing
    APK row.  Other fields (apk_url, icon_url, id) are immutable
    via this endpoint — use the upload + icon endpoints to change
    binaries."""
    store = _load_store()
    apks = store.get("apks", [])
    target = next((a for a in apks if a["id"] == aid), None)
    if not target:
        raise HTTPException(404, "apk not found")
    allowed = {"name", "description", "version_name", "package_id", "category"}
    for k, v in payload.items():
        if k in allowed:
            target[k] = v if (v is None or v != "") else None
    _save_store(store)
    return {"ok": True, "apk": target}


@app.post("/api/admin/apks/{aid}/icon", dependencies=[Depends(require_admin)])
async def upload_apk_icon(
    aid: str, file: UploadFile = File(...),
) -> dict:
    """Replace an APK's icon by drag-dropping a fresh PNG/JPEG.  The
    image is resized to 256×256 to match the icons we extract via
    `pyaxmlparser`, so everything renders consistently."""
    from PIL import Image
    import io
    store = _load_store()
    target = next((a for a in store.get("apks", []) if a["id"] == aid), None)
    if not target:
        raise HTTPException(404, "apk not found")
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "apk_icons" / f"{aid}.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        img.thumbnail((256, 256), Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    target["icon_url"] = f"/assets/apk_icons/{aid}.png"
    _save_store(store)
    return {"ok": True, "icon_url": target["icon_url"]}


# ── v2.0 — App Store hero image (single banner) ───────────────────
# v2.8.14 — Final on-screen rectangle on a 1080p TV:
#   • Hero banner       — 1920 × 280 px (EDGE-TO-EDGE, full screen width)
#   • Background image  — 1920 × 1080 px (fullscreen behind grid)
#
# Pipeline: ImageOps.contain() preserves the upload's aspect ratio
# without cropping, THEN ImageOps.pad() pads with transparent pixels
# to fill the exact 1920×280 target — so the on-device ImageView
# (FIT_XY) renders a pixel-perfect edge-to-edge banner regardless
# of the upload's aspect.  Upload at exactly 1920×280 for a fully
# saturated banner; upload at any other aspect for a centered
# letterbox/pillarbox effect against transparent padding.
APPSTORE_HERO_SIZE       = (1920, 280)
APPSTORE_BACKGROUND_SIZE = (1920, 1080)


@app.post("/api/admin/appstore/hero", dependencies=[Depends(require_admin)])
async def upload_appstore_hero(file: UploadFile = File(...)) -> dict:
    """Drag-drop a fresh hero banner for the launcher's App Store.
    Image is auto-fit (no crop) AND padded to exactly 1920×280 px
    so the launcher always renders a perfect edge-to-edge banner."""
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "appstore" / "hero.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        # 1) contain → scale to fit inside the target, no crop.
        img = ImageOps.contain(img, APPSTORE_HERO_SIZE, Image.LANCZOS)
        # 2) pad → wrap with the launcher's root background color
        #    (#04060B opaque) to reach the exact target dimensions.
        #    v2.8.15 — Changed from transparent (0,0,0,0) to solid
        #    dark per user feedback: when the upload's aspect didn't
        #    match 1920×280 exactly, the transparent padding let the
        #    launcher's old cyan placeholder gradient show through
        #    on the sides as a bright blue stripe.  Solid #04060B
        #    matches the launcher root so any letterboxing now
        #    blends invisibly into the rest of the screen.
        img = ImageOps.pad(
            img,
            APPSTORE_HERO_SIZE,
            method=Image.LANCZOS,
            color=(4, 6, 11, 255),
            centering=(0.5, 0.5),
        )
        img.save(out_path, format="PNG", optimize=True)
        final_size = img.size
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["hero_image_url"] = f"/assets/appstore/hero.png?ts={now_ts()}"
    _save_store(store)
    return {
        "ok": True,
        "hero_image_url": appstore["hero_image_url"],
        "rendered_size": list(APPSTORE_HERO_SIZE),
        "saved_size": list(final_size),
    }


@app.delete("/api/admin/appstore/hero", dependencies=[Depends(require_admin)])
def clear_appstore_hero() -> dict:
    """Remove the current hero so the launcher falls back to the
    bundled placeholder gradient."""
    out_path = DATA_DIR / "appstore" / "hero.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("appstore", {})["hero_image_url"] = None
    _save_store(store)
    return {"ok": True}


# ── v2.8.10 — App Store fullscreen background ─────────────────────
@app.post("/api/admin/appstore/background", dependencies=[Depends(require_admin)])
async def upload_appstore_background(file: UploadFile = File(...)) -> dict:
    """Drag-drop a fullscreen background that sits BEHIND the app
    tiles in the launcher's App Store.  Auto-fits to 1920×1080 via
    center-crop — admins can drop in any size image and it lands
    at the exact shape."""
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "appstore" / "background.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        img = ImageOps.fit(img, APPSTORE_BACKGROUND_SIZE, Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["background_image_url"] = f"/assets/appstore/background.png?ts={now_ts()}"
    _save_store(store)
    return {
        "ok": True,
        "background_image_url": appstore["background_image_url"],
        "rendered_size": list(APPSTORE_BACKGROUND_SIZE),
    }


@app.delete("/api/admin/appstore/background", dependencies=[Depends(require_admin)])
def clear_appstore_background() -> dict:
    """Remove the current App Store background image."""
    out_path = DATA_DIR / "appstore" / "background.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("appstore", {})["background_image_url"] = None
    _save_store(store)
    return {"ok": True}


# ── v2.8.18 — Admin-editable app-tile colors ──────────────────────
class TileColorsBody(BaseModel):
    tile_bg_color:   Optional[str] = None
    tile_text_color: Optional[str] = None


_HEX_RE = re.compile(r"^#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$")


def _normalize_color(c: Optional[str]) -> Optional[str]:
    """Accepts '#RRGGBB' or '#AARRGGBB' (Android's format).  Always
    returns the 8-char alpha-first form, opaque by default.  Empty /
    None passes through so the launcher falls back to its built-in
    defaults."""
    if not c:
        return None
    c = c.strip().upper()
    if not _HEX_RE.match(c):
        raise HTTPException(400, f"invalid color literal: {c}")
    return c if len(c) == 9 else f"#FF{c[1:]}"


@app.post("/api/admin/appstore/tile-colors", dependencies=[Depends(require_admin)])
def update_tile_colors(body: TileColorsBody) -> dict:
    """Set the App Store tile background + text colors.  Each field
    accepts None (= reset to default), '#RRGGBB' (opaque), or
    '#AARRGGBB' (with alpha).  Stored in `store.json → appstore`
    and surfaced via `/api/launcher/config` so every device picks
    up the change on the next 30s poll."""
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["tile_bg_color"]   = _normalize_color(body.tile_bg_color)
    appstore["tile_text_color"] = _normalize_color(body.tile_text_color)
    _save_store(store)
    return {
        "ok": True,
        "tile_bg_color":   appstore["tile_bg_color"],
        "tile_text_color": appstore["tile_text_color"],
    }


# ── v2.8.20 — Top-bar pill colors (VPN + Speed Test buttons) ──────
class TopbarColorsBody(BaseModel):
    topbar_btn_bg_color:   Optional[str] = None
    topbar_btn_text_color: Optional[str] = None


@app.post("/api/admin/appstore/topbar-colors", dependencies=[Depends(require_admin)])
def update_topbar_colors(body: TopbarColorsBody) -> dict:
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["topbar_btn_bg_color"]   = _normalize_color(body.topbar_btn_bg_color)
    appstore["topbar_btn_text_color"] = _normalize_color(body.topbar_btn_text_color)
    _save_store(store)
    return {
        "ok": True,
        "topbar_btn_bg_color":   appstore["topbar_btn_bg_color"],
        "topbar_btn_text_color": appstore["topbar_btn_text_color"],
    }


# ── v2.8.20 — Admin-uploadable top-bar logo image ─────────────────
# Final rendered size in the top bar: ~260 × 56 px (logo fits inside
# its own 56dp-tall row, max width ~260dp to leave room for clock).
# ImageOps.contain() preserves the upload's aspect — never zoomed,
# never cropped — so a designer can drop any size in.
APPSTORE_LOGO_SIZE = (520, 112)  # 2× the on-screen target for crispness


@app.post("/api/admin/appstore/logo", dependencies=[Depends(require_admin)])
async def upload_appstore_logo(file: UploadFile = File(...)) -> dict:
    """Drag-drop a topbar logo image.  Auto-fit to fit within
    520×112 preserving aspect (no crop, no stretch).  PNG with
    transparency strongly recommended."""
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "appstore" / "logo.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        img = ImageOps.contain(img, APPSTORE_LOGO_SIZE, Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
        saved = img.size
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["logo_image_url"] = f"/assets/appstore/logo.png?ts={now_ts()}"
    _save_store(store)
    return {
        "ok": True,
        "logo_image_url": appstore["logo_image_url"],
        "saved_size": list(saved),
    }


@app.delete("/api/admin/appstore/logo", dependencies=[Depends(require_admin)])
def clear_appstore_logo() -> dict:
    out_path = DATA_DIR / "appstore" / "logo.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("appstore", {})["logo_image_url"] = None
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
