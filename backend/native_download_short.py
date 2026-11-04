"""Short direct-APK links for the Fire TV *Downloader* app.

Downloader can't handle our JS page or a long `#token` URL, so each app gets
a short, typeable path that streams the APK directly:

    /api/dl/tv/<code>     -> newest ON NOW TV (vesper-tv) APK
    /api/dl/live/<code>   -> newest Live TV APK

`code` is a 10-char HMAC of the slug under the private capability token, so
it can't be guessed and the real capability never appears in a URL.
"""
import base64
import hashlib
import hmac
import os
import secrets
from pathlib import Path

from fastapi import APIRouter, Request
from fastapi.responses import Response

from native_download_stream import apk_response

router = APIRouter(prefix="/api/dl")
ROOT = Path(__file__).parent.parent / "android"
SLUGS = {"tv": "vesper-tv", "live": "onnowtv-livetv", "one": "onnow-one"}
NO_STORE = {"Cache-Control": "no-store, no-cache, must-revalidate", "Pragma": "no-cache", "Referrer-Policy": "no-referrer"}


def short_code(slug: str) -> str | None:
    secret = os.environ.get("APK_DOWNLOAD_CAPABILITY_TOKEN")
    if not secret or slug not in SLUGS:
        return None
    mac = hmac.new(secret.encode(), f"dl:{slug}".encode(), hashlib.sha256).digest()
    return base64.urlsafe_b64encode(mac).rstrip(b"=").decode("ascii")[:10]


def _newest(slug: str) -> Path | None:
    folder = ROOT / SLUGS[slug] / "test-artifacts"
    apks = sorted(folder.glob("*.apk"), key=lambda p: p.stat().st_mtime, reverse=True) if folder.is_dir() else []
    return apks[0] if apks else None


@router.api_route("/{slug}/{code}", methods=["GET", "HEAD"])
async def direct_apk(slug: str, code: str, request: Request):
    expected = short_code(slug)
    if not expected or not secrets.compare_digest(code.encode(), expected.encode()):
        return Response(status_code=404, headers=NO_STORE)
    apk = _newest(slug)
    if apk is None:
        return Response(status_code=404, headers=NO_STORE)
    return apk_response(apk, apk.name, apk.stat(), request, NO_STORE)
