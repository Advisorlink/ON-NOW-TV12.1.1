/**
 * Detects the Android host environment (the WebView wrapper that
 * sideloads the app onto the HK1 box).  Exposes:
 *
 *   - `isAndroid`  — true when running inside the OnNowTV WebView
 *   - `isLowEnd`   — Android reports its memory class is small, OR
 *                    the device looks low-end via heuristic UA sniffing
 *   - `playVideo({url, title, type})` — hands a stream off to the
 *     system video player (VLC / MX Player / Kodi / etc.).  Returns
 *     true if it dispatched, false if no Android bridge is available
 *     (so the caller can fall back to the in-page <video>).
 */

const Host = (() => {
    const a = typeof window !== 'undefined' ? window.OnNowTV : null;
    const ua = (typeof navigator !== 'undefined' && navigator.userAgent) || '';
    const isAndroid = !!a && typeof a.isAndroidHost === 'function';
    const isOnNowTV = /OnNowTV\//.test(ua);

    let lowEnd = false;
    if (isAndroid) {
        try {
            lowEnd = a.deviceClass() === 'low';
        } catch {
            lowEnd = false;
        }
    } else {
        // Browser fallback heuristic — covers HK1-style cheap boxes
        // running TV browsers, Fire TV Silk, etc.
        const cores = navigator.hardwareConcurrency || 4;
        const mem = navigator.deviceMemory || 4;
        if (cores <= 2 || mem <= 2) lowEnd = true;
    }

    const playVideo = ({
        url,
        title,
        type,
        subtitleUrl,
        poster,
        backdrop,
        synopsis,
        year,
        rating,
        runtime,
        genres,
        startAtMs,
        cwId,
        // Watch Together — only set when arriving via a party.
        partyCode,
        partyRole,
        partyMemberId,
        partyWsUrl,
        partyAvatarEmoji,  // single emoji char ('🦁') used for reactions
        partyDisplayName,  // 'Mum', 'Test', etc.
        // v2.7.25 — Alternate streams for the in-player stream
        // picker overlay.  Pressing MENU/INFO inside the player
        // shows the list; OK swaps without leaving the player.
        // v2.13.4 — the current index is recomputed here by URL
        // match after filtering out unplayable torrent entries.
        streamsList,
        currentStreamIdx, // eslint-disable-line no-unused-vars
    } = {}) => {
        if (!url) return false;
        // Internal libVLC player (native, every codec, in-app).
        // Prefer the party-aware bridge when we have party params.
        if (
            isAndroid &&
            partyCode &&
            typeof a.playInternalParty === 'function'
        ) {
            try {
                a.playInternalParty(
                    url,
                    title || '',
                    subtitleUrl || '',
                    poster || '',
                    backdrop || '',
                    synopsis || '',
                    year || '',
                    rating == null ? '' : String(rating),
                    runtime || '',
                    Array.isArray(genres) ? genres.join(' · ') : (genres || ''),
                    type || '',
                    typeof startAtMs === 'number' ? Math.floor(startAtMs) : 0,
                    cwId || '',
                    partyCode,
                    partyRole || 'guest',
                    partyMemberId || '',
                    partyWsUrl || '',
                    partyAvatarEmoji || '',
                    partyDisplayName || ''
                );
                return true;
            } catch {
                return false;
            }
        }
        // Prefer the rich V2 bridge (passes cinematic preview meta
        // + alternate-streams payload for the in-player picker).
        if (isAndroid && typeof a.playInternalRichV2 === 'function') {
            try {
                /* v2.13.4 — The picker must ONLY list streams that can
                 * actually play.  Torrent entries (magnet:/infoHash —
                 * i.e. NOT debrid-cached) can never play on the box:
                 * libvlc-all 3.6.0 ships no bittorrent demuxer and
                 * ExoPlayer can't ingest magnets either, so picking
                 * one just buffered forever (the "swap stream does
                 * nothing" bug).  Keep http(s) URLs only (direct +
                 * Premiumize-cached torrents) and recompute the
                 * current index against the filtered list by URL. */
                const rows = Array.isArray(streamsList)
                    ? streamsList
                        .map((s) => ({
                            label: ((s.title || s.name || '(untitled)') + '').slice(0, 200),
                            url: s.url || '',
                            infoHash: s.infoHash || null,
                            isEnglish: !!s._is_english,
                            addonSource: s._addon_source || '',
                            quality: s._quality_label || '',
                            pmCached: !!s._pm_cached,
                        }))
                        .filter((e) => /^https?:\/\//i.test(e.url))
                    : [];
                const streamsJson = rows.length > 0 ? JSON.stringify(rows) : '';
                const curIdx = rows.findIndex((e) => e.url === url);
                a.playInternalRichV2(
                    url,
                    title || '',
                    subtitleUrl || '',
                    poster || '',
                    backdrop || '',
                    synopsis || '',
                    year || '',
                    rating == null ? '' : String(rating),
                    runtime || '',
                    Array.isArray(genres) ? genres.join(' · ') : (genres || ''),
                    type || '',
                    typeof startAtMs === 'number' ? Math.floor(startAtMs) : 0,
                    cwId || '',
                    streamsJson,
                    curIdx
                );
                return true;
            } catch {
                // Fall through to legacy V1 bridge below.
            }
        }
        // Legacy rich bridge (V1 — no streams payload).
        if (isAndroid && typeof a.playInternalRich === 'function') {
            try {
                a.playInternalRich(
                    url,
                    title || '',
                    subtitleUrl || '',
                    poster || '',
                    backdrop || '',
                    synopsis || '',
                    year || '',
                    rating == null ? '' : String(rating),
                    runtime || '',
                    Array.isArray(genres) ? genres.join(' · ') : (genres || ''),
                    type || '',
                    typeof startAtMs === 'number' ? Math.floor(startAtMs) : 0,
                    cwId || ''
                );
                return true;
            } catch {
                return false;
            }
        }
        if (isAndroid && typeof a.playInternal === 'function') {
            try {
                a.playInternal(url, title || '', subtitleUrl || '');
                return true;
            } catch {
                return false;
            }
        }
        return false;
    };

    const playExternal = ({ url, title, type } = {}) => {
        if (!url) return false;
        if (isAndroid && typeof a.playExternal === 'function') {
            try {
                a.playExternal(url, title || '', mimeFor(url, type));
                return true;
            } catch {
                return false;
            }
        }
        // Older APK versions used `playVideo` as the external handoff
        if (isAndroid && typeof a.playVideo === 'function') {
            try {
                a.playVideo(url, title || '', mimeFor(url, type));
                return true;
            } catch {
                return false;
            }
        }
        return false;
    };

    /**
     * Voice search.  Two modes:
     *
     *   1. Native (Android WebView) — calls
     *      `window.OnNowTV.startVoiceSearch(id)` which launches the
     *      system speech recognizer (Google Voice).  The bridge fires
     *      `window.__voiceSearchResult(id, text, error)` when done.
     *
     *   2. Browser preview — falls back to the Web Speech API
     *      (`webkitSpeechRecognition`) so the developer can test
     *      the flow in Chrome without flashing an APK.
     *
     * Returns a Promise<string> that resolves with the recognized
     * phrase, or rejects with an Error whose message is one of
     * `cancelled` / `unsupported` / `empty` / `error`.
     */
    const voiceSearch = () => {
        // Native path
        if (isAndroid && a && typeof a.startVoiceSearch === 'function') {
            return new Promise((resolve, reject) => {
                const id =
                    'vs_' +
                    Date.now() +
                    '_' +
                    Math.random().toString(36).slice(2, 8);
                if (typeof window !== 'undefined') {
                    window.__voiceSearchResult = (
                        callbackId,
                        text,
                        error
                    ) => {
                        if (callbackId !== id) return;
                        if (error) reject(new Error(error));
                        else if (!text) reject(new Error('empty'));
                        else resolve(text);
                    };
                }
                try {
                    a.startVoiceSearch(id);
                } catch (e) {
                    reject(new Error('unsupported'));
                }
            });
        }
        // Browser fallback — Web Speech API.
        if (typeof window === 'undefined') {
            return Promise.reject(new Error('unsupported'));
        }
        const SR =
            window.SpeechRecognition || window.webkitSpeechRecognition;
        if (!SR) return Promise.reject(new Error('unsupported'));
        return new Promise((resolve, reject) => {
            const rec = new SR();
            rec.lang = navigator.language || 'en-US';
            rec.interimResults = false;
            rec.maxAlternatives = 1;
            rec.continuous = false;
            rec.onresult = (e) => {
                const r = e.results?.[0]?.[0]?.transcript || '';
                if (r) resolve(r);
                else reject(new Error('empty'));
            };
            rec.onerror = (e) => reject(new Error(e.error || 'error'));
            rec.onend = () => {
                /* If we got here without resolving, treat as cancelled. */
            };
            try {
                rec.start();
            } catch (err) {
                reject(new Error('error'));
            }
        });
    };

    /** True if either native or browser STT is available. */
    const isVoiceSearchAvailable = () => {
        if (isAndroid && a && typeof a.startVoiceSearch === 'function')
            return true;
        if (typeof window === 'undefined') return false;
        return !!(window.SpeechRecognition || window.webkitSpeechRecognition);
    };

    // ──────────────────────────────────────────────────────────────
    // v2.7.39 — Video-player backend switcher (LibVLC ⇄ ExoPlayer).
    // ──────────────────────────────────────────────────────────────
    /** Returns "libvlc" | "exoplayer" | null (browser preview). */
    const getPlayerBackend = () => {
        if (!isAndroid || !a) return null;
        if (typeof a.getPlayerBackend !== 'function') return 'libvlc'; // older APK
        try {
            const b = a.getPlayerBackend();
            return (b === 'exoplayer' || b === 'exo') ? 'exoplayer' : 'libvlc';
        } catch {
            return 'libvlc';
        }
    };

    /** Set backend.  `b` must be "libvlc" or "exoplayer". */
    const setPlayerBackend = (b) => {
        if (!isAndroid || !a || typeof a.setPlayerBackend !== 'function') {
            return false;
        }
        try {
            a.setPlayerBackend(b);
            return true;
        } catch {
            return false;
        }
    };

    return {
        isAndroid,
        isOnNowTV,
        isLowEnd: lowEnd,
        playVideo,
        playExternal,
        voiceSearch,
        isVoiceSearchAvailable,
        publicAsset,
        // v2.7.39 — player backend toggle (A/B test).
        getPlayerBackend,
        setPlayerBackend,
    };
})();

/**
 * Resolve a public-folder asset URL (e.g. "networks/disney-plus.webp")
 * to something that works both in the hosted preview (http://) and
 * in the sideloaded APK (file:///android_asset/web/index.html).
 *
 * Under `file://` an absolute path like "/networks/x.webp" resolves
 * to the device root and 404s.  We resolve against `document.baseURI`
 * instead, which always points at the index.html the WebView loaded.
 */
function publicAsset(path) {
    if (!path) return path;
    const clean = path.startsWith('/') ? path.slice(1) : path;
    if (typeof window === 'undefined') return `/${clean}`;
    if (window.location.protocol === 'file:' && typeof document !== 'undefined') {
        try {
            return new URL(clean, document.baseURI).toString();
        } catch {
            return `./${clean}`;
        }
    }
    return `/${clean}`;
}

function mimeFor(url, type) {
    const u = (url || '').toLowerCase();
    if (u.includes('.m3u8')) return 'application/x-mpegurl';
    if (u.includes('.mpd')) return 'application/dash+xml';
    if (u.includes('.mkv')) return 'video/x-matroska';
    if (u.includes('.mp4')) return 'video/mp4';
    if (u.includes('.webm')) return 'video/webm';
    if (u.includes('.ts')) return 'video/mp2t';
    return type || 'video/*';
}

// Expose performance mode to CSS so we can switch off heavy effects.
if (typeof document !== 'undefined') {
    if (Host.isLowEnd) document.documentElement.classList.add('vesper-low-end');
    if (Host.isAndroid) document.documentElement.classList.add('vesper-host-android');
}

export default Host;
