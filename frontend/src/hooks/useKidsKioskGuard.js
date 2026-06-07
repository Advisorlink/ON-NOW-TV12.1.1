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
import { getKidsConfig, isKidsActive } from '@/lib/profiles';

const KIDS_ROUTE_RE = /^\/kids(\/|$)/;
const ALLOWED_AUX_ROUTES = ['/title', '/play', '/resolve', '/search'];

function isKidsAllowedPath(pathname) {
    // v2.9.7 — `/` is the Kids HOME (HomeRouter renders KidsHome when
    // isKidsActive()).  Without this, the guard treated `/` as
    // outside the sandbox and immediately bounced the user to
    // `/kids/exit-pin` on app launch — making it look like the user
    // had to enter a PIN just to GET IN to the Kids app.
    if (pathname === '/' || pathname === '') return true;
    if (KIDS_ROUTE_RE.test(pathname)) return true;
    // The Kids experience can navigate into title detail / player —
    // those are still inside the kid-safe sandbox.  Anything else
    // (Settings, Search, Music, etc.) is OUT.
    return ALLOWED_AUX_ROUTES.some((p) => pathname.startsWith(p));
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

        const reArmLock = () => {
            try { window.OnNowTV?.setKidsLock?.(true); } catch { /* ignore */ }
            try { window.__vesperKidsLocked = '1'; } catch { /* ignore */ }
        };

        // Path-watch: redirect to /kids/exit-pin if route escapes
        // the sandbox while kids is locked.
        if (armed() && !isKidsAllowedPath(location.pathname)) {
            reArmLock();
            navigate('/kids/exit-pin', { replace: true });
            return undefined;
        }
        if (armed()) reArmLock();

        // Visibility guard: when the user returns from a HOME press
        // (Vesper backgrounded → foregrounded), force them back into
        // /kids if they wandered out.
        const onVisibility = () => {
            if (document.visibilityState !== 'visible') return;
            if (!armed()) return;
            reArmLock();
            if (!isKidsAllowedPath(window.location.hash.replace(/^#/, '') || '/')) {
                navigate('/kids', { replace: true });
            }
        };
        document.addEventListener('visibilitychange', onVisibility);
        window.addEventListener('focus', onVisibility);

        // Heartbeat re-arm every 30 s — paranoid defence against the
        // launcher backend forgetting our lock.
        const interval = setInterval(() => { if (armed()) reArmLock(); }, 30_000);

        return () => {
            document.removeEventListener('visibilitychange', onVisibility);
            window.removeEventListener('focus', onVisibility);
            clearInterval(interval);
        };
    }, [location.pathname, navigate]);
}
