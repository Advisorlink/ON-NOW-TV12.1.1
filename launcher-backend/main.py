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

# v2.8.25 — Load /app/launcher-backend/.env so secrets like
# EMERGENT_LLM_KEY (used by the V2 AI voice assistant) are picked up
# without having to bake them into the supervisor / Docker env.
try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).resolve().parent / ".env")
except Exception:
    pass

import aiofiles
import jwt
from fastapi import (
    Body,
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
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
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
# v2.8.25 — V2 AI screen background image lives here.
(DATA_DIR / "v2ai").mkdir(exist_ok=True)
# v2.8.50 — Featured-panel heading image lives here (drag-drop
# upload from the admin Layout Editor).  Filename "heading.png".
(DATA_DIR / "layout").mkdir(exist_ok=True)

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
    # v2.10.81 — Stamped fresh (UUIDv4 hex) on every APK upload.  The
    # launcher persists the build-id of the LAST install it ran, then
    # compares it to this field — when they differ, the tile shows the
    # UPDATE pill regardless of whether `apk_version` changed.  This
    # is the fix for the operator complaint that re-uploading an APK
    # with the same versionName silently did NOT light up the pill
    # (Android refuses downgrades but admins still want a re-push to
    # be visible to every box on a same-version rebuild).
    apk_build_id: Optional[str]   = None
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
    """v2.0 — On-launcher App Store branding.  v2.8.10+ adds image
    + color customisation; v2.8.23 adds speed-test target.  Tile
    colors are stored as 8-char "#AARRGGBB" hex strings."""
    hero_image_url: Optional[str]                = None
    background_image_url: Optional[str]          = None
    logo_image_url: Optional[str]                = None
    tile_bg_color: Optional[str]                 = None
    tile_text_color: Optional[str]               = None
    topbar_btn_bg_color: Optional[str]           = None
    topbar_btn_text_color: Optional[str]         = None
    topbar_btn_focus_bg_color: Optional[str]     = None
    topbar_btn_focus_text_color: Optional[str]   = None
    speed_test_package: Optional[str]            = None


# v2.8.25 — V2 AI screen customisation.  Admin can swap the heading
# text shown above the waveform and upload a fullscreen background
# image for the voice-assistant activity.
# v2.8.26 — Adds waveform style selector + optional V2 AI button
# icon image (replaces the top-bar lightning bolt SVG).
# v2.8.30 — Adds in-activity hold-button image + visibility toggle.
class V2AIConfig(BaseModel):
    heading_text: Optional[str] = None         # default in-app fallback if null
    background_image_url: Optional[str] = None # 1920×1080 fullscreen
    waveform_style: Optional[str] = None       # "bars" (default), "dots", "ring", "sweep", "pulse"
    button_image_url: Optional[str] = None     # square icon replacing the topbar lightning bolt
    hold_button_image_url: Optional[str] = None  # image painted on the in-activity Hold button
    hold_button_visible: bool = True             # show / hide the in-activity Hold button entirely
    # v2.8.38 — Per-V2-AI top-bar pill color overrides.  Each can
    # be null to fall back to the shared top-bar colors set in the
    # App Store tab.  When SET, only the V2 AI pill picks them up.
    button_bg_color: Optional[str] = None
    button_text_color: Optional[str] = None
    button_focus_bg_color: Optional[str] = None
    button_focus_text_color: Optional[str] = None
    # v2.8.49 — V2 AI "hero" pill sizing.  Height is always applied;
    # width is only applied when >0 (otherwise pill wraps its text).
    # When the admin uploads a custom image, set `button_width_dp`
    # to give the image a proper canvas (e.g. 200 dp wide → 64 dp
    # tall image, plenty of space for a wordmark).
    button_height_dp: int = 64
    button_width_dp: int  = 0


# v2.8.24 — QR-coded sharing videos.  Admin pastes a Google Drive /
# Dropbox / HTTP video link, backend generates a QR PNG anyone can
# scan with their phone to open a mobile-friendly inline player page.
# Each entry can be hidden from the launcher home or visible as a
# tile.  The QR encodes /qr-play/<id> (NOT the raw video URL) so
# the admin can rotate / fix the underlying Google Drive / Dropbox
# link without reprinting the QR code.
class QrVideo(BaseModel):
    id: str
    name: str
    url: str          # the actual video URL the player page loads
    caption: Optional[str] = None  # shown under the QR on the TV
    visible: bool = True
    qr_image_url: Optional[str] = None  # absolute URL to the PNG
    player_url: Optional[str] = None    # absolute URL the QR encodes
    created_at: int = 0


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
    # v1.6 — Heading-as-image.  When set, the launcher renders this
    # image alongside (or in place of) the text heading.  The
    # image is sized at `featured_heading_image_height_dp` and
    # positioned by `featured_heading_image_placement`:
    #   "above"   → image renders ABOVE the text heading (default)
    #   "below"   → image renders BELOW the text heading
    #   "replace" → image replaces the text heading (legacy)
    # Fine position tweaks: `featured_heading_image_offset_x_dp`
    # and `_y_dp` translate the ImageView without disturbing the
    # surrounding layout — handy for nudging a logo a few pixels.
    #
    # v2.8.58 — Default flipped from "replace" → "above" so that
    # uploading an image doesn't silently hide the heading TEXT.
    # The admin can still pick "replace" explicitly for legacy
    # logo-only behaviour.
    featured_heading_image_url: Optional[str] = None
    featured_heading_image_height_dp: int     = 80
    featured_heading_image_placement: str     = "above"
    featured_heading_image_offset_x_dp: int   = 0
    featured_heading_image_offset_y_dp: int   = 0
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
    # v2.8.25 — V2 AI screen customisation (heading text + bg image).
    v2ai: V2AIConfig = V2AIConfig()
    # v2.8.24 — Admin-curated QR-coded sharing videos.  Devices
    # that ignore the field still work — older Launcher builds
    # never deserialize it.
    qr_videos: list[QrVideo] = []


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
        # v2.8.24 migration — QR videos: ensure each entry has a
        # `player_url` and `caption` field, and that the on-disk QR PNG
        # encodes the player URL (not the raw video URL).  Legacy
        # entries from the pre-caption build are upgraded in-place.
        qr_dirty = False
        for v in store.get("qr_videos", []):
            qid = v.get("id")
            if not qid:
                continue
            v.setdefault("caption", None)
            expected_player = f"{PUBLIC_BASE_URL}/qr-play/{qid}"
            if v.get("player_url") != expected_player:
                v["player_url"] = expected_player
                try:
                    _generate_qr_png(DATA_DIR / "qr" / f"{qid}.png", expected_player)
                    v["qr_image_url"] = f"/assets/qr/{qid}.png?ts={now_ts()}"
                except Exception:
                    log.exception("Failed to regenerate QR PNG for %s", qid)
                qr_dirty = True
        if qr_dirty:
            # Persist without bumping `generation` — pure migration.
            tmp = STORE_FILE.with_suffix(".tmp")
            tmp.write_text(json.dumps(store, indent=2))
            tmp.replace(STORE_FILE)
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
    """v2.8.126 — Auth temporarily disabled per operator request.
    The launcher is single-user for now; we'll re-enable token /
    cookie gating before multi-tenant rollout."""
    return


# ════════════════════════════════════════════════════════════════════
#  FastAPI app
# ════════════════════════════════════════════════════════════════════
app = FastAPI(
    title="ON NOW TV V2 Launcher API",
    description="Admin-driven config, wallpapers, APKs, and popup notifications for the OnNow TV V2 Android TV launcher.",
    version="0.1.0",
    # v2.8.40 — Behind nginx at https://<host>/launcher when ROOT_PATH
    # is set in prod.  Empty default keeps local dev unaffected.
    root_path=os.environ.get("ROOT_PATH", ""),
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
# v2.8.25 — V2 AI screen background image asset.
app.mount("/assets/v2ai",        StaticFiles(directory=str(DATA_DIR / "v2ai")),        name="v2ai")
# v2.8.50 — Featured-panel heading image (drag-and-drop upload).
app.mount("/assets/layout",      StaticFiles(directory=str(DATA_DIR / "layout")),      name="layout")
# v2.8.24 — QR code PNGs for admin-uploaded sharing videos.
(DATA_DIR / "qr").mkdir(parents=True, exist_ok=True)
app.mount("/assets/qr",          StaticFiles(directory=str(DATA_DIR / "qr")),          name="qr")

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
                apk_build_id=t.get("apk_build_id"),
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
        # v2.8.50 — Heading image is now drag-drop-uploaded to
        # `/assets/layout/heading.png` (relative path stored in
        # store.json).  Absolutise it before serving so the
        # launcher can fetch from the correct host.
        layout=(lambda L: L.model_copy(update={
            "featured_heading_image_url": _abs(L.featured_heading_image_url),
        }))(LayoutSettings(**store.get("layout", {}))),
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
            topbar_btn_focus_bg_color=(store.get("appstore") or {}).get("topbar_btn_focus_bg_color"),
            topbar_btn_focus_text_color=(store.get("appstore") or {}).get("topbar_btn_focus_text_color"),
            speed_test_package=(store.get("appstore") or {}).get("speed_test_package"),
        ),
        v2ai=V2AIConfig(
            heading_text=(store.get("v2ai") or {}).get("heading_text"),
            background_image_url=_abs(
                (store.get("v2ai") or {}).get("background_image_url")
            ),
            waveform_style=(store.get("v2ai") or {}).get("waveform_style"),
            button_image_url=_abs(
                (store.get("v2ai") or {}).get("button_image_url")
            ),
            hold_button_image_url=_abs(
                (store.get("v2ai") or {}).get("hold_button_image_url")
            ),
            hold_button_visible=bool(
                (store.get("v2ai") or {}).get("hold_button_visible", True)
            ),
            button_bg_color=(store.get("v2ai") or {}).get("button_bg_color"),
            button_text_color=(store.get("v2ai") or {}).get("button_text_color"),
            button_focus_bg_color=(store.get("v2ai") or {}).get("button_focus_bg_color"),
            button_focus_text_color=(store.get("v2ai") or {}).get("button_focus_text_color"),
            button_height_dp=int((store.get("v2ai") or {}).get("button_height_dp", 64)),
            button_width_dp=int((store.get("v2ai") or {}).get("button_width_dp", 0)),
        ),
        qr_videos=[
            QrVideo(
                id=v["id"], name=v["name"], url=v["url"],
                caption=v.get("caption"),
                visible=bool(v.get("visible", True)),
                qr_image_url=_abs(v.get("qr_image_url")),
                player_url=_abs(v.get("player_url")) or f"{PUBLIC_BASE_URL}/qr-play/{v['id']}",
                created_at=int(v.get("created_at") or 0),
            )
            for v in store.get("qr_videos", [])
            if v.get("visible", True)
        ],
    )


@app.get("/api/launcher/health")
def health() -> dict:
    return {"ok": True, "ts": now_ts(), "version": app.version}


# ════════════════════════════════════════════════════════════════════
#  V2 AI — voice assistant pipeline
# ════════════════════════════════════════════════════════════════════
# Pipeline:
#   1. Launcher records audio (push-and-hold), POSTs the m4a/wav file.
#   2. We transcribe via OpenAI Whisper (whisper-1).
#   3. We parse the transcript with GPT-5.4 into a strict JSON intent.
#   4. Launcher acts on the intent — either deep-links into Vesper for
#      auto-play, or shows the recommendation list in-screen.
#
# IMPORTANT scope restriction: GPT is instructed to REJECT anything
# that isn't about movies / TV shows / installed apps.  Troubleshooting,
# weather, settings, etc. all return `intent: "reject"`.

import json as _json

V2AI_SYSTEM_PROMPT = """You are V2 AI — the entertainment voice assistant for the \
ON NOW TV V2 streaming launcher.

✅ YOU ANSWER any question that's about MOVIES, TV SHOWS, ACTORS, \
DIRECTORS, WRITERS, EPISODES, FILM INDUSTRY, BOX OFFICE, AWARDS, \
ENTERTAINMENT NEWS, CELEBRITY FACTS (net worth, age, spouse, \
filmography, awards, deaths, marriages, gossip), STREAMING SERVICES, \
APPS installed on the box, OR LAUNCHER FEATURES.  Be generous and \
friendly — if it's adjacent to film / TV / entertainment, just \
answer it.

🚫 YOU REJECT only:
- Device / box troubleshooting ("Wi-Fi slow", "remote not working", "screen frozen", "audio cutting out", "won't turn on", "won't update", "buffering", "lagging", "no signal", "how do I change settings on the box")
- General how-to about the physical hardware
- Truly off-topic stuff (weather, recipes, math, programming, translation, sports scores, politics, your own AI nature, harmful content)
For those: intent="reject", reject_reason="V2 AI only helps with movies, TV, and entertainment — not device troubleshooting.", speech_reply="I only help with movies and shows."

✅ Reply with STRICT JSON (no markdown, no comments) matching this schema:
{
  "intent":        "play_movie" | "play_series" | "recommend" | "search" | "trending" | "open_app" | "qa" | "person_info" | "reject",
  "title":         string | null,         // for play_movie / play_series — proper title-case
  "query":         string | null,         // for recommend / search
  "mood":          string | null,         // for recommend ("funny", "scary", "feel-good", "tonight", …)
  "trending_kind": "movie" | "series" | "all" | null,   // for trending — what TMDB list to pull
  "app_name":      string | null,         // for open_app
  "question":      string | null,         // for qa — the user's literal question
  "answer":        string | null,         // for qa — 1-4 sentence factual answer
  "answer_subject": string | null,        // for qa — main show/movie/PERSON the answer is about (helps poster lookup)
  "answer_subject_type": "movie" | "series" | "person" | null,   // hint for TMDB lookup
  "person_name":   string | null,         // for person_info — full name in proper case
  "person_bio":    string | null,         // for person_info — 1-4 sentence biography
  "known_for":     [                      // for person_info — 3-8 titles (used for TMDB poster lookup)
    { "title": string, "year": number | null, "type": "movie" | "series" }
  ] | null,
  "reject_reason": string | null,
  "speech_reply":  string,                // ALWAYS present — short TTS-friendly sentence
  "recommendations": [                    // for recommend / search (10-20 entries)
    { "title": string, "year": number | null, "type": "movie" | "series", "why": string }
  ]
}

Rules with examples:
- "Play The Matrix" → play_movie, title="The Matrix".
- "Watch Breaking Bad" → play_series, title="Breaking Bad".
- "Open Netflix" → open_app, app_name="Netflix".
- "Recommend something funny" / "What should I watch tonight" / "I'm bored find me something" → recommend with 10-20 titles + 1-line "why" each.  Use mood="tonight" for "tonight"/"now" type queries.
- "What's trending right now?" / "What movies are popular this week?" / "What are the top 10 shows?" → trending, trending_kind="movie" or "series" or "all".  Leave recommendations EMPTY — the backend fills it from TMDB.  speech_reply="Here's what's hot right now.".
- "What episode of Breaking Bad does Walter meet Gus?" → qa, answer="Season 2 Episode 11 'Mandala'.", answer_subject="Breaking Bad", answer_subject_type="series".
- "What's Vin Diesel's net worth?" → qa, answer="Vin Diesel's estimated net worth is around $225 million as of 2024, mainly from the Fast & Furious franchise.", answer_subject="Vin Diesel", answer_subject_type="person".  Be CONFIDENT with rounded ballpark figures — better than refusing.
- "How old is Tom Cruise?" → qa, answer=<his current age>, answer_subject="Tom Cruise", answer_subject_type="person".
- "Did Heath Ledger win an Oscar?" → qa, answer="Yes — Best Supporting Actor for The Dark Knight (2008), posthumously.", answer_subject="The Dark Knight", answer_subject_type="movie".
- "What's the highest grossing movie of all time?" → qa, answer="Avatar (2009) at ~$2.92 billion globally.", answer_subject="Avatar", answer_subject_type="movie".
- "Who's the main actor in Inception?" → person_info, person_name="Leonardo DiCaprio".
- "Who directed Pulp Fiction?" → person_info, person_name="Quentin Tarantino".
- "Tell me about Stranger Things" → qa with overview, answer_subject="Stranger Things", answer_subject_type="series".
- "Why is my Wi-Fi slow?" / "Remote not working" / "Box keeps freezing" → reject.

For "qa": 1-4 sentences max.  Factual, confident, friendly — never refuse a celebrity / film question just because it's "personal info"; entertainment fact-files are public knowledge.  Always set answer_subject + answer_subject_type so the UI can fetch the poster or actor photo.

For "person_info": person_bio MUST be a real 1-3 sentence biography focused on their acting / directing career.  known_for MUST contain 3-8 of their most famous works.

For "trending": leave `recommendations` empty; the backend fills the list from TMDB's official trending feed.  Just set intent + trending_kind + speech_reply.

Always title-case proper nouns ("Breaking Bad", "Heath Ledger", not "breaking bad").  Critical."""


# ── v2.8.29 — TMDB metadata lookup for V2 AI ───────────────────────
# Used to enrich recommendation + QA responses with poster art,
# rating, and synopsis so the launcher can render a beautiful card
# layout instead of plain text.
TMDB_BEARER_TOKEN = os.environ.get("TMDB_BEARER_TOKEN", "")
TMDB_BASE = "https://api.themoviedb.org/3"
TMDB_IMG  = "https://image.tmdb.org/t/p/w342"


async def _tmdb_person_lookup(name: str) -> Optional[dict]:
    """Look up an actor / director / writer on TMDB.  Returns
    {name, profile_url, known_for, biography} or None."""
    if not TMDB_BEARER_TOKEN or not name.strip():
        return None
    import httpx
    headers = {
        "Authorization": f"Bearer {TMDB_BEARER_TOKEN}",
        "accept": "application/json",
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                f"{TMDB_BASE}/search/person",
                params={"query": name, "include_adult": "false"},
                headers=headers,
            )
            if resp.status_code != 200:
                return None
            results = (resp.json() or {}).get("results") or []
            if not results:
                return None
            top = results[0]
            profile = top.get("profile_path")
            # Fetch their biography (only available on the detail endpoint).
            bio = ""
            try:
                d = await client.get(
                    f"{TMDB_BASE}/person/{top['id']}",
                    headers=headers,
                )
                if d.status_code == 200:
                    bio = (d.json() or {}).get("biography") or ""
            except Exception:  # noqa: BLE001
                pass
            return {
                "name":        top.get("name") or name,
                "profile_url": f"https://image.tmdb.org/t/p/w342{profile}" if profile else None,
                "biography":   bio.strip()[:600],   # cap at ~600 chars
                "known_for":   top.get("known_for") or [],
            }
    except Exception:  # noqa: BLE001
        log.exception("TMDB person lookup failed for %r", name)
        return None


async def _enrich_person_info(parsed: dict) -> None:
    """Mutate `parsed` in place — pull profile + bio from TMDB.

    The model may have already supplied a `person_bio` + `known_for`
    list; we OVERWRITE the bio with TMDB's canonical text (much more
    authoritative) but keep the model's `known_for` titles if they
    came back, then enrich each known_for entry with poster art."""
    name = (parsed.get("person_name") or "").strip()
    if not name:
        return
    tm = await _tmdb_person_lookup(name)
    if not tm:
        return
    parsed["person_profile_url"] = tm.get("profile_url")
    # Prefer TMDB's biography (more accurate) but fall back to the
    # model's if TMDB returned empty.
    if tm.get("biography"):
        parsed["person_bio"] = tm["biography"]
    # Enrich the model's known_for list with poster art + ratings.
    model_known = parsed.get("known_for") or []
    if not isinstance(model_known, list) or not model_known:
        # No known_for from the model — fall back to TMDB's list.
        model_known = [
            {
                "title": (k.get("title") or k.get("name") or ""),
                "year":  ((k.get("release_date") or k.get("first_air_date") or "")[:4] or None),
                "type":  ("series" if k.get("media_type") == "tv" else "movie"),
            }
            for k in (tm.get("known_for") or [])
            if (k.get("title") or k.get("name"))
        ][:6]
    parsed["known_for"] = await _enrich_recommendations(model_known)


async def _tmdb_trending(kind: str = "all", limit: int = 20) -> list[dict]:
    """v2.8.36 — Pull TMDB's official trending list.

    `kind` ∈ {"movie", "series" (mapped to "tv"), "all"}.
    Returns a list shaped like `_enrich_recommendations` output —
    {title, year, type, poster_url, backdrop_url, rating, overview}.
    """
    if not TMDB_BEARER_TOKEN:
        return []
    import httpx
    endpoint = {
        "movie":  "trending/movie/week",
        "series": "trending/tv/week",
        "tv":     "trending/tv/week",
        "all":    "trending/all/week",
    }.get(kind.lower(), "trending/all/week")
    headers = {
        "Authorization": f"Bearer {TMDB_BEARER_TOKEN}",
        "accept": "application/json",
    }
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(f"{TMDB_BASE}/{endpoint}", headers=headers)
            if resp.status_code != 200:
                return []
            results = (resp.json() or {}).get("results") or []
    except Exception:  # noqa: BLE001
        log.exception("TMDB trending lookup failed for %r", kind)
        return []
    out = []
    for r in results[:limit]:
        media = r.get("media_type") or ("movie" if kind == "movie" else "tv")
        ret_kind = "series" if media == "tv" else "movie"
        date_key = "release_date" if media == "movie" else "first_air_date"
        year_str = (r.get(date_key) or "")[:4]
        out.append({
            "title":        r.get("title") or r.get("name") or "?",
            "year":         int(year_str) if year_str.isdigit() else None,
            "type":         ret_kind,
            "poster_url":   f"{TMDB_IMG}{r['poster_path']}" if r.get("poster_path") else None,
            "backdrop_url": f"https://image.tmdb.org/t/p/w780{r['backdrop_path']}" if r.get("backdrop_path") else None,
            "rating":       round(float(r.get("vote_average") or 0), 1),
            "overview":     (r.get("overview") or "").strip(),
            "why":          "Trending this week",
        })
    return out


async def _tmdb_lookup(
    title: str,
    kind: Optional[str] = None,
    year: Optional[int] = None,
) -> Optional[dict]:
    """Look up `title` on TMDB.  Returns the first hit with
    {title, year, type, poster_url, rating, overview} or None.

    `kind` ∈ {"movie", "series", None} — narrows the search.  Uses
    a 10 s timeout per call.  Failures swallowed quietly so a
    flaky TMDB call never crashes V2 AI."""
    if not TMDB_BEARER_TOKEN or not title.strip():
        return None
    import httpx
    headers = {
        "Authorization": f"Bearer {TMDB_BEARER_TOKEN}",
        "accept": "application/json",
    }
    paths = []
    if kind == "movie":
        paths.append(("movie", "movie"))
    elif kind in ("series", "tv"):
        paths.append(("tv", "series"))
    else:
        # Try both — pick whichever has higher popularity.
        paths.extend([("movie", "movie"), ("tv", "series")])
    best: Optional[dict] = None
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            for endpoint, ret_kind in paths:
                params: dict = {"query": title, "include_adult": "false"}
                if year and endpoint == "movie":
                    params["primary_release_year"] = year
                elif year:
                    params["first_air_date_year"] = year
                resp = await client.get(
                    f"{TMDB_BASE}/search/{endpoint}",
                    params=params, headers=headers,
                )
                if resp.status_code != 200:
                    continue
                results = (resp.json() or {}).get("results") or []
                if not results:
                    continue
                top = results[0]
                pop = float(top.get("popularity") or 0)
                if best and pop <= float(best.get("_pop") or 0):
                    continue
                poster = top.get("poster_path")
                backdrop = top.get("backdrop_path")
                date_key = "release_date" if endpoint == "movie" else "first_air_date"
                year_str = (top.get(date_key) or "")[:4]
                best = {
                    "title":      top.get("title") or top.get("name") or title,
                    "year":       int(year_str) if year_str.isdigit() else None,
                    "type":       ret_kind,
                    "poster_url": f"{TMDB_IMG}{poster}" if poster else None,
                    "backdrop_url": f"https://image.tmdb.org/t/p/w780{backdrop}" if backdrop else None,
                    "rating":     round(float(top.get("vote_average") or 0), 1),
                    "overview":   (top.get("overview") or "").strip(),
                    "_pop":       pop,
                }
    except Exception:  # noqa: BLE001
        log.exception("TMDB lookup failed for %r", title)
        return None
    if best:
        best.pop("_pop", None)
    return best


async def _enrich_recommendations(items: list) -> list:
    """Replace each `{title, year, type, why}` entry with a richer
    `{title, year, type, why, poster_url, rating, overview}` dict.
    Fan-out concurrent TMDB lookups, skip silently on any failure
    so a slow TMDB never blocks V2 AI's main response."""
    if not items:
        return []
    async def one(it: dict) -> dict:
        if not isinstance(it, dict):
            return {}
        title = (it.get("title") or "").strip()
        kind  = (it.get("type") or "").lower()
        year  = it.get("year")
        try:
            year_int = int(year) if year not in (None, "", "null") else None
        except (TypeError, ValueError):
            year_int = None
        tm = await _tmdb_lookup(title, kind=kind or None, year=year_int)
        merged = dict(it)
        if tm:
            merged["poster_url"]    = tm.get("poster_url")
            merged["backdrop_url"]  = tm.get("backdrop_url")
            merged["rating"]        = tm.get("rating")
            merged["overview"]      = tm.get("overview") or it.get("why") or ""
            if not merged.get("year"):
                merged["year"] = tm.get("year")
        return merged
    return await asyncio.gather(*(one(it) for it in items if it))


# ── v2.8.35 — Per-device conversation memory ───────────────────────
# Each launcher box keeps a rolling buffer of its 6 most recent
# (user transcript → assistant intent summary) exchanges on the
# backend.  These are pasted into GPT's prompt as "Recent
# conversation context" so follow-up questions like "and what about
# his other movies?" or "tell me more about that" work naturally.
#
# Stored in-memory only — restart clears all sessions, which is
# fine because conversations are ephemeral by nature.
from collections import deque
_v2ai_conversations: dict[str, "deque[tuple[str,str]]"] = {}
_V2AI_HISTORY_LEN = 6  # last 6 user+assistant turns


def _v2ai_summarise_for_history(parsed: dict) -> str:
    """Compress an intent dict to a short 1-line assistant-side
    message for the conversation buffer.  Pure text, no JSON, so
    GPT's context window stays small."""
    intent = parsed.get("intent", "reject")
    if intent in ("play_movie", "play_series"):
        return f"Played '{parsed.get('title', '?')}'."
    if intent == "open_app":
        return f"Opened the {parsed.get('app_name', '?')} app."
    if intent == "recommend":
        titles = [r.get("title", "?") for r in (parsed.get("recommendations") or [])[:5]]
        return f"Suggested: {', '.join(titles)}."
    if intent == "search":
        return f"Searched for '{parsed.get('query', '?')}'."
    if intent == "qa":
        subj = parsed.get("answer_subject") or ""
        ans  = (parsed.get("answer") or "")[:140]
        return f"Answered about {subj}: {ans}"
    if intent == "person_info":
        return f"Showed info about {parsed.get('person_name', '?')}."
    return parsed.get("reject_reason") or parsed.get("speech_reply") or "Rejected."


def _v2ai_history_block(device_id: str) -> str:
    """Format the device's recent exchanges as a single prompt
    paragraph.  Empty string if the device has no history yet."""
    buf = _v2ai_conversations.get(device_id)
    if not buf:
        return ""
    lines = ["Recent conversation context:"]
    for user, assist in buf:
        lines.append(f"  User: {user}")
        lines.append(f"  V2 AI: {assist}")
    lines.append("")
    return "\n".join(lines)


def _v2ai_remember(device_id: str, transcript: str, parsed: dict) -> None:
    """Push the current exchange onto the device's history buffer."""
    if not device_id:
        return
    buf = _v2ai_conversations.setdefault(
        device_id, deque(maxlen=_V2AI_HISTORY_LEN),
    )
    buf.append((transcript.strip(), _v2ai_summarise_for_history(parsed)))


# ── v2.8.26 — Diagnostic ping endpoint ─────────────────────────────
# Returns instantly (no LLM call).  Lets the launcher Android client
# verify it can reach the V2 AI backend before attempting an audio
# upload.  Surfaces specific error messages in the UI:
#   • DNS / TLS failure → "Wi-Fi is off or DNS is broken"
#   • 4xx/5xx           → "Server is busy — please try again"
@app.get("/api/launcher/v2ai/ping")
def v2ai_ping() -> dict:
    """Return immediately with backend health.  No LLM call."""
    have_key = bool(os.environ.get("EMERGENT_LLM_KEY"))
    return {
        "ok":   have_key,
        "build": "v2.8.26",
        "ts":   now_ts(),
        "reason": None if have_key else "EMERGENT_LLM_KEY missing on the backend",
    }


@app.post("/api/launcher/kids-lock")
async def set_kids_lock(payload: dict = Body(...)) -> dict:
    """Vesper calls this when the Kids profile is activated with a
    PIN configured (locked=true) and when the PIN is successfully
    entered to exit (locked=false).  Launcher polls the GET
    counterpart every onResume; if locked it bounces the user back
    to Vesper so the HOME button can't escape the Kids sandbox.

    v2.8.42 — Initial release.  Lock state lives in `store.json`
    under `kids_locks: {device_id: {locked: bool, ts: epoch_s}}`.
    Stale entries (>24h) are treated as unlocked by the GET so a
    crashed Vesper can't trap the launcher forever."""
    device_id = (payload.get("device_id") or "").strip()
    locked = bool(payload.get("locked"))
    if not device_id:
        raise HTTPException(400, "device_id required")
    s = _load_store()
    locks = s.setdefault("kids_locks", {})
    locks[device_id] = {
        "locked": locked,
        "ts": int(datetime.now(timezone.utc).timestamp()),
    }
    _save_store(s)
    log.info("kids-lock: device=%s locked=%s", device_id, locked)
    return {"ok": True, "device_id": device_id, "locked": locked}


@app.get("/api/launcher/kids-lock/{device_id}")
async def get_kids_lock(device_id: str) -> dict:
    """Returns the current Kids-sandbox lock state for a device.
    Stale entries (>24h old) auto-treated as unlocked so a crashed
    Vesper can never permanently trap the launcher."""
    s = _load_store()
    entry = (s.get("kids_locks") or {}).get(device_id)
    if not entry:
        return {"locked": False, "ts": 0}
    now = int(datetime.now(timezone.utc).timestamp())
    age = now - int(entry.get("ts") or 0)
    locked = bool(entry.get("locked")) and age < 24 * 3600
    return {"locked": locked, "ts": entry.get("ts") or 0, "age_seconds": age}


@app.post("/api/launcher/v2ai/process", dependencies=[Depends(_optional_admin_no_op)] if False else [])
async def v2ai_process(
    file: UploadFile = File(...),
    device_id: Optional[str] = Form(None),
) -> dict:
    """Accept an audio recording, return a strict JSON intent the
    launcher acts on.  Endpoint is unauthenticated (every box
    registered with the launcher gets to use it).

    v2.8.35 — Optional `device_id` form field lets the backend
    maintain a 6-turn conversation buffer per device.  Follow-up
    queries like "and what about his other movies?" now resolve
    against the earlier exchange."""
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty audio")
    if len(raw) > 25 * 1024 * 1024:
        raise HTTPException(413, "audio file too large (25 MB max)")

    log.info(
        "v2ai_process: %d bytes received (filename=%s device=%s)",
        len(raw), file.filename, (device_id or "?")[:24],
    )

    # 1) Transcribe via Whisper.
    import time as _time
    _t0 = _time.monotonic()
    transcript = await _v2ai_transcribe(raw, filename=file.filename or "audio.m4a")
    log.info("v2ai_process: whisper took %.1fs → '%s'", _time.monotonic() - _t0, transcript[:80])
    if not transcript or len(transcript.strip()) < 2:
        return {
            "intent": "reject",
            "reject_reason": "I didn't catch that.",
            "speech_reply": "I didn't catch that, please try again.",
            "transcript": transcript or "",
        }

    # 2a) FAST PATH — regex matcher for the 80% common cases.
    # Avoids the 8-15 s GPT round-trip when the user is clearly
    # saying "play X" / "open X" / "watch X".  This is the
    # difference between a 6 s and a 20 s end-to-end response on
    # the user's HK1 box.
    fast = _v2ai_fast_intent(transcript)
    if fast is not None:
        log.info("v2ai_process: fast-path intent=%s title=%r", fast.get("intent"), fast.get("title") or fast.get("app_name") or fast.get("query"))
        # v2.8.29 — Enrich recommendations on the fast path too so
        # the launcher always renders posters even for "what should
        # I watch" style queries (rare on the fast path but handled).
        if fast.get("intent") in ("recommend", "search"):
            recs = fast.get("recommendations") or []
            if recs:
                fast["recommendations"] = await _enrich_recommendations(recs)
            elif fast.get("query"):
                # search intent without explicit recommendations — do
                # a TMDB lookup on the query itself.
                tm = await _tmdb_lookup(fast["query"])
                if tm:
                    fast["recommendations"] = [{
                        "title": tm["title"], "year": tm.get("year"),
                        "type": tm.get("type", "movie"),
                        "why": tm.get("overview") or "",
                        "poster_url": tm.get("poster_url"),
                        "backdrop_url": tm.get("backdrop_url"),
                        "rating": tm.get("rating"),
                        "overview": tm.get("overview") or "",
                    }]
        fast["transcript"] = transcript
        _v2ai_remember(device_id or "", transcript, fast)
        return fast

    # 2b) Parse intent via GPT (slow fallback for ambiguous queries).
    _t1 = _time.monotonic()
    history_block = _v2ai_history_block(device_id or "")
    parsed = await _v2ai_parse_intent(transcript, history_block=history_block)
    log.info("v2ai_process: GPT took %.1fs → intent=%s", _time.monotonic() - _t1, parsed.get("intent"))

    # v2.8.29 — Enrich any recommendations from GPT with TMDB
    # metadata (poster, rating, overview) for the rich card UI.
    if parsed.get("intent") in ("recommend", "search"):
        recs = parsed.get("recommendations") or []
        if recs:
            parsed["recommendations"] = await _enrich_recommendations(recs)

    # v2.8.36 — Trending intent: fill recommendations from TMDB's
    # official trending list.  Faster + more authoritative than
    # GPT guessing what's popular.
    if parsed.get("intent") == "trending":
        kind = (parsed.get("trending_kind") or "all").lower()
        parsed["recommendations"] = await _tmdb_trending(kind)

    # For QA — fetch the answer_subject's poster + backdrop so the
    # launcher can render a beautiful answer overlay with hero art.
    # v2.8.36 — Also handles person subjects (Vin Diesel net worth,
    # Tom Cruise age, etc) by falling back to TMDB person lookup
    # when `answer_subject_type == "person"`.
    if parsed.get("intent") == "qa":
        subj = (parsed.get("answer_subject") or "").strip()
        subj_type = (parsed.get("answer_subject_type") or "").strip().lower()
        if subj:
            if subj_type == "person":
                tm = await _tmdb_person_lookup(subj)
                if tm:
                    parsed["subject_poster_url"]   = tm.get("profile_url")
                    parsed["subject_backdrop_url"] = None
                    parsed["subject_rating"]       = None
                    parsed["subject_overview"]     = tm.get("biography") or ""
                    parsed["subject_year"]         = None
            else:
                tm = await _tmdb_lookup(
                    subj,
                    kind=(subj_type if subj_type in ("movie", "series") else None),
                )
                if tm:
                    parsed["subject_poster_url"]   = tm.get("poster_url")
                    parsed["subject_backdrop_url"] = tm.get("backdrop_url")
                    parsed["subject_rating"]      = tm.get("rating")
                    parsed["subject_overview"]    = tm.get("overview")
                    parsed["subject_year"]        = tm.get("year")
                else:
                    # Movie/show lookup missed — try person fallback so
                    # actor-name subjects still get a face on the card.
                    tm = await _tmdb_person_lookup(subj)
                    if tm:
                        parsed["subject_poster_url"]   = tm.get("profile_url")
                        parsed["subject_backdrop_url"] = None
                        parsed["subject_overview"]     = tm.get("biography") or parsed.get("subject_overview", "")

    # v2.8.30 — For person_info, look up the actor / director on
    # TMDB and enrich their known_for titles with posters.
    if parsed.get("intent") == "person_info":
        await _enrich_person_info(parsed)

    parsed["transcript"] = transcript
    _v2ai_remember(device_id or "", transcript, parsed)
    return parsed


# ── v2.8.26 — Fast regex intent matcher ────────────────────────────
# Returns a fully-formed intent dict for the common voice patterns,
# or None if the transcript is too ambiguous and we should fall back
# to GPT.  The patterns mirror the strict JSON shape produced by
# V2AI_SYSTEM_PROMPT so downstream code is identical.
#
# v2.8.27 — Made dramatically more forgiving.  Whisper consistently
# mis-transcribes title prefixes (drops "The"), inserts random
# punctuation, and adds filler ("please", "for me", "would you").
# This rewrite strips all that noise BEFORE attempting to match and
# now returns a `search` intent with the raw transcript as the
# query whenever no specific verb is detected — better than a stale
# "I didn't understand" card.
_PLAY_TRIGGER     = r"(?:please\s+)?(?:hey\s+|ok\s+|now\s+|just\s+)?(?:could\s+you\s+|can\s+you\s+|would\s+you\s+|i\s+(?:want\s+to|wanna|need\s+to|would\s+like\s+to)\s+)?(?:play|watch|put\s+on|start|stream|i\s+want\s+to\s+watch|let's\s+watch)"
_OPEN_TRIGGER     = r"(?:please\s+)?(?:hey\s+|ok\s+|just\s+)?(?:could\s+you\s+|can\s+you\s+|would\s+you\s+)?(?:open|launch|start|go\s+to|switch\s+to|fire\s+up)"
_RECOMMEND_RX     = r"(?:recommend|suggest|what\s+should\s+i\s+watch|what\s+can\s+i\s+watch|what's\s+(?:good|new|on)|show\s+me\s+something|find\s+me\s+something|got\s+anything|any\s+ideas|surprise\s+me)"
_FILLER_RX        = r"\b(?:the|a|an)\s+(?:movie|film|show|series|tv\s+show|tv\s+series|tv|episode)\b"
_TYPE_HINT_MOVIE  = r"\b(?:movie|film)\b"
_TYPE_HINT_SERIES = r"\b(?:show|series|tv\s+show|tv\s+series|episode|season)\b"
_TRAIL_FILLER_RX  = r"\b(?:please|thanks|now|for\s+me|on\s+the\s+tv|on\s+tv|app|application)\b"

# v2.8.38 — Trending fast-path.  Matches "what's trending", "top
# movies", "popular shows", "hot right now", "what's hot", "top 10
# this week" etc.  When this matches we skip GPT entirely and hit
# TMDB directly — saves ~5-8 s on the common "what's popular" query.
_TRENDING_RX      = r"(?:trending|trendy|popular|hot|top\s*\d*|number\s+one|what's\s+hot|what's\s+new|what\s+is\s+everyone\s+watching|whats\s+everyone\s+watching)"


def _v2ai_fast_intent(transcript: str) -> Optional[dict]:
    raw = transcript.strip()
    if len(raw) < 2:
        return None
    # Normalise: lowercase, strip internal + terminal punctuation,
    # collapse repeated whitespace, drop disfluencies (uh/um/er/hmm).
    t = raw.lower()
    # Replace internal punctuation with a space so "play, the matrix"
    # becomes "play  the matrix" → regex still matches.
    t = re.sub(r"[\.\,\!\?\;\:\"]+", " ", t)
    t = re.sub(r"^\s*(?:uh+|um+|er+|hmm+|like)\s+", "", t)
    t = re.sub(r"\s+(?:uh+|um+|er+|hmm+)\s+", " ", t)
    t = re.sub(r"\s+", " ", t).strip()
    if not t:
        return None

    # v2.8.29 — WH-question detection.  ANY "what", "who", "when",
    # "where", "why", "how" prefix → skip the fast path so GPT can
    # answer factual questions about movies / TV.  EXCEPT for the
    # specific "what should I watch / what's on" recommendation
    # prefixes handled below.
    # v2.8.38 — Also except for the trending fast-path so "what's
    # trending" / "top movies" skip GPT entirely.
    is_recommend_question = re.search(_RECOMMEND_RX, t) is not None
    is_trending_question  = re.search(_TRENDING_RX, t) is not None
    if not is_recommend_question and not is_trending_question and re.match(
        r"^(?:what|who|when|where|why|how|is|are|does|did|do)\b", t,
    ):
        return None

    # v2.8.38 — Trending fast-path.  Detect "what's trending" /
    # "popular movies" / "top shows" / "top 10 this week" — we'll
    # let v2ai_process do the TMDB call.  Returning a stub here
    # short-circuits GPT entirely.
    if is_trending_question:
        kind = "all"
        if re.search(r"\bmovies?\b|\bfilms?\b", t):
            kind = "movie"
        elif re.search(r"\bshows?\b|\bseries\b|\btv\b", t):
            kind = "series"
        return {
            "intent": "trending",
            "trending_kind": kind,
            "speech_reply": "Here's what's hot right now.",
            "recommendations": [],
        }

    # "recommend …" / "what should i watch …"
    if re.search(_RECOMMEND_RX, t):
        mood_match = re.search(r"(?:something|anything)\s+(\w+(?:\s+\w+)?)", t)
        mood = mood_match.group(1).strip() if mood_match else None
        return {
            "intent": "recommend",
            "mood": mood,
            "speech_reply": f"Here are some {mood} picks." if mood else "Here are a few picks for you.",
        }

    # "open <app>" — capture trailing text as app name.
    m = re.match(rf"^{_OPEN_TRIGGER}\s+(.+)$", t)
    if m:
        app_name = m.group(1).strip()
        app_name = re.sub(_TRAIL_FILLER_RX, "", app_name).strip(" ,.")
        if app_name and len(app_name) <= 50:
            # Title-case for display.
            pretty = " ".join(w.capitalize() for w in app_name.split())
            return {
                "intent": "open_app",
                "app_name": pretty,
                "speech_reply": f"Opening {pretty}.",
            }

    # "play <title>" / "watch <title>" / "put on <title>" / "stream <title>"
    m = re.match(rf"^{_PLAY_TRIGGER}\s+(.+)$", t)
    if m:
        title_raw = m.group(1).strip()
        is_series = bool(re.search(_TYPE_HINT_SERIES, title_raw))
        is_movie  = bool(re.search(_TYPE_HINT_MOVIE,  title_raw))
        # Strip "the movie" / "the show" prefix/suffix + trailing filler.
        title = re.sub(_FILLER_RX, " ", title_raw)
        title = re.sub(_TRAIL_FILLER_RX, "", title)
        title = re.sub(r"\s+", " ", title).strip(" ,.;:!?")
        title = re.sub(r"^(?:on|the|a|an|to|for)\s+", "", title).strip()
        if not title or len(title) > 80:
            return None
        intent = "play_series" if is_series and not is_movie else "play_movie"
        pretty = title.title()
        return {
            "intent": intent,
            "title": pretty,
            "speech_reply": f"Loading {pretty}.",
        }

    # v2.8.27 — Single-word / no-verb transcript → treat as a movie
    # search if it's long enough to be a plausible title.  Beats
    # "I didn't understand" hands-down for the user.
    if len(t.split()) <= 4 and len(t) >= 4 and re.match(r"^[a-z0-9 \-':]+$", t):
        pretty = t.title()
        return {
            "intent": "search",
            "query": pretty,
            "speech_reply": f"Searching for {pretty}.",
        }

    return None


async def _v2ai_transcribe(raw: bytes, filename: str = "audio.m4a") -> str:
    """OpenAI Whisper STT.

    v2.8.40 — Supports TWO authentication modes:
      1. `OPENAI_API_KEY` set → direct OpenAI API call (works
         from ANY server including production VPS).  This is the
         path used on the user's Contabo deployment.
      2. Else `EMERGENT_LLM_KEY` set → routes through the Emergent
         platform gateway.  Only works from inside Emergent's
         preview pod (used for dev).

    v2.8.27 — Adds a domain-specific `prompt` so Whisper biases
    toward movie / TV / app vocabulary instead of generic English.
    Also temperature=0 for deterministic output."""
    import io
    domain_prompt = (
        "Voice command for a smart TV entertainment assistant.  The "
        "user asks to play movies / TV shows, open apps, get "
        "recommendations, see what's trending or popular, or ask "
        "questions about movies, TV, actors, directors, awards, box "
        "office, net worth, age, deaths, relationships, plot, "
        "characters, episodes, or entertainment trivia. "
        "Verbs: play, watch, put on, start, stream, launch, open, "
        "switch to, fire up, recommend, suggest, show me, find me, "
        "surprise me, what should I watch, what's trending, what's "
        "popular, what's the top, what's new, what's hot, who "
        "played, who directed, who starred, what episode, when "
        "does, how old, what's the net worth, what's the rating. "
        "Apps: Netflix, Disney Plus, HBO Max, Max, Hulu, Prime "
        "Video, Apple TV, Paramount Plus, Peacock, YouTube, "
        "Spotify, Plex, Jellyfin, Kodi, VLC, Twitch, Crunchyroll. "
        "Famous people: Leonardo DiCaprio, Tom Cruise, Vin Diesel, "
        "Brad Pitt, Heath Ledger, Quentin Tarantino, Christopher "
        "Nolan, Scarlett Johansson, Ryan Reynolds, Margot Robbie, "
        "Cillian Murphy, Dwayne Johnson, Zendaya, Timothée "
        "Chalamet, Pedro Pascal, Florence Pugh, Pedro Almodóvar. "
        "Titles: The Matrix, Inception, Interstellar, Avatar, Top "
        "Gun Maverick, Oppenheimer, Barbie, Dune, Tenet, Breaking "
        "Bad, Stranger Things, The Last of Us, House of the "
        "Dragon, Game of Thrones, Succession, The Bear, Severance, "
        "Wednesday, Squid Game, Peaky Blinders, The Crown, The "
        "Mandalorian, Loki, Andor, The Boys, Yellowstone, Better "
        "Call Saul, Ted Lasso, Friends, Seinfeld, The Office, "
        "Lost, The Sopranos."
    )
    openai_key = os.environ.get("OPENAI_API_KEY")
    groq_key = os.environ.get("GROQ_API_KEY")

    # v2.8.41 — Groq path takes precedence (10× cheaper, 5× faster
    # than OpenAI Whisper).  Groq's API is OpenAI-compatible — we
    # just point the AsyncOpenAI client at api.groq.com and pick
    # `whisper-large-v3-turbo` instead of `whisper-1`.
    # Groq caps the Whisper `prompt` at 896 chars (OpenAI allows ~1000).
    # We use a shorter domain hint here — still seeds the model with
    # the most useful vocabulary (titles + actor names) which is
    # what biases transcription accuracy.
    if groq_key:
        groq_prompt = (
            "Voice command for a smart TV. User asks to play movies, TV "
            "shows, open apps, get recommendations, ask trivia. Apps: "
            "Netflix, Disney Plus, HBO Max, Hulu, Prime Video, Apple TV, "
            "Paramount Plus, Peacock, YouTube, Spotify, Plex, Jellyfin, "
            "Kodi, VLC, Twitch, Crunchyroll. People: Leonardo DiCaprio, "
            "Tom Cruise, Brad Pitt, Heath Ledger, Quentin Tarantino, "
            "Christopher Nolan, Scarlett Johansson, Ryan Reynolds, "
            "Margot Robbie, Cillian Murphy, Dwayne Johnson, Zendaya, "
            "Timothee Chalamet, Pedro Pascal, Florence Pugh. Titles: "
            "The Matrix, Inception, Interstellar, Avatar, Top Gun "
            "Maverick, Oppenheimer, Barbie, Dune, Breaking Bad, "
            "Stranger Things, The Last of Us, House of the Dragon, "
            "Game of Thrones, Succession, The Bear, Severance, "
            "Wednesday, Squid Game, Peaky Blinders, The Crown."
        )
        from openai import AsyncOpenAI
        client = AsyncOpenAI(api_key=groq_key, base_url="https://api.groq.com/openai/v1")
        try:
            resp = await client.audio.transcriptions.create(
                model="whisper-large-v3-turbo",
                file=(filename, raw, "audio/m4a"),
                response_format="json",
                language="en",
                prompt=groq_prompt,
                temperature=0,
            )
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(502, f"transcription failed (groq): {exc}")
        return (getattr(resp, "text", "") or "").strip()

    if openai_key:
        # Direct OpenAI path — works from any server.
        from openai import AsyncOpenAI
        client = AsyncOpenAI(api_key=openai_key)
        buf = io.BytesIO(raw)
        buf.name = filename
        try:
            resp = await client.audio.transcriptions.create(
                model="whisper-1",
                file=(filename, raw, "audio/m4a"),
                response_format="json",
                language="en",
                prompt=domain_prompt,
                temperature=0,
            )
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(502, f"transcription failed: {exc}")
        return (getattr(resp, "text", "") or "").strip()

    # Fallback — Emergent platform gateway (dev preview pod only).
    api_key = os.environ.get("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(500, "Set OPENAI_API_KEY (production) or EMERGENT_LLM_KEY (dev) on the backend")
    from emergentintegrations.llm.openai import OpenAISpeechToText
    stt = OpenAISpeechToText(api_key=api_key)
    buf = io.BytesIO(raw)
    buf.name = filename
    try:
        resp = await stt.transcribe(
            file=buf,
            model="whisper-1",
            response_format="json",
            language="en",
            prompt=domain_prompt,
            temperature=0,
        )
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(502, f"transcription failed: {exc}")
    return (getattr(resp, "text", "") or "").strip()


async def _v2ai_parse_intent(transcript: str, history_block: str = "") -> dict:
    """GPT intent parser — returns the schema described in
    V2AI_SYSTEM_PROMPT.  Falls back to a safe reject if the model
    returns garbage.

    v2.8.27 — Switched from gpt-5 → gpt-4o-mini.  For this strict-
    JSON intent task gpt-4o-mini is ~5-8 s vs gpt-5's ~12-18 s, with
    no measurable accuracy drop.  Combined with the regex fast-path
    this brings worst-case end-to-end V2 AI latency under 15 s on
    the user's HK1 box.

    v2.8.35 — Optional `history_block` prepended to the system
    prompt so follow-up queries ("and what about his other movies?")
    resolve against earlier context.

    v2.8.40 — Direct OpenAI when `OPENAI_API_KEY` is set (prod);
    Emergent gateway otherwise (dev)."""
    system_msg = V2AI_SYSTEM_PROMPT
    if history_block:
        system_msg = f"{V2AI_SYSTEM_PROMPT}\n\n{history_block}"

    openai_key = os.environ.get("OPENAI_API_KEY")
    groq_key = os.environ.get("GROQ_API_KEY")

    # v2.8.41 — Groq path takes precedence for intent parsing:
    # llama-3.1-8b-instant on Groq returns valid JSON in ~200-400 ms
    # vs gpt-4o-mini's ~1-2 s, at ~8× lower cost.  OpenAI-compatible
    # API so we just swap `base_url` + model name.
    if groq_key:
        from openai import AsyncOpenAI
        client = AsyncOpenAI(api_key=groq_key, base_url="https://api.groq.com/openai/v1")
        try:
            resp = await client.chat.completions.create(
                model="llama-3.1-8b-instant",
                temperature=0.2,
                response_format={"type": "json_object"},
                messages=[
                    {"role": "system", "content": system_msg},
                    {"role": "user",   "content": transcript},
                ],
            )
            raw = resp.choices[0].message.content or "{}"
        except Exception as exc:  # noqa: BLE001
            log.exception("Groq chat failed")
            return {
                "intent": "reject",
                "reject_reason": "I couldn't process that — try again.",
                "speech_reply": "Something went wrong, please try again.",
            }
        try:
            import json
            return json.loads(raw)
        except json.JSONDecodeError:
            return {
                "intent": "reject",
                "reject_reason": "I couldn't parse the response — try again.",
                "speech_reply": "Something went wrong, please try again.",
            }

    if openai_key:
        from openai import AsyncOpenAI
        client = AsyncOpenAI(api_key=openai_key)
        try:
            resp = await client.chat.completions.create(
                model="gpt-4o-mini",
                temperature=0.2,
                response_format={"type": "json_object"},
                messages=[
                    {"role": "system", "content": system_msg},
                    {"role": "user",   "content": transcript},
                ],
            )
            raw = resp.choices[0].message.content or "{}"
        except Exception as exc:  # noqa: BLE001
            log.exception("OpenAI chat failed")
            return {
                "intent": "reject",
                "reject_reason": "I couldn't process that — try again.",
                "speech_reply": "Something went wrong, please try again.",
            }
        try:
            import json
            return json.loads(raw)
        except json.JSONDecodeError:
            return {
                "intent": "reject",
                "reject_reason": "I couldn't parse the response — try again.",
                "speech_reply": "Something went wrong, please try again.",
            }

    # Fallback — Emergent platform gateway (dev preview pod only).
    from emergentintegrations.llm.chat import LlmChat, UserMessage
    api_key = os.environ.get("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(500, "Set OPENAI_API_KEY (production) or EMERGENT_LLM_KEY (dev) on the backend")
    chat = LlmChat(
        api_key=api_key,
        session_id=f"v2ai-{uuid.uuid4().hex[:8]}",
        system_message=system_msg,
    ).with_model("openai", "gpt-4o-mini")
    msg = UserMessage(text=transcript)
    try:
        reply = await chat.send_message(msg)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(502, f"intent parsing failed: {exc}")

    text = str(reply).strip()
    # Strip any accidental code-fence wrapping.
    if text.startswith("```"):
        text = text.strip("`")
        # remove leading 'json' fence label if present
        if text.lower().startswith("json"):
            text = text[4:]
    text = text.strip()
    try:
        parsed = _json.loads(text)
    except Exception:
        # Fallback — model didn't return JSON.  Treat as a safe reject.
        return {
            "intent": "reject",
            "reject_reason": "I couldn't understand that request.",
            "speech_reply": "Sorry, I couldn't understand that.",
        }
    # Ensure required keys exist.
    parsed.setdefault("intent", "reject")
    parsed.setdefault("speech_reply", "Done.")
    return parsed


def _optional_admin_no_op():
    """Placeholder so the decorator above resolves cleanly when we
    want the endpoint open.  No-op."""
    return None


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


# ════════════════════════════════════════════════════════════════════
#  Backup & Restore — download the entire launcher state (store.json
#  + every uploaded APK / icon / wallpaper / image) as a single ZIP,
#  upload one to restore it.  Lets the admin migrate data between
#  launcher deployments in one click (no SSH, no scp).
# ════════════════════════════════════════════════════════════════════
@app.get("/api/admin/backup", dependencies=[Depends(require_admin)])
async def backup_launcher_data():
    """Stream a ZIP containing everything under /data — store.json,
    apks/, apk_icons/, tile_apks/, tile_images/, wallpapers/, icons/,
    qr/, v2ai/, appstore/, layout/.  Hit this from prod to grab a
    snapshot, then upload it to a fresh launcher via /api/admin/restore."""
    import io
    import zipfile
    from fastapi.responses import StreamingResponse

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for path in DATA_DIR.rglob("*"):
            if path.is_file() and ".gitkeep" not in path.name:
                arc = path.relative_to(DATA_DIR).as_posix()
                z.write(path, arcname=arc)
    buf.seek(0)
    ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    return StreamingResponse(
        buf,
        media_type="application/zip",
        headers={
            "Content-Disposition": f'attachment; filename="launcher-backup-{ts}.zip"',
        },
    )


@app.post("/api/admin/restore", dependencies=[Depends(require_admin)])
async def restore_launcher_data(file: UploadFile = File(...)):
    """Restore a backup ZIP produced by /api/admin/backup.  The ZIP
    contents are extracted into /data, overwriting any existing
    files (store.json, all APKs, all icons, etc.).  After restoring
    the in-memory store cache is invalidated so the next API call
    reads the new state."""
    import io
    import zipfile

    if not (file.filename or "").lower().endswith(".zip"):
        raise HTTPException(400, "Upload must be a .zip file")
    data = await file.read()
    if not data:
        raise HTTPException(400, "Upload is empty")
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            members = z.namelist()
            for name in members:
                # Reject path-traversal attempts.
                if name.startswith("/") or ".." in name.split("/"):
                    raise HTTPException(400, f"Unsafe path in zip: {name}")
            z.extractall(DATA_DIR)
    except zipfile.BadZipFile:
        raise HTTPException(400, "Not a valid ZIP archive")
    # Re-read from disk — _load_store has no in-memory cache.
    fresh = _load_store()
    return {
        "ok":          True,
        "files":       len(members),
        "dock_tiles":  len(fresh.get("dock_tiles", [])),
        "apks":        len(fresh.get("apks", [])),
        "devices":     len(fresh.get("registered_devices", [])),
    }


# ════════════════════════════════════════════════════════════════════
#  Vesper v2 login vault — proxy to the main Vesper backend.
#  Lets the launcher admin UI add / edit / expire client logins for
#  the Vesper v2 streaming app without leaving the dashboard.
# ════════════════════════════════════════════════════════════════════
VESPER_BACKEND_URL = (os.environ.get("VESPER_BACKEND_URL") or "http://localhost:8001").rstrip("/")
# Matches the auth_router default in the Vesper backend so the two
# services can authenticate to each other without manual env setup.
VESPER_ADMIN_KEY = (
    os.environ.get("VESPER_ADMIN_KEY")
    or "vesper-admin-49a1f8e2c7b03d6e85a4192c8d3f6e0a"
)


async def _vesper_proxy(method: str, path: str, payload: dict | None = None) -> JSONResponse:
    """Forward a request to the main Vesper backend's /api/admin/accounts
    endpoint family, attaching the shared X-Admin-Key.  Returns the
    upstream JSON verbatim (status + body) so the admin UI sees the
    same error shape it would get talking direct."""
    import httpx
    url = f"{VESPER_BACKEND_URL}{path}"
    headers = {"X-Admin-Key": VESPER_ADMIN_KEY, "Content-Type": "application/json"}
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            if method == "GET":
                r = await client.get(url, headers=headers)
            elif method == "POST":
                r = await client.post(url, headers=headers, json=payload or {})
            elif method == "PATCH":
                r = await client.patch(url, headers=headers, json=payload or {})
            elif method == "DELETE":
                r = await client.delete(url, headers=headers)
            else:
                raise HTTPException(405, "method not allowed")
    except httpx.HTTPError as exc:
        return JSONResponse(
            status_code=502,
            content={"ok": False, "error": f"Vesper backend unreachable: {exc}"},
        )
    try:
        body = r.json()
    except Exception:  # noqa: BLE001
        body = {"detail": r.text}
    return JSONResponse(status_code=r.status_code, content=body)


@app.get("/api/admin/vesper-accounts",
         dependencies=[Depends(require_admin)])
async def vesper_list_accounts() -> JSONResponse:
    return await _vesper_proxy("GET", "/api/admin/accounts")


@app.post("/api/admin/vesper-accounts",
          dependencies=[Depends(require_admin)])
async def vesper_create_account(payload: dict = Body(...)) -> JSONResponse:
    # Only forward the fields the main backend cares about so we
    # don't accidentally leak admin-only metadata.
    safe = {
        k: payload.get(k)
        for k in ("username", "password", "label", "expires_at", "status", "notes")
        if payload.get(k) is not None
    }
    return await _vesper_proxy("POST", "/api/admin/accounts", safe)


@app.patch("/api/admin/vesper-accounts/{account_id}",
           dependencies=[Depends(require_admin)])
async def vesper_update_account(account_id: str, payload: dict = Body(...)) -> JSONResponse:
    safe = {
        k: payload.get(k)
        for k in ("username", "password", "label", "expires_at", "status", "notes")
        if k in payload
    }
    return await _vesper_proxy("PATCH", f"/api/admin/accounts/{account_id}", safe)


@app.delete("/api/admin/vesper-accounts/{account_id}",
            dependencies=[Depends(require_admin)])
async def vesper_delete_account(account_id: str) -> JSONResponse:
    return await _vesper_proxy("DELETE", f"/api/admin/accounts/{account_id}")


@app.post("/api/admin/vesper-accounts/bulk-import",
          dependencies=[Depends(require_admin)])
async def vesper_bulk_import(payload: dict = Body(...)) -> JSONResponse:
    return await _vesper_proxy("POST", "/api/admin/accounts/bulk-import", payload)


@app.post("/api/admin/layout", dependencies=[Depends(require_admin)])
def set_layout(layout: LayoutSettings) -> dict:
    """v1.0 — Persist admin-edited Layout Editor values."""
    store = _load_store()
    store["layout"] = layout.model_dump()
    _save_store(store)
    return {"ok": True, "generation": store["generation"], "layout": store["layout"]}


@app.post("/api/admin/layout/heading-image",
          dependencies=[Depends(require_admin)])
async def upload_layout_heading_image(file: UploadFile = File(...)) -> dict:
    """v2.8.50 — Drag-and-drop heading-image upload (was URL-only).

    Saves at `data/layout/heading.png` and points
    `layout.featured_heading_image_url` at it.  Image is preserved
    at up to 2048×1024 px so it stays crisp at any TV resolution
    and at any admin-chosen height (default 80 dp · max 280 dp).
    """
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "layout" / "heading.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        img = ImageOps.contain(img, (2048, 1024), Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    layout = store.setdefault("layout", {})
    # `?ts=…` busts the CDN / WebView cache so the launcher
    # picks up the new image on the next config poll.
    layout["featured_heading_image_url"] = (
        f"/assets/layout/heading.png?ts={now_ts()}"
    )
    _save_store(store)
    return {
        "ok": True,
        "featured_heading_image_url": _abs(layout["featured_heading_image_url"]),
    }


@app.delete("/api/admin/layout/heading-image",
            dependencies=[Depends(require_admin)])
def clear_layout_heading_image() -> dict:
    """v2.8.50 — Remove the uploaded heading image (drag-drop pair)."""
    out_path = DATA_DIR / "layout" / "heading.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("layout", {})["featured_heading_image_url"] = None
    _save_store(store)
    return {"ok": True}


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
            "apk_build_id": t.get("apk_build_id"),
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
        # v2.10.81 — preserve the per-tile build-id across dock saves
        # (it's stamped only by the APK upload endpoint).
        d["apk_build_id"] = prev.get("apk_build_id")
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
        "apk_build_id": None,
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

    v2.10.59 — `apk_package_id` and `apk_version` are now auto-
    extracted from the APK manifest via pyaxmlparser whenever the
    admin doesn't supply explicit overrides.  This is what enables
    the launcher's UPDATE pill to detect a freshly-pinned version
    without restarting the box — the launcher's version-compare
    against PackageManager.versionName needs a trustworthy
    version_name, not a typed-by-hand string."""
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
    # v2.10.81 — Fresh build-id on every upload so the launcher's
    # per-tile UPDATE pill fires unconditionally on the next box
    # poll, regardless of whether the operator bumped versionName.
    # Pure UUIDv4 hex (no APK SHA — operator might re-upload the
    # very same .apk binary with different metadata and still want
    # the pill to light up on every box).
    tile["apk_build_id"] = uuid.uuid4().hex

    # v2.10.59 — Auto-extract package_id + version_name from the
    # APK manifest.  Admin-provided form fields override (they're
    # the source-of-truth for stripped-manifest builds), otherwise
    # we trust the manifest.  Same `inspect_apk` helper the App
    # Store upload uses.
    extracted_pkg: Optional[str] = None
    extracted_ver: Optional[str] = None
    try:
        from apk_meta import inspect_apk
        meta = await asyncio.to_thread(
            inspect_apk,
            target,
            DATA_DIR / "apk_icons",
            f"_tile_{key}_{uuid.uuid4().hex[:6]}",
        )
        if isinstance(meta, dict):
            extracted_pkg = (meta.get("package_id") or "").strip() or None
            extracted_ver = (meta.get("version_name") or "").strip() or None
    except Exception as exc:  # noqa: BLE001
        # Manifest extraction is best-effort — operator can still
        # supply values manually if pyaxmlparser fails.
        log.warning("inspect_apk failed for tile %s: %s", key, exc)

    if apk_package_id and apk_package_id.strip():
        tile["apk_package_id"] = apk_package_id.strip()
    elif extracted_pkg:
        tile["apk_package_id"] = extracted_pkg
    if apk_version and apk_version.strip():
        tile["apk_version"] = apk_version.strip()
    elif extracted_ver:
        tile["apk_version"] = extracted_ver

    # v2.10.62 — Sanity-check: if the manifest's package name differs
    # from the tile's existing `target_package` (which the operator
    # set up explicitly when configuring the tile), surface a warning
    # in the response so the admin UI can show a confirmation toast
    # before the operator silently pins the wrong APK to a tile.
    # Example: uploading Vesper TV (`tv.onnowtv.app`) to a tile whose
    # `target_package` is `tv.onnowtv.fta.recycler` would otherwise
    # quietly install Vesper when the user clicks the launcher's
    # INSTALL pill.  That's the exact bug v2.10.62 closes.
    mismatch_warning = None
    target_pkg = (tile.get("target_package") or "").strip()
    final_apk_pkg = (tile.get("apk_package_id") or "").strip()
    if target_pkg and final_apk_pkg and target_pkg != final_apk_pkg:
        mismatch_warning = (
            f"⚠ APK contains package '{final_apk_pkg}' but this tile's "
            f"target_package is '{target_pkg}'. The launcher will install "
            f"'{final_apk_pkg}' (not '{target_pkg}') when the user clicks "
            f"the tile's INSTALL pill. Re-upload the correct APK if this "
            f"isn't what you intended."
        )

    _save_store(store)
    return {
        "ok": True,
        "apk_url": _abs(tile["apk_url"]),
        "apk_filename": tile["apk_filename"],
        "apk_package_id": tile.get("apk_package_id"),
        "apk_version": tile.get("apk_version"),
        # v2.10.59 — Tell the admin UI whether we auto-extracted so
        # it can show a "✓ detected v1.2.3" hint next to the inputs.
        "auto_extracted": {
            "package_id": extracted_pkg,
            "version_name": extracted_ver,
        },
        # v2.10.62 — Wrong-APK guard rail.  See block above.
        "package_mismatch_warning": mismatch_warning,
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


# ───────────────────────── Bulk install manifest ─────────────────────────
#
# v2.10.71 — Powers the launcher's hidden "Install all apps" flow.  The
# user wanted a way to bulk-install every pinned APK on a fresh box in
# one shot, so they don't have to find each APK by hand.  The launcher
# fires a 5-second long-press in the top-left corner of the Apps drawer,
# opens a hidden BulkInstallActivity, GETs this manifest, downloads
# every APK to cache first, then fires the system install prompt for
# each in sequence.
#
# Public (no admin auth required) — the same machine that's allowed to
# fetch /api/launcher/config is implicitly allowed to install the apps
# the admin has pinned for it.  We do NOT expose admin-only metadata
# here; just the bare minimum the installer queue needs.

@app.get("/api/bulk/manifest")
def bulk_install_manifest(request: Request) -> dict:
    """Return every dock tile that has a pinned APK.  The launcher's
    BulkInstallActivity uses this to drive its sequential download +
    install queue."""
    store = _load_store()
    items: list[dict] = []
    for tile in store.get("dock_tiles", []):
        apk_url = tile.get("apk_url")
        if not apk_url:
            continue
        items.append({
            "key":         tile["key"],
            "label":       tile.get("label") or tile["key"],
            "package_id":  tile.get("apk_package_id") or tile.get("target_package") or "",
            "version":     tile.get("apk_version") or "",
            "apk_url":     _abs(apk_url),
            "apk_filename": tile.get("apk_filename") or "",
            "icon_url":    _abs(tile.get("image_url") or tile.get("icon_url")),
            "size_bytes":  _safe_filesize(tile.get("apk_url")),
        })
    return {
        "apks": items,
        "generation": store.get("generation", 0),
        "count": len(items),
    }


def _safe_filesize(asset_url: Optional[str]) -> int:
    """Best-effort byte size for an /assets/* URL.  Returns 0 if the
    file is missing — the installer treats 0 as "unknown" and just
    streams without a progress bar."""
    if not asset_url:
        return 0
    try:
        rel = asset_url.lstrip("/")
        if not rel.startswith("assets/"):
            return 0
        path = DATA_DIR / rel[len("assets/"):]
        return path.stat().st_size if path.exists() else 0
    except Exception:  # noqa: BLE001
        return 0




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
    # v2.10.53 — Accept both plain `.apk` and split-APK bundles
    # (`.apkm` / `.xapk` / `.apks`).  The Launcher's in-house APKM
    # installer (v2.10.53+) handles all three formats natively, so
    # admins can drop any of them onto the App Store.
    ALLOWED_EXTS = {".apk", ".apkm", ".xapk", ".apks"}
    incoming_ext = Path(file.filename).suffix.lower()
    if incoming_ext not in ALLOWED_EXTS:
        raise HTTPException(
            400,
            f"Unsupported file type {incoming_ext!r}. Allowed: {sorted(ALLOWED_EXTS)}",
        )
    aid = uuid.uuid4().hex[:12]
    safe_name = f"{aid}_{Path(file.filename).name}"
    target = DATA_DIR / "apks" / safe_name
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            await f.write(chunk)

    # Introspect the APK that just landed.  Runs in a thread pool —
    # pyaxmlparser is blocking I/O.  Bundles (.apkm/.xapk/.apks)
    # are ZIP archives of split APKs; pyaxmlparser only knows how
    # to read a plain APK, so for bundles we extract `base.apk`
    # from the ZIP first and introspect that.
    from apk_meta import inspect_apk
    introspect_target = target
    bundle_tmp: Optional[Path] = None
    if incoming_ext in {".apkm", ".xapk", ".apks"}:
        import zipfile
        import tempfile
        try:
            with zipfile.ZipFile(target) as zf:
                base_entry = next(
                    (n for n in zf.namelist()
                     if n.lower().endswith(".apk")
                     and "split" not in n.lower()),
                    None,
                ) or next(
                    (n for n in zf.namelist() if n.lower().endswith(".apk")),
                    None,
                )
                if base_entry:
                    bundle_tmp = Path(tempfile.gettempdir()) / f"{aid}_base.apk"
                    with zf.open(base_entry) as src, open(bundle_tmp, "wb") as dst:
                        dst.write(src.read())
                    introspect_target = bundle_tmp
        except Exception:
            # Fall back to scanning the bundle directly; pyaxmlparser
            # will most likely fail gracefully and return {}.
            pass
    try:
        meta = await asyncio.to_thread(
            inspect_apk,
            introspect_target,
            DATA_DIR / "apk_icons",
            aid,
        )
    finally:
        # v2.10.53 — Clean up the bundle's extracted base.apk tmp
        # so long-running pods don't accumulate .apk files in /tmp.
        if bundle_tmp is not None:
            try:
                bundle_tmp.unlink(missing_ok=True)
            except Exception:
                pass

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


# ── v2.10.53 — System dependency distribution ─────────────────────
# The Vesper TV app (and other clients) call this PUBLIC endpoint
# at launch to fetch missing system-level dependencies, e.g.
# Android System WebView 138.  The admin uploads the bundle once
# via the App Store ("system dependency" pseudo-app) and pins the
# id under `system_deps[<name>]` in the store; this endpoint maps
# the friendly name to the underlying file.
#
# Why public?  The client doing the fetch is a TV box at first
# boot — it doesn't yet have an admin JWT and the file has no
# private content (it's a public WebView APKM).  We still rate-limit
# elsewhere to prevent obvious abuse.
@app.get("/api/system-deps/{name}.apkm")
def get_system_dep_apkm(name: str):
    """Return the APKM bundle registered under `system_deps[name]`.

    Resolution order (first hit wins):
      1. Pre-baked file at `data/system-deps/{name}.apkm` — the
         simplest path, no admin setup needed.  Just drop the file
         on the server and clients get it instantly.
      2. Explicit admin-pinned mapping in `store['system_deps']`.
      3. Fuzzy match against the App Store's `apks` by name prefix.

    Falls through to 404 when nothing matches.  Public on purpose —
    TV clients hit this on first boot before they have any auth.
    """
    from fastapi.responses import FileResponse, JSONResponse
    target_path: Optional[Path] = None

    # Path 1 — pre-baked file on disk (preferred).
    safe_name = name.replace("/", "").replace("..", "")
    prebaked = DATA_DIR / "system-deps" / f"{safe_name}.apkm"
    if prebaked.exists() and prebaked.is_file():
        target_path = prebaked
    else:
        # Same lookup but the admin happened to drop the file with
        # a `.apk` extension — accept it either way.
        prebaked_apk = DATA_DIR / "system-deps" / f"{safe_name}.apk"
        if prebaked_apk.exists() and prebaked_apk.is_file():
            target_path = prebaked_apk

    # Path 2 — explicit system_deps mapping in the store.
    if target_path is None:
        store = _load_store()
        deps = store.get("system_deps") or {}

        def _resolve_apk_url(rel: str) -> Optional[Path]:
            """Resolve any `apk_url` field — relative OR absolute —
            to a real on-disk Path under DATA_DIR/apks/.

            Historical formats we've seen in the wild:
              • `/assets/apks/abc_App.apk`              (relative, dev)
              • `/launcher/assets/apks/abc_App.apk`     (relative w/ prefix)
              • `https://host/launcher/assets/apks/abc_App.apk` (absolute, prod)
              • `https://host/assets/apks/abc_App.apk`  (absolute, no prefix)
            We extract just the filename component and reuse the
            local apks directory.
            """
            if not rel:
                return None
            from urllib.parse import urlparse
            parsed_path = urlparse(rel).path if rel.startswith("http") else rel
            if "/assets/apks/" not in parsed_path:
                return None
            filename = Path(parsed_path).name
            if not filename:
                return None
            candidate = DATA_DIR / "apks" / filename
            return candidate if candidate.exists() else None

        if name in deps:
            target_path = _resolve_apk_url((deps[name] or {}).get("apk_url") or "")

        # Path 3 — find by App Store name match.  Robust to common
        # naming variants the admin might use:
        #   • "WebView 138"
        #   • "Android System WebView 138"
        #   • "Webview138"  /  "WEBVIEW_138_apkm"
        #   • "com.android.chrome_138.0.7204.180_..."  (raw APKMirror
        #     filename — Chrome ships as the WebView provider on
        #     some boxes, so it should match `webview-138`).
        # Normalisation: lowercase + strip every non-alphanumeric
        # char, then substring-test the requested key against the
        # normalised name.  `webview-138` → `webview138`.  For known
        # synonym groups (webview↔chrome↔android system webview)
        # we expand the candidate set so any of them wins.
        if target_path is None:
            import re

            def _norm(s: Optional[str]) -> str:
                return re.sub(r"[^a-z0-9]", "", (s or "").lower())

            # Synonym groups — each key shares its set's words.
            # Adding more dependency types in future (e.g. play
            # services) is a one-liner here.
            _SYNONYM_GROUPS: list[set[str]] = [
                {
                    "webview",                    # standalone System WebView
                    "androidsystemwebview",
                    "androidwebview",
                    "googlewebview",
                    "chrome",                     # Chrome ships an embedded WebView
                    "googlechrome",
                    "comandroidchrome",           # raw package-name prefix in filenames
                    "comgoogleandroidwebview",
                },
            ]

            wanted_norm = _norm(name)  # e.g. "webview138"
            # Split into <slug><version> when possible so we can swap the slug.
            m = re.match(r"^([a-z]+)(\d+)$", wanted_norm)

            candidates: set[str] = {wanted_norm}
            if m:
                slug, version = m.group(1), m.group(2)
                for group in _SYNONYM_GROUPS:
                    if slug in group:
                        candidates.update(alias + version for alias in group)
                        break

            for a in store.get("apks", []):
                name_norm = _norm(a.get("name"))
                if not name_norm:
                    continue
                if any(c and c in name_norm for c in candidates):
                    resolved = _resolve_apk_url(a.get("apk_url") or "")
                    if resolved:
                        target_path = resolved
                        break

    if target_path is None or not target_path.exists():
        return JSONResponse(
            {"detail": f"system dependency {name!r} not registered"},
            status_code=404,
        )
    # v2.10.53-c — Use the file's REAL extension so the downstream
    # client (Vesper's ApkmInstaller) reads the correct format hint.
    # The endpoint accepts both `.apk` (single APK) and `.apkm` /
    # `.xapk` / `.apks` (split-APK bundles).  Installer also
    # double-checks by inspecting ZIP contents on the device.
    actual_ext = target_path.suffix.lower() or ".apkm"
    download_name = f"{name}{actual_ext}"
    return FileResponse(
        target_path,
        media_type="application/vnd.android.package-archive",
        filename=download_name,
    )


@app.post(
    "/api/admin/system-deps/{name}",
    dependencies=[Depends(require_admin)],
)
def set_system_dep(name: str, body: dict) -> dict:
    """Pin an existing `apks` entry as the canonical bundle for a
    named system dependency (e.g. `webview-138`).  Body shape:
        { "apk_id": "abc123def456" }
    """
    aid = (body or {}).get("apk_id")
    if not aid:
        raise HTTPException(400, "apk_id required")
    store = _load_store()
    entry = next((a for a in store.get("apks", []) if a["id"] == aid), None)
    if not entry:
        raise HTTPException(404, f"apk_id {aid!r} not found")
    store.setdefault("system_deps", {})[name] = {
        "apk_id":   aid,
        "apk_url":  entry.get("apk_url"),
        "version_name": entry.get("version_name"),
        "updated_at":   now_ts(),
    }
    _save_store(store)
    return {"ok": True, "dep": store["system_deps"][name]}


# ════════════════════════════════════════════════════════════════════
#  Home Update — operator-managed launcher self-update
#
#  v2.10.55 — Operator wants a one-click way to ship a NEW launcher
#  APK to every client box in the field WITHOUT touching the boxes.
#
#  Flow:
#    1. Operator drops the freshly-built launcher APK into the admin
#       UI's "Home Update" section.  Backend stashes it at
#       `DATA_DIR/system-deps/home-update.apk` and records the
#       inspected metadata (versionCode, versionName, sha256) in
#       `store.json → home_update`.
#    2. TV clients call GET /api/launcher/home-update/info on demand
#       (e.g. when the user taps the "Home Update" pill in the App
#       Store) to see if an update is available + what version.
#    3. Clients download GET /api/system-deps/home-update.apk and
#       stream it into Android's PackageInstaller.  Because every
#       repo build is signed with the same committed keystore, the
#       new APK installs as an IN-PLACE UPGRADE — no uninstall, no
#       data wipe, no parsing error.
#
#  Endpoints:
#    POST   /api/admin/home-update/upload     — admin uploads APK
#    GET    /api/admin/home-update/status     — admin sees pinned ver
#    DELETE /api/admin/home-update            — admin clears pin
#    GET    /api/launcher/home-update/info    — public, what's pinned
#    GET    /api/system-deps/home-update.apk  — public, the binary
# ════════════════════════════════════════════════════════════════════

HOME_UPDATE_FILENAME = "home-update.apk"


def _home_update_path() -> Path:
    return DATA_DIR / "system-deps" / HOME_UPDATE_FILENAME


def _home_update_meta(store: dict | None = None) -> dict:
    s = store if store is not None else _load_store()
    return (s.get("home_update") or {}).copy()


@app.post(
    "/api/admin/home-update/upload",
    dependencies=[Depends(require_admin)],
)
async def upload_home_update(file: UploadFile = File(...)) -> dict:
    """Receive the freshly-built launcher APK, store it on disk, and
    record its metadata in `store.json`.  The TV clients can then
    download it via `/api/system-deps/home-update.apk`.
    """
    if not file.filename or not file.filename.lower().endswith(".apk"):
        raise HTTPException(400, "expected a `.apk` file (not split)")
    target = _home_update_path()
    target.parent.mkdir(parents=True, exist_ok=True)

    # v2.10.55 — Stream upload to disk first, then inspect.  The
    # operator's launcher APK is ~30–50 MB so this is faster than
    # reading the whole thing into memory.
    h = hashlib.sha256()
    size = 0
    async with aiofiles.open(target, "wb") as f:
        while chunk := await file.read(1024 * 1024):
            h.update(chunk)
            size += len(chunk)
            await f.write(chunk)

    # Best-effort metadata extraction — we still record SOMETHING
    # even if pyaxmlparser fails (rare, but APK metadata isn't a
    # blocker for serving the file).
    pkg_id: Optional[str] = None
    version_name: Optional[str] = None
    version_code: Optional[int] = None
    try:
        from apk_meta import inspect_apk
        meta = await asyncio.to_thread(
            inspect_apk, target, DATA_DIR / "apk_icons",
            "home_update_icon",
        )
        pkg_id = meta.get("package_id")
        version_name = meta.get("version_name")
        version_code = meta.get("version_code")
    except Exception as e:
        # Log but don't fail — operator can still ship the binary.
        print(f"[home-update] metadata extraction failed: {e}")

    store = _load_store()
    store["home_update"] = {
        "filename":     HOME_UPDATE_FILENAME,
        "size":         size,
        "sha256":       h.hexdigest(),
        "package_id":   pkg_id,
        "version_name": version_name,
        "version_code": version_code,
        # v2.10.81 — Stamp a fresh UUIDv4 hex on every upload so the
        # launcher's HOME UPDATE pill fires regardless of whether the
        # operator bumped versionCode/versionName.  The TV box stores
        # the last installed build_id in SharedPrefs; if the pinned
        # one differs we light up the pill.  This is the fix for
        # operator-reported "I re-uploaded the same version and the
        # pill never appeared on the box."
        "build_id":     uuid.uuid4().hex,
        "uploaded_at":  now_ts(),
    }
    _save_store(store)
    return {"ok": True, "home_update": store["home_update"]}


@app.get(
    "/api/admin/home-update/status",
    dependencies=[Depends(require_admin)],
)
def home_update_status() -> dict:
    """Returns the metadata for the current pinned home-update APK."""
    meta = _home_update_meta()
    if not meta:
        return {"pinned": False, "home_update": None}
    # Also surface whether the file is actually on disk (could have
    # been manually deleted, in which case the admin should re-upload).
    on_disk = _home_update_path().exists()
    return {"pinned": True, "on_disk": on_disk, "home_update": meta}


@app.delete(
    "/api/admin/home-update",
    dependencies=[Depends(require_admin)],
)
def home_update_clear() -> dict:
    """Clear the pinned home-update APK + remove the binary on disk.
    The TV "Home Update" pill will show "Up to date" after this."""
    target = _home_update_path()
    if target.exists():
        target.unlink(missing_ok=True)
    store = _load_store()
    store.pop("home_update", None)
    _save_store(store)
    return {"ok": True}


@app.get("/api/launcher/home-update/info")
def home_update_info(
    current_version_code: Optional[int] = None,
    current_build_id: Optional[str] = None,
    device_id: Optional[str] = None,
) -> dict:
    """Public — TV clients call this to see if an update is
    available.

    Update detection (v2.10.81) — TWO independent signals, either
    fires has_update=True:

      1. `current_version_code` < pinned `version_code` (legacy
         semver bump path).
      2. `current_build_id` != pinned `build_id` (NEW — fires on
         every fresh re-upload regardless of versionCode, so the
         operator can push a rebuild of the SAME version to every
         box and the HOME UPDATE pill still appears).

    A device that doesn't send either param defaults to
    has_update=True whenever an APK is pinned (lets the device
    decide).  Once a device starts sending `current_build_id`, the
    backend ONLY says has_update=True when there's an actual change
    to install — never on every poll.
    """
    meta = _home_update_meta()
    if not meta or not _home_update_path().exists():
        return {"has_update": False, "home_update": None}
    apk_url = f"{PUBLIC_BASE_URL}/api/system-deps/home-update.apk"
    pinned_vc = meta.get("version_code")
    pinned_build_id = meta.get("build_id")

    # Default: pinned APK exists → update available.  Refined below
    # when caller provides comparison hints.
    has_update = True

    # Refinement 1 — build_id mismatch is the authoritative signal.
    if current_build_id is not None and pinned_build_id is not None:
        has_update = (current_build_id != pinned_build_id)
    # Refinement 2 — fall back to version_code compare when build_id
    # isn't provided (older launcher builds).
    elif current_version_code is not None and pinned_vc is not None:
        has_update = int(pinned_vc) > int(current_version_code)
    return {
        "has_update":    has_update,
        "apk_url":       apk_url,
        "size":          meta.get("size"),
        "sha256":        meta.get("sha256"),
        "package_id":    meta.get("package_id"),
        "version_name":  meta.get("version_name"),
        "version_code":  meta.get("version_code"),
        "build_id":      pinned_build_id,
        "uploaded_at":   meta.get("uploaded_at"),
    }


@app.get("/api/system-deps/home-update.apk")
def get_home_update_apk():
    """Serve the pinned home-update APK as a binary download.
    Same Content-Disposition trick as the App Store /assets/apks/*
    so Android's DownloadManager / PackageInstaller can use the
    right extension."""
    from fastapi.responses import FileResponse
    target = _home_update_path()
    if not target.exists() or not target.is_file():
        raise HTTPException(404, "No home update pinned. Upload one in the admin UI.")
    return FileResponse(
        target,
        media_type="application/vnd.android.package-archive",
        filename=HOME_UPDATE_FILENAME,
    )


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


# ── v2.8.25 — V2 AI screen customisation ──────────────────────────
# Admin can swap the heading text shown above the waveform and
# upload a fullscreen background image for the voice-assistant
# Activity.  Both are surfaced via /api/launcher/config so the
# launcher reads them on the next ~30 s poll.

class V2AIConfigBody(BaseModel):
    heading_text: Optional[str] = None
    waveform_style: Optional[str] = None
    hold_button_visible: Optional[bool] = None
    # v2.8.38 — V2 AI top-bar pill color overrides.  8-char
    # "#AARRGGBB" hex strings; empty string clears the override.
    button_bg_color: Optional[str] = None
    button_text_color: Optional[str] = None
    button_focus_bg_color: Optional[str] = None
    button_focus_text_color: Optional[str] = None
    # v2.8.49 — V2 AI hero-pill sizing.  None means "no change"; an
    # integer updates the corresponding dimension on the launcher.
    button_height_dp: Optional[int] = None
    button_width_dp: Optional[int]  = None


_ALLOWED_WAVEFORM_STYLES = {
    "bars", "dots", "ring", "sweep", "pulse",
    # v2.8.31 — premium "Apple-feel" visualizers.
    "aurora", "orb", "particles", "neon", "prism",
}


@app.post("/api/admin/v2ai/config", dependencies=[Depends(require_admin)])
def set_v2ai_config(body: V2AIConfigBody) -> dict:
    store = _load_store()
    v2ai = store.setdefault("v2ai", {})
    if body.heading_text is not None:
        text = body.heading_text.strip()
        v2ai["heading_text"] = text or None
    if body.waveform_style is not None:
        wf = body.waveform_style.strip().lower()
        if wf and wf not in _ALLOWED_WAVEFORM_STYLES:
            raise HTTPException(400, f"waveform_style must be one of {sorted(_ALLOWED_WAVEFORM_STYLES)}")
        v2ai["waveform_style"] = wf or None
    if body.hold_button_visible is not None:
        v2ai["hold_button_visible"] = bool(body.hold_button_visible)
    # v2.8.38 — Per-V2-AI pill color updates.  Empty string clears
    # the override so the pill falls back to the shared top-bar
    # palette set on the App Store tab.
    for field in (
        "button_bg_color", "button_text_color",
        "button_focus_bg_color", "button_focus_text_color",
    ):
        val = getattr(body, field)
        if val is None:
            continue
        v = val.strip()
        if v and not re.fullmatch(r"#[A-Fa-f0-9]{6,8}", v):
            raise HTTPException(400, f"{field} must be a hex color (#RRGGBB or #AARRGGBB)")
        v2ai[field] = v or None
    # v2.8.49 — pill height / width updates.  Clamp into a sane
    # range so a typo can't render the pill invisible.
    if body.button_height_dp is not None:
        if not (32 <= body.button_height_dp <= 200):
            raise HTTPException(400, "button_height_dp must be between 32 and 200")
        v2ai["button_height_dp"] = body.button_height_dp
    if body.button_width_dp is not None:
        if body.button_width_dp != 0 and not (60 <= body.button_width_dp <= 600):
            raise HTTPException(400, "button_width_dp must be 0 (auto) or between 60 and 600")
        v2ai["button_width_dp"] = body.button_width_dp
    _save_store(store)
    return {"ok": True, "v2ai": v2ai}


@app.post("/api/admin/v2ai/background", dependencies=[Depends(require_admin)])
async def upload_v2ai_background(file: UploadFile = File(...)) -> dict:
    """Drag-drop a fullscreen background painted behind the V2 AI
    voice-assistant Activity.  Auto-fits to 1920×1080 via centre-crop
    so any aspect uploads cleanly."""
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "v2ai" / "background.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        img = ImageOps.fit(img, (1920, 1080), Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    v2ai = store.setdefault("v2ai", {})
    v2ai["background_image_url"] = f"/assets/v2ai/background.png?ts={now_ts()}"
    _save_store(store)
    return {
        "ok": True,
        "background_image_url": _abs(v2ai["background_image_url"]),
    }


@app.delete("/api/admin/v2ai/background", dependencies=[Depends(require_admin)])
def clear_v2ai_background() -> dict:
    """Remove the current V2 AI screen background image."""
    out_path = DATA_DIR / "v2ai" / "background.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("v2ai", {})["background_image_url"] = None
    _save_store(store)
    return {"ok": True}


# ── v2.8.26 — V2 AI top-bar button icon ────────────────────────────
# Admin uploads a square PNG that replaces the default lightning-bolt
# SVG drawn on the V2 AI pill in the launcher top bar.  Auto-scales
# to 96×96 for crisp rendering at every density.
@app.post("/api/admin/v2ai/button", dependencies=[Depends(require_admin)])
async def upload_v2ai_button(file: UploadFile = File(...)) -> dict:
    """Admin uploads a custom image to use as the V2 AI top-bar pill.
    The image becomes the ENTIRE pill (replaces background + icon +
    text) so we need enough resolution to look sharp at the largest
    pill size the admin might pick (button_width_dp up to 600).
    """
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "v2ai" / "button.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        # v2.8.50 — Was capped at 96×96 (one-line `ImageOps.contain`)
        # which made the pill look pixelated whenever the admin used
        # a width >96 dp.  Bumped to 2048 × 1024 — preserves any
        # mainstream-uploaded wordmark / logo at 2-3× the largest
        # supported pill size.  Aspect ratio is preserved.  PNG-9
        # compression keeps the file ~50-200 KiB for a typical
        # transparent wordmark.
        img = ImageOps.contain(img, (2048, 1024), Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    v2ai = store.setdefault("v2ai", {})
    v2ai["button_image_url"] = f"/assets/v2ai/button.png?ts={now_ts()}"
    _save_store(store)
    return {"ok": True, "button_image_url": _abs(v2ai["button_image_url"])}


@app.delete("/api/admin/v2ai/button", dependencies=[Depends(require_admin)])
def clear_v2ai_button() -> dict:
    out_path = DATA_DIR / "v2ai" / "button.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("v2ai", {})["button_image_url"] = None
    _save_store(store)
    return {"ok": True}


# ── v2.8.30 — V2 AI in-activity HOLD button ────────────────────────
# A second, larger image painted on the V2 AI screen itself
# (centered, between waveform + status line).  Tappable to start
# recording on touch devices; also acts as a visual focal point on
# TV remotes.  Separate from the top-bar pill icon — that one stays
# small and lives in the launcher home top bar.
@app.post("/api/admin/v2ai/hold-button", dependencies=[Depends(require_admin)])
async def upload_v2ai_hold_button(file: UploadFile = File(...)) -> dict:
    from PIL import Image, ImageOps
    import io
    raw = await file.read()
    if not raw:
        raise HTTPException(400, "empty file")
    out_path = DATA_DIR / "v2ai" / "hold-button.png"
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGBA")
        # 256×256 — big enough for any TV; keeps aspect via contain.
        img = ImageOps.contain(img, (256, 256), Image.LANCZOS)
        img.save(out_path, format="PNG", optimize=True)
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(400, f"could not decode image: {exc}")
    store = _load_store()
    v2ai = store.setdefault("v2ai", {})
    v2ai["hold_button_image_url"] = f"/assets/v2ai/hold-button.png?ts={now_ts()}"
    _save_store(store)
    return {"ok": True, "hold_button_image_url": _abs(v2ai["hold_button_image_url"])}


@app.delete("/api/admin/v2ai/hold-button", dependencies=[Depends(require_admin)])
def clear_v2ai_hold_button() -> dict:
    out_path = DATA_DIR / "v2ai" / "hold-button.png"
    try:
        out_path.unlink()
    except FileNotFoundError:
        pass
    store = _load_store()
    store.setdefault("v2ai", {})["hold_button_image_url"] = None
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
    topbar_btn_bg_color:           Optional[str] = None
    topbar_btn_text_color:         Optional[str] = None
    topbar_btn_focus_bg_color:     Optional[str] = None
    topbar_btn_focus_text_color:   Optional[str] = None


@app.post("/api/admin/appstore/topbar-colors", dependencies=[Depends(require_admin)])
def update_topbar_colors(body: TopbarColorsBody) -> dict:
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["topbar_btn_bg_color"]         = _normalize_color(body.topbar_btn_bg_color)
    appstore["topbar_btn_text_color"]       = _normalize_color(body.topbar_btn_text_color)
    appstore["topbar_btn_focus_bg_color"]   = _normalize_color(body.topbar_btn_focus_bg_color)
    appstore["topbar_btn_focus_text_color"] = _normalize_color(body.topbar_btn_focus_text_color)
    _save_store(store)
    return {
        "ok": True,
        "topbar_btn_bg_color":         appstore["topbar_btn_bg_color"],
        "topbar_btn_text_color":       appstore["topbar_btn_text_color"],
        "topbar_btn_focus_bg_color":   appstore["topbar_btn_focus_bg_color"],
        "topbar_btn_focus_text_color": appstore["topbar_btn_focus_text_color"],
    }


# ── v2.8.22 — Speed Test pill → launchable APK package ────────────
class SpeedTestTargetBody(BaseModel):
    speed_test_package: Optional[str] = None


@app.post("/api/admin/appstore/speed-test-target", dependencies=[Depends(require_admin)])
def update_speed_test_target(body: SpeedTestTargetBody) -> dict:
    """Set the Android package name the Speed Test top-bar pill
    should open (e.g. `org.zwanoo.android.speedtest` for Ookla).
    Empty string / None falls back to the system VPN settings page
    so the pill still does something on factory installs."""
    pkg = (body.speed_test_package or "").strip() or None
    store = _load_store()
    appstore = store.setdefault("appstore", {})
    appstore["speed_test_package"] = pkg
    _save_store(store)
    return {"ok": True, "speed_test_package": pkg}


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


# ── v2.8.24 — QR Videos ───────────────────────────────────────────
# Admin pastes a Google Drive / Dropbox / direct-HTTP video URL.
# We generate a QR PNG encoding that URL.  Each entry can be flagged
# visible (shown on the launcher home as a tile) or hidden (kept on
# the backend for future use without polluting the home screen).

class QrVideoIn(BaseModel):
    name: str
    url: str
    caption: Optional[str] = None
    visible: bool = True


class QrVideoUpdate(BaseModel):
    name: Optional[str]    = None
    url: Optional[str]     = None
    caption: Optional[str] = None
    visible: Optional[bool] = None


def _generate_qr_png(out_path: Path, payload: str) -> None:
    """Write a 512×512 QR PNG encoding `payload`."""
    import qrcode
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=10,
        border=2,
    )
    qr.add_data(payload)
    qr.make(fit=True)
    img = qr.make_image(fill_color="#04060B", back_color="#FFFFFF")
    img = img.convert("RGBA").resize((512, 512))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(out_path, format="PNG", optimize=True)


def _player_url_for(qid: str) -> str:
    """Absolute URL of the mobile inline-player page that the QR
    encodes.  We never encode the raw video URL — that way the admin
    can rotate / fix the underlying Google Drive / Dropbox link
    without reprinting the QR code."""
    return f"{PUBLIC_BASE_URL}/qr-play/{qid}"


@app.get("/api/admin/qr-videos", dependencies=[Depends(require_admin)])
def list_qr_videos() -> dict:
    store = _load_store()
    out = []
    for v in store.get("qr_videos", []):
        out.append({
            **v,
            "qr_image_url": _abs(v.get("qr_image_url")),
            "player_url":   _abs(v.get("player_url")) or _player_url_for(v["id"]),
        })
    return {"data": out}


@app.post("/api/admin/qr-videos", dependencies=[Depends(require_admin)])
def create_qr_video(body: QrVideoIn) -> dict:
    name = body.name.strip()
    url = body.url.strip()
    caption = (body.caption or "").strip()
    if not name:
        raise HTTPException(400, "name required")
    if not url:
        raise HTTPException(400, "url required")
    if not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(400, "url must start with http:// or https://")
    qid = uuid.uuid4().hex[:12]
    player_url = _player_url_for(qid)
    out_path = DATA_DIR / "qr" / f"{qid}.png"
    _generate_qr_png(out_path, player_url)
    entry = {
        "id":            qid,
        "name":          name,
        "url":           url,
        "caption":       caption or None,
        "visible":       bool(body.visible),
        "qr_image_url":  f"/assets/qr/{qid}.png?ts={now_ts()}",
        "player_url":    player_url,
        "created_at":    now_ts(),
    }
    store = _load_store()
    store.setdefault("qr_videos", []).insert(0, entry)
    _save_store(store)
    # Return absolute URLs to the admin UI for instant rendering.
    return {
        "ok": True,
        "entry": {
            **entry,
            "qr_image_url": _abs(entry["qr_image_url"]),
            "player_url":   _abs(entry["player_url"]),
        },
    }


@app.patch("/api/admin/qr-videos/{qid}", dependencies=[Depends(require_admin)])
def update_qr_video(qid: str, body: QrVideoUpdate) -> dict:
    store = _load_store()
    videos = store.setdefault("qr_videos", [])
    entry = next((v for v in videos if v["id"] == qid), None)
    if not entry:
        raise HTTPException(404, "not found")
    # Ensure player_url + caption fields exist on legacy rows.
    entry.setdefault("caption", None)
    entry.setdefault("player_url", _player_url_for(qid))
    if body.name is not None:
        entry["name"] = body.name.strip() or entry["name"]
    if body.caption is not None:
        c = body.caption.strip()
        entry["caption"] = c or None
    if body.visible is not None:
        entry["visible"] = bool(body.visible)
    if body.url is not None:
        new_url = body.url.strip()
        if new_url and new_url != entry["url"]:
            if not (new_url.startswith("http://") or new_url.startswith("https://")):
                raise HTTPException(400, "url must start with http:// or https://")
            entry["url"] = new_url
            # The QR still encodes /qr-play/<id> — no need to regen
            # the PNG.  Only regenerate if the player_url is somehow
            # missing or stale (defensive).
            if not entry.get("player_url"):
                entry["player_url"] = _player_url_for(qid)
                _generate_qr_png(DATA_DIR / "qr" / f"{qid}.png", entry["player_url"])
                entry["qr_image_url"] = f"/assets/qr/{qid}.png?ts={now_ts()}"
    _save_store(store)
    return {
        "ok": True,
        "entry": {
            **entry,
            "qr_image_url": _abs(entry["qr_image_url"]),
            "player_url":   _abs(entry["player_url"]),
        },
    }


@app.delete("/api/admin/qr-videos/{qid}", dependencies=[Depends(require_admin)])
def delete_qr_video(qid: str) -> dict:
    store = _load_store()
    videos = store.setdefault("qr_videos", [])
    before = len(videos)
    store["qr_videos"] = [v for v in videos if v["id"] != qid]
    if len(store["qr_videos"]) == before:
        raise HTTPException(404, "not found")
    try:
        (DATA_DIR / "qr" / f"{qid}.png").unlink()
    except FileNotFoundError:
        pass
    _save_store(store)
    return {"ok": True}


# ── Public: inline player page (where the QR points) ───────────────
# Mobile-first HTML that auto-detects the URL kind and renders the
# appropriate player:
#   • Google Drive  →  https://drive.google.com/file/d/<id>/preview iframe
#   • Dropbox       →  rewrite ?dl=0 → ?raw=1 for inline <video>
#   • Direct video  →  HTML5 <video autoplay playsinline>
#   • Anything else →  open the URL directly (fallback link)
def _to_inline_url(raw: str) -> tuple[str, str]:
    """Return (kind, embed_url) where kind ∈ {iframe, video, link}."""
    u = (raw or "").strip()
    if not u:
        return ("link", "")
    # Google Drive share link
    m = re.search(r"drive\.google\.com/file/d/([A-Za-z0-9_-]+)", u)
    if m:
        return ("iframe", f"https://drive.google.com/file/d/{m.group(1)}/preview")
    m = re.search(r"drive\.google\.com/.*[?&]id=([A-Za-z0-9_-]+)", u)
    if m:
        return ("iframe", f"https://drive.google.com/file/d/{m.group(1)}/preview")
    # Dropbox share link — force raw=1 for inline playback
    if "dropbox.com" in u:
        if "dl=0" in u:
            u = u.replace("dl=0", "raw=1")
        elif "raw=" not in u and "dl=" not in u:
            sep = "&" if "?" in u else "?"
            u = f"{u}{sep}raw=1"
        return ("video", u)
    # YouTube — let it embed as an iframe (rare, but works)
    m = re.search(r"(?:youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]+)", u)
    if m:
        return ("iframe", f"https://www.youtube.com/embed/{m.group(1)}?autoplay=1&playsinline=1")
    # Direct video — match common extensions
    if re.search(r"\.(mp4|m4v|mov|webm|ogg|mkv)(\?|$)", u, re.I):
        return ("video", u)
    # Fallback — open the URL in the browser
    return ("link", u)


@app.get("/qr-play/{qid}", response_class=HTMLResponse)
def qr_play_page(qid: str) -> HTMLResponse:
    store = _load_store()
    entry = next(
        (v for v in store.get("qr_videos", []) if v["id"] == qid),
        None,
    )
    if not entry:
        return HTMLResponse(
            "<h1 style='font-family:system-ui;padding:48px;'>Video not found</h1>",
            status_code=404,
        )
    name    = entry.get("name", "Video")
    caption = entry.get("caption") or ""
    kind, embed = _to_inline_url(entry.get("url", ""))
    # HTML-escape user-supplied text fields.
    def esc(s: str) -> str:
        return (
            s.replace("&", "&amp;")
             .replace("<", "&lt;")
             .replace(">", "&gt;")
             .replace('"', "&quot;")
             .replace("'", "&#39;")
        )
    name_e    = esc(name)
    caption_e = esc(caption)
    embed_e   = esc(embed)
    if kind == "iframe":
        player_html = (
            f'<iframe src="{embed_e}" allow="autoplay; fullscreen; encrypted-media" '
            f'allowfullscreen playsinline></iframe>'
        )
    elif kind == "video":
        player_html = (
            f'<video src="{embed_e}" controls autoplay playsinline preload="auto"></video>'
        )
    else:
        player_html = (
            f'<a class="open-link" href="{embed_e}" target="_blank" rel="noopener">'
            f'Open video ↗</a>'
        )
    return HTMLResponse(f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>{name_e}</title>
<style>
  *,*::before,*::after {{ box-sizing: border-box; }}
  html, body {{ margin:0; padding:0; height:100%; background:#04060B; color:#F4F7FB;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }}
  body {{ display:flex; flex-direction:column; min-height:100dvh; }}
  header {{ padding: 18px 20px 10px; }}
  .eyebrow {{ font-size: 10px; letter-spacing:0.22em; text-transform:uppercase;
              color:#2BB6FF; font-weight:700; }}
  h1 {{ margin:6px 0 0; font-size:22px; font-weight:700; letter-spacing:-0.01em; }}
  .caption {{ margin: 6px 0 0; font-size:14px; color:#A0AEC0; line-height:1.5; }}
  .stage {{ flex:1; display:flex; align-items:center; justify-content:center;
            padding: 14px 12px 24px; }}
  .frame {{ position:relative; width:100%; max-width:560px; aspect-ratio: 16/9;
            background:#000; border-radius:14px; overflow:hidden;
            box-shadow: 0 20px 60px rgba(0,0,0,0.5); }}
  .frame video, .frame iframe {{ position:absolute; inset:0; width:100%; height:100%;
                                 border:0; background:#000; object-fit: contain; }}
  .open-link {{ display:flex; align-items:center; justify-content:center;
                width:100%; height:100%; color:#2BB6FF; font-weight:700;
                font-size:18px; text-decoration:none; background:#0A1224; }}
  footer {{ padding: 10px 20px 22px; font-size:11px; color:#5C6B82; text-align:center;
            letter-spacing:0.06em; }}
  footer strong {{ color:#A0AEC0; font-weight:600; }}
</style>
</head>
<body>
  <header>
    <div class="eyebrow">ON NOW TV V2 · scan to watch</div>
    <h1>{name_e}</h1>
    {f'<p class="caption">{caption_e}</p>' if caption_e else ''}
  </header>
  <div class="stage">
    <div class="frame">{player_html}</div>
  </div>
  <footer>Played from <strong>ON NOW TV V2</strong></footer>
</body>
</html>""")


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
def admin_root_redirect():
    """v2.8.40 — Redirect /admin → /admin/ so the trailing slash
    makes relative asset paths (`static/style.css`) resolve cleanly
    both locally AND behind a reverse-proxy sub-path."""
    return RedirectResponse(url="admin/", status_code=307)


@app.get("/admin/", response_class=HTMLResponse)
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
