/**
 * <CastRow/> — bottom cast strip on the Detail page.
 *
 * TWO MODES, swapped in-place (no layout shift):
 *   1. Cast (default) — top-billed actors, B&W → color on focus.
 *      OK on an actor card drills into MODE 2.
 *   2. Filmography — same lane, same card size, now shows the
 *      focused actor's combined movie + TV credits.  Focusing a
 *      card swaps the page hero + backdrop wallpaper to that
 *      title (driven by `onMovieFocus`).  OK navigates to that
 *      title's detail page.  UP exits filmography back to cast.
 *
 * Props:
 *   tmdbId       — TMDB id of the current title.
 *   mediaType    — 'movie' | 'tv' for the title.
 *   onFocus(p)   — fires when an actor card receives focus
 *                  (cast mode) or null when focus leaves.
 *   onMovieFocus(m) — fires when a filmography card receives
 *                     focus or null when focus leaves.
 *   onModeChange(mode) — fires 'cast' or 'filmography' so the
 *                       parent can swap the "Press OK..." hint.
 */
import React, { useEffect, useRef, useState, useCallback } from 'react';
import axios from 'axios';
import { useNavigate } from 'react-router-dom';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;

export default function CastRow({
    tmdbId,
    mediaType,
    onFocus,
    onMovieFocus,
    onModeChange,
    testId = 'cast-row',
}) {
    const navigate = useNavigate();
    const [cast, setCast] = useState([]);
    const [busy, setBusy] = useState(true);

    /* Filmography state.  `filmographyFor` holds the actor whose
     * films are currently displayed (null when in cast mode).
     * `films` is the list returned by /api/tmdb/person/:id. */
    const [filmographyFor, setFilmographyFor] = useState(null);
    const [films, setFilms] = useState([]);
    const [filmsBusy, setFilmsBusy] = useState(false);
    /* Cache by person id so a second drill-in is instant. */
    const filmCacheRef = useRef(new Map());

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

    /* Tell the parent which mode we're in so it can adjust the
     * hint text in the Play-button area. */
    useEffect(() => {
        if (onModeChange) onModeChange(filmographyFor ? 'filmography' : 'cast');
    }, [filmographyFor, onModeChange]);

    /* When user enters filmography mode on a fresh actor, fetch
     * their credits.  Cache hits are instant. */
    useEffect(() => {
        if (!filmographyFor) {
            setFilms([]);
            return undefined;
        }
        const personId = filmographyFor.id;
        const cache = filmCacheRef.current;
        if (cache.has(personId)) {
            setFilms(cache.get(personId));
            setFilmsBusy(false);
            return undefined;
        }
        let cancel = false;
        setFilmsBusy(true);
        (async () => {
            try {
                const { data } = await axios.get(
                    `${API}/tmdb/person/${personId}`,
                    { timeout: 12000 }
                );
                if (cancel) return;
                const list = Array.isArray(data?.filmography) ? data.filmography : [];
                cache.set(personId, list);
                setFilms(list);
            } catch {
                if (!cancel) setFilms([]);
            } finally {
                if (!cancel) setFilmsBusy(false);
            }
        })();
        return () => { cancel = true; };
    }, [filmographyFor]);

    const handleActorFocus = useCallback((actor) => {
        focusedRef.current = actor;
        if (onFocus) onFocus(actor);
    }, [onFocus]);

    const handleActorBlur = useCallback((actor) => {
        if (focusedRef.current?.id === actor?.id) {
            focusedRef.current = null;
            if (onFocus) onFocus(null);
        }
    }, [onFocus]);

    const handleMovieFocus = useCallback((movie) => {
        if (onMovieFocus) onMovieFocus(movie);
    }, [onMovieFocus]);

    const handleMovieBlur = useCallback(() => {
        if (onMovieFocus) onMovieFocus(null);
    }, [onMovieFocus]);

    /* Drill into an actor's filmography.  Clears the cast-mode
     * actor focus so the hero can reset, then sets the
     * filmographyFor actor which triggers a re-render with the
     * movies list and lands focus on the first movie card. */
    const drillIntoActor = useCallback((actor) => {
        if (onFocus) onFocus(null);
        setFilmographyFor(actor);
    }, [onFocus]);

    /* Activate a film card — resolve its IMDB id then navigate
     * to the detail page.  We hit /api/tmdb/imdb/{type}/{id} for
     * the lookup (already cached server-side). */
    const openFilm = useCallback(async (film) => {
        try {
            const { data } = await axios.get(
                `${API}/tmdb/imdb/${film.media_type}/${film.tmdb_id}`,
                { timeout: 8000 }
            );
            const imdb = data?.imdb_id;
            if (imdb) {
                navigate(`/title/${film.media_type}/${imdb}`);
            }
        } catch {
            /* swallow — no nav if the lookup fails */
        }
    }, [navigate]);

    /* D-pad UP from a film card → exit filmography back to cast.
     * Parent's keyboard handler doesn't see film cards, so the
     * row itself owns this exit path.  Listen with capture so we
     * intercept before the global spatial-focus code. */
    useEffect(() => {
        if (!filmographyFor) return undefined;
        const onKey = (e) => {
            if (e.key !== 'ArrowUp') return;
            const active = document.activeElement;
            if (!active) return;
            if (!active.matches('[data-testid^="cast-film-"]')) return;
            e.preventDefault();
            e.stopPropagation();
            if (onMovieFocus) onMovieFocus(null);
            const exitingActor = filmographyFor;
            setFilmographyFor(null);
            /* After the cast cards re-render, focus the actor we
             * drilled in from. */
            requestAnimationFrame(() => requestAnimationFrame(() => {
                const target = document.querySelector(
                    `[data-testid="cast-actor-${exitingActor.id}"]`
                );
                if (target) {
                    try { target.focus({ preventScroll: false }); } catch { /* ignore */ }
                }
            }));
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [filmographyFor, onMovieFocus]);

    /* Auto-focus the first film card whenever filmography first
     * loads, so the user doesn't have to press RIGHT just to land
     * inside the freshly-swapped row. */
    useEffect(() => {
        if (!filmographyFor || filmsBusy || films.length === 0) return;
        requestAnimationFrame(() => requestAnimationFrame(() => {
            const target = document.querySelector('[data-testid^="cast-film-"]');
            if (target) {
                try { target.focus({ preventScroll: false }); } catch { /* ignore */ }
            }
        }));
    }, [filmographyFor, filmsBusy, films.length]);

    if (busy || cast.length === 0) return null;

    const inFilmography = !!filmographyFor;
    const items = inFilmography ? films : cast;

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
                    paddingLeft: 80,
                }}
            >
                {inFilmography ? `${filmographyFor.name}'s work` : 'Cast'}
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 10,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {inFilmography
                        ? (filmsBusy ? 'loading…' : `${films.length} titles`)
                        : `${cast.length} actors`}
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
                    paddingLeft: 80,
                    paddingRight: 80,
                    paddingTop: 12,
                    paddingBottom: 14,
                    scrollPaddingLeft: 80,
                    scrollPaddingRight: 80,
                    scrollbarWidth: 'none',
                }}
            >
                {inFilmography
                    ? items.map((film) => (
                        <FilmCard
                            key={`${film.media_type}-${film.tmdb_id}`}
                            film={film}
                            onActivate={() => openFilm(film)}
                            onFocus={() => handleMovieFocus(film)}
                            onBlur={handleMovieBlur}
                        />
                    ))
                    : items.map((actor) => (
                        <ActorCard
                            key={actor.id}
                            actor={actor}
                            onFocus={() => handleActorFocus(actor)}
                            onBlur={() => handleActorBlur(actor)}
                            onActivate={() => drillIntoActor(actor)}
                        />
                    ))}
            </div>
        </section>
    );
}

/* ───────────────────────── ActorCard ───────────────────────── */

function ActorCard({ actor, onFocus, onBlur, onActivate }) {
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
            onClick={() => onActivate?.()}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                width: 'clamp(116px, 9.4vw, 172px)',
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

            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.93) 78%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div className="absolute inset-x-0 bottom-0 p-3">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 'clamp(12px, 0.78vw, 14px)',
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

/* ───────────────────────── FilmCard ───────────────────────── */

function FilmCard({ film, onActivate, onFocus, onBlur }) {
    const [focused, setFocused] = useState(false);
    return (
        <button
            data-testid={`cast-film-${film.media_type}-${film.tmdb_id}`}
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
            onClick={() => onActivate?.()}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                /* IDENTICAL size to ActorCard so swapping modes is
                 * visually seamless — no layout shift, no jitter. */
                width: 'clamp(116px, 9.4vw, 172px)',
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
            {film.poster ? (
                <img
                    src={film.poster}
                    alt={film.title}
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                    style={{
                        filter: focused
                            ? 'grayscale(0) contrast(1.02)'
                            : 'grayscale(1) contrast(1.02) brightness(0.92)',
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
                            fontSize: 48,
                            color: 'rgba(var(--vesper-blue-rgb),0.18)',
                        }}
                    >
                        {(film.title || '?')[0]}
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

            <div className="absolute inset-x-0 bottom-0 p-3">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 'clamp(12px, 0.78vw, 14px)',
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
                    {film.title}
                </div>
                {(film.year || film.media_type) && (
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
                        {[film.media_type === 'tv' ? 'TV' : 'Movie', film.year]
                            .filter(Boolean)
                            .join(' · ')}
                    </div>
                )}
            </div>
        </button>
    );
}
