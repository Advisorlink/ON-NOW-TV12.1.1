/**
 * Per-profile localStorage scoping.
 *
 * Adds a "<active-profile-id>" suffix to a storage key so each
 * profile keeps its own Continue Watching, Theme choice, Watched
 * flags etc. without cross-contamination.
 *
 * Kept import-free (no profiles.js dependency) so it can be used
 * from any other lib without circular imports.  The active profile
 * id lives at `onnowtv-active-profile-v1` — matches the constant
 * in profiles.js.  If no profile is active we fall back to a
 * deterministic "global" namespace so the app still functions
 * before the user picks a profile (e.g. during a brand-new install).
 */

const ACTIVE_KEY = 'onnowtv-active-profile-v1';
const AUTH_ACCOUNT_KEY = 'vesper-auth-account-v1';

/* v2.13.4 — profiles.js suffixes the active-profile key with the
 * signed-in account (`onnowtv-active-profile-v1:USERNAME`).  This
 * module used to read ONLY the unsuffixed key, so whenever a user
 * was signed in every scoped read resolved to ':global' — per-profile
 * themes, viewing-style picks and continue-watching were silently
 * shared/lost.  Mirror the same suffix logic here (kept inline so
 * the module stays import-free). */
export function accountSuffix() {
    try {
        const raw = localStorage.getItem(AUTH_ACCOUNT_KEY);
        if (!raw) return '';
        const u = (JSON.parse(raw) || {}).username || '';
        return u ? ':' + String(u).replace(/[^A-Za-z0-9_-]+/g, '_') : '';
    } catch {
        return '';
    }
}

export function activeProfileId() {
    try {
        if (typeof localStorage === 'undefined') return 'global';
        const suffix = accountSuffix();
        if (suffix) {
            const v = localStorage.getItem(ACTIVE_KEY + suffix);
            if (v) return v;
        }
        return localStorage.getItem(ACTIVE_KEY) || 'global';
    } catch {
        return 'global';
    }
}

/** Append `:<profileId>` to a base key. */
export function scoped(baseKey) {
    return `${baseKey}:${activeProfileId()}`;
}

/**
 * Read a scoped value.  No fallback to the legacy unscoped key —
 * each profile owns its own namespace and a fresh profile starts
 * completely empty.  The unscoped fallback that used to live here
 * caused new profiles to inherit the previous profile's library,
 * watch-later list, continue-watching state, etc.
 *
 * If an install still has data at the legacy unscoped key, we
 * promote it ONCE to the currently active profile's scoped key
 * (so the very first profile a user creates inherits their
 * pre-profile data) and then remove the legacy key.  Subsequent
 * profiles see an empty scope.
 */
export function readScopedString(baseKey) {
    try {
        const scopedKey = scoped(baseKey);
        const s = localStorage.getItem(scopedKey);
        if (s !== null) return s;
        // One-shot legacy promotion: only fires if the legacy
        // unscoped key still exists.  Move it into the current
        // scope, then nuke the legacy key so no other profile
        // ever inherits it again.
        const legacy = localStorage.getItem(baseKey);
        if (legacy !== null) {
            try {
                localStorage.setItem(scopedKey, legacy);
                localStorage.removeItem(baseKey);
            } catch { /* ignore */ }
            return legacy;
        }
        // v2.13.4 promotion: before the account-suffix fix above, all
        // scoped data landed under ':global' even with a profile
        // active.  Claim it once for the first real profile that
        // reads the key so nobody loses continue-watching/library.
        const globalKey = `${baseKey}:global`;
        if (scopedKey !== globalKey) {
            const g = localStorage.getItem(globalKey);
            if (g !== null) {
                try {
                    localStorage.setItem(scopedKey, g);
                    localStorage.removeItem(globalKey);
                } catch { /* ignore */ }
                return g;
            }
        }
        return null;
    } catch {
        return null;
    }
}

export function writeScopedString(baseKey, value) {
    try {
        if (value === null || value === undefined) {
            localStorage.removeItem(scoped(baseKey));
        } else {
            localStorage.setItem(scoped(baseKey), value);
        }
    } catch {
        /* quota / disabled */
    }
}

export function removeScoped(baseKey) {
    try {
        localStorage.removeItem(scoped(baseKey));
    } catch {
        /* noop */
    }
}
