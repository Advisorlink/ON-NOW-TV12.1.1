import { useEffect, useState } from 'react';
import * as img from '@/lib/img';

/**
 * Focus-follow hero: whichever rail cover is focused takes over the
 * billboard (art + title + synopsis). Wait for the row's motion to
 * settle; rapid scrubbing doesn't decode a fullscreen image per key.
 */
export function tileToHero(it) {
    if (!it) return null;
    const rating = it.imdbRating ? `★ ${it.imdbRating}` : null;
    return {
        id: it.imdbId || it.id,
        title: it.title || it.name || '',
        backdrop: it.background || it.backdrop || null,
        synopsis: it.synopsis || it.description || it.overview || '',
        year: it.releaseInfo || it.year || null,
        rating: it.rating || rating,
        genres: Array.isArray(it.genres) ? it.genres : [],
        routePath: it.routePath || null,
        type: it.type,
    };
}

export default function useFocusHero() {
    const [focusHero, setFocusHero] = useState(null);
    useEffect(() => {
        let timer = null;
        const onTileFocus = (e) => {
            const item = e.detail;
            if (!item) return;
            clearTimeout(timer);
            timer = setTimeout(() => {
                setFocusHero(tileToHero(item));
                const el = document.activeElement?.closest?.('[data-preview="true"]');
                const cell = el?.closest('[data-shelf-cell]') || el;
                // Warm only the next image after settling, never both sides
                // of every tile traversed while the key is held.
                const sib = cell?.nextElementSibling;
                const next = sib?.matches('[data-preview]') ? sib : sib?.querySelector('[data-preview]');
                const b = next?.getAttribute('data-preview-bg-raw');
                if (b) { const im = new Image(); im.src = img.heroBackdrop(b); }
            }, 200);
        };
        window.addEventListener('vesper:tile-focus', onTileFocus);
        return () => {
            clearTimeout(timer);
            window.removeEventListener('vesper:tile-focus', onTileFocus);
        };
    }, []);
    return focusHero;
}
