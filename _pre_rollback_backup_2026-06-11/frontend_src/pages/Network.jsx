import React, { useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Loader2, Plus } from 'lucide-react';
import SideNav from '@/components/SideNav';
import FullscreenButton from '@/components/FullscreenButton';
import NetworkPosterTile from '@/components/NetworkPosterTile';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';
import { findNetwork } from '@/lib/networks';
import { API } from '@/lib/api';

/**
 * Live network catalogue page powered by TMDB through Vesper's
 * backend (`/api/networks/:slug`).  Supports a TV / Movies sub-tab
 * and infinite-style "Load more" pagination.  Each result is a
 * TMDB id; <NetworkPosterTile> resolves it to an IMDB id on click
 * before routing to the unified detail page.
 */
const SUBTAB_KEY = 'vesper-network-subtab';

export default function Network() {
    useSpatialFocus();
    useBackHandler('/');
    const { slug } = useParams();
    const navigate = useNavigate();
    const network = findNetwork(slug);

    const [subTab, setSubTab] = useState(() => {
        try {
            return localStorage.getItem(SUBTAB_KEY) || 'tv';
        } catch {
            return 'tv';
        }
    });
    useEffect(() => {
        try {
            localStorage.setItem(SUBTAB_KEY, subTab);
        } catch {
            /* ignore */
        }
    }, [subTab]);

    const [items, setItems] = useState([]);
    const [page, setPage] = useState(1);
    const [totalPages, setTotalPages] = useState(1);
    const [totalResults, setTotalResults] = useState(0);
    const [loading, setLoading] = useState(true);
    const [loadingMore, setLoadingMore] = useState(false);
    const [error, setError] = useState(null);

    // Reset whenever the slug or sub-tab changes
    useEffect(() => {
        if (!network) return;
        let cancel = false;
        setItems([]);
        setPage(1);
        setTotalPages(1);
        setTotalResults(0);
        setError(null);
        setLoading(true);

        (async () => {
            try {
                const r = await fetch(
                    `${API}/networks/${slug}?type=${subTab}&page=1&region=${network.region || 'US'}`,
                    { cache: 'no-store' }
                );
                if (!r.ok) throw new Error(`HTTP ${r.status}`);
                const json = await r.json();
                const data = json?.data || {};
                if (cancel) return;
                setItems(data.results || []);
                setTotalPages(data.total_pages || 1);
                setTotalResults(data.total_results || 0);
                setPage(1);
            } catch (e) {
                if (!cancel) {
                    setError(
                        e?.message || 'Could not load this network catalogue'
                    );
                }
            } finally {
                if (!cancel) setLoading(false);
            }
        })();

        return () => {
            cancel = true;
        };
    }, [slug, subTab, network]);

    const loadMore = async () => {
        if (loadingMore || page >= totalPages) return;
        setLoadingMore(true);
        try {
            const next = page + 1;
            const r = await fetch(
                `${API}/networks/${slug}?type=${subTab}&page=${next}&region=${network.region || 'US'}`,
                { cache: 'no-store' }
            );
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            const json = await r.json();
            const data = json?.data || {};
            const fresh = data.results || [];
            // De-dupe: TMDB's popularity-sorted /discover may return
            // the same title on adjacent pages when popularity shifts
            // mid-request.  Keep the first occurrence.
            setItems((prev) => {
                const seen = new Set(prev.map((p) => p.tmdb_id));
                return [...prev, ...fresh.filter((n) => !seen.has(n.tmdb_id))];
            });
            setPage(next);
        } catch {
            /* swallow */
        } finally {
            setLoadingMore(false);
        }
    };

    // IntersectionObserver for auto-load when user scrolls near the
    // sentinel.  Falls back to manual button click as well.
    const sentinelRef = useRef(null);
    useEffect(() => {
        const node = sentinelRef.current;
        if (!node) return;
        const observer = new IntersectionObserver(
            (entries) => {
                if (entries[0].isIntersecting && !loadingMore && !loading) {
                    loadMore();
                }
            },
            { rootMargin: '600px 0px 600px 0px' }
        );
        observer.observe(node);
        return () => observer.disconnect();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [page, totalPages, loadingMore, loading, subTab, slug]);

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
                        height: 'clamp(220px, 28vw, 360px)',
                        background: network.background,
                        paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                        paddingRight: 'clamp(40px, 4.2vw, 80px)',
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
                                Browse · Network · Live from TMDB
                            </div>
                            <h1
                                className="vesper-display"
                                style={{
                                    fontSize: 'clamp(56px, 6vw, 92px)',
                                    letterSpacing: '-0.035em',
                                    color: '#fff',
                                    textShadow: '0 4px 24px rgba(0,0,0,0.45)',
                                }}
                            >
                                {network.name}
                            </h1>
                        </div>
                        {totalResults > 0 && (
                            <div
                                className="vesper-mono shrink-0"
                                style={{
                                    fontSize: 12,
                                    letterSpacing: '0.22em',
                                    textTransform: 'uppercase',
                                    color: 'rgba(255,255,255,0.78)',
                                    paddingBottom: 14,
                                }}
                            >
                                {items.length.toLocaleString()} of{' '}
                                {totalResults.toLocaleString()}
                            </div>
                        )}
                    </div>
                </header>

                {/* TV / Movies sub-tabs */}
                <div
                    data-testid="network-subtabs"
                    className="flex items-center"
                    style={{
                        paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                        paddingRight: 'clamp(40px, 4.2vw, 80px)',
                        paddingTop: 'clamp(16px, 1.6vw, 24px)',
                        paddingBottom: 0,
                        gap: 'clamp(8px, 0.7vw, 12px)',
                    }}
                >
                    <SubTab
                        active={subTab === 'tv'}
                        label="TV Shows"
                        testId="network-subtab-tv"
                        onClick={() => setSubTab('tv')}
                    />
                    <SubTab
                        active={subTab === 'movie'}
                        label="Movies"
                        testId="network-subtab-movie"
                        onClick={() => setSubTab('movie')}
                    />
                </div>

                <section
                    className="relative w-full"
                    style={{
                        paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                        paddingRight: 'clamp(40px, 4.2vw, 80px)',
                        paddingTop: 'clamp(20px, 2.4vw, 32px)',
                        paddingBottom: 'clamp(56px, 6vw, 96px)',
                    }}
                >
                    {loading ? (
                        <div
                            className="flex items-center gap-3"
                            style={{ color: 'var(--vesper-text-2)' }}
                        >
                            <Loader2 className="vesper-spin" size={18} />
                            Loading {network.name} catalogue from TMDB…
                        </div>
                    ) : error ? (
                        <div
                            className="vesper-glass rounded-xl p-6"
                            style={{ color: '#ffb5b5' }}
                        >
                            {error}
                        </div>
                    ) : items.length === 0 ? (
                        <div
                            className="vesper-glass rounded-xl p-6"
                            style={{ color: 'var(--vesper-text-2)' }}
                        >
                            No {subTab === 'tv' ? 'TV shows' : 'movies'}{' '}
                            currently streamable on {network.name} (US region).
                        </div>
                    ) : (
                        <>
                            <div
                                className="flex flex-wrap"
                                style={{ gap: 'clamp(14px, 1.25vw, 24px)' }}
                            >
                                {items.map((item) => (
                                    <NetworkPosterTile
                                        key={`${subTab}-${item.tmdb_id}`}
                                        item={item}
                                    />
                                ))}
                            </div>

                            {/* Load-more sentinel + button */}
                            <div
                                ref={sentinelRef}
                                className="flex items-center justify-center"
                                style={{ marginTop: 48 }}
                            >
                                {page < totalPages ? (
                                    <button
                                        data-testid="network-load-more"
                                        data-focusable="true"
                                        data-focus-style="pill"
                                        tabIndex={0}
                                        onClick={loadMore}
                                        disabled={loadingMore}
                                        className="flex items-center gap-2 h-12 px-6 rounded-full font-sans font-semibold"
                                        style={{
                                            background:
                                                'rgba(var(--vesper-blue-rgb),0.12)',
                                            color: 'var(--vesper-blue)',
                                            border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                                            fontSize: 15,
                                            opacity: loadingMore ? 0.6 : 1,
                                        }}
                                    >
                                        {loadingMore ? (
                                            <Loader2
                                                className="vesper-spin"
                                                size={16}
                                            />
                                        ) : (
                                            <Plus size={16} />
                                        )}
                                        {loadingMore
                                            ? 'Loading more…'
                                            : `Load more (${(
                                                  totalResults -
                                                  items.length
                                              ).toLocaleString()} remaining)`}
                                    </button>
                                ) : (
                                    <div
                                        className="vesper-mono"
                                        style={{
                                            fontSize: 11,
                                            color: 'var(--vesper-text-3)',
                                            letterSpacing: '0.22em',
                                            textTransform: 'uppercase',
                                        }}
                                    >
                                        End of catalogue · {items.length.toLocaleString()} titles
                                    </div>
                                )}
                            </div>
                        </>
                    )}
                </section>
            </main>
        </div>
    );
}

const SubTab = ({ active, label, testId, onClick }) => (
    <button
        data-testid={testId}
        data-focusable="true"
        data-focus-style="pill"
        tabIndex={0}
        onClick={onClick}
        className="font-sans font-semibold rounded-full"
        style={{
            height: 'clamp(36px, 3vw, 44px)',
            paddingLeft: 'clamp(16px, 1.4vw, 22px)',
            paddingRight: 'clamp(16px, 1.4vw, 22px)',
            fontSize: 'clamp(13px, 0.95vw, 15px)',
            letterSpacing: '-0.01em',
            background: active
                ? 'var(--vesper-blue)'
                : 'rgba(255,255,255,0.04)',
            color: active ? 'var(--vesper-bg-0)' : 'var(--vesper-text-2)',
            border: active
                ? '1px solid transparent'
                : '1px solid rgba(255,255,255,0.08)',
            transition: 'background-color 180ms ease, color 180ms ease',
        }}
    >
        {label}
    </button>
);
