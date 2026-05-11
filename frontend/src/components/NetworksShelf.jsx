import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { NETWORKS } from '@/lib/networks';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';
import * as img from '@/lib/img';

/**
 * "Browse by Network" — premium tile rail.
 *
 * Tiles use the network's real wordmark logo, served via TMDB's
 * watch-provider asset CDN (cached server-side for 24 h and locally
 * for 7 days).  Each tile is a dark glass plate with the brand color
 * as a subtle inner glow + the logo centred on a square plate so all
 * six wordmarks share the same optical weight regardless of source.
 */
const LOGOS_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export default function NetworksShelf() {
    const navigate = useNavigate();
    const [logos, setLogos] = useState(() => {
        const c = cache.get('networks:logos');
        return c?.value || {};
    });

    useEffect(() => {
        const c = cache.get('networks:logos');
        if (c && !cache.isStale(c, LOGOS_TTL_MS)) return;
        (async () => {
            try {
                const r = await fetch(`${API}/networks/logos`);
                if (!r.ok) return;
                const json = await r.json();
                const data = json?.data || {};
                cache.set('networks:logos', data);
                setLogos(data);
            } catch {
                /* keep cached or empty */
            }
        })();
    }, []);

    return (
        <section
            data-testid="networks-shelf"
            className="relative w-full vesper-shelf-section"
            style={{
                paddingTop: 'clamp(28px, 3vw, 56px)',
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
                    paddingTop: 'clamp(14px, 1.4vw, 22px)',
                    paddingBottom: 'clamp(14px, 1.4vw, 24px)',
                }}
            >
                {NETWORKS.map((n) => (
                    <NetworkTile
                        key={n.slug}
                        net={n}
                        logo={logos[n.slug]?.logo}
                        onClick={() => navigate(`/networks/${n.slug}`)}
                    />
                ))}
            </div>
        </section>
    );
}

function NetworkTile({ net, logo, onClick }) {
    return (
        <button
            data-testid={`network-${net.slug}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onClick}
            className="group relative shrink-0 overflow-hidden text-left"
            style={{
                width: 'clamp(180px, 15vw, 260px)',
                aspectRatio: '16 / 9',
                borderRadius: 18,
                background:
                    'linear-gradient(155deg, #0E1422 0%, #0A0F1A 60%, #06080F 100%)',
                border: '1px solid rgba(255,255,255,0.06)',
                boxShadow:
                    '0 14px 30px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.04)',
            }}
        >
            {/* Brand-tinted inner glow */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background: `
                        radial-gradient(circle at 28% 18%, ${net.accent}33 0%, transparent 55%),
                        radial-gradient(circle at 78% 88%, ${net.accent}1F 0%, transparent 60%)
                    `,
                }}
            />

            {/* Subtle diagonal sheen */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(135deg, rgba(255,255,255,0.08) 0%, rgba(255,255,255,0) 38%, rgba(255,255,255,0.04) 100%)',
                }}
            />

            {/* Logo plate */}
            <div className="absolute inset-0 flex items-center justify-center p-5">
                {logo ? (
                    <img
                        src={img.poster(logo)}
                        alt={net.name}
                        loading="lazy"
                        decoding="async"
                        style={{
                            maxWidth: '78%',
                            maxHeight: '58%',
                            objectFit: 'contain',
                            filter:
                                'drop-shadow(0 4px 14px rgba(0,0,0,0.55)) drop-shadow(0 1px 1px rgba(0,0,0,0.7))',
                        }}
                    />
                ) : (
                    <span
                        style={{
                            color: net.accent,
                            fontFamily: '"Geist", system-ui, sans-serif',
                            fontWeight: 800,
                            letterSpacing: '-0.04em',
                            fontSize:
                                net.wordmark.length > 6
                                    ? 'clamp(26px, 2.2vw, 44px)'
                                    : 'clamp(34px, 3vw, 56px)',
                            textShadow:
                                '0 4px 16px rgba(0,0,0,0.55)',
                        }}
                    >
                        {net.wordmark}
                    </span>
                )}
            </div>

            {/* Bottom accent rule — animates in on focus via the
               theme's [data-focus-style='tile'] focus rule. */}
            <div
                className="absolute"
                style={{
                    left: 14,
                    right: 14,
                    bottom: 10,
                    height: 2,
                    borderRadius: 999,
                    background: `linear-gradient(90deg, ${net.accent}00 0%, ${net.accent}88 50%, ${net.accent}00 100%)`,
                    opacity: 0.6,
                }}
            />
        </button>
    );
}
