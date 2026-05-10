import { useEffect, useState, useCallback } from 'react';
import { Vesper } from '@/lib/api';

export function useAddons() {
    const [addons, setAddons] = useState([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);

    const refresh = useCallback(async () => {
        try {
            setLoading(true);
            const data = await Vesper.listAddons();
            setAddons(Array.isArray(data) ? data : []);
            setError(null);
        } catch (e) {
            setError(e?.message || 'Failed to load addons');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        refresh();
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
