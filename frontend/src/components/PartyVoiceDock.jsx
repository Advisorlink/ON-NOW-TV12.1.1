/**
 * <PartyVoiceDock /> — bottom-right horizontal dock during party
 * playback.
 *
 * Layout (left → right):
 *   [avatar 1] [avatar 2] [...]  [☰ menu]
 *
 * Behaviour:
 *   • D-pad LEFT / RIGHT moves focus between the items.
 *   • Press-and-hold OK on a member avatar → records up to 10 s →
 *     /api/stt/transcribe → broadcasts the transcript as a
 *     `voice_message` over the party WS.  Sender sees the local
 *     echo immediately.
 *   • Single-tap OK on the menu button → opens the player chrome
 *     (top bar + control deck) via the `onOpenMenu` callback.
 *
 * Visuals: glassmorphism, cyan focus ring, red pulsing ring while
 * recording.  No emojis text on avatars — the gradient-on-glyph
 * avatar is the visual.
 */

import React, { useEffect, useRef, useState } from 'react';
import { Menu, Mic, Loader2, MicOff } from 'lucide-react';
import axios from 'axios';
import { AvatarCircle } from '@/lib/avatars';

const MAX_RECORD_MS = 10_000;
const MIN_RECORD_MS = 400;
const PRESS_INDICATOR_MS = 120;
const API = (process.env.REACT_APP_BACKEND_URL || '').replace(/\/$/, '');

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

export default function PartyVoiceDock({
    members,           // [{id, name, avatar, ready}]
    selfMemberId,      // current user's member id (so own avatar can record)
    selfAvatarEmoji,   // single glyph used as the broadcast avatar_emoji
    wsRef,
    onOpenMenu,        // () => void
    onLocalEcho,       // (text) => void
}) {
    // Filter out duplicates and sort so the current user is first.
    const list = (members || []).filter((m) => m && m.id);
    // Always-visible: render up to 4 avatars + menu.  If we have
    // more than 4 members, prefer the first 4 (host + earliest 3).
    const visible = list.slice(0, 4);

    // ── Focus state (D-pad LEFT/RIGHT cycles items) ────────────
    const dockRef = useRef(null);
    const itemRefs = useRef([]);
    const itemCount = visible.length + 1; // + menu button
    const [focusIdx, setFocusIdx] = useState(0);

    const moveFocus = (delta) => {
        setFocusIdx((cur) => {
            let next = cur + delta;
            if (next < 0) next = 0;
            if (next >= itemCount) next = itemCount - 1;
            const el = itemRefs.current[next];
            try { el?.focus?.({ preventScroll: true }); } catch { /* */ }
            return next;
        });
    };

    // Listen for arrow keys ONLY when the dock has focus.
    useEffect(() => {
        const dock = dockRef.current;
        if (!dock) return undefined;
        const onKey = (e) => {
            if (!dock.contains(document.activeElement)) return;
            if (e.key === 'ArrowLeft')  { e.preventDefault(); e.stopPropagation(); moveFocus(-1); }
            if (e.key === 'ArrowRight') { e.preventDefault(); e.stopPropagation(); moveFocus(1); }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [itemCount]);

    // v2.7.59 — Global "jump to dock" hotkey.  When the user is
    // NOT focused inside the dock, pressing D-pad RIGHT moves them
    // INTO the dock's first item.  Lets the user always reach the
    // dock from any focused element in the player without having
    // to physically navigate through the layout.
    useEffect(() => {
        const dock = dockRef.current;
        if (!dock) return undefined;
        const onJump = (e) => {
            if (dock.contains(document.activeElement)) return;
            if (e.key !== 'ArrowRight') return;
            const el = itemRefs.current[0];
            if (!el) return;
            // Only steal focus from elements that are NOT also
            // bottom-right (don't fight other dock-like widgets).
            try {
                el.focus({ preventScroll: true });
                setFocusIdx(0);
                e.preventDefault();
                e.stopPropagation();
            } catch { /* */ }
        };
        window.addEventListener('keydown', onJump, true);
        return () => window.removeEventListener('keydown', onJump, true);
    }, []);

    // ── Recording state shared by all avatar tiles ─────────────
    // (Only one mic recording at a time — when the user holds an
    // avatar, this state tracks it regardless of which avatar they
    // were on.)
    const [recState, setRecState] = useState('idle');
    // 'idle' | 'pressing' | 'recording' | 'transcribing' | 'blocked' | 'error'
    const recorderRef = useRef(null);
    const streamRef = useRef(null);
    const chunksRef = useRef([]);
    const startedAtRef = useRef(0);
    const pressTimerRef = useRef(null);
    const recordTimerRef = useRef(null);

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

    const submitTranscript = async (blob, ext) => {
        setRecState('transcribing');
        try {
            const form = new FormData();
            form.append('audio', blob, `voice.${ext}`);
            const r = await axios.post(`${API}/api/stt/transcribe`, form, {
                timeout: 20_000,
            });
            const text = ((r && r.data && r.data.text) || '').trim();
            if (text) {
                const ws = wsRef && wsRef.current;
                if (ws && ws.readyState === 1) {
                    try {
                        ws.send(JSON.stringify({
                            type: 'voice_message',
                            text,
                            avatar_emoji: selfAvatarEmoji || '',
                        }));
                    } catch { /* */ }
                }
                try { onLocalEcho?.(text); } catch { /* */ }
            }
            setRecState('idle');
        } catch {
            setRecState('error');
            setTimeout(() => setRecState('idle'), 2200);
        }
    };

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
                    setRecState('idle');
                    return;
                }
                await submitTranscript(blob, mimeToExt(rec.mimeType));
            };
            rec.start();
            startedAtRef.current = Date.now();
            setRecState('recording');
            recordTimerRef.current = setTimeout(() => stopRecording(), MAX_RECORD_MS);
        } catch (err) {
            cleanup();
            setRecState(err && err.name === 'NotAllowedError' ? 'blocked' : 'error');
            setTimeout(() => setRecState('idle'), 2200);
        }
    };
    const stopRecording = () => {
        if (!recorderRef.current) return;
        try { recorderRef.current.requestData?.(); } catch { /* */ }
        try { recorderRef.current.stop(); } catch { /* */ }
    };

    // Avatar press handlers (shared)
    const handleAvatarPressStart = () => {
        if (recState !== 'idle') return;
        setRecState('pressing');
        pressTimerRef.current = setTimeout(() => {
            startRecording();
        }, PRESS_INDICATOR_MS);
    };
    const handleAvatarPressEnd = () => {
        if (pressTimerRef.current) {
            clearTimeout(pressTimerRef.current);
            pressTimerRef.current = null;
        }
        if (recState === 'recording') stopRecording();
        else if (recState === 'pressing') setRecState('idle');
    };

    const labelForState = () => {
        if (recState === 'recording')    return 'LISTENING…';
        if (recState === 'transcribing') return 'TRANSCRIBING…';
        if (recState === 'blocked')      return 'MIC BLOCKED';
        if (recState === 'error')        return 'TRY AGAIN';
        return null;
    };
    const statusLabel = labelForState();

    return (
        <div
            ref={dockRef}
            data-testid="party-voice-dock"
            style={{
                position: 'fixed',
                right: '3vw',
                bottom: '8vh',
                display: 'flex',
                alignItems: 'center',
                gap: 10,
                padding: 8,
                background: 'rgba(8, 14, 26, 0.62)',
                borderRadius: 999,
                border: '1px solid rgba(93,200,255,0.30)',
                backdropFilter: 'blur(14px)',
                WebkitBackdropFilter: 'blur(14px)',
                zIndex: 96,
                pointerEvents: 'auto',
            }}
        >
            {visible.map((m, i) => (
                <AvatarTile
                    key={m.id}
                    member={m}
                    isSelf={m.id === selfMemberId}
                    isRecording={recState === 'recording' && m.id === selfMemberId}
                    isPressing={recState === 'pressing' && m.id === selfMemberId}
                    isFocused={focusIdx === i}
                    onPressStart={m.id === selfMemberId ? handleAvatarPressStart : null}
                    onPressEnd={m.id === selfMemberId ? handleAvatarPressEnd : null}
                    onFocus={() => setFocusIdx(i)}
                    ref={(el) => { itemRefs.current[i] = el; }}
                />
            ))}
            <MenuTile
                isFocused={focusIdx === visible.length}
                onClick={() => { onOpenMenu?.(); }}
                onFocus={() => setFocusIdx(visible.length)}
                ref={(el) => { itemRefs.current[visible.length] = el; }}
            />

            {/* Status pill (right of the dock) */}
            {statusLabel && (
                <div
                    style={{
                        position: 'absolute',
                        right: 0,
                        top: -38,
                        padding: '6px 12px',
                        borderRadius: 999,
                        background: recState === 'recording'
                            ? 'rgba(255, 80, 80, 0.92)'
                            : 'rgba(11, 19, 34, 0.92)',
                        border: '1px solid rgba(255,120,120,0.45)',
                        color: '#fff',
                        fontFamily: 'monospace',
                        fontSize: 11,
                        fontWeight: 700,
                        letterSpacing: '0.16em',
                        whiteSpace: 'nowrap',
                    }}
                >
                    {recState === 'recording' && <Mic size={12} style={{ display: 'inline', marginRight: 4, verticalAlign: '-2px' }} />}
                    {recState === 'blocked' && <MicOff size={12} style={{ display: 'inline', marginRight: 4, verticalAlign: '-2px' }} />}
                    {recState === 'transcribing' && <Loader2 size={12} className="animate-spin" style={{ display: 'inline', marginRight: 4, verticalAlign: '-2px' }} />}
                    {statusLabel}
                </div>
            )}

            <style>{`
@keyframes party-dock-pulse {
    0%   { transform: scale(1);    opacity: 0.85; }
    100% { transform: scale(1.22); opacity: 0; }
}
            `}</style>
        </div>
    );
}

const AvatarTile = React.forwardRef(function AvatarTile(
    { member, isSelf, isRecording, isPressing, isFocused, onPressStart, onPressEnd, onFocus },
    ref,
) {
    const interactive = !!onPressStart;
    return (
        <button
            ref={ref}
            type="button"
            data-testid={`party-dock-avatar-${member.id}`}
            tabIndex={0}
            data-focusable="true"
            data-focus-style="pill"
            onFocus={onFocus}
            onMouseDown={interactive ? onPressStart : undefined}
            onMouseUp={interactive ? onPressEnd : undefined}
            onMouseLeave={interactive ? onPressEnd : undefined}
            onTouchStart={interactive ? onPressStart : undefined}
            onTouchEnd={interactive ? onPressEnd : undefined}
            onTouchCancel={interactive ? onPressEnd : undefined}
            onKeyDown={(e) => {
                if (!interactive) return;
                if (e.repeat) return;
                if (e.key === 'Enter' || e.keyCode === 23) {
                    e.preventDefault();
                    onPressStart?.();
                }
            }}
            onKeyUp={(e) => {
                if (!interactive) return;
                if (e.key === 'Enter' || e.keyCode === 23) {
                    e.preventDefault();
                    onPressEnd?.();
                }
            }}
            aria-label={
                interactive
                    ? (isRecording ? 'Recording — release to send' : `Hold to send a voice message as ${member.name}`)
                    : `Party member ${member.name}`
            }
            style={{
                position: 'relative',
                width: 52,
                height: 52,
                padding: 0,
                borderRadius: '50%',
                background: 'transparent',
                border: 'none',
                cursor: interactive ? 'pointer' : 'default',
                outline: 'none',
                transform: isPressing ? 'scale(0.95)' : isFocused ? 'scale(1.06)' : 'scale(1)',
                transition: 'transform 130ms ease',
                userSelect: 'none',
            }}
        >
            <AvatarCircle avatarId={member.avatar} srcOverride={member.avatar_src} size={52} ring={isFocused} />
            {isSelf && (
                <div
                    style={{
                        position: 'absolute',
                        right: -2,
                        bottom: -2,
                        width: 18,
                        height: 18,
                        borderRadius: '50%',
                        background: isRecording ? '#FF5050' : 'rgba(11,19,34,0.92)',
                        border: '1.5px solid rgba(93,200,255,0.85)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        color: '#fff',
                    }}
                >
                    <Mic size={11} strokeWidth={2.6} />
                </div>
            )}
            {isRecording && (
                <span
                    aria-hidden="true"
                    style={{
                        position: 'absolute',
                        inset: -4,
                        borderRadius: '50%',
                        border: '2px solid rgba(255,90,90,0.85)',
                        animation: 'party-dock-pulse 1.05s ease-out infinite',
                        pointerEvents: 'none',
                    }}
                />
            )}
        </button>
    );
});

const MenuTile = React.forwardRef(function MenuTile(
    { isFocused, onClick, onFocus },
    ref,
) {
    return (
        <button
            ref={ref}
            type="button"
            data-testid="party-dock-menu-btn"
            tabIndex={0}
            data-focusable="true"
            data-focus-style="pill"
            onClick={onClick}
            onFocus={onFocus}
            onKeyDown={(e) => {
                if (e.key === 'Enter' || e.keyCode === 23) {
                    e.preventDefault();
                    onClick?.();
                }
            }}
            aria-label="Open player menu"
            style={{
                width: 52,
                height: 52,
                borderRadius: '50%',
                background: 'rgba(11,19,34,0.85)',
                border: '1px solid rgba(93,200,255,0.55)',
                color: '#fff',
                cursor: 'pointer',
                outline: 'none',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                transform: isFocused ? 'scale(1.06)' : 'scale(1)',
                transition: 'transform 130ms ease',
            }}
        >
            <Menu size={22} strokeWidth={2.2} />
        </button>
    );
});
