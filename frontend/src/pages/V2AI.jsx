import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { API } from '@/lib/api';
import useSpatialFocus from '@/hooks/useSpatialFocus';

/**
 * /v2ai — the V2 AI voice assistant, living INSIDE Vesper.
 *
 * v2.13.1 — Pixel-parity rebuild.  The page now replicates the
 * launcher's native VoiceAssistantActivity exactly:
 *   • Same admin wallpaper behind it (fetched live from the launcher
 *     backend config, so the portal's V2 AI tab drives BOTH screens)
 *   • Same 48-bar scrolling waveform (levels shift left @55 ms, bar
 *     height 10%+amp*85%, cyan→blue→pink gradient) driven by a REAL
 *     WebAudio analyser while you talk — plus the same idle shimmer
 *     (2.4 s sine sweep) when quiet
 *   • Same standby column: eyebrow → heading → waveform → (optional
 *     HOLD OK badge, admin-toggled) → status line
 *   • Same texts: Ready / Listening… / Speaking… / Thinking…
 *   • Hold OK ANYWHERE on the page to talk (activity-level key
 *     handling, not tied to a button) — release to submit
 *   • ?listen=1 → starts listening immediately (rail push-and-hold)
 *
 * Play flow: play intents navigate /v2ai-play → /resolve →
 * /title?autoplay=1&src=v2ai which uses the SAME curated stream
 * cascade as normal Autoplay (no junk-stream fallback).
 */

const MAX_RECORD_MS = 10_000;
const MIN_RECORD_MS = 400;
const BARS = 48;

function pickRecorderMime() {
    if (typeof MediaRecorder === 'undefined') return null;
    const candidates = [
        'audio/webm;codecs=opus',
        'audio/webm',
        'audio/ogg;codecs=opus',
        'audio/mp4',
    ];
    for (const m of candidates) {
        try { if (MediaRecorder.isTypeSupported(m)) return m; } catch { /* */ }
    }
    return '';
}
function mimeToExt(mime) {
    if (!mime) return 'webm';
    if (mime.startsWith('audio/webm')) return 'webm';
    if (mime.startsWith('audio/ogg')) return 'ogg';
    if (mime.startsWith('audio/mp4')) return 'm4a';
    return 'webm';
}
function deviceId() {
    try {
        let id = window.localStorage.getItem('vesper-v2ai-device-id');
        if (!id) {
            id = 'web_' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
            window.localStorage.setItem('vesper-v2ai-device-id', id);
        }
        return id;
    } catch { return 'web_anon'; }
}

const OK_KEYS = ['Enter', ' ', 'Spacebar'];

/* ─────────── waveform — exact port of VoiceWaveform BARS ─────────── */

function VoiceWaveformCanvas({ listening, analyserRef }) {
    const canvasRef = useRef(null);
    const levelsRef = useRef(new Float32Array(BARS));
    const lastSampleRef = useRef(0);
    const listeningRef = useRef(listening);
    listeningRef.current = listening;

    useEffect(() => {
        if (!listening) levelsRef.current.fill(0);
    }, [listening]);

    useEffect(() => {
        const canvas = canvasRef.current;
        if (!canvas) return undefined;
        const ctx = canvas.getContext('2d');
        let raf = 0;
        const buf = new Uint8Array(1024);
        // Waveform follows the active Vesper theme (user request:
        // theme colours must apply to V2 AI, not hardcoded purple).
        const cs = getComputedStyle(document.documentElement);
        const accent = (cs.getPropertyValue('--vesper-blue') || '').trim() || '#7C5CFF';
        const bright = (cs.getPropertyValue('--vesper-blue-bright') || '').trim() || '#A78BFF';

        const draw = (now) => {
            raf = requestAnimationFrame(draw);
            const dpr = window.devicePixelRatio || 1;
            const w = canvas.clientWidth;
            const h = canvas.clientHeight;
            if (!w || !h) return;
            if (canvas.width !== w * dpr || canvas.height !== h * dpr) {
                canvas.width = w * dpr;
                canvas.height = h * dpr;
            }
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.clearRect(0, 0, w, h);

            // Sample the mic every 55 ms (launcher polls maxAmplitude
            // at the same interval) and scroll the buffer left.
            if (listeningRef.current && now - lastSampleRef.current >= 55) {
                lastSampleRef.current = now;
                let norm = 0;
                const analyser = analyserRef.current;
                if (analyser) {
                    analyser.getByteTimeDomainData(buf);
                    let peak = 0;
                    for (let i = 0; i < buf.length; i++) {
                        const d = Math.abs(buf[i] - 128);
                        if (d > peak) peak = d;
                    }
                    norm = peak / 128;
                }
                const levels = levelsRef.current;
                for (let i = 0; i < BARS - 1; i++) levels[i] = levels[i + 1];
                levels[BARS - 1] = Math.min(norm * 1.6, 1);
            }

            // idlePhase loops 0→2π every 2.4 s — same as the launcher.
            const idlePhase = ((now % 2400) / 2400) * Math.PI * 2;

            const grad = ctx.createLinearGradient(0, 0, w, 0);
            grad.addColorStop(0, accent);
            grad.addColorStop(0.5, bright);
            grad.addColorStop(1, accent);
            ctx.fillStyle = grad;

            const barW = w / (BARS * 1.5);
            const gap = barW * 0.5;
            const levels = levelsRef.current;
            for (let i = 0; i < BARS; i++) {
                const amp = listeningRef.current
                    ? levels[i]
                    : 0.10 + 0.05 * Math.sin(idlePhase + (i / BARS) * Math.PI * 2);
                const bh = Math.min(h * (0.10 + amp * 0.85), h);
                const left = i * (barW + gap);
                const top = (h - bh) / 2;
                const r = barW / 2;
                ctx.beginPath();
                if (ctx.roundRect) ctx.roundRect(left, top, barW, bh, r);
                else ctx.rect(left, top, barW, bh);
                ctx.fill();
            }
        };
        raf = requestAnimationFrame(draw);
        return () => cancelAnimationFrame(raf);
    }, [analyserRef]);

    return (
        <canvas
            ref={canvasRef}
            data-testid="v2ai-waveform"
            data-keep-anim="true"
            aria-hidden="true"
            style={{ width: 'min(640px, 78vw)', height: 120, display: 'block' }}
        />
    );
}

export default function V2AI() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [params] = useSearchParams();

    // phase: idle | recording | thinking
    const [phase, setPhase] = useState('idle');
    const [status, setStatus] = useState('Ready');
    const [heading, setHeading] = useState('Hold OK and ask anything about movies, TV, or apps.');
    // result: null | {kind:'recs'|'qa'|'person', parsed}
    const [result, setResult] = useState(null);
    // Live transcript — words appear on screen WHILE you speak
    // (Google-Assistant style), refreshed every ~1 s from Whisper.
    const [liveText, setLiveText] = useState('');
    const liveTextRef = useRef('');
    liveTextRef.current = liveText;
    // Launcher-portal V2 AI config (wallpaper, heading, badge).
    const [cfg, setCfg] = useState(null);

    const recorderRef = useRef(null);
    const streamRef = useRef(null);
    const chunksRef = useRef([]);
    const startedAtRef = useRef(0);
    const maxTimerRef = useRef(null);
    const audioCtxRef = useRef(null);
    const analyserRef = useRef(null);
    const phaseRef = useRef('idle');
    phaseRef.current = phase;
    // Rail push-and-hold edge case: the OK keyup can land BEFORE the
    // recorder finishes starting (navigation + getUserMedia latency).
    // Remember it and auto-stop shortly after start instead of ghost-
    // recording for the full 10 s cap.
    const keyUpPendingRef = useRef(false);
    const standbyHintRef = useRef('Hold OK and ask anything about movies, TV, or apps.');
    const partialBusyRef = useRef(false);
    const partialSeqRef = useRef(0);
    const lastShownSeqRef = useRef(0);

    const cleanupRecorder = () => {
        try { streamRef.current?.getTracks().forEach((t) => t.stop()); } catch { /* */ }
        streamRef.current = null;
        recorderRef.current = null;
        analyserRef.current = null;
        try { audioCtxRef.current?.close(); } catch { /* */ }
        audioCtxRef.current = null;
        if (maxTimerRef.current) { clearTimeout(maxTimerRef.current); maxTimerRef.current = null; }
    };
    useEffect(() => () => cleanupRecorder(), []);

    /* ─────────── launcher config (wallpaper + heading + badge) ─────────── */
    useEffect(() => {
        let cancelled = false;
        (async () => {
            try {
                const r = await fetch(`${API}/v2ai/config`);
                if (!r.ok) return;
                const c = await r.json();
                if (cancelled || !c) return;
                setCfg(c);
                const ht = (c.heading_text || '').trim();
                if (ht) {
                    standbyHintRef.current = ht;
                    setHeading((prev) =>
                        prev === 'Hold OK and ask anything about movies, TV, or apps.' ? ht : prev);
                }
            } catch { /* keep defaults */ }
        })();
        return () => { cancelled = true; };
    }, []);

    const bgUrl = '/v2ai-bg.png';
    const holdVisible = cfg ? cfg.hold_button_visible !== false : true;
    const holdImageUrl = cfg?.hold_button_image_url
        ? `${API}/v2ai/asset?path=${encodeURIComponent(cfg.hold_button_image_url)}`
        : null;

    /* ─────────── intent dispatch (mirrors the launcher) ─────────── */
    const showStandby = useCallback(() => {
        setResult(null);
        setLiveText('');
        setHeading(standbyHintRef.current);
        setStatus('Ready');
    }, []);

    const handleParsed = useCallback((parsed) => {
        const intent = parsed?.intent || 'reject';
        const reply = parsed?.speech_reply || '';
        const transcript = parsed?.transcript || '';
        setPhase('idle');
        setLiveText('');
        if (transcript) setStatus(transcript);
        if (intent === 'play_movie' || intent === 'play_series') {
            const title = (parsed.title || '').trim();
            if (!title) {
                setHeading('Sorry');
                setStatus("I didn't catch the title — hold OK and try again.");
                return;
            }
            setHeading(reply || `Loading ${title}…`);
            navigate(
                `/v2ai-play?title=${encodeURIComponent(title)}` +
                `&type=${intent === 'play_series' ? 'series' : 'movie'}`,
            );
        } else if (intent === 'open_app') {
            const nm = (parsed.app_name || '').toLowerCase();
            if (nm.includes('music') || nm.includes('tunes')) navigate('/music');
            else if (nm.includes('search')) navigate('/search');
            else if (nm.includes('setting')) navigate('/settings');
            else {
                setHeading('Sorry');
                setStatus('I can play movies and shows right here — other apps open from the launcher home.');
            }
        } else if (intent === 'recommend' || intent === 'search' || intent === 'trending') {
            const items = parsed.recommendations || [];
            if (!items.length) {
                setHeading('Sorry');
                setStatus('No matches found.');
                return;
            }
            setResult({ kind: 'recs', parsed });
        } else if (intent === 'qa') {
            setResult({ kind: 'qa', parsed });
        } else if (intent === 'person_info') {
            setResult({ kind: 'person', parsed });
        } else {
            setHeading('Sorry');
            setStatus(parsed?.reject_reason || 'I only help with movies, TV, and apps.');
        }
    }, [navigate]);

    const submitText = useCallback(async (text) => {
        setPhase('thinking');
        setStatus('Thinking…');
        setHeading('Processing your request…');
        try {
            const r = await fetch(`${API}/v2ai/process-text`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text, device_id: deviceId() }),
            });
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            handleParsed(await r.json());
        } catch {
            setPhase('idle');
            setHeading(standbyHintRef.current);
            setStatus("Couldn't reach V2 AI — check Wi-Fi and try again.");
        }
    }, [handleParsed]);

    const submitAudio = useCallback(async (blob, ext) => {
        setPhase('thinking');
        setStatus('Thinking…');
        setHeading(liveTextRef.current ? `“${liveTextRef.current}”` : 'Processing your request…');
        try {
            const fd = new FormData();
            fd.append('file', blob, `v2ai.${ext}`);
            fd.append('device_id', deviceId());
            const r = await fetch(`${API}/v2ai/process`, { method: 'POST', body: fd });
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            handleParsed(await r.json());
        } catch {
            setPhase('idle');
            setHeading(standbyHintRef.current);
            setStatus("Couldn't reach V2 AI — check Wi-Fi and try again.");
        }
    }, [handleParsed]);

    /* ─────────── voice capture ───────────
       v2.13.2 — mic capture ONLY.  The old Host.voiceSearch()
       fallback opened Android's Google speech dialog on the box —
       user explicitly does not want that.  On failure we show a
       clear status instead. */
    const micUnavailable = useCallback((err) => {
        setPhase('idle');
        setHeading(standbyHintRef.current);
        setStatus(
            err && err.name === 'NotAllowedError'
                ? 'Mic permission needed — allow the microphone for ON NOW TV V2.'
                : 'Microphone unavailable on this device.',
        );
    }, []);

    /* Live transcript: every ~1 s while recording, send the audio
       accumulated SO FAR to Whisper and paint the partial text on
       screen — same feel as the Google box's live captions.  The
       busy flag gives natural throttling (next partial fires on the
       first data chunk after the previous request lands). */
    const transcribePartial = useCallback(async () => {
        if (partialBusyRef.current || !recorderRef.current) return;
        if (!chunksRef.current.length) return;
        const rec = recorderRef.current;
        const blob = new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' });
        if (blob.size < 2000) return;
        partialBusyRef.current = true;
        const seq = ++partialSeqRef.current;
        try {
            const fd = new FormData();
            fd.append('file', blob, `v2ai-partial.${mimeToExt(rec.mimeType)}`);
            const r = await fetch(`${API}/v2ai/transcribe-partial`, { method: 'POST', body: fd });
            if (r.ok) {
                const j = await r.json();
                const text = (j.text || '').trim();
                if (text && seq > lastShownSeqRef.current && phaseRef.current === 'recording') {
                    lastShownSeqRef.current = seq;
                    setLiveText(text);
                }
            }
        } catch { /* partials are best-effort */ }
        finally { partialBusyRef.current = false; }
    }, []);

    const startRecording = useCallback(async () => {
        if (recorderRef.current || phaseRef.current !== 'idle') return;
        if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
            micUnavailable(null);
            return;
        }
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
            });
            streamRef.current = stream;
            // WebAudio analyser drives the live waveform — the "moving
            // animation when you're talking to it".
            try {
                const AC = window.AudioContext || window.webkitAudioContext;
                const actx = new AC();
                const src = actx.createMediaStreamSource(stream);
                const analyser = actx.createAnalyser();
                analyser.fftSize = 1024;
                src.connect(analyser);
                audioCtxRef.current = actx;
                analyserRef.current = analyser;
            } catch { /* waveform falls back to idle shimmer */ }
            const mime = pickRecorderMime();
            const rec = mime ? new MediaRecorder(stream, { mimeType: mime })
                             : new MediaRecorder(stream);
            recorderRef.current = rec;
            chunksRef.current = [];
            setLiveText('');
            lastShownSeqRef.current = 0;
            partialSeqRef.current = 0;
            rec.ondataavailable = (e) => {
                if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
                if (recorderRef.current === rec && rec.state === 'recording') transcribePartial();
            };
            rec.onstop = async () => {
                const elapsed = Date.now() - startedAtRef.current;
                const blob = new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' });
                cleanupRecorder();
                if (elapsed < MIN_RECORD_MS || blob.size < 800) {
                    setPhase('idle');
                    setHeading(standbyHintRef.current);
                    setStatus('Hold OK longer to speak');
                    return;
                }
                await submitAudio(blob, mimeToExt(rec.mimeType));
            };
            rec.start(900); // 900 ms timeslice → chunks feed the live transcript
            startedAtRef.current = Date.now();
            setPhase('recording');
            setStatus('Listening…');
            setHeading('Speaking…');
            maxTimerRef.current = setTimeout(() => stopRecording(), MAX_RECORD_MS);
            if (keyUpPendingRef.current) {
                keyUpPendingRef.current = false;
                setTimeout(() => stopRecording(), 900);
            }
        } catch (err) {
            cleanupRecorder();
            micUnavailable(err);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [submitAudio, micUnavailable, transcribePartial]);

    const stopRecording = () => {
        if (!recorderRef.current) return;
        try { recorderRef.current.requestData?.(); } catch { /* */ }
        try { recorderRef.current.stop(); } catch { /* */ }
    };

    /* ─────────── activity-level hold-OK — anywhere on the page ─────────── */
    useEffect(() => {
        const down = (e) => {
            if (!OK_KEYS.includes(e.key)) return;
            // Don't hijack OK when the user is activating a result
            // card / ask-again button.
            if (result) return;
            e.preventDefault();
            if (e.repeat) return;
            keyUpPendingRef.current = false;
            startRecording();
        };
        const up = (e) => {
            if (!OK_KEYS.includes(e.key)) return;
            if (result) return;
            e.preventDefault();
            if (!recorderRef.current) { keyUpPendingRef.current = true; return; }
            stopRecording();
        };
        window.addEventListener('keydown', down, true);
        window.addEventListener('keyup', up, true);
        return () => {
            window.removeEventListener('keydown', down, true);
            window.removeEventListener('keyup', up, true);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [result, startRecording]);

    /* ─────────── boot: ?listen=1 (rail hold) / ?q= deep-link ─────────── */
    useEffect(() => {
        const q = (params.get('q') || '').trim();
        if (q) { submitText(q); return; }
        if (params.get('listen') === '1' && phaseRef.current === 'idle') {
            startRecording();
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [params]);

    /* ─────────── BACK = ask again while results shown ─────────── */
    useEffect(() => {
        if (!result) return undefined;
        window.history.pushState({ v2aiResults: true }, '');
        const onPop = () => showStandby();
        window.addEventListener('popstate', onPop);
        return () => window.removeEventListener('popstate', onPop);
    }, [result, showStandby]);
    // Focus the first result card when results appear.
    useEffect(() => {
        if (!result) return;
        const t = setTimeout(() => {
            const first =
                document.querySelector('[data-testid="v2ai-rec-card-0"]') ||
                document.querySelector('[data-testid="v2ai-qa-play"]') ||
                document.querySelector('[data-testid="v2ai-ask-again"]');
            if (first) {
                first.focus({ preventScroll: true });
                first.setAttribute('data-focused', 'true');
            }
        }, 60);
        return () => clearTimeout(t);
    }, [result]);

    const askAgain = () => {
        if (window.history.state?.v2aiResults) window.history.back();
        else showStandby();
    };

    const playTitle = (title, type) => {
        navigate(
            `/v2ai-play?title=${encodeURIComponent(title)}` +
            `&type=${type === 'series' ? 'series' : 'movie'}`,
        );
    };

    return (
        <div
            data-testid="v2ai-page"
            className="w-screen h-[100dvh] relative overflow-hidden"
            style={{
                background:
                    'radial-gradient(ellipse at 50% 30%, var(--vesper-bg-2, #0e2548) 0%, var(--vesper-bg-1, #050912) 62%, var(--vesper-bg-0, #02030A) 100%)',
                color: '#F4F7FB',
            }}
        >
            {/* Admin wallpaper — same image the launcher shows,
                rendered VIBRANT with no scrim (launcher v2.8.27). */}
            {bgUrl ? (
                <img
                    src={bgUrl}
                    alt=""
                    aria-hidden="true"
                    data-testid="v2ai-wallpaper"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                    }}
                />
            ) : null}
            {/* Stage-dimmer scrim — launcher shows #B3000000 over the
                wallpaper whenever results are on screen. */}
            {result ? (
                <div
                    aria-hidden="true"
                    style={{ position: 'absolute', inset: 0, background: 'rgba(0,0,0,0.70)' }}
                />
            ) : null}

            {!result ? (
                /* ───────────── STANDBY — launcher-exact column ───────────── */
                <div
                    className="relative h-full flex flex-col items-center justify-center"
                    style={{ padding: '36px 48px' }}
                >
                <div
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                    }}
                >
                    <span
                        aria-hidden="true"
                        data-keep-anim="true"
                        className="v2ai-orb"
                        style={{
                            width: 22,
                            height: 22,
                            borderRadius: '50%',
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            fontSize: 9,
                            fontWeight: 800,
                            color: '#fff',
                            background:
                                'radial-gradient(circle at 32% 28%, var(--vesper-blue-bright) 0%, var(--vesper-blue) 45%, rgba(var(--vesper-blue-rgb), 0.55) 100%)',
                            boxShadow: '0 0 18px rgba(var(--vesper-blue-rgb), 0.75)',
                        }}
                    >
                        AI
                    </span>
                    <span
                        style={{
                            fontFamily: 'monospace',
                            fontSize: 12,
                            letterSpacing: '0.30em',
                            textTransform: 'uppercase',
                            background:
                                'linear-gradient(90deg, var(--vesper-blue-bright) 0%, var(--vesper-blue) 55%, var(--vesper-blue-bright) 100%)',
                            WebkitBackgroundClip: 'text',
                            WebkitTextFillColor: 'transparent',
                        }}
                    >
                        ON NOW TV V2 · V2 AI
                    </span>
                </div>
                    <div style={{ height: 10 }} />
                    <div
                        data-testid="v2ai-big-hint"
                        style={{
                            fontSize: 30,
                            fontWeight: 700,
                            color: '#F4F7FB',
                            letterSpacing: '-0.02em',
                            textAlign: 'center',
                            textShadow:
                                '0 2px 12px rgba(6,2,22,0.85), 0 0 42px rgba(var(--vesper-blue-rgb), 0.45)',
                            maxWidth: 900,
                        }}
                    >
                        {phase !== 'idle' && liveText ? `“${liveText}”` : heading}
                    </div>
                    <div style={{ height: 24 }} />
                    <div style={{ position: 'relative' }}>
                        <div
                            aria-hidden="true"
                            style={{
                                position: 'absolute',
                                inset: '-46px -80px',
                                background:
                                    'radial-gradient(ellipse at center, rgba(var(--vesper-blue-rgb), 0.28) 0%, rgba(var(--vesper-blue-rgb), 0.10) 45%, transparent 72%)',
                                pointerEvents: 'none',
                            }}
                        />
                        <VoiceWaveformCanvas listening={phase === 'recording'} analyserRef={analyserRef} />
                    </div>
                    <div style={{ height: 16 }} />
                    {holdVisible ? (
                        <>
                            <button
                                type="button"
                                data-testid="v2ai-hold-btn"
                                data-focusable="true"
                                tabIndex={0}
                                onMouseDown={() => startRecording()}
                                onMouseUp={() => stopRecording()}
                                onMouseLeave={() => stopRecording()}
                                onTouchStart={(e) => { e.preventDefault(); startRecording(); }}
                                onTouchEnd={(e) => { e.preventDefault(); stopRecording(); }}
                                style={{
                                    width: 140,
                                    height: 140,
                                    borderRadius: '50%',
                                    border: 'none',
                                    cursor: 'pointer',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    fontWeight: 700,
                                    fontSize: 16,
                                    color: '#FFFFFF',
                                    background: holdImageUrl
                                        ? 'transparent'
                                        : 'linear-gradient(135deg, var(--vesper-blue) 0%, var(--vesper-blue-bright) 100%)',
                                    boxShadow: '0 0 34px rgba(var(--vesper-blue-rgb), 0.55)',
                                    padding: 0,
                                    overflow: 'hidden',
                                }}
                            >
                                {holdImageUrl
                                    ? <img src={holdImageUrl} alt="Hold OK to talk" style={{ width: '100%', height: '100%', objectFit: 'contain' }} />
                                    : 'HOLD OK'}
                            </button>
                            <div style={{ height: 16 }} />
                        </>
                    ) : null}
                    <div
                        data-testid="v2ai-status"
                        style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 10,
                            fontSize: 14,
                            letterSpacing: '0.16em',
                            color: '#C9CFE3',
                            textAlign: 'center',
                            maxWidth: 760,
                            padding: '10px 26px',
                            borderRadius: 999,
                            background: 'rgba(10,6,30,0.55)',
                            border: '1px solid rgba(var(--vesper-blue-rgb), 0.40)',
                            boxShadow: '0 0 24px rgba(var(--vesper-blue-rgb), 0.22)',
                            backdropFilter: 'blur(14px)',
                            WebkitBackdropFilter: 'blur(14px)',
                        }}
                    >
                        <span
                            aria-hidden="true"
                            style={{
                                width: 8,
                                height: 8,
                                borderRadius: '50%',
                                flex: '0 0 auto',
                                background: phase === 'recording' ? 'var(--vesper-blue-bright)' : 'var(--vesper-blue)',
                                boxShadow: '0 0 10px rgba(var(--vesper-blue-rgb), 0.9)',
                            }}
                        />
                        {status}
                    </div>
                </div>
            ) : (
                /* ───────────── RESULTS — launcher-exact layout ───────────── */
                <div
                    className="relative h-full flex flex-col"
                    style={{ padding: '36px 48px', minHeight: 0 }}
                >
                    <div style={{ display: 'flex', alignItems: 'center', gap: 18, paddingLeft: 8, paddingBottom: 14 }}>
                        <button
                            type="button"
                            data-testid="v2ai-ask-again"
                            data-focusable="true"
                            tabIndex={0}
                            onClick={askAgain}
                            style={{
                                fontSize: 13,
                                letterSpacing: '0.16em',
                                color: '#8EA0B7',
                                background: 'transparent',
                                border: '1px solid rgba(142,160,183,0.35)',
                                borderRadius: 999,
                                padding: '6px 16px',
                                cursor: 'pointer',
                            }}
                        >
                            ← ASK AGAIN
                        </button>
                        <span style={{ fontSize: 13, letterSpacing: '0.16em', color: '#8EA0B7' }}>
                            PRESS BACK TO ASK AGAIN
                        </span>
                    </div>
                    {result.parsed?.transcript ? (
                        <div
                            data-testid="v2ai-transcript"
                            style={{
                                fontSize: 22,
                                fontWeight: 700,
                                letterSpacing: '-0.01em',
                                padding: '0 8px 18px',
                                whiteSpace: 'nowrap',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                            }}
                        >
                            “{result.parsed.transcript}”
                        </div>
                    ) : null}

                    <div className="flex-1 flex" style={{ minHeight: 0, alignItems: 'center' }}>
                        {result.kind === 'recs' && (
                            <RecRail items={result.parsed.recommendations || []} onPlay={playTitle} />
                        )}
                        {result.kind === 'qa' && (
                            <QaCard parsed={result.parsed} onPlay={playTitle} />
                        )}
                        {result.kind === 'person' && (
                            <PersonCard parsed={result.parsed} onPlay={playTitle} />
                        )}
                    </div>
                </div>
            )}

            {/* D-pad focus visuals — the spatial engine mirrors
                focus onto data-focused; make it unmistakable on TV. */}
            <style>{`
                .v2ai-card { transition: transform 160ms ease, box-shadow 160ms ease, border-color 160ms ease; outline: none; }
                .v2ai-card[data-focused="true"],
                .v2ai-card:focus {
                    border-color: var(--vesper-blue-bright) !important;
                    box-shadow: 0 0 0 3px rgba(var(--vesper-blue-rgb),0.9), 0 0 36px rgba(var(--vesper-blue-rgb),0.5);
                    transform: scale(1.045);
                    z-index: 2;
                }
                [data-testid="v2ai-ask-again"],
                [data-testid="v2ai-qa-play"] { outline: none; transition: box-shadow 160ms ease, color 160ms ease; }
                [data-testid="v2ai-ask-again"][data-focused="true"],
                [data-testid="v2ai-ask-again"]:focus,
                [data-testid="v2ai-qa-play"][data-focused="true"],
                [data-testid="v2ai-qa-play"]:focus {
                    box-shadow: 0 0 0 3px rgba(var(--vesper-blue-rgb),0.9), 0 0 24px rgba(var(--vesper-blue-rgb),0.45);
                    color: #fff;
                }
            `}</style>
        </div>
    );
}

/* ─────────── result renderers ─────────── */

function metaLine(item) {
    const bits = [];
    if (item.year) bits.push(String(item.year));
    bits.push(item.type === 'series' ? 'TV' : 'Movie');
    return bits.join('  ·  ');
}

function RecRail({ items, onPlay }) {
    return (
        <div
            data-testid="v2ai-rec-rail"
            style={{
                display: 'flex',
                gap: 16,
                overflowX: 'auto',
                padding: '10px 4px 18px',
                alignItems: 'center',
                width: '100%',
                justifyContent: items.length <= 4 ? 'center' : 'flex-start',
            }}
        >
            {items.map((item, i) => (
                <button
                    key={`${item.title}-${i}`}
                    type="button"
                    data-testid={`v2ai-rec-card-${i}`}
                    data-focusable="true"
                    data-focus-style="card"
                    tabIndex={0}
                    onClick={() => onPlay(item.title, item.type)}
                    className="v2ai-card"
                    style={{
                        width: 252,
                        flex: '0 0 auto',
                        textAlign: 'left',
                        borderRadius: 18,
                        border: '2px solid rgba(67,88,127,0.20)',
                        background: 'rgba(14,24,52,0.85)',
                        padding: 12,
                        color: 'inherit',
                        cursor: 'pointer',
                    }}
                >
                    <div
                        style={{
                            width: '100%',
                            aspectRatio: '16 / 9',
                            borderRadius: 10,
                            overflow: 'hidden',
                            background: 'linear-gradient(180deg, #1A2542, #0E1834)',
                            marginBottom: 10,
                        }}
                    >
                        {(item.backdrop_url || item.poster_url) ? (
                            <img
                                src={item.backdrop_url || item.poster_url}
                                alt={item.title}
                                loading="lazy"
                                style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                            />
                        ) : null}
                    </div>
                    <div style={{ fontWeight: 700, fontSize: 15, lineHeight: 1.25, marginBottom: 6 }}>
                        {item.title}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <span style={{ flex: 1, fontSize: 11, letterSpacing: '0.06em', color: '#8EA0B7' }}>
                            {metaLine(item)}
                        </span>
                        {item.rating && Number(item.rating) > 0 ? (
                            <span
                                style={{
                                    fontSize: 11,
                                    fontWeight: 800,
                                    padding: '2px 8px',
                                    borderRadius: 999,
                                    background: '#FFC857',
                                    color: '#0E1834',
                                }}
                            >
                                ★ {item.rating}
                            </span>
                        ) : null}
                    </div>
                    {(item.overview || item.why) ? (
                        <div
                            style={{
                                marginTop: 8,
                                fontSize: 12,
                                lineHeight: 1.45,
                                color: '#8EA0B7',
                                display: '-webkit-box',
                                WebkitLineClamp: 3,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {item.overview || item.why}
                        </div>
                    ) : null}
                </button>
            ))}
        </div>
    );
}

function QaCard({ parsed, onPlay }) {
    const answer = parsed.answer || parsed.speech_reply || '';
    const subject = parsed.answer_subject || '';
    const subjType = (parsed.answer_subject_type || '').toLowerCase();
    const playable = subject && (subjType === 'movie' || subjType === 'series');
    return (
        <div
            data-testid="v2ai-qa-card"
            style={{
                display: 'flex',
                gap: 28,
                alignItems: 'flex-start',
                background: 'rgba(14,24,52,0.85)',
                border: '2px solid rgba(67,88,127,0.20)',
                borderRadius: 22,
                padding: 'clamp(20px, 2.5vw, 34px)',
                maxWidth: 1100,
                margin: '0 auto',
            }}
        >
            {parsed.subject_poster_url ? (
                <img
                    src={parsed.subject_poster_url}
                    alt={subject}
                    style={{ width: 190, borderRadius: 14, flex: '0 0 auto' }}
                />
            ) : null}
            <div style={{ minWidth: 0 }}>
                {subject ? (
                    <div style={{ fontSize: 22, fontWeight: 800, marginBottom: 6 }}>
                        {subject}
                        {parsed.subject_year ? (
                            <span style={{ marginLeft: 10, fontWeight: 500, color: '#8EA0B7', fontSize: 15 }}>
                                {parsed.subject_year}
                            </span>
                        ) : null}
                        {parsed.subject_rating ? (
                            <span
                                style={{
                                    marginLeft: 10,
                                    fontSize: 12,
                                    fontWeight: 800,
                                    padding: '2px 8px',
                                    borderRadius: 999,
                                    background: '#FFC857',
                                    color: '#0E1834',
                                    verticalAlign: 'middle',
                                }}
                            >
                                ★ {parsed.subject_rating}
                            </span>
                        ) : null}
                    </div>
                ) : null}
                <div style={{ fontSize: 17, lineHeight: 1.55 }}>{answer}</div>
                {playable ? (
                    <button
                        type="button"
                        data-testid="v2ai-qa-play"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={() => onPlay(subject, subjType)}
                        style={{
                            marginTop: 18,
                            background: 'var(--vesper-blue)',
                            color: '#04060B',
                            fontWeight: 700,
                            fontSize: 15,
                            border: 'none',
                            borderRadius: 999,
                            padding: '10px 24px',
                            cursor: 'pointer',
                        }}
                    >
                        ▶ Play {subject}
                    </button>
                ) : null}
            </div>
        </div>
    );
}

function PersonCard({ parsed, onPlay }) {
    const known = parsed.known_for || [];
    return (
        <div data-testid="v2ai-person-card" style={{ minHeight: 0, display: 'flex', flexDirection: 'column', width: '100%' }}>
            <div style={{ display: 'flex', gap: 24, alignItems: 'flex-start', marginBottom: 18, padding: '0 8px' }}>
                {parsed.person_profile_url ? (
                    <img
                        src={parsed.person_profile_url}
                        alt={parsed.person_name || ''}
                        style={{ width: 130, height: 130, borderRadius: '50%', objectFit: 'cover', flex: '0 0 auto' }}
                    />
                ) : null}
                <div style={{ minWidth: 0 }}>
                    <div style={{ fontSize: 24, fontWeight: 800, marginBottom: 6 }}>
                        {parsed.person_name || ''}
                    </div>
                    <div
                        style={{
                            fontSize: 14,
                            lineHeight: 1.55,
                            color: '#8EA0B7',
                            maxWidth: 900,
                            display: '-webkit-box',
                            WebkitLineClamp: 4,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                        }}
                    >
                        {parsed.person_bio || parsed.speech_reply || ''}
                    </div>
                </div>
            </div>
            {known.length ? <RecRail items={known} onPlay={onPlay} /> : null}
        </div>
    );
}
