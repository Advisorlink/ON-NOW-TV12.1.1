import React, { useState } from 'react';
import { Play, Pause, SkipBack, SkipForward, Volume2, Maximize2 } from 'lucide-react';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import { FullScreenPlayer } from '../../pages/music/FullScreenPlayer';

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

    if (!state.current) return null;
    const t = state.current;
    const isLive = state.kind === 'radio';
    const pct = isLive || !state.duration
        ? 0
        : Math.min(100, (state.position / state.duration) * 100);

    return (
        <>
            <div className="tunes-mini" data-testid="tunes-mini-player">
                <button
                    type="button"
                    className="tunes-mini__now"
                    onClick={() => setExpanded(true)}
                    data-testid="tunes-mini-now-btn"
                    data-focusable="true"
                    data-focus-style="tile"
                    tabIndex={0}
                    style={{ background: 'none', border: 'none', color: 'inherit', textAlign: 'left', padding: 0 }}
                >
                    {t.artwork ? (
                        <img src={t.artwork} alt="" className="tunes-mini__art" />
                    ) : (
                        <div className="tunes-mini__art" />
                    )}
                    <div className="tunes-mini__text">
                        <p className="tunes-mini__title">
                            {t.title}
                            {/* v2.8.50 — Diagnostic pill: tells us EXACTLY
                                which resolver tier served the current
                                track.  Helps debug "stuck on preview"
                                without needing logs.
                                  newpipe  → native NewPipeExtractor bridge  (full)
                                  youtube  → backend yt-dlp + cookies        (full)
                                  jiosaavn → JioSaavn fallback                (full)
                                  audius   → Audius decentralized fallback   (full)
                                  preview  → Deezer 30-second preview         (NOT full) */}
                            {state.kind === 'track' && !t._resolving && t._streamSource && (
                                <span data-testid="track-source-badge" style={{
                                    marginLeft: 10,
                                    padding: '3px 8px',
                                    background: t._isFullTrack
                                        ? 'linear-gradient(135deg, #10b981, #34d399)'
                                        : 'rgba(255,196,107,0.20)',
                                    color: t._isFullTrack ? '#fff' : '#FFC46B',
                                    borderRadius: 999,
                                    fontSize: 10,
                                    fontWeight: 700,
                                    letterSpacing: '0.06em',
                                    textTransform: 'uppercase',
                                    verticalAlign: 'middle',
                                    border: t._isFullTrack ? 'none' : '1px solid rgba(255,196,107,0.45)',
                                }}>
                                    {t._isFullTrack ? `Full · ${t._streamSource}` : `30 s · ${t._streamSource}`}
                                </span>
                            )}
                            {t._resolving && (
                                <span style={{
                                    marginLeft: 10,
                                    padding: '3px 8px',
                                    background: 'rgba(255,45,127,0.18)',
                                    color: 'var(--tunes-accent-3)',
                                    borderRadius: 999,
                                    fontSize: 10,
                                    fontWeight: 600,
                                    letterSpacing: '0.05em',
                                    textTransform: 'uppercase',
                                    verticalAlign: 'middle',
                                }}>Loading…</span>
                            )}
                        </p>
                        <p className="tunes-mini__subtitle">
                            {state.kind === 'track'   ? (t.artist?.name || '') :
                             state.kind === 'radio'   ? `LIVE · ${t.subtitle || ''}` :
                             state.kind === 'episode' ? t.subtitle :
                             ''}
                        </p>
                    </div>
                </button>

                <div className="tunes-mini__controls">
                    <div className="tunes-mini__buttons">
                        <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            className="tunes-iconbtn"
                            onClick={controls.previous}
                            data-testid="tunes-mini-prev"
                            aria-label="Previous"
                            disabled={state.kind !== 'track'}
                            style={state.kind !== 'track' ? { opacity: 0.4, cursor: 'not-allowed' } : undefined}
                        >
                            <SkipBack size={20} />
                        </button>
                        <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            className="tunes-iconbtn tunes-iconbtn--play"
                            onClick={controls.toggle}
                            data-testid="tunes-mini-toggle"
                            aria-label={state.isPlaying ? 'Pause' : 'Play'}
                        >
                            {state.isPlaying ? <Pause size={22} /> : <Play size={22} style={{ marginLeft: 2 }} />}
                        </button>
                        <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            className="tunes-iconbtn"
                            onClick={controls.next}
                            data-testid="tunes-mini-next"
                            aria-label="Next"
                            disabled={state.kind !== 'track'}
                            style={state.kind !== 'track' ? { opacity: 0.4, cursor: 'not-allowed' } : undefined}
                        >
                            <SkipForward size={20} />
                        </button>
                    </div>
                    {!isLive && (
                        <div className="tunes-mini__scrubber">
                            <span className="tunes-progress__time">{fmt(state.position)}</span>
                            <div
                                className="tunes-progress"
                                onClick={(e) => {
                                    if (!state.duration) return;
                                    const rect = e.currentTarget.getBoundingClientRect();
                                    const ratio = (e.clientX - rect.left) / rect.width;
                                    controls.seek(state.duration * ratio);
                                }}
                                data-testid="tunes-mini-scrub"
                            >
                                <div
                                    className="tunes-progress__bar"
                                    style={{ width: `${pct}%` }}
                                />
                            </div>
                            <span className="tunes-progress__time">{fmt(state.duration)}</span>
                        </div>
                    )}
                </div>

                <div className="tunes-mini__right">
                    <Volume2 size={16} color="var(--tunes-text-faint)" />
                    <input
                        type="range"
                        min={0} max={1} step={0.02}
                        value={state.volume}
                        onChange={(e) => controls.setVolume(parseFloat(e.target.value))}
                        style={{ width: 100, accentColor: 'var(--tunes-accent)' }}
                        data-testid="tunes-mini-volume"
                    />
                    <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                        type="button"
                        onClick={() => setExpanded(true)}
                        aria-label="Open Now Playing fullscreen"
                        data-testid="tunes-mini-expand"
                        style={{
                            display: 'inline-flex', alignItems: 'center', gap: 8,
                            padding: '10px 18px',
                            background: 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-accent-2))',
                            color: '#fff',
                            border: 'none',
                            borderRadius: 999,
                            cursor: 'pointer',
                            fontSize: 13,
                            fontWeight: 600,
                            boxShadow: '0 6px 18px rgba(255,45,127,0.35)',
                            transition: 'transform 0.15s, box-shadow 0.15s',
                        }}
                        onMouseEnter={(e) => { e.currentTarget.style.transform = 'scale(1.05)'; }}
                        onMouseLeave={(e) => { e.currentTarget.style.transform = 'scale(1)'; }}
                    >
                        <Maximize2 size={16} />
                        Full screen
                    </button>
                </div>
            </div>

            {expanded && (
                <FullScreenPlayer onClose={() => setExpanded(false)} />
            )}
        </>
    );
}
