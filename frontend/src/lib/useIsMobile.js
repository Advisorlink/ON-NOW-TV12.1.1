/**
 * useIsMobile — runtime mobile detection.
 *
 * "Mobile" means: viewport width < MOBILE_BREAKPOINT AND the primary
 * input is touch (i.e. not a TV box with a mouse-like remote).  This
 * combination prevents tablets from accidentally falling into mobile
 * mode AND prevents a small Android-TV WebView from being mistaken
 * for a phone.
 *
 * The hook subscribes to window resizes + orientation changes so
 * rotating the phone re-checks (e.g. landscape may push the viewport
 * above the breakpoint).
 */

import { useEffect, useState } from 'react';

const MOBILE_BREAKPOINT = 900;   // px

function detect() {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
        return false;
    }
    /* Coarse pointer ≈ touch.  matchMedia is supported on Chrome 52
     * (HK1 WebView) — safe to call. */
    const coarse = (typeof window.matchMedia === 'function')
        ? window.matchMedia('(pointer: coarse)').matches
        : false;
    const hasTouch =
        coarse ||
        (typeof navigator !== 'undefined' && navigator.maxTouchPoints > 0) ||
        'ontouchstart' in window;
    const narrow = window.innerWidth < MOBILE_BREAKPOINT;
    /* Explicit URL override so QA / preview testing can force the
     * mobile shell on a desktop:  ?mobile=1
     * The override is persisted into sessionStorage so SPA
     * navigation that strips the query string still keeps the
     * mode active for the rest of the session. */
    try {
        if (window.location.search.indexOf('mobile=1') !== -1) {
            sessionStorage.setItem('vesper-mobile-override', '1');
            return true;
        }
        if (window.location.search.indexOf('mobile=0') !== -1) {
            sessionStorage.setItem('vesper-mobile-override', '0');
            return false;
        }
        const stored = sessionStorage.getItem('vesper-mobile-override');
        if (stored === '1') return true;
        if (stored === '0') return false;
    } catch { /* ignore */ }
    return narrow && hasTouch;
}

export default function useIsMobile() {
    const [isMobile, setIsMobile] = useState(detect);
    useEffect(() => {
        const onChange = () => setIsMobile(detect());
        window.addEventListener('resize', onChange);
        window.addEventListener('orientationchange', onChange);
        return () => {
            window.removeEventListener('resize', onChange);
            window.removeEventListener('orientationchange', onChange);
        };
    }, []);
    return isMobile;
}
