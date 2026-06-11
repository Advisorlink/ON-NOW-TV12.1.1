/**
 * IndexedDB-backed cache for the big Live TV blobs (channels + EPG).
 *
 * Why this file exists
 * --------------------
 * Prior to v2.7.77 the entire Live TV cache lived in `localStorage`.
 * Per-origin localStorage quota on Android WebViews is typically
 * 5–10 MB — but the instant-bundle ships ~6 MB of channels and up
 * to ~40 MB of EPG.  The legacy `liveCache.js` had a hard 1 MB
 * ceiling per write, which meant **the channels list and EPG were
 * NEVER persisted** — they only lived in `memCache` (in-memory).
 *
 * Result: every time the WebView reloaded (cold boot, app re-launch,
 * back-from-player, etc.) memCache was wiped and the page had to
 * re-fetch the 6.6 MB gzipped bundle and re-parse 42 MB of JSON
 * just to render channels.  That's the 30–40 s "loading guide…"
 * delay the user has been complaining about.
 *
 * IndexedDB has no realistic quota issue on Android WebView
 * (~50–250 MB depending on free space), and writes are async so
 * they don't block the UI thread.  Reads are equally cheap.
 *
 * This module exposes:
 *   • saveChannelsIdb(providerId, byCatId)
 *   • loadChannelsIdb(providerId)            → Promise<map|null>
 *   • saveEpgIdb(providerId, byStreamId)
 *   • loadEpgIdb(providerId)                 → Promise<map|null>
 *   • clearIdbForProvider(providerId)
 *
 * Schema: one DB ("onnowtv-livecache-v1") with two object stores:
 *   • "channels"  keyed by providerId → { at, data }
 *   • "epg"       keyed by providerId → { at, data }
 */

const DB_NAME = 'onnowtv-livecache-v2';
const DB_VERSION = 1;
const STORE_CHANNELS = 'channels';
const STORE_EPG = 'epg';

let _dbPromise = null;

function openDb() {
    if (_dbPromise) return _dbPromise;
    _dbPromise = new Promise((resolve, reject) => {
        if (typeof indexedDB === 'undefined') {
            reject(new Error('IndexedDB unavailable'));
            return;
        }
        const req = indexedDB.open(DB_NAME, DB_VERSION);
        req.onupgradeneeded = (ev) => {
            const db = ev.target.result;
            if (!db.objectStoreNames.contains(STORE_CHANNELS)) {
                db.createObjectStore(STORE_CHANNELS);
            }
            if (!db.objectStoreNames.contains(STORE_EPG)) {
                db.createObjectStore(STORE_EPG);
            }
        };
        req.onsuccess = () => resolve(req.result);
        req.onerror   = () => reject(req.error || new Error('IDB open failed'));
        req.onblocked = () => reject(new Error('IDB open blocked'));
    });
    return _dbPromise;
}

function txGet(storeName, key) {
    return openDb().then(
        (db) => new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, 'readonly');
            const store = tx.objectStore(storeName);
            const req = store.get(key);
            req.onsuccess = () => resolve(req.result);
            req.onerror   = () => reject(req.error);
        }),
    );
}

function txPut(storeName, key, value) {
    return openDb().then(
        (db) => new Promise((resolve, reject) => {
            const tx = db.transaction(storeName, 'readwrite');
            const store = tx.objectStore(storeName);
            const req = store.put(value, key);
            tx.oncomplete = () => resolve();
            tx.onerror    = () => reject(tx.error || req.error);
            tx.onabort    = () => reject(tx.error || new Error('IDB tx aborted'));
        }),
    );
}

function txDel(storeName, key) {
    return openDb().then(
        (db) => new Promise((resolve) => {
            try {
                const tx = db.transaction(storeName, 'readwrite');
                tx.objectStore(storeName).delete(key);
                tx.oncomplete = () => resolve();
                tx.onerror    = () => resolve();   // swallow — best effort
            } catch {
                resolve();
            }
        }),
    );
}

export async function loadChannelsIdb(providerId) {
    try {
        const row = await txGet(STORE_CHANNELS, providerId);
        if (!row || !row.data) return null;
        return row.data;
    } catch { return null; }
}

export async function saveChannelsIdb(providerId, byCatId) {
    if (!providerId || !byCatId) return;
    try {
        await txPut(STORE_CHANNELS, providerId, { at: Date.now(), data: byCatId });
    } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('[liveCacheIdb] saveChannels failed:', e?.message || e);
    }
}

export async function loadEpgIdb(providerId) {
    try {
        const row = await txGet(STORE_EPG, providerId);
        if (!row || !row.data) return null;
        return row.data;
    } catch { return null; }
}

export async function saveEpgIdb(providerId, byStreamId) {
    if (!providerId || !byStreamId) return;
    try {
        await txPut(STORE_EPG, providerId, { at: Date.now(), data: byStreamId });
    } catch (e) {
        // eslint-disable-next-line no-console
        console.warn('[liveCacheIdb] saveEpg failed:', e?.message || e);
    }
}

export async function clearIdbForProvider(providerId) {
    if (!providerId) return;
    await txDel(STORE_CHANNELS, providerId);
    await txDel(STORE_EPG, providerId);
}

/** Returns true if IndexedDB is available on this WebView. */
export function isIdbSupported() {
    try { return typeof indexedDB !== 'undefined'; }
    catch { return false; }
}
