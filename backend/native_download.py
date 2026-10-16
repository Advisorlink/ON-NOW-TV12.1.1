"""Private test artifact delivery. Capability stays in a header, never a URL query."""
import os
import secrets
from pathlib import Path

from fastapi import APIRouter, Header
from fastapi.responses import FileResponse, HTMLResponse, Response

router = APIRouter(prefix="/api/download")
APK = Path(__file__).parent.parent / "android/vesper-native/app/build/outputs/apk/debug/app-debug.apk"
NO_STORE = {"Cache-Control": "no-store, no-cache, must-revalidate", "Pragma": "no-cache", "Expires": "0", "Referrer-Policy": "no-referrer"}


@router.get("/vesper-native-apk")
async def download_vesper_native_apk(x_download_token: str | None = Header(default=None)):
    expected = os.environ.get("APK_DOWNLOAD_CAPABILITY_TOKEN")
    if not expected or not x_download_token or not secrets.compare_digest(x_download_token.encode(), expected.encode()) or not APK.is_file():
        return Response(status_code=404, headers=NO_STORE)
    return FileResponse(APK, filename="vesper-native-v0.2.1-private.apk",
        media_type="application/vnd.android.package-archive", headers=NO_STORE)


@router.get("/vesper-native")
async def private_download_page():
    # The fragment is not sent to our server/proxy. Clear it from the current
    # history entry before issuing a header-authenticated fetch; no persistence.
    nonce = secrets.token_urlsafe(24)
    script = (Path(__file__).parent / "native_download.js").read_text()
    headers = {**NO_STORE, "Content-Security-Policy": f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'", "X-Content-Type-Options": "nosniff"}
    return HTMLResponse(f"""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>Vesper Native · Private test</title>
<style>body{{margin:0;background:#03060f;color:#f4f8ff;font:18px sans-serif;min-height:100vh;display:grid;place-items:center}}main{{max-width:36rem;padding:32px;box-sizing:border-box;width:100%}}h1{{font-size:32px}}button{{background:#00b8ff;color:#03060f;border:0;border-radius:24px;padding:14px 24px;font:inherit;cursor:pointer}}button:disabled{{opacity:.6;cursor:wait}}button:focus-visible{{outline:3px solid #5cdfff;outline-offset:4px}}p{{line-height:1.5;overflow-wrap:anywhere}}small{{color:#a8b5c7}}</style></head><body><main><small data-testid="download-build-label">PRIVATE TEST · v0.2.1</small><h1 data-testid="download-title">Vesper Native</h1><p id="status" role="status" aria-live="polite" data-testid="download-status">Ready to download your private test APK.</p><button id="download" data-testid="download-apk-button">Download APK</button><p><small data-testid="download-privacy-notice">This test build contains your prefilled login. Keep the APK and this link private.</small></p></main><script nonce="{nonce}">{script}</script></body></html>""", headers=headers)