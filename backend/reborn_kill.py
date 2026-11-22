"""Reborn Play kill switch: a single remote flag that locks every Reborn Play app, plus a private page to flip it."""
from __future__ import annotations

import html
import os
import time

from fastapi import APIRouter, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse

router = APIRouter(prefix="/api/reborn", tags=["reborn-kill"])
_STATE: dict = {"db": None}
_DEFAULT_MSG = "This app has been disabled. Please contact the developer to restore access."


def configure_reborn_kill(db) -> None:
    _STATE["db"] = db


def _col():
    if _STATE["db"] is None:
        raise HTTPException(503, "store not ready")
    return _STATE["db"].reborn_kill


async def _state() -> dict:
    doc = await _col().find_one({"_id": "global"}) or {}
    return {
        "killed": bool(doc.get("killed", False)),
        "message": doc.get("message") or _DEFAULT_MSG,
        "updated_at": doc.get("updated_at", 0),
    }


def _check_key(key: str) -> None:
    expected = os.environ["REBORN_KILL_ADMIN_KEY"]
    if not key or key != expected:
        raise HTTPException(401, "wrong password")


@router.get("/status")
async def status():
    """Polled by every app. Kept tiny and always fresh so a flip reaches apps within one poll."""
    s = await _state()
    return JSONResponse(
        {"killed": s["killed"], "message": s["message"], "updated_at": s["updated_at"]},
        headers={"Cache-Control": "no-store, max-age=0"},
    )


_PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reborn Play — Kill Switch</title><style>
*{{box-sizing:border-box}}body{{margin:0;font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0a1222;color:#eef2f8;display:flex;min-height:100vh;align-items:center;justify-content:center;padding:20px}}
.card{{width:100%;max-width:460px;background:#121a2d;border:1px solid #223049;border-radius:18px;padding:28px}}
h1{{font-size:20px;margin:0 0 4px}}.sub{{color:#93a0b5;font-size:13px;margin:0 0 22px}}
label{{display:block;font-size:13px;color:#aeb8c8;margin:14px 0 6px}}
input,textarea{{width:100%;background:#0a1222;border:1px solid #2a3a54;color:#fff;border-radius:10px;padding:11px 13px;font-size:15px}}
textarea{{min-height:84px;resize:vertical}}
.state{{display:flex;align-items:center;gap:10px;background:#0a1222;border:1px solid #2a3a54;border-radius:10px;padding:14px;margin-bottom:8px}}
.dot{{width:12px;height:12px;border-radius:50%}}.live{{background:#28c76f}}.dead{{background:#ea4b4b}}
.row{{display:flex;gap:12px;margin-top:20px}}button{{flex:1;border:0;border-radius:10px;padding:13px;font-size:15px;font-weight:700;cursor:pointer;color:#fff}}
.kill{{background:#ea4b4b}}.restore{{background:#28c76f}}.save{{background:#3063ff}}.muted{{color:#6f7d93;font-size:12px;margin-top:16px}}
</style></head><body><form class="card" method="post" action="/api/reborn/kill/apply">
<h1>Reborn Play — Kill Switch</h1><p class="sub">Locks or unlocks every Reborn Play app. Takes effect within about a minute.</p>
<div class="state"><span class="dot {dotcls}"></span><b>{statetxt}</b></div>
<div class="muted">{updtxt}</div>
<label>Admin password</label><input name="key" type="password" value="{key}" autocomplete="current-password" required>
<label>Message shown on locked apps</label><textarea name="message">{msg}</textarea>
<div class="row"><button class="{actcls}" name="action" value="{actval}">{actlabel}</button>
<button class="save" name="action" value="save">Save message only</button></div>
<p class="muted">Bookmark this page. Anyone with the password can lock or unlock the apps.</p>
{err}</form></body></html>"""


async def _render(key: str = "", err: str = "") -> str:
    s = await _state()
    killed = s["killed"]
    upd = s["updated_at"]
    updtxt = ("Last changed " + time.strftime("%d %b %Y, %H:%M UTC", time.gmtime(upd))) if upd else "Never changed"
    return _PAGE.format(
        dotcls="dead" if killed else "live",
        statetxt="LOCKED — apps are disabled" if killed else "ACTIVE — apps are working",
        updtxt=updtxt,
        key=html.escape(key),
        msg=html.escape(s["message"]),
        actcls="restore" if killed else "kill",
        actval="restore" if killed else "kill",
        actlabel="Unlock all apps" if killed else "Kill all apps",
        err=f'<p class="muted" style="color:#ff8f8f">{html.escape(err)}</p>' if err else "",
    )


@router.get("/kill", response_class=HTMLResponse)
async def kill_page():
    return HTMLResponse(await _render())


@router.post("/kill/apply", response_class=HTMLResponse)
async def kill_apply(key: str = Form(""), action: str = Form(""), message: str = Form("")):
    try:
        _check_key(key)
    except HTTPException as e:
        return HTMLResponse(await _render(err=str(e.detail)), status_code=401)
    msg = (message or "").strip() or _DEFAULT_MSG
    doc = {"message": msg, "updated_at": int(time.time())}
    if action == "kill":
        doc["killed"] = True
    elif action == "restore":
        doc["killed"] = False
    await _col().update_one({"_id": "global"}, {"$set": doc}, upsert=True)
    return HTMLResponse(await _render(key=key))
