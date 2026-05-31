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
 * @param {object} [opts]
 * @param {boolean} [opts.karaoke]
 *   v2.8.81 — When true the resolver appends " karaoke instrumental"
 *   to the search title so YouTube returns a karaoke / minus-one /
 *   instrumental version of the song instead of the original with
 *   vocals.  Used by the Karaoke flow so singers actually have
 *   something to sing over.
 */
export async function resolveTrackStream(track, opts) {
    const artist = track?.artist?.name || '';
    let title  = track?.title || '';
    if (!artist || !title) return null;
    if (opts && opts.karaoke) title = `${title} karaoke instrumental`;

    const emit = (detail) => {
        try {
            if (typeof window !== 'undefined') {
                window.dispatchEvent(new CustomEvent('onnowtv-resolver-event', { detail }));
            }
        } catch { /* ignore */ }
    };

    // 1) Native bridge first.
    if (hasNativeBridge()) {
        const t0 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
        const native = await resolveViaNative(artist, title);
        const ms = Math.round(((typeof performance !== 'undefined') ? performance.now() : Date.now()) - t0);
        if (native && native.ok) {
            // v2.8.55 — Three possible shapes from the bridge:
            //   • `source: 'youtube-direct'` + `url` → AD-FREE
            //     direct googlevideo.com URL from authenticated
            //     TVHTML5 InnerTube call.  Plays in HTML5 <audio>.
            //   • `source: 'youtube-iframe'` + `yt_id` → fallback
            //     to YouTube's IFrame Player (may show ads on free
            //     accounts; Premium accounts still ad-free).
            //   • Legacy `source: 'newpipe'` + `url` (kept for
            //     backwards compat with v2.8.48-style bridges).
            if (native.source === 'youtube-direct' && native.url) {
                emit({ artist, title, source: 'youtube-direct', ms, ok: true });
                return {
                    stream_url: native.url,
                    source: 'youtube-direct',
                    is_full_track: true,
                    title: native.title,
                    uploader: native.uploader,
                    duration: native.duration,
                    yt_id: native.yt_id,
                };
            }
            if (native.source === 'youtube-iframe' && native.yt_id) {
                emit({ artist, title, source: 'youtube-iframe', ms, ok: true });
                return {
                    stream_url: null,
                    source: 'youtube-iframe',
                    is_full_track: true,
                    yt_id: native.yt_id,
                    title: native.title,
                    uploader: native.uploader,
                    duration: native.duration,
                };
            }
            if (native.url) {
                emit({ artist, title, source: 'newpipe', ms, ok: true });
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
        }
        emit({ artist, title, source: 'newpipe', ms, ok: false, error: (native?.error) || 'no url' });
        // Fall through to backend if the bridge couldn't resolve.
    } else {
        emit({ artist, title, source: 'no-bridge', ms: 0, ok: false, error: 'native bridge not present' });
    }

    // 2) Backend (admin cookies → JioSaavn → Audius → preview).
    const tb0 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
    const backend = await resolveViaBackend(track.id, artist, title);
    const tbMs = Math.round(((typeof performance !== 'undefined') ? performance.now() : Date.now()) - tb0);
    if (backend && backend.stream_url && backend.is_full_track) {
        emit({ artist, title, source: backend.source || 'backend', ms: tbMs, ok: true });
        return {
            stream_url: backend.stream_url,
            source: backend.source,
            is_full_track: true,
            title: backend.title,
            uploader: backend.uploader,
            duration: backend.duration,
        };
    }
    // backend returned only a 30 s preview (or nothing) — try the
    // YT IFrame route below before falling back to that preview.
    const backendPreview = backend && backend.stream_url ? backend : null;
    emit({ artist, title, source: 'backend', ms: tbMs, ok: false, error: backend ? (backend.reason || 'no full stream_url') : 'fetch failed' });

    // 2.5) YouTube IFrame player fallback (works WITHOUT cookies).
    // v2.8.64 — Uses `/api/music/yt-search` to get the top YouTube
    // video ID for "artist title".  The IFrame Player handles audio
    // playback in-browser — works on desktop, mobile AND the HK1
    // APK regardless of whether the native InnerTube bridge is
    // present.  This is the reliable Karaoke playback path.
    try {
        const base = (process.env.REACT_APP_BACKEND_URL || '').replace(/\/$/, '');
        const tyt0 = (typeof performance !== 'undefined') ? performance.now() : Date.now();
        const r = await fetch(`${base}/api/music/yt-search?q=${encodeURIComponent(artist + ' ' + title + ' audio')}`);
        const tytMs = Math.round(((typeof performance !== 'undefined') ? performance.now() : Date.now()) - tyt0);
        if (r.ok) {
            const body = await r.json();
            const ytId = body?.data?.yt_id;
            if (ytId) {
                emit({ artist, title, source: 'yt-iframe-search', ms: tytMs, ok: true });
                return {
                    stream_url: null,
                    source: 'youtube-iframe',
                    is_full_track: true,
                    yt_id: ytId,
                    title: body?.data?.title,
                    uploader: body?.data?.uploader,
                    duration: body?.data?.duration,
                };
            }
        }
        emit({ artist, title, source: 'yt-iframe-search', ms: tytMs, ok: false, error: 'no yt_id from search' });
    } catch (e) {
        emit({ artist, title, source: 'yt-iframe-search', ms: 0, ok: false, error: 'yt-search threw: ' + (e?.message || e) });
    }

    // 3) Last-resort previews:  prefer the backend's preview (it may
    //    have additional metadata) over Deezer's, but both work the
    //    same way at the audio element.
    if (backendPreview && backendPreview.stream_url) {
        emit({ artist, title, source: 'preview-backend', ms: 0, ok: false, error: 'using backend preview' });
        return {
            stream_url: backendPreview.stream_url,
            source: backendPreview.source || 'preview',
            is_full_track: false,
            duration: backendPreview.duration,
        };
    }
    if (track.preview_url) {
        emit({ artist, title, source: 'preview', ms: 0, ok: false, error: 'using Deezer 30s' });
        return {
            stream_url: track.preview_url,
            source: 'preview',
            is_full_track: false,
        };
    }
    return null;
}
