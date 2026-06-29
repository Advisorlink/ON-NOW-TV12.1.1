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
    Users,
    MoreHorizontal,
    ChevronDown,
    ChevronUp,
    Bell,
    Trash2,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useFocusRestore from '@/hooks/useFocusRestore';
import useLongPress from '@/hooks/useLongPress';
import {
    listFavouritesByType,
    listWatchLater,
    removeFromWatchLater,
    listActors,
    listNotifyList,
    removeFromNotifyList,
} from '@/lib/library';
import LibraryCalendar from '@/components/LibraryCalendar';
import { Vesper } from '@/lib/api';

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
    // v2.10.83 — Restore focus to the exact tile when returning
    // from a Detail / Actor page.  Library entries hydrate from
    // localStorage synchronously so `ready: true` is fine.
    useFocusRestore({ ready: true });
    const [tv, setTv] = useState(listFavouritesByType('series'));
    const [watchLater, setWatchLater] = useState(listWatchLater());
    const [actors, setActors] = useState(listActors());
    const [notifyItems, setNotifyItems] = useState(listNotifyList());
    const [expanded, setExpanded] = useState(false);
    const [calendarOpen, setCalendarOpen] = useState(false);
    /* Per-section expand toggles — "click the three dots to extend
     * the section past 2 rows".  Default = collapsed so the page
     * stays scannable; user clicks to reveal the full grid. */
    const [tvExpanded, setTvExpanded] = useState(false);
    const [actorsExpanded, setActorsExpanded] = useState(false);
    const [notifyExpanded, setNotifyExpanded] = useState(false);
    /* Notifications popover (replaces the in-row Notifications
     * section — user feedback: shouldn't clutter the main grid).
     * Toggled by the bell button in the header. */
    const [notifyPopoverOpen, setNotifyPopoverOpen] = useState(false);

    useEffect(() => {
        const sync = () => {
            setTv(listFavouritesByType('series'));
            setWatchLater(listWatchLater());
            setActors(listActors());
            setNotifyItems(listNotifyList());
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
                /* v2.7.85 — Page padding tuned for 1920×1080 TV.  Was
                   100 / 60 / 48 / 120; the right column + first row
                   were getting clipped because the focused-tile
                   transform: scale(1.08) translateY(-2px) plus the
                   1.5 px outline + 2 px outline-offset = ~10–14 px
                   overrun beyond the cell.  Bumped paddingRight 60→84
                   and paddingTop 48→64 so corner / top-row tiles
                   have room to scale without being snipped by
                   overflowX: hidden. */
                paddingLeft: 100,
                paddingRight: 84,
                paddingTop: 64,
                paddingBottom: 120,
            }}
        >
            <Header
                onBack={() => navigate('/')}
                notifyCount={notifyItems.length}
                onNotifyOpen={() => setNotifyPopoverOpen(true)}
            />

            <Section
                icon={Tv}
                eyebrow="My library · Series"
                title="TV Shows"
                collapsible={tv.length > 6}
                expanded={tvExpanded}
                onToggle={() => setTvExpanded((v) => !v)}
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
                ) : tv.length > 14 ? (
                    <CollapsibleGrid
                        expanded={tvExpanded}
                        onToggle={() => setTvExpanded((v) => !v)}
                        items={tv}
                        renderGrid={(slice) => (
                            <FavouriteGrid items={slice} type="series" />
                        )}
                    />
                ) : (
                    <FavouriteGrid items={tv} type="series" />
                )}
            </Section>

            {actors.length > 0 && (
                <Section
                    icon={Users}
                    eyebrow="My library · Actors"
                    title="My Actors"
                    collapsible={actors.length > 8}
                    expanded={actorsExpanded}
                    onToggle={() => setActorsExpanded((v) => !v)}
                >
                    {actors.length > 14 ? (
                        <CollapsibleGrid
                            expanded={actorsExpanded}
                            onToggle={() => setActorsExpanded((v) => !v)}
                            items={actors}
                            renderGrid={(slice) => <ActorGrid items={slice} />}
                        />
                    ) : (
                        <ActorGrid items={actors} />
                    )}
                </Section>
            )}

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

            {notifyPopoverOpen && (
                <NotifyPopover
                    items={notifyItems}
                    onClose={() => setNotifyPopoverOpen(false)}
                    onOpen={(n) => {
                        if (String(n.id).startsWith('tt')) {
                            navigate(`/title/${n.type || 'movie'}/${n.id}`);
                        }
                        setNotifyPopoverOpen(false);
                    }}
                    onRemove={(n) => {
                        removeFromNotifyList(n.id);
                        setNotifyItems(listNotifyList());
                    }}
                />
            )}
        </div>
    );
}

/* ----------------------------- Header ----------------------------- */

function Header({ onBack, notifyCount, onNotifyOpen }) {
    return (
        <div className="flex items-center justify-between" style={{ gap: 20, marginBottom: 40 }}>
            <div className="flex items-center gap-5">
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

            {/* Notifications button — opens a popover with the user's
                full reminder list (titles they asked to be notified
                about when streams drop).  Replaces the in-row
                "Notifications" section so the cover grids stay
                pristine.  Bell badge is hidden when there are zero
                pending notifications. */}
            <button
                data-testid="library-notifications-toggle"
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={onNotifyOpen}
                aria-label={`Notifications (${notifyCount})`}
                className="relative flex items-center gap-2 rounded-full"
                style={{
                    height: 46,
                    padding: '0 18px 0 14px',
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    color: 'var(--vesper-text)',
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 11,
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    fontWeight: 600,
                }}
            >
                <Bell size={16} strokeWidth={2} style={{ color: 'var(--vesper-blue-bright)' }} />
                Reminders
                {notifyCount > 0 && (
                    <span
                        aria-hidden
                        style={{
                            minWidth: 22,
                            height: 22,
                            padding: '0 7px',
                            borderRadius: 999,
                            background: 'var(--vesper-blue, #5DC8FF)',
                            color: '#0A0F1A',
                            fontSize: 11,
                            fontWeight: 800,
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            lineHeight: 1,
                            marginLeft: 6,
                        }}
                    >
                        {notifyCount > 99 ? '99+' : notifyCount}
                    </span>
                )}
            </button>
        </div>
    );
}

/* ----------------------------- Section ----------------------------- */

function Section({
    icon: Icon,
    eyebrow,
    title,
    style,
    action,
    children,
    collapsible = false,
    expanded = false,
    onToggle,
}) {
    return (
        <section style={style}>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    /* v2.7.07 — align the eyebrow with the title TEXT
                     * (not the icon).  Icon (24px) + gap-3 (12px) = 36px
                     * — push the eyebrow right by that so it sits
                     * cleanly above the heading text, not the icon. */
                    marginLeft: 36,
                    marginBottom: 12,
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
                <div className="flex items-center gap-2">
                    {action}
                    {collapsible && (
                        <button
                            data-testid={`section-toggle-${(title || '').toLowerCase().replace(/\s+/g, '-')}`}
                            data-focusable="true"
                            data-focus-style="quiet"
                            tabIndex={0}
                            onClick={onToggle}
                            aria-label={expanded ? 'Collapse section' : 'Expand section'}
                            aria-expanded={expanded}
                            className="flex items-center justify-center rounded-full"
                            style={{
                                width: 36,
                                height: 36,
                                background: 'rgba(255,255,255,0.06)',
                                border: '1px solid rgba(255,255,255,0.12)',
                                color: 'var(--vesper-text-2)',
                                cursor: 'pointer',
                                transition: 'background 200ms, color 200ms',
                            }}
                        >
                            {expanded ? (
                                <ChevronUp size={16} strokeWidth={2.2} />
                            ) : (
                                <MoreHorizontal size={16} strokeWidth={2.2} />
                            )}
                        </button>
                    )}
                </div>
            </div>
            {children}
        </section>
    );
}

/* CollapsibleGrid — wraps the grid in a maxHeight container that
 * shows ~2 rows worth of tiles by default, followed by an easy-to-
 * see "Show more" pill that mirrors the row's tile aspect.
 *
 * v2.8.88 — User asked for:
 *   1. Pressing D-pad DOWN from a TV Shows tile to skip the masked
 *      tiles and land on Actors (the next Section).  The previous
 *      implementation rendered ALL tiles even when collapsed, just
 *      mask-faded them — so focus iterated through them.  We now
 *      slice the children so off-screen tiles aren't focusable,
 *      and re-render everything when the user explicitly expands.
 *   2. A more discoverable "Show more" affordance — a labeled pill
 *      tile at the end of the row instead of a tiny "..." icon.
 *   3. The header's expand button still works for parity (some
 *      users will go to the header by reflex).
 */
function CollapsibleGrid({ expanded, onToggle, items, renderGrid, collapsedLimit = 14 }) {
    const overflow = !expanded && items.length > collapsedLimit;
    const slice = overflow ? items.slice(0, collapsedLimit) : items;
    const hiddenCount = overflow ? items.length - collapsedLimit : 0;
    const showPill = items.length > collapsedLimit;

    return (
        <div style={{ position: 'relative' }}>
            {/* Wrap the visible slice so spatial focus only sees
                what's actually painted.  No mask — physically slice
                instead of fading, so pressing DOWN on the bottom-
                visible row goes straight to the next <Section>. */}
            <div style={{ paddingBottom: overflow ? 6 : 0 }}>
                {renderGrid(slice)}
            </div>
            {showPill && (
                <div
                    style={{
                        display: 'flex',
                        justifyContent: 'flex-start',
                        padding: '14px 14px 4px',
                    }}
                >
                    <button
                        data-testid={expanded
                            ? 'section-show-less'
                            : 'section-show-more'}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onToggle}
                        className="vesper-mono"
                        style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 10,
                            height: 44,
                            padding: '0 22px',
                            borderRadius: 999,
                            background: 'linear-gradient(135deg, rgba(93,200,255,0.20) 0%, rgba(93,200,255,0.08) 100%)',
                            border: '1px solid rgba(93,200,255,0.45)',
                            color: 'var(--vesper-text)',
                            fontSize: 11,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            fontWeight: 700,
                            cursor: 'pointer',
                            boxShadow: '0 4px 18px rgba(93,200,255,0.08)',
                        }}
                    >
                        {expanded ? (
                            <>
                                <ChevronUp size={14} strokeWidth={2.2} />
                                Show less
                            </>
                        ) : (
                            <>
                                <ChevronDown size={14} strokeWidth={2.2} />
                                Show {hiddenCount > 0 ? `${hiddenCount} more` : 'more'}
                            </>
                        )}
                    </button>
                </div>
            )}
        </div>
    );
}

/* NotifyPopover — opened by the bell button in the Library header.
 * Modal-style card pinned to the top right (mirrors the in-app
 * NotifyHitWatcher placement) listing every notify-list item with
 * remove + open actions.  Escape / Backspace closes the popover. */
function NotifyPopover({ items, onClose, onOpen, onRemove }) {
    const containerRef = React.useRef(null);

    /* Esc / Back closes us before propagating to the page-level
     * back handler so we don't kick the user out to Home as well. */
    React.useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onClose();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onClose]);

    /* v2.8.88 — Focus management:
     *   1. When the popover mounts, focus jumps to the TOP item
     *      (first reminder row).  User explicitly asked: "When you
     *      click on the Reminders section, the focus needs to go to
     *      the top tile".
     *   2. Focus stays trapped inside the popover.  Any external
     *      focusin event snaps focus back to the first focusable
     *      child so the D-pad can't escape behind the modal. */
    React.useEffect(() => {
        const t = setTimeout(() => {
            const c = containerRef.current;
            if (!c) return;
            const first = c.querySelector('[data-focusable="true"], button');
            try { first?.focus(); } catch { /* ignore */ }
        }, 60);
        return () => clearTimeout(t);
    }, []);
    React.useEffect(() => {
        const onFocus = (e) => {
            const c = containerRef.current;
            if (!c || c.contains(e.target)) return;
            e.stopPropagation();
            const first = c.querySelector('[data-focusable="true"], button');
            try { first?.focus(); } catch { /* ignore */ }
        };
        document.addEventListener('focusin', onFocus, true);
        return () => document.removeEventListener('focusin', onFocus, true);
    }, []);

    return (
        <div
            data-testid="notify-popover"
            role="dialog"
            aria-label="Reminders"
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 9999,
                background: 'rgba(4,6,12,0.65)',
                backdropFilter: 'blur(8px)',
                animation: 'vesper-fadein 200ms ease both',
            }}
            onClick={onClose}
        >
            <div
                ref={containerRef}
                onClick={(e) => e.stopPropagation()}
                style={{
                    position: 'absolute',
                    top: 110,
                    right: 'clamp(40px, 4vw, 80px)',
                    width: 'min(460px, 90vw)',
                    maxHeight: 'calc(100vh - 160px)',
                    overflowY: 'auto',
                    background: '#0E1422',
                    border: '1px solid rgba(var(--vesper-blue-rgb, 93,200,255), 0.45)',
                    borderRadius: 16,
                    boxShadow: '0 24px 60px rgba(0,0,0,0.6)',
                    padding: 22,
                    color: 'var(--vesper-text)',
                    animation: 'vesper-popover-in 240ms cubic-bezier(.16,1,.3,1) both',
                }}
            >
                <div className="flex items-center justify-between" style={{ marginBottom: 16 }}>
                    <div>
                        <div className="vesper-mono" style={{
                            fontSize: 10, letterSpacing: '0.32em', textTransform: 'uppercase',
                            color: 'var(--vesper-blue-bright)',
                        }}>
                            My library · Reminder list
                        </div>
                        <h2 style={{
                            fontSize: 22, fontWeight: 700, marginTop: 4, lineHeight: 1,
                            letterSpacing: '-0.02em', display: 'inline-flex',
                            alignItems: 'center', gap: 8,
                        }}>
                            <Bell size={18} style={{ color: 'var(--vesper-blue)' }} />
                            Reminders
                            <span style={{
                                fontSize: 12, fontWeight: 700,
                                color: 'var(--vesper-text-2)',
                            }}>· {items.length}</span>
                        </h2>
                    </div>
                    <button
                        data-testid="notify-popover-close"
                        onClick={onClose}
                        aria-label="Close"
                        className="flex items-center justify-center rounded-full"
                        style={{
                            width: 34, height: 34,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            color: 'var(--vesper-text-2)', cursor: 'pointer',
                        }}
                    >
                        <X size={15} />
                    </button>
                </div>

                {items.length === 0 ? (
                    <div style={{
                        padding: '32px 12px',
                        textAlign: 'center',
                        color: 'var(--vesper-text-3)',
                        fontSize: 13,
                        lineHeight: 1.55,
                    }}>
                        No reminders yet.<br />
                        Tap "Notify me" on any movie that's not out yet and
                        it'll appear here.
                    </div>
                ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                        {items.map((n) => (
                            <NotifyRow
                                key={n.id}
                                notify={n}
                                onOpen={() => onOpen(n)}
                                onRemove={() => onRemove(n)}
                            />
                        ))}
                    </div>
                )}
            </div>

            <style>{`
@keyframes vesper-popover-in {
    from { opacity: 0; transform: translateY(-8px) scale(0.98); }
    to   { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes vesper-fadein {
    from { opacity: 0; }
    to   { opacity: 1; }
}
            `}</style>
        </div>
    );
}

function NotifyRow({ notify, onOpen, onRemove }) {
    const meta = notify.meta || {};
    return (
        <div
            data-testid={`notify-row-${notify.id}`}
            className="flex items-center gap-3"
            style={{
                padding: '10px 12px',
                background: 'rgba(255,255,255,0.03)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 10,
            }}
        >
            <button
                onClick={onOpen}
                data-focusable="true"
                tabIndex={0}
                aria-label={`Open ${meta.name || 'item'}`}
                className="flex items-center gap-3 flex-1 min-w-0 text-left"
                style={{ background: 'transparent', border: 'none', cursor: 'pointer' }}
            >
                <div style={{
                    width: 52, height: 78, flexShrink: 0,
                    borderRadius: 6,
                    background: meta.poster
                        ? `center/cover url(${meta.poster})`
                        : 'rgba(var(--vesper-blue-rgb), 0.15)',
                    border: '1px solid rgba(255,255,255,0.08)',
                }} />
                <div className="min-w-0 flex-1">
                    <div style={{
                        fontSize: 13, fontWeight: 700,
                        color: 'var(--vesper-text)', lineHeight: 1.25,
                        overflow: 'hidden', textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                    }}>
                        {meta.name || 'Untitled'}
                    </div>
                    <div className="vesper-mono" style={{
                        marginTop: 4, fontSize: 9, letterSpacing: '0.2em',
                        textTransform: 'uppercase', color: 'var(--vesper-blue-bright)',
                    }}>
                        <Bell size={9} style={{ display: 'inline-block', marginRight: 4 }} />
                        Notify on HD
                    </div>
                </div>
            </button>
            <button
                data-testid={`notify-row-remove-${notify.id}`}
                onClick={onRemove}
                aria-label="Remove"
                data-focusable="true"
                tabIndex={0}
                className="flex items-center justify-center rounded-full"
                style={{
                    width: 32, height: 32, flexShrink: 0,
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.10)',
                    color: 'var(--vesper-text-2)', cursor: 'pointer',
                }}
            >
                <Trash2 size={13} />
            </button>
        </div>
    );
}

/* NotifyGrid — Library "Notifications" section.  Each card shows
   a notification + the next episode air date.                       */
function NotifyGrid({ items, onOpen, onRemove }) {
    return (
        <div
            className="grid"
            style={{
                /* v2.7.85 — Match other Library grids for consistent
                   breathing room around the focused 1.08× scale. */
                gridTemplateColumns: 'repeat(auto-fill, minmax(160px, 1fr))',
                gap: 18,
                padding: '12px 14px',
            }}
        >
            {items.map((n) => (
                <NotifyCard
                    key={n.id}
                    notify={n}
                    onOpen={() => onOpen(n)}
                    onRemove={() => onRemove(n)}
                />
            ))}
        </div>
    );
}

function NotifyCard({ notify, onOpen, onRemove }) {
    const meta = notify.meta || {};
    return (
        <div
            data-testid={`notify-card-${notify.id}`}
            className="relative overflow-hidden"
            style={{
                aspectRatio: '2 / 3',
                borderRadius: 10,
                background: meta.poster
                    ? '#1a1f2e'
                    : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.2), rgba(10,14,26,0.9))',
                border: '1px solid rgba(255,255,255,0.08)',
            }}
        >
            <button
                data-focusable="true"
                data-focus-style="tile"
                tabIndex={0}
                onClick={onOpen}
                className="absolute inset-0 text-left"
                style={{ border: 'none', background: 'transparent', cursor: 'pointer' }}
                aria-label={`Open ${meta.name || 'item'}`}
            >
                {meta.poster && (
                    <img
                        src={meta.poster}
                        alt={meta.name || ''}
                        loading="lazy"
                        className="w-full h-full object-cover"
                    />
                )}
                <div
                    className="absolute inset-x-0 bottom-0"
                    style={{
                        padding: '10px 10px 8px',
                        background:
                            'linear-gradient(180deg, rgba(10,15,26,0) 0%, rgba(10,15,26,0.9) 70%)',
                    }}
                >
                    <div
                        style={{
                            fontSize: 12,
                            fontWeight: 700,
                            color: '#fff',
                            lineHeight: 1.2,
                            display: '-webkit-box',
                            WebkitLineClamp: 2,
                            WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                            textShadow: '0 1px 4px rgba(0,0,0,0.55)',
                        }}
                    >
                        {meta.name || 'Untitled'}
                    </div>
                    <div
                        style={{
                            marginTop: 4,
                            fontSize: 9,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-blue-bright)',
                            fontFamily: 'monospace',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 4,
                        }}
                    >
                        <Bell size={9} />
                        Notify on HD
                    </div>
                </div>
            </button>
            <button
                data-testid={`notify-remove-${notify.id}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={(e) => { e.stopPropagation(); onRemove(); }}
                aria-label="Remove from notifications"
                className="absolute"
                style={{
                    top: 6,
                    right: 6,
                    width: 26,
                    height: 26,
                    borderRadius: '50%',
                    background: 'rgba(0,0,0,0.55)',
                    border: '1px solid rgba(255,255,255,0.15)',
                    color: '#fff',
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    cursor: 'pointer',
                    zIndex: 2,
                }}
            >
                <Trash2 size={11} />
            </button>
        </div>
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
                /* v2.8.88 — Per user: TV Shows + Movies should be
                   EXACTLY 7 across (was auto-fill which yielded 6
                   at the box's content width).  Padding and gap
                   preserved so the 1.08× focused scale still has
                   breathing room. */
                gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                gap: 18,
                padding: '12px 14px',
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
    const onLongPress = async () => {
        // v2.10.82 — Lazy-fetch the synopsis from the meta endpoint
        // since the library's stored `meta` doesn't carry it (only
        // name/poster/year).  Fire the event WITHOUT synopsis first
        // so the modal appears instantly, then update by re-firing
        // once the description lands.  Cached on the backend so the
        // second-and-onward long-press is sub-100 ms.
        const baseDetail = {
            id: item.id,
            type,
            title: item.meta?.name,
            poster,
            year: item.meta?.year,
        };
        window.dispatchEvent(
            new CustomEvent('vesper:request-add-to-list', {
                detail: baseDetail,
            })
        );
        try {
            const m = await Vesper.getMeta(type, item.id);
            const description = m?.data?.meta?.description;
            const background = m?.data?.meta?.background;
            const genres = m?.data?.meta?.genres;
            if (description || background || genres) {
                window.dispatchEvent(
                    new CustomEvent('vesper:request-add-to-list', {
                        detail: {
                            ...baseDetail,
                            synopsis: description,
                            background,
                            genres,
                        },
                    })
                );
            }
        } catch { /* swallow — modal still works without it */ }
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

/* --------------------------- Actor grid --------------------------- */

function ActorGrid({ items }) {
    return (
        <div
            className="grid"
            style={{
                /* v2.8.88 — Per user: Actors should be 7 across
                   matching the TV Shows / Movies grids. */
                gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                gap: 18,
                padding: '12px 14px',
            }}
        >
            {items.map((a) => (
                <ActorCard key={a.id} actor={a} />
            ))}
        </div>
    );
}

function ActorCard({ actor }) {
    const navigate = useNavigate();
    const [focused, setFocused] = useState(false);
    const onTap = () => {
        if (actor.id != null) navigate(`/person/${actor.id}`);
    };
    const onLongPress = () => {
        window.dispatchEvent(
            new CustomEvent('vesper:request-add-to-list', {
                detail: {
                    id: actor.id,
                    type: 'actor',
                    title: actor.name,
                    poster: actor.profile || null,
                },
            })
        );
    };
    const press = useLongPress(onLongPress, onTap);
    return (
        <button
            data-testid={`actor-${actor.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            {...press}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            onMouseEnter={() => setFocused(true)}
            onMouseLeave={(e) => {
                setFocused(false);
                press.onMouseLeave?.(e);
            }}
            className="block relative overflow-hidden text-left"
            style={{
                aspectRatio: '2 / 3',
                borderRadius: 10,
                padding: 0,
                border: 'none',
                background: actor.profile
                    ? '#1a1f2e'
                    : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.2), rgba(10,14,26,0.9))',
            }}
        >
            {actor.profile ? (
                <img
                    src={actor.profile}
                    alt={actor.name}
                    loading="lazy"
                    className="w-full h-full object-cover"
                    style={{
                        // v2.7.32 — black-and-white at rest, full
                        // colour on hover / D-pad focus.  Matches
                        // the CastRow behaviour on Detail pages so
                        // the Library actor grid "comes alive" as
                        // the user navigates through it.
                        filter: focused
                            ? 'grayscale(0) contrast(1.05)'
                            : 'grayscale(1) contrast(1.05)',
                        transition: 'filter 200ms ease',
                    }}
                />
            ) : (
                <div
                    className="absolute inset-0 flex items-center justify-center vesper-display"
                    style={{
                        color: 'var(--vesper-blue-bright)',
                        fontSize: 56,
                        fontWeight: 700,
                    }}
                >
                    {(actor.name || '?')[0]?.toUpperCase()}
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
                    {actor.name}
                </div>
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
                            /* v2.7.07 — align with the heading text
                             * (bookmark icon 24px + gap-3 12px = 36px). */
                            marginLeft: 36,
                            marginBottom: 10,
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
