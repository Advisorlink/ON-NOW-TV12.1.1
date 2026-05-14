import React from 'react';
import { Radio, Loader2, CheckCircle2 } from 'lucide-react';

/**
 * Live TV boot loading screen — TV-Mate style.
 *
 * Renders a sequence of stages with their current status (pending /
 * active / done / failed).  Used while we authenticate + fetch the
 * categories + pre-warm the first category in the background.
 *
 * No glow, no drop shadow.  Single solid background + thin progress
 * bar.  Animations are limited to one spinner per active stage.
 */
export default function LiveTVBoot({ stages, message }) {
    const totalDone = stages.filter((s) => s.status === 'done').length;
    const pct = stages.length ? Math.round((totalDone / stages.length) * 100) : 0;

    return (
        <div
            data-testid="live-tv-boot"
            className="flex flex-col items-center justify-center"
            style={{
                minHeight: 'calc(100dvh - 40px)',
                padding: 'clamp(24px, 4vw, 56px)',
                gap: 32,
                background: 'var(--vesper-bg-0)',
            }}
        >
            <div className="flex flex-col items-center" style={{ gap: 12, maxWidth: 580, width: '100%' }}>
                <div
                    style={{
                        width: 72, height: 72, borderRadius: 999,
                        background: 'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb), 0.35), rgba(var(--vesper-blue-rgb), 0.10) 70%)',
                        border: '2px solid rgba(var(--vesper-blue-rgb), 0.6)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        color: 'var(--vesper-blue-bright)',
                    }}
                >
                    <Radio size={30} strokeWidth={1.6} />
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11, letterSpacing: '0.32em', textTransform: 'uppercase',
                        color: 'var(--vesper-blue-bright)',
                    }}
                >
                    Live TV · Setting up
                </div>
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(28px, 3.2vw, 44px)',
                        letterSpacing: '-0.025em',
                        lineHeight: 1.04,
                        textAlign: 'center',
                        color: 'var(--vesper-text)',
                    }}
                >
                    {message || 'Preparing your TV guide…'}
                </h1>
                <p
                    style={{
                        fontSize: 14,
                        color: 'var(--vesper-text-2)',
                        lineHeight: 1.45,
                        maxWidth: '48ch',
                        textAlign: 'center',
                    }}
                >
                    We're cataloguing your channels and pre-warming the guide so
                    zapping stays buttery-fast.  This only runs once each session.
                </p>
            </div>

            {/* Progress bar */}
            <div
                style={{
                    width: 'min(520px, 80vw)',
                    height: 6,
                    borderRadius: 99,
                    background: 'rgba(255,255,255,0.08)',
                    overflow: 'hidden',
                }}
            >
                <div
                    style={{
                        width: `${pct}%`,
                        height: '100%',
                        background: 'var(--vesper-blue-bright)',
                    }}
                />
            </div>

            {/* Stages list */}
            <ul
                data-testid="live-tv-boot-stages"
                style={{
                    listStyle: 'none', margin: 0, padding: 0,
                    width: 'min(520px, 80vw)',
                    display: 'flex', flexDirection: 'column', gap: 10,
                }}
            >
                {stages.map((s) => (
                    <li
                        key={s.id}
                        data-testid={`boot-stage-${s.id}-${s.status}`}
                        className="flex items-center"
                        style={{
                            gap: 14,
                            padding: '10px 14px',
                            borderRadius: 12,
                            background: s.status === 'active'
                                ? 'rgba(var(--vesper-blue-rgb), 0.10)'
                                : 'rgba(255,255,255,0.025)',
                            border: s.status === 'active'
                                ? '1px solid rgba(var(--vesper-blue-rgb), 0.45)'
                                : '1px solid rgba(255,255,255,0.05)',
                        }}
                    >
                        <span style={{
                            width: 22, height: 22, flexShrink: 0,
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            color: s.status === 'done' ? '#3ee07a'
                                : s.status === 'active' ? 'var(--vesper-blue-bright)'
                                : s.status === 'failed' ? '#FF6B6B'
                                : 'var(--vesper-text-3)',
                        }}>
                            {s.status === 'done' ? <CheckCircle2 size={18} strokeWidth={2.4} />
                                : s.status === 'active' ? <Loader2 size={16} className="vesper-spin" strokeWidth={2.2} />
                                : <span style={{
                                    width: 8, height: 8, borderRadius: '50%',
                                    background: 'currentColor', opacity: 0.5,
                                }} />}
                        </span>
                        <span style={{ flex: 1, minWidth: 0 }}>
                            <span style={{
                                display: 'block', fontSize: 14, fontWeight: 600,
                                color: s.status === 'pending' ? 'var(--vesper-text-3)' : 'var(--vesper-text)',
                            }}>
                                {s.label}
                            </span>
                            {s.detail && (
                                <span className="vesper-mono" style={{
                                    display: 'block', fontSize: 10,
                                    letterSpacing: '0.16em', textTransform: 'uppercase',
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
