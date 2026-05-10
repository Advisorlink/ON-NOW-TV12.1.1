import { useEffect, useState } from 'react';
import { Vesper } from '@/lib/api';

/**
 * Pulls real catalogs from every installed addon and emits them
 * **progressively**, so a single slow addon can't hold up the whole
 * home screen.  Returns shelves shaped like
 *   { id, title, eyebrow, items[] }
 * which drop straight into <Shelf />.
 */
const BROWSABLE_TYPES = new Set(['movie', 'series', 'channel', 'tv', 'anime']);

export function useLiveShelves(addons) {
    const [shelves, setShelves] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!addons || addons.length === 0) {
            setShelves([]);
            return;
        }

        let cancelled = false;
        setShelves([]);
        setLoading(true);
        const acc = [];

        (async () => {
            for (const addon of addons) {
                if (cancelled) return;
                const catalogs = (addon.catalogs || [])
                    .filter((c) => BROWSABLE_TYPES.has(c.type))
                    .slice(0, 4);

                for (const cat of catalogs) {
                    if (cancelled) return;
                    try {
                        const res = await Vesper.getCatalog(
                            addon.id,
                            cat.type,
                            cat.id
                        );
                        const metas = res?.data?.metas || [];
                        if (!metas.length) continue;
                        const shelf = {
                            id: `${addon.id}-${cat.type}-${cat.id}`,
                            title: cat.name || prettify(cat.id),
                            eyebrow: `${addon.name} · ${capitalize(cat.type)}`,
                            items: metas.slice(0, 18).map((m) => ({
                                id: `${addon.id}-${m.id}`,
                                imdbId: m.id,
                                type: cat.type,
                                title: m.name,
                                sub: [
                                    m.releaseInfo,
                                    m.imdbRating ? `★ ${m.imdbRating}` : null,
                                ]
                                    .filter(Boolean)
                                    .join(' · '),
                                poster: m.poster,
                                background: m.background,
                                routePath: `/title/${cat.type}/${m.id}`,
                            })),
                        };
                        acc.push(shelf);
                        if (!cancelled) setShelves([...acc]);
                    } catch {
                        // skip silently — one bad catalog shouldn't kill the row
                    }
                }
            }
            if (!cancelled) setLoading(false);
        })();

        return () => {
            cancelled = true;
        };
    }, [addons]);

    return { shelves, loading };
}

const prettify = (s = '') =>
    s.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

const capitalize = (s = '') =>
    (s.charAt(0).toUpperCase() + s.slice(1)).trim();
