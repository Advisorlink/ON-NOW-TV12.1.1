import { useEffect, useState } from 'react';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';

/**
 * Pulls curated, kid-safe shelves directly from TMDB (via our backend
 * proxy).  Bypasses Stremio addon catalogs because almost none of
 * them surface a `certification` field, which means the generic
 * `isKidsSafe` filter let too much through in practice.  TMDB lets us
 * hard-filter by certification + family genres + adult flag.
 */
const TTL_MS = 30 * 60 * 1000;
const KEY = 'kids:shelves:v3';

export function useKidsShelves() {
    const cached = cache.get(KEY);
    const [shelves, setShelves] = useState(
        Array.isArray(cached?.value) ? cached.value : []
    );
    const [loading, setLoading] = useState(!cached);

    useEffect(() => {
        const cur = cache.get(KEY);
        if (cur) {
            setShelves(Array.isArray(cur.value) ? cur.value : []);
            setLoading(false);
            if (!cache.isStale(cur, TTL_MS)) return;
        } else {
            setLoading(true);
        }

        let cancelled = false;
        (async () => {
            try {
                const r = await fetch(`${API}/tmdb/kids/shelves`);
                if (!r.ok) throw new Error('http ' + r.status);
                const json = await r.json();
                const list = Array.isArray(json?.data) ? json.data : [];
                // Reshape into the structure that <Shelf> already expects.
                const shaped = list.map((s) => ({
                    id: s.id,
                    title: s.title,
                    eyebrow: s.eyebrow,
                    items: (s.items || []).map((it) => ({
                        id: `kids-${s.id}-${it.tmdb_id}`,
                        imdbId: null,
                        type: s.type,
                        title: it.title,
                        sub: [
                            it.year,
                            it.rating ? `★ ${it.rating}` : null,
                        ].filter(Boolean).join(' · '),
                        poster: it.poster,
                        background: it.backdrop,
                        // Detail page reaches via TMDB→IMDB resolve route.
                        routePath: `/resolve/${s.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
                    })),
                }));
                if (cancelled) return;
                setShelves(shaped);
                cache.set(KEY, shaped);
            } catch {
                /* keep cached */
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, []);

    return { shelves, loading };
}
