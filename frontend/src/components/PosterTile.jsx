import React from 'react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';
import useLongPress from '@/hooks/useLongPress';
import { API } from '@/lib/api';

/**
 * Module-level memo of TMDB→IMDB resolutions.  The backend caches
 * forever (24h) but this keeps the second long-press on the same
 * tile completely network-free.  Keyed by `${type}:${tmdb_id}`.
 *
 * Surviving HMR reloads isn't critical — the worst case is one extra
 * fetch per tile per cold mount.
 */
const TMDB_TO_IMDB_MEMO = new Map();

/**
 * Poster tile.  Image renders immediately on mount — we don't try
 * to be clever about deferring decode because the user reported
 * seeing placeholder text flash during fast D-pad scrolls.  Browser
 * native `loading="lazy"` already handles off-screen rate-limiting,
 * and the parent `<Lazy>` shelf wrapper still skips work for shelves
 * far below the viewport.
 *
 * Press-and-hold OK (or mouse) to fire the global "Add to My List"
 * modal — short-tap still navigates to the detail page.
 */
export default function PosterTile({ item, onSelect, initialFocus = false }) {
    const navigate = useNavigate();

    const onTap = () => {
        if (onSelect) {
            onSelect(item);
        } else if (item.routePath) {
            navigate(item.routePath);
        } else if (item.imdbId) {
            // v2.10.46-c — Movies tile-click now jumps STRAIGHT to
            // autoplay (loading screen → player, no stream picker
            // visible).  Series omit the flag because the user
            // expects to land on the episode picker, not autoplay.
            const t = item.type || 'movie';
            const qs = t === 'movie' ? '?autoplay=1' : '';
            navigate(`/title/${t}/${item.imdbId}${qs}`);
        } else {
            navigate(`/title/${item.id}`);
        }
    };

    const onLongPress = async () => {
        let id = item.imdbId || item.id;
        // v2.10.53 — TMDB-sourced tiles (For You "Similar to what
        // you love" / genre rails) carry `tmdbId` + `tmdbType` but
        // no IMDB id.  Resolve to IMDB on demand so the long-press
        // "Add to My List" / "Watch Later" flow works on those
        // tiles too.  Backend response is in-memory cached for 24 h
        // so the round-trip is typically 50-200 ms; we also memoise
        // module-side so a second long-press is instant.
        if (
            (!id || !id.toString().startsWith('tt')) &&
            item.tmdbId &&
            item.tmdbType
        ) {
            const memoKey = `${item.tmdbType}:${item.tmdbId}`;
            if (TMDB_TO_IMDB_MEMO.has(memoKey)) {
                id = TMDB_TO_IMDB_MEMO.get(memoKey);
            } else {
                try {
                    const r = await fetch(
                        `${API}/tmdb/imdb/${item.tmdbType}/${item.tmdbId}`
                    );
                    if (r.ok) {
                        const json = await r.json();
                        if (json && json.imdb_id) {
                            id = json.imdb_id;
                            TMDB_TO_IMDB_MEMO.set(memoKey, id);
                        }
                    }
                } catch {
                    /* ignore — fall through to the guard below */
                }
            }
        }
        if (!id || !id.toString().startsWith('tt')) return;
        window.dispatchEvent(
            new CustomEvent('vesper:request-add-to-list', {
                detail: {
                    id,
                    type: item.type || 'movie',
                    title: item.title,
                    poster: item.poster ? img.poster(item.poster) : null,
                    background: item.background
                        ? img.backdrop(item.background)
                        : null,
                    year: item.year || item.sub,
                    genres: item.genres,
                    synopsis: item.description,
                },
            })
        );
    };

    const press = useLongPress(onLongPress, onTap);

    return (
        <button
            data-testid={`poster-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            {...(initialFocus ? { 'data-initial-focus': 'true' } : {})}
            tabIndex={0}
            {...press}
            className="group relative shrink-0 overflow-hidden rounded-xl text-left"
            style={{
                width: 'clamp(132px, 11.5vw, 198px)',
                aspectRatio: '2 / 3',
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
                /* v2.7.88 — INLINE touch-action so a finger drag
                   across the poster doesn't capture the gesture.
                   We've tried gating this via CSS at the body
                   (`data-platform="mobile"`) and via media-query
                   (`pointer: coarse`) — both got overridden by
                   downstream component CSS on the user's Samsung
                   WebView.  Inline style on the element itself
                   has the highest specificity short of a
                   stylesheet `!important` and CANNOT be
                   overridden by any CSS rule, so this is the
                   final say on touch behaviour.  TVs (D-pad
                   only) never fire touch events so this is a
                   no-op there. */
                touchAction: 'pan-x pan-y',
                scrollMarginTop: 24,
                scrollMarginBottom: 24,
                // GPU compositing only.  IMPORTANT: do NOT add
                // `content-visibility: auto` or `contain: size /
                // paint / strict` here — those create a
                // size-contained box that clips the focused tile's
                // `scale(1.08)` animation at the tile's bottom
                // edge.  Pure compositor promotion is enough to
                // get smooth scrolling on the HK1 without breaking
                // the scale animation.
                transform: 'translateZ(0)',
                backfaceVisibility: 'hidden',
                willChange: 'transform',
            }}
        >
            {item.poster ? (
                <img
                    src={img.poster(item.poster)}
                    alt={item.title}
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                />
            ) : (
                <div
                    className="absolute inset-0 flex items-center justify-center"
                    style={{
                        background:
                            'linear-gradient(180deg, var(--vesper-bg-2) 0%, var(--vesper-bg-1) 100%)',
                    }}
                >
                    <span
                        className="vesper-display"
                        style={{
                            fontSize: 64,
                            color: 'rgba(var(--vesper-blue-rgb),0.18)',
                        }}
                    >
                        {(item.title || '?')[0]}
                    </span>
                </div>
            )}

            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.93) 78%, var(--vesper-bg-0) 100%)',
                }}
            />

            <div className="absolute inset-x-0 bottom-0 p-4">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 'clamp(13px, 1vw, 17px)',
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.15,
                        color: 'var(--vesper-text)',
                    }}
                >
                    {item.title}
                </div>
                {item.sub && (
                    <div
                        className="vesper-mono mt-1.5"
                        style={{
                            fontSize: 'clamp(9px, 0.62vw, 11px)',
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        {item.sub}
                    </div>
                )}
            </div>
        </button>
    );
}
