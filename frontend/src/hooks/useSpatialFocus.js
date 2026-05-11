import { useEffect } from 'react';

/**
 * Spatial D-pad focus manager for TV.
 *
 * Listens for ArrowUp / ArrowDown / ArrowLeft / ArrowRight at the
 * window level and moves focus to the geometrically nearest
 * element marked with `data-focusable="true"`.
 *
 * Also forwards Enter to a click() on the focused element so
 * keyboards / remotes that don't fire native click work.
 *
 * Designed to "just work" with native browser focus + scrollIntoView,
 * so any element in the tree only needs:
 *
 *   <button data-focusable="true" data-focus-style="tile" tabIndex={0}>
 */
export default function useSpatialFocus() {
    useEffect(() => {
        // Rapid-press throttle.  On HK1 boxes the IR remote auto-
        // repeats arrow keys at ~12 Hz (one event every ~83 ms).
        // We pace direction moves to ~190 ms apart so every tile in
        // the path gets a chance to animate its focus ring + pop-out.
        // Without this the JS focus loop outruns the smooth-scroll
        // animation and tiles appear to "skip" past without ever
        // lighting up — most noticeable on tightly-packed poster
        // shelves and on vertical row-to-row jumps.
        const DIR_COOLDOWN_MS = 190;
        let lastDirAt = 0;

        const focusables = () =>
            Array.from(
                document.querySelectorAll('[data-focusable="true"]')
            ).filter((el) => {
                const r = el.getBoundingClientRect();
                return (
                    !el.hasAttribute('disabled') &&
                    r.width > 0 &&
                    r.height > 0 &&
                    getComputedStyle(el).visibility !== 'hidden'
                );
            });

        const center = (rect) => ({
            x: rect.left + rect.width / 2,
            y: rect.top + rect.height / 2,
        });

        const findNext = (current, dir) => {
            const cur = current.getBoundingClientRect();
            const c = center(cur);
            const candidates = focusables().filter((el) => el !== current);

            let best = null;
            let bestScore = Infinity;

            for (const el of candidates) {
                const r = el.getBoundingClientRect();
                const ec = center(r);
                const dx = ec.x - c.x;
                const dy = ec.y - c.y;

                let inDirection = false;
                let primary = 0;
                let perpendicular = 0;
                const overlapTol = 8;

                if (dir === 'right') {
                    inDirection = r.left >= cur.right - overlapTol;
                    primary = r.left - cur.right;
                    perpendicular = Math.abs(dy);
                } else if (dir === 'left') {
                    inDirection = r.right <= cur.left + overlapTol;
                    primary = cur.left - r.right;
                    perpendicular = Math.abs(dy);
                } else if (dir === 'down') {
                    inDirection = r.top >= cur.bottom - overlapTol;
                    primary = r.top - cur.bottom;
                    perpendicular = Math.abs(dx);
                } else if (dir === 'up') {
                    inDirection = r.bottom <= cur.top + overlapTol;
                    primary = cur.top - r.bottom;
                    perpendicular = Math.abs(dx);
                }

                if (!inDirection) continue;
                if (primary < 0) primary = 0;

                // Heavy weight on perpendicular distance so we prefer items
                // roughly aligned with the current focused item.
                const score = primary + perpendicular * 2;
                if (score < bestScore) {
                    bestScore = score;
                    best = el;
                }
            }
            return best;
        };

        // Find the nearest vertically-scrollable ancestor of an element.
        const verticalScroller = (el) => {
            let p = el.parentElement;
            while (p && p !== document.body) {
                const cs = getComputedStyle(p);
                const oy = cs.overflowY;
                if (
                    (oy === 'auto' || oy === 'scroll') &&
                    p.scrollHeight > p.clientHeight
                ) {
                    return p;
                }
                p = p.parentElement;
            }
            return null;
        };

        // Find the nearest horizontally-scrollable ancestor (the shelf).
        const horizontalScroller = (el) => {
            let p = el.parentElement;
            while (p && p !== document.body) {
                const cs = getComputedStyle(p);
                const ox = cs.overflowX;
                if (
                    (ox === 'auto' || ox === 'scroll') &&
                    p.scrollWidth > p.clientWidth
                ) {
                    return p;
                }
                p = p.parentElement;
            }
            return null;
        };

        // Maintain a `data-focused` attribute so CSS focus styles
        // (scale pop-out, glow ring) apply even when programmatic
        // focus is used on Android WebView, where :focus-visible
        // doesn't always trigger.
        let lastFocused = null;
        const setFocusAttr = (el) => {
            if (lastFocused && lastFocused !== el) {
                lastFocused.removeAttribute('data-focused');
            }
            if (el) {
                el.setAttribute('data-focused', 'true');
                lastFocused = el;
            }
        };

        const focusEl = (el, dir) => {
            if (!el) return;
            el.focus({ preventScroll: true });
            setFocusAttr(el);

            const rect = el.getBoundingClientRect();
            const vh = window.innerHeight;

            if (dir === 'left' || dir === 'right') {
                // Horizontal move: scroll ONLY the shelf, never the
                // page.  CSS scroll-snap on the shelf has been
                // disabled to prevent rubber-banding against this
                // JS-controlled smooth scroll.
                const hs = horizontalScroller(el);
                if (hs) {
                    const cRect = hs.getBoundingClientRect();
                    const delta =
                        rect.left - cRect.left - (cRect.width - rect.width) / 2;
                    hs.scrollBy({ left: delta, behavior: 'smooth' });
                }
                return;
            }

            // Vertical move: scroll the page (not the shelf) so the
            // focused tile sits in a comfortable band.  Compute the
            // delta ourselves and animate via the outer scroller —
            // never via scrollIntoView, which double-scrolls when
            // CSS scroll-behavior is also smooth.
            const vs = verticalScroller(el) || document.scrollingElement;
            if (!vs) return;

            // Target band: keep the focused row between 22% and 70%
            // of the viewport height.
            const topBand = vh * 0.22;
            const bottomBand = vh * 0.7;
            let delta = 0;
            if (rect.top < topBand) {
                delta = rect.top - topBand;
            } else if (rect.bottom > bottomBand) {
                delta = rect.bottom - bottomBand;
            }
            if (Math.abs(delta) > 4) {
                vs.scrollBy({ top: delta, behavior: 'smooth' });
            }
        };

        const onKey = (e) => {
            // When the user is typing in an input/textarea, let the
            // browser handle keys natively (cursor movement, paste,
            // typing, Enter-to-submit handled by the input's own
            // onKeyDown).  Escape blurs the input so D-pad nav resumes.
            const tag = (document.activeElement?.tagName || '').toLowerCase();
            if (tag === 'input' || tag === 'textarea') {
                if (e.key === 'Escape') {
                    document.activeElement.blur();
                }
                return;
            }

            const dirMap = {
                ArrowRight: 'right',
                ArrowLeft: 'left',
                ArrowUp: 'up',
                ArrowDown: 'down',
            };
            const dir = dirMap[e.key];

            if (dir) {
                e.preventDefault();
                // Throttle: discard the press if the previous one is
                // still within the cooldown window.  This is what
                // stops the visual "skip" on auto-repeat — every
                // accepted press now has time to animate.
                const now =
                    typeof performance !== 'undefined'
                        ? performance.now()
                        : Date.now();
                if (now - lastDirAt < DIR_COOLDOWN_MS) return;
                lastDirAt = now;

                const active =
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                        ? document.activeElement
                        : focusables()[0];
                if (!active) return;
                const next = findNext(active, dir);
                if (next) focusEl(next, dir);
                return;
            }

            if (e.key === 'Enter' || e.key === ' ') {
                if (
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                ) {
                    e.preventDefault();
                    document.activeElement.click();
                }
            }
        };

        // Initial focus: prefer an element marked data-initial-focus,
        // otherwise the first focusable on the page.
        const init = () => {
            if (
                document.activeElement &&
                document.activeElement.matches('[data-focusable="true"]')
            ) {
                setFocusAttr(document.activeElement);
                return;
            }
            const preferred = document.querySelector(
                '[data-focusable="true"][data-initial-focus="true"]'
            );
            const target = preferred || focusables()[0];
            if (target) {
                target.focus({ preventScroll: true });
                setFocusAttr(target);
            }
        };

        const t = setTimeout(init, 250);
        window.addEventListener('keydown', onKey);
        return () => {
            window.removeEventListener('keydown', onKey);
            clearTimeout(t);
        };
    }, []);
}
