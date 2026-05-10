import { useEffect, useState } from 'react';
import { Vesper } from '@/lib/api';

/**
 * Pulls real catalogs from every installed addon.
 * Returns shelves shaped like { id, title, eyebrow, items[] } so they
 * drop straight into <Shelf />.
 */
export function useLiveShelves(addons) {
    const [shelves, setShelves] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        if (!addons || addons.length === 0) {
            setShelves([]);
            return;
        }

        let cancelled = false;
        (async () => {
            setLoading(true);
            const out = [];

            for (const addon of addons) {
                const catalogs = (addon.catalogs || []).slice(0, 4); // cap per addon
                for (const cat of catalogs) {
                    try {
                        const res = await Vesper.getCatalog(
                            addon.id,
                            cat.type,
                            cat.id
                        );
                        const metas = res?.data?.metas || [];
                        if (!metas.length) continue;
                        out.push({
                            id: `${addon.id}-${cat.type}-${cat.id}`,
                            title: cat.name || prettify(cat.id),
                            eyebrow: `${addon.name} · ${capitalize(cat.type)}`,
                            items: metas.slice(0, 18).map((m) => ({
                                id: `${addon.id}-${m.id}`,
                                imdbId: m.id,
                                type: cat.type,
                                title: m.name,
                                sub: [m.releaseInfo, m.imdbRating ? `★ ${m.imdbRating}` : null]
                                    .filter(Boolean)
                                    .join(' · '),
                                poster: m.poster,
                                background: m.background,
                                routePath: `/title/${cat.type}/${m.id}`,
                            })),
                        });
                        if (cancelled) return;
                    } catch (e) {
                        // skip silently — one bad catalog shouldn't kill the row
                    }
                }
            }

            if (!cancelled) {
                setShelves(out);
                setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [addons]);

    return { shelves, loading };
}

const prettify = (s = '') =>
    s
        .replace(/[-_]/g, ' ')
        .replace(/\b\w/g, (c) => c.toUpperCase());

const capitalize = (s = '') =>
    (s.charAt(0).toUpperCase() + s.slice(1)).trim();
