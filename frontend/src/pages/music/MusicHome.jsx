import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Play, Sparkles } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

function AlbumCard({ album, onClick }) {
    return (
        <Link
            to={`/music/album/${album.id}`}
            className="tunes-card"
            data-testid={`tunes-album-${album.id}`}
            onClick={onClick}
        >
            <img
                src={album.cover || ''}
                alt={album.title}
                className="tunes-card__art"
                loading="lazy"
            />
            <div className="tunes-card__body">
                <p className="tunes-card__title">{album.title}</p>
                <p className="tunes-card__subtitle">{album.artist?.name || ''}</p>
            </div>
        </Link>
    );
}

function ArtistCard({ artist }) {
    return (
        <Link
            to={`/music/artist/${artist.id}`}
            className="tunes-card"
            data-testid={`tunes-artist-${artist.id}`}
            style={{ textAlign: 'center' }}
        >
            <img
                src={artist.picture || ''}
                alt={artist.name}
                className="tunes-card__art tunes-card__art--round"
                loading="lazy"
            />
            <div className="tunes-card__body" style={{ textAlign: 'center' }}>
                <p className="tunes-card__title">{artist.name}</p>
                <p className="tunes-card__subtitle">
                    {artist.nb_fan ? `${(artist.nb_fan / 1000).toFixed(0)}K fans` : 'Artist'}
                </p>
            </div>
        </Link>
    );
}

function TrackCard({ track, queue }) {
    const { controls } = useMusicPlayer();
    return (
        <button
            type="button"
            className="tunes-card"
            data-testid={`tunes-track-${track.id}`}
            onClick={() => controls.playTrack(track, queue)}
            style={{ background: 'var(--tunes-glass-bg)', textAlign: 'left', color: 'inherit', cursor: 'pointer' }}
        >
            <div style={{ position: 'relative' }}>
                <img
                    src={track.album?.cover || ''}
                    alt={track.title}
                    className="tunes-card__art"
                    loading="lazy"
                />
                <div style={{
                    position: 'absolute', inset: 0,
                    display: 'flex', alignItems: 'flex-end', justifyContent: 'flex-end',
                    padding: 12,
                }}>
                    <div style={{
                        width: 44, height: 44, borderRadius: '50%',
                        background: 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-accent-2))',
                        display: 'grid', placeItems: 'center',
                        boxShadow: '0 8px 20px rgba(255,45,127,0.55)',
                    }}>
                        <Play size={18} color="#fff" style={{ marginLeft: 2 }} />
                    </div>
                </div>
            </div>
            <div className="tunes-card__body">
                <p className="tunes-card__title">{track.title}</p>
                <p className="tunes-card__subtitle">{track.artist?.name || ''}</p>
            </div>
        </button>
    );
}

function GenreTile({ genre }) {
    return (
        <Link
            to={`/music/genre/${genre.id}`}
            className="tunes-genre-tile"
            data-testid={`tunes-genre-${genre.id}`}
            style={{ backgroundImage: genre.picture ? `url(${genre.picture})` : undefined }}
        >
            <div data-focusable="true" data-focus-style="tile" tabIndex={0} className="tunes-genre-tile__overlay">{genre.name}</div>
        </Link>
    );
}

function Shelf({ shelf }) {
    const items = shelf.items || [];
    if (!items.length) return null;
    return (
        <section className="tunes-shelf" data-testid={`tunes-shelf-${shelf.id}`}>
            <div className="tunes-shelf__head">
                <h2 className="tunes-shelf__title">{shelf.title}</h2>
            </div>
            <div className="tunes-shelf__rail">
                {shelf.type === 'tracks'  && items.map((t) => <TrackCard  key={t.id} track={t}  queue={items} />)}
                {shelf.type === 'albums'  && items.map((a) => <AlbumCard  key={a.id} album={a} />)}
                {shelf.type === 'artists' && items.map((ar)=> <ArtistCard key={ar.id} artist={ar} />)}
            </div>
        </section>
    );
}

export default function MusicHome() {
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);
    const { controls } = useMusicPlayer();

    useEffect(() => {
        musicAPI.home()
            .then((res) => setData(res.data || res))
            .catch((e) => setErr(e.message || 'failed to load'));
    }, []);

    const heroTrack = data?.shelves?.find((s) => s.id === 'top-tracks')?.items?.[0];

    return (
        <div data-testid="music-home">
            <p className="tunes-page-subtitle" style={{ marginBottom: 8 }}>
                <Sparkles size={14} style={{ display: 'inline', marginRight: 6, verticalAlign: -2 }} />
                Discover music, radio, and podcasts in one place
            </p>
            <h1 className="tunes-page-title">Welcome back</h1>

            {heroTrack && (
                <section className="tunes-hero" data-testid="tunes-hero">
                    <div>
                        <p className="tunes-hero__eyebrow">Top Chart · #{1}</p>
                        <h2 className="tunes-hero__title">
                            {heroTrack.title}
                        </h2>
                        <p className="tunes-hero__desc">
                            by <strong>{heroTrack.artist?.name}</strong>
                            {heroTrack.album?.title ? ` · from "${heroTrack.album.title}"` : ''}
                        </p>
                        <button
                            className="tunes-btn-primary"
                            onClick={() => controls.playTrack(heroTrack, data?.shelves?.find((s) => s.id === 'top-tracks')?.items || [heroTrack])}
                            data-testid="tunes-hero-play"
                        >
                            <Play size={18} style={{ marginLeft: 2 }} />
                            Play preview
                        </button>
                    </div>
                    {heroTrack.album?.cover && (
                        <img
                            src={heroTrack.album.cover}
                            alt={heroTrack.title}
                            style={{
                                width: 220, height: 220, objectFit: 'cover',
                                borderRadius: 18,
                                boxShadow: '0 20px 50px rgba(0,0,0,0.5)',
                            }}
                        />
                    )}
                </section>
            )}

            {!data && !err && (
                <>
                    {[1, 2, 3].map((i) => (
                        <div key={i} className="tunes-shelf">
                            <div className="tunes-skel" style={{ height: 28, width: 200, marginBottom: 14 }} />
                            <div className="tunes-shelf__rail">
                                {[1,2,3,4,5,6].map((j) => (
                                    <div key={j} className="tunes-skel" style={{ height: 220 }} />
                                ))}
                            </div>
                        </div>
                    ))}
                </>
            )}

            {err && <div className="tunes-empty">Couldn't load music — {err}</div>}

            {data?.shelves?.map((s) => <Shelf key={s.id} shelf={s} />)}

            {data?.genres?.length > 0 && (
                <section className="tunes-section">
                    <h2 className="tunes-section__title">Browse genres</h2>
                    <div className="tunes-grid">
                        {data.genres.map((g) => <GenreTile key={g.id} genre={g} />)}
                    </div>
                </section>
            )}
        </div>
    );
}
