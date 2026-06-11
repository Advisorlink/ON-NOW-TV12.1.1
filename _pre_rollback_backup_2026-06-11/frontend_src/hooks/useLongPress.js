import { useEffect, useRef } from 'react';

/**
 * Press-and-hold detector for TV remote (Enter / Space) and mouse.
 *
 * Returns props that spread onto the focusable element.  Calls
 * `onLongPress()` when held for `duration` ms; otherwise calls
 * `onTap()` on release.  Sets `data-holding="true"` on the
 * element during the hold so CSS can paint a progress ring.
 *
 *   const press = useLongPress(showAddModal, navigate);
 *   <button {...press} ...>
 *
 * Quirks worth knowing:
 *  • The global spatial-focus hook fires `el.click()` on every
 *    Enter keydown (including held repeats).  We `stopPropagation`
 *    on our keydown so that window listener never sees the event,
 *    then manually fire `onTap` on keyup if the hold timer didn't
 *    trip.  This way:
 *      - quick tap → onTap (= original onClick navigation)
 *      - hold ≥ 700 ms → onLongPress (= "Add to My List" modal)
 *  • We `preventDefault` on the Enter keydown so the browser's
 *    own "Enter activates focused button" path doesn't double-fire
 *    a click.  Same reason we wrap onClick to swallow it on mouse.
 *
 * v2.7.85 — TOUCH FAST-PATH.  On phones / tablets (coarse pointer)
 * we used to install custom touchstart / touchmove / touchend
 * handlers to disambiguate tap from scroll + scroll from
 * long-press.  In practice every variant of that logic ended up
 * stealing the scroll gesture when the user's finger landed on a
 * poster ("I can't scroll up when my thumb is on a cover" — user
 * report).  The fix: on touch devices DON'T install ANY touch
 * handlers at all.  The browser's native click event already
 * fires after a touchstart → touchend that didn't move; scrolling
 * gestures never produce a click.  Result: page scroll is
 * BUTTER-SMOOTH (no JS in the touch loop), short tap navigates,
 * long-press is unavailable on touch (still works via mouse / D-pad).
 */

/* Detect coarse pointers (= touch primary input).  Evaluated once
   per module load — phones don't switch input mode mid-session. */
function detectTouchPrimary() {
    if (typeof window === 'undefined') return false;
    try {
        if (typeof window.matchMedia === 'function') {
            return window.matchMedia('(pointer: coarse)').matches;
        }
    } catch { /* ignore */ }
    return (
        ('ontouchstart' in window) ||
        (typeof navigator !== 'undefined' && navigator.maxTouchPoints > 0)
    );
}
const TOUCH_PRIMARY = detectTouchPrimary();

export default function useLongPress(onLongPress, onTap, { duration = 700 } = {}) {
    const timerRef = useRef(null);
    const heldRef = useRef(false);
    const longPressFiredRef = useRef(false);
    const lastReleaseRef = useRef(0);
    const elRef = useRef(null);

    useEffect(() => {
        return () => {
            if (timerRef.current) clearTimeout(timerRef.current);
        };
    }, []);

    /* ─── TOUCH FAST-PATH ────────────────────────────────────────
       On phones we install ONLY a native onClick.  The browser
       handles scroll vs tap natively; we never intercept the
       touch loop.  No visual hold-glow, no long-press — the
       "Add to My List" affordance on mobile lives elsewhere
       (the Detail page has a dedicated button). */
    if (TOUCH_PRIMARY) {
        return {
            ref: (el) => { elRef.current = el; },
            onClick: (e) => {
                /* Synthetic click from native button activation.
                   Stop further bubbling so the global spatial-
                   focus listener doesn't fire a SECOND click. */
                e.stopPropagation();
                const now = Date.now();
                if (now - lastReleaseRef.current < 250) return;
                lastReleaseRef.current = now;
                if (typeof onTap === 'function') onTap();
            },
            onContextMenu: (e) => {
                /* Suppress the browser's right-click menu when a
                   long-press fires on touch — but we don't act on
                   it (long-press isn't supported on mobile). */
                e.preventDefault();
            },
        };
    }

    /* ─── DESKTOP / TV PATH ──────────────────────────────────── */
    const start = () => {
        if (heldRef.current) return; // already holding
        heldRef.current = true;
        longPressFiredRef.current = false;
        if (elRef.current) elRef.current.setAttribute('data-holding', 'true');
        timerRef.current = setTimeout(() => {
            longPressFiredRef.current = true;
            if (elRef.current) elRef.current.removeAttribute('data-holding');
            if (typeof onLongPress === 'function') onLongPress();
        }, duration);
    };

    const cancel = () => {
        if (timerRef.current) {
            clearTimeout(timerRef.current);
            timerRef.current = null;
        }
        heldRef.current = false;
        if (elRef.current) elRef.current.removeAttribute('data-holding');
    };

    const release = () => {
        if (!heldRef.current && !longPressFiredRef.current) return;
        const fired = longPressFiredRef.current;
        cancel();
        if (!fired) {
            const now = Date.now();
            if (now - lastReleaseRef.current < 250) return;
            lastReleaseRef.current = now;
            if (typeof onTap === 'function') onTap();
        }
    };

    return {
        ref: (el) => {
            elRef.current = el;
        },
        onKeyDown: (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            e.preventDefault();
            e.stopPropagation();
            if (e.repeat) return;
            start();
        },
        onKeyUp: (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            e.preventDefault();
            e.stopPropagation();
            release();
        },
        onMouseDown: (e) => {
            if (e.button !== 0) return;
            start();
        },
        onMouseUp: (e) => {
            if (e.button !== 0) return;
            release();
        },
        onMouseLeave: () => {
            cancel();
        },
        onClick: (e) => {
            e.preventDefault();
            e.stopPropagation();
        },
        onContextMenu: (e) => {
            e.preventDefault();
        },
    };
}
