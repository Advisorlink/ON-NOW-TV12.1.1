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
            style={{ paddingTop: 48, paddingBottom: 16 }}
        >
            <header
                className="flex items-end justify-between mb-5"
                style={{ paddingLeft: 180, paddingRight: 80 }}
            >
                <div className="flex items-baseline gap-4">
                    <span className="vesper-eyebrow">Browse</span>
                    <h2
                        className="vesper-display"
                        style={{
                            fontSize: 34,
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        By network
                    </h2>
                </div>
                <span
                    className="vesper-mono"
                    style={{
                        color: 'var(--vesper-text-3)',
                        fontSize: 11,
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {NETWORKS.length} networks
                </span>
            </header>

            <div
                className="vesper-shelf flex gap-6"
                style={{
                    paddingLeft: 180,
                    paddingRight: 180,
                    paddingTop: 28,
                    paddingBottom: 56,
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
                            width: 360,
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
                            className="absolute inset-0 flex items-center justify-center"
                            style={{
                                color: n.accent,
                                fontFamily: '"Geist", system-ui, sans-serif',
                                fontWeight: 800,
                                letterSpacing: '-0.04em',
                                fontSize:
                                    n.wordmark.length > 6
                                        ? 'clamp(34px, 3.6vw, 56px)'
                                        : 'clamp(48px, 4.8vw, 72px)',
                                textShadow:
                                    '0 4px 24px rgba(0,0,0,0.45), 0 1px 2px rgba(0,0,0,0.6)',
                            }}
                        >
                            {n.wordmark}
                        </div>

                        <div className="absolute inset-x-0 bottom-0 p-4">
                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 11,
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
