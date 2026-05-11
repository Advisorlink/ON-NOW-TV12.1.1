/**
 * Tiny localStorage-backed preferences store.  Centralised so the
 * Settings page, Detail page, and the autoplay logic all read/write
 * through the same keys without sprinkling raw localStorage.* calls.
 *
 * NOTE: Autoplay 1080p defaults to TRUE on first run — the user
 * doesn't have to find Settings to enable it.  Setting `'0'` in
 * localStorage explicitly turns it off; `null` (never touched) =
 * enabled.
 */

const KEY_AUTOPLAY_1080P = 'onnowtv-autoplay-1080p';

export function getAutoplay1080p() {
    try {
        const v = localStorage.getItem(KEY_AUTOPLAY_1080P);
        if (v === null) return true; // default ON
        return v === '1';
    } catch {
        return true;
    }
}

export function setAutoplay1080p(enabled) {
    try {
        localStorage.setItem(KEY_AUTOPLAY_1080P, enabled ? '1' : '0');
    } catch {
        /* ignore */
    }
}
