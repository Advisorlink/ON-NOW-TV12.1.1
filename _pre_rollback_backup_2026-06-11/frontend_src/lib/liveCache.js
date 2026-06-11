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

const NS = 'onnowtv-livecache-v2';

import { loadChannelsIdb, saveChannelsIdb, loadEpgIdb, saveEpgIdb, isIdbSupported } from './liveCacheIdb';

/* v2.7.77 — Hydration kick-off for the IndexedDB-backed big blobs.
 *
 * IDB is async, but the rest of the app reads `loadChannels` /
 * `loadEpg` synchronously off the in-memory cache.  We solve the
 * impedance mismatch by firing an async hydrate on module load:
 * once IDB returns the previously-persisted channels + EPG, we drop
 * them into `memCache` and notify subscribers so the LiveTV page
 * re-renders with the now-populated data.
 *
 * This means a subsequent Live TV open hits memCache immediately
 * (synchronous, instant) instead of re-fetching the 6.6 MB
 * gzipped bundle from the backend.  That kills the 30–40 s wait.
 *
 * Consumers (LiveTV.jsx) can `await waitForHydration()` before
 * declaring the cache "empty" so we never wastefully refetch the
 * bundle when IDB has it ready in <100 ms. */
let _hydrationDone = false;
let _hydrationPromise = null;
async function hydrateFromIdb() {
    if (!isIdbSupported()) {
        _hydrationDone = true;
        return;
    }
    try {
        const activeId = (typeof localStorage !== 'undefined'
            ? localStorage.getItem('onnowtv-xtream-active') : '') || '';
        if (!activeId) {
            _hydrationDone = true;
            return;
        }
        const [ch, ep] = await Promise.all([
            loadChannelsIdb(activeId),
            loadEpgIdb(activeId),
        ]);
        if (ch && typeof ch === 'object') {
            memCache.set(memKey(activeId, 'chans'), ch);
        }
        if (ep && typeof ep === 'object') {
            memCache.set(memKey(activeId, 'epg'), ep);
        }
        _hydrationDone = true;
        if (ch || ep) notifyCacheUpdate('hydrate', activeId);
    } catch {
        _hydrationDone = true;
    }
}

/** Promise that resolves once the initial IndexedDB hydration has
 *  completed (or failed).  Safe to await from anywhere — resolves
 *  immediately on subsequent calls.  Used by LiveTV.jsx to ensure
 *  we don't refetch the 6 MB bundle when IDB already has it. */
export function waitForHydration() {
    if (_hydrationDone) return Promise.resolve();
    if (!_hydrationPromise) _hydrationPromise = hydrateFromIdb();
    return _hydrationPromise;
}

// v2.7.50 — One-time boot sweep: any legacy entry larger than 1 MB
// (typically the giant EPG blob from v2.7.49 and earlier) gets
// removed so we reclaim localStorage quota for shelves / heroes /
// library on cold boot.  Runs exactly once per page load.
//
// v2.7.77 — Channels and EPG now live in IndexedDB, so any oversized
// entry left in localStorage is purely legacy garbage from older
// app versions and can be safely reaped.
(function reclaimQuota() {
    try {
        if (typeof localStorage === 'undefined') return;
        for (let i = localStorage.length - 1; i >= 0; i--) {
            const key = localStorage.key(i);
            if (!key || !key.startsWith(NS)) continue;
            const val = localStorage.getItem(key);
            if (val && val.length > 1_000_000) {
                try { localStorage.removeItem(key); } catch { /* ignore */ }
            }
        }
    } catch { /* localStorage disabled */ }
})();

/* In-memory cache fallback — survives the current session even when
 * localStorage quota is exceeded.  Keyed by `${kind}:${providerId}`
 * so each cache kind (cats / chans / epg) stays distinct.  Reads
 * check memory FIRST (cheaper than a JSON.parse anyway), then
 * fall through to localStorage. */
const memCache = new Map();
function memKey(providerId, kind) {
    return `${kind}:${providerId}`;
}

/* v2.7.77 — Kick off the IndexedDB hydration the moment memCache is
 * declared.  Done on next microtask so the rest of this module
 * finishes its top-level setup first (subscribers Set, etc.). */
Promise.resolve().then(() => { waitForHydration(); });

/* ── Pub-sub for cache updates ──────────────────────────────────
 * Consumers like the SportsGuide need to re-render when fresh
 * EPG / channel / category data arrives asynchronously from the
 * instant-bundle fetch (the React useMemo deps don't include
 * anything that changes when the cache populates).  Components
 * subscribe and bump a state counter to force a recompute. */
const subscribers = new Set();

export function subscribeLiveCache(cb) {
    if (typeof cb !== 'function') return () => {};
    subscribers.add(cb);
    return () => { subscribers.delete(cb); };
}

let notifyScheduled = false;
function notifyCacheUpdate(kind, providerId) {
    // Coalesce multi-write bursts (cats + chans + epg arrive in
    // quick succession) into a single notify on the next tick.
    if (notifyScheduled) return;
    notifyScheduled = true;
    Promise.resolve().then(() => {
        notifyScheduled = false;
        for (const cb of [...subscribers]) {
            try { cb({ kind, providerId }); }
            catch (e) {
                // eslint-disable-next-line no-console
                console.warn('[liveCache] subscriber threw:', e);
            }
        }
    });
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

// v2.7.50 — Hard ceiling for any single localStorage write.  EPG
// blobs hit 42 MB on rich providers, which:
//   1. NEVER fits in a browser's 5–10 MB per-origin quota.
//   2. Throws QuotaExceededError, which on some Chromium builds
//      poisons subsequent writes in the same tab → other caches
//      (shelves / heroes / library) silently fail to persist →
//      cold-boot catalog data goes missing after every reboot.
// We hard-cap at 1 MB.  Anything bigger stays in-memory only
// (loadEpg() still returns it for the session) but never tries to
// touch disk.
const MAX_PERSIST_BYTES = 1_000_000;

function safeWrite(key, payload) {
    try {
        const blob = JSON.stringify(payload);
        if (blob.length > MAX_PERSIST_BYTES) {
            // Too big to persist — skip silently so we don't corrupt
            // localStorage for everyone else.
            return false;
        }
        localStorage.setItem(key, blob);
        return true;
    } catch (e) {
        try {
            const size = (payload && typeof payload === 'object')
                ? JSON.stringify(payload).length : 0;
            // eslint-disable-next-line no-console
            console.warn(
                `[liveCache] quota write failed for ${key} (size=${size}B):`,
                e?.message || e
            );
        } catch { /* nested toString failure — give up */ }
        // Best-effort cleanup: drop the bloated EPG key so we don't
        // strand 5+ MB in localStorage that we'll never read back
        // (the in-memory copy is what the session uses).
        try { localStorage.removeItem(key); } catch { /* ignore */ }
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
    notifyCacheUpdate('cats', providerId);
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
    // v2.7.77 — Channels are typically 5–10 MB JSON which exceeds
    // both our 1 MB localStorage cap and the WebView per-origin
    // quota anyway.  Write through to IndexedDB (no quota issues)
    // and skip the localStorage attempt entirely so we don't waste
    // a quota write.
    saveChannelsIdb(providerId, byCatId);
    notifyCacheUpdate('chans', providerId);
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
    // v2.7.77 — EPG is the biggest blob (up to ~40 MB on rich
    // providers).  localStorage can't hold it; IndexedDB can.
    // Write through to IDB so the next cold boot has it ready
    // synchronously (via memCache, hydrated on module load).
    saveEpgIdb(providerId, existing);
    // localStorage still gets a best-effort subset write so the
    // legacy /sports cold-boot path keeps working.  Failure here
    // is silent (the IDB write above is the source of truth).
    if (!safeWrite(k(providerId, 'epg'), { at: Date.now(), data: existing })) {
        const keys = Object.keys(existing);
        keys.sort((a, b) => (existing[a]?.length || 0) - (existing[b]?.length || 0));
        const trimmed = {};
        for (let i = 0; i < Math.floor(keys.length / 2); i++) {
            trimmed[keys[i]] = existing[keys[i]];
        }
        safeWrite(k(providerId, 'epg'), { at: Date.now(), data: trimmed });
    }
    notifyCacheUpdate('epg', providerId);
}

/** Persist ONLY the EPG entries for `keysToPersist` (a Set of
 *  epg_channel_ids) to disk, while keeping the full in-memory cache
 *  intact.  Used by the cold-boot path so the next /sports visit
 *  renders chips at T+0 instead of T+15-30s waiting for the bundle
 *  to merge again.  The sports-only subset is small enough (~50–80
 *  channels = <500 KB) to fit comfortably inside localStorage. */
export function persistEpgSubset(providerId, keysToPersist) {
    if (!(keysToPersist instanceof Set) || keysToPersist.size === 0) return;
    const full = memCache.get(memKey(providerId, 'epg'));
    if (!full || typeof full !== 'object') return;
    const subset = {};
    for (const key of keysToPersist) {
        if (full[key]) subset[key] = full[key];
    }
    if (Object.keys(subset).length === 0) return;
    safeWrite(k(providerId, 'epg'), { at: Date.now(), data: subset });
}
