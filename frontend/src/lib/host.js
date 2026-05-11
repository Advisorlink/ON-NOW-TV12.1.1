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
    } = {}) => {
        if (!url) return false;
        // Internal libVLC player (native, every codec, in-app).
        // Prefer the rich bridge (passes cinematic preview meta).
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
                    Array.isArray(genres) ? genres.join(' · ') : (genres || '')
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

    return {
        isAndroid,
        isOnNowTV,
        isLowEnd: lowEnd,
        playVideo,    // legacy — now always returns false
        playExternal, // explicit "open in VLC" — opt-in only
    };
})();

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
