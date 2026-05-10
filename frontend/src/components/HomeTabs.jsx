import React from 'react';

/**
 * Pill-style segmented control for the Home tabs.  D-pad focusable,
 * fully responsive, and keeps the visual language consistent with the
 * rest of Vesper's blue-accent UI.
 */
export default function HomeTabs({ value, onChange }) {
    const tabs = [
        { id: 'all', label: 'All' },
        { id: 'series', label: 'TV Shows' },
        { id: 'movie', label: 'Movies' },
    ];

    return (
        <div
            data-testid="home-tabs"
            className="flex items-center"
            style={{
                paddingLeft: 'clamp(124px, 9.5vw, 180px)',
                paddingRight: 'clamp(40px, 4.2vw, 80px)',
                paddingTop: 'clamp(8px, 1vw, 16px)',
                paddingBottom: 'clamp(4px, 0.5vw, 8px)',
                gap: 'clamp(8px, 0.7vw, 12px)',
            }}
        >
            <div
                className="vesper-mono shrink-0"
                style={{
                    fontSize: 'clamp(10px, 0.72vw, 12px)',
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-text-3)',
                    marginRight: 'clamp(12px, 1vw, 20px)',
                }}
            >
                Browse
            </div>
            {tabs.map((t) => {
                const active = value === t.id;
                return (
                    <button
                        key={t.id}
                        data-testid={`home-tab-${t.id}`}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => onChange(t.id)}
                        className="font-sans font-semibold rounded-full"
                        style={{
                            height: 'clamp(36px, 3vw, 44px)',
                            paddingLeft: 'clamp(16px, 1.4vw, 22px)',
                            paddingRight: 'clamp(16px, 1.4vw, 22px)',
                            fontSize: 'clamp(13px, 0.95vw, 15px)',
                            letterSpacing: '-0.01em',
                            background: active
                                ? 'var(--vesper-blue)'
                                : 'rgba(255,255,255,0.04)',
                            color: active
                                ? 'var(--vesper-bg-0)'
                                : 'var(--vesper-text-2)',
                            border: active
                                ? '1px solid transparent'
                                : '1px solid rgba(255,255,255,0.08)',
                            transition:
                                'background-color 180ms ease, color 180ms ease',
                        }}
                    >
                        {t.label}
                    </button>
                );
            })}
        </div>
    );
}
