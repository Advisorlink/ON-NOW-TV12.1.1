/**
 * <CastRow/> — bottom lane on the Detail page.
 *
 * The lane has TWO LEVELS, swapped by D-pad UP/DOWN:
 *   • Level 1 (default) — "Cast" actors.
 *   • Level 2           — "Just like this" similar movies/shows.
 *
 * Level 1 has a sub-state: pressing OK on an actor drills into
 * that actor's filmography (still on level 1, same physical row —
 * just different content).  UP from a filmography card exits back
 * to the same actor card.
 *
 * Navigation summary (all internal to this component):
 *   Cast actor    DOWN  → Similar (level 2), focus first
 *   Cast actor    OK    → Filmography (still level 1)
 *   Filmography   UP    → Cast, focus the actor we drilled in from
 *   Filmography   DOWN  → Similar (level 2)
 *   Filmography   OK    → navigate to that title's detail page
 *   Similar       UP    → Cast (level 1), focus first actor
 *   Similar       OK    → navigate to that title's detail page
 *
 * UP from Cast actor → handled by the parent (Detail.jsx) → focuses
 * the Autoplay button.  The parent watches `cast-actor-*` only.
 *
 * Props:
 *   tmdbId         — TMDB id of the current title.
 *   mediaType      — 'movie' | 'tv'.
 *   onFocus(p)     — actor focus (level 1, cast mode).
 *   onMovieFocus(m)— film/title focus (filmography OR similar).
 *   onViewChange(v)— 'cast' | 'filmography' | 'similar' for the
 *                    parent to update hint text / dot indicator.
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
    onViewChange,
    testId = 'cast-row',
}) {
    const navigate = useNavigate();
    const [cast, setCast] = useState([]);
    const [busy, setBusy] = useState(true);

    /* Level 1 sub-state: filmography drill-in. */
    const [filmographyFor, setFilmographyFor] = useState(null);
    const [films, setFilms] = useState([]);
    const [filmsBusy, setFilmsBusy] = useState(false);
    const filmCacheRef = useRef(new Map());

    /* Level 2 state: similar movies / TV shows. */
    const [similar, setSimilar] = useState([]);
    /* Which "level" of the lane is currently in view.  Drives the
     * 2-dot indicator + the "just like this" affordance up in
     * Detail.jsx via `onViewChange`. */
    const [level, setLevel] = useState(1);     // 1 = cast/filmography, 2 = similar

    const focusedRef = useRef(null);

    /* ── Fetch cast on mount ── */
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

    /* ── Fetch similar on mount (level 2 source) ── */
    useEffect(() => {
        let cancel = false;
        if (!tmdbId || !mediaType) return undefined;
        (async () => {
            try {
                const { data } = await axios.get(
                    `${API}/tmdb/recommendations/${mediaType}/${tmdbId}`,
                    { timeout: 10000 }
                );
                if (!cancel) {
                    setSimilar(Array.isArray(data?.results) ? data.results : []);
                }
            } catch {
                if (!cancel) setSimilar([]);
            }
        })();
        return () => { cancel = true; };
    }, [tmdbId, mediaType]);

    /* Tell parent which view is active. */
    const currentView = filmographyFor
        ? 'filmography'
        : level === 2
        ? 'similar'
        : 'cast';
    useEffect(() => {
        if (onViewChange) onViewChange(currentView);
    }, [currentView, onViewChange]);

    /* When user enters filmography mode, fetch credits. */
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

    /* ── Focus reporters ── */
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

    /* ── State transitions ── */
    const drillIntoActor = useCallback((actor) => {
        if (onFocus) onFocus(null);
        setFilmographyFor(actor);
    }, [onFocus]);

    const enterSimilar = useCallback(() => {
        if (onFocus) onFocus(null);
        if (onMovieFocus) onMovieFocus(null);
        setFilmographyFor(null);
        setLevel(2);
    }, [onFocus, onMovieFocus]);

    const exitToCast = useCallback((preferActor) => {
        if (onMovieFocus) onMovieFocus(null);
        setFilmographyFor(null);
        setLevel(1);
        requestAnimationFrame(() => requestAnimationFrame(() => {
            const sel = preferActor
                ? `[data-testid="cast-actor-${preferActor.id}"]`
                : '[data-testid^="cast-actor-"]';
            const target = document.querySelector(sel);
            if (target) {
                try { target.focus({ preventScroll: false }); } catch { /* ignore */ }
            }
        }));
    }, [onMovieFocus]);

    /* Activate a film/similar card — resolve IMDB id and navigate. */
    const openTitle = useCallback(async (item) => {
        try {
            const { data } = await axios.get(
                `${API}/tmdb/imdb/${item.media_type}/${item.tmdb_id}`,
                { timeout: 8000 }
            );
            const imdb = data?.imdb_id;
            if (imdb) {
                navigate(`/title/${item.media_type === 'tv' ? 'series' : 'movie'}/${imdb}`);
            }
        } catch {
            /* swallow — no nav if the lookup fails */
        }
    }, [navigate]);

    /* ── D-pad navigation between the lane sub-views ── */
    useEffect(() => {
        const onKey = (e) => {
            if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;
            const active = document.activeElement;
            if (!active) return;
            const onActor = active.matches('[data-testid^="cast-actor-"]');
            const onFilm  = active.matches('[data-testid^="cast-film-"]');
            const onSimilar = active.matches('[data-testid^="cast-similar-"]');

            if (e.key === 'ArrowDown') {
                if (onActor) {
                    if (!similar.length) return;          // no similar → fall through
                    e.preventDefault(); e.stopPropagation();
                    enterSimilar();
                    return;
                }
                if (onFilm) {
                    if (!similar.length) return;
                    e.preventDefault(); e.stopPropagation();
                    enterSimilar();
                    return;
                }
            }

            if (e.key === 'ArrowUp') {
                if (onFilm) {
                    e.preventDefault(); e.stopPropagation();
                    exitToCast(filmographyFor);          // back to drill-in actor
                    return;
                }
                if (onSimilar) {
                    e.preventDefault(); e.stopPropagation();
                    exitToCast(null);                    // back to first cast actor
                }
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [filmographyFor, similar.length, enterSimilar, exitToCast]);

    /* Auto-focus first card whenever view changes to filmography
     * or similar (so the user lands inside the new content). */
    useEffect(() => {
        if (currentView === 'filmography' && !filmsBusy && films.length > 0) {
            requestAnimationFrame(() => requestAnimationFrame(() => {
                const t = document.querySelector('[data-testid^="cast-film-"]');
                if (t) { try { t.focus({ preventScroll: false }); } catch { /* ignore */ } }
            }));
        } else if (currentView === 'similar' && similar.length > 0) {
            requestAnimationFrame(() => requestAnimationFrame(() => {
                const t = document.querySelector('[data-testid^="cast-similar-"]');
                if (t) { try { t.focus({ preventScroll: false }); } catch { /* ignore */ } }
            }));
        }
    }, [currentView, filmsBusy, films.length, similar.length]);

    if (busy || cast.length === 0) return null;

    const items =
        currentView === 'filmography' ? films :
        currentView === 'similar'     ? similar :
        cast;

    return (
        <section
            data-testid={testId}
            style={{ width: '100%' }}
        >
            <div
                style={{
                    paddingLeft: 80,
                    paddingRight: 80,
                    display: 'flex',
                    alignItems: 'flex-end',
                    justifyContent: 'space-between',
                    marginBottom: 10,
                    gap: 16,
                }}
            >
                <h3
                    className="vesper-display"
                    style={{
                        fontSize: 18,
                        letterSpacing: '-0.02em',
                        margin: 0,
                    }}
                >
                    {currentView === 'filmography'
                        ? `${filmographyFor.name}'s work`
                        : currentView === 'similar'
                        ? 'Just like this'
                        : 'Cast'}
                    <span
                        className="ml-3 vesper-mono"
                        style={{
                            fontSize: 10,
                            color: 'var(--vesper-text-3)',
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                        }}
                    >
                        {currentView === 'filmography'
                            ? (filmsBusy ? 'loading…' : `${films.length} titles`)
                            : currentView === 'similar'
                            ? `${similar.length} titles`
                            : `${cast.length} actors`}
                    </span>
                </h3>

                {/* RIGHT-SIDE AFFORDANCE — dot stack + level hint.
                    Cast view : 2 dots (top filled), ↓ JUST LIKE THIS.
                    Similar   : 2 dots (bottom filled), ↑ CAST.
                    Filmography: dots stay on top (still level 1),
                    no hint (user is inside a sub-mode).            */}
                <LevelIndicator view={currentView} hasSimilar={similar.length > 0} />
            </div>

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
                {currentView === 'cast' && items.map((actor) => (
                    <ActorCard
                        key={actor.id}
                        actor={actor}
                        onFocus={() => handleActorFocus(actor)}
                        onBlur={() => handleActorBlur(actor)}
                        onActivate={() => drillIntoActor(actor)}
                    />
                ))}
                {currentView === 'filmography' && items.map((film) => (
                    <TitleCard
                        key={`f-${film.media_type}-${film.tmdb_id}`}
                        item={film}
                        testIdPrefix="cast-film"
                        onActivate={() => openTitle(film)}
                        onFocus={() => handleMovieFocus(film)}
                        onBlur={handleMovieBlur}
                    />
                ))}
                {currentView === 'similar' && items.map((film) => (
                    <TitleCard
                        key={`s-${film.media_type}-${film.tmdb_id}`}
                        item={film}
                        testIdPrefix="cast-similar"
                        onActivate={() => openTitle(film)}
                        onFocus={() => handleMovieFocus(film)}
                        onBlur={handleMovieBlur}
                    />
                ))}
            </div>
        </section>
    );
}

/* ────────────────────── LevelIndicator ────────────────────── */

function LevelIndicator({ view, hasSimilar }) {
    if (!hasSimilar) return null;
    const onSimilar = view === 'similar';
    return (
        <div
            data-testid="cast-level-indicator"
            style={{
                display: 'flex',
                alignItems: 'center',
                gap: 14,
                pointerEvents: 'none',
            }}
        >
            <span
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.24em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-text-3)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 6,
                }}
            >
                {onSimilar ? (
                    <>
                        <span>↑ Cast</span>
                    </>
                ) : view === 'filmography' ? (
                    <>
                        <span>↑ Autoplay · ↓ Just like this</span>
                    </>
                ) : (
                    <>
                        <span>↓ Just like this</span>
                    </>
                )}
            </span>
            <div
                style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 6,
                    alignItems: 'center',
                }}
            >
                <Dot active={!onSimilar} />
                <Dot active={onSimilar} />
            </div>
        </div>
    );
}

function Dot({ active }) {
    return (
        <span
            style={{
                width: active ? 9 : 6,
                height: active ? 9 : 6,
                borderRadius: '50%',
                background: active
                    ? 'var(--vesper-blue-bright)'
                    : 'rgba(255,255,255,0.28)',
                boxShadow: active ? '0 0 10px rgba(93,200,255,0.5)' : 'none',
                transition: 'all 160ms ease',
            }}
        />
    );
}

/* ────────────────────── ActorCard ────────────────────── */

function ActorCard({ actor, onFocus, onBlur, onActivate }) {
    const fallback = actor.name?.charAt(0)?.toUpperCase() || '?';
    const [focused, setFocused] = useState(false);
    return (
        <button
            data-testid={`cast-actor-${actor.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onFocus={(e) => { setFocused(true); onFocus?.(e); }}
            onBlur={(e) => { setFocused(false); onBlur?.(e); }}
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

/* ────────────────────── TitleCard ──────────────────────
 * Shared card for filmography + similar.  Same size as the
 * ActorCard so the row swaps content with zero layout shift.
 */
function TitleCard({ item, testIdPrefix, onActivate, onFocus, onBlur }) {
    return (
        <button
            data-testid={`${testIdPrefix}-${item.media_type}-${item.tmdb_id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onFocus={(e) => onFocus?.(e)}
            onBlur={(e) => onBlur?.(e)}
            onMouseEnter={(e) => onFocus?.(e)}
            onMouseLeave={(e) => onBlur?.(e)}
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
            {item.poster ? (
                <img
                    src={item.poster}
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
                            fontSize: 48,
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
                    {item.title}
                </div>
                {(item.year || item.media_type) && (
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
                        {[item.media_type === 'tv' ? 'TV' : 'Movie', item.year]
                            .filter(Boolean)
                            .join(' · ')}
                    </div>
                )}
            </div>
        </button>
    );
}
