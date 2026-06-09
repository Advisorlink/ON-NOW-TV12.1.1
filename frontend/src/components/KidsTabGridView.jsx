import React, { useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import * as img from '@/lib/img';
import { showNavLoader } from '@/lib/navLoader';

/**
 * Kids-only newest-first grid for the Movies / TV filter views
 * inside Kids mode.
 *
 * v2.8.9 — Replaces the regular `TabGridView` inside Kids.  The
 * regular one calls `useTabCatalog(addons, type)` which streams the
 * ENTIRE Vesper adult addon catalogue — including R / NC-17 titles
 * — and was the source of the user's "Movies section shows all the
 * movies from Vesper" complaint.
 *
 * This component:
 *   • Takes the already rating-filtered kids shelves as input
 *     (driven by Settings → maxRatingMovie / maxRatingSeries).
 *   • Flattens every item whose `type` matches the requested filter
 *     (`movie` or `series`) into a single deduped list.
 *   • Renders the same poster grid styling as TabGridView so the
 *     UX matches the rest of the app — only the data source
 *     changes.
 *
 * Because we never touch addons here, the only content that can
 * ever reach the screen has already been certified kid-safe by
 * the `/tmdb/kids/shelves` backend (which uses TMDB's
 * `certification` filter for movies and `with_release_type +
 * include_adult=false` plus `certification.lte` for TV).
 */
export default function KidsTabGridView({ shelves, loading, type }) {
    const navigate = useNavigate();

    const items = useMemo(() => {
        if (!Array.isArray(shelves)) return [];
        const seen = new Set();
        const out = [];
        // The kids shelves remap (useKidsShelves) stores `type` on
        // every individual item rather than the shelf wrapper, so
        // filter at the item level — works regardless of how the
        // shelves are grouped on the backend.
        for (const s of shelves) {
            for (const it of s?.items || []) {
                if (it.type !== type) continue;
                const key = it.id;
                if (seen.has(key)) continue;
                seen.add(key);
                out.push(it);
            }
        }
        return out;
    }, [shelves, type]);

    const heading = type === 'series' ? 'Cartoons' : 'Movies';
    const eyebrow =
        type === 'series'
            ? 'KID-SAFE TV · BROWSE EVERY SHOW'
            : 'KID-SAFE MOVIES · BROWSE EVERY FILM';

    return (
        <section
            data-testid={`kids-tab-grid-${type}`}
            style={{
                paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                paddingRight: 'clamp(40px, 4.2vw, 80px)',
                paddingTop: 'clamp(60px, 6vw, 96px)',
                paddingBottom: 'clamp(40px, 5vw, 80px)',
            }}
        >
            <header style={{ marginBottom: 32 }}>
                <div
                    className="vesper-eyebrow"
                    style={{ marginBottom: 10, fontSize: 12, color: '#FFD43B' }}
                >
                    {eyebrow}
                </div>
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(40px, 4.4vw, 72px)',
                        letterSpacing: '-0.035em',
                        lineHeight: 1,
                        color: '#fff',
                    }}
                >
                    {heading}
                </h1>
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 14,
                        fontSize: 12,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {loading
                        ? `Finding kid-safe ${type === 'series' ? 'TV shows' : 'movies'}…`
                        : `${items.length} kid-safe ${
                              type === 'series' ? 'shows' : 'movies'
                          } to pick from`}
                </div>
            </header>

            {items.length === 0 && !loading ? (
                <div
                    className="vesper-glass rounded-2xl"
                    style={{
                        padding: '28px 32px',
                        color: 'var(--vesper-text-2)',
                        fontSize: 16,
                    }}
                >
                    No kid-safe {type === 'series' ? 'shows' : 'movies'} for
                    the current rating. Ask a grown-up to widen the rating
                    in Settings.
                </div>
            ) : (
                <div style={{ position: 'relative' }}>
                    <div
                        data-testid={`kids-tab-grid-list-${type}`}
                        className="grid"
                        style={{
                            gridTemplateColumns:
                                'repeat(auto-fill, minmax(clamp(150px, 11vw, 200px), 1fr))',
                            gap: 'clamp(18px, 1.6vw, 28px)',
                        }}
                    >
                        {items.map((item, i) => (
                            <KidsTile
                                key={item.id}
                                item={item}
                                navigate={navigate}
                                initialFocus={i === 0}
                            />
                        ))}
                    </div>

                    {loading && items.length === 0 && (
                        <div
                            className="absolute inset-0 flex flex-col items-center justify-center"
                            style={{
                                gap: 18,
                                color: 'var(--vesper-blue-bright)',
                                pointerEvents: 'none',
                                minHeight: '50vh',
                            }}
                        >
                            <Loader2 size={64} strokeWidth={2} className="vesper-spin" />
                        </div>
                    )}
                </div>
            )}
        </section>
    );
}

function KidsTile({ item, navigate, initialFocus }) {
    const onTap = () => {
        showNavLoader();
        if (item.routePath) navigate(item.routePath);
        else if (item.imdbId)
            navigate(`/title/${item.type || 'movie'}/${item.imdbId}`);
        else navigate(`/title/${item.id}`);
    };
    return (
        <button
            data-testid={`kids-grid-${item.id}`}
            data-focusable="true"
            data-focus-style="tile"
            {...(initialFocus ? { 'data-initial-focus': 'true' } : {})}
            tabIndex={0}
            onClick={onTap}
            aria-label={item.title}
            className="group relative overflow-hidden rounded-xl text-left"
            style={{
                width: '100%',
                aspectRatio: '2 / 3',
                padding: 0,
                background: 'var(--vesper-bg-2)',
                border: '1px solid rgba(255,255,255,0.05)',
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
                        style={{ fontSize: 64, color: 'rgba(255,212,59,0.20)' }}
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
            <div className="absolute inset-x-0 bottom-0 p-3">
                <div
                    className="font-sans"
                    style={{
                        fontSize: 'clamp(13px, 1vw, 17px)',
                        fontWeight: 600,
                        letterSpacing: '-0.015em',
                        lineHeight: 1.15,
                        color: 'var(--vesper-text)',
                        display: '-webkit-box',
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                    }}
                >
                    {item.title}
                </div>
                {item.sub && (
                    <div
                        className="vesper-mono mt-1"
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
