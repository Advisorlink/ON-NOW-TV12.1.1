// ON NOW TV TUNES — Full-screen Now Playing (Vesper-style)
// =============================================================
// Matches the "Neon Hearts" reference:
//   - V2 emblem top-left + NOW PLAYING eyebrow
//   - Album art (1:1) on the left with a glowing cyan ring backdrop
//   - Centered meta: title + artist + album + year/Dolby chip + heart
//   - Lyrics + Up Next panel anchored to the right
//   - Bottom dock: scrubber + transport (big circular play) + volume
import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
    Play, Pause, SkipBack, SkipForward, Shuffle, Repeat,
    Volume2, X, Maximize, Minimize, Heart, BarChart3,
} from 'lucide-react';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import { musicAPI } from '../../lib/music-api';
import {
    isMusicLiked, toggleMusicLike, subscribeMusicLibrary,
} from '../../lib/music-library';

function fmt(secs) {
    if (!Number.isFinite(secs)) return '--:--';
    const s = Math.max(0, Math.floor(secs));
    const m = Math.floor(s / 60);
    const r = String(s % 60).padStart(2, '0');
    return `${m}:${r}`;
}

function requestFs(el) {
    const r = el?.requestFullscreen
        || el?.webkitRequestFullscreen
        || el?.mozRequestFullScreen
        || el?.msRequestFullscreen;
    if (r) { try { return r.call(el); } catch { /* ignore */ } }
    return null;
}
function exitFs() {
    const d = document;
    const e = d.exitFullscreen
        || d.webkitExitFullscreen
        || d.mozCancelFullScreen
        || d.msExitFullscreen;
    if (e && (d.fullscreenElement || d.webkitFullscreenElement)) {
        try { return e.call(d); } catch { /* ignore */ }
    }
    return null;
}
function getFsEl() {
    return document.fullscreenElement
        || document.webkitFullscreenElement
        || document.mozFullScreenElement
        || document.msFullscreenElement
        || null;
}

export function FullScreenPlayer({ onClose }) {
    const { state, controls } = useMusicPlayer();
    const [isFs, setIsFs] = useState(!!getFsEl());
    const [lyrics, setLyrics] = useState(null);
    const [liked, setLiked] = useState(false);
    const rootRef = useRef(null);

    // Try to enter OS-level fullscreen on mount.
    useEffect(() => {
        const root = rootRef.current;
        if (root) { try { requestFs(root); } catch { /* ignore */ } }
        const onChange = () => setIsFs(!!getFsEl());
        document.addEventListener('fullscreenchange', onChange);
        document.addEventListener('webkitfullscreenchange', onChange);
        return () => {
            document.removeEventListener('fullscreenchange', onChange);
            document.removeEventListener('webkitfullscreenchange', onChange);
            exitFs();
        };
    }, []);

    // Fetch synced lyrics from LRCLIB whenever the track changes.
    const t = state.current;
    useEffect(() => {
        if (!t || state.kind !== 'track') { setLyrics(null); return; }
        let active = true;
        setLyrics(null);
        musicAPI.lyrics({
            artist: t.artist?.name || '',
            title: t.title || '',
            album: t.album?.title || undefined,
            duration: state.duration || t.duration || undefined,
        }).then((r) => {
            if (!active) return;
            const data = r?.data || r;
            setLyrics(data);
        }).catch(() => { if (active) setLyrics({ synced: [], plain: '' }); });
        return () => { active = false; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [t?.id]);

    useEffect(() => {
        if (!t) { setLiked(false); return; }
        const update = () => setLiked(isMusicLiked('track', t.id));
        update();
        return subscribeMusicLibrary(update);
    }, [t?.id]);

    const synced = lyrics?.synced || [];
    const position = state.position || 0;
    const activeLyricIdx = useMemo(() => {
        if (!synced.length) return -1;
        let lo = 0, hi = synced.length - 1, best = -1;
        while (lo <= hi) {
            const mid = (lo + hi) >> 1;
            if (synced[mid].t <= position) { best = mid; lo = mid + 1; }
            else hi = mid - 1;
        }
        return best;
    }, [synced, position]);

    const visibleLyrics = useMemo(() => {
        if (!synced.length) return [];
        const start = Math.max(0, activeLyricIdx - 2);
        const end = Math.min(synced.length, activeLyricIdx + 4);
        return synced.slice(start, end).map((row, i) => ({
            ...row,
            offset: start + i - activeLyricIdx,
        }));
    }, [synced, activeLyricIdx]);

    if (!t) return null;

    const isLive = state.kind === 'radio';
    const pct = isLive || !state.duration
        ? 0
        : Math.min(100, (state.position / state.duration) * 100);

    // Artwork with YouTube fallback chain.
    const yt = t._ytId || t.yt_id;
    const ytArt = yt ? `https://i.ytimg.com/vi/${yt}/maxresdefault.jpg` : null;
    const artSrc = t.artwork || t.album?.cover_xl || t.album?.cover || ytArt || null;
    const bgSrc = artSrc;

    const toggleFs = () => {
        if (getFsEl()) exitFs();
        else if (rootRef.current) requestFs(rootRef.current);
    };

    // Build the Up Next list (next 5 tracks after current).
    const upNext = state.kind === 'track' && state.queue
        ? state.queue.slice(state.queueIndex + 1, state.queueIndex + 6)
        : [];

    return (
        <div
            ref={rootRef}
            className="tunes-fullplayer"
            data-testid="tunes-fullplayer"
        >
            {bgSrc && (
                <div
                    className="tunes-fullplayer__bg"
                    style={{ backgroundImage: `url(${bgSrc})` }}
                />
            )}
            <div className="tunes-fullplayer__scrim" />

            <div className="tunes-fullplayer__corner-left">
                <span
                    style={{
                        display: 'inline-block', marginRight: 12,
                        fontWeight: 800, fontSize: 22,
                        letterSpacing: '-0.04em',
                        color: 'var(--vesper-blue)',
                        textShadow: '0 0 14px rgba(var(--vesper-blue-rgb),0.55)',
                        verticalAlign: -2,
                    }}
                    aria-hidden="true"
                >
                    ♪
                </span>
                Now Playing
            </div>

            <div className="tunes-fullplayer__corner-right">
                <button
                    type="button"
                    className="tunes-iconbtn"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={toggleFs}
                    aria-label={isFs ? 'Exit fullscreen' : 'Enter fullscreen'}
                    title={isFs ? 'Exit fullscreen' : 'Enter fullscreen'}
                    data-testid="tunes-fullplayer-fs"
                >
                    {isFs ? <Minimize size={20} /> : <Maximize size={20} />}
                </button>
                <button
                    type="button"
                    className="tunes-iconbtn"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onClose}
                    aria-label="Close player"
                    data-testid="tunes-fullplayer-close"
                >
                    <X size={20} />
                </button>
            </div>

            <div className="tunes-fullplayer__body">
                <div className="tunes-fullplayer__art-col">
                    <div className="tunes-fullplayer__art">
                        <div className="tunes-fullplayer__art-ring" />
                        {artSrc && (
                            <img
                                src={artSrc}
                                alt={t.title || ''}
                                onError={(e) => {
                                    const img = e.currentTarget;
                                    if (yt && img.src.includes('maxresdefault')) {
                                        img.src = `https://i.ytimg.com/vi/${yt}/hqdefault.jpg`;
                                    } else if (yt && img.src.includes('hqdefault')) {
                                        img.src = `https://i.ytimg.com/vi/${yt}/mqdefault.jpg`;
                                    } else {
                                        img.style.display = 'none';
                                    }
                                }}
                            />
                        )}
                    </div>
                </div>

                <div className="tunes-fullplayer__meta">
                    <h1 className="tunes-fullplayer__title">{t.title}</h1>
                    {t.artist?.name && (
                        <p className="tunes-fullplayer__artist">{t.artist.name}</p>
                    )}
                    {t.album?.title && (
                        <p className="tunes-fullplayer__album">{t.album.title}</p>
                    )}
                    <div className="tunes-fullplayer__chips">
                        {t.album?.release_date && (
                            <span className="tunes-fullplayer__chip">
                                {String(t.album.release_date).slice(0, 4)}
                            </span>
                        )}
                        {t._isFullTrack && (
                            <span className="tunes-fullplayer__chip">
                                Full Track · {t._streamSource}
                            </span>
                        )}
                        {!t._isFullTrack && !t._resolving && (
                            <span className="tunes-fullplayer__chip">
                                30 s Preview
                            </span>
                        )}
                    </div>
                    <button
                        type="button"
                        className="tunes-fullplayer__like"
                        data-liked={liked}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => toggleMusicLike('track', t)}
                        data-testid="tunes-fullplayer-like"
                        aria-label={liked ? 'Unlike' : 'Like'}
                    >
                        <Heart
                            size={22}
                            fill={liked ? 'currentColor' : 'none'}
                            strokeWidth={1.8}
                        />
                    </button>
                </div>

                <aside className="tunes-fullplayer__queue" data-testid="tunes-fullplayer-side">
                    <section className="tunes-fullplayer__queue-section">
                        <h3 className="tunes-fullplayer__queue-title">Lyrics</h3>
                        {visibleLyrics.length === 0 && lyrics?.plain && (
                            <div style={{ maxHeight: 280, overflow: 'hidden' }}>
                                {lyrics.plain.split('\n').slice(0, 8).map((line, i) => (
                                    <p key={i} className="tunes-lyric-row">{line || '\u00A0'}</p>
                                ))}
                            </div>
                        )}
                        {visibleLyrics.length > 0 && visibleLyrics.map((row) => (
                            <p
                                key={`${row.t}-${row.offset}`}
                                className="tunes-lyric-row"
                                data-active={row.offset === 0}
                            >
                                {row.text || '♪'}
                            </p>
                        ))}
                        {visibleLyrics.length === 0 && !lyrics?.plain && lyrics && (
                            <p className="tunes-lyric-row" data-active="false">
                                {lyrics.instrumental
                                    ? 'Instrumental — vibe out'
                                    : 'Lyrics unavailable for this track.'}
                            </p>
                        )}
                        {!lyrics && (
                            <p className="tunes-lyric-row">Loading lyrics…</p>
                        )}
                    </section>

                    {upNext.length > 0 && (
                        <section className="tunes-fullplayer__queue-section">
                            <h3 className="tunes-fullplayer__queue-title">Up Next</h3>
                            {upNext.map((q) => (
                                <button
                                    key={q.id}
                                    type="button"
                                    className="tunes-queue-row"
                                    onClick={() => controls.playTrack(q, state.queue)}
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    data-testid={`tunes-up-next-${q.id}`}
                                    style={{ background: 'none', border: 'none', textAlign: 'left', width: '100%' }}
                                >
                                    {q.album?.cover && <img src={q.album.cover} alt="" />}
                                    <div style={{ minWidth: 0 }}>
                                        <p className="tunes-queue-row__title">{q.title}</p>
                                        <p className="tunes-queue-row__subtitle">{q.artist?.name || ''}</p>
                                    </div>
                                    <span className="tunes-queue-row__time">{fmt(q.duration)}</span>
                                </button>
                            ))}
                        </section>
                    )}
                </aside>
            </div>

            <div className="tunes-fullplayer__dock">
                {!isLive && (
                    <div className="tunes-fullplayer__scrub">
                        <span>{fmt(state.position)}</span>
                        <div
                            className="tunes-fullplayer__scrub-bar"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            role="slider"
                            aria-valuemin={0}
                            aria-valuemax={state.duration || 0}
                            aria-valuenow={state.position || 0}
                            onClick={(e) => {
                                if (!state.duration) return;
                                const r = e.currentTarget.getBoundingClientRect();
                                controls.seek(state.duration * ((e.clientX - r.left) / r.width));
                            }}
                            onKeyDown={(e) => {
                                if (!state.duration) return;
                                const step = state.duration * 0.05;
                                if (e.key === 'ArrowLeft') {
                                    e.preventDefault();
                                    controls.seek(Math.max(0, (state.position || 0) - step));
                                } else if (e.key === 'ArrowRight') {
                                    e.preventDefault();
                                    controls.seek(Math.min(state.duration, (state.position || 0) + step));
                                }
                            }}
                        >
                            <div
                                className="tunes-fullplayer__scrub-fill"
                                style={{ width: `${pct}%` }}
                            />
                        </div>
                        <span>{fmt(state.duration)}</span>
                    </div>
                )}

                <div className="tunes-fullplayer__transport">
                    <button
                        type="button"
                        className="tunes-iconbtn"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        aria-label="Shuffle"
                        data-testid="tunes-fullplayer-shuffle"
                    >
                        <Shuffle size={22} />
                    </button>
                    <button
                        type="button"
                        className="tunes-iconbtn"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={controls.previous}
                        disabled={state.kind !== 'track'}
                        aria-label="Previous"
                        data-testid="tunes-fullplayer-prev"
                    >
                        <SkipBack size={28} />
                    </button>
                    <button
                        type="button"
                        className="tunes-iconbtn tunes-iconbtn--play"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={controls.toggle}
                        data-testid="tunes-fullplayer-toggle"
                        aria-label={state.isPlaying ? 'Pause' : 'Play'}
                    >
                        {state.isPlaying
                            ? <Pause size={32} />
                            : <Play size={32} style={{ marginLeft: 4 }} fill="currentColor" />}
                    </button>
                    <button
                        type="button"
                        className="tunes-iconbtn"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={controls.next}
                        disabled={state.kind !== 'track'}
                        aria-label="Next"
                        data-testid="tunes-fullplayer-next"
                    >
                        <SkipForward size={28} />
                    </button>
                    <button
                        type="button"
                        className="tunes-iconbtn"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        aria-label="Repeat"
                        data-testid="tunes-fullplayer-repeat"
                    >
                        <Repeat size={22} />
                    </button>
                </div>

                <div className="tunes-fullplayer__bottom-row">
                    <div className="tunes-volume">
                        <Volume2 size={18} />
                        <input
                            type="range"
                            min={0}
                            max={1}
                            step={0.02}
                            value={state.volume}
                            onChange={(e) => controls.setVolume(parseFloat(e.target.value))}
                            style={{ width: 160, accentColor: 'var(--vesper-blue)' }}
                            data-testid="tunes-fullplayer-volume"
                        />
                    </div>
                    <BarChart3 size={18} color="var(--vesper-text-3)" />
                </div>
            </div>
        </div>
    );
}
