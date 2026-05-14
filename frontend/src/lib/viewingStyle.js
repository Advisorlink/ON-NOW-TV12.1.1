/**
 * Viewing-style preferences — the per-profile record of which
 * TMDB genres the user picked during onboarding plus any
 * individual titles they manually flagged from the genre picker.
 *
 * Stored as a scoped JSON blob:
 *   {
 *     movieGenres: number[],  // TMDB genre ids
 *     tvGenres:    number[],
 *     items:       Array<{
 *         tmdb_id: number,
 *         type:    'movie' | 'series',
 *         title:   string,
 *         poster:  string,
 *         year:    string,
 *     }>,
 *   }
 *
 * The Home "For You" rail uses these genres to ask the backend
 * `/api/tmdb/for-you` for newest popular titles matching them.
 */

import { readScopedString, writeScopedString } from './profileScope';

const KEY = 'onnowtv-viewing-style-v1';

const EMPTY = { movieGenres: [], tvGenres: [], items: [] };

export function getViewingStyle() {
    try {
        const raw = readScopedString(KEY);
        if (!raw) return { ...EMPTY };
        const p = JSON.parse(raw);
        return {
            movieGenres: Array.isArray(p.movieGenres) ? p.movieGenres : [],
            tvGenres: Array.isArray(p.tvGenres) ? p.tvGenres : [],
            items: Array.isArray(p.items) ? p.items : [],
        };
    } catch {
        return { ...EMPTY };
    }
}

export function saveViewingStyle(next) {
    const clean = {
        movieGenres: Array.isArray(next.movieGenres) ? next.movieGenres : [],
        tvGenres: Array.isArray(next.tvGenres) ? next.tvGenres : [],
        items: Array.isArray(next.items) ? next.items : [],
    };
    writeScopedString(KEY, JSON.stringify(clean));
    try {
        window.dispatchEvent(new Event('vesper:viewing-style-change'));
    } catch {
        /* noop */
    }
    return clean;
}

/**
 * Direct write for the brand-new profile being created in the
 * wizard.  The wizard cannot rely on `saveViewingStyle` because
 * `writeScopedString` uses the CURRENTLY-active profile, which is
 * not yet the new profile.  This helper writes to the new
 * profile's scoped key explicitly.
 */
export function writeViewingStyleForProfile(profileId, next) {
    const clean = {
        movieGenres: Array.isArray(next.movieGenres) ? next.movieGenres : [],
        tvGenres: Array.isArray(next.tvGenres) ? next.tvGenres : [],
        items: Array.isArray(next.items) ? next.items : [],
    };
    try {
        localStorage.setItem(
            `${KEY}:${profileId}`,
            JSON.stringify(clean)
        );
    } catch { /* ignore */ }
}
