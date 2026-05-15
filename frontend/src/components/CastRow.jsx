/**
 * <CastRow/> — horizontal cast strip rendered below the Detail
 * page's autoplay / streams section.
 *
 * Behaviour:
 *   • Loads top-billed cast from /api/tmdb/credits/{type}/{tmdb_id}.
 *   • Renders each actor as a 132×196 B&W portrait card with name +
 *     character below.  Hover/focus brings the portrait back to
 *     colour for a "selected" feel.
 *   • Focused actor (D-pad highlight OR pointer hover/tap on mobile)
 *     is reported up to Detail.jsx via `onFocus(actor)` so the page's
 *     hero backdrop swaps to a giant B&W portrait of them.
 *   • Click / OK navigates to /person/{tmdb_id} (the new actor profile
 *     page).
 *
 * Props:
 *   tmdbId      — TMDB id of the title.  Required.
 *   mediaType   — 'movie' or 'tv'.  Required.
 *   onFocus(p)  — called whenever an actor card receives focus / is
 *                 hovered / is the centre-most on mobile.  Passed the
 *                 actor object ({id, name, character, profile, ...})
 *                 or `null` when focus leaves the row.
 *   testId      — optional data-testid override.
 *
 * The component is intentionally self-contained — it owns its own
 * fetch + cache, so the Detail page just renders it and forgets.
 */
import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import useIsMobile from '@/lib/useIsMobile';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;

export default function CastRow({ tmdbId, mediaType, onFocus, testId = 'cast-row' }) {
    const [cast, setCast] = useState([]);
    const [busy, setBusy] = useState(true);
    const navigate = useNavigate();
    const isMobile = useIsMobile();
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
        // Only fire onFocus(null) if we're losing focus from the
        // currently-tracked actor (otherwise the next card's focus
        // event would race with this blur).
        if (focusedRef.current?.id === actor?.id) {
            focusedRef.current = null;
            if (onFocus) onFocus(null);
        }
    }, [onFocus]);

    /* Hide the section entirely while loading + when there is no
       cast — much cleaner than rendering an empty rail. */
    if (busy || cast.length === 0) return null;

    return (
        <section
            data-testid={testId}
            className="mt-10"
            style={{ width: '100%' }}
        >
            <h3
                className="vesper-display mb-5"
                style={{ fontSize: 26, letterSpacing: '-0.02em' }}
            >
                Cast
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 12,
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
                    gap: 18,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    paddingBottom: 16,
                    /* Hide scrollbar on TV / desktop — keep visible on
                       mobile so users know they can swipe. */
                    scrollbarWidth: isMobile ? 'thin' : 'none',
                }}
            >
                {cast.map((actor) => (
                    <ActorCard
                        key={actor.id}
                        actor={actor}
                        onFocus={() => handleFocus(actor)}
                        onBlur={() => handleBlur(actor)}
                        onPick={() => navigate(`/person/${actor.id}`)}
                    />
                ))}
            </div>
        </section>
    );
}

/* ───────────────────────── ActorCard ───────────────────────── */

function ActorCard({ actor, onFocus, onBlur, onPick }) {
    const fallback = actor.name?.charAt(0)?.toUpperCase() || '?';
    const [focused, setFocused] = React.useState(false);
    return (
        <button
            data-testid={`cast-actor-${actor.id}`}
            data-focusable="true"
            data-focus-style="poster"
            tabIndex={0}
            onClick={onPick}
            onFocus={(e) => {
                setFocused(true);
                onFocus?.(e);
            }}
            onBlur={(e) => {
                setFocused(false);
                onBlur?.(e);
            }}
            onMouseEnter={(e) => onFocus?.(e)}
            onMouseLeave={(e) => onBlur?.(e)}
            style={{
                flexShrink: 0,
                width: 132,
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
                    width: 132,
                    height: 196,
                    borderRadius: 14,
                    overflow: 'hidden',
                    position: 'relative',
                    background: 'rgba(255,255,255,0.05)',
                    border: focused
                        ? '2px solid var(--vesper-blue)'
                        : '1px solid rgba(255,255,255,0.08)',
                    transition: 'border 120ms ease',
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
                            /* B&W by default; full colour on focus.
                               This is the "beautiful B&W" + "instant
                               feedback on scroll" feeling the user
                               asked for. */
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
                            fontFamily: 'var(--vesper-display-font, sans-serif)',
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
