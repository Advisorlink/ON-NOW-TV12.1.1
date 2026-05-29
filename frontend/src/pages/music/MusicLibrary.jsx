import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Heart, Music2, Trash2, Play, ListMusic } from 'lucide-react';
import {
    getMusicLibrary,
    subscribeMusicLibrary,
    removeMusicLike,
    getPlaylists,
    deletePlaylist,
    removeTrackFromPlaylist,
} from '../../lib/music-library';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

export default function MusicLibrary() {
    const [lib, setLib] = useState(getMusicLibrary());
    const [playlists, setPlaylists] = useState(getPlaylists());
    const [activePl, setActivePl] = useState(null);
    const { controls } = useMusicPlayer();

    useEffect(() => {
        const u = subscribeMusicLibrary(() => {
            setLib(getMusicLibrary());
            setPlaylists(getPlaylists());
        });
        return u;
    }, []);

    const sections = [
        { kind: 'artist',  title: 'Liked Artists',  items: lib.artists,  shape: 'round' },
        { kind: 'album',   title: 'Liked Albums',   items: lib.albums,   shape: 'square' },
        { kind: 'track',   title: 'Liked Songs',    items: lib.tracks,   shape: 'row'   },
        { kind: 'radio',   title: 'Liked Radio',    items: lib.radio,    shape: 'square' },
        { kind: 'podcast', title: 'Liked Podcasts', items: lib.podcasts, shape: 'square' },
    ];

    const isEmpty =
        sections.every((s) => !s.items?.length) && playlists.length === 0;

    return (
        <div data-testid="music-library">
            <h1 className="tunes-page-title">Your Library</h1>
            <p className="tunes-page-subtitle">Everything you've saved. Liked songs, artists, albums, radio stations, podcasts — and your playlists.</p>

            {isEmpty && (
                <div style={{
                    padding: 60,
                    textAlign: 'center',
                    background: 'var(--tunes-glass-bg)',
                    borderRadius: 18,
                    border: '1px solid var(--tunes-glass-border)',
                    marginTop: 40,
                }}>
                    <Heart size={48} color="var(--tunes-accent)" style={{ marginBottom: 18 }} />
                    <h2 style={{ fontSize: 22, fontWeight: 700, margin: '0 0 8px' }}>No saved music yet</h2>
                    <p style={{ color: 'var(--tunes-text-dim)', maxWidth: 540, margin: '0 auto' }}>
                        Tap the heart on any artist, song, album, or station to start your library — or create a playlist by tapping the "+" icon on a track.
                    </p>
                </div>
            )}

            {/* ── Playlists ───────────────────────────────────────── */}
            {playlists.length > 0 && (
                <section className="tunes-section">
                    <h2 className="tunes-section__title">
                        <ListMusic size={20} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                        Playlists
                    </h2>
                    <div className="tunes-grid">
                        {playlists.map((pl) => (
                            <button
                                key={pl.id}
                                type="button"
                                className="tunes-card"
                                onClick={() => setActivePl(activePl === pl.id ? null : pl.id)}
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                data-testid={`playlist-${pl.id}`}
                                style={{ textAlign: 'left', cursor: 'pointer' }}
                            >
                                <div className="tunes-card__art"
                                    style={{
                                        background: 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-radio))',
                                        display: 'grid', placeItems: 'center',
                                    }}
                                >
                                    <ListMusic size={60} color="rgba(255,255,255,0.85)" />
                                </div>
                                <div className="tunes-card__body">
                                    <p className="tunes-card__title">{pl.name}</p>
                                    <p className="tunes-card__subtitle">{pl.tracks.length} track{pl.tracks.length === 1 ? '' : 's'}</p>
                                </div>
                            </button>
                        ))}
                    </div>

                    {activePl && (() => {
                        const pl = playlists.find((p) => p.id === activePl);
                        if (!pl) return null;
                        return (
                            <div style={{
                                marginTop: 22,
                                padding: 22,
                                background: 'var(--tunes-glass-bg)',
                                border: '1px solid var(--tunes-glass-border)',
                                borderRadius: 18,
                            }}>
                                <div style={{
                                    display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                                    marginBottom: 14,
                                }}>
                                    <h3 style={{ fontSize: 22, fontWeight: 700, margin: 0 }}>
                                        {pl.name} <span style={{ color: 'var(--tunes-text-dim)', fontWeight: 500, fontSize: 14, marginLeft: 8 }}>{pl.tracks.length} tracks</span>
                                    </h3>
                                    <div style={{ display: 'flex', gap: 8 }}>
                                        {pl.tracks.length > 0 && (
                                            <button
                                                type="button"
                                                className="tunes-btn-primary"
                                                onClick={() => controls.playTrack(pl.tracks[0], pl.tracks)}
                                                data-focusable="true"
                                                data-focus-style="pill"
                                                tabIndex={0}
                                                style={{ padding: '8px 16px', fontSize: 13 }}
                                            >
                                                <Play size={14} />
                                                Play all
                                            </button>
                                        )}
                                        <button
                                            type="button"
                                            onClick={() => { if (confirm(`Delete playlist "${pl.name}"?`)) { deletePlaylist(pl.id); setActivePl(null); } }}
                                            data-focusable="true"
                                            data-focus-style="pill"
                                            tabIndex={0}
                                            style={{
                                                padding: 10, background: 'transparent', border: '1px solid rgba(255,255,255,0.12)',
                                                borderRadius: 999, color: 'rgba(255,255,255,0.85)', cursor: 'pointer',
                                            }}
                                            title="Delete playlist"
                                        >
                                            <Trash2 size={16} />
                                        </button>
                                    </div>
                                </div>
                                <div className="tunes-track-list">
                                    {pl.tracks.length === 0 && (
                                        <div className="tunes-empty">No tracks yet — add some from Search or Album pages.</div>
                                    )}
                                    {pl.tracks.map((t, i) => (
                                        <div
                                            key={t.id}
                                            className="tunes-track-row"
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                            onClick={() => controls.playTrack(t, pl.tracks)}
                                            onKeyDown={(e) => { if (e.key === 'Enter') controls.playTrack(t, pl.tracks); }}
                                        >
                                            <div className="tunes-track-row__num">{i + 1}</div>
                                            <img src={t.album?.cover || ''} alt="" className="tunes-track-row__art" loading="lazy" />
                                            <div>
                                                <p className="tunes-track-row__title">{t.title}</p>
                                                <p className="tunes-track-row__artist">{t.artist?.name}</p>
                                            </div>
                                            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                                                <span className="tunes-track-row__duration">{fmtDur(t.duration)}</span>
                                                <button
                                                    type="button"
                                                    onClick={(e) => { e.stopPropagation(); removeTrackFromPlaylist(pl.id, t.id); }}
                                                    data-focusable="true"
                                                    data-focus-style="pill"
                                                    tabIndex={0}
                                                    style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'rgba(255,255,255,0.55)', padding: 8 }}
                                                    title="Remove from playlist"
                                                >
                                                    <Trash2 size={14} />
                                                </button>
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        );
                    })()}
                </section>
            )}

            {/* ── Liked Artists ─────────────────────────────────── */}
            {sections.map((s) => {
                if (!s.items?.length) return null;
                return (
                    <section className="tunes-section" key={s.kind} data-testid={`library-${s.kind}-section`}>
                        <h2 className="tunes-section__title">{s.title}</h2>
                        {s.shape === 'row' ? (
                            <div className="tunes-track-list">
                                {s.items.map((t, i) => (
                                    <div
                                        key={t.id}
                                        className="tunes-track-row"
                                        data-focusable="true"
                                        data-focus-style="tile"
                                        tabIndex={0}
                                        onClick={() => controls.playTrack(t, s.items)}
                                        onKeyDown={(e) => { if (e.key === 'Enter') controls.playTrack(t, s.items); }}
                                    >
                                        <div className="tunes-track-row__num">{i + 1}</div>
                                        <img src={t.album?.cover || ''} alt="" className="tunes-track-row__art" loading="lazy" />
                                        <div>
                                            <p className="tunes-track-row__title">{t.title}</p>
                                            <p className="tunes-track-row__artist">{t.artist?.name}</p>
                                        </div>
                                        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                                            <span className="tunes-track-row__duration">{fmtDur(t.duration)}</span>
                                            <button
                                                type="button"
                                                onClick={(e) => { e.stopPropagation(); removeMusicLike('track', t.id); }}
                                                data-focusable="true"
                                                data-focus-style="pill"
                                                tabIndex={0}
                                                style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--tunes-accent)', padding: 8 }}
                                                title="Remove from library"
                                            >
                                                <Heart size={16} fill="currentColor" />
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <div className="tunes-grid">
                                {s.items.map((it) => {
                                    const isRound = s.shape === 'round';
                                    const linkTo = s.kind === 'artist' ? `/music/artist/${it.id}` :
                                                   s.kind === 'album'  ? `/music/album/${it.id}` :
                                                   s.kind === 'podcast'? `/music/podcast/${encodeURIComponent(it.feed_url || '')}` :
                                                   null;
                                    const inner = (
                                        <>
                                            <img
                                                src={it.picture || it.cover || it.favicon || it.artwork || ''}
                                                alt={it.name || it.title}
                                                className={'tunes-card__art' + (isRound ? ' tunes-card__art--round' : '')}
                                                loading="lazy"
                                            />
                                            <div className="tunes-card__body" style={isRound ? { textAlign: 'center' } : undefined}>
                                                <p className="tunes-card__title">{it.name || it.title}</p>
                                                <p className="tunes-card__subtitle">{it.artist?.name || it.country || it.subtitle || s.kind}</p>
                                            </div>
                                        </>
                                    );
                                    return linkTo ? (
                                        <Link
                                            key={it.id}
                                            to={linkTo}
                                            className="tunes-card"
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                            style={isRound ? { textAlign: 'center' } : undefined}
                                        >
                                            {inner}
                                        </Link>
                                    ) : (
                                        <button
                                            key={it.id}
                                            type="button"
                                            className="tunes-card"
                                            onClick={() => { if (s.kind === 'radio') controls.playRadio(it); }}
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                            style={{ textAlign: 'left', cursor: 'pointer' }}
                                        >
                                            {inner}
                                        </button>
                                    );
                                })}
                            </div>
                        )}
                    </section>
                );
            })}
        </div>
    );
}
