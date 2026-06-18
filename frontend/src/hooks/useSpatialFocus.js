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
        // v2.8.59 — Row-boundary selector.  Was hard-coded to
        // `[data-testid="shelf-page"]` (Vesper convention); now
        // ALSO matches the Tunes app's `.tunes-shelf`, `.tunes-section`
        // and `.tunes-tracklist` wrappers.  This is what makes UP /
        // DOWN navigate row-by-row inside the Music app without
        // touching 30+ JSX call sites.
        const ROW_PAGE =
            '[data-testid="shelf-page"], ' +
            '.tunes-shelf, .tunes-section, .tunes-tracklist';

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
            /* v2.10.46 — Focus-trap support.  When an overlay component
             * (e.g. `FullScreenPlayer`) wants ALL spatial-focus
             * navigation to be confined to its subtree, it stamps a
             * `data-focus-trap="true"` attribute on its root.  We
             * detect that here and scope the focusables query to
             * only descendants of the most-recently-added trap
             * (`lastElementChild` of the matching set).  Without
             * this the FullScreenPlayer was unreachable on the box:
             * the rail item that opened it kept focus, and pressing
             * arrow keys moved focus on the still-visible rail
             * behind the overlay rather than onto the in-overlay
             * controls. */
            const traps = document.querySelectorAll('[data-focus-trap="true"]');
            const scope = traps.length > 0 ? traps[traps.length - 1] : document;
            const all = scope.querySelectorAll('[data-focusable="true"]');
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

            // -------- FAST PATH: vertical nav inside the side-nav rail --------
            // v2.7.13 — strict DOM-sibling movement within the
            // side-nav so Up/Down can't accidentally yank focus
            // into a shelf tile that happens to be geometrically
            // closer (root cause of user-reported "border
            // disappears / jumps to a random tile" inside the
            // nav rail).
            if ((dir === 'up' || dir === 'down') && currentInNav) {
                const navRoot = current.closest(NAV_RAIL);
                if (navRoot) {
                    const navItems = Array.from(
                        navRoot.querySelectorAll('[data-focusable="true"]')
                    ).filter((el) => !el.hasAttribute('disabled'));
                    const idx = navItems.indexOf(current);
                    if (idx !== -1) {
                        const nextIdx = dir === 'down' ? idx + 1 : idx - 1;
                        if (nextIdx >= 0 && nextIdx < navItems.length) {
                            return navItems[nextIdx];
                        }
                        // Edge of nav — stop, don't leak into shelves.
                        return null;
                    }
                }
            }

            // -------- FAST PATH: vertical nav between shelf pages --------
            // v2.7.13 — strict directional navigation.  When the user
            // presses Up or Down from a tile that lives inside a
            // [data-testid="shelf-page"] (home / library / upcoming
            // layouts), we walk the DOM in document order to the
            // previous/next shelf-page and pick its bookmarked tile
            // (or its first focusable).  This bypasses ALL geometric
            // scoring, eliminating the user-reported bugs:
            //   • "skipping covers" — geometry picked a tile two
            //     rails away because it happened to be closer in
            //     pixel distance to the focused tile's centre
            //   • "jumping to menu for no reason" — geometry picked
            //     a nav-rail item when its perpendicular distance
            //     was less than the next shelf's
            //   • "border disappears" — focus was being set on an
            //     element that wasn't fully visible, so the
            //     :focus-visible / data-focused style didn't apply
            //     until the snap finished scrolling.  Now the focus
            //     target is always inside a snap page that the
            //     browser instantly snaps to, eliminating the
            //     transient invisible state.
            if (
                (dir === 'up' || dir === 'down') &&
                !currentInNav
            ) {
                const curPage = current.closest(ROW_PAGE);
                if (curPage) {
                    let targetPage = null;
                    if (dir === 'down') {
                        let n = curPage.nextElementSibling;
                        while (n) {
                            if (n.matches(ROW_PAGE)) {
                                targetPage = n;
                                break;
                            }
                            n = n.nextElementSibling;
                        }
                    } else {
                        let p = curPage.previousElementSibling;
                        while (p) {
                            if (p.matches(ROW_PAGE)) {
                                targetPage = p;
                                break;
                            }
                            p = p.previousElementSibling;
                        }
                    }
                    if (targetPage) {
                        // Prefer the rail's bookmarked tile (so up-
                        // then-down returns focus to the column the
                        // user was on), else fall back to the first
                        // focusable in the rail.
                        const rail = targetPage.querySelector(
                            '.vesper-shelf, [data-shelf-rail]'
                        );
                        let pick = null;
                        if (rail && rail.__lastFocusedKey) {
                            pick = rail.querySelector(
                                `[data-testid="${rail.__lastFocusedKey}"]`
                            );
                        }
                        if (!pick) {
                            pick = targetPage.querySelector(
                                '[data-focusable="true"]:not([disabled])'
                            );
                        }
                        if (pick) return pick;
                    }
                }

                /* v2.7.17 — Hero → first shelf-page fast-path.  When
                 * the user presses Down from a hero button (which is
                 * NOT inside a shelf-page), find the first shelf-page
                 * on screen and target its first focusable.  Without
                 * this, the geometric scorer was overshooting to a
                 * tile in the 2nd or 3rd shelf-page because chips
                 * tend to be small / off-axis vs the wider hero
                 * buttons. */
                if (
                    dir === 'down' &&
                    current.closest('[data-testid="hero-billboard"]')
                ) {
                    const region = document.querySelector(
                        '[data-testid="shelves-region"]'
                    );
                    if (region) {
                        const firstPage = region.querySelector(
                            ROW_PAGE
                        );
                        if (firstPage) {
                            const pick = firstPage.querySelector(
                                '[data-focusable="true"]:not([disabled])'
                            );
                            if (pick) return pick;
                        }
                    }
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
                /* v2.8.96 — opt-out: containers marked with
                   data-no-h-rail="true" (e.g. the FTA EPG grid) are
                   horizontally scrollable BUT we want up/down nav to
                   freely move between rows inside them.  Treating
                   them as a rail makes the geometric scorer exclude
                   siblings on other rows, which is why pressing
                   Down on an EPG cell was dropping focus into the
                   topbar.  Skipping this branch keeps left/right
                   nav working via the regular geometric path. */
                if (p.getAttribute && p.getAttribute('data-no-h-rail') === 'true') {
                    p = p.parentElement;
                    continue;
                }
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
            // v2.7.02 — document-wide sweep instead of only clearing
            // `lastFocused`.  Multiple components (Home.jsx,
            // Detail.jsx, Settings.jsx, WatchTogether.jsx) set
            // `data-focused="true"` directly on initial-focus
            // priming, but our `lastFocused` closure variable
            // doesn't know about those.  Without the sweep, those
            // attributes lingered on stale tiles and the user saw
            // TWO blue focus rings on screen at the same time —
            // one from the stale priming and one on the actually-
            // focused tile.  Sweep is O(n) over `[data-focused=
            // "true"]` matches which is at most a handful of
            // elements at any time, so it's free.
            const stale = document.querySelectorAll(
                '[data-focused="true"]'
            );
            for (let i = 0; i < stale.length; i++) {
                if (stale[i] !== el) {
                    stale[i].removeAttribute('data-focused');
                }
            }
            // Also clean up any stragglers from interrupted
            // long-presses or press-ripples on OTHER tiles — these
            // attributes drive their own box-shadow ring animations
            // and if the user starts a press then arrow-navs away
            // before keyup, the original tile keeps animating.
            const stragglers = document.querySelectorAll(
                '[data-holding="true"], [data-pressed="true"]'
            );
            for (let i = 0; i < stragglers.length; i++) {
                if (stragglers[i] !== el) {
                    stragglers[i].removeAttribute('data-holding');
                    stragglers[i].removeAttribute('data-pressed');
                }
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
            // Opt-out: containers marked with `data-no-row-snap` skip
            // the vertical row-pin scroll entirely.  Used by the
            // Detail page (Cast row, Recommendations row) where the
            // user wants the page to stay perfectly still and only
            // the focus state / backdrop swap on row change.
            const skipRowPin =
                el.closest('[data-no-row-snap="true"]') ||
                vs.closest?.('[data-no-row-snap="true"]');
            if (skipRowPin) return;

            /* v2.10.45 — Sticky-overlay offset.  If the scroll
             * container has a `position: sticky` element pinned at
             * its top (marked with `data-sticky-overlay="true"`),
             * the row-pin target must clear that overlay's BOTTOM
             * edge — otherwise focused rows get scrolled UNDER the
             * sticky hero and become invisible.  Bug reported on
             * the Artist page: the user pressed Down through the
             * "Popular" track list and the focused track kept
             * disappearing behind the Artist hero.  The focus ring
             * was "stuck" because the actually-focused element was
             * occluded by the hero overlay above it.
             *
             * We pick the LARGEST overlay rectangle inside the
             * scroll container that's currently visible at the top
             * (rect.top <= scrollerTop + 4 && rect.bottom > scrollerTop),
             * then add its rendered height as a top-padding for the
             * row-pin math. */
            let stickyOffset = 0;
            try {
                const overlays = vs.querySelectorAll
                    ? vs.querySelectorAll('[data-sticky-overlay="true"]')
                    : [];
                let scrollerTopForOverlay;
                if (
                    vs === document.scrollingElement ||
                    vs === document.body ||
                    vs === document.documentElement
                ) {
                    scrollerTopForOverlay = 0;
                } else {
                    scrollerTopForOverlay = vs.getBoundingClientRect().top;
                }
                for (let i = 0; i < overlays.length; i++) {
                    const orect = overlays[i].getBoundingClientRect();
                    // Overlay is "pinned at the top of the scroller"
                    // when its top edge is within a few pixels of
                    // the scroll container's top edge.
                    if (
                        orect.top <= scrollerTopForOverlay + 4 &&
                        orect.bottom > scrollerTopForOverlay
                    ) {
                        const height = orect.bottom - scrollerTopForOverlay;
                        if (height > stickyOffset) stickyOffset = height;
                    }
                }
            } catch { /* defensive — feature still works without offset */ }

            /* v2.7.19 — Snap-row fast-path.  When the focused tile
             * lives inside a `[data-testid="shelf-page"]` (Home's
             * snap-container layout), bypass the pixel-pin scroll
             * math entirely and let the browser's native
             * `scroll-snap-type: y mandatory` engine do the work.
             * We just call `scrollIntoView({block:'center'})` on
             * the snap-page parent — the browser commits the snap
             * on the next frame, instant cut, no slide, identical
             * behaviour for every row (Continue Watching, Networks,
             * For You, addon catalogues, Upcoming).  This is the
             * "treat every row the same way" guarantee the user
             * explicitly asked for. */
            const snapPage = el.closest(ROW_PAGE);
            if (snapPage) {
                try {
                    snapPage.scrollIntoView({
                        behavior: 'auto',
                        block: 'center',
                        inline: 'nearest',
                    });
                } catch { /* ignore */ }
                return;
            }

            // Pin the TOP edge of the focused row roughly a fifth of
            // the way down so the shelf eyebrow + title above it is
            // always visible AND the focus ring isn't clipped.
            // v2.10.45 — `stickyOffset` (computed above) pushes the
            // target line BELOW any pinned overlay (e.g. Artist
            // hero) so focused rows are never occluded by a sticky
            // header.  Falls back to the original 22%-of-viewport
            // logic when no sticky overlay is present.
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
            // Available content height below the sticky overlay.
            const availableHeight = Math.max(120, scrollerHeight - stickyOffset);
            const targetTop =
                scrollerTop + stickyOffset + Math.max(availableHeight * 0.12, 24);
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
                /* v2.7.15 — strict rule per user spec: Left at the
                 * left edge of a rail goes to the side-nav ONLY
                 * from the Continue Watching (first) shelf-page.
                 * From any other shelf (Networks, For You, addon
                 * catalogues, Upcoming Movies, etc.) Left STOPS —
                 * the focus stays on the leftmost tile.  Prevents
                 * the "I went into Popular Movies and now my focus
                 * is in the menu" surprise the user reported.
                 *
                 * v2.10.45 — EXCEPT for the Music app, where the
                 * user explicitly asked: "every row should be able
                 * to move across to the left-hand side rail, not
                 * just the top one — any row should be able to move
                 * to the rail".  The Music app's rail is narrower
                 * and self-collapsing, so the "accidental jump to
                 * menu" risk that motivated the Vesper restriction
                 * doesn't apply.  Detected via the music shell's
                 * `body[data-music-app="true"]` flag set in
                 * MusicLayout, plus the Tunes-root container as a
                 * fallback. */
                const navItems = document.querySelectorAll(
                    `${NAV_RAIL.split(',').map((s) => s.trim() + ' [data-focusable="true"]').join(', ')}`
                );
                const inNav = active.closest(NAV_RAIL);
                const isMusicApp =
                    document.body?.getAttribute('data-music-app') === 'true' ||
                    !!active.closest('.tunes-root');
                if (!inNav && navItems.length > 0) {
                    if (isMusicApp) {
                        // Music app: always allow Left → rail from
                        // any row, regardless of shelf position.
                        focusEl(navItems[0], 'left');
                    } else {
                        const curPage = active.closest(ROW_PAGE);
                        if (curPage) {
                            // Only allow nav escape from the FIRST shelf
                            // page (Continue Watching).  Detect by DOM
                            // order: no preceding shelf-page sibling.
                            let prev = curPage.previousElementSibling;
                            let isFirstShelf = true;
                            while (prev) {
                                if (prev.matches(ROW_PAGE)) {
                                    isFirstShelf = false;
                                    break;
                                }
                                prev = prev.previousElementSibling;
                            }
                            if (isFirstShelf) {
                                focusEl(navItems[0], 'left');
                            }
                            // Else: do nothing — focus stays put.
                        } else {
                            // No shelf-page ancestor (e.g. Library /
                            // Settings page) — preserve legacy escape
                            // behaviour so non-home pages still work.
                            focusEl(navItems[0], 'left');
                        }
                    }
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
                    /* v2.7.15 — Right-from-nav: jump to the CURRENTLY
                     * VISIBLE shelf-page's tile (bookmarked or first),
                     * not the document's first focusable.  Earlier we
                     * picked `focusables().find(el => !el.closest(NAV))`
                     * which always returned the Hero Play button at
                     * the top of Home — fine when not scrolled, but
                     * once the user had snap-scrolled down to e.g.
                     * "Popular Movies", Right would yank focus back
                     * to a Hero button that was off-screen and the
                     * `:focus-visible` styling never painted (user
                     * reported "focus border disappears").  Now we
                     * find the shelf-page whose centre intersects the
                     * viewport centre and target its bookmark/first
                     * focusable. */
                    const region = document.querySelector(
                        '[data-testid="shelves-region"]'
                    );
                    let pick = null;
                    if (region) {
                        const regionRect = region.getBoundingClientRect();
                        const probeY = regionRect.top + regionRect.height / 2;
                        const pages = Array.from(
                            region.querySelectorAll(ROW_PAGE)
                        );
                        const visible = pages.find((p) => {
                            const r = p.getBoundingClientRect();
                            return r.top <= probeY && r.bottom >= probeY;
                        }) || pages[0];
                        if (visible) {
                            const rail = visible.querySelector(
                                '.vesper-shelf, [data-shelf-rail]'
                            );
                            if (rail && rail.__lastFocusedKey) {
                                pick = rail.querySelector(
                                    `[data-testid="${rail.__lastFocusedKey}"]`
                                );
                            }
                            if (!pick) {
                                pick = visible.querySelector(
                                    '[data-focusable="true"]:not([disabled])'
                                );
                            }
                        }
                    }
                    if (!pick) {
                        // Off-home pages (Library, Settings, Detail)
                        // — fall back to the legacy "first content
                        // focusable" behaviour.
                        const all = focusables();
                        pick = all.find((el) => !el.closest(NAV_RAIL));
                    }
                    if (pick) {
                        focusEl(pick, 'right');
                    }
                }
            }
        };

        const onKey = (e) => {
            // If a component-level onKeyDown already handled this
            // press (and called preventDefault), bail out so we don't
            // double-move.  React's synthetic e.stopPropagation()
            // does NOT stop the native event from reaching this
            // window-level listener, so this defaultPrevented gate
            // is the only reliable signal.  Without it, e.g. the
            // HeroBillboard buttons handle Left/Right locally to
            // clamp focus within the action row, but the global
            // engine ALSO runs and yanks focus into adjacent
            // shelves / nav — producing the user's reported
            // "Right on hero jumps focus back up" bug.
            if (e.defaultPrevented) return;

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

            // Enter / Space — we ONLY swallow the default browser
            // behaviour here.  The actual click() fires on keyup so
            // a component's own onKeyDown / onKeyUp handler (e.g.,
            // hold-OK to favourite / set reminder) gets first crack
            // at consuming the press.  Without this split, a held
            // OK would fire BOTH the long-press action AND a click
            // on every repeat — playing the channel AND favouriting
            // it in one go.
            if (e.key === 'Enter' || e.key === ' ') {
                if (
                    document.activeElement &&
                    document.activeElement.matches('[data-focusable="true"]')
                ) {
                    e.preventDefault();
                    // Press-ripple feedback (CSS @keyframes).
                    if (!e.repeat) {
                        document.activeElement.setAttribute('data-pressed', 'true');
                    }
                }
            }
        };

        const onKeyUp = (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            const target = document.activeElement;
            if (!target || !target.matches('[data-focusable="true"]')) return;
            // Clear the press-ripple state.
            target.removeAttribute('data-pressed');
            setTimeout(() => {
                target.removeAttribute('data-pressed');
            }, 320);
            // If a component-level handler claimed the press as a
            // long-press, don't also dispatch a click.
            if (target.getAttribute('data-long-pressed') === 'true') {
                target.removeAttribute('data-long-pressed');
                return;
            }
            target.click();
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
        window.addEventListener('keyup', onKeyUp);
        return () => {
            window.removeEventListener('keydown', onKey);
            window.removeEventListener('keyup', onKeyUp);
            timers.forEach((t) => clearTimeout(t));
            observer.disconnect();
            if (invalidationTimer) cancelAnimationFrame(invalidationTimer);
        };
    }, []);
}
