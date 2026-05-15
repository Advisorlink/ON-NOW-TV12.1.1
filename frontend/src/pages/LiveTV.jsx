/**
 * Live TV — V2.3 design rebuild.
 *
 *   ── Layout ──
 *     Hero (fixed at top, never scrolls):
 *       • Full-bleed TMDB programme backdrop (debounced, cached)
 *       • Eyebrow · Channel name · NOW time · synopsis · progress bar
 *       • UP NEXT line · big "Watch full-screen" pill
 *       • Top-right utility buttons (★ favourite · ⟳ refresh · ↪ exit)
 *     Body (only this scrolls inside its 3 columns):
 *       • Categories pill list (Favourites pinned, then real cats)
 *       • Channels pill cards (logo + ch# + name + NOW + progress)
 *       • Guide column grouped by TODAY / TOMORROW (reminder rows)
 *
 *   ── Perf ──
 *     Same proven primitives from v2.2.2:
 *       • Hot data in refs, only `sel` state in the hot path
 *       • All row + column components React.memo'd
 *       • Stable rowFn callbacks via useCallback
 *       • Stable EMPTY_ARRAY for "no EPG" case
 *       • Guide column debounced 120 ms — settles, then renders
 *       • TMDB backdrop fires only after settle + cache by title
 *
 *   ── Keypad ──
 *     Initial focus: col 0 (categories), idx 0.
 *     ←/→ moves between columns; ↑/↓ within column; Enter plays a
 *     channel or toggles a reminder; F toggles favourite.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Star, Calendar, Bell, RefreshCw, LogOut, Search } from 'lucide-react';
import SideNav from '@/components/SideNav';
import XtreamLogin from '@/components/XtreamLogin';
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
import { getRecents, pushRecent } from '@/lib/liveRecents';
import {
    getReminders,
    toggleReminder,
    pruneStale,
} from '@/lib/liveReminders';
import {
    loadCategories,
    saveCategories,
    loadChannels,
    saveChannels,
    loadEpg,
    mergeAndSaveEpg,
} from '@/lib/liveCache';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import Host from '@/lib/host';

const ROW_H = 32;            // category row height
const CHAN_H = 54;           // channel card height
const GUIDE_ROW_H = 40;      // guide row height
const BUFFER = 4;
const FAV_CAT = '__fav__';
const REC_CAT = '__rec__';
const REM_CAT = '__rem__';
const EMPTY_ARRAY = [];

/* ─────────────────────────── Page shell ─────────────────────────── */

export default function LiveTV() {
    const [provider, setProvider] = useState(() => getActiveProvider());
    const navigate = useNavigate();
    const handleLogout = useCallback(() => setProvider(null), []);
    const handleAuthed = useCallback((p) => setProvider(p), []);

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

    return (
        <div style={{
            position: 'fixed',
            inset: 0,
            background: '#0A0F1A',
            color: '#E6EAF2',
            overflow: 'hidden',
        }}>
            <SideNav />
            <main style={{
                position: 'absolute',
                inset: '0 0 0 100px',
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden',
            }}>
                {provider
                    ? <Grid provider={provider} onLogout={handleLogout} />
                    : <XtreamLogin onAuthed={handleAuthed} />}
            </main>
        </div>
    );
}

/* ─────────────────────────────── Grid ─────────────────────────────── */

function Grid({ provider, onLogout }) {
    const navigate = useNavigate();

    /* Hot data — never re-rendered. */
    const cats = useRef([]);
    const channelsByCat = useRef(new Map());
    const epg = useRef(new Map());

    /* Synchronous hydrate. */
    if (cats.current.length === 0) {
        const c = loadCategories(provider.id) || [];
        const ch = loadChannels(provider.id) || {};
        const e = loadEpg(provider.id) || {};
        cats.current = c;
        for (const k in ch) channelsByCat.current.set(k, ch[k]);
        const nowSec = Math.floor(Date.now() / 1000);
        for (const sid in e) {
            const arr = (e[sid] || []).filter((it) => Number(it.stopTimestamp || 0) > nowSec);
            if (arr.length) epg.current.set(Number(sid) || sid, arr);
        }
    }

    /* Selection — initial focus on TOP CATEGORY. */
    const [sel, setSel] = useState(() => ({
        col: 0,                 // Categories column
        catIdx: 0,              // Top of the list (Favourites or first cat)
        chanIdx: 0,
        guideIdx: 0,
    }));

    const [query, setQuery] = useState('');
    const [syncing, setSyncing] = useState(false);

    const [favs, setFavs] = useState(() => new Set(getFavList(provider.id).map(String)));
    const [recents, setRecents] = useState(() => getRecents(provider.id).map(String));
    const [reminders, setReminders] = useState(() => pruneStale(provider.id));
    const reminderKeys = useMemo(
        () => new Set(reminders.map((r) => r.id)),
        [reminders],
    );

    /* Unique stream IDs that have at least one reminder set — used
     * both as a sidebar entry count and to resolve the channel
     * list when the user selects the Reminders pseudo-category. */
    const reminderStreamIds = useMemo(() => {
        const seen = new Set();
        for (const r of reminders) seen.add(String(r.streamId));
        return seen;
    }, [reminders]);

    const sidebarCats = useMemo(
        () => buildSidebarCats(cats.current, favs.size, recents.length, reminderStreamIds.size, channelsByCat.current),
        [favs, recents, reminderStreamIds, cats.current.length, channelsByCat.current.size],
    );

    const allChannels = useMemo(() => {
        const cat = sidebarCats[sel.catIdx];
        if (!cat) return EMPTY_ARRAY;
        if (cat.id === FAV_CAT) return resolveByIds(favs, channelsByCat.current);
        if (cat.id === REC_CAT) return resolveByIds(new Set(recents), channelsByCat.current, recents);
        if (cat.id === REM_CAT) return resolveByIds(reminderStreamIds, channelsByCat.current);
        return channelsByCat.current.get(cat.id) || EMPTY_ARRAY;
    }, [sel.catIdx, favs, recents, reminderStreamIds, sidebarCats, channelsByCat.current.size]);

    const channels = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return allChannels;
        const isNum = /^\d+$/.test(q);
        return allChannels.filter((c) => {
            const n = (c.name || '').toLowerCase();
            return n.includes(q) || (isNum && c.num != null && String(c.num).includes(q));
        });
    }, [allChannels, query]);

    const focusedChannel = channels[Math.min(sel.chanIdx, channels.length - 1)] || null;

    /* Debounced guide channel (120 ms after focus settles).  Skips
     * re-renders of the right column during fast scrubbing. */
    const [debouncedChannel, setDebouncedChannel] = useState(focusedChannel);
    useEffect(() => {
        const t = setTimeout(() => setDebouncedChannel(focusedChannel), 120);
        return () => clearTimeout(t);
    }, [focusedChannel]);

    /* On-demand EPG fetch — fires 200 ms after the user settles on
     * a channel that has no cached EPG.  Lands in epg.current
     * (same map the prefetch fills), so a subsequent focus on the
     * same channel is instant.  Cancelled if the user moves on. */
    const epgReqId = useRef(0);
    useEffect(() => {
        const ch = debouncedChannel;
        if (!ch) return undefined;
        if (epg.current.has(ch.stream_id)) return undefined;
        const myReq = ++epgReqId.current;
        const t = setTimeout(async () => {
            try {
                const items = await getFullEpg(provider, ch.stream_id, 12);
                if (epgReqId.current !== myReq) return;
                if (items && items.length) {
                    epg.current.set(ch.stream_id, items);
                    mergeAndSaveEpg(provider.id, { [ch.stream_id]: items });
                    rerender();
                }
            } catch { /* swallow */ }
        }, 200);
        return () => clearTimeout(t);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [debouncedChannel, provider]);

    /* Visible-channels EPG prefetch — fills in EPG for the first
     * ~20 channels of the active category in PARALLEL so progress
     * bars + "NOW" lines appear across all of them within a couple
     * of seconds, not one-at-a-time.  Re-runs whenever the user
     * lands on a new category.  Batches re-renders every 4
     * completions so the UI fills in incrementally (you see the
     * top cards light up first, then the rest as they arrive). */
    useEffect(() => {
        const sample = channels.slice(0, 20);
        const missing = sample.filter((c) => !epg.current.has(c.stream_id));
        if (missing.length === 0) return undefined;
        let cancel = false;
        (async () => {
            const CONC = 6;
            let cursor = 0;
            let sinceLastFlush = 0;
            const worker = async () => {
                while (!cancel) {
                    const i = cursor++;
                    if (i >= missing.length) return;
                    const ch = missing[i];
                    try {
                        const items = await getFullEpg(provider, ch.stream_id, 12);
                        if (cancel) return;
                        if (items && items.length) {
                            epg.current.set(ch.stream_id, items);
                            mergeAndSaveEpg(provider.id, { [ch.stream_id]: items });
                            sinceLastFlush += 1;
                            // Incremental UI update — every 4 hits
                            // we re-render so cards light up as
                            // they arrive instead of waiting for
                            // the whole batch.
                            if (sinceLastFlush >= 4) {
                                sinceLastFlush = 0;
                                if (!cancel) rerender();
                            }
                        }
                    } catch { /* swallow */ }
                }
            };
            const workers = [];
            for (let i = 0; i < CONC; i++) workers.push(worker());
            await Promise.all(workers);
            if (!cancel && sinceLastFlush > 0) rerender();
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sel.catIdx, channels.length]);

    const guideItems = debouncedChannel
        ? (epg.current.get(debouncedChannel.stream_id) || EMPTY_ARRAY)
        : EMPTY_ARRAY;

    /* Group guide entries by day for the TODAY / TOMORROW headers. */
    const guideGroups = useMemo(() => groupByDay(guideItems), [guideItems]);
    /* Blue label above the GUIDE header — shows today's date in
     * the format the user asked for ("TODAY · WED 15 MAY"). */
    const guideTodayLabel = useMemo(() => {
        const d = new Date();
        return `TODAY · ${formatDayLabel(d)}`;
    }, []);

    /* ───────── Background sync ───────── */
    useEffect(() => {
        let cancel = false;
        (async () => {
            setSyncing(true);
            try {
                await authenticate(provider);
                if (cancel) return;

                const list = await getCategories(provider, 'live');
                if (cancel) return;
                if (Array.isArray(list) && list.length) {
                    cats.current = list;
                    saveCategories(provider.id, list);
                    rerender();
                }

                const fetched = {};
                const BATCH = 4;
                for (let i = 0; i < list.length; i += BATCH) {
                    if (cancel) return;
                    const slice = list.slice(i, i + BATCH);
                    await Promise.all(slice.map(async (cat) => {
                        try {
                            const ch = await getStreams(provider, 'live', cat.category_id);
                            const arr = Array.isArray(ch) ? ch : [];
                            channelsByCat.current.set(cat.category_id, arr);
                            fetched[cat.category_id] = arr;
                        } catch { /* keep stale */ }
                    }));
                }
                if (cancel) return;
                if (Object.keys(fetched).length) saveChannels(provider.id, fetched);
                rerender();

                const sids = [];
                const seen = new Set();
                for (const arr of channelsByCat.current.values()) {
                    for (const c of (arr || [])) {
                        const k = String(c.stream_id);
                        if (!seen.has(k)) { seen.add(k); sids.push(c.stream_id); }
                    }
                }
                const startedAt = Date.now();
                const HARD_CAP = 120_000;
                const buffer = {};
                let bufferDirty = 0;
                let cursor = 0;
                const flush = () => {
                    if (bufferDirty === 0) return;
                    mergeAndSaveEpg(provider.id, buffer);
                    for (const k in buffer) delete buffer[k];
                    bufferDirty = 0;
                };
                const worker = async () => {
                    while (!cancel) {
                        const i = cursor++;
                        if (i >= sids.length) return;
                        if (Date.now() - startedAt > HARD_CAP) return;
                        const sid = sids[i];
                        try {
                            const items = await getFullEpg(provider, sid, 12);
                            if (cancel) return;
                            if (items && items.length) {
                                epg.current.set(sid, items);
                                buffer[sid] = items;
                                bufferDirty += 1;
                                if (bufferDirty >= 25) flush();
                            }
                        } catch { /* swallow */ }
                    }
                };
                const workers = [];
                for (let i = 0; i < 6; i++) workers.push(worker());
                await Promise.all(workers);
                if (!cancel) flush();
            } finally {
                if (!cancel) setSyncing(false);
            }
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider]);

    /* `bump` is a cheap re-render counter — incremented whenever
     * we mutate epg.current so the memoised renderChannel can
     * regenerate, which in turn changes ChannelCard's `now` prop
     * so React.memo doesn't short-circuit and the new EPG data
     * appears on every visible card. */
    const [bump, setBump] = useState(0);
    const rerender = useCallback(() => setBump((b) => b + 1), []);

    /* ───────── Handlers ───────── */
    const onToggleFav = useCallback(() => {
        if (!focusedChannel) return;
        toggleFavorite(provider.id, focusedChannel.stream_id);
        setFavs(new Set(getFavList(provider.id).map(String)));
    }, [focusedChannel, provider]);

    const onToggleReminder = useCallback((item) => {
        if (!debouncedChannel || !item?.startTimestamp) return;
        toggleReminder(provider.id, debouncedChannel.stream_id, {
            channelName: debouncedChannel.name,
            title: item.title,
            startTs: item.startTimestamp,
            stopTs: item.stopTimestamp,
        });
        setReminders(getReminders(provider.id));
    }, [debouncedChannel, provider]);

    const playChannel = useCallback(async (ch) => {
        if (!ch) return;
        const url = await getStreamUrl(provider, 'live', ch.stream_id, 'ts');
        if (!url) return;
        try {
            pushRecent(provider.id, ch.stream_id);
            setRecents(getRecents(provider.id).map(String));
        } catch { /* ignore */ }
        if (Host.playVideo({
            url, title: ch.name, type: 'live',
            cwId: `live:${provider.id}:${ch.stream_id}`,
        })) return;
        navigate(`/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(ch.name)}&type=live`);
    }, [provider, navigate]);

    const onRefresh = useCallback(() => {
        // Cheapest meaningful "refresh" — clear in-memory + persistent
        // EPG, then trigger a sync by re-running the effect.  Refs
        // are reset so the next render reads from disk again.
        try {
            localStorage.removeItem(`onnowtv-livecache-v1:${provider.id}:epg`);
        } catch { /* ignore */ }
        epg.current.clear();
        rerender();
    }, [provider, rerender]);

    /* ───────── Keyboard ─────────
     *
     * Long-press support for Enter/Space — most TV remotes report
     * a held button as repeated keydown events.  When the same
     * Enter key arrives ≥ 6 times in quick succession (≈ 600 ms),
     * we interpret it as a long-press and toggle the favourite
     * on the focused channel instead of playing it.
     *
     * On keyup we reset the counter so a fresh press starts over.
     * The play / reminder action only fires on keyup (so short
     * taps still work as before, and a long-press doesn't both
     * play AND favourite).
     */
    const pressRef = useRef({ key: '', count: 0, fired: false });

    useEffect(() => {
        const onKey = (e) => {
            const tag = (document.activeElement?.tagName || '').toLowerCase();

            if (tag === 'input') {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    document.activeElement.blur();
                    setSel((s) => ({ ...s, col: 1, chanIdx: 0 }));
                    return;
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    document.activeElement.blur();
                    if (query) setQuery('');
                    return;
                }
                return;
            }

            if (e.key === '/') {
                e.preventDefault();
                document.querySelector('[data-testid="live-tv-search"]')?.focus();
                return;
            }
            if (e.key === 'f' || e.key === 'F') {
                e.preventDefault();
                onToggleFav();
                return;
            }

            const key = e.key;
            if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                key !== 'ArrowLeft' && key !== 'ArrowRight' &&
                key !== 'Enter' && key !== ' ') return;

            e.preventDefault();
            e.stopPropagation();

            /* Enter / Space — track repeat count.  Action fires on
             * keyup; long-press toggles favourite. */
            if (key === 'Enter' || key === ' ') {
                const p = pressRef.current;
                if (p.key !== key) {
                    p.key = key;
                    p.count = 1;
                    p.fired = false;
                } else {
                    p.count += 1;
                }
                // Long-press threshold: 6 repeats ≈ 600 ms on most
                // TV firmwares.  Fire the favourite toggle once,
                // mark `fired` so the keyup handler skips its
                // default play action.
                if (p.count >= 6 && !p.fired && sel.col === 1 && focusedChannel) {
                    p.fired = true;
                    onToggleFav();
                }
                return;
            }

            setSel((s) => {
                if (key === 'ArrowLeft') {
                    if (s.col === 0) {
                        const nav = document.querySelector('[data-testid="side-nav"] [data-focusable="true"]');
                        nav?.focus();
                        return s;
                    }
                    return { ...s, col: s.col - 1 };
                }
                if (key === 'ArrowRight') {
                    if (s.col === 2) return s;
                    if (s.col === 0 && channels.length === 0) return s;
                    return { ...s, col: s.col + 1 };
                }
                if (key === 'ArrowUp') {
                    if (s.col === 0) {
                        return { ...s, catIdx: prevNavigableIdx(sidebarCats, s.catIdx) };
                    }
                    if (s.col === 1) return { ...s, chanIdx: Math.max(0, s.chanIdx - 1) };
                    return { ...s, guideIdx: prevNavigableIdx(guideGroups, s.guideIdx) };
                }
                if (key === 'ArrowDown') {
                    if (s.col === 0) {
                        return { ...s, catIdx: nextNavigableIdx(sidebarCats, s.catIdx) };
                    }
                    if (s.col === 1) return { ...s, chanIdx: Math.min(channels.length - 1, s.chanIdx + 1) };
                    return { ...s, guideIdx: nextNavigableIdx(guideGroups, s.guideIdx) };
                }
                return s;
            });
        };

        const onKeyUp = (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            const p = pressRef.current;
            const wasLongPress = p.fired;
            // Reset state regardless.
            p.key = '';
            p.count = 0;
            p.fired = false;
            if (wasLongPress) return; // long-press already handled the favourite
            // Short tap — perform the column's default action.
            if (sel.col === 1 && focusedChannel) {
                playChannel(focusedChannel);
            } else if (sel.col === 2) {
                const it = guideGroups[sel.guideIdx];
                if (it && !it._kind && !it.kind) onToggleReminder(it);
            }
        };

        window.addEventListener('keydown', onKey, true);
        window.addEventListener('keyup', onKeyUp, true);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            window.removeEventListener('keyup', onKeyUp, true);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sel, channels, guideGroups, focusedChannel, provider, query, sidebarCats, onToggleFav, onToggleReminder, playChannel]);

    useEffect(() => { setSel((s) => ({ ...s, chanIdx: 0, guideIdx: 0 })); }, [sel.catIdx]);
    useEffect(() => { setSel((s) => ({ ...s, guideIdx: 0 })); }, [sel.chanIdx, allChannels]);

    useEffect(() => {
        if (sel.chanIdx >= channels.length && channels.length > 0) {
            setSel((s) => ({ ...s, chanIdx: 0 }));
        }
    }, [channels.length, sel.chanIdx]);

    /* Stable row renderers. */
    const renderCategory = useCallback(
        (c, i, focused) => <CategoryRow key={c.id} cat={c} focused={focused} />,
        [],
    );
    const renderChannel = useCallback(
        (c, i, focused) => {
            const nextEpg = epg.current.get(c.stream_id);
            const now = nextEpg?.[0] || null;
            return (
                <ChannelCard
                    key={c.stream_id}
                    ch={c}
                    focused={focused}
                    isFav={favs.has(String(c.stream_id))}
                    now={now}
                />
            );
        },
        // `bump` is critical here: when prefetch fills epg.current,
        // rerender() bumps the counter, this callback regenerates,
        // Column receives a new rowFn, and every visible card
        // gets a fresh `now` prop — overriding React.memo.
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [favs, bump],
    );
    const renderGuide = useCallback(
        (it, i, focused) => (
            <GuideRow
                key={`${it._kind || 'row'}-${it.id || it.startTimestamp || i}`}
                item={it}
                focused={focused}
                isReminded={it.startTimestamp ? reminderKeys.has(`${Number(debouncedChannel?.stream_id) || debouncedChannel?.stream_id}:${Number(it.startTimestamp) || it.startTimestamp}`) : false}
            />
        ),
        [reminderKeys, debouncedChannel],
    );

    const activeCat = sidebarCats[sel.catIdx] || sidebarCats[0];
    const focusedNow = focusedChannel ? (epg.current.get(focusedChannel.stream_id)?.[0] || null) : null;
    const focusedNext = focusedChannel ? (epg.current.get(focusedChannel.stream_id)?.[1] || null) : null;

    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            height: '100%',
            minHeight: 0,
        }}>
            <Hero
                channel={focusedChannel}
                now={focusedNow}
                next={focusedNext}
                isFav={focusedChannel ? favs.has(String(focusedChannel.stream_id)) : false}
                syncing={syncing}
                onToggleFav={onToggleFav}
                onRefresh={onRefresh}
                onLogout={onLogout}
            />
            <div style={{
                display: 'grid',
                gridTemplateColumns: '230px 1fr 320px',
                gap: 14,
                padding: '0 24px 24px 24px',
                flex: 1,
                minHeight: 0,
            }}>
                <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, gap: 8 }}>
                    <Column
                        testid="cats"
                        isFocused={sel.col === 0}
                        items={sidebarCats}
                        idx={sel.catIdx}
                        rowHeight={ROW_H}
                        rowFn={renderCategory}
                    />
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, gap: 8 }}>
                    <SearchRow
                        query={query}
                        onChange={setQuery}
                        resultCount={channels.length}
                        totalCount={allChannels.length}
                    />
                    <Column
                        testid="channels"
                        isFocused={sel.col === 1}
                        items={channels}
                        idx={sel.chanIdx}
                        rowHeight={CHAN_H}
                        rowFn={renderChannel}
                    />
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, gap: 8 }}>
                    {/* Top blue date label with live clock */}
                    <GuideTopBar todayLabel={guideTodayLabel} />
                    <GuideHeader channelName={debouncedChannel?.name || ''} />
                    <Column
                        testid="guide"
                        isFocused={sel.col === 2}
                        items={guideGroups}
                        idx={sel.guideIdx}
                        rowHeight={GUIDE_ROW_H}
                        rowFn={renderGuide}
                    />
                </div>
            </div>
        </div>
    );
}

/* ─────────────────────────────── Hero ─────────────────────────────── */

const Hero = React.memo(function Hero({
    channel, now, next, isFav, syncing,
    onToggleFav, onRefresh, onLogout,
}) {
    const tmdb = useProgrammeBackdrop(now?.title || '', channel?.name || '');
    const backdropUrl = tmdb?.backdrop
        ? proxyImg(tmdb.backdrop, 1200, 60)
        : '';

    const progress = computeProgress(now);
    const nowTime = formatTime(now?.startTimestamp);
    const nextTime = formatTime(next?.startTimestamp);

    return (
        <section style={{
            position: 'relative',
            minHeight: 240,
            padding: '24px 32px 14px 32px',
            overflow: 'hidden',
            flexShrink: 0,
        }}>
            {/* TMDB backdrop layer */}
            {backdropUrl && (
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        backgroundImage: `linear-gradient(90deg, #0A0F1A 0%, rgba(10,15,26,0.85) 38%, rgba(10,15,26,0.2) 100%), url(${backdropUrl})`,
                        backgroundSize: 'auto, cover',
                        backgroundPosition: 'center, center',
                        backgroundRepeat: 'no-repeat, no-repeat',
                    }}
                />
            )}
            {/* Bottom fade so hero blends into body */}
            <div aria-hidden="true" style={{
                position: 'absolute',
                inset: 'auto 0 0 0',
                height: 80,
                background: 'linear-gradient(180deg, transparent 0%, #0A0F1A 100%)',
            }} />

            {/* Top-right utility cluster */}
            <div style={{
                position: 'absolute',
                top: 24,
                right: 32,
                display: 'flex',
                gap: 8,
                zIndex: 2,
            }}>
                <HeroIconButton
                    label={isFav ? 'Unfavourite' : 'Favourite'}
                    onClick={onToggleFav}
                    accent={isFav ? '#FFC850' : undefined}
                >
                    <Star size={16} fill={isFav ? '#FFC850' : 'none'} color={isFav ? '#FFC850' : '#9DA5B5'} />
                </HeroIconButton>
                <HeroIconButton label="Refresh" onClick={onRefresh}>
                    <RefreshCw size={15} color={syncing ? '#5DC8FF' : '#9DA5B5'} />
                </HeroIconButton>
                <HeroIconButton label="Sign out" onClick={onLogout}>
                    <LogOut size={15} color="#9DA5B5" />
                </HeroIconButton>
            </div>

            <div style={{ position: 'relative', maxWidth: 720, zIndex: 1 }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                    letterSpacing: '0.32em', color: '#5DC8FF', marginBottom: 10,
                }}>
                    LIVE TV{channel?.num != null ? ` · CH ${channel.num}` : ''}
                </div>
                <h1 style={{
                    margin: 0,
                    fontSize: 'clamp(36px, 4vw, 56px)',
                    fontWeight: 800,
                    lineHeight: 1.05,
                    letterSpacing: '-0.025em',
                    color: '#fff',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {channel?.name || 'Live TV'}
                </h1>

                {now ? (
                    <>
                        <div style={{
                            marginTop: 16,
                            fontSize: 13,
                            color: '#E6EAF2',
                            display: 'flex', alignItems: 'baseline', gap: 12,
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        }}>
                            <span style={{
                                fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                letterSpacing: '0.24em', color: '#5DC8FF',
                            }}>
                                NOW · {nowTime}
                            </span>
                            <span style={{ fontWeight: 700, fontSize: 14 }}>
                                {now.title || 'Untitled'}
                            </span>
                        </div>
                        {now.description && (
                            <div style={{
                                marginTop: 8,
                                fontSize: 13,
                                color: '#9DA5B5',
                                lineHeight: 1.45,
                                maxWidth: 640,
                                overflow: 'hidden',
                                display: '-webkit-box',
                                WebkitLineClamp: 3,
                                WebkitBoxOrient: 'vertical',
                                textOverflow: 'ellipsis',
                            }}>
                                {now.description}
                            </div>
                        )}
                        <div style={{
                            marginTop: 12,
                            width: '100%', maxWidth: 540,
                            height: 3, background: 'rgba(255,255,255,0.10)', borderRadius: 2,
                            overflow: 'hidden',
                        }}>
                            <div style={{
                                width: `${progress}%`,
                                height: '100%',
                                background: '#5DC8FF',
                            }} />
                        </div>
                        {next && (
                            <div style={{
                                marginTop: 8,
                                fontFamily: 'monospace',
                                fontSize: 10,
                                letterSpacing: '0.2em',
                                color: '#7d8493',
                                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                maxWidth: 540,
                            }}>
                                UP NEXT · {nextTime} · {next.title || 'Untitled'}
                            </div>
                        )}
                    </>
                ) : channel ? (
                    <div style={{
                        marginTop: 16,
                        fontFamily: 'monospace', fontSize: 11,
                        letterSpacing: '0.24em', color: '#7d8493',
                    }}>
                        LOADING PROGRAMME GUIDE…
                    </div>
                ) : null}
            </div>
        </section>
    );
});

const HeroIconButton = React.memo(function HeroIconButton({ children, label, onClick, accent }) {
    return (
        <button
            type="button"
            aria-label={label}
            onClick={onClick}
            style={{
                width: 40, height: 40,
                borderRadius: 999,
                background: accent ? 'rgba(255,200,80,0.12)' : 'rgba(20,28,42,0.85)',
                border: '1px solid ' + (accent ? 'rgba(255,200,80,0.45)' : 'rgba(255,255,255,0.10)'),
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                cursor: 'pointer',
            }}
        >
            {children}
        </button>
    );
});

/* ─────────────────────────── Search row ─────────────────────────── */

const SearchRow = React.memo(function SearchRow({ query, onChange, resultCount, totalCount }) {
    const has = !!query.trim();
    return (
        <div style={{
            padding: '10px 16px',
            display: 'flex', alignItems: 'center', gap: 12,
            background: 'rgba(20,28,42,0.6)',
            border: '1px solid ' + (has ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.07)'),
            borderRadius: 14,
            minHeight: 50,
        }}>
            <Search size={14} color={has ? '#5DC8FF' : '#7d8493'} />
            <input
                data-testid="live-tv-search"
                type="text"
                value={query}
                onChange={(e) => onChange(e.target.value)}
                placeholder="Search channels — type to search every category"
                style={{
                    flex: 1, minWidth: 0,
                    background: 'transparent', border: 'none', outline: 'none',
                    color: '#fff', fontSize: 13,
                }}
            />
            <span style={{
                fontFamily: 'monospace', fontSize: 10, fontWeight: 700, letterSpacing: '0.16em',
                color: has ? '#5DC8FF' : '#7d8493',
            }}>
                {has ? `${resultCount} / ${totalCount}` : `${totalCount} CHANNELS`}
            </span>
        </div>
    );
});

/* ─────────────────────── Guide top bar (date + clock) ─────────────────────── */

const GuideTopBar = React.memo(function GuideTopBar({ todayLabel }) {
    const clock = useClock();
    return (
        <div style={{
            padding: '4px 4px 0 4px',
            display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
            gap: 8,
        }}>
            <span style={{
                fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                letterSpacing: '0.32em',
                color: '#5DC8FF',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {todayLabel}
            </span>
            <span style={{
                fontFamily: 'monospace', fontSize: 13, fontWeight: 800,
                letterSpacing: '0.06em',
                color: '#fff',
                fontVariantNumeric: 'tabular-nums',
                whiteSpace: 'nowrap',
                flexShrink: 0,
            }}>
                {clock}
            </span>
        </div>
    );
});

function useClock() {
    const [now, setNow] = useState(() => formatClock(new Date()));
    useEffect(() => {
        const t = setInterval(() => setNow(formatClock(new Date())), 30_000);
        return () => clearInterval(t);
    }, []);
    return now;
}
function formatClock(d) {
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${String(h).padStart(2, '0')}:${mm} ${ap}`;
}

/* ─────────────────────── Guide column header ─────────────────────── */

const GuideHeader = React.memo(function GuideHeader({ channelName }) {
    return (
        <div style={{
            padding: '10px 16px',
            display: 'flex', alignItems: 'center', gap: 10,
            background: 'rgba(20,28,42,0.6)',
            border: '1px solid rgba(255,255,255,0.07)',
            borderRadius: 14,
            minHeight: 50,
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>
            <Calendar size={14} color="#7d8493" />
            <span style={{
                fontFamily: 'monospace', fontSize: 11, letterSpacing: '0.24em',
                color: '#7d8493', fontWeight: 700,
            }}>
                GUIDE
            </span>
            <span style={{ color: '#5e6473' }}>·</span>
            <span style={{
                color: '#E6EAF2', fontSize: 13, fontWeight: 600,
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                flex: 1, minWidth: 0,
            }}>
                {channelName || '—'}
            </span>
        </div>
    );
});

/* ──────────────────── Column (virtualised) ──────────────────── */

const Column = React.memo(function Column({ testid, isFocused, items, idx, rowHeight, rowFn }) {
    const containerRef = useRef(null);
    const [scrollTop, setScrollTop] = useState(0);

    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        const top = idx * rowHeight;
        const bottom = top + rowHeight;
        const viewTop = el.scrollTop;
        const viewBottom = viewTop + el.clientHeight;
        if (top < viewTop) el.scrollTop = top;
        else if (bottom > viewBottom) el.scrollTop = bottom - el.clientHeight;
    }, [idx, rowHeight]);

    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        let pending = false;
        const onScroll = () => {
            if (pending) return;
            pending = true;
            requestAnimationFrame(() => {
                setScrollTop(el.scrollTop);
                pending = false;
            });
        };
        el.addEventListener('scroll', onScroll);
        return () => el.removeEventListener('scroll', onScroll);
    }, []);

    const view = containerRef.current?.clientHeight || 600;
    const start = Math.max(0, Math.floor(scrollTop / rowHeight) - BUFFER);
    const end = Math.min(items.length, Math.ceil((scrollTop + view) / rowHeight) + BUFFER);
    const visible = [];
    for (let i = start; i < end; i++) {
        visible.push({ item: items[i], i });
    }

    return (
        <div
            data-testid={`live-tv-${testid}`}
            ref={containerRef}
            style={{
                flex: 1,
                overflowY: 'auto',
                overflowX: 'hidden',
                position: 'relative',
            }}
        >
            <div style={{ height: items.length * rowHeight, position: 'relative' }}>
                {visible.map(({ item, i }) => (
                    <div
                        key={item?.id || item?.stream_id || item?.startTimestamp || i}
                        style={{
                            position: 'absolute',
                            top: i * rowHeight,
                            left: 0, right: 0,
                            height: rowHeight,
                            padding: '0 0 6px 0',
                        }}
                    >
                        {rowFn(item, i, isFocused && i === idx)}
                    </div>
                ))}
            </div>
        </div>
    );
});

/* ─────────────────────────── Rows ─────────────────────────── */

const CategoryRow = React.memo(function CategoryRow({ cat, focused }) {
    if (cat.kind === 'header') {
        return (
            <div style={{
                height: '100%',
                padding: '14px 16px 4px 16px',
                fontFamily: 'monospace', fontSize: 10, letterSpacing: '0.24em',
                color: '#5e6473', fontWeight: 700,
                display: 'flex', alignItems: 'center',
            }}>
                {cat.name}
            </div>
        );
    }
    const isFav = cat.id === FAV_CAT;
    const isRem = cat.id === REM_CAT;
    const accent = isFav ? '#FFC850' : isRem ? '#FFC850' : '#5DC8FF';
    return (
        <div style={{
            height: '100%',
            padding: '0 12px',
            display: 'flex', alignItems: 'center', gap: 8,
            background: focused
                ? (isFav || isRem ? 'rgba(255,200,80,0.10)' : 'rgba(20,28,42,0.85)')
                : 'rgba(20,28,42,0.5)',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.06)'),
            boxShadow: focused ? `0 0 0 1px ${accent}` : 'none',
            borderRadius: 10,
            color: focused ? '#fff' : '#9DA5B5',
            fontWeight: focused ? 700 : 600,
            fontSize: 12,
        }}>
            {isFav && (
                <Star size={12} color={accent} fill={focused ? accent : 'none'} />
            )}
            {isRem && (
                <Bell size={11} color={accent} fill={focused ? accent : 'none'} />
            )}
            <span style={{
                flex: 1, minWidth: 0,
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {cat.name}
            </span>
            {cat.count > 0 && (
                <span style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    color: focused ? accent : '#5e6473',
                }}>
                    {cat.count}
                </span>
            )}
        </div>
    );
});

const ChannelCard = React.memo(function ChannelCard({ ch, focused, isFav, now }) {
    const accent = '#5DC8FF';
    const progress = computeProgress(now);
    return (
        <div style={{
            height: '100%',
            padding: '0 14px',
            display: 'flex', alignItems: 'center', gap: 14,
            background: focused
                ? 'linear-gradient(180deg, rgba(93,200,255,0.10) 0%, rgba(20,28,42,0.65) 100%)'
                : 'linear-gradient(180deg, rgba(255,255,255,0.04) 0%, rgba(20,28,42,0.55) 100%)',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.07)'),
            boxShadow: focused
                ? '0 0 0 1px rgba(93,200,255,0.35), inset 0 1px 0 rgba(255,255,255,0.08)'
                : 'inset 0 1px 0 rgba(255,255,255,0.04)',
            borderRadius: 14,
            position: 'relative',
            overflow: 'hidden',
        }}>
            <span style={{
                fontFamily: 'monospace', fontSize: 12, fontWeight: 700,
                color: focused ? accent : '#7d8493',
                minWidth: 30, textAlign: 'right',
            }}>
                {ch.num ?? ''}
            </span>
            <span style={{
                width: 44, height: 30, flexShrink: 0,
                background: 'rgba(255,255,255,0.04)',
                borderRadius: 5,
                overflow: 'hidden',
                position: 'relative',
            }}>
                {ch.stream_icon && (
                    <img
                        src={proxyImg(ch.stream_icon, 44, 50)}
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
                            padding: 2,
                        }}
                    />
                )}
            </span>
            {/* Title + NOW line + (right-aligned) progress bar.  The
                progress bar lives inside this column so it aligns
                with the title text (starts under "NOW") rather than
                spanning the whole card width. */}
            <div style={{ flex: 1, minWidth: 0,
                            display: 'flex', flexDirection: 'column', gap: 2, position: 'relative' }}>
                <span style={{
                    fontSize: 13, fontWeight: 700,
                    color: focused ? '#fff' : '#E6EAF2',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    lineHeight: 1.15,
                }}>
                    {ch.name}
                </span>
                {now ? (
                    <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        fontSize: 10,
                        color: '#9DA5B5',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        minWidth: 0,
                        lineHeight: 1.15,
                    }}>
                        <span style={{
                            fontFamily: 'monospace', fontSize: 8, fontWeight: 800,
                            letterSpacing: '0.16em', color: '#fff',
                            padding: '1px 5px',
                            background: '#FF4D5E',
                            borderRadius: 2,
                            flexShrink: 0,
                        }}>
                            NOW
                        </span>
                        <span style={{
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            flex: 1, minWidth: 0,
                        }}>
                            {now.title || 'Untitled'}
                        </span>
                    </span>
                ) : (
                    <span style={{
                        fontFamily: 'monospace', fontSize: 8, letterSpacing: '0.2em',
                        color: '#5e6473', lineHeight: 1.15,
                    }}>
                        NO GUIDE DATA
                    </span>
                )}
                <div style={{
                    marginTop: 3,
                    height: 2,
                    background: 'rgba(255,255,255,0.08)',
                    borderRadius: 1,
                    overflow: 'hidden',
                }}>
                    <div style={{
                        width: `${progress}%`,
                        height: '100%',
                        background: accent,
                    }} />
                </div>
            </div>
            {isFav && <Star size={12} color="#FFC850" fill="#FFC850" style={{ flexShrink: 0 }} />}
        </div>
    );
});

const GuideRow = React.memo(function GuideRow({ item, focused, isReminded }) {
    if (item._kind === 'header') {
        return (
            <div style={{
                height: '100%',
                padding: '14px 16px 4px 16px',
                fontFamily: 'monospace', fontSize: 10, letterSpacing: '0.24em',
                color: '#5e6473', fontWeight: 700,
                display: 'flex', alignItems: 'center',
            }}>
                {item.label}
            </div>
        );
    }
    const start = Number(item.startTimestamp) || 0;
    const stop = Number(item.stopTimestamp) || 0;
    const nowSec = Math.floor(Date.now() / 1000);
    const isLive = nowSec >= start && nowSec < stop;
    const isPast = stop > 0 && stop <= nowSec;
    const accent = isLive ? '#5DC8FF' : (isReminded ? '#FFC850' : '#5DC8FF');
    const timeStr = formatTime(start);
    const [hhmm, ampm] = splitHHMM_AMPM(timeStr);

    return (
        <div style={{
            height: '100%',
            padding: '0 10px',
            display: 'flex', gap: 8, alignItems: 'stretch',
            background: 'rgba(20,28,42,0.55)',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.06)'),
            boxShadow: focused ? `0 0 0 1px ${accent}` : 'none',
            borderRadius: 10,
            opacity: isPast ? 0.55 : 1,
            overflow: 'hidden',
        }}>
            <div style={{
                width: 38, flexShrink: 0,
                display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center', gap: 0,
                color: isLive ? accent : '#9DA5B5',
            }}>
                <span style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 700, letterSpacing: '0.02em', lineHeight: 1.1 }}>
                    {hhmm}
                </span>
                <span style={{ fontFamily: 'monospace', fontSize: 8, fontWeight: 700, letterSpacing: '0.1em', color: '#5e6473', lineHeight: 1.1 }}>
                    {ampm}
                </span>
            </div>
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column',
                            justifyContent: 'center', gap: 2 }}>
                <span style={{
                    fontSize: 12, fontWeight: 600,
                    color: isLive ? '#fff' : '#E6EAF2',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    lineHeight: 1.2,
                }}>
                    {item.title || 'Untitled'}
                </span>
                {!isPast && (
                    <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4,
                        fontFamily: 'monospace', fontSize: 8, fontWeight: 700,
                        letterSpacing: '0.14em', color: isReminded ? '#FFC850' : '#7d8493',
                        lineHeight: 1.1,
                    }}>
                        <Bell size={9} color={isReminded ? '#FFC850' : '#7d8493'}
                                fill={isReminded ? '#FFC850' : 'none'} />
                        {isReminded ? 'REMIND ON' : 'OK TO REMIND'}
                    </span>
                )}
            </div>
        </div>
    );
});

/* ─────────────────────────── Helpers ─────────────────────────── */

function formatTime(ts) {
    // 12-hour with AM/PM for the design.
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${String(h).padStart(2, '0')}:${mm} ${ap}`;
}

function splitHHMM_AMPM(timeStr) {
    if (!timeStr) return ['', ''];
    const parts = timeStr.split(' ');
    return [parts[0] || '', parts[1] || ''];
}

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

function proxyImg(url, width = 36, quality = 50) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    if (!base) return url;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${width}&q=${quality}`;
}

function buildSidebarCats(rawCats, favCount, recCount, remCount, channelsMap) {
    const out = [];
    out.push({ id: FAV_CAT, name: 'Favourites', count: favCount });
    if (recCount > 0) out.push({ id: REC_CAT, name: 'Recently Watched', count: recCount });
    if (remCount > 0) out.push({ id: REM_CAT, name: 'Reminders', count: remCount });
    if (rawCats.length > 0) {
        out.push({ id: 'h-cats', kind: 'header', name: 'CHANNEL GROUPS' });
    }
    for (const c of rawCats) {
        out.push({
            id: c.category_id,
            name: c.category_name,
            count: channelsMap.get(c.category_id)?.length || 0,
        });
    }
    return out;
}

function resolveByIds(idsSet, channelsMap, orderedKeys) {
    const lookup = new Map();
    for (const arr of channelsMap.values()) {
        for (const ch of (arr || [])) lookup.set(String(ch.stream_id), ch);
    }
    if (orderedKeys) {
        const out = [];
        for (const k of orderedKeys) {
            const ch = lookup.get(String(k));
            if (ch) out.push(ch);
        }
        return out;
    }
    const out = [];
    for (const k of idsSet) {
        const ch = lookup.get(String(k));
        if (ch) out.push(ch);
    }
    return out;
}

/** Inject TOMORROW / dated headers into an EPG list.  The TODAY
 *  group is implicit — its label sits above the GUIDE header in
 *  the UI, not inside the scrollable list.  This keeps the column
 *  clean: it's all upcoming items, with a date divider only when
 *  programmes span into a new day. */
function groupByDay(items) {
    if (!items || items.length === 0) return EMPTY_ARRAY;
    const out = [];
    const today = startOfDay(new Date()).getTime() / 1000;
    const tomorrow = today + 86400;
    const dayAfter = tomorrow + 86400;
    let lastBucket = 'TODAY';   // assume we start with today
    for (const it of items) {
        const start = Number(it.startTimestamp) || 0;
        let bucket;
        if (start >= today && start < tomorrow) bucket = 'TODAY';
        else if (start >= tomorrow && start < dayAfter) bucket = 'TOMORROW';
        else bucket = formatDayLabel(new Date(start * 1000));
        if (bucket !== lastBucket) {
            out.push({ _kind: 'header', id: `h-${bucket}`, label: bucket });
            lastBucket = bucket;
        }
        out.push(it);
    }
    return out;
}

function startOfDay(d) {
    const x = new Date(d);
    x.setHours(0, 0, 0, 0);
    return x;
}

function formatDayLabel(d) {
    const days = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
    const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
    return `${days[d.getDay()]} ${d.getDate()} ${months[d.getMonth()]}`;
}

/** Skip non-focusable section headers when arrow-navigating. */
function isHeaderItem(it) {
    return !!(it && (it.kind === 'header' || it._kind === 'header'));
}
function nextNavigableIdx(arr, from) {
    for (let i = from + 1; i < arr.length; i++) {
        if (!isHeaderItem(arr[i])) return i;
    }
    return from;
}
function prevNavigableIdx(arr, from) {
    for (let i = from - 1; i >= 0; i--) {
        if (!isHeaderItem(arr[i])) return i;
    }
    return from;
}
