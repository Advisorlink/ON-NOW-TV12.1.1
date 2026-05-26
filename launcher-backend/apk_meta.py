"""
APK introspection helper for the launcher backend.
v1.9 — added Feb 2026.

When the admin drags an APK into the App Store tab we want to
auto-fill the package name, version, app label, and icon — without
making them retype anything.  `pyaxmlparser` reads everything out of
the APK's compiled `AndroidManifest.xml` + resource table; we then
crop/resize the icon to a clean 256×256 PNG so the admin grid + the
launcher Apps screen render consistently.

Functions are intentionally synchronous (called from a thread pool
via `asyncio.to_thread`) — `pyaxmlparser` itself is blocking I/O.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Optional, TypedDict

from PIL import Image
from pyaxmlparser import APK

log = logging.getLogger("launcher-api.apk_meta")

# Silence pyaxmlparser's chatty warnings about resolved resources.
logging.getLogger("pyaxmlparser").setLevel(logging.ERROR)
logging.getLogger("pyaxmlparser.core").setLevel(logging.ERROR)


class ApkInspection(TypedDict, total=False):
    """All fields are optional — APKs with stripped manifests may
    return only a subset. Callers should fall back to user-provided
    values when a field is missing or empty."""

    package_id:   Optional[str]
    version_name: Optional[str]
    version_code: Optional[int]
    app_name:     Optional[str]
    icon_path:    Optional[str]  # path on disk to the saved 256px PNG
    icon_bytes:   Optional[int]  # raw size of extracted icon


def inspect_apk(apk_path: Path, icon_out_dir: Path, icon_id: str) -> ApkInspection:
    """Open `apk_path`, extract identity + icon, write a 256×256 PNG
    to `icon_out_dir/{icon_id}.png` and return everything we found.

    Soft failure: if a field can't be read the dict simply omits it
    (the API endpoint above stitches in user-supplied fallbacks).
    """
    result: ApkInspection = {}
    try:
        apk = APK(str(apk_path))
    except Exception as exc:  # noqa: BLE001
        log.warning("pyaxmlparser failed to open %s: %s", apk_path, exc)
        return result

    try:
        if apk.package:
            result["package_id"] = apk.package
    except Exception:  # noqa: BLE001
        pass
    try:
        if apk.version_name:
            result["version_name"] = apk.version_name
    except Exception:  # noqa: BLE001
        pass
    try:
        if apk.version_code:
            result["version_code"] = int(apk.version_code)
    except Exception:  # noqa: BLE001
        pass
    try:
        # apk.application = the resolved <application android:label> value.
        # Falls back to package if no label is found.
        label = apk.application
        if label and label != apk.package:
            result["app_name"] = label
    except Exception:  # noqa: BLE001
        pass

    # ── Icon: prefer the largest density we can find, resize to 256. ──
    try:
        raw = apk.icon_data
        if raw:
            icon_out_dir.mkdir(parents=True, exist_ok=True)
            out_path = icon_out_dir / f"{icon_id}.png"
            try:
                # apk.icon_data may be either PNG bytes (most common) or
                # raw WebP/JPEG.  Pillow handles all three transparently.
                import io
                img = Image.open(io.BytesIO(raw)).convert("RGBA")
                # Resize down — keep aspect ratio + alpha.  256px is
                # the sweet spot for both the admin grid (96px display)
                # and the launcher Apps screen (160px display) without
                # being needlessly heavy.
                img.thumbnail((256, 256), Image.LANCZOS)
                img.save(out_path, format="PNG", optimize=True)
                result["icon_path"]  = str(out_path)
                result["icon_bytes"] = out_path.stat().st_size
            except Exception as exc:  # noqa: BLE001
                # If Pillow can't decode the bytes, save them verbatim
                # — better than nothing.
                out_path.write_bytes(raw)
                result["icon_path"]  = str(out_path)
                result["icon_bytes"] = len(raw)
                log.warning("icon resize failed for %s: %s", apk_path, exc)
    except Exception as exc:  # noqa: BLE001
        log.warning("icon extraction failed for %s: %s", apk_path, exc)

    return result
