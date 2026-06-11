/**
 * <VoiceReactionButton /> — Watch Together voice-to-text reaction.
 *
 * Behaviour:
 *   • Press-and-hold (mouse / touch / D-pad OK / HK1 remote mic
 *     button) → starts recording.
 *   • Release / 10 s timeout → stops, sends to /api/stt/transcribe,
 *     then broadcasts `voice_message` over the party WS so every
 *     member sees the transcript bubble up like an emoji reaction.
 *   • A pulsing red ring around the icon while recording.
 *   • A small inline status pill ("Listening…", "Transcribing…",
 *     "Mic blocked") so the user always knows what's happening.
 *
 * Permission: the first long-press triggers a getUserMedia({audio})
 * call which prompts for mic permission.  On Android TV (HK1) the
 * permission is granted via the system dialog or via the manifest's
 * RECORD_AUDIO entry.  We never proactively call getUserMedia until
 * the user holds the button, so the page boot doesn't trigger a
 * permission prompt.
 */

import React, { useEffect, useRef, useState } from 'react';
import { Mic, MicOff, Loader2 } from 'lucide-react';
import axios from 'axios';

const MAX_RECORD_MS = 10_000;     // 10 s ceiling per user spec
const MIN_RECORD_MS = 400;        // ignore accidental taps
const PRESS_INDICATOR_MS = 120;   // visual response delay

const API = (process.env.REACT_APP_BACKEND_URL || '').replace(/\/$/, '');

/**
 * Pick the best MediaRecorder mime type supported by this browser.
 * Whisper accepts webm/opus, wav, mp3, m4a, mp4, ogg.  Webm/opus is
 * universally supported in Chromium-based WebViews (which is what
 * the HK1 box runs).
 */
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
    if (mime.startsWith('audio/mp3')) return 'mp3';
    return 'webm';
}

export default function VoiceReactionButton({
    wsRef,                 // ref to party WebSocket
    avatarEmoji = '',      // user's avatar emoji to attach to the broadcast
    onLocalEcho,           // (text) => void — show the bubble locally too
    style,
}) {
    const [state, setState] = useState('idle');
    // 'idle' | 'pressing' | 'recording' | 'transcribing' | 'blocked' | 'error'
    const recorderRef = useRef(null);
    const streamRef = useRef(null);
    const chunksRef = useRef([]);
    const startedAtRef = useRef(0);
    const pressTimerRef = useRef(null);
    const recordTimerRef = useRef(null);
    const lastSubmitRef = useRef(0);

    const cleanup = () => {
        try { recorderRef.current?.stop(); } catch { /* */ }
        try { streamRef.current?.getTracks().forEach((t) => t.stop()); } catch { /* */ }
        recorderRef.current = null;
        streamRef.current = null;
        chunksRef.current = [];
        if (pressTimerRef.current) { clearTimeout(pressTimerRef.current); pressTimerRef.current = null; }
        if (recordTimerRef.current) { clearTimeout(recordTimerRef.current); recordTimerRef.current = null; }
    };

    useEffect(() => () => cleanup(), []);

    const startRecording = async () => {
        if (recorderRef.current) return;
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
                cleanup();
                if (elapsed < MIN_RECORD_MS || blob.size < 800) {
                    setState('idle');
                    return;
                }
                await submitTranscript(blob, mimeToExt(rec.mimeType));
            };
            rec.start();
            startedAtRef.current = Date.now();
            setState('recording');
            // Hard 10 s ceiling
            recordTimerRef.current = setTimeout(() => stopRecording(), MAX_RECORD_MS);
        } catch (err) {
            cleanup();
            setState(err && err.name === 'NotAllowedError' ? 'blocked' : 'error');
            setTimeout(() => setState('idle'), 2200);
        }
    };

    const stopRecording = () => {
        if (!recorderRef.current) return;
        try { recorderRef.current.requestData?.(); } catch { /* */ }
        try { recorderRef.current.stop(); } catch { /* */ }
        // onstop handler takes it from here.
    };

    const submitTranscript = async (blob, ext) => {
        // Coalesce rapid submissions (defensive).
        const now = Date.now();
        if (now - lastSubmitRef.current < 700) return;
        lastSubmitRef.current = now;

        setState('transcribing');
        try {
            const form = new FormData();
            form.append('audio', blob, `voice.${ext}`);
            const r = await axios.post(`${API}/api/stt/transcribe`, form, {
                timeout: 20_000,
            });
            const text = ((r && r.data && r.data.text) || '').trim();
            if (!text) { setState('idle'); return; }
            // Broadcast on the party WS (best-effort).
            const ws = wsRef && wsRef.current;
            if (ws && ws.readyState === 1) {
                try {
                    ws.send(JSON.stringify({
                        type: 'voice_message',
                        text,
                        avatar_emoji: avatarEmoji || '',
                    }));
                } catch { /* */ }
            }
            // Local echo so the sender sees their own bubble immediately.
            try { onLocalEcho?.(text); } catch { /* */ }
            setState('idle');
        } catch {
            setState('error');
            setTimeout(() => setState('idle'), 2200);
        }
    };

    // ── Press handling (mouse / touch / D-pad OK / Enter) ─────────
    const handlePressStart = () => {
        if (state !== 'idle') return;
        setState('pressing');
        pressTimerRef.current = setTimeout(() => {
            startRecording();
        }, PRESS_INDICATOR_MS);
    };
    const handlePressEnd = () => {
        if (pressTimerRef.current) {
            clearTimeout(pressTimerRef.current);
            pressTimerRef.current = null;
        }
        if (state === 'recording') stopRecording();
        else if (state === 'pressing') setState('idle');
    };
    const handleKeyDown = (e) => {
        if (e.repeat) return;
        if (e.key !== 'Enter' && e.keyCode !== 23) return;
        e.preventDefault();
        handlePressStart();
    };
    const handleKeyUp = (e) => {
        if (e.key !== 'Enter' && e.keyCode !== 23) return;
        e.preventDefault();
        handlePressEnd();
    };

    const recording = state === 'recording';
    const transcribing = state === 'transcribing';
    const blocked = state === 'blocked';
    const error = state === 'error';

    return (
        <button
            data-testid="voice-reaction-btn"
            onMouseDown={handlePressStart}
            onMouseUp={handlePressEnd}
            onMouseLeave={handlePressEnd}
            onTouchStart={handlePressStart}
            onTouchEnd={handlePressEnd}
            onTouchCancel={handlePressEnd}
            onKeyDown={handleKeyDown}
            onKeyUp={handleKeyUp}
            data-focusable="true"
            data-focus-style="pill"
            tabIndex={0}
            aria-label={recording ? 'Recording — release to send' : 'Hold to send voice reaction'}
            style={{
                position: 'relative',
                display: 'inline-flex',
                alignItems: 'center',
                gap: 10,
                padding: '10px 16px',
                borderRadius: 999,
                background: recording
                    ? 'rgba(255, 80, 80, 0.92)'
                    : 'rgba(11, 19, 34, 0.72)',
                border: `1px solid ${recording ? 'rgba(255,120,120,0.9)' : 'rgba(93,200,255,0.4)'}`,
                color: '#fff',
                fontFamily: 'inherit',
                fontSize: 13,
                fontWeight: 600,
                letterSpacing: '0.06em',
                cursor: 'pointer',
                outline: 'none',
                userSelect: 'none',
                transition: 'background 120ms ease, transform 120ms ease',
                transform: state === 'pressing' ? 'scale(0.96)' : 'scale(1)',
                backdropFilter: 'blur(12px)',
                WebkitBackdropFilter: 'blur(12px)',
                ...(style || {}),
            }}
        >
            {blocked ? (
                <MicOff size={18} />
            ) : transcribing ? (
                <Loader2 size={18} className="animate-spin" />
            ) : (
                <Mic size={18} />
            )}
            <span style={{ textTransform: 'uppercase', fontFamily: 'monospace' }}>
                {recording ? 'LISTENING…'
                    : transcribing ? 'TRANSCRIBING…'
                    : blocked ? 'MIC BLOCKED'
                    : error ? 'TRY AGAIN'
                    : 'HOLD TO TALK'}
            </span>
            {recording && (
                <span
                    aria-hidden="true"
                    style={{
                        position: 'absolute',
                        inset: -4,
                        borderRadius: 999,
                        border: '2px solid rgba(255,120,120,0.7)',
                        animation: 'voice-pulse 1.1s ease-out infinite',
                        pointerEvents: 'none',
                    }}
                />
            )}
            <style>{`
@keyframes voice-pulse {
    0%   { transform: scale(1);    opacity: 0.85; }
    100% { transform: scale(1.18); opacity: 0; }
}
            `}</style>
        </button>
    );
}
