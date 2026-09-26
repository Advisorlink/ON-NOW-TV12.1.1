/**
 * <TrailerHoverPreview/> — Netflix-style focus/hover preview.
 *
 * Mounted once on the Home page.  When a poster tile (any element
 * with `data-preview="true"`) is FOCUSED (D-pad) or HOVERED (mouse)
 * for a short dwell, it expands into a wide 16:9 card anchored over
 * the tile and auto-plays the title's English YouTube trailer.
 *
 * • Purely visual — `pointer-events:none` so it never steals D-pad
 *   focus; the underlying tile stays focused and OK still navigates.
 * • Sound: unmuted on the TV box (native WebView allows autoplay
 *   with sound), muted in the browser preview (autoplay-with-sound
 *   is blocked) — matches the user's chosen behaviour.
 * • Respects the Settings "Auto-play trailers on Home" toggle.
 */
import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { API } from '@/lib/api';
import { getAutoTrailer } from '@/lib/prefs';

const DWELL_MS = 550;

export default function TrailerHoverPreview() {
    const [enabled, setEnabled] = useState(() => getAutoTrailer());
    const [preview, setPreview] = useState(null); // {rect,title,sub,backdrop,ytKey}
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

        const hide = () => {
            if (dwell) clearTimeout(dwell);
            dwell = null;
            activeEl = null;
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
            const rect = tile.getBoundingClientRect();

            // Nothing to preview (no art, no resolvable id) → skip.
            if (!backdrop && !tmdb && !imdb.startsWith('tt')) return;

            // Show the expanded art card immediately (feels instant),
            // then swap in the trailer once resolved.
            if (token !== tokenRef.current) return;
            setPreview({ rect, title, sub, backdrop, ytKey: null });

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
                const key = tj?.data?.key || '';
                if (!key || token !== tokenRef.current) return;
                setPreview((p) => (p ? { ...p, ytKey: key } : p));
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
            activeEl = tile;
            if (dwell) clearTimeout(dwell);
            tokenRef.current += 1;
            setPreview(null);
            const token = tokenRef.current;
            dwell = setTimeout(() => resolveTrailer(tile, token), DWELL_MS);
        };

        const onFocusIn = (e) => onEnter(e.target);
        const onOver = (e) => onEnter(e.target);

        document.addEventListener('focusin', onFocusIn);
        document.addEventListener('mouseover', onOver);
        window.addEventListener('vesper:hide-trailer-preview', hide);
        return () => {
            document.removeEventListener('focusin', onFocusIn);
            document.removeEventListener('mouseover', onOver);
            window.removeEventListener('vesper:hide-trailer-preview', hide);
            if (dwell) clearTimeout(dwell);
        };
    }, [enabled]);

    if (!enabled || !preview) return null;

    const { rect, title, sub, backdrop, ytKey } = preview;
    const vw = window.innerWidth;
    const vh = window.innerHeight;
    const w = Math.min(Math.max(rect.width * 1.95, 320), Math.min(520, vw - 32));
    const h = w * (9 / 16);
    const cx = rect.left + rect.width / 2;
    let left = cx - w / 2;
    let top = rect.top - (h - rect.height) / 2 - 6;
    left = Math.max(16, Math.min(left, vw - w - 16));
    top = Math.max(16, Math.min(top, vh - h - 16));

    const muted = !(typeof window !== 'undefined' && window.OnNowTV);
    const src = ytKey
        ? `https://www.youtube.com/embed/${encodeURIComponent(ytKey)}?autoplay=1&mute=${muted ? 1 : 0}&controls=0&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3&fs=0&disablekb=1`
        : null;

    return createPortal(
        <div
            data-testid="trailer-hover-preview"
            style={{
                position: 'fixed',
                left,
                top,
                width: w,
                height: h,
                zIndex: 90,
                pointerEvents: 'none',
                borderRadius: 'clamp(10px, 0.9vw, 16px)',
                overflow: 'hidden',
                background: '#05070d',
                boxShadow:
                    '0 24px 60px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.06), 0 0 40px 6px rgba(var(--vesper-blue-rgb),0.28)',
                animation: 'vesper-hoverprev-in 200ms cubic-bezier(.2,.7,.2,1) both',
            }}
        >
            <style>{`@keyframes vesper-hoverprev-in{from{opacity:0;transform:scale(0.94)}to{opacity:1;transform:scale(1)}}`}</style>
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
                        opacity: ytKey ? 0 : 1,
                        transition: 'opacity 400ms ease',
                    }}
                />
            )}
            {src && (
                <iframe
                    key={ytKey}
                    data-testid="trailer-hover-iframe"
                    title={title || 'Trailer'}
                    src={src}
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        border: 0,
                    }}
                    allow="autoplay; encrypted-media"
                    sandbox="allow-scripts allow-same-origin allow-presentation"
                />
            )}
            {/* Bottom title strip */}
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
        document.body
    );
}
