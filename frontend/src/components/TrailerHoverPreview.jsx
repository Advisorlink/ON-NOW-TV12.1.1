/**
 * <TrailerHoverPreview/> — Netflix-style focus/hover preview.
 *
 * Mounted once on the Home page.  Focusing (D-pad) or hovering a
 * poster tile (`data-preview="true"`) starts resolving its trailer
 * IMMEDIATELY; after a short dwell the TILE ITSELF widens into a
 * 16:9 card (same row height, siblings shift right) and the trailer
 * plays inside it — once, then the cover art stays.
 *
 * Playback goes through `lib/trailerEngine` (same engine as the
 * Detail-page TrailerModal): native muxed <video> on the box, YouTube
 * iframe in the browser.  Pressing OK on a playing trailer shows two
 * actions inside the card: "Play Full Screen" and "Open to Play".
 */
import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Maximize2, Play } from 'lucide-react';
import { getAutoTrailer } from '@/lib/prefs';
import {
    fetchTrailerCandidates,
    isBox,
    prefetchTileTrailer,
    resolveMuxedTrailer,
    tileTrailerRequest,
} from '@/lib/trailerEngine';
import TrailerModal from '@/components/TrailerModal';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';
import { peekHd, prefetchHd } from '@/lib/trailerEngine';

const EXPAND_MS = 0; // instant — no slide-out, the card is simply there

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

function nextPreviewTile(tile) {
    let el = tile.nextElementSibling;
    while (el && el.getAttribute('data-preview') !== 'true') el = el.nextElementSibling;
    return el;
}

export default function TrailerHoverPreview() {
    const [enabled, setEnabled] = useState(() => getAutoTrailer());
    // {tile,title,sub,backdrop,media,playing,ended}
    const [preview, setPreview] = useState(null);
    const [actions, setActions] = useState(false);
    const [fullscreen, setFullscreen] = useState(null); // {candidates, source, title, backdrop}
    const tokenRef = useRef(0);
    const previewRef = useRef(null);
    const actionsRef = useRef(false);
    previewRef.current = preview;
    actionsRef.current = actions;

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
        let expandTimer = null;
        let activeEl = null;

        const collapse = () => {
            if (activeEl) activeEl.removeAttribute('data-preview-active');
            activeEl = null;
        };

        const hide = () => {
            if (expandTimer) clearTimeout(expandTimer);
            expandTimer = null;
            collapse();
            tokenRef.current += 1;
            setPreview(null);
            setActions(false);
        };

        const startTile = (tile, token) => {
            const title = tile.getAttribute('data-preview-title') || '';
            const sub = tile.getAttribute('data-preview-sub') || '';
            const backdrop = tile.getAttribute('data-preview-backdrop') || '';
            const req = tileTrailerRequest(tile);
            const hasId = !!req.tmdbId || req.imdbId.startsWith('tt');
            if (!backdrop && !hasId) return;

            const stale = () => token !== tokenRef.current;

            // 1. Expand after a short dwell (art first).
            expandTimer = setTimeout(() => {
                if (stale()) return;
                tile.setAttribute('data-preview-active', 'true');
                setPreview({ tile, title, sub, backdrop, media: null, playing: false, ended: false });
                setTimeout(() => {
                    if (!stale()) revealInShelf(tile);
                }, 300);
            }, EXPAND_MS);

            // 2. Resolve the trailer right away — no waiting on the dwell.
            if (!hasId) return;
            (async () => {
                try {
                    const candidates = await fetchTrailerCandidates(req);
                    if (stale() || !candidates.length) return;
                    let media = null;
                    if (isBox()) {
                        const r = await resolveMuxedTrailer(candidates, stale);
                        if (stale()) return;
                        if (r?.url) media = { kind: 'video', url: r.url, candidates };
                        // Warm the HD pair now so "Play Full Screen" is instant.
                        if (media) prefetchHd(candidates);
                    } else if (!isBox()) {
                        media = { kind: 'yt', candidates };
                    }
                    if (!media) return;
                    const apply = () => setPreview((p) => (p && p.tile === tile ? { ...p, media } : p));
                    // The card may not be expanded yet (fast resolve) —
                    // wait for the expand tick, then attach the media.
                    if (tile.getAttribute('data-preview-active') === 'true') apply();
                    else setTimeout(() => { if (!stale()) apply(); }, EXPAND_MS + 20);
                    // Warm the next tile so scrolling right feels instant.
                    prefetchTileTrailer(nextPreviewTile(tile));
                } catch {
                    /* keep the art card */
                }
            })();
        };

        const onEnter = (target) => {
            const tile = target?.closest?.('[data-preview="true"]');
            if (!tile) {
                // Focus moved into our own action overlay / modal — keep the card.
                if (target?.closest?.('[data-testid="trailer-hover-actions"], [data-testid="trailer-modal"]')) return;
                hide();
                return;
            }
            if (tile === activeEl) return;
            if (expandTimer) clearTimeout(expandTimer);
            collapse();
            activeEl = tile;
            tokenRef.current += 1;
            setPreview(null);
            setActions(false);
            startTile(tile, tokenRef.current);
        };

        const onFocusIn = (e) => onEnter(e.target);
        // mousemove (not mouseover) so layout shifting under a
        // stationary cursor never hijacks the D-pad focus preview.
        const onMove = (e) => onEnter(e.target);

        // OK on a PLAYING trailer → show the in-card actions instead
        // of navigating.  PosterTile uses useLongPress (navigates on
        // Enter keyup / mouseup, no click event), so swallow the
        // whole press in the capture phase.
        const shouldIntercept = (e) => {
            const p = previewRef.current;
            if (!p || !p.playing || actionsRef.current) return false;
            const tile = e.target?.closest?.('[data-preview="true"]');
            return !!tile && tile === p.tile;
        };
        const onPressStart = (e) => {
            if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
            if (!shouldIntercept(e)) return;
            e.preventDefault();
            e.stopPropagation();
        };
        const onPressEnd = (e) => {
            if (e.type === 'keyup' && e.key !== 'Enter' && e.key !== ' ') return;
            if (!shouldIntercept(e)) return;
            e.preventDefault();
            e.stopPropagation();
            setActions(true);
        };
        const onClick = (e) => {
            if (!shouldIntercept(e)) return;
            e.preventDefault();
            e.stopPropagation();
        };

        document.addEventListener('focusin', onFocusIn);
        document.addEventListener('mousemove', onMove);
        document.addEventListener('keydown', onPressStart, true);
        document.addEventListener('mousedown', onPressStart, true);
        document.addEventListener('keyup', onPressEnd, true);
        document.addEventListener('mouseup', onPressEnd, true);
        document.addEventListener('click', onClick, true);
        window.addEventListener('vesper:hide-trailer-preview', hide);

        const ae = document.activeElement;
        if (ae && ae.closest && ae.closest('[data-preview="true"]')) onEnter(ae);

        return () => {
            document.removeEventListener('focusin', onFocusIn);
            document.removeEventListener('mousemove', onMove);
            document.removeEventListener('keydown', onPressStart, true);
            document.removeEventListener('mousedown', onPressStart, true);
            document.removeEventListener('keyup', onPressEnd, true);
            document.removeEventListener('mouseup', onPressEnd, true);
            document.removeEventListener('click', onClick, true);
            window.removeEventListener('vesper:hide-trailer-preview', hide);
            if (expandTimer) clearTimeout(expandTimer);
            collapse();
        };
    }, [enabled]);

    if (!enabled || !preview) return null;

    const { tile, title, sub, backdrop, media } = preview;
    const patch = (fn) => setPreview((p) => (p ? fn(p) : p));
    const onEnded = () => patch((p) => ({ ...p, media: null, playing: false, ended: true }));

    const closeActions = () => {
        setActions(false);
        try { tile.focus({ preventScroll: true }); } catch { /* ignore */ }
    };
    const openFullscreen = () => {
        setActions(false);
        const resume = media;
        // Carry the exact second the little card is up to, so the
        // fullscreen version continues instead of restarting.
        const vid = tile.querySelector('video[data-testid="trailer-hover-video"]');
        const startAt = Number.isFinite(vid?.currentTime) ? vid.currentTime : 0;
        patch((p) => ({ ...p, media: null, playing: false }));
        // Expands IN the app (same WebView, no player hand-off).  On the
        // box the modal plays the HD DASH pair (1080p/720p) — prefetched
        // while the card was playing — and falls back to the muxed file.
        setFullscreen({
            candidates: media?.candidates || [],
            source: media?.kind === 'video' ? { url: media.url } : null,
            hdSource: isBox() ? peekHd(media?.candidates) : null,
            startAt,
            title,
            backdrop,
            resume,
        });
    };
    const openTitle = () => {
        setActions(false);
        tile.dispatchEvent(new CustomEvent('vesper:preview-open'));
    };

    return (
        <>
            {createPortal(
                <div
                    data-testid="trailer-hover-preview"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        zIndex: 3,
                        pointerEvents: 'none',
                        background: '#05070d',
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
                            startAt={media.startAt || 0}
                            onPlaying={() => patch((p) => ({ ...p, playing: true }))}
                            onEnded={onEnded}
                            onFail={() => patch((p) => ({ ...p, media: null, playing: false }))}
                        />
                    )}
                    {media?.kind === 'yt' && (
                        <YtPreview
                            candidates={media.candidates}
                            title={title}
                            onPlaying={() => patch((p) => ({ ...p, playing: true }))}
                            onEnded={onEnded}
                            onExhausted={() => patch((p) => ({ ...p, media: null, playing: false }))}
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
            )}
            {actions && (
                <PreviewActions
                    tile={tile}
                    onFullscreen={openFullscreen}
                    onOpen={openTitle}
                    onClose={closeActions}
                />
            )}
            {fullscreen && (
                <TrailerModal
                    youtubeKey={fullscreen.candidates}
                    nativeSource={fullscreen.source}
                    hdSource={fullscreen.hdSource}
                    startAt={fullscreen.startAt}
                    initialFullscreen
                    title={fullscreen.title}
                    backdrop={fullscreen.backdrop}
                    onClose={(atSec) => {
                        const r = fullscreen.resume;
                        setFullscreen(null);
                        if (r && r.kind === 'video') {
                            patch((p) => ({ ...p, media: { ...r, startAt: atSec || 0 }, ended: false }));
                        }
                        try { tile.focus({ preventScroll: true }); } catch { /* ignore */ }
                    }}
                />
            )}
        </>
    );
}

/* Two pill actions floated over the playing card (portaled to body so
 * we never nest <button>s).  Own key handling + focus trap. */
function PreviewActions({ tile, onFullscreen, onOpen, onClose }) {
    const [rect, setRect] = useState(() => tile.getBoundingClientRect());
    const firstRef = useRef(null);
    // Android BACK closes the actions instead of leaving Home.
    useNativeBackTrap(true, () => { onClose(); return false; });

    useEffect(() => {
        const t = setTimeout(() => firstRef.current?.focus({ preventScroll: true }), 30);
        const onScroll = () => setRect(tile.getBoundingClientRect());
        window.addEventListener('scroll', onScroll, true);
        window.addEventListener('resize', onScroll);
        return () => {
            clearTimeout(t);
            window.removeEventListener('scroll', onScroll, true);
            window.removeEventListener('resize', onScroll);
        };
    }, [tile]);

    const onKeyDown = (e) => {
        const k = e.key;
        if (k === 'ArrowLeft' || k === 'ArrowRight') {
            e.preventDefault();
            e.stopPropagation();
            const btns = Array.from(e.currentTarget.querySelectorAll('button'));
            const i = btns.indexOf(document.activeElement);
            const n = k === 'ArrowRight' ? Math.min(btns.length - 1, i + 1) : Math.max(0, i - 1);
            btns[n]?.focus({ preventScroll: true });
            return;
        }
        if (k === 'ArrowUp' || k === 'ArrowDown') {
            e.preventDefault();
            e.stopPropagation();
            onClose();
            return;
        }
        if (k === 'Escape' || k === 'Backspace' || k === 'GoBack' || e.keyCode === 27 || e.keyCode === 8) {
            e.preventDefault();
            e.stopPropagation();
            triggerTrapBack();
        }
    };

    return createPortal(
        <div
            data-testid="trailer-hover-actions"
            data-focus-trap="true"
            onKeyDown={onKeyDown}
            style={{
                position: 'fixed',
                left: rect.left,
                top: rect.top,
                width: rect.width,
                height: rect.height,
                zIndex: 60,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 12,
                borderRadius: 12,
                background: 'rgba(5,7,13,0.45)',
                animation: 'vesper-hoverprev-in 160ms ease-out both',
            }}
        >
            <button
                ref={firstRef}
                type="button"
                data-testid="trailer-hover-fullscreen"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onFullscreen}
                style={actionBtnStyle}
            >
                <Maximize2 size={15} /> Play Full Screen
            </button>
            <button
                type="button"
                data-testid="trailer-hover-open"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onOpen}
                style={actionBtnStyle}
            >
                <Play size={15} /> Open to Play
            </button>
        </div>,
        document.body
    );
}

const actionBtnStyle = {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 8,
    padding: '10px 18px',
    borderRadius: 999,
    background: 'rgba(0,0,0,0.62)',
    border: '1px solid rgba(255,255,255,0.28)',
    color: '#fff',
    fontSize: 'clamp(12px, 0.85vw, 14px)',
    fontWeight: 700,
    letterSpacing: '0.01em',
    whiteSpace: 'nowrap',
    cursor: 'pointer',
    backdropFilter: 'blur(6px)',
    WebkitBackdropFilter: 'blur(6px)',
};

/* Native (TV box) path — muxed googlevideo URL in a plain <video>,
 * unmuted, plays ONCE.  Fades in over the backdrop once frames flow. */
function VideoPreview({ url, startAt = 0, onPlaying, onEnded, onFail }) {
    const [playing, setPlaying] = useState(false);
    return (
        <video
            key={url}
            data-testid="trailer-hover-video"
            src={url}
            autoPlay
            playsInline
            onLoadedMetadata={(e) => {
                if (startAt > 0) {
                    try { e.currentTarget.currentTime = startAt; } catch { /* ignore */ }
                }
            }}
            onPlaying={() => {
                setPlaying(true);
                onPlaying?.();
            }}
            onEnded={onEnded}
            onError={onFail}
            style={{
                position: 'absolute',
                inset: 0,
                width: '100%',
                height: '100%',
                objectFit: 'cover',
                opacity: playing ? 1 : 0,
                transition: 'opacity 150ms ease-in',
            }}
        />
    );
}

/* Browser-only YouTube iframe with embed-block detection: YouTube
 * sends no IFrame-API events at all for Error-153 videos, so a
 * ready-timeout (or an explicit onError) advances to the next
 * candidate. */
function YtPreview({ candidates, title, onPlaying, onEnded, onExhausted }) {
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
                if (!gotReady) onPlaying?.();
                gotReady = true;
                setReady(true);
            }
            if (d.event === 'infoDelivery' && d.info?.playerState === 0 && gotReady) onEnded?.();
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
