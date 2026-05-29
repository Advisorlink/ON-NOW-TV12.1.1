// musicResolver.js — Resolves a (artist, title) → playable audio URL.
//
// Resolution order:
//
//   1. **Native bridge** (preferred). Inside the Tunes APK,
//      `window.OnNowTV.resolveYouTubeAudio(artist, title, cbId)` runs
//      NewPipeExtractor on the BOX's residential IP — bypasses
//      YouTube's datacenter bot block.  Returns a direct
//      googlevideo.com CDN URL the HTML5 `<audio>` element can
//      stream with zero VPS involvement.
//
//   2. **Backend resolver** (fallback).  `/api/music/stream/{id}`
//      tries YouTube (admin cookies) → JioSaavn → Audius → preview.
//
//   3. **30-second Deezer preview** (last resort).  Always works.
//
// Tier 1 has a 10 s timeout — if anything goes wrong (NewPipe API
// drift, YouTube rate-limit, network) we silently fall back to
// Tier 2 and the user just hears their track without delay.

const NATIVE_TIMEOUT_MS = 10_000;

// One-time setup of the JS-side callback dispatcher the native
// bridge writes to.  Idempotent — safe to call from React renders.
function ensureCallbackBus() {
    if (typeof window === 'undefined') return null;
    if (!window.__onnowtvMusicCB) {
        window.__onnowtvMusicCB_pending = window.__onnowtvMusicCB_pending || new Map();
        window.__onnowtvMusicCB = (cbId, payload) => {
            const fn = window.__onnowtvMusicCB_pending.get(cbId);
            if (!fn) return;
            window.__onnowtvMusicCB_pending.delete(cbId);
            fn(payload);
        };
    }
    return window.__onnowtvMusicCB_pending;
}

let _cbCounter = 0;
function nextCallbackId() {
    _cbCounter += 1;
    return 'cb_' + Date.now().toString(36) + '_' + _cbCounter;
}

export function hasNativeBridge() {
    return !!(typeof window !== 'undefined'
        && window.OnNowTV
        && typeof window.OnNowTV.resolveYouTubeAudio === 'function');
}

export function resolveViaNative(artist, title) {
    return new Promise((resolve) => {
        if (!hasNativeBridge()) {
            resolve(null);
            return;
        }
        const pending = ensureCallbackBus();
        if (!pending) {
            resolve(null);
            return;
        }
        const cbId = nextCallbackId();
        const timeoutHandle = setTimeout(() => {
            pending.delete(cbId);
            resolve({ ok: false, error: 'native bridge timed out' });
        }, NATIVE_TIMEOUT_MS);

        pending.set(cbId, (payload) => {
            clearTimeout(timeoutHandle);
            resolve(payload || null);
        });
        try {
            window.OnNowTV.resolveYouTubeAudio(artist, title, cbId);
        } catch (e) {
            clearTimeout(timeoutHandle);
            pending.delete(cbId);
            resolve({ ok: false, error: 'bridge call threw: ' + (e?.message || e) });
        }
    });
}

export async function resolveViaBackend(trackId, artist, title) {
    const base = (process.env.REACT_APP_BACKEND_URL || '').replace(/\/$/, '');
    const url = `${base}/api/music/stream/${encodeURIComponent(trackId)}`
        + `?artist=${encodeURIComponent(artist)}&title=${encodeURIComponent(title)}`;
    try {
        const r = await fetch(url);
        if (!r.ok) return null;
        const body = await r.json();
        return body?.data || null;
    } catch (e) {
        return null;
    }
}

/**
 * Top-level resolver.  Returns either:
 *   { stream_url, source, is_full_track, title?, uploader?, duration? }
 * or null if nothing playable was found.
 *
 * @param {object} track   Deezer-shaped track ({ id, title, artist, preview_url, ... }).
 */
export async function resolveTrackStream(track) {
    const artist = track?.artist?.name || '';
    const title  = track?.title || '';
    if (!artist || !title) return null;

    // 1) Native bridge first.
    if (hasNativeBridge()) {
        const native = await resolveViaNative(artist, title);
        if (native && native.ok && native.url) {
            return {
                stream_url: native.url,
                source: 'newpipe',
                is_full_track: true,
                title: native.title,
                uploader: native.uploader,
                duration: native.duration,
                yt_id: native.yt_id,
            };
        }
        // Fall through to backend if the bridge couldn't resolve.
    }

    // 2) Backend (admin cookies → JioSaavn → Audius → preview).
    const backend = await resolveViaBackend(track.id, artist, title);
    if (backend && backend.stream_url) {
        return {
            stream_url: backend.stream_url,
            source: backend.source,
            is_full_track: !!backend.is_full_track,
            title: backend.title,
            uploader: backend.uploader,
            duration: backend.duration,
        };
    }

    // 3) Last-resort 30 s Deezer preview.
    if (track.preview_url) {
        return {
            stream_url: track.preview_url,
            source: 'preview',
            is_full_track: false,
        };
    }
    return null;
}
