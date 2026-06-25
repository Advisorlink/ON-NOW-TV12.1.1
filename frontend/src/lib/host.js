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

    // v2.10.61 — Broader "Android-WebView host" detection.  The
    // strict `isAndroid` flag above only fires when the OnNowTV
    // native bridge is injected, so factory Chrome 138 / sideloaded
    // browsers on HK1 / Fire TV / generic Android TV boxes never
    // pick up the `.vesper-host-android` perf-mode CSS.  Result:
    // backdrop-filter blur layers compositing-fail and leave huge
    // empty grey rectangles where the Starting-Playback / loading
    // overlays should be.  We add a second flag that's true when
    // we're on any Android UA running on a TV-sized viewport
    // (>=1200 px) — phones in landscape would be smaller — so the
    // same perf protections apply.  Native bridges (playVideo etc.)
    // remain gated on the strict `isAndroid` flag.
    const isAndroidUA = /Android/i.test(ua);
    const vpW = typeof window !== 'undefined' ? (window.innerWidth || 0) : 0;
    const isAndroidWebViewHost =
        isAndroid ||
        (isAndroidUA && vpW >= 1200) ||
        /Linux\s*\(.*Android|AFT[A-Z]+|TV\s*Box|HK1|RK3|MagicBox/i.test(ua);

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
        // v2.7.25 — Alternate streams + current index for the
        // in-player stream picker overlay.  Pressing MENU/INFO
        // inside the player shows the list; OK swaps without
        // leaving the player.
        streamsList,
        currentStreamIdx,
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
                const streamsJson = Array.isArray(streamsList) && streamsList.length > 0
                    ? JSON.stringify(streamsList.map((s) => ({
                        label: ((s.title || s.name || '(untitled)') + '').slice(0, 200),
                        url: s.url || '',
                        infoHash: s.infoHash || null,
                        // v2.7.33 — propagate the English flag the
                        // backend stamped on each stream, so the
                        // native in-player picker can render a
                        // 🇬🇧 ENGLISH chip.
                        isEnglish: !!s._is_english,
                        // v2.7.48 — propagate the addon source tag
                        // (TORRENTIO / MEDIAFUSION / COMET / PLEXIO /
                        // …) + quality label + Premiumize-cached flag
                        // so the native picker can show the same
                        // chips the React picker does.
                        addonSource: s._addon_source || '',
                        quality: s._quality_label || '',
                        pmCached: !!s._pm_cached,
                    })))
                    : '';
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
                    typeof currentStreamIdx === 'number' ? currentStreamIdx : -1
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
        // v2.10.61 — broader perf-host flag (factory Chrome on TV, etc.)
        isAndroidWebViewHost,
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
    // v2.10.61 — Apply the perf-mode CSS class for ANY Android-WebView
    // host (native wrapper OR factory Chrome 138 on TV), not just the
    // native OnNowTV bridge.  This is what makes the global
    // backdrop-filter strip-out work on factory-Chrome HK1 boxes that
    // were rendering loading overlays as solid grey rectangles.
    if (Host.isAndroidWebViewHost) document.documentElement.classList.add('vesper-host-android');
}

export default Host;
