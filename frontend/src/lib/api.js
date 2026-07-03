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

/* v2.13.10 — NON-BLOCKING native bridge fetch.
 * The legacy `window.OnNowTV.fetchUrl(url, timeout)` bridge call is
 * SYNCHRONOUS: a @JavascriptInterface call only returns when the
 * Kotlin method returns, which means the WebView's JS thread was
 * BLOCKED for the entire HTTP round-trip (up to 8 s per addon probe).
 * With several probes in flight this froze all scrolling/UI on the
 * box.  New APKs expose `fetchUrlAsync(url, timeout, requestId)`
 * which returns instantly and posts the result back through
 * `window.__onnowFetchDone(requestId, resultJson)`. */
let bridgeFetchSeq = 0;
const bridgeFetchPending = new Map();
if (typeof window !== 'undefined') {
    window.__onnowFetchDone = (id, raw) => {
        const entry = bridgeFetchPending.get(id);
        if (!entry) return;
        bridgeFetchPending.delete(id);
        clearTimeout(entry.timer);
        entry.resolve(raw);
    };
}

function bridgeFetchAsync(url, timeout) {
    return new Promise((resolve, reject) => {
        const id = `bf${++bridgeFetchSeq}`;
        const timer = setTimeout(() => {
            bridgeFetchPending.delete(id);
            reject(new Error('bridge fetch timeout'));
        }, timeout + 5000);
        bridgeFetchPending.set(id, { resolve, timer });
        try {
            window.OnNowTV.fetchUrlAsync(url, timeout, id);
        } catch (e) {
            bridgeFetchPending.delete(id);
            clearTimeout(timer);
            reject(e);
        }
    });
}

function parseBridgePayload(raw, url) {
    const parsed = JSON.parse(raw);
    if (!parsed.ok) {
        const err = new Error(
            parsed.error || `HTTP ${parsed.status} from ${url}`
        );
        err.status = parsed.status;
        throw err;
    }
    try {
        return JSON.parse(parsed.body || '{}');
    } catch {
        const err = new Error(`Non-JSON response from ${url}`);
        err.body = (parsed.body || '').slice(0, 200);
        throw err;
    }
}

async function fetchJsonDirect(url, { timeout = 15000, signal } = {}) {
    // Prefer the native Android HTTP bridge when running inside the
    // sideloaded APK.  This is critical for stream addons like
    // Torrentio that reject calls from datacentre IPs — the HK1
    // box's residential IP succeeds where the backend proxy gets a
    // Cloudflare wall.
    if (typeof window !== 'undefined' && window.OnNowTV?.fetchUrlAsync) {
        // v2.13.10 — non-blocking bridge (JS thread never freezes).
        try {
            const raw = await bridgeFetchAsync(url, timeout);
            return parseBridgePayload(raw, url);
        } catch (e) {
            if (e?.status === undefined) {
                // bridge failure — try browser fallback below
            } else {
                throw e;
            }
        }
    } else if (typeof window !== 'undefined' && window.OnNowTV?.fetchUrl) {
        // Legacy SYNCHRONOUS bridge (older APKs only — blocks JS).
        try {
            const raw = window.OnNowTV.fetchUrl(url, timeout);
            return parseBridgePayload(raw, url);
        } catch (e) {
            if (e?.status === undefined) {
                // bridge failure — try browser fallback
            } else {
                throw e;
            }
        }
    }

    const ctl = new AbortController();
    const t = setTimeout(() => ctl.abort(), timeout);
    // v2.13.10 — honour an external abort signal ("stop everything
    // the moment the user backs out").
    const onOuterAbort = () => { try { ctl.abort(); } catch { /* */ } };
    if (signal) {
        if (signal.aborted) onOuterAbort();
        else signal.addEventListener('abort', onOuterAbort, { once: true });
    }
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
        if (signal) signal.removeEventListener('abort', onOuterAbort);
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
     *
     * v2.7.30 — accepts an optional `onPartial(streams)` callback.
     * When provided we invoke it with the backend results AS SOON AS
     * they arrive (typically <300 ms when cached), then run the
     * browser-direct probes in the background.  Detail.jsx uses this
     * to render stream tiles immediately so the user isn't staring
     * at a spinner while a slow addon (e.g. cold Torrentio) finishes.
     * Per-addon timeout dropped from 20 s → 8 s for the same reason.
     */
    getStreams: async (type, itemId, onPartial, opts = {}) => {
        /* v2.13.9 — FULLY PARALLEL + PROGRESSIVE (Stremio-style).
         * The old flow SERIALIZED: backend aggregate (≤5 s+) THEN
         * browser-direct probes (≤8 s) and the callers only rendered
         * when everything settled — the user stared at nothing for up
         * to ~15-20 s.  Now the backend call and EVERY per-addon
         * browser probe fire at the same instant, and
         * `onPartial(streams)` is invoked with the accumulated list as
         * EACH source lands — the first links appear as fast as the
         * fastest addon, exactly like Stremio.  Backend entries win
         * over a browser probe for the same addon (they carry richer
         * tags: _is_english / _quality_label / _pm_cached). */
        const signal = opts.signal;
        const byAddon = new Map();      // addonId -> streams[]
        const backendOwned = new Set(); // addon ids answered by backend
        const assemble = () => {
            const out = [];
            for (const arr of byAddon.values()) out.push(...arr);
            return out;
        };
        const emit = () => {
            if (typeof onPartial !== 'function') return;
            if (signal?.aborted) return;
            try { onPartial(assemble()); } catch (_e) { /* ignore */ }
        };

        const backendP = (async () => {
            try {
                const r = await api.get(`/streams/${type}/${itemId}`, { signal });
                const bs = r.data?.streams || [];
                const grouped = new Map();
                for (const s of bs) {
                    const k = s._addon_id || '__backend__';
                    if (!grouped.has(k)) grouped.set(k, []);
                    grouped.get(k).push(s);
                }
                let added = false;
                for (const [k, arr] of grouped) {
                    backendOwned.add(k);
                    byAddon.set(k, arr);
                    added = true;
                }
                if (added) emit();
            } catch (_e) {
                // backend down — the direct probes are already running
            }
        })();

        // Browser-direct probe per addon — fired IN PARALLEL with the
        // backend call above (catches Cloudflare-walled addons).
        let results = [];
        try {
            const addons = await Vesper.listAddons({ signal });
            results = await Promise.all(
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
                        // 8 s cap so a single slow addon can't stall
                        // the FINAL settle (partials already painted).
                        const data = await fetchJsonDirect(url, { timeout: 8000, signal });
                        const streams = (Array.isArray(data?.streams)
                            ? data.streams
                            : []
                        ).map((s) => ({
                            ...s,
                            _addon_id: a.id,
                            _addon_name: a.name || a.id,
                        }));
                        // Progressive merge — skip if the backend
                        // already answered for this addon.
                        if (streams.length > 0 && !backendOwned.has(a.id)) {
                            byAddon.set(a.id, streams);
                            emit();
                        }
                        return { addon: a, count: streams.length, streams };
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
        } catch (_e) {
            // listAddons failed — backend results (if any) still count
        }

        await backendP;
        return { streams: assemble(), diagnostics: results };
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
