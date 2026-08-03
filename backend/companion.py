"""Companion-app data endpoints (v2.18.0).

The phone Companion web app (served by the launcher backend at
/remote) needs a LIGHTWEIGHT Live TV channel list — the full
/api/xtream/instant-bundle is ~12 MB with 72 h of EPG, far too heavy
for a phone browse tab.  This router serves a slim projection of the
same in-memory bundle instant_bundle.py already maintains:
channels (id / name / logo / category) + categories only.

Per-channel now/next EPG stays on the existing
/api/xtream/epg/{stream_id} endpoint.
"""

from __future__ import annotations

import gzip
import json
import logging
import re
import time

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from instant_bundle import _state as _bundle_state

log = logging.getLogger("companion")

router = APIRouter(prefix="/api/companion", tags=["companion"])

# Bound at boot from server.py (same pattern as vesper_sync).
_db = None


def configure_companion(db) -> None:
    global _db
    _db = db

# Cache the gzipped slim payload per bundle generation.
_slim_cache: dict = {"key": None, "gz": None}


@router.get("/livetv/channels")
async def companion_livetv_channels() -> Response:
    channels = _bundle_state.get("channels") or []
    if not channels:
        raise HTTPException(503, "Channel bundle not ready yet — try again shortly.")
    key = _bundle_state.get("channels_fetched_at") or 0
    if _slim_cache["key"] != key or _slim_cache["gz"] is None:
        slim = {
            "categories": _bundle_state.get("categories") or [],
            "channels": [
                {
                    "stream_id": str(c.get("stream_id") or ""),
                    "name": c.get("name") or "",
                    "logo": c.get("logo") or "",
                    "category_id": str(c.get("category_id") or ""),
                }
                for c in channels
            ],
            "generated_at": key,
        }
        _slim_cache["gz"] = gzip.compress(
            json.dumps(slim, separators=(",", ":")).encode("utf-8"), 6
        )
        _slim_cache["key"] = key
    return Response(
        content=_slim_cache["gz"],
        media_type="application/json",
        headers={
            "Content-Encoding": "gzip",
            "Cache-Control": "public, max-age=300",
        },
    )


@router.get("/vesper/profiles")
async def companion_vesper_profiles(u: str = "") -> dict:
    """Profile names on a Vesper account — powers the Companion app's
    settings profile picker.  Reads the account's silent cloud-sync
    snapshot (`vesper_sync`), which mirrors the TV's localStorage
    (`onnowtv-profiles-v1*` keys)."""
    u = (u or "").strip()
    if not u:
        raise HTTPException(400, "missing_username")
    if _db is None:
        raise HTTPException(503, "profiles store not ready")
    doc = await _db.vesper_sync.find_one(
        {"username": {"$regex": f"^{re.escape(u)}$", "$options": "i"}},
        {"_id": 0, "data": 1},
    )
    if not doc:
        return {"found": False, "profiles": []}
    names: list = []
    seen: set = set()
    for key, raw in (doc.get("data") or {}).items():
        if not str(key).startswith("onnowtv-profiles-v1"):
            continue
        try:
            arr = json.loads(raw) if isinstance(raw, str) else raw
        except Exception:
            continue
        if not isinstance(arr, list):
            continue
        for p in arr:
            if not isinstance(p, dict):
                continue
            name = str(p.get("name") or "").strip()
            pid = str(p.get("id") or name)
            if name and pid not in seen:
                seen.add(pid)
                names.append(name)
    return {"found": True, "profiles": names}


_SPORT_RE = re.compile(
    r"sport|espn|dazn|fight|ufc|wwe|boxing|f1|motogp|motorsport|racing|nfl|nba|mlb|nhl|golf|tennis|cricket|rugby|gaa|loitv|clubber|pre-season|kayo",
    re.I,
)
_PPV_RE = re.compile(r"ppv|pay[\s.\-]?per[\s.\-]?view|\bevents?\b", re.I)
_EVENT_RE = re.compile(
    r"\bvs?\.?\s|\bv\b| @ |derby|grand prix|gp\b|final|cup|championship|round \d|race|match|test\b|open\b",
    re.I,
)
_whatson_cache = {"at": 0.0, "count": 0}


@router.get("/livetv/sections")
async def companion_livetv_sections() -> dict:
    """Category ids classified into Sports / PPV buckets for the
    Companion app's Live TV section tabs, plus an approximate count of
    live sport events airing right now (cached 120 s)."""
    cats = _bundle_state.get("categories") or []
    sports, ppv = [], []
    for c in cats:
        name = str(c.get("name") or c.get("category_name") or "").strip()
        cid = str(c.get("id") or c.get("category_id") or "").strip()
        if not name or not cid:
            continue
        if _PPV_RE.search(name):
            ppv.append({"id": cid, "name": name})
        elif _SPORT_RE.search(name):
            sports.append({"id": cid, "name": name})
    now = time.time()
    if now - _whatson_cache["at"] > 120:
        sport_ids = {c["id"] for c in sports}
        channels = _bundle_state.get("channels") or []
        epg = _bundle_state.get("epg") or {}
        seen: set = set()
        for c in channels:
            if str(c.get("category_id")) not in sport_ids:
                continue
            progs = epg.get(str(c.get("stream_id") or "")) or []
            for p in progs:
                if p.get("startTimestamp", 0) <= now < p.get("stopTimestamp", 0):
                    t = str(p.get("title") or "")
                    if _EVENT_RE.search(t):
                        seen.add(t.strip().lower())
                    break
        _whatson_cache["at"] = now
        _whatson_cache["count"] = len(seen)
    return {"sports": sports, "ppv": ppv, "whats_on_live": _whatson_cache["count"]}


def _slim_prog(p) -> dict | None:
    if not p:
        return None
    # Strip the provider's text superscript "Live" tag — the phone
    # renders its own LIVE pill.
    title = str(p.get("title", "")).replace("\u1d38\u1da6\u1d5b\u1d49", "").strip()
    return {
        "title": title,
        "start": p.get("startTimestamp", 0),
        "stop": p.get("stopTimestamp", 0),
    }


@router.get("/livetv/guide")
async def companion_livetv_guide(cat: str = "", ids: str = "") -> dict:
    """Now/next guide rows for one category (?cat=) or an explicit
    stream-id list (?ids=1,2,3 — favourites).  Served from the same
    in-memory EPG the TV's instant bundle uses."""
    channels = _bundle_state.get("channels") or []
    epg = _bundle_state.get("epg") or {}
    if ids:
        idset = {s.strip() for s in ids.split(",") if s.strip()}
        subset = [c for c in channels if str(c.get("stream_id")) in idset]
    elif cat:
        subset = [c for c in channels if str(c.get("category_id")) == cat]
    else:
        raise HTTPException(400, "cat_or_ids_required")
    now = time.time()
    out = []
    for c in subset[:400]:
        sid = str(c.get("stream_id") or "")
        progs = epg.get(sid) or []
        cur = nxt = None
        for i, p in enumerate(progs):
            st, sp = p.get("startTimestamp", 0), p.get("stopTimestamp", 0)
            if st <= now < sp:
                cur = p
                nxt = progs[i + 1] if i + 1 < len(progs) else None
                break
            if st > now:
                nxt = p
                break
        out.append({
            "stream_id": sid,
            "name": c.get("name") or "",
            "logo": c.get("logo") or "",
            "now": _slim_prog(cur),
            "next": _slim_prog(nxt),
        })
    return {"channels": out, "generated_at": int(now)}


_CW_EP_RE = re.compile(r"^(tt\d+):(\d+):(\d+)$")


@router.get("/vesper/continue")
async def companion_vesper_continue(u: str = "") -> dict:
    """Continue Watching entries for a Vesper account — powers the
    Companion Movies tab's resume rail.  Merges all
    `onnowtv-continue-watching-v1*` keys from the cloud-sync snapshot."""
    u = (u or "").strip()
    if not u:
        raise HTTPException(400, "missing_username")
    if _db is None:
        raise HTTPException(503, "store not ready")
    doc = await _db.vesper_sync.find_one(
        {"username": {"$regex": f"^{re.escape(u)}$", "$options": "i"}},
        {"_id": 0, "data": 1},
    )
    if not doc:
        return {"found": False, "items": []}
    entries: dict = {}
    for key, raw in (doc.get("data") or {}).items():
        if not str(key).startswith("onnowtv-continue-watching-v1"):
            continue
        try:
            arr = json.loads(raw) if isinstance(raw, str) else raw
        except Exception:
            continue
        if not isinstance(arr, list):
            continue
        for e in arr:
            if not isinstance(e, dict) or not e.get("id"):
                continue
            eid = str(e["id"])
            prev = entries.get(eid)
            if prev is None or (e.get("updatedAt") or 0) > (prev.get("updatedAt") or 0):
                entries[eid] = e
    items = sorted(entries.values(), key=lambda x: x.get("updatedAt") or 0, reverse=True)[:20]
    out = []
    for e in items:
        eid = str(e["id"])
        m = _CW_EP_RE.match(eid)
        imdb = m.group(1) if m else (eid if eid.startswith("tt") else "")
        out.append({
            "id": eid,
            "imdb": imdb,
            "season": int(m.group(2)) if m else None,
            "episode": int(m.group(3)) if m else None,
            "type": e.get("type") or ("series" if m else "movie"),
            "title": str(e.get("title") or "")[:200],
            "poster": str(e.get("poster") or "")[:1000],
            "backdrop": str(e.get("backdrop") or "")[:1000],
            "position_ms": int(e.get("positionMs") or 0),
            "duration_ms": int(e.get("durationMs") or 0),
            "updated_at": e.get("updatedAt") or 0,
        })
    return {"found": True, "items": out}


# ── v2.18.6 — "What's On Live" sports hub for the Companion app ──
# Python port of the Live TV APK's LiveSportsClassifier so the phone
# shows the SAME sport buckets + live channels the TV hub shows.

_WO_SUP_LIVE = "\u1d38\u1da6\u1d5b\u1d49"

_WO_RULES: list = [
    ("f1", ["formula 1", "formula one", " f1 ", "grand prix", "gp weekend",
            "monza", "silverstone", "spa francorchamps", "singapore gp"]),
    ("motorsport", ["motogp", "moto gp", "moto2", "moto3", "formula e",
                    "nascar", "indycar", "supercars", "wrc", "world rally",
                    "goodwood festival", "festival of speed", "le mans",
                    "world endurance", "world superbike", "wsbk", "extreme e"]),
    ("golf", ["golf", "pga tour", "dp world tour", "liv golf", "ryder cup",
              "solheim cup", "presidents cup", "the masters", "u.s. open golf",
              "the open", "scottish open", "irish open", " lpga"]),
    ("cricket", ["cricket", "test match", "the ashes", " odi ", " t20 ",
                 "ipl ", "big bash", "bbl", "world test", "county championship",
                 "one day international"]),
    ("nrl", ["nrl ", "rugby league", "state of origin", "super league",
             "grand final", "kangaroos", "kiwis"]),
    ("rugby", ["rugby", "six nations", "rugby championship", "rugby world cup",
               "super rugby", "wallabies", "all blacks", "premiership rugby",
               "top 14", "united rugby"]),
    ("afl", [" afl ", "aussie rules", "australian football", "afl live",
             "afl round", "afl finals", "aflw"]),
    ("nfl", [" nfl ", "super bowl", "monday night football",
             "thursday night football", "sunday night football",
             "college football", "ncaa football"]),
    ("nba", [" nba ", "basketball", "wnba", "euroleague basketball", "nba finals",
             "ncaa basketball", "march madness"]),
    ("nhl", [" nhl ", "ice hockey", "hockey night", "stanley cup"]),
    ("mlb", [" mlb ", " mlb:", "baseball", "world series",
             "mlb network", "mlb tonight", "mlb live", "mlb game",
             "yankees", "red sox", "dodgers", "mets", "brewers",
             "pirates", "phillies", "orioles", "blue jays", "astros",
             "oakland athletics", "mariners", "twins", "guardians", "tigers",
             "royals", "white sox", "braves", "marlins", "nationals",
             "padres", "rockies", "diamondbacks", "reds", "cubs",
             "fenway", "wrigley", "yankee stadium", "dodger stadium",
             "citi field", "camden yards", "coors field",
             "sunday night baseball", "little league world series"]),
    ("tennis", ["tennis", "wimbledon", "us open tennis", "australian open",
                "french open", "roland garros", " atp ", " wta ", "davis cup",
                "billie jean king cup"]),
    ("mma", [" ufc ", "ufc ", "boxing", "heavyweight", "mma ", "bellator",
             "one championship", "professional fighters league",
             "world boxing", "sky sports boxing", "top rank boxing"]),
    ("wwe", ["wwe ", "wwe raw", "smackdown", "wrestlemania", "aew ",
             "all elite wrestling", "impact wrestling"]),
    ("cycling", ["cycling", "tour de france", "giro d'italia", "vuelta",
                 "world tour cycling", "uci "]),
    ("athletics", ["athletics", "diamond league", "world athletics", "olympics live",
                   "marathon", "track and field"]),
    ("soccer", ["premier league", "champions league", "europa league",
                "world cup", "euro 20", "euro 21", "euro 22", "euro 24",
                "euro 26", "copa america", "copa libertadores",
                "la liga", "serie a", "bundesliga", "ligue 1", " mls ",
                "fa cup", "carabao cup", "efl", "womens super league",
                "wsl ", "afc cup", "nations league", "concacaf",
                "football live", "soccer", "fifa ", "uefa ", "football"]),
]

_WO_ORDER = ["soccer", "f1", "motorsport", "golf", "cricket", "tennis",
             "rugby", "nrl", "afl", "nfl", "nba", "nhl", "mlb",
             "mma", "cycling", "athletics", "wwe", "other"]

_WO_LABEL = {
    "soccer": "Football", "f1": "Formula 1", "motorsport": "Motorsport",
    "golf": "Golf", "cricket": "Cricket", "tennis": "Tennis",
    "rugby": "Rugby Union", "nrl": "Rugby League", "afl": "AFL",
    "nfl": "NFL", "nba": "Basketball", "nhl": "Ice Hockey", "mlb": "Baseball",
    "mma": "Combat Sports", "cycling": "Cycling", "athletics": "Athletics",
    "wwe": "Wrestling", "other": "Other Sport",
}

_WO_COLOR = {
    "soccer": "#3EB44A", "f1": "#E10600", "motorsport": "#FF6A00",
    "golf": "#7FC57F", "cricket": "#CC1F1F", "tennis": "#DCFF3F",
    "rugby": "#1F3E7A", "nrl": "#6E37FF", "afl": "#E30E2E",
    "nfl": "#875A2B", "nba": "#F57C1F", "nhl": "#6FDCFF", "mlb": "#1257A6",
    "mma": "#B80020", "cycling": "#FFCB05", "athletics": "#FF3B7A",
    "wwe": "#C8A027", "other": "#8FA1BF",
}

_WO_NON_LIVE = [
    " highlights", "highlights ", "extended highlights",
    "match highlights", "goals & highlights", "goals and highlights",
    " replay ", " replay:", "replayed", " rerun", " re-run",
    " encore ", "encore:", " review ", "review:", " recap ",
    "recap:", "post-match", "post match", " reaction ",
    "reaction:", "build-up", "build up", " preview ", "preview:",
    "best of ", "top 10", "top ten", " classic ", "classic:",
    "throwback", "greatest", "documentary", "the story of",
    " special ",
    " draft ", "draft:", " draft.", "hall of fame",
    "retrospective", " retro ", "retro:", "top plays",
    "top moments", "all-time", " archive ", "archives:",
    " vintage ", "history of", " retro-", "flashback",
    "iconic moments", "greatest moments", "the making of",
]

_WO_SPORT_CH = ["sport", "espn", "bein", "dazn", "tsn ", "sky sports",
                "fox sports", "nbc sports", "eurosport"]


def _wo_live_word(title: str) -> bool:
    if _WO_SUP_LIVE in title:
        return True
    if len(title) < 4:
        return False
    folded = "".join(c.lower() if c.isalnum() else " " for c in title)
    return " live " in f" {folded} "


def _wo_non_live(title: str) -> bool:
    hay = f" {title.lower()} "
    return any(m in hay for m in _WO_NON_LIVE)


def _wo_classify(title: str, channel_name: str) -> str | None:
    hay = f" {title.lower()} {channel_name.lower()} "
    for bucket, needles in _WO_RULES:
        for n in needles:
            if n in hay:
                return bucket
    ch = channel_name.lower()
    if any(s in ch for s in _WO_SPORT_CH):
        return "other"
    return None


_wo_hub_cache: dict = {"at": 0.0, "data": None}


@router.get("/livetv/whatson")
async def companion_livetv_whatson() -> dict:
    """Sport buckets + the live channels airing each sport RIGHT NOW.
    Mirrors the TV app's What's On Live hub (same classifier, same
    live-word + non-live gating).  Cached 60 s."""
    now = time.time()
    if _wo_hub_cache["data"] is not None and now - _wo_hub_cache["at"] < 60:
        return _wo_hub_cache["data"]
    channels = _bundle_state.get("channels") or []
    epg = _bundle_state.get("epg") or {}
    buckets: dict = {}
    seen: dict = {}
    for c in channels:
        sid = str(c.get("stream_id") or "")
        progs = epg.get(sid) or []
        cur = None
        for p in progs:
            if p.get("startTimestamp", 0) <= now < p.get("stopTimestamp", 0):
                cur = p
                break
        if not cur:
            continue
        title = str(cur.get("title") or "")
        if not (cur.get("live") or _wo_live_word(title)):
            continue
        if _wo_non_live(title):
            continue
        name = str(c.get("name") or "")
        bucket = _wo_classify(title, name)
        if not bucket:
            continue
        if sid in seen.setdefault(bucket, set()):
            continue
        seen[bucket].add(sid)
        buckets.setdefault(bucket, []).append({
            "stream_id": sid,
            "name": name,
            "logo": c.get("logo") or "",
            "title": title.replace(_WO_SUP_LIVE, "").strip(),
            "start": cur.get("startTimestamp", 0),
            "stop": cur.get("stopTimestamp", 0),
        })
    sports = []
    for bid in _WO_ORDER:
        lst = buckets.get(bid)
        if not lst:
            continue
        sports.append({
            "id": bid,
            "label": _WO_LABEL.get(bid, "Other Sport"),
            "color": _WO_COLOR.get(bid, "#8FA1BF"),
            "count": len(lst),
            "channels": lst,
        })
    data = {
        "generated_at": int(now),
        "total": sum(len(v) for v in buckets.values()),
        "sports": sports,
    }
    _wo_hub_cache["at"] = now
    _wo_hub_cache["data"] = data
    return data
