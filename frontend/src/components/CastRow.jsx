/**
 * <CastRow/> — horizontal cast strip on the Detail page.
 *
 * Single mode: renders top-billed actors as B&W portrait cards.
 * Focusing an actor reports up to the parent via `onFocus(actor)`
 * so the page hero swaps the movie title + synopsis to the actor's
 * name + bio.  Cards turn full color on focus.
 *
 * Props:
 *   tmdbId      — TMDB id of the title (current detail page).
 *   mediaType   — 'movie' or 'tv'.
 *   onFocus(p)  — called when an actor card receives focus / is
 *                 hovered; passed the actor or `null` when focus
 *                 leaves the row.
 *   testId      — optional data-testid override.
 *
 * Click is a no-op — the only interaction is focus.  Pressing OK
 * on the remote just keeps focus where it is.  This matches the
 * user's requested behaviour: hero swap is purely focus-driven.
 */
import React, { useEffect, useRef, useState, useCallback } from 'react';
import axios from 'axios';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;

export default function CastRow({ tmdbId, mediaType, onFocus, testId = 'cast-row' }) {
    const [cast, setCast] = useState([]);
    const [busy, setBusy] = useState(true);

    const focusedRef = useRef(null);

    useEffect(() => {
        let cancel = false;
        if (!tmdbId || !mediaType) {
            setBusy(false);
            return undefined;
        }
        (async () => {
            try {
                const { data } = await axios.get(
                    `${API}/tmdb/credits/${mediaType}/${tmdbId}`,
                    { timeout: 10000 }
                );
                if (!cancel) {
                    setCast(Array.isArray(data?.cast) ? data.cast : []);
                    setBusy(false);
                }
            } catch {
                if (!cancel) {
                    setCast([]);
                    setBusy(false);
                }
            }
        })();
        return () => { cancel = true; };
    }, [tmdbId, mediaType]);

    const handleFocus = useCallback((actor) => {
        focusedRef.current = actor;
        if (onFocus) onFocus(actor);
    }, [onFocus]);

    const handleBlur = useCallback((actor) => {
        if (focusedRef.current?.id === actor?.id) {
            focusedRef.current = null;
            if (onFocus) onFocus(null);
        }
    }, [onFocus]);

    if (busy || cast.length === 0) return null;

    return (
        <section
            data-testid={testId}
            style={{ width: '100%' }}
        >
            <h3
                className="vesper-display"
                style={{
                    fontSize: 22,
                    letterSpacing: '-0.02em',
                    marginBottom: 14,
                }}
            >
                Cast
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 11,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {cast.length} actors
                </span>
            </h3>

            <div
                data-testid={`${testId}-strip`}
                className="vesper-shelf"
                style={{
                    display: 'flex',
                    gap: 12,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    paddingBottom: 8,
                    paddingRight: 80,
                    scrollPaddingRight: 80,
                    scrollbarWidth: 'none',
                }}
            >
                {cast.map((actor) => (
                    <ActorCard
                        key={actor.id}
                        actor={actor}
                        onFocus={() => handleFocus(actor)}
                        onBlur={() => handleBlur(actor)}
                    />
                ))}
            </div>
        </section>
    );
}

/* ───────────────────────── ActorCard ───────────────────────── */

function ActorCard({ actor, onFocus, onBlur }) {
    const fallback = actor.name?.charAt(0)?.toUpperCase() || '?';
    const [focused, setFocused] = React.useState(false);
    return (
        <button
            data-testid={`cast-actor-${actor.id}`}
            data-focusable="true"
            data-focus-style="poster"
            tabIndex={0}
            onFocus={(e) => {
                setFocused(true);
                onFocus?.(e);
            }}
            onBlur={(e) => {
                setFocused(false);
                onBlur?.(e);
            }}
            onMouseEnter={(e) => { setFocused(true); onFocus?.(e); }}
            onMouseLeave={(e) => { setFocused(false); onBlur?.(e); }}
            style={{
                flexShrink: 0,
                width: 108,
                background: 'transparent',
                border: 'none',
                padding: 0,
                cursor: 'pointer',
                textAlign: 'left',
                color: 'inherit',
                outline: 'none',
                WebkitTapHighlightColor: 'transparent',
            }}
        >
            <div
                style={{
                    width: 108,
                    height: 162,
                    borderRadius: 12,
                    overflow: 'hidden',
                    position: 'relative',
                    background: 'rgba(255,255,255,0.05)',
                    border: focused
                        ? '3px solid var(--vesper-blue)'
                        : '1px solid rgba(255,255,255,0.08)',
                    boxShadow: focused
                        ? '0 16px 32px rgba(93,200,255,0.35), 0 4px 12px rgba(0,0,0,0.45)'
                        : 'none',
                    transform: focused ? 'translateY(-4px)' : 'translateY(0)',
                    transition: 'border 120ms ease, transform 160ms ease, box-shadow 160ms ease',
                }}
            >
                {actor.profile ? (
                    <img
                        src={actor.profile}
                        alt={actor.name}
                        loading="lazy"
                        style={{
                            width: '100%',
                            height: '100%',
                            objectFit: 'cover',
                            filter: focused
                                ? 'grayscale(0) contrast(1.05)'
                                : 'grayscale(1) contrast(1.05) brightness(0.92)',
                            transition: 'filter 180ms ease',
                        }}
                    />
                ) : (
                    <div
                        style={{
                            width: '100%', height: '100%',
                            display: 'flex',
                            alignItems: 'center', justifyContent: 'center',
                            color: 'var(--vesper-text-3)',
                            fontSize: 40, fontWeight: 700,
                        }}
                    >
                        {fallback}
                    </div>
                )}
            </div>
            <div
                style={{
                    marginTop: 8,
                    fontSize: 13,
                    fontWeight: 600,
                    color: focused ? 'var(--vesper-text)' : 'var(--vesper-text-2)',
                    lineHeight: 1.25,
                    overflow: 'hidden',
                    display: '-webkit-box',
                    WebkitLineClamp: 1,
                    WebkitBoxOrient: 'vertical',
                }}
            >
                {actor.name}
            </div>
            {actor.character && (
                <div
                    style={{
                        marginTop: 2,
                        fontSize: 11,
                        color: 'var(--vesper-text-3)',
                        lineHeight: 1.3,
                        overflow: 'hidden',
                        display: '-webkit-box',
                        WebkitLineClamp: 1,
                        WebkitBoxOrient: 'vertical',
                    }}
                >
                    as {actor.character}
                </div>
            )}
        </button>
    );
}
