/**
 * Sports Guide — natural-language "where can I watch …?" search.
 *
 *  Layout:
 *    • Top: compact search bar (single line).
 *    • Search bar focused → TVKeyboard pops up as a bottom overlay,
 *      blurring the content underneath.  Click anywhere outside or
 *      hit ESC to dismiss; ENTER on the keyboard runs the search.
 *    • Below the search bar: rails of cards
 *        🔥 ON RIGHT NOW
 *        ⏳ COMING UP SOON
 *      Once the user submits a query, a new SEARCH rail slots in
 *      at the top.
 *
 *  Cards:
 *    • Top half: TMDB programme backdrop (or a tinted gradient
 *      placeholder when no match).
 *    • Bottom half: small channel logo + channel name + time + title +
 *      Watch / Remind buttons.
 *
 *  Data:
 *    • Sports-tagged channels' EPG from localStorage.
 *    • Filtered to programmes ending in the future, starting in the
 *      next 24 h.
 *    • Sorted by start time.
 *    • Channel logos pulled from the same liveCache so they show
 *      next to the channel name in each card.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Trophy, Loader2, Play, Bell, Flame, Hourglass, Search, X } from 'lucide-react';
import SideNav from '@/components/SideNav';
import TVKeyboard from '@/components/TVKeyboard';
import { getActiveProvider, getStreamUrl } from '@/lib/xtream';
import { loadCategories, loadChannels, loadEpg } from '@/lib/liveCache';
import { getReminders, toggleReminder } from '@/lib/liveReminders';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import Host from '@/lib/host';

const SUGGESTIONS = [
    'Toronto Raptors',
    'Cowboys',
    'Premier League',
    'Champions League',
    'UFC',
    'Formula 1',
    'Tennis',
    'Cricket',
    'NBA',
    'Boxing',
];

function proxy(url, w = 64, q = 55) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${w}&q=${q}`;
}

export default function SportsGuide() {
    const provider = getActiveProvider();
    const navigate = useNavigate();

    const sportsCandidates = useMemo(() => {
        if (!provider) return [];
        return gatherSportsEpg(provider.id);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider?.id]);

    const liveNow = useMemo(() => {
        const nowSec = Math.floor(Date.now() / 1000);
        return sportsCandidates.filter(
            (it) => it.startTs <= nowSec && it.stopTs > nowSec,
        ).slice(0, 24);
    }, [sportsCandidates]);

    const upNext = useMemo(() => {
        const nowSec = Math.floor(Date.now() / 1000);
        const horizon = nowSec + 6 * 3600;
        return sportsCandidates
            .filter((it) => it.startTs > nowSec && it.startTs <= horizon)
            .slice(0, 24);
    }, [sportsCandidates]);

    const [query, setQuery] = useState('');
    const [loading, setLoading] = useState(false);
    const [results, setResults] = useState(null);
    const [error, setError] = useState('');
    const [kbOpen, setKbOpen] = useState(false);

    const submit = useCallback(async (q) => {
        const text = (q ?? query).trim();
        if (!text) return;
        setQuery(text);
        setKbOpen(false);
        setLoading(true);
        setError('');
        try {
            const r = await axios.post(
                `${process.env.REACT_APP_BACKEND_URL}/api/sports/find`,
                { query: text, candidates: sportsCandidates.slice(0, 80) },
                { timeout: 25000 },
            );
            setResults(r.data?.matches || []);
        } catch (e) {
            setError(e?.response?.data?.detail || e?.message || 'Search failed.');
            setResults([]);
        } finally {
            setLoading(false);
        }
    }, [query, sportsCandidates]);

    const [reminderTick, setReminderTick] = useState(0);
    const reminders = useMemo(
        () => (provider ? new Set(getReminders(provider.id).map((r) => r.id)) : new Set()),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [provider, reminderTick],
    );

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
        setReminderTick((t) => t + 1);
    }, [provider]);

    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                if (kbOpen) {
                    e.preventDefault();
                    setKbOpen(false);
                    return;
                }
                if ((document.activeElement?.tagName || '').toLowerCase() === 'input') return;
                e.preventDefault();
                navigate('/');
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [navigate, kbOpen]);

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
            <main style={{ position: 'absolute', inset: '0 0 0 100px',
                            display: 'flex', flexDirection: 'column', overflow: 'auto' }}>
                <section style={{ padding: '28px 32px 8px 32px' }}>
                    <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginBottom: 14 }}>
                        <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8,
                                            fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                            letterSpacing: '0.32em', color: '#5DC8FF',
                                            marginBottom: 6 }}>
                                <Trophy size={12} color="#5DC8FF" />
                                SPORTS GUIDE
                            </div>
                            <h1 style={{ fontSize: 'clamp(22px, 2.2vw, 32px)', fontWeight: 800,
                                            letterSpacing: '-0.025em', lineHeight: 1.1, margin: 0,
                                            color: '#fff' }}>
                                Where can I watch …?
                            </h1>
                        </div>
                    </div>

                    {/* One-line search trigger.  Tapping it opens the
                        TVKeyboard overlay; it is otherwise a static
                        display of the current query. */}
                    <SearchTrigger
                        query={query}
                        onOpen={() => setKbOpen(true)}
                        onClear={() => { setQuery(''); setResults(null); }}
                    />

                    {/* Suggestion chips — always visible.  Run a search
                        immediately when clicked. */}
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 12 }}>
                        {SUGGESTIONS.map((s) => (
                            <button
                                key={s}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => submit(s)}
                                style={{
                                    padding: '7px 13px',
                                    borderRadius: 999,
                                    background: 'rgba(255,255,255,0.04)',
                                    border: '1px solid rgba(255,255,255,0.10)',
                                    color: '#E6EAF2',
                                    fontSize: 12, fontWeight: 600,
                                    cursor: 'pointer',
                                }}
                            >
                                {s}
                            </button>
                        ))}
                    </div>
                </section>

                {/* Search results */}
                {(loading || error || (results !== null)) && (
                    <section style={{ padding: '8px 32px 12px 32px' }}>
                        <RailHeader icon={Search} label={`SEARCH · ${query.toUpperCase()}`} accent="#5DC8FF" />
                        {error && (
                            <div style={{ padding: 14, background: 'rgba(255,107,122,0.08)',
                                            border: '1px solid rgba(255,107,122,0.40)', borderRadius: 12,
                                            color: '#FF6B7A', fontSize: 13 }}>
                                {error}
                            </div>
                        )}
                        {loading && (
                            <div style={{ padding: 14, color: '#9DA5B5', fontSize: 13,
                                            display: 'flex', alignItems: 'center', gap: 10 }}>
                                <Loader2 size={16} color="#5DC8FF" style={{ animation: 'spin 1.2s linear infinite' }} />
                                Searching…
                            </div>
                        )}
                        {!loading && results !== null && results.length === 0 && !error && (
                            <div style={{ padding: 14, color: '#7d8493', fontSize: 13 }}>
                                No matches in the next 24 hours.
                            </div>
                        )}
                        {!loading && results !== null && results.length > 0 && (
                            <Grid items={results.map((m) => decorateWithIcon(m, provider?.id))}
                                  reminders={reminders} onPlay={onPlay} onRemind={onRemind} />
                        )}
                    </section>
                )}

                {/* Always-on rails */}
                <section style={{ padding: '8px 32px 12px 32px' }}>
                    <RailHeader icon={Flame} label={`ON RIGHT NOW · ${liveNow.length}`} accent="#FF4D5E" />
                    {liveNow.length === 0
                        ? <EmptyRail text="No sports currently airing on your provider." />
                        : <Grid items={liveNow} reminders={reminders} onPlay={onPlay} onRemind={onRemind} />}
                </section>

                <section style={{ padding: '8px 32px 40px 32px' }}>
                    <RailHeader icon={Hourglass} label={`COMING UP SOON · ${upNext.length}`} accent="#FFC850" />
                    {upNext.length === 0
                        ? <EmptyRail text="No upcoming sports in the next 6 hours." />
                        : <Grid items={upNext} reminders={reminders} onPlay={onPlay} onRemind={onRemind} />}
                </section>
            </main>

            {/* Floating keyboard overlay — only present when search is focused. */}
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

/* ──────────────────────────── Pieces ──────────────────────────── */

function SearchTrigger({ query, onOpen, onClear }) {
    const has = !!query;
    return (
        <button
            data-focusable="true"
            data-focus-style="quiet"
            tabIndex={0}
            onClick={onOpen}
            style={{
                width: '100%',
                display: 'flex', alignItems: 'center', gap: 12,
                padding: '0 18px',
                height: 52,
                background: 'rgba(20,28,42,0.6)',
                border: '1px solid ' + (has ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.10)'),
                borderRadius: 14,
                color: '#fff', textAlign: 'left',
                cursor: 'pointer',
            }}
        >
            <Search size={16} color={has ? '#5DC8FF' : '#7d8493'} />
            <span style={{
                flex: 1, minWidth: 0,
                fontSize: 14, fontWeight: 600,
                color: has ? '#fff' : '#5e6473',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {has ? query : 'Tap to search — “Toronto Raptors”, “UFC”, “Cowboys”…'}
            </span>
            {has && (
                <span
                    role="button"
                    aria-label="Clear search"
                    onClick={(e) => { e.stopPropagation(); onClear(); }}
                    style={{
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                        width: 26, height: 26,
                        borderRadius: 999,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.10)',
                    }}
                >
                    <X size={12} color="#9DA5B5" />
                </span>
            )}
        </button>
    );
}

function KeyboardOverlay({ query, onChange, onSubmit, onClose }) {
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
                    width: 'min(840px, 100%)',
                    margin: '0 24px 24px 24px',
                    background: '#11182A',
                    border: '1px solid rgba(255,255,255,0.10)',
                    borderRadius: 16,
                    padding: 20,
                    boxShadow: '0 24px 60px rgba(0,0,0,0.65), inset 0 1px 0 rgba(255,255,255,0.06)',
                }}
            >
                <div style={{
                    minHeight: 48,
                    padding: '0 16px',
                    display: 'flex', alignItems: 'center', gap: 12,
                    background: 'rgba(20,28,42,0.6)',
                    border: '1px solid ' + (query ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.10)'),
                    borderRadius: 12,
                    marginBottom: 14,
                }}>
                    <Search size={16} color={query ? '#5DC8FF' : '#7d8493'} />
                    <span style={{
                        flex: 1, minWidth: 0,
                        fontSize: 15, fontWeight: 700,
                        color: query ? '#fff' : '#5e6473',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {query || 'Start typing…'}
                    </span>
                    <span style={{
                        fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                        letterSpacing: '0.22em', color: '#7d8493',
                    }}>
                        OK = SEARCH
                    </span>
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

function RailHeader({ icon: Icon, label, accent }) {
    return (
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8,
                        marginBottom: 10,
                        fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                        letterSpacing: '0.28em', color: accent }}>
            <Icon size={13} color={accent} />
            {label}
        </div>
    );
}

function EmptyRail({ text }) {
    return (
        <div style={{ padding: '10px 14px', color: '#7d8493', fontSize: 12,
                        background: 'rgba(255,255,255,0.025)',
                        border: '1px solid rgba(255,255,255,0.06)',
                        borderRadius: 10 }}>
            {text}
        </div>
    );
}

function Grid({ items, reminders, onPlay, onRemind }) {
    return (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 12 }}>
            {items.map((it) => (
                <Card
                    key={`${it.streamId}-${it.startTs}`}
                    item={it}
                    isReminded={reminders.has(`${Number(it.streamId) || it.streamId}:${Number(it.startTs) || it.startTs}`)}
                    onPlay={() => onPlay(it)}
                    onRemind={() => onRemind(it)}
                />
            ))}
        </div>
    );
}

const Card = React.memo(function Card({ item, isReminded, onPlay, onRemind }) {
    const tmdb = useProgrammeBackdrop(item.title, item.channelName);
    const art = tmdb?.backdrop
        ? proxy(tmdb.backdrop, 540, 55)
        : '';
    const channelLogo = item.streamIcon ? proxy(item.streamIcon, 48, 60) : '';
    const isLive = isLiveNow(item);

    return (
        <div style={{
            background: '#11182A',
            border: '1px solid rgba(255,255,255,0.07)',
            borderRadius: 12,
            overflow: 'hidden',
            display: 'flex', flexDirection: 'column',
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.05)',
        }}>
            <div style={{
                height: 130,
                position: 'relative',
                backgroundImage: art
                    ? `linear-gradient(180deg, rgba(17,24,42,0.1) 0%, rgba(17,24,42,0.85) 100%), url(${art})`
                    : 'linear-gradient(135deg, rgba(93,200,255,0.16) 0%, rgba(17,24,42,1) 100%)',
                backgroundSize: 'auto, cover',
                backgroundPosition: 'center',
            }}>
                {/* Time / status badge */}
                <div style={{
                    position: 'absolute', top: 10, left: 10,
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '3px 9px',
                    background: isLive ? 'rgba(255,77,94,0.20)' : 'rgba(93,200,255,0.20)',
                    border: '1px solid ' + (isLive ? 'rgba(255,77,94,0.55)' : 'rgba(93,200,255,0.55)'),
                    borderRadius: 999,
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    letterSpacing: '0.18em', color: isLive ? '#FF6B7A' : '#5DC8FF',
                }}>
                    {labelFor(item)}
                </div>
                {/* TMDB watermark — only when no cover */}
                {!art && (
                    <div style={{
                        position: 'absolute', bottom: 10, right: 12,
                        fontFamily: 'monospace', fontSize: 9, letterSpacing: '0.22em',
                        color: '#5e6473', fontWeight: 700,
                    }}>
                        NO COVER ART
                    </div>
                )}
            </div>
            <div style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 10, flex: 1 }}>
                {/* Channel logo row */}
                <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                    <div style={{
                        width: 30, height: 22, flexShrink: 0,
                        background: 'rgba(255,255,255,0.04)',
                        borderRadius: 4,
                        overflow: 'hidden', position: 'relative',
                    }}>
                        {channelLogo && (
                            <img
                                src={channelLogo}
                                alt=""
                                width={30}
                                height={22}
                                loading="lazy"
                                decoding="async"
                                referrerPolicy="no-referrer"
                                onError={(e) => { e.currentTarget.style.display = 'none'; }}
                                style={{
                                    position: 'absolute', inset: 0,
                                    width: '100%', height: '100%',
                                    objectFit: 'contain', padding: 2,
                                }}
                            />
                        )}
                    </div>
                    <span style={{
                        fontSize: 11, fontWeight: 700, letterSpacing: '0.08em',
                        color: '#fff',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        flex: 1, minWidth: 0,
                    }}>
                        {item.channelName || ''}
                    </span>
                </div>
                <div style={{ fontSize: 14, fontWeight: 700, color: '#fff', lineHeight: 1.25,
                                display: '-webkit-box',
                                WebkitLineClamp: 2, WebkitBoxOrient: 'vertical',
                                overflow: 'hidden', textOverflow: 'ellipsis',
                                flex: 1, minHeight: 0 }}>
                    {item.title || 'Untitled'}
                </div>
                <div style={{ display: 'flex', gap: 6 }}>
                    <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            onClick={onPlay} style={{
                        flex: 1,
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                        height: 34, padding: '0 12px', borderRadius: 8,
                        background: '#5DC8FF', color: '#0A0F1A',
                        fontWeight: 700, fontSize: 12, border: 'none', cursor: 'pointer',
                    }}>
                        <Play size={11} fill="#0A0F1A" /> Watch
                    </button>
                    <button data-focusable="true" data-focus-style="pill" tabIndex={0}
                            onClick={onRemind} style={{
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 5,
                        height: 34, padding: '0 12px', borderRadius: 8,
                        background: isReminded ? 'rgba(255,200,80,0.16)' : 'rgba(255,255,255,0.05)',
                        border: '1px solid ' + (isReminded ? 'rgba(255,200,80,0.45)' : 'rgba(255,255,255,0.12)'),
                        color: isReminded ? '#FFC850' : '#E6EAF2',
                        fontWeight: 600, fontSize: 12, cursor: 'pointer',
                    }}>
                        <Bell size={11} fill={isReminded ? '#FFC850' : 'none'} color={isReminded ? '#FFC850' : 'currentColor'} />
                        {isReminded ? 'On' : 'Remind'}
                    </button>
                </div>
            </div>
        </div>
    );
});

/* ──────────────────────────── Helpers ──────────────────────────── */

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

/* When the LLM returns matches, decorate each one with the channel
 * icon URL from our local cache.  Cheap O(channels) walk. */
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
        cats
            .filter((c) => /sport/i.test(c.category_name || ''))
            .map((c) => String(c.category_id)),
    );

    // channelLookup keeps not just the name but the logo URL too.
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
