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
 * Read a scoped value falling back to the legacy unscoped key.
 * Used for one-shot migration: existing installs with data at the
 * unscoped key get to keep their data on the FIRST profile they
 * use; subsequent profiles start fresh.
 */
export function readScopedString(baseKey) {
    try {
        const s = localStorage.getItem(scoped(baseKey));
        if (s !== null) return s;
        // Legacy fallback.  Read but DON'T migrate — that way
        // multiple profiles don't all inherit the same legacy data.
        return localStorage.getItem(baseKey);
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
