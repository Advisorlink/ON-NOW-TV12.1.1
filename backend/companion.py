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

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from instant_bundle import _state as _bundle_state

log = logging.getLogger("companion")

router = APIRouter(prefix="/api/companion", tags=["companion"])

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
