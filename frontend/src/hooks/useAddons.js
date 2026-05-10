import { useEffect, useRef, useState, useCallback } from 'react';
import { Vesper } from '@/lib/api';

/**
 * Default addons we silently bootstrap on first run.  Cinemeta is the
 * IMDB-id metadata backbone everything else depends on; OpenSubtitles
 * v3 unlocks the Player's subtitle picker.  We only auto-install when
 * the user has *zero* addons to avoid resurrecting an addon they
 * deliberately removed.
 */
const BOOTSTRAP_ADDONS = [
    'https://v3-cinemeta.strem.io/manifest.json',
    'https://opensubtitles-v3.strem.io/manifest.json',
];
const BOOTSTRAP_FLAG = 'vesper-bootstrap-attempted-v1';

export function useAddons() {
    const [addons, setAddons] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const bootstrapping = useRef(false);

    const refresh = useCallback(async () => {
        try {
            setLoading(true);
            const data = await Vesper.listAddons();
            const list = Array.isArray(data) ? data : [];
            setAddons(list);
            setError(null);
            return list;
        } catch (e) {
            setError(e?.message || 'Failed to load addons');
            return [];
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
            const list = await refresh();
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
            await refresh();
            return res;
        },
        [refresh]
    );

    const remove = useCallback(
        async (id) => {
            await Vesper.removeAddon(id);
            await refresh();
        },
        [refresh]
    );

    return { addons, loading, error, refresh, install, remove };
}
