import { useEffect } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { getKidsConfig, isKidsActive } from '@/lib/profiles';

/**
 * v2.8.9 — Kids sandbox back-navigation guard.
 *
 * Mount this on every Kids-mode page so the hardware Back button
 * (HK1 remote → KEYCODE_BACK → WebView popstate) can NEVER escape
 * the kid-safe area without the parent PIN.
 *
 * Strategy:
 *   1. On mount, push a sentinel history entry whose `state.kids`
 *      flag we own.  This is the entry the user lands on AFTER
 *      pressing Back from any deeper Kids route.
 *   2. Listen for `popstate`.  When the popped entry isn't ours
 *      (i.e. the user is trying to navigate out of the sandbox)
 *      we re-push the sentinel + route them to the PIN gate.
 *   3. Also set `window.__vesperKidsLocked = '1'` so the native
 *      `MainActivity.onKeyDown` BACK handler knows to route to the
 *      PIN gate instead of calling `webView.goBack()` / `finish()`.
 *
 * Active only when:
 *   • Kids profile is active, AND
 *   • A Kids PIN is configured (`profileHasPin(kidsCfg)`).
 *
 * If no PIN is set the sandbox is "soft" (registration wizard
 * forces a PIN on first activation anyway), and back acts normally.
 */
export default function useKidsBackGuard() {
    const navigate = useNavigate();
    const location = useLocation();

    useEffect(() => {
        if (!isKidsActive()) return undefined;
        const cfg = getKidsConfig();
        const pinSet = !!(cfg.pin && cfg.pin.length === 4);
        if (!pinSet) {
            try { window.__vesperKidsLocked = ''; } catch { /* ignore */ }
            // v2.8.42 — Also clear the backend kids-lock so the
            // launcher won't bounce the user back to Vesper.
            try { window.OnNowTV?.setKidsLock?.(false); } catch { /* ignore */ }
            return undefined;
        }
        // Expose lock-state to the native back handler.
        try { window.__vesperKidsLocked = '1'; } catch { /* ignore */ }
        // v2.8.42 — Notify the LAUNCHER (via its backend) that this
        // box is now in Kids+PIN sandbox mode.  Launcher's
        // MainActivity.onResume() polls the matching GET and bounces
        // the user straight back into Vesper if HOME is pressed.
        try { window.OnNowTV?.setKidsLock?.(true); } catch { /* ignore */ }

        // v2.10.70 — Only TRAP the user with a sentinel on the
        // topmost kids pages (KidsHome at "/" and the exit-PIN
        // itself).  Any deeper page (Detail, Player, Resolve,
        // Search) must allow normal Back so the user can pop
        // KidsHome → Detail → Player → Detail → KidsHome.  Without
        // this, the back-guard intercepted EVERY popstate and
        // bounced the user to /kids/exit-pin the moment they tried
        // to back out of the player — exactly the bug the user
        // reported as "clicking on a movie ends up going back to
        // that Exit App thing".
        const isTopmostKidsPath =
            location.pathname === '/' ||
            location.pathname === '/kids' ||
            location.pathname.startsWith('/kids/exit-pin');
        if (!isTopmostKidsPath) {
            // Normal back navigation is allowed inside the
            // sandbox.  We still keep the locked window flag set
            // so the native HOME handler routes to the PIN gate.
            return () => {
                /* nothing to clean up at this depth */
            };
        }

        // Push a sentinel so the very next Back has somewhere to
        // land that we control.
        try {
            window.history.pushState(
                { kidsSentinel: true, at: location.pathname },
                '',
                location.pathname + location.search,
            );
        } catch { /* ignore */ }

        const onPop = (e) => {
            // The popped state is the entry BEFORE our sentinel.
            // If it has our sentinel flag, the user just hit Back
            // ONTO our sentinel — re-push another sentinel so they
            // can't pop through it on subsequent presses, and
            // route to the PIN gate.
            const inKids = isKidsActive();
            if (!inKids) return;
            // Re-push the sentinel so the stack never empties.
            try {
                window.history.pushState(
                    { kidsSentinel: true, at: location.pathname },
                    '',
                    location.pathname + location.search,
                );
            } catch { /* ignore */ }
            // If we're already on the PIN gate, do nothing — let the
            // user enter the PIN.  Else, route there.
            if (!location.pathname.startsWith('/kids/exit-pin')) {
                navigate('/kids/exit-pin');
            }
        };
        window.addEventListener('popstate', onPop);
        return () => {
            window.removeEventListener('popstate', onPop);
            try { window.__vesperKidsLocked = ''; } catch { /* ignore */ }
            // v2.8.42 — Cleanup: also clear the backend kids-lock so
            // the launcher stops bouncing back to Vesper.  This
            // runs when:
            //   • The user successfully exits Kids via the PIN gate
            //     (KidsExitPin clears localStorage → KidsHome
            //     unmounts → this cleanup fires).
            //   • The user reloads / closes Vesper while in Kids
            //     (defence-in-depth — also covered by the 24-h
            //     stale-lock auto-expiry in the backend).
            try { window.OnNowTV?.setKidsLock?.(false); } catch { /* ignore */ }
        };
    }, [location.pathname, location.search, navigate]);
}
