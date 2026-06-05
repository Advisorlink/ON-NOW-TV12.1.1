"""
Library — AI-generated cover-art endpoints for the native Live TV
client.

The Android side asks for a 16:9 cover image for a given category
name (e.g. "Sports", "Sky Cinema HD") and we either:

  1. **Generate** a fresh one via Gemini Nano Banana
     (`gemini-3.1-flash-image-preview`) using a tightly-tuned prompt
     so every cover sits in the same visual family — dark navy
     gradient, cyan/blue neon edge-glow, photorealistic editorial
     thumbnail style, NO text — and persist it in Mongo keyed by a
     deterministic hash, OR
  2. **Serve** the previously-generated PNG bytes for a given hash so
     the client can re-download on reinstall.

Endpoints (all under the standard ``/api`` umbrella so the
Kubernetes ingress routes them to the backend pod):

  • POST /api/library/generate-cover  { name, style? }  →  { hash, url, mime, b64 }
  • GET  /api/library/cover/{hash}.png                  →  image/png bytes

The model returns base64 directly; the binary is decoded once on the
server, stored in Mongo, and served as `image/png` on demand.
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import logging
import os
from datetime import datetime, timezone
from typing import Optional

from dotenv import load_dotenv
from fastapi import APIRouter, HTTPException, Response
from motor.motor_asyncio import AsyncIOMotorClient
from pydantic import BaseModel, Field

load_dotenv()

log = logging.getLogger("library")

router = APIRouter(prefix="/api/library")

# ---------------------------------------------------------------------------
# Mongo — reuse the existing connection string.
# ---------------------------------------------------------------------------
_MONGO_URL = os.environ["MONGO_URL"]
_DB_NAME = os.environ["DB_NAME"]
_client = AsyncIOMotorClient(_MONGO_URL)
_col = _client[_DB_NAME]["library_covers"]


async def _ensure_indexes() -> None:
    try:
        await _col.create_index("hash", unique=True)
    except Exception:  # pragma: no cover — idempotent
        pass


# ---------------------------------------------------------------------------
# Prompt template — produces realistic, professional editorial
# *promo banners* for each category.  The previous "dark navy + cyan
# neon" lock made every cover look like an Apple TV mood board; the
# user wants legit-feeling broadcaster banners (Sky Sports UK with
# its actual logo + sports imagery, ESPN with a gridiron player,
# Sky Cinema with cinema spotlights, etc.).  We DO allow Gemini to
# render text + logos when the category name suggests a real brand
# — that's the whole point of "Sky Sports KO" → it should *look*
# like a Sky Sports KO banner.
# ---------------------------------------------------------------------------
_BASE_STYLE = (
    "ultra-realistic 16:9 widescreen promotional banner, "
    "professional editorial advertisement style, cinematic photography "
    "or high-end digital illustration, dramatic lighting, rich saturated "
    "colours, sharp focus, magazine-cover production value"
)


def _build_prompt(name: str, style: Optional[str]) -> str:
    cleaned = (name or "Channel").strip()
    base = (
        f"Create a {style or _BASE_STYLE} hero banner for the TV / "
        f"streaming category \"{cleaned}\".\n\n"
        f"The image MUST visually represent what \"{cleaned}\" is actually "
        f"about — e.g. a sports category should show real athletes mid-"
        f"action; a movie category a cinema reel or red-carpet imagery; "
        f"a documentary channel a striking nature / cultural scene; a "
        f"kids channel bright cartoon energy.  When the name suggests a "
        f"real broadcaster (e.g. Sky Sports, ESPN, Fox, BBC) include the "
        f"recognisable logo typography on the image — render the name "
        f"itself as bold elegant on-screen lettering, integrated like a "
        f"proper broadcast brand banner.\n\n"
        f"Style: looks like a real ad you would see on a streaming service "
        f"home shelf or on a billboard — NOT a stock photo, NOT a generic "
        f"abstract gradient, NOT a neon collage.  Polished, professional, "
        f"ready-to-ship cover art."
    )
    return base


def _hash_for(name: str, style: Optional[str], salt: str = "") -> str:
    """Deterministic hash so we can dedupe within a (name, style, salt)
    triple.  The salt is what lets the client request a *new* variant
    of the same category without colliding with the previous cover."""
    h = hashlib.sha256()
    h.update((name or "").strip().lower().encode("utf-8"))
    h.update(b"|")
    h.update((style or _BASE_STYLE).encode("utf-8"))
    h.update(b"|")
    h.update(salt.encode("utf-8"))
    return h.hexdigest()[:24]


# ---------------------------------------------------------------------------
# Models
# ---------------------------------------------------------------------------
class GenerateRequest(BaseModel):
    name: str = Field(..., max_length=200)
    style: Optional[str] = None
    # Caller-supplied salt — pass a fresh nonce (e.g. the current
    # timestamp) to bypass the "same hash → cached image" path and
    # force a brand new generation.  Used by the "Regenerate cover"
    # long-press in the LibraryActivity.
    salt: Optional[str] = None


class GenerateResponse(BaseModel):
    hash: str
    url: str
    mime: str
    b64: str


# ---------------------------------------------------------------------------
# POST /api/library/generate-cover
# ---------------------------------------------------------------------------
@router.post("/generate-cover", response_model=GenerateResponse)
async def generate_cover(req: GenerateRequest) -> GenerateResponse:
    await _ensure_indexes()

    chash = _hash_for(req.name, req.style, req.salt or "")

    # Hit cache first (only when no salt — salted requests are
    # explicit regen requests so we always run Nano Banana).
    if not req.salt:
        cached = await _col.find_one({"hash": chash})
        if cached:
            return GenerateResponse(
                hash=chash,
                url=f"/api/library/cover/{chash}.png",
                mime=cached.get("mime", "image/png"),
                b64=cached["b64"],
            )

    api_key = os.environ.get("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(status_code=500, detail="EMERGENT_LLM_KEY not configured")

    try:
        from emergentintegrations.llm.chat import LlmChat, UserMessage
    except Exception as exc:  # pragma: no cover
        log.exception("emergentintegrations import failed")
        raise HTTPException(status_code=500, detail="emergentintegrations missing") from exc

    chat = (
        LlmChat(
            api_key=api_key,
            session_id=f"library-cover-{chash}",
            system_message=(
                "You are a senior visual designer producing realistic, "
                "professional 16:9 promotional banner art for a premium "
                "TV / streaming app.  Each cover should look like the "
                "real broadcaster's hero banner — include logo typography "
                "when the name suggests a known brand, and depict real "
                "subject matter (athletes, film stars, presenters etc.) "
                "rather than abstract gradients or generic neon collages."
            ),
        )
        .with_model("gemini", "gemini-3.1-flash-image-preview")
        .with_params(modalities=["image", "text"])
    )

    prompt = _build_prompt(req.name, req.style)
    msg = UserMessage(text=prompt)

    try:
        _text, images = await chat.send_message_multimodal_response(msg)
    except Exception as exc:
        log.exception("Nano Banana generation failed for %r", req.name)
        raise HTTPException(status_code=502, detail=f"image gen failed: {exc}") from exc

    if not images:
        raise HTTPException(status_code=502, detail="Gemini returned no image")

    img = images[0]
    mime = img.get("mime_type") or "image/png"
    b64 = img["data"]

    # Stash in Mongo so subsequent re-installs can re-fetch by hash.
    await _col.update_one(
        {"hash": chash},
        {
            "$set": {
                "hash": chash,
                "name": req.name,
                "mime": mime,
                "b64": b64,
                "prompt": prompt,
                "updated_at": datetime.now(timezone.utc),
            }
        },
        upsert=True,
    )

    return GenerateResponse(
        hash=chash,
        url=f"/api/library/cover/{chash}.png",
        mime=mime,
        b64=b64,
    )


# ---------------------------------------------------------------------------
# GET /api/library/cover/{hash}.png
# ---------------------------------------------------------------------------
@router.get("/cover/{filename}")
async def get_cover(filename: str) -> Response:
    # Accept both "{hash}.png" and a raw hash for flexibility.
    chash = filename.split(".", 1)[0]
    doc = await _col.find_one({"hash": chash})
    if not doc:
        raise HTTPException(status_code=404, detail="cover not found")
    try:
        data = base64.b64decode(doc["b64"])
    except Exception as exc:
        raise HTTPException(status_code=500, detail="cover corrupt") from exc
    return Response(
        content=data,
        media_type=doc.get("mime", "image/png"),
        headers={
            # Long browser/Coil cache — covers are immutable per hash.
            "Cache-Control": "public, max-age=31536000, immutable",
        },
    )
