import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { COLLECTIONS } from '@/lib/collections';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';

const ART_KEY = 'collections:logos:v1';
const ART_TTL_MS = 7 * 24 * 60 * 60 * 1000;

/** "Collections" rail — studios + franchises, under "By network". */
export default function CollectionsShelf() {
    const navigate = useNavigate();
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
                        Collections
                    </h2>
                </div>
                <span
                    className="vesper-mono shrink-0"
                    style={{ color: 'var(--vesper-text-3)', fontSize: 'clamp(9px, 0.62vw, 11px)', letterSpacing: '0.22em', textTransform: 'uppercase' }}
                >
                    {COLLECTIONS.length} collections
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
                {COLLECTIONS.map((c) => (
                    <CollectionTile key={c.slug} col={c} art={art[c.slug]} onClick={() => navigate(`/collections/${c.slug}`)} />
                ))}
            </div>
        </section>
    );
}

function CollectionTile({ col, art, onClick }) {
    const logo = art?.logo;
    const wordmark = art?.logo_style === 'wordmark';
    return (
        <button
            data-testid={`collection-${col.slug}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onClick}
            aria-label={col.name}
            className="relative shrink-0 overflow-hidden text-left"
            style={{
                width: 'clamp(220px, 18vw, 310px)',
                aspectRatio: '16 / 9',
                borderRadius: 20,
                background: `linear-gradient(135deg, #070a12 0%, #0d1424 60%, ${col.accent}33 100%)`,
                border: '1px solid rgba(255,255,255,0.08)',
                boxShadow: '0 14px 30px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.05)',
            }}
        >
            {art?.backdrop && (
                <img
                    src={art.backdrop}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                    style={{ filter: 'saturate(0.9) brightness(0.55)' }}
                />
            )}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{ background: 'linear-gradient(180deg, rgba(5,7,13,0.15) 0%, rgba(5,7,13,0.55) 100%)' }}
            />
            {logo ? (
                wordmark ? (
                    // Studio marks are drawn for white paper (black
                    // Disney/Pixar script, filled Marvel/DC shapes) →
                    // a soft white plate keeps every one legible.
                    <div
                        className="absolute flex items-center justify-center"
                        style={{
                            left: '16%',
                            right: '16%',
                            top: '24%',
                            bottom: '26%',
                            borderRadius: 14,
                            background: 'rgba(255,255,255,0.94)',
                            boxShadow: '0 10px 30px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.9)',
                            padding: '6% 7%',
                        }}
                    >
                        <img
                            src={logo}
                            alt={col.name}
                            loading="lazy"
                            decoding="async"
                            style={{ width: '100%', height: '100%', objectFit: 'contain' }}
                        />
                    </div>
                ) : (
                    <img
                        src={logo}
                        alt={col.name}
                        loading="lazy"
                        decoding="async"
                        className="absolute"
                        style={{
                            left: '14%',
                            top: '20%',
                            width: '72%',
                            height: '60%',
                            objectFit: 'contain',
                            filter: 'drop-shadow(0 4px 14px rgba(0,0,0,0.7))',
                        }}
                    />
                )
            ) : (
                <div
                    className="absolute inset-0 flex items-center justify-center px-4 text-center vesper-display"
                    style={{
                        color: '#fff',
                        fontWeight: 800,
                        letterSpacing: '-0.03em',
                        fontSize: col.name.length > 12 ? 'clamp(20px, 1.6vw, 30px)' : 'clamp(28px, 2.4vw, 44px)',
                        textShadow: '0 4px 16px rgba(0,0,0,0.6)',
                    }}
                >
                    {col.name}
                </div>
            )}
            <span
                className="absolute vesper-mono"
                style={{
                    left: 14,
                    bottom: 10,
                    fontSize: 'clamp(9px, 0.6vw, 11px)',
                    letterSpacing: '0.2em',
                    textTransform: 'uppercase',
                    color: 'rgba(255,255,255,0.72)',
                    textShadow: '0 1px 6px rgba(0,0,0,0.8)',
                }}
            >
                {col.name}
            </span>
            <div className="absolute inset-0 pointer-events-none" style={{ boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.10), inset 0 -1px 0 rgba(0,0,0,0.45)' }} />
        </button>
    );
}
