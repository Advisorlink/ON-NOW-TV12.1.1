import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Hls from 'hls.js';
import {
    ArrowLeft,
    Loader2,
    Play,
    Pause,
    Volume2,
    VolumeX,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';

export default function Player() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const url = params.get('url');
    const title = params.get('title') || 'Now Playing';

    const videoRef = useRef(null);
    const hlsRef = useRef(null);
    const [playing, setPlaying] = useState(false);
    const [muted, setMuted] = useState(false);
    const [showUnmuteHint, setShowUnmuteHint] = useState(false);
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(true);

    /**
     * Attempt to start playback.  Browsers reject `play()` calls
     * after a route change unless the gesture is "fresh enough", in
     * which case we fall back to **muted autoplay** (always allowed)
     * and surface a "Tap to unmute" overlay.
     */
    const startPlayback = async () => {
        const v = videoRef.current;
        if (!v) return;
        try {
            v.muted = false;
            await v.play();
            setMuted(false);
            setShowUnmuteHint(false);
        } catch {
            try {
                v.muted = true;
                await v.play();
                setMuted(true);
                setShowUnmuteHint(true);
            } catch {
                /* user can press the on-screen Play button */
            }
        }
    };

    useEffect(() => {
        const v = videoRef.current;
        if (!v || !url) return;

        setError(null);
        setLoading(true);

        const isHls = url.toLowerCase().includes('.m3u8');
        const cleanup = [];

        if (isHls && Hls.isSupported()) {
            const hls = new Hls({ enableWorker: true });
            hls.loadSource(url);
            hls.attachMedia(v);
            hls.on(Hls.Events.MANIFEST_PARSED, () => {
                setLoading(false);
                startPlayback();
            });
            hls.on(Hls.Events.ERROR, (_, data) => {
                if (data.fatal) {
                    setError(`Playback error: ${data.details || data.type}`);
                    hls.destroy();
                }
            });
            hlsRef.current = hls;
        } else {
            v.src = url;
            const onLoaded = () => {
                setLoading(false);
                startPlayback();
            };
            const onErr = () => {
                setError('Could not load this stream.');
                setLoading(false);
            };
            v.addEventListener('loadedmetadata', onLoaded);
            v.addEventListener('error', onErr);
            cleanup.push(() => {
                v.removeEventListener('loadedmetadata', onLoaded);
                v.removeEventListener('error', onErr);
            });
        }

        const onPlay = () => setPlaying(true);
        const onPause = () => setPlaying(false);
        const onVolume = () => setMuted(v.muted);
        v.addEventListener('play', onPlay);
        v.addEventListener('pause', onPause);
        v.addEventListener('volumechange', onVolume);

        return () => {
            v.removeEventListener('play', onPlay);
            v.removeEventListener('pause', onPause);
            v.removeEventListener('volumechange', onVolume);
            cleanup.forEach((f) => f());
            if (hlsRef.current) {
                hlsRef.current.destroy();
                hlsRef.current = null;
            }
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [url]);

    /**
     * Auto-unmute on the first key/click after the video starts —
     * any in-app interaction satisfies the browser's gesture
     * requirement.
     */
    useEffect(() => {
        if (!showUnmuteHint) return;
        const unmute = () => {
            const v = videoRef.current;
            if (!v) return;
            v.muted = false;
            setMuted(false);
            setShowUnmuteHint(false);
        };
        const handler = (e) => {
            // Only react to actual user input (key, click, tap)
            if (e.type === 'keydown' && e.repeat) return;
            unmute();
        };
        window.addEventListener('keydown', handler, { once: true });
        window.addEventListener('click', handler, { once: true });
        return () => {
            window.removeEventListener('keydown', handler);
            window.removeEventListener('click', handler);
        };
    }, [showUnmuteHint]);

    const togglePlay = () => {
        const v = videoRef.current;
        if (!v) return;
        if (v.paused) v.play().catch(() => {});
        else v.pause();
    };

    const toggleMute = () => {
        const v = videoRef.current;
        if (!v) return;
        v.muted = !v.muted;
        setMuted(v.muted);
        if (!v.muted) setShowUnmuteHint(false);
    };

    if (!url) {
        return (
            <CenterMsg>
                <div style={{ color: '#ffb5b5' }}>No stream URL provided.</div>
                <button
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    className="mt-6 h-12 px-5 rounded-full"
                    style={{
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.12)',
                    }}
                >
                    Back
                </button>
            </CenterMsg>
        );
    }

    return (
        <div
            data-testid="player-page"
            className="fixed inset-0 z-50"
            style={{ background: '#000' }}
        >
            <video
                ref={videoRef}
                data-testid="player-video"
                controls
                playsInline
                className="absolute inset-0 w-full h-full object-contain"
            />

            {/* Top bar */}
            <div
                className="absolute top-0 left-0 right-0 z-10 flex items-center gap-4 p-6"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0) 100%)',
                }}
            >
                <button
                    data-testid="player-back"
                    data-focusable="true"
                    data-focus-style="quiet"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    aria-label="Back"
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 48,
                        height: 48,
                        background: 'rgba(17,24,39,0.7)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.1)',
                    }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div className="min-w-0">
                    <div
                        className="font-sans font-medium truncate"
                        style={{ fontSize: 19 }}
                    >
                        {title}
                    </div>
                    <div
                        className="vesper-eyebrow truncate"
                        style={{ fontSize: 10, maxWidth: '60vw' }}
                    >
                        {url}
                    </div>
                </div>
            </div>

            {loading && (
                <div
                    className="absolute inset-0 flex items-center justify-center pointer-events-none"
                    style={{ color: 'var(--vesper-text-2)' }}
                >
                    <Loader2 className="vesper-spin" size={36} />
                </div>
            )}

            {showUnmuteHint && !loading && (
                <button
                    data-testid="unmute-hint"
                    onClick={toggleMute}
                    className="absolute z-20 flex items-center gap-3 px-6 h-14 rounded-full vesper-pulse"
                    style={{
                        top: 32,
                        left: '50%',
                        transform: 'translateX(-50%)',
                        background: 'rgba(93,200,255,0.95)',
                        color: '#06080f',
                        fontSize: 17,
                        fontWeight: 600,
                        boxShadow: '0 12px 40px rgba(93,200,255,0.55)',
                        cursor: 'pointer',
                    }}
                >
                    <VolumeX size={18} strokeWidth={2.5} />
                    Tap or press any key to unmute
                </button>
            )}

            {error && (
                <div className="absolute inset-x-0 bottom-32 flex justify-center">
                    <div
                        className="px-6 py-4 rounded-xl"
                        style={{
                            background: 'rgba(255,80,80,0.12)',
                            border: '1px solid rgba(255,80,80,0.45)',
                            color: '#ffb5b5',
                            fontSize: 16,
                        }}
                    >
                        {error}
                    </div>
                </div>
            )}

            {/* Bottom right action cluster */}
            <div className="absolute bottom-8 right-8 z-10 flex items-center gap-3">
                <button
                    data-testid="player-mute"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={toggleMute}
                    aria-label={muted ? 'Unmute' : 'Mute'}
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 48,
                        height: 48,
                        background: 'rgba(17,24,39,0.85)',
                        color: muted ? 'var(--vesper-blue)' : 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.15)',
                    }}
                >
                    {muted ? <VolumeX size={18} /> : <Volume2 size={18} />}
                </button>
                <button
                    data-testid="player-toggle"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={togglePlay}
                    className="flex items-center gap-2 h-12 px-6 rounded-full"
                    style={{
                        background: 'rgba(17,24,39,0.85)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.15)',
                    }}
                >
                    {playing ? <Pause size={18} /> : <Play size={18} />}
                    {playing ? 'Pause' : 'Play'}
                </button>
            </div>
        </div>
    );
}

const CenterMsg = ({ children }) => (
    <div
        className="w-screen h-screen flex flex-col items-center justify-center"
        style={{
            color: 'var(--vesper-text-2)',
            fontSize: 18,
            gap: 12,
            background: 'var(--vesper-bg-0)',
        }}
    >
        {children}
    </div>
);
