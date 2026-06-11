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
                        if (!metas.length && !devUnlock) continue;
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
                                genres: Array.isArray(m.genres)
                                    ? m.genres
                                    : [],
                                releaseInfo: m.releaseInfo,
                                routePath: `/title/${cat.type}/${m.id}`,
                            })),
                        };
                        acc.push(shelf);
                        if (!cancelled) setShelves([...acc]);
                    } catch (err) {
                        if (devUnlock) {
                            // Push a diagnostic stub so the user sees
                            // which catalog crashed when troubleshooting.
                            const stub = {
                                id: `${addon.id}-${cat.type}-${cat.id}`,
                                title: cat.name || prettify(cat.id),
                                eyebrow: capitalize(cat.type === 'movie' ? 'movies' : cat.type),
                                empty: true,
                                error: err?.message || 'fetch error',
                                addonName: addon.name || '',
                                items: [],
                            };
                            acc.push(stub);
                            if (!cancelled) setShelves([...acc]);
                        }
                        // skip silently in prod — one bad catalog shouldn't kill the row
                    }
                }
            }
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
                }
                setLoading(false);
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [addons, filterType, FETCH_LIMIT, key, unlockTick]);

    return { shelves, loading };
}

const prettify = (s = '') =>
    s.replace(/[-_]/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());

const capitalize = (s = '') =>
    (s.charAt(0).toUpperCase() + s.slice(1)).trim();
