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
    const cardRef = useRef(null);

    /* v2.11.0 — Trailer playback strategy rewrite.
     *
     * Previous chain (yt-dlp extract → libVLC HD-pair → iframe
     * fallback) was deceptively fragile: yt-dlp can stall behind
     * YouTube signature rolls, libVLC sometimes can't merge the
     * video+audio slave on specific codecs, and we'd end up
     * bouncing between three code paths none of which would land.
     *
     * The new strategy is ONE path: the in-WebView YouTube IFrame
     * embed via `youtube-nocookie.com/embed/{id}`.  Plays in HD
     * (player auto-selects 1080p on a decent connection),
     * stays inside Vesper's WebView (VesperWebViewClient swallows
     * every YouTube intent + main-frame nav, MainActivity's
     * WebChromeClient `onCreateWindow` blocks every popup
     * attempt), supports controls / seek / fullscreen, never
     * breaks behind yt-dlp signature changes because there's no
     * extraction step.
     */

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

    // v2.11.0 — Iframe is the SOLE playback path.  No spinner card,
    // no native libVLC handoff, no extract.  Always renders the
    // YouTube iframe directly so the trailer plays inside Vesper's
    // WebView — WebView-level guards (VesperWebViewClient
    // main-frame nav swallow + MainActivity onCreateWindow swallow)
    // ensure clicks inside the iframe can't trigger a YouTube-app
    // launch.

    /* v2.10.97 — When extract fails on Android, we no longer show
     * an "Open in YouTube" error card.  Instead we set
     * `useIframeFallback = true` in the effect above, which falls
     * through to the in-WebView iframe render path below.  That
     * iframe plays YouTube INSIDE Vesper — never launches the
     * external YouTube app (user spec).  The block that used to
     * live here (the "Trailer not available" error card with the
     * "Open in YouTube" button) is intentionally removed. */

    const params = new URLSearchParams({
        autoplay: '1',
        rel: '0',
        modestbranding: '1',
        playsinline: '1',
        controls: '1',
        iv_load_policy: '3',
        fs: '0',
        vq: 'hd1080',                       // request HD by default
        cc_load_policy: '0',
        enablejsapi: '1',
        widget_referrer: typeof window !== 'undefined' ? window.location.origin : '',
        origin: typeof window !== 'undefined' ? window.location.origin : '',
    });
    /* v2.11.0 — Switched to `youtube-nocookie.com` host.  This is
     * YouTube's privacy-enhanced embed domain — fewer overlays, less
     * aggressive about offering to "Watch on YouTube", and treats
     * embeds as first-class users (no GDPR cookie banner that
     * sometimes blocks playback in EU regions).  Identical video
     * catalog as youtube.com. */
    const src = `https://www.youtube-nocookie.com/embed/${encodeURIComponent(youtubeKey)}?${params}`;

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
