import { useEffect } from 'react';

/**
 * Spatial D-pad focus manager for TV.
 *
 * Listens for ArrowUp / ArrowDown / ArrowLeft / ArrowRight at the
 * window level and moves focus to the geometrically nearest
 * element marked with `data-focusable="true"`.
 *
 * Designed for buttery-smooth Android TV navigation:
 *   - Every keydown is processed SYNCHRONOUSLY (no rAF queue, no
 *     throttle).  The OS already auto-repeats at ~30 Hz which is
 *     the rate we want to honour 1:1 — anything slower feels
 *     "chunky".
 *   - Candidate set is scoped before geometry tests so populated
 *     For-You pages (~600 tiles) don't thrash layout per press.
 *   - Focusables list is cached and only rebuilt on DOM mutations.
 *   - scrollBy() calls are coalesced into one commit per paint so
 *     held-D-pad never paints 60 separate scrolls per second.
 *
 *   <button data-focusable="true" data-focus-style="tile" tabIndex={0}>
 */
export default function useSpatialFocus() {
    useEffect(() => {
        const NAV_RAIL = '[data-testid="side-nav"], [data-testid="kids-side-nav"]';

        // -------- focusables cache --------
        // Calling document.querySelectorAll on every keypress (with
        // 80+ tiles in the DOM) is the single biggest perf hit.
        // Cache the focusable array and invalidate via a debounced
        // MutationObserver.
        let cachedFocusables = null;
        let cacheGen = 0;
        let invalidationTimer = null;
        const invalidateCache = () => {
            if (invalidationTimer) return;
            invalidationTimer = requestAnimationFrame(() => {
                cachedFocusables = null;
                cacheGen++;
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
            const arr = [];
            for (let i = 0; i < all.length; i++) {
                const el = all[i];
                if (el.hasAttribute('disabled')) continue;
                const r = el.getBoundingClientRect();
                if (r.width === 0 || r.height === 0) continue;
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
            const currentInNav = !!current.closest(NAV_RAIL);

            // -------- FAST PATH: horizontal nav within a rail --------
            // When the user is moving Left/Right inside a horizontal
            // shelf, the "next" tile is simply the next focusable
            // DOM sibling in the same rail.  Skip ALL geometry — no
            // getBoundingClientRect loop, no scoring.  This is what
            // makes the Profile-select screen feel buttery (its
            // tiles are flex siblings with no scroll); we now apply
            // the same shortcut to home shelves.
            const curRail = currentInNav ? null : horizontalScroller(current);
            if ((dir === 'left' || dir === 'right') && curRail) {
                let list = curRail.__sfChildFocusables;
                if (!list || curRail.__sfChildFocusablesGen !== cacheGen) {
                    list = Array.from(
                        curRail.querySelectorAll('[data-focusable="true"]')
                    ).filter((el) => !el.hasAttribute('disabled'));
                    curRail.__sfChildFocusables = list;
                    curRail.__sfChildFocusablesGen = cacheGen;
                }
                const idx = list.indexOf(current);
                if (idx !== -1) {
                    const nextIdx = dir === 'right' ? idx + 1 : idx - 1;
                    if (nextIdx >= 0 && nextIdx < list.length) {
                        return list[nextIdx];
                    }
                    // Edge of rail — return null in BOTH directions
                    // so applyMove's edge fallback can take over.
                    // For LEFT this is critical: the geometry path
                    // would otherwise pick whichever nav item is
                    // vertically nearest (often Autoplay at the
                    // bottom), but the user expects Left from a
                    // shelf to ALWAYS land on Home (top of nav).
                    // applyMove handles that explicitly below.
                    return null;
                }
            }

            const cur = current.getBoundingClientRect();
            const c = center(cur);

            // -------- candidate scoping (geometry path) --------
            //   LEFT  — for hopping from rail's left edge to side-nav.
            //   UP / DOWN — everything except the current rail,
            //               constrained to a 1200px vertical band.
            const all = focusables();
            let scoped;
            if (dir === 'left' || dir === 'right') {
                if (curRail) {
                    // Only side-nav items reachable from rail edge.
                    scoped = [];
                    for (let i = 0; i < all.length; i++) {
                        const el = all[i];
                        if (el === current) continue;
                        if (el.closest(NAV_RAIL)) scoped.push(el);
                    }
                } else {
                    scoped = [];
                    const yMin = cur.top - 80;
                    const yMax = cur.bottom + 80;
                    for (let i = 0; i < all.length; i++) {
                        const el = all[i];
                        if (el === current) continue;
                        const r = el.getBoundingClientRect();
                        if (r.bottom < yMin || r.top > yMax) continue;
                        scoped.push(el);
                    }
                }
            } else {
                const VBAND = 1200;
                scoped = [];
                for (let i = 0; i < all.length; i++) {
                    const el = all[i];
                    if (el === current) continue;
                    if (curRail && curRail.contains(el)) continue;
                    const r = el.getBoundingClientRect();
                    if (dir === 'down') {
                        if (r.top < cur.bottom - 20) continue;
                        if (r.top > cur.bottom + VBAND) continue;
                    } else {
                        if (r.bottom > cur.top + 20) continue;
                        if (r.bottom < cur.top - VBAND) continue;
                    }
                    el.__sfRect = r;
                    scoped.push(el);
                }
            }

            // SideNav permission filter (cheap, runs on already-small
            // set).
            const candidates = scoped.filter((el) => {
                const inNav = !!el.closest(NAV_RAIL);
                if (!currentInNav && inNav) {
                    return dir === 'left';
                }
                if (currentInNav && !inNav) {
                    return dir === 'right' || dir === 'up' || dir === 'down';
                }
                return true;
            });

            let best = null;
            let bestScore = Infinity;
            let fallback = null;
            let fallbackScore = Infinity;

            for (const el of candidates) {
                const r = el.__sfRect || el.getBoundingClientRect();
                el.__sfRect = undefined;
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

                let strict = true;
                if (dir === 'left' || dir === 'right') {
                    const sameRow =
                        r.top < cur.bottom - 4 && r.bottom > cur.top + 4;
                    if (!sameRow) continue;
                } else {
                    const maxColumnDrift = Math.max(cur.width * 1.5, 200);
                    const overlapsCol =
                        r.left <= cur.right && r.right >= cur.left;
                    if (Math.abs(dx) > maxColumnDrift && !overlapsCol) {
                        strict = false;
                    }
                }

                const score = primary + perpendicular * 3;
                if (strict) {
                    if (score < bestScore) {
                        bestScore = score;
                        best = el;
                    }
                } else {
                    const fbScore = primary + perpendicular * 0.5;
                    if (fbScore < fallbackScore) {
                        fallbackScore = fbScore;
                        fallback = el;
                    }
                }
            }
            return best || fallback;
        };

        // Cached per-element scroller lookups.  The getComputedStyle
        // walk is one of the more expensive ops per keystroke, so
        // we memoise on the element itself.
        const verticalScroller = (el) => {
            if (!el) return null;
            if (el.__sfVRail !== undefined) return el.__sfVRail;
            let p = el.parentElement;
            while (p && p !== document.body) {
                const cs = getComputedStyle(p);
                const oy = cs.overflowY;
                if (
                    (oy === 'auto' || oy === 'scroll') &&
                    p.scrollHeight > p.clientHeight
                ) {
                    el.__sfVRail = p;
                    return p;
                }
                p = p.parentElement;
            }
            el.__sfVRail = null;
            return null;
        };

        const horizontalScroller = (el) => {
            if (!el) return null;
            if (el.__sfHRail !== undefined) return el.__sfHRail;
            let p = el.parentElement;
            while (p && p !== document.body) {
                const cs = getComputedStyle(p);
                const ox = cs.overflowX;
                if (
                    (ox === 'auto' || ox === 'scroll') &&
                    p.scrollWidth > p.clientWidth
                ) {
                    el.__sfHRail = p;
                    return p;
                }
                p = p.parentElement;
            }
            el.__sfHRail = null;
            return null;
        };

        // `data-focused` mirrors :focus-visible for Android WebView
        // where programmatic focus doesn't always trigger the
        // pseudo-class.
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

        // ---- Coalesced scrolls ----
        // Multiple scrollBy()s in the same frame collapse into one
        // commit per scroller.  Held-D-pad without this paints 30
        // separate scrolls per second — visible judder.
        const scrollPending = new WeakMap();
        const scrollQueue = [];
        let scrollRafScheduled = false;
        const flushScrolls = () => {
            scrollRafScheduled = false;
            for (let i = 0; i < scrollQueue.length; i++) {
                const el = scrollQueue[i];
                const p = scrollPending.get(el);
                if (!p) continue;
                scrollPending.delete(el);
                if (p.x || p.y) {
                    el.scrollBy({
                        left: p.x || 0,
                        top: p.y || 0,
                        behavior: 'auto',
                    });
                }
            }
            scrollQueue.length = 0;
        };
        const queueScroll = (el, dx, dy) => {
            if (!el || (Math.abs(dx) < 4 && Math.abs(dy) < 4)) return;
            const cur = scrollPending.get(el);
            if (cur) {
                cur.x = (cur.x || 0) + dx;
                cur.y = (cur.y || 0) + dy;
            } else {
                scrollPending.set(el, { x: dx, y: dy });
                scrollQueue.push(el);
            }
            if (!scrollRafScheduled) {
                scrollRafScheduled = true;
                requestAnimationFrame(flushScrolls);
            }
        };

        const focusEl = (el, dir) => {
            if (!el) return;
            // Snap-to-top when crossing scroll containers so the new
            // tile is fully visible from the start.
            const prevVs = lastFocused
                ? verticalScroller(lastFocused)
                : null;
            const nextVs = verticalScroller(el);
            const crossingScrollers =
                nextVs && nextVs !== prevVs && nextVs !== document.scrollingElement;
            if (crossingScrollers) {
                try { nextVs.scrollTop = 0; } catch { /* ignore */ }
            }

            el.focus({ preventScroll: true });
            setFocusAttr(el);

            const rect = el.getBoundingClientRect();
            const vh = window.innerHeight;

            if (dir === 'left' || dir === 'right') {
                const hs = horizontalScroller(el);
                if (hs) {
                    // EDGE-COMFORT scroll — first cards stay anchored
                    // left, cursor drifts naturally across the row,
                    // shelf only scrolls when tile approaches the
                    // rail's edge.  Matches Apple TV / Google TV.
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
            // Pin the TOP edge of the focused row roughly a fifth of
            // the way down so the shelf eyebrow + title above it is
            // always visible AND the focus ring isn't clipped.
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

        // --- Move primitive ------------------------------------
        // Applies a single directional move from the currently
        // focused element.  Called SYNCHRONOUSLY from the keydown
        // handler — no queueing, no rAF latency.  This is the
        // "before Dev Mode" behaviour the user said felt perfect.
        const applyMove = (dir) => {
            const active =
                document.activeElement &&
                document.activeElement.matches('[data-focusable="true"]')
                    ? document.activeElement
                    : focusables()[0];
            if (!active) return;
            // Remember which tile was focused in this rail so when
            // we navigate Up/Down and come back, focus returns to
            // the same column.
            const curRailForBookmark = horizontalScroller(active);
            if (curRailForBookmark) {
                curRailForBookmark.__lastFocusedKey =
                    active.getAttribute('data-testid') || null;
            }

            const next = findNext(active, dir);
            if (next) {
                let target = next;
                if (dir === 'up' || dir === 'down') {
                    const nextRail = horizontalScroller(next);
                    if (
                        nextRail &&
                        nextRail !== curRailForBookmark &&
                        nextRail.__lastFocusedKey
                    ) {
                        const bookmarked = nextRail.querySelector(
                            `[data-testid="${nextRail.__lastFocusedKey}"]`
                        );
                        if (bookmarked) target = bookmarked;
                    }
                }
                focusEl(target, dir);
                return;
            }

            // Edge-of-page fallbacks
            if (dir === 'left') {
                const navItems = document.querySelectorAll(
                    `${NAV_RAIL.split(',').map((s) => s.trim() + ' [data-focusable="true"]').join(', ')}`
                );
                const inNav = active.closest(NAV_RAIL);
                if (!inNav && navItems.length > 0) {
                    focusEl(navItems[0], 'left');
                }
            } else if (dir === 'up') {
                const vs = verticalScroller(active) || document.scrollingElement;
                if (vs && vs.scrollTop > 0) {
                    vs.scrollTo({ top: 0, behavior: 'auto' });
                }
            } else if (dir === 'down') {
                // Force-scroll to mount the next Lazy shelf, then
                // retry on the following frame.
                const vs = verticalScroller(active) || document.scrollingElement;
                if (!vs) return;
                const before = vs.scrollTop;
                const chunk = Math.max(
                    260,
                    Math.round((vs.clientHeight || 600) * 0.55)
                );
                vs.scrollTo({ top: before + chunk, behavior: 'auto' });
                if (vs.scrollTop === before) return;
                requestAnimationFrame(() => {
                    requestAnimationFrame(() => {
                        cachedFocusables = null;
                        const retry = findNext(active, 'down');
                        if (retry) {
                            let target = retry;
                            const nextRail = horizontalScroller(retry);
                            if (
                                nextRail &&
                                nextRail !== curRailForBookmark &&
                                nextRail.__lastFocusedKey
                            ) {
                                const bookmarked = nextRail.querySelector(
                                    `[data-testid="${nextRail.__lastFocusedKey}"]`
                                );
                                if (bookmarked) target = bookmarked;
                            }
                            focusEl(target, 'down');
                        }
                    });
                });
            } else if (dir === 'right') {
                const inNav = active.closest(NAV_RAIL);
                if (inNav) {
                    const all = focusables();
                    const firstContent = all.find(
                        (el) => !el.closest(NAV_RAIL)
                    );
                    if (firstContent) {
                        focusEl(firstContent, 'right');
                    }
                }
            }
        };

        const onKey = (e) => {
            // While typing in an input/textarea, let LEFT/RIGHT move
            // the text cursor natively, but forward UP/DOWN to the
            // spatial focus so the user can D-pad out of the input
            // into surrounding focusables (e.g. name field → avatar
            // grid below).  Enter is consumed natively by inputs
            // (form submit / commit).  Escape still blurs.  Typing,
            // Backspace, Tab, etc. always reach the input.
            const tag = (document.activeElement?.tagName || '').toLowerCase();
            if (tag === 'input' || tag === 'textarea') {
                if (e.key === 'Escape') {
                    document.activeElement.blur();
                    return;
                }
                if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') {
                    // Native text editing — let it through.
                    return;
                }
                // ArrowUp / ArrowDown: fall through into the
                // spatial logic below.  The input IS the
                // activeElement and has data-focusable="true", so
                // findNext(input, dir) finds the next focusable in
                // that direction (avatar tile, PIN box, etc.) and
                // focus() implicitly blurs the input.
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
                // Synchronous move.  Every press — discrete or
                // repeat — produces exactly one move call.  No
                // queue, no throttle, no dropped inputs.
                applyMove(dir);
                return;
            }

            if (e.key === 'Enter' || e.key === ' ') {
                if (
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                ) {
                    e.preventDefault();
                    const target = document.activeElement;
                    // Press-ripple feedback (CSS @keyframes).
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
        // respond — so we retry over a 1.8 s window before falling
        // back to "first non-nav focusable".
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
            const ae = document.activeElement;
            if (
                ae &&
                ae.matches('[data-focusable="true"]') &&
                !ae.closest(NAV_RAIL)
            ) {
                setFocusAttr(ae);
                return;
            }
            const all = focusables();
            const firstContent = all.find((el) => !el.closest(NAV_RAIL));
            if (firstContent) {
                firstContent.focus({ preventScroll: true });
                setFocusAttr(firstContent);
            }
        };

        const timers = [];
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
