import { useEffect, useState } from 'react';
import { Vesper, API } from '@/lib/api';
import * as cache from '@/lib/cache';

const TTL_MS = 10 * 60 * 1000;

function heroKey(addons, type) {
    const ids = (addons || []).map((a) => a.id).sort().join(',');
    return `heroes:${type}:${ids}`;
}

export function useLiveHeroes(addons, type = 'movie') {
    const key = heroKey(addons, type);
    const cached = cache.get(key);
    const [heroes, setHeroes] = useState(
        Array.isArray(cached?.value) ? cached.value : []
    );
    const [loading, setLoading] = useState(!cached);

    useEffect(() => {
        // Hot path: cached and fresh → paint instantly, no refetch.
        const cur = cache.get(key);
        if (cur) {
            setHeroes(Array.isArray(cur.value) ? cur.value : []);
            setLoading(false);
            if (!cache.isStale(cur, TTL_MS)) return;
            // Stale — background refresh, but keep showing cached.
        } else {
            setLoading(true);
        }

        let cancelled = false;
        // Snapshot for "don't replace a good cache with a worse one"
        // guard below (mirrors useLiveShelves).
        const prevCached = cache.get(key);
        const prevHeroes = Array.isArray(prevCached?.value)
            ? prevCached.value : [];

        const fromCinemeta = async () => {
            if (!addons || addons.length === 0) return null;
            const cinemeta = addons.find((a) => /cinemeta/i.test(a.id || ''));
            if (!cinemeta) return null;

            const cat =
                (cinemeta.catalogs || []).find(
                    (c) =>
                        c.type === type &&
                        /top|popular/i.test(c.id || c.name || '')
                ) || (cinemeta.catalogs || []).find((c) => c.type === type);
            if (!cat) return null;

            const res = await Vesper.getCatalog(
                cinemeta.id,
                cat.type,
                cat.id
            );
            const metas = res?.data?.metas || [];
            const picks = metas
                .filter(
                    (m) =>
                        m?.background &&
                        m?.name &&
                        m?.description &&
                        m.description.length > 60
                )
                .slice(0, 5)
                .map((m) => ({
                    id: m.id,
                    title: m.name,
                    eyebrow: `Featured · ${(m.genres || [])[0] || cat.name || 'Film'}`,
                    year: m.releaseInfo || m.year || '',
                    runtime: m.runtime || '',
                    rating: m.imdbRating ? `★ ${m.imdbRating}` : '',
                    genres: m.genres || [],
                    synopsis: m.description,
                    backdrop: m.background,
                    sources: ['Cinemeta'],
                    routePath: `/title/${cat.type}/${m.id}`,
                }));
            return picks.length > 0 ? picks : null;
        };

        const fromTmdb = async () => {
            // `media` is "movie" or "tv" — map "series" → "tv"
            const tmdbMedia = type === 'series' ? 'tv' : 'movie';
            const r = await fetch(
                `${API}/tmdb/trending?window=week&media=${tmdbMedia}`,
                { cache: 'force-cache' }
            );
            if (!r.ok) return [];
            const json = await r.json();
            const list = json?.data || [];
            return list.slice(0, 6).map((m) => ({
                id: `tmdb-${m.tmdb_id}`,
                title: m.title,
                eyebrow: `Trending this week · ${type === 'series' ? 'TV' : 'Film'}`,
                year: m.year || '',
                runtime: '',
                rating: m.rating ? `★ ${m.rating}` : '',
                genres: [],
                synopsis: m.synopsis,
                backdrop: m.backdrop,
                sources: ['TMDB'],
                // Click resolves TMDB→IMDB then routes via the
                // existing NetworkPosterTile flow.  We point at a
                // helper route that lives in App.jsx.
                routePath: `/resolve/${m.type === 'series' ? 'tv' : 'movie'}/${m.tmdb_id}`,
            }));
        };

        (async () => {
            try {
                const cinemetaHeroes = await fromCinemeta();
                if (cinemetaHeroes && !cancelled) {
                    setHeroes(cinemetaHeroes);
                    cache.set(key, cinemetaHeroes);
                    return;
                }
                const tmdbHeroes = await fromTmdb();
                if (!cancelled) {
                    // GUARD — never wipe a populated hero cache with
                    // an empty list (network blip / preview asleep).
                    if (tmdbHeroes.length === 0 && prevHeroes.length > 0) {
                        setHeroes(prevHeroes);
                    } else {
                        setHeroes(tmdbHeroes);
                        cache.set(key, tmdbHeroes);
                    }
                }
            } catch {
                // Network died mid-fetch — fall back to whatever we
                // had cached so the billboard never goes blank.
                if (!cancelled) {
                    if (prevHeroes.length > 0) setHeroes(prevHeroes);
                    else setHeroes([]);
                }
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [addons, type, key]);

    return { heroes, loading };
}
