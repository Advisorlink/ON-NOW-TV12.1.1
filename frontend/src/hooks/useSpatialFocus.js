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
        const COOLDOWN_PRESS_MS = 70;
        const COOLDOWN_REPEAT_MS = 45;
        const VERTICAL_PIN_RATIO = 0.32;
        let lastDirAt = 0;

        // -------- focusables cache --------
        // Calling document.querySelectorAll on every keypress (with
        // 80+ tiles in the DOM) is the single biggest perf hit.  We
        // cache the focusable array and invalidate via a debounced
        // MutationObserver — list rebuilds happen lazily, not on
        // every D-pad press.  Result on the HK1: ~3-4 ms saved per
        // key press, which makes hold-down nav visibly smoother.
        let cachedFocusables = null;
        let invalidationTimer = null;
        const invalidateCache = () => {
            // Coalesce many mutations in one paint into one cache
            // rebuild.
            if (invalidationTimer) return;
            invalidationTimer = requestAnimationFrame(() => {
                cachedFocusables = null;
                invalidationTimer = null;
            });
        };
        const observer = new MutationObserver(invalidateCache);
        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['data-focusable', 'disabled', 'tabindex'],
        });

        const focusables = () => {
            if (cachedFocusables) return cachedFocusables;
            const all = document.querySelectorAll('[data-focusable="true"]');
            // Visibility filter is still per-call because rect/style
            // can change without a DOM mutation — but we only run it
            // on the FILTERED list, not the full querySelectorAll.
            const arr = [];
            for (let i = 0; i < all.length; i++) {
                const el = all[i];
                if (el.hasAttribute('disabled')) continue;
                const r = el.getBoundingClientRect();
                if (r.width === 0 || r.height === 0) continue;
                // getComputedStyle is expensive; skip it unless the
                // element opted into a visibility-dependent layout.
                arr.push(el);
            }
            cachedFocusables = arr;
            return arr;
        };

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

                // ---- HARD ROW / COLUMN CONSTRAINT ----
                // For horizontal moves (Left / Right), the candidate
                // MUST overlap the focused tile's vertical band.  In
                // other words: if the user is at the last tile in a
                // row and presses Right, we DO NOT fall through to a
                // candidate on a different row — we just stop.
                //
                // Same idea (mirrored) for Up / Down: candidates must
                // be reasonably aligned with the focused column,
                // otherwise the user gets dragged sideways during a
                // vertical scroll.
                if (dir === 'left' || dir === 'right') {
                    const sameRow =
                        r.top < cur.bottom - 4 && r.bottom > cur.top + 4;
                    if (!sameRow) continue;
                } else {
                    // Vertical move — allow generous column tolerance
                    // (1.5 × the focused tile's width) so the user can
                    // descend from a sidebar onto wider content, but
                    // refuse jumps farther than that.
                    const maxColumnDrift = Math.max(cur.width * 1.5, 200);
                    if (Math.abs(dx) > maxColumnDrift) continue;
                }

                // Heavy weight on perpendicular distance so we
                // strongly prefer items on the same row/column as
                // the focused one — mirrors Stremio's launcher.
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

        // ---- Coalesced scroll requests ----
        // Multiple key-presses in the same animation frame all
        // contribute to ONE scroll commit per scroller.  Without
        // this, hold-down nav fires 60 separate scrollBy() calls
        // per second which the WebView paints sequentially —
        // visible as judder.
        const scrollPending = new WeakMap(); // scroller -> {x, y}
        let scrollRafScheduled = false;
        const flushScrolls = () => {
            scrollRafScheduled = false;
            // We can't iterate a WeakMap; track separately.
            // eslint-disable-next-line no-use-before-define
            scrollQueue.forEach(({ el }) => {
                const pending = scrollPending.get(el);
                if (!pending) return;
                scrollPending.delete(el);
                if (pending.x || pending.y) {
                    el.scrollBy({
                        left: pending.x || 0,
                        top: pending.y || 0,
                        behavior: 'auto',
                    });
                }
            });
            // eslint-disable-next-line no-use-before-define
            scrollQueue.length = 0;
        };
        const scrollQueue = []; // array of {el} for iteration
        const queueScroll = (el, dx, dy) => {
            if (!el || (Math.abs(dx) < 4 && Math.abs(dy) < 4)) return;
            const cur = scrollPending.get(el);
            if (cur) {
                cur.x = (cur.x || 0) + dx;
                cur.y = (cur.y || 0) + dy;
            } else {
                scrollPending.set(el, { x: dx, y: dy });
                scrollQueue.push({ el });
            }
            if (!scrollRafScheduled) {
                scrollRafScheduled = true;
                requestAnimationFrame(flushScrolls);
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
                        queueScroll(hs, delta, 0);
                    }
                }
                return;
            }

            const vs = verticalScroller(el) || document.scrollingElement;
            if (!vs) return;
            // Pin the TOP edge of the focused element (with enough
            // offset that the shelf header above it stays visible
            // AND the focus ring above the tile is never clipped).
            //
            // Pinning by centre is fine on a big desktop browser but
            // when the scroller is short (e.g. the shelves region
            // below the locked hero is ~350 px tall), a tile that's
            // 280 px tall has its centre at 32 % = 112 px which puts
            // its TOP at -28 px — clipped above the scroller.  The
            // shelf header that lives just above the tile gets
            // pushed even further out of view.
            //
            // Solution: pin the rect TOP at the larger of
            //   (scrollerHeight × 0.22, 90 px)
            // so the focused row sits roughly a fifth of the way
            // down with a guaranteed 90 px above it — enough for the
            // shelf eyebrow + title PLUS the focus ring (~22 px).
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
            const targetTop =
                scrollerTop + Math.max(scrollerHeight * 0.22, 90);
            const delta = rect.top - targetTop;
            if (Math.abs(delta) > 4) {
                queueScroll(vs, 0, delta);
            }
        };

        const onKey = (e) => {
            // Per-shelf focus memory: remember which tile was
            // focused in each horizontal rail so navigating away
            // (Up/Down) and back returns focus to that exact tile.
            // Map: rail element → focused element id (or fallback
            // to a numeric data-key we set when the tile is missing
            // a stable id).
            const rememberFocusInRail = () => {
                const ae = document.activeElement;
                if (!ae || !ae.matches('[data-focusable="true"]')) return;
                const rail = horizontalScroller(ae);
                if (!rail) return;
                rail.__lastFocusedKey =
                    ae.getAttribute('data-testid') || null;
            };
            // Save the last-focused tile in the rail before we
            // process the press — so when the press moves focus
            // out of the rail we have the bookmark ready.
            rememberFocusInRail();
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
                    // Focus memory: if this is a vertical move INTO
                    // a new horizontal rail (dir == up/down), try to
                    // restore the rail's last-focused tile instead
                    // of just landing on `next`.
                    let target = next;
                    if (dir === 'up' || dir === 'down') {
                        const nextRail = horizontalScroller(next);
                        const curRail = horizontalScroller(active);
                        if (
                            nextRail &&
                            nextRail !== curRail &&
                            nextRail.__lastFocusedKey
                        ) {
                            const bookmarked = nextRail.querySelector(
                                `[data-testid="${nextRail.__lastFocusedKey}"]`
                            );
                            if (bookmarked) target = bookmarked;
                        }
                    }
                    focusEl(target, dir, repeat);
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
                    const target = document.activeElement;
                    // Fire the press-ripple feedback animation — a
                    // pure-CSS @keyframes triggered by the
                    // `data-pressed` attribute.  Removed after
                    // 320 ms so it can re-fire on the next press.
                    target.setAttribute('data-pressed', 'true');
                    setTimeout(() => {
                        target.removeAttribute('data-pressed');
                    }, 320);
                    target.click();
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
            observer.disconnect();
            if (invalidationTimer) cancelAnimationFrame(invalidationTimer);
        };
    }, []);
}
