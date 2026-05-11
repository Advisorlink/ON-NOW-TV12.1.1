/**
 * Tiny localStorage-backed preferences store.  Centralised so the
 * Settings page, Detail page, and the autoplay logic all read/write
 * through the same keys without sprinkling raw localStorage.* calls.
 */

const KEY_AUTOPLAY_1080P = 'onnowtv-autoplay-1080p';

export function getAutoplay1080p() {
    try {
        return localStorage.getItem(KEY_AUTOPLAY_1080P) === '1';
    } catch {
        return false;
    }
}

export function setAutoplay1080p(enabled) {
    try {
        localStorage.setItem(KEY_AUTOPLAY_1080P, enabled ? '1' : '0');
    } catch {
        /* ignore */
    }
}
