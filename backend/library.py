"""
Library — AI-generated cover-art endpoints for the native Live TV
client.

The Android side asks for a 16:9 cover image for a given category
name (e.g. "Sports", "Sky Cinema HD") and we either:

  1. **Generate** a fresh one via OpenAI's GPT-Image-1 (via the
     Emergent Universal LLM Key) using the user-vetted prompt — a
     channel tile with the logo on the left fading into a related
     image on the right and a black gradient at the bottom.  The
     bytes are cropped to exact 16:9 and persisted in Mongo keyed
     by a deterministic hash, OR
  2. **Serve** the previously-generated PNG bytes for a given hash so
     the client can re-download on reinstall.

Endpoints (all under the standard ``/api`` umbrella so the
Kubernetes ingress routes them to the backend pod):

  • POST /api/library/generate-cover  { name, style? }  →  { hash, url, mime, b64 }
  • GET  /api/library/cover/{hash}.png                  →  image/png bytes

GPT-Image-1 returns raw bytes; we open with Pillow, centre-crop to
exact 16:9, re-encode as PNG, base64 the result and stash it in
Mongo.
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
# Prompt template — verbatim user-supplied prompt (Feb 14, 2026).
# The user vetted this exact wording against ChatGPT and confirmed it
# produces the desired channel-tile look (logo on the left fading
# into a related image on the right, with a black gradient at the
# bottom).  We're testing the same prompt against Nano Banana; if
# the output drifts we'll switch this provider to GPT-Image-1.
#
# DO NOT rewrite or "improve" this prompt — the previous AI-slop
# elaborations (neon collages, magazine-cover production value, etc.)
# are exactly what the user wants to avoid.  Inject the category
# name as the channel and leave the rest of the wording intact.
# ---------------------------------------------------------------------------
_BASE_STYLE = (
    "ultra-realistic 16:9 widescreen channel tile, "
    "channel logo on the left fading into a related image on the right, "
    "black gradient at the bottom"
)


def _build_prompt(name: str, style: Optional[str]) -> str:
    cleaned = (name or "Channel").strip()
    # User-vetted ChatGPT prompt, with two tiny disambiguations:
    #   - "legal" → "licensed" so GPT-Image-1 doesn't render scales of
    #     justice (it took the original word literally).
    #   - One trailing sentence telling the model what the right-hand
    #     image should depict, so a channel called "Sky Sports KO"
    #     produces a boxing photo rather than an unrelated still life.
    # Everything else is the user's exact wording.
    base = (
        f"I need a channel tile design for my project — it's a "
        f"licensed streaming-app branding exercise, not showing any "
        f"copyrighted content, just need an image.  The image needs "
        f"to be a 16:9 tile and it needs to have the \"{cleaned}\" "
        f"channel logo on the left fading to some related image on "
        f"the right.. there needs to be a black gradient on the "
        f"bottom aswell.  The image on the right should clearly "
        f"represent what \"{cleaned}\" actually broadcasts (sports "
        f"channels → real athletes mid-action, movie channels → "
        f"cinematic film imagery, news → studio anchors, kids → "
        f"bright cartoon art, etc.)."
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
    # explicit regen requests so we always run GPT-Image-1).
    if not req.salt:
        cached = await _col.find_one({"hash": chash})
        if cached:
            return GenerateResponse(
                hash=chash,
                url=f"/api/library/cover/{chash}.png",
                mime=cached.get("mime", "image/png"),
                b64=cached["b64"],
            )

    # Prefer the user's direct OpenAI key when supplied — that
    # bypasses the Emergent Universal proxy (no shared budget cap)
    # and hits OpenAI's image API directly.  Falls back to the
    # universal Emergent key when only that one is configured.
    api_key = os.environ.get("OPENAI_API_KEY") or os.environ.get("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(
            status_code=500,
            detail="No OpenAI key configured — set OPENAI_API_KEY or EMERGENT_LLM_KEY in backend/.env",
        )

    try:
        from emergentintegrations.llm.openai.image_generation import OpenAIImageGeneration
    except Exception as exc:  # pragma: no cover
        log.exception("OpenAIImageGeneration import failed")
        raise HTTPException(status_code=500, detail="emergentintegrations missing") from exc

    prompt = _build_prompt(req.name, req.style)

    # GPT-Image-1's native sizes are 1024x1024 / 1024x1536 / 1536x1024.
    # 1536x1024 is the widest landscape we get; we crop it to 1536x864
    # for a true 16:9 tile (matches what the Android client renders).
    image_gen = OpenAIImageGeneration(api_key=api_key)
    try:
        images = await image_gen.generate_images(
            prompt=prompt,
            model="gpt-image-1",
            number_of_images=1,
            quality="high",  # default in the wrapper is "low" — looks muddy/muted
        )
    except Exception as exc:
        log.exception("GPT-Image-1 generation failed for %r", req.name)
        raise HTTPException(status_code=502, detail=f"image gen failed: {exc}") from exc

    if not images:
        raise HTTPException(status_code=502, detail="OpenAI returned no image")

    # Crop the generated bytes to exact 16:9 (matches the Android
    # preview tile's aspect so the tile never letterboxes).
    try:
        from io import BytesIO
        from PIL import Image
        src = Image.open(BytesIO(images[0])).convert("RGB")
        w, h = src.size
        target_ratio = 16.0 / 9.0
        cur_ratio = w / h
        if cur_ratio > target_ratio:
            # too wide — trim left/right
            new_w = int(h * target_ratio)
            x0 = (w - new_w) // 2
            cropped = src.crop((x0, 0, x0 + new_w, h))
        elif cur_ratio < target_ratio:
            # too tall — trim top/bottom
            new_h = int(w / target_ratio)
            y0 = (h - new_h) // 2
            cropped = src.crop((0, y0, w, y0 + new_h))
        else:
            cropped = src
        buf = BytesIO()
        cropped.save(buf, format="PNG", optimize=True)
        png_bytes = buf.getvalue()
    except Exception:
        log.exception("16:9 crop failed — serving raw GPT-Image-1 PNG")
        png_bytes = images[0]

    mime = "image/png"
    b64 = base64.b64encode(png_bytes).decode("ascii")

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
