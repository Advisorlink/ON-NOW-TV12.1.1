import React from 'react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';
import useLongPress from '@/hooks/useLongPress';

/**
 * Poster tile.  Image renders immediately on mount — we don't try
 * to be clever about deferring decode because the user reported
 * seeing placeholder text flash during fast D-pad scrolls.  Browser
 * native `loading="lazy"` already handles off-screen rate-limiting,
 * and the parent `<Lazy>` shelf wrapper still skips work for shelves
 * far below the viewport.
 *
 * Press-and-hold OK (or mouse) to fire the global "Add to My List"
 * modal — short-tap still navigates to the detail page.
 */
export default function PosterTile({ item, onSelect }) {
    const navigate = useNavigate();

    const onTap = () => {
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

    const onLongPress = () => {
        const id = item.imdbId || item.id;
        if (!id || !id.toString().startsWith('tt')) return;
        window.dispatchEvent(
            new CustomEvent('vesper:request-add-to-list', {
                detail: {
                    id,
                    type: item.type || 'movie',
                    title: item.title,
                    poster: item.poster ? img.poster(item.poster) : null,
                    background: item.background
                        ? img.backdrop(item.background)
                        : null,
                    year: item.year || item.sub,
                    genres: item.genres,
                    synopsis: item.description,
                },
            })
        );
    };

    const press = useLongPress(onLongPress, onTap);

    return (
        <button
            data-testid={`poster-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            {...press}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                width: 'clamp(120px, 10.5vw, 180px)',
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
            }}
        >
            {item.poster ? (
                <img
                    src={img.poster(item.poster)}
                    alt={item.title}
                    loading="lazy"
                    decoding="async"
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
                            color: 'rgba(var(--vesper-blue-rgb),0.18)',
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
                        fontSize: 'clamp(13px, 1vw, 17px)',
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
                            fontSize: 'clamp(9px, 0.62vw, 11px)',
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
