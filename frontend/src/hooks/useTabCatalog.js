import { useEffect, useState, useRef } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';

/**
 * Dedicated, FAST data hook for the Movies / TV Shows tab grid.
 *
 * This is a from-scratch replacement for the slow path the user
 * complained about.  Previous behaviour walked every addon
 * sequentially, awaiting each catalog one after the other.  For 5
 * installed addons * 4 catalogs each that's 20 serial round trips
 * over the network — on a slow HK1 box those add up to ~10–20 s
 * before the user can navigate.
 *
 * This hook:
 *   • Issues every catalog request IN PARALLEL via Promise.all.
 *   • Caches the merged result in memory + sessionStorage so a
 *     second visit is instant.
 *   • Returns a flat, year-sorted item list (already deduped) +
 *     a frequency-sorted genre list.  No shelf shape.
 *   • Exposes a `progress` 0-1 so the consumer can render a
 *     determinate spinner ("Loaded 12 of 20 catalogues…").
 *
 * Cache layout:
 *   cache key: `tab:${type}:${sortedAddonIds}`
 *   value:     { items: [...], genres: [...] }
 */
const TTL_MS = 10 * 60 * 1000;
const PER_CATALOG = 60;   // metas requested per catalog
const MAX_CATALOGS_PER_ADDON = 4;

function buildKey(type, addons) {
    const ids = (addons || []).map((a) => a.id).sort().join(',');
    return `tab:${type}:${ids}`;
}

function yearOf(m) {
    const raw = (m.releaseInfo || '') + ' ' + (m.year || '');
    const match = raw.match(/(19|20)\d{2}/);
    return match ? parseInt(match[0], 10) : 0;
}

function mapMeta(addon, cat, m) {
    return {
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
        genres: Array.isArray(m.genres) ? m.genres : [],
        releaseInfo: m.releaseInfo,
        year: yearOf(m),
        routePath: `/title/${cat.type}/${m.id}`,
    };
}

export function useTabCatalog(addons, type) {
    const key = buildKey(type, addons);
    const cached = cache.get(key);
    const cachedValue = cached?.value;
    const [data, setData] = useState(
        cachedValue && Array.isArray(cachedValue.items)
            ? cachedValue
            : { items: [], genres: [] }
    );
    const [loading, setLoading] = useState(!cachedValue);
    const [progress, setProgress] = useState(cachedValue ? 1 : 0);
    const runIdRef = useRef(0);

    useEffect(() => {
        if (!addons || addons.length === 0) {
            setData({ items: [], genres: [] });
            setLoading(false);
            setProgress(1);
            return;
        }

        const runId = ++runIdRef.current;

        // Hot path: cached + fresh.  Paint immediately and bail.
        const cur = cache.get(key);
        if (cur && Array.isArray(cur.value?.items)) {
            setData(cur.value);
            setLoading(false);
            setProgress(1);
            if (!cache.isStale(cur, TTL_MS)) return;
            // Stale → fall through to silent refetch.
        } else {
            setData({ items: [], genres: [] });
            setLoading(true);
            setProgress(0);
        }

        // Build the full list of (addon, catalog) pairs to fetch.
        const jobs = [];
        for (const addon of addons) {
            const catalogs = (addon.catalogs || [])
                .filter((c) => c.type === type)
                .slice(0, MAX_CATALOGS_PER_ADDON);
            for (const cat of catalogs) {
                jobs.push({ addon, cat });
            }
        }

        if (jobs.length === 0) {
            setData({ items: [], genres: [] });
            setLoading(false);
            setProgress(1);
            return;
        }

        let done = 0;
        // Fire EVERY catalog request in parallel — this is the
        // single biggest perf win over the old serial walk.
        const promises = jobs.map(async ({ addon, cat }) => {
            try {
                const res = await Vesper.getCatalog(
                    addon.id,
                    cat.type,
                    cat.id
                );
                const metas = res?.data?.metas || [];
                return metas
                    .slice(0, PER_CATALOG)
                    .map((m) => mapMeta(addon, cat, m));
            } catch {
                return [];
            } finally {
                done++;
                if (runIdRef.current === runId) {
                    setProgress(done / jobs.length);
                }
            }
        });

        Promise.all(promises)
            .then((batches) => {
                if (runIdRef.current !== runId) return; // superseded
                const seen = new Map();
                const genreCount = new Map();
                for (const batch of batches) {
                    if (!batch) continue;
                    for (const it of batch) {
                        const k = it.routePath || it.imdbId || it.id;
                        if (!k || seen.has(k)) continue;
                        seen.set(k, it);
                        for (const g of it.genres || []) {
                            if (!g) continue;
                            genreCount.set(
                                g,
                                (genreCount.get(g) || 0) + 1
                            );
                        }
                    }
                }
                const items = Array.from(seen.values()).sort(
                    (a, b) => b.year - a.year
                );
                const genres = Array.from(genreCount.entries())
                    .sort((a, b) => b[1] - a[1])
                    .slice(0, 14)
                    .map(([name]) => name);
                const payload = { items, genres };
                try { cache.set(key, payload); } catch (_) { /* ignore */ }
                setData(payload);
                setLoading(false);
                setProgress(1);
            })
            .catch((err) => {
                if (runIdRef.current !== runId) return;
                // eslint-disable-next-line no-console
                console.error('[useTabCatalog] aggregate failed:', err);
                setLoading(false);
                setProgress(1);
            });
    }, [key, addons, type]);

    return { ...data, loading, progress };
}
