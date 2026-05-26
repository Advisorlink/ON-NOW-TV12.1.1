import { useEffect, useMemo, useState, useRef } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';

/**
 * Fetch EVERY title in a given genre across every installed addon.
 *
 * v2.0 — Continuous background pagination so the user sees the
 * complete library, not just the first 400 titles.
 *
 * Strategy:
 *   • Initial burst — 4 parallel pages of 100 per catalog (skip
 *     0/100/200/300).  Renders the first ~400 items per catalog
 *     within ~1 s so the grid is interactive immediately.
 *   • Background continuation — once the initial burst lands, fire
 *     successive 4-page batches per catalog in the background until
 *     a batch returns 0 new items (i.e. the catalog is exhausted),
 *     OR we hit MAX_PAGES_HARD as a safety cap.  Items stream into
 *     the grid as they arrive via incremental setState.
 *   • Dedupe by IMDb id, sort by year DESC.
 *   • Cache the final exhaustive result so the second visit is
 *     instant.
 *
 * Hook is inert (returns the seed `items`) when `genre` is falsy.
 *
 * Params:
 *   addons      — installed addon list (stable identity required).
 *   type        — 'movie' | 'series'.
 *   genre       — '' to bypass, otherwise the genre string.
 *   seedItems   — top-100 newest already loaded (used as starting
 *                 point so the first frame after the user picks a
 *                 chip is never empty).
 *
 * Returned shape:
 *   items     — current merged + sorted list (grows over time).
 *   loading   — true during the initial burst only.
 *   progress  — 0..1 progress of the INITIAL burst.
 *   loadingMore — true during background continuation.
 *   totalLoaded — running count of unique items (useful for a
 *                 "Loaded 1,247 titles so far…" footer).
 */
const PAGE_SIZE = 100;
const INITIAL_PAGES = 4;         // first burst — fast first paint
const BATCH_PAGES = 4;           // background batch size
const MAX_PAGES_HARD = 50;       // 50 × 100 = 5,000 per catalog cap
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
    const runIdRef = useRef(0);

    useEffect(() => {
        if (!genre) {
            setItems(seedItems || []);
            setLoading(false);
            setLoadingMore(false);
            setProgress(1);
            setTotalLoaded((seedItems || []).length);
            return;
        }

        const runId = ++runIdRef.current;

        // Shared dedup map across initial burst + background batches.
        // Pre-seeded with any matching items from seedItems so the
        // first paint is never empty.
        const seen = new Map();
        const g = genre.toLowerCase();
        for (const it of seedItems || []) {
            if ((it.genres || []).some((x) => (x || '').toLowerCase() === g)) {
                const k = it.routePath || it.imdbId || it.id;
                if (k && !seen.has(k)) seen.set(k, it);
            }
        }

        // Helper to push the current state to React, sorted by year.
        const pushState = () => {
            if (runIdRef.current !== runId) return;
            const merged = Array.from(seen.values()).sort(
                (a, b) => b.year - a.year
            );
            setItems(merged);
            setTotalLoaded(merged.length);
        };

        // Hot cache path — instant render of the previously-cached
        // exhaustive list.  We STILL kick off a background revalidate
        // so the list stays fresh and grows if new titles appeared.
        const cur = cache.get(key);
        if (cur && Array.isArray(cur.value?.items)) {
            for (const it of cur.value.items) {
                const k = it.routePath || it.imdbId || it.id;
                if (k && !seen.has(k)) seen.set(k, it);
            }
            pushState();
            setLoading(false);
            setProgress(1);
            if (!cache.isStale(cur, TTL_MS)) return;
        } else {
            pushState();    // shows the filtered seedItems immediately
            setLoading(true);
            setProgress(0);
        }

        // Discover every (addon, catalog) pair that advertises this
        // genre.  Each pair is paged independently so a slow catalog
        // doesn't block fast ones.
        const list = addonsRef.current || [];
        const pairs = [];
        for (const addon of list) {
            const catalogs = (addon.catalogs || []).filter(
                (c) => c.type === type && catalogSupportsGenre(c, genre)
            );
            for (const cat of catalogs) {
                pairs.push({ addon, cat });
            }
        }

        if (pairs.length === 0) {
            setLoading(false);
            setProgress(1);
            return;
        }

        // Fetch one page from one (addon, catalog) pair.  Returns
        // the count of NEW items added (so we can detect catalog
        // exhaustion: 0 new items = stop paging this catalog).
        async function fetchPage(addon, cat, skip) {
            try {
                const res = await Vesper.getCatalog(addon.id, cat.type, cat.id, {
                    genre,
                    skip,
                });
                const metas = res?.data?.metas || [];
                if (!metas.length) return 0;
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
        }

        // Phase 1 — INITIAL BURST: 4 pages × pairs in parallel.
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
                // Stream new items into the grid every ~4 pages so the
                // user sees fresh rows during the initial burst.
                if (added > 0 && initialDone % 4 === 0) pushState();
            }
            return { ...job, added };
        });

        Promise.all(initialPromises).then((results) => {
            if (runIdRef.current !== runId) return;
            pushState();
            setLoading(false);
            setProgress(1);

            // Phase 2 — BACKGROUND CONTINUATION.  For each pair, if
            // ANY of its initial pages returned items, keep paging in
            // batches of 4 until a whole batch returns 0 (exhausted),
            // or we hit the hard cap.
            const exhausted = new Set();
            // A pair is "alive" if its last initial page returned > 0.
            const lastSkipPerPair = new Map();
            for (const r of results) {
                const id = `${r.addon.id}|${r.cat.id}`;
                const prev = lastSkipPerPair.get(id) || 0;
                if (r.added > 0 && r.skip + PAGE_SIZE > prev) {
                    lastSkipPerPair.set(id, r.skip + PAGE_SIZE);
                }
            }
            // Pairs that got NO results in the initial burst are
            // already considered exhausted — don't keep crawling them.
            for (const { addon, cat } of pairs) {
                const id = `${addon.id}|${cat.id}`;
                if (!lastSkipPerPair.has(id)) exhausted.add(id);
            }

            if (lastSkipPerPair.size === 0) {
                // No catalog had any results past the initial burst —
                // we're done.  Cache and exit.
                try { cache.set(key, { items: Array.from(seen.values()) }); }
                catch { /* ignore */ }
                return;
            }

            setLoadingMore(true);

            async function continueCrawling() {
                let round = 0;
                while (runIdRef.current === runId) {
                    round++;
                    const batchJobs = [];
                    for (const { addon, cat } of pairs) {
                        const id = `${addon.id}|${cat.id}`;
                        if (exhausted.has(id)) continue;
                        const startSkip = lastSkipPerPair.get(id) || 0;
                        for (let p = 0; p < BATCH_PAGES; p++) {
                            const skip = startSkip + p * PAGE_SIZE;
                            if (skip >= MAX_PAGES_HARD * PAGE_SIZE) {
                                exhausted.add(id);
                                break;
                            }
                            batchJobs.push({ addon, cat, skip });
                        }
                    }
                    if (batchJobs.length === 0) break;

                    const batchResults = await Promise.all(
                        batchJobs.map((j) =>
                            fetchPage(j.addon, j.cat, j.skip).then((added) => ({ ...j, added }))
                        )
                    );
                    if (runIdRef.current !== runId) return;

                    // Advance per-pair `lastSkip` only by the pages
                    // that actually returned items; mark pair
                    // exhausted when its entire batch returned 0.
                    const perPairAdded = new Map();
                    for (const r of batchResults) {
                        const id = `${r.addon.id}|${r.cat.id}`;
                        perPairAdded.set(id, (perPairAdded.get(id) || 0) + r.added);
                    }
                    for (const r of batchResults) {
                        const id = `${r.addon.id}|${r.cat.id}`;
                        if (perPairAdded.get(id) === 0) {
                            exhausted.add(id);
                            continue;
                        }
                        const prev = lastSkipPerPair.get(id) || 0;
                        const next = r.skip + PAGE_SIZE;
                        if (next > prev) lastSkipPerPair.set(id, next);
                    }

                    pushState();    // stream the new rows into the grid

                    // Politeness: yield to the browser between
                    // batches so scroll/focus stays buttery on TV.
                    await new Promise((resolve) => setTimeout(resolve, 120));
                }
                // Cache the final exhaustive list.
                if (runIdRef.current === runId) {
                    try { cache.set(key, { items: Array.from(seen.values()) }); }
                    catch { /* ignore */ }
                    setLoadingMore(false);
                }
            }
            continueCrawling();
        }).catch((err) => {
            if (runIdRef.current !== runId) return;
            // eslint-disable-next-line no-console
            console.error('[useTabGenreCatalog] initial burst failed:', err);
            setLoading(false);
            setProgress(1);
        });

        return () => {
            // Bump runId so the inflight continuation aborts its
            // setState calls cleanly when the effect re-runs.
            runIdRef.current++;
        };
        // `seedItems` excluded — it's a fresh array every render
        // and would loop the effect.  Reads it via closure at
        // effect-run time, which is the correct semantic anyway.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [key, type, genre]);

    return { items, loading, progress, loadingMore, totalLoaded };
}
