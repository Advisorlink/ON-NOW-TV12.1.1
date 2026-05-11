import { useEffect, useRef, useState, useCallback } from 'react';
import { Vesper } from '@/lib/api';
import * as cache from '@/lib/cache';

const BOOTSTRAP_ADDONS = [
    'https://v3-cinemeta.strem.io/manifest.json',
    'https://opensubtitles-v3.strem.io/manifest.json',
];
const BOOTSTRAP_FLAG = 'vesper-bootstrap-attempted-v1';
const ADDONS_CACHE_KEY = 'addons';
const ADDONS_TTL_MS = 5 * 60 * 1000; // refresh in background after 5 min

export function useAddons() {
    // Hydrate from cache synchronously so the first paint already
    // has the addon list — no flash of empty home screen on navigation.
    const cached = cache.get(ADDONS_CACHE_KEY);
    const [addons, setAddons] = useState(
        Array.isArray(cached?.value) ? cached.value : []
    );
    const [loading, setLoading] = useState(!cached);
    const [error, setError] = useState(null);
    const bootstrapping = useRef(false);

    const refresh = useCallback(async () => {
        try {
            // Only show the spinner if we have nothing to render.
            const hasCache = !!cache.get(ADDONS_CACHE_KEY);
            if (!hasCache) setLoading(true);

            // Hard-cap the backend call.  On preview environments
            // the backend can sleep and HTTPS requests hang for 60+
            // seconds.  We bail at 6s and fall back to whatever
            // cache we have (or empty), so the UI never spinners
            // forever.
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 6000);
            let data;
            try {
                data = await Vesper.listAddons({ signal: controller.signal });
            } finally {
                clearTimeout(timeoutId);
            }
            const list = Array.isArray(data) ? data : [];
            cache.set(ADDONS_CACHE_KEY, list);
            setAddons(list);
            setError(null);
            return list;
        } catch (e) {
            setError(e?.message || 'Failed to load addons');
            // Keep whatever cached addons we had.
            const c = cache.get(ADDONS_CACHE_KEY);
            return Array.isArray(c?.value) ? c.value : [];
        } finally {
            setLoading(false);
        }
    }, []);

    // First-run bootstrap: install Cinemeta + OpenSubtitles whenever
    // they're missing on the very first launch, so the home screen and
    // player are useful out of the box.  We persist a per-default flag
    // in localStorage so that if the user explicitly removes one of
    // them later, we won't resurrect it on the next page load.
    useEffect(() => {
        let cancelled = false;
        (async () => {
            // Stale-while-revalidate: if we already have cached addons,
            // skip the network call for the first 5 minutes after a
            // refresh.  This is what makes back-to-Home feel instant.
            const cur = cache.get(ADDONS_CACHE_KEY);
            const list = cur && !cache.isStale(cur, ADDONS_TTL_MS)
                ? cur.value
                : await refresh();
            if (cancelled) return;
            if (bootstrapping.current) return;

            let attempted;
            try {
                attempted = JSON.parse(
                    localStorage.getItem(BOOTSTRAP_FLAG) || '[]'
                );
                if (!Array.isArray(attempted)) attempted = [];
            } catch {
                attempted = [];
            }

            const missing = BOOTSTRAP_ADDONS.filter((url) => {
                if (attempted.includes(url)) return false;
                // Already installed under any id?  Match by host.
                const host = (() => {
                    try {
                        return new URL(url).host;
                    } catch {
                        return '';
                    }
                })();
                if (!host) return false;
                const present = list.some((a) => {
                    try {
                        return new URL(a.url).host === host;
                    } catch {
                        return false;
                    }
                });
                return !present;
            });

            if (missing.length === 0) return;

            bootstrapping.current = true;
            for (const url of missing) {
                try {
                    await Vesper.installAddon(url);
                } catch {
                    /* one bad addon shouldn't kill the rest */
                }
            }
            try {
                localStorage.setItem(
                    BOOTSTRAP_FLAG,
                    JSON.stringify([...attempted, ...missing])
                );
            } catch {
                /* localStorage may be disabled */
            }
            if (!cancelled) await refresh();
            bootstrapping.current = false;
        })();
        return () => {
            cancelled = true;
        };
    }, [refresh]);

    const install = useCallback(
        async (url) => {
            const res = await Vesper.installAddon(url);
            cache.clear(ADDONS_CACHE_KEY);
            await refresh();
            return res;
        },
        [refresh]
    );

    const remove = useCallback(
        async (id) => {
            await Vesper.removeAddon(id);
            cache.clear(ADDONS_CACHE_KEY);
            await refresh();
        },
        [refresh]
    );

    return { addons, loading, error, refresh, install, remove };
}
