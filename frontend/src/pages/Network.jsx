import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Loader2 } from 'lucide-react';
import SideNav from '@/components/SideNav';
import FullscreenButton from '@/components/FullscreenButton';
import PosterTile from '@/components/PosterTile';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { findNetwork } from '@/lib/networks';

/**
 * Network catalogue page.  Pulls each curated imdb id through Cinemeta
 * directly from the browser (residential IP, public CORS), in parallel,
 * and renders successful resolutions as poster tiles.  Failures are
 * skipped silently so a single dead id doesn't blank the page.
 */
export default function Network() {
    useSpatialFocus();
    const { slug } = useParams();
    const navigate = useNavigate();
    const network = findNetwork(slug);

    const [items, setItems] = useState([]);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        if (!network) return;
        let cancelled = false;
        setLoading(true);
        setItems([]);

        (async () => {
            const acc = [];
            await Promise.all(
                network.imdbIds.map(async (imdbId) => {
                    try {
                        const r = await fetch(
                            `https://v3-cinemeta.strem.io/meta/series/${imdbId}.json`,
                            { mode: 'cors', cache: 'force-cache' }
                        );
                        if (!r.ok) return;
                        const data = await r.json();
                        const m = data?.meta;
                        if (!m || !m.poster) return;
                        acc.push({
                            id: `${slug}-${imdbId}`,
                            imdbId,
                            type: 'series',
                            title: m.name,
                            sub: [
                                m.releaseInfo,
                                m.imdbRating ? `★ ${m.imdbRating}` : null,
                            ]
                                .filter(Boolean)
                                .join(' · '),
                            poster: m.poster,
                            routePath: `/title/series/${imdbId}`,
                        });
                    } catch {
                        /* swallow individual failures */
                    }
                })
            );
            if (!cancelled) {
                // Preserve the curated order when possible
                acc.sort(
                    (a, b) =>
                        network.imdbIds.indexOf(a.imdbId) -
                        network.imdbIds.indexOf(b.imdbId)
                );
                setItems(acc);
                setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [slug, network]);

    if (!network) {
        return (
            <div
                className="w-screen h-[100dvh] flex flex-col items-center justify-center"
                style={{ background: 'var(--vesper-bg-0)', gap: 16 }}
            >
                <div className="vesper-display" style={{ fontSize: 48 }}>
                    Unknown network
                </div>
                <button
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => navigate('/')}
                    className="h-12 px-5 rounded-full"
                    style={{
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.12)',
                    }}
                >
                    Home
                </button>
            </div>
        );
    }

    return (
        <div
            data-testid={`network-page-${slug}`}
            className="relative w-screen h-[100dvh] min-h-screen overflow-hidden"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <SideNav />
            <FullscreenButton />

            <main
                className="absolute inset-0 overflow-y-auto"
                style={{ scrollBehavior: 'smooth' }}
            >
                {/* Branded hero strip */}
                <header
                    className="relative w-full"
                    style={{
                        height: 320,
                        background: network.background,
                        paddingLeft: 180,
                        paddingRight: 80,
                    }}
                >
                    <div
                        className="absolute inset-0 pointer-events-none"
                        style={{
                            background:
                                'linear-gradient(180deg, rgba(6,8,15,0) 35%, var(--vesper-bg-0) 100%)',
                        }}
                    />
                    <div
                        className="relative h-full flex items-end pb-8"
                        style={{ gap: 24 }}
                    >
                        <button
                            data-testid="network-back"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            onClick={() => navigate(-1)}
                            className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
                            style={{
                                background: 'rgba(6,8,15,0.55)',
                                color: 'rgba(255,255,255,0.92)',
                                border: '1px solid rgba(255,255,255,0.18)',
                                fontSize: 13,
                                letterSpacing: '0.18em',
                                textTransform: 'uppercase',
                                backdropFilter: 'blur(12px)',
                            }}
                        >
                            <ArrowLeft size={16} /> Back
                        </button>
                        <div className="flex-1 min-w-0">
                            <div
                                className="vesper-eyebrow"
                                style={{
                                    color: 'rgba(255,255,255,0.78)',
                                    marginBottom: 6,
                                }}
                            >
                                Browse · Network
                            </div>
                            <h1
                                className="vesper-display"
                                style={{
                                    fontSize: 'clamp(56px, 6vw, 92px)',
                                    letterSpacing: '-0.035em',
                                    color: '#fff',
                                    textShadow:
                                        '0 4px 24px rgba(0,0,0,0.45)',
                                }}
                            >
                                {network.name}
                            </h1>
                        </div>
                    </div>
                </header>

                <section
                    className="relative w-full"
                    style={{
                        paddingLeft: 180,
                        paddingRight: 180,
                        paddingTop: 32,
                        paddingBottom: 96,
                    }}
                >
                    {loading ? (
                        <div
                            className="flex items-center gap-3"
                            style={{ color: 'var(--vesper-text-2)' }}
                        >
                            <Loader2 className="vesper-spin" size={18} />
                            Loading {network.name} picks…
                        </div>
                    ) : items.length === 0 ? (
                        <div
                            className="vesper-glass rounded-xl p-6"
                            style={{ color: 'var(--vesper-text-2)' }}
                        >
                            Couldn&apos;t resolve any titles right now. Cinemeta
                            may be temporarily unreachable — try again in a
                            moment.
                        </div>
                    ) : (
                        <div className="flex flex-wrap gap-6">
                            {items.map((item) => (
                                <PosterTile key={item.id} item={item} />
                            ))}
                        </div>
                    )}
                </section>
            </main>
        </div>
    );
}
