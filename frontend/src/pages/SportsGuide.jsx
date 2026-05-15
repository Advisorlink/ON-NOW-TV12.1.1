/**
 * Sports Guide — V2 (TV-remote first).
 *
 *  Two-column browser:
 *    LEFT (220 px):  Sport categories.
 *                    Pinned: "All Sports", "Search…"
 *                    Detected: Football, Basketball, NFL, F1, UFC,
 *                              Tennis, Cricket, Boxing, Golf, …
 *                    + any leftover sports categories the IPTV
 *                    provider exposes.
 *    RIGHT (rest):   Selected sport's content.
 *                    Vertical stack of rails:
 *                       🔴 LIVE NOW     (compact horizontal cards)
 *                       ⏰ NEXT 6 HOURS
 *                       📅 LATER TODAY
 *                    Search-tab variant: TVKeyboard + LLM results.
 *
 *  Keypad model:
 *    ↑/↓ within a column;  ←/→ between columns.
 *    When the right column has multiple rails, ↓ off the last card
 *    of one rail lands you on the first card of the next rail.
 *    Enter on a card plays the channel.
 *    Long-press Enter on a card sets a reminder.
 *
 *  Why this design works on a TV box:
 *    • Cards are small (180 × 88) so 6-8 fit per rail without scroll.
 *    • Channel logos are recognisable at a glance, no reading needed.
 *    • Spatial focus only has to traverse two columns plus rails —
 *      far fewer keystrokes than a giant grid.
 *
 *  Data, performance:
 *    • Everything reads from the existing liveCache (no extra
 *      network) so the page paints on the first frame.
 *    • TMDB backdrop is a quiet enhancement — cards render fine
 *      without it.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import {
    Trophy, Search, Loader2, Play, Bell, Flame, Hourglass,
    CalendarDays, Dumbbell, Volleyball, Bike, X,
} from 'lucide-react';
import SideNav from '@/components/SideNav';
import TVKeyboard from '@/components/TVKeyboard';
import { getActiveProvider, getStreamUrl } from '@/lib/xtream';
import { loadCategories, loadChannels, loadEpg } from '@/lib/liveCache';
import { getReminders, toggleReminder } from '@/lib/liveReminders';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import Host from '@/lib/host';

const SPORTS = [
    { id: 'football',   label: 'Football',     kws: ['football', 'soccer', 'fifa', 'champions', 'epl', 'premier league', 'la liga', 'serie a', 'bundesliga'] },
    { id: 'nfl',        label: 'NFL',          kws: ['nfl', 'gridiron', 'super bowl', 'american football'] },
    { id: 'nba',        label: 'Basketball',   kws: ['nba', 'basketball', 'wnba', 'ncaa basketball'] },
    { id: 'nrl',        label: 'Rugby / NRL',  kws: ['nrl', 'rugby', 'state of origin'] },
    { id: 'afl',        label: 'AFL',          kws: ['afl', 'australian rules'] },
    { id: 'mlb',        label: 'Baseball',     kws: ['mlb', 'baseball', 'world series'] },
    { id: 'nhl',        label: 'Ice Hockey',   kws: ['nhl', 'hockey', 'stanley cup'] },
    { id: 'cricket',    label: 'Cricket',      kws: ['cricket', 'test match', 't20', 'ipl', 'big bash'] },
    { id: 'tennis',     label: 'Tennis',       kws: ['tennis', 'atp', 'wta', 'us open', 'wimbledon', 'australian open', 'roland garros'] },
    { id: 'golf',       label: 'Golf',         kws: ['golf', 'pga', 'masters', 'open championship'] },
    { id: 'ufc',        label: 'UFC / MMA',    kws: ['ufc', 'mma', 'cage'] },
    { id: 'boxing',     label: 'Boxing',       kws: ['boxing', 'heavyweight', 'fight night'] },
    { id: 'f1',         label: 'Motorsport',   kws: ['f1', 'formula 1', 'formula one', 'motogp', 'nascar', 'indycar'] },
    { id: 'cycling',    label: 'Cycling',      kws: ['cycling', 'tour de france', 'giro', 'vuelta'] },
];

function proxy(url, w = 64, q = 55) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${w}&q=${q}`;
}

export default function SportsGuide() {
    const provider = getActiveProvider();
    const navigate = useNavigate();

    /* All EPG candidates from sports-tagged channels (next 24 h). */
    const allCandidates = useMemo(() => {
        if (!provider) return [];
        return gatherSportsEpg(provider.id);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider?.id]);

    /* Buckets: { all: [], football: [], nba: [], … }
     * Built once per data refresh — used by the right column. */
    const buckets = useMemo(() => bucketBySport(allCandidates), [allCandidates]);

    /* Categories the user actually sees — only sports with content. */
    const navItems = useMemo(() => {
        const out = [
            { id: 'all',    kind: 'cat', label: 'All Sports', count: allCandidates.length },
        ];
        for (const s of SPORTS) {
            const list = buckets[s.id] || [];
            if (list.length > 0) {
                out.push({ id: s.id, kind: 'cat', label: s.label, count: list.length });
            }
        }
        out.push({ id: 'search', kind: 'search', label: 'Search…' });
        return out;
    }, [buckets, allCandidates]);

    /* Selection: col 0 = nav, col 1 = rail/card. */
    const [sel, setSel] = useState(() => ({ col: 0, navIdx: 0, railIdx: 0, cardIdx: 0 }));

    /* Search-tab state. */
    const [query, setQuery] = useState('');
    const [searching, setSearching] = useState(false);
    const [searchErr, setSearchErr] = useState('');
    const [searchResults, setSearchResults] = useState(null);
    const [kbOpen, setKbOpen] = useState(false);

    const activeNav = navItems[sel.navIdx] || navItems[0];

    /* Right-column rails for the current selection. */
    const rails = useMemo(() => {
        if (!activeNav) return [];
        if (activeNav.id === 'search') return [];
        const list = activeNav.id === 'all'
            ? allCandidates
            : (buckets[activeNav.id] || []);
        return buildRails(list);
    }, [activeNav, allCandidates, buckets]);

    /* Reminders bookkeeping. */
    const [, setBump] = useState(0);
    const reminders = useMemo(
        () => (provider ? new Set(getReminders(provider.id).map((r) => r.id)) : new Set()),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [provider, sel],   // re-read on any nav/sel update (cheap)
    );

    const submit = useCallback(async (q) => {
        const text = (q ?? query).trim();
        if (!text) return;
        setQuery(text);
        setKbOpen(false);
        setSearching(true);
        setSearchErr('');
        try {
            const r = await axios.post(
                `${process.env.REACT_APP_BACKEND_URL}/api/sports/find`,
                { query: text, candidates: allCandidates.slice(0, 80) },
                { timeout: 25000 },
            );
            setSearchResults(r.data?.matches || []);
        } catch (e) {
            setSearchErr(e?.response?.data?.detail || e?.message || 'Search failed.');
            setSearchResults([]);
        } finally {
            setSearching(false);
        }
    }, [query, allCandidates]);

    /* Play / remind handlers. */
    const onPlay = useCallback(async (item) => {
        if (!provider) return;
        const chans = loadChannels(provider.id) || {};
        let ch = null;
        for (const k in chans) {
            ch = (chans[k] || []).find((x) => String(x.stream_id) === String(item.streamId));
            if (ch) break;
        }
        if (!ch) return;
        const url = await getStreamUrl(provider, 'live', ch.stream_id, 'ts');
        if (!url) return;
        const payload = {
            url, title: ch.name, type: 'live',
            cwId: `live:${provider.id}:${ch.stream_id}`,
        };
        if (Host.playVideo(payload)) return;
        navigate(`/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(ch.name)}&type=live`);
    }, [provider, navigate]);

    const onRemind = useCallback((item) => {
        if (!provider || !item?.startTs) return;
        toggleReminder(provider.id, item.streamId, {
            channelName: item.channelName,
            title: item.title,
            startTs: item.startTs,
            stopTs: item.stopTs,
        });
        setBump((b) => b + 1);
    }, [provider]);

    /* Long-press tracking for OK = reminder shortcut on cards. */
    const pressRef = useRef({ count: 0, fired: false });

    /* Keyboard. */
    useEffect(() => {
        const onKey = (e) => {
            const tag = (document.activeElement?.tagName || '').toLowerCase();
            if (tag === 'input' || kbOpen) return;  // let inputs / overlay handle it

            const k = e.key;
            if (k === 'Escape' || k === 'Backspace') {
                e.preventDefault();
                navigate('/');
                return;
            }
            if (k !== 'ArrowUp' && k !== 'ArrowDown' &&
                k !== 'ArrowLeft' && k !== 'ArrowRight' &&
                k !== 'Enter' && k !== ' ') return;

            e.preventDefault();
            e.stopPropagation();

            // Long-press tracking for Enter
            if (k === 'Enter' || k === ' ') {
                const p = pressRef.current;
                p.count += 1;
                if (p.count >= 6 && !p.fired) {
                    p.fired = true;
                    if (sel.col === 1 && rails.length > 0) {
                        const rail = rails[sel.railIdx];
                        const card = rail?.items?.[sel.cardIdx];
                        if (card) onRemind(card);
                    }
                }
                return;
            }

            setSel((s) => {
                if (k === 'ArrowLeft') {
                    if (s.col === 0) {
                        const nav = document.querySelector('[data-testid="side-nav"] [data-focusable="true"]');
                        nav?.focus();
                        return s;
                    }
                    // From card → first card of same row → nav
                    if (s.cardIdx > 0) return { ...s, cardIdx: s.cardIdx - 1 };
                    return { ...s, col: 0 };
                }
                if (k === 'ArrowRight') {
                    if (s.col === 0) {
                        // Search tab has no rails — focus stays on nav.
                        if (activeNav?.id === 'search') return s;
                        if (rails.length === 0) return s;
                        return { ...s, col: 1, railIdx: 0, cardIdx: 0 };
                    }
                    // Card → next card in same rail
                    const rail = rails[s.railIdx];
                    if (rail && s.cardIdx < rail.items.length - 1) {
                        return { ...s, cardIdx: s.cardIdx + 1 };
                    }
                    return s;
                }
                if (k === 'ArrowUp') {
                    if (s.col === 0) return { ...s, navIdx: Math.max(0, s.navIdx - 1) };
                    if (s.railIdx > 0) return { ...s, railIdx: s.railIdx - 1, cardIdx: 0 };
                    return s;
                }
                if (k === 'ArrowDown') {
                    if (s.col === 0) {
                        return { ...s, navIdx: Math.min(navItems.length - 1, s.navIdx + 1) };
                    }
                    if (s.railIdx < rails.length - 1) {
                        return { ...s, railIdx: s.railIdx + 1, cardIdx: 0 };
                    }
                    return s;
                }
                return s;
            });
        };

        const onKeyUp = (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            const p = pressRef.current;
            const long = p.fired;
            p.count = 0; p.fired = false;
            if (long) return;
            // Short tap → play on cards, open keyboard on Search tab
            if (sel.col === 0) {
                if (activeNav?.id === 'search') setKbOpen(true);
                return;
            }
            const rail = rails[sel.railIdx];
            const card = rail?.items?.[sel.cardIdx];
            if (card) onPlay(card);
        };

        window.addEventListener('keydown', onKey, true);
        window.addEventListener('keyup', onKeyUp, true);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            window.removeEventListener('keyup', onKeyUp, true);
        };
    }, [sel, rails, navItems, activeNav, kbOpen, onPlay, onRemind, navigate]);

    /* Reset card cursor when nav changes. */
    useEffect(() => {
        setSel((s) => ({ ...s, railIdx: 0, cardIdx: 0 }));
    }, [sel.navIdx]);

    if (!provider) {
        return (
            <div style={{ position: 'fixed', inset: 0, background: '#0A0F1A', color: '#9DA5B5',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontFamily: 'monospace', letterSpacing: '0.2em', fontSize: 13 }}>
                CONNECT YOUR IPTV PROVIDER IN LIVE TV FIRST.
            </div>
        );
    }

    return (
        <div style={{ position: 'fixed', inset: 0, background: '#0A0F1A', color: '#E6EAF2', overflow: 'hidden' }}>
            <SideNav />
            <main style={{
                position: 'absolute', inset: '0 0 0 100px',
                display: 'flex', flexDirection: 'column', overflow: 'hidden',
            }}>
                {/* Title strip */}
                <header style={{
                    padding: '20px 24px 12px 24px',
                    display: 'flex', alignItems: 'baseline', gap: 12,
                }}>
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8,
                                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                    letterSpacing: '0.32em', color: '#5DC8FF' }}>
                        <Trophy size={12} color="#5DC8FF" />
                        SPORTS GUIDE
                    </div>
                    <span style={{ fontSize: 13, color: '#7d8493' }}>
                        Pick a sport on the left, or use Search.
                    </span>
                </header>

                {/* Two-column body */}
                <div style={{
                    display: 'grid',
                    gridTemplateColumns: '220px 1fr',
                    gap: 14,
                    padding: '0 24px 24px 24px',
                    flex: 1, minHeight: 0,
                }}>
                    {/* LEFT — categories */}
                    <NavColumn
                        items={navItems}
                        idx={sel.navIdx}
                        isFocused={sel.col === 0}
                    />

                    {/* RIGHT — content for the active category */}
                    <div style={{
                        background: 'rgba(255,255,255,0.018)',
                        border: '1px solid rgba(255,255,255,0.06)',
                        borderRadius: 14,
                        overflow: 'hidden',
                        display: 'flex', flexDirection: 'column',
                        minHeight: 0,
                    }}>
                        {activeNav?.id === 'search' ? (
                            <SearchPanel
                                query={query}
                                onOpen={() => setKbOpen(true)}
                                loading={searching}
                                error={searchErr}
                                results={searchResults}
                                providerId={provider.id}
                                reminders={reminders}
                                isFocused={sel.col === 1}
                                onPlay={onPlay}
                                onRemind={onRemind}
                            />
                        ) : (
                            <RailsPanel
                                rails={rails}
                                railIdx={sel.railIdx}
                                cardIdx={sel.cardIdx}
                                isFocused={sel.col === 1}
                                reminders={reminders}
                            />
                        )}
                    </div>
                </div>
            </main>

            {kbOpen && (
                <KeyboardOverlay
                    query={query}
                    onChange={setQuery}
                    onSubmit={() => submit()}
                    onClose={() => setKbOpen(false)}
                />
            )}

            <style>{`@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }`}</style>
        </div>
    );
}

/* ──────────────────────────── Left column ──────────────────────────── */

const NavColumn = React.memo(function NavColumn({ items, idx, isFocused }) {
    return (
        <div style={{
            background: 'rgba(255,255,255,0.025)',
            border: '1px solid rgba(255,255,255,0.06)',
            borderRadius: 14,
            overflow: 'auto',
            padding: 6,
        }}>
            {items.map((it, i) => {
                const focused = isFocused && i === idx;
                const Icon = iconFor(it.id);
                const accent = it.id === 'search' ? '#FFC850' : '#5DC8FF';
                return (
                    <div
                        key={it.id}
                        style={{
                            display: 'flex', alignItems: 'center', gap: 10,
                            padding: '0 12px',
                            height: 38,
                            margin: '2px 0',
                            borderRadius: 8,
                            background: focused
                                ? (it.id === 'search' ? 'rgba(255,200,80,0.10)' : 'rgba(93,200,255,0.10)')
                                : 'transparent',
                            border: '1px solid ' + (focused ? accent : 'transparent'),
                            color: focused ? '#fff' : '#9DA5B5',
                            fontSize: 13, fontWeight: focused ? 700 : 600,
                            letterSpacing: it.id === 'search' ? '0.04em' : 0,
                        }}
                    >
                        <Icon size={13} color={focused ? accent : '#5e6473'} />
                        <span style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                            {it.label}
                        </span>
                        {it.count !== undefined && it.count > 0 && (
                            <span style={{ fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                            color: focused ? accent : '#5e6473' }}>
                                {it.count}
                            </span>
                        )}
                    </div>
                );
            })}
        </div>
    );
});

function iconFor(id) {
    if (id === 'all') return Trophy;
    if (id === 'search') return Search;
    if (id === 'football') return Volleyball;
    if (id === 'f1' || id === 'cycling') return Bike;
    if (id === 'ufc' || id === 'boxing') return Dumbbell;
    return Flame;
}

/* ──────────────────────────── Right column (rails) ──────────────────────────── */

const RailsPanel = React.memo(function RailsPanel({ rails, railIdx, cardIdx, isFocused, reminders }) {
    if (rails.length === 0) {
        return (
            <div style={{
                flex: 1,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#7d8493', fontSize: 13, padding: 24, textAlign: 'center',
            }}>
                Nothing scheduled on this sport in the next 24 hours.<br />
                Try “All Sports” or another category.
            </div>
        );
    }
    return (
        <div style={{ flex: 1, overflowY: 'auto', padding: 14 }}>
            {rails.map((r, i) => (
                <Rail
                    key={r.id}
                    rail={r}
                    cardIdx={i === railIdx ? cardIdx : -1}
                    isFocused={isFocused && i === railIdx}
                    reminders={reminders}
                />
            ))}
        </div>
    );
});

const Rail = React.memo(function Rail({ rail, cardIdx, isFocused, reminders }) {
    const trackRef = useRef(null);
    /* Keep the focused card scrolled into view horizontally. */
    useEffect(() => {
        if (cardIdx < 0 || !trackRef.current) return;
        const el = trackRef.current.children[cardIdx];
        if (el?.scrollIntoView) {
            el.scrollIntoView({ behavior: 'auto', block: 'nearest', inline: 'nearest' });
        }
    }, [cardIdx]);

    return (
        <div style={{ marginBottom: 18 }}>
            <div style={{
                display: 'inline-flex', alignItems: 'center', gap: 7,
                fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                letterSpacing: '0.28em', color: rail.accent, marginBottom: 8,
            }}>
                <rail.Icon size={12} color={rail.accent} />
                {rail.label}
                <span style={{ color: '#5e6473' }}>·  {rail.items.length}</span>
            </div>
            <div
                ref={trackRef}
                style={{
                    display: 'grid',
                    gridAutoFlow: 'column',
                    gridAutoColumns: 'minmax(180px, 1fr)',
                    gap: 10,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    paddingBottom: 2,
                }}
            >
                {rail.items.map((it, i) => (
                    <Card
                        key={`${it.streamId}-${it.startTs}`}
                        item={it}
                        focused={isFocused && i === cardIdx}
                        isReminded={reminders.has(`${Number(it.streamId) || it.streamId}:${Number(it.startTs) || it.startTs}`)}
                    />
                ))}
            </div>
        </div>
    );
});

const Card = React.memo(function Card({ item, focused, isReminded }) {
    /* TMDB lookup — only renders when actually focused for perf.
     * Unfocused cards stay minimal: channel name + title + time. */
    const tmdb = useProgrammeBackdrop(focused ? (item.title || '') : '', focused ? (item.channelName || '') : '');
    const art = tmdb?.backdrop && focused ? proxy(tmdb.backdrop, 360, 50) : '';
    const channelLogo = item.streamIcon ? proxy(item.streamIcon, 40, 60) : '';
    const live = isLiveNow(item);
    const accent = live ? '#FF4D5E' : '#5DC8FF';

    return (
        <div style={{
            height: 124,
            background: '#11182A',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.07)'),
            boxShadow: focused ? `0 0 0 1px ${accent}` : 'inset 0 1px 0 rgba(255,255,255,0.04)',
            borderRadius: 10,
            overflow: 'hidden',
            position: 'relative',
            display: 'flex', flexDirection: 'column',
        }}>
            {/* Top art half */}
            <div style={{
                height: 58,
                position: 'relative',
                backgroundImage: art
                    ? `linear-gradient(180deg, rgba(17,24,42,0.05) 0%, rgba(17,24,42,0.88) 100%), url(${art})`
                    : 'linear-gradient(135deg, rgba(93,200,255,0.16) 0%, rgba(17,24,42,1) 100%)',
                backgroundSize: 'auto, cover',
                backgroundPosition: 'center',
            }}>
                <div style={{
                    position: 'absolute', top: 6, left: 6,
                    display: 'inline-flex', alignItems: 'center', gap: 4,
                    padding: '2px 6px',
                    background: live ? 'rgba(255,77,94,0.20)' : 'rgba(93,200,255,0.20)',
                    border: '1px solid ' + (live ? 'rgba(255,77,94,0.55)' : 'rgba(93,200,255,0.55)'),
                    borderRadius: 999,
                    fontFamily: 'monospace', fontSize: 8, fontWeight: 700,
                    letterSpacing: '0.16em', color: accent,
                }}>
                    {labelFor(item)}
                </div>
                {isReminded && (
                    <div style={{
                        position: 'absolute', top: 6, right: 6,
                        display: 'inline-flex', alignItems: 'center',
                        padding: 3, borderRadius: 999,
                        background: 'rgba(255,200,80,0.18)',
                        border: '1px solid rgba(255,200,80,0.5)',
                    }}>
                        <Bell size={9} color="#FFC850" fill="#FFC850" />
                    </div>
                )}
            </div>
            {/* Bottom info half */}
            <div style={{
                flex: 1, padding: '6px 8px 8px 8px',
                display: 'flex', flexDirection: 'column', gap: 4,
                minWidth: 0,
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <div style={{
                        width: 22, height: 16, flexShrink: 0,
                        background: 'rgba(255,255,255,0.04)',
                        borderRadius: 3,
                        overflow: 'hidden', position: 'relative',
                    }}>
                        {channelLogo && (
                            <img
                                src={channelLogo}
                                alt=""
                                width={22}
                                height={16}
                                loading="lazy"
                                decoding="async"
                                referrerPolicy="no-referrer"
                                onError={(e) => { e.currentTarget.style.display = 'none'; }}
                                style={{
                                    position: 'absolute', inset: 0,
                                    width: '100%', height: '100%',
                                    objectFit: 'contain',
                                }}
                            />
                        )}
                    </div>
                    <span style={{
                        fontFamily: 'monospace', fontSize: 8, fontWeight: 700,
                        letterSpacing: '0.16em', color: '#9DA5B5',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        flex: 1, minWidth: 0,
                    }}>
                        {(item.channelName || '').toUpperCase()}
                    </span>
                </div>
                <div style={{
                    fontSize: 12, fontWeight: 700,
                    color: focused ? '#fff' : '#E6EAF2',
                    lineHeight: 1.2,
                    display: '-webkit-box',
                    WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                    overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {item.title || 'Untitled'}
                </div>
            </div>
        </div>
    );
});

/* ──────────────────────────── Right column (search) ──────────────────────────── */

function SearchPanel({ query, onOpen, loading, error, results, providerId, reminders, isFocused, onPlay, onRemind }) {
    return (
        <div style={{ flex: 1, overflowY: 'auto', padding: 14 }}>
            <button
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={onOpen}
                style={{
                    width: '100%',
                    display: 'flex', alignItems: 'center', gap: 10,
                    padding: '0 14px',
                    height: 44,
                    background: 'rgba(20,28,42,0.6)',
                    border: '1px solid ' + (query ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.10)'),
                    borderRadius: 10,
                    color: '#fff', textAlign: 'left',
                    cursor: 'pointer',
                }}
            >
                <Search size={14} color={query ? '#5DC8FF' : '#7d8493'} />
                <span style={{
                    flex: 1, minWidth: 0,
                    fontSize: 13, fontWeight: 600,
                    color: query ? '#fff' : '#5e6473',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {query || 'Tap to search — “Toronto Raptors”, “UFC”, “Cowboys”…'}
                </span>
            </button>
            {loading && (
                <div style={{ padding: 14, color: '#9DA5B5', fontSize: 12,
                                display: 'flex', alignItems: 'center', gap: 10 }}>
                    <Loader2 size={14} color="#5DC8FF" style={{ animation: 'spin 1.2s linear infinite' }} />
                    Searching…
                </div>
            )}
            {error && !loading && (
                <div style={{ padding: 12, background: 'rgba(255,107,122,0.08)',
                                border: '1px solid rgba(255,107,122,0.40)', borderRadius: 10,
                                color: '#FF6B7A', fontSize: 12, marginTop: 12 }}>
                    {error}
                </div>
            )}
            {results !== null && !loading && (
                <div style={{ marginTop: 14 }}>
                    <div style={{ fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                    letterSpacing: '0.28em', color: '#5DC8FF', marginBottom: 8 }}>
                        RESULTS · {results.length}
                    </div>
                    {results.length === 0 ? (
                        <div style={{ color: '#7d8493', fontSize: 12, padding: '8px 4px' }}>
                            No matches in the next 24 hours.
                        </div>
                    ) : (
                        <div style={{ display: 'grid', gridAutoFlow: 'column',
                                        gridAutoColumns: 'minmax(180px, 1fr)',
                                        gap: 10, overflowX: 'auto' }}>
                            {results.map((m) => (
                                <Card
                                    key={`${m.streamId}-${m.startTs}`}
                                    item={decorateWithIcon(m, providerId)}
                                    focused={false}
                                    isReminded={reminders.has(`${Number(m.streamId) || m.streamId}:${Number(m.startTs) || m.startTs}`)}
                                />
                            ))}
                        </div>
                    )}
                </div>
            )}
        </div>
    );
}

/* ──────────────────────────── Keyboard overlay ──────────────────────────── */

function KeyboardOverlay({ query, onChange, onSubmit, onClose }) {
    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onClose();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onClose]);
    return (
        <div
            role="dialog"
            aria-modal="true"
            onClick={onClose}
            style={{
                position: 'fixed', inset: 0,
                zIndex: 9000,
                background: 'rgba(0,0,0,0.55)',
                display: 'flex', alignItems: 'flex-end', justifyContent: 'center',
            }}
        >
            <div
                onClick={(e) => e.stopPropagation()}
                style={{
                    width: 'min(820px, 100%)',
                    margin: '0 24px 24px 24px',
                    background: '#11182A',
                    border: '1px solid rgba(255,255,255,0.10)',
                    borderRadius: 14,
                    padding: 18,
                    boxShadow: '0 24px 60px rgba(0,0,0,0.65), inset 0 1px 0 rgba(255,255,255,0.06)',
                }}
            >
                <div style={{
                    minHeight: 44,
                    padding: '0 14px',
                    display: 'flex', alignItems: 'center', gap: 10,
                    background: 'rgba(20,28,42,0.6)',
                    border: '1px solid ' + (query ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.10)'),
                    borderRadius: 10,
                    marginBottom: 12,
                }}>
                    <Search size={14} color={query ? '#5DC8FF' : '#7d8493'} />
                    <span style={{
                        flex: 1, minWidth: 0,
                        fontSize: 14, fontWeight: 700,
                        color: query ? '#fff' : '#5e6473',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {query || 'Start typing…'}
                    </span>
                    <button
                        onClick={onClose}
                        aria-label="Close"
                        style={{
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            width: 26, height: 26, borderRadius: 999,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            color: '#9DA5B5', cursor: 'pointer',
                        }}
                    >
                        <X size={11} />
                    </button>
                </div>
                <TVKeyboard
                    value={query}
                    onChange={onChange}
                    onSubmit={onSubmit}
                    maxLength={60}
                    variant="name"
                />
            </div>
        </div>
    );
}

/* ──────────────────────────── Data helpers ──────────────────────────── */

function isLiveNow(item) {
    const nowSec = Math.floor(Date.now() / 1000);
    return item.startTs <= nowSec && item.stopTs > nowSec;
}

function labelFor(item) {
    if (isLiveNow(item)) return `LIVE · ${fmt(item.startTs)}`;
    return `${dayLabel(item.startTs)} · ${fmt(item.startTs)}`;
}

function fmt(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    return `${String(h).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')} ${ap}`;
}

function dayLabel(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const target = new Date(d); target.setHours(0, 0, 0, 0);
    const diff = Math.round((target - today) / 86400000);
    if (diff === 0) return 'TODAY';
    if (diff === 1) return 'TOMORROW';
    const days = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
    return days[d.getDay()];
}

function decorateWithIcon(match, providerId) {
    if (!providerId) return match;
    const chans = loadChannels(providerId) || {};
    for (const k in chans) {
        const hit = (chans[k] || []).find((x) => String(x.stream_id) === String(match.streamId));
        if (hit) return { ...match, streamIcon: hit.stream_icon || '' };
    }
    return match;
}

function gatherSportsEpg(providerId) {
    const cats = loadCategories(providerId) || [];
    const chans = loadChannels(providerId) || {};
    const epg = loadEpg(providerId) || {};

    const sportsCatIds = new Set(
        cats.filter((c) => /sport/i.test(c.category_name || '')).map((c) => String(c.category_id)),
    );

    const channelLookup = new Map();
    for (const catId in chans) {
        if (!sportsCatIds.has(String(catId))) continue;
        for (const ch of (chans[catId] || [])) {
            channelLookup.set(String(ch.stream_id), {
                name: ch.name || '',
                icon: ch.stream_icon || '',
            });
        }
    }

    const nowSec = Math.floor(Date.now() / 1000);
    const horizonSec = nowSec + 24 * 3600;
    const out = [];
    for (const sid in epg) {
        const meta = channelLookup.get(String(sid));
        if (!meta) continue;
        for (const it of (epg[sid] || [])) {
            const start = Number(it.startTimestamp) || 0;
            const stop = Number(it.stopTimestamp) || 0;
            if (stop <= nowSec) continue;
            if (start > horizonSec) continue;
            out.push({
                streamId: sid,
                channelName: meta.name,
                streamIcon: meta.icon,
                title: it.title || '',
                description: (it.description || '').slice(0, 220),
                startTs: start,
                stopTs: stop,
            });
        }
    }
    return out.sort((a, b) => a.startTs - b.startTs);
}

function bucketBySport(items) {
    const buckets = {};
    for (const s of SPORTS) buckets[s.id] = [];
    for (const it of items) {
        const text = `${it.title || ''} ${it.description || ''}`.toLowerCase();
        for (const s of SPORTS) {
            if (s.kws.some((kw) => text.includes(kw))) {
                buckets[s.id].push(it);
            }
        }
    }
    return buckets;
}

function buildRails(items) {
    const nowSec = Math.floor(Date.now() / 1000);
    const sixHr = nowSec + 6 * 3600;
    const live = items.filter((it) => it.startTs <= nowSec && it.stopTs > nowSec);
    const next = items.filter((it) => it.startTs > nowSec && it.startTs <= sixHr)
        .sort((a, b) => a.startTs - b.startTs);
    const later = items.filter((it) => it.startTs > sixHr)
        .sort((a, b) => a.startTs - b.startTs);
    const out = [];
    if (live.length) out.push({ id: 'live', label: 'LIVE NOW', accent: '#FF4D5E', Icon: Flame, items: live });
    if (next.length) out.push({ id: 'next', label: 'NEXT 6 HOURS', accent: '#5DC8FF', Icon: Hourglass, items: next });
    if (later.length) out.push({ id: 'later', label: 'LATER TODAY', accent: '#FFC850', Icon: CalendarDays, items: later });
    return out;
}
