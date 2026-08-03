"""Companion-app data endpoints (v2.18.0).

The phone Companion web app (served by the launcher backend at
/remote) needs a LIGHTWEIGHT Live TV channel list — the full
/api/xtream/instant-bundle is ~12 MB with 72 h of EPG, far too heavy
for a phone browse tab.  This router serves a slim projection of the
same in-memory bundle instant_bundle.py already maintains:
channels (id / name / logo / category) + categories only.

Per-channel now/next EPG stays on the existing
/api/xtream/epg/{stream_id} endpoint.
"""

from __future__ import annotations

import gzip
import json
import logging
import re

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from instant_bundle import _state as _bundle_state

log = logging.getLogger("companion")

router = APIRouter(prefix="/api/companion", tags=["companion"])

# Bound at boot from server.py (same pattern as vesper_sync).
_db = None


def configure_companion(db) -> None:
    global _db
    _db = db

# Cache the gzipped slim payload per bundle generation.
_slim_cache: dict = {"key": None, "gz": None}


@router.get("/livetv/channels")
async def companion_livetv_channels() -> Response:
    channels = _bundle_state.get("channels") or []
    if not channels:
        raise HTTPException(503, "Channel bundle not ready yet — try again shortly.")
    key = _bundle_state.get("channels_fetched_at") or 0
    if _slim_cache["key"] != key or _slim_cache["gz"] is None:
        slim = {
            "categories": _bundle_state.get("categories") or [],
            "channels": [
                {
                    "stream_id": str(c.get("stream_id") or ""),
                    "name": c.get("name") or "",
                    "logo": c.get("logo") or "",
                    "category_id": str(c.get("category_id") or ""),
                }
                for c in channels
            ],
            "generated_at": key,
        }
        _slim_cache["gz"] = gzip.compress(
            json.dumps(slim, separators=(",", ":")).encode("utf-8"), 6
        )
        _slim_cache["key"] = key
    return Response(
        content=_slim_cache["gz"],
        media_type="application/json",
        headers={
            "Content-Encoding": "gzip",
            "Cache-Control": "public, max-age=300",
        },
    )


@router.get("/vesper/profiles")
async def companion_vesper_profiles(u: str = "") -> dict:
    """Profile names on a Vesper account — powers the Companion app's
    settings profile picker.  Reads the account's silent cloud-sync
    snapshot (`vesper_sync`), which mirrors the TV's localStorage
    (`onnowtv-profiles-v1*` keys)."""
    u = (u or "").strip()
    if not u:
        raise HTTPException(400, "missing_username")
    if _db is None:
        raise HTTPException(503, "profiles store not ready")
    doc = await _db.vesper_sync.find_one(
        {"username": {"$regex": f"^{re.escape(u)}$", "$options": "i"}},
        {"_id": 0, "data": 1},
    )
    if not doc:
        return {"found": False, "profiles": []}
    names: list = []
    seen: set = set()
    for key, raw in (doc.get("data") or {}).items():
        if not str(key).startswith("onnowtv-profiles-v1"):
            continue
        try:
            arr = json.loads(raw) if isinstance(raw, str) else raw
        except Exception:
            continue
        if not isinstance(arr, list):
            continue
        for p in arr:
            if not isinstance(p, dict):
                continue
            name = str(p.get("name") or "").strip()
            pid = str(p.get("id") or name)
            if name and pid not in seen:
                seen.add(pid)
                names.append(name)
    return {"found": True, "profiles": names}
