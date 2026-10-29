"""Private test artifact delivery. Capability stays in a header, never a query."""
import html
import json
import re
import secrets
from pathlib import Path

from fastapi import APIRouter, Header, Request
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response
from native_download_ticket import TICKET_PATH, TICKET_TTL, authorized_capability, issue_ticket, valid_ticket
from native_download_stream import apk_response

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
    if not authorized_capability(x_download_token) or not APK.is_file():
        return Response(status_code=404, headers=NO_STORE)
    return FileResponse(APK, filename=f"vesper-native-v{artifact_version()}-private.apk",
        media_type="application/vnd.android.package-archive", headers=NO_STORE)


@router.post("/vesper-native-ticket")
async def prepare_vesper_native_download(x_download_token: str | None = Header(default=None)):
    if not authorized_capability(x_download_token):
        return Response(status_code=404, headers=NO_STORE)
    try:
        stat = APK.stat()
        if not APK.is_file():
            raise OSError()
        ticket = issue_ticket(stat)
    except (OSError, ValueError):
        return Response(status_code=404, headers=NO_STORE)
    return JSONResponse({"url": f"{TICKET_PATH}?ticket={ticket}", "expiresIn": TICKET_TTL,
                         "filename": f"vesper-native-v{artifact_version()}-private.apk", "bytes": stat.st_size}, headers=NO_STORE)


@router.api_route("/vesper-native-file", methods=["GET", "HEAD"])
async def stream_vesper_native_download(request: Request, ticket: str | None = None):
    try:
        stat = APK.stat()
        allowed = APK.is_file() and valid_ticket(ticket, stat)
    except OSError:
        allowed = False
    if not allowed:
        return Response(status_code=404, headers=NO_STORE)
    return apk_response(APK, f"vesper-native-v{artifact_version()}-private.apk", stat, request, NO_STORE)


@router.get("/vesper-native")
async def private_download_page():
    nonce = secrets.token_urlsafe(24)
    directory = Path(__file__).parent
    page = directory.joinpath("native_download.html").read_text().replace("{{VERSION}}", html.escape(artifact_version()))
    page = page.replace("{{NONCE}}", nonce).replace("{{SCRIPT}}", directory.joinpath("native_download.js").read_text())
    headers = {**NO_STORE, "Content-Security-Policy": f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'", "X-Content-Type-Options": "nosniff"}
    return HTMLResponse(page, headers=headers)

# 2026-10-20 — private test build of the V2 Vesper TV app (buffering fix); same capability + ticket flow.
TV_APK = Path(__file__).parent.parent / "android/vesper-tv/test-artifacts/vesper-tv-2.10.92-hw-audio.apk"
TV_NAME = "vesper-tv-v2.10.91-direct-fast.apk"


@router.post("/vesper-tv-ticket")
async def prepare_vesper_tv_download(x_download_token: str | None = Header(default=None)):
    if not authorized_capability(x_download_token):
        return Response(status_code=404, headers=NO_STORE)
    try:
        stat = TV_APK.stat()
        ticket = issue_ticket(stat)
    except (OSError, ValueError):
        return Response(status_code=404, headers=NO_STORE)
    return JSONResponse({"url": f"/api/download/vesper-tv-file?ticket={ticket}", "expiresIn": TICKET_TTL,
                         "filename": TV_NAME, "bytes": stat.st_size}, headers=NO_STORE)


@router.api_route("/vesper-tv-file", methods=["GET", "HEAD"])
async def stream_vesper_tv_download(request: Request, ticket: str | None = None):
    try:
        stat = TV_APK.stat()
        allowed = TV_APK.is_file() and valid_ticket(ticket, stat)
    except OSError:
        allowed = False
    if not allowed:
        return Response(status_code=404, headers=NO_STORE)
    return apk_response(TV_APK, TV_NAME, stat, request, NO_STORE)


@router.get("/vesper-tv")
async def private_tv_download_page():
    nonce = secrets.token_urlsafe(24)
    directory = Path(__file__).parent
    script = directory.joinpath("native_download.js").read_text().replace("vesper-native-ticket", "vesper-tv-ticket") \
        .replace("vesper-native-file", "vesper-tv-file").replace("vesper-native-download-v1", "vesper-tv-download-v1")
    page = directory.joinpath("native_download.html").read_text().replace("{{VERSION}}", "2.10.88 (one loading screen)")
    page = page.replace("Vesper Native", "Vesper TV (V2)").replace("This test build contains your prefilled login. ", "").replace("{{NONCE}}", nonce).replace("{{SCRIPT}}", script)
    headers = {**NO_STORE, "Content-Security-Policy": f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'", "X-Content-Type-Options": "nosniff"}
    return HTMLResponse(page, headers=headers)


# 2026-10-22 — same private link flow for the Live TV and Free to Air apps
# (newest APK in each app's test-artifacts folder).
EXTRA_APPS = {
    "livetv": ("ON NOW TV Live TV", "android/onnowtv-livetv/test-artifacts"),
    "fta": ("ON NOW TV Free to Air", "android/onnowtv-fta-native/test-artifacts"),
}


def _newest_apk(folder: str) -> Path | None:
    apks = sorted((Path(__file__).parent.parent / folder).glob("*.apk"), key=lambda p: p.stat().st_mtime)
    return apks[-1] if apks else None


def _register_extra(slug: str, label: str, folder: str):
    @router.post(f"/{slug}-ticket")
    async def ticket(x_download_token: str | None = Header(default=None)):
        apk = _newest_apk(folder)
        if not authorized_capability(x_download_token) or apk is None:
            return Response(status_code=404, headers=NO_STORE)
        stat = apk.stat()
        return JSONResponse({"url": f"/api/download/{slug}-file?ticket={issue_ticket(stat)}", "expiresIn": TICKET_TTL,
                             "filename": apk.name, "bytes": stat.st_size}, headers=NO_STORE)

    @router.api_route(f"/{slug}-file", methods=["GET", "HEAD"])
    async def file(request: Request, ticket: str | None = None):
        apk = _newest_apk(folder)
        stat = apk.stat() if apk else None
        if stat is None or not valid_ticket(ticket, stat):
            return Response(status_code=404, headers=NO_STORE)
        return apk_response(apk, apk.name, stat, request, NO_STORE)

    @router.get(f"/{slug}")
    async def page():
        nonce = secrets.token_urlsafe(24)
        directory = Path(__file__).parent
        apk = _newest_apk(folder)
        script = directory.joinpath("native_download.js").read_text().replace("vesper-native-ticket", f"{slug}-ticket") \
            .replace("vesper-native-file", f"{slug}-file").replace("vesper-native-download-v1", f"{slug}-download-v1")
        version = apk.stem.split("-", 2)[-1] if apk else "not built"
        body = directory.joinpath("native_download.html").read_text().replace("{{VERSION}}", html.escape(version))
        body = body.replace("Vesper Native", label).replace("This test build contains your prefilled login. ", "") \
            .replace("{{NONCE}}", nonce).replace("{{SCRIPT}}", script)
        headers = {**NO_STORE, "Content-Security-Policy": f"default-src 'none'; script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'", "X-Content-Type-Options": "nosniff"}
        return HTMLResponse(body, headers=headers)


for _slug, (_label, _folder) in EXTRA_APPS.items():
    _register_extra(_slug, _label, _folder)
