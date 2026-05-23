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
 */
export default function useLongPress(onLongPress, onTap, { duration = 700 } = {}) {
    const timerRef = useRef(null);
    const heldRef = useRef(false);
    const longPressFiredRef = useRef(false);
    const lastReleaseRef = useRef(0);
    const elRef = useRef(null);
    /* Touch tracking: remember the starting (x,y) so we can detect
       scroll gestures and cancel cleanly without ever firing onTap. */
    const touchStartRef = useRef({ x: 0, y: 0, t: 0, moved: false });
    /* Touch "confirm-press" timer — on touch we delay the visual
       hold-glow + long-press countdown by 130 ms so a vertical
       page-scroll gesture (which starts with finger ON a tile)
       never paints the glow.  If the user actually intends a
       press, 130 ms is below human-perception of latency so the
       feedback still feels instant. */
    const touchConfirmRef = useRef(null);

    useEffect(() => {
        return () => {
            if (timerRef.current) clearTimeout(timerRef.current);
        };
    }, []);

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
        if (touchConfirmRef.current) {
            clearTimeout(touchConfirmRef.current);
            touchConfirmRef.current = null;
        }
        heldRef.current = false;
        if (elRef.current) elRef.current.removeAttribute('data-holding');
    };

    const release = () => {
        if (!heldRef.current && !longPressFiredRef.current) return;
        const fired = longPressFiredRef.current;
        cancel();
        if (!fired) {
            // Short tap.  Debounce so a double-fire from concurrent
            // mouseup+click events doesn't navigate twice.
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
            // Swallow so the global spatial-focus listener does not
            // fire its own .click() — we own activation here.
            e.preventDefault();
            e.stopPropagation();
            if (e.repeat) return; // already holding
            start();
        },
        onKeyUp: (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            e.preventDefault();
            e.stopPropagation();
            release();
        },
        onMouseDown: (e) => {
            // Only respond to primary (left) click.
            if (e.button !== 0) return;
            start();
        },
        onMouseUp: (e) => {
            if (e.button !== 0) return;
            release();
        },
        onMouseLeave: () => {
            // Mouse dragged off mid-hold → cancel without firing tap.
            cancel();
        },
        /* Touch handlers ─────────────────────────────────────────
         *
         * The key insight here is that on a phone, every touch is
         * potentially the START of a scroll gesture.  We must NOT
         * preventDefault on touchstart/touchend/touchmove (doing so
         * would freeze page scrolling), and we must cancel the long-
         * press timer the moment we detect the finger has moved
         * more than a few pixels in any direction — that's the
         * browser's signal that the user is scrolling, not tapping.
         *
         * v2.7.84 — defer the visible hold-glow + long-press countdown
         * by 130 ms.  Without this, every vertical page-scroll
         * gesture that happens to start ON a tile would briefly
         * paint the glow ring (because touchstart fires before the
         * browser has had a chance to interpret the gesture as a
         * scroll).  130 ms is below typical human latency
         * perception so taps still feel instant.
         */
        onTouchStart: (e) => {
            if (e.touches && e.touches.length > 1) return;   // pinch
            const t = e.touches?.[0] || e;
            touchStartRef.current = {
                x: t.clientX || 0,
                y: t.clientY || 0,
                t: Date.now(),
                moved: false,
            };
            /* Defer the actual press-start.  If the finger moves
               within 130 ms (= scroll gesture) or the tap releases
               within 130 ms (= quick tap, handled in onTouchEnd
               directly), no visual feedback ever paints. */
            if (touchConfirmRef.current) clearTimeout(touchConfirmRef.current);
            touchConfirmRef.current = setTimeout(() => {
                touchConfirmRef.current = null;
                if (touchStartRef.current.moved) return;
                start();
            }, 130);
        },
        onTouchMove: (e) => {
            /* If the finger has moved more than 6 px in any
               direction since touchstart, treat it as a scroll
               gesture and cancel any pending press intent.  6 px is
               a touch tighter than the 8 px we used previously —
               on a high-DPI Samsung screen even an unintentional
               finger micro-tremor reads as 4-5 px, so 6 keeps the
               tap firmly distinct from scroll. */
            const t = e.touches?.[0];
            if (!t) return;
            const dx = (t.clientX || 0) - touchStartRef.current.x;
            const dy = (t.clientY || 0) - touchStartRef.current.y;
            if (Math.hypot(dx, dy) > 6) {
                touchStartRef.current.moved = true;
                cancel();
            }
        },
        onTouchEnd: (_e) => {
            /* IMPORTANT: do NOT call preventDefault here.  Doing so
               prevents browsers from completing the scroll-up
               gesture cleanly on iOS Safari + some Android
               WebViews — they snap back instead of holding the
               scroll position.  We also don't need to suppress the
               synthetic click — onClick is already a no-op (see
               below) and the tap action is fired from `release()`. */
            if (touchStartRef.current.moved) {
                /* Pure scroll gesture — neutralise so onTap never
                   fires from a leftover heldRef. */
                cancel();
                return;
            }
            /* Quick tap that released BEFORE the 130 ms confirm
               timer ever fired — neither start() nor the long-
               press timer ever ran.  Manually fire onTap here. */
            if (touchConfirmRef.current) {
                clearTimeout(touchConfirmRef.current);
                touchConfirmRef.current = null;
                const now = Date.now();
                if (now - lastReleaseRef.current < 250) return;
                lastReleaseRef.current = now;
                if (typeof onTap === 'function') onTap();
                return;
            }
            release();
        },
        onTouchCancel: () => {
            cancel();
        },
        onClick: (e) => {
            // We handle activation manually via key/mouse up.  Suppress
            // the synthetic click event (fires from native button
            // activation OR from the spatial-focus hook).
            e.preventDefault();
            e.stopPropagation();
        },
        onContextMenu: (e) => {
            // Long-press on touch devices fires contextmenu — block
            // the right-click menu but DON'T treat it as anything.
            e.preventDefault();
        },
    };
}
