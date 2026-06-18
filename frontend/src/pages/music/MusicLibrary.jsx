// ON NOW TV TUNES — Library page (v2.10.46 two-column TV layout)
// ============================================================
// User-requested layout per video brief:
//   LEFT column  (≈40% of content area)
//     • Liked Artists   — small circular avatars in a thin shelf
//     • Liked Songs     — vertical list with art + title + artist
//     • Liked Radio     — compact grid (smaller tiles)
//     • Liked Podcasts  — compact grid (smaller tiles)
//   RIGHT column (≈60%)
//     • Liked Albums    — fixed 3-column grid of album covers
//
// Playlists still appear at the top of the page, full-width.
//
// The new layout is driven by CSS in `tunes.css`
// (`.tunes-library-layout`, `.tunes-library-left`,
// `.tunes-library-right`, `.tunes-library-albums`,
// `.tunes-library-compact`).  We deliberately stopped using the
// generic `.tunes-grid` for the Library because that grid uses
// `auto-fill minmax(180px, 1fr)` and produced the giant single
// album tile shown in the user's video.

import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Heart, Trash2, Play, ListMusic } from 'lucide-react';
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

/* ── Reusable bits ──────────────────────────────────────────── */

function TrackRow({ track, list, idx, onUnlike }) {
    const { controls } = useMusicPlayer();
    return (
        <div
            className="tunes-track-row"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => controls.playTrack(track, list)}
            onKeyDown={(e) => { if (e.key === 'Enter') controls.playTrack(track, list); }}
            data-testid={`library-track-${track.id}`}
        >
            <div className="tunes-track-row__num">{idx + 1}</div>
            <img
                src={track.album?.cover || ''}
                alt=""
                className="tunes-track-row__art"
                loading="lazy"
            />
            <div style={{ minWidth: 0 }}>
                <p className="tunes-track-row__title">{track.title}</p>
                <p className="tunes-track-row__artist">{track.artist?.name}</p>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span className="tunes-track-row__duration">{fmtDur(track.duration)}</span>
                {onUnlike && (
                    <button
                        type="button"
                        onClick={(e) => { e.stopPropagation(); onUnlike(track); }}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        className="tunes-iconbtn tunes-iconbtn--mini"
                        title="Remove from library"
                        data-testid={`library-track-unlike-${track.id}`}
                    >
                        <Heart size={14} fill="currentColor" />
                    </button>
                )}
            </div>
        </div>
    );
}

function ArtistAvatar({ artist }) {
    return (
        <Link
            to={`/music/artist/${artist.id}`}
            className="tunes-library-artist"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`library-artist-${artist.id}`}
        >
            <img
                src={artist.picture || ''}
                alt={artist.name}
                className="tunes-library-artist__avatar"
                loading="lazy"
            />
            <span className="tunes-library-artist__name">{artist.name}</span>
        </Link>
    );
}

function CompactTile({ item, kind, onPlayRadio }) {
    const linkTo =
        kind === 'podcast'
            ? `/music/podcast/${encodeURIComponent(item.feed_url || '')}`
            : null;
    const art = item.picture || item.cover || item.favicon || item.artwork || '';
    const title = item.name || item.title;
    const subtitle = item.country || item.subtitle || (kind === 'radio' ? 'Radio' : 'Podcast');

    const inner = (
        <>
            <img
                src={art}
                alt={title}
                className="tunes-library-compact__art"
                loading="lazy"
            />
            <div className="tunes-library-compact__meta">
                <p className="tunes-library-compact__title">{title}</p>
                <p className="tunes-library-compact__subtitle">{subtitle}</p>
            </div>
        </>
    );
    if (linkTo) {
        return (
            <Link
                to={linkTo}
                className="tunes-library-compact__tile"
                data-focusable="true"
                data-focus-style="tile"
                tabIndex={0}
                data-testid={`library-${kind}-${item.id}`}
            >
                {inner}
            </Link>
        );
    }
    return (
        <button
            type="button"
            className="tunes-library-compact__tile"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => { if (kind === 'radio' && onPlayRadio) onPlayRadio(item); }}
            data-testid={`library-${kind}-${item.id}`}
        >
            {inner}
        </button>
    );
}

function AlbumTile({ album }) {
    return (
        <Link
            to={`/music/album/${album.id}`}
            className="tunes-library-album"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`library-album-${album.id}`}
        >
            <img
                src={album.cover || album.cover_xl || ''}
                alt={album.title}
                className="tunes-library-album__art"
                loading="lazy"
            />
            <p className="tunes-library-album__title">{album.title}</p>
            <p className="tunes-library-album__artist">{album.artist?.name}</p>
        </Link>
    );
}

/* ── Page ───────────────────────────────────────────────────── */

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

    const hasArtists = (lib.artists?.length || 0) > 0;
    const hasAlbums = (lib.albums?.length || 0) > 0;
    const hasTracks = (lib.tracks?.length || 0) > 0;
    const hasRadio = (lib.radio?.length || 0) > 0;
    const hasPodcasts = (lib.podcasts?.length || 0) > 0;
    const isEmpty =
        !hasArtists && !hasAlbums && !hasTracks && !hasRadio && !hasPodcasts && playlists.length === 0;

    return (
        <div data-testid="music-library" className="tunes-library-page">
            <h1 className="tunes-page-title">Your Library</h1>
            <p className="tunes-page-subtitle">
                Everything you&apos;ve saved. Liked songs, artists, albums, radio stations, podcasts, and your playlists.
            </p>

            {isEmpty && (
                <div className="tunes-library-empty" data-testid="library-empty">
                    <Heart size={48} color="var(--tunes-accent)" style={{ marginBottom: 18 }} />
                    <h2>No saved music yet</h2>
                    <p>
                        Tap the heart on any artist, song, album, or station to start your library — or create a playlist by tapping the &quot;+&quot; icon on a track.
                    </p>
                </div>
            )}

            {/* ── Playlists (full-width top section) ───────────── */}
            {playlists.length > 0 && (
                <section className="tunes-section" data-testid="library-playlists-section">
                    <h2 className="tunes-section__title">
                        <ListMusic size={20} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                        Playlists
                    </h2>
                    <div className="tunes-library-albums">
                        {playlists.map((pl) => (
                            <button
                                key={pl.id}
                                type="button"
                                className="tunes-library-album"
                                onClick={() => setActivePl(activePl === pl.id ? null : pl.id)}
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                data-testid={`playlist-${pl.id}`}
                            >
                                <div
                                    className="tunes-library-album__art"
                                    style={{
                                        background: 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-radio))',
                                        display: 'grid', placeItems: 'center',
                                    }}
                                >
                                    <ListMusic size={48} color="rgba(255,255,255,0.85)" />
                                </div>
                                <p className="tunes-library-album__title">{pl.name}</p>
                                <p className="tunes-library-album__artist">
                                    {pl.tracks.length} track{pl.tracks.length === 1 ? '' : 's'}
                                </p>
                            </button>
                        ))}
                    </div>

                    {activePl && (() => {
                        const pl = playlists.find((p) => p.id === activePl);
                        if (!pl) return null;
                        return (
                            <div className="tunes-library-playlist-detail">
                                <div className="tunes-library-playlist-detail__head">
                                    <h3>
                                        {pl.name}
                                        <span>{pl.tracks.length} tracks</span>
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
                                            onClick={() => {
                                                if (confirm(`Delete playlist "${pl.name}"?`)) {
                                                    deletePlaylist(pl.id);
                                                    setActivePl(null);
                                                }
                                            }}
                                            data-focusable="true"
                                            data-focus-style="pill"
                                            tabIndex={0}
                                            className="tunes-iconbtn tunes-iconbtn--mini"
                                            title="Delete playlist"
                                        >
                                            <Trash2 size={16} />
                                        </button>
                                    </div>
                                </div>
                                <div className="tunes-track-list">
                                    {pl.tracks.length === 0 && (
                                        <div className="tunes-empty">No tracks yet. Add some from Search or Album pages.</div>
                                    )}
                                    {pl.tracks.map((t, i) => (
                                        <TrackRow
                                            key={t.id}
                                            track={t}
                                            list={pl.tracks}
                                            idx={i}
                                            onUnlike={(track) => removeTrackFromPlaylist(pl.id, track.id)}
                                        />
                                    ))}
                                </div>
                            </div>
                        );
                    })()}
                </section>
            )}

            {/* ── Two-column layout ─────────────────────────────── */}
            {(hasArtists || hasAlbums || hasTracks || hasRadio || hasPodcasts) && (
                <div className="tunes-library-layout">

                    {/* LEFT: artists row + songs list + radio + podcasts */}
                    <div className="tunes-library-left">

                        {hasArtists && (
                            <section className="tunes-section" data-testid="library-artist-section">
                                <h2 className="tunes-section__title">Liked Artists</h2>
                                <div className="tunes-library-artists">
                                    {lib.artists.map((a) => (
                                        <ArtistAvatar key={a.id} artist={a} />
                                    ))}
                                </div>
                            </section>
                        )}

                        {hasTracks && (
                            <section className="tunes-section" data-testid="library-track-section">
                                <h2 className="tunes-section__title">Liked Songs</h2>
                                <div className="tunes-track-list">
                                    {lib.tracks.map((t, i) => (
                                        <TrackRow
                                            key={t.id}
                                            track={t}
                                            list={lib.tracks}
                                            idx={i}
                                            onUnlike={(track) => removeMusicLike('track', track.id)}
                                        />
                                    ))}
                                </div>
                            </section>
                        )}

                        {hasRadio && (
                            <section className="tunes-section" data-testid="library-radio-section">
                                <h2 className="tunes-section__title">Liked Radio</h2>
                                <div className="tunes-library-compact">
                                    {lib.radio.map((r) => (
                                        <CompactTile
                                            key={r.id}
                                            item={r}
                                            kind="radio"
                                            onPlayRadio={controls.playRadio}
                                        />
                                    ))}
                                </div>
                            </section>
                        )}

                        {hasPodcasts && (
                            <section className="tunes-section" data-testid="library-podcast-section">
                                <h2 className="tunes-section__title">Liked Podcasts</h2>
                                <div className="tunes-library-compact">
                                    {lib.podcasts.map((p) => (
                                        <CompactTile key={p.id} item={p} kind="podcast" />
                                    ))}
                                </div>
                            </section>
                        )}
                    </div>

                    {/* RIGHT: albums in a 3-column grid */}
                    {hasAlbums && (
                        <div className="tunes-library-right">
                            <section className="tunes-section" data-testid="library-album-section">
                                <h2 className="tunes-section__title">Liked Albums</h2>
                                <div className="tunes-library-albums">
                                    {lib.albums.map((a) => (
                                        <AlbumTile key={a.id} album={a} />
                                    ))}
                                </div>
                            </section>
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
