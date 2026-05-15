/**
 * Sports Guide — natural-language "where can I watch …?" search.
 *
 *  Layout:
 *    1. Hero — "Sports Guide" title + tagline
 *    2. Live preview line — shows your current draft query
 *    3. TVKeyboard (themed on-screen keyboard — no Android IME)
 *    4. Suggestion chips ("Toronto Raptors", "NFL", …)
 *    5. After a search: ranked LLM matches as cards
 *    6. Always: "🔥 ON RIGHT NOW" + "⏰ COMING UP SOON" rails of
 *       sport programmes pulled from cached EPG — no LLM call,
 *       instant on first render.
 *
 *  How candidates are gathered (zero network):
 *    • Read sports-tagged channels + their EPG from localStorage.
 *    • Filter to programmes whose stop_ts > now and start_ts within
 *      the next 24 h.
 *    • Sorted by start time.
 *
 *  Sports Guide is *additive* to Live TV — both pull from the same
 *  liveCache so EPG warmed in one view powers the other.
 */

import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Trophy, Loader2, Play, Bell, Flame, Hourglass } from 'lucide-react';
import SideNav from '@/components/SideNav';
import TVKeyboard from '@/components/TVKeyboard';
import { getActiveProvider, getStreamUrl } from '@/lib/xtream';
import { loadCategories, loadChannels, loadEpg } from '@/lib/liveCache';
import { getReminders, toggleReminder } from '@/lib/liveReminders';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import Host from '@/lib/host';

const SUGGESTIONS = [
    'Toronto Raptors',
    'NFL Cowboys',
    'Premier League',
    'Champions League',
    'UFC',
    'Formula 1',
    'Tennis',
    'Cricket',
    'NBA',
    'Boxing',
];

export default function SportsGuide() {
    const provider = getActiveProvider();
    const navigate = useNavigate();

    /* Cached sports EPG — built once, used by suggestion rails AND
     * LLM candidate payload. */
    const sportsCandidates = useMemo(() => {
        if (!provider) return [];
        return gatherSportsEpg(provider.id);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider?.id]);

    /* Rail A — programmes currently airing on sports channels. */
    const liveNow = useMemo(() => {
        const nowSec = Math.floor(Date.now() / 1000);
        return sportsCandidates.filter(
            (it) => it.startTs <= nowSec && it.stopTs > nowSec,
        ).slice(0, 24);
    }, [sportsCandidates]);

    /* Rail B — programmes starting within the next 6 h. */
    const upNext = useMemo(() => {
        const nowSec = Math.floor(Date.now() / 1000);
        const horizon = nowSec + 6 * 3600;
        return sportsCandidates
            .filter((it) => it.startTs > nowSec && it.startTs <= horizon)
            .sort((a, b) => a.startTs - b.startTs)
            .slice(0, 24);
    }, [sportsCandidates]);

    const [query, setQuery] = useState('');
    const [loading, setLoading] = useState(false);
    const [results, setResults] = useState(null);    // null = pre-search
    const [error, setError] = useState('');

    const submit = useCallback(async (q) => {
        const text = (q ?? query).trim();
        if (!text) return;
        setQuery(text);
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

    /* Reminders snapshot used by every card to highlight bells. */
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

    /* Esc / Back goes home. */
    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                if ((document.activeElement?.tagName || '').toLowerCase() === 'input') return;
                e.preventDefault();
                navigate('/');
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [navigate]);

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
                <section style={{ padding: '32px 32px 16px 32px' }}>
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8,
                                    fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                                    letterSpacing: '0.32em', color: '#5DC8FF',
                                    marginBottom: 8 }}>
                        <Trophy size={13} color="#5DC8FF" />
                        SPORTS GUIDE
                    </div>
                    <h1 style={{ fontSize: 'clamp(24px, 2.6vw, 38px)', fontWeight: 800,
                                    letterSpacing: '-0.025em', lineHeight: 1.05, margin: 0,
                                    color: '#fff', marginBottom: 6 }}>
                        Where can I watch …?
                    </h1>
                    <p style={{ color: '#9DA5B5', fontSize: 13, margin: 0, marginBottom: 16 }}>
                        Type any team, league, fight, or fixture and we'll find the channel and time.
                    </p>

                    {/* Live draft preview — mirrors what's in the TVKeyboard */}
                    <DraftPreview query={query} />

                    <div style={{ marginTop: 12, width: '100%', maxWidth: 720 }}>
                        <TVKeyboard
                            value={query}
                            onChange={setQuery}
                            onSubmit={() => submit()}
                            maxLength={60}
                            variant="name"
                        />
                    </div>

                    {/* Suggestion chips — focus first chip sits in the
                        spatial-focus pool so D-pad up from the keyboard
                        can reach them.  Press OK to run the query. */}
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 16 }}>
                        {SUGGESTIONS.map((s) => (
                            <button
                                key={s}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => submit(s)}
                                style={{
                                    padding: '8px 14px',
                                    borderRadius: 999,
                                    background: 'rgba(255,255,255,0.05)',
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

                {/* Search results (only after a search submission) */}
                {(loading || error || (results !== null)) && (
                    <section style={{ padding: '8px 32px 16px 32px' }}>
                        <RailHeader icon={Trophy} label={`SEARCH · ${query.toUpperCase()}`} accent="#5DC8FF" />
                        {error && (
                            <div style={{ padding: 16, background: 'rgba(255,107,122,0.08)',
                                            border: '1px solid rgba(255,107,122,0.40)', borderRadius: 12,
                                            color: '#FF6B7A', fontSize: 13, marginBottom: 8 }}>
                                {error}
                            </div>
                        )}
                        {loading && (
                            <div style={{ padding: 16, color: '#9DA5B5', fontSize: 13,
                                            display: 'flex', alignItems: 'center', gap: 10 }}>
                                <Loader2 size={16} color="#5DC8FF" style={{ animation: 'spin 1.2s linear infinite' }} />
                                Searching the guide…
                            </div>
                        )}
                        {!loading && results !== null && results.length === 0 && !error && (
                            <div style={{ padding: 16, color: '#7d8493', fontSize: 13 }}>
                                No matches in the next 24 hours.  Try a different query.
                            </div>
                        )}
                        {!loading && results !== null && results.length > 0 && (
                            <Grid items={results.map((m) => ({
                                streamId: m.streamId,
                                channelName: m.channelName,
                                title: m.title,
                                description: m.description,
                                startTs: m.startTs,
                                stopTs: m.stopTs,
                            }))}
                                  reminders={reminders}
                                  onPlay={onPlay}
                                  onRemind={onRemind} />
                        )}
                    </section>
                )}

                {/* Always-on rails */}
                <section style={{ padding: '8px 32px 16px 32px' }}>
                    <RailHeader icon={Flame} label={`ON RIGHT NOW · ${liveNow.length}`} accent="#FF4D5E" />
                    {liveNow.length === 0 ? (
                        <EmptyRail text="No sports currently airing on your provider." />
                    ) : (
                        <Grid items={liveNow} reminders={reminders}
                              onPlay={onPlay} onRemind={onRemind} />
                    )}
                </section>

                <section style={{ padding: '8px 32px 40px 32px' }}>
                    <RailHeader icon={Hourglass} label={`COMING UP SOON · ${upNext.length}`} accent="#FFC850" />
                    {upNext.length === 0 ? (
                        <EmptyRail text="No upcoming sports in the next 6 hours." />
                    ) : (
                        <Grid items={upNext} reminders={reminders}
                              onPlay={onPlay} onRemind={onRemind} />
                    )}
                </section>
            </main>
            <style>{`@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }`}</style>
        </div>
    );
}

/* ─────────────────────────────── Bits ─────────────────────────────── */

function DraftPreview({ query }) {
    return (
        <div
            data-testid="sports-draft"
            style={{
                minHeight: 56,
                padding: '0 18px',
                display: 'flex', alignItems: 'center', gap: 12,
                background: 'rgba(20,28,42,0.6)',
                border: '1px solid ' + (query ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.10)'),
                borderRadius: 14,
            }}
        >
            <div style={{
                fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                letterSpacing: '0.28em',
                color: query ? '#5DC8FF' : '#5e6473',
                flexShrink: 0,
            }}>
                QUERY
            </div>
            <div style={{
                fontSize: 18, fontWeight: 700,
                color: query ? '#fff' : '#5e6473',
                minHeight: 22,
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                flex: 1, minWidth: 0,
            }}>
                {query || 'Start typing on the keyboard below…'}
            </div>
        </div>
    );
}

function RailHeader({ icon: Icon, label, accent }) {
    return (
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8,
                        marginBottom: 12,
                        fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                        letterSpacing: '0.28em', color: accent }}>
            <Icon size={14} color={accent} />
            {label}
        </div>
    );
}

function EmptyRail({ text }) {
    return (
        <div style={{ padding: '12px 14px', color: '#7d8493', fontSize: 12,
                        background: 'rgba(255,255,255,0.025)',
                        border: '1px solid rgba(255,255,255,0.06)',
                        borderRadius: 10 }}>
            {text}
        </div>
    );
}

function Grid({ items, reminders, onPlay, onRemind }) {
    return (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 12 }}>
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

function Card({ item, isReminded, onPlay, onRemind }) {
    const tmdb = useProgrammeBackdrop(item.title, item.channelName);
    const art = tmdb?.backdrop
        ? `${process.env.REACT_APP_BACKEND_URL}/api/img-proxy?url=${encodeURIComponent(tmdb.backdrop)}&w=540&q=55`
        : '';

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
                height: 110,
                position: 'relative',
                backgroundImage: art
                    ? `linear-gradient(180deg, rgba(17,24,42,0.05) 0%, rgba(17,24,42,0.88) 100%), url(${art})`
                    : 'linear-gradient(135deg, rgba(93,200,255,0.14) 0%, rgba(17,24,42,1) 100%)',
                backgroundSize: 'auto, cover',
                backgroundPosition: 'center',
            }}>
                <div style={{
                    position: 'absolute', top: 8, left: 10,
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '3px 9px',
                    background: 'rgba(93,200,255,0.18)',
                    border: '1px solid rgba(93,200,255,0.45)',
                    borderRadius: 999,
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    letterSpacing: '0.18em', color: '#5DC8FF',
                }}>
                    {labelFor(item)}
                </div>
            </div>
            <div style={{ padding: 12, display: 'flex', flexDirection: 'column', gap: 10, flex: 1 }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    letterSpacing: '0.22em', color: '#9DA5B5',
                }}>
                    {(item.channelName || '').toUpperCase()}
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
}

function labelFor(item) {
    const nowSec = Math.floor(Date.now() / 1000);
    if (item.startTs <= nowSec && item.stopTs > nowSec) return `LIVE · ${fmt(item.startTs)}`;
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

function gatherSportsEpg(providerId) {
    const cats = loadCategories(providerId) || [];
    const chans = loadChannels(providerId) || {};
    const epg = loadEpg(providerId) || {};

    const sportsCatIds = new Set(
        cats
            .filter((c) => /sport/i.test(c.category_name || ''))
            .map((c) => String(c.category_id)),
    );

    const channelLookup = new Map();
    for (const catId in chans) {
        if (!sportsCatIds.has(String(catId))) continue;
        for (const ch of (chans[catId] || [])) {
            channelLookup.set(String(ch.stream_id), ch.name || '');
        }
    }

    const nowSec = Math.floor(Date.now() / 1000);
    const horizonSec = nowSec + 24 * 3600;
    const out = [];
    for (const sid in epg) {
        const channelName = channelLookup.get(String(sid));
        if (!channelName) continue;
        for (const it of (epg[sid] || [])) {
            const start = Number(it.startTimestamp) || 0;
            const stop = Number(it.stopTimestamp) || 0;
            if (stop <= nowSec) continue;
            if (start > horizonSec) continue;
            out.push({
                streamId: sid,
                channelName,
                title: it.title || '',
                description: (it.description || '').slice(0, 220),
                startTs: start,
                stopTs: stop,
            });
        }
    }
    return out.sort((a, b) => a.startTs - b.startTs);
}
