import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, LogOut } from 'lucide-react';
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
} from '@/lib/xtream';
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

    /* Category switch — pure cache lookup, no fetch */
    const pickCategory = useCallback((catId) => {
        if (!catId) return;
        setActiveCat(catId);
        const list = channelsCache.current.get(catId) || [];
        setChannels(list);
        setFocusedChannel(list[0] || null);
    }, []);

    const playChannel = useCallback(async (channel) => {
        if (!channel) return;
        const url = await getStreamUrl(provider, 'live', channel.stream_id, 'ts');
        if (!url) return;
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

    if (!booted) {
        return <LiveTVBoot stages={stages} />;
    }

    return (
        <div>
            <LiveHeroLean
                channel={focusedChannel}
                categoryName={activeCategoryName}
                onPlay={() => playChannel(focusedChannel)}
                onExit={onChangeProvider}
            />
            <div className="grid" style={{
                gridTemplateColumns: 'minmax(220px, 260px) minmax(0, 1fr)',
                gap: 16,
                padding: '14px 32px 0 32px',
                alignItems: 'start',
            }}>
                <CategoriesCol
                    cats={cats}
                    error={catsError}
                    activeId={activeCat}
                    onPick={pickCategory}
                />
                <ChannelsCol
                    channels={channels}
                    focusedId={focusedChannel?.stream_id}
                    onFocus={setFocusedChannel}
                    onPlay={playChannel}
                />
            </div>
        </div>
    );
}

/* ============================ Hero (lean) ============================ */

function LiveHeroLean({ channel, categoryName, onPlay, onExit }) {
    const logoSrc = channel?.stream_icon ? proxiedLogo(channel.stream_icon, 200) : '';
    const eyebrowParts = ['LIVE TV'];
    if (channel?.num != null) eyebrowParts.push(`CH ${channel.num}`);
    if (categoryName) eyebrowParts.push(categoryName.toUpperCase());

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
                            marginTop: 10, height: 48, padding: '0 24px',
                            fontSize: 14, fontWeight: 700,
                            background: '#fff', color: '#0B1322', border: 'none',
                            alignSelf: 'flex-start',
                            opacity: channel ? 1 : 0.5,
                            cursor: channel ? 'pointer' : 'not-allowed',
                        }}
                    >
                        <Play size={15} strokeWidth={2.5} fill="#0B1322" />
                        Watch full-screen
                    </button>
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
                        width: 44, height: 44, alignSelf: 'flex-start',
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.14)',
                        color: 'var(--vesper-text)',
                    }}
                >
                    <LogOut size={18} strokeWidth={2} />
                </button>
            </div>
        </section>
    );
}

/* ============================ Columns ============================ */

function CategoriesCol({ cats, error, activeId, onPick }) {
    return (
        <div data-testid="live-tv-categories" style={{
            padding: '12px 0',
            background: 'rgba(255,255,255,0.02)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: 14,
            maxHeight: 'calc(100dvh - 250px)',
            overflowY: 'auto',
        }}>
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
                    </button>
                );
            })}
        </div>
    );
}

function ChannelsCol({ channels, focusedId, onFocus, onPlay }) {
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

    return (
        <div
            data-testid="live-tv-channels"
            ref={containerRef}
            style={{
                padding: '8px 6px',
                background: 'rgba(255,255,255,0.018)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 14,
                maxHeight: 'calc(100dvh - 250px)',
                overflowY: 'auto',
            }}
        >
            {channels.length === 0 ? (
                <div style={{ padding: '20px 12px', color: 'var(--vesper-text-3)', fontSize: 13 }}>
                    No channels.
                </div>
            ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {visible.map((c) => (
                        <ChannelRowLean
                            key={c.stream_id}
                            channel={c}
                            focused={c.stream_id === focusedId}
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
    );
}

/* ============================ Channel Row (lean) ============================ */

function ChannelRowLean({ channel, focused, onFocus, onPlay }) {
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
            </button>
        </li>
    );
}

/* ============================ Helpers ============================ */

function proxiedLogo(url, width = 36) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    if (!base) return url;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${width}&q=50`;
}
