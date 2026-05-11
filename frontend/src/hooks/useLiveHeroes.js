import { useEffect, useState } from 'react';
import { Vesper, API } from '@/lib/api';

/**
 * Hero billboard data source.
 *
 *   1. Preferred  — Cinemeta "top movies/series" via the user's
 *      installed Stremio addons (gives a clickable /title/... route).
 *   2. Fallback   — TMDB trending (`/api/tmdb/trending`) so the hero
 *      is *always* populated with real, current content even when no
 *      addons are installed.  The TMDB hero items route into the
 *      same Detail page via a TMDB→IMDB lookup on click.
 *
 * Returns hero objects shaped exactly like HeroBillboard expects:
 *   { id, title, eyebrow, year, runtime, rating, genres,
 *     synopsis, backdrop, sources, routePath }
 */
export function useLiveHeroes(addons, type = 'movie') {
    const [heroes, setHeroes] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        let cancelled = false;
        setLoading(true);

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
                    return;
                }
                const tmdbHeroes = await fromTmdb();
                if (!cancelled) setHeroes(tmdbHeroes);
            } catch {
                if (!cancelled) setHeroes([]);
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [addons, type]);

    return { heroes, loading };
}
