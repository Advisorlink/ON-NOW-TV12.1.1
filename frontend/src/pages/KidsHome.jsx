import React, { useMemo } from 'react';
import { useLocation } from 'react-router-dom';
import KidsSideNav from '@/components/KidsSideNav';
import HeroBillboard from '@/components/HeroBillboard';
import Shelf from '@/components/Shelf';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useHomeBackHandler from '@/hooks/useHomeBackHandler';
import { useKidsShelves } from '@/hooks/useKidsShelves';
import { useKidsHeroes } from '@/hooks/useKidsHeroes';
import Lazy from '@/components/Lazy';

/**
 * Kids-mode home page.
 *
 * Visually mirrors the regular Home (Hero + horizontal Shelves) so
 * children get the same premium TV experience, but:
 *   • Data comes EXCLUSIVELY from TMDB's curated kid-safe discover
 *     endpoints — never from raw Stremio addon catalogs that may
 *     include R-rated titles.
 *   • A scoped CSS theme (data-kids-theme="1") swaps the cyber-blue
 *     accent for sunshine-yellow + magenta, warms the background to
 *     a playful grape/berry gradient, and softens every focus ring.
 *   • A KidsSideNav rail with limited destinations replaces the main
 *     SideNav — no Sources, no Settings, no APK fiddling for kids.
 */
export default function KidsHome() {
    useSpatialFocus();
    const location = useLocation();
    useHomeBackHandler('kids-home');

    const filter = new URLSearchParams(location.search).get('filter');

    const { shelves: allShelves, loading: shelvesLoading } = useKidsShelves();
    const { heroes } = useKidsHeroes();

    const shelves = useMemo(() => {
        if (!Array.isArray(allShelves)) return [];
        if (filter === 'movie')
            return allShelves.filter((s) =>
                (s.items || []).some((i) => i.type === 'movie')
            );
        if (filter === 'series')
            return allShelves.filter((s) =>
                (s.items || []).some((i) => i.type === 'series')
            );
        return allShelves;
    }, [allShelves, filter]);

    React.useLayoutEffect(() => {
        const region = document.querySelector(
            '[data-testid="kids-shelves-region"]'
        );
        if (region) region.scrollTop = 0;
    }, [filter]);

    return (
        <div
            data-testid="kids-home"
            data-kids-theme="1"
            className="vesper-kids-root relative w-screen h-[100dvh] min-h-screen overflow-hidden"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <KidsSideNav />

            <main
                data-testid="kids-home-main"
                className="absolute inset-0 flex flex-col"
            >
                <div className="shrink-0">
                    <HeroBillboard heroes={heroes} />
                </div>

                <div
                    data-testid="kids-shelves-region"
                    className="flex-1 overflow-y-auto"
                    style={{ scrollBehavior: 'auto', paddingTop: 26 }}
                >
                    <KidsBadgeBanner />

                    {shelvesLoading && shelves.length === 0 && (
                        <div
                            className="vesper-mono"
                            style={{
                                textAlign: 'center',
                                padding: 60,
                                color: 'var(--vesper-text-3)',
                                letterSpacing: '0.22em',
                                fontSize: 12,
                            }}
                        >
                            FINDING FUN STUFF…
                        </div>
                    )}

                    {!shelvesLoading && shelves.length === 0 && (
                        <div
                            style={{
                                textAlign: 'center',
                                padding: '60px 40px',
                                color: 'var(--vesper-text-2)',
                            }}
                        >
                            <div
                                className="vesper-display"
                                style={{ fontSize: 28, color: '#fff' }}
                            >
                                No shows right now
                            </div>
                            <div style={{ marginTop: 8, fontSize: 15 }}>
                                Ask a grown-up to check the internet.
                            </div>
                        </div>
                    )}

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
                            ON NOW TV · KIDS
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
                            {shelves.length} shelves
                        </div>
                    </footer>
                </div>
            </main>
        </div>
    );
}

function KidsBadgeBanner() {
    return (
        <section
            className="flex items-center justify-between"
            style={{
                margin:
                    '6px clamp(40px, 4.2vw, 80px) 0 clamp(92px, 6.5vw, 132px)',
                padding: '14px 22px',
                borderRadius: 18,
                background:
                    'linear-gradient(90deg, rgba(255,107,203,0.16) 0%, rgba(255,212,59,0.10) 50%, rgba(61,220,151,0.10) 100%)',
                border: '1px solid rgba(255,212,59,0.25)',
            }}
        >
            <div className="flex items-center gap-3">
                <div
                    style={{
                        width: 44,
                        height: 44,
                        borderRadius: 14,
                        background:
                            'linear-gradient(135deg, #FFD43B 0%, #FF6BCB 100%)',
                        display: 'grid',
                        placeItems: 'center',
                        boxShadow: '0 8px 22px rgba(255,212,59,0.35)',
                        fontSize: 24,
                    }}
                >
                    🌈
                </div>
                <div>
                    <div
                        className="vesper-eyebrow"
                        style={{ fontSize: 11, color: '#FFD43B' }}
                    >
                        Kid-safe zone
                    </div>
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 20,
                            color: '#fff',
                            letterSpacing: '-0.02em',
                            marginTop: 2,
                        }}
                    >
                        Pick a show and let&apos;s go!
                    </div>
                </div>
            </div>
        </section>
    );
}
