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
import { LayoutGrid, Star, RotateCw, ChevronDown } from 'lucide-react';
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
    /* v2.8.103 — Vesper-style permanent left icon rail.  Three
       icons: Categories (opens a slide-in submenu), Favourites
       (toggles favourites-only view), Refresh (re-fetches the EPG).
       `sideMenuOpen` = the Categories submenu (not the rail itself);
       the rail is always rendered. */
    const [sideMenuOpen, setSideMenuOpen] = useState(false);
    const [railFocus, setRailFocus] = useState(null); // 'categories'|'favourites'|'refresh'|null
    const [favPulse, setFavPulse] = useState(null);   // channel id flashing after long-press
    const [refreshKey, setRefreshKey] = useState(0);

    /* v2.8.103 — long-press LEFT on a live cell toggles favourite.
       Tracked via a ref because the keydown handler is closure-
       captured.  `leftHoldFiredRef` is consulted by the keydown
       handler so a long-press doesn't ALSO open the icon rail. */
    const leftHoldTimerRef = useRef(null);
    const leftHoldFiredRef = useRef(false);

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
    }, [city, refreshKey]);

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

    /* v2.8.103 — LEFT-on-live-cell gesture splitter.  keydown starts
       a 550ms timer.  If the user keeps holding past 550ms, fire
       the favourite toggle (long-press); otherwise on keyup we
       open the icon rail (tap).  Exactly one of those two paths
       runs per LEFT gesture — tap and hold are mutually exclusive. */
    useEffect(() => {
        const onDown = (e) => {
            if (e.key !== 'ArrowLeft' || e.repeat) return;
            const ae = document.activeElement;
            if (!ae || !ae.classList || !ae.classList.contains('fta-cell')) return;
            const row = ae.closest('.fta-row');
            if (!row) return;
            const cells = Array.from(row.querySelectorAll('.fta-cell'));
            if (cells.indexOf(ae) !== 0) return; // only on the leftmost (live) cell
            const chId = row.getAttribute('data-channel-id');
            if (!chId) return;
            leftHoldFiredRef.current = false;
            if (leftHoldTimerRef.current) clearTimeout(leftHoldTimerRef.current);
            leftHoldTimerRef.current = setTimeout(() => {
                leftHoldFiredRef.current = true;
                toggleFav(chId);
                setFavPulse(chId);
                setRailFocus('favourites'); // visually confirm the action on the rail
                setTimeout(() => setFavPulse(null), 900);
            }, 550);
        };
        const onUp = (e) => {
            if (e.key !== 'ArrowLeft') return;
            if (leftHoldTimerRef.current) {
                clearTimeout(leftHoldTimerRef.current);
                leftHoldTimerRef.current = null;
            }
            // Tap (released before long-press timer fired) → open rail.
            if (!leftHoldFiredRef.current) {
                // Only valid if the user was on the live cell when
                // they pressed.  Re-check the current focus owner
                // and that the rail isn't already showing.
                const ae = document.activeElement;
                if (ae && ae.classList && ae.classList.contains('fta-cell')) {
                    const row = ae.closest('.fta-row');
                    const cells = row ? Array.from(row.querySelectorAll('.fta-cell')) : [];
                    if (cells.indexOf(ae) === 0) {
                        setRailFocus((prev) => prev || 'categories');
                    }
                }
            }
        };
        window.addEventListener('keydown', onDown, true);
        window.addEventListener('keyup', onUp, true);
        return () => {
            window.removeEventListener('keydown', onDown, true);
            window.removeEventListener('keyup', onUp, true);
            if (leftHoldTimerRef.current) clearTimeout(leftHoldTimerRef.current);
        };
    }, [toggleFav]);

    /* v2.8.100 — auto-focus the first channel cell on app open so
       D-pad nav starts on the live programme of the first channel
       (the user explicitly asked: "when the app opens the focus
       needs to be on the first channel").  Runs on the FIRST paint
       after the EPG renders AND every time the user switches
       category (`tab` change resets `hasAutoFocused` below). */
    const [hasAutoFocused, setHasAutoFocused] = useState(false);
    useEffect(() => {
        if (loading || hasAutoFocused || !visibleChannels.length) return;
        const t = setTimeout(() => {
            const firstCell = document.querySelector('.fta-row .fta-cell');
            if (firstCell) {
                try {
                    firstCell.focus({ preventScroll: true });
                    setHasAutoFocused(true);
                    const scroller = document.querySelector('.fta-grid-rows');
                    if (scroller) scroller.scrollLeft = 0;
                    // Also scroll back to the very top so the first
                    // channel is the one in focus visually.
                    if (scroller) scroller.scrollTop = 0;
                } catch { /* */ }
            }
        }, 250);
        return () => clearTimeout(t);
    }, [loading, visibleChannels.length, hasAutoFocused]);

    /* v2.8.101 — when the user switches category (Live TV → Kids,
       Favourites → Live TV, etc.) the visible channel list changes
       completely.  Reset the autofocus guard so the next effect
       run lands focus on the new first channel and snaps the grid
       back to the live column. */
    useEffect(() => {
        setHasAutoFocused(false);
    }, [tab]);

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

    /* v2.8.101 — D-pad nav rewritten per the user's latest feedback:
         Up/Down  → move to the row above/below at the SAME time
                    column (cell whose horizontal range straddles
                    the current cell's left edge).  No more
                    snapping-to-live-cell — they reported it was
                    "skipping ahead to what's next".  When you
                    walk down from a future cell, you stay in the
                    future column.
         Right    → next cell to the right in the same row, grid
                    scrolls horizontally to follow.
         Left     → previous cell in the same row.  When already on
                    the leftmost (live) cell, LEFT opens the side
                    menu.  Walking left across many cells naturally
                    pulls scrollLeft back to 0 (the live column),
                    fixing the user's "live cells get cut off when
                    you come back from the future" complaint —
                    every left-arrow brings the grid back one cell
                    width until you're flush against the rail.
         Holding-key  → all scrolls use behavior:'auto' (instant)
                    instead of 'smooth' so 30Hz key auto-repeat
                    doesn't queue overlapping animations.
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
            const scroller = row.closest('.fta-grid-rows');

            /* Move focus + sync the grid scroll position so the
               focused cell sits just past the channel rail. */
            const focusAndScroll = (next) => {
                if (!next) return;
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') {
                    e.stopImmediatePropagation();
                }
                try {
                    next.focus({ preventScroll: true });
                    if (scroller) {
                        const isFirstInRow = next === next.closest('.fta-row')
                            ?.querySelector('.fta-cell');
                        const cellLeft = parseFloat(next.style.left || '0');
                        const target = isFirstInRow ? 0 : Math.max(0, cellLeft - 4);
                        // Instant scroll — `behavior: smooth` queues
                        // overlapping animations under D-pad
                        // auto-repeat and feels chunky.
                        scroller.scrollTo({ left: target, behavior: 'auto' });
                    }
                    next.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'auto' });
                } catch { /* */ }
            };

            if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                const cells = Array.from(row.querySelectorAll('.fta-cell'));
                const idx = cells.indexOf(ae);
                if (e.key === 'ArrowRight') {
                    focusAndScroll(cells[idx + 1]);
                } else {
                    if (idx <= 0) {
                        // v2.8.103 — defer the rail-open decision to
                        // keyup: a tap (released < 550 ms) opens the
                        // rail, a hold (still pressed at 550 ms)
                        // toggles favourite.  See the dedicated
                        // long-press effect below.
                        e.preventDefault();
                        e.stopPropagation();
                        if (typeof e.stopImmediatePropagation === 'function') {
                            e.stopImmediatePropagation();
                        }
                    } else {
                        focusAndScroll(cells[idx - 1]);
                    }
                }
                return;
            }

            // Up / Down — find the cell in the adjacent row.
            // Selection rules:
            //   1. If the source cell is the LIVE cell (idx 0 of its
            //      row, the leftmost programme that's currently
            //      airing), the target is the LIVE cell of the
            //      adjacent row.  Live cells can be very narrow
            //      (e.g. a programme ending in 2 minutes is only
            //      ~18px wide), so the geometric probe used to fall
            //      OUTSIDE them and land on the next future cell,
            //      yanking focus into the future column.  The user
            //      reported exactly this: "when scrolling down, as
            //      soon as it gets to a certain section, it skips
            //      all the way across to the next thing".
            //   2. Otherwise (source is a future cell) use the
            //      geometric matcher — keep the user in the same
            //      time column.
            const targetRow = e.key === 'ArrowDown'
                ? row.nextElementSibling
                : row.previousElementSibling;
            if (!targetRow || !targetRow.classList || !targetRow.classList.contains('fta-row')) return;
            const targetCells = Array.from(targetRow.querySelectorAll('.fta-cell'));
            if (!targetCells.length) return;

            const sourceCells = Array.from(row.querySelectorAll('.fta-cell'));
            const sourceIsLive = sourceCells.indexOf(ae) === 0;
            if (sourceIsLive) {
                focusAndScroll(targetCells[0]);
                return;
            }

            const curLeft = parseFloat(ae.style.left || '0');
            const curWidth = parseFloat(ae.style.width || '0');
            const probe = curLeft + Math.min(40, curWidth / 4);
            let best = null;
            for (const cell of targetCells) {
                const l = parseFloat(cell.style.left || '0');
                const w = parseFloat(cell.style.width || '0');
                if (l <= probe && probe < l + w) { best = cell; break; }
            }
            if (!best) {
                // No overlapping cell in target row — pick the live cell
                // so the user lands somewhere obviously useful.
                best = targetCells[0];
            }
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
            <IconRail
                focus={railFocus}
                /* v2.8.105 — also keep the rail expanded while the
                   categories submenu is open so the icons don't
                   collapse out from under the user's finger. */
                forceExpanded={sideMenuOpen}
                tab={tab}
                favPulse={!!favPulse}
                onChangeFocus={setRailFocus}
                onPickCategories={() => setSideMenuOpen(true)}
                onToggleFavourites={() => setTab((t) => t === 'favourites' ? 'live' : 'favourites')}
                onRefresh={() => setRefreshKey((k) => k + 1)}
                onReturnToEpg={() => {
                    setRailFocus(null);
                    setTimeout(() => {
                        const first = document.querySelector('.fta-row .fta-cell');
                        if (first) try { first.focus({ preventScroll: true }); } catch { /* */ }
                    }, 20);
                }}
            />

            <div className="fta-clock-strip" data-testid="fta-clock-strip">
                <span className="fta-clock-strip__city" data-testid="fta-clock-strip-city">{city}</span>
                <span className="fta-clock-strip__time" data-testid="fta-clock-strip-time">{fmtTime(now)}</span>
            </div>

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
                    {/* v2.8.107 — no in-app loading overlay per user
                        feedback ("I don't want two splash screens").
                        The native Android splash holds until the
                        page is ready (≤4 s); after it dismisses we
                        just render the EPG (empty until fetch
                        completes — typically <300 ms). */}
                    {!loading && (
                        <>
                            <GridHeader
                                gridStartMs={gridStartMs}
                                gridEndMs={gridEndMs}
                                gridRowsRef={gridRowsRef}
                                now={now}
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
                    onPick={(id) => { setTab(id); setSideMenuOpen(false); setRailFocus(null); }}
                    onClose={(target) => {
                        setSideMenuOpen(false);
                        if (target === 'rail') {
                            setRailFocus('categories');
                        } else {
                            setRailFocus(null);
                            setTimeout(() => {
                                const first = document.querySelector('.fta-row .fta-cell');
                                if (first) try { first.focus({ preventScroll: true }); } catch { /* */ }
                            }, 20);
                        }
                    }}
                />
            )}
        </div>
    );
}

/* ----------------------- side menu -------------------------------- */
function SideMenu({ categories, currentTab, onPick, onClose }) {
    const items = useMemo(() => {
        /* v2.8.103 — Favourites is now its own rail icon, not a
           category here.  Categories list only. */
        return (categories || []).map((c) => ({ id: c.id, label: c.label, count: c.count }));
    }, [categories]);

    /* Focus the current tab on mount + handle BACK only.
       v2.8.106 — per user feedback the submenu must STAY OPEN while
       navigating with the D-pad.  We used to close on LEFT (return
       to rail) and RIGHT (back to EPG), but that made the menu feel
       like it was instantly disappearing every time the user
       pressed an arrow.  Now ONLY Escape / Backspace dismiss it —
       UP / DOWN walk the list (handled by useSpatialFocus), LEFT /
       RIGHT do nothing inside the menu, and picking an item
       implicitly closes via onPick. */
    const firstRef = useRef(null);
    useEffect(() => {
        const t = setTimeout(() => firstRef.current?.focus(), 30);
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                onClose('epg');
            } else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
                /* Swallow horizontal arrows so they don't bubble into
                   the global spatial-focus engine and yank focus out
                   of the submenu (which is what the user reported as
                   "the menu instantly closes when I push left"). */
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => { clearTimeout(t); window.removeEventListener('keydown', onKey, true); };
    }, [onClose]);

    return (
        <div className="fta-side-menu" data-testid="fta-side-menu">
            <div className="fta-side-menu__title">Categories</div>
            <div className="fta-side-menu__list" role="menu">
                {items.map((it) => (
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

/* ----------------------- left icon rail --------------------------
   v2.8.104 — Now mirrors the Vesper / Tunes SideNav exactly so the
   whole TV suite feels like one app:
     • Same 76 px collapsed / 240 px expanded width
     • Same brand wordmark with the glowing "V2" emblem
     • Same icon container: 36 × 36 square holding a 20 px lucide
       icon with strokeWidth 1.7 (active = 2.2)
     • Same dwell-then-expand behaviour (no expansion on a quick
       LEFT-then-RIGHT round trip)
     • Same label fade (300 ms opacity transition once expanded)
   Three icons: Categories · Favourites · Refresh.  No other entries
   — this is the Free-to-Air-specific rail, not the global Vesper
   nav. */
function IconRail({ focus, forceExpanded, tab, favPulse, onChangeFocus, onPickCategories, onToggleFavourites, onRefresh, onReturnToEpg }) {
    const items = useMemo(() => ([
        { id: 'categories', label: 'Categories', Icon: LayoutGrid },
        { id: 'favourites', label: 'Favourites', Icon: Star, isOn: tab === 'favourites' },
        { id: 'refresh',    label: 'Refresh',    Icon: RotateCw },
    ]), [tab]);

    const refs = useRef({});

    /* When the rail becomes focused, move DOM focus to the matching
       icon.  When un-focused, do nothing — the parent has already
       handed focus back to the EPG. */
    useEffect(() => {
        if (!focus) return;
        const el = refs.current[focus];
        if (el) try { el.focus({ preventScroll: true }); } catch { /* */ }
    }, [focus]);

    /* Up/Down/Right/Enter/Escape handling for rail-focused state. */
    useEffect(() => {
        if (!focus) return;
        const onKey = (e) => {
            const ae = document.activeElement;
            const onRail = ae && ae.getAttribute('data-rail-icon');
            if (!onRail) return;
            const idx = items.findIndex((it) => it.id === onRail);
            if (idx < 0) return;

            if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                const next = e.key === 'ArrowDown' ? Math.min(items.length - 1, idx + 1)
                                                   : Math.max(0, idx - 1);
                onChangeFocus(items[next].id);
            } else if (e.key === 'ArrowRight' || e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                onReturnToEpg();
            } else if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                e.stopPropagation();
                if (typeof e.stopImmediatePropagation === 'function') e.stopImmediatePropagation();
                if (onRail === 'categories') onPickCategories();
                if (onRail === 'favourites') onToggleFavourites();
                if (onRail === 'refresh')    onRefresh();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [focus, items, onChangeFocus, onPickCategories, onToggleFavourites, onRefresh, onReturnToEpg]);

    const isExpanded = !!focus || !!forceExpanded;

    return (
        <nav
            data-testid="fta-rail"
            className={`fta-rail ${isExpanded ? 'is-focused' : ''}`}
            style={{
                width: isExpanded ? 240 : 76,
                background: isExpanded
                    ? 'linear-gradient(90deg, rgba(8,11,20,0.98) 0%, rgba(8,11,20,0.95) 60%, rgba(8,11,20,0.0) 100%)'
                    : 'transparent',
            }}
        >
            {/* v2.8.106 — brand wordmark redesigned per user mockup:
                pure type — red "V2" + white "Free-to-Air".  No TV
                icon (the mockup is a wordmark-only logo). */}
            <div className="fta-rail__brandmark" style={{ height: 56 }}>
                <div
                    className="fta-rail__brand-text"
                    style={{
                        opacity: isExpanded ? 1 : 0,
                        maxWidth: isExpanded ? 220 : 0,
                        marginLeft: 0,
                        transition:
                            'opacity 220ms ease 80ms, max-width 240ms ease',
                    }}
                >
                    <span className="fta-rail__brand-v2">V2</span>
                    <span className="fta-rail__brand-suffix">Free-to-Air</span>
                </div>
                {/* Collapsed-state mark: just a bold red "V2" so the
                    rail still has identity when the icons are
                    centred. */}
                {!isExpanded && (
                    <span className="fta-rail__brand-collapsed">V2</span>
                )}
            </div>

            <div className="fta-rail__items">
                {items.map((it) => {
                    const I = it.Icon;
                    const isActive = !!it.isOn;
                    const isFocused = focus === it.id;
                    return (
                        <button
                            key={it.id}
                            ref={(el) => { refs.current[it.id] = el; }}
                            data-testid={`fta-rail-${it.id}`}
                            data-rail-icon={it.id}
                            data-focusable="true"
                            data-focus-style="nav"
                            tabIndex={0}
                            aria-label={it.label}
                            className={`fta-rail__btn ${isActive ? 'is-on' : ''} ${isFocused ? 'is-focused' : ''} ${(favPulse && it.id === 'favourites') ? 'is-favourite-pulse' : ''}`}
                            onClick={() => {
                                if (it.id === 'categories') onPickCategories();
                                if (it.id === 'favourites') onToggleFavourites();
                                if (it.id === 'refresh')    onRefresh();
                            }}
                        >
                            <span className="fta-rail__btn-icon-wrap">
                                <I
                                    size={20}
                                    strokeWidth={isActive ? 2.2 : 1.7}
                                    fill={isActive && it.id === 'favourites' ? 'currentColor' : 'none'}
                                />
                            </span>
                            <span
                                className="fta-rail__btn-label"
                                style={{ opacity: isExpanded ? 1 : 0 }}
                            >
                                {it.label}
                            </span>
                        </button>
                    );
                })}
            </div>
        </nav>
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
    /* v2.8.98 — per user feedback the top bar is now MUCH cleaner:
       just the brand wordmark on the left, two top-level views
       (Free-to-Air vs Favourites), and the city / clock on the
       right.  All category filtering (Kids, Sport, News, Drama …)
       lives in the slide-in side menu where the D-pad LEFT
       gesture exposes it. */
    const liveCount = useMemo(() => {
        const live = (categories || []).find((c) => c.id === 'live');
        return live?.count ?? null;
    }, [categories]);
    const favCount = useMemo(() => {
        const f = (categories || []).find((c) => c.id === 'favourites');
        return f?.count ?? null;
    }, [categories]);
    const tabs = useMemo(() => ([
        { id: 'live', label: 'Free-to-Air', count: liveCount },
        { id: 'favourites', label: 'Favourites', count: favCount },
    ]), [liveCount, favCount]);

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
                    {tabs.map((t) => {
                        // Treat any non-favourites category tab as "Free-to-Air".
                        const isActive = t.id === 'favourites'
                            ? tab === 'favourites'
                            : tab !== 'favourites';
                        return (
                            <button
                                key={t.id}
                                data-testid={`fta-tab-${t.id}`}
                                data-focusable="true"
                                tabIndex={0}
                                role="tab"
                                aria-selected={isActive}
                                onClick={() => onTab(t.id)}
                                className={`fta-tab ${isActive ? 'is-active' : ''}`}
                            >
                                <span>{t.label}</span>
                                {t.count != null && t.count > 0 && (
                                    <span className="fta-tab__count">{t.count}</span>
                                )}
                            </button>
                        );
                    })}
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
                    {/* v2.8.103 — heart button removed per user feedback
                        ("I don't need there to be a love heart there either").
                        Favouriting is now done by long-pressing LEFT on a
                        live cell (handled in the FreeToAir keydown effect). */}
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
            /* v2.8.100 — more aggressive buffering targets so the
               first frame appears sooner.  startLevel:-1 picks the
               smallest variant first (faster handshake), then ABR
               steps up once the buffer is healthy.  Reduces the
               "first click does nothing" perception the user
               reported. */
            hls = new Hls({
                enableWorker: true,
                lowLatencyMode: false,
                startLevel: -1,
                maxBufferLength: 6,
                maxMaxBufferLength: 30,
                manifestLoadingTimeOut: 8000,
                fragLoadingTimeOut: 12000,
            });
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
          fade the video on top.
        - v2.8.100: when armed && !streamReady, show a centred
          spinner so the user knows "loading…" — the previous
          version showed only cover art during the HLS handshake,
          which made the first click feel unresponsive on slower
          channels (user clicked again thinking nothing happened). */
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
            {armed && !streamReady && (
                <div className="fta-preview-loading" data-testid="fta-preview-loading">
                    <div className="fta-preview-loading__spinner" />
                    <div className="fta-preview-loading__label">Tuning in…</div>
                </div>
            )}
        </>
    );
}

/* ---------------------------- grid header ------------------------- */
function GridHeader({ gridStartMs, gridEndMs, gridRowsRef, now }) {
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
    const nowOffsetPx = ((now - gridStartMs) / 60000) * PX_PER_MIN;

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
                {/* v2.8.99 — NOW pill lives in the time strip now so
                    it never overlaps the live cell title in the rows
                    below.  Same translate-X parent as the time slots
                    so it tracks the horizontal scroll automatically. */}
                <div
                    className="fta-grid-header__now-pill"
                    style={{ left: nowOffsetPx }}
                >
                    {fmtTime(now)}
                </div>
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
                {/* Vertical NOW line — the pill label is rendered in
                    the header strip (GridHeader) so it never overlaps
                    a live cell title. */}
                <div
                    className="fta-now-line"
                    style={{ left: CHANNEL_RAIL_W + nowOffsetPx }}
                />

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
    /* v2.8.99 — some MJH channel rows ship with logo URLs that no
       longer resolve (typo, dead repo branch, …).  Track per-row
       whether the <img> failed and swap to a styled text fallback
       so the user sees the channel name + LCN, not the raw `alt`. */
    const [logoFailed, setLogoFailed] = useState(false);

    return (
        <div className="fta-row" data-channel-id={channel.id}>
            <div className="fta-row__rail">
                <div className="fta-row__rail-inner">
                    {channel.logo && !logoFailed
                        ? <img
                            src={channel.logo}
                            alt={channel.name}
                            loading="lazy"
                            onError={() => setLogoFailed(true)}
                          />
                        : <span className="fta-row__rail-fallback">{channel.name}</span>}
                </div>
                {channel.lcn && (
                    <span className="fta-row__rail-lcn">{channel.lcn}</span>
                )}
            </div>
            <div className="fta-row__cells">
                {programmes.length === 0 && (
                    <button
                        data-testid={`fta-cell-${channel.id}-empty`}
                        data-focusable="true"
                        data-focus-style="cell"
                        tabIndex={0}
                        className="fta-cell fta-cell--empty"
                        style={{
                            position: 'absolute',
                            left: 4,
                            width: 300,
                            top: 5,
                        }}
                        onFocus={() => onFocus({
                            title: 'No programme info',
                            start: now,
                            stop: now + 30 * 60 * 1000,
                            desc: 'Live channel — no EPG data available.',
                        })}
                        onClick={() => onOpen({
                            title: 'No programme info',
                            start: now,
                            stop: now + 30 * 60 * 1000,
                        })}
                    >
                        <div className="fta-cell__title">No programme info</div>
                        <div className="fta-cell__subtitle">Live channel · press OK to play</div>
                    </button>
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
