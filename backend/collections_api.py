"""
"Collections" rail — studios + franchises browsable like networks.

Each entry is either a TMDB *company* (Marvel Studios, Pixar, A24 …),
a TMDB *collection* (Harry Potter, James Bond …) or a *keyword*.
Tiles get a real TMDB asset: company wordmark logo (rendered white by
the frontend) or the franchise's title-treatment logo from its most
popular film, over the franchise backdrop.
"""
from __future__ import annotations

import asyncio
from typing import Any, Callable, Dict, List, Optional

from fastapi import APIRouter, HTTPException, Query

router = APIRouter(prefix="/api/collections", tags=["collections"])

_tmdb: Optional[Callable] = None
_cache: Any = None
IMG = "https://image.tmdb.org/t/p"


def configure_collections(tmdb_get: Callable, cache: Any) -> None:
    global _tmdb, _cache
    _tmdb, _cache = tmdb_get, cache


# slug → definition.  ids verified against TMDB (Jun 2026).
COLLECTIONS: Dict[str, Dict[str, Any]] = {
    "marvel":          {"label": "Marvel",                 "kind": "company",    "ids": [420]},
    "dc":              {"label": "DC",                     "kind": "company",    "ids": [9993, 128064, 429], "logo_id": 429},
    "disney":          {"label": "Disney",                 "kind": "company",    "ids": [2]},
    "pixar":           {"label": "Pixar",                  "kind": "company",    "ids": [3]},
    "star-wars":       {"label": "Star Wars",              "kind": "collection", "ids": [10],     "tv_company": 1},
    "wizarding-world": {"label": "Wizarding World",        "kind": "collection", "ids": [1241, 435259]},
    "middle-earth":    {"label": "Middle-earth",           "kind": "collection", "ids": [119, 121938]},
    "james-bond":      {"label": "James Bond",             "kind": "collection", "ids": [645]},
    "fast-furious":    {"label": "Fast & Furious",         "kind": "collection", "ids": [9485]},
    "jurassic":        {"label": "Jurassic",               "kind": "collection", "ids": [328]},
    "mission-impossible": {"label": "Mission: Impossible", "kind": "collection", "ids": [87359]},
    "john-wick":       {"label": "John Wick",              "kind": "collection", "ids": [404609]},
    "spider-man":      {"label": "Spider-Man",             "kind": "collection", "ids": [531241, 556, 125574, 573436]},
    "batman":          {"label": "Batman",                 "kind": "collection", "ids": [263, 120794]},
    "avengers":        {"label": "Avengers",               "kind": "collection", "ids": [86311]},
    "x-men":           {"label": "X-Men",                  "kind": "collection", "ids": [748]},
    "deadpool":        {"label": "Deadpool",               "kind": "collection", "ids": [448150]},
    "transformers":    {"label": "Transformers",           "kind": "collection", "ids": [8650]},
    "indiana-jones":   {"label": "Indiana Jones",          "kind": "collection", "ids": [84]},
    "pirates":         {"label": "Pirates of the Caribbean", "kind": "collection", "ids": [295]},
    "toy-story":       {"label": "Toy Story",              "kind": "collection", "ids": [10194]},
    "shrek":           {"label": "Shrek",                  "kind": "collection", "ids": [2150]},
    "despicable-me":   {"label": "Despicable Me",          "kind": "collection", "ids": [86066, 544669]},
    "matrix":          {"label": "The Matrix",             "kind": "collection", "ids": [2344]},
    "rocky":           {"label": "Rocky & Creed",          "kind": "collection", "ids": [1575, 553717]},
    "alien":           {"label": "Alien",                  "kind": "collection", "ids": [8091]},
    "terminator":      {"label": "Terminator",             "kind": "collection", "ids": [528]},
    "hunger-games":    {"label": "The Hunger Games",       "kind": "collection", "ids": [131635]},
    "twilight":        {"label": "Twilight",               "kind": "collection", "ids": [33514]},
    "godzilla":        {"label": "MonsterVerse",           "kind": "collection", "ids": [535313]},
    "star-trek":       {"label": "Star Trek",              "kind": "collection", "ids": [115575, 151]},
    "mad-max":         {"label": "Mad Max",                "kind": "collection", "ids": [8945]},
    "back-to-the-future": {"label": "Back to the Future",  "kind": "collection", "ids": [264]},
    "bourne":          {"label": "Bourne",                 "kind": "collection", "ids": [31562]},
    "die-hard":        {"label": "Die Hard",               "kind": "collection", "ids": [1570]},
    "oceans":          {"label": "Ocean's",                "kind": "collection", "ids": [304]},
    "conjuring":       {"label": "The Conjuring",          "kind": "collection", "ids": [313086]},
    "scream":          {"label": "Scream",                 "kind": "collection", "ids": [2602]},
    "planet-of-the-apes": {"label": "Planet of the Apes",  "kind": "collection", "ids": [173710]},
    "ice-age":         {"label": "Ice Age",                "kind": "collection", "ids": [8354]},
    "kung-fu-panda":   {"label": "Kung Fu Panda",          "kind": "collection", "ids": [77816]},
    "httyd":           {"label": "How to Train Your Dragon", "kind": "collection", "ids": [89137]},
    "dreamworks":      {"label": "DreamWorks",             "kind": "company",    "ids": [521]},
    "illumination":    {"label": "Illumination",           "kind": "company",    "ids": [6704]},
    "ghibli":          {"label": "Studio Ghibli",          "kind": "company",    "ids": [10342]},
    "warner-bros":     {"label": "Warner Bros.",           "kind": "company",    "ids": [174]},
    "universal":       {"label": "Universal",              "kind": "company",    "ids": [33]},
    "paramount":       {"label": "Paramount",              "kind": "company",    "ids": [4]},
    "20th-century":    {"label": "20th Century",           "kind": "company",    "ids": [127928]},
    "a24":             {"label": "A24",                    "kind": "company",    "ids": [41077]},
    "blumhouse":       {"label": "Blumhouse",              "kind": "company",    "ids": [3172]},
    "legendary":       {"label": "Legendary",              "kind": "company",    "ids": [923]},
    "lionsgate":       {"label": "Lionsgate",              "kind": "company",    "ids": [1632]},
}

LOGOS_TTL = 7 * 24 * 3600
TITLES_TTL = 12 * 3600


def _shape(item: Dict[str, Any], is_tv: bool) -> Dict[str, Any]:
    date = item.get("first_air_date") if is_tv else item.get("release_date")
    return {
        "tmdb_id": item.get("id"),
        "type": "series" if is_tv else "movie",
        "title": item.get("name") if is_tv else item.get("title"),
        "poster": f"{IMG}/w500{item['poster_path']}" if item.get("poster_path") else None,
        "backdrop": f"{IMG}/w1280{item['backdrop_path']}" if item.get("backdrop_path") else None,
        "overview": item.get("overview"),
        "year": (date or "")[:4] or None,
        "rating": round(item.get("vote_average") or 0, 1) or None,
        "release_date": date,
    }


async def _collection_parts(cid: int) -> Dict[str, Any]:
    try:
        return await _tmdb(f"/collection/{cid}")
    except HTTPException:
        return {}


async def _title_logo(movie_id: int) -> Optional[str]:
    try:
        imgs = await _tmdb(f"/movie/{movie_id}/images", {"include_image_language": "en,null"})
    except HTTPException:
        return None
    logos = imgs.get("logos") or []
    # Prefer PNG (transparent) English title treatments, widest first.
    logos = sorted(
        (l for l in logos if l.get("file_path")),
        key=lambda l: (l.get("iso_639_1") != "en", not str(l.get("file_path", "")).endswith(".png"), -(l.get("width") or 0)),
    )
    return logos[0]["file_path"] if logos else None


async def _tile_for(slug: str, cfg: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {"name": cfg["label"], "kind": cfg["kind"], "logo": None, "backdrop": None}
    if cfg["kind"] == "company":
        cid = cfg.get("logo_id") or cfg["ids"][0]
        try:
            co = await _tmdb(f"/company/{cid}")
            if co.get("logo_path"):
                out["logo"] = f"{IMG}/w500{co['logo_path']}"
        except HTTPException:
            pass
        try:
            disc = await _tmdb("/discover/movie", {"with_companies": "|".join(map(str, cfg["ids"])), "sort_by": "popularity.desc"})
            for it in disc.get("results") or []:
                if it.get("backdrop_path"):
                    out["backdrop"] = f"{IMG}/w780{it['backdrop_path']}"
                    break
        except HTTPException:
            pass
        out["logo_style"] = "wordmark"
    else:
        col = await _collection_parts(cfg["ids"][0])
        parts = [p for p in (col.get("parts") or []) if p.get("id")]
        if col.get("backdrop_path"):
            out["backdrop"] = f"{IMG}/w780{col['backdrop_path']}"
        parts.sort(key=lambda p: -(p.get("popularity") or 0))
        for p in parts[:3]:
            lp = await _title_logo(p["id"])
            if lp:
                out["logo"] = f"{IMG}/w500{lp}"
                if not out["backdrop"] and p.get("backdrop_path"):
                    out["backdrop"] = f"{IMG}/w780{p['backdrop_path']}"
                break
        out["logo_style"] = "title"
    return out


@router.get("/logos")
async def collection_logos():
    key = "collections:logos:v1"
    cached = await _cache.get(key)
    if cached:
        return {"cached": True, "data": cached}
    sem = asyncio.Semaphore(6)

    async def one(slug: str, cfg: Dict[str, Any]):
        async with sem:
            return slug, await _tile_for(slug, cfg)

    pairs = await asyncio.gather(*[one(s, c) for s, c in COLLECTIONS.items()])
    data = {s: t for s, t in pairs}
    await _cache.set(key, data, LOGOS_TTL)
    return {"cached": False, "data": data}


@router.get("/{slug}")
async def collection_titles(
    slug: str,
    type_: str = Query("movie", alias="type"),
    page: int = 1,
):
    cfg = COLLECTIONS.get(slug)
    if not cfg:
        raise HTTPException(404, f"Unknown collection '{slug}'")
    if type_ not in ("tv", "movie"):
        raise HTTPException(400, "type must be 'tv' or 'movie'")
    key = f"coll:{slug}:{type_}:{page}"
    cached = await _cache.get(key)
    if cached:
        return {"cached": True, "data": cached}

    is_tv = type_ == "tv"
    results: List[Dict[str, Any]] = []
    total_pages, total_results = 1, 0

    if cfg["kind"] == "collection" and not is_tv:
        cols = await asyncio.gather(*[_collection_parts(c) for c in cfg["ids"]])
        seen = set()
        parts: List[Dict[str, Any]] = []
        for col in cols:
            for p in col.get("parts") or []:
                if p.get("id") in seen or not (p.get("release_date") or p.get("poster_path")):
                    continue
                seen.add(p["id"])
                parts.append(p)
        parts.sort(key=lambda p: p.get("release_date") or "9999")
        results = [_shape(p, False) for p in parts]
        total_results = len(results)
    else:
        company_ids = cfg["ids"] if cfg["kind"] == "company" else ([cfg["tv_company"]] if cfg.get("tv_company") else [])
        if not company_ids:
            data = {"results": [], "total_pages": 1, "total_results": 0}
        else:
            data = await _tmdb(
                f"/discover/{type_}",
                {"with_companies": "|".join(map(str, company_ids)), "page": page, "sort_by": "popularity.desc"},
            )
        results = [_shape(it, is_tv) for it in data.get("results") or []]
        total_pages = min(int(data.get("total_pages") or 1), 500)
        total_results = int(data.get("total_results") or 0)

    payload = {
        "collection": {"slug": slug, "name": cfg["label"], "kind": cfg["kind"]},
        "type": type_,
        "page": page,
        "total_pages": total_pages,
        "total_results": total_results,
        "results": results,
    }
    await _cache.set(key, payload, TITLES_TTL)
    return {"cached": False, "data": payload}
