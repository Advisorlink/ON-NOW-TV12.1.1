// ON NOW TV TUNES — Library page (v2.10.49 — Search-style layout)
// =============================================================
// User feedback (third iteration):
//   "The library is way too big.  It needs to look more like the
//    search page — songs down the left, albums on the right.
//    And underneath the albums can be the artists, so you can
//    follow them as well."
//
// New layout (mirrors `MusicSearch`):
//   LEFT column  (≈1fr)
//     • Liked Songs    — slim list (same shape as Search results)
//     • Liked Radio    — compact grid (3-up)
//     • Liked Podcasts — compact grid (3-up)
//   RIGHT column (≈1.2fr)
//     • Liked Albums   — 3-up grid (small tiles, same size as
//                        Search's result tiles)
//     • Liked Artists  — 3-up grid (small round avatars, same
//                        size as Search's result tiles)
//
// Playlists stay at the top, full-width.
//
// Every tile uses `useTuneTap` so press-and-hold opens the
// "Add to library" modal (v2.10.49 added a key-repeat fallback
// so it works on TV remotes that tap-fire instead of hold-fire).

import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
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
import useTuneTap from '../../hooks/useTuneTap';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

/* ── Library tiles — reuse Search's compact `.tunes-result-*`
   styles so Library/Search are visually consistent. ─────────── */

function TrackRow({ track, list, idx, onUnlike, isCurrent }) {
    const tap = useTuneTap({ kind: 'track', item: track, list });
    return (
        <div
            className={'tunes-result-track' + (isCurrent ? ' is-playing' : '')}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`library-track-${track.id}`}
            {...tap}
        >
            <img
                src={track.album?.cover || ''}
                alt=""
                className="tunes-result-track__art"
                loading="lazy"
            />
            <div className="tunes-result-track__meta">
                <p className="tunes-result-track__title">{track.title}</p>
                <p className="tunes-result-track__artist">{track.artist?.name}</p>
            </div>
            <span className="tunes-result-track__time">{fmtDur(track.duration)}</span>
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
                    style={{ marginLeft: 8 }}
                >
                    <Heart size={14} fill="currentColor" />
                </button>
            )}
        </div>
    );
}

function ArtistTile({ artist }) {
    const navigate = useNavigate();
    const tap = useTuneTap({
        kind: 'artist',
        item: artist,
        onTap: () => navigate(`/music/artist/${artist.id}`),
    });
    return (
        <button
            type="button"
            className="tunes-result-tile tunes-result-tile--round"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`library-artist-${artist.id}`}
            {...tap}
        >
            <img
                src={artist.picture || ''}
                alt=""
                className="tunes-result-tile__art tunes-result-tile__art--round"
                loading="lazy"
            />
            <p className="tunes-result-tile__title">{artist.name}</p>
            <p className="tunes-result-tile__subtitle">Artist</p>
        </button>
    );
}

function AlbumTile({ album }) {
    const navigate = useNavigate();
    const tap = useTuneTap({
        kind: 'album',
        item: album,
        onTap: () => navigate(`/music/album/${album.id}`),
    });
    return (
        <button
            type="button"
            className="tunes-result-tile"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`library-album-${album.id}`}
            {...tap}
        >
            <img
                src={album.cover || album.cover_xl || ''}
                alt=""
                className="tunes-result-tile__art"
                loading="lazy"
            />
            <p className="tunes-result-tile__title">{album.title}</p>
            <p className="tunes-result-tile__subtitle">{album.artist?.name}</p>
        </button>
    );
}

function CompactTile({ item, kind, onPlayRadio }) {
    const navigate = useNavigate();
    const tap = useTuneTap({
        kind,
        item,
        onTap: (it) => {
            if (kind === 'podcast') {
                navigate(`/music/podcast/${encodeURIComponent(it.feed_url || '')}`);
            } else if (kind === 'radio' && onPlayRadio) {
                onPlayRadio(it);
            }
        },
    });
    const art = item.picture || item.cover || item.favicon || item.artwork || '';
    const title = item.name || item.title;
    const subtitle = item.country || item.subtitle || (kind === 'radio' ? 'Radio' : 'Podcast');

    return (
        <button
            type="button"
            className="tunes-result-tile"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`library-${kind}-${item.id}`}
            {...tap}
        >
            {kind === 'radio' && !art ? (
                <div
                    className="tunes-result-tile__art"
                    style={{ background: 'linear-gradient(135deg, #064a59, #0a0118)' }}
                />
            ) : (
                <img
                    src={art}
                    alt={title}
                    className="tunes-result-tile__art"
                    loading="lazy"
                />
            )}
            <p className="tunes-result-tile__title">{title}</p>
            <p className="tunes-result-tile__subtitle">{subtitle}</p>
        </button>
    );
}

/* ── Page ───────────────────────────────────────────────────── */

export default function MusicLibrary() {
    const [lib, setLib] = useState(getMusicLibrary());
    const [playlists, setPlaylists] = useState(getPlaylists());
    const [activePl, setActivePl] = useState(null);
    const { state, controls } = useMusicPlayer();

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

            {/* Playlists section — full-width top */}
            {playlists.length > 0 && (
                <section className="tunes-section" data-testid="library-playlists-section">
                    <h2 className="tunes-section__title">
                        <ListMusic size={20} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                        Playlists
                    </h2>
                    <div className="tunes-library-grid-playlists">
                        {playlists.map((pl) => (
                            <button
                                key={pl.id}
                                type="button"
                                className="tunes-result-tile"
                                onClick={() => setActivePl(activePl === pl.id ? null : pl.id)}
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                data-testid={`playlist-${pl.id}`}
                            >
                                <div
                                    className="tunes-result-tile__art"
                                    style={{
                                        background: 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-radio))',
                                        display: 'grid', placeItems: 'center',
                                    }}
                                >
                                    <ListMusic size={36} color="rgba(255,255,255,0.85)" />
                                </div>
                                <p className="tunes-result-tile__title">{pl.name}</p>
                                <p className="tunes-result-tile__subtitle">
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
                                <div className="tunes-search-tracklist">
                                    {pl.tracks.length === 0 && (
                                        <div className="tunes-empty">No tracks yet. Add some from Search or Album pages.</div>
                                    )}
                                    {pl.tracks.map((t, i) => (
                                        <TrackRow
                                            key={t.id}
                                            track={t}
                                            list={pl.tracks}
                                            idx={i}
                                            isCurrent={state?.current?.id === t.id}
                                        />
                                    ))}
                                </div>
                            </div>
                        );
                    })()}
                </section>
            )}

            {/* Two-column layout — same shape as Search */}
            {(hasArtists || hasAlbums || hasTracks || hasRadio || hasPodcasts) && (
                <div className="tunes-library-layout">

                    {/* LEFT: songs list (slim), radio (compact), podcasts (compact) */}
                    <div className="tunes-library-left">
                        {hasTracks && (
                            <section className="tunes-section" data-testid="library-track-section">
                                <h2 className="tunes-section__title">Liked Songs</h2>
                                <div className="tunes-search-tracklist">
                                    {lib.tracks.map((t, i) => (
                                        <TrackRow
                                            key={t.id}
                                            track={t}
                                            list={lib.tracks}
                                            idx={i}
                                            onUnlike={(track) => removeMusicLike('track', track.id)}
                                            isCurrent={state?.current?.id === t.id}
                                        />
                                    ))}
                                </div>
                            </section>
                        )}

                        {hasRadio && (
                            <section className="tunes-section" data-testid="library-radio-section">
                                <h2 className="tunes-section__title">Liked Radio</h2>
                                <div className="tunes-library-grid-compact">
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
                                <div className="tunes-library-grid-compact">
                                    {lib.podcasts.map((p) => (
                                        <CompactTile key={p.id} item={p} kind="podcast" />
                                    ))}
                                </div>
                            </section>
                        )}
                    </div>

                    {/* RIGHT: albums 3-up, artists 3-up underneath */}
                    {(hasAlbums || hasArtists) && (
                        <div className="tunes-library-right">
                            {hasAlbums && (
                                <section className="tunes-section" data-testid="library-album-section">
                                    <h2 className="tunes-section__title">Liked Albums</h2>
                                    <div className="tunes-library-grid-compact">
                                        {lib.albums.map((a) => (
                                            <AlbumTile key={a.id} album={a} />
                                        ))}
                                    </div>
                                </section>
                            )}

                            {hasArtists && (
                                <section className="tunes-section" data-testid="library-artist-section">
                                    <h2 className="tunes-section__title">Liked Artists</h2>
                                    <div className="tunes-library-grid-compact">
                                        {lib.artists.map((a) => (
                                            <ArtistTile key={a.id} artist={a} />
                                        ))}
                                    </div>
                                </section>
                            )}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}
