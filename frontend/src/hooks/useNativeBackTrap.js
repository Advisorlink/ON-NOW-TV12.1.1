import { useEffect, useRef } from 'react';

/**
 * useNativeBackTrap — make the Android BACK key close an overlay
 * instead of unwinding the WebView / exiting the app.
 *
 * MainActivity turns KEYCODE_BACK into `webView.goBack()` (or the
 * exit dialog when `window.__vesperOnHome === 'home-root'`).  While
 * `active` we push a synthetic history entry and blank the Home
 * flag, so BACK → popstate → `onBack()`.  If `onBack` returns true
 * ("handled, still open") the entry is re-armed for the next press.
 */
let armed = 0;
let stale = 0; // markers left behind by traps that closed while another was armed

export function useNativeBackTrap(active, onBack) {
    const cbRef = useRef(onBack);
    cbRef.current = onBack;

    useEffect(() => {
        if (!active || typeof window === 'undefined') return undefined;
        let popped = false;
        const prevHome = window.__vesperOnHome;
        window.__vesperOnHome = '';
        const arm = () => {
            try { window.history.pushState({ vesperTrap: true, ts: Date.now() }, ''); } catch { /* ignore */ }
        };
        arm();
        armed += 1;
        const onPop = () => {
            const stay = cbRef.current?.() === true;
            if (stay) {
                arm();
                return;
            }
            popped = true;
            if (stale > 0) {
                stale -= 1;
                setTimeout(() => { try { window.history.back(); } catch { /* ignore */ } }, 0);
            }
        };
        window.addEventListener('popstate', onPop);
        return () => {
            window.removeEventListener('popstate', onPop);
            window.__vesperOnHome = prevHome || '';
            armed -= 1;
            if (popped) return;
            // Deferred so a StrictMode remount (which re-arms
            // synchronously) never gets its fresh entry popped.
            setTimeout(() => {
                if (armed > 0) {
                    stale += 1;
                    return;
                }
                try {
                    if (window.history.state?.vesperTrap) window.history.back();
                } catch { /* ignore */ }
            }, 0);
        };
    }, [active]);
}

/** Fire the same unwind the hardware key would (keeps history clean). */
export function triggerTrapBack() {
    try { window.history.back(); } catch { /* ignore */ }
}
