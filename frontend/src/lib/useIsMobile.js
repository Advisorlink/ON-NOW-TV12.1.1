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
 * Runtime priority: explicit URL/session override, then the underlying
 * device UA (ignoring the APK's OnNowTV suffix), then narrow touch input.
 * Android/iPad tablets stay handheld in landscape regardless of width;
 * genuine TV UAs remain on the remote-controlled path.
 *
 * The hook subscribes to window resizes + orientation changes so
 * rotating the phone re-checks (e.g. landscape may push viewport
 * above breakpoint — but UA path catches it before width matters).
 */

import { useEffect, useState } from 'react';
import { isHandheldInput } from '@/lib/deviceInput';

const INPUT_SEEN_KEY = 'vesper-input-seen';

function readInputSeen() {
    try { return localStorage.getItem(INPUT_SEEN_KEY); } catch { return null; }
}

/** Remember the first real input (finger vs remote) so older APKs that
 *  cannot tell a TV box from a tablet still land on the right layout. */
let probeInstalled = false;
function installInputProbe() {
    if (probeInstalled || typeof window === 'undefined') return;
    probeInstalled = true;
    const mark = (kind) => {
        const seen = readInputSeen();
        if (seen === kind || (seen === 'touch' && kind === 'key')) return;
        try { localStorage.setItem(INPUT_SEEN_KEY, kind); } catch { /* ignore */ }
        window.dispatchEvent(new Event('resize'));
    };
    window.addEventListener('touchstart', () => mark('touch'), { capture: true, passive: true });
    window.addEventListener('keydown', (e) => {
        if (/^(Arrow(Up|Down|Left|Right)|Enter|GoBack|BrowserBack|MediaPlayPause|ChannelUp|ChannelDown)$/.test(e.key)) mark('key');
    }, true);
}

function nativeBridge() {
    const a = typeof window !== 'undefined' ? window.OnNowTV : null;
    const inApk = !!a && typeof a.isAndroidHost === 'function';
    let nativeTv = null;
    if (inApk && typeof a.isTelevision === 'function') {
        try { nativeTv = !!a.isTelevision(); } catch { nativeTv = null; }
    }
    return { inApk, nativeTv };
}

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

    /* Match the underlying device, not the APK's OnNowTV UA suffix. */
    try {
        return isHandheldInput({
            userAgent: navigator.userAgent || '',
            coarse: typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches,
            touchPoints: navigator.maxTouchPoints || 0,
            touchEvents: 'ontouchstart' in window,
            width: window.innerWidth,
            height: window.innerHeight,
            inputSeen: readInputSeen(),
            ...nativeBridge(),
        });
    } catch { /* ignore */ }

    /* 4. Default: TV / desktop. */
    return false;
}

export default function useIsMobile() {
    const [isMobile, setIsMobile] = useState(detect);
    useEffect(() => {
        installInputProbe();
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
