import React, { useMemo, useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, ChevronRight, Search, Library, Plug, Settings as Cog } from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import NetworksShelf from '@/components/NetworksShelf';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import { useLiveShelves } from '@/hooks/useLiveShelves';
import { useLiveHeroes } from '@/hooks/useLiveHeroes';
import Lazy from '@/components/Lazy';
import * as img from '@/lib/img';

/**
 * Paper Cinema — editorial, magazine-inspired Home page.
 *
 * Architecture:
 *   - No side nav.  A top "masthead" sits the OnNowTV wordmark with
 *     small nav links on the right.
 *   - The hero is the magazine "cover story" — a single huge feature
 *     with a vintage poster on the LEFT and tall serif text on the
 *     right.  No carousel of horizontal tiles; it just rotates.
 *   - Below the masthead, the page is split into TWO vertical columns:
 *     • Left: editorial "table of contents" — numbered list of shelves
 *       and the titles inside them, like a newspaper index.
 *     • Right: when a list item is focused, the right column previews
 *       a poster + meta + synopsis for that title.
 *   - No infinite horizontal carousels.  Reading vertical, scrolling
 *     vertical.  Feels like a film magazine.
 */
export default function HomePaper() {
    useSpatialFocus();
    const { addons } = useAddons();
    const { shelves: liveShelves } = useLiveShelves(addons, null);
    const { heroes: liveHeroes } = useLiveHeroes(addons, 'movie');
    const navigate = useNavigate();

    const shelves = useMemo(
        () => (Array.isArray(liveShelves) ? liveShelves : []),
        [liveShelves]
    );

    const [heroIdx, setHeroIdx] = useState(0);
    useEffect(() => {
        if (!liveHeroes || liveHeroes.length <= 1) return;
        const t = setInterval(
            () => setHeroIdx((i) => (i + 1) % liveHeroes.length),
            12000
        );
        return () => clearInterval(t);
    }, [liveHeroes]);
    const hero = (liveHeroes && liveHeroes[heroIdx]) || null;

    return (
        <div
            data-testid="home-page-paper"
            className="relative w-screen min-h-[100dvh] overflow-x-hidden"
            style={{
                background: 'var(--vesper-bg-0)',
                color: 'var(--vesper-text)',
                fontFamily: 'var(--theme-font-body)',
            }}
        >
            <FullscreenButton />

            {/* ───── Masthead ───── */}
            <header
                className="flex items-center justify-between"
                style={{
                    padding: '36px clamp(40px, 6vw, 96px) 20px',
                    borderBottom: '2px solid var(--vesper-text)',
                }}
            >
                <div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.32em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                            fontFamily: 'var(--theme-font-mono)',
                        }}
                    >
                        Vol. 01 · {new Date().toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })}
                    </div>
                    <div
                        style={{
                            fontFamily: 'var(--theme-font-display)',
                            fontSize: 'clamp(38px, 4.6vw, 64px)',
                            fontWeight: 700,
                            letterSpacing: '-0.02em',
                            lineHeight: 0.95,
                            marginTop: 4,
                        }}
                    >
                        On Now TV
                    </div>
                </div>
                <nav className="flex items-center gap-2">
                    <PaperNavBtn label="Search" icon={Search} onClick={() => navigate('/search')} />
                    <PaperNavBtn label="Library" icon={Library} onClick={() => navigate('/library')} />
                    <PaperNavBtn label="Sources" icon={Plug} onClick={() => navigate('/sources')} />
                    <PaperNavBtn
                        label="Settings"
                        icon={Cog}
                        data-initial-focus="true"
                        onClick={() => navigate('/settings')}
                    />
                </nav>
            </header>

            {/* ───── Cover story (hero) ───── */}
            {hero && (
                <section
                    style={{
                        display: 'grid',
                        gridTemplateColumns: '0.9fr 1.2fr',
                        gap: 'clamp(32px, 4vw, 72px)',
                        padding: 'clamp(40px, 5vw, 80px) clamp(40px, 6vw, 96px)',
                        borderBottom: '1px solid var(--vesper-text-3)',
                    }}
                >
                    <div
                        style={{
                            position: 'relative',
                            aspectRatio: '3 / 4',
                            background: 'var(--vesper-bg-2)',
                            boxShadow:
                                '0 24px 56px rgba(0,0,0,0.18), 0 0 0 1px var(--vesper-text)',
                        }}
                    >
                        <img
                            src={img.backdrop(hero.backdrop)}
                            alt={hero.title}
                            loading="eager"
                            decoding="async"
                            style={{
                                width: '100%',
                                height: '100%',
                                objectFit: 'cover',
                                filter: 'saturate(0.85) contrast(1.05)',
                            }}
                        />
                        <div
                            style={{
                                position: 'absolute',
                                top: 16,
                                left: 16,
                                padding: '6px 12px',
                                background: 'var(--theme-accent)',
                                color: 'var(--vesper-bg-0)',
                                fontFamily: 'var(--theme-font-mono)',
                                fontSize: 11,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                fontWeight: 700,
                            }}
                        >
                            Cover Story
                        </div>
                    </div>

                    <div className="flex flex-col justify-center">
                        <div
                            className="vesper-mono"
                            style={{
                                fontSize: 11,
                                letterSpacing: '0.32em',
                                textTransform: 'uppercase',
                                color: 'var(--theme-accent)',
                                fontFamily: 'var(--theme-font-mono)',
                                marginBottom: 12,
                            }}
                        >
                            {hero.eyebrow || 'Featured'}
                        </div>
                        <h1
                            style={{
                                fontFamily: 'var(--theme-font-display)',
                                fontSize: 'clamp(56px, 6vw, 96px)',
                                fontWeight: 700,
                                letterSpacing: '-0.025em',
                                lineHeight: 0.95,
                                fontStyle: 'italic',
                            }}
                        >
                            {hero.title}
                        </h1>
                        <div
                            style={{
                                fontFamily: 'var(--theme-font-mono)',
                                fontSize: 13,
                                color: 'var(--vesper-text-2)',
                                marginTop: 14,
                                letterSpacing: '0.04em',
                            }}
                        >
                            {[hero.year, hero.rating, (hero.genres || []).slice(0, 2).join(' · ')]
                                .filter(Boolean)
                                .join('  ·  ')}
                        </div>
                        <p
                            style={{
                                fontFamily: 'var(--theme-font-body)',
                                fontSize: 'clamp(15px, 1.1vw, 19px)',
                                lineHeight: 1.65,
                                marginTop: 24,
                                maxWidth: '52ch',
                                color: 'var(--vesper-text)',
                                columnCount: 1,
                            }}
                        >
                            {hero.synopsis}
                        </p>
                        <div className="flex items-center gap-3 mt-8">
                            <button
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => hero.routePath && navigate(hero.routePath)}
                                className="inline-flex items-center gap-2"
                                style={{
                                    fontFamily: 'var(--theme-font-display)',
                                    fontSize: 16,
                                    fontWeight: 600,
                                    padding: '14px 28px',
                                    background: 'var(--vesper-text)',
                                    color: 'var(--vesper-bg-0)',
                                    borderRadius: 'var(--theme-radius)',
                                }}
                            >
                                <Play size={16} fill="currentColor" />
                                Read & Watch
                            </button>
                        </div>
                    </div>
                </section>
            )}

            {/* ───── Table of contents ───── */}
            <section
                style={{
                    padding: 'clamp(40px, 5vw, 80px) clamp(40px, 6vw, 96px)',
                }}
            >
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-text-2)',
                        fontFamily: 'var(--theme-font-mono)',
                        marginBottom: 8,
                    }}
                >
                    Contents
                </div>
                <h2
                    style={{
                        fontFamily: 'var(--theme-font-display)',
                        fontSize: 'clamp(36px, 3.6vw, 56px)',
                        fontWeight: 700,
                        letterSpacing: '-0.02em',
                        marginBottom: 28,
                    }}
                >
                    Inside this issue
                </h2>

                {shelves.map((shelf, i) => (
                    <Lazy key={shelf.id} minHeight={260} eager={i < 1}>
                        <PaperShelfBlock shelf={shelf} index={i + 1} />
                    </Lazy>
                ))}
            </section>

            {/* ───── Networks rail ───── */}
            <Lazy minHeight={260}>
                <NetworksShelf />
            </Lazy>
        </div>
    );
}

function PaperNavBtn({ label, icon: Icon, onClick, ...rest }) {
    return (
        <button
            data-focusable="true"
            data-focus-style="pill"
            tabIndex={0}
            onClick={onClick}
            className="inline-flex items-center gap-2"
            style={{
                padding: '10px 18px',
                fontFamily: 'var(--theme-font-mono)',
                fontSize: 12,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
                color: 'var(--vesper-text)',
                background: 'transparent',
                border: '1px solid var(--vesper-text)',
                borderRadius: 'var(--theme-radius)',
            }}
            {...rest}
        >
            <Icon size={14} strokeWidth={2} />
            {label}
        </button>
    );
}

function PaperShelfBlock({ shelf, index }) {
    const navigate = useNavigate();
    const items = (shelf.items || []).slice(0, 6);
    return (
        <div
            style={{
                display: 'grid',
                gridTemplateColumns: '64px 1fr',
                gap: 24,
                paddingBottom: 32,
                marginBottom: 32,
                borderBottom: '1px solid var(--vesper-text-3)',
            }}
        >
            <div
                style={{
                    fontFamily: 'var(--theme-font-display)',
                    fontSize: 56,
                    fontWeight: 700,
                    fontStyle: 'italic',
                    color: 'var(--theme-accent)',
                    lineHeight: 1,
                }}
            >
                {String(index).padStart(2, '0')}
            </div>
            <div>
                <h3
                    style={{
                        fontFamily: 'var(--theme-font-display)',
                        fontSize: 'clamp(26px, 2.4vw, 36px)',
                        fontWeight: 700,
                        letterSpacing: '-0.015em',
                    }}
                >
                    {shelf.title}
                </h3>
                <div
                    className="vesper-mono"
                    style={{
                        fontFamily: 'var(--theme-font-mono)',
                        fontSize: 11,
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-text-3)',
                        marginTop: 4,
                    }}
                >
                    {shelf.items.length} titles · page {String(index * 12).padStart(3, '0')}
                </div>

                <ul style={{ marginTop: 20, columnCount: 2, columnGap: 32 }}>
                    {items.map((item, i) => (
                        <li
                            key={item.id}
                            style={{
                                breakInside: 'avoid',
                                marginBottom: 14,
                            }}
                        >
                            <button
                                data-focusable="true"
                                data-focus-style="paper-row"
                                tabIndex={0}
                                onClick={() => {
                                    if (item.routePath) navigate(item.routePath);
                                    else if (item.imdbId)
                                        navigate(`/title/${item.type || 'movie'}/${item.imdbId}`);
                                    else navigate(`/title/${item.id}`);
                                }}
                                className="w-full text-left flex items-baseline gap-3"
                                style={{
                                    padding: '6px 8px',
                                    borderRadius: 'var(--theme-radius)',
                                }}
                            >
                                <span
                                    className="vesper-mono"
                                    style={{
                                        fontFamily: 'var(--theme-font-mono)',
                                        fontSize: 11,
                                        color: 'var(--vesper-text-3)',
                                        minWidth: 28,
                                    }}
                                >
                                    {String(i + 1).padStart(2, '0')}
                                </span>
                                <span
                                    style={{
                                        fontFamily: 'var(--theme-font-display)',
                                        fontSize: 'clamp(15px, 1.1vw, 20px)',
                                        fontWeight: 600,
                                        letterSpacing: '-0.01em',
                                    }}
                                >
                                    {item.title}
                                </span>
                                {item.sub && (
                                    <span
                                        style={{
                                            fontFamily: 'var(--theme-font-mono)',
                                            fontSize: 11,
                                            color: 'var(--vesper-text-3)',
                                            marginLeft: 'auto',
                                        }}
                                    >
                                        {item.sub}
                                    </span>
                                )}
                                <ChevronRight size={12} style={{ opacity: 0.4 }} />
                            </button>
                        </li>
                    ))}
                </ul>
            </div>
        </div>
    );
}
