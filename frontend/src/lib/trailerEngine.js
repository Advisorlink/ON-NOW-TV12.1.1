/**
 * Shared trailer engine — ONE playback path for the Home hover
 * preview and the Detail-page TrailerModal.
 *
 *   • candidates  : TMDB → ordered YouTube keys (cached per title)
 *   • native      : on the Android box, `OnNowTV.previewTrailer`
 *                   extracts a MUXED ≤720p googlevideo URL that a
 *                   plain <video> plays with sound (no iframe, no
 *                   "Watch on YouTube · Error 153").  Cached + de-duped
 *                   so re-focusing a tile is instant.
 */
import { API } from '@/lib/api';

const CAND_TTL = 30 * 60_000;
const NATIVE_TTL = 20 * 60_000;
const NATIVE_TIMEOUT = 12_000;
export const MAX_NATIVE_TRIES = 3;

const candCache = new Map(); // "type:id" -> {ts, list}
const nativeCache = new Map(); // videoId -> {ts, url, title} | {ts, fail}
const inflight = new Map(); // dedupe key -> Promise

export const bridge = () => (typeof window !== 'undefined' ? window.OnNowTV : null);
export const isBox = () => !!bridge();
export const hasNativePreview = () => typeof bridge()?.previewTrailer === 'function';

function dedupe(key, factory) {
    if (inflight.has(key)) return inflight.get(key);
    const p = factory().finally(() => inflight.delete(key));
    inflight.set(key, p);
    return p;
}

/* Chained global callback so we coexist with any other hook. */
const nativeCallbacks = new Map();
function hookBridgeCallback() {
    if (window.__vesperTrailerHooked) return;
    window.__vesperTrailerHooked = true;
    const prev = window.__trailerReady;
    window.__trailerReady = (id, result) => {
        const cb = nativeCallbacks.get(id);
        if (cb) {
            nativeCallbacks.delete(id);
            cb(result);
            return;
        }
        try { prev?.(id, result); } catch { /* ignore */ }
    };
}

function callBridge(videoId) {
    return new Promise((resolve) => {
        hookBridgeCallback();
        const id = 'tr-' + Math.random().toString(36).slice(2, 10);
        const t = setTimeout(() => {
            nativeCallbacks.delete(id);
            resolve(null);
        }, NATIVE_TIMEOUT);
        nativeCallbacks.set(id, (r) => {
            clearTimeout(t);
            resolve(r);
        });
        try {
            bridge().previewTrailer(id, videoId);
        } catch {
            clearTimeout(t);
            nativeCallbacks.delete(id);
            resolve(null);
        }
    });
}

/** Resolve ONE YouTube id → {url,title} muxed stream (cached). */
export function resolveNativeTrailer(videoId) {
    if (!videoId || !hasNativePreview()) return Promise.resolve(null);
    const hit = nativeCache.get(videoId);
    if (hit && Date.now() - hit.ts < NATIVE_TTL) {
        return Promise.resolve(hit.fail ? null : { url: hit.url, title: hit.title });
    }
    return dedupe(`n:${videoId}`, async () => {
        const r = await callBridge(videoId);
        if (r?.videoUrl) {
            nativeCache.set(videoId, { ts: Date.now(), url: r.videoUrl, title: r.title || '' });
            return { url: r.videoUrl, title: r.title || '' };
        }
        nativeCache.set(videoId, { ts: Date.now(), fail: true });
        return null;
    });
}

/** Try candidates in order (max 3) → {url,title,key} | null. */
export async function resolveMuxedTrailer(candidates, isStale) {
    const keys = (candidates || [])
        .map((c) => (typeof c === 'string' ? c : c?.key))
        .filter(Boolean)
        .slice(0, MAX_NATIVE_TRIES);
    for (const key of keys) {
        const r = await resolveNativeTrailer(key);
        if (isStale?.()) return null;
        if (r?.url) return { ...r, key };
    }
    return null;
}

/**
 * Ordered YouTube candidate keys for a title.  Accepts a TMDB id or
 * an IMDb id (resolved via /tmdb/find-by-imdb).
 */
export function fetchTrailerCandidates({ type = 'movie', tmdbId = '', imdbId = '' }) {
    const ck = tmdbId ? `${type}:${tmdbId}` : `imdb:${imdbId}`;
    const hit = candCache.get(ck);
    if (hit && Date.now() - hit.ts < CAND_TTL) return Promise.resolve(hit.list);
    return dedupe(`c:${ck}`, async () => {
        let tmdb = tmdbId;
        let mediaType = type;
        try {
            if (!tmdb && imdbId.startsWith('tt')) {
                const fr = await fetch(`${API}/tmdb/find-by-imdb/${imdbId}`);
                if (fr.ok) {
                    const fj = await fr.json();
                    tmdb = fj.tmdb_id ? String(fj.tmdb_id) : '';
                    mediaType = fj.media_type || type;
                }
            }
            if (!tmdb) return [];
            const tr = await fetch(`${API}/tmdb/trailer/${mediaType}/${tmdb}`);
            if (!tr.ok) return [];
            const data = (await tr.json())?.data;
            const list = (data?.candidates?.length
                ? data.candidates.map((c) => c.key)
                : [data?.key]).filter(Boolean);
            candCache.set(ck, { ts: Date.now(), list });
            return list;
        } catch {
            return [];
        }
    });
}

/** Read a tile's data-preview-* attributes into a lookup request. */
export function tileTrailerRequest(tile) {
    return {
        type: tile.getAttribute('data-preview-type') || 'movie',
        tmdbId: tile.getAttribute('data-preview-tmdb') || '',
        imdbId: tile.getAttribute('data-preview-imdb') || '',
    };
}

/** Warm both caches for a tile (used for the next-sibling prefetch). */
export async function prefetchTileTrailer(tile) {
    if (!tile) return;
    const req = tileTrailerRequest(tile);
    if (!req.tmdbId && !req.imdbId.startsWith('tt')) return;
    const keys = await fetchTrailerCandidates(req);
    if (keys.length && hasNativePreview()) await resolveMuxedTrailer(keys);
}
