import React, { useEffect, useRef, useState } from 'react';
import PosterTile from './PosterTile';
import { getAutoTrailer } from '@/lib/prefs';
import useShelfMotion from '@/hooks/useShelfMotion';
import { getPerf, PERF_CHANGE } from '@/lib/perfLab';
import { fetchShelfItems } from '@/hooks/useLiveShelves';

/**
 * Responsive shelf row.  All horizontal paddings + gaps + type sizes
 * scale via clamp() so the layout stays readable from a 720p browser
 * window all the way up to 4K.  The left rail (SideNav) is 108px when
 * collapsed, so the minimum left padding stays well clear of it.
 *
 * A 16:9 "trailer rectangle" is PERMANENTLY parked in slot 2 of every
 * row (slot 1 only while the very first card is focused): the card in
 * that slot renders wide with its backdrop art, and the row slides
 * underneath it as focus moves — nothing expands or stretches.
 */
export default function Shelf({ shelf, onSelect, onLongPress, firstTileInitialFocus = false }) {
    const scroller = useRef(null);
    // Trailers OFF in Settings → no wide rectangle, plain covers only.
    const [trailers, setTrailers] = useState(() => getAutoTrailer());
    useEffect(() => {
        const sync = () => setTrailers(getAutoTrailer());
        window.addEventListener('vesper:auto-trailer-change', sync);
        window.addEventListener('vesper:profile-change', sync);
        return () => {
            window.removeEventListener('vesper:auto-trailer-change', sync);
            window.removeEventListener('vesper:profile-change', sync);
        };
    }, []);
    // Lazy row-loading strategy: a pending stub fetches its own items
    // once this Shelf mounts (Home's <Lazy> mounts rows near the screen).
    const [lazyItems, setLazyItems] = useState(null);
    useEffect(() => {
        if (!shelf.pending) { setLazyItems(null); return undefined; }
        let alive = true;
        fetchShelfItems(shelf).then((items) => { if (alive) setLazyItems(items); }).catch(() => { if (alive) setLazyItems([]); });
        return () => { alive = false; };
    }, [shelf]);
    const items = shelf.pending ? (lazyItems || []) : shelf.items;
    const { wideIndex, onFocus } = useShelfMotion(scroller, items.length, trailers);
    // Lazy row whose catalog came back empty / dead → collapse it.
    const collapsed = shelf.pending && lazyItems && lazyItems.length === 0 && !shelf.empty;
    // RecyclerView-style windowing (2026-10-11): only tiles near the
    // focused slot get a real <PosterTile>; the rest are same-size
    // empty cells so row geometry, scroll maths and the FLIP motion
    // are untouched while the DOM per row drops from ~48 tiles to
    // ~19.  D-pad focus only ever moves to an adjacent tile, so the
    // target is always mounted; the window slides with the selection.
    // Window size is a Performance-lab setting (Settings → Performance).
    const [recycle, setRecycle] = useState(() => getPerf('recycle'));
    useEffect(() => {
        const sync = () => setRecycle(getPerf('recycle'));
        window.addEventListener(PERF_CHANGE, sync);
        return () => window.removeEventListener(PERF_CHANGE, sync);
    }, []);
    const AHEAD = recycle === 'tight' ? 9 : 12;
    const BEHIND = recycle === 'tight' ? 3 : 6;
    const mounted = (idx) => recycle === 'off' || (idx >= wideIndex - BEHIND && idx <= wideIndex + AHEAD);

    if (collapsed) return null;
    return (
        <section
            data-testid={`shelf-${shelf.id}`}
            className="relative w-full vesper-shelf-section"
            style={{
                paddingTop: 'clamp(8px, 0.9vw, 14px)',
                paddingBottom: 'clamp(8px, 0.9vw, 14px)',
            }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                }}
            >
                <div className="flex items-baseline gap-4 min-w-0">
                    {shelf.eyebrow && (
                        <span className="vesper-eyebrow truncate">
                            {shelf.eyebrow}
                        </span>
                    )}
                    <h2
                        className="vesper-display truncate"
                        style={{
                            fontSize: 'clamp(22px, 2.2vw, 34px)',
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        {shelf.title}
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
                    {shelf.pending && !lazyItems ? 'loading…' : `${items.length} titles`}
                </span>
            </header>

            <div
                ref={scroller}
                className="vesper-shelf vesper-motion-shelf flex"
                onFocus={onFocus}
                data-focus-scroll="shelf"
                data-testid={`shelf-row-${shelf.id || shelf.title}`}
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                    paddingTop: 'clamp(26px, 2.4vw, 44px)',
                    paddingBottom: 'clamp(26px, 2.4vw, 44px)',
                    // GPU-accelerated horizontal scrolling on the
                    // WebView.  NOTE: do NOT add `contain: content`
                    // here — it would clip the focused tile's
                    // scale(1.08) transform at the row's bottom
                    // edge.  Virtualisation already happens at the
                    // tile level via `content-visibility: auto`.
                    transform: 'translateZ(0)',
                    willChange: 'scroll-position',
                    // useShelfMotion owns horizontal positioning; no second
                    // scroll-snap or edge-comfort correction may fight it.
                    overscrollBehavior: 'contain',
                }}
            >
                {items.map((item, idx) => (
                    <div
                        key={item.id}
                        data-shelf-cell="true"
                        data-testid={`shelf-cell-${shelf.id}-${item.id}`}
                        className="vesper-shelf-cell shrink-0"
                        style={{ zIndex: trailers && idx === wideIndex ? 2 : 0 }}
                    >
                        {mounted(idx) ? (
                            <PosterTile
                                item={item}
                                onSelect={onSelect}
                                onLongPress={onLongPress}
                                wide={trailers && idx === wideIndex}
                                initialFocus={firstTileInitialFocus && idx === 0}
                            />
                        ) : (
                            <div
                                aria-hidden="true"
                                data-testid={`shelf-recycled-${shelf.id}-${item.id}`}
                                className="rounded-xl"
                                style={{
                                    '--tile-w': 'clamp(132px, 11.5vw, 198px)',
                                    width: 'var(--tile-w)',
                                    height: 'calc(var(--tile-w) * 1.5)',
                                    background: 'var(--vesper-bg-1)',
                                }}
                            />
                        )}
                    </div>
                ))}
                {/* Trailing runway so the last tiles can still park
                    in slot 2 under the focus-lock. */}
                <div aria-hidden="true" className="shrink-0" style={{ width: '100vw', height: 1 }} />
                {/* Dev-Unlock diagnostic — when a row was kept even
                 * though it returned 0 items, render a clear stub
                 * card so the user can see exactly which addon
                 * catalog is failing.  Stays out of production UX
                 * because useLiveShelves skips empty rows unless
                 * unlock is on. */}
                {shelf.empty && items.length === 0 && (
                    <div
                        data-testid={`shelf-empty-${shelf.id}`}
                        style={{
                            minWidth: 220,
                            maxWidth: 320,
                            padding: '16px 18px',
                            background: 'rgba(255,180,60,0.08)',
                            border: '1px dashed rgba(255,180,60,0.45)',
                            borderRadius: 12,
                            color: '#FFD8A1',
                            fontFamily: 'monospace',
                            fontSize: 11,
                            lineHeight: 1.55,
                            flexShrink: 0,
                        }}
                    >
                        <div style={{
                            fontWeight: 800, marginBottom: 6,
                            letterSpacing: '0.18em', fontSize: 9,
                        }}>
                            UNLOCK · EMPTY CATALOG
                        </div>
                        <div>Addon: <b>{shelf.addonName || 'unknown'}</b></div>
                        <div>Catalog: <b>{shelf.title}</b></div>
                        {shelf.error && (
                            <div style={{ marginTop: 6, color: '#FFB069' }}>
                                error: {shelf.error}
                            </div>
                        )}
                    </div>
                )}
            </div>
        </section>
    );
}
