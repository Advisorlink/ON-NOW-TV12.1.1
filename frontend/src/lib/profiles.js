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
 *       pin: string,               // 4-digit PIN; '' = none (free access)
 *       createdAt: number,
 *   }
 *
 *   KidsConfig = {
 *       maxRatingMovie: 'G' | 'PG' | 'PG-13',
 *       maxRatingSeries: 'TV-Y' | 'TV-Y7' | 'TV-G' | 'TV-PG' | 'TV-14',
 *       contentTypes: 'both' | 'movies' | 'series',
 *       pin: string,               // 4-digit PIN required to EXIT kids
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
    const raw = readJSON(KEY_PROFILES, []);
    /* Defensive: if a previous version stored profiles as an
     * object / null / something else, fall back to an empty
     * array rather than crashing on `.some()`.  We never want
     * a bad localStorage blob to brick the entire app. */
    const all = Array.isArray(raw) ? raw : [];
    const hasKids = all.some((p) => p && p.id === 'kids');
    return hasKids ? all : [...all, KIDS_PROFILE];
}

export function saveProfile(partial) {
    const raw = readJSON(KEY_PROFILES, []);
    const list = (Array.isArray(raw) ? raw : []).filter(
        (p) => p && p.id !== 'kids'
    );
    const id = partial.id || uuid();
    const isNew = !partial.id;
    const next = {
        id,
        name: partial.name || 'Profile',
        avatarId: partial.avatarId || 'fn-popcorn-fg',
        kids: false,
        pin: typeof partial.pin === 'string' ? partial.pin : '',
        createdAt: partial.createdAt || Date.now(),
    };
    const i = list.findIndex((p) => p.id === id);
    if (i >= 0) list[i] = next;
    else list.unshift(next);
    writeJSON(KEY_PROFILES, list);
    if (isNew) seedNewProfileStorage(id);
    return next;
}

/**
 * Initialise a brand-new profile's scoped localStorage namespace
 * with empty/default values so the fresh profile can never
 * accidentally inherit another profile's library, continue-watching
 * list, viewing-style or autoplay setting via the
 * `readScopedString` legacy fallback (which has been removed but
 * we double-belt-and-brace here regardless).
 */
function seedNewProfileStorage(id) {
    const tag = (k) => `${k}:${id}`;
    const seeds = {
        // Library (favourites + watch-later + dismissed)
        'vesper-library': JSON.stringify({
            favorites: {},
            watchLater: [],
            dismissed: {},
        }),
        // Continue watching + watched flags
        'onnowtv-continue-watching-v1': '[]',
        'onnowtv-watched-v1': '[]',
        // Viewing-style preferences (genres + manually-added items)
        'onnowtv-viewing-style-v1': JSON.stringify({
            movieGenres: [],
            tvGenres: [],
            items: [],
        }),
        // Autoplay 1080p toggle — default OFF; the wizard sets '1'
        // if the user opts in.
        'onnowtv-autoplay-1080p': '0',
    };
    try {
        for (const [base, value] of Object.entries(seeds)) {
            const key = tag(base);
            if (localStorage.getItem(key) === null) {
                localStorage.setItem(key, value);
            }
        }
    } catch {
        /* quota / disabled — non-blocking */
    }
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

/** True if this profile requires a PIN before it can be made active. */
export function profileHasPin(p) {
    return !!(p && p.pin && p.pin.length === 4);
}

/** Validate a PIN against the given profile. */
export function checkProfilePin(p, entered) {
    if (!profileHasPin(p)) return true;
    return p.pin === entered;
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

// v2.8.12 — `M` is the Australian classification roughly equivalent to
// US PG-13 (mild themes, suitable for 13+).  Placed BEFORE PG-13 in
// the order so M-rated content passes the PG-13 ceiling, and PG-13
// content passes the M ceiling — they're treated as equivalent.
const MOVIE_RATING_ORDER = ['G', 'PG', 'M', 'PG-13', 'PG13', 'R', 'NC-17', 'TV-MA'];
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
 * Standalone helper: true if a content rating string passes the
 * configured ceiling.  Used by Detail.jsx after a TMDB lookup to
 * gate per-title rendering in Kids mode.
 *
 * Accepts:
 *   cert    — raw rating string (e.g. "PG-13", "TV-MA", "R", or "")
 *   ceiling — configured maximum (e.g. "PG", "TV-PG")
 *
 * If either side is empty / unknown, defaults to ALLOW so we never
 * silently hide content because TMDB lacks a certification.
 */
export function isRatingAllowed(cert, ceiling) {
    if (!cert || !ceiling) return true;
    const c = String(cert).toUpperCase().replace(/\s+/g, '');
    const looksTV = c.startsWith('TV-') || String(ceiling).toUpperCase().startsWith('TV-');
    const arr = looksTV ? TV_RATING_ORDER : MOVIE_RATING_ORDER;
    const r = rank(arr, cert);
    const cap = rank(arr, ceiling);
    if (r === -1) return true; // unknown rating → allow
    if (cap === -1) return true; // unknown ceiling → allow
    return r <= cap;
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
