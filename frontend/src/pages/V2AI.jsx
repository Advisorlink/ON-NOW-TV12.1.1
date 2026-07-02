import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2, Sparkles } from 'lucide-react';
import { API } from '@/lib/api';
import Host from '@/lib/host';
import useSpatialFocus from '@/hooks/useSpatialFocus';

/**
 * /v2ai — the V2 AI voice assistant, now living INSIDE Vesper.
 *
 * v2.13.0 — Ported from the launcher's native VoiceAssistantActivity
 * per user spec: "remove the AI from the launcher... just put it
 * inside the app".  Because the page runs inside Vesper (already
 * past the profile gate), "Play The Matrix" navigates straight to
 * /v2ai-play → /resolve → autoplay with NO app relaunch and NO
 * profile-screen interruption.
 *
 * Flow: hold OK on the mic badge → MediaRecorder captures audio →
 * POST /api/v2ai/process (proxied to the launcher backend's
 * Whisper + intent parser) → dispatch the intent in-app.  When mic
 * capture isn't available, falls back to Host.voiceSearch() (native
 * SpeechRecognizer / Web Speech API) + the /process-text endpoint.
 */

const MAX_RECORD_MS = 10_000;
const MIN_RECORD_MS = 400;

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

export default function V2AI() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [params] = useSearchParams();

    // phase: idle | recording | thinking
    const [phase, setPhase] = useState('idle');
    const [status, setStatus] = useState('Ready');
    // result: null | {kind:'recs'|'qa'|'person', parsed}
    const [result, setResult] = useState(null);

    const holdBtnRef = useRef(null);
    const recorderRef = useRef(null);
    const streamRef = useRef(null);
    const chunksRef = useRef([]);
    const startedAtRef = useRef(0);
    const maxTimerRef = useRef(null);
    const phaseRef = useRef('idle');
    phaseRef.current = phase;

    const cleanupRecorder = () => {
        try { streamRef.current?.getTracks().forEach((t) => t.stop()); } catch { /* */ }
        streamRef.current = null;
        recorderRef.current = null;
        if (maxTimerRef.current) { clearTimeout(maxTimerRef.current); maxTimerRef.current = null; }
    };
    useEffect(() => () => cleanupRecorder(), []);

    /* ─────────── intent dispatch (mirrors the launcher) ─────────── */
    const handleParsed = useCallback((parsed) => {
        const intent = parsed?.intent || 'reject';
        const reply = parsed?.speech_reply || '';
        setPhase('idle');
        if (intent === 'play_movie' || intent === 'play_series') {
            const title = (parsed.title || '').trim();
            if (!title) {
                setStatus("I didn't catch the title.  Hold OK and try again.");
                return;
            }
            setStatus(reply || `Loading ${title}…`);
            navigate(
                `/v2ai-play?title=${encodeURIComponent(title)}` +
                `&type=${intent === 'play_series' ? 'series' : 'movie'}`,
            );
        } else if (intent === 'open_app') {
            const nm = (parsed.app_name || '').toLowerCase();
            if (nm.includes('music') || nm.includes('tunes')) navigate('/music');
            else if (nm.includes('search')) navigate('/search');
            else if (nm.includes('setting')) navigate('/settings');
            else setStatus('I can play movies and shows right here — other apps open from the launcher home.');
        } else if (intent === 'recommend' || intent === 'search' || intent === 'trending') {
            const items = parsed.recommendations || [];
            if (!items.length) { setStatus('No matches found.'); return; }
            setResult({ kind: 'recs', parsed });
        } else if (intent === 'qa') {
            setResult({ kind: 'qa', parsed });
        } else if (intent === 'person_info') {
            setResult({ kind: 'person', parsed });
        } else {
            setStatus(parsed?.reject_reason || 'I only help with movies, TV, and apps.');
        }
    }, [navigate]);

    const submitText = useCallback(async (text) => {
        setPhase('thinking');
        setStatus('Thinking…');
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
            setStatus("Couldn't reach V2 AI — check Wi-Fi and try again.");
        }
    }, [handleParsed]);

    const submitAudio = useCallback(async (blob, ext) => {
        setPhase('thinking');
        setStatus('Thinking…');
        try {
            const fd = new FormData();
            fd.append('file', blob, `v2ai.${ext}`);
            fd.append('device_id', deviceId());
            const r = await fetch(`${API}/v2ai/process`, { method: 'POST', body: fd });
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            handleParsed(await r.json());
        } catch {
            setPhase('idle');
            setStatus("Couldn't reach V2 AI — check Wi-Fi and try again.");
        }
    }, [handleParsed]);

    /* ─────────── voice capture ─────────── */
    const voiceSearchFallback = useCallback(async () => {
        try {
            setPhase('recording');
            setStatus('Listening…');
            const text = await Host.voiceSearch();
            await submitText(text);
        } catch (e) {
            setPhase('idle');
            setStatus(
                e && e.message === 'unsupported'
                    ? 'Microphone unavailable on this device.'
                    : "I didn't catch that — hold OK and try again.",
            );
        }
    }, [submitText]);

    const startRecording = useCallback(async () => {
        if (recorderRef.current || phaseRef.current !== 'idle') return;
        if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
            voiceSearchFallback();
            return;
        }
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, channelCount: 1 },
            });
            streamRef.current = stream;
            const mime = pickRecorderMime();
            const rec = mime ? new MediaRecorder(stream, { mimeType: mime })
                             : new MediaRecorder(stream);
            recorderRef.current = rec;
            chunksRef.current = [];
            rec.ondataavailable = (e) => {
                if (e.data && e.data.size > 0) chunksRef.current.push(e.data);
            };
            rec.onstop = async () => {
                const elapsed = Date.now() - startedAtRef.current;
                const blob = new Blob(chunksRef.current, { type: rec.mimeType || 'audio/webm' });
                cleanupRecorder();
                if (elapsed < MIN_RECORD_MS || blob.size < 800) {
                    setPhase('idle');
                    setStatus('Too short — hold OK while you speak.');
                    return;
                }
                await submitAudio(blob, mimeToExt(rec.mimeType));
            };
            rec.start();
            startedAtRef.current = Date.now();
            setPhase('recording');
            setStatus('Listening… release OK when done.');
            maxTimerRef.current = setTimeout(() => stopRecording(), MAX_RECORD_MS);
        } catch (err) {
            cleanupRecorder();
            if (err && err.name === 'NotAllowedError') {
                setPhase('idle');
                setStatus('Microphone permission blocked.');
            } else {
                voiceSearchFallback();
            }
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [submitAudio, voiceSearchFallback]);

    const stopRecording = () => {
        if (!recorderRef.current) return;
        try { recorderRef.current.requestData?.(); } catch { /* */ }
        try { recorderRef.current.stop(); } catch { /* */ }
    };

    /* ─────────── hold-OK interactions ─────────── */
    const onHoldKeyDown = (e) => {
        if (!OK_KEYS.includes(e.key)) return;
        e.preventDefault();
        if (e.repeat) return;
        startRecording();
    };
    const onHoldKeyUp = (e) => {
        if (!OK_KEYS.includes(e.key)) return;
        e.preventDefault();
        stopRecording();
    };

    /* ─────────── boot: focus + optional ?q= deep-link ─────────── */
    useEffect(() => {
        const q = (params.get('q') || '').trim();
        if (q) { submitText(q); return; }
        const btn = holdBtnRef.current;
        if (btn) {
            btn.focus({ preventScroll: true });
            btn.setAttribute('data-focused', 'true');
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [params]);

    /* ─────────── BACK = ask again while results shown ─────────── */
    useEffect(() => {
        if (!result) return undefined;
        window.history.pushState({ v2aiResults: true }, '');
        const onPop = () => setResult(null);
        window.addEventListener('popstate', onPop);
        return () => window.removeEventListener('popstate', onPop);
    }, [result]);
    // Re-prime focus on the hold button whenever we return to standby.
    useEffect(() => {
        if (result) return;
        const btn = holdBtnRef.current;
        if (btn) {
            btn.focus({ preventScroll: true });
            btn.setAttribute('data-focused', 'true');
        }
    }, [result]);
    // Focus the first result card when results appear.
    useEffect(() => {
        if (!result) return;
        const t = setTimeout(() => {
            const first = document.querySelector('[data-testid="v2ai-rec-card-0"], [data-testid="v2ai-ask-again"]');
            if (first) {
                first.focus({ preventScroll: true });
                first.setAttribute('data-focused', 'true');
            }
        }, 60);
        return () => clearTimeout(t);
    }, [result]);

    const askAgain = () => {
        if (window.history.state?.v2aiResults) window.history.back();
        else setResult(null);
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
            className="w-screen min-h-[100dvh] flex flex-col"
            style={{
                background:
                    'radial-gradient(ellipse at 50% 30%, #0e2548 0%, #050912 62%, #02030A 100%)',
                color: 'var(--vesper-text, #F4F7FB)',
                padding: 'clamp(28px, 4vh, 52px) clamp(32px, 4vw, 64px)',
            }}
        >
            {!result ? (
                /* ───────────────── STANDBY ───────────────── */
                <div className="flex-1 flex flex-col items-center justify-center" style={{ gap: 22 }}>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 12,
                            letterSpacing: '0.30em',
                            color: 'var(--vesper-blue-bright, #5DC8FF)',
                            textTransform: 'uppercase',
                        }}
                    >
                        ON NOW TV V2 · V2 AI
                    </div>
                    <div
                        data-testid="v2ai-big-hint"
                        style={{
                            fontSize: 'clamp(24px, 2.6vw, 34px)',
                            fontWeight: 800,
                            letterSpacing: '-0.02em',
                            textAlign: 'center',
                            maxWidth: 820,
                            lineHeight: 1.2,
                        }}
                    >
                        Hold OK and ask anything about movies or TV.
                    </div>

                    {/* Waveform */}
                    <div
                        aria-hidden="true"
                        data-keep-anim="true"
                        style={{ display: 'flex', alignItems: 'center', gap: 5, height: 84 }}
                    >
                        {Array.from({ length: 26 }).map((_, i) => (
                            <span
                                key={i}
                                className={phase === 'recording' ? 'v2ai-bar v2ai-bar--live' : 'v2ai-bar'}
                                style={{ animationDelay: `${(i % 7) * 90}ms` }}
                            />
                        ))}
                    </div>

                    {/* Hold-OK badge */}
                    <button
                        ref={holdBtnRef}
                        type="button"
                        data-testid="v2ai-hold-btn"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-no-row-snap="true"
                        tabIndex={0}
                        onKeyDown={onHoldKeyDown}
                        onKeyUp={onHoldKeyUp}
                        onMouseDown={() => startRecording()}
                        onMouseUp={() => stopRecording()}
                        onMouseLeave={() => stopRecording()}
                        onTouchStart={(e) => { e.preventDefault(); startRecording(); }}
                        onTouchEnd={(e) => { e.preventDefault(); stopRecording(); }}
                        style={{
                            width: 148,
                            height: 148,
                            borderRadius: '50%',
                            border: 'none',
                            cursor: 'pointer',
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 6,
                            fontWeight: 800,
                            fontSize: 16,
                            letterSpacing: '0.08em',
                            color: '#04060B',
                            background: phase === 'recording'
                                ? 'linear-gradient(135deg, #FF5CA8 0%, #C24DFF 100%)'
                                : 'linear-gradient(135deg, #5DC8FF 0%, #2BB6FF 55%, #7C6BFF 100%)',
                            boxShadow: phase === 'recording'
                                ? '0 0 44px rgba(255,92,168,0.55)'
                                : '0 0 34px rgba(43,182,255,0.40)',
                            transform: phase === 'recording' ? 'scale(1.06)' : 'scale(1)',
                            transition: 'transform 160ms ease, box-shadow 160ms ease',
                        }}
                    >
                        {phase === 'thinking'
                            ? <Loader2 className="vesper-spin" size={30} />
                            : <Sparkles size={30} strokeWidth={2} />}
                        {phase === 'thinking' ? 'THINKING' : phase === 'recording' ? 'LISTENING' : 'HOLD OK'}
                    </button>

                    <div
                        data-testid="v2ai-status"
                        style={{
                            fontSize: 14,
                            letterSpacing: '0.14em',
                            color: 'var(--vesper-text-2, #8EA0B7)',
                            textAlign: 'center',
                            maxWidth: 700,
                        }}
                    >
                        {status}
                    </div>
                </div>
            ) : (
                /* ───────────────── RESULTS ───────────────── */
                <div className="flex-1 flex flex-col" style={{ minHeight: 0 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 10 }}>
                        <button
                            type="button"
                            data-testid="v2ai-ask-again"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={askAgain}
                            className="vesper-btn"
                            style={{ padding: '8px 18px', fontSize: 13 }}
                        >
                            ← Ask again
                        </button>
                        <span
                            className="vesper-mono"
                            style={{ fontSize: 12, letterSpacing: '0.18em', color: 'var(--vesper-text-3, #62748C)' }}
                        >
                            PRESS BACK TO ASK AGAIN
                        </span>
                    </div>
                    {result.parsed?.transcript ? (
                        <div
                            data-testid="v2ai-transcript"
                            style={{ fontSize: 'clamp(18px, 1.8vw, 24px)', fontWeight: 700, marginBottom: 18 }}
                        >
                            “{result.parsed.transcript}”
                        </div>
                    ) : null}

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
            )}

            <style>{`
                .v2ai-bar {
                    width: 5px;
                    height: 12px;
                    border-radius: 999px;
                    background: rgba(93,200,255,0.35);
                }
                .v2ai-bar--live {
                    background: linear-gradient(180deg, #5DC8FF, #7C6BFF);
                    animation: v2ai-bar-bounce 640ms ease-in-out infinite alternate;
                }
                @keyframes v2ai-bar-bounce {
                    from { transform: scaleY(0.6); }
                    to   { transform: scaleY(5.4); }
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
                padding: '10px 4px 24px',
                alignItems: 'flex-start',
                flex: 1,
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
                        background: 'rgba(14,24,52,0.70)',
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
                        <span style={{ flex: 1, fontSize: 11, letterSpacing: '0.06em', color: 'var(--vesper-text-2, #8EA0B7)' }}>
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
                                color: 'var(--vesper-text-2, #8EA0B7)',
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
                background: 'rgba(14,24,52,0.70)',
                border: '2px solid rgba(67,88,127,0.20)',
                borderRadius: 22,
                padding: 'clamp(20px, 2.5vw, 34px)',
                maxWidth: 1100,
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
                            <span style={{ marginLeft: 10, fontWeight: 500, color: 'var(--vesper-text-2, #8EA0B7)', fontSize: 15 }}>
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
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => onPlay(subject, subjType)}
                        className="vesper-btn"
                        style={{ marginTop: 18 }}
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
        <div data-testid="v2ai-person-card" style={{ minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            <div style={{ display: 'flex', gap: 24, alignItems: 'flex-start', marginBottom: 18 }}>
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
                            color: 'var(--vesper-text-2, #8EA0B7)',
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
