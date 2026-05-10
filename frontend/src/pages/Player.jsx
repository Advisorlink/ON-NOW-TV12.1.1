import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Hls from 'hls.js';
import { ArrowLeft, Loader2, Play, Pause } from 'lucide-react';
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
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(true);

    useEffect(() => {
        const v = videoRef.current;
        if (!v || !url) return;

        setError(null);
        setLoading(true);

        const isHls = url.includes('.m3u8');
        if (isHls && Hls.isSupported()) {
            const hls = new Hls({ enableWorker: true, lowLatencyMode: false });
            hls.loadSource(url);
            hls.attachMedia(v);
            hls.on(Hls.Events.MANIFEST_PARSED, () => {
                setLoading(false);
                v.play().catch(() => {});
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
            v.addEventListener('loadedmetadata', () => {
                setLoading(false);
                v.play().catch(() => {});
            });
            v.addEventListener('error', () => {
                setError('Could not load this stream.');
                setLoading(false);
            });
        }

        const onPlay = () => setPlaying(true);
        const onPause = () => setPlaying(false);
        v.addEventListener('play', onPlay);
        v.addEventListener('pause', onPause);

        return () => {
            v.removeEventListener('play', onPlay);
            v.removeEventListener('pause', onPause);
            if (hlsRef.current) {
                hlsRef.current.destroy();
                hlsRef.current = null;
            }
        };
    }, [url]);

    const togglePlay = () => {
        const v = videoRef.current;
        if (!v) return;
        if (v.paused) v.play().catch(() => {});
        else v.pause();
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

            <button
                data-testid="player-toggle"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={togglePlay}
                className="absolute bottom-8 right-8 z-10 flex items-center gap-2 h-12 px-6 rounded-full"
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
    );
}

const CenterMsg = ({ children }) => (
    <div
        className="w-screen h-[100dvh] min-h-screen flex flex-col items-center justify-center"
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
