/**
 * ON NOW V2 — Free-to-Air EPG (v2.8.90).
 *
 * Layout = top bar  +  left sidebar  +  right EPG grid.
 *
 *   - Top bar: brand wordmark · "Free-to-Air | Favourites" tabs · clock.
 *   - Sidebar: live preview (HLS) · channel logo + LCN + name · heart
 *     favourite toggle · synopsis + chips (PG / Drama / HD / CC).
 *   - Grid: scrollable EPG with one fixed channel rail (left) and
 *     virtualised cells per channel.  Vertical red NOW line at the
 *     current time.
 *
 * Performance:
 *   - Programmes are positioned absolutely with `left` + `width` in
 *     pixels (12 px = 1 min) so we never re-measure on focus change.
 *   - Channel rows render as a flat list; <Grid> is short enough
 *     (~21 channels × 24 h = ~3.5 k cells max) that we don't need
 *     react-window's GridChildComponent — but cell rendering is
 *     guarded by `data-focusable` so the global spatial focus hook
 *     handles D-pad nav without any per-cell key listeners.
 *
 * Source data:
 *   - GET /api/fta/channels  → channels list + logos
 *   - GET /api/fta/epg       → next 24 h of programmes
 *   - GET /api/fta/streams/{id} → resolves the HLS URL on demand
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Hls from 'hls.js';
import { Heart, ChevronDown } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';
import './fta.css';

const API = (process.env.REACT_APP_BACKEND_URL || '').replace(/\/$/, '');
const PX_PER_MIN = 14;             // matches --fta-grid-px-per-min
const ROW_H = 104;                 // matches --fta-row-h
const CHANNEL_RAIL_W = 132;        // matches --fta-channel-rail-w
const FAV_KEY = 'fta-favourites-v1';
const CITY_KEY = 'fta-city-v1';
const HOURS_FORWARD = 24;
const HOURS_BACKWARD = 0.5;

/* ---------- favourites + city storage ----------------------------- */
function loadFavs() {
    try { return JSON.parse(localStorage.getItem(FAV_KEY) || '[]'); }
    catch { return []; }
}
function saveFavs(ids) {
    try { localStorage.setItem(FAV_KEY, JSON.stringify(ids)); } catch { /* */ }
}
function loadCity() {
    try { return localStorage.getItem(CITY_KEY) || 'Brisbane'; }
    catch { return 'Brisbane'; }
}
function saveCity(c) {
    try { localStorage.setItem(CITY_KEY, c); } catch { /* */ }
}

/* ---------- time helpers ------------------------------------------ */
function fmtTime(ms) {
    const d = new Date(ms);
    let h = d.getHours();
    const m = String(d.getMinutes()).padStart(2, '0');
    const am = h >= 12 ? 'pm' : 'am';
    h = h % 12 || 12;
    return `${h}:${m}${am}`;
}

function fmtClock(ms) {
    return fmtTime(ms);
}

function snapTo30(ms) {
    const d = new Date(ms);
    d.setSeconds(0, 0);
    d.setMinutes(d.getMinutes() < 30 ? 0 : 30);
    return d.getTime();
}

/* ====================================================================
   Top-level page
==================================================================== */
export default function FreeToAir() {
    useSpatialFocus();
    useBackHandler('/');

    const [tab, setTab] = useState('all');           // 'all' | 'favourites'
    const [city, setCity] = useState(loadCity);
    const [cityMenuOpen, setCityMenuOpen] = useState(false);
    const [supportedCities, setSupportedCities] = useState(['Brisbane']);
    const [channels, setChannels] = useState([]);
    const [programmes, setProgrammes] = useState({});
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [favs, setFavs] = useState(loadFavs());
    const [now, setNow] = useState(Date.now());
    const [activeChannel, setActiveChannel] = useState(null);
    const [activeProgramme, setActiveProgramme] = useState(null);
    const [fullScreen, setFullScreen] = useState(false);

    /* clock — update once per 30 s */
    useEffect(() => {
        const t = setInterval(() => setNow(Date.now()), 30_000);
        return () => clearInterval(t);
    }, []);

    /* Persist city + favourites */
    useEffect(() => { saveCity(city); }, [city]);
    useEffect(() => { saveFavs(favs); }, [favs]);

    /* Fetch supported cities once */
    useEffect(() => {
        fetch(`${API}/api/fta/cities`)
            .then((r) => r.json())
            .then((d) => setSupportedCities(d.cities || ['Brisbane']))
            .catch(() => { /* leave default */ });
    }, []);

    /* Fetch channels + EPG every time the city changes */
    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                setLoading(true);
                setError(null);
                const [chRes, epgRes] = await Promise.all([
                    fetch(`${API}/api/fta/channels?city=${encodeURIComponent(city)}`).then((r) => r.json()),
                    fetch(`${API}/api/fta/epg?city=${encodeURIComponent(city)}`).then((r) => r.json()),
                ]);
                if (cancel) return;
                setChannels(chRes.channels || []);
                setProgrammes(epgRes.programmes || {});
                if ((chRes.channels || []).length > 0) {
                    setActiveChannel(chRes.channels[0]);
                }
            } catch (e) {
                if (!cancel) setError(e.message || 'Could not load EPG');
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, [city]);

    /* Visible channel set based on the active tab */
    const visibleChannels = useMemo(() => {
        if (tab === 'favourites') {
            const favSet = new Set(favs);
            return channels.filter((c) => favSet.has(c.id));
        }
        return channels;
    }, [tab, channels, favs]);

    /* Origin point for the grid x axis — 30 min before now, rounded down */
    const gridStartMs = useMemo(() => {
        return snapTo30(now - HOURS_BACKWARD * 3600 * 1000);
    }, [now]);

    const gridEndMs = useMemo(() => {
        return gridStartMs + HOURS_FORWARD * 3600 * 1000;
    }, [gridStartMs]);

    const gridWidthPx = useMemo(() => {
        return ((gridEndMs - gridStartMs) / 60000) * PX_PER_MIN;
    }, [gridStartMs, gridEndMs]);

    /* Determine the live-now programme for each channel — used by the
       sidebar to surface the active synopsis automatically. */
    const liveByChannel = useMemo(() => {
        const out = {};
        for (const cid of Object.keys(programmes)) {
            const list = programmes[cid] || [];
            const live = list.find((p) => p.start <= now && p.stop > now);
            if (live) out[cid] = live;
        }
        return out;
    }, [programmes, now]);

    /* Whenever the active channel changes we update the active
       programme to that channel's currently-airing show.  The user
       can then arrow-right through the grid to inspect future shows. */
    useEffect(() => {
        if (!activeChannel) return;
        const live = liveByChannel[activeChannel.id];
        if (live) setActiveProgramme(live);
    }, [activeChannel, liveByChannel]);

    const toggleFav = useCallback((chId) => {
        setFavs((prev) => (
            prev.includes(chId) ? prev.filter((x) => x !== chId) : [...prev, chId]
        ));
    }, []);

    /* When a programme cell is opened (Enter on focused cell) */
    const onProgrammeOpen = useCallback((channel, programme) => {
        if (activeChannel?.id === channel.id) {
            // Second tap → fullscreen
            setFullScreen(true);
        } else {
            // First tap → preview in sidebar
            setActiveChannel(channel);
            setActiveProgramme(programme);
        }
    }, [activeChannel]);

    /* Auto-scroll the grid horizontally so NOW is roughly centred on
       first paint (10% from the left edge looks closest to the user's
       mockup). */
    const gridRowsRef = useRef(null);
    useEffect(() => {
        const el = gridRowsRef.current;
        if (!el) return;
        const nowOffsetPx = ((now - gridStartMs) / 60000) * PX_PER_MIN;
        const target = Math.max(0, nowOffsetPx - el.clientWidth * 0.15);
        el.scrollTo({ left: target, top: el.scrollTop, behavior: 'instant' });
    }, [gridStartMs, channels.length]); // eslint-disable-line react-hooks/exhaustive-deps

    if (error) {
        return (
            <div className="fta-root">
                <div className="fta-loading" style={{ color: '#ff6e6e' }}>
                    Couldn&apos;t load the guide.<br />{error}
                </div>
            </div>
        );
    }

    return (
        <div className="fta-root" data-testid="fta-root">
            <TopBar
                tab={tab}
                onTab={setTab}
                now={now}
                city={city}
                supportedCities={supportedCities}
                onCity={(c) => { setCity(c); setCityMenuOpen(false); }}
                cityMenuOpen={cityMenuOpen}
                onToggleCity={() => setCityMenuOpen((v) => !v)}
            />

            <div className="fta-body">
                <Sidebar
                    channel={activeChannel}
                    programme={activeProgramme}
                    isFav={activeChannel ? favs.includes(activeChannel.id) : false}
                    onToggleFav={() => activeChannel && toggleFav(activeChannel.id)}
                    streamFor={(id) => fetch(`${API}/api/fta/streams/${id}?city=${encodeURIComponent(city)}`).then((r) => r.json())}
                    onActivate={() => setFullScreen(true)}
                    now={now}
                    upNext={activeChannel ? findUpNext(programmes[activeChannel.id] || [], now) : null}
                />

                <div className="fta-grid-wrap">
                    {loading && (
                        <div className="fta-loading">
                            Loading {city} guide
                            <div className="fta-loading__sweep" />
                        </div>
                    )}

                    {!loading && (
                        <>
                            <GridHeader
                                gridStartMs={gridStartMs}
                                gridEndMs={gridEndMs}
                                gridRowsRef={gridRowsRef}
                            />
                            <GridRows
                                ref={gridRowsRef}
                                channels={visibleChannels}
                                programmes={programmes}
                                gridStartMs={gridStartMs}
                                gridWidthPx={gridWidthPx}
                                now={now}
                                activeChannelId={activeChannel?.id}
                                activeProgrammeKey={activeProgramme && `${activeProgramme.start}|${activeProgramme.stop}|${activeProgramme.title}`}
                                onOpen={onProgrammeOpen}
                                onToggleFav={toggleFav}
                                favs={favs}
                            />
                        </>
                    )}
                </div>
            </div>

            {fullScreen && activeChannel && (
                <FullScreenPlayer
                    channel={activeChannel}
                    city={city}
                    onExit={() => setFullScreen(false)}
                />
            )}
        </div>
    );
}

function findUpNext(list, now) {
    if (!Array.isArray(list)) return null;
    return list.find((p) => p.start > now) || null;
}

/* ====================================================================
   Sub-components
==================================================================== */

function TopBar({ tab, onTab, now, city, supportedCities, onCity, cityMenuOpen, onToggleCity }) {
    return (
        <div className="fta-topbar">
            <div className="fta-brand">
                <span className="fta-brand-v2">V2</span>
                <span className="fta-brand-text">Free-to-Air</span>
                <div className="fta-tabs">
                    <button
                        data-testid="fta-tab-all"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={() => onTab('all')}
                        className={`fta-tab ${tab === 'all' ? 'is-active' : ''}`}
                    >
                        Free-to-Air
                    </button>
                    <button
                        data-testid="fta-tab-fav"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={() => onTab('favourites')}
                        className={`fta-tab ${tab === 'favourites' ? 'is-active' : ''}`}
                    >
                        Favourites
                    </button>
                </div>
            </div>
            <div className="fta-topbar-right">
                <button
                    data-testid="fta-city-toggle"
                    data-focusable="true"
                    tabIndex={0}
                    className="fta-city"
                    onClick={onToggleCity}
                >
                    {city}
                    <ChevronDown size={14} strokeWidth={2.5} />
                </button>
                <div className="fta-clock" data-testid="fta-clock">{fmtClock(now)}</div>
            </div>
            {cityMenuOpen && (
                <div className="fta-city-menu" role="menu">
                    {supportedCities.map((c) => (
                        <button
                            key={c}
                            data-testid={`fta-city-${c}`}
                            data-focusable="true"
                            tabIndex={0}
                            onClick={() => onCity(c)}
                            className={`fta-city-menu__item ${c === city ? 'is-active' : ''}`}
                        >
                            {c}
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
}

/* ------------------------------- sidebar -------------------------- */
function Sidebar({ channel, programme, isFav, onToggleFav, streamFor, onActivate, now, upNext }) {
    if (!channel) {
        return (
            <aside className="fta-sidebar">
                <div className="fta-preview"><div className="fta-loading">Select a channel</div></div>
            </aside>
        );
    }
    const live = programme || { title: '—', desc: '', start: now, stop: now + 1, rating: '' };
    const pct = live.start && live.stop && live.stop > live.start
        ? Math.min(100, Math.max(0, ((now - live.start) / (live.stop - live.start)) * 100))
        : 0;
    const remainingMin = Math.max(0, Math.round((live.stop - now) / 60000));

    return (
        <aside className="fta-sidebar">
            <button
                data-testid="fta-preview-open"
                data-focusable="true"
                tabIndex={0}
                onClick={onActivate}
                className="fta-preview"
            >
                <ChannelPreview channelId={channel.id} streamFor={streamFor} poster={channel.logo} />
                <span className="fta-live-pill">● LIVE</span>
                <div className="fta-preview-overlay">
                    <div className="fta-preview-overlay__title">{live.title || channel.name}</div>
                    <div className="fta-preview-overlay__meta">
                        {fmtTime(live.start)} – {fmtTime(live.stop)}
                        {remainingMin > 0 && <>  ·  {remainingMin}m left</>}
                    </div>
                    <div className="fta-progress"><div className="fta-progress__bar" style={{ width: `${pct}%` }} /></div>
                </div>
            </button>

            <div className="fta-info">
                <div className="fta-info-row">
                    <div className="fta-info-logo">
                        {channel.logo
                            ? <img src={channel.logo} alt={channel.name} />
                            : <span>{channel.name?.[0] || '?'}</span>}
                    </div>
                    <div className="fta-info-name">
                        <div className="fta-info-name__num">{channel.lcn || channel.name}</div>
                        <div className="fta-info-name__lbl">{channel.lcn ? channel.name : ''}</div>
                    </div>
                    <button
                        data-testid="fta-sidebar-fav"
                        data-focusable="true"
                        tabIndex={0}
                        aria-label={isFav ? 'Remove from favourites' : 'Add to favourites'}
                        onClick={onToggleFav}
                        className={`fta-fav-btn ${isFav ? 'is-fav' : ''}`}
                    >
                        <Heart size={20} fill={isFav ? '#ff2535' : 'transparent'} />
                    </button>
                </div>

                <p className="fta-synopsis" data-testid="fta-sidebar-synopsis">
                    {live.desc || (programme?.title ? '' : 'No programme info available.')}
                </p>

                <div className="fta-chips">
                    {live.rating && <span className="fta-chip fta-chip--pg">{live.rating}</span>}
                    {live.category && <span className="fta-chip">{live.category}</span>}
                    <span className="fta-chip">HD</span>
                    <span className="fta-chip">CC</span>
                </div>
            </div>

            {upNext && (
                <div className="fta-upnext" data-testid="fta-upnext">
                    <div className="fta-upnext__label">Coming up next</div>
                    <div className="fta-upnext__title">{upNext.title}</div>
                    <div className="fta-upnext__meta">
                        {fmtTime(upNext.start)} – {fmtTime(upNext.stop)}
                        {upNext.category && <>  ·  {upNext.category}</>}
                    </div>
                </div>
            )}
        </aside>
    );
}

/* --------------------------- preview HLS -------------------------- */
function ChannelPreview({ channelId, streamFor, poster }) {
    const videoRef = useRef(null);
    const [streamUrl, setStreamUrl] = useState(null);

    useEffect(() => {
        let cancel = false;
        if (!channelId) return;
        setStreamUrl(null);
        (async () => {
            try {
                const j = await streamFor(channelId);
                if (!cancel && j.url) setStreamUrl(j.url);
            } catch { /* preview falls back to poster */ }
        })();
        return () => { cancel = true; };
    }, [channelId, streamFor]);

    useEffect(() => {
        const video = videoRef.current;
        if (!video || !streamUrl) return;
        let hls = null;
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            // Safari (and Android Chrome via MediaPlayer)
            video.src = streamUrl;
        } else if (Hls.isSupported()) {
            hls = new Hls({ enableWorker: true, lowLatencyMode: false });
            hls.loadSource(streamUrl);
            hls.attachMedia(video);
        }
        video.muted = true;          // preview is silent — fullscreen unmutes
        const playPromise = video.play();
        if (playPromise) playPromise.catch(() => { /* autoplay can be blocked */ });
        return () => {
            if (hls) { try { hls.destroy(); } catch { /* */ } }
            try { video.pause(); video.removeAttribute('src'); video.load(); } catch { /* */ }
        };
    }, [streamUrl]);

    return streamUrl
        ? <video ref={videoRef} playsInline autoPlay muted poster={poster} />
        : <img src={poster || ''} alt="" />;
}

/* ---------------------------- grid header ------------------------- */
function GridHeader({ gridStartMs, gridEndMs, gridRowsRef }) {
    /* Times row mirrors the horizontal scroll position of GridRows.  We
       keep the times floating with translateX so we don't have to
       re-render on every scroll tick. */
    const timesRef = useRef(null);

    useEffect(() => {
        const rows = gridRowsRef.current;
        const times = timesRef.current;
        if (!rows || !times) return;
        const onScroll = () => {
            times.style.transform = `translateX(${-rows.scrollLeft}px)`;
        };
        onScroll();
        rows.addEventListener('scroll', onScroll, { passive: true });
        return () => rows.removeEventListener('scroll', onScroll);
    }, [gridRowsRef]);

    const slots = [];
    for (let t = gridStartMs; t < gridEndMs; t += 30 * 60 * 1000) {
        slots.push(t);
    }

    return (
        <div className="fta-grid-header">
            <span className="fta-grid-header__today">Today</span>
            <div className="fta-grid-times" ref={timesRef}>
                {slots.map((t) => (
                    <div
                        key={t}
                        className="fta-grid-time"
                        style={{ width: 30 * PX_PER_MIN, paddingLeft: 6 }}
                    >
                        {fmtTime(t)}
                    </div>
                ))}
            </div>
        </div>
    );
}

/* --------------------------- grid rows ---------------------------- */
const GridRows = React.forwardRef(function GridRows(
    {
        channels, programmes, gridStartMs, gridWidthPx, now,
        activeChannelId, activeProgrammeKey, onOpen, onToggleFav, favs,
    },
    ref,
) {
    const nowOffsetPx = ((now - gridStartMs) / 60000) * PX_PER_MIN;

    return (
        <div className="fta-grid-rows" ref={ref} data-testid="fta-grid-rows">
            <div style={{ position: 'relative', width: gridWidthPx + CHANNEL_RAIL_W }}>
                {/* NOW line */}
                <div
                    className="fta-now-line"
                    style={{ left: CHANNEL_RAIL_W + nowOffsetPx }}
                />
                <div
                    className="fta-now-label"
                    style={{ left: CHANNEL_RAIL_W + nowOffsetPx }}
                >
                    {fmtTime(now)}
                </div>

                {channels.map((ch) => {
                    const list = (programmes[ch.id] || []).filter(
                        (p) => p.stop > gridStartMs && p.start < gridStartMs + gridWidthPx / PX_PER_MIN * 60000
                    );
                    return (
                        <ChannelRow
                            key={ch.id}
                            channel={ch}
                            programmes={list}
                            gridStartMs={gridStartMs}
                            isFavRow={favs.includes(ch.id)}
                            onFav={() => onToggleFav(ch.id)}
                            activeProgrammeKey={
                                activeChannelId === ch.id ? activeProgrammeKey : null
                            }
                            onOpen={(p) => onOpen(ch, p)}
                            now={now}
                        />
                    );
                })}
            </div>
        </div>
    );
});

/* --------------------------- one row ------------------------------ */
function ChannelRow({ channel, programmes, gridStartMs, onOpen, activeProgrammeKey, now }) {
    const upNextProg = programmes.find((p) => p.start > now);
    const upNextKey = upNextProg
        ? `${upNextProg.start}|${upNextProg.stop}|${upNextProg.title}`
        : null;

    return (
        <div className="fta-row" data-channel-id={channel.id}>
            <div className="fta-row__rail">
                <div className="fta-row__rail-inner">
                    {channel.logo
                        ? <img src={channel.logo} alt={channel.name} loading="lazy" />
                        : <span style={{ fontSize: 12, fontWeight: 700, color: 'rgba(255,255,255,0.6)' }}>{channel.name}</span>}
                </div>
                {channel.lcn && (
                    <span className="fta-row__rail-lcn">{channel.lcn}</span>
                )}
            </div>
            <div className="fta-row__cells">
                {programmes.length === 0 && (
                    <div className="fta-cell fta-cell--empty" style={{
                        position: 'absolute',
                        left: 6, right: 6, top: 8,
                    }}>
                        <div className="fta-cell__title">No programme info</div>
                    </div>
                )}
                {programmes.map((p) => {
                    const startOffsetMin = Math.max(0, (p.start - gridStartMs) / 60000);
                    const endOffsetMin = Math.max(0, (p.stop - gridStartMs) / 60000);
                    const left = startOffsetMin * PX_PER_MIN;
                    const width = Math.max(80, (endOffsetMin - startOffsetMin) * PX_PER_MIN - 4);
                    const isLive = p.start <= now && p.stop > now;
                    const key = `${p.start}|${p.stop}|${p.title}`;
                    const isFocused = activeProgrammeKey === key;
                    const isNext = !isLive && upNextKey === key;
                    /* Episode/subtitle: prefer the second line of desc
                       if it looks like an episode/season pattern, else
                       leave blank — many channels embed S02E07 in desc
                       which we surface as a small subtitle. */
                    const subtitle = extractSubtitle(p);
                    return (
                        <button
                            key={key}
                            data-testid={`fta-cell-${channel.id}-${p.start}`}
                            data-focusable="true"
                            data-focus-style="cell"
                            tabIndex={0}
                            className={`fta-cell ${isFocused ? 'is-focused' : ''} ${isLive ? 'is-live' : ''}`}
                            style={{
                                position: 'absolute',
                                left: left + 2,
                                width,
                                top: 8,
                            }}
                            onClick={() => onOpen(p)}
                        >
                            <div className="fta-cell__title">{p.title || '—'}</div>
                            {subtitle && (
                                <div className="fta-cell__subtitle">{subtitle}</div>
                            )}
                            <div className="fta-cell__meta">
                                {fmtTime(p.start)} – {fmtTime(p.stop)}
                                {isLive && <span className="fta-cell__live-pill">● Live</span>}
                                {isNext && <span className="fta-cell__next-pill">Next</span>}
                            </div>
                        </button>
                    );
                })}
            </div>
        </div>
    );
}

/* Heuristic episode/subtitle extractor.  Returns empty string when no
   useful subtitle is found so the cell stays single-line for short slots. */
function extractSubtitle(p) {
    const blob = (p.desc || '').trim();
    if (!blob) return p.category || '';
    // S01E02 / S1 Ep 2 / Series 1 Episode 4 pattern
    const ep = blob.match(/(S\d+\s*E\d+|Series\s*\d+\s*Ep(?:isode)?\s*\d+|Ep\s*\d+)/i);
    if (ep) return ep[0];
    // Otherwise show the first short fragment of the description
    const firstSentence = blob.split(/[.!?]/)[0] || '';
    if (firstSentence.length < 70) return firstSentence;
    return p.category || '';
}

/* ----------------------- full-screen player ----------------------- */
function FullScreenPlayer({ channel, city, onExit }) {
    const videoRef = useRef(null);
    const [url, setUrl] = useState(null);

    useEffect(() => {
        let cancel = false;
        (async () => {
            const j = await fetch(`${API}/api/fta/streams/${channel.id}?city=${encodeURIComponent(city || 'Brisbane')}`).then((r) => r.json());
            if (!cancel && j.url) setUrl(j.url);
        })();
        return () => { cancel = true; };
    }, [channel.id, city]);

    useEffect(() => {
        const video = videoRef.current;
        if (!video || !url) return;
        let hls = null;
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = url;
        } else if (Hls.isSupported()) {
            hls = new Hls();
            hls.loadSource(url);
            hls.attachMedia(video);
        }
        video.muted = false;
        video.play().catch(() => { /* autoplay */ });
        return () => { if (hls) { try { hls.destroy(); } catch { /* */ } } };
    }, [url]);

    /* BACK / Escape exits */
    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onExit();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onExit]);

    return (
        <div className="fta-fullscreen" data-testid="fta-fullscreen">
            <video ref={videoRef} playsInline autoPlay controls={false} />
            <div className="fta-fullscreen-hint">Press BACK to return</div>
        </div>
    );
}
