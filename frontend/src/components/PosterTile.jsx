import React from 'react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';
import { API } from '@/lib/api';
import useLongPress from '@/hooks/useLongPress';
import { getReleaseTag } from '@/lib/releaseTags';

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

    // v2.19.4 — CINEMA / CAM COPY tag on recent movie covers.  Only
    // recent movies can be in a release window, so old titles skip
    // the lookup entirely (keeps the batch tiny).
    const [releaseTag, setReleaseTag] = React.useState(null);
    React.useEffect(() => {
        const id = item.imdbId;
        if (!id || !String(id).startsWith('tt')) return undefined;
        if ((item.type || 'movie') !== 'movie') return undefined;
        const y = item.year || 0;
        if (y && y < new Date().getFullYear() - 1) return undefined;
        let on = true;
        getReleaseTag(id, (t) => { if (on) setReleaseTag(t); });
        return () => { on = false; };
    }, [item.imdbId, item.type, item.year]);

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

    const fireAddToList = (id) => {
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

    const onLongPress = () => {
        const id = item.imdbId || item.id;
        if (id && id.toString().startsWith('tt')) {
            fireAddToList(id);
            return;
        }
        // USER SPEC — "Similar to what you love" (For You) tiles come
        // from TMDB and carry no imdb id, so hold-OK used to silently
        // do nothing.  Resolve tmdb→imdb on the fly (same endpoint
        // the /resolve route uses) so these covers add to Library
        // exactly like every other tile.
        const m = /^\/resolve\/(tv|movie)\/(\d+)/.exec(item.routePath || '');
        if (!m) return;
        fetch(`${API}/tmdb/imdb/${m[1]}/${m[2]}`, { cache: 'force-cache' })
            .then((r) => r.json())
            .then((data) => {
                const tt = data?.imdb_id;
                if (tt && tt.toString().startsWith('tt')) fireAddToList(tt);
            })
            .catch(() => { /* offline — hold just does nothing */ });
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

            {releaseTag && (
                <span
                    className="vesper-mono absolute z-10 pointer-events-none"
                    data-testid={`poster-tag-${releaseTag}`}
                    style={{
                        top: 8,
                        left: 8,
                        fontSize: 9,
                        fontWeight: 700,
                        letterSpacing: '0.16em',
                        textTransform: 'uppercase',
                        padding: '3px 8px',
                        borderRadius: 6,
                        color: '#fff',
                        background: releaseTag === 'cinema'
                            ? 'rgba(79, 70, 229, 0.95)'
                            : 'rgba(202, 96, 8, 0.95)',
                        boxShadow: '0 2px 12px rgba(0,0,0,0.55)',
                    }}
                >
                    {releaseTag === 'cinema' ? 'Cinema' : 'Cam Copy'}
                </span>
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
