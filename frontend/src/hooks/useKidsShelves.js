import { useEffect, useState } from 'react';
import { API } from '@/lib/api';
import * as cache from '@/lib/cache';
import { getKidsConfig } from '@/lib/profiles';

/**
 * Pulls curated, kid-safe shelves directly from TMDB (via our backend
 * proxy).  Strictness comes from the user's Settings (maxRatingMovie,
 * maxRatingSeries) so flipping G ↔ PG-13 ↔ M15 in Settings reshapes
 * the home in real time.
 */
const TTL_MS = 30 * 60 * 1000;

function keyFor(cfg) {
    return `kids:shelves:v11:${cfg.maxRatingMovie}:${cfg.maxRatingSeries}`;
}

export function useKidsShelves() {
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
            setShelves([]);
            setLoading(true);
        }

        let cancelled = false;
        (async () => {
            try {
                const q = new URLSearchParams({
                    movie_cert: cfg.maxRatingMovie,
                    tv_level: cfg.maxRatingSeries,
                }).toString();
                const r = await fetch(`${API}/tmdb/kids/shelves?${q}`);
                if (!r.ok) throw new Error('http ' + r.status);
                const json = await r.json();
                const list = Array.isArray(json?.data) ? json.data : [];
                const shaped = list.map((s) => ({
                    id: s.id,
                    title: s.title,
                    eyebrow: s.eyebrow,
                    items: (s.items || []).map((it) => ({
                        id: `kids-${s.id}-${it.tmdb_id}`,
                        imdbId: null,
                        // v2.10.53 — carry TMDB metadata so long-press
                        // "Add to My List" can resolve IMDB on demand.
                        tmdbId: it.tmdb_id,
                        tmdbType: s.type === 'series' ? 'tv' : 'movie',
                        type: s.type,
                        title: it.title,
                        sub: [
                            it.year,
                            it.rating ? `★ ${it.rating}` : null,
                        ].filter(Boolean).join(' · '),
                        poster: it.poster,
                        background: it.backdrop,
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
    }, [KEY, cfg.maxRatingMovie, cfg.maxRatingSeries]);

    return { shelves, loading };
}
