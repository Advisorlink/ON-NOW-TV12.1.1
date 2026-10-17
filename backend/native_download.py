"""Private test artifact delivery. Capability stays in a header, never a query."""
import html
import json
import os
import re
import secrets
from pathlib import Path

from fastapi import APIRouter, Header
from fastapi.responses import FileResponse, HTMLResponse, Response

router = APIRouter(prefix="/api/download")
APK = Path(__file__).parent.parent / "android/vesper-native/app/build/outputs/apk/debug/app-debug.apk"
NO_STORE = {"Cache-Control": "no-store, no-cache, must-revalidate", "Pragma": "no-cache", "Expires": "0", "Referrer-Policy": "no-referrer"}


def artifact_version():
    try:
        value = json.loads(APK.with_name("output-metadata.json").read_text())["elements"][0]["versionName"]
        return re.match(r"\d+\.\d+\.\d+", value).group()
    except (OSError, ValueError, KeyError, IndexError, AttributeError, TypeError):
        return "test"


@router.get("/vesper-native-apk")
async def download_vesper_native_apk(x_download_token: str | None = Header(default=None)):
    expected = os.environ.get("APK_DOWNLOAD_CAPABILITY_TOKEN")
    if not expected or not x_download_token or not secrets.compare_digest(x_download_token.encode(), expected.encode()) or not APK.is_file():
        return Response(status_code=404, headers=NO_STORE)
    return FileResponse(APK, filename=f"vesper-native-v{artifact_version()}-private.apk",
        media_type="application/vnd.android.package-archive", headers=NO_STORE)


@router.get("/vesper-native")
async def private_download_page():
    nonce = secrets.token_urlsafe(24)
    directory = Path(__file__).parent
    page = directory.joinpath("native_download.html").read_text().replace("{{VERSION}}", html.escape(artifact_version()))
    page = page.replace("{{NONCE}}", nonce).replace("{{SCRIPT}}", directory.joinpath("native_download.js").read_text())
    headers = {**NO_STORE, "Content-Security-Policy": f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'", "X-Content-Type-Options": "nosniff"}
    return HTMLResponse(page, headers=headers)