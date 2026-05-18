/**
 * <CastRow/> — horizontal cast strip on the Detail page.
 *
 * Single mode: renders top-billed actors as poster-style cards.
 * Focusing an actor reports up to the parent via `onFocus(actor)`
 * so the page hero swaps the movie title + synopsis to the actor's
 * name + bio.
 *
 * Visuals match the home-screen PosterTile exactly:
 *   • aspect-ratio 2 / 3, 12 px rounded corners
 *   • `data-focus-style="tile"` → globally-styled scale(1.08)
 *     translateY(-2px) + 3 px solid cyan ring on focus
 *   • Same dark fallback when there's no profile image
 * One Cast-row-specific behaviour layered on top:
 *   • B&W filter by default, full color on focus
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
                    fontSize: 18,
                    letterSpacing: '-0.02em',
                    marginBottom: 10,
                }}
            >
                Cast
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 10,
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
                    gap: 14,
                    overflowX: 'auto',
                    overflowY: 'visible',
                    paddingTop: 10,
                    paddingBottom: 12,
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

/* ───────────────────────── ActorCard ─────────────────────────
 * Built on the same skeleton as <PosterTile/> from the home
 * screen so the focus animation (scale + ring) comes from the
 * global  `data-focus-style="tile"`  CSS rule instead of
 * component-level transforms.  The only Cast-specific override
 * is the B&W → color filter swap.
 */
function ActorCard({ actor, onFocus, onBlur }) {
    const fallback = actor.name?.charAt(0)?.toUpperCase() || '?';
    const [focused, setFocused] = useState(false);
    return (
        <button
            data-testid={`cast-actor-${actor.id}`}
            data-focusable="true"
            data-focus-style="tile"
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
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                /* Smaller than home-screen poster tiles so the
                 * whole cast row fits cleanly UNDER the Play
                 * button.  Same 2/3 aspect + global focus animation
                 * as poster tiles, just shrunk for the cast lane. */
                width: 'clamp(88px, 7vw, 128px)',
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
                scrollMarginTop: 24,
                scrollMarginBottom: 24,
                transform: 'translateZ(0)',
                backfaceVisibility: 'hidden',
                willChange: 'transform',
            }}
        >
            {actor.profile ? (
                <img
                    src={actor.profile}
                    alt={actor.name}
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                    style={{
                        filter: focused
                            ? 'grayscale(0) contrast(1.05)'
                            : 'grayscale(1) contrast(1.05) brightness(0.92)',
                        transition: 'filter 200ms ease',
                    }}
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
                        {fallback}
                    </span>
                </div>
            )}

            {/* Bottom-fade gradient identical to PosterTile so
                the name + character row reads cleanly over any
                portrait. */}
            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.93) 78%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div className="absolute inset-x-0 bottom-0 p-2.5">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 'clamp(11px, 0.72vw, 13px)',
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.15,
                        color: 'var(--vesper-text)',
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
                        className="vesper-mono mt-1"
                        style={{
                            fontSize: 9,
                            letterSpacing: '0.16em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                            overflow: 'hidden',
                            display: '-webkit-box',
                            WebkitLineClamp: 1,
                            WebkitBoxOrient: 'vertical',
                        }}
                    >
                        as {actor.character}
                    </div>
                )}
            </div>
        </button>
    );
}
