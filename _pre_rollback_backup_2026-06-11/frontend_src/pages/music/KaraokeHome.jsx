// ON NOW TV Tunes — Karaoke "Coming Soon" placeholder.
//
// v2.10.10 — Karaoke is parked behind a Coming Soon screen until
// the native AudioTrack low-latency receiver lands.  Keeps the
// route mounted (so nav focus + deep links still work) but shows
// a calm full-page splash instead of any partial feature surface.

import React from 'react';
import SpinningLogo from '@/components/SpinningLogo';

export default function KaraokeHome() {
    return (
        <div
            className="tunes-page"
            data-testid="karaoke-coming-soon"
            style={{
                minHeight: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                paddingInline: 'clamp(48px, 6vw, 120px)',
                paddingBlock: 'clamp(64px, 8vh, 120px)',
                textAlign: 'center',
            }}
        >
            <div
                style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 28,
                    maxWidth: 640,
                }}
            >
                <SpinningLogo size={72} speedMs={2400} />

                <p
                    style={{
                        fontFamily: 'Geist Mono, monospace',
                        fontSize: 12,
                        letterSpacing: '0.42em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue, #5DC8FF)',
                        margin: 0,
                    }}
                >
                    On Now Tunes · Karaoke
                </p>

                <h1
                    style={{
                        fontFamily: 'Geist, system-ui, sans-serif',
                        fontWeight: 800,
                        fontSize: 'clamp(44px, 5vw, 72px)',
                        letterSpacing: '-0.035em',
                        lineHeight: 0.98,
                        color: 'var(--vesper-text, #f5f8ff)',
                        margin: 0,
                    }}
                >
                    Coming Soon
                </h1>

                <p
                    style={{
                        fontFamily: 'Geist, system-ui, sans-serif',
                        fontSize: 'clamp(15px, 1.05vw, 18px)',
                        color: 'var(--vesper-text-2, #b0bacc)',
                        lineHeight: 1.55,
                        margin: 0,
                        maxWidth: 520,
                    }}
                >
                    We&apos;re tuning the karaoke engine.  Low-latency
                    microphone routing, real-time lyric sync, and party
                    mode are all on the way.
                </p>

                <span
                    style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 10,
                        marginTop: 4,
                        paddingInline: 16,
                        paddingBlock: 8,
                        borderRadius: 999,
                        background: 'rgba(93,200,255,0.08)',
                        border: '1px solid rgba(93,200,255,0.22)',
                        color: 'var(--vesper-blue, #5DC8FF)',
                        fontFamily: 'Geist Mono, monospace',
                        fontSize: 11,
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    Stay Tuned
                </span>
            </div>
        </div>
    );
}
