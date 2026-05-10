import React from 'react';
import { useNavigate } from 'react-router-dom';

export default function PosterTile({ item, onSelect }) {
    const navigate = useNavigate();
    const handleClick = () => {
        if (onSelect) {
            onSelect(item);
        } else if (item.routePath) {
            navigate(item.routePath);
        } else if (item.imdbId) {
            navigate(`/title/${item.type || 'movie'}/${item.imdbId}`);
        } else {
            navigate(`/title/${item.id}`);
        }
    };

    return (
        <button
            data-testid={`poster-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={handleClick}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                width: 264,
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
            }}
        >
            {item.poster ? (
                <img
                    src={item.poster}
                    alt={item.title}
                    loading="lazy"
                    className="absolute inset-0 w-full h-full object-cover"
                />
            ) : (
                <div
                    className="absolute inset-0 flex items-center justify-center"
                    style={{
                        background:
                            'linear-gradient(180deg, var(--vesper-bg-2) 0%, var(--vesper-bg-1) 100%)',
                    }}
                >
                    <span
                        className="vesper-display"
                        style={{
                            fontSize: 64,
                            color: 'rgba(93,200,255,0.18)',
                        }}
                    >
                        {(item.title || '?')[0]}
                    </span>
                </div>
            )}

            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.93) 78%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div className="absolute inset-x-0 bottom-0 p-4">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 19,
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.15,
                        color: 'var(--vesper-text)',
                    }}
                >
                    {item.title}
                </div>
                {item.sub && (
                    <div
                        className="vesper-mono mt-1.5"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        {item.sub}
                    </div>
                )}
            </div>
        </button>
    );
}
