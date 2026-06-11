import React, { useEffect, useMemo, useState } from 'react';
import { useLocation } from 'react-router-dom';
import SideNav from '@/components/SideNav';
import DPadHint from '@/components/DPadHint';
import HeroBillboard from '@/components/HeroBillboard';
import Shelf from '@/components/Shelf';
import NetworksShelf from '@/components/NetworksShelf';
import ContinueWatchingShelf from '@/components/ContinueWatchingShelf';
import ForYouShelf from '@/components/ForYouShelf';
import UpcomingMoviesShelf from '@/components/UpcomingMoviesShelf';
import TabGridView from '@/components/TabGridView';
import FullscreenButton from '@/components/FullscreenButton';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useHomeBackHandler from '@/hooks/useHomeBackHandler';
import { useAddons } from '@/hooks/useAddons';
import { useLiveShelves } from '@/hooks/useLiveShelves';
import { useLiveHeroes } from '@/hooks/useLiveHeroes';
import Lazy from '@/components/Lazy';
import { getEntries as listContinueWatching } from '@/lib/continueWatching';
import { getViewingStyle } from '@/lib/viewingStyle';
import useIsMobile from '@/lib/useIsMobile';

export default function Home() {
    useSpatialFocus();
    const { addons } = useAddons();
    const location = useLocation();
    const isMobile = useIsMobile();

    const filter = new URLSearchParams(location.search).get('filter');
    const isFilterView = filter === 'movie' || filter === 'series';

    useHomeBackHandler(isFilterView ? 'home-filter' : 'home-root');

    const itemsPerCatalog = isFilterView ? 60 : 18;
    // On the filter views (Movies / TV Shows tabs) we DON'T pull
    // live shelves any more — those views now use their own
    // dedicated, parallel-fetch hook (useTabCatalog) inside
    // TabGridView.  Skipping this hook here removes a heavy
    // duplicate fetch that was making the box load slowly.
    const shelfFilter = isFilterView ? null : null;
    const heroType = filter === 'movie' ? 'movie' : 'series';

    // Stable empty-array reference.  CRITICAL: a fresh `[]` on
    // every render would invalidate the `addons` dep of the live
    // shelves / heroes hooks and trigger React's "Maximum update
    // depth" guard during tab swaps.  Memoising once keeps the
    // reference identical between renders.
    const EMPTY = React.useMemo(() => [], []);
    const addonsForHome = isFilterView ? EMPTY : addons;

    const { shelves: liveShelves, loading: liveLoading } = useLiveShelves(
        addonsForHome,
        shelfFilter,
        itemsPerCatalog
    );
    const { heroes: liveHeroes } = useLiveHeroes(
        addonsForHome,
        heroType
    );

    // (Prefetch removed.)  Earlier we ran two extra `useTabCatalog`
    // calls here to warm the cache, but they were sharing setState
    // signal-paths with the real call inside TabGridView and the
    // box was hitting React's "Maximum update depth" guard during
    // tab swaps.  The single in-grid call already covers the user
    // path: click TV Shows → TabGridView mounts with type=series
    // → catalog fetch fires.  Going back to Movies after that
    // round-trip is instant from the cache layer (sessionStorage
    // hit).

    const shelves = useMemo(() => {
        const all = Array.isArray(liveShelves) ? liveShelves : [];
        // User explicitly wants the home rails locked to exactly 4
        // shelves below Networks: Movies Popular · Series Popular ·
        // Movies New · Series New.  Every other addon-driven shelf
        // (Trending / Anime / Channels / etc.) is stripped so the
        // page renders quickly on the HK1 box and stays focused on
        // the "essential 4".  Catalog ids `-top` and `-year` are
        // the Cinemeta-style conventions; we match by suffix so
        // any swappable addon that ships those catalogues still
        // surfaces them.
        //
        // v2.8.88 — Dev Unlock (Settings → Unlock testing) bypasses
        // the "essential 4" filter so the user can see every
        // installed add-on's catalogues (IPTV, anime, channels, etc.)
        // as additional rows beneath the trailer/picks section.
        // Turning the toggle OFF reverts to the locked 4-row layout.
        let devUnlock = false;
        try { devUnlock = localStorage.getItem('onnowtv-dev-unlock') === '1'; }
        catch { /* ignore */ }

        const wanted = [
            { suffix: '-movie-year',  eyebrow: 'MOVIES',   title: 'New movies' },
            { suffix: '-series-year', eyebrow: 'SERIES',   title: 'New series' },
            { suffix: '-movie-top',   eyebrow: 'MOVIES',   title: 'Popular movies' },
            { suffix: '-series-top',  eyebrow: 'SERIES',   title: 'Popular series' },
        ];
        const out = [];
        const claimedIds = new Set();
        for (const w of wanted) {
            const match = all.find((s) => s.id && s.id.endsWith(w.suffix));
            if (match) {
                out.push({
                    ...match,
                    title: w.title,
                    eyebrow: w.eyebrow,
                });
                claimedIds.add(match.id);
            }
        }
        if (devUnlock) {
            // Append every other live shelf the addons returned —
            // these are the user's installed catalogues (IPTV,
            // anime, channels, etc.) the locked layout normally
            // hides.  Skip ones we've already shown above.
            for (const s of all) {
                if (claimedIds.has(s.id)) continue;
                out.push(s);
            }
        }
        return out;
    }, [liveShelves]);

    // Reset the scrollable shelves region to the top whenever the
    // filter changes (so a new heading sits flush at the top of the
    // shelves area).  Hero stays put as it isn't part of the scroll.
    React.useLayoutEffect(() => {
        const region = document.querySelector(
            '[data-testid="shelves-region"]'
        );
        if (region) region.scrollTop = 0;
        const main = document.querySelector('[data-testid="home-main"]');
        if (main) main.scrollTop = 0;
    }, [filter]);

    // Initial focus on the FIRST tile of the FIRST VISIBLE shelf.
    // User feedback v2.6.96: "any time you get off the menu or push
    // the home button in the side rail it needs to focus the first
    // card in the row that is showing at the time" — i.e. respect
    // the current scroll position so we don't yank the user back to
    // the top of the page.  We walk the shelves and grab the first
    // focusable whose bounding rect is at least partially within
    // the viewport.  Falls back to the first focusable in the page
    // when nothing is visible (initial mount, before paint).
    React.useEffect(() => {
        let cancelled = false;
        const trySetFocus = () => {
            if (cancelled) return false;
            const target =
                document.querySelector(
                    `[data-testid="tab-grid-list-${filter}"]`
                ) ||
                document.querySelector('[data-testid="shelves-region"]');
            if (!target) return false;
            const focusables = Array.from(
                target.querySelectorAll('[data-focusable="true"]')
            );
            if (!focusables.length) return false;
            const vh = window.innerHeight;
            // Pick the first focusable whose middle Y lies in
            // [120, vh - 80] — gives a generous "visible row"
            // window that excludes the top hero & bottom footer.
            const first = focusables.find((el) => {
                const r = el.getBoundingClientRect();
                const mid = r.top + r.height / 2;
                return mid > 120 && mid < vh - 80 && r.width > 0;
            }) || focusables[0];
            try {
                first.focus({ preventScroll: true });
            } catch {
                /* ignore */
            }
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== first) el.removeAttribute('data-focused');
                });
            first.setAttribute('data-focused', 'true');
            return true;
        };
        const timers = [80, 250, 600, 1200, 2200, 3500, 5000, 7500].map((ms) =>
            setTimeout(() => {
                if (trySetFocus()) {
                    // Cancel remaining retries once we've succeeded.
                    timers.forEach((t) => clearTimeout(t));
                }
            }, ms)
        );
        return () => {
            cancelled = true;
            timers.forEach((t) => clearTimeout(t));
        };
        // Re-run when filter swaps so the first tile of the new
        // filtered view (movies-only or series-only) gets focus too.
    }, [filter]);

    // Row-aware D-pad Up/Down for Home.
    //
    // Each shelf (Continue Watching / For You / Networks / live
    // shelves) is one "row".  The hero billboard is the row above
    // them all.  Pressing Down/Up MUST walk from one row to the
    // next regardless of which horizontal column the user is on
    // — never sideways, never jumping back to the hero's
    // "More info" button just because nothing is geometrically
    // directly above the focused tile.
    React.useEffect(() => {
        if (isFilterView) return undefined;

        /* v2.10.45 — Restored the "buttery D-pad" implementation and
         * fixed the focus jumping the user reported:
         *
         * 1.  ROWS ARE CACHED across keypresses and only rebuilt on
         *     relevant DOM mutations (shelves mount/unmount, tiles
         *     flip enabled/disabled).  The uncached version did a
         *     full `querySelectorAll` + per-tile layout pass on
         *     EVERY keypress (~100 forced layouts) which made held
         *     D-pad feel chunky on the HK1.
         *
         * 2.  `focus({ preventScroll: true })` + ONE explicit
         *     scrollIntoView on the snap-page parent.  The previous
         *     `preventScroll: false` ran the browser's implicit
         *     focus-scroll AND the manual snap-scroll on every
         *     press — two scrolls fighting over a scroll-snap
         *     container is exactly the "jumping top to bottom"
         *     the user reported. */
        let cachedRows = null;
        const invalidateRows = () => { cachedRows = null; };
        const buildRows = () => {
            const homeRoot = document.querySelector('[data-testid="home-page"]');
            if (!homeRoot) return [];
            const heroFocusables = Array.from(
                homeRoot.querySelectorAll(
                    '[data-testid="hero-billboard"] [data-focusable="true"]'
                )
            ).filter((el) => !el.hasAttribute('disabled'));
            const shelfNodes = Array.from(
                homeRoot.querySelectorAll(
                    '[data-testid="shelves-region"] > section, ' +
                    '[data-testid="shelves-region"] > [data-testid="for-you-shelf"] section, ' +
                    '[data-testid="shelves-region"] > div > a[data-focusable="true"]'
                )
            );
            const rows = [];
            if (heroFocusables.length) rows.push(heroFocusables);
            for (const node of shelfNodes) {
                const list = node.matches('[data-focusable="true"]')
                    ? [node]
                    : Array.from(
                          node.querySelectorAll('[data-focusable="true"]')
                      );
                const list2 = list.filter((el) => !el.hasAttribute('disabled'));
                if (list2.length) rows.push(list2);
            }
            return rows;
        };
        // Invalidate the cached row list on relevant DOM changes —
        // new shelves loading, tiles enabling/disabling.  We do NOT
        // invalidate on `data-focused` attribute flips (the focus
        // ring) because those happen on every move and would defeat
        // the cache.
        const homeRootForObs = document.querySelector('[data-testid="home-page"]');
        const obs = new MutationObserver(invalidateRows);
        if (homeRootForObs) {
            obs.observe(homeRootForObs, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['data-focusable', 'disabled', 'tabindex'],
            });
        }

        const onKey = (e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            const homeRoot = document.querySelector('[data-testid="home-page"]');
            if (!homeRoot) return;
            const active = document.activeElement;
            if (!active || !homeRoot.contains(active)) return;

            // Bail when focus is inside the SideNav.  The SideNav
            // is its OWN navigation universe — Up/Down should walk
            // its menu items (handled by the global spatial focus
            // engine), not jump out of the menu into the home
            // rails.  Once the user navigates AWAY from the menu
            // (clicks an item or presses Right) the menu collapses
            // and this row-walker takes over again.
            if (active.closest('[data-testid="side-nav"]')) return;

            // Build/reuse the cached rows list.
            if (!cachedRows) cachedRows = buildRows();
            const rows = cachedRows;
            if (rows.length === 0) return;

            // Which row is the user currently on?  Array.includes is
            // cheap; geometry only as a rare fallback (focus on a
            // tile that mounted between mutations).
            let curRowIdx = -1;
            for (let i = 0; i < rows.length; i++) {
                if (rows[i].includes(active)) {
                    curRowIdx = i;
                    break;
                }
            }
            if (curRowIdx === -1) {
                // Stale cache (focused tile not in rows) — rebuild
                // once before falling back to geometry.
                cachedRows = null;
                const fresh = buildRows();
                cachedRows = fresh;
                for (let i = 0; i < fresh.length; i++) {
                    if (fresh[i].includes(active)) {
                        curRowIdx = i;
                        break;
                    }
                }
                if (curRowIdx === -1) {
                    const activeY = active.getBoundingClientRect().top;
                    let best = 0;
                    let bestDy = Infinity;
                    for (let i = 0; i < fresh.length; i++) {
                        const dy = Math.abs(
                            fresh[i][0].getBoundingClientRect().top - activeY
                        );
                        if (dy < bestDy) {
                            bestDy = dy;
                            best = i;
                        }
                    }
                    curRowIdx = best;
                }
            }
            const rowsNow = cachedRows;

            const targetIdx =
                e.key === 'ArrowDown' ? curRowIdx + 1 : curRowIdx - 1;
            if (targetIdx < 0 || targetIdx >= rowsNow.length) return; // edge

            // Pick the tile on the target row closest to the
            // current X column so the user stays in the same
            // visual column when scrolling rails up/down.  Two
            // getBoundingClientRect calls per target-row tile —
            // orders of magnitude less than a full-page scan.
            const curRect = active.getBoundingClientRect();
            const curX = curRect.left + curRect.width / 2;
            const target = rowsNow[targetIdx].reduce((best, el) => {
                const r = el.getBoundingClientRect();
                const dx = Math.abs(r.left + r.width / 2 - curX);
                if (!best || dx < best.dx) return { el, dx };
                return best;
            }, null);
            if (!target) return;

            e.preventDefault();
            e.stopPropagation();
            try { target.el.focus({ preventScroll: true }); } catch { /* ignore */ }
            target.el.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== target.el) el.removeAttribute('data-focused');
                });
            /* v2.7.11 — scroll the SHELF PAGE (snap target), not
             * the inner section.  Earlier code scrolled the
             * section with `behavior: 'smooth'`, which fought
             * scroll-snap and caused the disappearing-border /
             * focus-jumps-to-top bugs the user reported.  Now we
             * scroll-snap to the parent ShelfPage instantly
             * (`auto`); the browser commits to the snap target on
             * the next frame, no animation, no jitter. */
            const pageAncestor = target.el.closest(
                '[data-testid="shelf-page"]'
            );
            const toScroll = pageAncestor || target.el;
            try {
                toScroll.scrollIntoView({
                    behavior: 'auto',
                    block: 'center',
                    inline: 'nearest',
                });
            } catch { /* ignore */ }
        };

        window.addEventListener('keydown', onKey, true);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            obs.disconnect();
        };
    }, [isFilterView]);

    /* v2.7.10 — measure the EXACT pixel height available for one
     * shelf "page" so CSS scroll-snap behaves identically on every
     * device.  The previous calc(100dvh - 480px) approach relied
     * on the HK1 WebView reporting `dvh` correctly + hero matching
     * the static 480px; both assumptions failed in practice and
     * users saw two/three shelves bleeding through at once. */
    const [shelfPageHeight, setShelfPageHeight] = useState(600);
    useEffect(() => {
        const compute = () => {
            const heroEl = document.querySelector(
                '[data-testid="hero-billboard"]'
            );
            const heroH = heroEl ? heroEl.offsetHeight : 480;
            const wh = window.innerHeight;
            setShelfPageHeight(Math.max(320, wh - heroH));
        };
        compute();
        const t1 = setTimeout(compute, 80);
        const t2 = setTimeout(compute, 400);
        const t3 = setTimeout(compute, 1200);
        window.addEventListener('resize', compute);
        return () => {
            clearTimeout(t1);
            clearTimeout(t2);
            clearTimeout(t3);
            window.removeEventListener('resize', compute);
        };
    }, []);

    /* v2.7.17 — Avoid rendering empty ShelfPage wrappers for the
     * Continue Watching and "Similar to what you like" rails when
     * the profile has no CW history and no viewing-style picks.
     * Without this, a brand-new profile lands on Home with TWO
     * blank 600 px snap-pages between the hero and the first
     * visible row (Networks), and the spatial-nav engine loses
     * focus stepping into them.  User report (video): "When we
     * start a new profile and there is no continue watching yet,
     * the rows either start with Networks or similar to what I
     * like section." */
    const [hasCW, setHasCW] = useState(() => {
        try { return listContinueWatching().length > 0; }
        catch { return false; }
    });
    const [hasViewingStyle, setHasViewingStyle] = useState(() => {
        try {
            const vs = getViewingStyle();
            return vs.movieGenres.length > 0 || vs.tvGenres.length > 0 || vs.items.length > 0;
        } catch { return false; }
    });
    useEffect(() => {
        const refresh = () => {
            try { setHasCW(listContinueWatching().length > 0); }
            catch { setHasCW(false); }
            try {
                const vs = getViewingStyle();
                setHasViewingStyle(
                    vs.movieGenres.length > 0
                    || vs.tvGenres.length > 0
                    || vs.items.length > 0
                );
            } catch { setHasViewingStyle(false); }
        };
        refresh();
        window.addEventListener('vesper:profile-change', refresh);
        window.addEventListener('vesper:viewing-style-change', refresh);
        window.addEventListener('vesper:cw-change', refresh);
        window.addEventListener('storage', refresh);
        return () => {
            window.removeEventListener('vesper:profile-change', refresh);
            window.removeEventListener('vesper:viewing-style-change', refresh);
            window.removeEventListener('vesper:cw-change', refresh);
            window.removeEventListener('storage', refresh);
        };
    }, []);

    return (
        <div
            data-testid="home-page"
            className="relative w-screen h-[100dvh] min-h-screen overflow-hidden"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <SideNav />
            <FullscreenButton />

            {isFilterView ? (
                <main
                    data-testid="home-main"
                    className="absolute inset-0 overflow-y-auto"
                    style={{ scrollBehavior: 'auto' }}
                >
                    <TabGridView key={filter} type={filter} />
                </main>
            ) : (
                /* Split layout — hero stays LOCKED at the top, only
                   the shelves region (Continue Watching, Networks,
                   shelves...) scrolls vertically.  This matches what
                   the user kept asking for: "only the Rows should
                   move".  Hero is pinned; D-pad Down from Play moves
                   focus into the shelves and the shelves region
                   scrolls under the hero. */
                <main
                    data-testid="home-main"
                    className="absolute inset-0 flex flex-col"
                >
                    <div className="shrink-0">
                        <HeroBillboard heroes={liveHeroes} />
                    </div>

                    <div
                        data-testid="shelves-region"
                        className="flex-1 overflow-y-auto"
                        style={{
                            /* v2.7.11 — INSTANT snap per user spec
                             * ("NO sliding up and down effect, I
                             * want snap change like before").  The
                             * v2.7.08+ `smooth` behaviour was
                             * fighting with scroll-snap-stop:always
                             * + the spatial-focus engine's own
                             * scrollIntoView calls, producing the
                             * jittery / disappearing-focus-border
                             * behaviour visible in the user video.
                             * `auto` lets the browser instantly
                             * commit to the snap target on every
                             * D-pad press.
                             *
                             * v2.7.84 — Mobile: disable scroll-snap
                             * entirely.  The one-shelf-per-viewport
                             * snap-pagination is a 10-foot TV
                             * design idiom; on a phone it makes the
                             * page feel "grabby" / stretched and
                             * the user can't simply flick through
                             * shelves naturally.  We keep snap ON
                             * for desktop / TV. */
                            scrollBehavior: 'auto',
                            scrollSnapType: isMobile ? 'none' : 'y mandatory',
                            scrollSnapStop: isMobile ? 'normal' : 'always',
                            paddingTop: 0,
                            paddingBottom: 0,
                            transform: 'translateZ(0)',
                            willChange: 'scroll-position',
                            overscrollBehavior: 'contain',
                        }}
                    >
                        {hasCW && (
                            <ShelfPage height={shelfPageHeight} isMobile={isMobile}><ContinueWatchingShelf /></ShelfPage>
                        )}
                        {hasViewingStyle && (
                            <ShelfPage height={shelfPageHeight} isMobile={isMobile}><ForYouShelf /></ShelfPage>
                        )}
                        <ShelfPage height={shelfPageHeight} isMobile={isMobile}><NetworksShelf /></ShelfPage>
                        {addons.length === 0 && (
                            <ShelfPage height={shelfPageHeight} isMobile={isMobile}><EmptyAddonsBanner /></ShelfPage>
                        )}
                        {shelves.map((shelf, i) => (
                            <ShelfPage key={shelf.id} height={shelfPageHeight} isMobile={isMobile}>
                                <Lazy minHeight={340} eager={i < 3}>
                                    <Shelf shelf={shelf} />
                                </Lazy>
                            </ShelfPage>
                        ))}

                        {/* Upcoming Movies — always the last rail on
                            Home.  Pulls TMDB's next-60-day window via
                            /api/tmdb/upcoming-movies.  Clicking a tile
                            opens Detail (which renders the trailer +
                            "Notify me" CTA when no streams exist). */}
                        <ShelfPage height={shelfPageHeight} isMobile={isMobile}>
                            <Lazy minHeight={340} eager={false}>
                                <UpcomingMoviesShelf />
                            </Lazy>
                        </ShelfPage>

                        <footer
                            className="flex items-center justify-between"
                            style={{
                                paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                                paddingRight: 'clamp(40px, 4.2vw, 80px)',
                                paddingTop: 'clamp(20px, 2vw, 32px)',
                                paddingBottom: 'clamp(24px, 2.5vw, 40px)',
                            }}
                        >
                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 11,
                                    color: 'var(--vesper-text-3)',
                                    letterSpacing: '0.22em',
                                    textTransform: 'uppercase',
                                }}
                            >
                                ON NOW TV V2 · v1.0
                            </div>
                            <div
                                className="vesper-mono"
                                style={{
                                    color: 'var(--vesper-text-3)',
                                    fontSize: 11,
                                    letterSpacing: '0.22em',
                                    textTransform: 'uppercase',
                                }}
                            >
                                {liveLoading
                                    ? 'Loading addons…'
                                    : `${addons.length} source${
                                          addons.length === 1 ? '' : 's'
                                      } active`}
                            </div>
                        </footer>
                    </div>
                </main>
            )}
            <DPadHint
                storageKey="home"
                items={[
                    { keys: '↑↓←→', label: 'NAVIGATE' },
                    { keys: 'OK', label: 'OPEN' },
                    { keys: '←←', label: 'MENU' },
                ]}
            />
        </div>
    );
}

function EmptyAddonsBanner() {
    /* v2.6.78: replaced the dev-facing "Install a Stremio addon to
       see real catalogues here" + Sources button with a polished
       end-user message.  No-one outside the dev workflow should
       ever see (or need to use) the Sources screen — the app is a
       finished product, not a Stremio configurator. */
    return (
        <section
            className="flex items-center justify-between"
            style={{
                margin:
                    '32px clamp(40px, 4.2vw, 80px) 0 clamp(92px, 6.5vw, 132px)',
                padding: '20px 28px',
                borderRadius: 16,
                background:
                    'linear-gradient(90deg, rgba(var(--vesper-blue-rgb),0.10) 0%, rgba(var(--vesper-blue-rgb),0.02) 100%)',
                border: '1px solid rgba(var(--vesper-blue-rgb),0.25)',
            }}
        >
            <div>
                <div className="vesper-eyebrow" style={{ fontSize: 11 }}>
                    Connection
                </div>
                <div
                    className="vesper-display mt-1"
                    style={{ fontSize: 22, letterSpacing: '-0.02em' }}
                >
                    On Now TV is currently offline.
                </div>
                <div
                    style={{
                        marginTop: 6,
                        fontSize: 13,
                        color: 'var(--vesper-text-2)',
                        opacity: 0.78,
                    }}
                >
                    Check your internet connection and try again — your
                    profile and library are saved.
                </div>
            </div>
        </section>
    );
}


/* ShelfPage — wraps each home-screen shelf so it occupies EXACTLY
   the visible scroll area (window.innerHeight - heroHeight).
   v2.7.24 — paddingBottom slashed 8 → 0 to push the rows down to
   the very bottom edge of the snap-page per user spec "STOP
   TOUCHING THE HERO SECTION AND JUST MOVE THE ROWS down a tiny
   bit".  Cards now sit flush at viewport bottom.
   v2.7.84 — On mobile we render the shelf at its NATURAL height
   (no forced viewport snap), so the page scrolls smoothly through
   shelves like a normal feed.  The TV layout is untouched. */
const ShelfPage = ({ children, height, isMobile }) => (
    <div
        data-testid="shelf-page"
        style={
            isMobile
                ? {
                      /* Phones: natural height, no snap.  Just a
                         small vertical breather between rails. */
                      paddingTop: 4,
                      paddingBottom: 8,
                  }
                : {
                      height,
                      minHeight: height,
                      scrollSnapAlign: 'center',
                      scrollSnapStop: 'always',
                      display: 'flex',
                      flexDirection: 'column',
                      justifyContent: 'flex-end',
                      paddingBottom: 0,
                  }
        }
    >
        {children}
    </div>
);
