import React, { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Search as SearchIcon, Radio as RadioIcon, Mic } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import { LikeButton } from '../../components/music/LikeButton';
import { AddToPlaylistButton } from '../../components/music/AddToPlaylistButton';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

export default function MusicSearch() {
    const [q, setQ] = useState('');
    const [results, setResults] = useState(null);
    const [loading, setLoading] = useState(false);
    const debounce = useRef(null);
    const { controls } = useMusicPlayer();

    useEffect(() => {
        if (!q.trim()) {
            setResults(null);
            return undefined;
        }
        clearTimeout(debounce.current);
        debounce.current = setTimeout(() => {
            setLoading(true);
            musicAPI.search(q.trim())
                .then(setResults)
                .catch(() => setResults(null))
                .finally(() => setLoading(false));
        }, 320);
        return () => clearTimeout(debounce.current);
    }, [q]);

    return (
        <div data-testid="music-search">
            <h1 className="tunes-page-title">Search</h1>
            <p className="tunes-page-subtitle">Tracks, albums, artists, radio stations, podcasts — all at once.</p>

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

            {loading && (<div className="tunes-empty">Searching…</div>)}
            {!loading && !results && q.length === 0 && (
                <div className="tunes-empty">Start typing to search across music, radio, and podcasts.</div>
            )}

            {results && (
                <>
                    {/* ─── Artists first (matches Spotify UX expectation) ─── */}
                    {results.artists?.length > 0 && (
                        <section className="tunes-section" data-testid="tunes-results-artists">
                            <h2 className="tunes-section__title">Artists</h2>
                            <div className="tunes-grid">
                                {results.artists.slice(0, 10).map((ar) => (
                                    <div key={ar.id} style={{ position: 'relative' }}>
                                        <Link
                                            to={`/music/artist/${ar.id}`}
                                            className="tunes-card"
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                            data-testid={`search-artist-${ar.id}`}
                                            style={{ textAlign: 'center' }}
                                        >
                                            <img src={ar.picture || ''} alt="" className="tunes-card__art tunes-card__art--round" loading="lazy" />
                                            <div className="tunes-card__body" style={{ textAlign: 'center' }}>
                                                <p className="tunes-card__title">{ar.name}</p>
                                                <p className="tunes-card__subtitle">Artist</p>
                                            </div>
                                        </Link>
                                        <div style={{ position: 'absolute', top: 10, right: 10 }}>
                                            <LikeButton kind="artist" item={ar} size="sm" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>
                    )}

                    {results.tracks?.length > 0 && (
                        <section className="tunes-section" data-testid="tunes-results-tracks">
                            <h2 className="tunes-section__title">Songs</h2>
                            <div className="tunes-track-list">
                                {results.tracks.slice(0, 10).map((t, i) => (
                                    <div
                                        key={t.id}
                                        className="tunes-track-row"
                                        data-focusable="true"
                                        data-focus-style="tile"
                                        tabIndex={0}
                                        onClick={() => controls.playTrack(t, results.tracks)}
                                        onKeyDown={(e) => { if (e.key === 'Enter') controls.playTrack(t, results.tracks); }}
                                        data-testid={`tunes-result-track-${t.id}`}
                                        style={{ gridTemplateColumns: '28px 64px 1fr auto auto auto' }}
                                    >
                                        <div className="tunes-track-row__num">{i + 1}</div>
                                        <img src={t.album?.cover || ''} alt="" className="tunes-track-row__art" loading="lazy" />
                                        <div>
                                            <p className="tunes-track-row__title">{t.title}</p>
                                            <p className="tunes-track-row__artist">{t.artist?.name}</p>
                                        </div>
                                        <span className="tunes-track-row__duration">{fmtDur(t.duration)}</span>
                                        <LikeButton kind="track" item={t} size="sm" />
                                        <AddToPlaylistButton track={t} />
                                    </div>
                                ))}
                            </div>
                        </section>
                    )}

                    {results.albums?.length > 0 && (
                        <section className="tunes-section">
                            <h2 className="tunes-section__title">Albums</h2>
                            <div className="tunes-grid">
                                {results.albums.slice(0, 12).map((a) => (
                                    <div key={a.id} style={{ position: 'relative' }}>
                                        <Link
                                            to={`/music/album/${a.id}`}
                                            className="tunes-card"
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                            data-testid={`search-album-${a.id}`}
                                        >
                                            <img src={a.cover || ''} alt="" className="tunes-card__art" loading="lazy" />
                                            <div className="tunes-card__body">
                                                <p className="tunes-card__title">{a.title}</p>
                                                <p className="tunes-card__subtitle">{a.artist?.name}</p>
                                            </div>
                                        </Link>
                                        <div style={{ position: 'absolute', top: 10, right: 10 }}>
                                            <LikeButton kind="album" item={a} size="sm" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>
                    )}

                    {results.radio?.length > 0 && (
                        <section className="tunes-section tunes-section--radio">
                            <h2 className="tunes-section__title">
                                <RadioIcon size={18} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                                Radio
                            </h2>
                            <div className="tunes-grid">
                                {results.radio.map((s) => (
                                    <div key={s.id} style={{ position: 'relative' }}>
                                        <button
                                            type="button"
                                            className="tunes-card"
                                            onClick={() => { controls.playRadio(s); musicAPI.radioClick(s.id); }}
                                            data-testid={`tunes-result-radio-${s.id}`}
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                            style={{ textAlign: 'left', cursor: 'pointer' }}
                                        >
                                            <div className="tunes-card__art"
                                                style={{
                                                    background: s.favicon
                                                        ? `center/cover no-repeat url(${s.favicon}), linear-gradient(135deg, #064a59, #0a0118)`
                                                        : 'linear-gradient(135deg, #064a59, #0a0118)',
                                                }}
                                            />
                                            <div className="tunes-card__body">
                                                <p className="tunes-card__title">{s.name}</p>
                                                <p className="tunes-card__subtitle">{s.country || 'Radio'}</p>
                                            </div>
                                        </button>
                                        <div style={{ position: 'absolute', top: 10, right: 10 }}>
                                            <LikeButton kind="radio" item={s} size="sm" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>
                    )}

                    {results.podcasts?.length > 0 && (
                        <section className="tunes-section tunes-section--podcast">
                            <h2 className="tunes-section__title">
                                <Mic size={18} style={{ display: 'inline', verticalAlign: -3, marginRight: 8 }} />
                                Podcasts
                            </h2>
                            <div className="tunes-grid">
                                {results.podcasts.map((p) => (
                                    <div key={p.id} style={{ position: 'relative' }}>
                                        <Link
                                            to={`/music/podcast/${encodeURIComponent(p.feed_url)}`}
                                            className="tunes-card"
                                            data-focusable="true"
                                            data-focus-style="tile"
                                            tabIndex={0}
                                        >
                                            <img src={p.artwork || ''} alt="" className="tunes-card__art" loading="lazy" />
                                            <div className="tunes-card__body">
                                                <p className="tunes-card__title">{p.title}</p>
                                                <p className="tunes-card__subtitle">{p.artist || p.genre}</p>
                                            </div>
                                        </Link>
                                        <div style={{ position: 'absolute', top: 10, right: 10 }}>
                                            <LikeButton kind="podcast" item={p} size="sm" />
                                        </div>
                                    </div>
                                ))}
                            </div>
                        </section>
                    )}

                    {Object.values(results).every((v) => !Array.isArray(v) || v.length === 0) && (
                        <div className="tunes-empty">Nothing matched "{q}". Try a different search.</div>
                    )}
                </>
            )}
        </div>
    );
}
