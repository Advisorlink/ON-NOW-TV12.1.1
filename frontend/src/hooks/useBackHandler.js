/**
 * useBackHandler — wires the browser-level Escape / Backspace key (which
 * is what the Android Kotlin wrapper translates KEYCODE_BACK into) to a
 * navigation handler.
 *
 * Use on every full-screen page that should pop back to a known route
 * when the user presses BACK on the remote.  This is independent of the
 * `useHomeBackHandler` flag — that one just tells the native Activity
 * "this is the Home page, you can prompt-exit on BACK".  This hook is
 * the JS-side action.
 *
 * @param onBack  Function to run when Escape or Backspace is pressed
 *                while focus is NOT inside an input/textarea (so users
 *                can still backspace text inside fields).  Pass either
 *                a route string (`'/'`, `'/movies'`) or a callable.
 *
 * Usage:
 *   useBackHandler('/');                 // navigate to Home
 *   useBackHandler(() => { close(); });   // run a callback instead
 */

import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

export default function useBackHandler(onBack) {
    const navigate = useNavigate();

    useEffect(() => {
        const handler = (e) => {
            if (e.key !== 'Escape' && e.key !== 'Backspace') return;
            const tag = (e.target?.tagName || '').toUpperCase();
            // Never steal Backspace from text inputs — that's how the
            // user deletes characters.  Escape, on the other hand, is
            // legit: many TV firmwares forward the remote BACK key as
            // Escape regardless of focus, and we want it to navigate.
            if (e.key === 'Backspace' && (tag === 'INPUT' || tag === 'TEXTAREA')) {
                return;
            }
            e.preventDefault();
            e.stopPropagation();
            if (typeof onBack === 'function') {
                onBack(e);
            } else if (typeof onBack === 'string') {
                navigate(onBack);
            } else {
                // Default: pop browser history one step.
                navigate(-1);
            }
        };
        // Use capture phase so this runs BEFORE any page-level handler
        // (e.g. LiveTV's own onKey listener) — guarantees BACK is
        // consistent across the app.
        window.addEventListener('keydown', handler, true);
        return () => window.removeEventListener('keydown', handler, true);
    }, [onBack, navigate]);
}
