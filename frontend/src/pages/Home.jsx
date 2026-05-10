import React, { useMemo } from 'react';
import SideNav from '@/components/SideNav';
import HeroBillboard from '@/components/HeroBillboard';
import Shelf from '@/components/Shelf';
import FullscreenButton from '@/components/FullscreenButton';
import { SHELVES as MOCK_SHELVES } from '@/data/mockCatalog';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import { useLiveShelves } from '@/hooks/useLiveShelves';

export default function Home() {
    useSpatialFocus();
    const { addons } = useAddons();
    const { shelves: liveShelves, loading: liveLoading } = useLiveShelves(addons);

    const shelves = useMemo(() => {
        if (liveShelves && liveShelves.length > 0) return liveShelves;
        return MOCK_SHELVES;
    }, [liveShelves]);

    return (
        <div
            data-testid="home-page"
            className="relative w-screen h-screen overflow-hidden"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <SideNav />
            <FullscreenButton />

            <main
                className="absolute inset-0 overflow-y-auto"
                style={{ scrollBehavior: 'smooth' }}
            >
                <HeroBillboard />

                {addons.length === 0 && (
                    <section
                        className="flex items-center justify-between"
                        style={{
                            margin: '32px 80px 0 180px',
                            padding: '20px 28px',
                            borderRadius: 16,
                            background:
                                'linear-gradient(90deg, rgba(93,200,255,0.10) 0%, rgba(93,200,255,0.02) 100%)',
                            border: '1px solid rgba(93,200,255,0.25)',
                        }}
                    >
                        <div>
                            <div
                                className="vesper-eyebrow"
                                style={{ fontSize: 11 }}
                            >
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
                )}

                {shelves.map((shelf) => (
                    <Shelf key={shelf.id} shelf={shelf} />
                ))}

                <footer
                    className="flex items-center justify-between"
                    style={{
                        paddingLeft: 180,
                        paddingRight: 80,
                        paddingTop: 64,
                        paddingBottom: 80,
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
                        Vesper · v0.2 · Vespertine
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
                            : `${addons.length} source${addons.length === 1 ? '' : 's'} active`}
                    </div>
                </footer>
            </main>
        </div>
    );
}
