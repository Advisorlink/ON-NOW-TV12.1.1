// ON NOW TV TUNES — Mini player (Vesper-style bottom bar)
// =============================================================
// Layout (mirrors the Neon Dreams reference):
//   left  : cover + title/subtitle + heart-like button
//   center: shuffle · prev · BIG round play (cyan ring) · next · repeat
//           with scrub bar + duration below
//   right : volume slider + full-screen button
import React, { useEffect, useState } from 'react';
import {
    Play, Pause, SkipBack, SkipForward, Shuffle, Repeat,
    Heart, Maximize2, Volume2, VolumeX,
} from 'lucide-react';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import { FullScreenPlayer } from '../../pages/music/FullScreenPlayer';
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

export function MiniPlayer() {
    const { state, controls } = useMusicPlayer();
    const [expanded, setExpanded] = useState(false);
    const [liked, setLiked] = useState(false);
    const [muted, setMuted] = useState(false);
    const [lastVol, setLastVol] = useState(0.85);

    useEffect(() => {
        const t = state.current;
        if (!t) { setLiked(false); return; }
        const update = () => setLiked(isMusicLiked('track', t.id));
        update();
        return subscribeMusicLibrary(update);
    }, [state.current?.id]);

    if (!state.current) return null;

    const t = state.current;
    const isLive = state.kind === 'radio';
    const pct = isLive || !state.duration
        ? 0
        : Math.min(100, (state.position / state.duration) * 100);

    // Album art with YouTube fallback (covers tracks resolved as
    // youtube-iframe / youtube-direct that don't have artwork).
    const yt = t._ytId || t.yt_id;
    const ytArt = yt ? `https://i.ytimg.com/vi/${yt}/mqdefault.jpg` : null;
    const artSrc = t.artwork || t.album?.cover || ytArt || null;

    return (
        <>
            <div className="tunes-mini" data-testid="tunes-mini-player">
                <button
                    type="button"
                    className="tunes-mini__left"
                    onClick={() => setExpanded(true)}
                    data-testid="tunes-mini-now-btn"
                    data-focusable="true"
                    data-focus-style="tile"
                    tabIndex={0}
                    aria-label="Open Now Playing"
                >
                    {artSrc
                        ? <img src={artSrc} alt="" className="tunes-mini__art" />
                        : <div className="tunes-mini__art" />}
                    <div className="tunes-mini__text">
                        <p className="tunes-mini__title">
                            <span>{t.title}</span>
                            {t.explicit_lyrics && (
                                <span className="tunes-track-row__pill">E</span>
                            )}
                        </p>
                        <p className="tunes-mini__subtitle">
                            {state.kind === 'track'
                                ? (t.artist?.name || t.album?.title || '')
                                : state.kind === 'radio'
                                    ? `LIVE${t.subtitle ? ` · ${t.subtitle}` : ''}`
                                    : (t.subtitle || '')}
                        </p>
                    </div>
                    <span
                        className="tunes-mini__like"
                        data-liked={liked}
                        data-testid="tunes-mini-like"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        role="button"
                        aria-label={liked ? 'Unlike' : 'Like'}
                        onClick={(e) => {
                            e.stopPropagation();
                            toggleMusicLike('track', t);
                        }}
                    >
                        <Heart size={18} fill={liked ? 'currentColor' : 'none'} />
                    </span>
                </button>

                <div className="tunes-mini__center">
                    <div className="tunes-mini__buttons">
                        <button
                            type="button"
                            className="tunes-iconbtn"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            aria-label="Shuffle"
                            data-testid="tunes-mini-shuffle"
                        >
                            <Shuffle size={18} />
                        </button>
                        <button
                            type="button"
                            className="tunes-iconbtn"
                            onClick={controls.previous}
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            data-testid="tunes-mini-prev"
                            aria-label="Previous"
                            disabled={state.kind !== 'track'}
                        >
                            <SkipBack size={20} />
                        </button>
                        <button
                            type="button"
                            className="tunes-iconbtn tunes-iconbtn--play"
                            onClick={controls.toggle}
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            data-testid="tunes-mini-toggle"
                            aria-label={state.isPlaying ? 'Pause' : 'Play'}
                        >
                            {state.isPlaying
                                ? <Pause size={22} />
                                : <Play size={22} style={{ marginLeft: 2 }} fill="currentColor" />}
                        </button>
                        <button
                            type="button"
                            className="tunes-iconbtn"
                            onClick={controls.next}
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            data-testid="tunes-mini-next"
                            aria-label="Next"
                            disabled={state.kind !== 'track'}
                        >
                            <SkipForward size={20} />
                        </button>
                        <button
                            type="button"
                            className="tunes-iconbtn"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            aria-label="Repeat"
                            data-testid="tunes-mini-repeat"
                        >
                            <Repeat size={18} />
                        </button>
                    </div>
                    {!isLive && (
                        <div className="tunes-mini__scrub">
                            <span className="tunes-mini__time">{fmt(state.position)}</span>
                            <div
                                className="tunes-progress"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                role="slider"
                                aria-valuemin={0}
                                aria-valuemax={state.duration || 0}
                                aria-valuenow={state.position || 0}
                                onClick={(e) => {
                                    if (!state.duration) return;
                                    const rect = e.currentTarget.getBoundingClientRect();
                                    const ratio = (e.clientX - rect.left) / rect.width;
                                    controls.seek(state.duration * ratio);
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
                                data-testid="tunes-mini-scrub"
                            >
                                <div
                                    className="tunes-progress__bar"
                                    style={{ width: `${pct}%` }}
                                />
                            </div>
                            <span className="tunes-mini__time">{fmt(state.duration)}</span>
                        </div>
                    )}
                </div>

                <div className="tunes-mini__right">
                    <span
                        className="tunes-iconbtn"
                        role="button"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        aria-label={muted ? 'Unmute' : 'Mute'}
                        onClick={() => {
                            if (muted) {
                                controls.setVolume(lastVol || 0.85);
                                setMuted(false);
                            } else {
                                setLastVol(state.volume);
                                controls.setVolume(0);
                                setMuted(true);
                            }
                        }}
                    >
                        {muted || state.volume === 0
                            ? <VolumeX size={18} />
                            : <Volume2 size={18} />}
                    </span>
                    <input
                        type="range"
                        min={0}
                        max={1}
                        step={0.02}
                        value={state.volume}
                        onChange={(e) => {
                            const v = parseFloat(e.target.value);
                            controls.setVolume(v);
                            setMuted(v === 0);
                        }}
                        style={{ width: 100, accentColor: 'var(--vesper-blue)' }}
                        data-testid="tunes-mini-volume"
                        aria-label="Volume"
                    />
                    <button
                        type="button"
                        className="tunes-iconbtn"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => setExpanded(true)}
                        aria-label="Open Now Playing fullscreen"
                        data-testid="tunes-mini-expand"
                    >
                        <Maximize2 size={18} />
                    </button>
                </div>
            </div>

            {expanded && (
                <FullScreenPlayer onClose={() => setExpanded(false)} />
            )}
        </>
    );
}
