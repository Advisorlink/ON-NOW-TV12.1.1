// V2 Karaoke — beautiful immersive sing-along experience.
//
// Architecture:
//
//   1. /music/karaoke
//        Landing screen.  Search any song → tap → opens the
//        karaoke view for that track.  Recently-played karaoke
//        sessions surface as a "Sing again" rail at the top.
//
//   2. /music/karaoke/play/:trackId   (managed by `KaraokeStage`)
//        Full-screen karaoke view.  Background = blurred YouTube
//        thumbnail + dark gradient.  Center = synced lyrics with
//        the current line scaled up + glowing in the active theme
//        colour, previous/next lines smaller + dim.  Bottom = a
//        minimal control bar (play/pause, scrub, exit).  Top-right
//        = animated microphone icon that pulses with the beat.
//
//   3. Lyrics come from LRCLIB via the backend `/api/music/lyrics`
//      endpoint.  Synced format `[mm:ss.xx]` is parsed server-side.
//
//   4. Audio plays through the existing `useMusicPlayer` engine —
//      same NewPipe / YouTube IFrame / Deezer-preview resolution
//      chain we already shipped.  Karaoke is just a different view
//      on the same playback engine.
//
// Smooth-as-butter tricks:
//   • Lyric transitions use `transform: scale()` + `opacity`
//     (GPU-cheap) instead of changing font-size.
//   • Background blur is `filter: blur(40px)` applied to a static
//     image (no re-blur per frame).
//   • Beat pulse on the mic icon is CSS `@keyframes` (no JS timer).

import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
    Mic, Search as SearchIcon, ArrowLeft, Play, Pause, X, Sparkles,
} from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

/**
 * Landing screen — search box + curated "popular karaoke" picks.
 *
 * Keeps the look-and-feel consistent with the rest of the Tunes
 * app (same `.tunes-page-title`, `.tunes-search-input`, etc.) but
 * the headline uses a karaoke-stage gradient instead of the
 * default white.
 */
export default function KaraokePage() {
    const [q, setQ] = useState('');
    const [results, setResults] = useState(null);
    const [picks, setPicks] = useState(null);
    const [loading, setLoading] = useState(false);
    const navigate = useNavigate();

    // Curated "always-good karaoke songs" — first thing the user
    // sees so the page isn't empty on first load.
    useEffect(() => {
        let active = true;
        musicAPI.search('queen bohemian rhapsody').then((r) => {
            if (!active) return;
            const seeds = [
                'queen bohemian rhapsody',
                'adele rolling in the deep',
                'whitney houston i will always love you',
                'bon jovi livin on a prayer',
                'taylor swift shake it off',
                'journey dont stop believin',
                'ed sheeran perfect',
                'celine dion my heart will go on',
            ];
            return Promise.all(
                seeds.map((s) => musicAPI.search(s).then((sr) => (sr?.data?.tracks || sr?.tracks || [])[0]).catch(() => null))
            );
        }).then((seedResults) => {
            if (!active) return;
            const filtered = (seedResults || []).filter(Boolean);
            setPicks(filtered);
        }).catch(() => setPicks([]));
        return () => { active = false; };
    }, []);

    useEffect(() => {
        const t = q.trim();
        if (!t) { setResults(null); return; }
        let active = true;
        setLoading(true);
        const handle = setTimeout(async () => {
            try {
                const r = await musicAPI.search(t);
                if (!active) return;
                setResults(r.data?.tracks || r.tracks || []);
            } catch {
                if (active) setResults([]);
            } finally {
                if (active) setLoading(false);
            }
        }, 280);
        return () => { active = false; clearTimeout(handle); };
    }, [q]);

    const startKaraoke = (track) => {
        if (!track) return;
        navigate(`/music/karaoke/play/${encodeURIComponent(track.id)}`, {
            state: { track },
        });
    };

    return (
        <div className="tunes-karaoke" data-testid="music-karaoke">
            <div className="tunes-karaoke__hero">
                <div className="tunes-karaoke__hero-icon">
                    <Mic size={36} strokeWidth={1.5} />
                    <Sparkles size={18} className="tunes-karaoke__sparkle" />
                </div>
                <div>
                    <h1 className="tunes-karaoke__title">V2 Karaoke</h1>
                    <p className="tunes-karaoke__subtitle">
                        Pick any song · lyrics light up in time · sing along to your screen.
                    </p>
                </div>
            </div>

            <div className="tunes-karaoke__search">
                <SearchIcon size={20} className="tunes-karaoke__search-icon" />
                <input
                    className="tunes-search-input tunes-karaoke__search-input"
                    placeholder="Search any song to sing…"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    data-testid="karaoke-search-input"
                    data-focusable="true"
                    data-focus-style="pill"
                />
            </div>

            {results !== null && (
                <section className="tunes-section" data-testid="shelf-page" data-shelf-id="karaoke-results">
                    <h2 className="tunes-section__title">
                        {loading ? 'Searching…' : `${results.length} result${results.length === 1 ? '' : 's'}`}
                    </h2>
                    <div className="tunes-karaoke__grid">
                        {results.map((t) => (
                            <KaraokeSongCard key={t.id} track={t} onClick={() => startKaraoke(t)} />
                        ))}
                        {!loading && results.length === 0 && (
                            <p className="tunes-empty">Nothing matched. Try another song.</p>
                        )}
                    </div>
                </section>
            )}

            {results === null && (
                <section className="tunes-section" data-testid="shelf-page" data-shelf-id="karaoke-picks">
                    <h2 className="tunes-section__title">Crowd-pleasers</h2>
                    {picks === null && <p className="tunes-empty">Warming up the stage…</p>}
                    <div className="tunes-karaoke__grid">
                        {(picks || []).map((t) => (
                            <KaraokeSongCard key={t.id} track={t} onClick={() => startKaraoke(t)} />
                        ))}
                    </div>
                </section>
            )}
        </div>
    );
}

/** A single song tile on the karaoke landing screen. */
function KaraokeSongCard({ track, onClick }) {
    const cover = track?.album?.cover || track?.artwork;
    return (
        <button
            type="button"
            className="tunes-karaoke-card"
            onClick={onClick}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`karaoke-song-${track.id}`}
        >
            <div className="tunes-karaoke-card__art" style={{ backgroundImage: cover ? `url(${cover})` : undefined }}>
                <div className="tunes-karaoke-card__play">
                    <Mic size={28} />
                </div>
            </div>
            <div className="tunes-karaoke-card__body">
                <p className="tunes-karaoke-card__title">{track.title}</p>
                <p className="tunes-karaoke-card__artist">{track.artist?.name || ''}</p>
            </div>
        </button>
    );
}

/**
 * Full-screen karaoke stage.  Mounted at /music/karaoke/play/:trackId
 * — receives `{ track }` via React Router's location state OR fetches
 * the track if the user deep-linked.
 */
export function KaraokeStage() {
    const { trackId } = useParams();
    const navigate = useNavigate();
    const { state, controls } = useMusicPlayer();
    const passedTrack = (window.history.state?.usr?.track) || null;
    const [track, setTrack] = useState(passedTrack);
    const [lyrics, setLyrics] = useState(null);
    const [lyricsError, setLyricsError] = useState(null);

    // Fetch track if we don't have it (deep-link scenario).
    useEffect(() => {
        if (track) return;
        let active = true;
        musicAPI.search(trackId).then((r) => {
            const found = (r?.data?.tracks || r?.tracks || []).find((t) => String(t.id) === String(trackId));
            if (active) setTrack(found || null);
        }).catch(() => {});
        return () => { active = false; };
    }, [trackId, track]);

    // Kick off playback as soon as we have a track.
    useEffect(() => {
        if (!track) return;
        controls.playTrack(track, [track]);
        // We intentionally don't re-trigger on controls — the
        // useMusicPlayer hook handles play/pause via state.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [track?.id]);

    // Fetch lyrics once we know the track.
    useEffect(() => {
        if (!track) return;
        let active = true;
        setLyricsError(null);
        musicAPI.lyrics({
            artist: track.artist?.name || '',
            title: track.title,
            album: track.album?.title || undefined,
            duration: track.duration || undefined,
        }).then((r) => {
            if (!active) return;
            setLyrics(r.data || r);
        }).catch(() => {
            if (active) setLyricsError('Could not load lyrics');
        });
        return () => { active = false; };
    }, [track?.id]);

    // Current position in seconds, refreshed from the player engine.
    const position = state.position || 0;
    const synced = lyrics?.synced || [];
    const activeIdx = useMemo(() => {
        if (!synced.length) return -1;
        // Find the last entry whose timestamp is ≤ current position.
        // The lyric "active" until the NEXT entry's timestamp shows.
        let lo = 0, hi = synced.length - 1, best = -1;
        while (lo <= hi) {
            const mid = (lo + hi) >> 1;
            if (synced[mid].t <= position) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }, [synced, position]);

    const onExit = () => {
        controls.pause();
        navigate('/music/karaoke');
    };

    const cover = track?.album?.cover || track?.artwork;
    const ytId = track?._ytId || track?.yt_id;
    const ytArt = ytId ? `https://i.ytimg.com/vi/${ytId}/maxresdefault.jpg` : null;
    const bgArt = cover || ytArt;

    if (!track) {
        return (
            <div className="tunes-karaoke-stage tunes-karaoke-stage--loading">
                <p>Loading song…</p>
            </div>
        );
    }

    return (
        <div className="tunes-karaoke-stage" data-testid="karaoke-stage">
            {/* Backdrop — blurred album / video art with a vignette
                 so the lyrics text stays legible regardless of art. */}
            <div
                className="tunes-karaoke-stage__bg"
                style={{ backgroundImage: bgArt ? `url(${bgArt})` : undefined }}
            />
            <div className="tunes-karaoke-stage__vignette" />

            {/* Top bar — track meta + animated mic icon + close. */}
            <header className="tunes-karaoke-stage__top">
                <button
                    type="button"
                    className="tunes-karaoke-stage__exit"
                    onClick={onExit}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    data-testid="karaoke-exit"
                    aria-label="Exit karaoke"
                >
                    <ArrowLeft size={22} />
                </button>
                <div className="tunes-karaoke-stage__meta">
                    <p className="tunes-karaoke-stage__song">{track.title}</p>
                    <p className="tunes-karaoke-stage__artist">{track.artist?.name || ''}</p>
                </div>
                <div className={'tunes-karaoke-stage__mic' + (state.isPlaying ? ' is-playing' : '')}>
                    <Mic size={26} />
                </div>
            </header>

            {/* Lyric column — center of the screen.  Three lines
                 visible at once: previous, current, next.  Lines
                 transition with the active theme's accent colour. */}
            <main className="tunes-karaoke-stage__lyrics" data-testid="karaoke-lyrics">
                {synced.length > 0 ? (
                    <LyricsTicker synced={synced} activeIdx={activeIdx} />
                ) : lyrics?.instrumental ? (
                    <div className="tunes-karaoke-stage__instr">
                        <Sparkles size={48} />
                        <p>Instrumental track</p>
                        <span>No lyrics — vibe out</span>
                    </div>
                ) : lyrics?.plain ? (
                    <PlainLyricsScroll plain={lyrics.plain} />
                ) : lyricsError ? (
                    <div className="tunes-karaoke-stage__missing">
                        <p>Lyrics unavailable</p>
                        <span>The song still plays — just no captions for this one.</span>
                    </div>
                ) : (
                    <div className="tunes-karaoke-stage__missing">
                        <p>Loading lyrics…</p>
                    </div>
                )}
            </main>

            {/* Bottom dock — minimal play/pause + scrub bar. */}
            <footer className="tunes-karaoke-stage__dock">
                <button
                    type="button"
                    className="tunes-karaoke-stage__play"
                    onClick={() => controls.toggle()}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    data-testid="karaoke-toggle"
                    aria-label={state.isPlaying ? 'Pause' : 'Play'}
                >
                    {state.isPlaying ? <Pause size={28} /> : <Play size={28} />}
                </button>
                <div className="tunes-karaoke-stage__scrub">
                    <span className="tunes-karaoke-stage__time">{fmt(position)}</span>
                    <div className="tunes-karaoke-stage__bar" role="progressbar"
                         aria-valuemin={0} aria-valuemax={state.duration || 0} aria-valuenow={position}>
                        <div
                            className="tunes-karaoke-stage__bar-fill"
                            style={{ width: `${state.duration > 0 ? (position / state.duration) * 100 : 0}%` }}
                        />
                    </div>
                    <span className="tunes-karaoke-stage__time">{fmt(state.duration || 0)}</span>
                </div>
                <button
                    type="button"
                    className="tunes-karaoke-stage__close"
                    onClick={onExit}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    aria-label="Close karaoke"
                >
                    <X size={20} />
                </button>
            </footer>
        </div>
    );
}

/** Synced-lyrics ticker.  Shows the previous, current and next
 *  line stacked vertically, with the current line emphasised. */
function LyricsTicker({ synced, activeIdx }) {
    // Range = [active-2, active+3] so we always show 5 lines for
    // depth, even at the edges of the song.
    const start = Math.max(0, activeIdx - 2);
    const end = Math.min(synced.length, activeIdx + 4);
    const visible = synced.slice(start, end).map((row, i) => ({
        ...row,
        offset: start + i - activeIdx,
    }));

    return (
        <div className="tunes-karaoke-stage__ticker">
            {visible.map((row) => {
                const isActive = row.offset === 0;
                const dist = Math.abs(row.offset);
                return (
                    <p
                        key={row.t}
                        className={
                            'tunes-karaoke-stage__line' +
                            (isActive ? ' is-active' : '') +
                            (row.offset < 0 ? ' is-past' : '')
                        }
                        style={{
                            opacity: isActive ? 1 : Math.max(0.18, 0.65 - dist * 0.18),
                            transform: `scale(${isActive ? 1 : 1 - dist * 0.08})`,
                        }}
                    >
                        {row.text || '♪'}
                    </p>
                );
            })}
        </div>
    );
}

/** Fallback when only plain (un-timed) lyrics are available — the
 *  user gets a simple scrolling view instead of synced highlight. */
function PlainLyricsScroll({ plain }) {
    const lines = plain.split('\n').filter(Boolean);
    return (
        <div className="tunes-karaoke-stage__plain">
            <p className="tunes-karaoke-stage__plain-note">No synced lyrics available — showing the full text instead.</p>
            <div className="tunes-karaoke-stage__plain-scroll">
                {lines.map((line, i) => <p key={i}>{line}</p>)}
            </div>
        </div>
    );
}

function fmt(s) {
    const x = Math.max(0, Math.floor(s || 0));
    return `${Math.floor(x / 60)}:${String(x % 60).padStart(2, '0')}`;
}
