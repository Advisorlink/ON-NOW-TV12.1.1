import { useEffect, useState } from 'react';
import { Vesper } from '@/lib/api';

/**
 * Pulls 5 cinematic hero items from Cinemeta's top-movies catalog
 * (the most reliable source of high-quality backdrops).  Falls back
 * to an empty array when no addons are installed; the hero
 * component will then show its baked-in placeholder set.
 */
export function useLiveHeroes(addons) {
    const [heroes, setHeroes] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!addons || addons.length === 0) {
            setHeroes([]);
            return;
        }
        const cinemeta = addons.find((a) => /cinemeta/i.test(a.id || ''));
        if (!cinemeta) {
            setHeroes([]);
            return;
        }

        const cat =
            (cinemeta.catalogs || []).find(
                (c) => c.type === 'movie' && /top|popular/i.test(c.id || c.name || '')
            ) || (cinemeta.catalogs || []).find((c) => c.type === 'movie');
        if (!cat) {
            setHeroes([]);
            return;
        }

        let cancelled = false;
        (async () => {
            setLoading(true);
            try {
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
                if (!cancelled) setHeroes(picks);
            } catch {
                if (!cancelled) setHeroes([]);
            } finally {
                if (!cancelled) setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [addons]);

    return { heroes, loading };
}
