import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { API } from '@/lib/api';
import * as img from '@/lib/img';
import useLongPress from '@/hooks/useLongPress';

/**
 * Poster tile for TMDB-sourced network catalogues.  The TMDB discover
 * response only carries TMDB ids — not IMDB — so on click we resolve
 * the IMDB id via `/api/tmdb/imdb/{type}/{tmdbId}` before routing to
 * the existing `/title/{type}/{imdbId}` detail page.  Resolution is
 * cached on the backend for 7 days, so the first click on a given
 * title is the only slow one.
 *
 * Long-press (OK held >700 ms on the remote, or tap-and-hold on
 * mobile) opens the "Add to My List" modal — same UX as the
 * Home-page <PosterTile/>, so users have one mental model for
 * adding to library across the whole app.
 */
export default function NetworkPosterTile({ item }) {
    const navigate = useNavigate();
    const [resolving, setResolving] = useState(false);
    const [error, setError] = useState(false);

    const tmdbType = item.type === 'series' ? 'tv' : 'movie';

    const handleClick = async () => {
        if (resolving) return;
        setResolving(true);
        setError(false);
        try {
            const r = await fetch(
                `${API}/tmdb/imdb/${tmdbType}/${item.tmdb_id}`,
                { cache: 'force-cache' }
            );
            const data = await r.json();
            const imdbId = data?.imdb_id;
            if (!imdbId) {
                setError(true);
                setTimeout(() => setError(false), 2200);
                return;
            }
            navigate(
                `/title/${item.type}/${imdbId}` +
                (item.type === 'movie' ? '?autoplay=1' : '')
            );
        } catch {
            setError(true);
            setTimeout(() => setError(false), 2200);
        } finally {
            setResolving(false);
        }
    };

    /* Long-press → "Add to My List" modal.  We need to resolve the
       IMDB id first (the AddToListModal keys library entries on
       imdb ids, matching the rest of the app).  Resolution is
       cached so subsequent long-presses on the same tile are
       instant. */
    const handleLongPress = async () => {
        try {
            const r = await fetch(
                `${API}/tmdb/imdb/${tmdbType}/${item.tmdb_id}`,
                { cache: 'force-cache' }
            );
            const data = await r.json();
            const imdbId = data?.imdb_id;
            if (!imdbId) return;
            window.dispatchEvent(new CustomEvent('vesper:request-add-to-list', {
                detail: {
                    id:       imdbId,
                    type:     item.type,    // 'movie' or 'series'
                    title:    item.title,
                    poster:   img.poster(item.poster),
                    year:     item.year ? String(item.year).slice(0, 4) : '',
                    synopsis: item.overview || item.synopsis || '',
                },
            }));
        } catch {
            /* swallow — user can try again or click for detail page */
        }
    };

    const press = useLongPress(handleLongPress, handleClick);

    const ratingLabel =
        item.rating && item.rating > 0 ? `★ ${Number(item.rating).toFixed(1)}` : null;
    const sub = [item.year ? item.year.slice(0, 4) : null, ratingLabel]
        .filter(Boolean)
        .join(' · ');

    return (
        <button
            data-testid={`network-tile-${item.tmdb_id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            ref={press.ref}
            onKeyDown={press.onKeyDown}
            onKeyUp={press.onKeyUp}
            onMouseDown={press.onMouseDown}
            onMouseUp={press.onMouseUp}
            onMouseLeave={press.onMouseLeave}
            onTouchStart={press.onTouchStart}
            onTouchMove={press.onTouchMove}
            onTouchEnd={press.onTouchEnd}
            onTouchCancel={press.onTouchCancel}
            onClick={press.onClick}
            onContextMenu={press.onContextMenu}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                width: 'clamp(120px, 10.5vw, 180px)',
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
            }}
        >
            {item.poster ? (
                <img
                    src={img.poster(item.poster)}
                    alt={item.title}
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                />
            ) : (
                <div
                    className="absolute inset-0 flex items-center justify-center"
                    style={{
                        background:
                            'linear-gradient(180deg, var(--vesper-bg-2) 0%, var(--vesper-bg-1) 100%)',
                    }}
                >
                    <span
                        className="vesper-display"
                        style={{
                            fontSize: 64,
                            color: 'rgba(var(--vesper-blue-rgb),0.18)',
                        }}
                    >
                        {(item.title || '?')[0]}
                    </span>
                </div>
            )}

            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.93) 78%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div className="absolute inset-x-0 bottom-0 p-4">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 'clamp(13px, 1vw, 17px)',
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.15,
                        color: 'var(--vesper-text)',
                    }}
                >
                    {item.title}
                </div>
                {sub && (
                    <div
                        className="vesper-mono mt-1.5"
                        style={{
                            fontSize: 'clamp(9px, 0.62vw, 11px)',
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        {sub}
                    </div>
                )}
            </div>

            {/* Resolution overlay */}
            {(resolving || error) && (
                <div
                    className="absolute inset-0 flex items-center justify-center"
                    style={{
                        background: error
                            ? 'rgba(80,8,8,0.55)'
                            : 'rgba(6,8,15,0.55)',
                        backdropFilter: 'blur(2px)',
                    }}
                >
                    {error ? (
                        <span
                            className="vesper-mono"
                            style={{
                                color: '#ffb5b5',
                                fontSize: 11,
                                letterSpacing: '0.18em',
                                textTransform: 'uppercase',
                            }}
                        >
                            No IMDb match
                        </span>
                    ) : (
                        <Loader2
                            className="vesper-spin"
                            size={22}
                            style={{ color: 'var(--vesper-blue)' }}
                        />
                    )}
                </div>
            )}
        </button>
    );
}
