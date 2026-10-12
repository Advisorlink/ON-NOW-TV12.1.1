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
    // Performance-lab knobs (Settings → Performance): tile window + titles per row.
    const [perf, setPerfState] = useState(() => ({ recycle: getPerf('recycle'), limit: Number(getPerf('itemsPerRow')) || 60, posterSize: getPerf('posterSize') }));
    useEffect(() => {
        const sync = () => setPerfState({ recycle: getPerf('recycle'), limit: Number(getPerf('itemsPerRow')) || 60, posterSize: getPerf('posterSize') });
        window.addEventListener(PERF_CHANGE, sync);
        return () => window.removeEventListener(PERF_CHANGE, sync);
    }, []);
    const recycle = perf.recycle;
    const allItems = shelf.pending ? (lazyItems || []) : shelf.items;
    // "Show more" at the end of the row reveals the next batch (and asks
    // the owner for another page via shelf.loadMore when we run dry).
    const [extra, setExtra] = useState(0);
    const [loadingMore, setLoadingMore] = useState(false);
    const focusIdxRef = useRef(null);
    useEffect(() => { setExtra(0); }, [shelf.id]);
    const limit = perf.limit + extra;
    const items = allItems.length > limit ? allItems.slice(0, limit) : allItems;
    const hiddenCount = allItems.length - items.length;
    const canLoadMore = typeof shelf.loadMore === 'function' && shelf.hasMore !== false;
    const showMore = items.length > 0 && (hiddenCount > 0 || canLoadMore);
    const onShowMore = async () => {
        if (loadingMore) return;
        focusIdxRef.current = items.length;
        if (hiddenCount > 0) { setExtra((e) => e + perf.limit); return; }
        setLoadingMore(true);
        try { await shelf.loadMore(); setExtra((e) => e + perf.limit); }
        catch { focusIdxRef.current = null; }
        finally { setLoadingMore(false); }
    };
    // Land on the first newly revealed title so the row keeps flowing.
    useEffect(() => {
        const idx = focusIdxRef.current;
        if (idx == null || idx >= items.length) return;
        focusIdxRef.current = null;
        const cell = scroller.current?.querySelectorAll(':scope > [data-shelf-cell]')[idx];
        const btn = cell?.querySelector('[data-focusable="true"]');
        if (btn) requestAnimationFrame(() => btn.focus({ preventScroll: true }));
    }, [items.length]);
    const { wideIndex, onFocus } = useShelfMotion(scroller, items.length + (showMore ? 1 : 0), trailers);
    // Lazy row whose catalog came back empty / dead → collapse it.
    const collapsed = shelf.pending && lazyItems && lazyItems.length === 0 && !shelf.empty;
    // RecyclerView-style windowing: only tiles near the focused slot get
    // a real <PosterTile>; the rest are same-size empty cells.
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
                    {shelf.pending && !lazyItems ? 'loading…' : `${items.length}${hiddenCount > 0 ? ` of ${allItems.length}` : ''} titles`}
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
                                posterSize={perf.posterSize}
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
                {showMore && (
                    <div
                        data-shelf-cell="true"
                        data-testid={`shelf-cell-${shelf.id}-more`}
                        className="vesper-shelf-cell shrink-0"
                    >
                        <button
                            type="button"
                            data-testid={`shelf-more-${shelf.id}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            data-preview="true"
                            tabIndex={0}
                            onClick={onShowMore}
                            aria-label="Show more titles"
                            className="rounded-xl flex flex-col items-center justify-center"
                            style={{
                                '--tile-w': 'clamp(132px, 11.5vw, 198px)',
                                width: 'var(--tile-w)',
                                height: 'calc(var(--tile-w) * 1.5)',
                                gap: 10,
                                background: 'linear-gradient(160deg, rgba(var(--vesper-blue-rgb),0.18) 0%, rgba(255,255,255,0.04) 100%)',
                                border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                                color: 'var(--vesper-text)',
                                padding: 0,
                            }}
                        >
                            <span
                                className="flex items-center justify-center rounded-full"
                                style={{
                                    width: 52,
                                    height: 52,
                                    background: 'rgba(var(--vesper-blue-rgb),0.22)',
                                    border: '1px solid rgba(var(--vesper-blue-rgb),0.5)',
                                    fontSize: 30,
                                    lineHeight: 1,
                                    fontWeight: 300,
                                    color: 'var(--vesper-blue-bright)',
                                }}
                            >
                                {loadingMore ? '…' : '+'}
                            </span>
                            <span className="vesper-display" style={{ fontSize: 'clamp(14px, 1.1vw, 18px)', fontWeight: 700 }}>
                                {loadingMore ? 'Loading' : 'Show more'}
                            </span>
                            <span
                                className="vesper-mono"
                                style={{ fontSize: 'clamp(9px, 0.62vw, 11px)', letterSpacing: '0.2em', textTransform: 'uppercase', color: 'var(--vesper-text-3)' }}
                            >
                                {hiddenCount > 0 ? `${hiddenCount} more` : 'next page'}
                            </span>
                        </button>
                    </div>
                )}
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
