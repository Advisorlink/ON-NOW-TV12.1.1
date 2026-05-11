/**
 * Tiny in-memory + sessionStorage cache used by the data hooks
 * (useAddons / useLiveShelves / useLiveHeroes) to make navigation
 * between Home, Detail, Search, etc. feel instant.
 *
 * Strategy: stale-while-revalidate.
 *   - Hooks first read the cached value (sync) and render it.
 *   - If the value is older than TTL_REFRESH ms, they trigger a
 *     background refetch.  The new value updates the cache + state
 *     when it lands, so the UI never blocks.
 *
 * sessionStorage keeps the cache alive across full page reloads
 * within a tab.  We deliberately do NOT use localStorage — that
 * would persist forever and surface stale addons on a fresh boot.
 */

const memory = new Map();
const PREFIX = 'onnowtv:cache:';
// Keys we want to survive a full APK restart (so the TV box can
// still show its last-known catalogues / heroes / shelves even when
// the backend preview environment is paused or unreachable on a
// cold boot).  Anything starting with these prefixes is mirrored
// to localStorage in addition to sessionStorage.
const PERSIST_PREFIXES = ['addons', 'shelves:', 'heroes:', 'networks:'];

function isPersistKey(key) {
    return PERSIST_PREFIXES.some(
        (p) => key === p.replace(/:$/, '') || key.startsWith(p)
    );
}

function nowMs() {
    return Date.now();
}

function readSession(key) {
    if (typeof sessionStorage === 'undefined') return null;
    try {
        const raw = sessionStorage.getItem(PREFIX + key);
        if (raw) return JSON.parse(raw);
    } catch {
        /* ignore */
    }
    // Fallback to localStorage for keys we explicitly persist
    // across full app restarts (e.g. catalogues — so when the
    // preview backend is asleep or unreachable on a cold boot, the
    // app still renders the last-known-good data).
    if (isPersistKey(key) && typeof localStorage !== 'undefined') {
        try {
            const raw = localStorage.getItem(PREFIX + key);
            if (raw) return JSON.parse(raw);
        } catch {
            /* ignore */
        }
    }
    return null;
}

function writeSession(key, entry) {
    try {
        if (typeof sessionStorage !== 'undefined') {
            sessionStorage.setItem(PREFIX + key, JSON.stringify(entry));
        }
    } catch {
        /* quota exceeded or disabled */
    }
    if (isPersistKey(key)) {
        try {
            if (typeof localStorage !== 'undefined') {
                localStorage.setItem(PREFIX + key, JSON.stringify(entry));
            }
        } catch {
            /* ignore */
        }
    }
}

export function get(key) {
    let entry = memory.get(key);
    if (entry) return entry;
    entry = readSession(key);
    if (entry) memory.set(key, entry);
    return entry || null;
}

export function set(key, value) {
    const entry = { value, ts: nowMs() };
    memory.set(key, entry);
    writeSession(key, entry);
}

export function ageMs(entry) {
    if (!entry || typeof entry.ts !== 'number') return Infinity;
    return nowMs() - entry.ts;
}

export function isStale(entry, ttl) {
    return ageMs(entry) > ttl;
}

export function clear(key) {
    memory.delete(key);
    if (typeof sessionStorage === 'undefined') return;
    try {
        sessionStorage.removeItem(PREFIX + key);
    } catch {
        /* ignore */
    }
}
