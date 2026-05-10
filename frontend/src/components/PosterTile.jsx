import React from 'react';

export default function PosterTile({ item, onSelect = () => {} }) {
    return (
        <button
            data-testid={`poster-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => onSelect(item)}
            className="group relative shrink-0 overflow-hidden rounded-lg text-left"
            style={{
                width: 280,
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.06)',
            }}
        >
            <img
                src={item.poster}
                alt={item.title}
                loading="lazy"
                className="absolute inset-0 w-full h-full object-cover"
            />

            {/* Always-on bottom scrim for legibility */}
            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(5,5,5,0) 0%, rgba(5,5,5,0.92) 78%, #050505 100%)',
                }}
            />

            {/* Title block */}
            <div className="absolute inset-x-0 bottom-0 p-5">
                <div
                    className="vesper-display"
                    style={{
                        fontSize: 28,
                        lineHeight: 1.05,
                        color: 'var(--vesper-text)',
                    }}
                >
                    {item.title}
                </div>
                <div
                    className="font-mono mt-2"
                    style={{
                        fontSize: 13,
                        letterSpacing: '0.18em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-text2)',
                    }}
                >
                    {item.sub}
                </div>
            </div>

            {/* Subtle copper edge that brightens on focus */}
            <span
                className="absolute inset-0 pointer-events-none rounded-lg"
                style={{
                    boxShadow: 'inset 0 0 0 1px rgba(229,138,89,0)',
                }}
            />
        </button>
    );
}
