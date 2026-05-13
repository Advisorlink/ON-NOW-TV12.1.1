import React, { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
    ArrowLeft,
    Tv,
    Film,
    Bookmark,
    Play,
    Sparkles,
    Trash2,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import {
    listFavouritesByType,
    listWatchLater,
    removeFromWatchLater,
} from '@/lib/library';

/**
 * /library — My Library.
 *
 * Layout:
 *   ┌────────────────────────────────────────┬──────────────┐
 *   │ Header: "Library"                       │ WATCH LATER  │
 *   │ TV Shows row (or empty hero)            │  · episode 1 │
 *   │ Movies row (or empty hero)              │  · episode 2 │
 *   └────────────────────────────────────────┴──────────────┘
 *
 * Empty states explain the concept — for TV shows we render an
 * inline preview of the top-right "new episode" notification so
 * users see what they're signing up for.
 */
export default function Library() {
    useSpatialFocus();
    const [tv, setTv] = useState(listFavouritesByType('series'));
    const [movies, setMovies] = useState(listFavouritesByType('movie'));
    const [watchLater, setWatchLater] = useState(listWatchLater());

    useEffect(() => {
        const sync = () => {
            setTv(listFavouritesByType('series'));
            setMovies(listFavouritesByType('movie'));
            setWatchLater(listWatchLater());
        };
        window.addEventListener('vesper:library-change', sync);
        return () => window.removeEventListener('vesper:library-change', sync);
    }, []);

    const navigate = useNavigate();

    return (
        <div
            data-testid="library-page"
            className="relative w-screen min-h-[100dvh] flex"
            style={{
                background: 'var(--vesper-bg-0)',
                paddingLeft: 100,
                paddingRight: 24,
                paddingTop: 48,
                paddingBottom: 60,
            }}
        >
            <main className="flex-1 min-w-0" style={{ paddingRight: 32 }}>
                <Header onBack={() => navigate('/')} />

                <Section
                    icon={Tv}
                    eyebrow="My library · Series"
                    title="TV Shows"
                >
                    {tv.length === 0 ? (
                        <TvEmptyState />
                    ) : (
                        <FavouriteGrid items={tv} type="series" />
                    )}
                </Section>

                <Section
                    icon={Film}
                    eyebrow="My library · Films"
                    title="Movies"
                    style={{ marginTop: 64 }}
                >
                    {movies.length === 0 ? (
                        <MovieEmptyState />
                    ) : (
                        <FavouriteGrid items={movies} type="movie" />
                    )}
                </Section>
            </main>

            <WatchLaterRail
                items={watchLater}
                onRemove={(w) =>
                    removeFromWatchLater({
                        id: w.id,
                        season: w.episode.season,
                        number: w.episode.number,
                    })
                }
            />
        </div>
    );
}

/* ----------------------------- Header ----------------------------- */

function Header({ onBack }) {
    return (
        <div className="flex items-center gap-5" style={{ marginBottom: 40 }}>
            <button
                data-testid="library-back"
                data-focusable="true"
                data-focus-style="quiet"
                data-initial-focus="true"
                tabIndex={0}
                onClick={onBack}
                className="flex items-center justify-center rounded-full"
                style={{
                    width: 46,
                    height: 46,
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    color: 'var(--vesper-text-2)',
                }}
            >
                <ArrowLeft size={20} />
            </button>
            <div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)',
                    }}
                >
                    Your collection
                </div>
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(40px, 4vw, 64px)',
                        letterSpacing: '-0.03em',
                        lineHeight: 0.95,
                        marginTop: 4,
                    }}
                >
                    My Library
                </h1>
            </div>
        </div>
    );
}

/* ----------------------------- Section ----------------------------- */

function Section({ icon: Icon, eyebrow, title, style, children }) {
    return (
        <section style={style}>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    marginBottom: 8,
                }}
            >
                {eyebrow}
            </div>
            <h2
                className="vesper-display flex items-center gap-3"
                style={{
                    fontSize: 'clamp(28px, 3vw, 44px)',
                    letterSpacing: '-0.025em',
                    lineHeight: 1,
                    marginBottom: 22,
                }}
            >
                <Icon size={24} strokeWidth={1.8} style={{ color: 'var(--vesper-blue)' }} />
                {title}
            </h2>
            {children}
        </section>
    );
}

/* --------------------------- Empty states --------------------------- */

function TvEmptyState() {
    return (
        <div
            className="grid"
            style={{
                gridTemplateColumns: '1.1fr 1fr',
                gap: 36,
                alignItems: 'center',
                padding: '28px 32px',
                background: 'rgba(255,255,255,0.025)',
                border: '1px dashed rgba(var(--vesper-blue-rgb), 0.35)',
                borderRadius: 22,
            }}
        >
            <div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.28em',
                        color: 'var(--vesper-blue-bright)',
                        marginBottom: 10,
                    }}
                >
                    How this works
                </div>
                <div
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(22px, 2vw, 30px)',
                        lineHeight: 1.2,
                        letterSpacing: '-0.02em',
                        color: 'var(--vesper-text)',
                        marginBottom: 14,
                    }}
                >
                    Follow shows you love. We&apos;ll tell you when there&apos;s
                    a new episode out.
                </div>
                <p
                    style={{
                        fontSize: 15,
                        lineHeight: 1.6,
                        color: 'var(--vesper-text-2)',
                        maxWidth: '46ch',
                    }}
                >
                    Open any series and tap{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Add to My List
                    </span>
                    . Every time a fresh episode airs, a notification pops up
                    in the top-right corner — hit{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Play
                    </span>{' '}
                    to start instantly, or{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Watch Later
                    </span>{' '}
                    to drop it in the rail on the right of this page.
                </p>
            </div>
            <NotificationPreview />
        </div>
    );
}

function NotificationPreview() {
    return (
        <div
            className="relative"
            style={{
                aspectRatio: '5 / 3.6',
                background:
                    'radial-gradient(ellipse at 30% 30%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 60%), rgba(6,8,15,0.6)',
                borderRadius: 16,
                padding: 18,
                border: '1px solid rgba(255,255,255,0.06)',
                overflow: 'hidden',
            }}
        >
            <div
                style={{
                    position: 'absolute',
                    top: 14,
                    right: 14,
                    width: '70%',
                    background:
                        'linear-gradient(180deg, rgba(10,14,26,0.96) 0%, rgba(10,14,26,0.92) 100%)',
                    border: '1px solid rgba(var(--vesper-blue-rgb), 0.45)',
                    borderRadius: 14,
                    overflow: 'hidden',
                    boxShadow:
                        '0 14px 32px rgba(0,0,0,0.45), 0 0 0 1px rgba(var(--vesper-blue-rgb), 0.18)',
                }}
            >
                <div
                    style={{
                        height: 78,
                        background:
                            'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.35) 0%, rgba(10,14,26,0.9) 100%)',
                        position: 'relative',
                    }}
                >
                    <div
                        style={{
                            position: 'absolute',
                            top: 10,
                            left: 12,
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 5,
                            padding: '4px 9px',
                            borderRadius: 999,
                            background: 'rgba(var(--vesper-blue-rgb),0.18)',
                            border: '1px solid rgba(var(--vesper-blue-rgb),0.55)',
                            color: 'var(--vesper-blue-bright)',
                            fontFamily: 'var(--theme-font-mono, monospace)',
                            fontSize: 8,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            fontWeight: 600,
                        }}
                    >
                        <Sparkles size={9} strokeWidth={2.4} />
                        New Episode
                    </div>
                </div>
                <div style={{ padding: '10px 12px 12px' }}>
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 13,
                            letterSpacing: '-0.02em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        Your show
                    </div>
                    <div
                        style={{
                            fontSize: 10,
                            color: 'var(--vesper-text-2)',
                            marginTop: 2,
                        }}
                    >
                        S1 · E1 · brand new
                    </div>
                    <div className="flex items-center gap-1.5 mt-2">
                        <span
                            style={{
                                flex: 1,
                                height: 26,
                                borderRadius: 999,
                                background: 'var(--vesper-blue)',
                                color: 'var(--vesper-bg-0)',
                                fontSize: 10,
                                fontWeight: 700,
                                display: 'inline-flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: 4,
                            }}
                        >
                            <Play size={10} strokeWidth={2.6} />
                            Play
                        </span>
                        <span
                            style={{
                                flex: 1,
                                height: 26,
                                borderRadius: 999,
                                background: 'rgba(255,255,255,0.08)',
                                color: 'var(--vesper-text)',
                                border: '1px solid rgba(255,255,255,0.16)',
                                fontSize: 10,
                                fontWeight: 600,
                                display: 'inline-flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: 4,
                            }}
                        >
                            <Bookmark size={10} strokeWidth={2.4} />
                            Later
                        </span>
                    </div>
                </div>
            </div>
        </div>
    );
}

function MovieEmptyState() {
    return (
        <div
            style={{
                padding: '24px 30px',
                background: 'rgba(255,255,255,0.025)',
                border: '1px dashed rgba(255,255,255,0.14)',
                borderRadius: 22,
            }}
        >
            <div
                className="vesper-display"
                style={{
                    fontSize: 'clamp(20px, 1.8vw, 26px)',
                    lineHeight: 1.25,
                    letterSpacing: '-0.02em',
                    color: 'var(--vesper-text)',
                    marginBottom: 8,
                }}
            >
                A wishlist for the films you want to come back to.
            </div>
            <p
                style={{
                    fontSize: 14,
                    lineHeight: 1.55,
                    color: 'var(--vesper-text-2)',
                    maxWidth: '60ch',
                }}
            >
                Open any movie and tap{' '}
                <span
                    style={{
                        color: 'var(--vesper-blue-bright)',
                        fontWeight: 600,
                    }}
                >
                    Add to My List
                </span>
                . It&apos;ll live here, ready for whenever you&apos;re in the
                mood.
            </p>
        </div>
    );
}

/* --------------------------- Favourite grid --------------------------- */

function FavouriteGrid({ items, type }) {
    return (
        <div
            className="grid"
            style={{
                gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
                gap: 16,
            }}
        >
            {items.map((it) => (
                <FavouriteCard key={it.id} item={it} type={type} />
            ))}
        </div>
    );
}

function FavouriteCard({ item, type }) {
    const poster = item.meta?.poster;
    return (
        <Link
            to={`/title/${type}/${item.id}`}
            data-testid={`favorite-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            className="block relative overflow-hidden"
            style={{
                aspectRatio: '2 / 3',
                borderRadius: 12,
                background: poster
                    ? '#1a1f2e'
                    : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.2), rgba(10,14,26,0.9))',
            }}
        >
            {poster ? (
                <img
                    src={poster}
                    alt={item.meta?.name || ''}
                    loading="lazy"
                    className="w-full h-full object-cover"
                />
            ) : (
                <div
                    className="absolute inset-0 flex items-center justify-center vesper-display"
                    style={{
                        color: 'var(--vesper-text-2)',
                        fontSize: 14,
                        textAlign: 'center',
                        padding: 12,
                        letterSpacing: '-0.01em',
                    }}
                >
                    {item.meta?.name || item.id}
                </div>
            )}
            <div
                className="absolute left-0 right-0 bottom-0 p-2.5"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.92) 100%)',
                }}
            >
                <div
                    style={{
                        fontSize: 12,
                        fontWeight: 600,
                        color: 'var(--vesper-text)',
                        lineHeight: 1.2,
                    }}
                >
                    {item.meta?.name || item.id}
                </div>
                {item.meta?.year && (
                    <div
                        style={{
                            fontSize: 10,
                            color: 'var(--vesper-text-3)',
                            marginTop: 2,
                        }}
                    >
                        {item.meta.year}
                    </div>
                )}
            </div>
        </Link>
    );
}

/* --------------------------- Watch Later rail --------------------------- */

function WatchLaterRail({ items, onRemove }) {
    const navigate = useNavigate();
    return (
        <aside
            data-testid="watch-later-rail"
            style={{
                width: 320,
                flex: '0 0 320px',
                background:
                    'linear-gradient(180deg, rgba(var(--vesper-blue-rgb), 0.06) 0%, rgba(255,255,255,0.02) 100%)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.22)',
                borderRadius: 22,
                padding: 22,
                alignSelf: 'flex-start',
                position: 'sticky',
                top: 48,
            }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)',
                    textTransform: 'uppercase',
                    marginBottom: 6,
                }}
            >
                Queued up
            </div>
            <h3
                className="vesper-display flex items-center gap-2"
                style={{
                    fontSize: 22,
                    letterSpacing: '-0.02em',
                    lineHeight: 1,
                    marginBottom: 14,
                }}
            >
                <Bookmark size={18} strokeWidth={2.2} style={{ color: 'var(--vesper-blue)' }} />
                Watch Later
            </h3>

            {items.length === 0 ? (
                <div
                    style={{
                        fontSize: 13,
                        lineHeight: 1.55,
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    When a new episode pops up in the top-right corner and you
                    tap{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Watch Later
                    </span>
                    , it lands here for you.
                </div>
            ) : (
                <div className="flex flex-col gap-3">
                    {items.map((w) => (
                        <WatchLaterTile
                            key={`${w.id}:S${w.episode.season}E${w.episode.number}`}
                            item={w}
                            onPlay={() => {
                                const videoId = `${w.id}:${w.episode.season}:${w.episode.number}`;
                                navigate(
                                    `/resolve/series/${encodeURIComponent(videoId)}`
                                );
                            }}
                            onRemove={() => onRemove(w)}
                        />
                    ))}
                </div>
            )}
        </aside>
    );
}

function WatchLaterTile({ item, onPlay, onRemove }) {
    const { showMeta, episode } = item;
    const thumb = episode.thumbnail || showMeta.poster;
    return (
        <div
            className="relative overflow-hidden"
            style={{
                borderRadius: 14,
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
            }}
        >
            <button
                data-testid={`watch-later-play-${item.id}-${episode.season}-${episode.number}`}
                data-focusable="true"
                data-focus-style="tile"
                tabIndex={0}
                onClick={onPlay}
                className="w-full text-left block"
                style={{
                    background: 'transparent',
                    border: 'none',
                    padding: 0,
                }}
            >
                <div
                    className="relative"
                    style={{
                        aspectRatio: '16 / 9',
                        background: thumb
                            ? '#1a1f2e'
                            : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.3), rgba(10,14,26,0.9))',
                    }}
                >
                    {thumb && (
                        <img
                            src={thumb}
                            alt=""
                            className="w-full h-full object-cover"
                            loading="lazy"
                        />
                    )}
                    <div
                        className="absolute inset-0 flex items-center justify-center"
                        style={{
                            background:
                                'linear-gradient(180deg, rgba(6,8,15,0.05) 0%, rgba(6,8,15,0.55) 100%)',
                        }}
                    >
                        <span
                            className="flex items-center justify-center rounded-full"
                            style={{
                                width: 46,
                                height: 46,
                                background: 'rgba(6,8,15,0.78)',
                                border:
                                    '1px solid rgba(var(--vesper-blue-rgb), 0.55)',
                                color: 'var(--vesper-blue-bright)',
                            }}
                        >
                            <Play size={18} strokeWidth={2.4} />
                        </span>
                    </div>
                </div>
                <div style={{ padding: '10px 12px 12px' }}>
                    <div
                        style={{
                            fontSize: 13,
                            fontWeight: 600,
                            color: 'var(--vesper-text)',
                            lineHeight: 1.25,
                        }}
                    >
                        {showMeta.name}
                    </div>
                    <div
                        style={{
                            fontSize: 11,
                            color: 'var(--vesper-text-2)',
                            marginTop: 3,
                        }}
                    >
                        S{episode.season} · E{episode.number}
                        {episode.name && episode.name !== `S${episode.season} · E${episode.number}` && (
                            <> · {episode.name}</>
                        )}
                    </div>
                </div>
            </button>
            <button
                data-testid={`watch-later-remove-${item.id}-${episode.season}-${episode.number}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={onRemove}
                aria-label="Remove from Watch Later"
                className="absolute flex items-center justify-center rounded-full"
                style={{
                    top: 8,
                    right: 8,
                    width: 28,
                    height: 28,
                    background: 'rgba(6,8,15,0.78)',
                    border: '1px solid rgba(255,255,255,0.16)',
                    color: 'var(--vesper-text-2)',
                }}
            >
                <Trash2 size={13} strokeWidth={2} />
            </button>
        </div>
    );
}
