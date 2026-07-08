/**
 * Tiny localStorage-backed preferences store.  Centralised so the
 * Settings page, Detail page, and the autoplay logic all read/write
 * through the same keys without sprinkling raw localStorage.* calls.
 *
 * Each preference is scoped to the active profile so that flipping
 * autoplay ON in Mum's profile doesn't change Dad's setting.
 *
 * NOTE: Autoplay 1080p defaults to TRUE on first run — the user
 * doesn't have to find Settings to enable it.
 */

import { readScopedString, writeScopedString } from '@/lib/profileScope';

const KEY_AUTOPLAY_1080P = 'onnowtv-autoplay-1080p';
const KEY_AUTOPLAY_TV    = 'onnowtv-autoplay-tv';

export function getAutoplay1080p() {
    try {
        const v = readScopedString(KEY_AUTOPLAY_1080P);
        if (v === null || v === undefined) return true; // default ON
        return v === '1';
    } catch {
        return true;
    }
}

export function setAutoplay1080p(enabled) {
    try {
        writeScopedString(KEY_AUTOPLAY_1080P, enabled ? '1' : '0');
    } catch {
        /* ignore */
    }
}

/**
 * TV-shows-ONLY autoplay toggle (USER SPEC): turning autoplay off on
 * a TV show page must NOT kill movie autoplay.  Movies follow the
 * master rail switch alone; episodes require master AND this flag.
 */
export function getAutoplayTV() {
    try {
        const v = readScopedString(KEY_AUTOPLAY_TV);
        if (v === null || v === undefined) return true; // default ON
        return v === '1';
    } catch {
        return true;
    }
}

export function setAutoplayTV(enabled) {
    try {
        writeScopedString(KEY_AUTOPLAY_TV, enabled ? '1' : '0');
    } catch {
        /* ignore */
    }
}
