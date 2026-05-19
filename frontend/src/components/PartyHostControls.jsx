import React, { useEffect, useRef } from 'react';
import { Pause, Play, FastForward, RefreshCw, Lock, Captions } from 'lucide-react';

/**
 * <PartyHostControls/> — H3 + R4 build (v2.6.86).
 *
 * Picks user's chosen pair from the mockup gallery:
 *   • Host menu  → "H3 · Curved Mac-style dock with depth"
 *     A floating glass dock at the bottom-centre with 5 3D bubble
 *     buttons.  Each bubble has a top sheen + inner shadow recess
 *     (sphere illusion) and lifts/scales 1.10× when focused, with
 *     a cyan-tooltip caption appearing below.
 *
 *   • Reaction remote → "R4 · Orbital ring"
 *     A persistent 200 px ring on the right edge of the screen
 *     with 4 emoji bubbles orbiting at N/E/S/W and a glowing
 *     "OK" core in the middle.  Teaches the host (and guest) what
 *     each D-pad arrow does without taking up an entire row of
 *     real-estate.  The compass arrows are pure decoration — the
 *     emoji positions themselves are the D-pad mapping.
 *
 * Behaviour for the menu is unchanged from v2.6.85:
 *   D-pad LEFT/RIGHT moves focus, OK fires, BACK closes (Player.jsx).
 *
 * The reaction remote is *informational* — it doesn't intercept
 * any keys, just shows the host what their D-pad does at a glance.
 * (Native VLC handles the actual emoji firing via the existing
 * HOLD-direction-1-second flow.)
 */
export default function PartyHostControls({
    paused,
    locked,
    onTogglePause,
    onSkip30,
    onCatchUp,
    onLock,
    onUnlock: _onUnlock,
    onSubs,
    visible,
    onAutoHideRefresh,
}) {
    const [focusIdx, setFocusIdx] = React.useState(0);
    const btnRefs = useRef([]);

    useEffect(() => {
        if (!visible) return undefined;
        const t = setTimeout(() => {
            btnRefs.current[focusIdx]?.focus({ preventScroll: true });
        }, 60);
        return () => clearTimeout(t);
    }, [visible, focusIdx]);

    if (locked) {
        return (
            <>
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
                        padding: '12px 22px',
                        borderRadius: 999,
                        background: 'rgba(2,6,16,0.75)',
                        backdropFilter: 'blur(16px)',
                        WebkitBackdropFilter: 'blur(16px)',
                        border: '1px solid rgba(255,200,200,0.30)',
                        boxShadow: '0 12px 36px rgba(0,0,0,0.6)',
                        color: '#FFD1D1',
                        fontFamily: 'monospace',
                        fontSize: 11,
                        fontWeight: 700,
                        letterSpacing: '0.32em',
                        textTransform: 'uppercase',
                        zIndex: 60,
                    }}
                >
                    <Lock size={14} />
                    Hold OK 2 s to unlock
                </div>
                <ReactionRemote />
            </>
        );
    }

    if (!visible) {
        return <ReactionRemote />;
    }

    const items = [
        { key: 'pause',   icon: paused ? Play : Pause,  label: paused ? 'Resume' : 'Pause', onClick: onTogglePause },
        { key: 'skip',    icon: FastForward,            label: 'Skip 30s',    onClick: onSkip30  },
        { key: 'catchup', icon: RefreshCw,              label: 'Catch Up',    onClick: onCatchUp },
        { key: 'lock',    icon: Lock,                   label: 'Lock',        onClick: onLock    },
        { key: 'subs',    icon: Captions,               label: 'Subtitles',   onClick: onSubs    },
    ];

    return (
        <>
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
                    bottom: 'clamp(36px, 8vh, 96px)',
                    left: '50%',
                    transform: 'translateX(-50%)',
                    /* Curved Mac-style dock — glassy frame, inner top
                     * sheen, soft outer shadow.  Houses the 5 bubble
                     * buttons. */
                    padding: '16px 28px',
                    borderRadius: 48,
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0.70) 0%, rgba(15,22,38,0.60) 100%)',
                    backdropFilter: 'blur(30px) saturate(180%)',
                    WebkitBackdropFilter: 'blur(30px) saturate(180%)',
                    border: '1px solid rgba(255,255,255,0.15)',
                    boxShadow:
                        '0 20px 50px rgba(0,0,0,0.55),' +
                        ' inset 0 1px 0 rgba(255,255,255,0.18)',
                    display: 'flex',
                    gap: 18,
                    alignItems: 'center',
                    zIndex: 60,
                    animation: 'vesper-host-controls-in 220ms cubic-bezier(.16,1,.3,1) both',
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
                            aria-label={it.label}
                            style={{
                                position: 'relative',
                                width: 80,
                                height: 80,
                                borderRadius: '50%',
                                /* 3D bubble: light source up-left + bottom
                                 * inner shadow gives the sphere illusion */
                                background: focused
                                    ? 'radial-gradient(circle at 30% 22%, rgba(255,255,255,0.55) 0%, rgba(255,255,255,0) 50%),' +
                                      ' linear-gradient(160deg, #5DC8FF 0%, #1a78b8 100%)'
                                    : 'radial-gradient(circle at 30% 22%, rgba(255,255,255,0.35) 0%, rgba(255,255,255,0) 38%),' +
                                      ' linear-gradient(160deg, #1d2842 0%, #0a1224 100%)',
                                boxShadow: focused
                                    ? 'inset 0 1px 0 rgba(255,255,255,0.45),' +
                                      ' inset 0 -12px 18px rgba(0,40,80,0.5),' +
                                      ' inset 0 3px 6px rgba(255,255,255,0.15),' +
                                      ' 0 0 0 1.5px rgba(255,255,255,0.18),' +
                                      ' 0 16px 40px rgba(93,200,255,0.55),' +
                                      ' 0 0 80px rgba(93,200,255,0.35)'
                                    : 'inset 0 1px 0 rgba(255,255,255,0.22),' +
                                      ' inset 0 -10px 16px rgba(0,0,0,0.45),' +
                                      ' inset 0 2px 4px rgba(255,255,255,0.08),' +
                                      ' 0 14px 32px rgba(0,0,0,0.55),' +
                                      ' 0 2px 4px rgba(0,0,0,0.4)',
                                color: focused ? '#FFFFFF' : '#C7CFDB',
                                border: 'none',
                                cursor: 'pointer',
                                outline: 'none',
                                transform: focused ? 'translateY(-4px) scale(1.10)' : 'scale(1)',
                                transition:
                                    'transform 220ms cubic-bezier(.16,1,.3,1),' +
                                    ' box-shadow 200ms ease, background 200ms ease, color 200ms ease',
                            }}
                        >
                            <Icon size={28} strokeWidth={2.4} />

                            {/* Floating caption under focused bubble */}
                            {focused && (
                                <span
                                    className="vesper-mono"
                                    style={{
                                        position: 'absolute',
                                        bottom: -28,
                                        left: '50%',
                                        transform: 'translateX(-50%)',
                                        fontSize: 10,
                                        letterSpacing: '0.32em',
                                        color: '#FFFFFF',
                                        fontWeight: 800,
                                        whiteSpace: 'nowrap',
                                        textTransform: 'uppercase',
                                        textShadow: '0 0 14px rgba(93,200,255,0.9)',
                                    }}
                                >
                                    {it.label}
                                </span>
                            )}
                        </button>
                    );
                })}
            </div>

            <ReactionRemote />

            <style>{`
@keyframes vesper-host-controls-in {
    from {
        opacity: 0;
        transform: translateX(-50%) translateY(18px) scale(0.94);
    }
    to {
        opacity: 1;
        transform: translateX(-50%) translateY(0) scale(1);
    }
}
            `}</style>
        </>
    );
}

/* ────────────────────────────────────────────────────────────────
 * <ReactionRemote/> — R4 Orbital
 *
 * Persistent (always visible during party) info-graphic that
 * shows what each D-pad direction does emoji-wise.  The emojis
 * themselves are the buttons — no separate arrow icons, no
 * "arrow + emoji stacked" pattern.  Cardinal positions match the
 * native VLC reaction hold-direction logic:
 *
 *   ▲ UP    → 😂 laugh-cry
 *   ▶ RIGHT → 🔥 fire
 *   ▼ DOWN  → 😱 shock
 *   ◀ LEFT  → ❤️ heart
 *
 * Why a permanent overlay?  The user feedback was that the host
 * "can't tell what the arrow keys do" — having this on screen at
 * all times means there's never a moment where they have to guess.
 * Sits on the RIGHT edge of the screen so it doesn't fight focus
 * with the dock at the bottom-centre.
 */
function ReactionRemote() {
    return (
        <div
            data-testid="party-reaction-remote"
            aria-hidden="true"
            style={{
                position: 'absolute',
                top: '50%',
                right: 'clamp(20px, 3vw, 40px)',
                transform: 'translateY(-50%)',
                width: 240,
                padding: '28px 18px',
                borderRadius: 28,
                background: 'rgba(6,8,15,0.65)',
                backdropFilter: 'blur(20px)',
                WebkitBackdropFilter: 'blur(20px)',
                border: '1px solid rgba(93,200,255,0.22)',
                boxShadow:
                    '0 24px 60px rgba(0,0,0,0.6),' +
                    ' inset 0 1px 0 rgba(255,255,255,0.10)',
                zIndex: 55,
                pointerEvents: 'none',
            }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.40em',
                    color: '#5DC8FF',
                    fontWeight: 800,
                    textAlign: 'center',
                    marginBottom: 16,
                    textShadow: '0 0 12px rgba(93,200,255,0.6)',
                }}
            >
                REACTIONS
            </div>

            <div
                style={{
                    position: 'relative',
                    width: 200,
                    height: 200,
                    margin: '0 auto',
                    borderRadius: '50%',
                    border: '1.5px solid rgba(93,200,255,0.25)',
                    boxShadow: 'inset 0 0 30px rgba(93,200,255,0.10)',
                }}
            >
                {/* Inner gradient halo */}
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute',
                        inset: 18,
                        borderRadius: '50%',
                        background:
                            'radial-gradient(circle, rgba(93,200,255,0.08) 0%, transparent 70%)',
                    }}
                />

                {/* 4 emoji bubbles riding the rim */}
                <OrbitEmoji emoji="😂" position="up" />
                <OrbitEmoji emoji="🔥" position="right" />
                <OrbitEmoji emoji="😱" position="down" />
                <OrbitEmoji emoji="❤️" position="left" />

                {/* Centre OK core — glowing cyan */}
                <div
                    style={{
                        position: 'absolute',
                        top: '50%',
                        left: '50%',
                        transform: 'translate(-50%, -50%)',
                        width: 80,
                        height: 80,
                        borderRadius: '50%',
                        background:
                            'radial-gradient(circle at 30% 22%, rgba(255,255,255,0.5) 0%, rgba(255,255,255,0) 50%),' +
                            ' linear-gradient(160deg, #5DC8FF 0%, #1a78b8 100%)',
                        color: '#06080F',
                        fontFamily: 'monospace',
                        fontSize: 15,
                        fontWeight: 900,
                        letterSpacing: '0.04em',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        boxShadow:
                            'inset 0 2px 0 rgba(255,255,255,0.5),' +
                            ' inset 0 -10px 18px rgba(0,40,80,0.5),' +
                            ' 0 0 40px rgba(93,200,255,0.9),' +
                            ' 0 12px 32px rgba(93,200,255,0.55)',
                    }}
                >
                    OK
                </div>
            </div>

            <div
                className="vesper-mono"
                style={{
                    fontSize: 9,
                    letterSpacing: '0.24em',
                    color: '#5b6473',
                    textAlign: 'center',
                    marginTop: 18,
                    textTransform: 'uppercase',
                }}
            >
                Hold D-pad to react
            </div>
        </div>
    );
}

function OrbitEmoji({ emoji, position }) {
    const offsetStyle = {
        up:    { top: -12, left: '50%', transform: 'translateX(-50%)' },
        right: { right: -12, top: '50%', transform: 'translateY(-50%)' },
        down:  { bottom: -12, left: '50%', transform: 'translateX(-50%)' },
        left:  { left: -12, top: '50%', transform: 'translateY(-50%)' },
    }[position];
    return (
        <div
            style={{
                position: 'absolute',
                width: 56,
                height: 56,
                borderRadius: '50%',
                background:
                    'radial-gradient(circle at 30% 22%, rgba(255,255,255,0.45) 0%, rgba(255,255,255,0) 50%),' +
                    ' rgba(20,30,50,0.85)',
                boxShadow:
                    'inset 0 1px 0 rgba(255,255,255,0.25),' +
                    ' 0 6px 16px rgba(0,0,0,0.5),' +
                    ' 0 0 20px rgba(93,200,255,0.30)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 30,
                ...offsetStyle,
            }}
        >
            {emoji}
        </div>
    );
}
