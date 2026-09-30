import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Loader2 } from 'lucide-react';
import SideNav from '@/components/SideNav';
import FullscreenButton from '@/components/FullscreenButton';
import HeroBillboard from '@/components/HeroBillboard';
import TrailerHoverPreview from '@/components/TrailerHoverPreview';
import Shelf from '@/components/Shelf';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';
import useFocusHero, { tileToHero } from '@/hooks/useFocusHero';
import { findCollection } from '@/lib/collections';
import { API } from '@/lib/api';

/**
 * Box Set / Studio page — same experience as the For You page: the
 * billboard follows the focused cover (backdrop + synopsis), the
 * 16:9 trailer slot plays as you scroll across, OK opens the title.
 */
const toTile = (slug, it) => ({
    id: `col-${slug}-${it.type}-${it.tmdb_id}`,
    imdbId: null,
    type: it.type,
    title: it.title,
    sub: [it.year, it.rating ? `★ ${it.rating}` : null].filter(Boolean).join(' · '),
    poster: it.poster,
    background: it.backdrop,
    year: it.year || null,
    rating: it.rating ? `★ ${it.rating}` : null,
    genres: [],
    synopsis: it.overview || '',
    routePath: `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
});

async function fetchAll(slug, type, region, pages) {
    const out = [];
    const seen = new Set();
    for (let p = 1; p <= pages; p += 1) {
        const r = await fetch(`${API}/collections/${slug}?type=${type}&page=${p}&region=${region}`, { cache: 'no-store' });
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        const data = (await r.json())?.data || {};
        for (const it of data.results || []) {
            if (!it.poster || seen.has(it.tmdb_id)) continue;
            seen.add(it.tmdb_id);
            out.push(toTile(slug, it));
        }
        if (p >= (data.total_pages || 1)) break;
    }
    return out;
}

export default function Collection() {
    useSpatialFocus();
    useBackHandler();
    const { slug } = useParams();
    const navigate = useNavigate();
    const col = useMemo(() => findCollection(slug), [slug]);
    const focusHero = useFocusHero();

    const [movies, setMovies] = useState([]);
    const [series, setSeries] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    useEffect(() => {
        if (!col) return;
        let cancel = false;
        setLoading(true);
        setError(null);
        setMovies([]);
        setSeries([]);
        (async () => {
            try {
                const region = col.region || 'US';
                const [m, s] = await Promise.all([
                    fetchAll(slug, 'movie', region, 3),
                    col.moviesOnly ? Promise.resolve([]) : fetchAll(slug, 'tv', region, 2),
                ]);
                if (cancel) return;
                setMovies(m);
                setSeries(s);
            } catch (e) {
                if (!cancel) setError(e?.message || 'Could not load this collection');
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, [slug, col]);

    const isBoxSet = col?.group === 'boxset';
    const first = movies[0] || series[0];
    const heroes = useMemo(() => (first ? [tileToHero(first)] : []), [first]);

    const shelves = useMemo(() => {
        if (!col) return [];
        const list = [];
        if (movies.length) {
            list.push({
                id: `collection-${slug}-movies`,
                eyebrow: isBoxSet ? 'Box set' : 'Studio',
                title: isBoxSet ? `${col.name} · Films in order` : `${col.name} · Movies`,
                items: movies,
            });
        }
        if (series.length) {
            list.push({ id: `collection-${slug}-series`, eyebrow: 'Studio', title: `${col.name} · TV Shows`, items: series });
        }
        return list;
    }, [col, slug, movies, series, isBoxSet]);

    if (!col) {
        return (
            <div className="w-screen h-[100dvh] flex flex-col items-center justify-center" style={{ background: 'var(--vesper-bg-0)', gap: 16 }}>
                <div className="vesper-display" style={{ fontSize: 48 }}>Unknown collection</div>
                <button
                    data-testid="collection-home"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={() => navigate('/')}
                    className="h-12 px-5 rounded-full"
                    style={{ background: 'rgba(255,255,255,0.06)', color: 'var(--vesper-text)', border: '1px solid rgba(255,255,255,0.12)' }}
                >
                    Home
                </button>
            </div>
        );
    }

    return (
        <div
            data-testid={`collection-page-${slug}`}
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
                    data-testid="collection-back"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-initial-focus={!loading && shelves.length === 0 ? 'true' : undefined}
                    tabIndex={0}
                    onClick={() => navigate(-1)}
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
                    data-testid="collection-eyebrow"
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.26em',
                        textTransform: 'uppercase',
                        color: col.accent || 'var(--vesper-blue-bright)',
                        textShadow: '0 1px 8px rgba(0,0,0,0.6)',
                    }}
                >
                    {isBoxSet ? 'Box set' : 'Studio'} · {col.name}
                </span>
            </div>

            <main data-testid="collection-main" className="absolute inset-0 flex flex-col">
                <div className="shrink-0">
                    {heroes.length > 0 ? (
                        <HeroBillboard heroes={heroes} override={focusHero} />
                    ) : (
                        <div
                            data-testid="collection-hero-placeholder"
                            style={{
                                height: 'clamp(380px, 50vh, 540px)',
                                background: `linear-gradient(135deg, #070a12 0%, #0d1424 60%, ${col.accent || '#3b82f6'}55 100%)`,
                            }}
                        />
                    )}
                </div>
                <div data-testid="collection-shelves" className="flex-1 overflow-y-auto" style={{ overscrollBehavior: 'contain' }}>
                    {loading ? (
                        <div
                            className="flex items-center gap-3"
                            style={{ color: 'var(--vesper-text-2)', padding: 'clamp(26px, 2.4vw, 44px) clamp(92px, 6.5vw, 132px)' }}
                        >
                            <Loader2 className="vesper-spin" size={18} /> Loading {col.name}…
                        </div>
                    ) : error ? (
                        <div className="vesper-glass rounded-xl p-6" style={{ color: '#ffb5b5', margin: '24px clamp(92px, 6.5vw, 132px)' }}>{error}</div>
                    ) : shelves.length === 0 ? (
                        <div
                            data-testid="collection-empty"
                            className="vesper-glass rounded-xl p-6"
                            style={{ color: 'var(--vesper-text-2)', margin: '24px clamp(92px, 6.5vw, 132px)' }}
                        >
                            Nothing in {col.name} yet.
                        </div>
                    ) : (
                        shelves.map((shelf, i) => (
                            <Shelf key={shelf.id} shelf={shelf} firstTileInitialFocus={i === 0} />
                        ))
                    )}
                </div>
            </main>
        </div>
    );
}
