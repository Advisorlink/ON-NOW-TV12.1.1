import axios from 'axios';

const BACKEND_URL = process.env.REACT_APP_BACKEND_URL;
export const API = `${BACKEND_URL}/api`;

export const api = axios.create({
    baseURL: API,
    timeout: 25000,
});

export const Vesper = {
    listAddons: () => api.get('/addons').then((r) => r.data),
    suggestedAddons: () => api.get('/addons/suggested').then((r) => r.data),
    installAddon: (url) =>
        api.post('/addons/install', { url }).then((r) => r.data),
    removeAddon: (addonId) =>
        api.delete(`/addons/${addonId}`).then((r) => r.data),
    getCatalog: (addonId, type, catalogId, params = {}) =>
        api
            .get(`/addons/${addonId}/catalog/${type}/${catalogId}`, { params })
            .then((r) => r.data),
    getMeta: (type, itemId) =>
        api.get(`/meta/${type}/${itemId}`).then((r) => r.data),
    getStreams: (type, itemId) =>
        api.get(`/streams/${type}/${itemId}`).then((r) => r.data),
};

/** Resolve a Stremio poster URL or fall back to null. */
export const resolvePoster = (item) => item?.poster || item?.posterUrl || null;
