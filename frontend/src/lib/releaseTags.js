import { API } from '@/lib/api';

/**
 * v2.19.4 — Batched lookup of CINEMA / HD / CAM release tags for
 * movie posters.  Tiles register their imdb id; a debounced batcher
 * fires ONE `/api/release-status?ids=...` call for the whole shelf.
 * v2.19.7 — in-memory cache ONLY (no sessionStorage): the user wants
 * tags re-checked every time the app is opened.
 */
const mem = new Map(); // imdbId -> { cinema: bool, quality: 'hd'|'cam' } | null
const pending = new Map(); // imdbId -> [callbacks]
let timer = null;

async function flush() {
    timer = null;
    const ids = [...pending.keys()].slice(0, 60);
    const batch = new Map(ids.map((id) => [id, pending.get(id)]));
    ids.forEach((id) => pending.delete(id));
    if (pending.size) timer = setTimeout(flush, 200); // drain the tail
    if (!ids.length) return;
    let data = {};
    try {
        const r = await fetch(`${API}/release-status?ids=${ids.join(',')}`);
        data = await r.json();
    } catch { /* offline — resolve as no-tag */ }
    batch.forEach((cbs, id) => {
        const tag = data[id] || null;
        mem.set(id, tag);
        cbs.forEach((cb) => { try { cb(tag); } catch { /* ignore */ } });
    });
}

export function getReleaseTag(imdbId, cb) {
    if (mem.has(imdbId)) {
        cb(mem.get(imdbId));
        return;
    }
    if (pending.has(imdbId)) pending.get(imdbId).push(cb);
    else pending.set(imdbId, [cb]);
    if (!timer) timer = setTimeout(flush, 300);
}
