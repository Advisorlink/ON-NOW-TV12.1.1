import axios from 'axios';

const BACKEND_URL = process.env.REACT_APP_BACKEND_URL;
export const API = `${BACKEND_URL}/api`;

export const api = axios.create({
    baseURL: API,
    timeout: 25000,
});

// ---------------------------------------------------------------------------
// Direct addon fetching helpers
//
// Many Stremio addons (Torrentio, Cinemeta, etc.) sit behind Cloudflare bot
// protection that flags datacentre IPs as bots — so our backend can't reach
// them.  Your *browser* on a residential IP usually can, and Stremio addons
// universally serve permissive CORS, so we try the browser-direct path first
// and fall back to the backend proxy only when that fails.
// ---------------------------------------------------------------------------

const trimSlash = (s) => s.replace(/\/+$/, '');

export function normaliseManifestUrl(raw) {
    let u = (raw || '').trim();
    if (!u) return null;
    if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
    if (u.endsWith('/manifest.json')) {
        return { base: u.slice(0, -'/manifest.json'.length), manifest: u };
    }
    const base = trimSlash(u);
    return { base, manifest: base + '/manifest.json' };
}

async function fetchJsonDirect(url, { timeout = 15000 } = {}) {
    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeout);
    try {
        const res = await fetch(url, {
            mode: 'cors',
            cache: 'no-store',
            signal: ctl.signal,
        });
        if (!res.ok) {
            const txt = await res.text().catch(() => '');
            const err = new Error(`HTTP ${res.status} from ${url}`);
            err.status = res.status;
            err.body = txt.slice(0, 200);
            throw err;
        }
        return await res.json();
    } finally {
        clearTimeout(t);
    }
}

const buildExtraPath = (extra) => {
    if (!extra) return '';
    const parts = Object.entries(extra)
        .filter(([, v]) => v !== undefined && v !== null && v !== '')
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`);
    return parts.length ? '/' + parts.join('&') : '';
};

// ---------------------------------------------------------------------------
// Vesper API client
// ---------------------------------------------------------------------------

export const Vesper = {
    listAddons: () => api.get('/addons').then((r) => r.data),
    suggestedAddons: () => api.get('/addons/suggested').then((r) => r.data),

    /**
     * Install an addon by URL.
     *  1. Try to fetch the manifest from this browser (residential IP,
     *     CORS allowed) and post {url, manifest} to backend — backend
     *     stores without re-fetching.
     *  2. If browser fetch fails (no internet, weird CORS, etc.), post
     *     just {url} so the backend tries — works for non-CF addons.
     */
    installAddon: async (rawUrl) => {
        const norm = normaliseManifestUrl(rawUrl);
        if (!norm) throw new Error('Empty URL');
        let manifest = null;
        try {
            manifest = await fetchJsonDirect(norm.manifest);
        } catch (_e) {
            // fall through to server-side fetch
        }
        const body = manifest ? { url: norm.base, manifest } : { url: norm.base };
        const res = await api.post('/addons/install', body);
        return res.data;
    },

    removeAddon: (addonId) =>
        api.delete(`/addons/${addonId}`).then((r) => r.data),

    /**
     * Catalog — try direct fetch from addon, fall back to backend proxy.
     */
    getCatalog: async (addonId, type, catalogId, params = {}) => {
        const addon = await findAddonById(addonId);
        if (addon?.url) {
            const url = `${trimSlash(addon.url)}/catalog/${type}/${catalogId}${buildExtraPath(params)}.json`;
            try {
                const data = await fetchJsonDirect(url);
                return { cached: false, data };
            } catch (_e) {
                // fall through
            }
        }
        const r = await api.get(
            `/addons/${addonId}/catalog/${type}/${catalogId}`,
            { params }
        );
        return r.data;
    },

    /** Meta — iterate installed addons in browser, then fall back to backend. */
    getMeta: async (type, itemId) => {
        const addons = await Vesper.listAddons();
        // Cinemeta first
        addons.sort((a, b) =>
            /cinemeta/i.test(a.id) ? -1 : /cinemeta/i.test(b.id) ? 1 : 0
        );
        for (const a of addons) {
            const supportsMeta = (a.resources || []).some((r) =>
                typeof r === 'string' ? r === 'meta' : r?.name === 'meta'
            );
            if (!supportsMeta) continue;
            const prefixes = a.id_prefixes || [];
            if (prefixes.length && !prefixes.some((p) => itemId.startsWith(p)))
                continue;
            const url = `${trimSlash(a.url)}/meta/${type}/${itemId}.json`;
            try {
                const data = await fetchJsonDirect(url);
                if (data?.meta) return { cached: false, data, source: a.id };
            } catch (_e) {
                continue;
            }
        }
        // Last resort: backend (might also fail if addon is CF-walled)
        const r = await api.get(`/meta/${type}/${itemId}`);
        return r.data;
    },

    /** Streams — aggregate across all addons in the browser. */
    getStreams: async (type, itemId) => {
        const addons = await Vesper.listAddons();
        const tasks = addons.map(async (a) => {
            const supportsStream = (a.resources || []).some((r) =>
                typeof r === 'string' ? r === 'stream' : r?.name === 'stream'
            );
            if (!supportsStream) return [];
            const prefixes = a.id_prefixes || [];
            if (prefixes.length && !prefixes.some((p) => itemId.startsWith(p)))
                return [];
            const url = `${trimSlash(a.url)}/stream/${type}/${itemId}.json`;
            try {
                const data = await fetchJsonDirect(url, { timeout: 20000 });
                const streams = Array.isArray(data?.streams) ? data.streams : [];
                return streams.map((s) => ({
                    ...s,
                    _addon_id: a.id,
                    _addon_name: a.name || a.id,
                }));
            } catch (_e) {
                return [];
            }
        });
        const lists = await Promise.all(tasks);
        const direct = lists.flat();
        if (direct.length > 0) return { cached: false, streams: direct };
        // Fallback to server-side aggregator (works for non-CF addons)
        const r = await api.get(`/streams/${type}/${itemId}`);
        return r.data;
    },
};

async function findAddonById(id) {
    try {
        const list = await Vesper.listAddons();
        return list.find((a) => a.id === id) || null;
    } catch {
        return null;
    }
}

/** Resolve a Stremio poster URL or fall back to null. */
export const resolvePoster = (item) => item?.poster || item?.posterUrl || null;
