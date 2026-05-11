import { useEffect, useState } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';

/**
 * Pulls real catalogs from every installed addon and emits them
 * **progressively**, so a single slow addon can't hold up the whole
 * home screen.  Returns shelves shaped like
 *   { id, title, eyebrow, items[] }
 * which drop straight into <Shelf />.
 *
 * Results are cached in memory + sessionStorage keyed by the
 * combination of installed-addon ids + active filter, so navigating
 * back to Home (or switching between All/Movies/TV tabs you've
 * already visited) is instant.  After TTL_MS the hook silently
 * refetches in the background to keep things fresh.
 */
const BROWSABLE_TYPES = new Set(['movie', 'series', 'channel', 'tv', 'anime']);
const TTL_MS = 10 * 60 * 1000; // 10 min stale window

function cacheKey(addons, filterType, itemsPerCatalog) {
    const ids = (addons || []).map((a) => a.id).sort().join(',');
    return `shelves:${filterType || 'all'}:${itemsPerCatalog || 18}:${ids}`;
}

export function useLiveShelves(addons, filterType = null, itemsPerCatalog = 18) {
    const key = cacheKey(addons, filterType, itemsPerCatalog);
    const cached = cache.get(key);
    const [shelves, setShelves] = useState(
        Array.isArray(cached?.value) ? cached.value : []
    );
    const [loading, setLoading] = useState(!cached);

    useEffect(() => {
        if (!addons || addons.length === 0) {
            setShelves([]);
            setLoading(false);
            return;
        }

        // Hot path: cached and fresh — just paint and bail.
        const cur = cache.get(key);
        if (cur) {
            setShelves(Array.isArray(cur.value) ? cur.value : []);
            setLoading(false);
            if (!cache.isStale(cur, TTL_MS)) return;
            // Stale — fall through to background refetch but do NOT
            // wipe the screen; keep showing the cached value.
        } else {
            setShelves([]);
            setLoading(true);
        }

        let cancelled = false;
        const acc = [];

        (async () => {
            for (const addon of addons) {
                if (cancelled) return;
                const catalogs = (addon.catalogs || [])
                    .filter((c) => BROWSABLE_TYPES.has(c.type))
                    .filter((c) => !filterType || c.type === filterType)
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
                            eyebrow: capitalize(cat.type === 'movie' ? 'movies' : cat.type),
                            items: metas.slice(0, itemsPerCatalog).map((m) => ({
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
            if (!cancelled) {
                cache.set(key, acc);
                setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [addons, filterType, itemsPerCatalog, key]);

    return { shelves, loading };
}

const prettify = (s = '') =>
    s.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

const capitalize = (s = '') =>
    (s.charAt(0).toUpperCase() + s.slice(1)).trim();
