import React, { useMemo, useEffect } from 'react';
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

    // Filter drives the entire page mode:
    //   - null   → full home (hero + Continue Watching + networks + shelves)
    //   - movie  → movies-only grid sorted newest-first
    //   - series → tv-shows-only grid sorted newest-first
    const filter = new URLSearchParams(location.search).get('filter');
    const isFilterView = filter === 'movie' || filter === 'series';

    // Tell the native wrapper which "home mode" we're in so it can
    // pop up the exit-confirm dialog only on the bare home, not on
    // the filter views (where back should just go back to home).
    useHomeBackHandler(isFilterView ? 'home-filter' : 'home-root');

    // Filter views fetch up to 60 items per catalogue so the grid
    // has enough density to feel "endless".  Default home stays at
    // the standard 18 to keep horizontal shelves snappy.
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

    // Whenever the filter changes, snap the page back to the top so
    // the user lands on the new heading instead of mid-grid.
    useEffect(() => {
        document.scrollingElement?.scrollTo({ top: 0, behavior: 'auto' });
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

            <main
                data-testid="home-main"
                className="absolute inset-0 overflow-y-auto"
                style={{ scrollBehavior: 'auto' }}
            >
                {isFilterView ? (
                    <TabGridView
                        shelves={shelves}
                        loading={liveLoading}
                        type={filter}
                    />
                ) : (
                    <>
                        <HeroBillboard heroes={liveHeroes} />

                        <ContinueWatchingShelf />

                        <NetworksShelf />

                        {addons.length === 0 && <EmptyAddonsBanner />}

                        {shelves.map((shelf, i) => (
                            <Lazy key={shelf.id} minHeight={340} eager={i < 1}>
                                <Shelf shelf={shelf} />
                            </Lazy>
                        ))}
                    </>
                )}

                <footer
                    className="flex items-center justify-between"
                    style={{
                        paddingLeft: 'clamp(124px, 9.5vw, 180px)',
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
            </main>
        </div>
    );
}

function EmptyAddonsBanner() {
    return (
        <section
            className="flex items-center justify-between"
            style={{
                margin:
                    '32px clamp(40px, 4.2vw, 80px) 0 clamp(124px, 9.5vw, 180px)',
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
