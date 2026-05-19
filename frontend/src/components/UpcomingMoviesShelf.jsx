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
                        // Tell PosterTile exactly where to send the user.
                        // When IMDB id is known → standard Detail route.
                        // Otherwise fall back to TMDB find-by route so
                        // Detail.jsx can still resolve metadata.
                        routePath: m.imdb_id
                            ? `/title/movie/${m.imdb_id}`
                            : null,
                    }))
                );
            } catch {
                /* swallow — empty rail is acceptable */
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, []);

    if (!loading && items.length === 0) return null;

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
