"""Catch Up verifier: checks, one at a time, which archive recordings the provider really serves.

The provider allows one connection per account and has real gaps (~10% of shows, some channels none at
all), so apps can't check quickly themselves. This worker probes each recording once with an idle
customer login account, stores the verdict, and `/api/catchup/recordings` lists only confirmed ones.
"""
from __future__ import annotations

import asyncio
import gzip
import base64
import json
import logging
import math
import os
import random
import time
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from zoneinfo import ZoneInfo

import httpx
from fastapi import APIRouter, Response

log = logging.getLogger("catchup_verifier")
router = APIRouter(prefix="/api/catchup", tags=["catchup"])

LISTING_REFRESH_SECS = 20 * 60
FAIL_RETRY_SECS = 3 * 3600
MAX_TRIES = 3
LEASE_SECS = 120

_db = None
_task: Optional[asyncio.Task] = None
_owner = f"{os.getpid()}-{random.randint(0, 1 << 30)}"
_payload: Tuple[float, Optional[bytes]] = (0.0, None)
_status: Dict[str, Any] = {"phase": "idle", "account": None, "probed": 0, "cycle_started": 0}


def _col():
    return _db["catchup_recordings"]


def _provider() -> Optional[Dict[str, Any]]:
    from instant_bundle import _provider_from_env
    return _provider_from_env()


def _base(p: Dict[str, Any]) -> str:
    port = f":{p['port']}" if p.get("port") else ""
    return f"{p.get('scheme') or 'http'}://{p['host']}{port}"


def _archive_channels() -> List[Dict[str, Any]]:
    from instant_bundle import _state
    return [c for c in (_state.get("channels") or []) if c.get("tv_archive") == 1]


async def _lease() -> bool:
    now = time.time()
    try:
        await _db["catchup_verifier_lease"].update_one(
            {"_id": "lease", "$or": [{"until": {"$lt": now}}, {"owner": _owner}]},
            {"$set": {"owner": _owner, "until": now + LEASE_SECS}}, upsert=True)
        return True
    except Exception:  # duplicate key → another worker holds it
        return False


async def _user_info(cl: httpx.AsyncClient, base: str, u: str, pw: str) -> Optional[Dict[str, Any]]:
    try:
        r = await cl.get(f"{base}/player_api.php", params={"username": u, "password": pw})
        j = r.json()
        return j if isinstance(j, dict) and (j.get("user_info") or {}).get("auth") == 1 else None
    except Exception:
        return None


async def _pick_account(cl: httpx.AsyncClient, base: str, skip: set) -> Optional[Tuple[str, str, str]]:
    """An idle login account on this provider: (username, password, server timezone)."""
    rows = await _db["vesper_accounts"].find(
        {"xtream_password": {"$nin": [None, ""]}}, {"username": 1, "xtream_username": 1, "xtream_password": 1}).to_list(2000)
    random.shuffle(rows)
    for a in rows[:60]:
        u = (a.get("xtream_username") or a.get("username") or "").strip()
        pw = (a.get("xtream_password") or "").strip()
        if not u or u in skip:
            continue
        info = await _user_info(cl, base, u, pw)
        if not info:
            continue
        ui = info["user_info"]
        if str(ui.get("status")) != "Active" or int(ui.get("active_cons") or 0) > 0:
            continue
        tz = (info.get("server_info") or {}).get("timezone") or "UTC"
        return u, pw, tz
    return None


def _url(base: str, u: str, pw: str, tz: str, sid: str, start: int, stop: int) -> str:
    s = start // 60 * 60
    mins = max(1, math.ceil((stop - s) / 60))
    date = datetime.fromtimestamp(s, ZoneInfo(tz)).strftime("%Y-%m-%d:%H-%M")
    return f"{base}/timeshift/{u}/{pw}/{mins}/{date}/{sid}.ts"


async def _probe(cl: httpx.AsyncClient, url: str) -> bool:
    try:
        async with cl.stream("GET", url, headers={"Range": "bytes=0-4095", "Connection": "close"}) as r:
            if r.status_code not in (200, 206):
                return False
            buf = b""
            async for chunk in r.aiter_bytes():
                buf += chunk
                if len(buf) >= 400:
                    break
        if buf[:7] == b"#EXTM3U":
            return True
        return len(buf) >= 189 and buf[0] == 0x47 and buf[188] == 0x47
    except Exception:
        return False


def _decode(v: Any) -> str:
    try:
        return base64.b64decode(str(v or "")).decode("utf-8")
    except Exception:
        return str(v or "")


async def _listings(cl: httpx.AsyncClient, base: str, u: str, pw: str, ch: Dict[str, Any], now: float) -> List[Dict[str, Any]]:
    try:
        r = await cl.get(f"{base}/player_api.php", params={"username": u, "password": pw,
                                                         "action": "get_simple_data_table", "stream_id": ch["stream_id"]})
        rows = (r.json() or {}).get("epg_listings") or []
    except Exception:
        return []
    days = int(ch.get("tv_archive_duration") or 7) or 7
    out = []
    for x in rows:
        try:
            start, stop = int(x["start_timestamp"]), int(x["stop_timestamp"])
        except Exception:
            continue
        if stop > now or stop <= start or start < now - days * 86400 or str(x.get("has_archive")) != "1":
            continue
        out.append({"sid": ch["stream_id"], "start": start, "stop": stop,
                    "title": _decode(x.get("title")).strip(), "desc": _decode(x.get("description")).strip()[:240]})
    return out


async def _cycle(cl: httpx.AsyncClient) -> None:
    p = _provider()
    channels = _archive_channels()
    if not p or not channels:
        await asyncio.sleep(60)
        return
    base = _base(p)
    skip: set = set()
    acct = await _pick_account(cl, base, skip)
    if not acct:
        _status.update(phase="no idle account")
        await asyncio.sleep(120)
        return
    u, pw, tz = acct
    _status.update(phase="listing", account=u, cycle_started=time.time())
    now = time.time()
    rows: List[Dict[str, Any]] = []
    sem = asyncio.Semaphore(4)

    async def one(ch):
        async with sem:
            rows.extend(await _listings(cl, base, u, pw, ch, now))
    await asyncio.gather(*(one(c) for c in channels))

    known = {d["_id"]: d async for d in _col().find({"start": {"$gte": int(now) - 8 * 86400}}, {"ok": 1, "checked_at": 1, "tries": 1})}
    todo = []
    for r in rows:
        k = f"{r['sid']}:{r['start']}"
        d = known.get(k)
        if d is None or (not d.get("ok") and d.get("tries", 0) < MAX_TRIES and now - d.get("checked_at", 0) > FAIL_RETRY_SECS):
            todo.append(r)
        elif d.get("ok"):
            await _col().update_one({"_id": k}, {"$set": {"title": r["title"], "desc": r["desc"], "stop": r["stop"]}})
    todo.sort(key=lambda r: -r["start"])
    _status.update(phase="checking", queue=len(todo))
    control: Optional[str] = None
    deadline = time.time() + LISTING_REFRESH_SECS
    for r in todo:
        if time.time() > deadline:
            break
        if not await _lease():
            return
        url = _url(base, u, pw, tz, r["sid"], r["start"], r["stop"])
        ok = await _probe(cl, url)
        if not ok and control:
            await asyncio.sleep(0.5)
            if not await _probe(cl, control):
                # The account got busy (someone started watching): result is unreliable, switch account.
                skip.add(u)
                acct = await _pick_account(cl, base, skip)
                if not acct:
                    _status.update(phase="no idle account")
                    return
                u, pw, tz = acct
                control = None
                _status.update(account=u)
                continue
        if ok:
            control = url
        k = f"{r['sid']}:{r['start']}"
        await _col().update_one({"_id": k}, {"$set": {**r, "ok": ok, "checked_at": time.time()}, "$inc": {"tries": 1}}, upsert=True)
        _status["probed"] += 1
        _status["queue"] = max(0, _status.get("queue", 1) - 1)
    await _col().delete_many({"stop": {"$lt": int(time.time()) - 8 * 86400}})
    _status.update(phase="idle", last_cycle=time.time())
    if not todo:
        await asyncio.sleep(300)


async def _loop() -> None:
    await asyncio.sleep(30)
    async with httpx.AsyncClient(timeout=15, follow_redirects=True, headers={"User-Agent": "V2LiveTV/CatchUpCheck"}) as cl:
        while True:
            try:
                if await _lease():
                    await _cycle(cl)
                else:
                    await asyncio.sleep(60)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001
                log.warning("catchup verifier cycle failed: %s", exc)
                await asyncio.sleep(60)


def start(db) -> None:
    global _db, _task
    _db = db
    if os.environ.get("DISABLE_CATCHUP_VERIFIER", "").lower() in ("1", "true", "yes"):
        log.info("catchup verifier disabled via env")
        return
    _task = asyncio.create_task(_loop())


@router.get("/recordings")
async def recordings() -> Response:
    """Confirmed recordings only: {at, channels: {stream_id: [{t, d, s, e}]}} (seconds, oldest first)."""
    global _payload
    if _payload[1] is None or time.time() - _payload[0] > 60:
        now = int(time.time())
        days = {c["stream_id"]: int(c.get("tv_archive_duration") or 7) or 7 for c in _archive_channels()}
        out: Dict[str, List[Dict[str, Any]]] = {}
        async for d in _col().find({"ok": True}, {"sid": 1, "start": 1, "stop": 1, "title": 1, "desc": 1}).sort("start", 1):
            if d["sid"] in days and d["start"] >= now - days[d["sid"]] * 86400:
                out.setdefault(d["sid"], []).append({"t": d.get("title", ""), "d": d.get("desc", ""), "s": d["start"], "e": d["stop"]})
        body = json.dumps({"at": now, "checking": _status.get("phase") == "checking", "channels": out}, separators=(",", ":")).encode()
        _payload = (time.time(), gzip.compress(body, 6))
    return Response(_payload[1], media_type="application/json", headers={"Content-Encoding": "gzip", "Cache-Control": "no-store"})


@router.get("/status")
async def status() -> Dict[str, Any]:
    ok = await _col().count_documents({"ok": True})
    bad = await _col().count_documents({"ok": False})
    return {**{k: v for k, v in _status.items() if k != "account"}, "confirmed": ok, "missing": bad}
