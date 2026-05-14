import React from 'react';

/**
 * Live TV boot loading screen — LEAN MODE.
 *
 * No spinners, no gradients, no transitions.  Flat colours only —
 * a static progress bar fills as stages complete, and each stage row
 * shows a square status dot (grey / blue / green / red).  Designed
 * to take as little GPU/CPU as possible on the HK1 box.
 */
export default function LiveTVBoot({ stages }) {
    const totalDone = stages.filter((s) => s.status === 'done').length;
    const pct = stages.length ? Math.round((totalDone / stages.length) * 100) : 0;

    return (
        <div
            data-testid="live-tv-boot"
            style={{
                minHeight: 'calc(100dvh - 40px)',
                padding: '60px 48px',
                background: 'var(--vesper-bg-0)',
                color: 'var(--vesper-text)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 28,
            }}
        >
            <div style={{ textAlign: 'center', maxWidth: 560 }}>
                <div
                    style={{
                        fontFamily: 'monospace', fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)',
                        textTransform: 'uppercase',
                        marginBottom: 14,
                    }}
                >
                    Live TV · Setting up
                </div>
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 32, letterSpacing: '-0.02em',
                        lineHeight: 1.1, color: 'var(--vesper-text)',
                        marginBottom: 8,
                    }}
                >
                    Preparing your TV guide
                </h1>
                <p
                    style={{
                        fontSize: 13, color: 'var(--vesper-text-2)',
                        lineHeight: 1.4, maxWidth: '46ch', margin: '0 auto',
                    }}
                >
                    Caching every channel so zapping is instant.  This may take a
                    minute or two on the first run — please leave it open.
                </p>
            </div>

            {/* Static progress bar — no transition, flat fill */}
            <div
                style={{
                    width: 'min(480px, 80vw)', height: 4,
                    background: 'rgba(255,255,255,0.08)',
                    borderRadius: 0,
                }}
            >
                <div
                    style={{
                        width: `${pct}%`, height: '100%',
                        background: 'var(--vesper-blue-bright)',
                    }}
                />
            </div>

            <ul
                data-testid="live-tv-boot-stages"
                style={{
                    listStyle: 'none', margin: 0, padding: 0,
                    width: 'min(480px, 80vw)',
                    display: 'flex', flexDirection: 'column', gap: 6,
                }}
            >
                {stages.map((s) => (
                    <li
                        key={s.id}
                        data-testid={`boot-stage-${s.id}-${s.status}`}
                        style={{
                            display: 'flex', alignItems: 'center', gap: 12,
                            padding: '10px 12px',
                            background: 'rgba(255,255,255,0.025)',
                            borderRadius: 6,
                        }}
                    >
                        <span
                            style={{
                                width: 10, height: 10, flexShrink: 0,
                                background: s.status === 'done' ? '#3ee07a'
                                    : s.status === 'active' ? 'var(--vesper-blue-bright)'
                                    : s.status === 'failed' ? '#FF6B6B'
                                    : 'rgba(255,255,255,0.18)',
                            }}
                        />
                        <span style={{ flex: 1, minWidth: 0 }}>
                            <span style={{
                                display: 'block', fontSize: 13, fontWeight: 600,
                                color: s.status === 'pending' ? 'var(--vesper-text-3)' : 'var(--vesper-text)',
                            }}>
                                {s.label}
                            </span>
                            {s.detail && (
                                <span style={{
                                    display: 'block',
                                    fontSize: 11,
                                    color: s.status === 'failed' ? '#FF6B6B' : 'var(--vesper-text-3)',
                                    marginTop: 2,
                                }}>
                                    {s.detail}
                                </span>
                            )}
                        </span>
                    </li>
                ))}
            </ul>
        </div>
    );
}
