import React, { useEffect } from 'react';
import { X, Play, Pause, SkipBack, SkipForward, Maximize, Minimize } from 'lucide-react';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

function fmt(secs) {
    if (!Number.isFinite(secs)) return '--:--';
    const s = Math.max(0, Math.floor(secs));
    const m = Math.floor(s / 60);
    const r = String(s % 60).padStart(2, '0');
    return `${m}:${r}`;
}

/** Helpers: cross-browser fullscreen API wrappers (Safari + Chrome + Firefox). */
function requestFs(el) {
    const r = el.requestFullscreen
        || el.webkitRequestFullscreen
        || el.mozRequestFullScreen
        || el.msRequestFullscreen;
    if (r) {
        try { return r.call(el); } catch { /* ignore */ }
    }
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
    const [isFs, setIsFs] = React.useState(!!getFsEl());

    // v2.8.45 — Auto-enter true OS-level fullscreen when the
    // Now Playing screen mounts (browser Fullscreen API).  Removes
    // browser chrome (URL bar / status bar) so the experience is
    // genuinely cinematic.  Bound to a "fs" button in the corner so
    // the user can also toggle it manually with the remote / mouse.
    // Some boxes (Android WebView, certain Chromium builds) ignore
    // requestFullscreen unless triggered by a user gesture — that's
    // why the corner button exists as the manual fallback.
    useEffect(() => {
        const root = document.querySelector('.tunes-fullplayer');
        if (root) {
            // requestFullscreen may need a user gesture; we attempt
            // it once on mount, swallow errors.
            requestFs(root);
        }
        const onChange = () => setIsFs(!!getFsEl());
        document.addEventListener('fullscreenchange', onChange);
        document.addEventListener('webkitfullscreenchange', onChange);
        return () => {
            document.removeEventListener('fullscreenchange', onChange);
            document.removeEventListener('webkitfullscreenchange', onChange);
            // Always exit OS-fullscreen when the player closes so
            // the rest of the app isn't stuck in fullscreen.
            exitFs();
        };
    }, []);

    const toggleFs = () => {
        if (getFsEl()) {
            exitFs();
        } else {
            const root = document.querySelector('.tunes-fullplayer');
            if (root) requestFs(root);
        }
    };

    if (!state.current) return null;
    const t = state.current;
    const isLive = state.kind === 'radio';
    const pct = isLive || !state.duration ? 0 : Math.min(100, (state.position / state.duration) * 100);

    return (
        <div className="tunes-fullplayer" data-testid="tunes-fullplayer">
            <div className="tunes-fullplayer__close" style={{ display: 'flex', gap: 8 }}>
                <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                    type="button"
                    className="tunes-iconbtn"
                    onClick={toggleFs}
                    data-testid="tunes-fullplayer-fs"
                    aria-label={isFs ? 'Exit fullscreen' : 'Enter fullscreen'}
                    title={isFs ? 'Exit fullscreen' : 'Enter fullscreen'}
                >
                    {isFs ? <Minimize size={22} /> : <Maximize size={22} />}
                </button>
                <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                    type="button"
                    className="tunes-iconbtn"
                    onClick={onClose}
                    data-testid="tunes-fullplayer-close"
                    aria-label="Close player"
                >
                    <X size={22} />
                </button>
            </div>

            <div className="tunes-fullplayer__body">
                <div className="tunes-fullplayer__art" style={{
                    animation: state.isPlaying && state.kind === 'track' ? 'tunes-spin 24s linear infinite' : 'none',
                }}>
                    {t.artwork ? (
                        <img src={t.artwork} alt={t.title} />
                    ) : (
                        <div style={{ width: '100%', height: '100%' }} />
                    )}
                </div>
                <div className="tunes-fullplayer__meta">
                    <p className="tunes-fullplayer__eyebrow">
                        {state.kind === 'radio' ? 'Now broadcasting' :
                         state.kind === 'episode' ? 'Podcast' :
                         'Now playing'}
                    </p>
                    <h1 className="tunes-fullplayer__title">{t.title}</h1>
                    <p className="tunes-fullplayer__artist">
                        {state.kind === 'track' ? t.artist?.name :
                         state.kind === 'radio' ? `LIVE${t.subtitle ? ` · ${t.subtitle}` : ''}` :
                         t.subtitle}
                    </p>

                    {!isLive && (
                        <div className="tunes-fullplayer__progress">
                            <div
                                className="tunes-fullplayer__progress-bar"
                                onClick={(e) => {
                                    if (!state.duration) return;
                                    const r = e.currentTarget.getBoundingClientRect();
                                    controls.seek(state.duration * ((e.clientX - r.left) / r.width));
                                }}
                            >
                                <div style={{ width: `${pct}%` }} />
                            </div>
                            <div className="tunes-fullplayer__times">
                                <span>{fmt(state.position)}</span>
                                <span>{fmt(state.duration)}</span>
                            </div>
                        </div>
                    )}

                    <div className="tunes-fullplayer__controls">
                        <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            className="tunes-iconbtn"
                            onClick={controls.previous}
                            disabled={state.kind !== 'track'}
                            style={state.kind !== 'track' ? { opacity: 0.4, cursor: 'not-allowed' } : undefined}
                            aria-label="Previous"
                        >
                            <SkipBack size={28} />
                        </button>
                        <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            className="tunes-iconbtn tunes-iconbtn--play"
                            onClick={controls.toggle}
                            data-testid="tunes-fullplayer-toggle"
                            aria-label={state.isPlaying ? 'Pause' : 'Play'}
                        >
                            {state.isPlaying ? <Pause size={30} /> : <Play size={30} style={{ marginLeft: 4 }} />}
                        </button>
                        <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            className="tunes-iconbtn"
                            onClick={controls.next}
                            disabled={state.kind !== 'track'}
                            style={state.kind !== 'track' ? { opacity: 0.4, cursor: 'not-allowed' } : undefined}
                            aria-label="Next"
                        >
                            <SkipForward size={28} />
                        </button>
                    </div>
                </div>
            </div>
            <div className="tunes-fullplayer__footer" />

            <style>{`
                @keyframes tunes-spin { to { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
}
