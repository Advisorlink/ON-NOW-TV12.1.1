import React, { useEffect, useRef } from 'react';
import { Pause, Play, FastForward, RefreshCw, Lock, Captions } from 'lucide-react';

/**
 * <PartyHostControls/> — Vesper-signature host menu.
 *
 * REDESIGN (v2.6.85): the previous version was a utility-bar (flat
 * 5 buttons in a pill) that looked nothing like the rest of the app.
 * User feedback: "that design looks absolutely crap compared to the
 * rest of our design in the host player."
 *
 * New design:
 *   • Glass-morphism card centred at the bottom-third of the screen
 *   • Cyan neon-glow accent matching home screens / detail pages
 *   • Each button is a vertical icon-tile (big icon up top, small
 *     caption underneath) — feels like a STB control panel, not a
 *     web nav-bar
 *   • Focused tile scales to 1.08x, glows cyan, and the caption
 *     pulses gently — visually unmistakable on a 10-foot-away TV
 *   • Subtle eyebrow "HOST CONTROLS" at the top of the card so the
 *     host always knows *they* are in charge of this menu, not the
 *     guests
 *
 * Behaviour is unchanged from the previous version (D-pad LEFT/
 * RIGHT moves focus, OK fires, BACK closes via Player.jsx parent).
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

    /* Snap focus to the active tile whenever the menu opens. */
    useEffect(() => {
        if (!visible) return undefined;
        const t = setTimeout(() => {
            btnRefs.current[focusIdx]?.focus({ preventScroll: true });
        }, 60);
        return () => clearTimeout(t);
    }, [visible, focusIdx]);

    /* LOCK mode: tiny floating chip — host has frozen the
     * party surface, so we render no menu, just an "OK 2s to
     * unlock" hint. */
    if (locked) {
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
        );
    }

    if (!visible) return null;

    const items = [
        {
            key: 'pause',
            label: paused ? 'Resume' : 'Pause',
            icon: paused ? Play : Pause,
            tone: paused ? 'play' : 'pause',
            onClick: onTogglePause,
        },
        {
            key: 'skip',
            label: 'Skip 30s',
            icon: FastForward,
            onClick: onSkip30,
        },
        {
            key: 'catchup',
            label: 'Catch Up',
            icon: RefreshCw,
            onClick: onCatchUp,
        },
        {
            key: 'lock',
            label: 'Lock',
            icon: Lock,
            onClick: onLock,
        },
        {
            key: 'subs',
            label: 'Subtitles',
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
                bottom: 'clamp(36px, 8vh, 96px)',
                left: '50%',
                transform: 'translateX(-50%)',
                /* glass card */
                padding: '20px 24px 22px',
                borderRadius: 22,
                background:
                    'linear-gradient(135deg, rgba(6,8,15,0.88) 0%, rgba(15,22,38,0.82) 100%)',
                backdropFilter: 'blur(28px)',
                WebkitBackdropFilter: 'blur(28px)',
                border: '1px solid rgba(93,200,255,0.28)',
                boxShadow:
                    '0 28px 80px rgba(0,0,0,0.7),' +
                    '0 0 0 1px rgba(93,200,255,0.10),' +
                    '0 0 60px rgba(93,200,255,0.18)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: 14,
                zIndex: 60,
                animation: 'vesper-host-controls-in 220ms cubic-bezier(.16,1,.3,1) both',
            }}
        >
            <div
                className="vesper-mono"
                style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                    fontSize: 10,
                    letterSpacing: '0.42em',
                    color: '#5DC8FF',
                    fontWeight: 800,
                    textTransform: 'uppercase',
                }}
            >
                <span
                    aria-hidden="true"
                    style={{
                        width: 6, height: 6, borderRadius: '50%',
                        background: '#5DC8FF',
                        boxShadow: '0 0 10px rgba(93,200,255,0.8)',
                    }}
                />
                Host controls
            </div>

            <div style={{ display: 'flex', gap: 14 }}>
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
                                /* Tile shape — vertical icon + caption */
                                width: 96,
                                padding: '14px 8px 12px',
                                borderRadius: 16,
                                display: 'flex',
                                flexDirection: 'column',
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: 8,
                                background: focused
                                    ? 'linear-gradient(135deg, rgba(93,200,255,0.32) 0%, rgba(93,200,255,0.14) 100%)'
                                    : 'rgba(255,255,255,0.04)',
                                border: focused
                                    ? '1px solid rgba(93,200,255,0.7)'
                                    : '1px solid rgba(255,255,255,0.08)',
                                color: focused ? '#FFFFFF' : '#C7CFDB',
                                fontFamily: 'inherit',
                                fontSize: 11,
                                fontWeight: 700,
                                letterSpacing: '0.10em',
                                textTransform: 'uppercase',
                                cursor: 'pointer',
                                outline: 'none',
                                whiteSpace: 'nowrap',
                                transition:
                                    'transform 200ms cubic-bezier(.16,1,.3,1),' +
                                    ' background 200ms ease, border-color 200ms ease,' +
                                    ' color 200ms ease, box-shadow 200ms ease',
                                transform: focused ? 'translateY(-2px) scale(1.06)' : 'scale(1.0)',
                                boxShadow: focused
                                    ? '0 12px 32px rgba(93,200,255,0.4), 0 0 0 1px rgba(93,200,255,0.4) inset'
                                    : '0 4px 12px rgba(0,0,0,0.35)',
                            }}
                        >
                            <span
                                aria-hidden="true"
                                style={{
                                    width: 44, height: 44,
                                    borderRadius: 14,
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    background: focused
                                        ? 'rgba(255,255,255,0.18)'
                                        : 'rgba(93,200,255,0.10)',
                                    color: focused ? '#FFFFFF' : '#5DC8FF',
                                    boxShadow: focused
                                        ? '0 0 24px rgba(255,255,255,0.45)'
                                        : 'none',
                                    transition: 'background 200ms ease, color 200ms ease',
                                }}
                            >
                                <Icon size={20} strokeWidth={2.4} />
                            </span>
                            <span
                                className="vesper-mono"
                                style={{
                                    fontSize: 10,
                                    fontWeight: 800,
                                    letterSpacing: '0.18em',
                                }}
                            >
                                {it.label}
                            </span>
                        </button>
                    );
                })}
            </div>

            <div
                className="vesper-mono"
                style={{
                    fontSize: 9,
                    letterSpacing: '0.32em',
                    color: '#5b6473',
                    textTransform: 'uppercase',
                    marginTop: 4,
                }}
            >
                ◀ ▶ MOVE &nbsp;·&nbsp; OK CONFIRM &nbsp;·&nbsp; BACK CLOSE
            </div>

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
        </div>
    );
}
