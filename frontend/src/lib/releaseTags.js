import { API } from '@/lib/api';

/**
 * v2.19.4 — Batched lookup of CINEMA / CAM COPY release tags for
 * movie posters.  Tiles register their imdb id; a debounced batcher
 * fires ONE `/api/release-status?ids=...` call for the whole shelf.
 * Results cached in memory + sessionStorage (6h).
 */
const SS_KEY = 'vesper-release-tags-v1';
const TTL_MS = 6 * 60 * 60 * 1000;

const mem = new Map(); // imdbId -> 'cinema' | 'cam' | null
const pending = new Map(); // imdbId -> [callbacks]
let timer = null;

(() => {
    try {
        const raw = JSON.parse(sessionStorage.getItem(SS_KEY) || 'null');
        if (raw && Date.now() - raw.ts < TTL_MS) {
            Object.entries(raw.map || {}).forEach(([k, v]) => mem.set(k, v));
        }
    } catch { /* ignore */ }
})();

function persist() {
    try {
        sessionStorage.setItem(SS_KEY, JSON.stringify({
            ts: Date.now(),
            map: Object.fromEntries(mem),
        }));
    } catch { /* ignore */ }
}

async function flush() {
    timer = null;
    const batch = new Map(pending);
    pending.clear();
    const ids = [...batch.keys()].slice(0, 60);
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
    persist();
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
