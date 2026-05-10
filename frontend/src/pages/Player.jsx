import React, { useEffect, useRef, useState, useMemo } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Hls from 'hls.js';
import {
    ArrowLeft,
    Loader2,
    Play,
    Pause,
    Volume2,
    VolumeX,
    Subtitles as SubtitlesIcon,
    Check,
    X as CloseIcon,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { API } from '@/lib/api';

/** Convert OpenSubtitles SRT body into WebVTT the <track> element can read. */
function srtToVtt(srt) {
    const body = (srt || '')
        .replace(/\r\n/g, '\n')
        .replace(/^\uFEFF/, '')
        .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2');
    return 'WEBVTT\n\n' + body;
}

async function fetchSubtitleAsVttBlob(url) {
    const res = await fetch(url, { mode: 'cors', cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    const isAlreadyVtt = /^\s*WEBVTT/i.test(text);
    const vtt = isAlreadyVtt ? text : srtToVtt(text);
    const blob = new Blob([vtt], { type: 'text/vtt' });
    return URL.createObjectURL(blob);
}

const langLabel = (code = '') => {
    const c = code.toLowerCase().slice(0, 3);
    const map = {
        eng: 'English',
        en: 'English',
        spa: 'Spanish',
        es: 'Spanish',
        fre: 'French',
        fra: 'French',
        fr: 'French',
        ger: 'German',
        deu: 'German',
        de: 'German',
        ita: 'Italian',
        it: 'Italian',
        por: 'Portuguese',
        pt: 'Portuguese',
        rus: 'Russian',
        ru: 'Russian',
        ara: 'Arabic',
        ar: 'Arabic',
        hin: 'Hindi',
        hi: 'Hindi',
        chi: 'Chinese',
        zho: 'Chinese',
        zh: 'Chinese',
        jpn: 'Japanese',
        ja: 'Japanese',
        kor: 'Korean',
        ko: 'Korean',
        tur: 'Turkish',
        tr: 'Turkish',
        dut: 'Dutch',
        nld: 'Dutch',
        nl: 'Dutch',
        pol: 'Polish',
        pl: 'Polish',
        swe: 'Swedish',
        sv: 'Swedish',
    };
    return map[c] || code.toUpperCase();
};

export default function Player() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const url = params.get('url');
    const title = params.get('title') || 'Now Playing';
    const type = params.get('type');
    const imdbId = params.get('imdbId');

    const videoRef = useRef(null);
    const hlsRef = useRef(null);
    const trackElRef = useRef(null);
    const trackBlobRef = useRef(null);
    const [playing, setPlaying] = useState(false);
    const [muted, setMuted] = useState(true);
    const [showUnmuteHint, setShowUnmuteHint] = useState(true);
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(true);

    // Subtitle picker state
    const [subs, setSubs] = useState([]);
    const [subsLoading, setSubsLoading] = useState(false);
    const [activeSubId, setActiveSubId] = useState(null); // null = off
    const [pickerOpen, setPickerOpen] = useState(false);

    const startPlayback = async () => {
        const v = videoRef.current;
        if (!v) return;
        v.muted = true;
        try {
            await v.play();
            setMuted(true);
            setShowUnmuteHint(true);
        } catch {
            setMuted(true);
            setShowUnmuteHint(true);
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
            if (trackBlobRef.current) {
                URL.revokeObjectURL(trackBlobRef.current);
                trackBlobRef.current = null;
            }
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [url]);

    // Auto-unmute on first user gesture
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

    // Fetch available subtitles whenever we have an imdbId
    useEffect(() => {
        if (!imdbId || !type) return;
        let cancel = false;
        setSubsLoading(true);
        (async () => {
            try {
                const r = await fetch(
                    `${API}/subtitles/${encodeURIComponent(
                        type
                    )}/${encodeURIComponent(imdbId)}`,
                    { cache: 'no-store' }
                );
                const data = await r.json();
                if (cancel) return;
                const list = Array.isArray(data?.subtitles) ? data.subtitles : [];
                // Dedupe by url, English first
                const seen = new Set();
                const cleaned = [];
                for (const s of list) {
                    if (!s?.url || seen.has(s.url)) continue;
                    seen.add(s.url);
                    cleaned.push({
                        id: s.id || s.url,
                        url: s.url,
                        lang: s.lang || s.language || 'unknown',
                    });
                }
                cleaned.sort((a, b) => {
                    const ae = /^en/i.test(a.lang) ? 0 : 1;
                    const be = /^en/i.test(b.lang) ? 0 : 1;
                    return ae - be;
                });
                setSubs(cleaned);
            } catch {
                if (!cancel) setSubs([]);
            } finally {
                if (!cancel) setSubsLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, imdbId]);

    // Group subtitle entries by language for the picker UI
    const subsByLang = useMemo(() => {
        const groups = new Map();
        for (const s of subs) {
            const k = (s.lang || 'unknown').toLowerCase();
            if (!groups.has(k))
                groups.set(k, { lang: s.lang, label: langLabel(s.lang), items: [] });
            groups.get(k).items.push(s);
        }
        return Array.from(groups.values());
    }, [subs]);

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

    const removeActiveTrack = () => {
        const v = videoRef.current;
        if (!v) return;
        if (trackElRef.current) {
            try {
                v.removeChild(trackElRef.current);
            } catch {
                /* ignore */
            }
            trackElRef.current = null;
        }
        if (trackBlobRef.current) {
            URL.revokeObjectURL(trackBlobRef.current);
            trackBlobRef.current = null;
        }
        setActiveSubId(null);
    };

    const applySubtitle = async (sub) => {
        const v = videoRef.current;
        if (!v) return;
        try {
            removeActiveTrack();
            const blobUrl = await fetchSubtitleAsVttBlob(sub.url);
            const trackEl = document.createElement('track');
            trackEl.kind = 'subtitles';
            trackEl.label = langLabel(sub.lang);
            trackEl.srclang = (sub.lang || 'en').slice(0, 2).toLowerCase();
            trackEl.src = blobUrl;
            trackEl.default = true;
            v.appendChild(trackEl);
            // Force-show the new track
            const onLoad = () => {
                for (let i = 0; i < v.textTracks.length; i += 1) {
                    v.textTracks[i].mode =
                        v.textTracks[i].label === trackEl.label ? 'showing' : 'disabled';
                }
            };
            trackEl.addEventListener('load', onLoad, { once: true });
            // Some browsers don't fire 'load' — force it on next tick too
            setTimeout(onLoad, 400);

            trackElRef.current = trackEl;
            trackBlobRef.current = blobUrl;
            setActiveSubId(sub.id);
            setPickerOpen(false);
        } catch (e) {
            setError(`Couldn't load that subtitle (${e?.message || 'fetch failed'}).`);
            setTimeout(() => setError(null), 4000);
        }
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
                crossOrigin="anonymous"
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
                    className="absolute inset-0 z-20 flex flex-col items-center justify-center gap-4"
                    style={{
                        background:
                            'radial-gradient(ellipse at center, rgba(6,8,15,0.55) 0%, rgba(6,8,15,0.85) 100%)',
                        cursor: 'pointer',
                        border: 'none',
                        color: 'var(--vesper-text)',
                    }}
                >
                    <div
                        className="flex items-center justify-center rounded-full vesper-pulse"
                        style={{
                            width: 132,
                            height: 132,
                            background:
                                'radial-gradient(circle, rgba(93,200,255,0.95) 0%, rgba(93,200,255,0.7) 70%, rgba(93,200,255,0) 100%)',
                            color: '#06080f',
                            boxShadow:
                                '0 0 80px rgba(93,200,255,0.7), 0 0 120px rgba(93,200,255,0.35)',
                        }}
                    >
                        <VolumeX size={56} strokeWidth={2.2} />
                    </div>
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(40px, 5vw, 64px)',
                            letterSpacing: '-0.02em',
                            textShadow: '0 4px 24px rgba(0,0,0,0.6)',
                        }}
                    >
                        Click to unmute
                    </div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 14,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        Or press any key on your remote
                    </div>
                </button>
            )}

            {error && (
                <div className="absolute inset-x-0 bottom-32 flex justify-center z-30">
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

            {/* Subtitle picker overlay */}
            {pickerOpen && (
                <div
                    data-testid="subtitle-picker"
                    className="absolute inset-0 z-40 flex items-center justify-end"
                    style={{
                        background:
                            'linear-gradient(90deg, rgba(0,0,0,0) 0%, rgba(0,0,0,0.55) 50%, rgba(0,0,0,0.85) 100%)',
                    }}
                    onClick={() => setPickerOpen(false)}
                >
                    <div
                        className="vesper-glass"
                        style={{
                            width: 460,
                            maxHeight: '82vh',
                            margin: 32,
                            borderRadius: 20,
                            display: 'flex',
                            flexDirection: 'column',
                            background: 'rgba(10,14,24,0.92)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            backdropFilter: 'blur(24px)',
                        }}
                        onClick={(e) => e.stopPropagation()}
                    >
                        <header
                            className="flex items-center justify-between"
                            style={{ padding: '20px 24px' }}
                        >
                            <div>
                                <div className="vesper-eyebrow">Subtitles</div>
                                <div
                                    className="vesper-display mt-1"
                                    style={{ fontSize: 22, letterSpacing: '-0.02em' }}
                                >
                                    Choose a track
                                </div>
                            </div>
                            <button
                                data-testid="subtitle-picker-close"
                                data-focusable="true"
                                data-focus-style="quiet"
                                tabIndex={0}
                                onClick={() => setPickerOpen(false)}
                                aria-label="Close"
                                className="flex items-center justify-center rounded-full"
                                style={{
                                    width: 40,
                                    height: 40,
                                    background: 'rgba(255,255,255,0.06)',
                                    border: '1px solid rgba(255,255,255,0.1)',
                                    color: 'var(--vesper-text-2)',
                                }}
                            >
                                <CloseIcon size={16} />
                            </button>
                        </header>

                        <div
                            className="flex-1 overflow-y-auto"
                            style={{ padding: '0 16px 16px' }}
                        >
                            <SubRow
                                testId="subtitle-off"
                                active={activeSubId === null}
                                title="Off"
                                detail="No captions"
                                onClick={removeActiveTrack}
                            />

                            {subsLoading && subs.length === 0 ? (
                                <div
                                    className="flex items-center gap-3 px-3 py-4"
                                    style={{
                                        color: 'var(--vesper-text-2)',
                                        fontSize: 14,
                                    }}
                                >
                                    <Loader2 className="vesper-spin" size={16} />
                                    Loading subtitles…
                                </div>
                            ) : subs.length === 0 ? (
                                <div
                                    className="px-3 py-5"
                                    style={{
                                        color: 'var(--vesper-text-3)',
                                        fontSize: 14,
                                    }}
                                >
                                    No subtitles found. Make sure the
                                    OpenSubtitles addon is installed on Sources.
                                </div>
                            ) : (
                                subsByLang.map((g) => (
                                    <div key={g.lang} className="mt-3">
                                        <div
                                            className="vesper-mono px-3 mb-1"
                                            style={{
                                                fontSize: 11,
                                                letterSpacing: '0.22em',
                                                textTransform: 'uppercase',
                                                color: 'var(--vesper-text-3)',
                                            }}
                                        >
                                            {g.label} · {g.items.length}
                                        </div>
                                        {g.items.slice(0, 6).map((s, i) => (
                                            <SubRow
                                                key={s.id}
                                                testId={`subtitle-${g.lang}-${i}`}
                                                active={activeSubId === s.id}
                                                title={`${g.label} · option ${i + 1}`}
                                                detail={s.url
                                                    .split('/')
                                                    .slice(-1)[0]
                                                    .slice(0, 60)}
                                                onClick={() => applySubtitle(s)}
                                            />
                                        ))}
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                </div>
            )}

            {/* Bottom right action cluster */}
            <div className="absolute bottom-8 right-8 z-10 flex items-center gap-3">
                {imdbId && (
                    <button
                        data-testid="player-subtitles"
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onClick={() => setPickerOpen((s) => !s)}
                        aria-label="Subtitles"
                        className="flex items-center justify-center rounded-full"
                        style={{
                            width: 48,
                            height: 48,
                            background: 'rgba(17,24,39,0.85)',
                            color:
                                activeSubId !== null
                                    ? 'var(--vesper-blue)'
                                    : 'var(--vesper-text)',
                            border: '1px solid rgba(255,255,255,0.15)',
                            position: 'relative',
                        }}
                    >
                        <SubtitlesIcon size={18} />
                        {activeSubId !== null && (
                            <span
                                style={{
                                    position: 'absolute',
                                    top: 6,
                                    right: 6,
                                    width: 8,
                                    height: 8,
                                    borderRadius: '50%',
                                    background: 'var(--vesper-blue)',
                                    boxShadow:
                                        '0 0 10px rgba(93,200,255,0.9)',
                                }}
                            />
                        )}
                    </button>
                )}
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

const SubRow = ({ testId, active, title, detail, onClick }) => (
    <button
        data-testid={testId}
        data-focusable="true"
        data-focus-style="pill"
        tabIndex={0}
        onClick={onClick}
        className="w-full text-left flex items-center gap-3 px-3 rounded-xl"
        style={{
            height: 56,
            background: active ? 'rgba(93,200,255,0.12)' : 'rgba(255,255,255,0.02)',
            border: active
                ? '1px solid rgba(93,200,255,0.45)'
                : '1px solid rgba(255,255,255,0.04)',
            color: active ? 'var(--vesper-text)' : 'var(--vesper-text-2)',
            marginTop: 6,
        }}
    >
        <span
            className="flex items-center justify-center rounded-full"
            style={{
                width: 28,
                height: 28,
                background: active ? 'var(--vesper-blue)' : 'rgba(255,255,255,0.08)',
                color: active ? '#06080f' : 'var(--vesper-text-2)',
                flexShrink: 0,
            }}
        >
            {active ? <Check size={14} /> : null}
        </span>
        <span className="min-w-0 flex-1">
            <span
                className="block font-sans font-medium"
                style={{ fontSize: 15, lineHeight: 1.2 }}
            >
                {title}
            </span>
            <span
                className="block vesper-mono truncate"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.06em',
                    color: 'var(--vesper-text-3)',
                    marginTop: 2,
                }}
            >
                {detail}
            </span>
        </span>
    </button>
);

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
