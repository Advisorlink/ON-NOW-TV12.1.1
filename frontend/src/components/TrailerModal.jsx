/**
 * <TrailerModal/> — popup trailer player.
 *
 * Behaviour depends on platform:
 *
 *   • Android WebView (HK1 box) — we hand the trailer to the NATIVE
 *     libVLC player via `window.OnNowTV.playTrailer(...)`.  The
 *     backend extracts BOTH a 1080p video-only URL and a matching
 *     m4a audio URL from YouTube (since YT only serves combined
 *     audio+video MP4 up to 360p) and the native player merges them
 *     via an input slave.  Result: HD trailer playback, no iframe,
 *     no YouTube app redirect, no chunky 360p, no surprise nags.
 *
 *   • Desktop / preview (no native bridge) — we render the
 *     YouTube iframe in a centered 16:9 modal so trailers still work
 *     when developing on a laptop.
 *
 * In both cases the user can close with Escape / Backspace / the X.
 */
import React, { useEffect, useRef, useState } from 'react';
import { X, Maximize2, Minimize2 } from 'lucide-react';

export default function TrailerModal({ youtubeKey, title, poster, backdrop, onClose }) {
    const [fullscreen, setFullscreen] = useState(false);
    const [resolving, setResolving] = useState(false);
    const [resolveError, setResolveError] = useState(null);
    const cardRef = useRef(null);
    const nativeLaunchedRef = useRef(false);

    /* ----- Native-VLC path (Android WebView) ---------------------
     * On the HK1 box, we'd rather hand the trailer to libVLC than
     * fight the iframe.  As soon as we get a youtubeKey, fetch the
     * extracted stream URLs from the backend and launch the native
     * player.  If the bridge isn't available (web preview), fall
     * through to the iframe path below.
     *
     * v2.10.82 — Added a 20-second hard timeout on the extract
     * call (yt-dlp can occasionally hang behind YouTube signature
     * changes / geo blocks).  On ANY failure on Android we now
     * hand off to the YouTube app via `playYoutubeFallback` so the
     * trailer ALWAYS plays — never a stuck "Loading…" screen. */
    useEffect(() => {
        if (!youtubeKey) return undefined;
        if (nativeLaunchedRef.current) return undefined;
        // v2.7.54 — Simplest reliable check: just see if the bridge
        // OBJECT exists.  `window.OnNowTV` is only ever injected by
        // MainActivity.addJavascriptInterface(...) — never present
        // on web preview / desktop.  Avoid `typeof === 'function'`
        // (Android returns 'object'), avoid `'playTrailer' in bridge`
        // (some WebViews enumerate @JavascriptInterface methods
        // lazily and the property only appears AFTER the first call
        // attempt).  Just trust the object.
        const bridge = (typeof window !== 'undefined') ? window.OnNowTV : null;
        if (!bridge) return undefined;
        let cancel = false;
        const ac = new AbortController();
        const timeoutId = setTimeout(() => {
            try { ac.abort(); } catch { /* ignore */ }
        }, 20000);  // 20 s hard cap
        (async () => {
            try {
                setResolving(true);
                setResolveError(null);
                const r = await fetch(
                    `${process.env.REACT_APP_BACKEND_URL}/api/trailer-stream/${encodeURIComponent(youtubeKey)}`,
                    { signal: ac.signal },
                );
                if (!r.ok) throw new Error(`extract failed (${r.status})`);
                const j = await r.json();
                if (cancel) return;
                const url = j?.url || j?.progressive_url;
                if (!url) throw new Error('no playable URL');
                nativeLaunchedRef.current = true;
                window.OnNowTV.playTrailer(
                    url,
                    j?.audio_url || '',
                    title || 'Trailer',
                    poster || '',
                    backdrop || ''
                );
                // The native player overlays the WebView immediately.
                // Close our modal so the WebView returns to a clean
                // state behind it.
                setTimeout(() => onClose?.(), 80);
            } catch (e) {
                if (cancel) return;
                // v2.10.82 — Try the YouTube app fallback bridge
                // method before showing an error.  This means the
                // user STILL gets a working trailer when yt-dlp
                // chokes or times out.  No more "stuck on Loading"
                // — the screen always opens with playable video.
                const fallbackBridge = window.OnNowTV;
                if (fallbackBridge && typeof fallbackBridge.playYoutubeFallback !== 'undefined') {
                    try {
                        nativeLaunchedRef.current = true;
                        fallbackBridge.playYoutubeFallback(youtubeKey, title || 'Trailer');
                        setTimeout(() => onClose?.(), 80);
                        return;
                    } catch { /* fall through to error UI */ }
                }
                setResolveError(e?.name === 'AbortError'
                    ? 'Trailer extraction timed out.  Open YouTube?'
                    : (e?.message || 'Could not extract trailer.'));
                setResolving(false);
            }
        })();
        return () => {
            cancel = true;
            clearTimeout(timeoutId);
            try { ac.abort(); } catch { /* ignore */ }
        };
    }, [youtubeKey, title, poster, backdrop, onClose]);

    /* Hardware back / Escape → close (fullscreen ↘ windowed; then
     * windowed ↘ closed). */
    useEffect(() => {
        if (!youtubeKey) return undefined;
        const onKey = (e) => {
            if (
                e.key === 'Escape' ||
                e.key === 'GoBack' ||
                e.key === 'Backspace' ||
                e.keyCode === 27 ||
                e.keyCode === 8
            ) {
                e.preventDefault();
                e.stopPropagation();
                if (fullscreen) setFullscreen(false);
                else onClose?.();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [youtubeKey, fullscreen, onClose]);

    /* Auto-focus the close button on open so any subsequent OK
     * press dismisses the modal cleanly. */
    useEffect(() => {
        if (!youtubeKey) return;
        const t = setTimeout(() => {
            cardRef.current
                ?.querySelector('[data-testid="trailer-close"]')
                ?.focus({ preventScroll: true });
        }, 80);
        return () => clearTimeout(t);
    }, [youtubeKey]);

    if (!youtubeKey) return null;

    const nativeHandoff = (typeof window !== 'undefined') && !!window.OnNowTV;
    if (nativeHandoff && !resolveError) {
        return (
            <div
                data-testid="trailer-modal"
                style={{
                    position: 'fixed',
                    inset: 0,
                    zIndex: 200,
                    background: 'rgba(6,8,15,0.92)',
                    backdropFilter: 'blur(10px)',
                    WebkitBackdropFilter: 'blur(10px)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexDirection: 'column',
                    gap: 18,
                    color: '#fff',
                }}
            >
                <div
                    style={{
                        width: 36,
                        height: 36,
                        borderRadius: 999,
                        border: '3px solid rgba(255,255,255,0.18)',
                        borderTopColor: 'var(--vesper-blue, #5DC8FF)',
                        animation: 'vesper-spin 0.9s linear infinite',
                    }}
                />
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'rgba(255,255,255,0.7)',
                        textTransform: 'uppercase',
                    }}
                >
                    {resolving ? 'Loading trailer in HD…' : 'Opening trailer…'}
                </div>
                <button
                    data-testid="trailer-cancel"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onClose}
                    style={{
                        marginTop: 18,
                        height: 40,
                        paddingLeft: 18,
                        paddingRight: 18,
                        borderRadius: 999,
                        background: 'rgba(255,255,255,0.08)',
                        color: 'rgba(255,255,255,0.85)',
                        border: '1px solid rgba(255,255,255,0.16)',
                        fontSize: 13,
                        cursor: 'pointer',
                    }}
                >
                    Cancel
                </button>
                <style>{`@keyframes vesper-spin { to { transform: rotate(360deg); } }`}</style>
            </div>
        );
    }

    /* v2.10.82 — Error UI for Android.  Shows when both the
     * extract AND the YouTube-app fallback have failed.  Gives
     * the user a clear retry path instead of an empty modal. */
    if (nativeHandoff && resolveError) {
        return (
            <div
                data-testid="trailer-modal"
                style={{
                    position: 'fixed',
                    inset: 0,
                    zIndex: 200,
                    background: 'rgba(6,8,15,0.94)',
                    backdropFilter: 'blur(10px)',
                    WebkitBackdropFilter: 'blur(10px)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexDirection: 'column',
                    gap: 14,
                    color: '#fff',
                    padding: 24,
                }}
            >
                <div
                    className="vesper-display"
                    style={{ fontSize: 26, letterSpacing: '-0.02em' }}
                >
                    Trailer not available
                </div>
                <div
                    style={{
                        fontSize: 14,
                        color: 'rgba(255,255,255,0.7)',
                        maxWidth: 460,
                        textAlign: 'center',
                        lineHeight: 1.5,
                    }}
                >
                    {resolveError}
                </div>
                <div style={{ display: 'flex', gap: 12, marginTop: 12 }}>
                    <button
                        data-testid="trailer-open-youtube"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => {
                            try {
                                window.OnNowTV?.playYoutubeFallback?.(
                                    youtubeKey, title || 'Trailer',
                                );
                            } catch { /* ignore */ }
                            setTimeout(() => onClose?.(), 100);
                        }}
                        style={{
                            height: 44,
                            paddingLeft: 20,
                            paddingRight: 22,
                            borderRadius: 999,
                            background: 'var(--vesper-blue, #5DC8FF)',
                            color: 'var(--vesper-bg-0, #06080F)',
                            border: 'none',
                            fontSize: 14,
                            fontWeight: 600,
                            cursor: 'pointer',
                        }}
                    >
                        Open in YouTube
                    </button>
                    <button
                        data-testid="trailer-close"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onClose}
                        style={{
                            height: 44,
                            paddingLeft: 20,
                            paddingRight: 22,
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.08)',
                            color: '#fff',
                            border: '1px solid rgba(255,255,255,0.18)',
                            fontSize: 14,
                            cursor: 'pointer',
                        }}
                    >
                        Cancel
                    </button>
                </div>
            </div>
        );
    }

    const params = new URLSearchParams({
        autoplay: '1',
        rel: '0',
        modestbranding: '1',
        playsinline: '1',
        controls: '1',
        iv_load_policy: '3',
        fs: '0',
        vq: 'hd1080',                       // request HD by default
        origin: typeof window !== 'undefined' ? window.location.origin : '',
    });
    const src = `https://www.youtube.com/embed/${encodeURIComponent(youtubeKey)}?${params}`;

    return (
        <div
            data-testid="trailer-modal"
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 200,
                background: fullscreen ? '#000' : 'rgba(6,8,15,0.82)',
                backdropFilter: fullscreen ? 'none' : 'blur(8px)',
                WebkitBackdropFilter: fullscreen ? 'none' : 'blur(8px)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: fullscreen ? 0 : 32,
                animation: 'vesper-trailer-fade 180ms ease-out',
            }}
        >
            <style>{`
                @keyframes vesper-trailer-fade {
                    from { opacity: 0; }
                    to   { opacity: 1; }
                }
                @keyframes vesper-trailer-rise {
                    from { opacity: 0; transform: translateY(12px) scale(0.97); }
                    to   { opacity: 1; transform: translateY(0)    scale(1); }
                }
            `}</style>
            <div
                ref={cardRef}
                data-testid="trailer-card"
                style={{
                    position: 'relative',
                    width: fullscreen ? '100%' : 'min(1200px, 90vw)',
                    aspectRatio: '16 / 9',
                    maxHeight: fullscreen ? '100%' : 'calc(100vh - 80px)',
                    background: '#000',
                    borderRadius: fullscreen ? 0 : 18,
                    overflow: 'hidden',
                    boxShadow: fullscreen
                        ? 'none'
                        : '0 30px 70px rgba(0,0,0,0.65), 0 0 60px rgba(93,200,255,0.18)',
                    border: fullscreen
                        ? 'none'
                        : '1px solid rgba(93,200,255,0.22)',
                    animation: fullscreen
                        ? undefined
                        : 'vesper-trailer-rise 220ms cubic-bezier(.2,.7,.2,1) both',
                }}
            >
                <iframe
                    data-testid="trailer-iframe"
                    title={title || 'Trailer'}
                    src={src}
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        border: 0,
                    }}
                    allow="autoplay; encrypted-media; fullscreen"
                    allowFullScreen
                />

                {/* HUD pills — top right */}
                <div
                    style={{
                        position: 'absolute',
                        top: 12,
                        right: 12,
                        display: 'flex',
                        gap: 8,
                        zIndex: 10,
                    }}
                >
                    <button
                        data-testid="trailer-expand"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => setFullscreen((v) => !v)}
                        aria-label={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}
                        style={pillBtnStyle}
                    >
                        {fullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
                    </button>
                    <button
                        data-testid="trailer-close"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onClose}
                        aria-label="Close trailer"
                        style={pillBtnStyle}
                    >
                        <X size={16} />
                    </button>
                </div>

                {/* Title strip — top-left, only in windowed mode */}
                {!fullscreen && title && (
                    <div
                        style={{
                            position: 'absolute',
                            top: 14,
                            left: 16,
                            paddingRight: 130,
                            zIndex: 10,
                            pointerEvents: 'none',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: 2,
                        }}
                    >
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 10,
                                letterSpacing: '0.22em',
                                color: 'rgba(255,255,255,0.7)',
                                textTransform: 'uppercase',
                                textShadow: '0 1px 4px rgba(0,0,0,0.7)',
                            }}
                        >
                            Trailer
                        </span>
                        <span
                            style={{
                                fontSize: 15,
                                fontWeight: 700,
                                color: '#fff',
                                letterSpacing: '-0.01em',
                                textShadow: '0 1px 6px rgba(0,0,0,0.7)',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                                whiteSpace: 'nowrap',
                                maxWidth: 480,
                            }}
                        >
                            {title}
                        </span>
                    </div>
                )}
            </div>
        </div>
    );
}

const pillBtnStyle = {
    width: 36,
    height: 36,
    borderRadius: 999,
    background: 'rgba(0,0,0,0.55)',
    border: '1px solid rgba(255,255,255,0.18)',
    backdropFilter: 'blur(6px)',
    WebkitBackdropFilter: 'blur(6px)',
    color: '#fff',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    cursor: 'pointer',
    WebkitTapHighlightColor: 'transparent',
};
