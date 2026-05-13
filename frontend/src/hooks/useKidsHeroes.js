import { useEffect, useState } from 'react';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';

const TTL_MS = 30 * 60 * 1000;
const KEY = 'kids:heroes:v3';

/**
 * Curated kid-safe hero billboard.  Backend hits TMDB /discover with
 * certification.lte=PG + family/animation genres so adult content
 * is impossible.
 */
export function useKidsHeroes() {
    const cached = cache.get(KEY);
    const [heroes, setHeroes] = useState(
        Array.isArray(cached?.value) ? cached.value : []
    );
    const [loading, setLoading] = useState(!cached);

    useEffect(() => {
        const cur = cache.get(KEY);
        if (cur) {
            setHeroes(Array.isArray(cur.value) ? cur.value : []);
            setLoading(false);
            if (!cache.isStale(cur, TTL_MS)) return;
        } else {
            setLoading(true);
        }

        let cancelled = false;
        (async () => {
            try {
                const r = await fetch(`${API}/tmdb/kids/heroes`);
                if (!r.ok) throw new Error('http ' + r.status);
                const json = await r.json();
                const list = Array.isArray(json?.data) ? json.data : [];
                if (cancelled) return;
                setHeroes(list);
                cache.set(KEY, list);
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

    return { heroes, loading };
}
