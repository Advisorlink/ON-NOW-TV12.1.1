/**
 * Actor profile page — `/person/:tmdbId`.
 *
 * Hero:
 *   • Left column — eyebrow + giant name + meta + bio.
 *   • Right column — full-color portrait card with subtle frame
 *     and depth shadow.  Vertical edge fade so the portrait
 *     dissolves into the dark hero.
 *
 * Filmography:
 *   • Two grouped sections: Movies, TV.  Heading + count for each.
 *   • 6-column responsive grid (auto-fill).  Each poster tile uses
 *     the home-screen card style (2/3 aspect, scale on focus, B&W
 *     fallback letter).  Long-press = "Add to Watch Later / My List".
 *   • OK navigates into that title's Detail page.
 */
import React, { useEffect, useState, useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import axios from 'axios';
import { ArrowLeft, Loader2, Heart, HeartOff } from 'lucide-react';
import SideNav from '@/components/SideNav';
import useBackHandler from '@/hooks/useBackHandler';
import useLongPress from '@/hooks/useLongPress';
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
                        <p className="vesper-mono" style={{ fontSize: 12 }}>
                            {err}
                        </p>
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

    /* Group filmography by media type, sorted by popularity. */
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
    const toggleSaved = () => {
        if (saved) {
            removeActorFromLibrary(tmdbId);
            setSaved(false);
        } else {
            addActorToLibrary({ id: tmdbId, name, profile });
            setSaved(true);
        }
    };

    return (
        <>
            {/* HERO — split layout: text left, portrait right. */}
            <section
                data-testid="person-hero"
                style={{
                    position: 'relative',
                    width: '100%',
                    minHeight: 'clamp(440px, 62vh, 760px)',
                    overflow: 'hidden',
                }}
            >
                {/* Background — desaturated portrait, very dim. */}
                {profile && (
                    <div
                        aria-hidden="true"
                        style={{
                            position: 'absolute',
                            inset: 0,
                            backgroundImage: `url(${profile})`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center 20%',
                            filter: 'grayscale(1) contrast(1.05) brightness(0.35) blur(8px)',
                            transform: 'scale(1.15)',
                        }}
                    />
                )}
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', inset: 0,
                        background: `linear-gradient(180deg,
                            rgba(6,8,15,0.55) 0%,
                            rgba(6,8,15,0.65) 50%,
                            rgba(6,8,15,0.9) 85%,
                            var(--vesper-bg-0) 100%)`,
                    }}
                />

                <div
                    style={{
                        position: 'relative',
                        zIndex: 2,
                        padding: '56px 80px 72px 80px',
                        height: '100%',
                        display: 'grid',
                        gridTemplateColumns: 'minmax(0,1.4fr) minmax(280px,420px)',
                        gap: 64,
                        alignItems: 'end',
                    }}
                >
                    {/* LEFT — back + title + meta + bio */}
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
                        <button
                            data-testid="person-back"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            onClick={() => navigate(-1)}
                            className="flex items-center gap-2 h-11 rounded-full vesper-mono"
                            style={{
                                alignSelf: 'flex-start',
                                paddingLeft: 18,
                                paddingRight: 22,
                                background: 'rgba(17,24,39,0.55)',
                                color: 'var(--vesper-text-2)',
                                border: '1px solid rgba(255,255,255,0.12)',
                                fontSize: 12,
                                letterSpacing: '0.18em',
                                textTransform: 'uppercase',
                            }}
                        >
                            <ArrowLeft size={16} /> Back
                        </button>

                        <div>
                            <div className="vesper-eyebrow mb-4">
                                {known_for_department || 'Actor'} · From TMDB
                            </div>
                            <h1
                                className="vesper-display"
                                data-testid="person-name"
                                style={{
                                    fontSize: 'clamp(48px, 5.8vw, 92px)',
                                    letterSpacing: '-0.035em',
                                    lineHeight: 0.95,
                                    margin: 0,
                                }}
                            >
                                {name}
                            </h1>

                            <div
                                className="flex items-center gap-3 mt-5 vesper-meta flex-wrap"
                                style={{ fontSize: 17 }}
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
                                    className="mt-6"
                                    style={{
                                        fontSize: 16,
                                        lineHeight: 1.6,
                                        color: 'var(--vesper-text-2)',
                                        maxWidth: '60ch',
                                        display: '-webkit-box',
                                        WebkitLineClamp: 5,
                                        WebkitBoxOrient: 'vertical',
                                        overflow: 'hidden',
                                    }}
                                >
                                    {biography}
                                </p>
                            )}

                            {/* Save-actor toggle pill */}
                            <div className="mt-8">
                                <button
                                    data-testid="person-save-toggle"
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onClick={toggleSaved}
                                    className="flex items-center gap-2 h-12 rounded-full font-sans font-semibold"
                                    style={{
                                        paddingLeft: 22,
                                        paddingRight: 24,
                                        background: saved
                                            ? 'rgba(255,93,109,0.14)'
                                            : 'rgba(93,200,255,0.16)',
                                        color: saved
                                            ? '#ff5d6d'
                                            : 'var(--vesper-blue-bright)',
                                        border: saved
                                            ? '1px solid rgba(255,93,109,0.35)'
                                            : '1px solid rgba(93,200,255,0.35)',
                                        fontSize: 14,
                                        letterSpacing: '0.02em',
                                    }}
                                >
                                    {saved ? <HeartOff size={16} /> : <Heart size={16} />}
                                    {saved ? 'Remove from My Actors' : 'Add to My Actors'}
                                </button>
                            </div>
                        </div>
                    </div>

                    {/* RIGHT — portrait card */}
                    {profile && (
                        <div
                            data-testid="person-portrait-card"
                            style={{
                                position: 'relative',
                                aspectRatio: '2 / 3',
                                borderRadius: 18,
                                overflow: 'hidden',
                                background: 'rgba(255,255,255,0.04)',
                                border: '1px solid rgba(255,255,255,0.08)',
                                boxShadow:
                                    '0 30px 60px rgba(0,0,0,0.55), 0 8px 18px rgba(0,0,0,0.35)',
                            }}
                        >
                            <img
                                src={profile}
                                alt={name}
                                style={{
                                    width: '100%',
                                    height: '100%',
                                    objectFit: 'cover',
                                    display: 'block',
                                }}
                            />
                            <div
                                aria-hidden="true"
                                style={{
                                    position: 'absolute', inset: 0,
                                    background:
                                        'linear-gradient(180deg, rgba(0,0,0,0) 60%, rgba(6,8,15,0.4) 100%)',
                                    pointerEvents: 'none',
                                }}
                            />
                        </div>
                    )}
                </div>
            </section>

            {/* FILMOGRAPHY — split by Movies / TV. */}
            <section
                data-testid="person-filmography"
                style={{ padding: '24px 80px 96px 80px' }}
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
            style={{ marginTop: topMargin ? 64 : 0 }}
        >
            <h2
                className="vesper-display mb-6"
                style={{ fontSize: 24, letterSpacing: '-0.02em' }}
            >
                {title}
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 11,
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
                    gridTemplateColumns: 'repeat(auto-fill, minmax(150px, 1fr))',
                    gap: 18,
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
                borderRadius: 12,
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
                padding: 0,
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
                            fontSize: 56,
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
                    height: '45%',
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.92) 70%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div className="absolute inset-x-0 bottom-0 p-3">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 13,
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.2,
                        color: 'var(--vesper-text)',
                        overflow: 'hidden',
                        display: '-webkit-box',
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: 'vertical',
                    }}
                >
                    {film.title}
                </div>
                <div
                    className="vesper-mono mt-1"
                    style={{
                        fontSize: 9,
                        letterSpacing: '0.16em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-text-3)',
                    }}
                >
                    {[
                        film.media_type === 'tv' ? 'TV' : 'Movie',
                        film.year,
                        film.rating != null ? `★ ${film.rating}` : null,
                    ].filter(Boolean).join(' · ')}
                </div>
                {film.character && (
                    <div
                        className="mt-1.5"
                        style={{
                            fontSize: 11,
                            color: 'var(--vesper-text-2)',
                            lineHeight: 1.25,
                            overflow: 'hidden',
                            display: '-webkit-box',
                            WebkitLineClamp: 1,
                            WebkitBoxOrient: 'vertical',
                        }}
                    >
                        as {film.character}
                    </div>
                )}
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
