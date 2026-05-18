/**
 * <BootSplash/> — full-screen "ON NOW TV 2 is starting" splash
 * shown for the first ~2.5 s of every app session OR until the
 * Instant EPG bundle has been hydrated into localStorage,
 * whichever comes last (capped at ~6 s so a broken backend never
 * leaves the user stranded).
 *
 * Uses ON NOW TV V2's neon-blue palette and a soft pulsing logo
 * so it feels intentional rather than a stuck loading screen.
 *
 * Closes itself by listening for two signals:
 *   1. `vesper:bundle-ready` window event fired by `App.js` after
 *      `bootInstantBundle()` resolves.
 *   2. Hard cap timer (default 6 s).
 */
import React, { useEffect, useState } from 'react';
import { Tv2 } from 'lucide-react';

export default function BootSplash({ minDurationMs = 1800, hardCapMs = 6000 }) {
    const [open, setOpen] = useState(true);
    const [bundleReady, setBundleReady] = useState(false);
    const [minElapsed, setMinElapsed] = useState(false);

    useEffect(() => {
        const onReady = () => setBundleReady(true);
        window.addEventListener('vesper:bundle-ready', onReady);

        const minT = setTimeout(() => setMinElapsed(true), minDurationMs);
        const hardT = setTimeout(() => setOpen(false), hardCapMs);

        /* If the bundle was already applied before this mount
         * (e.g. fast localStorage hit), the event has fired
         * already.  Check the meta flag once at mount. */
        try {
            const raw = localStorage.getItem('onnowtv-instant-bundle-meta');
            if (raw && JSON.parse(raw)?.generated_at) setBundleReady(true);
        } catch { /* ignore */ }

        return () => {
            window.removeEventListener('vesper:bundle-ready', onReady);
            clearTimeout(minT);
            clearTimeout(hardT);
        };
    }, [minDurationMs, hardCapMs]);

    useEffect(() => {
        if (bundleReady && minElapsed) setOpen(false);
    }, [bundleReady, minElapsed]);

    if (!open) return null;

    return (
        <div
            data-testid="boot-splash"
            className="fixed inset-0 flex flex-col items-center justify-center"
            style={{
                zIndex: 95,
                background:
                    'radial-gradient(ellipse at 50% 30%, #0c1a36 0%, #06080F 60%, #03050C 100%)',
                color: '#fff',
                gap: 22,
            }}
        >
            {/* Brand mark */}
            <div
                style={{
                    width: 96,
                    height: 96,
                    borderRadius: 28,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background:
                        'linear-gradient(135deg, rgba(93,200,255,0.28) 0%, rgba(93,200,255,0.06) 100%)',
                    border: '1.5px solid rgba(93,200,255,0.45)',
                    boxShadow:
                        '0 0 0 1px rgba(255,255,255,0.04), 0 24px 60px rgba(93,200,255,0.25)',
                    animation: 'vesper-boot-pulse 1.6s ease-in-out infinite',
                }}
            >
                <Tv2 size={42} strokeWidth={1.8} color="var(--vesper-blue-bright)" />
            </div>

            <div
                style={{
                    fontFamily: 'inherit',
                    fontSize: 24,
                    fontWeight: 700,
                    letterSpacing: '-0.02em',
                    textShadow: '0 2px 24px rgba(93,200,255,0.4)',
                }}
            >
                ON&nbsp;NOW&nbsp;TV&nbsp;
                <span style={{ color: 'var(--vesper-blue-bright)' }}>2</span>
            </div>

            <div
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.28em',
                    textTransform: 'uppercase',
                    color: 'rgba(255,255,255,0.55)',
                }}
            >
                Loading guide&hellip;
            </div>

            {/* Indeterminate progress bar */}
            <div
                style={{
                    marginTop: 12,
                    width: 240,
                    height: 3,
                    borderRadius: 999,
                    background: 'rgba(255,255,255,0.06)',
                    overflow: 'hidden',
                }}
            >
                <div
                    style={{
                        width: '40%',
                        height: '100%',
                        borderRadius: 999,
                        background:
                            'linear-gradient(90deg, transparent 0%, var(--vesper-blue-bright) 50%, transparent 100%)',
                        animation: 'vesper-boot-slide 1.4s linear infinite',
                    }}
                />
            </div>

            <style>{`
                @keyframes vesper-boot-pulse {
                    0%, 100% { transform: scale(1); }
                    50%      { transform: scale(1.05); }
                }
                @keyframes vesper-boot-slide {
                    0%   { transform: translateX(-100%); }
                    100% { transform: translateX(350%); }
                }
            `}</style>
        </div>
    );
}
