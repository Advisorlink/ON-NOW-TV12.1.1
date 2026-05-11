import React, { useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import SideNav from '@/components/SideNav';
import HeroBillboard from '@/components/HeroBillboard';
import Shelf from '@/components/Shelf';
import NetworksShelf from '@/components/NetworksShelf';
import ContinueWatchingShelf from '@/components/ContinueWatchingShelf';
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
    const shelfFilter = isFilterView ? filter : null;
    const heroType = filter === 'movie' ? 'movie' : 'series';

    const { shelves: liveShelves, loading: liveLoading } = useLiveShelves(
        addons,
        shelfFilter,
        itemsPerCatalog
    );
    const { heroes: liveHeroes } = useLiveHeroes(addons, heroType);

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
                    <TabGridView
                        shelves={shelves}
                        loading={liveLoading}
                        type={filter}
                    />
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
                        <NetworksShelf />
                        {addons.length === 0 && <EmptyAddonsBanner />}
                        {shelves.map((shelf, i) => (
                            <Lazy key={shelf.id} minHeight={340} eager={i < 1}>
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
                    'linear-gradient(90deg, rgba(93,200,255,0.10) 0%, rgba(93,200,255,0.02) 100%)',
                border: '1px solid rgba(93,200,255,0.25)',
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
