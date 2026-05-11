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

export async function authenticate(provider) {
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
    const res = await axios.get(`${API}/categories`, {
        params: { provider: blob(provider), type },
    });
    return res.data?.categories || [];
}

export async function getStreams(provider, type = 'live', categoryId = null) {
    const params = { provider: blob(provider), type };
    if (categoryId) params.category_id = categoryId;
    const res = await axios.get(`${API}/streams`, { params });
    return res.data?.streams || [];
}

export async function getSeriesInfo(provider, seriesId) {
    const res = await axios.get(`${API}/series-info`, {
        params: { provider: blob(provider), series_id: seriesId },
    });
    return res.data || {};
}

export async function getNowNext(provider, streamId) {
    const res = await axios.get(`${API}/now-next`, {
        params: { provider: blob(provider), stream_id: streamId },
    });
    return res.data?.items || [];
}

export async function getStreamUrl(provider, type, streamId, ext = 'ts') {
    const res = await axios.get(`${API}/stream-url`, {
        params: {
            provider: blob(provider),
            type,
            stream_id: streamId,
            container_extension: ext,
        },
    });
    return res.data?.url || '';
}
