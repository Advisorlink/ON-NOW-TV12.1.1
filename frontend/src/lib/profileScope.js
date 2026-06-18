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
 *
 * v2.10.57 — Per-account-isolation fix.  profiles.js stores the
 * active-profile-id at the **account-suffixed** key
 *   `onnowtv-active-profile-v1:<username>`
 * so each signed-in Vesper account keeps its own active profile.
 * This file used to read only the legacy un-suffixed key, which
 * silently returned null on any fresh install (post-v2.10.52
 * per-account isolation refactor).  That made every `readScopedString`
 * fall back to the `:global` namespace — meaning ForYouShelf,
 * Continue Watching, Watched flags etc. lost track of the actual
 * profile and rendered empty.  We now mirror profiles.js's suffix
 * logic, with the legacy un-suffixed key kept as a fallback so
 * pre-v2.10.52 installs continue to work.
 */

const BASE_ACTIVE_KEY = 'onnowtv-active-profile-v1';

/** Mirror of profiles.js `_accountSuffix()`.  Kept inline so this
 *  module stays import-free and free of circular dependencies. */
function _accountSuffix() {
    try {
        if (typeof localStorage === 'undefined') return '';
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

export function activeProfileId() {
    try {
        if (typeof localStorage === 'undefined') return 'global';
        // Preferred: the account-suffixed key written by profiles.js
        // (matches what `setActiveProfile()` writes after v2.10.52).
        const scoped = localStorage.getItem(BASE_ACTIVE_KEY + _accountSuffix());
        if (scoped) return scoped;
        // Legacy fallback: pre-v2.10.52 installs without an account
        // suffix.  Lets the app continue to work for users still on
        // legacy storage before they first sign in.
        const legacy = localStorage.getItem(BASE_ACTIVE_KEY);
        if (legacy) return legacy;
        return 'global';
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
