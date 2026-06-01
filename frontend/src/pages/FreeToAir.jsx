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
/* v2.8.96 — denser grid per user feedback.  These MUST match the
   CSS custom properties in fta.css (--fta-grid-px-per-min,
   --fta-row-h, --fta-channel-rail-w). */
const PX_PER_MIN = 9;              // matches --fta-grid-px-per-min
const ROW_H = 64;                  // matches --fta-row-h
const CHANNEL_RAIL_W = 104;        // matches --fta-channel-rail-w
const FAV_KEY = 'fta-favourites-v1';
const CITY_KEY = 'fta-city-v1';
/* v2.8.97 — per user feedback: only show currently-airing + future
   programmes (no scrolling backwards into already-finished shows),
   and clamp the visible left edge of any in-progress live show to
   the start of the grid so the title is always readable.  Grid
   window now starts AT NOW (snapped to the previous 15-min mark to
   keep the time labels round). */
const HOURS_FORWARD = 12;

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

function snapTo15(ms) {
    const d = new Date(ms);
    d.setSeconds(0, 0);
    d.setMinutes(Math.floor(d.getMinutes() / 15) * 15);
    return d.getTime();
}

/* ====================================================================
   Top-level page
==================================================================== */
export default function FreeToAir() {
    useSpatialFocus();
    useBackHandler('/');

    const [tab, setTab] = useState('live');          // category id
    const [categories, setCategories] = useState([]);
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
    /* v2.8.97 — the channel whose HLS stream is currently mounted
       in the preview pane.  Set ONLY when the user clicks a cell
       (Enter on the remote).  Separate from `activeChannel` so
       scrolling around / switching categories does not interrupt
       playback — per user feedback the preview "should continuously
       play until their is another channel clicked". */
    const [playingChannel, setPlayingChannel] = useState(null);
    const [fullScreen, setFullScreen] = useState(false);
    /* v2.8.97 — Vesper-style slide-in left side menu for category
       switching.  Pressing LEFT while on the leftmost cell of any
       row opens it; RIGHT or BACK closes it. */
    const [sideMenuOpen, setSideMenuOpen] = useState(false);

    /* clock — update once per 30 s */
    useEffect(() => {
        const t = setInterval(() => setNow(Date.now()), 30_000);
        return () => clearInterval(t);
    }, []);

    /* TMDB cover art for the currently-focused programme.  Shared
       between the sidebar preview pane and the fullscreen / native
       ExoPlayer handoff. */
    const programmeArt = useProgrammeArt(activeProgramme);

    /* Persist city + favourites */
    useEffect(() => { saveCity(city); }, [city]);
    useEffect(() => { saveFavs(favs); }, [favs]);

    /* Fetch supported cities + categories once */
    useEffect(() => {
        fetch(`${API}/api/fta/cities`)
            .then((r) => r.json())
            .then((d) => setSupportedCities(d.cities || ['Brisbane']))
            .catch(() => { /* */ });
    }, []);
    useEffect(() => {
        fetch(`${API}/api/fta/categories?city=${encodeURIComponent(city)}`)
            .then((r) => r.json())
            .then((d) => setCategories(d.categories || []))
            .catch(() => { /* */ });
    }, [city]);

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

    /* Visible channel set based on the active category tab. */
    const visibleChannels = useMemo(() => {
        if (tab === 'favourites') {
            const favSet = new Set(favs);
            return channels.filter((c) => favSet.has(c.id));
        }
        return channels.filter((c) =>
            Array.isArray(c.categories) && c.categories.includes(tab)
        );
    }, [tab, channels, favs]);

    /* Origin point for the grid x axis — snap NOW down to the
       previous 15-min mark.  Programmes currently airing get
       clamped to left=0 (see ChannelRow) so they're always
       readable; programmes that already finished are filtered out
       entirely. */
    const gridStartMs = useMemo(() => {
        return snapTo15(now);
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

    /* When the user switches tabs, pin focus to that tab's first
       channel so the EPG re-centres immediately. */
    useEffect(() => {
        if (!visibleChannels.length) return;
        if (!activeChannel || !visibleChannels.some((c) => c.id === activeChannel.id)) {
            setActiveChannel(visibleChannels[0]);
        }
    }, [tab, visibleChannels]); // eslint-disable-line react-hooks/exhaustive-deps

    /* Update the active programme to the focused channel's live show. */
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

    /* v2.8.97 — memo the stream-fetch closure so it doesn't get a new
       identity on every render.  ChannelPreview watches it in a
       dependency array; without this, every keypress would trigger
       a re-fetch + re-mount of the HLS source, defeating the
       "preview keeps playing while you scroll" promise. */
    const streamFor = useCallback((id) => (
        fetch(`${API}/api/fta/streams/${id}?city=${encodeURIComponent(city)}`).then((r) => r.json())
    ), [city]);

    /* When a programme cell is FOCUSED (D-pad navigation / mouse
       hover) — update the sidebar info + cover art but DO NOT
       interrupt the currently-playing preview.  The video element
       belongs to `playingChannel`, not `activeChannel`. */
    const onProgrammeFocus = useCallback((channel, programme) => {
        setActiveChannel((prev) => (prev?.id === channel.id ? prev : channel));
        setActiveProgramme(programme);
    }, []);

    /* When a programme cell is OPENED (Enter on focused cell).
         1st tap on a new channel → load its HLS stream into the
                                    preview (with sound).
         2nd tap on the same channel → go fullscreen. */
    const onProgrammeOpen = useCallback((channel, programme) => {
        if (playingChannel?.id === channel.id) {
            setFullScreen(true);
        } else {
            setActiveChannel(channel);
            setActiveProgramme(programme);
            setPlayingChannel(channel);
        }
    }, [playingChannel]);

    /* v2.8.97 — when fullscreen is requested AND the OnNowFTA native
       bridge is available (i.e. running inside the FTA Android APK),
       hand the stream off to native ExoPlayer.  In the browser
       fallback path we instead expand the in-place preview tile to
       cover the viewport via CSS — the SAME <video> element stays
       mounted so there is zero HLS reconnect on enter / exit. */
    useEffect(() => {
        if (!fullScreen || !playingChannel) return;
        const bridge = window.OnNowFTA;
        if (bridge && typeof bridge.openExoPlayer === 'function') {
            (async () => {
                try {
                    const j = await streamFor(playingChannel.id);
                    if (j && j.url) {
                        bridge.openExoPlayer(
                            j.url,
                            activeProgramme?.title || playingChannel.name || '',
                            playingChannel.name || '',
                            programmeArt.backdrop || programmeArt.poster || '',
                        );
                    }
                } catch { /* ignore — CSS fullscreen fallback already in effect */ }
                setFullScreen(false);
            })();
        }
    }, [fullScreen, playingChannel, activeProgramme, programmeArt, streamFor]);

    /* BACK / Escape in fullscreen returns to the EPG without
       interrupting playback — the persistent <video> stays mounted. */
    useEffect(() => {
        if (!fullScreen) return;
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                setFullScreen(false);
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [fullScreen]);

    /* v2.8.97 — tile-stepping D-pad nav.  The user explicitly asked
       for RecyclerView-style movement: Up/Down should land on the
       cell directly above/below in the next row, Left/Right walks
       the row's DOM-sibling cells one at a time.  Pressing LEFT on
       the leftmost cell of a row opens the category side menu.
       Bound on `window` with capture=true so we win against the
       generic geometric `useSpatialFocus` handler. */
    useEffect(() => {
        const onKey = (e) => {
            const ae = document.activeElement;
            if (!ae || !ae.classList || !ae.classList.contains('fta-cell')) return;
            if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown' &&
                e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;

            const row = ae.closest('.fta-row');
            if (!row) return;

            const focusAndScroll = (next) => {
                if (!next) return;
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') {
                    e.stopImmediatePropagation();
                }
                try {
                    next.focus({ preventScroll: false });
                    next.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
                } catch { /* */ }
            };

            if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                const cells = Array.from(row.querySelectorAll('.fta-cell:not(.fta-cell--empty)'));
                const idx = cells.indexOf(ae);
                if (e.key === 'ArrowRight') {
                    focusAndScroll(cells[idx + 1]);
                } else {
                    if (idx <= 0) {
                        // At leftmost cell → open the category side menu.
                        e.preventDefault();
                        e.stopPropagation();
                        if (typeof e.stopImmediatePropagation === 'function') {
                            e.stopImmediatePropagation();
                        }
                        setSideMenuOpen(true);
                    } else {
                        focusAndScroll(cells[idx - 1]);
                    }
                }
                return;
            }

            // Up / Down — find the cell in the adjacent row whose
            // horizontal range straddles the current cell's left edge.
            const targetRow = e.key === 'ArrowDown'
                ? row.nextElementSibling
                : row.previousElementSibling;
            if (!targetRow || !targetRow.classList || !targetRow.classList.contains('fta-row')) return;

            const curLeft = parseFloat(ae.style.left || '0');
            const curWidth = parseFloat(ae.style.width || '0');
            const curEdge = curLeft + Math.min(40, curWidth / 4); // bias toward the cell's start
            const cells = Array.from(targetRow.querySelectorAll('.fta-cell:not(.fta-cell--empty)'));
            let best = null;
            for (const cell of cells) {
                const l = parseFloat(cell.style.left || '0');
                const w = parseFloat(cell.style.width || '0');
                if (l <= curEdge && curEdge < l + w) { best = cell; break; }
            }
            if (!best && cells.length) best = cells[0];
            focusAndScroll(best);
        };
        // Capture phase + window-level so we run before useSpatialFocus.
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, []);

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
        <div className={`fta-root ${fullScreen ? 'is-fullscreen' : ''}`} data-testid="fta-root">
            <TopBar
                tab={tab}
                onTab={setTab}
                categories={categories}
                now={now}
                city={city}
                supportedCities={supportedCities}
                onCity={(c) => { setCity(c); setCityMenuOpen(false); }}
                cityMenuOpen={cityMenuOpen}
                onToggleCity={() => setCityMenuOpen((v) => !v)}
            />

            <div className="fta-body">
                <Sidebar
                    focusedChannel={activeChannel}
                    focusedProgramme={activeProgramme}
                    playingChannel={playingChannel}
                    art={programmeArt}
                    isFav={activeChannel ? favs.includes(activeChannel.id) : false}
                    onToggleFav={() => activeChannel && toggleFav(activeChannel.id)}
                    streamFor={streamFor}
                    onActivate={() => {
                        /* Sidebar click: 1st arms the preview, 2nd goes full-screen. */
                        if (playingChannel) {
                            setFullScreen(true);
                        } else if (activeChannel) {
                            setPlayingChannel(activeChannel);
                        }
                    }}
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
                                onFocus={onProgrammeFocus}
                                onOpen={onProgrammeOpen}
                                onToggleFav={toggleFav}
                                favs={favs}
                            />
                        </>
                    )}
                </div>
            </div>

            {/* v2.8.97 — fullscreen no longer renders a duplicate <video>.
                Instead the .fta-root.is-fullscreen CSS expands the
                existing preview pane (and its mounted <video>) to
                cover the viewport.  Native ExoPlayer handoff is
                handled by the side effect above. */}

            {sideMenuOpen && (
                <SideMenu
                    categories={categories}
                    currentTab={tab}
                    onPick={(id) => { setTab(id); setSideMenuOpen(false); }}
                    onClose={() => setSideMenuOpen(false)}
                />
            )}
        </div>
    );
}

/* ----------------------- side menu -------------------------------- */
function SideMenu({ categories, currentTab, onPick, onClose }) {
    const items = useMemo(() => {
        const cats = (categories || []).map((c) => ({ id: c.id, label: c.label, count: c.count }));
        return [...cats, { id: 'favourites', label: 'Favourites', count: null }];
    }, [categories]);

    /* Focus the current tab on mount + handle RIGHT/BACK to close. */
    const firstRef = useRef(null);
    useEffect(() => {
        const t = setTimeout(() => firstRef.current?.focus(), 30);
        const onKey = (e) => {
            if (e.key === 'ArrowRight' || e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                onClose();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => { clearTimeout(t); window.removeEventListener('keydown', onKey, true); };
    }, [onClose]);

    return (
        <div className="fta-side-menu" data-testid="fta-side-menu">
            <div className="fta-side-menu__title">Categories</div>
            <div className="fta-side-menu__list" role="menu">
                {items.map((it, i) => (
                    <button
                        key={it.id}
                        ref={it.id === currentTab ? firstRef : null}
                        data-testid={`fta-side-${it.id}`}
                        data-focusable="true"
                        tabIndex={0}
                        role="menuitem"
                        onClick={() => onPick(it.id)}
                        className={`fta-side-menu__item ${currentTab === it.id ? 'is-active' : ''}`}
                    >
                        <span>{it.label}</span>
                        {it.count != null && it.count > 0 && (
                            <span className="fta-side-menu__count">{it.count}</span>
                        )}
                    </button>
                ))}
            </div>
        </div>
    );
}

function findUpNext(list, now) {
    if (!Array.isArray(list)) return null;
    return list.find((p) => p.start > now) || null;
}

/* TMDB cover-art lookup for an EPG programme.  Cached 7 days on the
   backend (/api/epg/art) — safe to call as often as focus changes. */
function useProgrammeArt(programme) {
    const [art, setArt] = useState({ backdrop: '', poster: '' });
    useEffect(() => {
        const title = (programme?.title || '').trim();
        if (!title) { setArt({ backdrop: '', poster: '' }); return; }
        let cancel = false;
        const q = new URLSearchParams({ title });
        if (programme?.start) {
            const y = new Date(programme.start).getFullYear();
            if (y > 1950 && y < 2100) q.set('year', String(y));
        }
        fetch(`${API}/api/epg/art?${q.toString()}`)
            .then((r) => r.json())
            .then((d) => { if (!cancel) setArt({ backdrop: d.backdrop || '', poster: d.poster || '' }); })
            .catch(() => { if (!cancel) setArt({ backdrop: '', poster: '' }); });
        return () => { cancel = true; };
    }, [programme?.title, programme?.start]);
    return art;
}

/* ====================================================================
   Sub-components
==================================================================== */

function TopBar({ tab, onTab, categories, now, city, supportedCities, onCity, cityMenuOpen, onToggleCity }) {
    /* All category tabs in the order the backend returned them, with
       Favourites appended at the end. */
    const tabs = useMemo(() => {
        const cats = (categories || []).map((c) => ({ id: c.id, label: c.label, count: c.count }));
        return [...cats, { id: 'favourites', label: 'Favourites', count: null }];
    }, [categories]);

    return (
        <div className="fta-topbar">
            <div className="fta-brand">
                <div className="fta-brand-mark" data-testid="fta-brand-mark">
                    <span className="fta-brand-mark__top">On Now</span>
                    <span className="fta-brand-mark__bottom">
                        <span className="fta-brand-v2">V2</span>
                        <span className="fta-brand-text">Free&nbsp;to&nbsp;Air</span>
                    </span>
                </div>
                <div className="fta-tabs" role="tablist">
                    {tabs.map((t) => (
                        <button
                            key={t.id}
                            data-testid={`fta-tab-${t.id}`}
                            data-focusable="true"
                            tabIndex={0}
                            role="tab"
                            aria-selected={tab === t.id}
                            onClick={() => onTab(t.id)}
                            className={`fta-tab ${tab === t.id ? 'is-active' : ''}`}
                        >
                            <span>{t.label}</span>
                            {t.count != null && t.count > 0 && (
                                <span className="fta-tab__count">{t.count}</span>
                            )}
                        </button>
                    ))}
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

/* ------------------------------- sidebar --------------------------
   v2.8.97 — separates the "focused" channel/programme (what the
   sidebar info card describes) from the "playing" channel (what
   the HLS video element is bound to).  This is what makes the
   preview keep playing while you scroll around the EPG and switch
   categories — the video stays mounted with the same source until
   the user clicks another cell. */
function Sidebar({ focusedChannel, focusedProgramme, playingChannel, art, isFav, onToggleFav, streamFor, onActivate, now, upNext }) {
    /* The info card and cover art use the FOCUSED channel/programme. */
    const channel = focusedChannel;
    const programme = focusedProgramme;

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
    const artwork = (art && (art.backdrop || art.poster)) || channel.logo || '';
    const isPlaying = !!playingChannel;

    return (
        <aside className="fta-sidebar">
            <button
                data-testid="fta-preview-open"
                data-focusable="true"
                tabIndex={0}
                onClick={onActivate}
                className="fta-preview"
            >
                <ChannelPreview
                    /* Bound to the PLAYING channel (or null until first click)
                       so scrolling the EPG never restarts the stream. */
                    channelId={playingChannel?.id || null}
                    streamFor={streamFor}
                    armed={isPlaying}
                    artwork={artwork}
                    fallback={channel.logo}
                />
                <span className="fta-live-pill">● LIVE</span>
                {!isPlaying && (
                    <span className="fta-preview-hint" data-testid="fta-preview-hint">
                        Press OK to play
                    </span>
                )}
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

/* --------------------------- preview HLS --------------------------
   v2.8.96 — only mount the HLS <video> when `armed === true` (i.e.
   the user has pressed OK on a cell or on the preview tile).  Until
   then we render TMDB programme artwork so scrolling through the
   guide doesn't fan out 188 background streams.

   Audio:  default is UNMUTED — the user explicitly asked for
   "preview with sound".  Some browsers (Chrome on Android WebView
   with `mediaPlaybackRequiresUserGesture=false` it's fine, but
   desktop Chrome rejects unmuted autoplay) will reject the play()
   promise; we then fall back to muted playback and listen for the
   first 'click' / 'keydown' to unmute. */
function ChannelPreview({ channelId, streamFor, armed, artwork, fallback }) {
    const videoRef = useRef(null);
    const [streamUrl, setStreamUrl] = useState(null);
    const [streamReady, setStreamReady] = useState(false);

    /* Reset stream state every time the channel changes or we
       disarm (e.g. switching to a new programme without re-clicking). */
    useEffect(() => {
        setStreamReady(false);
        if (!armed || !channelId) {
            setStreamUrl(null);
            return;
        }
        let cancel = false;
        (async () => {
            try {
                const j = await streamFor(channelId);
                if (!cancel && j.url) setStreamUrl(j.url);
            } catch { /* preview falls back to artwork */ }
        })();
        return () => { cancel = true; };
    }, [armed, channelId, streamFor]);

    useEffect(() => {
        const video = videoRef.current;
        if (!video || !streamUrl) return;
        let hls = null;
        if (video.canPlayType('application/vnd.apple.mpegurl')) {
            video.src = streamUrl;
        } else if (Hls.isSupported()) {
            hls = new Hls({ enableWorker: true, lowLatencyMode: false });
            hls.loadSource(streamUrl);
            hls.attachMedia(video);
        }

        video.muted = false;
        let unmuteOnInteract = null;
        const tryPlay = video.play();
        if (tryPlay && tryPlay.catch) {
            tryPlay.catch(() => {
                /* Autoplay-with-sound blocked.  Retry muted so the
                   user at least sees motion, and arm a one-shot
                   listener to unmute on first interaction. */
                try {
                    video.muted = true;
                    video.play().catch(() => { /* */ });
                } catch { /* */ }
                unmuteOnInteract = () => {
                    try { video.muted = false; } catch { /* */ }
                    window.removeEventListener('keydown', unmuteOnInteract, true);
                    window.removeEventListener('click', unmuteOnInteract, true);
                };
                window.addEventListener('keydown', unmuteOnInteract, true);
                window.addEventListener('click', unmuteOnInteract, true);
            });
        }
        const onPlay = () => setStreamReady(true);
        video.addEventListener('playing', onPlay);

        return () => {
            video.removeEventListener('playing', onPlay);
            if (unmuteOnInteract) {
                window.removeEventListener('keydown', unmuteOnInteract, true);
                window.removeEventListener('click', unmuteOnInteract, true);
            }
            if (hls) { try { hls.destroy(); } catch { /* */ } }
            try { video.pause(); video.removeAttribute('src'); video.load(); } catch { /* */ }
            setStreamReady(false);
        };
    }, [streamUrl]);

    /* Render order:
        - Cover-art image always sits underneath as an instant
          backdrop (no flash to black on every focus change).
        - <video> is mounted only when armed; while it's still
          buffering it stays transparent so the artwork shows
          through.  Once 'playing' fires (streamReady=true) we
          fade the video on top. */
    const poster = artwork || fallback || '';
    return (
        <>
            {poster
                ? <img className="fta-preview-art" src={poster} alt="" />
                : <div className="fta-preview-art fta-preview-art--empty" />}
            {armed && (
                <video
                    ref={videoRef}
                    className={`fta-preview-video ${streamReady ? 'is-ready' : ''}`}
                    playsInline
                    autoPlay
                    poster={poster}
                />
            )}
        </>
    );
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
        activeChannelId, activeProgrammeKey, onFocus, onOpen, onToggleFav, favs,
    },
    ref,
) {
    const nowOffsetPx = ((now - gridStartMs) / 60000) * PX_PER_MIN;

    return (
        <div className="fta-grid-rows" ref={ref} data-testid="fta-grid-rows" data-no-h-rail="true">
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
                    /* v2.8.97 — only show currently-airing + future
                       programmes.  No scrolling backwards into the
                       past per user feedback. */
                    const list = (programmes[ch.id] || []).filter(
                        (p) => p.stop > now && p.start < gridStartMs + gridWidthPx / PX_PER_MIN * 60000
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
                            onFocus={(p) => onFocus(ch, p)}
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
function ChannelRow({ channel, programmes, gridStartMs, onFocus, onOpen, activeProgrammeKey, now }) {
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
                        left: 4, right: 4, top: 5,
                    }}>
                        <div className="fta-cell__title">No programme info</div>
                    </div>
                )}
                {programmes.map((p) => {
                    const startOffsetMin = (p.start - gridStartMs) / 60000;
                    const endOffsetMin = (p.stop - gridStartMs) / 60000;
                    /* v2.8.97 — clamp the live programme's left edge
                       to the visible window so its title is always
                       readable (the user explicitly asked for "all
                       live shows pushed up against the far left"). */
                    const visibleStartMin = Math.max(0, startOffsetMin);
                    const left = visibleStartMin * PX_PER_MIN;
                    const width = Math.max(56, (endOffsetMin - visibleStartMin) * PX_PER_MIN - 3);
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
                                left: left + 1,
                                width,
                                top: 5,
                            }}
                            onFocus={() => onFocus(p)}
                            onMouseEnter={() => onFocus(p)}
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

/* ----------------------- full-screen player -----------------------
   v2.8.96 — when running inside the FTA Android APK, hand off the
   HLS URL to native ExoPlayer (media3) via the OnNowFTA bridge for
   faster start-up + native play/pause/seek controls.  In the
   browser (or any WebView without the bridge), fall back to the
   in-page <video> + hls.js implementation. */
function FullScreenPlayer({ channel, programme, city, artwork, onExit }) {
    const videoRef = useRef(null);
    const [url, setUrl] = useState(null);
    const [bridgedToNative, setBridgedToNative] = useState(false);

    useEffect(() => {
        let cancel = false;
        (async () => {
            const j = await fetch(`${API}/api/fta/streams/${channel.id}?city=${encodeURIComponent(city || 'Brisbane')}`).then((r) => r.json());
            if (!cancel && j.url) setUrl(j.url);
        })();
        return () => { cancel = true; };
    }, [channel.id, city]);

    /* Native ExoPlayer handoff */
    useEffect(() => {
        if (!url) return;
        const bridge = window.OnNowFTA;
        if (bridge && typeof bridge.openExoPlayer === 'function') {
            try {
                bridge.openExoPlayer(
                    url,
                    programme?.title || channel.name || '',
                    channel.name || '',
                    artwork || '',
                );
                setBridgedToNative(true);
                /* The native player consumes the URL and shows itself
                   on top — we exit the React fullscreen overlay so the
                   underlying EPG stays mounted for when the user
                   presses BACK. */
                onExit();
                return;
            } catch { /* fall through to HTML5 */ }
        }
    }, [url, programme, channel, artwork, onExit]);

    useEffect(() => {
        if (bridgedToNative) return;
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
        video.play().catch(() => {
            /* autoplay-with-sound blocked — retry muted, unmute on first key */
            try { video.muted = true; video.play().catch(() => { /* */ }); } catch { /* */ }
            const unmute = () => {
                try { video.muted = false; } catch { /* */ }
                window.removeEventListener('keydown', unmute, true);
            };
            window.addEventListener('keydown', unmute, true);
        });
        return () => { if (hls) { try { hls.destroy(); } catch { /* */ } } };
    }, [url, bridgedToNative]);

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

    if (bridgedToNative) return null;

    return (
        <div className="fta-fullscreen" data-testid="fta-fullscreen">
            {artwork && <img className="fta-fullscreen-art" src={artwork} alt="" />}
            <video ref={videoRef} playsInline autoPlay controls={false} />
            <div className="fta-fullscreen-hint">Press BACK to return</div>
        </div>
    );
}
