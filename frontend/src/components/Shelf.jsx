import React, { useEffect, useRef, useState } from 'react';
import PosterTile from './PosterTile';
import { getAutoTrailer } from '@/lib/prefs';

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
    const [wideIdx, setWideIdx] = useState(shelf.items.length > 1 ? 1 : 0);

    // Focus-lock: the focused tile always parks in the SECOND slot
    // and the row slides underneath it (leanback style), so the
    // wide trailer card never wanders across the screen.
    const lockToSlot = (e) => {
        const el = scroller.current;
        const tile = e.target?.closest?.('[data-preview="true"]');
        if (!el || !tile || !el.contains(tile)) return;
        const tiles = el.querySelectorAll('[data-preview="true"]');
        const idx = Array.prototype.indexOf.call(tiles, tile);
        if (idx < 0) return;
        setWideIdx(idx);
        let target = 0;
        if (idx >= 1) {
            // Slot pitch from a NARROW tile's layout width + the row gap.
            const base = Array.prototype.find.call(
                tiles,
                (t) => t.getAttribute('data-preview-active') !== 'true' &&
                    t.getAttribute('data-preview-wide') !== 'true',
            ) || tiles[0];
            const gap = parseFloat(getComputedStyle(el).columnGap) || 0;
            target = Math.max(0, (idx - 1) * (base.offsetWidth + gap));
        }
        if (Math.abs(el.scrollLeft - target) < 2) return;
        el.scrollLeft = target;
    };

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
                    {shelf.items.length} titles
                </span>
            </header>

            <div
                ref={scroller}
                className="vesper-shelf flex"
                onFocus={lockToSlot}
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
                    // Use scroll-snap so D-pad left/right anchors
                    // tiles to a consistent X — kills the slight
                    // drift the user sees inside long rows.
                    overscrollBehavior: 'contain',
                }}
            >
                {shelf.items.map((item, idx) => (
                    <PosterTile
                        key={item.id}
                        item={item}
                        onSelect={onSelect}
                        onLongPress={onLongPress}
                        wide={trailers && idx === wideIdx}
                        initialFocus={firstTileInitialFocus && idx === 0}
                    />
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
                {shelf.empty && shelf.items.length === 0 && (
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
