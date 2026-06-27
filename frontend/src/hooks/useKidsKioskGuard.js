// v2.8.78 — Global kids-kiosk guard.
//
// When the kids profile is active AND a 4-digit PIN is configured,
// this hook keeps the user trapped inside `/kids/*`:
//
//   1. As soon as the document becomes visible (HOME → Vesper return
//      from the launcher), if React Router is on a non-`/kids/*` URL
//      we redirect to `/kids` and re-arm `setKidsLock(true)` so the
//      launcher's onResume bounces any future HOME presses straight
//      back here.
//   2. Whenever the location pathname changes to a non-kids route
//      while kids is locked, we redirect to `/kids/exit-pin` — the
//      only legitimate way out.
//   3. Periodically (every 30 s) we re-fire `setKidsLock(true)` as a
//      defence-in-depth in case the launcher backend lost the flag
//      (e.g. backend restart).
//
// Mount once in `<App>` so every page is covered.

import { useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { getKidsConfig, isKidsActive, isKidsApp } from '@/lib/profiles';

const KIDS_ROUTE_RE = /^\/kids(\/|$)/;
const ALLOWED_AUX_ROUTES = ['/title', '/play', '/resolve', '/search'];

function isKidsAllowedPath(pathname) {
    // v2.10.69 — The Kids home page is at `/` in the standalone
    // Kids APK (and also via HomeRouter in legacy in-Vesper kids
    // mode), so root is always a legitimate Kids path.  Without
    // this, the kiosk guard bounces the user straight from Kids
    // Setup → Kids Home → /kids/exit-pin in a single tick.
    if (pathname === '/') return true;
    if (KIDS_ROUTE_RE.test(pathname)) return true;
    // The Kids experience can navigate into title detail / player —
    // those are still inside the kid-safe sandbox.  Anything else
    // (Settings, Search, Music, etc.) is OUT.
    return ALLOWED_AUX_ROUTES.some((p) => pathname.startsWith(p));
}

/**
 * v2.10.71 — True when the path is a TOPMOST kids surface (Home,
 * Exit-PIN gate).  Topmost paths are the only ones where the
 * native BACK handler should bounce to the PIN gate — pressing
 * BACK on a Detail / Player / Search page must do a normal
 * `webView.goBack()` so the user can pop out of the sandbox depth.
 */
function isTopmostKidsPath(pathname) {
    return pathname === '/' ||
        pathname === '/kids' ||
        pathname.startsWith('/kids/exit-pin');
}

export default function useKidsKioskGuard() {
    const navigate = useNavigate();
    const location = useLocation();

    useEffect(() => {
        const armed = () => {
            if (!isKidsActive()) return false;
            const cfg = getKidsConfig();
            return !!(cfg.pin && cfg.pin.length === 4);
        };

        // v2.10.71 — Split the lock-arming into two independent
        // signals so deep paths don't trigger the native BACK
        // bounce-to-PIN (which broke movie playback).
        //   • Backend kids-lock (launcher HOME guard) — ALWAYS on.
        //   • Native BACK lock (`__vesperKidsLocked`) — ONLY on
        //     topmost kids paths.  Clear it everywhere else so the
        //     remote BACK button does a normal `webView.goBack()`.
        const reArmBackendLock = () => {
            try { window.OnNowTV?.setKidsLock?.(true); } catch { /* ignore */ }
        };
        const armNativeBackLock = (topmost) => {
            try {
                window.__vesperKidsLocked = topmost ? '1' : '';
            } catch { /* ignore */ }
        };

        // Path-watch: redirect to /kids/exit-pin if route escapes
        // the sandbox while kids is locked.
        if (armed() && !isKidsAllowedPath(location.pathname)) {
            reArmBackendLock();
            armNativeBackLock(true);
            navigate('/kids/exit-pin', { replace: true });
            return undefined;
        }
        if (armed()) {
            reArmBackendLock();
            armNativeBackLock(isTopmostKidsPath(location.pathname));
        }

        // Visibility guard: when the user returns from a HOME press
        // (Vesper backgrounded → foregrounded), force them back into
        // /kids if they wandered out.
        const onVisibility = () => {
            if (document.visibilityState !== 'visible') return;
            if (!armed()) return;
            reArmBackendLock();
            const curPath = window.location.hash.replace(/^#/, '') || window.location.pathname || '/';
            armNativeBackLock(isTopmostKidsPath(curPath));
            if (!isKidsAllowedPath(curPath)) {
                // v2.10.69 — In the standalone Kids APK the home is
                // at `/`; the legacy `/kids` route doesn't exist
                // there.  Just go home.
                navigate(isKidsApp() ? '/' : '/kids', { replace: true });
            }
        };
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('focus', onVisibility);

        // Heartbeat re-arm every 30 s — paranoid defence against the
        // launcher backend forgetting our lock.
        const interval = setInterval(() => {
            if (armed()) reArmBackendLock();
        }, 30_000);

        return () => {
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('focus', onVisibility);
            clearInterval(interval);
        };
    }, [location.pathname, navigate]);
}
