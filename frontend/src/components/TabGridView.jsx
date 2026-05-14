import React from 'react';
import { useNavigate } from 'react-router-dom';
import * as img from '@/lib/img';
import useLongPress from '@/hooks/useLongPress';
import Lazy from '@/components/Lazy';

// Render the grid in chunks so a 340-item catalogue doesn't paint
// 340 <img> tags on the very first frame.  At 1080p the layout
// shakes out to ~7 tiles per row — 14 per chunk = 2 rows.  First
// 2 chunks (~4 rows = 28 tiles) mount eagerly per user request;
// later chunks lazy-mount when they enter the viewport.  This is
// the change that makes the TV-box version feel snappy: the user
// sees the heading + the first 3–4 rows of real posters right
// away, with the remaining 300+ tiles streaming in as they scroll.
const TILES_PER_CHUNK = 14;
const EAGER_CHUNKS = 2;

/**
 * Newest-first grid for the TV Shows / Movies tab views.
 *
 * Takes the live shelves payload (already type-filtered), flattens
 * every catalogue into one list, dedupes by IMDb id (the same movie
 * can appear under "Top Rated" and "Trending" simultaneously), then
 * sorts by `releaseInfo` / `year` descending so the freshest titles
 * sit at the top — exactly what the user asked for.
 */
export default function TabGridView({ shelves, loading, type }) {
    const navigate = useNavigate();

    const items = React.useMemo(() => {
        const seen = new Map();
        for (const shelf of Array.isArray(shelves) ? shelves : []) {
            for (const it of shelf.items || []) {
                // Kid-safe catalog tiles share a TMDB-based `routePath`
                // across shelves (e.g. /resolve/movie/12345), but each
                // shelf gives them a different `id`.  Prefer routePath
                // for dedupe so the same movie isn't repeated 3 times
                // when it lives in multiple shelves.
                const key = it.routePath || it.imdbId || it.id;
                if (!key) continue;
                if (!seen.has(key)) seen.set(key, it);
            }
        }
        const all = Array.from(seen.values());
        // Parse `releaseInfo` ("2023" / "2020-" / "2018–2022") into
        // an integer for sorting.  Items without a year sink.
        const yearOf = (it) => {
            const raw = (it.sub || '') + ' ' + (it.releaseInfo || '');
            const m = raw.match(/(19|20)\d{2}/);
            return m ? parseInt(m[0], 10) : 0;
        };
        all.sort((a, b) => yearOf(b) - yearOf(a));
        return all;
    }, [shelves]);

    const heading = type === 'series' ? 'TV Shows' : 'Movies';
    const eyebrow =
        type === 'series'
            ? 'EVERY SERIES · NEWEST FIRST'
            : 'EVERY MOVIE · NEWEST FIRST';

    return (
        <section
            data-testid={`tab-grid-${type}`}
            style={{
                paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                paddingRight: 'clamp(40px, 4.2vw, 80px)',
                paddingTop: 'clamp(60px, 6vw, 96px)',
                paddingBottom: 'clamp(40px, 5vw, 80px)',
            }}
        >
            <header className="mb-8">
                <div
                    className="vesper-eyebrow"
                    style={{ marginBottom: 10, fontSize: 12 }}
                >
                    {eyebrow}
                </div>
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(40px, 4.4vw, 72px)',
                        letterSpacing: '-0.035em',
                        lineHeight: 1,
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
                        ? 'Pulling catalogues from your installed sources…'
                        : `${items.length} title${
                              items.length === 1 ? '' : 's'
                          } from every catalogue you have installed`}
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
                    No catalogues available from your installed addons.
                    Open Sources to add one.
                </div>
            ) : (
                // Single grid that morphs from skeleton → real tiles
                // as catalogue results stream in.  We render at
                // least 12 placeholder slots so the page is fully
                // navigable the instant the user clicks the tab,
                // and we key by INDEX so the focused DOM node
                // stays mounted (and stays focused) when its
                // content swaps from skeleton to a real movie.
                // The total list is split into CHUNK-sized
                // sub-grids and wrapped in <Lazy> so the box only
                // paints 4 rows up front — the rest mount as they
                // approach the viewport.
                <ChunkedGrid
                    items={items}
                    type={type}
                    navigate={navigate}
                />
            )}
        </section>
    );
}

function ChunkedGrid({ items, type, navigate }) {
    // Reserve at least 1 chunk of placeholder slots so the grid
    // is navigable immediately on cold load.
    const total = Math.max(items.length, TILES_PER_CHUNK);
    const chunkCount = Math.ceil(total / TILES_PER_CHUNK);
    const chunks = [];
    for (let c = 0; c < chunkCount; c++) {
        const start = c * TILES_PER_CHUNK;
        const end = Math.min(start + TILES_PER_CHUNK, total);
        chunks.push({ start, end });
    }
    return (
        <div data-testid={`tab-grid-list-${type}`}>
            {chunks.map((ch, idx) => (
                <Lazy
                    key={idx}
                    // Reserve approximate row height (2 rows × ~280
                    // px tile + 24 px gap × 1.5 poster aspect ≈ 600)
                    // so the un-mounted chunks don't shrink layout.
                    minHeight={620}
                    // Tighter than the shelf default — keeps deep
                    // chunks unmounted until they're 1 viewport
                    // away.  Cuts the painted-tile count from ~340
                    // → ~28 at rest on the TV box.
                    rootMargin="600px 0px"
                    eager={idx < EAGER_CHUNKS}
                >
                    <div
                        className="grid"
                        style={{
                            gridTemplateColumns:
                                'repeat(auto-fill, minmax(clamp(150px, 11vw, 200px), 1fr))',
                            gap: 'clamp(18px, 1.6vw, 28px)',
                            // Vertical gap between chunks — keeps the
                            // overall rhythm continuous so chunks
                            // don't look like separate sections.
                            marginBottom: 'clamp(18px, 1.6vw, 28px)',
                        }}
                    >
                        {Array.from({ length: ch.end - ch.start }).map(
                            (_, j) => {
                                const i = ch.start + j;
                                return (
                                    <MorphTile
                                        key={i}
                                        item={items[i] || null}
                                        navigate={navigate}
                                    />
                                );
                            }
                        )}
                    </div>
                </Lazy>
            ))}
        </div>
    );
}

/**
 * Unified tile that morphs between a skeleton placeholder and a
 * real movie poster *within the same DOM node*.  Critical for
 * focus stability: React reuses the `<button>` element when the
 * component type stays the same, so the focused tile keeps the
 * focus ring even as its content swaps from gradient → poster.
 *
 * `item` is null while the catalogue is still loading, and gets
 * filled in once results stream in from useLiveShelves.
 */
function MorphTileImpl({ item, navigate }) {
    const onTap = () => {
        if (!item) return;
        if (item.routePath) navigate(item.routePath);
        else if (item.imdbId)
            navigate(`/title/${item.type || 'movie'}/${item.imdbId}`);
        else navigate(`/title/${item.id}`);
    };
    const onLongPress = () => {
        if (!item) return;
        const id = item.imdbId || item.id;
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
                    year: item.year || item.releaseInfo,
                    genres: item.genres,
                    synopsis: item.description,
                },
            })
        );
    };
    // useLongPress is unconditional so the hook order stays stable
    // between skeleton and real renders; its handlers no-op when
    // item is null.
    const press = useLongPress(onLongPress, onTap);

    const isReady = !!item;
    return (
        <button
            data-testid={isReady ? `grid-${item.imdbId || item.id}` : undefined}
            aria-label={isReady ? item.title : 'Loading'}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            {...press}
            className="group relative overflow-hidden rounded-xl text-left"
            style={{
                width: '100%',
                aspectRatio: '2 / 3',
                padding: 0,
                background: isReady
                    ? 'var(--vesper-bg-2)'
                    : 'linear-gradient(135deg, rgba(255,255,255,0.05) 0%, rgba(255,255,255,0.02) 50%, rgba(255,255,255,0.06) 100%)',
                border: '1px solid rgba(255,255,255,0.05)',
            }}
        >
            {isReady && item.poster ? (
                <img
                    src={img.poster(item.poster)}
                    alt={item.title}
                    loading="lazy"
                    decoding="async"
                    className="absolute inset-0 w-full h-full object-cover"
                />
            ) : isReady ? (
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
            ) : null}
            <div
                className="absolute inset-x-0 bottom-0 h-2/5 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(6,8,15,0) 0%, rgba(6,8,15,0.93) 78%, var(--vesper-bg-0) 100%)',
                }}
            />
            {isReady && (
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
            )}
        </button>
    );
}

// Memoize so identical props skip re-renders — important when
// the parent ChunkedGrid passes through hundreds of MorphTiles
// on every shelves update.  Tile renders only when its specific
// `item` reference actually changes.
const MorphTile = React.memo(MorphTileImpl);
