import React, { useEffect, useState } from 'react';
import { API } from '@/lib/api';
import Shelf from '@/components/Shelf';

/** A user-added Home category (Settings → Home screen → Add a category). */
export default function CustomRowShelf({ row }) {
    const [shelf, setShelf] = useState(null);

    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                const r = await fetch(`${API}/tmdb/custom-row?q=${encodeURIComponent(row.query)}&limit=40`);
                if (!r.ok) return;
                const json = await r.json();
                const list = Array.isArray(json?.data) ? json.data : [];
                if (cancel || list.length === 0) return;
                setShelf({
                    id: row.id,
                    title: row.label,
                    eyebrow: row.eyebrow || 'YOUR CATEGORY',
                    items: list.map((it) => ({
                        id: `${row.id}-${it.type}-${it.tmdb_id}`,
                        imdbId: null,
                        type: it.type,
                        title: it.title,
                        sub: [it.year, it.rating ? `★ ${it.rating}` : null].filter(Boolean).join(' · '),
                        poster: it.poster,
                        background: it.backdrop,
                        year: it.year || null,
                        rating: it.rating ? `★ ${it.rating}` : null,
                        genres: Array.isArray(it.genres) ? it.genres : [],
                        synopsis: it.synopsis || '',
                        routePath: `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
                    })),
                });
            } catch { /* ignore */ }
        })();
        return () => { cancel = true; };
    }, [row.id, row.query, row.label]);

    if (!shelf) return null;
    return (
        <div data-testid={`custom-row-shelf-${row.id.replace('custom:', '')}`}>
            <Shelf shelf={shelf} />
        </div>
    );
}
