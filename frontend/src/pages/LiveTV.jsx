import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Play, Loader2, Radio, RefreshCw, Star, LogOut, Heart, Bell, BellRing, CalendarDays, Check,
} from 'lucide-react';
import SideNav from '@/components/SideNav';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import XtreamLogin from '@/components/XtreamLogin';
import LiveTVBoot from '@/components/LiveTVBoot';
import {
    getActiveProvider,
    authenticate,
    getCategories,
    getStreams,
    getNowNext,
    getFullEpg,
    getStreamUrl,
} from '@/lib/xtream';
import {
    listFavourites, listFavouriteIds, toggleFavourite,
    hasReminder, toggleReminder, rehydrateReminders,
} from '@/lib/xtreamPrefs';
import Host from '@/lib/host';

/* ====================================================================
 *  /live-tv
 *
 *  Layout (matches design screenshot):
 *
 *  ┌───────────────────────────────────────────────────────────────┐
 *  │ LIVE TV · CH 93              [⭐] [↻] [⇥]                     │
 *  │                                                               │
 *  │ Sky Cinema Animation HD                  [channel logo]       │
 *  │ NOW · 03:00 PM  Current show                                  │
 *  │ ▓▓▓▓▓▓▓▓░░░░░░░░░░  ← progress bar                            │
 *  │ UP NEXT · 07:00 PM  Coming up...                              │
 *  │ [▶  Watch full-screen]                                        │
 *  ├──────────────┬───────────────────────────┬──────────────────┤
 *  │  Favourites  │  92  [logo] FHD          │  📅 GUIDE  · Sky… │
 *  │  ──          │       NOW Movie X        │                  │
 *  │  UK CHAN…    │       ▓▓░░░░             │  03:00  No long… │
 *  │  UK Ent…     │  93  [logo] HD ← active  │  TOMORROW        │
 *  │  UK Sky…  ◀  │       NOW Movie Y        │  07:00 …  ⚐ REM │
 *  │  UK Kids…    │       ▓▓▓▓░░             │  11:00 …  ✓ SET │
 *  │              │  94  [logo] SD           │  …               │
 *  └──────────────┴───────────────────────────┴──────────────────┘
 *
 *  Perf:
 *  - No glow/blur/scale animations anywhere.
 *  - content-visibility: auto on each channel row + EPG row.
 *  - contain: paint on each column scroll container.
 *  - AbortController + 180 ms debounce on NOW/NEXT + EPG fetches.
 *  - 60-s in-memory EPG cache; 300-s full-EPG cache.
 * ==================================================================== */

export default function LiveTV() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [provider, setProvider] = useState(() => getActiveProvider());
    const [view, setView] = useState(provider ? 'grid' : 'login');

    useEffect(() => {
        if (provider) rehydrateReminders(provider.id);
    }, [provider]);

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
    const [activeCat, setActiveCat] = useState(null); // null until cats arrive
    const [channels, setChannels] = useState([]);
    const [channelsLoading, setChannelsLoading] = useState(false);
    const [focusedChannel, setFocusedChannel] = useState(null);
    const [favVer, setFavVer] = useState(0);
    const [remVer, setRemVer] = useState(0);
    const [refreshing, setRefreshing] = useState(false);

    // Boot sequence — TV Mate style.  Once `booted` is true the
    // hero + 3-col body take over.  Until then we render the
    // LiveTVBoot loading screen with per-stage progress.
    const [booted, setBooted] = useState(false);
    const [stages, setStages] = useState(() => [
        { id: 'auth', label: 'Authenticating with provider', status: 'pending' },
        { id: 'cats', label: 'Fetching channel categories', status: 'pending' },
        { id: 'warm', label: 'Pre-warming the EPG guide', status: 'pending' },
        { id: 'cache', label: 'Caching every category in the background', status: 'pending' },
    ]);

    // EPG caches (key: stream_id)
    const nowNextCache = useRef(new Map());
    const fullEpgCache = useRef(new Map());
    const [, setEpgTick] = useState(0);
    const nowAbort = useRef(null);
    const fullAbort = useRef(null);
    // Track which categories' channel lists we've already fetched
    // so a return-to-category is instant.
    const channelsCache = useRef(new Map()); // category_id -> [channels]

    const navigate = useNavigate();

    /* React to fav / reminder mutations */
    useEffect(() => {
        const a = () => setFavVer((v) => v + 1);
        const b = () => setRemVer((v) => v + 1);
        window.addEventListener('vesper:xtream-favs-change', a);
        window.addEventListener('vesper:xtream-reminders-change', b);
        return () => {
            window.removeEventListener('vesper:xtream-favs-change', a);
            window.removeEventListener('vesper:xtream-reminders-change', b);
        };
    }, []);

    /* -------------------------------------------------------------------
     *  BOOT SEQUENCE — runs once per LiveTVGrid mount.
     *  Stages:
     *    1. auth   — quick GET to confirm credentials still valid
     *    2. cats   — fetch the categories list (small, fast)
     *    3. warm   — fetch the FIRST category's channels in background
     *                so the initial focused channel has EPG instantly
     *  We never fetch "all channels" — that's a TV Mate-style killer
     *  for big providers (1000+ channels).
     * ----------------------------------------------------------------- */
    useEffect(() => {
        let cancel = false;
        const setStage = (id, patch) => {
            if (cancel) return;
            setStages((arr) => arr.map((s) => (s.id === id ? { ...s, ...patch } : s)));
        };
        (async () => {
            // ---------- stage 1: auth ----------
            setStage('auth', { status: 'active' });
            try {
                const auth = await authenticate(provider);
                if (cancel) return;
                if (!auth?.ok && auth?.user_info?.auth !== 1 && auth?.user_info?.auth !== '1') {
                    // We got a response but credentials were rejected.
                    setStage('auth', { status: 'failed', detail: 'Credentials rejected.' });
                    setCatsError('Provider rejected the saved credentials.');
                    return;
                }
                setStage('auth', { status: 'done', detail: 'Connected.' });
            } catch (e) {
                if (cancel) return;
                setStage('auth', { status: 'failed', detail: 'Server unreachable.' });
                // Don't bail — categories might still come back from the
                // backend proxy fallback.  We just won't have an "auth OK"
                // indicator.
            }

            // ---------- stage 2: categories ----------
            setStage('cats', { status: 'active' });
            let list = [];
            try {
                list = await getCategories(provider, 'live');
                if (cancel) return;
                setCats(Array.isArray(list) ? list : []);
                if (list?.length) {
                    setActiveCat(list[0].category_id);
                    setStage('cats', { status: 'done', detail: `${list.length} categories.` });
                } else {
                    setStage('cats', { status: 'failed', detail: 'No categories returned.' });
                }
            } catch (e) {
                if (cancel) return;
                setStage('cats', { status: 'failed', detail: e?.message || 'Could not load categories.' });
                setCatsError(e?.message || 'Could not reach your IPTV server.');
                // Even if cats failed, render the empty grid so the
                // user can pick favourites / change provider.
                setTimeout(() => !cancel && setBooted(true), 300);
                return;
            }

            // ---------- stage 3: pre-warm first category ----------
            setStage('warm', { status: 'active', detail: list[0]?.category_name || '' });
            try {
                const first = list[0];
                if (first) {
                    const ch = await getStreams(provider, 'live', first.category_id);
                    if (cancel) return;
                    channelsCache.current.set(first.category_id, Array.isArray(ch) ? ch : []);
                    setChannels(Array.isArray(ch) ? ch : []);
                    if (ch?.length) setFocusedChannel(ch[0]);
                    setStage('warm', {
                        status: 'done',
                        detail: ch?.length ? `${ch.length} channels ready.` : 'No channels in first category.',
                    });
                }
            } catch (e) {
                if (cancel) return;
                setStage('warm', { status: 'failed', detail: e?.message || 'Could not warm cache.' });
            }

            // ---------- stage 4: cache EVERY remaining category in the background ----------
            // The user explicitly asked to let the boot screen run
            // for longer so subsequent category-switches are instant.
            // We fetch the rest in parallel batches of 4 so we don't
            // hammer the provider, and report progress as we go.
            setStage('cache', { status: 'active', detail: `0 / ${list.length}` });
            try {
                const rest = list.slice(1);
                const total = list.length;
                let done = 1;
                const BATCH = 4;
                for (let i = 0; i < rest.length; i += BATCH) {
                    if (cancel) return;
                    const slice = rest.slice(i, i + BATCH);
                    await Promise.all(slice.map(async (cat) => {
                        try {
                            const ch = await getStreams(provider, 'live', cat.category_id);
                            channelsCache.current.set(cat.category_id, Array.isArray(ch) ? ch : []);
                        } catch { /* ignore individual category fails */ }
                        done += 1;
                    }));
                    if (!cancel) setStage('cache', { status: 'active', detail: `${done} / ${total}` });
                }
                if (!cancel) setStage('cache', { status: 'done', detail: `${total} / ${total} cached.` });
            } catch (e) {
                if (!cancel) setStage('cache', { status: 'failed', detail: 'Some categories failed to cache.' });
            }

            // Let the user see the "ready" state for a beat so it doesn't
            // feel like a flicker, then transition to the grid.
            setTimeout(() => !cancel && setBooted(true), 600);
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider]);

    /* Channels for selected category — only fires after boot.  Uses
       the in-memory cache so re-selecting a previously-loaded
       category is instant. */
    useEffect(() => {
        if (!booted) return undefined;
        if (!activeCat) return undefined;
        let cancel = false;
        (async () => {
            try {
                if (activeCat === '__fav__') {
                    // Favourites render from localStorage only — no
                    // round-trip needed.
                    const favs = listFavourites(provider.id);
                    setChannels(favs);
                    setFocusedChannel(favs[0] || null);
                    return;
                }
                const cached = channelsCache.current.get(activeCat);
                if (cached) {
                    setChannels(cached);
                    setFocusedChannel(cached[0] || null);
                    return;
                }
                setChannelsLoading(true);
                const list = await getStreams(provider, 'live', activeCat);
                if (cancel) return;
                const arr = Array.isArray(list) ? list : [];
                channelsCache.current.set(activeCat, arr);
                setChannels(arr);
                setFocusedChannel(arr[0] || null);
            } catch (e) {
                if (!cancel) console.warn('streams', e);
            } finally {
                if (!cancel) setChannelsLoading(false);
            }
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider, activeCat, favVer, booted]);

    /* NOW/NEXT for focused channel — debounced, abortable */
    useEffect(() => {
        if (!focusedChannel) return undefined;
        const sid = focusedChannel.stream_id;
        const cached = nowNextCache.current.get(sid);
        if (cached && Date.now() - cached.fetchedAt < 60_000) return undefined;
        if (nowAbort.current) nowAbort.current.abort?.();
        const ctl = new AbortController();
        nowAbort.current = ctl;
        const t = setTimeout(async () => {
            try {
                const items = await getNowNext(provider, sid);
                if (ctl.signal.aborted) return;
                nowNextCache.current.set(sid, {
                    now: items[0] || null, next: items[1] || null, fetchedAt: Date.now(),
                });
                setEpgTick((v) => v + 1);
            } catch { /* ignore */ }
        }, 180);
        return () => { clearTimeout(t); ctl.abort?.(); };
    }, [focusedChannel, provider]);

    /* Full EPG for focused channel — debounced, abortable, 5 min cache */
    useEffect(() => {
        if (!focusedChannel) return undefined;
        const sid = focusedChannel.stream_id;
        const cached = fullEpgCache.current.get(sid);
        if (cached && Date.now() - cached.fetchedAt < 300_000) return undefined;
        if (fullAbort.current) fullAbort.current.abort?.();
        const ctl = new AbortController();
        fullAbort.current = ctl;
        const t = setTimeout(async () => {
            try {
                const items = await getFullEpg(provider, sid, 40);
                if (ctl.signal.aborted) return;
                fullEpgCache.current.set(sid, { items: items || [], fetchedAt: Date.now() });
                setEpgTick((v) => v + 1);
            } catch { /* ignore */ }
        }, 250);
        return () => { clearTimeout(t); ctl.abort?.(); };
    }, [focusedChannel, provider]);

    const refreshAll = useCallback(() => {
        nowNextCache.current.clear();
        fullEpgCache.current.clear();
        channelsCache.current.clear();
        setRefreshing(true);
        // Force re-fetch of the active category by toggling cat key.
        const cur = activeCat;
        setActiveCat(null);
        setTimeout(() => setActiveCat(cur), 50);
        setTimeout(() => setRefreshing(false), 800);
    }, [activeCat]);

    const playChannel = useCallback(async (channel) => {
        if (!channel) return;
        const url = await getStreamUrl(provider, 'live', channel.stream_id, 'ts');
        if (!url) return;
        const ep = nowNextCache.current.get(channel.stream_id);
        const title = `${channel.name}${ep?.now?.title ? ` · ${ep.now.title}` : ''}`;
        if (Host.playVideo({
            url, title, type: 'live',
            poster: channel.stream_icon || '', backdrop: channel.stream_icon || '',
            synopsis: ep?.now?.description || '',
            year: '', rating: '', runtime: '', genres: [],
            cwId: `live:${provider.id}:${channel.stream_id}`,
        })) return;
        navigate(`/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(title)}&type=live`);
    }, [provider, navigate]);

    const focusedNowNext = focusedChannel ? nowNextCache.current.get(focusedChannel.stream_id) : null;
    const focusedFullEpg = focusedChannel ? fullEpgCache.current.get(focusedChannel.stream_id) : null;
    const favCount = listFavouriteIds(provider.id).size;
    void favVer; void remVer;

    // Boot screen — until cats arrive (or fail), show progress.
    if (!booted) {
        return <LiveTVBoot stages={stages} />;
    }

    return (
        <div>
            {/* HERO */}
            <LiveHero
                provider={provider}
                channel={focusedChannel}
                epg={focusedNowNext}
                isFav={focusedChannel ? listFavouriteIds(provider.id).has(String(focusedChannel.stream_id)) : false}
                onFav={() => focusedChannel && toggleFavourite(provider.id, focusedChannel)}
                onRefresh={refreshAll}
                refreshing={refreshing}
                onExit={onChangeProvider}
                onPlay={() => playChannel(focusedChannel)}
            />

            {/* 3-col body */}
            <div className="grid" style={{
                gridTemplateColumns: 'minmax(220px, 240px) minmax(0, 1fr) minmax(360px, 400px)',
                gap: 18,
                padding: '14px 32px 0 32px',
                alignItems: 'start',
            }}>
                <CategoriesCol
                    cats={cats}
                    favCount={favCount}
                    loading={false}
                    error={catsError}
                    activeId={activeCat}
                    onPick={setActiveCat}
                />
                <ChannelsCol
                    channels={channels}
                    loading={channelsLoading}
                    nowNextCache={nowNextCache.current}
                    focusedId={focusedChannel?.stream_id}
                    onFocus={setFocusedChannel}
                    onPlay={playChannel}
                />
                <GuideCol
                    providerId={provider.id}
                    channel={focusedChannel}
                    epg={focusedFullEpg}
                />
            </div>
        </div>
    );
}

/* ============================ Hero ============================ */

function LiveHero({ provider, channel, epg, isFav, onFav, onRefresh, refreshing, onExit, onPlay }) {
    const now = epg?.now;
    const next = epg?.next;
    const progressPct = useMemo(() => {
        if (!now?.startTimestamp || !now?.stopTimestamp) return 0;
        const start = Number(now.startTimestamp) * 1000;
        const end = Number(now.stopTimestamp) * 1000;
        const t = Date.now();
        if (t <= start) return 0;
        if (t >= end) return 100;
        return Math.round(((t - start) / (end - start)) * 100);
    }, [now]);

    // TMDB backdrop lookup — when the focused channel's current
    // programme has a recognisable title (e.g. "Top Gun: Maverick",
    // "Bluey", "Game of Thrones") we pull the matching backdrop and
    // use it as a big cinematic hero background.  Replaces the
    // channel-logo top-right card the user said felt cluttered on
    // the actual box.
    const [backdrop, setBackdrop] = useState(null);
    const backdropCache = useRef(new Map());
    useEffect(() => {
        const title = (now?.title || '').trim();
        if (!title || title.length < 3) { setBackdrop(null); return; }
        if (backdropCache.current.has(title)) {
            setBackdrop(backdropCache.current.get(title));
            return;
        }
        const ctl = new AbortController();
        const t = setTimeout(async () => {
            try {
                const r = await fetch(
                    `${process.env.REACT_APP_BACKEND_URL}/api/tmdb/livetv-backdrop?q=${encodeURIComponent(title)}`,
                    { signal: ctl.signal },
                );
                if (!r.ok) return;
                const data = await r.json();
                if (ctl.signal.aborted) return;
                const url = data?.backdrop
                    ? `https://image.tmdb.org/t/p/w300${data.backdrop}`
                    : null;
                backdropCache.current.set(title, url);
                setBackdrop(url);
            } catch { /* ignore */ }
        }, 240);  // debounce so D-pad zapping doesn't queue lookups
        return () => { clearTimeout(t); ctl.abort?.(); };
    }, [now?.title]);

    return (
        <section data-testid="live-tv-hero" className="relative" style={{
            padding: '36px 40px 28px 40px',
            minHeight: 280,
            overflow: 'hidden',
            isolation: 'isolate',
        }}>
            {/* Cinematic backdrop — TMDB image of the now-playing
                programme.  Pure CSS — no React Image overhead. */}
            <div style={{
                position: 'absolute', inset: 0, zIndex: -2,
                background: backdrop
                    ? `#0B1322 center/cover no-repeat url(${backdrop})`
                    : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb), 0.18), rgba(11,19,34,0.95))',
                filter: backdrop ? 'saturate(1.05)' : 'none',
            }} />
            {/* Legibility gradients — left to right for the text
                column, top to bottom to bleed into the grid. */}
            <div style={{
                position: 'absolute', inset: 0, zIndex: -1,
                background:
                    'linear-gradient(90deg, rgba(6,8,15,0.96) 0%, rgba(6,8,15,0.78) 40%, rgba(6,8,15,0.35) 70%, rgba(6,8,15,0.55) 100%)',
            }} />
            <div style={{
                position: 'absolute', inset: 0, zIndex: -1,
                background: 'linear-gradient(180deg, rgba(6,8,15,0.55) 0%, transparent 35%, rgba(6,8,15,0.92) 100%)',
            }} />

            <div className="flex items-start justify-between" style={{ gap: 32 }}>
                <div className="flex flex-col" style={{ gap: 10, flex: 1, minWidth: 0 }}>
                    <div className="vesper-mono flex items-center gap-3" style={{
                        fontSize: 11, letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)', textTransform: 'uppercase',
                    }}>
                        LIVE TV{channel?.num != null ? ` · CH ${channel.num}` : ''}
                    </div>
                    <h1 className="vesper-display" style={{
                        fontSize: 'clamp(40px, 4.4vw, 64px)',
                        letterSpacing: '-0.028em',
                        lineHeight: 0.98,
                        color: '#fff',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {channel?.name || 'Live TV'}
                    </h1>
                    {/* NOW row */}
                    <div className="flex items-center gap-3" style={{ marginTop: 6 }}>
                        <span className="vesper-mono" style={{
                            padding: '3px 9px', borderRadius: 4,
                            background: 'rgba(255,68,68,0.20)',
                            border: '1px solid rgba(255,68,68,0.6)',
                            color: '#FF6B6B', fontSize: 10, letterSpacing: '0.22em',
                            fontWeight: 800,
                        }}>
                            NOW
                        </span>
                        <span className="vesper-mono" style={{ fontSize: 12, color: 'var(--vesper-text-2)', letterSpacing: '0.12em' }}>
                            · {formatTime(now?.start)}
                        </span>
                        <span style={{
                            fontSize: 16, color: '#fff',
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                            maxWidth: '54vw',
                        }}>
                            {now?.title || 'No EPG data'}
                        </span>
                    </div>
                    {/* Progress bar */}
                    <div style={{
                        width: 'min(640px, 60vw)', height: 3, borderRadius: 99,
                        background: 'rgba(255,255,255,0.12)', overflow: 'hidden',
                        marginTop: 2,
                    }}>
                        <div style={{ width: `${progressPct}%`, height: '100%', background: 'var(--vesper-blue-bright)' }} />
                    </div>
                    {/* UP NEXT row */}
                    {next && (
                        <div className="vesper-mono flex items-center gap-2" style={{
                            fontSize: 11, color: 'rgba(255,255,255,0.55)',
                            letterSpacing: '0.16em', textTransform: 'uppercase',
                            marginTop: 6,
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                            maxWidth: 'min(640px, 60vw)',
                        }}>
                            UP NEXT · {formatTime(next.start)} · <span style={{ color: 'rgba(255,255,255,0.75)', textTransform: 'none', letterSpacing: 0 }}>{next.title || '—'}</span>
                        </div>
                    )}
                    {/* Watch full-screen */}
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
                            marginTop: 16, height: 56, padding: '0 30px',
                            fontSize: 17, fontWeight: 700,
                            background: '#fff', color: '#0B1322', border: 'none',
                            alignSelf: 'flex-start',
                            opacity: channel ? 1 : 0.5,
                            cursor: channel ? 'pointer' : 'not-allowed',
                        }}
                    >
                        <Play size={18} strokeWidth={2.5} fill="#0B1322" />
                        Watch full-screen
                    </button>
                </div>

                {/* Right side — top action circles ONLY (no channel logo
                    — it was a perf hog on the HK1 because it was a
                    full-size 500×500 PNG decoded just to show at 0.65
                    opacity). */}
                <div className="flex flex-col items-end" style={{ gap: 24, flexShrink: 0 }}>
                    <div className="flex items-center gap-3">
                        <HeroActionCircle
                            testid="hero-fav"
                            label="Favourite"
                            icon={isFav ? <Heart size={18} strokeWidth={2.4} fill="#FF6BCB" color="#FF6BCB" /> : <Star size={18} strokeWidth={2} />}
                            onClick={onFav}
                            highlight={isFav}
                        />
                        <HeroActionCircle
                            testid="hero-refresh"
                            label="Refresh EPG"
                            icon={<RefreshCw size={18} strokeWidth={2} className={refreshing ? 'vesper-spin' : ''} />}
                            onClick={onRefresh}
                        />
                        <HeroActionCircle
                            testid="hero-exit"
                            label="Change provider"
                            icon={<LogOut size={18} strokeWidth={2} />}
                            onClick={onExit}
                        />
                    </div>
                </div>
            </div>
        </section>
    );
}

function HeroActionCircle({ testid, label, icon, onClick, highlight }) {
    return (
        <button
            data-testid={testid}
            data-focusable="true"
            data-focus-style="quiet"
            aria-label={label}
            tabIndex={0}
            onClick={onClick}
            className="flex items-center justify-center rounded-full"
            style={{
                width: 44, height: 44,
                background: highlight ? 'rgba(255,107,203,0.15)' : 'rgba(255,255,255,0.06)',
                border: highlight ? '1px solid rgba(255,107,203,0.6)' : '1px solid rgba(255,255,255,0.14)',
                color: highlight ? '#FF6BCB' : 'var(--vesper-text)',
                cursor: 'pointer',
            }}
        >
            {icon}
        </button>
    );
}

/* ====================== Categories column ====================== */

function CategoriesCol({ cats, favCount, loading, error, activeId, onPick }) {
    return (
        <div data-testid="live-tv-categories" style={{
            padding: '14px 0',
            background: 'rgba(255,255,255,0.02)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: 16,
            maxHeight: 'calc(100dvh - 290px)',
            overflowY: 'auto', contain: 'paint',
        }}>
            {/* Favourites pill (renders from localStorage — no fetch). */}
            <CategoryRow
                testid="live-cat-fav"
                active={activeId === '__fav__'}
                onClick={() => onPick('__fav__')}
                heart
                label="Favourites"
                count={favCount}
            />
            <div style={{ height: 1, background: 'rgba(255,255,255,0.06)', margin: '10px 14px' }} />
            {loading ? (
                <div style={{ padding: '8px 18px', color: 'var(--vesper-text-3)', fontSize: 13, display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                    <Loader2 size={14} className="vesper-spin" /> Loading…
                </div>
            ) : error ? (
                <div style={{ padding: '10px 18px' }}>
                    <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.22em', color: '#FF6B6B', fontWeight: 700, marginBottom: 4 }}>
                        Server unreachable
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--vesper-text-2)', lineHeight: 1.45 }}>
                        Normal in web preview — works on the sideloaded APK.
                    </div>
                </div>
            ) : (
                cats.map((c) => (
                    <CategoryRow
                        key={c.category_id}
                        testid={`live-cat-${c.category_id}`}
                        active={c.category_id === activeId}
                        onClick={() => onPick(c.category_id)}
                        label={c.category_name}
                    />
                ))
            )}
        </div>
    );
}

function CategoryRow({ testid, active, onClick, label, heart, count }) {
    return (
        <button
            data-testid={testid}
            data-focusable="true"
            data-focus-style="quiet"
            tabIndex={0}
            onFocus={onClick}
            onClick={onClick}
            className="text-left flex items-center gap-3"
            style={{
                display: 'flex', width: 'calc(100% - 10px)', margin: '0 5px',
                padding: '10px 14px',
                background: active ? 'rgba(var(--vesper-blue-rgb), 0.16)' : 'transparent',
                borderLeft: active ? '3px solid var(--vesper-blue-bright)' : '3px solid transparent',
                borderTop: 'none', borderRight: 'none', borderBottom: 'none',
                borderRadius: 8,
                color: active ? '#fff' : 'var(--vesper-text-2)',
                fontSize: 13.5, fontWeight: active ? 700 : 500,
                cursor: 'pointer',
            }}
        >
            {heart && <Heart size={14} strokeWidth={2} fill={active ? '#FF6BCB' : 'transparent'} color="#FF6BCB" />}
            <span style={{ flex: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {label}
            </span>
            {count != null && (
                <span className="vesper-mono" style={{
                    fontSize: 10, color: 'var(--vesper-text-3)',
                    background: 'rgba(255,255,255,0.05)', borderRadius: 99,
                    padding: '1px 8px', minWidth: 22, textAlign: 'center',
                }}>
                    {count}
                </span>
            )}
        </button>
    );
}

/* ====================== Channels column ====================== */

function ChannelsCol({ channels, loading, nowNextCache, focusedId, onFocus, onPlay }) {
    // Windowed render — Chrome 52 (HK1) does NOT support
    // `content-visibility: auto` so a 1000-row provider would
    // render 1000 button DOMs on every paint, killing the box.
    // We start with 60 rows and grow by 60 each time a sentinel
    // <li> at the end intersects with the viewport (works on
    // Chrome 52 — IntersectionObserver is supported from 51).
    const STEP = 60;
    const [visibleCount, setVisibleCount] = useState(STEP);
    const sentinelRef = useRef(null);
    const containerRef = useRef(null);

    // Reset window when the channel list itself changes (new category).
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
            { root: containerRef.current, rootMargin: '300px' },
        );
        obs.observe(sentinelRef.current);
        return () => obs.disconnect();
    }, [channels.length, visibleCount]);

    const visible = useMemo(
        () => channels.slice(0, visibleCount),
        [channels, visibleCount],
    );

    return (
        <div data-testid="live-tv-channels" ref={containerRef} style={{
            padding: '12px 6px',
            background: 'rgba(255,255,255,0.018)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: 16,
            maxHeight: 'calc(100dvh - 300px)',
            overflowY: 'auto',
            contain: 'strict',
        }}>
            {loading ? (
                <div style={{ padding: '12px', color: 'var(--vesper-text-3)', fontSize: 13, display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                    <Loader2 size={14} className="vesper-spin" /> Loading channels…
                </div>
            ) : channels.length === 0 ? (
                <div style={{ padding: '20px 12px', color: 'var(--vesper-text-3)', fontSize: 13 }}>
                    No channels.
                </div>
            ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {visible.map((c) => (
                        <ChannelRow
                            key={c.stream_id}
                            channel={c}
                            epg={nowNextCache.get(c.stream_id)}
                            focused={c.stream_id === focusedId}
                            onFocus={() => onFocus(c)}
                            onPlay={() => { onFocus(c); onPlay(c); }}
                        />
                    ))}
                    {/* Sentinel — when this scrolls into view we
                        grow the render window by STEP. */}
                    {visibleCount < channels.length && (
                        <li
                            ref={sentinelRef}
                            aria-hidden="true"
                            style={{
                                height: 1, margin: 0, padding: 0,
                                pointerEvents: 'none',
                            }}
                        />
                    )}
                </ul>
            )}
        </div>
    );
}

function ChannelRow({ channel, epg, focused, onFocus, onPlay }) {
    const now = epg?.now;
    // Only compute the live progress for the FOCUSED row.  On the
    // HK1 box, having every visible row tick a progress bar 1×/sec
    // was a measurable paint hog — for unfocused rows we just show
    // the "NOW · title" line.
    const progress = useMemo(() => {
        if (!focused || !now?.startTimestamp || !now?.stopTimestamp) return 0;
        const s = Number(now.startTimestamp) * 1000;
        const e = Number(now.stopTimestamp) * 1000;
        const t = Date.now();
        if (t <= s) return 0;
        if (t >= e) return 100;
        return Math.round(((t - s) / (e - s)) * 100);
    }, [now, focused]);
    return (
        <li style={{ contentVisibility: 'auto', containIntrinsicSize: '0 86px' }}>
            <button
                data-testid={`live-channel-${channel.stream_id}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onFocus={onFocus}
                onClick={onPlay}
                className="text-left flex items-center gap-3"
                style={{
                    width: 'calc(100% - 14px)', margin: '4px 7px',
                    padding: '12px 14px',
                    background: focused ? 'rgba(var(--vesper-blue-rgb), 0.08)' : 'transparent',
                    border: focused
                        ? '1px solid rgba(var(--vesper-blue-rgb), 0.7)'
                        : '1px solid rgba(255,255,255,0.05)',
                    borderRadius: 12,
                    color: 'var(--vesper-text)',
                    cursor: 'pointer',
                    minHeight: 74,
                }}
            >
                {channel.num != null && (
                    <div className="vesper-mono" style={{
                        fontSize: 13, color: 'var(--vesper-text-3)',
                        minWidth: 30, textAlign: 'right', fontWeight: 700,
                    }}>
                        {channel.num}
                    </div>
                )}
                <div style={{
                    width: 48, height: 32, flexShrink: 0, borderRadius: 5,
                    background: 'rgba(255,255,255,0.05)',
                    border: '1px solid rgba(255,255,255,0.06)',
                    overflow: 'hidden',
                    position: 'relative',
                }}>
                    {channel.stream_icon && (
                            <img
                                src={proxiedLogo(channel.stream_icon, 48)}
                                alt=""
                                width={48}
                                height={32}
                                loading="lazy"
                                decoding="async"
                                referrerPolicy="no-referrer"
                                onError={(e) => { e.currentTarget.style.visibility = 'hidden'; }}
                                style={{
                                    position: 'absolute', inset: 0,
                                    width: '100%', height: '100%',
                                    objectFit: 'contain',
                                    imageRendering: 'auto',
                                }}
                            />
                    )}
                </div>
                <div style={{ minWidth: 0, flex: 1 }}>
                    <div style={{
                        fontSize: 15, fontWeight: 700, lineHeight: 1.2,
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {channel.name}
                    </div>
                    {now ? (
                        <>
                            <div className="flex items-center gap-2" style={{ marginTop: 4 }}>
                                <span className="vesper-mono" style={{
                                    padding: '1px 7px', borderRadius: 3,
                                    background: 'rgba(255,68,68,0.18)',
                                    border: '1px solid rgba(255,68,68,0.55)',
                                    color: '#FF6B6B', fontSize: 9, letterSpacing: '0.18em',
                                    fontWeight: 800,
                                }}>NOW</span>
                                <span style={{
                                    fontSize: 12, color: 'var(--vesper-text-2)',
                                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                    flex: 1, minWidth: 0,
                                }}>
                                    {now.title || '—'}
                                </span>
                            </div>
                            {focused && (
                                <div style={{
                                    width: '100%', height: 2, marginTop: 6,
                                    background: 'rgba(255,255,255,0.08)', borderRadius: 99, overflow: 'hidden',
                                }}>
                                    <div style={{ width: `${progress}%`, height: '100%', background: 'var(--vesper-blue-bright)' }} />
                                </div>
                            )}
                        </>
                    ) : (
                        <div style={{ fontSize: 11, color: 'var(--vesper-text-3)', marginTop: 4 }}>—</div>
                    )}
                </div>
            </button>
        </li>
    );
}

/* ============================ Guide ============================ */

function GuideCol({ providerId, channel, epg }) {
    const items = epg?.items || [];
    // Group by day (today / tomorrow / weekday names).  Computed
    // unconditionally so the hook order stays stable even when we
    // render the "Pick a channel" placeholder below.
    const groups = useMemo(() => {
        const m = new Map();
        const today = new Date(); today.setHours(0, 0, 0, 0);
        const tomorrow = new Date(today.getTime() + 86400000);
        items.forEach((it) => {
            const ts = Number(it.startTimestamp || it.start_timestamp || 0) * 1000;
            const d = new Date(ts); d.setHours(0, 0, 0, 0);
            let label;
            if (d.getTime() === today.getTime()) label = 'TODAY';
            else if (d.getTime() === tomorrow.getTime()) label = 'TOMORROW';
            else label = d.toLocaleDateString(undefined, { weekday: 'long' }).toUpperCase();
            const key = `${d.getTime()}|${label}`;
            (m.get(key) || m.set(key, { label, items: [] }).get(key)).items.push(it);
        });
        return [...m.values()];
    }, [items]);

    if (!channel) {
        return (
            <div data-testid="live-tv-guide" style={{
                padding: '18px 18px',
                background: 'rgba(255,255,255,0.02)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 16,
                color: 'var(--vesper-text-3)', textAlign: 'center', fontSize: 13,
            }}>
                <Radio size={22} strokeWidth={1.5} style={{ display: 'block', margin: '14px auto 8px', color: 'var(--vesper-text-3)' }} />
                Pick a channel to see what's coming up.
            </div>
        );
    }

    return (
        <div data-testid="live-tv-guide" style={{
            padding: '14px 12px',
            background: 'rgba(255,255,255,0.02)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: 16,
            maxHeight: 'calc(100dvh - 290px)',
            overflowY: 'auto', contain: 'paint',
        }}>
            <div className="flex items-center gap-2" style={{ padding: '0 4px 10px' }}>
                <CalendarDays size={14} strokeWidth={2} color="var(--vesper-blue-bright)" />
                <div className="vesper-mono" style={{
                    fontSize: 10, letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)', textTransform: 'uppercase',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    GUIDE · {channel.name}
                </div>
            </div>
            {items.length === 0 ? (
                <div style={{ padding: '20px 6px', color: 'var(--vesper-text-3)', fontSize: 13, textAlign: 'center' }}>
                    <Loader2 size={14} className="vesper-spin" style={{ display: 'block', margin: '0 auto 8px' }} />
                    Loading guide…
                </div>
            ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {groups.map((g) => (
                        <li key={g.label}>
                            <div className="vesper-mono" style={{
                                fontSize: 10, letterSpacing: '0.32em',
                                color: 'var(--vesper-text-3)', textTransform: 'uppercase',
                                padding: '14px 6px 6px',
                            }}>
                                {g.label}
                            </div>
                            {g.items.map((it, i) => (
                                <GuideRow
                                    key={`${it.startTimestamp || i}`}
                                    item={it}
                                    channel={channel}
                                    providerId={providerId}
                                />
                            ))}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

function GuideRow({ item, channel, providerId }) {
    const startTs = Number(item.startTimestamp || item.start_timestamp || 0);
    const set = hasReminder(providerId, channel.stream_id, startTs);
    const onToggle = () => {
        toggleReminder(providerId, {
            streamId: channel.stream_id,
            startTs,
            stopTs: Number(item.stopTimestamp || item.stop_timestamp || 0),
            title: item.title || '',
            channelName: channel.name,
        });
    };
    return (
        <li style={{ contentVisibility: 'auto', containIntrinsicSize: '0 58px' }}>
            <button
                data-testid={`guide-row-${channel.stream_id}-${startTs}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={onToggle}
                className="text-left flex items-center gap-3"
                style={{
                    display: 'flex', width: 'calc(100% - 10px)', margin: '4px 5px',
                    padding: '10px 12px',
                    background: set ? 'rgba(255,196,68,0.06)' : 'transparent',
                    border: set
                        ? '1px solid rgba(255,196,68,0.55)'
                        : '1px solid rgba(255,255,255,0.06)',
                    borderRadius: 10,
                    color: 'var(--vesper-text)',
                    cursor: 'pointer',
                    minHeight: 48,
                }}
            >
                <div style={{ minWidth: 54, lineHeight: 1.05 }}>
                    <div className="vesper-mono" style={{
                        fontSize: 13, fontWeight: 800,
                        color: set ? '#FFC444' : 'var(--vesper-text)',
                        letterSpacing: '0.03em',
                    }}>
                        {formatTimeParts(item.start, startTs).hm}
                    </div>
                    <div className="vesper-mono" style={{
                        fontSize: 10, fontWeight: 700,
                        color: set ? '#FFC444' : 'var(--vesper-text-3)',
                        letterSpacing: '0.18em', marginTop: 1,
                    }}>
                        {formatTimeParts(item.start, startTs).ap}
                    </div>
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{
                        fontSize: 13, fontWeight: 600,
                        color: set ? '#FFE2A8' : 'var(--vesper-text)',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {item.title || 'Untitled'}
                    </div>
                    <div className="vesper-mono" style={{
                        fontSize: 9, letterSpacing: '0.22em',
                        color: set ? '#FFC444' : 'var(--vesper-text-3)',
                        textTransform: 'uppercase', marginTop: 2, fontWeight: 600,
                    }}>
                        {set ? (
                            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                <Check size={9} strokeWidth={3} /> REMINDER SET
                            </span>
                        ) : (
                            'OK TO REMIND'
                        )}
                    </div>
                </div>
                {set
                    ? <BellRing size={14} strokeWidth={2.2} color="#FFC444" />
                    : <Bell size={14} strokeWidth={2} color="var(--vesper-text-3)" />}
            </button>
        </li>
    );
}

/* ============================ Helpers ============================ */

/**
 * Route a remote image URL through our backend proxy that resizes
 * it to a tiny WebP.  The HK1's WebView spends most of its image
 * budget DECODING — even a 56-px-wide logo slot decodes a full
 * 500×500 PNG when the source URL points to one.  Routing through
 * /api/img-proxy?w=64 means the WebView only ever decodes a ~3 KB
 * WebP, which is dramatically faster on a Mali T-820 GPU.
 *
 * Returns the original URL when the proxy is unavailable (e.g.
 * REACT_APP_BACKEND_URL is missing) so we never end up with
 * broken images.
 */
function proxiedLogo(url, width = 48) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    if (!base) return url;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${width}&q=55`;
}

function formatTime(s) {
    if (!s) return '';
    const m = /\d{4}-\d{2}-\d{2}[ T](\d{2}):(\d{2})/.exec(s);
    if (m) {
        let h = parseInt(m[1], 10);
        const min = m[2];
        const ap = h >= 12 ? 'PM' : 'AM';
        h = h % 12; if (h === 0) h = 12;
        return `${String(h).padStart(2, '0')}:${min} ${ap}`;
    }
    try {
        return new Date(s).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    } catch { return s; }
}

function formatTimeParts(s, ts) {
    let date;
    if (s) {
        const m = /(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/.exec(s);
        if (m) {
            date = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]));
        }
    }
    if (!date && ts) date = new Date(Number(ts) * 1000);
    if (!date) return { hm: '', ap: '' };
    let h = date.getHours();
    const min = String(date.getMinutes()).padStart(2, '0');
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12; if (h === 0) h = 12;
    return { hm: `${String(h).padStart(2, '0')}:${min}`, ap };
}

function formatTimeFull() { return ''; }
