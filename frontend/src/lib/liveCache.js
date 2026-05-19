/**
 * Live TV persistent cache — backed by localStorage.
 *
 * Categories, channel lists, and EPG snapshots are written through
 * here whenever the network sync fetches them, and read back on app
 * launch so the LiveTV grid can paint a fully populated UI before
 * the IPTV server has answered a single request.
 *
 *   shape:
 *     `${NS}:${providerId}:cats`   →  { at, data: Category[] }
 *     `${NS}:${providerId}:chans`  →  { at, data: { [catId]: Channel[] } }
 *     `${NS}:${providerId}:epg`    →  { at, data: { [streamId]: EpgItem[] } }
 *
 * Quotas matter on the HK1 box (~5 MB localStorage).  EPG persistence
 * is opt-in per call: the boot path passes only the EPG entries that
 * are still in their validity window (stop_timestamp > now), and we
 * try/catch every write so a quota error never crashes the app.
 *
 * Versioned keys (`-v1`) so we can rev the schema later without
 * needing to read-and-migrate stale blobs.
 */

const NS = 'onnowtv-livecache-v1';

/* In-memory cache fallback — survives the current session even when
 * localStorage quota is exceeded.  Keyed by `${kind}:${providerId}`
 * so each cache kind (cats / chans / epg) stays distinct.  Reads
 * check memory FIRST (cheaper than a JSON.parse anyway), then
 * fall through to localStorage. */
const memCache = new Map();
function memKey(providerId, kind) {
    return `${kind}:${providerId}`;
}

function k(providerId, kind) {
    return `${NS}:${providerId}:${kind}`;
}

function safeRead(key) {
    try {
        const raw = localStorage.getItem(key);
        if (!raw) return null;
        const parsed = JSON.parse(raw);
        if (parsed && typeof parsed === 'object' && 'data' in parsed) return parsed;
        return null;
    } catch { return null; }
}

function safeWrite(key, payload) {
    try {
        localStorage.setItem(key, JSON.stringify(payload));
        return true;
    } catch (e) {
        // Quota exceeded or storage disabled — degrade GRACEFULLY,
        // but log so the next debug session doesn't waste days
        // chasing a silent quota bust like we did with the sports
        // chips.  In-memory cache (caller responsibility) still
        // covers the running session.
        try {
            const size = (payload && typeof payload === 'object')
                ? JSON.stringify(payload).length : 0;
            // eslint-disable-next-line no-console
            console.warn(
                `[liveCache] quota write failed for ${key} (size=${size}B):`,
                e?.message || e
            );
        } catch { /* nested toString failure — give up */ }
        return false;
    }
}

// -------- categories --------

export function loadCategories(providerId) {
    const mem = memCache.get(memKey(providerId, 'cats'));
    if (mem) return mem;
    const got = safeRead(k(providerId, 'cats'));
    if (got && Array.isArray(got.data)) {
        memCache.set(memKey(providerId, 'cats'), got.data);
        return got.data;
    }
    return null;
}

export function saveCategories(providerId, list) {
    if (!Array.isArray(list)) return;
    memCache.set(memKey(providerId, 'cats'), list);
    safeWrite(k(providerId, 'cats'), { at: Date.now(), data: list });
}

// -------- channels (per-category map) --------

export function loadChannels(providerId) {
    const mem = memCache.get(memKey(providerId, 'chans'));
    if (mem) return mem;
    const got = safeRead(k(providerId, 'chans'));
    if (got && got.data && typeof got.data === 'object') {
        memCache.set(memKey(providerId, 'chans'), got.data);
        return got.data;
    }
    return null;
}

export function saveChannels(providerId, byCatId) {
    if (!byCatId || typeof byCatId !== 'object') return;
    memCache.set(memKey(providerId, 'chans'), byCatId);
    safeWrite(k(providerId, 'chans'), { at: Date.now(), data: byCatId });
}

// -------- EPG (per-stream map) --------
// We persist EPG opportunistically.  Each call merges the new
// entries with whatever's already on disk (so an in-progress
// background sync doesn't blow away last session's cache when it's
// only halfway done) and prunes anything whose stop_timestamp has
// already passed.

export function loadEpg(providerId) {
    const mem = memCache.get(memKey(providerId, 'epg'));
    if (mem) return mem;
    const got = safeRead(k(providerId, 'epg'));
    if (got && got.data && typeof got.data === 'object') {
        memCache.set(memKey(providerId, 'epg'), got.data);
        return got.data;
    }
    return null;
}

/** Merge `partial` into the persisted EPG map (in chunks if too
 *  big), pruning expired entries.  Best-effort: if localStorage is
 *  full we drop the write and the in-memory cache is still good for
 *  this session. */
export function mergeAndSaveEpg(providerId, partial) {
    if (!partial || typeof partial !== 'object') return;
    const nowSec = Math.floor(Date.now() / 1000);
    const existing = loadEpg(providerId) || {};
    let dirty = false;
    // Merge new entries.
    for (const sid in partial) {
        if (!Object.prototype.hasOwnProperty.call(partial, sid)) continue;
        const items = (partial[sid] || []).filter(
            (it) => Number(it.stopTimestamp || 0) > nowSec,
        );
        if (items.length === 0) continue;
        existing[sid] = items;
        dirty = true;
    }
    // Prune anything whose final entry has expired.
    for (const sid in existing) {
        if (!Object.prototype.hasOwnProperty.call(existing, sid)) continue;
        const arr = existing[sid] || [];
        const stillValid = arr.filter(
            (it) => Number(it.stopTimestamp || 0) > nowSec,
        );
        if (stillValid.length === 0) {
            delete existing[sid];
            dirty = true;
        } else if (stillValid.length !== arr.length) {
            existing[sid] = stillValid;
            dirty = true;
        }
    }
    if (!dirty) return;
    // Always update the in-memory cache so the running session
    // has the data even if localStorage rejects the write.  This is
    // what saved us when the 30 MB EPG blob hit the ~5–10 MB quota
    // and silently failed — sportsMatch.buildIndex was iterating an
    // empty `for (const epgId in epg)` loop.  Now the in-memory
    // map has the data immediately, regardless of disk success.
    memCache.set(memKey(providerId, 'epg'), existing);
    if (!safeWrite(k(providerId, 'epg'), { at: Date.now(), data: existing })) {
        // Quota exceeded — try writing only the smallest 50% of
        // entries so at least *some* hot data persists.
        const keys = Object.keys(existing);
        keys.sort((a, b) => (existing[a]?.length || 0) - (existing[b]?.length || 0));
        const trimmed = {};
        for (let i = 0; i < Math.floor(keys.length / 2); i++) {
            trimmed[keys[i]] = existing[keys[i]];
        }
        safeWrite(k(providerId, 'epg'), { at: Date.now(), data: trimmed });
    }
}
