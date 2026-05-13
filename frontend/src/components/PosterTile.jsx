import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';

/**
 * Poster tile.  Image rendering is deferred until the tile is
 * within ~2 viewport heights of the visible area — saves
 * substantial decoded-bitmap RAM on cheap boxes (1 GB Mali devices)
 * and lets the For You page stay buttery smooth even with 8 shelves
 * × 24 tiles loaded.  Once an image has been shown ONCE we keep it
 * mounted so a focus-return doesn't repaint a placeholder.
 */
export default function PosterTile({ item, onSelect }) {
    const navigate = useNavigate();
    const ref = useRef(null);
    const [show, setShow] = useState(false);

    useEffect(() => {
        if (show) return;
        const el = ref.current;
        if (!el) return;
        if (typeof IntersectionObserver === 'undefined') {
            setShow(true);
            return undefined;
        }
        const io = new IntersectionObserver(
            (entries) => {
                for (const e of entries) {
                    if (e.isIntersecting) {
                        setShow(true);
                        io.disconnect();
                        return;
                    }
                }
            },
            { rootMargin: '1600px 800px' }
        );
        io.observe(el);
        return () => io.disconnect();
    }, [show]);

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
            ref={ref}
            data-testid={`poster-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={handleClick}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                width: 'clamp(120px, 10.5vw, 180px)',
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
            }}
        >
            {item.poster && show ? (
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
