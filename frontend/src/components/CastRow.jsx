/**
 * <CastRow/> — horizontal cast strip on the Detail page.
 *
 * Two display modes:
 *
 *  1. **Cast mode** (default).  Renders top-billed actors as 132×196
 *     B&W portrait cards with name + character.  Focusing/hovering an
 *     actor reports up to the parent via `onFocus(actor)` so the page
 *     hero swaps to the actor's portrait + name.
 *
 *  2. **Filmography mode** (after pressing OK / clicking an actor).
 *     The same strip morphs IN-PLACE into the actor's filmography.
 *     Header changes to the actor's name + age + birthplace; row
 *     shows movie/TV posters; a "Back to cast" pill returns to mode 1.
 *
 *  This matches the user's screenshots — a single horizontal strip
 *  that *reveals* the actor's body of work without a page navigation.
 *
 *  Props:
 *    tmdbId      — TMDB id of the title (current detail page).
 *    mediaType   — 'movie' or 'tv'.
 *    onFocus(p)  — called when an actor card receives focus / is
 *                  hovered; passed the actor or `null` when focus
 *                  leaves the row.
 *    testId      — optional data-testid override.
 */
import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { ArrowLeft, ChevronRight } from 'lucide-react';
import useIsMobile from '@/lib/useIsMobile';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;

export default function CastRow({ tmdbId, mediaType, onFocus, testId = 'cast-row' }) {
    const [cast, setCast] = useState([]);
    const [busy, setBusy] = useState(true);

    /* When non-null, we render filmography mode for this actor. */
    const [revealed, setRevealed] = useState(null);

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

    /* When the user navigates back to cast mode, restore the page
       hero to whatever was last hovered/focused.  Most of the time
       that means clearing it (revealed mode = no focused actor). */
    useEffect(() => {
        if (onFocus) onFocus(revealed);
    }, [revealed, onFocus]);

    const handleFocus = useCallback((actor) => {
        focusedRef.current = actor;
        if (!revealed && onFocus) onFocus(actor);
    }, [onFocus, revealed]);

    const handleBlur = useCallback((actor) => {
        if (focusedRef.current?.id === actor?.id) {
            focusedRef.current = null;
            if (!revealed && onFocus) onFocus(null);
        }
    }, [onFocus, revealed]);

    if (busy || cast.length === 0) return null;

    return (
        <section
            data-testid={testId}
            className="mt-10"
            style={{ width: '100%' }}
        >
            {!revealed ? (
                <CastHeader count={cast.length} />
            ) : (
                <RevealedHeader
                    actor={revealed}
                    onBack={() => setRevealed(null)}
                    onProfile={() => navigate(`/person/${revealed.id}`)}
                />
            )}

            <div
                data-testid={`${testId}-strip`}
                className="vesper-shelf"
                style={{
                    display: 'flex',
                    gap: 12,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    paddingBottom: 16,
                    /* Tail padding so the LAST card's focus glow
                     * isn't clipped by the page's right edge as
                     * the user scrolls it into view. */
                    paddingRight: 80,
                    scrollPaddingRight: 80,
                    scrollbarWidth: isMobile ? 'thin' : 'none',
                }}
            >
                {!revealed
                    ? cast.map((actor) => (
                        <ActorCard
                            key={actor.id}
                            actor={actor}
                            onFocus={() => handleFocus(actor)}
                            onBlur={() => handleBlur(actor)}
                            onPick={() => setRevealed(actor)}
                        />
                    ))
                    : <ActorFilmography actor={revealed} />
                }
            </div>
        </section>
    );
}

function CastHeader({ count }) {
    return (
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
                {count} actors
            </span>
        </h3>
    );
}

function RevealedHeader({ actor, onBack, onProfile }) {
    return (
        <div
            className="mb-5"
            style={{
                display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap',
            }}
        >
            <button
                data-testid="cast-row-back"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onBack}
                style={{
                    display: 'inline-flex', alignItems: 'center', gap: 8,
                    height: 40, padding: '0 16px',
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 999,
                    color: 'var(--vesper-text-2)',
                    fontSize: 13, fontWeight: 600, letterSpacing: '0.04em',
                    cursor: 'pointer',
                    WebkitTapHighlightColor: 'transparent',
                }}
            >
                <ArrowLeft size={16} /> Back to cast
            </button>
            <h3
                className="vesper-display"
                style={{
                    fontSize: 26, letterSpacing: '-0.02em',
                    margin: 0,
                }}
            >
                {actor.name}'s films
            </h3>
            <button
                data-testid="cast-row-profile"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onProfile}
                style={{
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    height: 32, padding: '0 14px',
                    background: 'transparent',
                    border: '1px solid rgba(93,200,255,0.45)',
                    borderRadius: 999,
                    color: 'var(--vesper-blue)',
                    fontSize: 11, fontWeight: 700, letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    cursor: 'pointer',
                    WebkitTapHighlightColor: 'transparent',
                    marginLeft: 'auto',
                }}
            >
                Full profile <ChevronRight size={14} />
            </button>
        </div>
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

/* ───────────────────── Actor Filmography (reveal) ─────────────── */

function ActorFilmography({ actor }) {
    const [films, setFilms] = useState([]);
    const [busy, setBusy] = useState(true);
    const navigate = useNavigate();

    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                const { data } = await axios.get(
                    `${API}/tmdb/person/${actor.id}`,
                    { timeout: 12000 }
                );
                if (!cancel) {
                    setFilms(Array.isArray(data?.filmography) ? data.filmography : []);
                    setBusy(false);
                }
            } catch {
                if (!cancel) {
                    setFilms([]);
                    setBusy(false);
                }
            }
        })();
        return () => { cancel = true; };
    }, [actor.id]);

    const handlePick = async (film) => {
        try {
            const { data } = await axios.get(
                `${API}/tmdb/imdb/${film.media_type}/${film.tmdb_id}`,
                { timeout: 8000 }
            );
            if (data?.imdb_id) {
                navigate(
                    `/title/${film.media_type === 'tv' ? 'series' : 'movie'}/${data.imdb_id}`
                );
            }
        } catch {
            /* swallow */
        }
    };

    if (busy) {
        return (
            <div style={{ color: 'var(--vesper-text-3)', padding: '40px 8px', fontSize: 13 }}>
                Loading {actor.name}'s filmography…
            </div>
        );
    }

    if (films.length === 0) {
        return (
            <div style={{ color: 'var(--vesper-text-3)', padding: '40px 8px', fontSize: 13 }}>
                No filmography found for {actor.name}.
            </div>
        );
    }

    return (
        <>
            {films.map((film) => (
                <FilmCard
                    key={`${film.media_type}-${film.tmdb_id}`}
                    film={film}
                    onPick={() => handlePick(film)}
                />
            ))}
        </>
    );
}

function FilmCard({ film, onPick }) {
    const [focused, setFocused] = useState(false);
    return (
        <button
            data-testid={`cast-film-${film.media_type}-${film.tmdb_id}`}
            data-focusable="true"
            data-focus-style="poster"
            tabIndex={0}
            onClick={onPick}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            onMouseEnter={() => setFocused(true)}
            onMouseLeave={() => setFocused(false)}
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
                    transform: focused ? 'translateY(-4px)' : 'translateY(0)',
                    boxShadow: focused
                        ? '0 16px 32px rgba(93,200,255,0.35), 0 4px 12px rgba(0,0,0,0.45)'
                        : '0 2px 8px rgba(0,0,0,0.3)',
                    transition: 'transform 160ms ease, box-shadow 160ms ease, border 120ms ease',
                }}
            >
                {film.poster ? (
                    <img
                        src={film.poster}
                        alt={film.title}
                        loading="lazy"
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    />
                ) : (
                    <div
                        style={{
                            width: '100%', height: '100%',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            padding: '0 10px', textAlign: 'center',
                            color: 'var(--vesper-text-3)', fontSize: 16, fontWeight: 700,
                        }}
                    >
                        {film.title}
                    </div>
                )}
                {film.media_type === 'tv' && (
                    <span
                        className="vesper-mono"
                        style={{
                            position: 'absolute',
                            top: 8, left: 8,
                            padding: '3px 8px', borderRadius: 999,
                            background: 'rgba(6,8,15,0.85)',
                            color: '#8de0ff',
                            fontSize: 9, letterSpacing: '0.18em',
                            border: '1px solid rgba(93,200,255,0.55)',
                            fontWeight: 700,
                        }}
                    >
                        TV
                    </span>
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
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                }}
            >
                {film.title}
            </div>
            {(film.character || film.year) && (
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
                    {film.character && <span>as {film.character}</span>}
                    {film.character && film.year && <span> · </span>}
                    {film.year && <span>{film.year}</span>}
                </div>
            )}
        </button>
    );
}
