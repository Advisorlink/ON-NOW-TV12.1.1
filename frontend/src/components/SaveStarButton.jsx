import React, { useEffect, useState } from 'react';
import { Star } from 'lucide-react';
import useIsMobile from '@/lib/useIsMobile';
import { isInLibrary, isMovieInWatchLater } from '@/lib/library';

function savedState(item) {
    const id = item?.imdbId || item?.id;
    if (!id || !String(id).startsWith('tt')) return false;
    const type = item.type === 'series' || item.type === 'tv' ? 'series' : 'movie';
    return type === 'movie' ? isMovieInWatchLater(id) : isInLibrary(id);
}

/** Phone/tablet-only star on covers: opens the same Add-to-list sheet
 *  as hold-OK on TV. `onActivate` is the tile's long-press handler. */
export const SaveStarButton = ({ item, onActivate, testId }) => {
    const isMobile = useIsMobile();
    const [saved, setSaved] = useState(() => savedState(item));
    useEffect(() => {
        setSaved(savedState(item));
        const sync = () => setSaved(savedState(item));
        window.addEventListener('vesper:library-change', sync);
        return () => window.removeEventListener('vesper:library-change', sync);
    }, [item]);
    if (!isMobile || !item) return null;
    const stop = (e) => { e.stopPropagation(); e.preventDefault(); };
    return (
        <span
            role="button"
            aria-label={saved ? 'Saved — tap to change' : 'Save to your library'}
            aria-pressed={saved}
            data-testid={testId || `save-star-${item.imdbId || item.id}`}
            data-saved={saved ? 'true' : 'false'}
            className="vesper-save-star"
            onPointerDown={stop}
            onPointerUp={stop}
            onTouchStart={(e) => e.stopPropagation()}
            onTouchEnd={(e) => { e.stopPropagation(); e.preventDefault(); onActivate?.(); }}
            onClick={(e) => { stop(e); onActivate?.(); }}
        >
            <Star size={15} strokeWidth={2.2} fill={saved ? 'currentColor' : 'none'} />
        </span>
    );
};
