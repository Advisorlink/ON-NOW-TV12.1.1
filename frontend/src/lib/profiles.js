/**
 * Profile system + Kids mode.
 *
 * Stored in localStorage so it works on a sideloaded APK without
 * a backend round-trip.  One "Kids" profile is permanent and
 * cannot be deleted.  Active profile id determines which
 * experience Home renders (regular vs. KidsHome).
 *
 * Data shapes:
 *   Profile = {
 *       id: string,                // 'kids' is special / immutable
 *       name: string,
 *       avatarId: string,          // from AVATARS in /lib/avatars.js
 *       kids: boolean,             // true → kids-only filter applied
 *       createdAt: number,
 *   }
 *
 *   KidsConfig = {
 *       maxRatingMovie: 'G' | 'PG' | 'PG-13',
 *       maxRatingSeries: 'TV-Y' | 'TV-Y7' | 'TV-G' | 'TV-PG' | 'TV-14',
 *       contentTypes: 'both' | 'movies' | 'series',
 *       pin: string,               // 4-digit PIN; '' = none set yet
 *   }
 */

const KEY_PROFILES = 'onnowtv-profiles-v1';
const KEY_ACTIVE = 'onnowtv-active-profile-v1';
const KEY_KIDS_CONFIG = 'onnowtv-kids-config-v1';

const DEFAULT_KIDS_CONFIG = {
    maxRatingMovie: 'PG',
    maxRatingSeries: 'TV-PG',
    contentTypes: 'both',
    pin: '',
};

const KIDS_PROFILE = {
    id: 'kids',
    name: 'Kids',
    avatarId: 'kids-default',
    kids: true,
    createdAt: 0,
};

function uuid() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    return 'p-' + Math.random().toString(36).slice(2, 11);
}

function readJSON(key, fallback) {
    try {
        const raw = localStorage.getItem(key);
        if (!raw) return fallback;
        return JSON.parse(raw);
    } catch {
        return fallback;
    }
}

function writeJSON(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
    } catch {
        /* ignore */
    }
}

// ---------- Profiles ----------

export function listProfiles() {
    const all = readJSON(KEY_PROFILES, []);
    // Inject the immutable Kids profile at the end.
    const hasKids = all.some((p) => p.id === 'kids');
    return hasKids ? all : [...all, KIDS_PROFILE];
}

export function saveProfile(partial) {
    const list = readJSON(KEY_PROFILES, []).filter((p) => p.id !== 'kids');
    const id = partial.id || uuid();
    const next = {
        id,
        name: partial.name || 'Profile',
        avatarId: partial.avatarId || 'a1',
        kids: false,
        createdAt: partial.createdAt || Date.now(),
    };
    const i = list.findIndex((p) => p.id === id);
    if (i >= 0) list[i] = next;
    else list.unshift(next);
    writeJSON(KEY_PROFILES, list);
    return next;
}

export function removeProfile(id) {
    if (id === 'kids') return; // immutable
    const list = readJSON(KEY_PROFILES, []).filter((p) => p.id !== id);
    writeJSON(KEY_PROFILES, list);
    if (getActiveProfileId() === id) clearActiveProfile();
}

export function getActiveProfile() {
    const id = getActiveProfileId();
    if (!id) return null;
    return listProfiles().find((p) => p.id === id) || null;
}

export function getActiveProfileId() {
    try {
        return localStorage.getItem(KEY_ACTIVE);
    } catch {
        return null;
    }
}

export function setActiveProfile(id) {
    try {
        localStorage.setItem(KEY_ACTIVE, id);
    } catch {
        /* ignore */
    }
    // Notify same-window listeners.
    window.dispatchEvent(new CustomEvent('vesper:profile-change'));
}

export function clearActiveProfile() {
    try {
        localStorage.removeItem(KEY_ACTIVE);
    } catch {
        /* ignore */
    }
    window.dispatchEvent(new CustomEvent('vesper:profile-change'));
}

export function isKidsActive() {
    const p = getActiveProfile();
    return p?.id === 'kids';
}

// ---------- Kids config ----------

export function getKidsConfig() {
    return { ...DEFAULT_KIDS_CONFIG, ...readJSON(KEY_KIDS_CONFIG, {}) };
}

export function saveKidsConfig(partial) {
    const next = { ...getKidsConfig(), ...partial };
    writeJSON(KEY_KIDS_CONFIG, next);
    window.dispatchEvent(new CustomEvent('vesper:kids-config-change'));
    return next;
}

// ---------- Kid-safe content filter ----------

const MOVIE_RATING_ORDER = ['G', 'PG', 'PG-13', 'PG13', 'R', 'NC-17', 'TV-MA'];
const TV_RATING_ORDER = [
    'TV-Y',
    'TV-Y7',
    'TV-Y7-FV',
    'TV-G',
    'TV-PG',
    'TV-14',
    'TV-MA',
];

function rank(arr, val) {
    if (!val) return -1;
    const v = String(val).toUpperCase().replace(/\s+/g, '');
    for (let i = 0; i < arr.length; i++) {
        if (v === arr[i].toUpperCase().replace(/\s+/g, '')) return i;
    }
    return -1;
}

/**
 * True if the given meta passes the kids filter.
 *  - Adult flag → always blocked
 *  - Rating present → must rank <= configured ceiling
 *  - Rating absent → allowed (we'd rather be inclusive than empty)
 */
export function isKidsSafe(meta, cfg) {
    if (!meta) return false;
    const config = cfg || getKidsConfig();
    if (meta.adult === true) return false;
    if (meta.imdbRating === 'X' || meta.imdbRating === 'NC-17') return false;
    const isSeries = (meta.type || '').startsWith('series') || meta.type === 'tv';
    if (config.contentTypes === 'movies' && isSeries) return false;
    if (config.contentTypes === 'series' && !isSeries) return false;
    const rating =
        meta.certification ||
        meta.certificate ||
        meta.contentRating ||
        meta.maturity ||
        '';
    if (isSeries) {
        const r = rank(TV_RATING_ORDER, rating);
        const ceiling = rank(TV_RATING_ORDER, config.maxRatingSeries);
        if (r === -1) return true;
        return r <= ceiling;
    }
    const r = rank(MOVIE_RATING_ORDER, rating);
    const ceiling = rank(MOVIE_RATING_ORDER, config.maxRatingMovie);
    if (r === -1) return true;
    return r <= ceiling;
}
