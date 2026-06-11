/**
 * Music library — local persistence for liked artists, albums,
 * tracks, radio stations, and podcasts.
 *
 * Mirrors Vesper's `lib/library.js` pattern (localStorage-backed,
 * versioned schema, idempotent CRUD) but keyed to music-shaped
 * data so the two libraries don't pollute each other.
 *
 * Public API:
 *   getLibrary()                  → { artists, albums, tracks, radio, podcasts }
 *   isLiked(kind, id)             → boolean
 *   toggleLike(kind, item)        → boolean (new state)
 *   saveLike(kind, item)
 *   removeLike(kind, id)
 *   subscribe(fn)                 → unsubscribe
 *
 *  kind ∈ 'artist' | 'album' | 'track' | 'radio' | 'podcast'
 */

const KEY = 'onnowtv-music-library-v1';

const EMPTY = () => ({
    artists: [],
    albums: [],
    tracks: [],
    radio: [],
    podcasts: [],
});

function read() {
    if (typeof window === 'undefined') return EMPTY();
    try {
        const raw = localStorage.getItem(KEY);
        if (!raw) return EMPTY();
        const parsed = JSON.parse(raw);
        return { ...EMPTY(), ...parsed };
    } catch {
        return EMPTY();
    }
}

function write(state) {
    if (typeof window === 'undefined') return;
    try {
        localStorage.setItem(KEY, JSON.stringify(state));
    } catch { /* quota / privacy mode */ }
    listeners.forEach((fn) => { try { fn(state); } catch { /* swallow */ } });
}

const listeners = new Set();
export function subscribeMusicLibrary(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}

const COLL = {
    artist:  'artists',
    album:   'albums',
    track:   'tracks',
    radio:   'radio',
    podcast: 'podcasts',
};

export function getMusicLibrary() {
    return read();
}

export function isMusicLiked(kind, id) {
    if (!kind || id == null) return false;
    const col = COLL[kind];
    if (!col) return false;
    const list = read()[col] || [];
    return list.some((it) => String(it.id) === String(id));
}

export function saveMusicLike(kind, item) {
    if (!kind || !item || item.id == null) return;
    const col = COLL[kind];
    if (!col) return;
    const state = read();
    const list = state[col] || [];
    if (list.some((it) => String(it.id) === String(item.id))) return;
    state[col] = [
        { ...item, _likedAt: new Date().toISOString() },
        ...list,
    ];
    write(state);
}

export function removeMusicLike(kind, id) {
    if (!kind || id == null) return;
    const col = COLL[kind];
    if (!col) return;
    const state = read();
    state[col] = (state[col] || []).filter((it) => String(it.id) !== String(id));
    write(state);
}

export function toggleMusicLike(kind, item) {
    if (!kind || !item || item.id == null) return false;
    if (isMusicLiked(kind, item.id)) {
        removeMusicLike(kind, item.id);
        return false;
    }
    saveMusicLike(kind, item);
    return true;
}

/* ─── Playlists (simple per-device collections of tracks) ─── */
const PL_KEY = 'onnowtv-music-playlists-v1';

function readPlaylists() {
    if (typeof window === 'undefined') return [];
    try {
        const raw = localStorage.getItem(PL_KEY);
        return raw ? (JSON.parse(raw) || []) : [];
    } catch { return []; }
}
function writePlaylists(list) {
    try { localStorage.setItem(PL_KEY, JSON.stringify(list)); } catch { /* swallow */ }
    listeners.forEach((fn) => { try { fn(); } catch { /* swallow */ } });
}

export function getPlaylists() {
    return readPlaylists();
}

export function createPlaylist(name) {
    const list = readPlaylists();
    const id = 'pl-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 6);
    list.unshift({
        id,
        name: (name || 'New playlist').trim() || 'New playlist',
        created_at: new Date().toISOString(),
        tracks: [],
    });
    writePlaylists(list);
    return id;
}

export function addTrackToPlaylist(playlistId, track) {
    if (!playlistId || !track || track.id == null) return;
    const list = readPlaylists();
    const pl = list.find((p) => p.id === playlistId);
    if (!pl) return;
    if (pl.tracks.some((t) => String(t.id) === String(track.id))) return; // dedupe
    pl.tracks = [{ ...track }, ...pl.tracks];
    writePlaylists(list);
}

export function removeTrackFromPlaylist(playlistId, trackId) {
    const list = readPlaylists();
    const pl = list.find((p) => p.id === playlistId);
    if (!pl) return;
    pl.tracks = pl.tracks.filter((t) => String(t.id) !== String(trackId));
    writePlaylists(list);
}

export function deletePlaylist(playlistId) {
    const list = readPlaylists().filter((p) => p.id !== playlistId);
    writePlaylists(list);
}
