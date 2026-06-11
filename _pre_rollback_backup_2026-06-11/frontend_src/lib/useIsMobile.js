/**
 * useIsMobile — runtime mobile detection.
 *
 * v2.7.89 — Detection rewritten to be USER-AGENT FIRST.  Earlier
 * versions used `viewport width < 900 && (pointer:coarse OR
 * maxTouchPoints>0)`.  In practice that returned the wrong answer
 * on the user's Samsung Galaxy phone running inside the Vesper
 * Android WebView — most likely because some downstream code or a
 * stale sessionStorage flag was getting in the way.  When isMobile
 * was wrong, the Home page kept its TV scroll-snap layout (one
 * shelf per viewport, snap-stop:always), which trapped vertical
 * swipes on touch devices — exactly the behaviour the user
 * reported ("horizontal works, vertical doesn't").
 *
 * The new logic, in priority order:
 *   1. Explicit URL / sessionStorage override (`?mobile=1` / `?mobile=0`)
 *   2. User-agent contains "Mobile" → mobile (phones always
 *      include "Mobile" in the UA; Android TV / WebView on TV
 *      boxes never do)
 *   3. Old combo: viewport < 900 px AND touch primary
 *   4. Default: not mobile (TV path)
 *
 * The hook subscribes to window resizes + orientation changes so
 * rotating the phone re-checks (e.g. landscape may push viewport
 * above breakpoint — but UA path catches it before width matters).
 */

import { useEffect, useState } from 'react';

const MOBILE_BREAKPOINT = 900;   // px

function detect() {
    if (typeof window === 'undefined' || typeof document === 'undefined') {
        return false;
    }

    /* 1. Explicit override.  URL param wins, then sessionStorage. */
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

    /* 2. User-agent check.  Phone UAs (Android, iPhone, etc.)
       ALWAYS contain "Mobile".  Android TV / Smart TV UAs never
       do.  This is the cleanest signal we have on a WebView. */
    try {
        const ua = navigator.userAgent || '';
        if (/Mobile|iPhone|iPad/.test(ua) && !/TV|SMART-TV|GoogleTV|AppleTV|HbbTV|NetCast|BRAVIA|Crkey/i.test(ua)) {
            return true;
        }
    } catch { /* ignore */ }

    /* 3. Old combo as a last resort. */
    try {
        const coarse = (typeof window.matchMedia === 'function')
            ? window.matchMedia('(pointer: coarse)').matches
            : false;
        const hasTouch =
            coarse ||
            (typeof navigator !== 'undefined' && navigator.maxTouchPoints > 0) ||
            'ontouchstart' in window;
        const narrow = window.innerWidth < MOBILE_BREAKPOINT;
        if (narrow && hasTouch) return true;
    } catch { /* ignore */ }

    /* 4. Default: TV / desktop. */
    return false;
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
