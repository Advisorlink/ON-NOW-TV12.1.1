import { useEffect, useState } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';
import { getPerf, PERF_CHANGE } from '@/lib/perfLab';

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

export function metaToItem(addonId, type, m) {
    return {
        id: `${addonId}-${m.id}`,
        imdbId: m.id,
        type,
        title: m.name,
        sub: [m.releaseInfo, m.imdbRating ? `★ ${m.imdbRating}` : null].filter(Boolean).join(' · '),
        poster: m.poster,
        background: m.background,
        synopsis: m.description || '',
        imdbRating: m.imdbRating || null,
        genres: Array.isArray(m.genres) ? m.genres : [],
        releaseInfo: m.releaseInfo,
        routePath: `/title/${type}/${m.id}`,
    };
}

/** Circuit breaker — a catalog that just failed (dead addon, 502 after
 *  a 10 s timeout) is skipped for 10 minutes instead of stalling the
 *  Home page on every stale refresh. */
const FAIL_TTL_MS = 10 * 60 * 1000;
const failKey = (addonId, type, id) => `vesper-catalog-fail:${addonId}:${type}:${id}`;
export function catalogRecentlyFailed(addonId, type, id) {
    try { return Date.now() - Number(sessionStorage.getItem(failKey(addonId, type, id)) || 0) < FAIL_TTL_MS; }
    catch { return false; }
}
export function markCatalogFailed(addonId, type, id) {
    try { sessionStorage.setItem(failKey(addonId, type, id), String(Date.now())); } catch { /* ignore */ }
}

/** Lazy strategy: a pending shelf stub fetches its own items when the
 *  row scrolls near the viewport (called from Shelf). */
export async function fetchShelfItems(shelf) {
    if (catalogRecentlyFailed(shelf.addonId, shelf.catType, shelf.catId)) return [];
    let res;
    try {
        res = await Vesper.getCatalog(shelf.addonId, shelf.catType, shelf.catId);
    } catch (err) {
        markCatalogFailed(shelf.addonId, shelf.catType, shelf.catId);
        throw err;
    }
    const metas = res?.data?.metas || [];
    return metas.slice(0, 60).map((m) => metaToItem(shelf.addonId, shelf.catType, m));
}
const TTL_MS = 10 * 60 * 1000; // 10 min stale window

function cacheKey(addons, filterType) {
    // Cache is keyed by addon set + filter only — NOT by
    // itemsPerCatalog.  We always store up to 60 items per
    // catalogue; consumers slice down at render time if they only
    // want 18.  Keying by itemsPerCatalog caused stale-cache misses
    // when the user clicked the TV Shows tab for the first time
    // after we changed the limit.
    const ids = (addons || []).map((a) => a.id).sort().join(',');
    return `shelves:v2:${filterType || 'all'}:${ids}`;
}

export function useLiveShelves(addons, filterType = null, itemsPerCatalog = 18) {
    // ALWAYS fetch the larger of (itemsPerCatalog, 60) so a single
    // cache entry can satisfy both home (18) and tab-grid (60).
    // Fetch the full 60 so Shelf's "Show more" tile can reveal beyond itemsPerRow.
    const FETCH_LIMIT = Math.max(itemsPerCatalog, 60);
    const strategy = getPerf('rowLoading');
    const [perfTick, setPerfTick] = useState(0);
    const key = cacheKey(addons, filterType);
    useEffect(() => {
        const bump = (e) => {
            if (!e.detail || ['rowLoading', 'itemsPerRow', '*'].includes(e.detail.key)) {
                cache.clear(key);
                setPerfTick((t) => t + 1);
            }
        };
        window.addEventListener(PERF_CHANGE, bump);
        return () => window.removeEventListener(PERF_CHANGE, bump);
    }, [key]);
    const cached = cache.get(key);
    const [shelves, setShelves] = useState(
        Array.isArray(cached?.value) ? cached.value : []
    );
    const [loading, setLoading] = useState(!cached);
    /* Re-fetch when the user toggles the dev-unlock flag so the
     * empty-row stubs appear/disappear immediately instead of
     * waiting for a stale cache TTL.  We mirror the flag into
     * React state so the effect deps cover it. */
    const [unlockTick, setUnlockTick] = useState(0);
    useEffect(() => {
        const bump = () => setUnlockTick((v) => v + 1);
        window.addEventListener('onnowtv:dev-unlock-changed', bump);
        return () => window.removeEventListener('onnowtv:dev-unlock-changed', bump);
    }, []);

    useEffect(() => {
        if (!addons || addons.length === 0) {
            // No addons → but if state is already populated from a
            // cache hit on first paint, KEEP showing those shelves.
            // A transient `addons=[]` (network blip during useAddons
            // refresh) used to wipe the rails — never again.
            setLoading(false);
            return;
        }

        // Hot path: cached and fresh — just paint and bail.
        const cur = cache.get(key);
        // When unlockTick changes (user toggled the dev-unlock
        // setting), bust the cache so the recomputed shelf list
        // reflects the new keep-empty-rows behaviour.
        if (cur && unlockTick > 0) {
            cache.set(key, []); // mark dirty
        }
        const fresh = unlockTick > 0 ? null : cur;
        if (fresh) {
            setShelves(Array.isArray(fresh.value) ? fresh.value : []);
            setLoading(false);
            if (!cache.isStale(fresh, TTL_MS)) return;
            // Stale — fall through to background refetch but do NOT
            // wipe the screen; keep showing the cached value.
        } else {
            setShelves([]);
            setLoading(true);
        }

        let cancelled = false;
        const acc = [];
        const prevCached = cache.get(key);
        const prevShelves = Array.isArray(prevCached?.value)
            ? prevCached.value : [];

        /* Dev-Unlock toggle (Settings → Unlock testing) — when ON,
         * the loop KEEPS empty-result catalogs so the user can see
         * exactly which addon catalogs returned 0 items.  Production
         * UX is unchanged: empty catalogs stay hidden by default. */
        let devUnlock = false;
        try { devUnlock = localStorage.getItem('onnowtv-dev-unlock') === '1'; }
        catch { /* ignore */ }

        if (strategy === 'lazy') {
            // Rows are known from the manifests — paint titles now,
            // each Shelf fetches its own items when it nears the screen.
            const stubs = [];
            for (const addon of addons) {
                (addon.catalogs || [])
                    .filter((c) => BROWSABLE_TYPES.has(c.type))
                    .filter((c) => !filterType || c.type === filterType)
                    .slice(0, 4)
                    .forEach((cat) => stubs.push({
                        id: `${addon.id}-${cat.type}-${cat.id}`,
                        title: cat.name || prettify(cat.id),
                        eyebrow: capitalize(cat.type === 'movie' ? 'movies' : cat.type),
                        addonName: addon.name || '',
                        addonId: addon.id,
                        catType: cat.type,
                        catId: cat.id,
                        pending: true,
                        items: [],
                    }));
            }
            setShelves(stubs);
            setLoading(false);
            return () => { cancelled = true; };
        }

        const buildShelf = (addon, cat, metas) => {
            // Strip the addon name from catalog labels.
            // Stremio catalogs often look like
            //   "Cinemeta - Popular Movies" or
            //   "Torrentio: Trending".
            // The user only wants the CATEGORY visible
            // ("Popular Movies", "Trending") — the actual
            // source/addon kicks in once they hit Play.
            const rawTitle = cat.name || prettify(cat.id);
            const addonName = (addon.name || '').trim();
            let cleanTitle = rawTitle;
            if (addonName) {
                const esc = addonName.replace(
                    /[.*+?^${}()|[\]\\]/g,
                    '\\$&'
                );
                cleanTitle = cleanTitle
                    .replace(
                        new RegExp(
                            '^' + esc + '\\s*[-–—:•|]\\s*',
                            'i'
                        ),
                        ''
                    )
                    .replace(
                        new RegExp(
                            '\\s*[-–—:•|]\\s*' + esc + '$',
                            'i'
                        ),
                        ''
                    )
                    .replace(
                        new RegExp('\\(' + esc + '\\)', 'gi'),
                        ''
                    )
                    .replace(
                        new RegExp('\\b' + esc + '\\b', 'gi'),
                        ''
                    )
                    .replace(/\s+/g, ' ')
                    .replace(/^[\s\-–—:•|]+|[\s\-–—:•|]+$/g, '')
                    .trim();
                if (!cleanTitle) cleanTitle = rawTitle;
            }
            // Final pass: many Stremio addons append their
            // brand as `Title | EP-STREM` or `Title -
            // OMDB` regardless of their declared name.
            // Strip any final ` | X` / ` - X` / ` • X`
            // segment whose suffix is mostly uppercase /
            // hyphenated (a brand) — not a normal English
            // word like "Drama" or "Top Rated".
            cleanTitle = cleanTitle
                .replace(
                    /\s*[|•]\s*[A-Z][A-Z0-9._\- ]{1,30}$/,
                    ''
                )
                .replace(
                    /\s*[-–—]\s*([A-Z]{2,}[A-Z0-9._\- ]*)$/,
                    ''
                )
                .trim();
            if (!cleanTitle) cleanTitle = rawTitle;
            const shelf = {
                id: `${addon.id}-${cat.type}-${cat.id}`,
                title: cleanTitle,
                eyebrow: capitalize(cat.type === 'movie' ? 'movies' : cat.type),
                // Dev-Unlock diagnostic — flag empty rows so the
                // user can see WHICH addon catalogs returned 0
                // items.  Shelf component handles the visual.
                empty: metas.length === 0,
                addonName: addon.name || '',
                items: metas.slice(0, FETCH_LIMIT).map((m) => metaToItem(addon.id, cat.type, m)),
            };
            return shelf;
        };

        // All catalogs in manifest order, fetched IN PARALLEL (a dead
        // addon used to block every row behind it for 10 s each).
        const entries = [];
        for (const addon of addons) {
            (addon.catalogs || [])
                .filter((c) => BROWSABLE_TYPES.has(c.type))
                .filter((c) => !filterType || c.type === filterType)
                .slice(0, 4)
                .forEach((cat) => entries.push({ addon, cat }));
        }
        const results = new Array(entries.length).fill(null);
        const collect = () => { acc.length = 0; results.forEach((r) => { if (r) acc.push(r); }); };
        let lastPaint = 0;
        let trailing = null;
        const paint = () => {
            if (cancelled) return;
            collect();
            lastPaint = Date.now();
            setShelves([...acc]);
        };
        const schedulePaint = () => {
            if (cancelled) return;
            if (strategy === 'progressive' || acc.length === 0 || Date.now() - lastPaint > 1500) { paint(); return; }
            // batched: coalesce arrivals, paint at most every 1.5 s
            clearTimeout(trailing);
            trailing = setTimeout(paint, 1500 - (Date.now() - lastPaint));
        };
        (async () => {
            await Promise.all(entries.map(async ({ addon, cat }, i) => {
                if (cancelled) return;
                if (catalogRecentlyFailed(addon.id, cat.type, cat.id) && !devUnlock) return;
                try {
                    const res = await Vesper.getCatalog(addon.id, cat.type, cat.id);
                    const metas = res?.data?.metas || [];
                    if (!metas.length && !devUnlock) return;
                    results[i] = buildShelf(addon, cat, metas);
                    schedulePaint();
                } catch (err) {
                    markCatalogFailed(addon.id, cat.type, cat.id);
                    if (devUnlock) {
                        // Push a diagnostic stub so the user sees
                        // which catalog crashed when troubleshooting.
                        results[i] = {
                            id: `${addon.id}-${cat.type}-${cat.id}`,
                            title: cat.name || prettify(cat.id),
                            eyebrow: capitalize(cat.type === 'movie' ? 'movies' : cat.type),
                            empty: true,
                            error: err?.message || 'fetch error',
                            addonName: addon.name || '',
                            items: [],
                        };
                        schedulePaint();
                    }
                    // skip silently in prod — one bad catalog shouldn't kill the row
                }
            }));
            clearTimeout(trailing);
            collect();
            if (!cancelled) {
                // GUARD: only overwrite the cache if the new fetch
                // is at least as good as what was already cached.
                // If the network dropped half-way and `acc` is empty
                // (or shrunk dramatically), keep the cached shelves
                // on screen + in storage so the next cold boot
                // still renders the last-known-good Home page.
                if (acc.length === 0 && prevShelves.length > 0) {
                    // Roll state back to the cached value — the
                    // progressive `setShelves([...acc])` loop above
                    // never fired (every catalog throw'd), but if
                    // it had partially painted with [] we restore.
                    setShelves(prevShelves);
                } else if (
                    acc.length > 0 &&
                    acc.length < Math.floor(prevShelves.length / 2) &&
                    prevShelves.length >= 3
                ) {
                    // Partial-failure case: we got SOME shelves but
                    // less than half what we had before.  Probably
                    // a flaky reconnection.  Keep the previous good
                    // cache + paint instead of replacing.
                    setShelves(prevShelves);
                } else {
                    cache.set(key, acc);
                    setShelves([...acc]);
                }
                setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
            clearTimeout(trailing);
        };
    }, [addons, filterType, FETCH_LIMIT, key, unlockTick, strategy, perfTick]);

    return { shelves, loading };
}

const prettify = (s = '') =>
    s.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

const capitalize = (s = '') =>
    (s.charAt(0).toUpperCase() + s.slice(1)).trim();
