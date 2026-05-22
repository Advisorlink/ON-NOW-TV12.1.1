"""
v2.7.55 — Speech-to-text endpoint for Watch Together voice reactions.

POST /api/stt/transcribe
  multipart/form-data:  audio (webm | wav | mp3 | m4a | ogg, max 25 MB)
  returns: { "text": "<transcript>" }

Architecture is provider-agnostic on purpose — the route just calls
`transcribe_audio()` below.  Today that delegates to OpenAI Whisper
via the Emergent Universal LLM Key.  Swapping in a different provider
(self-hosted whisper.cpp, ElevenLabs, Deepgram, …) only needs a new
implementation of `transcribe_audio()` — no frontend changes.
"""

from __future__ import annotations

import io
import logging
import os
from typing import Optional

from dotenv import load_dotenv
from fastapi import APIRouter, File, HTTPException, UploadFile

load_dotenv()
log = logging.getLogger("stt")

router = APIRouter(prefix="/api/stt", tags=["stt"])

# Audio formats supported by Whisper.  Anything else is rejected up front.
_ALLOWED_EXT = {"webm", "wav", "mp3", "m4a", "mpeg", "mpga", "mp4", "ogg"}
_MAX_BYTES = 24 * 1024 * 1024  # 24 MB (Whisper's hard limit is 25 MB)


async def transcribe_audio(
    audio_bytes: bytes,
    filename: str,
    language: Optional[str] = "en",
) -> str:
    """Provider-agnostic transcription.  Returns plain transcript text.

    Currently uses OpenAI Whisper via the Emergent LLM key.  Replace
    the body of this function to switch provider — the route + the
    frontend contract stay identical.
    """
    api_key = os.getenv("EMERGENT_LLM_KEY")
    if not api_key:
        raise HTTPException(500, "STT_KEY_MISSING")

    # Lazy-import so a broken `emergentintegrations` install doesn't
    # break the rest of the app at boot time.
    try:
        from emergentintegrations.llm.openai import OpenAISpeechToText
    except Exception as exc:  # noqa: BLE001
        log.exception("emergentintegrations import failed")
        raise HTTPException(500, "STT_BACKEND_UNAVAILABLE") from exc

    stt = OpenAISpeechToText(api_key=api_key)
    buf = io.BytesIO(audio_bytes)
    # The SDK reads `.name` to send the correct multipart filename
    # (which is how Whisper detects the audio format).
    buf.name = filename
    try:
        resp = await stt.transcribe(
            file=buf,
            model="whisper-1",
            response_format="json",
            language=language or "en",
            temperature=0.0,
        )
    except Exception as exc:  # noqa: BLE001
        log.exception("whisper transcription failed")
        raise HTTPException(502, "STT_PROVIDER_ERROR") from exc

    text = getattr(resp, "text", None) or ""
    return text.strip()


@router.post("/transcribe")
async def transcribe_endpoint(audio: UploadFile = File(...)) -> dict:
    if not audio or not audio.filename:
        raise HTTPException(400, "AUDIO_MISSING")

    # Reject obviously-wrong files before we burn an LLM credit.
    ext = (audio.filename.rsplit(".", 1)[-1] or "").lower()
    if ext and ext not in _ALLOWED_EXT:
        raise HTTPException(400, "AUDIO_FORMAT_UNSUPPORTED")

    data = await audio.read()
    if len(data) == 0:
        raise HTTPException(400, "AUDIO_EMPTY")
    if len(data) > _MAX_BYTES:
        raise HTTPException(413, "AUDIO_TOO_LARGE")

    text = await transcribe_audio(data, audio.filename)
    return {"text": text}
