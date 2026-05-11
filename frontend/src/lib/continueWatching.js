/**
 * Continue Watching store.
 *
 * Tracks items the user has started watching.  Each entry:
 *
 *   {
 *     id          (imdb / metahub id, unique)
 *     type        ('movie' | 'series')
 *     title
 *     backdrop    (16:9 hero art, used for the landscape tile)
 *     poster      (vertical, used for the player loading screen)
 *     synopsis
 *     year, rating, runtime, genres
 *     positionMs  (last reported playback position)
 *     durationMs  (total stream length, when known)
 *     streamUrl   (so resume click can re-open the same stream)
 *     subtitleUrl (English subtitle, when available)
 *     updatedAt   (ms timestamp)
 *     route       (Detail page path, fallback for resume click)
 *   }
 *
 * Backed by localStorage so it survives full app restarts.  When
 * running inside the Android wrapper, progress updates from the
 * native VlcPlayerActivity are merged in via OnNowTV.getProgressMap()
 * (called from Home.jsx on every mount).
 */

const STORAGE_KEY = 'onnowtv-continue-watching-v1';
const MAX_ENTRIES = 30;

function readAll() {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (!raw) return [];
        const list = JSON.parse(raw);
        return Array.isArray(list) ? list : [];
    } catch {
        return [];
    }
}

function writeAll(list) {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(list.slice(0, MAX_ENTRIES)));
    } catch {
        /* quota / disabled */
    }
}

export function getEntries() {
    return readAll()
        .slice()
        .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

export function upsert(partial) {
    if (!partial?.id) return;
    const list = readAll();
    const i = list.findIndex((e) => e.id === partial.id);
    const now = Date.now();
    const next = i >= 0
        ? { ...list[i], ...partial, updatedAt: now }
        : { positionMs: 0, durationMs: 0, ...partial, updatedAt: now };
    if (i >= 0) list[i] = next;
    else list.unshift(next);
    writeAll(list);
}

export function remove(id) {
    const list = readAll().filter((e) => e.id !== id);
    writeAll(list);
}

/**
 * Pull native progress reports (when available) and merge into the
 * local list.  Safe to call on every Home mount.  No-op outside
 * the Android wrapper.
 */
export function syncFromNative() {
    if (typeof window === 'undefined') return;
    const bridge = window.OnNowTV;
    if (!bridge || typeof bridge.getProgressMap !== 'function') return;
    try {
        const raw = bridge.getProgressMap();
        if (!raw) return;
        const map = typeof raw === 'string' ? JSON.parse(raw) : raw;
        if (!map || typeof map !== 'object') return;
        const list = readAll();
        let changed = false;
        for (const id of Object.keys(map)) {
            const i = list.findIndex((e) => e.id === id);
            if (i < 0) continue;
            const p = map[id];
            if (!p || typeof p.positionMs !== 'number') continue;
            // Use whichever timestamp is newer.
            const oldUpdated = list[i].updatedAt || 0;
            const newUpdated = p.updatedAt || Date.now();
            if (
                p.positionMs !== list[i].positionMs ||
                (p.durationMs && p.durationMs !== list[i].durationMs)
            ) {
                list[i] = {
                    ...list[i],
                    positionMs: p.positionMs,
                    durationMs: p.durationMs || list[i].durationMs,
                    updatedAt: Math.max(oldUpdated, newUpdated),
                };
                changed = true;
            }
        }
        if (changed) writeAll(list);
    } catch {
        /* swallow */
    }
}

/**
 * Mark an item as completed (positionMs near durationMs).  We remove
 * it from the list to keep the shelf tidy.
 */
export function maybeMarkCompleted(id, positionMs, durationMs) {
    if (!durationMs || !positionMs) return;
    // Within the last 30 seconds — count as done.
    if (durationMs - positionMs < 30_000) {
        remove(id);
    }
}
