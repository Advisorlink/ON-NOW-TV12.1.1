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
const WATCHED_KEY = 'onnowtv-watched-v1';
const MAX_ENTRIES = 30;

// All reads / writes go through profile-scoped helpers so each
// profile keeps its own Continue Watching + Watched state.  Legacy
// pre-profile data is still readable via the fallback inside
// `readScopedString` so existing installs don't lose anything on
// the upgrade.
import { readScopedString, writeScopedString } from '@/lib/profileScope';

function readAll() {
    try {
        const raw = readScopedString(STORAGE_KEY);
        if (!raw) return [];
        const list = JSON.parse(raw);
        return Array.isArray(list) ? list : [];
    } catch {
        return [];
    }
}

function writeAll(list) {
    try {
        writeScopedString(
            STORAGE_KEY,
            JSON.stringify(list.slice(0, MAX_ENTRIES))
        );
    } catch {
        /* quota / disabled */
    }
}

function readWatchedSet() {
    try {
        const raw = readScopedString(WATCHED_KEY);
        if (!raw) return new Set();
        const arr = JSON.parse(raw);
        return new Set(Array.isArray(arr) ? arr : []);
    } catch {
        return new Set();
    }
}

function writeWatchedSet(set) {
    try {
        writeScopedString(WATCHED_KEY, JSON.stringify(Array.from(set)));
    } catch {
        /* ignore */
    }
}

/**
 * Check whether a given entry's progress qualifies as "watched"
 * (≥92 % through, or within the last 60 s).
 */
function progressIsWatched(positionMs, durationMs) {
    const p = Number(positionMs) || 0;
    const d = Number(durationMs) || 0;
    if (!d || !p) return false;
    if (p / d >= 0.92) return true;
    if (d - p <= 60_000) return true;
    return false;
}

function markWatchedIfDone(id, positionMs, durationMs) {
    if (!id) return;
    if (!progressIsWatched(positionMs, durationMs)) return;
    const set = readWatchedSet();
    if (set.has(id)) return;
    set.add(id);
    writeWatchedSet(set);
}

export function getEntries() {
    const all = readAll()
        .slice()
        .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
    // v2.10.46 — Dedup by show.  When the user skips next episode
    // inside the native player (S1E3 → S1E4 → S1E5), each landed
    // episode is its own CW row.  The shelf should show ONE entry
    // per show — the most recently watched episode — not the
    // history of every skip.  For series the show key is the
    // IMDB id (the substring before the first ':').  Movies have
    // no ':' so they dedup by their own id (effectively a no-op).
    const seen = new Set();
    const out = [];
    for (const e of all) {
        const isSeries = e.type === 'series' && typeof e.id === 'string' && e.id.includes(':');
        const key = isSeries ? e.id.split(':')[0] : e.id;
        if (!key) continue;
        if (seen.has(key)) continue;
        seen.add(key);
        out.push(e);
    }
    return out;
}

/** Lookup a single CW entry by id.  Returns undefined if the entry
 *  doesn't exist.  Used by the resume flow to grab the FRESH
 *  position out of storage right before firing the player, since
 *  the shelf's React state can be slightly stale. */
export function getEntry(id) {
    if (!id) return undefined;
    return readAll().find((e) => e.id === id);
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
    markWatchedIfDone(next.id, next.positionMs, next.durationMs);
}

export function remove(id) {
    const list = readAll().filter((e) => e.id !== id);
    writeAll(list);
}

/**
 * Pull native progress reports (when available) and merge into the
 * local list.  Safe to call on every Home mount.  No-op outside
 * the Android wrapper.
 *
 * v2.10.46 — Three passes:
 *   1) Detect NEW ids written by the native player (in-place
 *      next-episode swap) that don't exist locally yet.  Clone
 *      metadata from any sibling entry of the same show so the
 *      tile has title/backdrop/poster/streamUrl — derive the new
 *      "S1E4" label from the new cwId.
 *   2) Update progress on existing entries.
 *   3) Remove entries whose progress crossed the "completed"
 *      threshold (so the OLD S1E3 row disappears after the swap
 *      wrote it to 100%).
 */
function showPrefixOf(id) {
    if (!id || typeof id !== 'string') return '';
    return id.split(':')[0] || '';
}
function seasonEpisodeOf(id) {
    if (!id || typeof id !== 'string') return null;
    const m1 = id.match(/:s(\d+)e(\d+)$/i);
    if (m1) return { season: Number(m1[1]), episode: Number(m1[2]) };
    const m2 = id.match(/:(\d+):(\d+)$/);
    if (m2) return { season: Number(m2[1]), episode: Number(m2[2]) };
    return null;
}
function rewriteEpisodeLabel(oldTitle, newId) {
    if (!oldTitle) return oldTitle;
    const se = seasonEpisodeOf(newId);
    if (!se) return oldTitle;
    const newLabel = `S${se.season}E${se.episode}`;
    // Strip the existing "· SxxExx · episodeName" tail (or just
    // "· SxxExx") and append the new label.  Handles both padded
    // and unpadded numbers ("S1E3" or "S01E03").
    const cleaned = oldTitle
        .replace(/\s*[·•|]\s*S\d{1,2}E\d{1,2}\b.*$/i, '')
        .trim();
    return cleaned ? `${cleaned} · ${newLabel}` : newLabel;
}
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

        // Pass 1 — clone metadata for NEW ids the native player
        // created by skipping to a next episode inside the
        // activity.  We need to do this BEFORE pass 3 removes
        // completed entries, otherwise the sibling we'd clone
        // from may already be gone.
        for (const id of Object.keys(map)) {
            if (list.findIndex((e) => e.id === id) >= 0) continue;
            const p = map[id];
            if (!p || typeof p.positionMs !== 'number') continue;
            const prefix = showPrefixOf(id);
            if (!prefix) continue;
            const sibling = list.find(
                (e) =>
                    typeof e.id === 'string' &&
                    e.id !== id &&
                    showPrefixOf(e.id) === prefix
            );
            if (!sibling) continue;
            const cloned = {
                ...sibling,
                id,
                title: rewriteEpisodeLabel(sibling.title, id),
                positionMs: p.positionMs,
                durationMs: p.durationMs || sibling.durationMs || 0,
                updatedAt: p.updatedAt || Date.now(),
                // The sibling's streamUrl + subtitleUrl point at the
                // OLD episode — using them on resume would play the
                // wrong episode.  Blank them so the resume flow
                // falls through to the Detail page where the user
                // (or the native bridge) picks a stream for THIS
                // episode.  The `route` field is preserved because
                // it's `/title/series/<imdbId>` — same for every
                // episode of the show.
                streamUrl: '',
                subtitleUrl: '',
            };
            list.unshift(cloned);
            changed = true;
        }

        // Pass 2 — refresh progress on entries that already exist.
        for (const id of Object.keys(map)) {
            const i = list.findIndex((e) => e.id === id);
            if (i < 0) continue;
            const p = map[id];
            if (!p || typeof p.positionMs !== 'number') continue;
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
                markWatchedIfDone(
                    id,
                    list[i].positionMs,
                    list[i].durationMs
                );
                changed = true;
            }
        }

        // Pass 3 — drop entries whose progress crossed the
        // completion threshold (native player writes the OLD
        // episode at 100 % during a next-episode swap, so this
        // tidies the row away).
        const kept = [];
        for (const e of list) {
            const finished =
                e.durationMs && e.positionMs &&
                e.durationMs - e.positionMs < 30_000;
            if (finished) {
                changed = true;
                continue;
            }
            kept.push(e);
        }

        if (changed) writeAll(kept);
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

/**
 * Returns true when the given cwId has been watched at least 92 % of
 * the way through (or within 60 s of the end if duration is known).
 * Reads the durable watched-set so the badge persists even after
 * the user removes the entry from the Continue Watching shelf.
 */
export function isWatched(id) {
    if (!id) return false;
    const set = readWatchedSet();
    if (set.has(id)) return true;
    // Fallback: derive from any current CW entry — handles items
    // recorded before the watched set was introduced.
    const list = readAll();
    const e = list.find((x) => x.id === id);
    return !!e && progressIsWatched(e.positionMs, e.durationMs);
}

/**
 * Hard-clear the "watched" flag for a single id.  Useful if the
 * user wants to re-mark an episode as unseen.
 */
export function markUnwatched(id) {
    if (!id) return;
    const set = readWatchedSet();
    if (set.delete(id)) writeWatchedSet(set);
}

/**
 * Return the raw progress entry (or null) so callers can show a
 * partial progress bar on tiles for in-progress titles.
 */
export function getProgress(id) {
    if (!id) return null;
    const list = readAll();
    return list.find((x) => x.id === id) || null;
}
