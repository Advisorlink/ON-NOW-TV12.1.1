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

function cacheKey(addons, filterType) {
    // Cache is keyed by addon set + filter only — NOT by
    // itemsPerCatalog.  We always store up to 60 items per
    // catalogue; consumers slice down at render time if they only
    // want 18.  Keying by itemsPerCatalog caused stale-cache misses
    // when the user clicked the TV Shows tab for the first time
    // after we changed the limit.
    const ids = (addons || []).map((a) => a.id).sort().join(',');
    return `shelves:${filterType || 'all'}:${ids}`;
}

export function useLiveShelves(addons, filterType = null, itemsPerCatalog = 18) {
    // ALWAYS fetch the larger of (itemsPerCatalog, 60) so a single
    // cache entry can satisfy both home (18) and tab-grid (60).
    const FETCH_LIMIT = Math.max(itemsPerCatalog, 60);
    const key = cacheKey(addons, filterType);
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
                            items: metas.slice(0, FETCH_LIMIT).map((m) => ({
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
    }, [addons, filterType, FETCH_LIMIT, key]);

    return { shelves, loading };
}

const prettify = (s = '') =>
    s.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

const capitalize = (s = '') =>
    (s.charAt(0).toUpperCase() + s.slice(1)).trim();
