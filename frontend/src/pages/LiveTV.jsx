import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, LogOut, Star, Clock, Search, X } from 'lucide-react';
import SideNav from '@/components/SideNav';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import XtreamLogin from '@/components/XtreamLogin';
import LiveTVBoot from '@/components/LiveTVBoot';
import {
    getActiveProvider,
    authenticate,
    getCategories,
    getStreams,
    getStreamUrl,
    getFullEpg,
} from '@/lib/xtream';
import {
    getFavorites as getFavList,
    toggleFavorite,
} from '@/lib/liveFavorites';
import {
    getRecents,
    pushRecent,
} from '@/lib/liveRecents';
import Host from '@/lib/host';

/* ====================================================================
 *  /live-tv  —  LEAN MODE
 *
 *  TV Mate-style: spend the boot screen pre-caching everything so the
 *  grid is bare-metal fast.  Stripped of:
 *      • TMDB backdrop hero (network call on every channel focus)
 *      • Per-row NOW EPG ticker (60-1000 animated rows = paint hog)
 *      • GUIDE column (heavy DOM)
 *      • Focus glow / scale / drop shadow / transitions
 *      • Hero NOW/UP NEXT inline + progress bar
 *      • Favourites / reminders UI (storage layer kept for later)
 *      • IntersectionObserver virtualization (was broken — re-enable
 *        once base grid runs smooth and we verify the bug is gone)
 *
 *  Grid is now: category list → channel list (number + logo + name)
 *  → click plays via libVLC.  That's it.  Everything else gets
 *  added back one feature at a time once we confirm it stays fast.
 * ==================================================================== */
export default function LiveTV() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [provider, setProvider] = useState(() => getActiveProvider());
    const [view, setView] = useState(provider ? 'grid' : 'login');

    useEffect(() => {
        const onKey = (e) => {
            if ((e.key === 'Escape' || e.key === 'Backspace') &&
                !['INPUT', 'TEXTAREA'].includes(e.target?.tagName)) {
                e.preventDefault();
                navigate('/');
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [navigate]);

    const onAuthed = (p) => { setProvider(p); setView('grid'); };

    return (
        <div data-testid="live-tv-page" className="relative w-screen" style={{
            minHeight: '100dvh', background: 'var(--vesper-bg-0)', overflowX: 'hidden',
        }}>
            <SideNav />
            <main style={{
                marginLeft: 100, minHeight: '100dvh',
                padding: view === 'grid' ? '0 0 24px 0' : '60px 64px 80px 64px',
            }}>
                {view === 'login'
                    ? <XtreamLogin onAuthed={onAuthed} onCancel={() => navigate('/')} />
                    : <LiveTVGrid provider={provider} onChangeProvider={() => setView('login')} />}
            </main>
        </div>
    );
}

/* ============================ Grid ============================ */

function LiveTVGrid({ provider, onChangeProvider }) {
    const [cats, setCats] = useState([]);
    const [catsError, setCatsError] = useState('');
    const [activeCat, setActiveCat] = useState(null);
    const [channels, setChannels] = useState([]);
    const [focusedChannel, setFocusedChannel] = useState(null);

    const [booted, setBooted] = useState(false);
    const [stages, setStages] = useState(() => [
        { id: 'auth', label: 'Authenticating with provider', status: 'pending' },
        { id: 'cats', label: 'Fetching channel categories', status: 'pending' },
        { id: 'cache', label: 'Caching every channel list', status: 'pending' },
    ]);

    // Favorites — keyed by provider.id, persisted to localStorage.
    // Tracked in state so toggling re-renders the star / sidebar
    // count / channel list when the user picks "★ Favourites".
    const [favorites, setFavorites] = useState(
        () => new Set(getFavList(provider.id).map((v) => String(v))),
    );
    const FAV_CAT_ID = '__favorites__';

    // Recently watched — array of stream_ids, MRU first.  Updated on
    // every successful playChannel() call.
    const [recents, setRecents] = useState(
        () => getRecents(provider.id).map((v) => String(v)),
    );
    const REC_CAT_ID = '__recents__';

    // Search query — filters the currently-visible channel list.
    // Reset on every category change so navigating away clears the
    // filter automatically (no stale state).
    const [query, setQuery] = useState('');

    // Per-category channel cache; populated entirely during boot so
    // the grid never has to fetch on category-switch.
    const channelsCache = useRef(new Map());
    const navigate = useNavigate();

    /* -------------------------------------------------------------------
     *  Boot — runs once per LiveTVGrid mount.
     *  Designed to take as long as it needs to (TV Mate takes ~90 s on
     *  the user's box).  Once done, the grid is purely synchronous.
     * ----------------------------------------------------------------- */
    useEffect(() => {
        let cancel = false;
        const setStage = (id, patch) => {
            if (cancel) return;
            setStages((arr) => arr.map((s) => (s.id === id ? { ...s, ...patch } : s)));
        };
        (async () => {
            setStage('auth', { status: 'active' });
            try {
                await authenticate(provider);
                if (cancel) return;
                setStage('auth', { status: 'done', detail: 'Connected.' });
            } catch {
                if (cancel) return;
                setStage('auth', { status: 'failed', detail: 'Server unreachable.' });
            }

            setStage('cats', { status: 'active' });
            let list = [];
            try {
                list = await getCategories(provider, 'live');
                if (cancel) return;
                setCats(Array.isArray(list) ? list : []);
                setStage('cats', { status: 'done', detail: `${list.length} categories.` });
            } catch (e) {
                if (cancel) return;
                setStage('cats', { status: 'failed', detail: e?.message || 'Failed.' });
                setCatsError(e?.message || 'Could not reach your IPTV server.');
                setTimeout(() => !cancel && setBooted(true), 400);
                return;
            }

            setStage('cache', { status: 'active', detail: `0 / ${list.length}` });
            try {
                let done = 0;
                const BATCH = 4;
                for (let i = 0; i < list.length; i += BATCH) {
                    if (cancel) return;
                    const slice = list.slice(i, i + BATCH);
                    await Promise.all(slice.map(async (cat) => {
                        try {
                            const ch = await getStreams(provider, 'live', cat.category_id);
                            channelsCache.current.set(cat.category_id, Array.isArray(ch) ? ch : []);
                        } catch { /* ignore */ }
                        done += 1;
                    }));
                    if (!cancel) setStage('cache', { status: 'active', detail: `${done} / ${list.length}` });
                }
                if (!cancel) setStage('cache', { status: 'done', detail: `${list.length} cached.` });
            } catch {
                if (!cancel) setStage('cache', { status: 'failed', detail: 'Cache failed.' });
            }

            // Auto-select first category + first channel.
            if (!cancel && list.length > 0) {
                const firstCat = list[0].category_id;
                const firstChannels = channelsCache.current.get(firstCat) || [];
                setActiveCat(firstCat);
                setChannels(firstChannels);
                setFocusedChannel(firstChannels[0] || null);
            }

            // Brief pause on the "done" state so the user sees the
            // green checkmark, then transition to the grid.
            setTimeout(() => !cancel && setBooted(true), 500);
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider]);

    /* Build the favourites list — flattens every cached category and
     * keeps only channels whose stream_id is in the favorites set.
     * Cheap to recompute (Map iteration), but we memoize on
     * favorites + booted so we only recalc when something changed. */
    const favoriteChannels = useMemo(() => {
        if (!booted || favorites.size === 0) return [];
        const seen = new Set();
        const out = [];
        for (const list of channelsCache.current.values()) {
            for (const ch of list) {
                const key = String(ch.stream_id);
                if (favorites.has(key) && !seen.has(key)) {
                    seen.add(key);
                    out.push(ch);
                }
            }
        }
        return out;
    }, [favorites, booted]);

    /* Recently-watched channels, in MRU order.  Uses a flat lookup
     * built from the cache so we can resolve stream_id → channel
     * object in O(1) per recent entry. */
    const recentChannels = useMemo(() => {
        if (!booted || recents.length === 0) return [];
        const lookup = new Map();
        for (const list of channelsCache.current.values()) {
            for (const ch of list) {
                lookup.set(String(ch.stream_id), ch);
            }
        }
        const out = [];
        for (const sid of recents) {
            const ch = lookup.get(String(sid));
            if (ch) out.push(ch);
        }
        return out;
    }, [recents, booted]);

    /* Category switch — handles both real categories and the two
     * virtual pseudo-categories.  Pure cache lookup, no fetch. */
    const pickCategory = useCallback((catId) => {
        if (!catId) return;
        setActiveCat(catId);
        setQuery('');  // reset filter on category change
        let list;
        if (catId === FAV_CAT_ID) list = favoriteChannels;
        else if (catId === REC_CAT_ID) list = recentChannels;
        else list = channelsCache.current.get(catId) || [];
        setChannels(list);
        setFocusedChannel(list[0] || null);
    }, [favoriteChannels, recentChannels]);

    /* Keep virtual-category channel lists in sync when their source
     * sets change (favorite toggled or new recent recorded). */
    useEffect(() => {
        if (activeCat === FAV_CAT_ID) {
            setChannels(favoriteChannels);
            if (focusedChannel && !favorites.has(String(focusedChannel.stream_id))) {
                setFocusedChannel(favoriteChannels[0] || null);
            }
        } else if (activeCat === REC_CAT_ID) {
            setChannels(recentChannels);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [favoriteChannels, recentChannels]);

    /* Star toggle — fired from the hero button + key "F" shortcut. */
    const onToggleFav = useCallback(() => {
        const ch = focusedChannel;
        if (!ch) return;
        toggleFavorite(provider.id, ch.stream_id);
        setFavorites(new Set(getFavList(provider.id).map((v) => String(v))));
    }, [focusedChannel, provider]);

    /* "F" key shortcut — toggles favourite on the focused channel. */
    useEffect(() => {
        const onKey = (e) => {
            if ((e.key === 'f' || e.key === 'F') &&
                !['INPUT', 'TEXTAREA'].includes(e.target?.tagName)) {
                e.preventDefault();
                onToggleFav();
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onToggleFav]);

    /* "/" key — jumps focus into the search bar from anywhere.
     *  ESC inside the search input — clears the query (and the
     *  global ESC→home handler is already gated on tagName so
     *  pressing ESC in the input won't navigate away). */
    useEffect(() => {
        const onKey = (e) => {
            if (e.key === '/' && !['INPUT', 'TEXTAREA'].includes(e.target?.tagName)) {
                e.preventDefault();
                const el = document.querySelector('[data-testid="live-tv-search"]');
                if (el) {
                    el.focus();
                    el.select?.();
                }
            } else if (e.key === 'Escape' &&
                e.target?.getAttribute?.('data-testid') === 'live-tv-search') {
                if (query) {
                    e.preventDefault();
                    e.stopPropagation();
                    setQuery('');
                }
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [query]);

    const playChannel = useCallback(async (channel) => {
        if (!channel) return;
        const url = await getStreamUrl(provider, 'live', channel.stream_id, 'ts');
        if (!url) return;
        // Record this play in recents — bumps the channel to the
        // front of the MRU list, persisted to localStorage.
        try {
            pushRecent(provider.id, channel.stream_id);
            setRecents(getRecents(provider.id).map((v) => String(v)));
        } catch { /* ignore */ }
        const title = channel.name;
        if (Host.playVideo({
            url, title, type: 'live',
            poster: '', backdrop: '', synopsis: '',
            year: '', rating: '', runtime: '', genres: [],
            cwId: `live:${provider.id}:${channel.stream_id}`,
        })) return;
        navigate(`/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(title)}&type=live`);
    }, [provider, navigate]);

    const activeCategoryName = useMemo(
        () => cats.find((c) => c.category_id === activeCat)?.category_name || '',
        [cats, activeCat],
    );

    // -------------------------------------------------------------------
    //  EPG — fetched ONLY for the focused channel.  One fetch returns
    //  the full upcoming guide (limit 12); the hero uses [0]+[1] for
    //  Now/Next, the right-hand GUIDE column shows the rest.
    //
    //  Guardrails for low-end boxes:
    //    • 250 ms debounce — fast D-pad scrubbing fires one request.
    //    • 5-min in-memory cache per stream_id — re-focusing is free.
    //    • Stale-request guard so out-of-order responses can't flicker.
    // -------------------------------------------------------------------
    const [epgItems, setEpgItems] = useState([]); // full upcoming list
    const epgCache = useRef(new Map()); // stream_id -> { at, items }
    const epgReqId = useRef(0);

    useEffect(() => {
        const ch = focusedChannel;
        if (!ch) { setEpgItems([]); return undefined; }
        const sid = ch.stream_id;

        // Cache hit (≤ 5 min) — show immediately, skip fetch.
        const cached = epgCache.current.get(sid);
        if (cached && Date.now() - cached.at < 5 * 60_000) {
            setEpgItems(cached.items);
            return undefined;
        }

        // Otherwise blank current EPG while we wait, debounce 250 ms.
        setEpgItems([]);
        const myReq = ++epgReqId.current;
        const t = setTimeout(async () => {
            try {
                const items = await getFullEpg(provider, sid, 12);
                if (epgReqId.current !== myReq) return; // stale
                epgCache.current.set(sid, { at: Date.now(), items });
                setEpgItems(items);
            } catch {
                if (epgReqId.current !== myReq) return;
                setEpgItems([]);
            }
        }, 250);
        return () => clearTimeout(t);
    }, [focusedChannel, provider]);

    const nowNext = useMemo(
        () => ({ now: epgItems[0] || null, next: epgItems[1] || null }),
        [epgItems],
    );

    /* Filtered channel list.  Case-insensitive substring match on
     * channel name; if the query is all digits it also matches the
     * channel number for quick "type 401 → BBC News" jumps. */
    const filteredChannels = useMemo(() => {
        const q = (query || '').trim().toLowerCase();
        if (!q) return channels;
        const isNumeric = /^\d+$/.test(q);
        return channels.filter((c) => {
            const name = (c.name || '').toLowerCase();
            if (name.includes(q)) return true;
            if (isNumeric && c.num != null && String(c.num).includes(q)) return true;
            return false;
        });
    }, [channels, query]);

    /* When the filter changes and the currently-focused channel
     * falls out of the result set, jump focus to the first match
     * so the hero + guide stay in sync with what's visible. */
    useEffect(() => {
        if (filteredChannels.length === 0) return;
        if (!focusedChannel) {
            setFocusedChannel(filteredChannels[0]);
            return;
        }
        const stillThere = filteredChannels.some(
            (c) => c.stream_id === focusedChannel.stream_id,
        );
        if (!stillThere) setFocusedChannel(filteredChannels[0]);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [filteredChannels]);

    if (!booted) {
        return <LiveTVBoot stages={stages} />;
    }

    // Snapshot the per-category channel counts so the sidebar can
    // show "(67)" badges.  Computed once after boot from the cache
    // we already built — no extra fetches.
    const catCounts = {};
    for (const c of cats) {
        catCounts[c.category_id] = channelsCache.current.get(c.category_id)?.length || 0;
    }
    catCounts[FAV_CAT_ID] = favoriteChannels.length;
    catCounts[REC_CAT_ID] = recentChannels.length;

    const isFocusedFav = !!focusedChannel && favorites.has(String(focusedChannel.stream_id));

    return (
        <div>
            <LiveHeroLean
                channel={focusedChannel}
                categoryName={activeCategoryName}
                nowNext={nowNext}
                isFavorite={isFocusedFav}
                onToggleFav={onToggleFav}
                onPlay={() => playChannel(focusedChannel)}
                onExit={onChangeProvider}
            />
            <div className="grid" style={{
                gridTemplateColumns: 'minmax(200px, 240px) minmax(0, 1fr) minmax(280px, 360px)',
                gap: 16,
                padding: '14px 32px 0 32px',
                alignItems: 'start',
            }}>
                <CategoriesCol
                    cats={cats}
                    counts={catCounts}
                    error={catsError}
                    activeId={activeCat}
                    favCatId={FAV_CAT_ID}
                    favCount={favoriteChannels.length}
                    recCatId={REC_CAT_ID}
                    recCount={recentChannels.length}
                    onPick={pickCategory}
                />
                <ChannelsCol
                    channels={filteredChannels}
                    totalCount={channels.length}
                    focusedId={focusedChannel?.stream_id}
                    favorites={favorites}
                    query={query}
                    onQueryChange={setQuery}
                    onFocus={setFocusedChannel}
                    onPlay={playChannel}
                />
                <GuideCol
                    channel={focusedChannel}
                    items={epgItems}
                />
            </div>
        </div>
    );
}

/* ============================ Hero (lean) ============================ */

function LiveHeroLean({ channel, categoryName, nowNext, isFavorite: favOn, onToggleFav, onPlay, onExit }) {
    const logoSrc = channel?.stream_icon ? proxiedLogo(channel.stream_icon, 200) : '';
    const eyebrowParts = ['LIVE TV'];
    if (channel?.num != null) eyebrowParts.push(`CH ${channel.num}`);
    if (categoryName) eyebrowParts.push(categoryName.toUpperCase());

    const now = nowNext?.now || null;
    const next = nowNext?.next || null;
    const progressPct = computeProgress(now);

    // Lightweight real-time clock — refreshes once a minute, no
    // animation, no transition.  Cheap enough for Chrome 52.
    const clock = useNowClock();

    return (
        <section data-testid="live-tv-hero" style={{
            padding: '32px 40px 18px 40px',
            background: 'transparent',
            minHeight: 200,
        }}>
            <div className="flex items-stretch justify-between" style={{ gap: 28 }}>
                {/* Channel logo card — solid bg, thin border, no filter */}
                <div
                    data-testid="live-tv-hero-logo"
                    style={{
                        width: 168,
                        height: 112,
                        flexShrink: 0,
                        background: 'linear-gradient(180deg, rgba(255,255,255,0.06) 0%, rgba(255,255,255,0.02) 100%)',
                        border: '1px solid rgba(255,255,255,0.1)',
                        borderRadius: 14,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        padding: 16,
                        position: 'relative',
                        overflow: 'hidden',
                    }}
                >
                    {logoSrc ? (
                        <img
                            src={logoSrc}
                            alt=""
                            referrerPolicy="no-referrer"
                            onError={(e) => { e.currentTarget.style.display = 'none'; }}
                            style={{
                                maxWidth: '100%',
                                maxHeight: '100%',
                                objectFit: 'contain',
                            }}
                        />
                    ) : (
                        <div className="vesper-mono" style={{
                            fontSize: 12,
                            letterSpacing: '0.3em',
                            color: 'rgba(255,255,255,0.35)',
                        }}>
                            NO LOGO
                        </div>
                    )}
                </div>

                <div className="flex flex-col" style={{ gap: 8, flex: 1, minWidth: 0, justifyContent: 'center' }}>
                    <div className="vesper-mono" style={{
                        fontSize: 11, letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)', textTransform: 'uppercase',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {eyebrowParts.join(' · ')}
                    </div>
                    <h1 className="vesper-display" style={{
                        fontSize: 'clamp(34px, 3.6vw, 52px)',
                        letterSpacing: '-0.025em',
                        lineHeight: 1.02,
                        color: '#fff',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        margin: 0,
                    }}>
                        {channel?.name || 'Live TV'}
                    </h1>

                    {/* EPG block — only when we have a "now" entry */}
                    {now && (
                        <div data-testid="live-tv-hero-epg" style={{ marginTop: 4 }}>
                            <div style={{
                                display: 'flex', alignItems: 'baseline',
                                gap: 10, color: '#fff',
                                whiteSpace: 'nowrap', overflow: 'hidden',
                            }}>
                                <span className="vesper-mono" style={{
                                    fontSize: 10, letterSpacing: '0.28em',
                                    color: 'var(--vesper-blue-bright)', flexShrink: 0,
                                }}>
                                    NOW
                                </span>
                                <span style={{
                                    fontSize: 15, fontWeight: 600, color: 'var(--vesper-text)',
                                    overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0,
                                }}>
                                    {now.title || 'Untitled programme'}
                                </span>
                                <span className="vesper-mono" style={{
                                    fontSize: 11, color: 'var(--vesper-text-3)', flexShrink: 0,
                                }}>
                                    {formatEpgWindow(now)}
                                </span>
                            </div>
                            {/* Static progress bar — no animation, no transition */}
                            <div style={{
                                marginTop: 6,
                                width: '100%', maxWidth: 480,
                                height: 3, background: 'rgba(255,255,255,0.10)',
                                borderRadius: 2, overflow: 'hidden',
                            }}>
                                <div style={{
                                    width: `${progressPct}%`,
                                    height: '100%',
                                    background: 'var(--vesper-blue-bright)',
                                }} />
                            </div>
                            {next && (
                                <div className="vesper-mono" style={{
                                    marginTop: 6, fontSize: 11,
                                    color: 'var(--vesper-text-3)',
                                    letterSpacing: '0.05em',
                                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                    maxWidth: 480,
                                }}>
                                    NEXT · {formatTime(next.startTimestamp)} · {next.title || 'Untitled'}
                                </div>
                            )}
                        </div>
                    )}

                    <div className="flex items-center" style={{ marginTop: 10, gap: 10 }}>
                        <button
                            data-testid="live-tv-hero-play"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            onClick={onPlay}
                            disabled={!channel}
                            className="flex items-center gap-2 rounded-full font-sans"
                            style={{
                                height: 48, padding: '0 24px',
                                fontSize: 14, fontWeight: 700,
                                background: '#fff', color: '#0B1322', border: 'none',
                                opacity: channel ? 1 : 0.5,
                                cursor: channel ? 'pointer' : 'not-allowed',
                            }}
                        >
                            <Play size={15} strokeWidth={2.5} fill="#0B1322" />
                            Watch full-screen
                        </button>
                        <button
                            data-testid="live-tv-hero-fav"
                            data-focusable="true"
                            data-focus-style="quiet"
                            tabIndex={0}
                            onClick={onToggleFav}
                            disabled={!channel}
                            aria-label={favOn ? 'Remove from favourites' : 'Add to favourites'}
                            className="flex items-center justify-center rounded-full"
                            style={{
                                height: 48, width: 48,
                                background: favOn ? 'rgba(255,200,80,0.18)' : 'rgba(255,255,255,0.06)',
                                border: favOn
                                    ? '1px solid rgba(255,200,80,0.55)'
                                    : '1px solid rgba(255,255,255,0.14)',
                                color: favOn ? '#FFC850' : 'var(--vesper-text)',
                                cursor: channel ? 'pointer' : 'not-allowed',
                                opacity: channel ? 1 : 0.5,
                            }}
                        >
                            <Star
                                size={18}
                                strokeWidth={2}
                                fill={favOn ? '#FFC850' : 'none'}
                            />
                        </button>
                    </div>
                </div>

                <div className="flex flex-col items-end" style={{ gap: 10 }}>
                    {/* Real-time clock — refreshes once a minute */}
                    <div data-testid="live-tv-hero-clock" className="flex flex-col items-end" style={{ gap: 2 }}>
                        <div className="vesper-mono" style={{
                            fontSize: 28, fontWeight: 700,
                            color: '#fff',
                            letterSpacing: '-0.02em',
                            lineHeight: 1,
                            fontVariantNumeric: 'tabular-nums',
                        }}>
                            {clock.hhmm}
                        </div>
                        <div className="vesper-mono" style={{
                            fontSize: 10, letterSpacing: '0.22em',
                            color: 'var(--vesper-text-3)',
                            textTransform: 'uppercase',
                        }}>
                            {clock.day}
                        </div>
                    </div>
                    <button
                        data-testid="hero-exit"
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onClick={onExit}
                        aria-label="Change provider"
                        className="flex items-center justify-center rounded-full"
                        style={{
                            width: 44, height: 44,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.14)',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        <LogOut size={18} strokeWidth={2} />
                    </button>
                </div>
            </div>
        </section>
    );
}

/* ============================ Columns ============================ */

function CategoriesCol({ cats, counts = {}, error, activeId, favCatId, favCount, recCatId, recCount, onPick }) {
    return (
        <div data-testid="live-tv-categories" style={{
            padding: '12px 0',
            background: 'rgba(255,255,255,0.02)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: 14,
            maxHeight: 'calc(100dvh - 250px)',
            overflowY: 'auto',
        }}>
            {/* Recently-watched pseudo-category — pinned at the very
                top.  Order is intentional: most-recent-first reading. */}
            {recCatId && recCount > 0 && (
                <RecentCategoryRow
                    isActive={activeId === recCatId}
                    count={recCount}
                    onPick={() => onPick(recCatId)}
                />
            )}
            {/* Favourites pseudo-category — directly below recents.
                Always visible even when empty, to advertise the F key. */}
            {favCatId && (
                <FavCategoryRow
                    isActive={activeId === favCatId}
                    count={favCount || 0}
                    onPick={() => onPick(favCatId)}
                />
            )}
            {error ? (
                <div style={{ padding: '10px 16px' }}>
                    <div style={{ fontSize: 11, color: '#FF6B6B', fontWeight: 700, marginBottom: 4 }}>
                        Server unreachable
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--vesper-text-2)' }}>
                        Normal in web preview. Works on sideloaded APK.
                    </div>
                </div>
            ) : cats.map((c) => {
                const isActive = c.category_id === activeId;
                return (
                    <button
                        key={c.category_id}
                        data-testid={`live-cat-${c.category_id}`}
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onFocus={() => onPick(c.category_id)}
                        onClick={() => onPick(c.category_id)}
                        className="text-left flex items-center"
                        style={{
                            width: 'calc(100% - 10px)', margin: '0 5px',
                            padding: '9px 12px',
                            gap: 10,
                            background: isActive ? 'rgba(93,200,255,0.12)' : 'transparent',
                            borderLeft: isActive ? '3px solid var(--vesper-blue-bright)' : '3px solid transparent',
                            borderTop: 'none', borderRight: 'none', borderBottom: 'none',
                            borderRadius: 6,
                            color: isActive ? '#fff' : 'var(--vesper-text-2)',
                            fontSize: 13, fontWeight: isActive ? 700 : 500,
                            cursor: 'pointer',
                        }}
                    >
                        <span style={{
                            width: 6, height: 6, borderRadius: '50%', flexShrink: 0,
                            background: isActive ? 'var(--vesper-blue-bright)' : 'rgba(255,255,255,0.18)',
                        }} />
                        <span style={{
                            flex: 1, minWidth: 0,
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        }}>
                            {c.category_name}
                        </span>
                        {counts[c.category_id] > 0 && (
                            <span className="vesper-mono" style={{
                                fontSize: 10,
                                fontWeight: 700,
                                color: isActive
                                    ? 'var(--vesper-blue-bright)'
                                    : 'var(--vesper-text-3)',
                                letterSpacing: '0.04em',
                                flexShrink: 0,
                            }}>
                                {counts[c.category_id]}
                            </span>
                        )}
                    </button>
                );
            })}
        </div>
    );
}

/* Recently-watched pseudo-category — same visual language as the
 * favourites row but with a clock icon + a different colour so the
 * two pinned rows feel like a related pair rather than a duplicate. */
function RecentCategoryRow({ isActive, count, onPick }) {
    return (
        <div style={{
            paddingBottom: 6,
            marginBottom: 6,
            borderBottom: '1px solid rgba(255,255,255,0.05)',
        }}>
            <button
                data-testid="live-cat-recents"
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onFocus={onPick}
                onClick={onPick}
                className="text-left flex items-center"
                style={{
                    width: 'calc(100% - 10px)', margin: '0 5px',
                    padding: '9px 12px',
                    gap: 10,
                    background: isActive ? 'rgba(93,200,255,0.12)' : 'transparent',
                    borderLeft: isActive ? '3px solid var(--vesper-blue-bright)' : '3px solid transparent',
                    borderTop: 'none', borderRight: 'none', borderBottom: 'none',
                    borderRadius: 6,
                    color: isActive ? '#fff' : 'var(--vesper-text-2)',
                    fontSize: 13, fontWeight: isActive ? 700 : 600,
                    cursor: 'pointer',
                }}
            >
                <Clock
                    size={13}
                    strokeWidth={2}
                    color={isActive ? 'var(--vesper-blue-bright)' : 'rgba(93,200,255,0.7)'}
                    style={{ flexShrink: 0 }}
                />
                <span style={{
                    flex: 1, minWidth: 0,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    letterSpacing: '0.04em',
                }}>
                    Recently Watched
                </span>
                {count > 0 && (
                    <span className="vesper-mono" style={{
                        fontSize: 10, fontWeight: 700,
                        color: isActive
                            ? 'var(--vesper-blue-bright)'
                            : 'var(--vesper-text-3)',
                        letterSpacing: '0.04em',
                        flexShrink: 0,
                    }}>
                        {count}
                    </span>
                )}
            </button>
        </div>
    );
}

/* Favourites pseudo-category — pinned to the top of the sidebar.
 * Visually identical to a real category row but with a gold star
 * marker instead of the neon dot, and a thin divider underneath
 * to separate it from the provider-supplied categories. */
function FavCategoryRow({ isActive, count, onPick }) {
    return (
        <div style={{
            paddingBottom: 6,
            marginBottom: 6,
            borderBottom: '1px solid rgba(255,255,255,0.05)',
        }}>
            <button
                data-testid="live-cat-favorites"
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onFocus={onPick}
                onClick={onPick}
                className="text-left flex items-center"
                style={{
                    width: 'calc(100% - 10px)', margin: '0 5px',
                    padding: '9px 12px',
                    gap: 10,
                    background: isActive ? 'rgba(255,200,80,0.12)' : 'transparent',
                    borderLeft: isActive ? '3px solid #FFC850' : '3px solid transparent',
                    borderTop: 'none', borderRight: 'none', borderBottom: 'none',
                    borderRadius: 6,
                    color: isActive ? '#fff' : 'var(--vesper-text-2)',
                    fontSize: 13, fontWeight: isActive ? 700 : 600,
                    cursor: 'pointer',
                }}
            >
                <Star
                    size={13}
                    strokeWidth={2}
                    fill={isActive ? '#FFC850' : 'none'}
                    color={isActive ? '#FFC850' : 'rgba(255,200,80,0.7)'}
                    style={{ flexShrink: 0 }}
                />
                <span style={{
                    flex: 1, minWidth: 0,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    letterSpacing: '0.04em',
                }}>
                    Favourites
                </span>
                {count > 0 && (
                    <span className="vesper-mono" style={{
                        fontSize: 10, fontWeight: 700,
                        color: isActive ? '#FFC850' : 'var(--vesper-text-3)',
                        letterSpacing: '0.04em',
                        flexShrink: 0,
                    }}>
                        {count}
                    </span>
                )}
            </button>
        </div>
    );
}

function ChannelsCol({ channels, totalCount, focusedId, favorites, query, onQueryChange, onFocus, onPlay }) {
    // Windowed render — Chrome 52 (HK1) doesn't support
    // content-visibility, so we hand-virtualize.  Start with 50,
    // grow by 50 every time the sentinel intersects.
    const STEP = 50;
    const [visibleCount, setVisibleCount] = useState(STEP);
    const sentinelRef = useRef(null);
    const containerRef = useRef(null);

    useEffect(() => { setVisibleCount(STEP); }, [channels]);

    useEffect(() => {
        if (!sentinelRef.current || !containerRef.current) return undefined;
        if (typeof IntersectionObserver === 'undefined') return undefined;
        const obs = new IntersectionObserver(
            (entries) => {
                if (entries.some((e) => e.isIntersecting)) {
                    setVisibleCount((v) => Math.min(v + STEP, channels.length));
                }
            },
            { root: containerRef.current, rootMargin: '200px' },
        );
        obs.observe(sentinelRef.current);
        return () => obs.disconnect();
    }, [channels.length, visibleCount]);

    const visible = useMemo(() => channels.slice(0, visibleCount), [channels, visibleCount]);

    const filterActive = !!(query && query.trim());

    return (
        <div data-testid="live-tv-channels-wrapper">
            {/* Search bar — sits above the scroll container so it
                stays in place while the channel list scrolls. */}
            <ChannelSearchBar
                query={query}
                onChange={onQueryChange}
                resultCount={channels.length}
                totalCount={totalCount}
            />
            <div
                data-testid="live-tv-channels"
                ref={containerRef}
                style={{
                    padding: '8px 6px',
                    background: 'rgba(255,255,255,0.018)',
                    border: '1px solid rgba(255,255,255,0.06)',
                    borderRadius: 14,
                    maxHeight: 'calc(100dvh - 310px)',
                    overflowY: 'auto',
                }}
            >
                {channels.length === 0 ? (
                    filterActive ? (
                        <div style={{ padding: '24px 18px', color: 'var(--vesper-text-3)', fontSize: 13, lineHeight: 1.5 }}>
                            No channels match <strong style={{ color: '#fff' }}>“{query}”</strong>.
                        </div>
                    ) : (
                        <div style={{ padding: '24px 18px', color: 'var(--vesper-text-3)', fontSize: 13, lineHeight: 1.5 }}>
                            No channels here yet.
                            <div style={{
                                marginTop: 6,
                                fontSize: 11, color: 'var(--vesper-text-3)',
                                letterSpacing: '0.04em',
                            }}>
                                Focus a channel and press <strong style={{ color: '#FFC850' }}>F</strong> (or tap the
                                <Star size={11} strokeWidth={2} fill="#FFC850" color="#FFC850" style={{ display: 'inline', verticalAlign: 'middle', margin: '0 3px' }} />
                                in the hero) to add it.
                            </div>
                        </div>
                    )
                ) : (
                    <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                        {visible.map((c) => (
                            <ChannelRowLean
                                key={c.stream_id}
                                channel={c}
                                focused={c.stream_id === focusedId}
                                isFav={favorites?.has(String(c.stream_id)) || false}
                                onFocus={() => onFocus(c)}
                                onPlay={() => { onFocus(c); onPlay(c); }}
                            />
                        ))}
                        {visibleCount < channels.length && (
                            <li ref={sentinelRef} aria-hidden="true" style={{ height: 1 }} />
                        )}
                    </ul>
                )}
            </div>
        </div>
    );
}

/* ============================ Search bar ============================ */
function ChannelSearchBar({ query, onChange, resultCount, totalCount }) {
    const inputRef = useRef(null);
    const hasQuery = !!(query && query.trim());

    return (
        <div style={{
            display: 'flex', alignItems: 'center',
            gap: 10, marginBottom: 10,
            padding: '8px 14px',
            background: hasQuery
                ? 'rgba(93,200,255,0.10)'
                : 'rgba(255,255,255,0.04)',
            border: hasQuery
                ? '1px solid rgba(93,200,255,0.45)'
                : '1px solid rgba(255,255,255,0.08)',
            borderRadius: 10,
            minHeight: 44,
        }}>
            <Search
                size={15}
                strokeWidth={2}
                color={hasQuery ? 'var(--vesper-blue-bright)' : 'var(--vesper-text-3)'}
                style={{ flexShrink: 0 }}
            />
            <input
                ref={inputRef}
                data-testid="live-tv-search"
                data-focusable="true"
                data-focus-style="quiet"
                type="text"
                value={query || ''}
                onChange={(e) => onChange(e.target.value)}
                placeholder="Search channels (name or number)…"
                style={{
                    flex: 1, minWidth: 0,
                    background: 'transparent',
                    border: 'none', outline: 'none',
                    color: '#fff',
                    fontSize: 13, fontWeight: 500,
                    fontFamily: 'inherit',
                }}
            />
            {hasQuery ? (
                <>
                    <span className="vesper-mono" style={{
                        fontSize: 10, fontWeight: 700,
                        color: 'var(--vesper-blue-bright)',
                        letterSpacing: '0.06em',
                        flexShrink: 0,
                    }}>
                        {resultCount} / {totalCount}
                    </span>
                    <button
                        data-testid="live-tv-search-clear"
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onClick={() => { onChange(''); inputRef.current?.focus(); }}
                        aria-label="Clear search"
                        className="flex items-center justify-center rounded-full"
                        style={{
                            width: 24, height: 24,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            color: 'var(--vesper-text-2)',
                            cursor: 'pointer', flexShrink: 0,
                        }}
                    >
                        <X size={12} strokeWidth={2.5} />
                    </button>
                </>
            ) : (
                <span className="vesper-mono" style={{
                    fontSize: 10, fontWeight: 700,
                    color: 'var(--vesper-text-3)',
                    letterSpacing: '0.06em',
                    flexShrink: 0,
                }}>
                    {totalCount}
                </span>
            )}
        </div>
    );
}

/* ============================ Channel Row (lean) ============================ */
function ChannelRowLean({ channel, focused, isFav, onFocus, onPlay }) {
    return (
        <li>
            <button
                data-testid={`live-channel-${channel.stream_id}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onFocus={onFocus}
                onClick={onPlay}
                className="text-left flex items-center"
                style={{
                    width: 'calc(100% - 12px)',
                    margin: '2px 6px',
                    padding: '8px 12px',
                    gap: 14,
                    background: focused ? 'rgba(93,200,255,0.10)' : 'transparent',
                    borderLeft: focused
                        ? '3px solid var(--vesper-blue-bright)'
                        : '3px solid transparent',
                    borderTop: 'none', borderRight: 'none', borderBottom: 'none',
                    borderRadius: 8,
                    color: focused ? '#fff' : 'var(--vesper-text)',
                    cursor: 'pointer',
                    minHeight: 52,
                }}
            >
                {channel.num != null && (
                    <span style={{
                        fontFamily: 'monospace', fontSize: 12,
                        color: focused ? 'var(--vesper-blue-bright)' : 'var(--vesper-text-3)',
                        minWidth: 36, textAlign: 'right', fontWeight: 700,
                        letterSpacing: '0.04em',
                    }}>
                        {channel.num}
                    </span>
                )}
                <span style={{
                    width: 44, height: 30, flexShrink: 0,
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.06)',
                    borderRadius: 4,
                    overflow: 'hidden',
                    position: 'relative',
                }}>
                    {channel.stream_icon && (
                        <img
                            src={proxiedLogo(channel.stream_icon, 44)}
                            alt=""
                            width={44}
                            height={30}
                            loading="lazy"
                            decoding="async"
                            referrerPolicy="no-referrer"
                            onError={(e) => { e.currentTarget.style.display = 'none'; }}
                            style={{
                                position: 'absolute', inset: 0,
                                width: '100%', height: '100%',
                                objectFit: 'contain',
                                padding: 3,
                            }}
                        />
                    )}
                </span>
                <span style={{
                    flex: 1, minWidth: 0,
                    fontSize: 14,
                    fontWeight: focused ? 700 : 600,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {channel.name}
                </span>
                {isFav && (
                    <Star
                        size={13}
                        strokeWidth={2}
                        fill="#FFC850"
                        color="#FFC850"
                        style={{ flexShrink: 0 }}
                    />
                )}
            </button>
        </li>
    );
}

/* ============================ Guide Column (right) ============================ */

function GuideCol({ channel, items }) {
    const nowSec = Math.floor(Date.now() / 1000);

    return (
        <div
            data-testid="live-tv-guide"
            style={{
                padding: '14px 0 6px 0',
                background: 'rgba(255,255,255,0.02)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 14,
                maxHeight: 'calc(100dvh - 250px)',
                overflowY: 'auto',
            }}
        >
            {/* Header */}
            <div style={{
                padding: '0 16px 12px 16px',
                borderBottom: '1px solid rgba(255,255,255,0.06)',
                marginBottom: 8,
            }}>
                <div className="vesper-mono" style={{
                    fontSize: 10, letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)',
                    marginBottom: 4,
                }}>
                    PROGRAMME GUIDE
                </div>
                <div style={{
                    fontSize: 13, color: 'var(--vesper-text-2)', fontWeight: 600,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {channel?.name || '—'}
                </div>
            </div>

            {/* Body */}
            {items.length === 0 ? (
                <div style={{
                    padding: '14px 16px', color: 'var(--vesper-text-3)',
                    fontSize: 12, lineHeight: 1.5,
                }}>
                    No guide data for this channel.
                </div>
            ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {items.slice(0, 12).map((it, idx) => {
                        const start = Number(it.startTimestamp) || 0;
                        const stop = Number(it.stopTimestamp) || 0;
                        const isLive = nowSec >= start && nowSec < stop;
                        return (
                            <GuideRow
                                key={`${start}-${idx}`}
                                item={it}
                                isLive={isLive}
                            />
                        );
                    })}
                </ul>
            )}
        </div>
    );
}

function GuideRow({ item, isLive }) {
    return (
        <li style={{
            position: 'relative',
            padding: '10px 16px',
            borderLeft: isLive
                ? '3px solid var(--vesper-blue-bright)'
                : '3px solid transparent',
            background: isLive ? 'rgba(93,200,255,0.06)' : 'transparent',
        }}>
            <div style={{
                display: 'flex', alignItems: 'center', gap: 8,
                marginBottom: 3,
            }}>
                <span className="vesper-mono" style={{
                    fontSize: 11, fontWeight: 700,
                    color: isLive ? 'var(--vesper-blue-bright)' : 'var(--vesper-text-3)',
                    letterSpacing: '0.04em',
                }}>
                    {formatTime(item.startTimestamp)}
                </span>
                {isLive && (
                    <span className="vesper-mono" style={{
                        fontSize: 9, letterSpacing: '0.22em', fontWeight: 700,
                        color: 'var(--vesper-blue-bright)',
                        padding: '2px 6px',
                        background: 'rgba(93,200,255,0.14)',
                        borderRadius: 3,
                    }}>
                        LIVE
                    </span>
                )}
            </div>
            <div style={{
                fontSize: 13, fontWeight: isLive ? 700 : 600,
                color: isLive ? '#fff' : 'var(--vesper-text)',
                lineHeight: 1.3,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                display: '-webkit-box',
                WebkitLineClamp: 2,
                WebkitBoxOrient: 'vertical',
                whiteSpace: 'normal',
            }}>
                {item.title || 'Untitled programme'}
            </div>
            {/* Description only on the currently-airing entry — keeps
                the rest of the list compact and avoids fetching
                anything extra (description already came with the EPG). */}
            {isLive && item.description && (
                <div style={{
                    marginTop: 6,
                    fontSize: 11,
                    color: 'var(--vesper-text-2)',
                    lineHeight: 1.4,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    display: '-webkit-box',
                    WebkitLineClamp: 3,
                    WebkitBoxOrient: 'vertical',
                    whiteSpace: 'normal',
                }}>
                    {item.description}
                </div>
            )}
        </li>
    );
}

/* ============================ Helpers ============================ */

/** Real-time clock — returns { hhmm: "14:32", day: "TUE 14 MAY" }.
 *  Refreshes itself every 30 s so the minute roll-over is never more
 *  than 30 s late.  No setInterval-on-every-render; uses a single
 *  state+useEffect pair so it costs ~nothing on the HK1 box. */
function useNowClock() {
    const [now, setNow] = useState(() => new Date());
    useEffect(() => {
        const t = setInterval(() => setNow(new Date()), 30_000);
        return () => clearInterval(t);
    }, []);
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    const days = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
    const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
    return {
        hhmm: `${hh}:${mm}`,
        day: `${days[now.getDay()]} ${now.getDate()} ${months[now.getMonth()]}`,
    };
}

function proxiedLogo(url, width = 36) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    if (!base) return url;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${width}&q=50`;
}

/** Returns "HH:MM–HH:MM" for an EPG entry's start/stop timestamps. */
function formatEpgWindow(item) {
    if (!item) return '';
    const a = formatTime(item.startTimestamp);
    const b = formatTime(item.stopTimestamp);
    if (!a && !b) return '';
    if (!a) return b;
    if (!b) return a;
    return `${a}–${b}`;
}

/** Returns "HH:MM" (24 h) for a unix-seconds timestamp. */
function formatTime(ts) {
    const n = Number(ts);
    if (!n || !Number.isFinite(n)) return '';
    const d = new Date(n * 1000);
    const hh = String(d.getHours()).padStart(2, '0');
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${hh}:${mm}`;
}

/** Returns 0–100 — how far through the current EPG window we are. */
function computeProgress(item) {
    if (!item) return 0;
    const start = Number(item.startTimestamp) || 0;
    const stop = Number(item.stopTimestamp) || 0;
    if (stop <= start) return 0;
    const nowSec = Math.floor(Date.now() / 1000);
    if (nowSec <= start) return 0;
    if (nowSec >= stop) return 100;
    return Math.round(((nowSec - start) / (stop - start)) * 100);
}
