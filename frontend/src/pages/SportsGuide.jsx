/**
 * Sports Guide — natural-language "where can I watch …?" search.
 *
 * UI:
 *   • Big search input + chip suggestions ("Toronto Raptors", "NFL",
 *     "Premier League") to seed common queries.
 *   • Hits the backend `/api/sports/find` endpoint which uses
 *     gemini-2.0-flash to match the user query against the EPG
 *     gathered from sports-tagged channels.
 *   • Each result card shows: cover art (TMDB), channel, time,
 *     title, with "Watch Now" + "Set Reminder" actions.
 *
 * How we gather candidates:
 *   • Scan localStorage (liveCache) for every cached EPG entry.
 *   • Limit to channels whose category name contains "sport".
 *   • Limit to programmes starting within the next 24 h (and not
 *     yet ended) so we don't waste the LLM context on stale
 *     entries.
 *
 * Performance:
 *   • Zero network calls until the user submits.
 *   • Candidate gathering happens once per query, in memory.
 *   • TMDB backdrops use the same proxy + hook pattern as Live TV.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { Search, Trophy, Loader2, Play, Bell } from 'lucide-react';
import SideNav from '@/components/SideNav';
import { getActiveProvider, getStreamUrl } from '@/lib/xtream';
import {
    loadCategories,
    loadChannels,
    loadEpg,
} from '@/lib/liveCache';
import {
    getReminders,
    toggleReminder,
} from '@/lib/liveReminders';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import Host from '@/lib/host';

const SUGGESTIONS = [
    'Toronto Raptors',
    'NFL Cowboys',
    'Premier League',
    'Champions League',
    'UFC',
    'F1',
    'Tennis',
    'Cricket',
];

export default function SportsGuide() {
    const [provider, setProvider] = useState(() => getActiveProvider());
    const navigate = useNavigate();

    /* Synchronously gather sports-tagged EPG candidates from disk. */
    const candidates = useMemo(() => {
        if (!provider) return [];
        const cats = loadCategories(provider.id) || [];
        const chans = loadChannels(provider.id) || {};
        const epg = loadEpg(provider.id) || {};

        const sportsCatIds = new Set(
            cats
                .filter((c) => /sport/i.test(c.category_name || ''))
                .map((c) => String(c.category_id)),
        );

        const channelLookup = new Map(); // streamId -> { name, category }
        for (const catId in chans) {
            if (!sportsCatIds.has(String(catId))) continue;
            const catName = cats.find((c) => String(c.category_id) === String(catId))?.category_name || '';
            for (const ch of (chans[catId] || [])) {
                channelLookup.set(String(ch.stream_id), { name: ch.name, category: catName });
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
                if (stop <= nowSec) continue;       // already ended
                if (start > horizonSec) continue;   // beyond 24 h
                out.push({
                    streamId: sid,
                    channelName: meta.name,
                    title: it.title || '',
                    description: (it.description || '').slice(0, 220),
                    startTs: start,
                    stopTs: stop,
                });
            }
        }
        // Cap at ~80 to stay under the LLM payload limit.
        return out.slice(0, 80);
    }, [provider]);

    const [query, setQuery] = useState('');
    const [loading, setLoading] = useState(false);
    const [results, setResults] = useState(null); // null = pre-search, [] = no matches
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
                { query: text, candidates },
                { timeout: 25000 },
            );
            setResults(r.data?.matches || []);
        } catch (e) {
            setError(e?.response?.data?.detail || e?.message || 'Search failed.');
            setResults([]);
        } finally {
            setLoading(false);
        }
    }, [query, candidates]);

    const reminders = useMemo(
        () => (provider ? new Set(getReminders(provider.id).map((r) => r.id)) : new Set()),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [provider, results],
    );

    const playMatch = useCallback(async (m) => {
        if (!provider) return;
        const chans = loadChannels(provider.id) || {};
        let ch = null;
        for (const k in chans) {
            ch = (chans[k] || []).find((x) => String(x.stream_id) === String(m.streamId));
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

    const remindMatch = useCallback((m) => {
        if (!provider || !m?.startTs) return;
        toggleReminder(provider.id, m.streamId, {
            channelName: m.channelName,
            title: m.title,
            startTs: m.startTs,
            stopTs: m.stopTs,
        });
        // Trigger a refresh of reminders set so the UI updates.
        setResults((r) => (r ? [...r] : r));
    }, [provider]);

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
                <section style={{ padding: '40px 32px 24px 32px' }}>
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8,
                                    fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                                    letterSpacing: '0.32em', color: '#5DC8FF',
                                    marginBottom: 10 }}>
                        <Trophy size={13} color="#5DC8FF" />
                        SPORTS GUIDE
                    </div>
                    <h1 style={{ fontSize: 'clamp(28px, 3vw, 44px)', fontWeight: 800,
                                    letterSpacing: '-0.025em', lineHeight: 1.05, margin: 0,
                                    color: '#fff', marginBottom: 8 }}>
                        Where can I watch …?
                    </h1>
                    <p style={{ color: '#9DA5B5', fontSize: 14, margin: 0, marginBottom: 22 }}>
                        Ask for any team, league, fight, or fixture — we'll find the channel and time.
                    </p>

                    <SearchInput
                        query={query}
                        onChange={setQuery}
                        onSubmit={() => submit()}
                        loading={loading}
                    />

                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginTop: 14 }}>
                        {SUGGESTIONS.map((s) => (
                            <button
                                key={s}
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

                <section style={{ padding: '8px 32px 40px 32px', flex: 1, minHeight: 0 }}>
                    {error && (
                        <div style={{ padding: 16, background: 'rgba(255,107,122,0.08)',
                                        border: '1px solid rgba(255,107,122,0.40)', borderRadius: 12,
                                        color: '#FF6B7A', fontSize: 13, marginBottom: 16 }}>
                            {error}
                        </div>
                    )}
                    {loading && (
                        <div style={{ padding: 24, color: '#9DA5B5', fontSize: 13,
                                        display: 'flex', alignItems: 'center', gap: 10 }}>
                            <Loader2 size={16} color="#5DC8FF" style={{ animation: 'spin 1.2s linear infinite' }} />
                            Searching the guide…
                        </div>
                    )}
                    {results !== null && !loading && results.length === 0 && (
                        <div style={{ padding: 24, color: '#7d8493', fontSize: 13 }}>
                            No matches in the next 24 hours.  Try a different search, or check Live TV
                            once the EPG has finished downloading.
                        </div>
                    )}
                    {results !== null && results.length > 0 && (
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))',
                                        gap: 16 }}>
                            {results.map((m) => (
                                <ResultCard
                                    key={`${m.streamId}-${m.startTs}`}
                                    match={m}
                                    isReminded={reminders.has(`${Number(m.streamId) || m.streamId}:${Number(m.startTs) || m.startTs}`)}
                                    onPlay={() => playMatch(m)}
                                    onRemind={() => remindMatch(m)}
                                />
                            ))}
                        </div>
                    )}
                </section>
            </main>
            <style>{`@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }`}</style>
        </div>
    );
}

function SearchInput({ query, onChange, onSubmit, loading }) {
    const ref = useRef(null);
    useEffect(() => { ref.current?.focus(); }, []);
    return (
        <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12,
                            padding: '0 18px',
                            background: 'rgba(20,28,42,0.6)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            borderRadius: 14, height: 56, flex: 1 }}>
                <Search size={18} color="#7d8493" />
                <input
                    ref={ref}
                    type="text"
                    value={query}
                    onChange={(e) => onChange(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') onSubmit(); }}
                    placeholder="e.g.  Toronto Raptors  ·  Cowboys game  ·  UFC fight tonight"
                    style={{ flex: 1, minWidth: 0, background: 'transparent', border: 'none',
                                outline: 'none', color: '#fff', fontSize: 15 }}
                />
            </div>
            <button
                onClick={onSubmit}
                disabled={loading || !query.trim()}
                style={{
                    height: 56, padding: '0 26px',
                    background: query.trim() ? '#5DC8FF' : 'rgba(255,255,255,0.06)',
                    color: query.trim() ? '#0A0F1A' : '#7d8493',
                    border: 'none', borderRadius: 14,
                    fontWeight: 700, fontSize: 14,
                    cursor: query.trim() ? 'pointer' : 'not-allowed',
                }}
            >
                Find
            </button>
        </div>
    );
}

function ResultCard({ match, isReminded, onPlay, onRemind }) {
    const tmdb = useProgrammeBackdrop(match.title, match.channelName);
    const art = tmdb?.backdrop
        ? `${process.env.REACT_APP_BACKEND_URL}/api/img-proxy?url=${encodeURIComponent(tmdb.backdrop)}&w=600&q=60`
        : '';

    const startStr = formatTime(match.startTs);
    const day = dayLabel(match.startTs);

    return (
        <div style={{
            background: '#11182A',
            border: '1px solid rgba(255,255,255,0.07)',
            borderRadius: 14,
            overflow: 'hidden',
            boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.05)',
        }}>
            <div style={{
                height: 130,
                position: 'relative',
                backgroundImage: art
                    ? `linear-gradient(180deg, rgba(17,24,42,0.1) 0%, rgba(17,24,42,0.85) 100%), url(${art})`
                    : 'linear-gradient(135deg, rgba(93,200,255,0.15) 0%, rgba(17,24,42,1) 100%)',
                backgroundSize: 'auto, cover',
                backgroundPosition: 'center',
            }}>
                <div style={{
                    position: 'absolute', top: 10, left: 12,
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '3px 9px',
                    background: 'rgba(93,200,255,0.18)',
                    border: '1px solid rgba(93,200,255,0.45)',
                    borderRadius: 999,
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    letterSpacing: '0.18em', color: '#5DC8FF',
                }}>
                    {day} · {startStr}
                </div>
            </div>
            <div style={{ padding: 16 }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    letterSpacing: '0.22em', color: '#9DA5B5', marginBottom: 6,
                }}>
                    {(match.channelName || '').toUpperCase()}
                </div>
                <div style={{ fontSize: 16, fontWeight: 700, color: '#fff', lineHeight: 1.25,
                                marginBottom: 14,
                                display: '-webkit-box',
                                WebkitLineClamp: 2,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis' }}>
                    {match.title || 'Untitled'}
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button onClick={onPlay} style={{
                        flex: 1,
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                        height: 38, padding: '0 14px', borderRadius: 10,
                        background: '#5DC8FF', color: '#0A0F1A',
                        fontWeight: 700, fontSize: 13, border: 'none', cursor: 'pointer',
                    }}>
                        <Play size={12} fill="#0A0F1A" /> Watch Now
                    </button>
                    <button onClick={onRemind} style={{
                        display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                        height: 38, padding: '0 14px', borderRadius: 10,
                        background: isReminded ? 'rgba(255,200,80,0.16)' : 'rgba(255,255,255,0.05)',
                        border: '1px solid ' + (isReminded ? 'rgba(255,200,80,0.45)' : 'rgba(255,255,255,0.12)'),
                        color: isReminded ? '#FFC850' : '#E6EAF2',
                        fontWeight: 600, fontSize: 13, cursor: 'pointer',
                    }}>
                        <Bell size={12} fill={isReminded ? '#FFC850' : 'none'} color={isReminded ? '#FFC850' : 'currentColor'} />
                        {isReminded ? 'Reminding' : 'Remind'}
                    </button>
                </div>
            </div>
        </div>
    );
}

function formatTime(ts) {
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
