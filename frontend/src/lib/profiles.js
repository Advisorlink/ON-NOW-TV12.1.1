/**
 * Profile system + Kids mode.
 *
 * v2.10.52 — Profile storage is now **namespaced per Vesper account**.
 * When user A signs in they see their own profiles; when user B
 * signs in on the same device they see THEIR OWN (or 0 if new).
 * Profiles never leak across accounts.  Implementation: every
 * top-level localStorage key gets a `:<username>` suffix derived
 * from the active Vesper auth account (stored in
 * `vesper-auth-account-v1`).  If no account is signed in we fall
 * back to the un-suffixed legacy keys so the app remains usable
 * during pre-auth bootstrap.
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

const BASE_PROFILES    = 'onnowtv-profiles-v1';
const BASE_ACTIVE      = 'onnowtv-active-profile-v1';
const BASE_KIDS_CONFIG = 'onnowtv-kids-config-v1';

/** Derive a per-account suffix from the cached Vesper auth account
 *  (written by /lib/auth.js).  Returns ':USERNAME' when signed in,
 *  empty string otherwise.  We strip non-alphanumerics to keep
 *  localStorage keys friendly even for usernames with spaces or
 *  symbols (e.g. "ANNIE M"). */
function _accountSuffix() {
    try {
        const raw = localStorage.getItem('vesper-auth-account-v1');
        if (!raw) return '';
        const acc = JSON.parse(raw);
        const u = (acc && acc.username) || '';
        if (!u) return '';
        return ':' + String(u).replace(/[^A-Za-z0-9_-]+/g, '_');
    } catch {
        return '';
    }
}
const keyProfiles    = () => BASE_PROFILES    + _accountSuffix();
const keyActive      = () => BASE_ACTIVE      + _accountSuffix();
const keyKidsConfig  = () => BASE_KIDS_CONFIG + _accountSuffix();

/* v2.10.86 — Read-side fallback to BOTH suffixed (`:USERNAME`) and
 * unsuffixed legacy keys.  Fixes a real-user race: on a fresh page
 * load `vesper-auth-account-v1` is restored asynchronously by the
 * auth bootstrap, so the very FIRST render of <RequireProfile> can
 * see `_accountSuffix() === ''` and resolve `keyActive()` to the
 * UNSUFFIXED key — missing the suffixed value that was actually
 * written.  Result: user gets bounced to /profiles on every hard
 * refresh.  Solution: on reads, try the suffixed key first and fall
 * back to the unsuffixed legacy key if missing.  Writes still use
 * the suffixed key only (no behaviour change there). */
function _readBothScopes(baseKey) {
    try {
        const suffix = _accountSuffix();
        if (suffix) {
            const v = localStorage.getItem(baseKey + suffix);
            if (v !== null) return v;
        }
        // Fallback to unsuffixed (legacy or pre-auth-hydration boot).
        return localStorage.getItem(baseKey);
    } catch {
        return null;
    }
}

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
    /* v2.10.96 — Read both suffixed and unsuffixed scopes so a hard
     * refresh that hits before `vesper-auth-account-v1` is restored
     * still finds the user's saved profiles instead of bouncing them
     * back to /profiles. */
    let raw;
    try {
        const txt = _readBothScopes(BASE_PROFILES);
        raw = txt ? JSON.parse(txt) : [];
    } catch {
        raw = [];
    }
    /* Defensive: if a previous version stored profiles as an
     * object / null / something else, fall back to an empty
     * array rather than crashing on `.some()`.  We never want
     * a bad localStorage blob to brick the entire app. */
    const all = Array.isArray(raw) ? raw : [];
    // v2.10.68 — Kids profile is auto-appended ONLY in the Kids
    // app context (standalone Kids APK or `?profile=kids`
    // deep-link).  Vesper / Tunes / FTA / browser must never see
    // the Kids tile in their profile picker — the user requested
    // hard separation.  We also strip any Kids profile that
    // managed to get persisted in earlier builds so a stale
    // localStorage value can never leak it into the Vesper UI.
    const isKidsCtx = (
        typeof window !== 'undefined' &&
        window.__vesperBootProfileKids === true
    );
    const filtered = isKidsCtx ? all : all.filter((p) => p && p.id !== 'kids');
    if (!isKidsCtx) return filtered;
    const hasKids = filtered.some((p) => p && p.id === 'kids');
    return hasKids ? filtered : [...filtered, KIDS_PROFILE];
}

export function saveProfile(partial) {
    const raw = readJSON(keyProfiles(), []);
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
    writeJSON(keyProfiles(), list);
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
    const raw = readJSON(keyProfiles(), []);
    const list = (Array.isArray(raw) ? raw : []).filter((p) => p.id !== id);
    writeJSON(keyProfiles(), list);
    if (getActiveProfileId() === id) clearActiveProfile();
}

export function getActiveProfile() {
    const id = getActiveProfileId();
    if (!id) return null;
    return listProfiles().find((p) => p.id === id) || null;
}

export function getActiveProfileId() {
    /* v2.10.96 — Cold-load hydration race: `vesper-auth-account-v1`
     * is restored asynchronously by /lib/auth.js so the first render
     * may see `_accountSuffix() === ''` and miss the suffixed value
     * that's actually persisted.  Read both scopes (suffixed →
     * unsuffixed fallback) so /RequireProfile resolves correctly on
     * the first render. */
    return _readBothScopes(BASE_ACTIVE);
}

export function setActiveProfile(id) {
    try {
        localStorage.setItem(keyActive(), id);
    } catch {
        /* ignore */
    }
    // Notify same-window listeners.
    window.dispatchEvent(new CustomEvent('vesper:profile-change'));
}

export function clearActiveProfile() {
    try {
        localStorage.removeItem(keyActive());
    } catch {
        /* ignore */
    }
    window.dispatchEvent(new CustomEvent('vesper:profile-change'));
}

export function isKidsActive() {
    // v2.10.69 — Two paths to "kids is active":
    //   • Standalone Kids APK (`window.__vesperBootProfileKids`
    //     set by App.js's module-load IIFE before React mounts).
    //     The Kids APK has NO profile system, NO Vesper login —
    //     the boot flag is the source of truth.
    //   • Legacy in-Vesper "Kids profile" (still used by the old
    //     Vesper APK builds that share a device with a child).
    //     Reads the active profile id.
    if (isKidsApp()) return true;
    const p = getActiveProfile();
    return p?.id === 'kids';
}

/**
 * v2.10.69 — True when the page was booted as the standalone Kids
 * APK (or a `?profile=kids` deep-link in a browser).  The boot
 * flag is set by `App.js`'s module-load IIFE BEFORE React mounts
 * and BEFORE `DeepLinkHandler` strips `?profile=kids` from the URL,
 * so it stays accurate for the lifetime of the page no matter how
 * many times components re-render.
 *
 * Use this anywhere you need "is the user inside the Kids app"
 * without dragging the profile system into it.
 */
export function isKidsApp() {
    if (typeof window === 'undefined') return false;
    return window.__vesperBootProfileKids === true;
}

/**
 * v2.12.9 — True when running inside the ON NOW V2 Music context:
 * either the standalone Tunes APK (boots the SPA at `#/music`) or a
 * browser sitting on a `/music` route.  Used for music-specific
 * login branding, post-login routing (never bounce to Vesper's
 * /profiles picker) and the "ON NOW V2 🎶" boot splash.
 */
export function isMusicApp() {
    if (typeof window === 'undefined') return false;
    try {
        if (window.location.hash.startsWith('#/music')) return true;
        if (window.location.pathname.startsWith('/music')) return true;
    } catch { /* ignore */ }
    return false;
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
    /* v2.10.96 — Read both suffixed + unsuffixed scopes so the kids
     * gate doesn't briefly fall back to defaults on first paint after
     * a hard refresh (auth bootstrap is async). */
    let stored = {};
    try {
        const txt = _readBothScopes(BASE_KIDS_CONFIG);
        stored = txt ? JSON.parse(txt) : {};
    } catch {
        stored = {};
    }
    return { ...DEFAULT_KIDS_CONFIG, ...(stored || {}) };
}

export function saveKidsConfig(partial) {
    const next = { ...getKidsConfig(), ...partial };
    writeJSON(keyKidsConfig(), next);
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
