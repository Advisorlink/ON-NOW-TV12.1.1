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
        // Two-mode pacing tuned to match Android TV's LeanBack launcher:
        //
        //   • SINGLE press → 75 ms cooldown, smooth scroll
        //   • HOLD / repeat (e.repeat === true) → 55 ms cooldown,
        //     instant scroll
        //
        // LeanBack accepts ~14 presses/sec while held; we mirror that
        // by detecting key auto-repeat (`e.repeat`) and switching to
        // instant scroll while the user holds — so tiles glide through
        // rather than queueing smooth animations that pile up.
        const COOLDOWN_PRESS_MS = 75;
        const COOLDOWN_REPEAT_MS = 55;
        // LeanBack pins the focused row at ~32% of the viewport so the
        // next row down is already visible (and the row above stays in
        // peripheral view).  Rows slide; focused tile stays put.
        const VERTICAL_PIN_RATIO = 0.32;
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

        const focusEl = (el, dir, instant = false) => {
            if (!el) return;
            el.focus({ preventScroll: true });
            setFocusAttr(el);

            const rect = el.getBoundingClientRect();
            const vh = window.innerHeight;
            const scrollBehavior = instant ? 'auto' : 'smooth';

            if (dir === 'left' || dir === 'right') {
                const hs = horizontalScroller(el);
                if (hs) {
                    const cRect = hs.getBoundingClientRect();
                    const delta =
                        rect.left - cRect.left - (cRect.width - rect.width) / 2;
                    hs.scrollBy({ left: delta, behavior: scrollBehavior });
                }
                return;
            }

            const vs = verticalScroller(el) || document.scrollingElement;
            if (!vs) return;
            // LeanBack-style: pin focused element at a fixed Y so the
            // shelves glide under it rather than the focused tile
            // drifting around within a band.  We use the centre of the
            // focused element for the pin point and only scroll when
            // it's measurably off-target.
            const targetY = vh * VERTICAL_PIN_RATIO;
            const focusedY = rect.top + rect.height / 2;
            const delta = focusedY - targetY;
            if (Math.abs(delta) > 4) {
                vs.scrollBy({ top: delta, behavior: scrollBehavior });
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
                const repeat = !!e.repeat;
                const cooldown = repeat ? COOLDOWN_REPEAT_MS : COOLDOWN_PRESS_MS;
                const now =
                    typeof performance !== 'undefined'
                        ? performance.now()
                        : Date.now();
                if (now - lastDirAt < cooldown) return;
                lastDirAt = now;

                const active =
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                        ? document.activeElement
                        : focusables()[0];
                if (!active) return;
                const next = findNext(active, dir);
                if (next) focusEl(next, dir, repeat);
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
