"""Reborn Play cloud: remember a device's login, and keep profiles / lists / progress against the service login."""
from __future__ import annotations

import base64
import hashlib
import json
import os
import time
from typing import Any, Dict, Optional

import httpx
from cryptography.fernet import Fernet, InvalidToken
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, Field

router = APIRouter(prefix="/api/reborn", tags=["reborn"])
_STATE: Dict[str, Any] = {"db": None}
_VERIFIED: Dict[str, float] = {}
_MAX_BYTES = 1_500_000


def configure_reborn_cloud(db) -> None:
    _STATE["db"] = db


def _db():
    if _STATE["db"] is None:
        raise HTTPException(503, "store not ready")
    return _STATE["db"]


def _fernet() -> Fernet:
    return Fernet(base64.urlsafe_b64encode(hashlib.sha256(os.environ["JWT_SECRET"].encode()).digest()))


def _account_id(username: str) -> str:
    return hashlib.sha256(("reborn:" + username.strip().lower()).encode()).hexdigest()


async def _verify(username: str, password: str) -> None:
    """The service itself decides whether a login is real; a good answer is trusted for 6 hours."""
    key = hashlib.sha256(f"{username}\n{password}".encode()).hexdigest()
    if _VERIFIED.get(key, 0) > time.time():
        return
    try:
        async with httpx.AsyncClient(timeout=15, verify=False, follow_redirects=True) as c:
            r = await c.get(os.environ["REBORN_PROVIDER_BASE"] + "/player_api.php", params={"username": username, "password": password})
    except httpx.HTTPError:
        raise HTTPException(503, "service unreachable")
    if r.status_code >= 500:
        raise HTTPException(503, "service unreachable")
    try:
        ok = r.status_code == 200 and int((r.json().get("user_info") or {}).get("auth", 0)) == 1
    except Exception:
        ok = False
    if not ok:
        raise HTTPException(401, "login not accepted")
    if len(_VERIFIED) > 5000:
        _VERIFIED.clear()
    _VERIFIED[key] = time.time() + 6 * 3600


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=120)
    password: str = Field(min_length=1, max_length=200)


class Remember(Login):
    device_id: str = Field(min_length=8, max_length=128)


class Device(BaseModel):
    device_id: str = Field(min_length=8, max_length=128)


class CrashReport(BaseModel):
    version: str = Field(default="", max_length=60)
    device: str = Field(default="", max_length=200)
    trace: str = Field(min_length=1, max_length=14000)


@router.post("/crash")
async def crash(body: CrashReport):
    await _db().reborn_crashes.insert_one({"ts": time.time(), "version": body.version, "device": body.device, "trace": body.trace})
    return {"ok": True}


class Push(Login):
    data: Dict[str, Any]


def _dev(device_id: str) -> str:
    return hashlib.sha256(("reborn-device:" + device_id).encode()).hexdigest()


@router.post("/device/remember")
async def remember(body: Remember):
    await _verify(body.username, body.password)
    secret = _fernet().encrypt(json.dumps({"u": body.username, "p": body.password}).encode()).decode()
    await _db()["reborn_devices"].update_one({"device": _dev(body.device_id)}, {"$set": {"device": _dev(body.device_id), "secret": secret, "updated_at": int(time.time() * 1000)}}, upsert=True)
    return {"ok": True}


@router.post("/device/recall")
async def recall(body: Device):
    doc = await _db()["reborn_devices"].find_one({"device": _dev(body.device_id)}, {"_id": 0, "secret": 1})
    if not doc or not doc.get("secret"):
        return {"found": False}
    try:
        d = json.loads(_fernet().decrypt(doc["secret"].encode()))
    except (InvalidToken, ValueError):
        return {"found": False}
    return {"found": True, "username": d["u"], "password": d["p"]}


@router.post("/device/forget")
async def forget(body: Device):
    # Sign-out: blank the stored login and mark the device forgotten (the record itself is kept).
    await _db()["reborn_devices"].update_one({"device": _dev(body.device_id)}, {"$set": {"secret": "", "forgotten_at": int(time.time() * 1000)}})
    return {"ok": True}


@router.post("/sync/push")
async def push(body: Push):
    await _verify(body.username, body.password)
    if len(json.dumps(body.data)) > _MAX_BYTES:
        raise HTTPException(413, "too large")
    now = int(time.time() * 1000)
    await _db()["reborn_sync"].update_one({"account": _account_id(body.username)}, {"$set": {"account": _account_id(body.username), "data": body.data, "updated_at": now}}, upsert=True)
    return {"ok": True, "updated_at": now}


@router.post("/sync/pull")
async def pull(body: Login):
    await _verify(body.username, body.password)
    doc = await _db()["reborn_sync"].find_one({"account": _account_id(body.username)}, {"_id": 0, "data": 1, "updated_at": 1})
    if not doc:
        return {"found": False}
    return {"found": True, "data": doc["data"], "updated_at": doc["updated_at"]}
