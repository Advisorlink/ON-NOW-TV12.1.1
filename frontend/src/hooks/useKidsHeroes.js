import { useEffect, useState } from 'react';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';
import { getKidsConfig } from '@/lib/profiles';

const TTL_MS = 30 * 60 * 1000;

function keyFor(cfg) {
    return `kids:heroes:v5:${cfg.maxRatingMovie}`;
}

export function useKidsHeroes() {
    const [cfg, setCfg] = useState(getKidsConfig());

    useEffect(() => {
        const sync = () => setCfg(getKidsConfig());
        window.addEventListener('vesper:kids-config-change', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('vesper:kids-config-change', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);

    const KEY = keyFor(cfg);
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
            setHeroes([]);
            setLoading(true);
        }

        let cancelled = false;
        (async () => {
            try {
                const q = new URLSearchParams({
                    movie_cert: cfg.maxRatingMovie,
                }).toString();
                const r = await fetch(`${API}/tmdb/kids/heroes?${q}`);
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
    }, [KEY, cfg.maxRatingMovie]);

    return { heroes, loading };
}
