// ON NOW TV TUNES — Search (v2.10.47 redesign)
// =============================================================
// User-feedback rewrite: "When you search something and it comes
// up it shows like a whole [mess].  It's just too hard to navigate
// and it skips a lot of stuff."
//
// The old version mixed three different grids per result type AND
// embedded an inline LikeButton + AddToPlaylistButton on top of
// every tile — so each "row" of focusables had ragged geometry
// (tile → like → playlist → tile → like → playlist…) which made
// D-pad rights / lefts skip in unpredictable amounts.
//
// The redesign:
//   * Single uniform tile size per kind (round avatars for
//     artists, square covers for albums / radio / podcasts, slim
//     rows for tracks).
//   * Per-row tile counts via a hard `repeat(N, 1fr)` grid (not
//     `auto-fill`) so geometry never depends on screen width
//     parity.
//   * Inline LikeButton + AddToPlaylistButton REMOVED.  Press-and-
//     hold OK on any tile opens the same "Add to library" modal
//     the user just asked for — one consistent affordance instead
//     of three competing ones.
//   * Tracks: re-tapping the currently-playing one opens the
//     FullScreen player (handled by `useTuneTap`).

import React, { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useNavigate } from 'react-router-dom';
import { Search as SearchIcon, Radio as RadioIcon, Mic, Music2 } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import useTuneTap from '../../hooks/useTuneTap';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

/* ── Reusable tiles ─────────────────────────────────────────── */

function TrackResultRow({ track, list, isCurrent }) {
    const tap = useTuneTap({ kind: 'track', item: track, list });
    return (
        <button
            type="button"
            className={'tunes-result-track' + (isCurrent ? ' is-playing' : '')}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`tunes-result-track-${track.id}`}
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
        </button>
    );
}

function ArtistResultTile({ artist }) {
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
            data-testid={`search-artist-${artist.id}`}
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

function AlbumResultTile({ album }) {
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
            data-testid={`search-album-${album.id}`}
            {...tap}
        >
            <img
                src={album.cover || ''}
                alt=""
                className="tunes-result-tile__art"
                loading="lazy"
            />
            <p className="tunes-result-tile__title">{album.title}</p>
            <p className="tunes-result-tile__subtitle">{album.artist?.name}</p>
        </button>
    );
}

function RadioResultTile({ station }) {
    const tap = useTuneTap({
        kind: 'radio',
        item: station,
        onTap: (s) => {
            try { musicAPI.radioClick(s.id); } catch { /* ignore */ }
        },
    });
    return (
        <button
            type="button"
            className="tunes-result-tile"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`tunes-result-radio-${station.id}`}
            {...tap}
        >
            <div
                className="tunes-result-tile__art"
                style={{
                    background: station.favicon
                        ? `center/cover no-repeat url(${station.favicon}), linear-gradient(135deg, #064a59, #0a0118)`
                        : 'linear-gradient(135deg, #064a59, #0a0118)',
                }}
            />
            <p className="tunes-result-tile__title">{station.name}</p>
            <p className="tunes-result-tile__subtitle">{station.country || 'Radio'}</p>
        </button>
    );
}

function PodcastResultTile({ podcast }) {
    const navigate = useNavigate();
    const tap = useTuneTap({
        kind: 'podcast',
        item: podcast,
        onTap: () => navigate(`/music/podcast/${encodeURIComponent(podcast.feed_url)}`),
    });
    return (
        <button
            type="button"
            className="tunes-result-tile"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`search-podcast-${podcast.id || podcast.feed_url}`}
            {...tap}
        >
            <img
                src={podcast.artwork || ''}
                alt=""
                className="tunes-result-tile__art"
                loading="lazy"
            />
            <p className="tunes-result-tile__title">{podcast.title}</p>
            <p className="tunes-result-tile__subtitle">{podcast.artist || podcast.genre}</p>
        </button>
    );
}

/* ── Page ───────────────────────────────────────────────────── */

export default function MusicSearch() {
    const [params] = useSearchParams();
    const initialQ = params.get('q') || '';
    const [q, setQ] = useState(initialQ);
    const [results, setResults] = useState(null);
    const [loading, setLoading] = useState(false);
    const debounce = useRef(null);
    const { state } = useMusicPlayer();

    useEffect(() => {
        if (!q.trim()) {
            setResults(null);
            return undefined;
        }
        clearTimeout(debounce.current);
        debounce.current = setTimeout(() => {
            setLoading(true);
            musicAPI
                .search(q.trim())
                .then(setResults)
                .catch(() => setResults(null))
                .finally(() => setLoading(false));
        }, 320);
        return () => clearTimeout(debounce.current);
    }, [q]);

    const hasResults =
        results &&
        ((results.tracks?.length || 0) > 0 ||
            (results.albums?.length || 0) > 0 ||
            (results.artists?.length || 0) > 0 ||
            (results.radio?.length || 0) > 0 ||
            (results.podcasts?.length || 0) > 0);

    return (
        <div data-testid="music-search" className="tunes-search-page">
            <h1 className="tunes-page-title">Search</h1>
            <p className="tunes-page-subtitle">
                Tracks, albums, artists, radio stations, podcasts. All at once.
            </p>

            <div className="tunes-search-wrap">
                <SearchIcon size={22} className="tunes-search-icon" />
                <input
                    className="tunes-search-input"
                    placeholder="What do you want to listen to?"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    autoFocus
                    data-testid="tunes-search-input"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                />
            </div>

            {loading && <div className="tunes-empty">Searching…</div>}
            {!loading && !results && q.length === 0 && (
                <div className="tunes-empty">
                    Start typing to search across music, radio, and podcasts.
                </div>
            )}

            {results && !hasResults && (
                <div className="tunes-empty">
                    Nothing matched &quot;{q}&quot;. Try a different search.
                </div>
            )}

            {hasResults && (
                <div className="tunes-search-results">

                    {/* TOP MATCHES — songs row first, side-by-side with
                        the artist grid for fast scanning at TV distance. */}
                    {(results.tracks?.length > 0 || results.artists?.length > 0) && (
                        <div className="tunes-search-row tunes-search-row--top">
                            {results.tracks?.length > 0 && (
                                <section
                                    className="tunes-section tunes-search-songs"
                                    data-testid="tunes-results-tracks"
                                >
                                    <h2 className="tunes-section__title">
                                        <Music2 size={18} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                                        Songs
                                    </h2>
                                    <div className="tunes-search-tracklist">
                                        {results.tracks.slice(0, 6).map((t) => (
                                            <TrackResultRow
                                                key={t.id}
                                                track={t}
                                                list={results.tracks}
                                                isCurrent={state?.current?.id === t.id && state?.kind === 'track'}
                                            />
                                        ))}
                                    </div>
                                </section>
                            )}

                            {results.artists?.length > 0 && (
                                <section
                                    className="tunes-section tunes-search-artists"
                                    data-testid="tunes-results-artists"
                                >
                                    <h2 className="tunes-section__title">Artists</h2>
                                    <div className="tunes-search-artists-grid">
                                        {results.artists.slice(0, 6).map((ar) => (
                                            <ArtistResultTile key={ar.id} artist={ar} />
                                        ))}
                                    </div>
                                </section>
                            )}
                        </div>
                    )}

                    {/* ALBUMS — full-width 4-up row */}
                    {results.albums?.length > 0 && (
                        <section className="tunes-section" data-testid="tunes-results-albums">
                            <h2 className="tunes-section__title">Albums</h2>
                            <div className="tunes-search-grid tunes-search-grid--4">
                                {results.albums.slice(0, 8).map((a) => (
                                    <AlbumResultTile key={a.id} album={a} />
                                ))}
                            </div>
                        </section>
                    )}

                    {/* RADIO + PODCASTS — half-width side-by-side */}
                    {(results.radio?.length > 0 || results.podcasts?.length > 0) && (
                        <div className="tunes-search-row">
                            {results.radio?.length > 0 && (
                                <section
                                    className="tunes-section tunes-section--radio"
                                    data-testid="tunes-results-radio"
                                >
                                    <h2 className="tunes-section__title">
                                        <RadioIcon size={18} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                                        Radio
                                    </h2>
                                    <div className="tunes-search-grid tunes-search-grid--3">
                                        {results.radio.slice(0, 6).map((s) => (
                                            <RadioResultTile key={s.id} station={s} />
                                        ))}
                                    </div>
                                </section>
                            )}

                            {results.podcasts?.length > 0 && (
                                <section
                                    className="tunes-section tunes-section--podcast"
                                    data-testid="tunes-results-podcasts"
                                >
                                    <h2 className="tunes-section__title">
                                        <Mic size={18} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                                        Podcasts
                                    </h2>
                                    <div className="tunes-search-grid tunes-search-grid--3">
                                        {results.podcasts.slice(0, 6).map((p) => (
                                            <PodcastResultTile
                                                key={p.id || p.feed_url}
                                                podcast={p}
                                            />
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
