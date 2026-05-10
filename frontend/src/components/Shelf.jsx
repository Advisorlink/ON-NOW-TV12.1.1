import React, { useRef } from 'react';
import PosterTile from './PosterTile';

export default function Shelf({ shelf, onSelect = () => {} }) {
    const scroller = useRef(null);

    return (
        <section
            data-testid={`shelf-${shelf.id}`}
            className="relative w-full"
            style={{ paddingTop: 56, paddingBottom: 24 }}
        >
            <header
                className="flex items-end justify-between mb-6"
                style={{ paddingLeft: 160, paddingRight: 80 }}
            >
                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 44,
                        letterSpacing: '-0.01em',
                        color: 'var(--vesper-text)',
                    }}
                >
                    {shelf.title}
                </h2>
                <span
                    className="vesper-eyebrow"
                    style={{ color: 'var(--vesper-text3)' }}
                >
                    {shelf.items.length} titles
                </span>
            </header>

            <div
                ref={scroller}
                className="vesper-shelf flex gap-7"
                style={{
                    paddingLeft: 160,
                    paddingRight: 160,
                    paddingTop: 32,
                    paddingBottom: 60,
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
