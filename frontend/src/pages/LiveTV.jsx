import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Tv, Radio, Play, Loader2, ChevronRight, Clock, Settings } from 'lucide-react';
import SideNav from '@/components/SideNav';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import XtreamLogin from '@/components/XtreamLogin';
import {
    getActiveProvider,
    getCategories,
    getStreams,
    getNowNext,
    getStreamUrl,
} from '@/lib/xtream';
import Host from '@/lib/host';

/* ====================================================================
 * /live-tv — Xtream Codes live TV browser.
 *
 * Architecture:
 *   1. Provider gate:   if no Xtream provider is configured, render
 *      <XtreamLogin /> wizard.  Otherwise jump straight to the grid.
 *   2. Hero rotator:    cycles through a small handful of "featured"
 *      channels (heuristic: channels with EPG data + a non-trivial
 *      logo).  Backdrop is the EPG show's TMDB-like backdrop when
 *      we have one, otherwise the channel's stream_icon.
 *   3. Three-column body:
 *        L  — Categories list (~240 px)
 *        M  — Channel list for the focused category (~520 px)
 *        R  — NOW / NEXT for the focused channel (~360 px)
 *
 * Performance: no glow / blur / scale animations (those killed the
 * previous build).  `content-visibility: auto` on each channel tile
 * so the browser skips paint work for off-screen rows even when the
 * category has 1000+ channels.  EPG fetches are AbortController-
 * cancelled when the user zaps through channels so we never stack
 * stale requests.
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
        <div
            data-testid="live-tv-page"
            className="relative w-screen"
            style={{
                minHeight: '100dvh',
                background: 'var(--vesper-bg-0)',
                overflowX: 'hidden',
            }}
        >
            <SideNav />
            <main
                style={{
                    marginLeft: 100,
                    minHeight: '100dvh',
                    padding: view === 'grid' ? '0 0 64px 0' : '60px 64px 80px 64px',
                }}
            >
                {view === 'login' ? (
                    <XtreamLogin onAuthed={onAuthed} onCancel={() => navigate('/')} />
                ) : (
                    <LiveTVGrid
                        provider={provider}
                        onChangeProvider={() => setView('login')}
                    />
                )}
            </main>
        </div>
    );
}

/* ============================ Grid ============================ */

function LiveTVGrid({ provider, onChangeProvider }) {
    // Categories + channels
    const [cats, setCats] = useState([]);
    const [catsLoading, setCatsLoading] = useState(true);
    const [activeCat, setActiveCat] = useState(null); // category_id, '*'=all favourites, null=loading
    const [channels, setChannels] = useState([]);
    const [channelsLoading, setChannelsLoading] = useState(false);
    const [focusedChannel, setFocusedChannel] = useState(null);

    // EPG cache: stream_id -> { now, next, fetchedAt }
    const epgCache = useRef(new Map());
    const epgAbort = useRef(null);
    const [epgVersion, setEpgVersion] = useState(0); // bump to re-render after cache update

    const navigate = useNavigate();

    // Fetch categories once
    const [catsError, setCatsError] = useState('');
    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                setCatsLoading(true);
                setCatsError('');
                const list = await getCategories(provider, 'live');
                if (cancel) return;
                setCats(Array.isArray(list) ? list : []);
                if (list?.length) setActiveCat(list[0].category_id);
            } catch (e) {
                console.warn('Failed to load categories', e);
                if (!cancel) setCatsError(
                    e?.response?.data?.detail ||
                    e?.message ||
                    'Could not reach your IPTV server.'
                );
            } finally {
                if (!cancel) setCatsLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, [provider]);

    // Fetch channels when category changes
    useEffect(() => {
        if (!activeCat) return undefined;
        let cancel = false;
        (async () => {
            try {
                setChannelsLoading(true);
                const list = await getStreams(provider, 'live', activeCat);
                if (cancel) return;
                setChannels(Array.isArray(list) ? list : []);
                if (list?.length) setFocusedChannel(list[0]);
                else setFocusedChannel(null);
            } catch (e) {
                console.warn('Failed to load streams', e);
            } finally {
                if (!cancel) setChannelsLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, [provider, activeCat]);

    // Fetch NOW/NEXT for focused channel (debounced + abortable).
    useEffect(() => {
        if (!focusedChannel) return undefined;
        const sid = focusedChannel.stream_id;
        const cached = epgCache.current.get(sid);
        const fresh = cached && Date.now() - cached.fetchedAt < 60_000;
        if (fresh) return undefined;
        if (epgAbort.current) epgAbort.current.abort?.();
        const ctl = new AbortController();
        epgAbort.current = ctl;
        const t = setTimeout(async () => {
            try {
                const items = await getNowNext(provider, sid);
                if (ctl.signal.aborted) return;
                epgCache.current.set(sid, {
                    now: items[0] || null,
                    next: items[1] || null,
                    fetchedAt: Date.now(),
                });
                setEpgVersion((v) => v + 1);
            } catch { /* ignore */ }
        }, 180);
        return () => { clearTimeout(t); ctl.abort?.(); };
    }, [focusedChannel, provider]);

    const playChannel = useCallback(async (channel) => {
        if (!channel) return;
        try {
            const r = await getStreamUrl(provider, 'live', channel.stream_id, 'ts');
            if (!r) return;
            const ep = epgCache.current.get(channel.stream_id);
            const playUrl = r;
            const title = `${channel.name}${ep?.now?.title ? ` · ${ep.now.title}` : ''}`;
            // libVLC on the box; JS player in the preview
            if (
                Host.playVideo({
                    url: playUrl,
                    title,
                    type: 'live',
                    poster: channel.stream_icon || '',
                    backdrop: channel.stream_icon || '',
                    synopsis: ep?.now?.description || '',
                    year: '',
                    rating: '',
                    runtime: '',
                    genres: [],
                    cwId: `live:${provider.id}:${channel.stream_id}`,
                })
            ) return;
            navigate(`/play?url=${encodeURIComponent(playUrl)}&title=${encodeURIComponent(title)}&type=live`);
        } catch (e) {
            console.warn('Failed to play channel', e);
        }
    }, [provider, navigate]);

    const focusedEpg = focusedChannel ? epgCache.current.get(focusedChannel.stream_id) : null;

    // Pick a hero channel — first channel whose EPG we have cached, fall back to focused
    const heroChannel = useMemo(() => {
        for (const [sid, ep] of epgCache.current.entries()) {
            if (!ep?.now) continue;
            const c = channels.find((ch) => ch.stream_id === sid);
            if (c) return { channel: c, epg: ep };
        }
        return focusedChannel ? { channel: focusedChannel, epg: focusedEpg } : null;
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [focusedChannel, focusedEpg, channels, epgVersion]);

    return (
        <div>
            {/* Hero */}
            <LiveHero
                channel={heroChannel?.channel}
                epg={heroChannel?.epg}
                provider={provider}
                onChangeProvider={onChangeProvider}
                onPlay={() => playChannel(heroChannel?.channel)}
            />

            {/* Three-column body */}
            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'minmax(220px, 240px) minmax(0, 1fr) minmax(320px, 360px)',
                    gap: 22,
                    padding: '24px 40px 0 40px',
                    alignItems: 'start',
                }}
            >
                <CategoriesCol
                    cats={cats}
                    loading={catsLoading}
                    error={catsError}
                    activeId={activeCat}
                    onPick={setActiveCat}
                />
                <ChannelsCol
                    channels={channels}
                    loading={channelsLoading}
                    epgCache={epgCache.current}
                    epgVersion={epgVersion}
                    focusedId={focusedChannel?.stream_id}
                    onFocus={setFocusedChannel}
                    onPlay={playChannel}
                />
                <NowNextCol
                    channel={focusedChannel}
                    epg={focusedEpg}
                    onPlay={() => playChannel(focusedChannel)}
                />
            </div>
        </div>
    );
}

/* ============================ Hero ============================ */

function LiveHero({ channel, epg, provider, onChangeProvider, onPlay }) {
    const now = epg?.now;
    const backdrop = channel?.stream_icon || '';
    const progressPct = useMemo(() => {
        if (!now?.startTimestamp || !now?.stopTimestamp) return 0;
        const start = Number(now.startTimestamp) * 1000;
        const end = Number(now.stopTimestamp) * 1000;
        const t = Date.now();
        if (t <= start) return 0;
        if (t >= end) return 100;
        return Math.round(((t - start) / (end - start)) * 100);
    }, [now]);
    const minsLeft = useMemo(() => {
        if (!now?.stopTimestamp) return null;
        const end = Number(now.stopTimestamp) * 1000;
        const left = Math.max(0, Math.round((end - Date.now()) / 60000));
        return left;
    }, [now]);

    return (
        <section
            data-testid="live-tv-hero"
            className="relative"
            style={{
                height: 'min(46vh, 460px)',
                overflow: 'hidden',
                background: 'var(--vesper-bg-0)',
            }}
        >
            {/* Backdrop */}
            <div
                style={{
                    position: 'absolute', inset: 0,
                    background: backdrop
                        ? `center/cover no-repeat url(${backdrop})`
                        : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb), 0.4), rgba(11,19,34,0.9))',
                    filter: 'saturate(1.05)',
                }}
            />
            {/* Cinematic gradients — left for legibility, right vignette */}
            <div style={{
                position: 'absolute', inset: 0,
                background:
                    'linear-gradient(90deg, rgba(6,8,15,0.95) 0%, rgba(6,8,15,0.6) 35%, rgba(6,8,15,0.2) 65%, rgba(6,8,15,0.85) 100%)',
            }} />
            <div style={{
                position: 'absolute', inset: 0,
                background: 'linear-gradient(180deg, rgba(6,8,15,0.5) 0%, transparent 30%, rgba(6,8,15,0.95) 100%)',
            }} />

            <div
                className="absolute"
                style={{
                    left: 40, right: 40, bottom: 30, top: 30,
                    display: 'flex', flexDirection: 'column', justifyContent: 'space-between',
                }}
            >
                {/* Top row — provider chip */}
                <div className="flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <span className="flex items-center gap-2" style={{
                            padding: '6px 12px', borderRadius: 999,
                            background: 'rgba(255,68,68,0.16)',
                            border: '1px solid rgba(255,68,68,0.55)',
                            color: '#FF6B6B', fontSize: 11, letterSpacing: '0.24em',
                            fontWeight: 700, textTransform: 'uppercase',
                            fontFamily: 'var(--theme-font-mono, monospace)',
                        }}>
                            <span style={{ width: 8, height: 8, borderRadius: '50%', background: '#FF6B6B', animation: 'vesperPulse 1500ms infinite' }} />
                            LIVE NOW
                        </span>
                        {channel?.name && (
                            <span style={{
                                fontSize: 12, color: 'var(--vesper-text-2)',
                                fontFamily: 'var(--theme-font-mono, monospace)',
                                letterSpacing: '0.22em', textTransform: 'uppercase',
                            }}>
                                {channel.name}
                            </span>
                        )}
                    </div>
                    <button
                        data-testid="live-tv-change-provider"
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onClick={onChangeProvider}
                        className="flex items-center gap-2 rounded-full vesper-mono"
                        style={{
                            height: 34, padding: '0 14px', fontSize: 10,
                            letterSpacing: '0.22em', textTransform: 'uppercase',
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.14)',
                            color: 'var(--vesper-text)', cursor: 'pointer', fontWeight: 600,
                        }}
                    >
                        <Settings size={12} strokeWidth={2.2} />
                        {provider?.name || 'Provider'}
                    </button>
                </div>

                {/* Bottom block — show title + meta + actions */}
                <div className="flex flex-col" style={{ gap: 12, maxWidth: '60ch' }}>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 10, letterSpacing: '0.32em',
                            color: 'var(--vesper-blue-bright)', textTransform: 'uppercase',
                        }}
                    >
                        Now Showing
                    </div>
                    <h1
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(32px, 4vw, 56px)',
                            letterSpacing: '-0.025em',
                            lineHeight: 1.0,
                            color: '#fff',
                            textShadow: '0 2px 18px rgba(0,0,0,0.55)',
                        }}
                    >
                        {now?.title || channel?.name || 'Live TV'}
                    </h1>
                    {now?.start && now?.end && (
                        <div className="flex items-center gap-3 vesper-mono" style={{
                            fontSize: 11, color: 'var(--vesper-text-2)',
                            letterSpacing: '0.16em', textTransform: 'uppercase',
                        }}>
                            <Clock size={12} strokeWidth={2} />
                            {formatTime(now.start)} – {formatTime(now.end)}
                            {minsLeft != null && minsLeft > 0 && (
                                <span style={{ color: 'var(--vesper-blue-bright)' }}>
                                    · {minsLeft} min left
                                </span>
                            )}
                        </div>
                    )}
                    {progressPct > 0 && (
                        <div style={{
                            width: 280, height: 4, borderRadius: 99,
                            background: 'rgba(255,255,255,0.10)', overflow: 'hidden',
                        }}>
                            <div style={{
                                width: `${progressPct}%`, height: '100%',
                                background: 'var(--vesper-blue-bright)',
                            }} />
                        </div>
                    )}
                    {now?.description && (
                        <p style={{
                            fontSize: 14, color: 'rgba(255,255,255,0.85)',
                            lineHeight: 1.5, maxWidth: '52ch',
                            display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                            overflow: 'hidden',
                        }}>
                            {now.description}
                        </p>
                    )}
                    <div className="flex items-center gap-3" style={{ marginTop: 4 }}>
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
                                height: 50, padding: '0 26px', fontSize: 15, fontWeight: 700,
                                background: '#fff', color: '#0B1322', border: 'none',
                                opacity: channel ? 1 : 0.5,
                                cursor: channel ? 'pointer' : 'not-allowed',
                            }}
                        >
                            <Play size={16} strokeWidth={2.5} fill="#0B1322" />
                            Watch live
                        </button>
                    </div>
                </div>
            </div>
        </section>
    );
}

/* ====================== Categories column ====================== */

function CategoriesCol({ cats, loading, error, activeId, onPick }) {
    return (
        <div
            data-testid="live-tv-categories"
            style={{
                padding: '16px 4px 16px 4px',
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 18,
                maxHeight: 'calc(100dvh - 46vh - 60px)',
                overflowY: 'auto',
                contain: 'paint',
            }}
        >
            <div className="vesper-mono" style={{
                fontSize: 10, letterSpacing: '0.32em',
                color: 'var(--vesper-text-3)', textTransform: 'uppercase',
                padding: '0 18px 12px',
            }}>
                Categories
            </div>
            {loading ? (
                <div style={{ padding: '8px 18px', color: 'var(--vesper-text-3)', fontSize: 13, display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                    <Loader2 size={14} className="vesper-spin" /> Loading…
                </div>
            ) : error ? (
                <div style={{ padding: '12px 18px' }}>
                    <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.22em', color: '#FF6B6B', textTransform: 'uppercase', fontWeight: 700, marginBottom: 6 }}>
                        Server unreachable
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--vesper-text-2)', lineHeight: 1.4 }}>
                        Could not reach your IPTV server.  This is normal in the
                        web preview — Live TV works fully on the sideloaded APK,
                        which makes requests through the native layer instead of
                        the browser.
                    </div>
                </div>
            ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {cats.map((c) => (
                        <li key={c.category_id}>
                            <button
                                data-testid={`live-cat-${c.category_id}`}
                                data-focusable="true"
                                data-focus-style="quiet"
                                tabIndex={0}
                                onFocus={() => onPick(c.category_id)}
                                onClick={() => onPick(c.category_id)}
                                className="text-left"
                                style={{
                                    display: 'block',
                                    width: 'calc(100% - 8px)',
                                    margin: '0 4px',
                                    padding: '11px 14px',
                                    background: c.category_id === activeId
                                        ? 'rgba(var(--vesper-blue-rgb), 0.16)'
                                        : 'transparent',
                                    borderLeft: c.category_id === activeId
                                        ? '3px solid var(--vesper-blue-bright)'
                                        : '3px solid transparent',
                                    borderTop: 'none', borderRight: 'none', borderBottom: 'none',
                                    color: c.category_id === activeId
                                        ? 'var(--vesper-text)'
                                        : 'var(--vesper-text-2)',
                                    fontSize: 13,
                                    fontWeight: c.category_id === activeId ? 700 : 500,
                                    cursor: 'pointer',
                                    whiteSpace: 'nowrap',
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    borderRadius: 0,
                                }}
                            >
                                {c.category_name}
                            </button>
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

/* ====================== Channels column ====================== */

function ChannelsCol({ channels, loading, epgCache, epgVersion, focusedId, onFocus, onPlay }) {
    // Note: epgVersion is in deps so this re-renders when EPG cache updates
    void epgVersion;
    return (
        <div
            data-testid="live-tv-channels"
            style={{
                padding: '14px 6px',
                background: 'rgba(255,255,255,0.02)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 18,
                maxHeight: 'calc(100dvh - 46vh - 60px)',
                overflowY: 'auto',
                contain: 'paint',
            }}
        >
            <div className="flex items-center justify-between" style={{ padding: '0 14px 10px' }}>
                <div className="vesper-mono" style={{
                    fontSize: 10, letterSpacing: '0.32em',
                    color: 'var(--vesper-text-3)', textTransform: 'uppercase',
                }}>
                    Channels{!loading && channels.length > 0 ? ` · ${channels.length}` : ''}
                </div>
            </div>
            {loading ? (
                <div style={{ padding: '8px 14px', color: 'var(--vesper-text-3)', fontSize: 13, display: 'inline-flex', gap: 8, alignItems: 'center' }}>
                    <Loader2 size={14} className="vesper-spin" /> Loading…
                </div>
            ) : channels.length === 0 ? (
                <div style={{ padding: '20px 14px', color: 'var(--vesper-text-3)', fontSize: 13 }}>
                    No channels in this category.
                </div>
            ) : (
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {channels.map((c) => (
                        <ChannelRow
                            key={c.stream_id}
                            channel={c}
                            epg={epgCache.get(c.stream_id)}
                            focused={c.stream_id === focusedId}
                            onFocus={() => onFocus(c)}
                            onPlay={() => onPlay(c)}
                        />
                    ))}
                </ul>
            )}
        </div>
    );
}

function ChannelRow({ channel, epg, focused, onFocus, onPlay }) {
    const now = epg?.now;
    return (
        <li style={{ contentVisibility: 'auto', containIntrinsicSize: '0 62px' }}>
            <button
                data-testid={`live-channel-${channel.stream_id}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onFocus={onFocus}
                onClick={() => { onFocus(); onPlay(); }}
                className="text-left flex items-center gap-3"
                style={{
                    width: 'calc(100% - 12px)',
                    margin: '2px 6px',
                    padding: '8px 10px',
                    background: focused
                        ? 'rgba(var(--vesper-blue-rgb), 0.18)'
                        : 'transparent',
                    border: focused
                        ? '1px solid rgba(var(--vesper-blue-rgb), 0.6)'
                        : '1px solid rgba(255,255,255,0.04)',
                    color: 'var(--vesper-text)',
                    borderRadius: 12,
                    cursor: 'pointer',
                    minHeight: 56,
                }}
            >
                <div style={{
                    width: 56, height: 38, flexShrink: 0,
                    borderRadius: 6,
                    background: channel.stream_icon
                        ? `#0a0d18 center/contain no-repeat url(${channel.stream_icon})`
                        : 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.06)',
                }} />
                <div style={{ minWidth: 0, flex: 1 }}>
                    <div style={{
                        fontSize: 14, fontWeight: 700,
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        lineHeight: 1.2,
                    }}>
                        {channel.name}
                    </div>
                    {now?.title && (
                        <div style={{
                            fontSize: 11, color: 'var(--vesper-blue-bright)',
                            marginTop: 2,
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                            letterSpacing: '-0.005em',
                        }}>
                            NOW · {now.title}
                        </div>
                    )}
                </div>
                {focused && <ChevronRight size={14} color="var(--vesper-blue-bright)" />}
            </button>
        </li>
    );
}

/* ====================== Now / Next column ====================== */

function NowNextCol({ channel, epg, onPlay }) {
    return (
        <div
            data-testid="live-tv-now-next"
            style={{
                padding: '18px 18px 22px',
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.2)',
                borderRadius: 18,
                maxHeight: 'calc(100dvh - 46vh - 60px)',
                overflowY: 'auto',
                contain: 'paint',
            }}
        >
            {!channel ? (
                <div className="flex flex-col items-center" style={{ gap: 12, padding: 30, color: 'var(--vesper-text-3)' }}>
                    <Radio size={22} strokeWidth={1.6} />
                    <div style={{ fontSize: 13, textAlign: 'center' }}>Pick a channel to see what's on.</div>
                </div>
            ) : (
                <>
                    <div className="flex items-center gap-2" style={{ marginBottom: 6 }}>
                        <div style={{
                            width: 40, height: 28, flexShrink: 0,
                            borderRadius: 4,
                            background: channel.stream_icon
                                ? `#0a0d18 center/contain no-repeat url(${channel.stream_icon})`
                                : 'rgba(255,255,255,0.06)',
                        }} />
                        <div className="vesper-mono" style={{
                            fontSize: 10, letterSpacing: '0.22em',
                            color: 'var(--vesper-blue-bright)', textTransform: 'uppercase',
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        }}>
                            {channel.name}
                        </div>
                    </div>

                    <EpgBlock label="NOW" item={epg?.now} highlight />
                    <EpgBlock label="UP NEXT" item={epg?.next} />

                    <button
                        data-testid="live-tv-play"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onPlay}
                        className="flex items-center justify-center gap-2 rounded-full font-sans w-full"
                        style={{
                            height: 46, marginTop: 14, fontSize: 14, fontWeight: 700,
                            background: 'var(--vesper-blue)', color: 'var(--vesper-bg-0)',
                            border: 'none', cursor: 'pointer',
                        }}
                    >
                        <Play size={14} strokeWidth={2.5} fill="currentColor" />
                        Watch this channel
                    </button>
                </>
            )}
        </div>
    );
}

function EpgBlock({ label, item, highlight }) {
    if (!item) {
        return (
            <div style={{
                padding: '10px 12px', marginTop: 10,
                borderRadius: 12,
                background: 'rgba(255,255,255,0.025)',
                border: '1px dashed rgba(255,255,255,0.1)',
            }}>
                <div className="vesper-mono" style={{
                    fontSize: 9, letterSpacing: '0.24em', color: 'var(--vesper-text-3)',
                    textTransform: 'uppercase', marginBottom: 4,
                }}>
                    {label}
                </div>
                <div style={{ fontSize: 12, color: 'var(--vesper-text-3)' }}>
                    No EPG data
                </div>
            </div>
        );
    }
    return (
        <div style={{
            padding: '12px 14px', marginTop: 10,
            borderRadius: 12,
            background: highlight ? 'rgba(var(--vesper-blue-rgb), 0.12)' : 'rgba(255,255,255,0.04)',
            border: highlight ? '1px solid rgba(var(--vesper-blue-rgb), 0.45)' : '1px solid rgba(255,255,255,0.08)',
        }}>
            <div className="vesper-mono" style={{
                fontSize: 9, letterSpacing: '0.24em',
                color: highlight ? 'var(--vesper-blue-bright)' : 'var(--vesper-text-3)',
                textTransform: 'uppercase', marginBottom: 4, fontWeight: 700,
            }}>
                {label}{item.start ? ` · ${formatTime(item.start)}–${formatTime(item.end)}` : ''}
            </div>
            <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--vesper-text)', lineHeight: 1.3 }}>
                {item.title || 'Untitled'}
            </div>
            {item.description && (
                <div style={{
                    fontSize: 12, color: 'var(--vesper-text-2)', lineHeight: 1.4,
                    marginTop: 4,
                    display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical',
                    overflow: 'hidden',
                }}>
                    {item.description}
                </div>
            )}
        </div>
    );
}

/* ============================ Helpers ============================ */

function formatTime(s) {
    if (!s) return '';
    // Xtream typically returns "2026-02-14 20:00:00" or ISO.
    const m = /\d{4}-\d{2}-\d{2}[ T](\d{2}):(\d{2})/.exec(s);
    if (m) return `${m[1]}:${m[2]}`;
    try { return new Date(s).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }); }
    catch { return s; }
}
