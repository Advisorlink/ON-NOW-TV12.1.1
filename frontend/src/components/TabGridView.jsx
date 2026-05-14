import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import * as img from '@/lib/img';
import useLongPress from '@/hooks/useLongPress';
import { useAddons } from '@/hooks/useAddons';
import { useTabCatalog } from '@/hooks/useTabCatalog';
import { useTabGenreCatalog } from '@/hooks/useTabGenreCatalog';

/**
 * Newest-first grid for the TV Shows / Movies tab views.
 *
 * Built on top of the new `useTabCatalog` hook that fetches every
 * catalogue from every installed addon IN PARALLEL — much faster
 * than the old serial walk through useLiveShelves.  Default view
 * shows the top 100 newest releases.  Picking a genre chip swaps
 * the grid to "every title in that genre" with no 100-cap.
 */
export default function TabGridView({ type }) {
    const navigate = useNavigate();
    const { addons } = useAddons();
    const { items: allItems, genres: genreList, loading, progress } =
        useTabCatalog(addons, type);

    // Selected genre filter ('' = "All").  Resets when type swaps.
    const [genre, setGenre] = React.useState('');
    React.useEffect(() => {
        setGenre('');
    }, [type]);

    // When a genre is selected, fire a deep-page fetch that pulls
    // EVERY title in that genre from every addon that advertises
    // it (per Stremio's `extra.genre` filter).  While the deep
    // results stream in, the genre hook surfaces matching items
    // from the top-100 cache so the grid is never empty.
    const {
        items: genreItems,
        loading: genreLoading,
        progress: genreProgress,
    } = useTabGenreCatalog(addons, type, genre, allItems);

    // What we actually paint:
    // - No genre selected → top 100 newest releases (capped for
    //   speed per user spec).
    // - A genre selected → every title in that genre, deep-paged.
    const items = React.useMemo(() => {
        if (!genre) return allItems.slice(0, 100);
        return genreItems;
    }, [allItems, genre, genreItems]);

    const showLoading = genre ? genreLoading : loading;
    const showProgress = genre ? genreProgress : progress;

    const heading = type === 'series' ? 'TV Shows' : 'Movies';
    const eyebrow = genre
        ? type === 'series'
            ? `EVERY ${genre.toUpperCase()} SERIES`
            : `EVERY ${genre.toUpperCase()} MOVIE`
        : type === 'series'
        ? 'TOP 100 NEW RELEASED TV SHOWS'
        : 'TOP 100 NEW RELEASED MOVIES';

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
                    {showLoading
                        ? genre
                            ? `Loading every ${genre.toLowerCase()} ${
                                  type === 'series' ? 'series' : 'movie'
                              }…`
                            : 'Pulling catalogues from your installed sources…'
                        : genre
                        ? `Every ${genre} ${
                              type === 'series' ? 'series' : 'movie'
                          } · ${items.length} title${
                              items.length === 1 ? '' : 's'
                          }`
                        : `Top ${Math.min(items.length, 100)} new released ${
                              type === 'series' ? 'TV shows' : 'movies'
                          }`}
                </div>
            </header>

            {genreList.length > 0 && (
                <GenreChips
                    genres={genreList}
                    selected={genre}
                    onSelect={(g) => setGenre(g)}
                />
            )}

            {items.length === 0 && !showLoading ? (
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
                // Full grid — every tile in one paint, streaming in
                // as `items` grows.  Index-based keys mean the
                // focused DOM node stays mounted between shelves.
                // While `loading` is still true we drape a dim
                // overlay + spinner over the grid so the user
                // gets a clear "something's happening" cue and
                // doesn't try to navigate until the data is fully
                // settled.  Once loading flips to false the
                // overlay fades out and the grid is interactive.
                <div style={{ position: 'relative' }}>
                    <div
                        data-testid={`tab-grid-list-${type}`}
                        className="grid"
                        style={{
                            gridTemplateColumns:
                                'repeat(auto-fill, minmax(clamp(150px, 11vw, 200px), 1fr))',
                            gap: 'clamp(18px, 1.6vw, 28px)',
                        }}
                    >
                        {Array.from({
                            length: Math.max(items.length, 14),
                        }).map((_, i) => (
                            <MorphTile
                                // Key by type+index so flipping
                                // Movies ↔ TV Shows unmounts every
                                // previous-tab tile (no poster
                                // bleed-through), while index keeps
                                // focus stable as items stream in
                                // within the same tab.
                                key={`${type}-${i}`}
                                item={items[i] || null}
                                navigate={navigate}
                            />
                        ))}
                    </div>

                    {showLoading && (
                        <LoadingOverlay
                            type={type}
                            progress={showProgress}
                            testId={`tab-grid-loading-${type}`}
                        />
                    )}
                </div>
            )}
        </section>
    );
}

/**
 * Horizontal scroller of genre pills above the tab grid.  The
 * "All" chip resets to the default top-100-newest view.  Picking
 * any other genre swaps the grid to show *every* title in that
 * genre (no 100-item cap), which is the user's explicit request.
 */
function GenreChips({ genres, selected, onSelect }) {
    return (
        <div
            data-testid="tab-genre-chips"
            className="flex"
            style={{
                gap: 10,
                overflowX: 'auto',
                overflowY: 'hidden',
                marginBottom: 26,
                paddingTop: 6,
                paddingBottom: 12,
                scrollSnapType: 'x proximity',
            }}
        >
            <GenreChip
                label="All"
                active={!selected}
                onClick={() => onSelect('')}
            />
            {genres.map((g) => (
                <GenreChip
                    key={g}
                    label={g}
                    active={selected === g}
                    onClick={() => onSelect(g)}
                />
            ))}
        </div>
    );
}

function GenreChip({ label, active, onClick }) {
    return (
        <button
            data-testid={`genre-chip-${label.toLowerCase()}`}
            data-focusable="true"
            data-focus-style="pill"
            tabIndex={0}
            onClick={onClick}
            className="font-sans flex-shrink-0"
            style={{
                height: 40,
                padding: '0 18px',
                borderRadius: 999,
                background: active
                    ? 'var(--vesper-blue)'
                    : 'rgba(255,255,255,0.06)',
                color: active
                    ? 'var(--vesper-bg-0)'
                    : 'var(--vesper-text)',
                border: active
                    ? 'none'
                    : '1px solid rgba(255,255,255,0.12)',
                fontSize: 13,
                fontWeight: active ? 700 : 600,
                whiteSpace: 'nowrap',
                scrollSnapAlign: 'start',
                boxShadow: active
                    ? '0 6px 16px rgba(var(--vesper-blue-rgb),0.4)'
                    : 'none',
            }}
        >
            {label}
        </button>
    );
}

/**
 * Dim-the-grid-while-we-load overlay.  Important: it's
 * `position: absolute` (not fixed) and `pointer-events: none`,
 * so the SideNav, search, and any other UI outside the grid
 * remain clickable while catalogues are still loading.  Without
 * these constraints a stuck `loading=true` state would silently
 * block every click in the app, which is exactly what the user
 * reported ("click TV Shows → nothing loads, click Library →
 * nothing happens").
 */
function LoadingOverlay({ type, testId, progress }) {
    const pct = Math.round(Math.max(0, Math.min(1, progress || 0)) * 100);
    return (
        <div
            data-testid={testId}
            className="absolute inset-0 flex flex-col items-center justify-center"
            style={{
                background: 'rgba(6,8,15,0.72)',
                backdropFilter: 'blur(4px)',
                WebkitBackdropFilter: 'blur(4px)',
                zIndex: 5,
                gap: 18,
                color: 'var(--vesper-blue-bright)',
                pointerEvents: 'none',
                minHeight: '50vh',
            }}
        >
            <Loader2 size={64} strokeWidth={2} className="vesper-spin" />
            <div
                className="vesper-mono"
                style={{
                    fontSize: 12,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-text-2)',
                }}
            >
                Loading {type === 'series' ? 'TV shows' : 'movies'}
                {pct > 0 ? ` · ${pct}%` : '…'}
            </div>
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
