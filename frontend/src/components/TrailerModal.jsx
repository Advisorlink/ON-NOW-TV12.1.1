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
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { X, Maximize2, Minimize2 } from 'lucide-react';

export default function TrailerModal({ youtubeKey, title, poster, backdrop, onClose }) {
    const [fullscreen, setFullscreen] = useState(false);
    const cardRef = useRef(null);

    /* v2.11.6 — Multi-candidate trailer playback with YouTube
     * IFrame Player API error listening.
     *
     * Problem: some YouTube videos are iframe-embed-BLOCKED by
     * their copyright holder (common on major-studio trailers —
     * Sony/Amazon/Disney tag many uploads as embed-restricted).
     * When an iframe hits such a video it shows "Watch video on
     * YouTube · Error 153" INSIDE the iframe — no DOM-level error
     * event fires because the iframe loaded successfully; only
     * the internal player state changed.
     *
     * Fix: Detail.jsx now passes ALL candidate trailers from TMDB
     * (up to 9 for major titles) as an ordered array.  This modal:
     *
     *   1. Renders an iframe with `enablejsapi=1` + `origin` so
     *      YouTube's IFrame Player API can postMessage error
     *      events back to us.
     *   2. Listens for `window.message` events from
     *      https://www.youtube.com whose data is `{event:"onError",
     *      info:101|150|153}`.  These indicate embed-blocked or
     *      removed videos.
     *   3. On any such error, advances `currentIdx` to the next
     *      candidate → the iframe re-mounts with the next YT id.
     *   4. After the last candidate errors, shows a friendly
     *      "Trailer unavailable" state so the user isn't stuck on
     *      YouTube's error UI.
     *
     * `youtubeKey` prop accepts EITHER:
     *   - a string (single YouTube video id) — legacy call sites
     *   - an array of `{key,name,type}` — new multi-candidate path
     *     from Detail.jsx.openTrailer()
     */
    const candidates = useMemo(() => {
        if (!youtubeKey) return [];
        if (Array.isArray(youtubeKey)) {
            return youtubeKey
                .map((c) => (typeof c === 'string' ? { key: c } : c))
                .filter((c) => c && c.key);
        }
        return [{ key: String(youtubeKey) }];
    }, [youtubeKey]);
    const [currentIdx, setCurrentIdx] = useState(0);
    const [allExhausted, setAllExhausted] = useState(false);
    const currentKey = candidates[currentIdx]?.key || '';

    useEffect(() => {
        setCurrentIdx(0);
        setAllExhausted(false);
    }, [youtubeKey]);

    /* Listen for YouTube IFrame Player error events via postMessage.
     * YouTube error codes we care about:
     *   2   invalid parameter
     *   5   HTML5 player error
     *   100 video removed
     *   101 embed disabled by owner        ← surfaces as Error 153 in UI
     *   150 same as 101, different codepath
     *   153 modern "Video player configuration error" — same cause
     * Also fallback timeout — some embed-blocked videos silently
     * sit on the error card without firing onError.
     */
    useEffect(() => {
        if (!currentKey || allExhausted) return undefined;

        let readyReceived = false;
        let advanced = false;
        const advance = () => {
            if (advanced) return;
            advanced = true;
            setTimeout(() => {
                setCurrentIdx((idx) => {
                    if (idx + 1 >= candidates.length) {
                        setAllExhausted(true);
                        return idx;
                    }
                    return idx + 1;
                });
            }, 40);
        };

        const handleMsg = (ev) => {
            try {
                const origin = ev.origin || '';
                if (!/^https?:\/\/(www\.)?youtube(-nocookie)?\.com$/.test(origin)) return;
                let payload = ev.data;
                if (typeof payload === 'string') {
                    try {
                        payload = JSON.parse(payload);
                    } catch { return; }
                }
                if (!payload || typeof payload !== 'object') return;
                const event = payload.event;
                if (event === 'onReady' || event === 'infoDelivery' || event === 'apiInfoDelivery') {
                    readyReceived = true;
                }
                if (event === 'onError' || event === 'onApiChange') {
                    // onError with any info → embed blocked / removed
                    if (event === 'onError') advance();
                }
            } catch { /* swallow */ }
        };

        window.addEventListener('message', handleMsg);

        // YouTube IFrame API handshake: send `listening` to the
        // iframe so YouTube starts posting `onReady`/`onError`
        // events.  Retry every 400 ms until the iframe becomes
        // available (WebView needs 2-3 s to load the player).
        let handshakeTicks = 0;
        const handshakeTimer = setInterval(() => {
            handshakeTicks += 1;
            const iframe = cardRef.current?.querySelector('iframe[data-testid="trailer-iframe"]');
            if (!iframe || !iframe.contentWindow) return;
            try {
                iframe.contentWindow.postMessage(
                    JSON.stringify({ event: 'listening', id: 'vesper-trailer', channel: 'widget' }),
                    '*'
                );
            } catch { /* swallow */ }
            if (handshakeTicks > 30 || readyReceived) {
                clearInterval(handshakeTimer);
            }
        }, 400);

        // Fallback timeout: 9 s for the iframe to fire onReady on
        // slow WebView JS engines (HK1 boxes need 3-4 s to boot).
        const tId = setTimeout(() => {
            if (!readyReceived) advance();
        }, 9000);

        return () => {
            window.removeEventListener('message', handleMsg);
            clearInterval(handshakeTimer);
            clearTimeout(tId);
        };
    }, [currentKey, candidates.length, allExhausted]);

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

    if (!youtubeKey || !candidates.length) return null;

    /* Compose iframe URL for the current candidate.  We include
     * `enablejsapi=1` + `origin` because we NEED them for the
     * postMessage error channel to work — without them YouTube
     * won't send us onError events and we can't auto-advance.
     * The earlier suspicion that these params CAUSE Error 153 was
     * wrong: verified empirically that a bare
     * `youtube.com/embed/{blocked_id}` (no params at all) still
     * shows Error 153 for embed-blocked videos.  So the params are
     * harmless — might as well enable jsapi and USE it. */
    const origin = typeof window !== 'undefined' ? window.location.origin : '';
    const params = new URLSearchParams({
        autoplay: '1',
        rel: '0',
        modestbranding: '1',
        playsinline: '1',
        controls: '1',
        iv_load_policy: '3',
        fs: '0',
        cc_load_policy: '0',
        enablejsapi: '1',
    });
    if (origin) params.set('origin', origin);
    const src = `https://www.youtube.com/embed/${encodeURIComponent(currentKey)}?${params}`;

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
                {!allExhausted && (
                    <iframe
                        key={currentKey}
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
                        /* v2.11.6 — LOOSER sandbox than v2.11.2.
                         * `allow-scripts allow-same-origin
                         * allow-presentation` was the previous set,
                         * but with `enablejsapi=1` we NEED the iframe
                         * to postMessage back to us; strict
                         * `allow-same-origin` alone is enough for
                         * that.  We keep top-navigation + popups
                         * BLOCKED so "Watch on YouTube" click stays
                         * a no-op inside Vesper's WebView. */
                        sandbox="allow-scripts allow-same-origin allow-presentation"
                        referrerPolicy="origin"
                    />
                )}
                {allExhausted && (
                    <div
                        data-testid="trailer-unavailable"
                        style={{
                            position: 'absolute',
                            inset: 0,
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 20,
                            background: backdrop
                                ? `linear-gradient(rgba(6,8,15,0.75),rgba(6,8,15,0.95)),url(${backdrop}) center/cover`
                                : '#06080f',
                            padding: 32,
                            textAlign: 'center',
                        }}
                    >
                        <div
                            style={{
                                fontFamily: 'monospace',
                                fontSize: 12,
                                letterSpacing: '0.25em',
                                color: '#5DC8FF',
                                textTransform: 'uppercase',
                            }}
                        >
                            Trailer unavailable
                        </div>
                        <div
                            style={{
                                fontSize: 22,
                                fontWeight: 600,
                                color: '#eef4ff',
                                maxWidth: 640,
                                lineHeight: 1.3,
                            }}
                        >
                            {title
                                ? `The uploader has disabled embedded playback for every ${title} trailer we could find.`
                                : 'The uploader has disabled embedded playback for every trailer we could find.'}
                        </div>
                        <div
                            style={{
                                fontSize: 13,
                                color: '#8ea0ba',
                                maxWidth: 500,
                            }}
                        >
                            {candidates.length > 1
                                ? `Checked ${candidates.length} candidate${candidates.length === 1 ? '' : 's'} — none allowed in-app playback.`
                                : 'Only one trailer was available and the copyright holder blocked it.'}
                        </div>
                        <button
                            data-testid="trailer-unavailable-close"
                            onClick={() => onClose?.()}
                            style={{
                                marginTop: 8,
                                padding: '10px 24px',
                                background: 'rgba(93,200,255,0.14)',
                                border: '1px solid rgba(93,200,255,0.4)',
                                borderRadius: 999,
                                color: '#5DC8FF',
                                fontFamily: 'monospace',
                                fontSize: 12,
                                letterSpacing: '0.2em',
                                textTransform: 'uppercase',
                                cursor: 'pointer',
                            }}
                        >
                            Close
                        </button>
                    </div>
                )}

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
