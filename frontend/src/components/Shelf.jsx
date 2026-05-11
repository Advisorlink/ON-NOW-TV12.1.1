import React, { useRef } from 'react';
import PosterTile from './PosterTile';

/**
 * Responsive shelf row.  All horizontal paddings + gaps + type sizes
 * scale via clamp() so the layout stays readable from a 720p browser
 * window all the way up to 4K.  The left rail (SideNav) is 108px when
 * collapsed, so the minimum left padding stays well clear of it.
 */
export default function Shelf({ shelf, onSelect }) {
    const scroller = useRef(null);

    return (
        <section
            data-testid={`shelf-${shelf.id}`}
            className="relative w-full vesper-shelf-section"
            style={{
                paddingTop: 'clamp(6px, 0.8vw, 14px)',
                paddingBottom: 4,
            }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{
                    paddingLeft: 'clamp(124px, 9.5vw, 180px)',
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
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    paddingLeft: 'clamp(124px, 9.5vw, 180px)',
                    paddingRight: 'clamp(124px, 9.5vw, 180px)',
                    paddingTop: 'clamp(14px, 1.4vw, 22px)',
                    paddingBottom: 'clamp(14px, 1.4vw, 24px)',
                }}
            >
                {shelf.items.map((item) => (
                    <PosterTile
                        key={item.id}
                        item={item}
                        onSelect={onSelect}
                    />
                ))}
            </div>
        </section>
    );
}
