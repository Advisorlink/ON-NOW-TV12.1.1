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
        // Pacing tuned to match Android TV LeanBack / Stremio TV.
        // Reference apps both use INSTANT scroll while the user
        // navigates — the smoothness comes from the focus-glow CSS
        // transition, NOT animated scrollTo (which queues frames
        // mid-flight and causes the "skipped icon" bug the user
        // reported).
        //
        //   • SINGLE press → 90 ms cooldown
        //   • HOLD / repeat → 55 ms cooldown
        const COOLDOWN_PRESS_MS = 90;
        const COOLDOWN_REPEAT_MS = 55;
        // LeanBack pins the focused row at ~32% of the viewport so
        // the next row down is already visible.
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
            const currentInNav = !!current.closest(
                '[data-testid="side-nav"]'
            );
            // The side-nav participates in spatial navigation ONLY
            // when:
            //   • the user is already inside it (moving up/down
            //     through the menu items), or
            //   • the user pressed Left from outside (handled as a
            //     fallback after findNext() returns null).
            // For all other directions we filter SideNav items out
            // of the candidate set so going Up from a shelf never
            // accidentally lands on Home / TV Shows / Movies.
            const candidates = focusables().filter((el) => {
                if (el === current) return false;
                const inNav = !!el.closest('[data-testid="side-nav"]');
                if (!currentInNav && inNav) return false;
                if (currentInNav && !inNav && dir === 'right') {
                    // Allow leaving the nav rightwards
                    return true;
                }
                return true;
            });

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
                // More forgiving alignment tolerance — picks up
                // items that are a few px off the row/column due to
                // rounding or fractional shelves.
                const overlapTol = 20;

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

                // Heavy weight on perpendicular distance so we
                // strongly prefer items on the same row/column as
                // the focused one — mirrors how Stremio's launcher
                // refuses to jump diagonally unless nothing's
                // directly in line.
                const score = primary + perpendicular * 3;
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

        const focusEl = (el, dir, _instant = false) => {
            if (!el) return;
            // Detect a focus transition that CROSSES into a different
            // scroll container — e.g. moving from the locked hero into
            // the shelves region.  When that happens we snap the new
            // scroller to its top FIRST so the focused tile is fully
            // visible from the start, rather than letting the pin
            // logic chase a delta that overlaps the locked hero.
            const prevVs = lastFocused
                ? verticalScroller(lastFocused)
                : null;
            const nextVs = verticalScroller(el);
            const crossingScrollers =
                nextVs && nextVs !== prevVs && nextVs !== document.scrollingElement;
            if (crossingScrollers) {
                try {
                    nextVs.scrollTop = 0;
                } catch {
                    /* ignore */
                }
            }

            el.focus({ preventScroll: true });
            setFocusAttr(el);

            const rect = el.getBoundingClientRect();
            const vh = window.innerHeight;
            const scrollBehavior = 'auto';

            if (dir === 'left' || dir === 'right') {
                const hs = horizontalScroller(el);
                if (hs) {
                    // EDGE-COMFORT horizontal scroll — matches
                    // Stremio / Apple TV / Google TV behaviour.
                    // The focused tile is allowed to drift naturally
                    // across the row; the rail only scrolls when the
                    // tile is about to go off-screen.  Concretely:
                    //   • While moving Right, scroll once the tile's
                    //     right edge is within `margin` px of the
                    //     rail's right edge.
                    //   • Same on the left side.
                    // Net effect: the first 3-4 cards stay anchored
                    // at the left, the cursor drifts to the right,
                    // and only the middle of the row "scrolls" the
                    // shelf at all.  The last card sits flush at the
                    // right edge — no center-pinning forever.
                    const cRect = hs.getBoundingClientRect();
                    const margin = Math.max(80, cRect.width * 0.18);
                    let delta = 0;
                    if (dir === 'right') {
                        if (rect.right > cRect.right - margin) {
                            delta = rect.right - (cRect.right - margin);
                        }
                    } else {
                        if (rect.left < cRect.left + margin) {
                            delta = rect.left - (cRect.left + margin);
                        }
                    }
                    if (Math.abs(delta) > 4) {
                        hs.scrollBy({ left: delta, behavior: scrollBehavior });
                    }
                }
                return;
            }

            const vs = verticalScroller(el) || document.scrollingElement;
            if (!vs) return;
            // The pin point must be expressed relative to the
            // VISIBLE area of the scroller, NOT the window.  When
            // the shelves region is a sub-container (e.g. Home's
            // `flex-1 overflow-y-auto` below the locked hero), its
            // top is not 0 — it might start at y=620.  Using
            // `window.innerHeight * 0.32` as the target would give
            // ~256 px, which is INSIDE the hero, so the container
            // would try to scroll upward to put content there but
            // can't, fighting itself and clipping the focused tile.
            //
            // Compute the scroller's own viewport rect and pin
            // inside that band instead.
            let scrollerTop;
            let scrollerHeight;
            if (
                vs === document.scrollingElement ||
                vs === document.body ||
                vs === document.documentElement
            ) {
                scrollerTop = 0;
                scrollerHeight = vh;
            } else {
                const sr = vs.getBoundingClientRect();
                scrollerTop = sr.top;
                scrollerHeight = sr.height;
            }
            const targetY = scrollerTop + scrollerHeight * VERTICAL_PIN_RATIO;
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
                if (next) {
                    focusEl(next, dir, repeat);
                } else if (dir === 'left') {
                    // No left-candidate found inside the content area.
                    // Reveal & focus the SideNav.  This is what makes
                    // the sidebar feel like Stremio TV / LeanBack —
                    // only the FAR-left press opens it; pressing Up
                    // from a shelf never accidentally jumps into the
                    // nav.  The nav already auto-expands on focus via
                    // its own onFocus handler.
                    const navItems = Array.from(
                        document.querySelectorAll(
                            '[data-testid="side-nav"] [data-focusable="true"]'
                        )
                    );
                    // If the user is already inside the side-nav, no
                    // further-left target exists — stay put.
                    const inNav = active.closest('[data-testid="side-nav"]');
                    if (!inNav && navItems.length > 0) {
                        focusEl(navItems[0], 'left', repeat);
                    }
                } else if (dir === 'up') {
                    // Already on the topmost focusable — snap the
                    // page (or its scroll region) to its absolute top.
                    const vs =
                        verticalScroller(active) || document.scrollingElement;
                    if (vs && vs.scrollTop > 0) {
                        vs.scrollTo({ top: 0, behavior: 'auto' });
                    }
                } else if (dir === 'right') {
                    // From the side-nav, pressing right should jump
                    // back into the content area (first non-nav
                    // focusable).
                    const inNav = active.closest('[data-testid="side-nav"]');
                    if (inNav) {
                        const all = focusables();
                        const firstContent = all.find(
                            (el) => !el.closest('[data-testid="side-nav"]')
                        );
                        if (firstContent) {
                            focusEl(firstContent, 'right', repeat);
                        }
                    }
                }
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

        // Initial focus: prefer `data-initial-focus`, but the hero
        // Play button mounts asynchronously after TMDB / Cinemeta
        // respond — so we retry over a 2 s window.  Only the FINAL
        // retry falls back to "first non-nav focusable"; earlier
        // retries strictly wait for the preferred element to appear.
        const tryPreferred = () => {
            const preferred = document.querySelector(
                '[data-focusable="true"][data-initial-focus="true"]'
            );
            if (!preferred) return false;
            preferred.focus({ preventScroll: true });
            setFocusAttr(preferred);
            return true;
        };

        const tryFallback = () => {
            // Only ever set a fallback if NOTHING is currently
            // focused — if the user already moved focus we leave
            // them alone.
            const ae = document.activeElement;
            if (
                ae &&
                ae.matches('[data-focusable="true"]') &&
                !ae.closest('[data-testid="side-nav"]')
            ) {
                setFocusAttr(ae);
                return;
            }
            const all = focusables();
            const firstContent = all.find(
                (el) => !el.closest('[data-testid="side-nav"]')
            );
            if (firstContent) {
                firstContent.focus({ preventScroll: true });
                setFocusAttr(firstContent);
            }
        };

        const timers = [];
        // Strict retries — only succeed once the preferred element
        // mounts.  After the strict window expires, fall back so
        // the user is never left with NO focused element.
        let preferredSet = false;
        [50, 200, 500, 1000, 1500].forEach((ms) => {
            timers.push(
                setTimeout(() => {
                    if (preferredSet) return;
                    if (tryPreferred()) preferredSet = true;
                }, ms)
            );
        });
        timers.push(
            setTimeout(() => {
                if (preferredSet) return;
                tryFallback();
            }, 1800)
        );

        window.addEventListener('keydown', onKey);
        return () => {
            window.removeEventListener('keydown', onKey);
            timers.forEach((t) => clearTimeout(t));
        };
    }, []);
}
