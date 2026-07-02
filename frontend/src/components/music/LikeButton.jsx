/**
 * LikeButton — single heart button that toggles a music entity
 * (artist | album | track | radio | podcast) in the local library.
 *
 * Stays in sync via `subscribeMusicLibrary` so multiple instances
 * across the page reflect each other immediately.  Stops click
 * propagation so it can sit on top of a card without triggering
 * the card's onClick.
 */
import React, { useEffect, useState } from 'react';
import { Heart } from 'lucide-react';
import {
    isMusicLiked,
    toggleMusicLike,
    subscribeMusicLibrary,
} from '../../lib/music-library';

export function LikeButton({
    kind,
    item,
    size = 'md',           // 'sm' | 'md' | 'lg'
    label,                 // optional text label next to heart
    stopProp = true,
}) {
    const [liked, setLiked] = useState(() => isMusicLiked(kind, item?.id));
    useEffect(() => {
        const id = item?.id;
        const u = subscribeMusicLibrary(() => setLiked(isMusicLiked(kind, id)));
        return u;
    }, [kind, item?.id]);

    const dim = size === 'lg' ? 22 : size === 'sm' ? 16 : 18;
    const onClick = (e) => {
        if (stopProp) {
            e.preventDefault();
            e.stopPropagation();
        }
        toggleMusicLike(kind, item);
    };

    return (
        <button
            type="button"
            onClick={onClick}
            aria-label={liked ? 'Remove from library' : 'Add to library'}
            title={liked ? 'Remove from library' : 'Add to library'}
            data-testid={`like-${kind}-${item?.id}`}
            tabIndex={-1}
            style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: label ? 8 : 0,
                padding: label ? '8px 14px' : 8,
                background: liked
                    ? 'linear-gradient(135deg, var(--tunes-accent), var(--tunes-accent-2))'
                    : 'rgba(255,255,255,0.06)',
                border: liked ? 'none' : '1px solid rgba(255,255,255,0.12)',
                borderRadius: 999,
                cursor: 'pointer',
                color: liked ? '#fff' : 'rgba(255,255,255,0.85)',
                fontSize: 13,
                fontWeight: 600,
                transition: 'transform 0.15s, background 0.15s, color 0.15s',
            }}
            onMouseEnter={(e) => { e.currentTarget.style.transform = 'scale(1.06)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.transform = 'scale(1)'; }}
        >
            <Heart size={dim} fill={liked ? '#fff' : 'transparent'} strokeWidth={2.4} />
            {label && <span>{liked ? 'In library' : label}</span>}
        </button>
    );
}
