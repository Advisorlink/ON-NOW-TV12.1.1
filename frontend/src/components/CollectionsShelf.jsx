import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { STUDIOS } from '@/lib/collections';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';

const ART_KEY = 'collections:logos:v2';
const ART_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/** Logo + backdrop art for every studio / box set (7-day cached). */
export function useCollectionArt() {
    const [art, setArt] = useState(() => cache.get(ART_KEY)?.value || {});
    useEffect(() => {
        const c = cache.get(ART_KEY);
        if (c && !cache.isStale(c, ART_TTL_MS)) return;
        (async () => {
            try {
                const r = await fetch(`${API}/collections/logos`);
                if (!r.ok) return;
                const data = (await r.json())?.data || {};
                cache.set(ART_KEY, data);
                setArt(data);
            } catch { /* keep cached */ }
        })();
    }, []);
    return art;
}

/** "Studios" rail — under "By network" on Home. */
export default function CollectionsShelf() {
    const navigate = useNavigate();
    const art = useCollectionArt();

    return (
        <section
            data-testid="collections-shelf"
            className="relative w-full vesper-shelf-section"
            style={{ paddingTop: 'clamp(12px, 1.4vw, 28px)', paddingBottom: 'clamp(18px, 1.8vw, 32px)' }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{ paddingLeft: 'clamp(92px, 6.5vw, 132px)', paddingRight: 'clamp(40px, 4.2vw, 80px)' }}
            >
                <div className="flex items-baseline gap-4 min-w-0">
                    <span className="vesper-eyebrow truncate">Browse</span>
                    <h2
                        className="vesper-display truncate"
                        style={{ fontSize: 'clamp(22px, 2.2vw, 34px)', letterSpacing: '-0.025em', color: 'var(--vesper-text)' }}
                    >
                        Studios
                    </h2>
                </div>
                <span
                    className="vesper-mono shrink-0"
                    style={{ color: 'var(--vesper-text-3)', fontSize: 'clamp(9px, 0.62vw, 11px)', letterSpacing: '0.22em', textTransform: 'uppercase' }}
                >
                    {STUDIOS.length} studios
                </span>
            </header>

            <div
                className="vesper-shelf flex"
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                    paddingTop: 'clamp(14px, 1.4vw, 22px)',
                    paddingBottom: 'clamp(14px, 1.4vw, 24px)',
                }}
            >
                {STUDIOS.map((c) => (
                    <CollectionTile key={c.slug} col={c} art={art[c.slug]} onClick={() => navigate(`/collections/${c.slug}`)} />
                ))}
            </div>
        </section>
    );
}

/* Logo rendering: TMDB studio marks are drawn for white paper, so the
   backend samples each PNG — 'invert' (black script → white), 'plate'
   (dark but colourful → lifted with a soft white glow), 'none' (keeps
   its brand colours).  Box-set title treatments are used as-is. */
function logoFilter(treatment) {
    if (treatment === 'invert') return 'invert(1) drop-shadow(0 4px 14px rgba(0,0,0,0.7))';
    if (treatment === 'plate') return 'drop-shadow(0 0 8px rgba(255,255,255,0.9)) drop-shadow(0 0 22px rgba(255,255,255,0.55)) drop-shadow(0 4px 14px rgba(0,0,0,0.7))';
    return 'drop-shadow(0 4px 14px rgba(0,0,0,0.7))';
}

export function CollectionTile({ col, art, onClick, width = 'clamp(200px, 15.6vw, 300px)', testId, extraProps = {}, saved = false }) {
    const logo = art?.logo;
    const backdrop = art?.backdrop;
    return (
        <button
            type="button"
            data-testid={testId || `collection-${col.slug}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onClick}
            aria-label={col.name}
            className="relative shrink-0 overflow-hidden text-left"
            style={{
                width,
                aspectRatio: '16 / 9',
                borderRadius: 14,
                background: `linear-gradient(135deg, #0b0f1a 0%, #111a2c 55%, ${col.accent}33 100%)`,
                border: '1px solid rgba(255,255,255,0.08)',
                boxShadow: '0 12px 30px rgba(0,0,0,0.45)',
                cursor: 'pointer',
            }}
            {...extraProps}
        >
            {backdrop && (
                <img
                    src={backdrop}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                    style={{ opacity: 0.55, filter: 'saturate(0.9)' }}
                />
            )}
            <div
                className="absolute inset-0"
                style={{ background: 'linear-gradient(180deg, rgba(5,8,15,0.15) 0%, rgba(5,8,15,0.45) 60%, rgba(5,8,15,0.85) 100%)' }}
            />
            {logo ? (
                <img
                    src={logo}
                    alt={col.name}
                    loading="lazy"
                    decoding="async"
                    className="absolute"
                    style={{
                        left: '14%',
                        top: '18%',
                        width: '72%',
                        height: '58%',
                        objectFit: 'contain',
                        filter: logoFilter(art?.logo_treatment),
                    }}
                />
            ) : (
                <span
                    className="absolute vesper-display"
                    style={{
                        left: 16,
                        right: 16,
                        top: '34%',
                        textAlign: 'center',
                        fontSize: 'clamp(16px, 1.3vw, 24px)',
                        fontWeight: 800,
                        letterSpacing: '-0.02em',
                        color: '#fff',
                        textShadow: '0 2px 12px rgba(0,0,0,0.8)',
                    }}
                >
                    {col.name}
                </span>
            )}
            <span
                className="absolute vesper-mono"
                style={{
                    left: 12,
                    bottom: 10,
                    fontSize: 'clamp(9px, 0.62vw, 11px)',
                    letterSpacing: '0.2em',
                    textTransform: 'uppercase',
                    color: 'rgba(255,255,255,0.75)',
                }}
            >
                {col.name}
            </span>
            {saved && (
                <span
                    data-testid={`collection-${col.slug}-saved`}
                    className="absolute vesper-mono"
                    style={{
                        right: 10,
                        top: 10,
                        fontSize: 10,
                        letterSpacing: '0.16em',
                        textTransform: 'uppercase',
                        padding: '3px 8px',
                        borderRadius: 999,
                        background: 'rgba(93,200,255,0.9)',
                        color: '#06080f',
                        fontWeight: 700,
                    }}
                >
                    ✓ Saved
                </span>
            )}
            <span className="absolute inset-0 pointer-events-none" style={{ boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.10), inset 0 -1px 0 rgba(0,0,0,0.45)' }} />
        </button>
    );
}
