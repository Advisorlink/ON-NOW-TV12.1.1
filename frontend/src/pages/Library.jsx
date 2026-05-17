import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    ArrowLeft,
    Tv,
    Bookmark,
    Play,
    Sparkles,
    Maximize2,
    X,
    CalendarDays,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useLongPress from '@/hooks/useLongPress';
import {
    listFavouritesByType,
    listWatchLater,
    removeFromWatchLater,
} from '@/lib/library';
import LibraryCalendar from '@/components/LibraryCalendar';

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
    const [watchLater, setWatchLater] = useState(listWatchLater());
    const [expanded, setExpanded] = useState(false);
    const [calendarOpen, setCalendarOpen] = useState(false);

    useEffect(() => {
        const sync = () => {
            setTv(listFavouritesByType('series'));
            setWatchLater(listWatchLater());
        };
        window.addEventListener('vesper:library-change', sync);
        return () => window.removeEventListener('vesper:library-change', sync);
    }, []);

    const navigate = useNavigate();

    // Global Back-key handler — Android TV remote BACK maps to
    // Escape inside the WebView (and Backspace inside a desktop
    // browser).  Without this the user could land on a tile, press
    // Back, and have nothing happen — the page felt "frozen".
    // The WatchLater Expanded overlay has its own escape handler
    // that runs first and stops propagation, so this only triggers
    // when the user is on the main Library shell.
    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                // Don't hijack Backspace inside text inputs.
                const tag = (e.target?.tagName || '').toLowerCase();
                if (
                    e.key === 'Backspace' &&
                    (tag === 'input' || tag === 'textarea' || e.target?.isContentEditable)
                ) {
                    return;
                }
                e.preventDefault();
                navigate('/');
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [navigate]);

    return (
        <div
            data-testid="library-page"
            className="relative w-screen"
            style={{
                height: '100dvh',
                overflowY: 'auto',
                overflowX: 'hidden',
                background: 'var(--vesper-bg-0)',
                paddingLeft: 100,
                paddingRight: 60,
                paddingTop: 48,
                paddingBottom: 120,
            }}
        >
            <Header onBack={() => navigate('/')} />

            <Section
                icon={Tv}
                eyebrow="My library · Series"
                title="TV Shows"
                action={
                    tv.length > 0 ? (
                        <button
                            data-testid="open-library-calendar"
                            data-focusable="true"
                            data-focus-style="quiet"
                            tabIndex={0}
                            onClick={() => setCalendarOpen(true)}
                            aria-label="Open release calendar"
                            className="flex items-center gap-2 rounded-full vesper-mono"
                            style={{
                                height: 36,
                                padding: '0 16px',
                                background: 'rgba(var(--vesper-blue-rgb), 0.14)',
                                color: 'var(--vesper-blue-bright)',
                                border: '1px solid rgba(var(--vesper-blue-rgb), 0.45)',
                                fontSize: 11,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                fontWeight: 600,
                            }}
                        >
                            <CalendarDays size={13} strokeWidth={2.2} />
                            Calendar
                        </button>
                    ) : null
                }
            >
                {tv.length === 0 ? (
                    <TvEmptyState />
                ) : (
                    <FavouriteGrid items={tv} type="series" />
                )}
            </Section>

            <WatchLaterBlock
                items={watchLater}
                onRemove={(w) =>
                    removeFromWatchLater({
                        id: w.id,
                        season: w.episode?.season,
                        number: w.episode?.number,
                    })
                }
                onExpand={() => setExpanded(true)}
            />

            {expanded && (
                <WatchLaterExpanded
                    items={watchLater}
                    onClose={() => setExpanded(false)}
                    onRemove={(w) =>
                        removeFromWatchLater({
                            id: w.id,
                            season: w.episode?.season,
                            number: w.episode?.number,
                        })
                    }
                />
            )}

            {calendarOpen && (
                <LibraryCalendar
                    tvFavourites={tv}
                    onClose={() => setCalendarOpen(false)}
                />
            )}
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

function Section({ icon: Icon, eyebrow, title, style, action, children }) {
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
            <div className="flex items-end justify-between" style={{ gap: 24, marginBottom: 22 }}>
                <h2
                    className="vesper-display flex items-center gap-3"
                    style={{
                        fontSize: 'clamp(28px, 3vw, 44px)',
                        letterSpacing: '-0.025em',
                        lineHeight: 1,
                    }}
                >
                    <Icon size={24} strokeWidth={1.8} style={{ color: 'var(--vesper-blue)' }} />
                    {title}
                </h2>
                {action}
            </div>
            {children}
        </section>
    );
}

/* --------------------------- Empty states --------------------------- */

function TvEmptyState() {
    return (
        <div
            data-focusable="true"
            data-focus-style="pill"
            tabIndex={0}
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
                    Press &amp; hold OK on any show to follow it.
                </div>
                <p
                    style={{
                        fontSize: 15,
                        lineHeight: 1.6,
                        color: 'var(--vesper-text-2)',
                        maxWidth: '46ch',
                    }}
                >
                    Find a series you love anywhere in the app, then{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        press &amp; hold the OK button
                    </span>{' '}
                    (or click &amp; hold) on its poster. A confirm card pops
                    up. Tap{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Add to My List
                    </span>{' '}
                    and it lands here. Every time a fresh episode airs, a
                    notification appears in the top-right with{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Play
                    </span>{' '}
                    or{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontWeight: 600,
                        }}
                    >
                        Watch Later
                    </span>
                    .
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

/* --------------------------- Favourite grid --------------------------- */

function FavouriteGrid({ items, type }) {
    return (
        <div
            className="grid"
            style={{
                gridTemplateColumns: 'repeat(auto-fill, minmax(120px, 1fr))',
                gap: 12,
            }}
        >
            {items.map((it) => (
                <FavouriteCard key={it.id} item={it} type={type} />
            ))}
        </div>
    );
}

function FavouriteCard({ item, type }) {
    const navigate = useNavigate();
    const poster = item.meta?.poster;

    const onTap = () => navigate(`/title/${type}/${item.id}`);
    const onLongPress = () => {
        window.dispatchEvent(
            new CustomEvent('vesper:request-add-to-list', {
                detail: {
                    id: item.id,
                    type,
                    title: item.meta?.name,
                    poster,
                    year: item.meta?.year,
                },
            })
        );
    };
    const press = useLongPress(onLongPress, onTap);

    return (
        <button
            data-testid={`favorite-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            {...press}
            className="block relative overflow-hidden text-left"
            style={{
                aspectRatio: '2 / 3',
                borderRadius: 10,
                padding: 0,
                border: 'none',
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
                        fontSize: 13,
                        textAlign: 'center',
                        padding: 10,
                        letterSpacing: '-0.01em',
                    }}
                >
                    {item.meta?.name || item.id}
                </div>
            )}
            <div
                className="absolute left-0 right-0 bottom-0"
                style={{
                    padding: '8px 9px 9px',
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.92) 100%)',
                }}
            >
                <div
                    style={{
                        fontSize: 11,
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
                            fontSize: 9,
                            color: 'var(--vesper-text-3)',
                            marginTop: 2,
                        }}
                    >
                        {item.meta.year}
                    </div>
                )}
            </div>
        </button>
    );
}

/* --------------------------- Watch Later rail --------------------------- */

function WatchLaterBlock({ items, onRemove, onExpand }) {
    const navigate = useNavigate();
    const playItem = (w) => {
        if (w.type === 'series') {
            const videoId = `${w.id}:${w.episode.season}:${w.episode.number}`;
            navigate(`/resolve/series/${encodeURIComponent(videoId)}`);
        } else {
            navigate(`/title/movie/${w.id}`);
        }
    };
    return (
        <section
            data-testid="watch-later-block"
            style={{
                marginTop: 56,
                padding: '28px 32px 30px',
                background:
                    'linear-gradient(180deg, rgba(var(--vesper-blue-rgb), 0.06) 0%, rgba(255,255,255,0.02) 100%)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.22)',
                borderRadius: 24,
            }}
        >
            <div
                className="flex items-end justify-between"
                style={{ marginBottom: 22, gap: 24 }}
            >
                <div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.32em',
                            color: 'var(--vesper-blue-bright)',
                            textTransform: 'uppercase',
                            marginBottom: 8,
                        }}
                    >
                        Queued up{items.length > 0 && ` · ${items.length}`}
                    </div>
                    <h2
                        className="vesper-display flex items-center gap-3"
                        style={{
                            fontSize: 'clamp(26px, 2.8vw, 40px)',
                            letterSpacing: '-0.025em',
                            lineHeight: 1,
                        }}
                    >
                        <Bookmark
                            size={24}
                            strokeWidth={2}
                            style={{ color: 'var(--vesper-blue)' }}
                        />
                        Watch Later
                    </h2>
                </div>
                <div
                    className="flex items-center"
                    style={{ gap: 14, flex: '0 0 auto' }}
                >
                    {items.length > 0 && (
                        <span
                            className="vesper-mono"
                            style={{
                                color: 'var(--vesper-text-3)',
                                fontSize: 11,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                            }}
                        >
                            Hold OK to remove
                        </span>
                    )}
                    {items.length > 0 && (
                        <button
                            data-testid="watch-later-expand"
                            data-focusable="true"
                            data-focus-style="quiet"
                            tabIndex={0}
                            onClick={onExpand}
                            aria-label="Expand Watch Later"
                            className="flex items-center gap-2 rounded-full vesper-mono"
                            style={{
                                height: 34,
                                padding: '0 14px',
                                background: 'rgba(var(--vesper-blue-rgb), 0.14)',
                                color: 'var(--vesper-blue-bright)',
                                border: '1px solid rgba(var(--vesper-blue-rgb), 0.45)',
                                fontSize: 11,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                fontWeight: 600,
                            }}
                        >
                            <Maximize2 size={12} strokeWidth={2.4} />
                            Expand
                        </button>
                    )}
                </div>
            </div>

            {items.length === 0 ? (
                <div
                    style={{
                        fontSize: 14,
                        lineHeight: 1.55,
                        color: 'var(--vesper-text-2)',
                        maxWidth: '70ch',
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
                    , or you long-press a movie to add it, it lands here for
                    you.
                </div>
            ) : (
                // Horizontal landscape tile row.  Snap-scrolls so a
                // D-pad press always lands at the start of the next
                // tile rather than mid-tile.  Tiles flex to ~280px so
                // 4-5 fit comfortably across at 1080p.  Vertical
                // padding leaves room for the focused-tile pop-out
                // (scale 1.08 + translateY(-2px) + 3px ring) so the
                // top and bottom of the focus glow aren't clipped.
                <div
                    data-testid="watch-later-scroller"
                    className="flex"
                    style={{
                        gap: 18,
                        overflowX: 'auto',
                        overflowY: 'hidden',
                        scrollSnapType: 'x mandatory',
                        paddingTop: 14,
                        paddingBottom: 14,
                        marginTop: -8,
                        marginBottom: -8,
                    }}
                >
                    {items.map((w) => (
                        <div
                            key={
                                w.type === 'movie'
                                    ? `movie:${w.id}`
                                    : `${w.id}:S${w.episode.season}E${w.episode.number}`
                            }
                            style={{
                                width: 280,
                                flex: '0 0 280px',
                                scrollSnapAlign: 'start',
                            }}
                        >
                            <WatchLaterTile
                                item={w}
                                onPlay={() => playItem(w)}
                                onRemove={() => onRemove(w)}
                            />
                        </div>
                    ))}
                </div>
            )}
        </section>
    );
}

/**
 * Full-screen Watch Later overlay.  Triggered by the rail's Expand
 * button.  Renders every queued item in a generous 4-col grid so
 * users can scan a long queue at a glance.
 */
function WatchLaterExpanded({ items, onClose, onRemove }) {
    const navigate = useNavigate();
    useEffect(() => {
        // Close on Escape — TV remote Back maps to Escape via the
        // Android wrapper, so this also covers the remote use case.
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') onClose();
        };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    }, [onClose]);

    const playItem = (w) => {
        if (w.type === 'series') {
            const videoId = `${w.id}:${w.episode.season}:${w.episode.number}`;
            navigate(`/resolve/series/${encodeURIComponent(videoId)}`);
        } else {
            navigate(`/title/movie/${w.id}`);
        }
    };

    return (
        <div
            data-testid="watch-later-expanded"
            className="fixed inset-0 z-[60]"
            style={{
                background: 'rgba(6,8,15,0.96)',
                backdropFilter: 'blur(12px)',
                WebkitBackdropFilter: 'blur(12px)',
                overflow: 'auto',
                padding: '56px 80px 80px',
            }}
        >
            <div className="flex items-end justify-between" style={{ marginBottom: 36 }}>
                <div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.32em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-blue-bright)',
                            marginBottom: 8,
                        }}
                    >
                        Queued up · {items.length}
                    </div>
                    <h2
                        className="vesper-display flex items-center gap-4"
                        style={{
                            fontSize: 'clamp(36px, 4vw, 56px)',
                            letterSpacing: '-0.03em',
                            lineHeight: 0.95,
                        }}
                    >
                        <Bookmark
                            size={36}
                            strokeWidth={2}
                            style={{ color: 'var(--vesper-blue)' }}
                        />
                        Watch Later
                    </h2>
                </div>
                <button
                    data-testid="watch-later-close"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={onClose}
                    className="flex items-center gap-2 rounded-full vesper-mono"
                    style={{
                        height: 46,
                        padding: '0 22px',
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.16)',
                        fontSize: 13,
                        letterSpacing: '0.18em',
                        textTransform: 'uppercase',
                        fontWeight: 600,
                    }}
                >
                    <X size={16} strokeWidth={2.2} />
                    Close
                </button>
            </div>

            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))',
                    gap: 22,
                    rowGap: 28,
                    paddingTop: 12,
                    paddingBottom: 12,
                }}
            >
                {items.map((w) => (
                    <WatchLaterTile
                        key={
                            'exp:' +
                            (w.type === 'movie'
                                ? `movie:${w.id}`
                                : `${w.id}:S${w.episode.season}E${w.episode.number}`)
                        }
                        item={w}
                        big
                        onPlay={() => playItem(w)}
                        onRemove={() => onRemove(w)}
                    />
                ))}
            </div>
        </div>
    );
}


function WatchLaterTile({ item, onPlay, onRemove, big }) {
    // CW-style landscape tile: 16:9 backdrop, bottom gradient,
    // Play badge bottom-left, title + small mono subtitle.  No
    // progress bar (these haven't been started) and no visible
    // trash button.  Long-press OK (or mouse-down 700 ms) flips
    // the tile into a "Remove from Watch Later?" confirm card,
    // exactly like Continue Watching.
    let title;
    let subtitle;
    let thumb;
    if (item.type === 'movie') {
        const m = item.movie || {};
        title = m.name;
        subtitle = m.year || 'Movie';
        thumb = m.background || m.poster;
    } else {
        const { showMeta, episode } = item;
        title = showMeta.name;
        subtitle = `S${episode.season} · E${episode.number}`;
        thumb = episode.thumbnail || showMeta.background || showMeta.poster;
    }

    const [confirmRemove, setConfirmRemove] = useState(false);
    const pressTimer = useRef(null);
    const startPress = useCallback(() => {
        if (pressTimer.current) clearTimeout(pressTimer.current);
        pressTimer.current = setTimeout(() => {
            pressTimer.current = null;
            setConfirmRemove(true);
        }, 700);
    }, []);
    const cancelPress = useCallback(() => {
        if (pressTimer.current) {
            clearTimeout(pressTimer.current);
            pressTimer.current = null;
        }
    }, []);
    useEffect(() => () => cancelPress(), [cancelPress]);

    // Pressing Back / Escape while the confirm card is showing
    // should DISMISS the confirm, not navigate away from Library.
    // We capture-phase the listener so it fires before Library's
    // page-level Back handler.
    const cancelConfirmRef = useRef(null);
    useEffect(() => {
        if (!confirmRemove) return undefined;
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                setConfirmRemove(false);
            }
        };
        window.addEventListener('keydown', onKey, true);
        // Imperatively focus Cancel when the confirm appears so
        // the safe action is highlighted by default.
        const grab = () => {
            const btn = cancelConfirmRef.current;
            if (!btn) return;
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== btn) el.removeAttribute('data-focused');
                });
            try { btn.focus({ preventScroll: true }); } catch { /* ignore */ }
            btn.setAttribute('data-focused', 'true');
        };
        grab();
        const r = requestAnimationFrame(grab);
        const t1 = setTimeout(grab, 60);
        const t2 = setTimeout(grab, 160);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            cancelAnimationFrame(r);
            clearTimeout(t1);
            clearTimeout(t2);
        };
    }, [confirmRemove]);

    const tileSize = big
        ? { width: '100%', minWidth: 0 }
        : { width: '100%', minWidth: 0 };

    const handleClick = () => {
        if (confirmRemove) return;
        onPlay();
    };
    const handleKeyDown = (e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13) {
            if (!e.repeat) startPress();
        }
    };
    const handleKeyUp = (e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13) {
            const wasShortPress = !!pressTimer.current;
            cancelPress();
            if (wasShortPress && !confirmRemove) {
                e.preventDefault();
                onPlay();
            }
        }
    };

    if (confirmRemove) {
        return (
            <div
                className="relative overflow-hidden"
                style={{
                    ...tileSize,
                    aspectRatio: '16 / 9',
                    borderRadius: big ? 18 : 14,
                    background: 'rgba(11,19,34,0.92)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'center',
                    alignItems: 'center',
                    gap: 10,
                    padding: 14,
                }}
            >
                <div
                    style={{
                        fontSize: 13,
                        color: 'var(--vesper-text-2)',
                        textAlign: 'center',
                        lineHeight: 1.3,
                        padding: '0 6px',
                    }}
                >
                    Remove from Watch Later?
                </div>
                <div className="flex gap-2">
                    <button
                        ref={cancelConfirmRef}
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={() => setConfirmRemove(false)}
                        style={{
                            padding: '7px 14px',
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.10)',
                            color: '#fff',
                            fontWeight: 600,
                            fontSize: 12,
                            border: 'none',
                        }}
                    >
                        Cancel
                    </button>
                    <button
                        data-testid={`watch-later-remove-confirm-${item.type}-${item.id}`}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => {
                            setConfirmRemove(false);
                            onRemove();
                        }}
                        style={{
                            padding: '7px 14px',
                            borderRadius: 999,
                            background: '#FF6B6B',
                            color: '#fff',
                            fontWeight: 600,
                            fontSize: 12,
                            border: 'none',
                        }}
                    >
                        Remove
                    </button>
                </div>
            </div>
        );
    }

    return (
        <button
            data-testid={`watch-later-${item.type}-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={handleClick}
            onKeyDown={handleKeyDown}
            onKeyUp={handleKeyUp}
            onMouseDown={startPress}
            onMouseUp={cancelPress}
            onMouseLeave={cancelPress}
            className="relative overflow-hidden text-left block"
            style={{
                ...tileSize,
                aspectRatio: '16 / 9',
                borderRadius: big ? 18 : 14,
                background: '#0B1322',
                border: '1px solid rgba(255,255,255,0.06)',
                padding: 0,
            }}
        >
            {thumb ? (
                <img
                    src={thumb}
                    alt={title || ''}
                    loading="lazy"
                    decoding="async"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                    }}
                />
            ) : null}

            {/* Bottom gradient for legibility */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(0,0,0,0) 30%, rgba(0,0,0,0.85) 100%)',
                }}
            />

            {/* Play badge bottom-left */}
            <div
                className="absolute"
                style={{
                    left: 14,
                    bottom: big ? 26 : 22,
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: big ? 40 : 36,
                    height: big ? 40 : 36,
                    borderRadius: 999,
                    background: 'rgba(11,19,34,0.7)',
                    border: '1px solid rgba(255,255,255,0.18)',
                    backdropFilter: 'blur(8px)',
                }}
            >
                <Play
                    size={big ? 16 : 14}
                    fill="#fff"
                    color="#fff"
                    style={{ marginLeft: 2 }}
                />
            </div>

            {/* Title + subtitle */}
            <div
                className="absolute"
                style={{
                    left: 14,
                    right: 14,
                    bottom: big ? 12 : 10,
                }}
            >
                <div
                    style={{
                        fontSize: 'clamp(13px, 1vw, 16px)',
                        fontWeight: 700,
                        color: '#fff',
                        textShadow: '0 1px 4px rgba(0,0,0,0.55)',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        paddingLeft: big ? 50 : 46,
                    }}
                >
                    {title}
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.14em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue)',
                        marginTop: 3,
                        paddingLeft: big ? 50 : 46,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                    }}
                >
                    {subtitle}
                </div>
            </div>
        </button>
    );
}
