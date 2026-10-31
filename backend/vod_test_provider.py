"""Test VOD provider ("GORILLA DIRECT") — offered ONLY to the accounts in
VOD_TEST_ACCOUNTS so the operator can trial another Xtream panel's movies and
series from V2's stream picker.  Catalogues are fetched lazily (first request
from an allowed account) and kept as compact tuples to stay inside the 512 MB
production limit."""
import os
import time
from typing import Any, Dict, List, Optional, Tuple

import httpx
from fastapi import APIRouter, Header, Query

import vod_match as _vm

router = APIRouter(prefix="/api/vod", tags=["vod-test"])

TTL = 6 * 3600
_movies: Dict[str, List[Tuple[str, Optional[int], str, str]]] = {}   # norm → (id, year, ext, name)
_series: Dict[str, List[Tuple[str, Optional[int], str]]] = {}        # norm → (series_id, year, name)
_loaded = {"movies": 0.0, "series": 0.0}
_series_info: Dict[str, Tuple[float, Dict[str, Any]]] = {}
_tv_find: Dict[str, Tuple[float, List[str], Optional[int]]] = {}


def _cfg() -> Optional[Dict[str, str]]:
    base, user, pwd = (os.environ.get(k, "").strip() for k in ("VOD_TEST_BASE", "VOD_TEST_USERNAME", "VOD_TEST_PASSWORD"))
    if not (base and user and pwd):
        return None
    return {"base": base.rstrip("/"), "user": user, "pwd": pwd, "label": os.environ.get("VOD_TEST_LABEL", "").strip() or "Test Provider · Direct"}


async def _allowed(authorization: Optional[str]) -> bool:
    allowed = {a.strip().lower() for a in os.environ.get("VOD_TEST_ACCOUNTS", "").split(",") if a.strip()}
    acct = _vm._account_from_bearer(authorization)
    if not (allowed and acct and _vm._db is not None):
        return False
    row = await _vm._db.vesper_accounts.find_one({"id": acct}, {"_id": 0, "username": 1})
    return bool(row and (row.get("username") or "").strip().lower() in allowed)


async def _api(c: Dict[str, str], **params) -> Any:
    async with httpx.AsyncClient(timeout=90.0, verify=False, follow_redirects=True, headers={"User-Agent": "ONNowTV/1.0"}) as h:
        r = await h.get(f"{c['base']}/player_api.php", params={"username": c["user"], "password": c["pwd"], **params})
        r.raise_for_status()
        return r.json()


def _year(row: Dict[str, Any], name: str) -> Optional[int]:
    y = str(row.get("year") or "")[:4]
    if y.isdigit():
        return int(y)
    return _vm._split_name(name)[1]


async def _ensure(kind: str, c: Dict[str, str]) -> None:
    if time.time() - _loaded[kind] < TTL:
        return
    if kind == "movies":
        idx: Dict[str, list] = {}
        for row in await _api(c, action="get_vod_streams") or []:
            sid, name = str(row.get("stream_id") or ""), row.get("name") or ""
            key = _vm._norm(_vm._split_name(row.get("title") or name)[0])
            if sid and key:
                idx.setdefault(key, []).append((sid, _year(row, name), (row.get("container_extension") or "mkv").strip("."), name))
        _movies.clear(); _movies.update(idx)
    else:
        idx = {}
        for row in await _api(c, action="get_series") or []:
            sid, name = str(row.get("series_id") or ""), row.get("name") or ""
            key = _vm._norm(_vm._split_name(row.get("title") or name)[0])
            if sid and key:
                idx.setdefault(key, []).append((sid, _year(row, name), name))
        _series.clear(); _series.update(idx)
    _loaded[kind] = time.time()


async def _tv_titles(imdb_id: str) -> Tuple[List[str], Optional[int]]:
    hit = _tv_find.get(imdb_id)
    if hit and time.time() - hit[0] < 7 * 86400:
        return hit[1], hit[2]
    titles, year = [], None
    if _vm._tmdb_get is not None:
        try:
            data = await _vm._tmdb_get(f"/find/{imdb_id}", {"external_source": "imdb_id"})
            tv = (data.get("tv_results") or [None])[0] or {}
            titles = [t for t in [tv.get("name"), tv.get("original_name")] if t]
            year = int(tv["first_air_date"][:4]) if tv.get("first_air_date") else None
        except Exception:  # noqa: BLE001
            pass
    _tv_find[imdb_id] = (time.time(), titles, year)
    return titles, year


def _stream(c: Dict[str, str], url: str, detail: str, i: int) -> Dict[str, Any]:
    label = c["label"] if i == 0 else f"{c['label']} · Alt {i + 1}"
    return {
        "name": label,
        "title": f"{label}\nTest provider (TRAV26 only)\n{detail}",
        "url": url,
        "_addon_id": "gorilla-direct",
        "_addon_name": c["label"],
        "_addon_source": c["label"],
        "_quality_label": "HD",
        "_is_english": True,
        "_english_strict": True,
        "behaviorHints": {"notWebReady": False},
    }


@router.get("/test-direct")
async def test_direct(
    type: str = Query(default="movie"),
    id: str = Query(default=""),
    authorization: Optional[str] = Header(default=None),
) -> Dict[str, Any]:
    c = _cfg()
    if not c or not await _allowed(authorization):
        return {"streams": []}
    parts = id.split(":")
    imdb = parts[0]
    if not imdb.startswith("tt"):
        return {"streams": []}

    if type == "movie":
        await _ensure("movies", c)
        titles, year = await _vm._titles_for(imdb, "", None)
        cands, seen = [], set()
        for t in titles:
            for e in _movies.get(_vm._norm(t), []):
                if e[0] in seen or (year and e[1] and abs(e[1] - year) > 1):
                    continue
                seen.add(e[0]); cands.append(e)
        cands.sort(key=lambda e: 0 if (year and e[1] == year) else 1)
        return {"streams": [
            _stream(c, f"{c['base']}/movie/{c['user']}/{c['pwd']}/{sid}.{ext}", f"{name} · {ext.upper()}", i)
            for i, (sid, _y, ext, name) in enumerate(cands[:3])
        ]}

    if len(parts) < 3 or not (parts[1].isdigit() and parts[2].isdigit()):
        return {"streams": []}
    season, episode = parts[1], int(parts[2])
    await _ensure("series", c)
    titles, year = await _tv_titles(imdb)
    cands, seen = [], set()
    for t in titles:
        for e in _series.get(_vm._norm(t), []):
            if e[0] in seen or (year and e[1] and abs(e[1] - year) > 1):
                continue
            seen.add(e[0]); cands.append(e)
    cands.sort(key=lambda e: 0 if (year and e[1] == year) else 1)
    out = []
    for series_id, _y, name in cands[:2]:
        cached = _series_info.get(series_id)
        if cached and time.time() - cached[0] < 3600:
            info = cached[1]
        else:
            try:
                info = await _api(c, action="get_series_info", series_id=series_id) or {}
            except Exception:  # noqa: BLE001
                continue
            if len(_series_info) > 60:
                _series_info.pop(next(iter(_series_info)))
            _series_info[series_id] = (time.time(), {"episodes": info.get("episodes") or {}})
        eps = (info.get("episodes") or {}).get(season) or []
        for ep in eps:
            try:
                num = int(ep.get("episode_num") or 0)
            except (TypeError, ValueError):
                continue
            if num == episode:
                ext = (ep.get("container_extension") or "mkv").strip(".")
                tag = f"S{int(season):02d}E{episode:02d}"
                out.append(_stream(c, f"{c['base']}/series/{c['user']}/{c['pwd']}/{ep.get('id')}.{ext}",
                                   f"{name} {tag} · {ep.get('title') or ''} · {ext.upper()}", len(out)))
                break
    return {"streams": out}
