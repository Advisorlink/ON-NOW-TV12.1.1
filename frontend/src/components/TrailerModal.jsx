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
import { X, Maximize2, Minimize2, Loader2 } from 'lucide-react';

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
    const [playerReady, setPlayerReady] = useState(false);
    /* v2.11.8 — Native trailer extraction path.
     *
     * When we're running inside the Vesper Android WebView, the
     * `OnNowTV.playTrailer(id, videoId)` JS bridge extracts the
     * direct googlevideo URL on-device using NewPipeExtractor.  The
     * URL is signed for the DEVICE's IP so a plain `<video>` inside
     * this modal streams it straight from googlevideo — bypasses
     * every YouTube iframe embed restriction (Error 153 etc.).
     *
     * `nativeState` values:
     *   'unknown'  first render / bridge availability not resolved
     *   'trying'   Host.playTrailer called; waiting for callback
     *   'muxed'    got a combined video+audio URL — play in <video>
     *   'failed'   extraction failed OR bridge not available; fall
     *              back to iframe cycling (existing v2.11.7 path)
     */
    const [nativeState, setNativeState] = useState('unknown');
    const [nativeUrl, setNativeUrl] = useState('');
    const [nativeTitle, setNativeTitle] = useState('');
    const currentKey = candidates[currentIdx]?.key || '';

    useEffect(() => {
        setCurrentIdx(0);
        setAllExhausted(false);
        setPlayerReady(false);
        setNativeState('unknown');
        setNativeUrl('');
        setNativeTitle('');
    }, [youtubeKey]);

    /* v2.11.8 — Try native extraction for the FIRST candidate only.
     *
     * NewPipeExtractor is bulletproof for videos it can extract;
     * cycling candidates isn't needed because it doesn't have the
     * per-video iframe embed restriction that broke us on the pod.
     * If the FIRST candidate's extraction fails, we fall through
     * to iframe cycling for ALL candidates.
     */
    useEffect(() => {
        if (!currentKey || currentIdx > 0) {
            // Only the first candidate gets the native path.  If
            // we've advanced past index 0, we're already in iframe
            // cycling — don't reset.
            return undefined;
        }
        const bridge = (typeof window !== 'undefined')
            ? window.OnNowTV
            : null;
        if (!bridge || typeof bridge.playTrailer !== 'function') {
            // Browser preview or older APK — use iframe path.
            setNativeState('failed');
            return undefined;
        }
        setNativeState('trying');
        const callbackId = 'vt-' + Math.random().toString(36).slice(2, 10);
        let settled = false;
        // Register the callback globally so the bridge can find us.
        // Multiple modals can be open sequentially so we allow
        // overwrites; when the callback fires with a stale id we
        // just ignore it.
        const prev = window.__trailerReady;
        window.__trailerReady = (id, result) => {
            if (id !== callbackId) {
                // Delegate to previous listener if any.
                try { prev?.(id, result); } catch { /* swallow */ }
                return;
            }
            if (settled) return;
            settled = true;
            if (result && result.videoUrl) {
                if (result.audioUrl) {
                    // DASH pair — hand off to native ExoPlayer.
                    // Modal stays open; native activity comes up
                    // over the top.
                    try {
                        bridge.playTrailerFullscreen(
                            result.videoUrl,
                            result.audioUrl,
                            result.title || title || ''
                        );
                        // Close the modal after handoff — the
                        // native player is now handling playback.
                        setTimeout(() => onClose?.(), 200);
                    } catch {
                        setNativeState('failed');
                    }
                } else {
                    setNativeUrl(result.videoUrl);
                    setNativeTitle(result.title || '');
                    setNativeState('muxed');
                }
            } else {
                // Extraction failed — fall through to iframe.
                setNativeState('failed');
            }
        };
        try {
            bridge.playTrailer(callbackId, currentKey);
        } catch {
            setNativeState('failed');
        }
        // 12 s timeout — if the bridge never calls back, fall
        // through to iframe.  NewPipeExtractor usually completes
        // in 1-3 s on a decent HK1 box.
        const tId = setTimeout(() => {
            if (!settled) {
                settled = true;
                setNativeState('failed');
            }
        }, 12_000);
        return () => {
            settled = true;
            clearTimeout(tId);
        };
    }, [currentKey, currentIdx, title, onClose]);

    // Reset ready state whenever we swap candidate — the new
    // iframe needs a fresh check.
    useEffect(() => {
        setPlayerReady(false);
    }, [currentKey]);

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
        // Only run iframe detection when we're in iframe-fallback mode
        // (native path exhausted or unavailable).
        if (nativeState !== 'failed') return undefined;
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
                if (event === 'onReady' || event === 'infoDelivery' ||
                    event === 'apiInfoDelivery' || event === 'initialDelivery') {
                    readyReceived = true;
                    setPlayerReady(true);
                }
                if (event === 'onError') advance();
            } catch { /* swallow */ }
        };

        window.addEventListener('message', handleMsg);

        // v2.11.7 — YT IFrame API handshake.  Send `listening`
        // message repeatedly until iframe becomes available so YT
        // starts posting `onReady` events back.
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
            if (handshakeTicks > 20 || readyReceived) {
                clearInterval(handshakeTimer);
            }
        }, 250);

        /* v2.11.7 — TIGHTENED READY-TIMEOUT (4.5 s, was 9 s).
         *
         * Verified empirically that YouTube sends ZERO postMessage
         * events for embed-blocked videos — the iframe silently
         * renders "Watch video on YouTube · Error 153" with no
         * `onError` call.  So the ONLY way to detect an embed-block
         * is a ready-timeout.
         *
         * For a PLAYING video: onReady/infoDelivery fires within
         * 700-2500 ms typically (up to ~3 s on slow WebViews).
         * For a BLOCKED video: no events at all, forever.
         *
         * 4.5 s catches every reasonable slow-boot case while not
         * making the operator wait unnecessarily.  If a slow
         * connection false-skips a playable video, the operator
         * will just see the NEXT candidate play — no visible
         * failure.
         */
        const tId = setTimeout(() => {
            if (!readyReceived) advance();
        }, 4500);

        return () => {
            window.removeEventListener('message', handleMsg);
            clearInterval(handshakeTimer);
            clearTimeout(tId);
        };
    }, [currentKey, candidates.length, allExhausted, nativeState]);

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
                {/* v2.11.8 — Native muxed path.  When NewPipeExtractor
                  * hands us a combined video+audio URL from the
                  * device, play it in a plain <video> — bypasses
                  * every iframe embed restriction. */}
                {nativeState === 'muxed' && nativeUrl && (
                    <video
                        key={`native-${currentKey}`}
                        data-testid="trailer-video-native"
                        src={nativeUrl}
                        style={{
                            position: 'absolute',
                            inset: 0,
                            width: '100%',
                            height: '100%',
                            background: '#000',
                            objectFit: 'contain',
                        }}
                        controls
                        autoPlay
                        playsInline
                        onError={() => {
                            // Signed URL expired mid-load or a
                            // network hiccup — fall through to
                            // iframe cycling.
                            setNativeState('failed');
                        }}
                    />
                )}
                {/* Iframe path — used when the native bridge is
                  * unavailable (browser preview / old APK) OR the
                  * native extraction failed. */}
                {nativeState === 'failed' && !allExhausted && (
                    <>
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
                                opacity: playerReady ? 1 : 0,
                                transition: 'opacity 240ms ease-in',
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
                        {/* v2.11.7 — Loading veil.  Covers YouTube's
                          * silent Error-153 card so the operator never
                          * sees "Watch video on YouTube · Error 153"
                          * during the auto-advance detection window.
                          * Fades out once the YT IFrame API confirms
                          * `onReady` (playback confirmed).  If the
                          * 4.5 s ready-timeout fires instead, we've
                          * already advanced to the next candidate and
                          * the iframe key changes — the veil stays put
                          * throughout. */}
                    </>
                )}
                {/* v2.11.8 — Universal loading veil.  Shows during:
                  *   1. native extraction in-flight (nativeState = 'unknown' | 'trying')
                  *   2. iframe fallback boot (nativeState = 'failed', !playerReady, !allExhausted)
                  * Hides once <video> starts (native path) or the
                  * iframe fires onReady (fallback path). */}
                {(nativeState === 'unknown' || nativeState === 'trying' ||
                  (nativeState === 'failed' && !playerReady && !allExhausted)) && (
                    <div
                        data-testid="trailer-loading"
                        style={{
                            position: 'absolute',
                            inset: 0,
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 16,
                            background: backdrop
                                ? `linear-gradient(rgba(6,8,15,0.65),rgba(6,8,15,0.9)),url(${backdrop}) center/cover`
                                : '#06080f',
                            pointerEvents: 'none',
                        }}
                    >
                        <Loader2
                            size={44}
                            style={{
                                animation: 'vesper-trailer-spin 1s linear infinite',
                                color: '#5DC8FF',
                            }}
                        />
                        <div
                            style={{
                                fontFamily: 'monospace',
                                fontSize: 12,
                                letterSpacing: '0.22em',
                                color: '#8de0ff',
                                textTransform: 'uppercase',
                            }}
                        >
                            {nativeState === 'trying' || nativeState === 'unknown'
                                ? 'Preparing trailer…'
                                : (currentIdx > 0
                                    ? `Trying trailer ${currentIdx + 1} of ${candidates.length}…`
                                    : 'Loading trailer…')}
                        </div>
                        <style>{`@keyframes vesper-trailer-spin{to{transform:rotate(360deg)}}`}</style>
                    </div>
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
