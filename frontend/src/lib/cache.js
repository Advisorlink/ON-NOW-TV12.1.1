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

function nowMs() {
    return Date.now();
}

function readSession(key) {
    if (typeof sessionStorage === 'undefined') return null;
    try {
        const raw = sessionStorage.getItem(PREFIX + key);
        if (!raw) return null;
        return JSON.parse(raw);
    } catch {
        return null;
    }
}

function writeSession(key, entry) {
    if (typeof sessionStorage === 'undefined') return;
    try {
        sessionStorage.setItem(PREFIX + key, JSON.stringify(entry));
    } catch {
        /* quota exceeded or disabled */
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
