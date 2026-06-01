/**
 * <BootSplash/> — full-screen "ON NOW V2" welcome splash.
 *
 * v2.8.88 — Redesigned per user request:
 *   • Big "ON NOW" + accent "V2" wordmark with subtle glow.
 *   • "Welcome to ON NOW V2" tagline beneath.
 *   • Cinematic radial backdrop, no centered indeterminate spinner
 *     that "dragged down" (per user feedback the old progress bar
 *     looked weird).  Replaced with a low-key animated underline
 *     so there's still motion but no centered loader.
 *   • Shows on EVERY app open for ~2.2 s then fades out.  Capped
 *     at 2200ms hard so a broken signal never costs real time.
 */
import React, { useEffect, useState } from 'react';

export default function BootSplash({ minDurationMs = 1800, hardCapMs = 2200 }) {
    const [open, setOpen] = useState(true);
    const [leaving, setLeaving] = useState(false);

    useEffect(() => {
        // Always show for the min duration on every open, then fade.
        const fadeT = setTimeout(() => setLeaving(true), minDurationMs);
        const closeT = setTimeout(() => setOpen(false), hardCapMs);
        return () => {
            clearTimeout(fadeT);
            clearTimeout(closeT);
        };
    }, [minDurationMs, hardCapMs]);

    if (!open) return null;

    return (
        <div
            data-testid="boot-splash"
            className="fixed inset-0 flex flex-col items-center justify-center"
            style={{
                zIndex: 95,
                background:
                    'radial-gradient(ellipse at 50% 35%, #0e2548 0%, #050912 65%, #02030A 100%)',
                color: '#fff',
                gap: 18,
                opacity: leaving ? 0 : 1,
                transition: 'opacity 380ms ease-out',
            }}
        >
            {/* Cinematic ambient scan lines for a TV-channel feel */}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    inset: 0,
                    background:
                        'repeating-linear-gradient(0deg, rgba(255,255,255,0.012) 0 1px, transparent 1px 3px)',
                    pointerEvents: 'none',
                }}
            />

            {/* Brand wordmark */}
            <div
                className="vesper-splash-mark"
                style={{
                    fontFamily: 'inherit',
                    fontSize: 'clamp(56px, 9vw, 132px)',
                    fontWeight: 800,
                    letterSpacing: '-0.045em',
                    lineHeight: 1,
                    display: 'flex',
                    alignItems: 'baseline',
                    gap: 'clamp(12px, 1vw, 22px)',
                    textShadow: '0 8px 60px rgba(93,200,255,0.28)',
                    animation:
                        'vesper-splash-rise 700ms cubic-bezier(.16,1,.3,1) both',
                }}
            >
                <span>ON&nbsp;NOW</span>
                <span
                    style={{
                        color: 'var(--vesper-blue-bright, #5DC8FF)',
                        textShadow:
                            '0 0 24px rgba(93,200,255,0.55), 0 0 60px rgba(93,200,255,0.25)',
                    }}
                >
                    V2
                </span>
            </div>

            {/* Animated underline — a clean horizontal sweep, no
                centred dragging loader. */}
            <div
                style={{
                    width: 'clamp(180px, 18vw, 320px)',
                    height: 2,
                    background:
                        'linear-gradient(90deg, transparent 0%, rgba(93,200,255,0.85) 50%, transparent 100%)',
                    backgroundSize: '200% 100%',
                    animation: 'vesper-splash-sweep 1.6s ease-in-out infinite',
                    borderRadius: 999,
                    boxShadow: '0 0 12px rgba(93,200,255,0.45)',
                }}
            />

            {/* Tagline */}
            <div
                className="vesper-mono"
                style={{
                    fontSize: 'clamp(11px, 0.9vw, 14px)',
                    letterSpacing: '0.36em',
                    textTransform: 'uppercase',
                    color: 'rgba(255,255,255,0.65)',
                    animation:
                        'vesper-splash-fade 900ms 250ms ease-out both',
                }}
            >
                Welcome to ON&nbsp;NOW&nbsp;V2
            </div>

            <style>{`
                @keyframes vesper-splash-rise {
                    from { opacity: 0; transform: translateY(18px); letter-spacing: -0.02em; }
                    to   { opacity: 1; transform: translateY(0);    letter-spacing: -0.045em; }
                }
                @keyframes vesper-splash-fade {
                    from { opacity: 0; }
                    to   { opacity: 1; }
                }
                @keyframes vesper-splash-sweep {
                    0%   { background-position:  100% 0; opacity: 0.85; }
                    50%  { background-position:    0% 0; opacity: 1; }
                    100% { background-position: -100% 0; opacity: 0.85; }
                }
            `}</style>
        </div>
    );
}
