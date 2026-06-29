import { useEffect, useMemo, useState, useRef, useCallback } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';

/**
 * Fetch titles in a given genre across every installed addon.
 *
 * v2.1 — ON-DEMAND pagination.  We load the FIRST burst eagerly so
 * the grid is interactive in ~1 s, and then we WAIT for the consumer
 * (TabGridView) to call `loadMore()` before fetching the next batch.
 *
 * Why on-demand?
 *   • Most users never scroll past the first 400 items, so crawling
 *     5,000 per catalog in the background is wasted bandwidth + box
 *     CPU.
 *   • Scroll-driven loading mirrors how Netflix/Plex/Stremio work —
 *     no need to wait, but the box doesn't churn either.
 *
 * Strategy:
 *   • Initial burst — 4 pages × 100 per catalog parallel.  Loads
 *     ~400 items per catalog in ~1 s for the first paint.
 *   • Subsequent batches — fire 4 more pages per still-alive
 *     catalog ONLY when `loadMore()` is called.  The IntersectionObserver
 *     in TabGridView pumps `loadMore` whenever the user scrolls
 *     near the bottom of the grid.
 *   • A catalog is "alive" until one of its batches returns 0 new
 *     items, at which point we mark it exhausted.
 *   • `hasMore` flips to false when EVERY catalog is exhausted, so
 *     the IntersectionObserver can disconnect.
 *
 * Returned shape:
 *   items        — current merged + sorted list.
 *   loading      — true during the INITIAL burst.
 *   progress     — 0..1 progress of the initial burst.
 *   loadingMore  — true while a `loadMore()` batch is inflight.
 *   totalLoaded  — running count of unique items.
 *   hasMore      — true if at least one catalog still has unfetched pages.
 *   loadMore     — call to fetch the next batch (idempotent — does
 *                  nothing if a batch is already inflight or hasMore=false).
 */
/*
 *  v2.10.85 — BACKGROUND PAGINATION RE-ENABLED (user request).
 *
 *  Feb 27 we'd disabled this because cold-mount was hammering the
 *  network with 24+ concurrent requests.  User now wants the SAME
 *  scroll-driven pagination that Stan/Binge/Paramount use on the
 *  /networks/<slug> pages — load a first page fast, then
 *  IntersectionObserver fires more pages as the user scrolls.
 *
 *  Configuration NOW:
 *    INITIAL_PAGES = 2   — first burst (2 pages × 100 items per
 *                          addon-catalog pair) gives ~200 items at
 *                          first paint, a clean 16-row 1080p grid.
 *    BATCH_PAGES   = 2   — every loadMore() pulls another 200 per
 *                          catalog so scrolling stays buttery.
 *    MAX_PAGES_HARD = 50 — safety cap (~5,000 unique items per
 *                          catalog before we declare exhaustion).
 *
 *  The hook now mirrors the on-demand strategy described above:
 *  `hasMore` stays true as long as at least one catalog can still
 *  serve a fresh page.  TabGridView's IntersectionObserver pumps
 *  loadMore() whenever the sentinel near the bottom of the grid
 *  enters the viewport, so the user sees a seamless infinite scroll
 *  identical to the Stan/Binge experience.
 */
const PAGE_SIZE = 100;
const INITIAL_PAGES = 2;
const BATCH_PAGES = 2;
const MAX_PAGES_HARD = 50;
const TTL_MS = 30 * 60 * 1000;

function buildKey(type, addons, genre) {
    const ids = (addons || []).map((a) => a.id).sort().join(',');
    return `tab:${type}:${ids}:g:${genre}`;
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

function catalogSupportsGenre(cat, genre) {
    const extra = cat.extra || cat.extraSupported || cat.extraRequired;
    if (!Array.isArray(extra)) return false;
    const g = (extra || []).find(
        (x) => (x?.name || x) === 'genre'
    );
    if (!g) return false;
    if (!Array.isArray(g.options)) return true;
    return g.options.some(
        (o) => (o || '').toLowerCase() === (genre || '').toLowerCase()
    );
}

export function useTabGenreCatalog(addons, type, genre, seedItems) {
    const addonIds = (addons || []).map((a) => a.id).sort().join(',');
    const key = useMemo(
        () => (genre ? buildKey(type, addons, genre) : ''),
        [type, addonIds, genre]
    );
    const addonsRef = useRef(addons);
    addonsRef.current = addons;

    const [items, setItems] = useState(seedItems || []);
    const [loading, setLoading] = useState(false);
    const [progress, setProgress] = useState(0);
    const [loadingMore, setLoadingMore] = useState(false);
    const [totalLoaded, setTotalLoaded] = useState(0);
    const [hasMore, setHasMore] = useState(false);

    // Per-run mutable state captured by `loadMore`.  We use refs so
    // the latest closure always sees the current crawl state without
    // requiring every page to be a new function instance.
    const runIdRef        = useRef(0);
    const seenRef         = useRef(new Map());
    const pairsRef        = useRef([]);          // [{ addon, cat }]
    const lastSkipRef     = useRef(new Map());   // pairId -> next skip
    const exhaustedRef    = useRef(new Set());   // pairIds we're done with
    const batchInflightRef = useRef(false);

    // Push the current `seenRef` map to React, sorted by year DESC.
    const pushState = useCallback(() => {
        const merged = Array.from(seenRef.current.values()).sort(
            (a, b) => b.year - a.year
        );
        setItems(merged);
        setTotalLoaded(merged.length);
    }, []);

    // Fetch one page; returns count of NEW items added to the dedup map.
    const fetchPage = useCallback(async (addon, cat, skip) => {
        try {
            const res = await Vesper.getCatalog(addon.id, cat.type, cat.id, {
                genre, skip,
            });
            const metas = res?.data?.metas || [];
            if (!metas.length) return 0;
            const seen = seenRef.current;
            let added = 0;
            for (const m of metas) {
                const mapped = mapMeta(addon, cat, m);
                const k = mapped.routePath || mapped.imdbId || mapped.id;
                if (!k || seen.has(k)) continue;
                seen.set(k, mapped);
                added++;
            }
            return added;
        } catch {
            return 0;
        }
    }, [genre]);

    // Fire 4 pages × every still-alive catalog in parallel.  Returns
    // when all batch pages settle.  Advances per-pair `lastSkip` and
    // marks exhausted catalogs.
    const runOneBatch = useCallback(async () => {
        const pairs = pairsRef.current;
        const jobs = [];
        for (const { addon, cat } of pairs) {
            const id = `${addon.id}|${cat.id}`;
            if (exhaustedRef.current.has(id)) continue;
            const startSkip = lastSkipRef.current.get(id) || 0;
            for (let p = 0; p < BATCH_PAGES; p++) {
                const skip = startSkip + p * PAGE_SIZE;
                if (skip >= MAX_PAGES_HARD * PAGE_SIZE) {
                    exhaustedRef.current.add(id);
                    break;
                }
                jobs.push({ addon, cat, skip });
            }
        }
        if (jobs.length === 0) return false;

        const results = await Promise.all(
            jobs.map((j) =>
                fetchPage(j.addon, j.cat, j.skip).then((added) => ({ ...j, added }))
            )
        );

        // Aggregate per-pair "added across this batch" so a pair is
        // marked exhausted ONLY if the whole batch returned 0.
        const perPairAdded = new Map();
        for (const r of results) {
            const id = `${r.addon.id}|${r.cat.id}`;
            perPairAdded.set(id, (perPairAdded.get(id) || 0) + r.added);
        }
        for (const r of results) {
            const id = `${r.addon.id}|${r.cat.id}`;
            if (perPairAdded.get(id) === 0) {
                exhaustedRef.current.add(id);
                continue;
            }
            const next = r.skip + PAGE_SIZE;
            const prev = lastSkipRef.current.get(id) || 0;
            if (next > prev) lastSkipRef.current.set(id, next);
        }
        return true;
    }, [fetchPage]);

    // Public hook handle exposed for the IntersectionObserver in
    // TabGridView.  Idempotent — fires nothing if a batch is already
    // inflight, or if we've exhausted every catalog.
    const loadMore = useCallback(async () => {
        if (batchInflightRef.current) return;
        if (exhaustedRef.current.size >= pairsRef.current.length) return;
        batchInflightRef.current = true;
        setLoadingMore(true);
        const runId = runIdRef.current;
        try {
            await runOneBatch();
            if (runIdRef.current !== runId) return;
            pushState();
            const stillAlive =
                exhaustedRef.current.size < pairsRef.current.length;
            setHasMore(stillAlive);
            // Cache the merged list whenever it grows so a reload
            // restores the latest view instantly.
            if (key) {
                try { cache.set(key, { items: Array.from(seenRef.current.values()) }); }
                catch { /* ignore */ }
            }
        } finally {
            batchInflightRef.current = false;
            setLoadingMore(false);
        }
    }, [runOneBatch, pushState, key]);

    useEffect(() => {
        if (!genre) {
            setItems(seedItems || []);
            setLoading(false);
            setLoadingMore(false);
            setHasMore(false);
            setProgress(1);
            setTotalLoaded((seedItems || []).length);
            return;
        }

        const runId = ++runIdRef.current;
        // Reset per-run mutable state.
        seenRef.current = new Map();
        pairsRef.current = [];
        lastSkipRef.current = new Map();
        exhaustedRef.current = new Set();
        batchInflightRef.current = false;
        setLoadingMore(false);

        // Pre-seed dedup map with matching items from seedItems so
        // the first paint is never empty.
        const g = genre.toLowerCase();
        for (const it of seedItems || []) {
            if ((it.genres || []).some((x) => (x || '').toLowerCase() === g)) {
                const k = it.routePath || it.imdbId || it.id;
                if (k && !seenRef.current.has(k)) seenRef.current.set(k, it);
            }
        }

        // Hot cache path — instant render of any previously cached
        // list.  We STILL run the initial burst so newly-added
        // titles are picked up.  Cached items already in `seenRef`
        // are deduped by routePath.
        const cur = cache.get(key);
        if (cur && Array.isArray(cur.value?.items)) {
            for (const it of cur.value.items) {
                const k = it.routePath || it.imdbId || it.id;
                if (k && !seenRef.current.has(k)) seenRef.current.set(k, it);
            }
            pushState();
            setLoading(false);
            setProgress(1);
            if (!cache.isStale(cur, TTL_MS)) {
                // Cache is fresh — skip the initial burst entirely.
                // v2.8.1 — Background pagination is OFF (see top
                // comment), so `hasMore` stays false; `loadMore`
                // is only callable explicitly.
                pairsRef.current = (addonsRef.current || []).flatMap(
                    (addon) => (addon.catalogs || [])
                        .filter((c) => c.type === type && catalogSupportsGenre(c, genre))
                        .map((cat) => ({ addon, cat }))
                );
                setHasMore(false);
                return;
            }
        } else {
            pushState();
            setLoading(true);
            setProgress(0);
        }

        // Discover every (addon, catalog) pair that advertises this genre.
        const list = addonsRef.current || [];
        const pairs = [];
        for (const addon of list) {
            const catalogs = (addon.catalogs || []).filter(
                (c) => c.type === type && catalogSupportsGenre(c, genre)
            );
            for (const cat of catalogs) pairs.push({ addon, cat });
        }
        pairsRef.current = pairs;

        if (pairs.length === 0) {
            setLoading(false);
            setProgress(1);
            setHasMore(false);
            return;
        }

        // INITIAL BURST — 4 pages × pairs in parallel.
        const initialJobs = [];
        for (const { addon, cat } of pairs) {
            for (let p = 0; p < INITIAL_PAGES; p++) {
                initialJobs.push({ addon, cat, skip: p * PAGE_SIZE });
            }
        }
        let initialDone = 0;
        const initialPromises = initialJobs.map(async (job) => {
            const added = await fetchPage(job.addon, job.cat, job.skip);
            initialDone++;
            if (runIdRef.current === runId) {
                setProgress(initialDone / initialJobs.length);
                if (added > 0 && initialDone % 4 === 0) pushState();
            }
            return { ...job, added };
        });

        Promise.all(initialPromises).then((results) => {
            if (runIdRef.current !== runId) return;

            // Seed per-pair lastSkip + exhausted set from the burst.
            const perPairAdded = new Map();
            for (const r of results) {
                const id = `${r.addon.id}|${r.cat.id}`;
                perPairAdded.set(id, (perPairAdded.get(id) || 0) + r.added);
                const next = r.skip + PAGE_SIZE;
                const prev = lastSkipRef.current.get(id) || 0;
                if (next > prev) lastSkipRef.current.set(id, next);
            }
            for (const { addon, cat } of pairs) {
                const id = `${addon.id}|${cat.id}`;
                if ((perPairAdded.get(id) || 0) === 0) {
                    exhaustedRef.current.add(id);
                }
            }

            pushState();
            setLoading(false);
            setProgress(1);
            // v2.10.85 — `hasMore` reflects whether ANY (addon, catalog)
            // pair still has unfetched pages.  TabGridView's
            // IntersectionObserver pumps loadMore() on scroll so the
            // grid behaves identically to /networks/<slug> (Stan,
            // Binge, Paramount+ etc.).  See top-of-file rationale.
            const anyAlive = pairs.some(({ addon, cat }) => {
                const id = `${addon.id}|${cat.id}`;
                return !exhaustedRef.current.has(id);
            });
            setHasMore(anyAlive);

            // Cache what we have so far (initial burst).
            try { cache.set(key, { items: Array.from(seenRef.current.values()) }); }
            catch { /* ignore */ }
        }).catch((err) => {
            if (runIdRef.current !== runId) return;
            // eslint-disable-next-line no-console
            console.error('[useTabGenreCatalog] initial burst failed:', err);
            setLoading(false);
            setProgress(1);
        });

        return () => {
            runIdRef.current++;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [key, type, genre]);

    return { items, loading, progress, loadingMore, totalLoaded, hasMore, loadMore };
}
