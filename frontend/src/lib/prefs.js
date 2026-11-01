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

/**
 * Auto-play trailers on the Home rails: focusing/hovering a poster
 * expands it into a wide preview and starts the (English) trailer.
 * Defaults ON.
 */
const KEY_AUTO_TRAILER = 'onnowtv-auto-trailer';

export function getAutoTrailer() {
    try {
        const v = readScopedString(KEY_AUTO_TRAILER);
        if (v === null || v === undefined) return true; // default ON
        return v === '1';
    } catch {
        return true;
    }
}

export function setAutoTrailer(enabled) {
    try {
        writeScopedString(KEY_AUTO_TRAILER, enabled ? '1' : '0');
    } catch {
        /* ignore */
    }
}

/**
 * Home row style — how the wide 16:9 "trailer rectangle" appears:
 *  'instant' → the cover you land on is already wide (original)
 *  'pause'   → plain covers; stop on one for getHomePauseMs() and it slides open
 *  'covers'  → never widens, no trailer window
 * Falls back to the old Auto-trailer toggle (OFF → 'covers').
 */
const KEY_HOME_LOOK = 'onnowtv-home-look';
const KEY_HOME_PAUSE = 'onnowtv-home-pause-ms';
export const HOME_LOOKS = ['instant', 'pause', 'covers'];
export const HOME_PAUSE_OPTIONS = [500, 1000, 1500];

export function getHomeLook() {
    try {
        const v = readScopedString(KEY_HOME_LOOK);
        if (HOME_LOOKS.includes(v)) return v;
        return getAutoTrailer() ? 'instant' : 'covers';
    } catch {
        return 'instant';
    }
}

export function setHomeLook(look) {
    try {
        writeScopedString(KEY_HOME_LOOK, HOME_LOOKS.includes(look) ? look : 'instant');
        writeScopedString(KEY_AUTO_TRAILER, look === 'covers' ? '0' : '1');
    } catch {
        /* ignore */
    }
}

export function getHomePauseMs() {
    try {
        const n = Number(readScopedString(KEY_HOME_PAUSE));
        return HOME_PAUSE_OPTIONS.includes(n) ? n : 1000;
    } catch {
        return 1000;
    }
}

export function setHomePauseMs(ms) {
    try {
        writeScopedString(KEY_HOME_PAUSE, String(HOME_PAUSE_OPTIONS.includes(ms) ? ms : 1000));
    } catch {
        /* ignore */
    }
}

/** Dwell before the hover trailer starts: short normally, after the pause in 'pause' mode. */
export function getPreviewDwellMs() {
    return getHomeLook() === 'pause' ? getHomePauseMs() + 150 : 280;
}

/**
 * Navigation layout — 'side' (classic left rail) or 'top' (glass
 * icon bar centred along the top; labels slide out on focus).
 */
const KEY_NAV_LAYOUT = 'onnowtv-nav-layout';

export function getNavLayout() {
    try {
        return readScopedString(KEY_NAV_LAYOUT) === 'side' ? 'side' : 'top';
    } catch {
        return 'top';
    }
}

export function setNavLayout(layout) {
    try {
        writeScopedString(KEY_NAV_LAYOUT, layout === 'top' ? 'top' : 'side');
    } catch {
        /* ignore */
    }
    try { window.dispatchEvent(new Event('vesper:nav-layout-change')); } catch { /* ignore */ }
}

/**
 * Load Home rows as you reach them — only the row in view (and the
 * next one) mounts its posters; the rest wait until you scroll down.
 * Defaults ON.
 */
const KEY_LAZY_ROWS = 'onnowtv-lazy-rows';
export function getLazyRows() {
    try {
        const v = readScopedString(KEY_LAZY_ROWS);
        return v === null || v === undefined ? true : v === '1';
    } catch {
        return true;
    }
}
export function setLazyRows(enabled) {
    try { writeScopedString(KEY_LAZY_ROWS, enabled ? '1' : '0'); } catch { /* ignore */ }
    try { window.dispatchEvent(new Event('vesper:lazy-rows-change')); } catch { /* ignore */ }
}

const KEY_SHOW_COLLECTIONS = 'onnowtv-show-collections';
export function getShowCollections() {
    try {
        const v = readScopedString(KEY_SHOW_COLLECTIONS);
        return v === null || v === undefined ? true : v === '1';
    } catch {
        return true;
    }
}
export function setShowCollections(enabled) {
    try { writeScopedString(KEY_SHOW_COLLECTIONS, enabled ? '1' : '0'); } catch { /* ignore */ }
}
