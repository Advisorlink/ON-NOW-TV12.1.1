"""
Live Sports Match Centre — ESPN proxy (site.api.espn.com, free & keyless).

Replaces the suspended API-Sports integration.  ESPN's undocumented
site API needs NO key and has no practical rate limit, so polling is
fast (30 s) and the boards are far richer:

  • scoreboard  → live game list, scores, linescores, team colours
  • summary     → deep boxscore (AFL disposals, NRL run metres,
                  soccer possession/shots), scoring timelines
  • racing      → F1 running order with driver flags

Payload stays normalized exactly like the old router so the Android
`StatsPlayerActivity` renderer keeps working, with new extras:
`home/away.color|abbr|form|scoreDetail`, `leaderboard[].flag`.
"""

from __future__ import annotations

import logging
import re
import time
from typing import Any, Dict, List, Optional, Tuple

import httpx
from fastapi import APIRouter, HTTPException, Query

logger = logging.getLogger("vesper.livestats")

router = APIRouter(prefix="/api/livestats")

ESPN = "https://site.api.espn.com/apis/site/v2/sports"

LIST_TTL = 60        # seconds — shared scoreboard per league
SUMMARY_TTL = 30     # seconds — per-event deep boxscore

# Bucket IDs match LiveSportsClassifier on the Android side.
SPORTS: Dict[str, Dict[str, Any]] = {
    "soccer": {"label": "Football", "kind": "soccer",
               "leagues": [("soccer", "all")]},
    "afl":    {"label": "AFL", "kind": "afl",
               "leagues": [("australian-football", "afl")]},
    "nrl":    {"label": "Rugby League", "kind": "rugby",
               "leagues": [("rugby-league", "3")]},
    "rugby":  {"label": "Rugby Union", "kind": "rugby",
               "leagues": [("rugby", "180659"), ("rugby", "242041"),
                           ("rugby", "267979"), ("rugby", "270557"),
                           ("rugby", "289234")]},
    "nba":    {"label": "Basketball", "kind": "generic",
               "leagues": [("basketball", "nba")]},
    "mlb":    {"label": "Baseball", "kind": "generic",
               "leagues": [("baseball", "mlb")]},
    "nhl":    {"label": "Ice Hockey", "kind": "generic",
               "leagues": [("hockey", "nhl")]},
    "nfl":    {"label": "NFL", "kind": "generic",
               "leagues": [("football", "nfl")]},
    "f1":     {"label": "Formula 1", "kind": "racing",
               "leagues": [("racing", "f1")]},
    "mma":    {"label": "MMA", "kind": "mma",
               "leagues": [("mma", "ufc")]},
    # v2.16.1 — Cricket: ESPN uses numeric Cricinfo trophy ids.
    # World Cup / World Test Championship / IPL / Big Bash / County.
    "cricket": {"label": "Cricket", "kind": "cricket",
                "leagues": [("cricket", "8039"), ("cricket", "19430"),
                            ("cricket", "8048"), ("cricket", "8044"),
                            ("cricket", "8052")]},
    # v2.16.1 — Tennis: scoreboard events are TOURNAMENTS whose
    # groupings[].competitions[] hold the individual matches — they
    # get flattened into pseudo-events before resolution.
    "tennis": {"label": "Tennis", "kind": "tennis",
               "leagues": [("tennis", "atp"), ("tennis", "wta")]},
    # v2.16.3 — Golf: leaderboard-style events across PGA / LPGA /
    # DP World / Champions / LIV tours.  Payload uses the same
    # leaderboard[] shape as racing so the Android renderer needs
    # no new adapters, plus a golf-specific "course" venue string
    # and current-round header.
    "golf": {"label": "Golf", "kind": "golf",
             "leagues": [("golf", "pga"), ("golf", "lpga"),
                         ("golf", "eur"), ("golf", "champions-tour"),
                         ("golf", "liv")]},
}

SUPERSCRIPT_LIVE = "\u1d38\u1da6\u1d5b\u1d49"

_STOP = {
    "live", "the", "vs", "v", "at", "and", "of", "on", "in", "round",
    "week", "match", "game", "games", "cup", "league", "rugby", "union",
    "football", "soccer", "hockey", "baseball", "basketball", "afl",
    "nfl", "nba", "nhl", "mlb", "mma", "ufc", "session", "night", "day",
    "fc", "womens", "women", "mens", "men",
}

_cache: Dict[str, Tuple[float, Any]] = {}


def _cache_get(key: str, ttl: int) -> Any:
    hit = _cache.get(key)
    if not hit:
        return None
    ts, data = hit
    if time.time() - ts > ttl:
        return None
    return data


def _cache_put(key: str, data: Any) -> None:
    _cache[key] = (time.time(), data)


async def _espn_get(path: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    async with httpx.AsyncClient(timeout=12.0) as client:
        r = await client.get(f"{ESPN}/{path}", params=params or {},
                             headers={"Accept": "application/json"})
    r.raise_for_status()
    return r.json()


async def _scoreboard(sport_path: str, league: str,
                      dates: Optional[str] = None) -> List[dict]:
    key = f"sb:{sport_path}/{league}:{dates or ''}"
    cached = _cache_get(key, LIST_TTL)
    if cached is not None:
        return cached
    try:
        params = {"dates": dates} if dates else None
        data = await _espn_get(f"{sport_path}/{league}/scoreboard", params)
        events = data.get("events") or []
    except Exception as exc:
        logger.warning("espn scoreboard %s/%s failed: %s", sport_path, league, exc)
        stale = _cache.get(key)
        return stale[1] if stale else []
    _cache_put(key, events)
    return events


async def _summary(sport_path: str, league: str, event_id: str) -> Dict[str, Any]:
    key = f"sum:{sport_path}/{league}/{event_id}"
    cached = _cache_get(key, SUMMARY_TTL)
    if cached is not None:
        return cached
    try:
        data = await _espn_get(f"{sport_path}/{league}/summary", {"event": event_id})
    except Exception as exc:
        logger.warning("espn summary %s failed: %s", event_id, exc)
        stale = _cache.get(key)
        return stale[1] if stale else {}
    _cache_put(key, data)
    return data


def _state(ev: dict) -> str:
    return str(((ev.get("status") or {}).get("type") or {}).get("state") or "")


# ── programme-title → live-game fuzzy matcher ────────────────────────

def _norm(s: str) -> str:
    s = s.replace(SUPERSCRIPT_LIVE, " ")
    s = re.sub(r"[^a-z0-9 ]+", " ", s.lower())
    return re.sub(r"\s+", " ", s).strip()


def _tokens(s: str) -> set:
    return {t for t in _norm(s).split() if len(t) > 1 and t not in _STOP}


def _competitors(ev: dict) -> Tuple[dict, dict]:
    comp = (ev.get("competitions") or [{}])[0]
    home, away = {}, {}
    comps = comp.get("competitors") or []
    for c in comps:
        if c.get("homeAway") == "home":
            home = c
        elif c.get("homeAway") == "away":
            away = c
    if not home and not away and len(comps) >= 2:
        home, away = comps[0], comps[1]
    return home, away


def _side_names(c: dict) -> List[str]:
    t = c.get("team") or {}
    ath = c.get("athlete") or {}
    return [n for n in (t.get("displayName"), t.get("shortDisplayName"),
                        t.get("name"), ath.get("displayName")) if n]


def _match_score(title_tokens: set, title_norm: str, names: List[str]) -> float:
    best = 0.0
    for name in names:
        nn = _norm(name)
        if nn and nn in title_norm:
            best = max(best, 3.0)
            continue
        overlap = _tokens(name) & title_tokens
        if any(len(t) >= 3 for t in overlap):
            best = max(best, 1 + 0.5 * max(0, len(overlap) - 1))
    return best


# Archive / retro title markers — mirrors the frontend classifier's
# NON_LIVE_MARKERS.  Used to block the `only_live` fallback so an
# archive rebroadcast tagged with the sport bucket (e.g. "2006 MLB
# Draft") cannot inherit today's live scoreboard.
_ARCHIVE_MARKERS = (
    "highlights", " replay", "replayed", "rerun", "re-run",
    "encore", "review", "recap", "post-match", "post match",
    "reaction", "build-up", " preview", "best of ", "top 10",
    "top ten", "classic", "throwback", "greatest", "documentary",
    "the story of", "special", " draft", "hall of fame",
    "retrospective", "retro", "archive", "vintage", "history of",
    "flashback", "iconic", "the making of", "top plays",
    "top moments", "all-time",
)


def _looks_like_archive(title: str) -> bool:
    """True when the EPG title suggests an archive/retro rebroadcast
    rather than a live match.  Also fires on 4-digit year prefixes
    (`1998`, `2006`) which are strong retro signals."""
    t = " " + title.lower() + " "
    if any(m in t for m in _ARCHIVE_MARKERS):
        return True
    # 4-digit year at the start of the title → almost certainly a
    # retro rebroadcast ("2006 Major League Baseball Draft",
    # "1998 World Cup Final").
    stripped = title.lstrip()
    if len(stripped) >= 5 and stripped[:4].isdigit():
        year = int(stripped[:4])
        if 1950 <= year <= 2020:
            return True
    return False


def _looks_like_generic(title: str, kind: str) -> bool:
    """True when the EPG title is generic sport-genre text ("Live
    Football", "MLB Baseball", "PGA Tour") with no specific team /
    fighter / player tokens — safe to fall back to the single live
    event.  False when the title contains 2+ meaningful tokens (real
    competitor names), where a bad match risks false-attribution.

    Tennis/racing/golf are always considered generic because their
    EPG titles rarely name the specific players/tournament exactly."""
    if kind in ("racing", "golf", "tennis", "mma"):
        return True
    meaningful = [t for t in _tokens(title) if len(t) >= 3]
    return len(meaningful) < 2


def _resolve(title: str, events: List[dict], kind: str) -> Tuple[Optional[dict], str]:
    tn, tt = _norm(title), _tokens(title)
    best, best_score, best_sides = None, 0.0, 0
    for ev in events:
        if kind in ("racing", "mma", "golf"):
            s = _match_score(tt, tn, [ev.get("name") or "", ev.get("shortName") or ""])
            sides = 1 if s > 0 else 0
        else:
            home, away = _competitors(ev)
            hs = _match_score(tt, tn, _side_names(home))
            as_ = _match_score(tt, tn, _side_names(away))
            s = hs + as_
            sides = (1 if hs > 0 else 0) + (1 if as_ > 0 else 0)
        if s > best_score:
            best, best_score, best_sides = ev, s, sides
    if best is not None and (best_sides >= 2 or best_score >= 3):
        return best, "matched"
    # v2.16.4 — Restored the "single live event" fallback for ALL
    # sports where ESPN typically has a small live scoreboard and
    # EPG titles are often generic ("Live Football", "PGA Tour").
    # Gated by _looks_like_archive() so the "2006 MLB Draft"
    # false-attribution bug stays fixed, AND by _looks_like_generic()
    # so a specific title like "Brewers vs Pirates" that fails to
    # match any live game returns `no_match` rather than being
    # falsely attributed to the current live game.
    if (events and not _looks_like_archive(title)
            and _looks_like_generic(title, kind)):
        return (best, "matched") if best is not None and best_score > 0 \
            else (events[0], "only_live")
    return None, "no_match"


# ── normalized board payload builders ────────────────────────────────

def _shape(sport: str, found: bool = True, **kw: Any) -> Dict[str, Any]:
    cfg = SPORTS[sport]
    base: Dict[str, Any] = {
        "found": found,
        "sport": sport,
        "sportLabel": cfg["label"],
        "nextRefreshSecs": 30 if found else 90,
    }
    base.update(kw)
    return base


def _num(x: Any) -> Optional[float]:
    if x is None:
        return None
    if isinstance(x, (int, float)):
        return float(x)
    try:
        return float(str(x).replace("%", "").replace(",", "").strip())
    except ValueError:
        return None


PERCENT_STATS = {"possession", "territory", "possessionpct", "goalaccuracy",
                 "disposalefficiency", "passpct", "shotpct", "tacklepct"}


def _fmt_stat(name: str, value: Any) -> str:
    v = _num(value)
    if v is None:
        return str(value)
    lname = name.lower()
    if lname in PERCENT_STATS:
        if 0 < v <= 1.0:
            v *= 100
        return f"{int(round(v))}%"
    if v == int(v):
        return str(int(v))
    return f"{v:g}"


def _bar(name: str, label: str, hv: Any, av: Any) -> Optional[Dict[str, Any]]:
    h, a = _num(hv), _num(av)
    if h is None or a is None:
        return None
    lname = name.lower()
    if lname in PERCENT_STATS:
        if 0 < h <= 1.0 and 0 < a <= 1.0:
            h, a = h * 100, a * 100
        if h <= 0 and a <= 0:
            return None
        pct = int(round(h)) if (h + a) > 90 else int(round(h * 100 / max(h + a, 1)))
    else:
        total = h + a
        if total <= 0:
            return None
        pct = int(round(h * 100 / total))
    return {
        "label": label,
        "home": _fmt_stat(name, hv),
        "away": _fmt_stat(name, av),
        "homePct": max(2, min(98, pct)),
    }


def _period_row(label: str, hv: Any, av: Any) -> Dict[str, str]:
    return {
        "label": label,
        "home": "-" if hv in (None, "") else str(hv),
        "away": "-" if av in (None, "") else str(av),
    }


def _team_side(c: dict, kind: str) -> Dict[str, Any]:
    t = c.get("team") or {}
    ath = c.get("athlete") or {}
    name = t.get("displayName") or ath.get("displayName") or ""
    logo = t.get("logo") or ""
    if not logo:
        logos = t.get("logos") or []
        if logos:
            logo = logos[0].get("href") or ""
    color = t.get("color") or ""
    form = ""
    for rec in c.get("records") or []:
        if rec.get("type") == "total" and re.fullmatch(r"[WLD]{2,6}", str(rec.get("summary") or "")):
            form = rec["summary"]
    side: Dict[str, Any] = {
        "name": name,
        "abbr": t.get("abbreviation") or "",
        "logo": logo,
        "score": c.get("score"),
        "color": f"#{color.lstrip('#')}" if color else "",
        "form": form,
    }
    if kind == "afl":
        ls = c.get("linescores") or []
        if ls:
            last = ls[-1]
            g = last.get("cumulativeGoalsDisplayValue")
            b = last.get("cumulativeBehindsDisplayValue")
            if g is not None and b is not None:
                side["scoreDetail"] = f"{g}.{b}"
    return side


def _status_block(ev: dict, comp: dict) -> Dict[str, Any]:
    st = comp.get("status") or ev.get("status") or {}
    t = st.get("type") or {}
    live = t.get("state") == "in"
    clock = ""
    if live:
        dc = str(st.get("displayClock") or "").strip()
        dp = str(st.get("displayPeriod") or "").strip()
        if dc and dc not in ("0:00", "0'"):
            clock = f"{dp} · {dc}" if dp and dp not in dc else dc
        else:
            clock = t.get("shortDetail") or ""
    return {
        "short": t.get("shortDetail") or "",
        "long": t.get("detail") or t.get("description") or "",
        "clock": clock,
        "live": live,
    }


def _stats_map(summary: dict) -> Tuple[Dict[str, Tuple[Any, str]], Dict[str, Tuple[Any, str]]]:
    """boxscore.teams → {statName: (displayValue, label)} per side."""
    home: Dict[str, Tuple[Any, str]] = {}
    away: Dict[str, Tuple[Any, str]] = {}
    for t in (summary.get("boxscore") or {}).get("teams") or []:
        target = home if t.get("homeAway") == "home" else away
        for item in t.get("statistics") or []:
            inner = item.get("stats")
            if isinstance(inner, list):
                for s in inner:
                    target[str(s.get("name"))] = (s.get("displayValue"), s.get("label") or "")
            elif item.get("name"):
                target[str(item["name"])] = (item.get("displayValue"), item.get("label") or "")
    return home, away


def _curated_bars(keys: List[Tuple[str, str]],
                  hmap: Dict[str, Tuple[Any, str]],
                  amap: Dict[str, Tuple[Any, str]]) -> List[Dict[str, Any]]:
    bars = []
    for name, label in keys:
        if name in hmap or name in amap:
            b = _bar(name, label,
                     (hmap.get(name) or (None, ""))[0],
                     (amap.get(name) or (None, ""))[0])
            if b:
                bars.append(b)
    return bars


def _generic_bars(hmap: Dict[str, Tuple[Any, str]],
                  amap: Dict[str, Tuple[Any, str]],
                  limit: int = 9) -> List[Dict[str, Any]]:
    bars = []
    for name, (hv, label) in hmap.items():
        if len(bars) >= limit:
            break
        av = (amap.get(name) or (None, ""))[0]
        b = _bar(name, label or name, hv, av)
        if b:
            bars.append(b)
    return bars


AFL_KEYS = [
    ("disposals", "Disposals"), ("kicks", "Kicks"), ("handballs", "Handballs"),
    ("marks", "Marks"), ("tackles", "Tackles"), ("inside50s", "Inside 50s"),
    ("totalClearances", "Clearances"), ("contestedPossessions", "Contested Poss"),
    ("hitouts", "Hitouts"), ("freesFor", "Free Kicks"),
    ("goalAccuracy", "Goal Accuracy"), ("clangers", "Clangers"),
]

RUGBY_KEYS = [
    ("possession", "Possession"), ("territory", "Territory"),
    ("runs", "Runs"), ("metres", "Run Metres"), ("tackles", "Tackles"),
    ("missedTackles", "Missed Tackles"), ("cleanBreaks", "Line Breaks"),
    ("offload", "Offloads"), ("kicks", "Kicks"), ("passes", "Passes"),
    ("penaltiesConceded", "Penalties"), ("turnoverKnockOn", "Errors"),
]

SOCCER_KEYS = [
    ("possessionPct", "Possession"), ("totalShots", "Shots"),
    ("shotsOnTarget", "On Target"), ("wonCorners", "Corners"),
    ("foulsCommitted", "Fouls"), ("yellowCards", "Yellow Cards"),
    ("saves", "Saves"), ("totalPasses", "Passes"),
    ("passPct", "Pass Accuracy"),
]

_KIND_KEYS = {"afl": AFL_KEYS, "rugby": RUGBY_KEYS, "soccer": SOCCER_KEYS}

EVENT_SKIP_TYPES = {
    "kickoff", "substitution", "end regular time", "halftime",
    "start 2nd half", "start 1st half", "end of 90 mins",
    "player substituted", "video review", "shootout",
}

SCORING_TYPES = {
    "goal", "try", "penalty try", "behind", "conversion", "penalty goal",
    "penalty - scored", "own goal", "drop goal", "field goal", "touchdown",
}


def _event_rows(summary: dict, sb_comp: dict, home_id: str) -> List[Dict[str, Any]]:
    """Timeline from keyEvents / plays / scoreboard details — newest first."""
    rows: List[Dict[str, Any]] = []

    def side_of(team: Any) -> str:
        tid = str((team or {}).get("id") or "")
        return "home" if tid == home_id else "away"

    def add(time_s: str, team: Any, type_s: str, player: str, detail: str = "") -> None:
        tl = type_s.strip().lower()
        if tl in EVENT_SKIP_TYPES or "substitut" in tl:
            return
        rows.append({
            "time": time_s,
            "team": side_of(team),
            "type": type_s.upper(),
            "player": player,
            "detail": detail,
            "scoring": tl in SCORING_TYPES,
        })

    for ev in summary.get("keyEvents") or []:
        tp = (ev.get("type") or {}).get("text") or ""
        players = [(p.get("athlete") or {}).get("displayName") or ""
                   for p in ev.get("participants") or []]
        add((ev.get("clock") or {}).get("displayValue") or "",
            ev.get("team"), tp, players[0] if players else "")

    if not rows:
        for p in summary.get("plays") or []:
            tp = (p.get("type") or {}).get("text") or ""
            text = str(p.get("text") or "")
            player = text
            if tp and text.lower().endswith(tp.lower()):
                player = text[: -len(tp)].strip(" -·")
            period = (p.get("period") or {}).get("number")
            clock = (p.get("clock") or {}).get("displayValue") or ""
            add(f"Q{period} {clock}".strip() if period else clock,
                p.get("team"), tp, player)

    if not rows:
        for d in sb_comp.get("details") or []:
            tp = (d.get("type") or {}).get("text") or ""
            players = [a.get("displayName") or "" for a in d.get("athletesInvolved") or []]
            add((d.get("clock") or {}).get("displayValue") or "",
                d.get("team"), tp, players[0] if players else "")

    if not rows:
        for sp in summary.get("scoringPlays") or []:
            period = (sp.get("period") or {}).get("number")
            clock = (sp.get("clock") or {}).get("displayValue") or ""
            add(f"Q{period} {clock}".strip() if period else clock,
                sp.get("team"), (sp.get("type") or {}).get("text") or "SCORE",
                str(sp.get("text") or ""))

    return list(reversed(rows[-40:]))


def _linescore_periods(home_c: dict, away_c: dict, kind: str) -> List[Dict[str, str]]:
    hls = home_c.get("linescores") or []
    als = away_c.get("linescores") or []
    n = max(len(hls), len(als))
    if n == 0:
        return []

    def val(ls: List[dict], i: int) -> Optional[str]:
        if i >= len(ls):
            return None
        v = ls[i].get("displayValue")
        if v is None:
            v = ls[i].get("value")
            if isinstance(v, float) and v == int(v):
                v = int(v)
        return None if v is None else str(v)

    rows: List[Dict[str, str]] = []
    if kind == "rugby":
        # ESPN rugby linescores are CUMULATIVE (HT, FT, ET…).
        h1, a1 = _num(val(hls, 0)), _num(val(als, 0))
        h2, a2 = _num(val(hls, 1)), _num(val(als, 1))
        if h1 is not None or a1 is not None:
            rows.append(_period_row("1H", _int_s(h1), _int_s(a1)))
        if (h2 or 0) > 0 or (a2 or 0) > 0:
            rows.append(_period_row(
                "2H",
                _int_s((h2 or 0) - (h1 or 0)) if h2 is not None else None,
                _int_s((a2 or 0) - (a1 or 0)) if a2 is not None else None,
            ))
        return rows

    labels = {"afl": "Q", "generic": "P"}
    prefix = labels.get(kind, "P")
    for i in range(min(n, 9)):
        hv, av = val(hls, i), val(als, i)
        if hv is None and av is None:
            continue
        rows.append(_period_row(f"{prefix}{i + 1}", hv, av))
    return rows


def _int_s(v: Optional[float]) -> Optional[str]:
    if v is None:
        return None
    return str(int(v)) if v == int(v) else f"{v:g}"


async def _board_team_sport(sport: str, kind: str, sport_path: str,
                            league: str, ev: dict) -> Dict[str, Any]:
    comp = (ev.get("competitions") or [{}])[0]
    home_c, away_c = _competitors(ev)
    home = _team_side(home_c, kind)
    away = _team_side(away_c, kind)
    home_id = str((home_c.get("team") or {}).get("id") or "")

    summary = await _summary(sport_path, league, str(ev.get("id")))
    if kind == "afl":
        for c in ((summary.get("header") or {}).get("competitions") or [{}])[0].get("competitors") or []:
            ls = c.get("linescores") or []
            if not ls:
                continue
            g = ls[-1].get("cumulativeGoalsDisplayValue")
            b = ls[-1].get("cumulativeBehindsDisplayValue")
            if g is None or b is None:
                continue
            target = home if c.get("homeAway") == "home" else away
            target["scoreDetail"] = f"{g}.{b}"
    hmap, amap = _stats_map(summary)
    keys = _KIND_KEYS.get(kind)
    bars = _curated_bars(keys, hmap, amap) if keys else _generic_bars(hmap, amap)
    if keys and len(bars) < 3:
        bars.extend(b for b in _generic_bars(hmap, amap, limit=9 - len(bars))
                    if b["label"] not in {x["label"] for x in bars})

    venue = ((comp.get("venue") or {}).get("fullName")
             or ((summary.get("gameInfo") or {}).get("venue") or {}).get("fullName")
             or "")
    header = summary.get("header") or {}
    hl = header.get("league") or {}
    league_name = hl.get("name") or ""
    if not league_name:
        for lg in header.get("leagues") or []:
            league_name = lg.get("name") or ""
            break

    return _shape(
        sport,
        fixtureId=ev.get("id"),
        league=league_name,
        venue=venue,
        status=_status_block(ev, comp),
        home=home,
        away=away,
        periods=_linescore_periods(home_c, away_c, kind),
        stats=bars,
        events=_event_rows(summary, comp, home_id),
    )


async def _board_racing(sport: str, ev: dict) -> Dict[str, Any]:
    comps = ev.get("competitions") or []
    active = next((c for c in comps
                   if ((c.get("status") or {}).get("type") or {}).get("state") == "in"),
                  None)
    race = next((c for c in comps
                 if (c.get("type") or {}).get("abbreviation") == "Race"), None)
    comp = active or race or (comps[0] if comps else {})
    session = (comp.get("type") or {}).get("text") or (comp.get("type") or {}).get("abbreviation") or ""

    st = comp.get("status") or {}
    st_type = st.get("type") or {}
    live = st_type.get("state") == "in"
    lap = st.get("period")
    clock = f"LAP {lap}" if (live and lap and int(lap) > 0) else (st_type.get("shortDetail") or "")

    leaderboard = []
    for c in sorted(comp.get("competitors") or [], key=lambda x: x.get("order") or 99)[:12]:
        ath = c.get("athlete") or {}
        leaderboard.append({
            "pos": c.get("order"),
            "name": ath.get("displayName") or "",
            "team": (c.get("vehicle") or {}).get("manufacturer") or "",
            "detail": "WINNER" if c.get("winner") else "",
            "flag": (ath.get("flag") or {}).get("href") or "",
        })

    circuit = ev.get("circuit") or {}
    city = (circuit.get("address") or {}).get("city") or ""
    country = (circuit.get("address") or {}).get("country") or ""
    venue = " · ".join(x for x in [circuit.get("fullName"), f"{city}, {country}".strip(", ")] if x)

    sessions = []
    for c in comps:
        ct = (c.get("type") or {})
        cst = ((c.get("status") or {}).get("type") or {})
        sessions.append({
            "name": ct.get("text") or ct.get("abbreviation") or "",
            "detail": cst.get("shortDetail") or "",
            "state": cst.get("state") or "",
        })

    return _shape(
        sport,
        fixtureId=ev.get("id"),
        league=" · ".join(x for x in [ev.get("name"), session] if x),
        venue=venue,
        status={"short": st_type.get("shortDetail") or "", "long": st_type.get("detail") or "",
                "clock": clock, "live": live},
        home={"name": ev.get("shortName") or ev.get("name") or "", "abbr": "", "logo": "",
              "score": None, "color": "", "form": ""},
        away={"name": "", "abbr": "", "logo": "", "score": None, "color": "", "form": ""},
        periods=[], stats=[], events=[], leaderboard=leaderboard, sessions=sessions,
    )


def _live_or_candidates(events: List[dict], include_finished: bool) -> List[dict]:
    live = [e for e in events if _state(e) == "in"]
    if live or not include_finished:
        return live
    post = [e for e in events if _state(e) == "post"]
    return post or events


# ── tennis: tournament events → per-match pseudo-events ─────────────

def _tennis_flatten(events: List[dict]) -> List[dict]:
    """Each ESPN tennis event is a TOURNAMENT; the real matches live
    in groupings[].competitions[].  Flatten to pseudo-events shaped
    like a normal scoreboard event so _state/_resolve/_competitors
    keep working unchanged."""
    out: List[dict] = []
    for tour in events:
        tname = tour.get("name") or ""
        for g in tour.get("groupings") or []:
            gname = (g.get("grouping") or {}).get("displayName") or ""
            for m in g.get("competitions") or []:
                names = []
                for c in m.get("competitors") or []:
                    ath = c.get("athlete") or {}
                    n = ath.get("displayName") or ath.get("shortName") or ""
                    if n:
                        names.append(n)
                out.append({
                    "id": m.get("id"),
                    "name": " vs ".join(names) if len(names) == 2 else (tname or "Match"),
                    "shortName": " v ".join(names) if len(names) == 2 else tname,
                    "status": m.get("status") or {},
                    "competitions": [m],
                    "_tournament": tname,
                    "_grouping": gname,
                })
    return out


def _tennis_side(c: dict) -> Dict[str, Any]:
    ath = c.get("athlete") or {}
    ls = c.get("linescores") or []
    sets_won = sum(1 for s in ls if s.get("winner"))
    return {
        "name": ath.get("displayName") or "",
        "abbr": ath.get("shortName") or "",
        "logo": (ath.get("flag") or {}).get("href") or "",
        "score": sets_won,
        "color": "",
        "form": "",
        "rank": (c.get("curatedRank") or {}).get("current"),
    }


def _board_tennis(sport: str, ev: dict) -> Dict[str, Any]:
    m = (ev.get("competitions") or [{}])[0]
    home_c, away_c = _competitors(ev)
    home = _tennis_side(home_c)
    away = _tennis_side(away_c)

    hls = home_c.get("linescores") or []
    als = away_c.get("linescores") or []
    periods: List[Dict[str, str]] = []
    for i in range(min(max(len(hls), len(als)), 5)):
        hv = hls[i].get("value") if i < len(hls) else None
        av = als[i].get("value") if i < len(als) else None
        periods.append(_period_row(f"S{i + 1}", _int_s(_num(hv)), _int_s(_num(av))))

    st = m.get("status") or ev.get("status") or {}
    t = st.get("type") or {}
    live = t.get("state") == "in"
    note = ""
    for n in m.get("notes") or []:
        note = str(n.get("text") or "")
        if note:
            break

    ven = m.get("venue") or {}
    court = ven.get("court") or ""
    venue = " · ".join(x for x in [court, ven.get("fullName") or ""] if x)

    league = " · ".join(x for x in [ev.get("_tournament") or "",
                                    ev.get("_grouping") or ""] if x)
    rnd = (m.get("round") or {}).get("displayName") or ""
    if rnd:
        league = f"{league} · {rnd}" if league else rnd

    return _shape(
        sport,
        fixtureId=ev.get("id"),
        league=league,
        venue=venue,
        status={
            "short": t.get("shortDetail") or "",
            "long": note or t.get("detail") or t.get("description") or "",
            "clock": (t.get("detail") or "") if live else "",
            "live": live,
        },
        home=home,
        away=away,
        periods=periods,
        stats=[],
        events=[],
    )


# ── cricket: innings scores parsed from scoreboard linescores ───────

def _cricket_innings(c: dict) -> List[Dict[str, Any]]:
    out = []
    for ls in c.get("linescores") or []:
        runs = _num(ls.get("runs"))
        overs = _num(ls.get("overs"))
        if runs is None:
            continue
        # A side's linescores mirror EVERY match period — only its
        # own batting innings count (isBatting, or runs on board).
        if runs <= 0 and not bool(ls.get("isBatting")):
            continue
        wickets = _num(ls.get("wickets")) or 0
        out.append({
            "runs": int(runs),
            "wickets": int(wickets),
            "overs": overs or 0.0,
            "current": bool(ls.get("isCurrent")),
        })
    return out


def _cricket_score_str(innings: List[Dict[str, Any]]) -> str:
    parts = []
    for inn in innings:
        if inn["wickets"] >= 10:
            parts.append(str(inn["runs"]))
        else:
            parts.append(f"{inn['runs']}/{inn['wickets']}")
    return " & ".join(parts) if parts else "0"


def _board_cricket(sport: str, ev: dict) -> Dict[str, Any]:
    comp = (ev.get("competitions") or [{}])[0]
    home_c, away_c = _competitors(ev)
    home = _team_side(home_c, "cricket")
    away = _team_side(away_c, "cricket")

    h_inn = _cricket_innings(home_c)
    a_inn = _cricket_innings(away_c)
    home["score"] = _cricket_score_str(h_inn)
    away["score"] = _cricket_score_str(a_inn)
    cur_h = next((i for i in h_inn if i["current"]), None)
    cur_a = next((i for i in a_inn if i["current"]), None)
    if cur_h:
        home["scoreDetail"] = f"{cur_h['overs']:g} OV"
    if cur_a:
        away["scoreDetail"] = f"{cur_a['overs']:g} OV"

    periods: List[Dict[str, str]] = []
    for i in range(max(len(h_inn), len(a_inn))):
        hv = _cricket_score_str([h_inn[i]]) if i < len(h_inn) else None
        av = _cricket_score_str([a_inn[i]]) if i < len(a_inn) else None
        periods.append(_period_row(f"I{i + 1}", hv, av))

    h_runs = sum(i["runs"] for i in h_inn)
    a_runs = sum(i["runs"] for i in a_inn)
    h_ov = sum(i["overs"] for i in h_inn)
    a_ov = sum(i["overs"] for i in a_inn)
    bars: List[Dict[str, Any]] = []
    b = _bar("runs", "Total Runs", h_runs, a_runs)
    if b:
        bars.append(b)
    if h_ov > 0 and a_ov > 0:
        b = _bar("runrate", "Run Rate", round(h_runs / h_ov, 2), round(a_runs / a_ov, 2))
        if b:
            bars.append(b)
    h_wk = sum(i["wickets"] for i in h_inn)
    a_wk = sum(i["wickets"] for i in a_inn)
    b = _bar("wickets", "Wickets Lost", h_wk, a_wk)
    if b:
        bars.append(b)

    st = comp.get("status") or ev.get("status") or {}
    t = st.get("type") or {}
    live = t.get("state") == "in"
    summary = str(st.get("summary") or "")

    return _shape(
        sport,
        fixtureId=ev.get("id"),
        league=(ev.get("season") or {}).get("displayName")
               or ev.get("description") or "",
        venue=((comp.get("venue") or {}).get("fullName") or ""),
        status={
            "short": t.get("shortDetail") or "",
            "long": summary or t.get("detail") or "",
            "clock": summary if live else "",
            "live": live,
        },
        home=home,
        away=away,
        periods=periods,
        stats=bars,
        events=[],
    )


# ── golf: tournament leaderboard (PGA / LPGA / Euro / LIV) ──────────

def _flag_country_abbr(flag_href: str) -> str:
    """Extract 3-letter country code from an ESPN athlete flag URL:
    `.../countries/500/usa.png` → `USA`."""
    if not flag_href:
        return ""
    m = re.search(r"/countries/\d+/([a-z]{2,4})\.", flag_href)
    return (m.group(1) if m else "").upper()


def _board_golf(sport: str, ev: dict) -> Dict[str, Any]:
    comp = (ev.get("competitions") or [{}])[0]
    comps = comp.get("competitors") or []

    # Sort by declared order (ESPN pre-sorts by leaderboard position).
    sorted_players = sorted(comps, key=lambda c: c.get("order") or 9999)

    def _to_par(raw: Any) -> str:
        if raw in (None, ""):
            return "E"
        if isinstance(raw, (int, float)):
            v = int(raw) if float(raw) == int(raw) else raw
            return "E" if v == 0 else (f"+{v}" if v > 0 else str(v))
        return str(raw)

    leaderboard: List[Dict[str, Any]] = []
    for c in sorted_players[:15]:
        ath = c.get("athlete") or {}
        flag_href = (ath.get("flag") or {}).get("href") or ""
        leaderboard.append({
            "pos": c.get("order"),
            "name": ath.get("displayName") or ath.get("shortName") or "",
            "team": _flag_country_abbr(flag_href),
            "detail": _to_par(c.get("score")),
            "flag": flag_href,
        })

    st = comp.get("status") or ev.get("status") or {}
    t = st.get("type") or {}
    live = t.get("state") == "in"
    # ESPN's shortDetail is authoritative for round info ("Round 3
    # — In Progress").  Don't recompute from linescores (partial
    # rounds get counted as complete and yield an off-by-one).
    round_label = t.get("shortDetail") or t.get("description") or ""

    ven = comp.get("venue") or {}
    course = ven.get("fullName") or ""
    city = (ven.get("address") or {}).get("city") or ""
    country = (ven.get("address") or {}).get("country") or ""
    venue = " · ".join(x for x in [course, ", ".join(y for y in [city, country] if y)] if x)

    # v2.16.4 — Fill the top scoreboard with LEADER + RUNNER-UP so
    # the golf panel doesn't look empty, and populate `sessions[]`
    # with the leader's round-by-round scorecard so the STATS box
    # renders a full "ROUNDS BREAKDOWN" board.
    home: Dict[str, Any] = {"name": "", "abbr": "", "logo": "",
                            "score": None, "color": "", "form": ""}
    away: Dict[str, Any] = {"name": "", "abbr": "", "logo": "",
                            "score": None, "color": "", "form": ""}
    sessions: List[Dict[str, Any]] = []
    stats_bars: List[Dict[str, Any]] = []

    if sorted_players:
        leader = sorted_players[0]
        lath = leader.get("athlete") or {}
        home = {
            "name": lath.get("displayName") or "",
            "abbr": lath.get("shortName") or "",
            "logo": (lath.get("flag") or {}).get("href") or "",
            "score": _to_par(leader.get("score")),
            "color": "#7FC57F",
            "form": "",
        }
        if len(sorted_players) >= 2:
            r2 = sorted_players[1]
            rath = r2.get("athlete") or {}
            away = {
                "name": rath.get("displayName") or "",
                "abbr": rath.get("shortName") or "",
                "logo": (rath.get("flag") or {}).get("href") or "",
                "score": _to_par(r2.get("score")),
                "color": "#8FA1BF",
                "form": "",
            }
        # Leader's per-round scorecard → sessions rows.  ESPN's
        # linescores[i].value is the stroke count for round i,
        # displayValue is the to-par, and the nested `linescores`
        # array holds hole-by-hole data (18 entries = round complete;
        # fewer entries = round still in progress).
        leader_rounds = leader.get("linescores") or []
        for i, ls in enumerate(leader_rounds[:4]):
            strokes = ls.get("value")
            to_par = ls.get("displayValue") or ""
            holes = ls.get("linescores") or []
            if strokes is None or strokes == 0:
                sessions.append({"name": f"Round {i + 1}",
                                 "detail": "UPCOMING", "state": "pre"})
            elif len(holes) >= 18:
                # Complete round
                sessions.append({
                    "name": f"Round {i + 1}",
                    "detail": f"{int(strokes)} ({to_par})" if to_par else str(int(strokes)),
                    "state": "post",
                })
            else:
                # Round in progress — show holes played + current to-par
                sessions.append({
                    "name": f"Round {i + 1}",
                    "detail": f"THRU {len(holes)} · {to_par}" if to_par else f"THRU {len(holes)}",
                    "state": "in",
                })
        # Ensure all 4 rounds are represented even if ESPN omits
        # future ones from linescores.
        while len(sessions) < 4:
            sessions.append({"name": f"Round {len(sessions) + 1}",
                             "detail": "UPCOMING", "state": "pre"})
        # Cross-field summary stats — three rows using head-to-head
        # bar shape so the frontend renderBars picks them up.  In
        # golf lower = better, so the *inverse* of the raw diff drives
        # the bar; homePct is boosted so the leader visually dominates.
        pars = []
        for c in sorted_players:
            v = _num(c.get("score"))
            if v is not None:
                pars.append(v)
        if pars:
            leader_par = pars[0]
            field_avg = round(sum(pars) / len(pars), 1)
            worst = max(pars)
            second = pars[1] if len(pars) > 1 else leader_par
            # Bar 1: Leader vs 2nd — margin (leader always ≥ visually)
            margin = max(1.0, abs(second - leader_par) + 1.0)
            stats_bars.append({
                "label": "Leader vs 2nd",
                "home": _to_par(leader_par),
                "away": _to_par(second),
                "homePct": max(52, min(85, int(50 + margin * 8))),
            })
            # Bar 2: Leader vs Field Avg
            diff = max(1.0, abs(field_avg - leader_par))
            stats_bars.append({
                "label": "Leader vs Field",
                "home": _to_par(leader_par),
                "away": _to_par(field_avg),
                "homePct": max(58, min(92, int(50 + diff * 3.5))),
            })
            # Bar 3: Cut Line (worst score currently in field)
            under_par = sum(1 for v in pars if v < 0)
            stats_bars.append({
                "label": f"Field ({len(pars)} players)",
                "home": f"{under_par} UNDER",
                "away": f"{len(pars) - under_par} OVER",
                "homePct": max(10, min(90, int(under_par * 100 / max(len(pars), 1)))),
            })

    return _shape(
        sport,
        fixtureId=ev.get("id"),
        league=ev.get("name") or "",
        venue=venue,
        status={
            "short": t.get("shortDetail") or round_label,
            "long": t.get("detail") or t.get("description") or "",
            "clock": round_label,
            "live": live,
        },
        home=home,
        away=away,
        periods=[],
        stats=stats_bars,
        events=[],
        leaderboard=leaderboard,
        sessions=sessions,
    )


# ── MMA / UFC: fight card with fighter records ─────────────────────

def _board_mma(sport: str, ev: dict) -> Dict[str, Any]:
    """UFC / MMA card renderer.  ESPN's `mma/ufc` scoreboard returns
    ONE event per card (e.g. UFC 329) whose competitions[] holds all
    the fights on that card.  The featured/main event is the last
    competition (highest ordinal); we surface it in the top score
    band, list the full card in events[], and pull physical stats
    into the stats bars."""
    ev_name = ev.get("name") or ev.get("shortName") or "UFC Card"
    comps = ev.get("competitions") or []
    if not comps:
        return _shape(sport, found=False, reason="no_match",
                      message="Fight card details unavailable")

    # Find the live fight, else the main event (last in card), else first.
    live_comp = next((c for c in comps
                      if ((c.get("status") or {}).get("type") or {}).get("state") == "in"), None)
    main = live_comp or comps[-1]

    def _fighter(c: dict) -> Dict[str, Any]:
        ath = c.get("athlete") or {}
        recs = c.get("records") or []
        summary = ""
        for r in recs:
            if r.get("type") in ("total", "career"):
                summary = r.get("summary") or ""
                break
        if not summary and recs:
            summary = recs[0].get("summary") or ""
        return {
            "name": ath.get("displayName") or ath.get("shortName") or "",
            "abbr": ath.get("shortName") or "",
            "logo": (ath.get("headshot") or {}).get("href")
                    or (ath.get("flag") or {}).get("href") or "",
            "score": c.get("score") or "",
            "color": ("#" + (c.get("team") or {}).get("color", "").lstrip("#"))
                     if (c.get("team") or {}).get("color") else "",
            "form": summary,
            "record": summary,
            "rank": (c.get("curatedRank") or {}).get("current"),
        }

    fighters = main.get("competitors") or []
    home = _fighter(fighters[0]) if len(fighters) > 0 else {}
    away = _fighter(fighters[1]) if len(fighters) > 1 else {}

    # Weight class label from the main event's `type.text` or note.
    weight_class = ""
    for n in main.get("notes") or []:
        txt = str(n.get("headline") or n.get("text") or "")
        if txt:
            weight_class = txt
            break
    if not weight_class:
        weight_class = (main.get("type") or {}).get("text") or ""

    st = main.get("status") or {}
    t = st.get("type") or {}
    live = t.get("state") == "in"
    round_no = st.get("period") or 0
    clock = str(st.get("displayClock") or "").strip()
    clock_txt = ""
    if live:
        if round_no and clock and clock not in ("0:00", "-"):
            clock_txt = f"R{round_no} · {clock}"
        elif round_no:
            clock_txt = f"R{round_no}"
        else:
            clock_txt = t.get("shortDetail") or ""

    # Fight card timeline: every bout on the card as an event row.
    events: List[Dict[str, Any]] = []
    for c in comps:
        cf = c.get("competitors") or []
        if len(cf) < 2:
            continue
        a1 = (cf[0].get("athlete") or {}).get("shortName") \
             or (cf[0].get("athlete") or {}).get("displayName") or "?"
        a2 = (cf[1].get("athlete") or {}).get("shortName") \
             or (cf[1].get("athlete") or {}).get("displayName") or "?"
        cst = ((c.get("status") or {}).get("type") or {})
        cstate = cst.get("state") or ""
        cnotes = ""
        for n in c.get("notes") or []:
            cnotes = str(n.get("headline") or n.get("text") or "")
            if cnotes:
                break
        # Determine winner if fight is finished
        winner = None
        for f in cf:
            if f.get("winner"):
                ath = f.get("athlete") or {}
                winner = ath.get("shortName") or ath.get("displayName") or ""
                break
        detail_txt = ""
        if cstate == "post" and winner:
            detail_txt = f"WON: {winner}"
        elif cstate == "in":
            detail_txt = "LIVE NOW"
        else:
            detail_txt = cst.get("shortDetail") or "SCHEDULED"
        events.append({
            "time": cnotes[:12] if cnotes else "",
            "team": "home",
            "type": detail_txt,
            "player": f"{a1}  vs  {a2}",
            "detail": "",
            "scoring": cstate == "post",
        })

    # Rich stats bars derived from fighter records (career wins,
    # losses, draws and win rate).  ESPN's scoreboard endpoint
    # rarely exposes height/reach/weight so we lean on the record
    # string ("27-9-0") which is always present.
    def _parse_record(rec: str) -> Optional[Tuple[int, int, int]]:
        m = re.match(r"^\s*(\d+)\s*-\s*(\d+)(?:\s*-\s*(\d+))?", rec or "")
        if not m:
            return None
        return int(m.group(1)), int(m.group(2)), int(m.group(3) or 0)

    stats_bars: List[Dict[str, Any]] = []
    h_rec = _parse_record(home.get("record", ""))
    a_rec = _parse_record(away.get("record", ""))
    if h_rec and a_rec:
        hw, hl, hd = h_rec
        aw, al, ad = a_rec
        # Career Wins
        stats_bars.append({
            "label": "Career Wins",
            "home": str(hw),
            "away": str(aw),
            "homePct": max(10, min(90, int(round(hw * 100 / max(hw + aw, 1))))),
        })
        # Total Fights
        ht = hw + hl + hd
        at = aw + al + ad
        stats_bars.append({
            "label": "Total Fights",
            "home": str(ht),
            "away": str(at),
            "homePct": max(10, min(90, int(round(ht * 100 / max(ht + at, 1))))),
        })
        # Win Rate
        h_pct = int(round(hw * 100 / max(ht, 1)))
        a_pct = int(round(aw * 100 / max(at, 1)))
        stats_bars.append({
            "label": "Win Rate",
            "home": f"{h_pct}%",
            "away": f"{a_pct}%",
            "homePct": max(10, min(90, int(round(h_pct * 100 / max(h_pct + a_pct, 1))))),
        })
        # Losses (inverse bar — lower is better, so shrink dominant side)
        stats_bars.append({
            "label": "Career Losses",
            "home": str(hl),
            "away": str(al),
            "homePct": max(10, min(90, int(round(hl * 100 / max(hl + al, 1))))),
        })
    # Ranks if both fighters have a curatedRank
    if home.get("rank") and away.get("rank"):
        try:
            hr = int(home["rank"])
            ar = int(away["rank"])
            if hr > 0 and ar > 0:
                stats_bars.append({
                    "label": "Rank",
                    "home": f"#{hr}",
                    "away": f"#{ar}",
                    # lower rank = better — invert the bar
                    "homePct": max(10, min(90, int(round(ar * 100 / max(hr + ar, 1))))),
                })
        except (ValueError, TypeError):
            pass

    venue = (ev.get("competitions", [{}])[0].get("venue") or {}).get("fullName") or ""

    return _shape(
        sport,
        fixtureId=ev.get("id"),
        league=weight_class or ev_name,
        venue=venue,
        status={
            "short": t.get("shortDetail") or "",
            "long": t.get("detail") or t.get("description") or ev_name,
            "clock": clock_txt,
            "live": live,
        },
        home=home,
        away=away,
        periods=[],
        stats=stats_bars,
        events=events,
    )


@router.get("/board")
async def board(
    sport: str = Query(..., description="Sport bucket id from the WhatsOn hub"),
    title: str = Query(..., min_length=2, description="EPG programme title"),
    includeFinished: bool = Query(False, description="Match finished games too (testing/demo)"),
    dates: Optional[str] = Query(None, regex=r"^[0-9-]{4,17}$",
                                 description="ESPN dates filter (testing/demo)"),
):
    sport = sport.lower().strip()
    if sport not in SPORTS:
        raise HTTPException(400, f"unsupported sport '{sport}'")
    cfg = SPORTS[sport]
    kind = cfg["kind"]
    label = cfg["label"]

    candidates: List[Tuple[dict, str, str]] = []
    fetch_failed = True
    for sport_path, league in cfg["leagues"]:
        events = await _scoreboard(sport_path, league, dates)
        if events:
            fetch_failed = False
        if kind == "tennis":
            events = _tennis_flatten(events)
        for ev in _live_or_candidates(events, includeFinished):
            candidates.append((ev, sport_path, league))

    if not candidates:
        if fetch_failed:
            return _shape(sport, found=False, reason="fetch_error",
                          message="Can't reach the live data feed right now — retrying")
        return _shape(sport, found=False, reason="no_live_games",
                      message=f"No live {label} match in the data feed right now")

    ev, how = _resolve(title, [c[0] for c in candidates], kind)
    if ev is None and kind == "tennis":
        # Tennis EPG titles rarely name players ("Live Tennis:
        # Wimbledon — Centre Court").  Fall back to tournament-name
        # matching, preferring singles matches.
        tn, tt = _norm(title), _tokens(title)
        tour_hits = [c[0] for c in candidates
                     if _match_score(tt, tn, [c[0].get("_tournament") or ""]) > 0]
        if tour_hits:
            singles = [e for e in tour_hits
                       if "singles" in (e.get("_grouping") or "").lower()]
            ev, how = (singles or tour_hits)[0], "tournament"
    if ev is None:
        return _shape(sport, found=False, reason="no_match", liveCount=len(candidates),
                      message=f"{len(candidates)} live {label} matches in the feed — "
                              f"none matched this programme yet")
    sport_path, league = next((c[1], c[2]) for c in candidates if c[0] is ev)

    if kind == "racing":
        payload = await _board_racing(sport, ev)
    elif kind == "tennis":
        payload = _board_tennis(sport, ev)
    elif kind == "cricket":
        payload = _board_cricket(sport, ev)
    elif kind == "golf":
        payload = _board_golf(sport, ev)
    elif kind == "mma":
        payload = _board_mma(sport, ev)
    else:
        payload = await _board_team_sport(sport, kind, sport_path, league, ev)
    payload["matched"] = how
    return payload
