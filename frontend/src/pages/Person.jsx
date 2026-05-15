/**
 * Actor profile page — `/person/:tmdbId`.
 *
 * Layout:
 *   • Full-bleed B&W portrait HERO covering the top 55vh, with the
 *     actor's name, age, birthplace, and bio overlaid (Detail-page
 *     style hero + Sports-Guide eyebrow).
 *   • Filmography grid below — same poster tile aesthetic as the
 *     Detail page's Cast row but on a grid (sorted by popularity).
 *   • Tap a poster → resolve TMDB → IMDB → navigate to the existing
 *     /title/{type}/{imdb_id} detail page so the user can watch.
 *
 * Data: GET /api/tmdb/person/{tmdbId} (combined_credits append).
 */
import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import axios from 'axios';
import { ArrowLeft, Loader2 } from 'lucide-react';
import SideNav from '@/components/SideNav';
import useBackHandler from '@/hooks/useBackHandler';

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
                        className="flex items-center justify-center"
                        style={{ height: '100%', gap: 12, color: 'var(--vesper-text-2)' }}
                    >
                        <Loader2 className="vesper-spin" size={22} /> Loading…
                    </div>
                )}

                {!busy && err && (
                    <div style={{ padding: 80, color: '#FCA5A5' }}>{err}</div>
                )}

                {!busy && !err && data && <PersonContent data={data} navigate={navigate} />}
            </main>
        </div>
    );
}

function PersonContent({ data, navigate }) {
    const {
        name, profile, biography, age, place_of_birth,
        known_for_department, deathday, filmography,
    } = data;

    return (
        <>
            {/* HERO — B&W portrait background, gradient overlay, big
                title with bio.  Mirrors Detail-page hero but in a
                taller 55vh footprint and with a desaturated portrait. */}
            <section
                data-testid="person-hero"
                style={{
                    position: 'relative',
                    width: '100%',
                    minHeight: 'clamp(420px, 60vh, 720px)',
                    overflow: 'hidden',
                }}
            >
                {profile && (
                    <div
                        aria-hidden="true"
                        style={{
                            position: 'absolute',
                            inset: 0,
                            backgroundImage: `url(${profile})`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center top',
                            /* B&W cinematic feel */
                            filter: 'grayscale(1) contrast(1.05) brightness(0.55)',
                        }}
                    />
                )}
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', inset: 0,
                        background: `linear-gradient(180deg,
                            rgba(6,8,15,0.45) 0%,
                            rgba(6,8,15,0.55) 40%,
                            rgba(6,8,15,0.85) 80%,
                            var(--vesper-bg-0) 100%)`,
                    }}
                />
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', inset: 0,
                        background: `linear-gradient(90deg,
                            rgba(6,8,15,0.85) 0%,
                            rgba(6,8,15,0.55) 40%,
                            rgba(6,8,15,0.05) 75%,
                            rgba(6,8,15,0) 100%)`,
                    }}
                />

                <div
                    style={{
                        position: 'relative',
                        zIndex: 2,
                        padding: '64px 80px 80px 80px',
                        height: '100%',
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 24,
                    }}
                >
                    {/* Back button */}
                    <button
                        data-testid="back-button"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={() => navigate(-1)}
                        className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
                        style={{
                            alignSelf: 'flex-start',
                            background: 'rgba(17,24,39,0.6)',
                            color: 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            fontSize: 13,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        <ArrowLeft size={16} /> Back
                    </button>

                    {/* Hero text — mt-auto pushes it to the bottom
                        like the Detail page hero. */}
                    <div style={{ maxWidth: '60vw', marginTop: 'auto' }}>
                        <div className="vesper-eyebrow mb-4">
                            {known_for_department || 'Actor'} · TMDB
                        </div>
                        <h1
                            className="vesper-display"
                            data-testid="person-name"
                            style={{
                                fontSize: 'clamp(48px, 6vw, 92px)',
                                letterSpacing: '-0.035em',
                                lineHeight: 1.0,
                            }}
                        >
                            {name}
                        </h1>

                        <div
                            className="flex items-center gap-3 mt-4 vesper-meta flex-wrap"
                            style={{ fontSize: 16 }}
                        >
                            {age != null && (
                                <span style={{ color: 'var(--vesper-blue)' }}>
                                    {deathday ? `Died at ${age}` : `${age} years old`}
                                </span>
                            )}
                            {place_of_birth && age != null && <Bullet />}
                            {place_of_birth && <span>{place_of_birth}</span>}
                        </div>

                        {biography && (
                            <p
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
                    </div>
                </div>
            </section>

            {/* FILMOGRAPHY GRID */}
            <section
                data-testid="person-filmography"
                style={{ padding: '24px 80px 80px 80px' }}
            >
                <h2
                    className="vesper-display mb-6"
                    style={{ fontSize: 28, letterSpacing: '-0.02em' }}
                >
                    Known for
                    <span
                        className="ml-3 vesper-mono"
                        style={{
                            fontSize: 12,
                            color: 'var(--vesper-text-3)',
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                        }}
                    >
                        {filmography?.length || 0} titles
                    </span>
                </h2>

                <div
                    data-testid="person-grid"
                    style={{
                        display: 'grid',
                        gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
                        gap: 24,
                    }}
                >
                    {(filmography || []).map((film) => (
                        <FilmCard
                            key={`${film.media_type}-${film.tmdb_id}`}
                            film={film}
                            onPick={async () => {
                                try {
                                    const { data: imdb } = await axios.get(
                                        `${API}/tmdb/imdb/${film.media_type}/${film.tmdb_id}`,
                                        { timeout: 8000 }
                                    );
                                    if (imdb?.imdb_id) {
                                        navigate(`/title/${film.media_type === 'tv' ? 'series' : 'movie'}/${imdb.imdb_id}`);
                                    }
                                } catch {
                                    /* swallow */
                                }
                            }}
                        />
                    ))}
                </div>
            </section>
        </>
    );
}

function FilmCard({ film, onPick }) {
    const [focused, setFocused] = useState(false);
    return (
        <button
            data-testid={`person-film-${film.media_type}-${film.tmdb_id}`}
            data-focusable="true"
            data-focus-style="poster"
            tabIndex={0}
            onClick={onPick}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            onMouseEnter={() => setFocused(true)}
            onMouseLeave={() => setFocused(false)}
            style={{
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
                    width: '100%',
                    aspectRatio: '2 / 3',
                    borderRadius: 14,
                    overflow: 'hidden',
                    background: 'rgba(255,255,255,0.05)',
                    border: focused
                        ? '2px solid var(--vesper-blue)'
                        : '1px solid rgba(255,255,255,0.08)',
                    transform: focused ? 'translateY(-4px)' : 'translateY(0)',
                    boxShadow: focused
                        ? '0 18px 36px rgba(93,200,255,0.18), 0 4px 12px rgba(0,0,0,0.5)'
                        : '0 2px 8px rgba(0,0,0,0.3)',
                    transition: 'transform 160ms ease, box-shadow 160ms ease, border 120ms ease',
                    position: 'relative',
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
                            padding: '0 12px', textAlign: 'center',
                            color: 'var(--vesper-text-3)', fontSize: 18, fontWeight: 700,
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
                            fontSize: 10, letterSpacing: '0.18em',
                            fontWeight: 700,
                            border: '1px solid rgba(93,200,255,0.55)',
                        }}
                    >
                        TV
                    </span>
                )}
            </div>
            <div
                style={{
                    marginTop: 10,
                    fontSize: 14,
                    fontWeight: 600,
                    color: 'var(--vesper-text)',
                    lineHeight: 1.25,
                    overflow: 'hidden',
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                }}
            >
                {film.title}
            </div>
            <div
                style={{
                    marginTop: 4,
                    fontSize: 11,
                    color: 'var(--vesper-text-3)',
                }}
            >
                {film.character && <span>as {film.character}</span>}
                {film.character && film.year && <span> · </span>}
                {film.year && <span>{film.year}</span>}
            </div>
        </button>
    );
}

function Bullet() {
    return (
        <span
            aria-hidden="true"
            style={{ color: 'var(--vesper-text-3)' }}
        >
            ·
        </span>
    );
}
