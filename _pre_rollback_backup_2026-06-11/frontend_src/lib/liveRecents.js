/**
 * Live TV recently-watched store — last N channels the user actually
 * pressed Play on, keyed by provider id (so different IPTV providers
 * keep separate histories).
 *
 *   shape:  { [providerId]: number[]    // stream_ids, MRU first
 *           }
 *
 * Capped at MAX entries.  No timestamps stored — order alone implies
 * recency.  Persisted to localStorage so it survives APK reinstalls.
 */

const KEY = 'onnowtv-live-recents-v1';
const MAX = 10;

let cache = null;

function load() {
    if (cache) return cache;
    try {
        const raw = localStorage.getItem(KEY);
        cache = raw ? JSON.parse(raw) : {};
        if (typeof cache !== 'object' || cache === null) cache = {};
    } catch {
        cache = {};
    }
    return cache;
}

function persist() {
    try { localStorage.setItem(KEY, JSON.stringify(cache)); } catch { /* ignore */ }
}

export function getRecents(providerId) {
    const c = load();
    return Array.isArray(c[providerId]) ? c[providerId] : [];
}

/** Move a stream_id to the front of the MRU list; cap at MAX. */
export function pushRecent(providerId, streamId) {
    if (providerId == null || streamId == null) return;
    const c = load();
    const sid = Number(streamId) || streamId;
    const cur = Array.isArray(c[providerId]) ? c[providerId].slice() : [];
    const idx = cur.indexOf(sid);
    if (idx >= 0) cur.splice(idx, 1);
    cur.unshift(sid);
    if (cur.length > MAX) cur.length = MAX;
    c[providerId] = cur;
    persist();
}
