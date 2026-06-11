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
        // box is now in Kids+PIN sandbox mode.
        try { window.OnNowTV?.setKidsLock?.(true); } catch { /* ignore */ }

        // v2.10.5 — Per user feedback: PIN should NOT be required
        // when navigating BACK within the Kids sandbox (e.g. from a
        // Detail page back to Kids Home).  Only intercept when the
        // user would otherwise leave the sandbox entirely.
        //
        // The sandbox boundary is "any URL inside the Kids-safe
        // SPA routes".  We list explicit blocked paths and use a
        // single popstate listener that decides AFTER the pop:
        //   • landed somewhere still inside the sandbox → allow
        //   • landed on a blocked path / outside SPA → re-push
        //     sentinel + redirect to /kids/exit-pin.
        //
        // We push ONE sentinel at mount on the root path so the
        // initial back-from-kids-home press has somewhere to land
        // that we control.  Deeper pages don't push extra
        // sentinels — their natural back stack works fine.
        const BLOCKED_PREFIXES = [
            '/settings',
            '/sources',
            '/watch-together',
            '/admin',
            '/music',  // Music is a sibling app, kids stay out
        ];
        const isExitTarget = (path) => {
            if (!path) return true;
            // Profiles is fine — the user is allowed to swap back
            // out via the proper PIN flow, but raw browser back to
            // it should still trigger PIN protection.
            if (path === '/profiles' || path.startsWith('/profiles/')) {
                return true;
            }
            return BLOCKED_PREFIXES.some((p) => path === p || path.startsWith(`${p}/`));
        };

        // Only push the safety sentinel when we're at the kids
        // home root.  This is the one page from which a Back press
        // would otherwise pop the SPA out of the kids sandbox.
        if (location.pathname === '/') {
            try {
                window.history.pushState(
                    { kidsSentinel: true, at: location.pathname },
                    '',
                    location.pathname + location.search,
                );
            } catch { /* ignore */ }
        }

        const onPop = () => {
            if (!isKidsActive()) return;
            // setTimeout 0 so window.location reflects the
            // post-pop URL (popstate fires before location updates
            // in some browsers).
            setTimeout(() => {
                const newPath = window.location.pathname;
                // Still safely inside the sandbox → let it be.
                if (!isExitTarget(newPath)) return;
                // User is heading somewhere outside the sandbox.
                // Re-push sentinel so they can't pop further and
                // route them to the PIN gate.
                try {
                    window.history.pushState(
                        { kidsSentinel: true, at: '/' },
                        '',
                        '/',
                    );
                } catch { /* ignore */ }
                if (!newPath.startsWith('/kids/exit-pin')) {
                    navigate('/kids/exit-pin');
                }
            }, 0);
        };
        window.addEventListener('popstate', onPop);
        return () => {
            window.removeEventListener('popstate', onPop);
            try { window.__vesperKidsLocked = ''; } catch { /* ignore */ }
            try { window.OnNowTV?.setKidsLock?.(false); } catch { /* ignore */ }
        };
    }, [location.pathname, location.search, navigate]);
}
