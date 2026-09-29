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

import httpx
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
    # ---- more studios ----
    "lucasfilm":       {"label": "Lucasfilm",              "kind": "company",    "ids": [1]},
    "sony-pictures":   {"label": "Sony Pictures",          "kind": "company",    "ids": [34, 5]},
    "columbia":        {"label": "Columbia Pictures",      "kind": "company",    "ids": [5]},
    "mgm":             {"label": "MGM",                    "kind": "company",    "ids": [21]},
    "new-line":        {"label": "New Line Cinema",        "kind": "company",    "ids": [12]},
    "amblin":          {"label": "Amblin",                 "kind": "company",    "ids": [56]},
    "focus-features":  {"label": "Focus Features",         "kind": "company",    "ids": [10146]},
    "searchlight":     {"label": "Searchlight",            "kind": "company",    "ids": [43]},
    "miramax":         {"label": "Miramax",                "kind": "company",    "ids": [14]},
    "aardman":         {"label": "Aardman",                "kind": "company",    "ids": [297]},
    "laika":           {"label": "LAIKA",                  "kind": "company",    "ids": [11537]},
    "disney-animation": {"label": "Walt Disney Animation", "kind": "company",    "ids": [6125]},
    "sony-animation":  {"label": "Sony Pictures Animation", "kind": "company",   "ids": [2251]},
    "bad-robot":       {"label": "Bad Robot",              "kind": "company",    "ids": [11461]},
    "working-title":   {"label": "Working Title",          "kind": "company",    "ids": [10163]},
    "village-roadshow": {"label": "Village Roadshow",      "kind": "company",    "ids": [79]},
    "touchstone":      {"label": "Touchstone",             "kind": "company",    "ids": [9195]},
    "hbo":             {"label": "HBO",                    "kind": "company",    "ids": [3268]},
    "netflix-studios": {"label": "Netflix",                "kind": "company",    "ids": [178464]},
    "amazon-studios":  {"label": "Amazon Studios",         "kind": "company",    "ids": [20580]},
    "apple-studios":   {"label": "Apple Studios",          "kind": "company",    "ids": [194232]},
    "studiocanal":     {"label": "StudioCanal",            "kind": "company",    "ids": [694]},
    "gaumont":         {"label": "Gaumont",                "kind": "company",    "ids": [9]},
    "toho":            {"label": "TOHO",                   "kind": "company",    "ids": [882]},
    "bbc-film":        {"label": "BBC Film",               "kind": "company",    "ids": [288]},
    "neon":            {"label": "NEON",                   "kind": "company",    "ids": [90733]},
    "plan-b":          {"label": "Plan B",                 "kind": "company",    "ids": [81]},
    "skydance":        {"label": "Skydance",               "kind": "company",    "ids": [82819]},
    "syncopy":         {"label": "Syncopy",                "kind": "company",    "ids": [9996]},
    "bruckheimer":     {"label": "Jerry Bruckheimer Films", "kind": "company",   "ids": [130]},
    "apatow":          {"label": "Apatow Productions",     "kind": "company",    "ids": [10105]},
    "ghost-house":     {"label": "Ghost House Pictures",   "kind": "company",    "ids": [768]},
    # ---- more box sets ----
    "iron-man":        {"label": "Iron Man",               "kind": "collection", "ids": [131292]},
    "thor":            {"label": "Thor",                   "kind": "collection", "ids": [131296]},
    "captain-america": {"label": "Captain America",        "kind": "collection", "ids": [131295]},
    "guardians":       {"label": "Guardians of the Galaxy", "kind": "collection", "ids": [284433]},
    "ant-man":         {"label": "Ant-Man",                "kind": "collection", "ids": [422834]},
    "doctor-strange":  {"label": "Doctor Strange",         "kind": "collection", "ids": [618529]},
    "wolverine":       {"label": "The Wolverine",          "kind": "collection", "ids": [453993]},
    "venom":           {"label": "Venom",                  "kind": "collection", "ids": [558216]},
    "fantastic-four":  {"label": "Fantastic Four",         "kind": "collection", "ids": [9744]},
    "superman":        {"label": "Superman",               "kind": "collection", "ids": [8537]},
    "wonder-woman":    {"label": "Wonder Woman",           "kind": "collection", "ids": [468552]},
    "avatar":          {"label": "Avatar",                 "kind": "collection", "ids": [87096]},
    "dune":            {"label": "Dune",                   "kind": "collection", "ids": [726871]},
    "blade-runner":    {"label": "Blade Runner",           "kind": "collection", "ids": [422837]},
    "men-in-black":    {"label": "Men in Black",           "kind": "collection", "ids": [86055]},
    "ghostbusters":    {"label": "Ghostbusters",           "kind": "collection", "ids": [2980]},
    "predator":        {"label": "Predator",               "kind": "collection", "ids": [399]},
    "riddick":         {"label": "Riddick",                "kind": "collection", "ids": [2794]},
    "pacific-rim":     {"label": "Pacific Rim",            "kind": "collection", "ids": [363369]},
    "jumanji":         {"label": "Jumanji",                "kind": "collection", "ids": [495527]},
    "sonic":           {"label": "Sonic the Hedgehog",     "kind": "collection", "ids": [720879]},
    "kingsman":        {"label": "Kingsman",               "kind": "collection", "ids": [391860]},
    "sherlock-holmes": {"label": "Sherlock Holmes",        "kind": "collection", "ids": [102322]},
    "jack-reacher":    {"label": "Jack Reacher",           "kind": "collection", "ids": [403374]},
    "expendables":     {"label": "The Expendables",        "kind": "collection", "ids": [126125]},
    "rambo":           {"label": "Rambo",                  "kind": "collection", "ids": [5039]},
    "lethal-weapon":   {"label": "Lethal Weapon",          "kind": "collection", "ids": [945]},
    "bad-boys":        {"label": "Bad Boys",               "kind": "collection", "ids": [14890]},
    "rush-hour":       {"label": "Rush Hour",              "kind": "collection", "ids": [90863]},
    "beverly-hills-cop": {"label": "Beverly Hills Cop",    "kind": "collection", "ids": [85861]},
    "national-treasure": {"label": "National Treasure",    "kind": "collection", "ids": [52984]},
    "the-mummy":       {"label": "The Mummy",              "kind": "collection", "ids": [1733]},
    "robert-langdon":  {"label": "The Da Vinci Code",      "kind": "collection", "ids": [115776]},
    "kill-bill":       {"label": "Kill Bill",              "kind": "collection", "ids": [2883]},
    "godfather":       {"label": "The Godfather",          "kind": "collection", "ids": [230]},
    "karate-kid":      {"label": "The Karate Kid",         "kind": "collection", "ids": [8580]},
    "blade":           {"label": "Blade",                  "kind": "collection", "ids": [735]},
    "underworld":      {"label": "Underworld",             "kind": "collection", "ids": [2326]},
    "resident-evil":   {"label": "Resident Evil",          "kind": "collection", "ids": [17255]},
    "divergent":       {"label": "Divergent",              "kind": "collection", "ids": [283579]},
    "maze-runner":     {"label": "The Maze Runner",        "kind": "collection", "ids": [295130]},
    "narnia":          {"label": "The Chronicles of Narnia", "kind": "collection", "ids": [420]},
    "fifty-shades":    {"label": "Fifty Shades",           "kind": "collection", "ids": [344830]},
    # ---- horror box sets ----
    "saw":             {"label": "Saw",                    "kind": "collection", "ids": [656]},
    "halloween":       {"label": "Halloween",              "kind": "collection", "ids": [91361]},
    "friday-13th":     {"label": "Friday the 13th",        "kind": "collection", "ids": [9735]},
    "elm-street":      {"label": "A Nightmare on Elm Street", "kind": "collection", "ids": [8581]},
    "chucky":          {"label": "Child's Play",           "kind": "collection", "ids": [10455]},
    "evil-dead":       {"label": "Evil Dead",              "kind": "collection", "ids": [1960]},
    "hellraiser":      {"label": "Hellraiser",             "kind": "collection", "ids": [8917]},
    "final-destination": {"label": "Final Destination",    "kind": "collection", "ids": [8864]},
    "paranormal-activity": {"label": "Paranormal Activity", "kind": "collection", "ids": [41437]},
    "insidious":       {"label": "Insidious",              "kind": "collection", "ids": [228446]},
    "the-purge":       {"label": "The Purge",              "kind": "collection", "ids": [256322]},
    "scary-movie":     {"label": "Scary Movie",            "kind": "collection", "ids": [4246]},
    # ---- comedy box sets ----
    "hangover":        {"label": "The Hangover",           "kind": "collection", "ids": [86119]},
    "american-pie":    {"label": "American Pie",           "kind": "collection", "ids": [2806]},
    "meet-the-parents": {"label": "Meet the Parents",      "kind": "collection", "ids": [51509]},
    "austin-powers":   {"label": "Austin Powers",          "kind": "collection", "ids": [1006]},
    "ace-ventura":     {"label": "Ace Ventura",            "kind": "collection", "ids": [3167]},
    "home-alone":      {"label": "Home Alone",             "kind": "collection", "ids": [9888]},
    "night-at-the-museum": {"label": "Night at the Museum", "kind": "collection", "ids": [85943]},
    # ---- family / animation box sets ----
    "cars":            {"label": "Cars",                   "kind": "collection", "ids": [87118]},
    "frozen":          {"label": "Frozen",                 "kind": "collection", "ids": [386382]},
    "incredibles":     {"label": "The Incredibles",        "kind": "collection", "ids": [468222]},
    "finding-nemo":    {"label": "Finding Nemo",           "kind": "collection", "ids": [137697]},
    "monsters-inc":    {"label": "Monsters, Inc.",         "kind": "collection", "ids": [137696]},
    "wreck-it-ralph":  {"label": "Wreck-It Ralph",         "kind": "collection", "ids": [404825]},
    "madagascar":      {"label": "Madagascar",             "kind": "collection", "ids": [14740]},
    "hotel-transylvania": {"label": "Hotel Transylvania",  "kind": "collection", "ids": [185103]},
    "secret-life-of-pets": {"label": "The Secret Life of Pets", "kind": "collection", "ids": [427084]},
    "lego-movie":      {"label": "The LEGO Movie",         "kind": "collection", "ids": [325470]},
    "scooby-doo":      {"label": "Scooby-Doo",             "kind": "collection", "ids": [86860]},
    "rio":             {"label": "Rio",                    "kind": "collection", "ids": [229932]},
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


async def _logo_treatment(url: str) -> str:
    """Studio marks on TMDB are drawn for white paper.  Sample the PNG:
    dark + colourless → 'invert' (black script becomes white);
    anything colourful keeps its brand colours ('none')."""
    try:
        import io
        from PIL import Image
        async with httpx.AsyncClient(timeout=8.0) as client:
            r = await client.get(url)
            r.raise_for_status()
        im = Image.open(io.BytesIO(r.content)).convert("RGBA")
        im.thumbnail((160, 160))
        px = [p for p in im.getdata() if p[3] > 40]
        if not px:
            return "none"
        lum = sum(0.2126 * p[0] + 0.7152 * p[1] + 0.0722 * p[2] for p in px) / (255 * len(px))
        sat = sum((max(p[:3]) - min(p[:3])) / 255 for p in px) / len(px)
        if lum < 0.42 and sat < 0.18:
            return "invert"
        if lum < 0.42:
            return "plate"
        return "none"
    except Exception:
        return "none"


async def _tile_for(slug: str, cfg: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {"name": cfg["label"], "kind": cfg["kind"], "logo": None, "backdrop": None,
                           "group": "studio" if cfg["kind"] == "company" else "boxset"}
    if cfg["kind"] == "company":
        cid = cfg.get("logo_id") or cfg["ids"][0]
        try:
            co = await _tmdb(f"/company/{cid}")
            if co.get("logo_path"):
                out["logo"] = f"{IMG}/w500{co['logo_path']}"
                out["logo_treatment"] = await _logo_treatment(out["logo"])
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
    key = "collections:logos:v2"
    cached = await _cache.get(key)
    if cached:
        return {"cached": True, "data": cached}
    return {"cached": False, "data": await _build_logos()}


async def _build_logos() -> Dict[str, Any]:
    key = "collections:logos:v2"
    sem = asyncio.Semaphore(6)

    async def one(slug: str, cfg: Dict[str, Any]):
        async with sem:
            return slug, await _tile_for(slug, cfg)

    pairs = await asyncio.gather(*[one(s, c) for s, c in COLLECTIONS.items()])
    data = {s: t for s, t in pairs}
    await _cache.set(key, data, LOGOS_TTL)
    return data


@router.on_event("startup")
async def _prewarm_logos() -> None:
    async def run():
        await asyncio.sleep(15)
        try:
            if not await _cache.get("collections:logos:v2"):
                await _build_logos()
        except Exception:
            pass
    asyncio.create_task(run())


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
