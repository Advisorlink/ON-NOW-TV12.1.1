/**
 * My Library — per-profile favourites + Watch-Later queue + Actors.
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
 *     watchLater: [ … ],
 *     actors: {
 *       "31": {                   // TMDB person id (string)
 *         name: "Tom Hanks",
 *         profile: "https://image.tmdb.org/…",
 *         addedAt: ISO,
 *       },
 *       ...
 *     },
 *     dismissed: { "tt1234567:S3E5": ISO }
 *   }
 */

import { readScopedString, writeScopedString } from './profileScope';

const KEY = 'vesper-library';

const EMPTY = { favorites: {}, watchLater: [], actors: {}, dismissed: {}, notifyList: {} };

function read() {
    try {
        const raw = readScopedString(KEY);
        if (!raw) return { ...EMPTY };
        const parsed = JSON.parse(raw);
        /* Defend against null / primitives / arrays — JSON.parse
         * is happy to return any of these, but we treat anything
         * that's not a plain object as a corrupted blob. */
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
            return { ...EMPTY };
        }
        return {
            favorites: parsed.favorites && typeof parsed.favorites === 'object'
                ? parsed.favorites : {},
            watchLater: Array.isArray(parsed.watchLater) ? parsed.watchLater : [],
            actors: parsed.actors && typeof parsed.actors === 'object'
                ? parsed.actors : {},
            dismissed: parsed.dismissed && typeof parsed.dismissed === 'object'
                ? parsed.dismissed : {},
            notifyList: parsed.notifyList && typeof parsed.notifyList === 'object'
                ? parsed.notifyList : {},
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

export function addToWatchLater({ id, episode, showMeta, movie }) {
    if (!id) return;
    const s = read();
    if (episode) {
        const key = `${id}:S${episode.season}E${episode.number}`;
        if (
            s.watchLater.some(
                (w) => w.type === 'series' && `${w.id}:S${w.episode.season}E${w.episode.number}` === key
            )
        ) {
            return;
        }
        s.watchLater.push({
            id,
            type: 'series',
            episode,
            addedAt: new Date().toISOString(),
            showMeta: showMeta || {},
        });
    } else if (movie) {
        // Movie Watch Later — no episode info, just the title +
        // landscape backdrop URL for the rail.
        if (
            s.watchLater.some(
                (w) => w.type === 'movie' && w.id === id
            )
        ) {
            return;
        }
        s.watchLater.push({
            id,
            type: 'movie',
            addedAt: new Date().toISOString(),
            movie: {
                name: movie.name,
                poster: movie.poster,
                background: movie.background,
                year: movie.year,
                synopsis: movie.synopsis,
            },
        });
    } else {
        return;
    }
    write(s);
}

export function removeFromWatchLater({ id, season, number }) {
    const s = read();
    s.watchLater = s.watchLater.filter((w) => {
        if (w.type === 'movie') {
            // Movies match purely by id.
            return w.id !== id;
        }
        return !(
            w.id === id &&
            w.episode.season === season &&
            w.episode.number === number
        );
    });
    write(s);
}

export function isMovieInWatchLater(id) {
    if (!id) return false;
    const s = read();
    return s.watchLater.some((w) => w.type === 'movie' && w.id === id);
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

/* --------------------- Actors --------------------- */

export function isActorInLibrary(personId) {
    if (personId == null) return false;
    const s = read();
    return !!s.actors[String(personId)];
}

export function addActorToLibrary({ id, name, profile }) {
    if (id == null || !name) return;
    const s = read();
    const key = String(id);
    if (s.actors[key]) return;
    s.actors[key] = {
        name,
        profile: profile || '',
        addedAt: new Date().toISOString(),
    };
    write(s);
}

export function removeActorFromLibrary(personId) {
    if (personId == null) return;
    const s = read();
    const key = String(personId);
    if (!s.actors[key]) return;
    delete s.actors[key];
    write(s);
}

export function listActors() {
    const s = read();
    return Object.entries(s.actors)
        .map(([id, v]) => ({ id, ...v }))
        .sort((a, b) => (b.addedAt || '').localeCompare(a.addedAt || ''));
}

/* ────────────────────────────────────────────────────────────────
 *  NOTIFY-LIST — "tell me when this movie/show has streams"
 *
 *  User clicks Play on something Cinemeta promotes but no addon
 *  has streams for yet (a new release that exists in TMDB but the
 *  uploaders haven't ripped it / Plex doesn't have it / etc.).
 *  The "Stream Unavailable" modal lets them tap "Notify me" — we
 *  drop the item here.  On every app boot, a background scanner
 *  re-checks Torrentio/Plex for each entry and surfaces a banner
 *  when a stream becomes available.
 * ──────────────────────────────────────────────────────────────── */

export function addToNotifyList(id, { type, meta }) {
    if (!id) return;
    const s = read();
    s.notifyList = s.notifyList || {};
    s.notifyList[id] = {
        type: type || 'movie',
        addedAt: new Date().toISOString(),
        meta: meta || {},
        lastCheckedAt: null,
        notifiedAt: null,
    };
    write(s);
}

export function removeFromNotifyList(id) {
    if (!id) return;
    const s = read();
    if (s.notifyList && s.notifyList[id]) {
        delete s.notifyList[id];
        write(s);
    }
}

export function isInNotifyList(id) {
    if (!id) return false;
    const s = read();
    return !!(s.notifyList && s.notifyList[id]);
}

export function listNotifyList() {
    const s = read();
    return Object.entries(s.notifyList || {})
        .map(([id, v]) => ({ id, ...v }))
        .sort((a, b) => (b.addedAt || '').localeCompare(a.addedAt || ''));
}

export function markNotifyListChecked(id, found) {
    if (!id) return;
    const s = read();
    if (!s.notifyList || !s.notifyList[id]) return;
    s.notifyList[id].lastCheckedAt = new Date().toISOString();
    if (found && !s.notifyList[id].notifiedAt) {
        s.notifyList[id].notifiedAt = new Date().toISOString();
    }
    write(s);
}
