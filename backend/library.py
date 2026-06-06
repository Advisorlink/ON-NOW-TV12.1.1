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
# Prompt template — user's ChatGPT-vetted wording with ONE micro-edit:
# "legal project" → "licensed project" so the model never renders
# scales-of-justice imagery when it parses the word literally.  No
# other rewrites, NO trailing example sentences — the user explicitly
# rejected adding ", e.g. sports → athletes" style examples because
# the model was using the example items rather than inferring from
# the channel name.
# ---------------------------------------------------------------------------
_BASE_STYLE = (
    "ultra-realistic 16:9 widescreen channel tile, "
    "channel logo on the left fading into a related image on the right, "
    "black gradient at the bottom"
)



def _build_prompt(name: str, style: Optional[str]) -> str:
    cleaned = (name or "Channel").strip()
    # v11 — user's verbatim "cable tv categorie" wording PLUS an
    # explicit "no logos, no text, no brand marks" guard appended
    # at the end.  At quality="high" the safety filter is more
    # permissive about real broadcaster wordmarks, so the explicit
    # guard is what keeps the output logo-free.
    return (
        f"I need a channel tile design for my project its a licensed "
        f"project not showing any content just need an image the "
        f"image needs to be a 16:9 tile and it needs to have a dark "
        f"space on the left fading to some related image on the "
        f"right.. there needs to be a black gradient on the bottom "
        f"aswell. The image needs to relate to the cable tv "
        f"categorie {cleaned}. the image should be of high quality "
        f"for cable tv app. "
        f"ABSOLUTELY NO LOGOS, no brand marks, no broadcaster logos, "
        f"no channel logos, no text, no letters, no words, no "
        f"numbers, no watermarks, no captions, no readable typography "
        f"anywhere in the image — just a clean themed image on the "
        f"right with the dark left fade."
    )


# ---------------------------------------------------------------------------
# Prompt-recipe version — BUMP THIS whenever `_build_prompt()` changes.
# It feeds into `_hash_for(...)` so the cache key naturally rolls over
# the instant the prompt logic changes: old PNGs generated with a
# previous prompt recipe live on disk with a different hash than the
# new requests, so they can NEVER be served again.  No manual cache
# wipes needed, ever.
#
#   v1 — verbatim user prompt with "legal" wording (Feb 2026)
#   v2 — "licensed" disambiguation
#   v3 — full safety-friendly ChatGPT-style enhanced prompt
#   v4 — added the 12 % safe-area / no-clipping clause
#   v5 — pure photoreal editorial photo, NO text / logos / typography
#   v6 — simplified back to short prompt, photoreal, no logos, no text
#   v7 — verbatim user-dictated prompt with category style table
#   v8 — Gemini Nano Banana + cinematic Pixar-grade prompt, no left brand-mark
#   v9 — GPT-Image-1 + same cinematic Pixar-grade prompt, no left brand-mark
#   v10 — USER-LOCKED VERBATIM prompt (cable tv categorie), GPT-Image-1
#   v11 — v10 prompt + strict no-logos / no-text guard, quality=high (current)
# ---------------------------------------------------------------------------
PROMPT_RECIPE_VERSION = "v11"


def _hash_for(name: str, style: Optional[str], salt: str = "") -> str:
    """Deterministic hash so we can dedupe within a (name, style,
    PROMPT_RECIPE_VERSION, salt) quadruple.  Including the prompt
    version means a bare reinstall + same-name regeneration NEVER
    returns artwork generated under an older prompt — exactly the
    bug the user reported when freshly-installed devices kept
    seeing the old "legal project" gavel imagery."""
    h = hashlib.sha256()
    h.update((name or "").strip().lower().encode("utf-8"))
    h.update(b"|")
    h.update((style or _BASE_STYLE).encode("utf-8"))
    h.update(b"|")
    h.update(PROMPT_RECIPE_VERSION.encode("utf-8"))
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
    # explicit regen requests so we always re-run the generator).
    if not req.salt:
        cached = await _col.find_one({"hash": chash})
        if cached:
            return GenerateResponse(
                hash=chash,
                url=f"/api/library/cover/{chash}.png",
                mime=cached.get("mime", "image/png"),
                b64=cached["b64"],
            )

    prompt = _build_prompt(req.name, req.style)

    # ---------- OpenAI GPT-Image-1 via the Emergent Universal Key ----------
    # User asked to re-run the v8 cinematic / Pixar-grade prompt
    # through GPT-Image-1 (instead of Gemini Nano Banana) so they
    # can compare both providers on the exact same wording.
    # GPT-Image-1 picks `1536×1024` automatically for landscape
    # prompts; we then centre-crop to 16:9 / resize to 1280×720
    # downstream.  Medium quality keeps the per-gen cost ~$0.06 and
    # the safety filter happy with real broadcaster names.
    api_key = os.environ.get("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(
            status_code=500,
            detail="EMERGENT_LLM_KEY not configured — set it in backend/.env",
        )

    try:
        from emergentintegrations.llm.openai.image_generation import OpenAIImageGeneration
    except Exception as exc:  # pragma: no cover
        log.exception("OpenAIImageGeneration import failed")
        raise HTTPException(status_code=500, detail="emergentintegrations missing") from exc

    image_gen = OpenAIImageGeneration(api_key=api_key)
    try:
        images = await image_gen.generate_images(
            prompt=prompt,
            model="gpt-image-1",
            number_of_images=1,
            quality="high",
        )
    except Exception as exc:
        log.exception("GPT-Image-1 generation failed for %r", req.name)
        raise HTTPException(status_code=502, detail=f"image gen failed: {exc}") from exc

    if not images:
        raise HTTPException(status_code=502, detail="OpenAI returned no image")

    raw_bytes = images[0]

    # Normalise to 1280×720 — centre-crop to 16:9 then LANCZOS-resize
    # to standard 720p HD (the Android tile renders at ~300-500 px
    # wide on a 1080p TV, so 720p is pixel-perfect at every realistic
    # tile size while saving ~55 % file size vs 1080p).
    try:
        from io import BytesIO
        from PIL import Image
        src = Image.open(BytesIO(raw_bytes)).convert("RGB")
        w, h = src.size
        target_ratio = 16.0 / 9.0
        cur_ratio = w / h
        if cur_ratio > target_ratio:
            new_w = int(h * target_ratio)
            x0 = (w - new_w) // 2
            cropped = src.crop((x0, 0, x0 + new_w, h))
        elif cur_ratio < target_ratio:
            new_h = int(w / target_ratio)
            y0 = (h - new_h) // 2
            cropped = src.crop((0, y0, w, y0 + new_h))
        else:
            cropped = src
        if cropped.size != (1280, 720):
            cropped = cropped.resize((1280, 720), Image.LANCZOS)
        buf = BytesIO()
        cropped.save(buf, format="PNG", optimize=True)
        png_bytes = buf.getvalue()
    except Exception:
        log.exception("16:9 normalisation failed — serving raw bytes")
        png_bytes = raw_bytes

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
