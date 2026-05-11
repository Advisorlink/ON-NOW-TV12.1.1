import React, { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search, Library, Plug, Settings as Cog, Home as HomeIcon } from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import { useLiveShelves } from '@/hooks/useLiveShelves';
import { useLiveHeroes } from '@/hooks/useLiveHeroes';
import Lazy from '@/components/Lazy';
import * as img from '@/lib/img';

/**
 * Arcade — cyberpunk / synthwave Home page.
 *
 * Architecture:
 *   - Top horizontal nav bar with monospace pill links.  Logo on
 *     the far left, status (date / time / online network count) far
 *     right.
 *   - Hero: full-width landscape backdrop with title overlaid in
 *     pink neon + scan-line gradient.  Cyan grid backdrop behind.
 *   - Shelves: rendered as dense LANDSCAPE-tile grids (4 tiles wide
 *     per row) using each title's backdrop as the tile image —
 *     completely different look from portrait posters.  Each grid
 *     is uniform 16:9 with a neon hover ring.
 *   - No side nav.  Bottom-corner network bar replaces the by-network
 *     shelf.
 */
export default function HomeArcade() {
    useSpatialFocus();
    const { addons } = useAddons();
    const { shelves: liveShelves } = useLiveShelves(addons, null);
    const { heroes: liveHeroes } = useLiveHeroes(addons, 'movie');
    const navigate = useNavigate();

    const shelves = useMemo(
        () => (Array.isArray(liveShelves) ? liveShelves : []),
        [liveShelves]
    );
    const hero = (liveHeroes && liveHeroes[0]) || null;

    return (
        <div
            data-testid="home-page-arcade"
            className="relative w-screen min-h-[100dvh] overflow-x-hidden"
            style={{
                background: 'var(--vesper-bg-0)',
                color: 'var(--vesper-text)',
                fontFamily: 'var(--theme-font-mono)',
                backgroundImage: `
                    linear-gradient(rgba(255,46,171,0.04) 1px, transparent 1px),
                    linear-gradient(90deg, rgba(35,229,255,0.04) 1px, transparent 1px)
                `,
                backgroundSize: '48px 48px',
            }}
        >
            <FullscreenButton />

            {/* ───── Top nav ───── */}
            <header
                style={{
                    position: 'sticky',
                    top: 0,
                    zIndex: 20,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    padding: '20px clamp(28px, 4vw, 64px)',
                    background:
                        'linear-gradient(180deg, rgba(10,0,20,0.92) 0%, rgba(10,0,20,0.6) 80%, transparent 100%)',
                    borderBottom: '1px solid var(--theme-accent)',
                    boxShadow: '0 0 24px var(--vesper-blue-glow)',
                }}
            >
                <div className="flex items-center gap-5">
                    <div
                        style={{
                            fontFamily: 'var(--theme-font-display)',
                            fontSize: 24,
                            fontWeight: 700,
                            letterSpacing: '0.18em',
                            color: 'var(--theme-accent)',
                            textShadow:
                                '0 0 24px var(--vesper-blue-glow), 0 0 4px rgba(255,255,255,0.4)',
                        }}
                    >
                        ON://NOW
                    </div>
                    <span
                        style={{
                            color: 'var(--theme-cyan, #23E5FF)',
                            fontSize: 11,
                            letterSpacing: '0.32em',
                        }}
                    >
                        TV.V2
                    </span>
                </div>
                <nav className="flex items-center gap-2">
                    <ArcadeNavBtn
                        label="Home"
                        icon={HomeIcon}
                        active
                        data-initial-focus="true"
                        onClick={() => navigate('/')}
                    />
                    <ArcadeNavBtn label="Search" icon={Search} onClick={() => navigate('/search')} />
                    <ArcadeNavBtn label="Library" icon={Library} onClick={() => navigate('/library')} />
                    <ArcadeNavBtn label="Sources" icon={Plug} onClick={() => navigate('/sources')} />
                    <ArcadeNavBtn label="Themes" icon={Cog} onClick={() => navigate('/settings')} />
                </nav>
            </header>

            {/* ───── Hero ───── */}
            {hero && (
                <section
                    className="relative"
                    style={{
                        height: 'clamp(280px, 36vh, 460px)',
                        marginBottom: 12,
                    }}
                >
                    <div
                        className="absolute inset-0"
                        style={{
                            backgroundImage: `url(${img.backdrop(hero.backdrop)})`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center',
                            filter: 'saturate(1.4) contrast(1.05)',
                        }}
                    />
                    <div
                        className="absolute inset-0 pointer-events-none"
                        style={{
                            background: `
                                linear-gradient(180deg,
                                    rgba(10,0,20,0) 0%,
                                    rgba(10,0,20,0.4) 60%,
                                    var(--vesper-bg-0) 100%
                                ),
                                repeating-linear-gradient(0deg,
                                    rgba(0,0,0,0.18) 0px,
                                    rgba(0,0,0,0.18) 1px,
                                    transparent 1px,
                                    transparent 3px
                                )
                            `,
                        }}
                    />
                    <div
                        className="absolute"
                        style={{
                            left: 'clamp(28px, 4vw, 64px)',
                            bottom: 32,
                            maxWidth: '70vw',
                        }}
                    >
                        <div
                            style={{
                                color: 'var(--theme-cyan, #23E5FF)',
                                fontSize: 12,
                                letterSpacing: '0.32em',
                                textTransform: 'uppercase',
                                marginBottom: 10,
                            }}
                        >
                            ▶ NOW PLAYING · LEVEL 01
                        </div>
                        <h1
                            style={{
                                fontFamily: 'var(--theme-font-display)',
                                fontSize: 'clamp(40px, 5vw, 84px)',
                                fontWeight: 700,
                                lineHeight: 0.95,
                                letterSpacing: '0.02em',
                                color: '#fff',
                                textTransform: 'uppercase',
                                textShadow:
                                    '0 0 32px var(--vesper-blue-glow), 0 0 4px rgba(0,0,0,0.6)',
                            }}
                        >
                            {hero.title}
                        </h1>
                        <button
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => hero.routePath && navigate(hero.routePath)}
                            style={{
                                marginTop: 18,
                                padding: '14px 28px',
                                background: 'var(--theme-accent)',
                                color: 'var(--vesper-bg-0)',
                                fontFamily: 'var(--theme-font-mono)',
                                fontSize: 13,
                                fontWeight: 700,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                border: 'none',
                                clipPath:
                                    'polygon(8px 0, 100% 0, calc(100% - 8px) 100%, 0 100%)',
                            }}
                        >
                            ▶ Insert Coin
                        </button>
                    </div>
                </section>
            )}

            {/* ───── Networks grid (top-of-page replacement for portrait shelf) ───── */}
            <section
                style={{
                    padding: '12px clamp(28px, 4vw, 64px) 32px',
                }}
            >
                <ArcadeNetworks />
            </section>

            {/* ───── Shelves as landscape grids ───── */}
            <section
                style={{
                    padding: '0 clamp(28px, 4vw, 64px) 80px',
                }}
            >
                {shelves.map((shelf, i) => (
                    <Lazy key={shelf.id} minHeight={320} eager={i < 1}>
                        <ArcadeShelf shelf={shelf} />
                    </Lazy>
                ))}
            </section>
        </div>
    );
}

function ArcadeNavBtn({ label, icon: Icon, active, onClick, ...rest }) {
    return (
        <button
            data-focusable="true"
            data-focus-style="pill"
            tabIndex={0}
            onClick={onClick}
            className="inline-flex items-center gap-2"
            style={{
                padding: '10px 16px',
                fontFamily: 'var(--theme-font-mono)',
                fontSize: 12,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                color: active ? 'var(--vesper-bg-0)' : 'var(--vesper-text)',
                background: active ? 'var(--theme-accent)' : 'transparent',
                border: '1px solid var(--theme-accent)',
                clipPath:
                    'polygon(6px 0, 100% 0, calc(100% - 6px) 100%, 0 100%)',
            }}
            {...rest}
        >
            <Icon size={12} strokeWidth={2.4} />
            {label}
        </button>
    );
}

function ArcadeNetworks() {
    const navigate = useNavigate();
    // 6 networks pulled inline so we don't reuse the portrait shelf
    const NETS = [
        { slug: 'netflix', label: 'NETFLIX', color: '#E50914' },
        { slug: 'hbo', label: 'HBO', color: '#FFFFFF' },
        { slug: 'disney-plus', label: 'DISNEY+', color: '#23E5FF' },
        { slug: 'prime-video', label: 'PRIME', color: '#00A8E1' },
        { slug: 'apple-tv', label: 'APPLE TV+', color: '#FFFFFF' },
        { slug: 'hulu', label: 'HULU', color: '#1CE783' },
    ];
    return (
        <div
            style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fit, minmax(120px, 1fr))',
                gap: 8,
            }}
        >
            {NETS.map((n) => (
                <button
                    key={n.slug}
                    data-focusable="true"
                    data-focus-style="tile"
                    tabIndex={0}
                    onClick={() => navigate(`/networks/${n.slug}`)}
                    style={{
                        padding: '18px 12px',
                        background: 'rgba(255,255,255,0.03)',
                        border: `1px solid ${n.color}55`,
                        color: n.color,
                        fontFamily: 'var(--theme-font-mono)',
                        fontSize: 13,
                        fontWeight: 700,
                        letterSpacing: '0.18em',
                        textAlign: 'center',
                        clipPath:
                            'polygon(8px 0, 100% 0, calc(100% - 8px) 100%, 0 100%)',
                    }}
                >
                    {n.label}
                </button>
            ))}
        </div>
    );
}

function ArcadeShelf({ shelf }) {
    const navigate = useNavigate();
    const items = (shelf.items || []).slice(0, 12);
    return (
        <div style={{ marginBottom: 48 }}>
            <header
                className="flex items-baseline justify-between"
                style={{
                    marginBottom: 16,
                    paddingBottom: 8,
                    borderBottom: '1px dashed var(--theme-accent)',
                }}
            >
                <h2
                    style={{
                        fontFamily: 'var(--theme-font-display)',
                        fontSize: 'clamp(20px, 1.8vw, 28px)',
                        fontWeight: 700,
                        letterSpacing: '0.06em',
                        textTransform: 'uppercase',
                        color: 'var(--theme-accent)',
                    }}
                >
                    ▌{shelf.title}
                </h2>
                <span
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.22em',
                        color: 'var(--theme-cyan, #23E5FF)',
                        textTransform: 'uppercase',
                    }}
                >
                    {shelf.items.length} entries
                </span>
            </header>
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))',
                    gap: 12,
                }}
            >
                {items.map((item) => (
                    <button
                        key={item.id}
                        data-focusable="true"
                        data-focus-style="tile"
                        tabIndex={0}
                        onClick={() => {
                            if (item.routePath) navigate(item.routePath);
                            else if (item.imdbId)
                                navigate(`/title/${item.type || 'movie'}/${item.imdbId}`);
                            else navigate(`/title/${item.id}`);
                        }}
                        style={{
                            position: 'relative',
                            aspectRatio: '16 / 9',
                            background: 'var(--vesper-bg-2)',
                            border: '1px solid rgba(255,46,171,0.35)',
                            overflow: 'hidden',
                            textAlign: 'left',
                        }}
                    >
                        {item.poster ? (
                            <img
                                src={img.poster(item.poster)}
                                alt={item.title}
                                loading="lazy"
                                decoding="async"
                                style={{
                                    position: 'absolute',
                                    inset: 0,
                                    width: '100%',
                                    height: '100%',
                                    objectFit: 'cover',
                                    filter: 'saturate(1.25) contrast(1.05)',
                                }}
                            />
                        ) : null}
                        <div
                            className="absolute inset-0 pointer-events-none"
                            style={{
                                background:
                                    'linear-gradient(180deg, rgba(0,0,0,0) 40%, rgba(10,0,20,0.92) 100%)',
                            }}
                        />
                        <div
                            className="absolute inset-x-0 bottom-0"
                            style={{ padding: '10px 12px' }}
                        >
                            <div
                                style={{
                                    fontFamily: 'var(--theme-font-mono)',
                                    fontSize: 13,
                                    fontWeight: 700,
                                    color: '#fff',
                                    textTransform: 'uppercase',
                                    letterSpacing: '0.04em',
                                    textShadow: '0 1px 4px rgba(0,0,0,0.8)',
                                    whiteSpace: 'nowrap',
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                }}
                            >
                                {item.title}
                            </div>
                        </div>
                    </button>
                ))}
            </div>
        </div>
    );
}
