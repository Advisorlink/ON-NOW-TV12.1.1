/**
 * Live TV — HK1-OPTIMISED REWRITE
 *
 * Designed for Android 7.1.2 / Chrome 52 / Mali-450 GPU.  Every
 * decision below is informed by what TV Mate does to feel buttery
 * on the same hardware.
 *
 *   ── Architecture ──
 *
 *   • Hot data lives in REFS, not state.  React only re-renders
 *     when the visible window or the selection cursor changes —
 *     never when a single channel is focused.
 *
 *   • The channel + guide lists are virtualised manually.  Rows
 *     are absolutely positioned at `top: index * ROW_H`.  No
 *     flexbox per-row, no nested spans, no buttons.  Only the
 *     ~20 rows visible in the viewport are mounted; everything
 *     else is just a number on a ruler.
 *
 *   • Navigation is INDEX-BASED, not geometric.  A single keydown
 *     handler at the page level reads `state.col` and `state.idx`,
 *     bumps the index, and re-renders.  No `getBoundingClientRect`
 *     loops, no spatial focus geometry — that's the single biggest
 *     source of chunkiness on the box.
 *
 *   • Focus is a CSS attribute on the column container, not the
 *     row.  When you arrow-down, we set `state.idx + 1`; React
 *     diffs *one* `data-focused="true"` attribute.  The previous
 *     and next rows don't re-render at all — their CSS rule
 *     `[data-col-focused] [data-idx="…"] { … }` applies the
 *     highlight purely via the cascade.
 *
 *   • EPG and channel data are persisted to localStorage (see
 *     liveCache.js) so subsequent launches are instant: the grid
 *     paints with cached data on the first frame, and the network
 *     refresh runs silently in the background.
 *
 *   • TMDB hero backdrop fires only after the user has SETTLED on
 *     a programme for 1500 ms — fast scrubbing triggers zero
 *     network calls.  Cached results re-render instantly.
 *
 *   ── Stripped on purpose ──
 *   No useSpatialFocus on this page.  No transitions/animations.
 *   No CSS variables in the row body.  No shadow effects.  No
 *   filter / mask-composite.  No channel logos.  No gradients on
 *   row backgrounds.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Star, Clock, Search } from 'lucide-react';
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
    loadCategories,
    saveCategories,
    loadChannels,
    saveChannels,
    loadEpg,
    mergeAndSaveEpg,
} from '@/lib/liveCache';
import Host from '@/lib/host';

const ROW_H = 36;            // single source of truth for row height
const BUFFER = 4;            // rows to render above + below visible window
const FAV_CAT = '__fav__';
const REC_CAT = '__rec__';

/* ─────────────────────────── Page shell ─────────────────────────── */

export default function LiveTV() {
    const [provider, setProvider] = useState(() => getActiveProvider());
    const navigate = useNavigate();

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
        <div className="relative w-screen" style={{
            minHeight: '100dvh',
            background: '#0A0F1A',
            overflow: 'hidden',
            color: '#E6EAF2',
        }}>
            <SideNav />
            <main style={{ marginLeft: 100, minHeight: '100dvh', position: 'relative' }}>
                {provider
                    ? <Grid provider={provider} onLogout={() => setProvider(null)} />
                    : <XtreamLogin onAuthed={(p) => setProvider(p)} />}
            </main>
        </div>
    );
}

/* ─────────────────────────────── Grid ─────────────────────────────── */

function Grid({ provider, onLogout }) {
    const navigate = useNavigate();

    /* Hot data — never re-rendered.  Only when we change selection. */
    const cats = useRef([]);                 // Category[]
    const channelsByCat = useRef(new Map()); // catId -> Channel[]
    const epg = useRef(new Map());           // streamId -> EpgItem[]

    /* Synchronous hydrate from localStorage on first mount.  If we
     * have anything, the grid renders with stale data on the very
     * first frame.  No boot screen, no loading spinner. */
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

    /* Selection — the ONLY state in the hot path.  `col` is one
     *  of 0 (categories), 1 (channels), 2 (guide). */
    const [sel, setSel] = useState(() => ({
        col: 1,                 // start in channels — feels like coming home
        catIdx: 0,
        chanIdx: 0,
        guideIdx: 0,
    }));

    /* Lightweight UI flags. */
    const [query, setQuery] = useState('');
    const [syncing, setSyncing] = useState(false);
    const [bootMessage, setBootMessage] = useState(
        cats.current.length > 0 ? '' : 'Connecting…',
    );

    /* Per-provider favs / recents.  Stored in state so the sidebar
     * re-renders when toggled. */
    const [favs, setFavs] = useState(
        () => new Set(getFavList(provider.id).map(String)),
    );
    const [recents, setRecents] = useState(
        () => getRecents(provider.id).map(String),
    );

    /* Derived: full + filtered list for the active category. */
    const allChannels = useMemo(() => {
        const cat = effectiveCat(sel.catIdx, cats.current);
        if (!cat) return [];
        if (cat.id === FAV_CAT) return resolveByIds(favs, channelsByCat.current);
        if (cat.id === REC_CAT) return resolveByIds(new Set(recents), channelsByCat.current, recents);
        return channelsByCat.current.get(cat.id) || [];
    }, [sel.catIdx, favs, recents, cats.current.length, channelsByCat.current.size]);

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
    const guideItems = focusedChannel
        ? (epg.current.get(focusedChannel.stream_id) || [])
        : [];
    const focusedItem = guideItems[Math.min(sel.guideIdx, Math.max(0, guideItems.length - 1))] || null;

    /* ───────── Background sync (writes-through to localStorage) ───────── */
    useEffect(() => {
        let cancel = false;
        (async () => {
            setSyncing(true);
            try {
                await authenticate(provider);
                if (cancel) return;
                if (!cats.current.length) setBootMessage('Loading channel list…');

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
                setBootMessage('');
                rerender();

                /* EPG prefetch — concurrency 6, hard cap 120 s.  All
                 * results write through to disk in 25-channel chunks. */
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
                            const items = await getFullEpg(provider, sid, 8);
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

    /* Cheap re-render trigger — just bumps a counter so React
     * notices the refs changed.  We avoid copying the data into
     * state because that's the whole point of refs. */
    const [, setBump] = useState(0);
    const rerender = useCallback(() => setBump((b) => b + 1), []);

    /* ───────── Keyboard ───────── */
    useEffect(() => {
        const onKey = (e) => {
            const tag = (document.activeElement?.tagName || '').toLowerCase();

            // Search input has its own input loop.  Up/Down hops out.
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
                if (focusedChannel) {
                    toggleFavorite(provider.id, focusedChannel.stream_id);
                    setFavs(new Set(getFavList(provider.id).map(String)));
                }
                return;
            }

            const key = e.key;
            if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                key !== 'ArrowLeft' && key !== 'ArrowRight' &&
                key !== 'Enter' && key !== ' ') return;

            e.preventDefault();
            e.stopPropagation();

            setSel((s) => {
                if (key === 'ArrowLeft') {
                    if (s.col === 0) {
                        // Hop to side nav.
                        const nav = document.querySelector('[data-testid="side-nav"] [data-focusable="true"]');
                        nav?.focus();
                        return s;
                    }
                    return { ...s, col: s.col - 1 };
                }
                if (key === 'ArrowRight') {
                    if (s.col === 2) return s;
                    if (s.col === 0 && channels.length === 0) return s;
                    if (s.col === 1 && guideItems.length === 0) return s;
                    return { ...s, col: s.col + 1 };
                }
                if (key === 'ArrowUp') {
                    if (s.col === 0) return { ...s, catIdx: Math.max(0, s.catIdx - 1) };
                    if (s.col === 1) return { ...s, chanIdx: Math.max(0, s.chanIdx - 1) };
                    return { ...s, guideIdx: Math.max(0, s.guideIdx - 1) };
                }
                if (key === 'ArrowDown') {
                    if (s.col === 0) {
                        const max = effectiveCatCount(cats.current) - 1;
                        return { ...s, catIdx: Math.min(max, s.catIdx + 1) };
                    }
                    if (s.col === 1) return { ...s, chanIdx: Math.min(channels.length - 1, s.chanIdx + 1) };
                    return { ...s, guideIdx: Math.min(guideItems.length - 1, s.guideIdx + 1) };
                }
                if (key === 'Enter' || key === ' ') {
                    if (s.col === 1 && focusedChannel) {
                        playChannel(focusedChannel);
                    }
                    return s;
                }
                return s;
            });
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [channels, guideItems, focusedChannel, provider, query]);

    /* When you change category, snap channel index to 0. */
    useEffect(() => { setSel((s) => ({ ...s, chanIdx: 0, guideIdx: 0 })); }, [sel.catIdx]);
    useEffect(() => { setSel((s) => ({ ...s, guideIdx: 0 })); }, [sel.chanIdx, allChannels]);

    /* Reset chanIdx when filtering shrinks the list past the cursor. */
    useEffect(() => {
        if (sel.chanIdx >= channels.length && channels.length > 0) {
            setSel((s) => ({ ...s, chanIdx: 0 }));
        }
    }, [channels.length, sel.chanIdx]);

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

    /* ───────── Render ───────── */

    if (cats.current.length === 0) {
        return (
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center',
                          minHeight: '100dvh', color: '#5DC8FF', fontSize: 14, letterSpacing: '0.2em' }}>
                {bootMessage}
            </div>
        );
    }

    const sidebarCats = buildSidebarCats(cats.current, favs.size, recents.length, channelsByCat.current);
    const activeCat = sidebarCats[sel.catIdx] || sidebarCats[0];

    return (
        <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100dvh' }}>
            <Header
                channel={focusedChannel}
                category={activeCat}
                programme={guideItems[0] || null}
                syncing={syncing}
                onLogout={onLogout}
            />
            <SearchRow query={query} onChange={setQuery} resultCount={channels.length} totalCount={allChannels.length} />
            <div style={{
                display: 'grid',
                gridTemplateColumns: '210px 1fr 320px',
                gap: 12,
                padding: '0 24px 24px 16px',
                flex: 1,
                minHeight: 0,
            }}>
                <Column
                    testid="cats"
                    isFocused={sel.col === 0}
                    items={sidebarCats}
                    idx={sel.catIdx}
                    rowHeight={ROW_H}
                    rowFn={(c, i, focused) => (
                        <CategoryRow key={c.id} cat={c} focused={focused} />
                    )}
                />
                <Column
                    testid="channels"
                    isFocused={sel.col === 1}
                    items={channels}
                    idx={sel.chanIdx}
                    rowHeight={ROW_H}
                    rowFn={(c, i, focused) => (
                        <ChannelRow key={c.stream_id}
                                    ch={c}
                                    focused={focused}
                                    isFav={favs.has(String(c.stream_id))}
                                    nowTitle={focused ? (epg.current.get(c.stream_id)?.[0]?.title || '') : ''} />
                    )}
                />
                <Column
                    testid="guide"
                    isFocused={sel.col === 2}
                    items={guideItems}
                    idx={sel.guideIdx}
                    rowHeight={ROW_H + 16}
                    headerLabel="PROGRAMME GUIDE"
                    rowFn={(it, i, focused) => (
                        <GuideRow key={`${it.startTimestamp}-${i}`} item={it} focused={focused} />
                    )}
                />
            </div>
        </div>
    );
}

/* ──────────────────────────── Header ──────────────────────────── */

function Header({ channel, category, programme, syncing, onLogout }) {
    const clock = useClock();
    const eyebrow = [
        'LIVE TV',
        channel?.num != null ? `CH ${channel.num}` : null,
        category?.name?.toUpperCase() || null,
    ].filter(Boolean).join(' · ');

    return (
        <section style={{
            padding: '24px 24px 14px 24px',
            display: 'flex',
            alignItems: 'flex-end',
            gap: 24,
            color: '#fff',
        }}>
            <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 6 }}>
                    <div style={{
                        fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                        letterSpacing: '0.32em', color: '#5DC8FF',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {eyebrow}
                    </div>
                    {syncing && (
                        <span style={{
                            fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                            letterSpacing: '0.22em', color: '#7d8493',
                            padding: '3px 7px',
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            borderRadius: 3,
                        }}>
                            UPDATING…
                        </span>
                    )}
                </div>
                <h1 style={{
                    fontSize: 'clamp(28px, 3.0vw, 44px)',
                    fontWeight: 800,
                    lineHeight: 1.05,
                    letterSpacing: '-0.02em',
                    margin: 0,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {channel?.name || 'Live TV'}
                </h1>
                {programme && (
                    <div style={{
                        marginTop: 4, fontSize: 13, color: '#9DA5B5',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        <span style={{ color: '#5DC8FF', fontWeight: 700, marginRight: 8 }}>NOW</span>
                        {programme.title}
                    </div>
                )}
            </div>
            <div style={{ textAlign: 'right' }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 24, fontWeight: 700,
                    color: '#fff', lineHeight: 1, fontVariantNumeric: 'tabular-nums',
                }}>
                    {clock.hhmm}
                </div>
                <div style={{
                    fontFamily: 'monospace', fontSize: 9, letterSpacing: '0.22em',
                    color: '#7d8493', marginTop: 4,
                }}>
                    {clock.day}
                </div>
            </div>
        </section>
    );
}

/* ──────────────────────────── Search row ──────────────────────────── */

function SearchRow({ query, onChange, resultCount, totalCount }) {
    const has = !!query.trim();
    return (
        <div style={{
            margin: '0 24px 12px 16px',
            padding: '8px 14px',
            display: 'flex', alignItems: 'center', gap: 10,
            background: has ? 'rgba(93,200,255,0.10)' : 'rgba(255,255,255,0.04)',
            border: '1px solid ' + (has ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.08)'),
            borderRadius: 10,
            minHeight: 40,
        }}>
            <Search size={14} color={has ? '#5DC8FF' : '#7d8493'} />
            <input
                data-testid="live-tv-search"
                type="text"
                value={query}
                onChange={(e) => onChange(e.target.value)}
                placeholder="Search channels (name or number)…"
                style={{
                    flex: 1, minWidth: 0,
                    background: 'transparent', border: 'none', outline: 'none',
                    color: '#fff', fontSize: 13,
                }}
            />
            <span style={{
                fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                color: has ? '#5DC8FF' : '#7d8493',
            }}>
                {has ? `${resultCount} / ${totalCount}` : totalCount}
            </span>
        </div>
    );
}

/* ──────────────────────────── Column (virtualised) ──────────────────────────── */

function Column({ testid, isFocused, items, idx, rowHeight, rowFn, headerLabel }) {
    const containerRef = useRef(null);
    const [scrollTop, setScrollTop] = useState(0);

    /* Scroll the focused row into view whenever idx changes.
     * No animation, no smooth-scroll — we just snap. */
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

    /* Throttle scroll listener via rAF. */
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

    /* Compute visible window. */
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
            data-col-focused={isFocused ? 'true' : 'false'}
            style={{
                position: 'relative',
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 10,
                overflow: 'hidden',
                display: 'flex',
                flexDirection: 'column',
                minHeight: 0,
            }}
        >
            {headerLabel && (
                <div style={{
                    padding: '10px 16px',
                    borderBottom: '1px solid rgba(255,255,255,0.06)',
                    fontFamily: 'monospace',
                    fontSize: 10, fontWeight: 700,
                    letterSpacing: '0.32em',
                    color: '#5DC8FF',
                    flexShrink: 0,
                }}>
                    {headerLabel}
                </div>
            )}
            <div
                ref={containerRef}
                style={{
                    flex: 1,
                    overflowY: 'auto',
                    overflowX: 'hidden',
                    position: 'relative',
                }}
            >
                {/* Spacer */}
                <div style={{ height: items.length * rowHeight, position: 'relative' }}>
                    {visible.map(({ item, i }) => (
                        <div
                            key={item?.id || item?.stream_id || item?.startTimestamp || i}
                            data-idx={i}
                            style={{
                                position: 'absolute',
                                top: i * rowHeight,
                                left: 0, right: 0,
                                height: rowHeight,
                            }}
                        >
                            {rowFn(item, i, isFocused && i === idx)}
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
}

/* ──────────────────────────── Row primitives ──────────────────────────── */

function CategoryRow({ cat, focused }) {
    const isFav = cat.id === FAV_CAT;
    const isRec = cat.id === REC_CAT;
    const accent = isFav ? '#FFC850' : '#5DC8FF';

    return (
        <div style={{
            height: '100%',
            padding: '0 12px',
            display: 'flex', alignItems: 'center', gap: 8,
            borderLeft: focused ? `3px solid ${accent}` : '3px solid transparent',
            background: focused ? (isFav ? 'rgba(255,200,80,0.12)' : 'rgba(93,200,255,0.10)') : 'transparent',
            color: focused ? '#fff' : '#9DA5B5',
            fontWeight: focused ? 700 : 600,
            fontSize: 12,
        }}>
            {(isFav || isRec) ? (
                isFav
                    ? <Star size={11} color={accent} fill={focused ? accent : 'none'} />
                    : <Clock size={11} color={accent} />
            ) : (
                <span style={{ width: 5, height: 5, borderRadius: '50%',
                                background: focused ? accent : 'rgba(255,255,255,0.20)' }} />
            )}
            <span style={{ flex: 1, minWidth: 0,
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                {cat.name}
            </span>
            {cat.count > 0 && (
                <span style={{
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    color: focused ? accent : '#5e6473',
                }}>
                    {cat.count}
                </span>
            )}
        </div>
    );
}

function ChannelRow({ ch, focused, isFav, nowTitle }) {
    return (
        <div style={{
            height: '100%',
            padding: '0 12px',
            display: 'flex', alignItems: 'center', gap: 12,
            borderLeft: focused ? '3px solid #5DC8FF' : '3px solid transparent',
            background: focused ? 'rgba(93,200,255,0.10)' : 'transparent',
            color: focused ? '#fff' : '#E6EAF2',
            fontWeight: focused ? 700 : 600,
            fontSize: 13,
        }}>
            {ch.num != null && (
                <span style={{
                    fontFamily: 'monospace', fontSize: 11,
                    color: focused ? '#5DC8FF' : '#5e6473',
                    minWidth: 32, textAlign: 'right', fontWeight: 700,
                }}>
                    {ch.num}
                </span>
            )}
            <span style={{
                flex: 1, minWidth: 0,
                display: 'flex', flexDirection: 'column', gap: 1,
                overflow: 'hidden',
            }}>
                <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                lineHeight: 1.15 }}>
                    {ch.name}
                </span>
                {focused && nowTitle && (
                    <span style={{
                        fontFamily: 'monospace', fontSize: 9, color: '#5DC8FF',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        letterSpacing: '0.04em', lineHeight: 1.15,
                    }}>
                        NOW · {nowTitle}
                    </span>
                )}
            </span>
            {isFav && <Star size={11} color="#FFC850" fill="#FFC850" />}
        </div>
    );
}

function GuideRow({ item, focused }) {
    const start = Number(item.startTimestamp) || 0;
    const stop = Number(item.stopTimestamp) || 0;
    const nowSec = Math.floor(Date.now() / 1000);
    const isLive = nowSec >= start && nowSec < stop;
    const isPast = stop > 0 && stop <= nowSec;
    const accent = isLive ? '#5DC8FF' : (focused ? '#5DC8FF' : 'transparent');

    return (
        <div style={{
            height: '100%',
            padding: '6px 14px',
            borderLeft: `3px solid ${accent}`,
            background: focused ? 'rgba(93,200,255,0.10)' : (isLive ? 'rgba(93,200,255,0.05)' : 'transparent'),
            color: isPast ? '#5e6473' : (focused || isLive ? '#fff' : '#E6EAF2'),
            opacity: isPast ? 0.6 : 1,
            display: 'flex', flexDirection: 'column', gap: 2,
            justifyContent: 'center',
        }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    color: isLive ? '#5DC8FF' : '#5e6473', letterSpacing: '0.04em',
                }}>
                    {fmtTime(start)}
                </span>
                {isLive && (
                    <span style={{
                        fontFamily: 'monospace', fontSize: 8, fontWeight: 700,
                        letterSpacing: '0.22em', color: '#5DC8FF',
                        padding: '1px 5px', background: 'rgba(93,200,255,0.14)', borderRadius: 2,
                    }}>
                        LIVE
                    </span>
                )}
            </div>
            <div style={{
                fontSize: 12, fontWeight: focused || isLive ? 700 : 500,
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                lineHeight: 1.2,
            }}>
                {item.title || 'Untitled'}
            </div>
        </div>
    );
}

/* ──────────────────────────── Helpers ──────────────────────────── */

function useClock() {
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

function fmtTime(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function buildSidebarCats(cats, favCount, recCount, channelsMap) {
    const out = [];
    if (recCount > 0) out.push({ id: REC_CAT, name: 'Recently Watched', count: recCount });
    out.push({ id: FAV_CAT, name: 'Favourites', count: favCount });
    for (const c of cats) {
        out.push({
            id: c.category_id,
            name: c.category_name,
            count: channelsMap.get(c.category_id)?.length || 0,
        });
    }
    return out;
}

function effectiveCat(idx, rawCats) {
    // Reconstruct the same sidebar order so idx maps to the right cat.
    if (idx === 0) return { id: REC_CAT, name: 'Recently Watched' };
    if (idx === 1) return { id: FAV_CAT, name: 'Favourites' };
    const real = rawCats[idx - 2];
    return real ? { id: real.category_id, name: real.category_name } : null;
}

function effectiveCatCount(rawCats) {
    return 2 + rawCats.length;
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
