/**
 * Live TV favorites store — keyed by provider id so multiple Xtream
 * providers don't share each other's stars.  Persisted to
 * localStorage so the user's favorites survive APK reinstalls
 * (WebView storage is preserved on package upgrade).
 *
 *   shape:  { [providerId]: number[]   // stream_ids
 *           , __lastSnapshot: string  // for cross-tab change events
 *           }
 *
 * We store an array (not a Set) so JSON round-trip is trivial.  The
 * in-memory cache is the source of truth during a session; we only
 * read localStorage once on first call.
 */

const KEY = 'onnowtv-live-favorites-v1';

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

export function getFavorites(providerId) {
    const c = load();
    return Array.isArray(c[providerId]) ? c[providerId] : [];
}

export function isFavorite(providerId, streamId) {
    if (providerId == null || streamId == null) return false;
    return getFavorites(providerId).includes(Number(streamId) || streamId);
}

export function toggleFavorite(providerId, streamId) {
    if (providerId == null || streamId == null) return false;
    const c = load();
    const sid = Number(streamId) || streamId;
    const list = Array.isArray(c[providerId]) ? c[providerId].slice() : [];
    const idx = list.indexOf(sid);
    let nowOn;
    if (idx >= 0) {
        list.splice(idx, 1);
        nowOn = false;
    } else {
        list.push(sid);
        nowOn = true;
    }
    c[providerId] = list;
    persist();
    return nowOn;
}
