/**
 * MusicGenre — artists inside a Deezer genre.
 *
 * v2.12.9 — Created because the home page's GenreTile linked to
 * `/music/genre/:id` but no route/page existed, so clicking any
 * genre landed on a blank screen.
 */
import React, { useEffect, useState } from 'react';
import { Link, useParams, useLocation } from 'react-router-dom';
import { musicAPI } from '@/lib/music-api';

export default function MusicGenre() {
    const { id } = useParams();
    const location = useLocation();
    const genreName = location.state?.name || 'Genre';
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);

    useEffect(() => {
        setData(null);
        setErr(null);
        musicAPI.genre(id).then((r) => setData(r.data || r)).catch((e) => setErr(e.message || 'failed'));
    }, [id]);

    if (err) return <div className="tunes-empty">Couldn&apos;t load genre — {err}</div>;
    if (!data) return <div className="tunes-empty">Loading…</div>;

    const artists = data.artists || [];

    return (
        <div className="tunes-artist-page" data-testid="tunes-genre-page">
            <h1 className="tunes-page-title">{genreName}</h1>
            <p className="tunes-page-subtitle">Top artists in this genre</p>
            {artists.length === 0 ? (
                <div className="tunes-empty">No artists found in this genre.</div>
            ) : (
                <div
                    style={{
                        display: 'flex',
                        flexWrap: 'wrap',
                        gap: 'clamp(18px, 2vw, 32px)',
                        marginTop: 'clamp(24px, 3vh, 40px)',
                    }}
                >
                    {artists.map((artist) => (
                        <Link
                            key={artist.id}
                            to={`/music/artist/${artist.id}`}
                            className="tunes-tile tunes-tile--artist"
                            data-testid={`tunes-genre-artist-${artist.id}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                        >
                            <div className="tunes-tile__art-wrap">
                                {artist.picture ? (
                                    <img
                                        src={artist.picture}
                                        alt={artist.name}
                                        className="tunes-tile__art"
                                        loading="lazy"
                                        decoding="async"
                                    />
                                ) : (
                                    <div className="tunes-tile__art-fallback">
                                        {(artist.name || '?')[0]}
                                    </div>
                                )}
                            </div>
                            <div className="tunes-tile__caption">
                                <p className="tunes-tile__title">{artist.name}</p>
                                <p className="tunes-tile__subtitle">
                                    {artist.nb_fan ? `${Math.round(artist.nb_fan / 1000)}K fans` : 'Artist'}
                                </p>
                            </div>
                        </Link>
                    ))}
                </div>
            )}
            <div style={{ height: 60 }} />
        </div>
    );
}
