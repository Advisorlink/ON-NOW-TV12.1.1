/**
 * My Library — per-profile favourites + Watch-Later queue.
 *
 * Storage shape (one key per profile, via profileScope):
 *
 *   {
 *     favorites: {
 *       "tt1234567": {
 *         type: "series" | "movie",
 *         addedAt: ISO,
 *         lastSeenAt: ISO,        // for "what's been notified" tracking
 *         meta: { name, poster, year }
 *       },
 *       ...
 *     },
 *     watchLater: [
 *       {
 *         id: "tt1234567",        // show id
 *         type: "series",
 *         episode: { season, number, name, aired },
 *         addedAt: ISO,
 *         showMeta: { name, poster }
 *       }
 *     ],
 *     // notifications that have been shown / dismissed so we
 *     // don't re-fire them every page load
 *     dismissed: { "tt1234567:S3E5": ISO }
 *   }
 */

import { readScopedString, writeScopedString } from './profileScope';

const KEY = 'vesper-library';

const EMPTY = { favorites: {}, watchLater: [], dismissed: {} };

function read() {
    try {
        const raw = readScopedString(KEY);
        if (!raw) return { ...EMPTY };
        const parsed = JSON.parse(raw);
        return {
            favorites: parsed.favorites || {},
            watchLater: Array.isArray(parsed.watchLater) ? parsed.watchLater : [],
            dismissed: parsed.dismissed || {},
        };
    } catch {
        return { ...EMPTY };
    }
}

function write(state) {
    writeScopedString(KEY, JSON.stringify(state));
    // Broadcast so any mounted view (Library page, toast, sidebar
    // badge) re-reads.  Same pattern as the Continue-Watching store.
    try {
        window.dispatchEvent(new Event('vesper:library-change'));
    } catch {
        /* noop */
    }
}

export function getLibrary() {
    return read();
}

export function isInLibrary(id) {
    if (!id) return false;
    const s = read();
    return !!s.favorites[id];
}

export function addToLibrary(id, { type, meta } = {}) {
    if (!id) return;
    const s = read();
    if (s.favorites[id]) return; // already there
    s.favorites[id] = {
        type: type || 'movie',
        addedAt: new Date().toISOString(),
        lastSeenAt: new Date().toISOString(),
        meta: meta || {},
    };
    write(s);
}

export function removeFromLibrary(id) {
    if (!id) return;
    const s = read();
    if (!s.favorites[id]) return;
    delete s.favorites[id];
    write(s);
}

/** Mark a favourite as "you've seen the latest episode notification". */
export function markLibrarySeen(id) {
    if (!id) return;
    const s = read();
    if (!s.favorites[id]) return;
    s.favorites[id].lastSeenAt = new Date().toISOString();
    write(s);
}

export function listFavourites() {
    const s = read();
    return Object.entries(s.favorites)
        .map(([id, v]) => ({ id, ...v }))
        .sort((a, b) => (b.addedAt || '').localeCompare(a.addedAt || ''));
}

export function listFavouritesByType(type) {
    return listFavourites().filter((f) => f.type === type);
}

/* --------------------- Watch Later --------------------- */

export function listWatchLater() {
    const s = read();
    return [...s.watchLater].sort((a, b) =>
        (b.addedAt || '').localeCompare(a.addedAt || '')
    );
}

export function addToWatchLater({ id, episode, showMeta }) {
    if (!id || !episode) return;
    const s = read();
    const key = `${id}:S${episode.season}E${episode.number}`;
    // Avoid duplicates.
    if (s.watchLater.some((w) => `${w.id}:S${w.episode.season}E${w.episode.number}` === key)) {
        return;
    }
    s.watchLater.push({
        id,
        type: 'series',
        episode,
        addedAt: new Date().toISOString(),
        showMeta: showMeta || {},
    });
    write(s);
}

export function removeFromWatchLater({ id, season, number }) {
    const s = read();
    s.watchLater = s.watchLater.filter(
        (w) =>
            !(
                w.id === id &&
                w.episode.season === season &&
                w.episode.number === number
            )
    );
    write(s);
}

/* --------------------- Notifications --------------------- */

export function isEpisodeDismissed(id, season, number) {
    const s = read();
    return !!s.dismissed[`${id}:S${season}E${number}`];
}

export function dismissEpisode(id, season, number) {
    const s = read();
    s.dismissed[`${id}:S${season}E${number}`] = new Date().toISOString();
    write(s);
}
