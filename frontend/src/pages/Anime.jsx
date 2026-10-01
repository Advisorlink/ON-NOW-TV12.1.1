import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Loader2 } from 'lucide-react';
import SideNav from '@/components/SideNav';
import FullscreenButton from '@/components/FullscreenButton';
import HeroBillboard from '@/components/HeroBillboard';
import TrailerHoverPreview from '@/components/TrailerHoverPreview';
import Shelf from '@/components/Shelf';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';
import useFocusHero, { tileToHero } from '@/hooks/useFocusHero';
import { API } from '@/lib/api';

/**
 * Anime hub — every anime rail TMDB can give us (trending, movies,
 * airing now, top rated, Ghibli, genres, kids, classics), presented
 * exactly like the For You page: hero follows focus, trailer slot.
 */
const toTile = (railId, it) => ({
    id: `anime-${railId}-${it.type}-${it.tmdb_id}`,
    imdbId: null,
    type: it.type,
    title: it.title,
    sub: [it.year, it.rating ? `★ ${it.rating}` : null].filter(Boolean).join(' · '),
    poster: it.poster,
    background: it.backdrop,
    year: it.year || null,
    rating: it.rating ? `★ ${it.rating}` : null,
    genres: ['Anime'],
    synopsis: it.overview || '',
    routePath: `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
});

export default function Anime() {
    useSpatialFocus();
    const navigate = useNavigate();
    useBackHandler(() => navigate('/'));
    const focusHero = useFocusHero();
    const [rails, setRails] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');

    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                const r = await fetch(`${API}/tmdb/anime?limit=40`);
                if (!r.ok) throw new Error(`HTTP ${r.status}`);
                const j = await r.json();
                if (cancel) return;
                setRails((j.rails || []).map((rail) => ({
                    id: `anime-${rail.id}`,
                    eyebrow: rail.eyebrow,
                    title: rail.title,
                    items: rail.data.map((it) => toTile(rail.id, it)),
                })));
            } catch {
                if (!cancel) setError('Couldn\u2019t load anime right now. Please try again in a moment.');
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, []);

    const heroes = useMemo(() => {
        const first = rails[0]?.items?.[0];
        return first ? [tileToHero(first)] : [];
    }, [rails]);

    // Rails arrive after the page's initial-focus pass → land on the first cover.
    useEffect(() => {
        if (loading || rails.length === 0) return undefined;
        const t = setTimeout(() => {
            const ae = document.activeElement;
            if (ae && ae.closest('[data-testid="anime-shelves"]')) return;
            const first = document.querySelector('[data-testid="anime-shelves"] [data-focusable="true"]');
            if (!first) return;
            try { first.focus({ preventScroll: true }); } catch { /* ignore */ }
            document.querySelectorAll('[data-focused="true"]').forEach((x) => { if (x !== first) x.removeAttribute('data-focused'); });
            first.setAttribute('data-focused', 'true');
        }, 80);
        return () => clearTimeout(t);
    }, [loading, rails.length]);

    return (
        <div
            data-testid="anime-page"
            className="relative w-screen h-[100dvh] min-h-screen overflow-hidden"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <SideNav />
            <FullscreenButton />
            <TrailerHoverPreview />

            <div
                className="absolute flex items-center"
                style={{ top: 'clamp(14px, 1.4vw, 24px)', left: 'clamp(92px, 6.5vw, 132px)', zIndex: 30, gap: 14 }}
            >
                <button
                    data-testid="anime-back"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-initial-focus={!loading && rails.length === 0 ? 'true' : undefined}
                    tabIndex={0}
                    onClick={() => navigate('/')}
                    className="flex items-center gap-2 h-10 px-4 rounded-full vesper-mono"
                    style={{
                        background: 'rgba(6,8,15,0.55)',
                        color: 'rgba(255,255,255,0.92)',
                        border: '1px solid rgba(255,255,255,0.18)',
                        fontSize: 12,
                        letterSpacing: '0.18em',
                        textTransform: 'uppercase',
                        backdropFilter: 'blur(12px)',
                    }}
                >
                    <ArrowLeft size={15} /> Back
                </button>
                <span
                    data-testid="anime-eyebrow"
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.26em',
                        textTransform: 'uppercase',
                        color: '#ff5fa2',
                        textShadow: '0 1px 8px rgba(0,0,0,0.6)',
                    }}
                >
                    Anime · Movies &amp; Series
                </span>
            </div>

            <main data-testid="anime-main" className="absolute inset-0 flex flex-col">
                <div className="shrink-0">
                    {heroes.length > 0 ? (
                        <HeroBillboard heroes={heroes} override={focusHero} />
                    ) : (
                        <div
                            data-testid="anime-hero-placeholder"
                            style={{
                                height: 'clamp(380px, 50vh, 540px)',
                                background: 'linear-gradient(135deg, #070a12 0%, #1a0d24 60%, #ff5fa255 100%)',
                            }}
                        />
                    )}
                </div>
                <div data-testid="anime-shelves" className="flex-1 overflow-y-auto" style={{ overscrollBehavior: 'contain' }}>
                    {loading ? (
                        <div
                            className="flex items-center gap-3"
                            style={{ color: 'var(--vesper-text-2)', padding: 'clamp(26px, 2.4vw, 44px) clamp(92px, 6.5vw, 132px)' }}
                        >
                            <Loader2 className="vesper-spin" size={18} /> Loading anime…
                        </div>
                    ) : error ? (
                        <div className="vesper-glass rounded-xl p-6" style={{ color: '#ffb5b5', margin: '24px clamp(92px, 6.5vw, 132px)' }}>{error}</div>
                    ) : (
                        rails.map((shelf, i) => (
                            <Shelf key={shelf.id} shelf={shelf} firstTileInitialFocus={i === 0} />
                        ))
                    )}
                </div>
            </main>
        </div>
    );
}
