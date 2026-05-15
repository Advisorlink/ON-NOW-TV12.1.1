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
    } catch {
        // Quota exceeded or storage disabled — degrade silently.
        return false;
    }
}

// -------- categories --------

export function loadCategories(providerId) {
    const got = safeRead(k(providerId, 'cats'));
    return got && Array.isArray(got.data) ? got.data : null;
}

export function saveCategories(providerId, list) {
    if (!Array.isArray(list)) return;
    safeWrite(k(providerId, 'cats'), { at: Date.now(), data: list });
}

// -------- channels (per-category map) --------

export function loadChannels(providerId) {
    const got = safeRead(k(providerId, 'chans'));
    return got && got.data && typeof got.data === 'object' ? got.data : null;
}

export function saveChannels(providerId, byCatId) {
    if (!byCatId || typeof byCatId !== 'object') return;
    safeWrite(k(providerId, 'chans'), { at: Date.now(), data: byCatId });
}

// -------- EPG (per-stream map) --------
// We persist EPG opportunistically.  Each call merges the new
// entries with whatever's already on disk (so an in-progress
// background sync doesn't blow away last session's cache when it's
// only halfway done) and prunes anything whose stop_timestamp has
// already passed.

export function loadEpg(providerId) {
    const got = safeRead(k(providerId, 'epg'));
    return got && got.data && typeof got.data === 'object' ? got.data : null;
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
