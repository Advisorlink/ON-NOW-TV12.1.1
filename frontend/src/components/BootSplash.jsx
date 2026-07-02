/**
 * <BootSplash/> — full-screen welcome splash.
 *
 * v2.10.70 — Host-aware branding.  Standalone Kids APK shows
 *   "ON NOW K2" wordmark with a warm sunshine-yellow accent mark
 *   and the tagline "Welcome to ON NOW Kids".  Every other context
 *   (Vesper / Tunes / FTA / browser) keeps the cyan "ON NOW V2"
 *   wordmark.  Per user spec: *"On Now Kids when it starts, not
 *   On Now TV2.  And also have a logo, a different logo.  Maybe
 *   K2 for the logo."*
 *
 * v2.8.88 — Original "ON NOW V2" splash design:
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
import { isKidsApp, isMusicApp } from '@/lib/profiles';

export default function BootSplash({ minDurationMs = 1800, hardCapMs = 2200 }) {
    const kids = isKidsApp();
    // v2.12.9 — Music splash: same design, wordmark reads
    // "ON NOW V2 🎶" and the tagline welcomes to V2 Music.
    const music = !kids && isMusicApp();
    const accentMark = kids ? 'K2' : 'V2';
    const tagline    = kids ? 'Welcome to ON\u00A0NOW\u00A0Kids'
        : music ? 'Welcome to ON\u00A0NOW\u00A0V2\u00A0Music'
        : 'Welcome to ON\u00A0NOW\u00A0V2';
    // Sunshine-yellow accent for Kids, the existing cyan for V2.
    const accentColor       = kids ? '#FFD24A' : 'var(--vesper-blue-bright, #5DC8FF)';
    const accentGlow24      = kids ? 'rgba(255,210,74,0.55)' : 'rgba(93,200,255,0.55)';
    const accentGlow60      = kids ? 'rgba(255,210,74,0.25)' : 'rgba(93,200,255,0.25)';
    const wordmarkShadow    = kids ? '0 8px 60px rgba(255,210,74,0.28)' : '0 8px 60px rgba(93,200,255,0.28)';
    const sweepColor        = kids ? 'rgba(255,210,74,0.85)' : 'rgba(93,200,255,0.85)';
    const sweepShadow       = kids ? '0 0 12px rgba(255,210,74,0.45)' : '0 0 12px rgba(93,200,255,0.45)';
    // Warmer backdrop for Kids so the splash matches the rest of
    // the kid-safe theme (grape/berry rather than blue navy).
    const backdrop = kids
        ? 'radial-gradient(ellipse at 50% 35%, #3c1f5e 0%, #0c0717 65%, #050309 100%)'
        : 'radial-gradient(ellipse at 50% 35%, #0e2548 0%, #050912 65%, #02030A 100%)';

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
                background: backdrop,
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
                    textShadow: wordmarkShadow,
                    animation:
                        'vesper-splash-rise 700ms cubic-bezier(.16,1,.3,1) both',
                }}
            >
                <span>ON&nbsp;NOW</span>
                <span
                    style={{
                        color: accentColor,
                        textShadow:
                            `0 0 24px ${accentGlow24}, 0 0 60px ${accentGlow60}`,
                    }}
                >
                    {accentMark}
                </span>
                {music && (
                    <span
                        aria-hidden="true"
                        style={{
                            fontSize: '0.62em',
                            textShadow: 'none',
                            transform: 'translateY(-0.08em)',
                        }}
                    >
                        {'\u{1F3B6}'}
                    </span>
                )}
            </div>

            {/* Animated underline — a clean horizontal sweep, no
                centred dragging loader. */}
            <div
                data-keep-anim="true"
                style={{
                    width: 'clamp(180px, 18vw, 320px)',
                    height: 2,
                    background:
                        `linear-gradient(90deg, transparent 0%, ${sweepColor} 50%, transparent 100%)`,
                    backgroundSize: '200% 100%',
                    animation: 'vesper-splash-sweep 1.6s ease-in-out infinite',
                    borderRadius: 999,
                    boxShadow: sweepShadow,
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
                {tagline}
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
