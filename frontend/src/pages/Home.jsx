import React, { useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import SideNav from '@/components/SideNav';
import HeroBillboard from '@/components/HeroBillboard';
import Shelf from '@/components/Shelf';
import NetworksShelf from '@/components/NetworksShelf';
import ContinueWatchingShelf from '@/components/ContinueWatchingShelf';
import ForYouShelf from '@/components/ForYouShelf';
import TabGridView from '@/components/TabGridView';
import FullscreenButton from '@/components/FullscreenButton';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useHomeBackHandler from '@/hooks/useHomeBackHandler';
import { useAddons } from '@/hooks/useAddons';
import { useLiveShelves } from '@/hooks/useLiveShelves';
import { useLiveHeroes } from '@/hooks/useLiveHeroes';
import Lazy from '@/components/Lazy';

export default function Home() {
    useSpatialFocus();
    const { addons } = useAddons();
    const location = useLocation();

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

    const shelves = useMemo(
        () => (Array.isArray(liveShelves) ? liveShelves : []),
        [liveShelves]
    );

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

    // Initial focus on the FIRST tile of the FIRST shelf — not the
    // hero Play button, not the side nav.  Shelves render async as
    // addons resolve, so we retry over ~2 s before giving up.  In
    // the filtered "TV Shows" / "Movies" tab view we target the
    // tab grid list instead so focus snaps into the grid as soon
    // as the first batch of items lands (= the page feels snappy
    // even while remaining catalogues stream in).
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
            const first = target.querySelector('[data-focusable="true"]');
            if (!first) return false;
            try {
                first.focus({ preventScroll: true });
            } catch {
                /* ignore */
            }
            // Clear lingering data-focused on anything else (hero Play
            // button may have picked it up from an earlier mount).
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

        const onKey = (e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            const homeRoot = document.querySelector('[data-testid="home-page"]');
            if (!homeRoot) return;
            const active = document.activeElement;
            if (!active || !homeRoot.contains(active)) return;

            // Build the ordered list of "rows" in DOM order.  Hero
            // is row 0 when it has any focusable; every shelf
            // section under shelves-region becomes a row when it
            // contains at least one focusable element.
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
            if (rows.length === 0) return;

            // Which row is the user currently on?
            let curRowIdx = -1;
            for (let i = 0; i < rows.length; i++) {
                if (rows[i].includes(active)) {
                    curRowIdx = i;
                    break;
                }
            }
            if (curRowIdx === -1) {
                const activeY = active.getBoundingClientRect().top;
                let best = 0;
                let bestDy = Infinity;
                for (let i = 0; i < rows.length; i++) {
                    const dy = Math.abs(
                        rows[i][0].getBoundingClientRect().top - activeY
                    );
                    if (dy < bestDy) {
                        bestDy = dy;
                        best = i;
                    }
                }
                curRowIdx = best;
            }

            const targetIdx =
                e.key === 'ArrowDown' ? curRowIdx + 1 : curRowIdx - 1;
            if (targetIdx < 0 || targetIdx >= rows.length) return; // edge

            // Pick the tile on the target row closest to the
            // current X column so the user stays in the same
            // visual column when scrolling rails up/down.
            const curRect = active.getBoundingClientRect();
            const curX = curRect.left + curRect.width / 2;
            const target = rows[targetIdx].reduce((best, el) => {
                const r = el.getBoundingClientRect();
                const dx = Math.abs(r.left + r.width / 2 - curX);
                if (!best || dx < best.dx) return { el, dx };
                return best;
            }, null);
            if (!target) return;

            e.preventDefault();
            e.stopPropagation();
            try { target.el.focus({ preventScroll: false }); } catch { /* ignore */ }
            target.el.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== target.el) el.removeAttribute('data-focused');
                });
            try {
                target.el.scrollIntoView({
                    behavior: 'smooth',
                    block: 'nearest',
                });
            } catch { /* ignore */ }
        };

        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [isFilterView]);

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
                            scrollBehavior: 'auto',
                            // Top padding so the focus ring around
                            // the FIRST shelf's tiles isn't clipped
                            // by the scroller's top edge (the focus
                            // ring extends ~22 px above each tile).
                            paddingTop: 26,
                        }}
                    >
                        <ContinueWatchingShelf />
                        <ForYouShelf />
                        <NetworksShelf />
                        {addons.length === 0 && <EmptyAddonsBanner />}
                        {shelves.map((shelf, i) => (
                            <Lazy key={shelf.id} minHeight={340} eager={i < 3}>
                                <Shelf shelf={shelf} />
                            </Lazy>
                        ))}

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
        </div>
    );
}

function EmptyAddonsBanner() {
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
                    Demo content shown
                </div>
                <div
                    className="vesper-display mt-1"
                    style={{ fontSize: 22, letterSpacing: '-0.02em' }}
                >
                    Install a Stremio addon to see real catalogues here.
                </div>
            </div>
            <a
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                href="/sources"
                onClick={(e) => {
                    e.preventDefault();
                    window.history.pushState({}, '', '/sources');
                    window.dispatchEvent(new PopStateEvent('popstate'));
                }}
                className="h-12 px-5 rounded-full flex items-center font-sans font-semibold text-[16px]"
                style={{
                    background: 'var(--vesper-blue)',
                    color: 'var(--vesper-bg-0)',
                }}
            >
                Open Sources →
            </a>
        </section>
    );
}
