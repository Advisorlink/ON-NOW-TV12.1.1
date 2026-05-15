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

/**
 * Full EPG (24-48 h) for a given stream_id.  Used by the right-hand
 * GUIDE column.  Returns up to `limit` entries; older entries are
 * filtered out client-side so the guide always starts from "now".
 */
export async function getFullEpg(provider, streamId, limit = 40) {
    const result = await fetchDirectOrProxy(
        provider,
        { action: 'get_short_epg', stream_id: streamId, limit },
        '/now-next',
        { provider: blob(provider), stream_id: streamId, limit },
    );
    let items;
    if (result.direct) {
        items = result.data?.epg_listings || [];
        items = items.map((it) => ({
            title: safeAtob(it.title),
            description: safeAtob(it.description),
            start: it.start,
            end: it.end,
            startTimestamp: it.start_timestamp,
            stopTimestamp: it.stop_timestamp,
        }));
    } else {
        items = result.data?.items || [];
    }
    const nowSec = Math.floor(Date.now() / 1000);
    return items.filter((it) => Number(it.stopTimestamp || it.stop_timestamp || 0) > nowSec);
}

function safeAtob(s) {
    if (!s) return '';
    try { return decodeURIComponent(escape(atob(s))); }
    catch { try { return atob(s); } catch { return s; } }
}

/**
 * Fetch the FULL XMLTV EPG for a provider in a single request.
 *
 * Returns:
 *   { epg: { <epg_channel_id>: [{title, startTimestamp, stopTimestamp, …}], … },
 *     channel_count, programme_count, size_bytes, fetched_at, cached }
 *
 * Strategy:
 *   1. Try the provider's `xmltv.php` directly from the WebView — same
 *      origin as the channel/stream requests so this works on the
 *      Android box without CORS hassle.  The provider gzips the
 *      response on the wire so we get the full 14 000-channel EPG in
 *      a single ~3 MB download instead of 14 000 individual JSON
 *      requests.
 *   2. If the direct fetch fails (CORS in a browser, network issue,
 *      etc.), fall back to the backend proxy `/api/xtream/full-epg`
 *      which does the same fetch + parse server-side.
 *
 *   The endpoint returns ~14 000 channels of programme data, so we
 *   stream-parse on the backend.  The frontend just gets a tidy
 *   JSON map keyed by EPG channel id.
 */
export async function getXmltvEpg(provider, { signal, directTimeoutMs = 15000, proxyTimeoutMs = 20000 } = {}) {
    const scheme = provider.scheme || 'http';
    const port = provider.port && provider.port !== '80' && provider.port !== '443'
        ? `:${provider.port}` : '';
    const xmltvUrl =
        `${scheme}://${provider.host}${port}/xmltv.php` +
        `?username=${encodeURIComponent(provider.username)}` +
        `&password=${encodeURIComponent(provider.password)}`;

    /* 1) Try the persistent backend cache FIRST.  The server keeps
     *    this warm via a 6-hourly background scheduler so it returns
     *    within a few hundred milliseconds — beating the direct
     *    XMLTV fetch on cheap Android-7 boxes whose Wi-Fi can take
     *    5–20 s for a 3 MB download.  Gzipped on the wire.
     *
     *    Timeout is generous (25 s) because on a fresh login the
     *    backend may still be mid-prewarm — the request acquires
     *    the per-provider lock, waits for the prewarm to finish,
     *    then serves the persisted copy.  Result: even the FIRST
     *    Live TV visit gets a fully-populated EPG. */
    try {
        const { data } = await axios.get(`${API}/cached-epg`, {
            params: { provider: blob(provider) },
            timeout: 25000,
            signal,
        });
        if (data && data.epg && Object.keys(data.epg).length > 0) {
            return {
                epg: data.epg,
                channelCount: data.channel_count || 0,
                programmeCount: data.programme_count || 0,
                sizeBytes: data.size_bytes || 0,
                cached: true,
                cacheAgeSec: data.cache_age_sec ?? null,
                source: 'backend-cached',
            };
        }
    } catch {
        // Backend cache miss / timeout — fall through.  We don't
        // surface this as an error because the next path usually
        // succeeds.
    }

    /* 2) Direct XMLTV from the IPTV server.  Aborted if it takes
     *    longer than directTimeoutMs — otherwise a dead provider can
     *    hang the entire boot splash. */
    try {
        const ctrl = new AbortController();
        const t = setTimeout(() => ctrl.abort(), directTimeoutMs);
        let res;
        try {
            res = await fetch(xmltvUrl, {
                method: 'GET',
                mode: 'cors',
                credentials: 'omit',
                signal: signal || ctrl.signal,
            });
        } finally {
            clearTimeout(t);
        }
        if (res && res.ok) {
            const text = await res.text();
            const parsed = parseXmltv(text);
            return { ...parsed, source: 'direct' };
        }
    } catch {
        // CORS, network, or our own timeout — fall through to the proxy.
    }

    /* 3) Backend live-fetch proxy fallback.  Lower default timeout so
     *    preview pod (which can't reach the IPTV server) bails fast
     *    and the per-channel loop takes over. */
    try {
        const { data } = await axios.get(`${API}/full-epg`, {
            params: { provider: blob(provider) },
            timeout: proxyTimeoutMs,
            signal,
        });
        return {
            epg: data?.epg || {},
            channelCount: data?.channel_count || 0,
            programmeCount: data?.programme_count || 0,
            sizeBytes: data?.size_bytes || 0,
            cached: !!data?.cached,
            source: 'backend-live',
        };
    } catch (err) {
        /* Last-ditch: return an empty result so the caller can fall
         * back to the per-channel loop without crashing. */
        return { epg: {}, channelCount: 0, programmeCount: 0, sizeBytes: 0, cached: false, source: 'failed', error: err?.message || String(err) };
    }
}

/* Lean XMLTV parser — done in JS because the WebView fetch returns
 * the raw XML string.  Hardened so a malformed / huge payload (e.g.,
 * a CORS-blocked HTML error page or an unbounded broker response)
 * fails gracefully with an empty result rather than crashing the
 * Live TV boot. */
function parseXmltv(xmlText) {
    /* Sanity ceiling — XMLTV for 14 000 channels with 7-day EPG is
     * ~80 MB uncompressed.  Refuse > 100 MB so a server bug can't
     * blow up Chrome 52 on the HK1 box. */
    if (!xmlText || typeof xmlText !== 'string' || xmlText.length < 80) {
        return { epg: {}, channelCount: 0, programmeCount: 0, sizeBytes: xmlText?.length || 0, cached: false };
    }
    if (xmlText.length > 100 * 1024 * 1024) {
        return { epg: {}, channelCount: 0, programmeCount: 0, sizeBytes: xmlText.length, cached: false, error: 'xmltv too large' };
    }
    /* Bail fast if this doesn't look like XMLTV — saves the regex
     * loop from chewing through a 5 MB HTML error page. */
    if (xmlText.indexOf('<programme') === -1) {
        return { epg: {}, channelCount: 0, programmeCount: 0, sizeBytes: xmlText.length, cached: false };
    }

    const epg = {};
    let programmeCount = 0;
    const channels = new Set();

    try {
        /* Regex-based extraction.  XMLTV is grammar-strict so the
         * "regex parse" is safe here — every <programme> appears on
         * its own with attribute syntax in a predictable order. */
        const PRG = /<programme([^>]+)>([\s\S]*?)<\/programme>/g;
        const ATTR = /(\w+)\s*=\s*"([^"]*)"/g;
        const TITLE = /<title[^>]*>([\s\S]*?)<\/title>/;
        const DESC = /<desc[^>]*>([\s\S]*?)<\/desc>/;

        let m;
        while ((m = PRG.exec(xmlText))) {
            const attrs = {};
            let am;
            ATTR.lastIndex = 0;
            while ((am = ATTR.exec(m[1]))) {
                attrs[am[1]] = am[2];
            }
            const channel = attrs.channel || '';
            if (!channel) continue;
            const start = parseXmltvTime(attrs.start);
            const stop = parseXmltvTime(attrs.stop);
            if (!start) continue;
            const tm = TITLE.exec(m[2]);
            const dm = DESC.exec(m[2]);
            const title = tm ? unescapeXml(tm[1]).slice(0, 200) : '';
            const desc = dm ? unescapeXml(dm[1]).slice(0, 600) : '';
            if (!epg[channel]) epg[channel] = [];
            epg[channel].push({
                title,
                description: desc,
                start: attrs.start || '',
                stop: attrs.stop || '',
                startTimestamp: start,
                stopTimestamp: stop,
            });
            channels.add(channel);
            programmeCount += 1;
        }
        for (const ch of Object.keys(epg)) {
            epg[ch].sort((a, b) => a.startTimestamp - b.startTimestamp);
        }
    } catch (err) {
        return { epg: {}, channelCount: 0, programmeCount: 0, sizeBytes: xmlText.length, cached: false, error: err?.message || String(err) };
    }

    return {
        epg,
        channelCount: channels.size,
        programmeCount,
        sizeBytes: xmlText.length,
        cached: false,
    };
}

function parseXmltvTime(raw) {
    if (!raw) return 0;
    // Format: "20260515063000 +0000"
    const m = /^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})/.exec(raw);
    if (!m) return 0;
    // Treat as UTC since EPG times are absolute.
    const dt = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]));
    return Math.floor(dt.getTime() / 1000);
}

function unescapeXml(s) {
    return String(s)
        .replace(/&lt;/g, '<')
        .replace(/&gt;/g, '>')
        .replace(/&quot;/g, '"')
        .replace(/&apos;/g, "'")
        .replace(/&amp;/g, '&');
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
