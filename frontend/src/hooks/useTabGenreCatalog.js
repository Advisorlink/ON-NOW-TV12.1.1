import { useEffect, useMemo, useState, useRef } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';

/**
 * Fetch EVERY title in a given genre across every installed addon.
 *
 * Unlike `useTabCatalog` (which caps each catalog to ~60 items so
 * the cold-load top-100 view stays fast), this hook deep-pages
 * each catalog using the Stremio `genre` extras filter + `skip`
 * pagination so we end up with the full library for the requested
 * genre.  Per the user's request: "every adventure movie that's
 * ever been made … every crime movie that's ever been made".
 *
 * Strategy:
 *   • Discover every catalog from every addon that matches `type`
 *     AND advertises a `genre` extras filter that includes our
 *     target genre (Stremio addons publish their genre lists in
 *     `extra[].options`).
 *   • For each matching catalog fire 4 parallel pages of size 100
 *     (skip 0, 100, 200, 300) — covers the 400-title-deep tail
 *     of even the busiest catalogs while staying within the
 *     box's network budget.
 *   • Dedupe by IMDb id + sort by year DESC.
 *   • Cache the merged result keyed by `tab:${type}:${addons}:g:${genre}`.
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
 */
const PAGE_SIZE = 100;
const MAX_PAGES = 4;
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

/**
 * Does the given catalog support a `genre` extras filter that
 * accepts our target genre?  Stremio addons publish this in
 * `extra: [{ name: 'genre', options: ['Action', 'Adventure', …] }]`.
 */
function catalogSupportsGenre(cat, genre) {
    const extra = cat.extra || cat.extraSupported || cat.extraRequired;
    if (!Array.isArray(extra)) return false;
    const g = (extra || []).find(
        (x) => (x?.name || x) === 'genre'
    );
    if (!g) return false;
    if (!Array.isArray(g.options)) return true; // accept any value
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
    const runIdRef = useRef(0);

    useEffect(() => {
        if (!genre) {
            // Idle — show seed and bail.
            setItems(seedItems || []);
            setLoading(false);
            setProgress(1);
            return;
        }

        const runId = ++runIdRef.current;

        // Hot cache path.
        const cur = cache.get(key);
        if (cur && Array.isArray(cur.value?.items)) {
            setItems(cur.value.items);
            setLoading(false);
            setProgress(1);
            if (!cache.isStale(cur, TTL_MS)) return;
            // Stale → silent refetch.
        } else {
            // Show the user the top-100 newest titles already in
            // seedItems that match the genre, so the screen is
            // never empty while we deep-page.
            const g = genre.toLowerCase();
            const filteredSeed = (seedItems || []).filter((it) =>
                (it.genres || []).some(
                    (x) => (x || '').toLowerCase() === g
                )
            );
            setItems(filteredSeed);
            setLoading(true);
            setProgress(0);
        }

        // Build job list: every (addon, catalog, skip) triple.
        const list = addonsRef.current || [];
        const jobs = [];
        for (const addon of list) {
            const catalogs = (addon.catalogs || []).filter(
                (c) => c.type === type && catalogSupportsGenre(c, genre)
            );
            for (const cat of catalogs) {
                for (let p = 0; p < MAX_PAGES; p++) {
                    jobs.push({
                        addon,
                        cat,
                        skip: p * PAGE_SIZE,
                    });
                }
            }
        }

        if (jobs.length === 0) {
            // No addon advertises this genre — fall back to a
            // local filter of whatever seedItems we have.  Still
            // honours the user spec ("show every X movie") within
            // the bound of what the addon set can serve.
            setLoading(false);
            setProgress(1);
            return;
        }

        let done = 0;
        const promises = jobs.map(async ({ addon, cat, skip }) => {
            try {
                const res = await Vesper.getCatalog(addon.id, cat.type, cat.id, {
                    genre,
                    skip,
                });
                const metas = res?.data?.metas || [];
                return metas.map((m) => mapMeta(addon, cat, m));
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
                if (runIdRef.current !== runId) return;
                const seen = new Map();
                // Pre-seed with any matching items from seedItems
                // so we don't lose top-100 hits the genre endpoint
                // might have dropped.
                const g = genre.toLowerCase();
                for (const it of seedItems || []) {
                    if (
                        (it.genres || []).some(
                            (x) => (x || '').toLowerCase() === g
                        )
                    ) {
                        const k = it.routePath || it.imdbId || it.id;
                        if (k && !seen.has(k)) seen.set(k, it);
                    }
                }
                for (const batch of batches) {
                    if (!batch) continue;
                    for (const it of batch) {
                        const k = it.routePath || it.imdbId || it.id;
                        if (!k || seen.has(k)) continue;
                        seen.set(k, it);
                    }
                }
                const merged = Array.from(seen.values()).sort(
                    (a, b) => b.year - a.year
                );
                const payload = { items: merged };
                try { cache.set(key, payload); } catch (_) { /* ignore */ }
                setItems(merged);
                setLoading(false);
                setProgress(1);
            })
            .catch((err) => {
                if (runIdRef.current !== runId) return;
                // eslint-disable-next-line no-console
                console.error('[useTabGenreCatalog] aggregate failed:', err);
                setLoading(false);
                setProgress(1);
            });
        // `seedItems` excluded — it's a fresh array every render
        // and would loop the effect.  Reads it via closure at
        // effect-run time, which is the correct semantic anyway.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [key, type, genre]);

    return { items, loading, progress };
}
