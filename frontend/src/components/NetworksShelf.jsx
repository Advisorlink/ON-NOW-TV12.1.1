import React from 'react';
import { useNavigate } from 'react-router-dom';
import { NETWORKS } from '@/lib/networks';

/**
 * "Browse by Network" — a horizontal rail of branded entry-point
 * tiles.  Each tile is a focusable D-pad target that routes to the
 * dedicated /networks/:slug catalogue page.  No third-party logos —
 * we use the network's own wordmark in their brand-derived colour.
 */
export default function NetworksShelf() {
    const navigate = useNavigate();

    return (
        <section
            data-testid="networks-shelf"
            className="relative w-full"
            style={{
                paddingTop: 'clamp(8px, 1vw, 16px)',
                paddingBottom: 0,
            }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{
                    paddingLeft: 'clamp(124px, 9.5vw, 180px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                }}
            >
                <div className="flex items-baseline gap-4 min-w-0">
                    <span className="vesper-eyebrow truncate">Browse</span>
                    <h2
                        className="vesper-display truncate"
                        style={{
                            fontSize: 'clamp(22px, 2.2vw, 34px)',
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        By network
                    </h2>
                </div>
                <span
                    className="vesper-mono shrink-0"
                    style={{
                        color: 'var(--vesper-text-3)',
                        fontSize: 'clamp(9px, 0.62vw, 11px)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {NETWORKS.length} networks
                </span>
            </header>

            <div
                className="vesper-shelf flex"
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    paddingLeft: 'clamp(124px, 9.5vw, 180px)',
                    paddingRight: 'clamp(124px, 9.5vw, 180px)',
                    paddingTop: 'clamp(10px, 1.1vw, 18px)',
                    paddingBottom: 'clamp(20px, 2vw, 36px)',
                }}
            >
                {NETWORKS.map((n) => (
                    <button
                        key={n.slug}
                        data-testid={`network-${n.slug}`}
                        data-focusable="true"
                        data-focus-style="tile"
                        tabIndex={0}
                        onClick={() => navigate(`/networks/${n.slug}`)}
                        className="group relative shrink-0 overflow-hidden rounded-2xl text-left"
                        style={{
                            width: 'clamp(180px, 15vw, 260px)',
                            aspectRatio: '16 / 9',
                            background: n.background,
                            border: '1px solid rgba(255,255,255,0.08)',
                        }}
                    >
                        <div
                            className="absolute inset-0 pointer-events-none"
                            style={{
                                background:
                                    'radial-gradient(circle at 30% 20%, rgba(255,255,255,0.18) 0%, rgba(255,255,255,0) 55%)',
                            }}
                        />
                        <div
                            className="absolute inset-0 flex items-center justify-center px-4 text-center"
                            style={{
                                color: n.accent,
                                fontFamily: '"Geist", system-ui, sans-serif',
                                fontWeight: 800,
                                letterSpacing: '-0.04em',
                                fontSize:
                                    n.wordmark.length > 6
                                        ? 'clamp(28px, 2.4vw, 48px)'
                                        : 'clamp(38px, 3.4vw, 64px)',
                                textShadow:
                                    '0 4px 24px rgba(0,0,0,0.45), 0 1px 2px rgba(0,0,0,0.6)',
                                lineHeight: 1.05,
                            }}
                        >
                            {n.wordmark}
                        </div>

                        <div
                            className="absolute inset-x-0 bottom-0"
                            style={{ padding: 'clamp(10px, 0.9vw, 16px)' }}
                        >
                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 'clamp(9px, 0.6vw, 11px)',
                                    letterSpacing: '0.22em',
                                    textTransform: 'uppercase',
                                    color: 'rgba(255,255,255,0.78)',
                                }}
                            >
                                {n.name} · top picks →
                            </div>
                        </div>
                    </button>
                ))}
            </div>
        </section>
    );
}
