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

/**
 * Collapse multiple series rows that share the same show into a
 * single entry — the most recently updated one wins.  This is a
 * read-time migration covering existing installs where prior
 * upserts wrote one row per episode before the dedupe-by-seriesId
 * rule landed in v2.10.7.  The "seriesId" anchor is derived from
 * either the explicit `seriesId` field (newer writes) OR the stem
 * of a composite CW id (`tt1234:s1e2` → `tt1234`), so even rows
 * that never carried `seriesId` get collapsed.
 */
function collapseSeriesDuplicates(list) {
    if (!Array.isArray(list) || list.length === 0) return list;
    const stemOf = (e) => {
        if (e.seriesId) return e.seriesId;
        if (typeof e.id === 'string' && e.id.includes(':')) {
            return e.id.split(':')[0];
        }
        return null;
    };
    const seen = new Map(); // showStem -> entry (latest)
    const out = [];
    let changed = false;
    for (const e of list) {
        if (e?.type !== 'series') {
            out.push(e);
            continue;
        }
        const stem = stemOf(e);
        if (!stem) {
            out.push(e);
            continue;
        }
        const prev = seen.get(stem);
        if (!prev) {
            seen.set(stem, e);
            out.push(e);
            continue;
        }
        changed = true;
        // Keep whichever entry was updated most recently.
        const winner = (e.updatedAt || 0) > (prev.updatedAt || 0) ? e : prev;
        // Replace the previously pushed prev with the winner.
        const idx = out.indexOf(prev);
        if (idx >= 0) out[idx] = winner;
        seen.set(stem, winner);
    }
    return changed ? out : list;
}

function readAll() {
    try {
        const raw = readScopedString(STORAGE_KEY);
        if (!raw) return [];
        const list = JSON.parse(raw);
        if (!Array.isArray(list)) return [];
        const collapsed = collapseSeriesDuplicates(list);
        // Persist the collapsed list so subsequent reads short-circuit
        // and the cleanup only runs once per affected install.
        if (collapsed !== list) {
            try {
                writeScopedString(
                    STORAGE_KEY,
                    JSON.stringify(collapsed.slice(0, MAX_ENTRIES))
                );
            } catch { /* ignore */ }
        }
        return collapsed;
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
    return readAll()
        .slice()
        .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
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
    const now = Date.now();
    // v2.10.7 — User requirement: only ONE Continue Watching entry
    // per TV show, never duplicates.  Movies still dedupe by `id`
    // (one row per movie), but series dedupe by `seriesId` so the
    // shelf only ever shows the latest episode the user touched
    // for any given show.  When a new episode is upserted we
    // delete every entry sharing the same seriesId before adding
    // the new one.
    const dedupeKey = partial.type === 'series' && partial.seriesId
        ? (e) => e.seriesId === partial.seriesId
        : (e) => e.id === partial.id;
    const existingIdx = list.findIndex(dedupeKey);
    const next = existingIdx >= 0
        ? { ...list[existingIdx], ...partial, updatedAt: now }
        : { positionMs: 0, durationMs: 0, ...partial, updatedAt: now };
    // If we're replacing a series entry, drop ALL older entries
    // matching the same seriesId — covers the (rare) case where a
    // prior upsert wrote multiple rows before this dedupe rule
    // landed, so existing installs are tidied up on next watch.
    let filtered = list.filter((e, i) => i === existingIdx || !dedupeKey(e));
    if (existingIdx >= 0) {
        filtered[filtered.findIndex(dedupeKey)] = next;
    } else {
        filtered.unshift(next);
    }
    writeAll(filtered);
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
                markWatchedIfDone(
                    id,
                    list[i].positionMs,
                    list[i].durationMs
                );
                changed = true;
            }
        }
        if (changed) writeAll(list);
    } catch {
        /* swallow */
    }
}

/**
 * Mark an item as completed (positionMs near durationMs).
 *
 * v2.10.7 — Behaviour now differs by type:
 *   • Movies: remove from the shelf as before.
 *   • Series: instead of removing, ADVANCE the entry to the next
 *     episode in the same season.  positionMs / durationMs are
 *     reset to 0 so the shelf renders the new episode as "fresh".
 *     The `id` field is rewritten to the next episode's composite
 *     (`imdbId:season:episode+1`) so the shelf's resume click
 *     navigates straight to the right episode.  Auto-advance only
 *     attempts +1 within the same season; if the season ends, the
 *     Detail page handles cross-season jumping when the user clicks
 *     resume.
 */
export function maybeMarkCompleted(id, positionMs, durationMs) {
    if (!durationMs || !positionMs) return;
    if (durationMs - positionMs >= 30_000) return;
    const list = readAll();
    const i = list.findIndex((e) => e.id === id);
    if (i < 0) return;
    const entry = list[i];
    if (entry.type === 'series' && entry.seriesId && entry.episode) {
        const nextEp = (Number(entry.episode) || 0) + 1;
        const season = Number(entry.season) || 1;
        const nextId = `${entry.seriesId}:${season}:${nextEp}`;
        list[i] = {
            ...entry,
            id: nextId,
            episode: nextEp,
            season,
            positionMs: 0,
            durationMs: 0,
            // Clear the cached stream URL — it's stale for the
            // new episode.  Detail page will resolve the next
            // episode's stream on resume click.
            streamUrl: '',
            subtitleUrl: '',
            episodeLabel: `S${String(season).padStart(2, '0')}E${String(nextEp).padStart(2, '0')}`,
            awaitingNextEpisode: true,
            updatedAt: Date.now(),
        };
        writeAll(list);
        return;
    }
    // Movie (or series entry missing the metadata to advance) →
    // remove from the shelf.
    remove(id);
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
