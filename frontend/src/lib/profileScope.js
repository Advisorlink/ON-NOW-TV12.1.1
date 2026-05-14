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

export function activeProfileId() {
    try {
        return (
            (typeof localStorage !== 'undefined' &&
                localStorage.getItem(ACTIVE_KEY)) ||
            'global'
        );
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
