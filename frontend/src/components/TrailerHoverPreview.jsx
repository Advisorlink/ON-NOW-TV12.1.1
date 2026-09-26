/**
 * <TrailerHoverPreview/> — Netflix-style focus/hover preview.
 *
 * Mounted once on the Home page.  When a poster tile (any element
 * with `data-preview="true"`) is FOCUSED (D-pad) or HOVERED (mouse)
 * for a short dwell, the TILE ITSELF widens into a 16:9 card (same
 * row height, siblings shift right) and the title's English trailer
 * auto-plays inside it.
 *
 * Playback path:
 *   • Android TV box → native on-device extractor
 *     (`OnNowTV.previewTrailer`) hands back a muxed googlevideo URL
 *     that a plain <video> plays WITH SOUND.  No YouTube iframe, so
 *     no "Watch on YouTube · Error 153" embed block.
 *   • Browser preview → YouTube iframe (muted; autoplay-with-sound is
 *     blocked by browsers) with automatic candidate cycling when a
 *     video is embed-restricted.
 */
import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { API } from '@/lib/api';
import { getAutoTrailer } from '@/lib/prefs';

const DWELL_MS = 550;
const MAX_NATIVE_TRIES = 3;

const bridge = () => (typeof window !== 'undefined' ? window.OnNowTV : null);
const hasNativePreview = () => typeof bridge()?.previewTrailer === 'function';

/* Chained global callback so we coexist with TrailerModal's hook. */
const nativeCallbacks = new Map();
function hookBridgeCallback() {
    if (window.__vesperPreviewHooked) return;
    window.__vesperPreviewHooked = true;
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

function nativePreview(videoId) {
    return new Promise((resolve) => {
        hookBridgeCallback();
        const id = 'hp-' + Math.random().toString(36).slice(2, 10);
        const t = setTimeout(() => {
            nativeCallbacks.delete(id);
            resolve(null);
        }, 12_000);
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

/* Keep the widened tile fully visible inside its horizontal shelf. */
function revealInShelf(tile) {
    const shelf = tile.closest('.vesper-shelf');
    if (!shelf) return;
    const sr = shelf.getBoundingClientRect();
    const tr = tile.getBoundingClientRect();
    const pad = 48;
    const overflow = tr.right + pad - sr.right;
    if (overflow > 0) shelf.scrollBy({ left: overflow, behavior: 'smooth' });
}

export default function TrailerHoverPreview() {
    const [enabled, setEnabled] = useState(() => getAutoTrailer());
    const [preview, setPreview] = useState(null); // {tile,title,sub,backdrop,media}
    const tokenRef = useRef(0);

    useEffect(() => {
        const sync = () => setEnabled(getAutoTrailer());
        window.addEventListener('vesper:auto-trailer-change', sync);
        window.addEventListener('vesper:profile-change', sync);
        return () => {
            window.removeEventListener('vesper:auto-trailer-change', sync);
            window.removeEventListener('vesper:profile-change', sync);
        };
    }, []);

    useEffect(() => {
        if (!enabled) {
            setPreview(null);
            return undefined;
        }
        let dwell = null;
        let activeEl = null;

        const collapse = () => {
            if (activeEl) activeEl.removeAttribute('data-preview-active');
            activeEl = null;
        };

        const hide = () => {
            if (dwell) clearTimeout(dwell);
            dwell = null;
            collapse();
            tokenRef.current += 1;
            setPreview(null);
        };

        const resolveTrailer = async (tile, token) => {
            const type = tile.getAttribute('data-preview-type') || 'movie';
            let tmdb = tile.getAttribute('data-preview-tmdb') || '';
            let mediaType = type;
            const imdb = tile.getAttribute('data-preview-imdb') || '';
            const title = tile.getAttribute('data-preview-title') || '';
            const sub = tile.getAttribute('data-preview-sub') || '';
            const backdrop = tile.getAttribute('data-preview-backdrop') || '';

            if (!backdrop && !tmdb && !imdb.startsWith('tt')) return;
            if (token !== tokenRef.current) return;

            // Expand the tile now (feels instant) — art first, trailer
            // swaps in once resolved.
            tile.setAttribute('data-preview-active', 'true');
            setPreview({ tile, title, sub, backdrop, media: null });
            setTimeout(() => {
                if (token === tokenRef.current) revealInShelf(tile);
            }, 300);

            try {
                if (!tmdb && imdb.startsWith('tt')) {
                    const fr = await fetch(`${API}/tmdb/find-by-imdb/${imdb}`);
                    if (fr.ok) {
                        const fj = await fr.json();
                        tmdb = fj.tmdb_id ? String(fj.tmdb_id) : '';
                        mediaType = fj.media_type || type;
                    }
                }
                if (!tmdb || token !== tokenRef.current) return;
                const tr = await fetch(`${API}/tmdb/trailer/${mediaType}/${tmdb}`);
                if (!tr.ok) return;
                const tj = await tr.json();
                const data = tj?.data;
                const candidates = (data?.candidates?.length
                    ? data.candidates.map((c) => c.key)
                    : [data?.key]).filter(Boolean);
                if (!candidates.length || token !== tokenRef.current) return;

                if (hasNativePreview()) {
                    for (const key of candidates.slice(0, MAX_NATIVE_TRIES)) {
                        const r = await nativePreview(key);
                        if (token !== tokenRef.current) return;
                        if (r?.videoUrl) {
                            setPreview((p) => (p ? { ...p, media: { kind: 'video', url: r.videoUrl } } : p));
                            return;
                        }
                    }
                    return;
                }
                if (bridge()) return; // box without the new bridge — art only
                setPreview((p) => (p ? { ...p, media: { kind: 'yt', candidates } } : p));
            } catch {
                /* keep the art card; no trailer */
            }
        };

        const onEnter = (target) => {
            const tile = target?.closest?.('[data-preview="true"]');
            if (!tile) {
                hide();
                return;
            }
            if (tile === activeEl) return;
            if (dwell) clearTimeout(dwell);
            collapse();
            activeEl = tile;
            tokenRef.current += 1;
            setPreview(null);
            const token = tokenRef.current;
            dwell = setTimeout(() => resolveTrailer(tile, token), DWELL_MS);
        };

        const onFocusIn = (e) => onEnter(e.target);
        // mousemove (not mouseover) so layout shifting under a
        // stationary cursor never hijacks the D-pad focus preview.
        const onMove = (e) => onEnter(e.target);

        document.addEventListener('focusin', onFocusIn);
        document.addEventListener('mousemove', onMove);
        window.addEventListener('vesper:hide-trailer-preview', hide);

        const ae = document.activeElement;
        if (ae && ae.closest && ae.closest('[data-preview="true"]')) {
            onEnter(ae);
        }

        return () => {
            document.removeEventListener('focusin', onFocusIn);
            document.removeEventListener('mousemove', onMove);
            window.removeEventListener('vesper:hide-trailer-preview', hide);
            if (dwell) clearTimeout(dwell);
            collapse();
        };
    }, [enabled]);

    if (!enabled || !preview) return null;

    const { tile, title, sub, backdrop, media } = preview;

    return createPortal(
        <div
            data-testid="trailer-hover-preview"
            style={{
                position: 'absolute',
                inset: 0,
                zIndex: 3,
                pointerEvents: 'none',
                background: '#05070d',
                animation: 'vesper-hoverprev-in 240ms ease-out both',
            }}
        >
            <style>{`@keyframes vesper-hoverprev-in{from{opacity:0}to{opacity:1}}`}</style>
            {backdrop && (
                <img
                    src={backdrop}
                    alt=""
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                    }}
                />
            )}
            {media?.kind === 'video' && (
                <VideoPreview
                    url={media.url}
                    onFail={() => setPreview((p) => (p ? { ...p, media: null } : p))}
                />
            )}
            {media?.kind === 'yt' && (
                <YtPreview
                    candidates={media.candidates}
                    title={title}
                    onExhausted={() => setPreview((p) => (p ? { ...p, media: null } : p))}
                />
            )}
            <div
                style={{
                    position: 'absolute',
                    inset: 'auto 0 0 0',
                    padding: '20px 14px 12px',
                    background:
                        'linear-gradient(180deg, rgba(5,7,13,0) 0%, rgba(5,7,13,0.85) 70%, rgba(5,7,13,0.96) 100%)',
                }}
            >
                <div
                    style={{
                        fontWeight: 700,
                        fontSize: 'clamp(13px, 1vw, 16px)',
                        color: '#fff',
                        lineHeight: 1.15,
                        letterSpacing: '-0.01em',
                        textShadow: '0 1px 6px rgba(0,0,0,0.6)',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                    }}
                >
                    {title}
                </div>
                {sub && (
                    <div
                        className="vesper-mono"
                        style={{
                            marginTop: 3,
                            fontSize: 'clamp(9px, 0.62vw, 11px)',
                            letterSpacing: '0.16em',
                            textTransform: 'uppercase',
                            color: 'rgba(220,230,255,0.82)',
                        }}
                    >
                        {sub}
                    </div>
                )}
            </div>
        </div>,
        tile
    );
}

/* Native (TV box) path — muxed googlevideo URL in a plain <video>,
 * unmuted.  Fades in over the backdrop once frames are flowing. */
function VideoPreview({ url, onFail }) {
    const [playing, setPlaying] = useState(false);
    return (
        <video
            key={url}
            data-testid="trailer-hover-video"
            src={url}
            autoPlay
            loop
            playsInline
            onPlaying={() => setPlaying(true)}
            onError={onFail}
            style={{
                position: 'absolute',
                inset: 0,
                width: '100%',
                height: '100%',
                objectFit: 'cover',
                opacity: playing ? 1 : 0,
                transition: 'opacity 300ms ease-in',
            }}
        />
    );
}

/* Browser-only YouTube iframe with embed-block detection: YouTube
 * sends no IFrame-API events at all for Error-153 videos, so a
 * ready-timeout (or an explicit onError) advances to the next
 * candidate. */
function YtPreview({ candidates, title, onExhausted }) {
    const [idx, setIdx] = useState(0);
    const [ready, setReady] = useState(false);
    const frameRef = useRef(null);
    const key = candidates[idx];

    useEffect(() => {
        if (!key) {
            onExhausted?.();
            return undefined;
        }
        let gotReady = false;
        let advanced = false;
        const advance = () => {
            if (advanced) return;
            advanced = true;
            setReady(false);
            setIdx((i) => i + 1);
        };
        const onMsg = (ev) => {
            if (!/^https?:\/\/(www\.)?youtube(-nocookie)?\.com$/.test(ev.origin || '')) return;
            let d = ev.data;
            if (typeof d === 'string') {
                try { d = JSON.parse(d); } catch { return; }
            }
            if (!d || typeof d !== 'object') return;
            // Only reveal once playback has actually started —
            // YouTube fires onReady even for embed-blocked videos.
            if (d.event === 'infoDelivery' && d.info?.playerState === 1) {
                gotReady = true;
                setReady(true);
            }
            if (d.event === 'onError') advance();
        };
        window.addEventListener('message', onMsg);
        const hs = setInterval(() => {
            const w = frameRef.current?.contentWindow;
            if (!w) return;
            try {
                w.postMessage(JSON.stringify({ event: 'listening', id: 'vesper-hover', channel: 'widget' }), '*');
            } catch { /* ignore */ }
            if (gotReady) clearInterval(hs);
        }, 250);
        const t = setTimeout(() => {
            if (!gotReady) advance();
        }, 8000);
        return () => {
            window.removeEventListener('message', onMsg);
            clearInterval(hs);
            clearTimeout(t);
        };
    }, [key]);

    if (!key) return null;
    const origin = window.location.origin;
    const params = new URLSearchParams({
        autoplay: '1',
        mute: '1',
        controls: '0',
        rel: '0',
        modestbranding: '1',
        playsinline: '1',
        iv_load_policy: '3',
        fs: '0',
        disablekb: '1',
        loop: '1',
        playlist: key,
        enablejsapi: '1',
        origin,
    });
    return (
        <iframe
            key={key}
            ref={frameRef}
            data-testid="trailer-hover-iframe"
            data-ready={ready ? 'true' : 'false'}
            title={title || 'Trailer'}
            src={`https://www.youtube.com/embed/${encodeURIComponent(key)}?${params}`}
            style={{
                position: 'absolute',
                inset: 0,
                width: '100%',
                height: '100%',
                border: 0,
                opacity: ready ? 1 : 0,
                transition: 'opacity 240ms ease-in',
            }}
            allow="autoplay; encrypted-media"
            referrerPolicy="strict-origin-when-cross-origin"
        />
    );
}
