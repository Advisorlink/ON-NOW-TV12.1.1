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

    // Stremio deep-link → plain https URL
    u = u.replace(/^stremio:\/\//i, 'https://');

    // Strip duplicate scheme prefixes that arise from copy-paste mishaps:
    //   "https://stremio://host/..."  →  "https://host/..."
    //   "https://https://host/..."    →  "https://host/..."
    u = u.replace(/^https?:\/\/(?:stremio:\/\/|https?:\/\/)/i, 'https://');
    // Also handle stray "s://" that's been seen in the wild
    u = u.replace(/^https?:\/\/s:\/\//i, 'https://');

    if (!/^https?:\/\//i.test(u)) u = 'https://' + u;

    let parsed;
    try {
        parsed = new URL(u);
    } catch {
        return null;
    }
    const host = parsed.hostname || '';
    if (host.length < 3 || !host.includes('.')) return null;

    const pathname = parsed.pathname.replace(/\/$/, '');
    if (pathname.endsWith('/manifest.json')) {
        const baseP = pathname.slice(0, -'/manifest.json'.length);
        return {
            base: parsed.origin + baseP,
            manifest: parsed.origin + pathname + (parsed.search || ''),
        };
    }
    const base = parsed.origin + pathname;
    return {
        base,
        manifest: base + '/manifest.json' + (parsed.search || ''),
    };
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
    listAddons: (opts = {}) => api.get('/addons', opts).then((r) => r.data),
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
        if (!norm) {
            const err = new Error(
                'That URL doesn\'t look right. It should start with https:// (or stremio://) and point at a real host.'
            );
            err.userFacing = true;
            throw err;
        }
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
     * Catalog — try backend proxy first (fast, cached, server-side
     * aggregation), and only fall back to direct browser fetch if the
     * backend can't reach the addon (e.g. Cloudflare-walled).
     */
    getCatalog: async (addonId, type, catalogId, params = {}) => {
        try {
            const r = await api.get(
                `/addons/${addonId}/catalog/${type}/${catalogId}`,
                { params }
            );
            return r.data;
        } catch (_e) {
            // fall through to direct fetch
        }
        const addon = await findAddonById(addonId);
        if (!addon?.url) throw new Error('Addon not installed');
        const url = `${trimSlash(addon.url)}/catalog/${type}/${catalogId}${buildExtraPath(params)}.json`;
        const data = await fetchJsonDirect(url);
        return { cached: false, data };
    },

    /** Meta — backend first, browser direct fallback. */
    getMeta: async (type, itemId) => {
        try {
            const r = await api.get(`/meta/${type}/${itemId}`);
            if (r.data?.data?.meta) return r.data;
        } catch (_e) {
            // fall through
        }
        const addons = await Vesper.listAddons();
        addons.sort((a, b) =>
            /cinemeta/i.test(a.id) ? -1 : /cinemeta/i.test(b.id) ? 1 : 0
        );
        for (const a of addons) {
            const supportsMeta = (a.resources || []).some((r) =>
                typeof r === 'string' ? r === 'meta' : r?.name === 'meta'
            );
            if (!supportsMeta) continue;
            const url = `${trimSlash(a.url)}/meta/${type}/${itemId}.json`;
            try {
                const data = await fetchJsonDirect(url);
                if (data?.meta) return { cached: false, data, source: a.id };
            } catch (_e) {
                continue;
            }
        }
        throw new Error('No metadata available');
    },

    /**
     * Streams — try backend proxy first, then browser-direct on each
     * addon for the ones the backend couldn't reach.  Returns
     * { streams, diagnostics } either way.
     */
    getStreams: async (type, itemId) => {
        // 1. Try backend aggregator (cached, parallel)
        let backendStreams = [];
        try {
            const r = await api.get(`/streams/${type}/${itemId}`);
            backendStreams = r.data?.streams || [];
        } catch (_e) {
            // ignore — fall to browser path
        }

        // 2. Browser-direct probe per addon (catches Cloudflare-walled ones).
        const addons = await Vesper.listAddons();
        const seenAddonIds = new Set(
            backendStreams.map((s) => s._addon_id).filter(Boolean)
        );

        const results = await Promise.all(
            addons.map(async (a) => {
                let streamResource = null;
                for (const r of a.resources || []) {
                    if (typeof r === 'string' && r === 'stream') {
                        streamResource = { name: 'stream' };
                        break;
                    }
                    if (typeof r === 'object' && r?.name === 'stream') {
                        streamResource = r;
                        break;
                    }
                }
                if (!streamResource) {
                    return { addon: a, count: 0, skipped: 'no stream resource' };
                }

                // Backend already returned streams from this addon; trust it.
                if (seenAddonIds.has(a.id)) {
                    const fromBackend = backendStreams.filter(
                        (s) => s._addon_id === a.id
                    );
                    return { addon: a, count: fromBackend.length, streams: fromBackend };
                }

                // Honour resource-level idPrefixes (Torrentio scopes here).
                const prefixes =
                    (Array.isArray(streamResource.idPrefixes) &&
                        streamResource.idPrefixes) ||
                    a.id_prefixes ||
                    [];
                if (
                    prefixes.length &&
                    !prefixes.some((p) => itemId.startsWith(p))
                ) {
                    return { addon: a, count: 0, skipped: 'id prefix mismatch' };
                }

                const url = `${trimSlash(a.url)}/stream/${type}/${itemId}.json`;
                try {
                    const data = await fetchJsonDirect(url, { timeout: 20000 });
                    const streams = Array.isArray(data?.streams)
                        ? data.streams
                        : [];
                    return {
                        addon: a,
                        count: streams.length,
                        streams: streams.map((s) => ({
                            ...s,
                            _addon_id: a.id,
                            _addon_name: a.name || a.id,
                        })),
                    };
                } catch (e) {
                    return {
                        addon: a,
                        count: 0,
                        error: e?.status
                            ? `HTTP ${e.status}`
                            : e?.message || 'fetch failed',
                    };
                }
            })
        );

        const direct = results.flatMap((r) => r.streams || []);
        return { streams: direct, diagnostics: results };
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
