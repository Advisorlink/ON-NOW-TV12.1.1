/**
 * Actor profile page — `/person/:tmdbId`.
 *
 * TV-optimised single-screen layout (1920×1080, 16:9):
 *   • Compact hero (≈360 px tall) — eyebrow + name + meta + 3-line
 *     bio + Save toggle on the LEFT, framed portrait (200 px) on
 *     the RIGHT.  Nothing scrolls inside the hero itself.
 *   • Filmography area scrolls underneath — two stacked sections
 *     ("Movies", "TV Shows") of compact poster tiles in a
 *     responsive grid (~10 columns at 1080p).
 *
 * D-pad navigation:
 *   • BACK pill is the initial focus.
 *   • DOWN from BACK / Save → first film card.
 *   • RIGHT / LEFT inside a row.
 *   • UP / DOWN between rows.  Spatial focus auto-scrolls the row
 *     into view via `scroll-margin`.
 *   • Long-press OK on any film → Add to Watch Later / My List.
 */
import React, { useEffect, useState, useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import axios from 'axios';
import { ArrowLeft, Loader2, Home } from 'lucide-react';
import SideNav from '@/components/SideNav';
import useBackHandler from '@/hooks/useBackHandler';
import useLongPress from '@/hooks/useLongPress';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import {
    isActorInLibrary,
    addActorToLibrary,
    removeActorFromLibrary,
} from '@/lib/library';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;

export default function Person() {
    const { tmdbId } = useParams();
    const navigate = useNavigate();
    const [data, setData] = useState(null);
    const [busy, setBusy] = useState(true);
    const [err, setErr] = useState(null);

    useSpatialFocus();
    useBackHandler(() => navigate(-1));

    useEffect(() => {
        let cancel = false;
        if (!tmdbId) {
            setBusy(false);
            return undefined;
        }
        (async () => {
            try {
                const res = await axios.get(`${API}/tmdb/person/${tmdbId}`, {
                    timeout: 15000,
                });
                if (!cancel) {
                    setData(res.data || null);
                    setBusy(false);
                }
            } catch (e) {
                if (!cancel) {
                    setErr(e?.message || 'Failed to load actor');
                    setBusy(false);
                }
            }
        })();
        return () => { cancel = true; };
    }, [tmdbId]);

    return (
        <div
            data-testid="person-page"
            style={{
                position: 'fixed',
                inset: 0,
                background: 'var(--vesper-bg-0)',
                color: 'var(--vesper-text)',
                overflow: 'hidden',
            }}
        >
            <SideNav />

            <main
                style={{
                    position: 'absolute',
                    inset: '0 0 0 100px',
                    overflowY: 'auto',
                }}
            >
                {busy && (
                    <div
                        className="absolute inset-0 flex items-center justify-center"
                        style={{ color: 'var(--vesper-text-2)' }}
                    >
                        <Loader2 className="vesper-spin" size={32} />
                    </div>
                )}
                {!busy && err && (
                    <div
                        className="absolute inset-0 flex flex-col items-center justify-center gap-3"
                        style={{ color: 'var(--vesper-text-2)' }}
                    >
                        <p>Couldn't load actor profile.</p>
                    </div>
                )}
                {!busy && !err && data && (
                    <PersonContent
                        data={data}
                        tmdbId={tmdbId}
                        navigate={navigate}
                    />
                )}
            </main>
        </div>
    );
}

function PersonContent({ data, tmdbId, navigate }) {
    const {
        name,
        biography,
        age,
        deathday,
        place_of_birth,
        profile,
        known_for_department,
        filmography = [],
    } = data;

    const { movies, shows } = useMemo(() => {
        const m = [];
        const s = [];
        for (const f of filmography) {
            if (f.media_type === 'tv') s.push(f);
            else m.push(f);
        }
        const byPop = (a, b) => (b.popularity || 0) - (a.popularity || 0);
        return { movies: m.sort(byPop), shows: s.sort(byPop) };
    }, [filmography]);

    const [saved, setSaved] = useState(() => isActorInLibrary(tmdbId));
    // Save toggle removed from the UI — long-press OK on the
    // actor card handles add/remove.  We keep the state alive
    // anyway so future UI (e.g. a heart badge) can hook into it.
    const toggleSaved = () => {
        if (saved) {
            removeActorFromLibrary(tmdbId);
            setSaved(false);
        } else {
            addActorToLibrary({ id: tmdbId, name, profile });
            setSaved(true);
        }
    };
    // Silence unused-variable lint — toggleSaved is reserved for
    // future use (long-press alternative, contextual menus, etc.).
    void toggleSaved;

    return (
        <>
            {/* HERO — compact 340 px tall, hard-capped so the page
                fits 16:9 at 1080p without inner scroll. */}
            <section
                data-testid="person-hero"
                style={{
                    position: 'relative',
                    width: '100%',
                    height: 340,
                    overflow: 'hidden',
                }}
            >
                {/* Background — heavily blurred & dimmed portrait. */}
                {profile && (
                    <div
                        aria-hidden="true"
                        style={{
                            position: 'absolute',
                            inset: 0,
                            backgroundImage: `url(${profile})`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center 15%',
                            filter:
                                'grayscale(1) contrast(1.05) brightness(0.32) blur(10px)',
                            transform: 'scale(1.18)',
                        }}
                    />
                )}
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', inset: 0,
                        background: `linear-gradient(180deg,
                            rgba(6,8,15,0.55) 0%,
                            rgba(6,8,15,0.7) 70%,
                            var(--vesper-bg-0) 100%)`,
                    }}
                />

                <div
                    style={{
                        position: 'relative',
                        zIndex: 2,
                        padding: '24px 56px 28px 56px',
                        height: '100%',
                        display: 'grid',
                        gridTemplateColumns: 'minmax(0,1fr) 320px',
                        gap: 32,
                        alignItems: 'stretch',
                    }}
                >
                    {/* LEFT */}
                    <div
                        style={{
                            display: 'flex',
                            flexDirection: 'column',
                            gap: 14,
                            minWidth: 0,
                        }}
                    >
                        <div style={{ display: 'flex', gap: 8 }}>
                            <button
                                data-testid="person-back"
                                data-focusable="true"
                                data-focus-style="pill"
                                data-initial-focus="true"
                                tabIndex={0}
                                onClick={() => navigate(-1)}
                                className="flex items-center gap-2 rounded-full vesper-mono"
                                style={{
                                    height: 36,
                                    paddingLeft: 14,
                                    paddingRight: 18,
                                    background: 'rgba(17,24,39,0.6)',
                                    color: 'var(--vesper-text-2)',
                                    border: '1px solid rgba(255,255,255,0.12)',
                                    fontSize: 11,
                                    letterSpacing: '0.2em',
                                    textTransform: 'uppercase',
                                }}
                            >
                                <ArrowLeft size={14} /> Back
                            </button>
                            <button
                                data-testid="person-home"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => navigate('/')}
                                className="flex items-center gap-2 rounded-full vesper-mono"
                                style={{
                                    height: 36,
                                    paddingLeft: 14,
                                    paddingRight: 18,
                                    background: 'rgba(17,24,39,0.6)',
                                    color: 'var(--vesper-text-2)',
                                    border: '1px solid rgba(255,255,255,0.12)',
                                    fontSize: 11,
                                    letterSpacing: '0.2em',
                                    textTransform: 'uppercase',
                                }}
                            >
                                <Home size={14} /> Home
                            </button>
                        </div>

                        <div style={{ minWidth: 0 }}>
                            <div
                                className="vesper-eyebrow"
                                style={{ marginBottom: 6 }}
                            >
                                {known_for_department || 'Actor'} · From TMDB
                            </div>
                            <h1
                                className="vesper-display"
                                data-testid="person-name"
                                style={{
                                    fontSize: 'clamp(40px, 3.4vw, 56px)',
                                    letterSpacing: '-0.035em',
                                    lineHeight: 1.0,
                                    margin: 0,
                                    overflow: 'hidden',
                                    display: '-webkit-box',
                                    WebkitLineClamp: 1,
                                    WebkitBoxOrient: 'vertical',
                                }}
                            >
                                {name}
                            </h1>

                            <div
                                className="flex items-center gap-3 vesper-meta flex-wrap"
                                style={{ fontSize: 14, marginTop: 10 }}
                            >
                                {age != null && (
                                    <span style={{ color: 'var(--vesper-blue)' }}>
                                        {deathday ? `Died at ${age}` : `${age} years old`}
                                    </span>
                                )}
                                {place_of_birth && age != null && <Bullet />}
                                {place_of_birth && <span>{place_of_birth}</span>}
                                {filmography.length > 0 && <Bullet />}
                                {filmography.length > 0 && (
                                    <span>{filmography.length} titles</span>
                                )}
                            </div>

                            {biography && (
                                <p
                                    data-testid="person-bio"
                                    style={{
                                        fontSize: 13,
                                        lineHeight: 1.55,
                                        color: 'var(--vesper-text-2)',
                                        maxWidth: '68ch',
                                        display: '-webkit-box',
                                        WebkitLineClamp: 3,
                                        WebkitBoxOrient: 'vertical',
                                        overflow: 'hidden',
                                        marginTop: 14,
                                    }}
                                >
                                    {biography}
                                </p>
                            )}
                        </div>

                        <div style={{ marginTop: 'auto' }}>
                            {/* Save toggle removed per user request —
                                long-press OK on the actor card adds /
                                removes them from My Actors. */}
                        </div>
                    </div>

                    {/* RIGHT — B&W portrait with strong soft-edge
                        fade.  Three layered masks (radial → top
                        gradient → bottom gradient) make the photo
                        dissolve into pure black on every side.
                        No card frame, no shadow — the actor
                        appears to simply stand in the dark. */}
                    {profile && (
                        <div
                            data-testid="person-portrait-card"
                            style={{
                                width: 320,
                                aspectRatio: '5 / 8',
                                position: 'relative',
                                alignSelf: 'center',
                                pointerEvents: 'none',
                                /* Vertical fades are now LIGHTER on
                                 * the bottom so chins are never
                                 * masked.  Radial centre shifted UP
                                 * slightly and ellipse widened so the
                                 * face + shoulders stay fully visible. */
                                WebkitMaskImage:
                                    'radial-gradient(ellipse 82% 88% at 50% 50%, ' +
                                    '#000 22%, ' +
                                    'rgba(0,0,0,0.85) 55%, ' +
                                    'rgba(0,0,0,0.45) 75%, ' +
                                    'rgba(0,0,0,0.12) 92%, ' +
                                    'transparent 100%), ' +
                                    'linear-gradient(180deg, transparent 0%, #000 12%, #000 92%, transparent 100%), ' +
                                    'linear-gradient(90deg, transparent 0%, #000 14%, #000 86%, transparent 100%)',
                                WebkitMaskComposite: 'source-in',
                                maskImage:
                                    'radial-gradient(ellipse 82% 88% at 50% 50%, ' +
                                    '#000 22%, ' +
                                    'rgba(0,0,0,0.85) 55%, ' +
                                    'rgba(0,0,0,0.45) 75%, ' +
                                    'rgba(0,0,0,0.12) 92%, ' +
                                    'transparent 100%), ' +
                                    'linear-gradient(180deg, transparent 0%, #000 12%, #000 92%, transparent 100%), ' +
                                    'linear-gradient(90deg, transparent 0%, #000 14%, #000 86%, transparent 100%)',
                                maskComposite: 'intersect',
                            }}
                        >
                            <img
                                src={profile}
                                alt={name}
                                style={{
                                    width: '100%',
                                    height: '100%',
                                    objectFit: 'cover',
                                    /* Keep full head + chin in
                                     * frame: position 30 % from
                                     * top of the source photo
                                     * (most TMDB headshots have
                                     * the head in the top 60 %). */
                                    objectPosition: 'center 30%',
                                    display: 'block',
                                    filter:
                                        'grayscale(1) contrast(1.08) brightness(0.95)',
                                }}
                            />
                        </div>
                    )}
                </div>
            </section>

            {/* FILMOGRAPHY */}
            <section
                data-testid="person-filmography"
                style={{ padding: '16px 56px 80px 56px' }}
            >
                {movies.length > 0 && (
                    <FilmGroup
                        title="Movies"
                        items={movies}
                        navigate={navigate}
                        testId="person-movies"
                    />
                )}
                {shows.length > 0 && (
                    <FilmGroup
                        title="TV Shows"
                        items={shows}
                        navigate={navigate}
                        testId="person-shows"
                        topMargin
                    />
                )}
            </section>
        </>
    );
}

function FilmGroup({ title, items, navigate, testId, topMargin }) {
    return (
        <div
            data-testid={testId}
            style={{ marginTop: topMargin ? 36 : 0 }}
        >
            <h2
                className="vesper-display"
                style={{
                    fontSize: 18,
                    letterSpacing: '-0.02em',
                    marginBottom: 14,
                }}
            >
                {title}
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 10,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {items.length} title{items.length === 1 ? '' : 's'}
                </span>
            </h2>

            <div
                className="grid"
                style={{
                    gridTemplateColumns:
                        'repeat(auto-fill, minmax(132px, 1fr))',
                    gap: 14,
                }}
            >
                {items.map((film) => (
                    <FilmCard
                        key={`${film.media_type}-${film.tmdb_id}`}
                        film={film}
                        navigate={navigate}
                    />
                ))}
            </div>
        </div>
    );
}

function FilmCard({ film, navigate }) {
    const openTitle = async () => {
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
        } catch { /* swallow */ }
    };

    const onLongPress = async () => {
        try {
            const { data } = await axios.get(
                `${API}/tmdb/imdb/${film.media_type}/${film.tmdb_id}`,
                { timeout: 8000 }
            );
            if (!data?.imdb_id) return;
            window.dispatchEvent(
                new CustomEvent('vesper:request-add-to-list', {
                    detail: {
                        id: data.imdb_id,
                        type: film.media_type === 'tv' ? 'series' : 'movie',
                        title: film.title,
                        poster: film.poster,
                        background: film.backdrop,
                        year: film.year,
                        synopsis: film.overview,
                    },
                })
            );
        } catch { /* swallow */ }
    };
    const press = useLongPress(onLongPress, openTitle);

    return (
        <button
            data-testid={`person-film-${film.media_type}-${film.tmdb_id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            {...press}
            className="group relative block overflow-hidden text-left"
            style={{
                aspectRatio: '2 / 3',
                borderRadius: 10,
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
                padding: 0,
                /* Keep tile inside the viewport when D-pad focus
                 * jumps into it — prevents the bottom-most rows
                 * being hidden under the page edge. */
                scrollMarginTop: 24,
                scrollMarginBottom: 24,
            }}
        >
            {film.poster ? (
                <img
                    src={film.poster}
                    alt={film.title}
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
                        {(film.title || '?')[0]}
                    </span>
                </div>
            )}

            <div
                className="absolute inset-x-0 bottom-0 pointer-events-none"
                style={{
                    height: '50%',
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.92) 70%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div
                className="absolute inset-x-0 bottom-0"
                style={{ padding: '8px 10px 10px' }}
            >
                <div
                    className="font-sans"
                    style={{
                        fontSize: 12,
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.2,
                        color: 'var(--vesper-text)',
                        overflow: 'hidden',
                        display: '-webkit-box',
                        WebkitLineClamp: 1,
                        WebkitBoxOrient: 'vertical',
                    }}
                >
                    {film.title}
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 9,
                        letterSpacing: '0.16em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-text-3)',
                        marginTop: 2,
                    }}
                >
                    {[
                        film.year,
                        film.rating != null ? `★ ${film.rating}` : null,
                    ].filter(Boolean).join(' · ')}
                </div>
            </div>
        </button>
    );
}

function Bullet() {
    return (
        <span
            className="inline-block w-1 h-1 rounded-full"
            style={{ background: 'rgba(255,255,255,0.32)' }}
        />
    );
}
