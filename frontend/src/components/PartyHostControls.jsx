import React, { useEffect, useRef, useState } from 'react';
import { Pause, Play, FastForward, RefreshCw, Lock, Captions, Unlock } from 'lucide-react';

/**
 * <PartyHostControls/> — the web counterpart of the native VLC
 * player's HOST PARTY MENU.  Mounted in `Player.jsx` whenever the
 * current member is the HOST of an active watch party.
 *
 * Layout (matches the Kotlin version exactly):
 *
 *   ⏸  PAUSE  ⏩  SKIP +30s  ⟳  CATCH UP  🔒  LOCK  💬  SUBS
 *
 * Behaviour
 * ---------
 *  • Hidden by default.  Tap the video or press OK to reveal the
 *    5-button bar.  Auto-hides after 6 s of inactivity.
 *  • PAUSE / RESUME — togglePlayPause, broadcast pause/resume
 *  • SKIP +30s     — currentTime += 30, broadcast play{position_ms}
 *  • CATCH UP      — broadcast play{position_ms} (forces every guest
 *                    to re-seek + resume at host's exact position).
 *                    Toast: "Re-syncing party…"
 *  • LOCK          — flips `locked=true`; consumes ALL clicks/keys
 *                    on the player surface until OK is held 2 s.
 *                    Emoji reactions (handled in Player.jsx) still
 *                    fire because they listen at the document level.
 *  • SUBS          — calls back to Player.jsx which opens the
 *                    subtitle picker.
 *
 * Hosts on the native HK1 box continue to use `VlcPlayerActivity`'s
 * Kotlin menu (same buttons, same actions).
 */
export default function PartyHostControls({
    paused,
    locked,
    onTogglePause,
    onSkip30,
    onCatchUp,
    onLock,
    onUnlock,
    onSubs,
    visible,
    onAutoHideRefresh,
}) {
    /* Track which menu button is "focused" for D-pad nav.  The bar
       has 5 buttons, navigate LEFT/RIGHT, OK to fire. */
    const [focusIdx, setFocusIdx] = useState(0);
    const btnRefs = useRef([]);

    // Auto-focus the first button when the menu becomes visible.
    useEffect(() => {
        if (!visible) return;
        const t = setTimeout(() => {
            btnRefs.current[focusIdx]?.focus({ preventScroll: true });
        }, 60);
        return () => clearTimeout(t);
    }, [visible, focusIdx]);

    if (locked) {
        // While locked the player surface is non-interactive except
        // for the 2-second OK-hold to unlock.  We render a small
        // bottom-center hint chip so the host knows how to escape.
        return (
            <div
                data-testid="party-host-locked-chip"
                style={{
                    position: 'absolute',
                    bottom: 'clamp(20px, 4vh, 36px)',
                    left: '50%',
                    transform: 'translateX(-50%)',
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 10,
                    padding: '10px 18px',
                    borderRadius: 999,
                    background: 'rgba(2,6,16,0.6)',
                    backdropFilter: 'blur(10px)',
                    WebkitBackdropFilter: 'blur(10px)',
                    border: '1px solid rgba(255,200,200,0.32)',
                    color: '#FFD1D1',
                    fontFamily: 'monospace',
                    fontSize: 11,
                    letterSpacing: '0.30em',
                    fontWeight: 700,
                    textTransform: 'uppercase',
                    pointerEvents: 'none',
                    zIndex: 60,
                }}
            >
                <Lock size={12} />
                LOCKED · HOLD OK 2 S TO UNLOCK
            </div>
        );
    }

    if (!visible) return null;

    const items = [
        {
            key: 'pause',
            label: paused ? 'RESUME' : 'PAUSE',
            icon: paused ? Play : Pause,
            onClick: onTogglePause,
        },
        {
            key: 'skip',
            label: 'SKIP +30s',
            icon: FastForward,
            onClick: onSkip30,
        },
        {
            key: 'catchup',
            label: 'CATCH UP',
            icon: RefreshCw,
            onClick: onCatchUp,
        },
        {
            key: 'lock',
            label: 'LOCK',
            icon: Lock,
            onClick: onLock,
        },
        {
            key: 'subs',
            label: 'SUBS',
            icon: Captions,
            onClick: onSubs,
        },
    ];

    return (
        <div
            data-testid="party-host-controls"
            onKeyDown={(e) => {
                onAutoHideRefresh?.();
                if (e.key === 'ArrowLeft') {
                    e.preventDefault();
                    setFocusIdx((i) => (i - 1 + items.length) % items.length);
                } else if (e.key === 'ArrowRight') {
                    e.preventDefault();
                    setFocusIdx((i) => (i + 1) % items.length);
                } else if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    items[focusIdx]?.onClick?.();
                }
            }}
            style={{
                position: 'absolute',
                bottom: 'clamp(28px, 6vh, 72px)',
                left: '50%',
                transform: 'translateX(-50%)',
                display: 'flex',
                gap: 10,
                padding: '10px 14px',
                borderRadius: 18,
                background: 'rgba(2,6,16,0.78)',
                backdropFilter: 'blur(14px)',
                WebkitBackdropFilter: 'blur(14px)',
                border: '1px solid rgba(93,200,255,0.20)',
                boxShadow: '0 16px 48px rgba(0,0,0,0.55)',
                zIndex: 60,
            }}
        >
            {items.map((it, i) => {
                const focused = i === focusIdx;
                const Icon = it.icon;
                return (
                    <button
                        key={it.key}
                        ref={(el) => { btnRefs.current[i] = el; }}
                        data-testid={`party-host-${it.key}`}
                        data-focusable="true"
                        tabIndex={0}
                        onClick={(e) => {
                            e.stopPropagation();
                            setFocusIdx(i);
                            it.onClick?.();
                        }}
                        onMouseEnter={() => setFocusIdx(i)}
                        onFocus={() => setFocusIdx(i)}
                        style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 8,
                            padding: '12px 18px',
                            borderRadius: 12,
                            background: focused
                                ? 'var(--vesper-blue, #5DC8FF)'
                                : 'rgba(13,19,34,0.94)',
                            border: focused
                                ? '2px solid var(--vesper-blue, #5DC8FF)'
                                : '2px solid rgba(93,200,255,0.18)',
                            color: focused ? '#06080F' : '#E6EAF2',
                            fontFamily: 'monospace',
                            fontSize: 12,
                            fontWeight: 800,
                            letterSpacing: '0.14em',
                            textTransform: 'uppercase',
                            cursor: 'pointer',
                            transition: 'transform 160ms ease, background 160ms ease, color 160ms ease',
                            transform: focused ? 'scale(1.06)' : 'scale(1.0)',
                            outline: 'none',
                            whiteSpace: 'nowrap',
                        }}
                    >
                        <Icon size={16} />
                        {it.label}
                    </button>
                );
            })}
        </div>
    );
}
