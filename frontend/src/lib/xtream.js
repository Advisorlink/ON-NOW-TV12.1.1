/**
 * Xtream Codes IPTV client + provider management.
 *
 * All credentials live in localStorage; backend never persists them.
 * Each provider blob is shipped verbatim to /api/xtream/* as a JSON
 * string in the `provider` query param so the backend stays
 * stateless.
 */

import axios from 'axios';

const API = `${process.env.REACT_APP_BACKEND_URL}/api/xtream`;
const KEY_PROVIDERS = 'onnowtv-xtream-providers-v1';
const KEY_ACTIVE = 'onnowtv-xtream-active-id';

/**
 * Dev convenience — auto-seed the owner's provider once if the user
 * hasn't configured anything yet.  Saves a trip through the login
 * wizard on every fresh APK install / browser cache clear.  No
 * effect once any provider exists.
 */
const DEFAULT_PROVIDER = {
    id: 'default-njala',
    name: 'On Now TV',
    host: 'njala.ddns.me',
    port: '8443',
    scheme: 'https',
    username: 'ONNOWTV2',
    password: '5259375949',
};

function autoSeed() {
    try {
        const raw = localStorage.getItem(KEY_PROVIDERS);
        const arr = raw ? JSON.parse(raw) : [];
        if (Array.isArray(arr) && arr.length > 0) return;
        localStorage.setItem(KEY_PROVIDERS, JSON.stringify([DEFAULT_PROVIDER]));
        localStorage.setItem(KEY_ACTIVE, DEFAULT_PROVIDER.id);
    } catch { /* ignore */ }
}
autoSeed();

// ---------- providers (localStorage CRUD) ----------

function uuid() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    return 'p-' + Math.random().toString(36).slice(2, 11);
}

export function listProviders() {
    try {
        const raw = localStorage.getItem(KEY_PROVIDERS);
        if (!raw) return [];
        const arr = JSON.parse(raw);
        return Array.isArray(arr) ? arr : [];
    } catch {
        return [];
    }
}

export function getActiveProvider() {
    const id = localStorage.getItem(KEY_ACTIVE);
    const all = listProviders();
    if (!id) return all[0] || null;
    return all.find((p) => p.id === id) || all[0] || null;
}

export function setActiveProvider(id) {
    try {
        localStorage.setItem(KEY_ACTIVE, id);
    } catch {
        /* ignore */
    }
}

export function saveProvider(provider) {
    const list = listProviders();
    const id = provider.id || uuid();
    const next = { ...provider, id };
    const i = list.findIndex((p) => p.id === id);
    if (i >= 0) list[i] = next;
    else list.push(next);
    localStorage.setItem(KEY_PROVIDERS, JSON.stringify(list));
    if (!localStorage.getItem(KEY_ACTIVE)) setActiveProvider(id);
    return next;
}

export function removeProvider(id) {
    const list = listProviders().filter((p) => p.id !== id);
    localStorage.setItem(KEY_PROVIDERS, JSON.stringify(list));
    if (localStorage.getItem(KEY_ACTIVE) === id) {
        if (list.length) setActiveProvider(list[0].id);
        else localStorage.removeItem(KEY_ACTIVE);
    }
}

// ---------- API ----------

function blob(p) {
    return JSON.stringify({
        host: p.host,
        port: p.port,
        scheme: p.scheme,
        username: p.username,
        password: p.password,
    });
}

/**
 * Build the canonical Xtream Codes player_api.php URL for the given
 * provider + action.  We hit this DIRECTLY from the WebView so the
 * backend pod doesn't need outbound access to the user's IPTV server
 * (most IPTV servers are firewalled to residential ISP ranges; the
 * datacenter pod cannot reach them).
 */
function directApiUrl(provider, params = {}) {
    const scheme = provider.scheme || 'http';
    const port = provider.port && provider.port !== '80' && provider.port !== '443'
        ? `:${provider.port}` : '';
    const qs = new URLSearchParams({
        username: provider.username || '',
        password: provider.password || '',
        ...params,
    }).toString();
    return `${scheme}://${provider.host}${port}/player_api.php?${qs}`;
}

/**
 * Fetch wrapper that tries the IPTV server directly first; if that
 * fails (CORS, mixed-content, network) we fall back to the backend
 * proxy at /api/xtream/*.  This gives us the best of both worlds:
 *   • Fast and reliable on the Android WebView (no CORS enforcement).
 *   • Still works in browser preview if the user happens to hit a
 *     CORS-friendly IPTV server.
 */
async function fetchDirectOrProxy(provider, directParams, proxyPath, proxyParams) {
    // 1) Direct first.
    try {
        const r = await fetch(directApiUrl(provider, directParams), {
            method: 'GET',
            cache: 'no-store',
            credentials: 'omit',
        });
        if (r.ok) {
            const data = await r.json();
            return { direct: true, data };
        }
    } catch { /* fall through to proxy */ }

    // 2) Backend proxy fallback.
    const res = await axios.get(`${API}${proxyPath}`, { params: proxyParams });
    return { direct: false, data: res.data };
}

export async function authenticate(provider) {
    // Try direct first.
    try {
        const r = await fetch(directApiUrl(provider), {
            method: 'GET',
            cache: 'no-store',
            credentials: 'omit',
        });
        if (r.ok) {
            const data = await r.json();
            const auth = data?.user_info?.auth;
            // auth === 1 means OK in the Xtream Codes API spec.
            if (auth === 1 || auth === '1') {
                return { ok: true, providerId: data?.user_info?.username || provider.username };
            }
            throw new Error('Authentication rejected by IPTV server');
        }
    } catch (e) {
        // Fall through to backend proxy.
        void e;
    }
    const res = await axios.post(`${API}/auth`, {
        host: provider.host,
        port: provider.port,
        scheme: provider.scheme,
        username: provider.username,
        password: provider.password,
    });
    return res.data;
}

export async function getCategories(provider, type = 'live') {
    const action = type === 'live' ? 'get_live_categories'
        : type === 'vod' ? 'get_vod_categories'
        : 'get_series_categories';
    const { direct, data } = await fetchDirectOrProxy(
        provider,
        { action },
        '/categories',
        { provider: blob(provider), type },
    );
    if (direct) return Array.isArray(data) ? data : [];
    return data?.categories || [];
}

export async function getStreams(provider, type = 'live', categoryId = null) {
    const action = type === 'live' ? 'get_live_streams'
        : type === 'vod' ? 'get_vod_streams'
        : 'get_series';
    const direct = { action };
    if (categoryId) direct.category_id = categoryId;
    const proxy = { provider: blob(provider), type };
    if (categoryId) proxy.category_id = categoryId;
    const result = await fetchDirectOrProxy(provider, direct, '/streams', proxy);
    if (result.direct) return Array.isArray(result.data) ? result.data : [];
    return result.data?.streams || [];
}

export async function getSeriesInfo(provider, seriesId) {
    const result = await fetchDirectOrProxy(
        provider,
        { action: 'get_series_info', series_id: seriesId },
        '/series-info',
        { provider: blob(provider), series_id: seriesId },
    );
    return result.data || {};
}

/**
 * Now-playing + next-up EPG for a live channel.  Xtream returns an
 * `epg_listings` array (base64-encoded title/description).  We
 * normalise it to `{ title, description, start, end,
 * startTimestamp, stopTimestamp }` here so the UI components stay
 * dumb.
 */
export async function getNowNext(provider, streamId) {
    const result = await fetchDirectOrProxy(
        provider,
        { action: 'get_short_epg', stream_id: streamId, limit: 2 },
        '/now-next',
        { provider: blob(provider), stream_id: streamId },
    );
    if (!result.direct) {
        return result.data?.items || [];
    }
    const items = result.data?.epg_listings || [];
    return items.map((it) => ({
        title: safeAtob(it.title),
        description: safeAtob(it.description),
        start: it.start,
        end: it.end,
        startTimestamp: it.start_timestamp,
        stopTimestamp: it.stop_timestamp,
    }));
}

function safeAtob(s) {
    if (!s) return '';
    try { return decodeURIComponent(escape(atob(s))); }
    catch { try { return atob(s); } catch { return s; } }
}

export async function getStreamUrl(provider, type, streamId, ext = 'ts') {
    // For Xtream Codes the stream URL is a direct construct — no
    // round-trip needed.  Format is documented:
    //   live:    {scheme}://{host}:{port}/live/{u}/{p}/{streamId}.ts
    //   vod:     {scheme}://{host}:{port}/movie/{u}/{p}/{streamId}.{ext}
    //   series:  {scheme}://{host}:{port}/series/{u}/{p}/{streamId}.{ext}
    const scheme = provider.scheme || 'http';
    const port = provider.port && provider.port !== '80' && provider.port !== '443'
        ? `:${provider.port}` : '';
    const path = type === 'live' ? 'live'
        : type === 'vod' ? 'movie'
        : 'series';
    const extension = type === 'live' ? 'ts' : ext;
    return `${scheme}://${provider.host}${port}/${path}/${encodeURIComponent(provider.username)}/${encodeURIComponent(provider.password)}/${streamId}.${extension}`;
}
