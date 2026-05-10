import React, { useRef } from 'react';
import PosterTile from './PosterTile';

export default function Shelf({ shelf, onSelect }) {
    const scroller = useRef(null);

    return (
        <section
            data-testid={`shelf-${shelf.id}`}
            className="relative w-full"
            style={{ paddingTop: 48, paddingBottom: 16 }}
        >
            <header
                className="flex items-end justify-between mb-5"
                style={{ paddingLeft: 180, paddingRight: 80 }}
            >
                <div className="flex items-baseline gap-4">
                    {shelf.eyebrow && (
                        <span className="vesper-eyebrow">{shelf.eyebrow}</span>
                    )}
                    <h2
                        className="vesper-display"
                        style={{
                            fontSize: 34,
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        {shelf.title}
                    </h2>
                </div>
                <span
                    className="vesper-mono"
                    style={{
                        color: 'var(--vesper-text-3)',
                        fontSize: 11,
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {shelf.items.length} titles
                </span>
            </header>

            <div
                ref={scroller}
                className="vesper-shelf flex gap-6"
                style={{
                    paddingLeft: 180,
                    paddingRight: 180,
                    paddingTop: 28,
                    paddingBottom: 56,
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
