/**
 * <UpcomingMoviesShelf/> — bottom Home row.
 *
 * Pulls TMDB's next-60-day upcoming movies via the backend
 * `/api/tmdb/upcoming-movies` endpoint and renders them as a
 * horizontal rail of poster tiles.  Tapping a tile navigates to the
 * Detail page (using the resolved IMDB id when available, falling
 * back to TMDB id otherwise) — Detail.jsx already handles the
 * "no streams yet" case with the cinematic StreamUnavailableModal
 * and the trailer pill.
 *
 * Lightweight: one fetch on mount, results live in component state.
 * Empty state renders nothing (the rail just doesn't appear).
 */
import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import PosterTile from './PosterTile';

const API = process.env.REACT_APP_BACKEND_URL;

export default function UpcomingMoviesShelf() {
    const navigate = useNavigate();
    const [items, setItems] = useState([]);
    const [loading, setLoading] = useState(true);
    const [diag, setDiag] = useState({ status: 'idle', count: 0, err: '' });
    /* "Unlock (testing)" toggle from Settings — when ON we render a
     * stub state showing whether the upcoming-movies API call
     * succeeded, returned empty, or failed.  Lets the user
     * differentiate "backend missing the endpoint" from "endpoint
     * returned no upcoming movies".  Watches an event so flipping
     * the toggle re-renders immediately. */
    const [unlock, setUnlock] = useState(() => {
        try { return localStorage.getItem('onnowtv-dev-unlock') === '1'; }
        catch { return false; }
    });
    useEffect(() => {
        const sync = () => {
            try { setUnlock(localStorage.getItem('onnowtv-dev-unlock') === '1'); }
            catch { /* ignore */ }
        };
        window.addEventListener('onnowtv:dev-unlock-changed', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('onnowtv:dev-unlock-changed', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);

    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                const r = await axios.get(
                    `${API}/api/tmdb/upcoming-movies`,
                    { params: { limit: 20, days: 60 }, timeout: 15_000 }
                );
                if (cancel) return;
                const data = Array.isArray(r?.data?.data) ? r.data.data : [];
                setDiag({
                    status: data.length === 0 ? 'empty' : 'ok',
                    count: data.length,
                    err: '',
                });
                setItems(
                    data.map((m) => ({
                        id: m.imdb_id || `tmdb:${m.tmdb_id}`,
                        imdbId: m.imdb_id,
                        tmdbId: m.tmdb_id,
                        type: 'movie',
                        title: m.title,
                        poster: m.poster,
                        background: m.backdrop,
                        year: m.year,
                        sub: m.year,
                        description: m.synopsis,
                        routePath: m.imdb_id
                            ? `/title/movie/${m.imdb_id}`
                            : null,
                    }))
                );
            } catch (e) {
                if (cancel) return;
                /* Capture HTTP status for the diag banner so the user
                 * can see "endpoint not deployed" (404) vs network
                 * error vs server crash without DevTools. */
                const status = e?.response?.status;
                setDiag({
                    status: 'error',
                    count: 0,
                    err: status ? `HTTP ${status}` : (e?.message || 'fetch failed'),
                });
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, []);

    /* When the API returns nothing AND the user hasn't enabled the
     * unlock toggle, hide the row entirely — that's the production
     * UX.  Unlock mode shows a diagnostic stub instead so the user
     * can see WHY the row is empty (404, network, etc.). */
    if (!loading && items.length === 0 && !unlock) return null;

    return (
        <section
            data-testid="upcoming-movies-shelf"
            className="relative w-full vesper-shelf-section"
            style={{
                paddingTop: 'clamp(14px, 1.4vw, 24px)',
                paddingBottom: 'clamp(14px, 1.4vw, 24px)',
            }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                }}
            >
                <div className="flex items-baseline gap-4 min-w-0">
                    <span className="vesper-eyebrow truncate">UPCOMING</span>
                    <h2
                        className="vesper-display truncate"
                        style={{
                            fontSize: 'clamp(22px, 2.2vw, 34px)',
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        Coming soon
                    </h2>
                </div>
                <span
                    className="vesper-mono shrink-0"
                    style={{
                        color: 'var(--vesper-text-3)',
                        fontSize: 'clamp(9px, 0.62vw, 11px)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    Next 60 days
                </span>
            </header>

            <div
                className="vesper-shelf flex"
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                    paddingTop: 'clamp(14px, 1.4vw, 22px)',
                    paddingBottom: 'clamp(14px, 1.4vw, 24px)',
                    transform: 'translateZ(0)',
                    willChange: 'scroll-position',
                    scrollSnapType: 'x proximity',
                    overscrollBehavior: 'contain',
                }}
            >
                {loading && items.length === 0 && (
                    Array.from({ length: 6 }).map((_, i) => (
                        <UpcomingSkeleton key={i} />
                    ))
                )}
                {!loading && items.length === 0 && unlock && (
                    <div
                        data-testid="upcoming-diag"
                        style={{
                            padding: '16px 22px',
                            background: 'rgba(255,180,60,0.08)',
                            border: '1px dashed rgba(255,180,60,0.45)',
                            borderRadius: 12,
                            color: '#FFD8A1',
                            fontFamily: 'monospace',
                            fontSize: 12,
                            lineHeight: 1.55,
                            maxWidth: 700,
                        }}
                    >
                        <div style={{
                            fontWeight: 800, marginBottom: 6,
                            letterSpacing: '0.18em', fontSize: 10,
                        }}>
                            UNLOCK · UPCOMING-MOVIES DIAGNOSTIC
                        </div>
                        <div>status: <b>{diag.status}</b></div>
                        <div>items returned: <b>{diag.count}</b></div>
                        {diag.err && <div>error: <b>{diag.err}</b></div>}
                        <div style={{ marginTop: 8, opacity: 0.8 }}>
                            Endpoint: <code>{API}/api/tmdb/upcoming-movies</code>
                        </div>
                        {diag.err && diag.err.includes('404') && (
                            <div style={{ marginTop: 8, color: '#FFB069' }}>
                                ⚠ Backend doesn't have this endpoint yet —
                                you need to redeploy the FastAPI server
                                code to your Contabo VPS (the new
                                `/api/tmdb/upcoming-movies` route ships
                                in this build's backend folder).
                            </div>
                        )}
                    </div>
                )}
                {items.map((item) => (
                    <PosterTile
                        key={item.id}
                        item={item}
                        onSelect={(it) => {
                            if (it.routePath) navigate(it.routePath);
                            else if (it.tmdbId) {
                                // Fall back to TMDB-id route; Resolve
                                // page converts TMDB → IMDB and
                                // forwards to /title/movie/<imdb>.
                                navigate(`/resolve/movie/${it.tmdbId}`);
                            }
                        }}
                    />
                ))}
            </div>
        </section>
    );
}

function UpcomingSkeleton() {
    return (
        <div
            aria-hidden="true"
            style={{
                width: 'clamp(140px, 11vw, 200px)',
                aspectRatio: '2 / 3',
                borderRadius: 12,
                background:
                    'linear-gradient(110deg, rgba(255,255,255,0.04) 30%,' +
                    ' rgba(255,255,255,0.08) 50%,' +
                    ' rgba(255,255,255,0.04) 70%)',
                backgroundSize: '200% 100%',
                animation: 'vesper-shimmer 1.6s linear infinite',
                flexShrink: 0,
            }}
        />
    );
}
