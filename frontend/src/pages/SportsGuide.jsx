/**
 * Sports Guide — V3.
 *
 *  Source-of-truth for fixtures:  TheSportsDB (via backend
 *  /api/sportsdb/fixtures).  Returns ~20-60 upcoming events across
 *  curated top leagues with team badges, league badges, venue, and
 *  kickoff time.
 *
 *  Match → Channel binding:  Each fixture is matched against the user's
 *  IPTV sports-channel EPG (via lib/sportsMatch).  Matching channels are
 *  rendered as "WATCH ON …" chips at the bottom of the card; OK plays
 *  the top-scoring channel directly.
 *
 *  Layout (TV-first, 10-foot UI):
 *    ┌────────────────────────────────────────────────────────────┐
 *    │ HERO  (marquee fixture: next big event, glossy)            │
 *    └────────────────────────────────────────────────────────────┘
 *    [SPORT PILLS]  [   DATE PILLS   ]
 *    ┌────────────────────────────────────────────────────────────┐
 *    │ LEAGUE BANNER · LEAGUE NAME · count                        │
 *    │ FIXTURE CARDS (2-col grid)                                 │
 *    └────────────────────────────────────────────────────────────┘
 *
 *  D-pad model:
 *    ↑/↓  walks through pills → hero → league/cards (rows).
 *    ←/→  cycles pills, or moves columns within the same row.
 *    Enter on card  → play top matching channel (or open card detail).
 *    Hold Enter     → set reminder for kickoff − 5 min.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import {
    Trophy, Bell, Clock, MapPin, Tv, Radio, ChevronLeft,
    Loader2, AlertTriangle, Calendar, Flame, Play, Award,
} from 'lucide-react';
import SideNav from '@/components/SideNav';
import DPadHint from '@/components/DPadHint';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useHomeBackHandler from '@/hooks/useHomeBackHandler';
import useBackHandler from '@/hooks/useBackHandler';
import { getActiveProvider, getStreamUrl } from '@/lib/xtream';
import { matchFixture, clearMatchCache } from '@/lib/sportsMatch';
import { getReminders, toggleReminder } from '@/lib/liveReminders';
import { loadChannels, subscribeLiveCache } from '@/lib/liveCache';
import Host from '@/lib/host';

function proxy(url, w = 80, q = 60) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${w}&q=${q}`;
}

const SPORT_EMOJI = {
    'Soccer':              '⚽',
    'American Football':   '🏈',
    'Basketball':          '🏀',
    'Ice Hockey':          '🏒',
    'Baseball':            '⚾',
    'Rugby League':        '🏉',
    'Rugby Union':         '🏉',
    'Rugby':               '🏉',
    'Australian Football': '🏉',
    'Cricket':             '🏏',
    'Motorsport':          '🏁',
    'MMA':                 '🥊',
    'Fighting':            '🥊',
    'Boxing':              '🥊',
    'Tennis':              '🎾',
    'Golf':                '⛳',
};

const SPORT_ACCENT = {
    'Soccer':              '#5DC8FF',
    'American Football':   '#FF8855',
    'Basketball':          '#FFA844',
    'Ice Hockey':          '#8DC9FF',
    'Baseball':            '#FFE08A',
    'Rugby League':        '#FF6BCB',
    'Rugby Union':         '#7AE2A8',
    'Rugby':               '#7AE2A8',
    'Australian Football': '#FF6B7A',
    'Cricket':             '#A7F0BA',
    'Motorsport':          '#FF4D5E',
    'MMA':                 '#FF4D5E',
    'Fighting':            '#FF4D5E',
    'Boxing':              '#FFC850',
    'Tennis':              '#D7FF6B',
    'Golf':                '#A7F0BA',
};

const ACCENT_DEFAULT = '#5DC8FF';

// Marquee leagues — when picking the hero fixture we prefer these over
// random smaller leagues for a more cinematic landing experience.
const MARQUEE_LEAGUES = new Set([
    '4328', // Premier League
    '4335', // La Liga
    '4332', // Serie A
    '4331', // Bundesliga
    '4334', // Ligue 1
    '4480', // Champions League
    '4481', // Europa League
    '4346', // MLS
    '4391', // NFL
    '4387', // NBA
    '4380', // NHL
    '4424', // MLB
    '4370', // Formula 1
    '4443', // UFC
    '4548', // IPL
    '4416', // Australian National Rugby League (NRL)
    '4415', // English Rugby League Super League
]);

export default function SportsGuide() {
    const navigate = useNavigate();
    const provider = getActiveProvider();

    // Spatial D-pad focus — same global hook Home / Detail / Search use.
    // Without this, ArrowKeys don't move focus on the TV box.
    useSpatialFocus();
    // Mark this page so the Kotlin Back-key handler treats Back as
    // "navigate to /" (web history pop), not an exit prompt.
    useHomeBackHandler('');
    // Remote BACK key (Escape / Backspace from the Kotlin wrapper) →
    // back to Home.  Without this, BACK gets ignored on /sports.
    useBackHandler('/');

    const [data, setData] = useState(null);
    const [liveScores, setLiveScores] = useState({});   // id → score patch
    const [loading, setLoading] = useState(true);
    const [err, setErr] = useState('');

    const [sportFilter, setSportFilter] = useState('all'); // 'all' or sport name
    const [dayFilter, setDayFilter]   = useState(0);       // 0=Today, 1=Tomorrow, ..., 6 / -1=Live, -2=All
    const [, setBump] = useState(0);
    /* `cacheVer` — bumped every time liveCache emits a notification
     * (EPG / channels / cats just got merged from the instant
     * bundle).  We thread it through to FixtureCard / HeroFixture
     * via useMemo deps so they re-run matchFixture() the instant
     * the data arrives, instead of staying stuck on the empty
     * result that was computed during initial mount.
     *
     * Also clears `sportsMatch`'s own 60s index TTL so buildIndex
     * gets fresh EPG data on the next call. */
    const [cacheVer, setCacheVer] = useState(0);
    useEffect(() => {
        const unsub = subscribeLiveCache(() => {
            clearMatchCache();
            setCacheVer((v) => v + 1);
        });
        return () => { unsub(); };
    }, []);

    /* Load fixtures once. */
    useEffect(() => {
        let aborted = false;
        (async () => {
            try {
                const r = await axios.get(
                    `${process.env.REACT_APP_BACKEND_URL}/api/sportsdb/fixtures`,
                    { timeout: 60000 },
                );
                if (aborted) return;
                setData(r.data);
                setLoading(false);
            } catch (e) {
                if (aborted) return;
                setErr(e?.message || 'Could not load fixtures.');
                setLoading(false);
            }
        })();
        return () => { aborted = true; };
    }, []);

    /* Poll /api/sportsdb/livescores every 30 s so live tickers update. */
    useEffect(() => {
        let aborted = false;
        let timer;
        const tick = async () => {
            try {
                const r = await axios.get(
                    `${process.env.REACT_APP_BACKEND_URL}/api/sportsdb/livescores`,
                    { timeout: 8000 },
                );
                if (aborted) return;
                const map = {};
                for (const s of (r.data?.scores || [])) map[s.id] = s;
                setLiveScores(map);
            } catch { /* ignore */ }
            if (!aborted) timer = setTimeout(tick, 30000);
        };
        tick();
        return () => { aborted = true; if (timer) clearTimeout(timer); };
    }, []);

    /* ─── Derive filtered fixtures + per-league grouping ─── */
    const baseEvents = data?.events || [];
    const sportsMeta = data?.sportsMeta || [];

    /* Patch in live score updates (id-keyed) so the cards tick up live. */
    const allEvents = useMemo(() => {
        if (!Object.keys(liveScores).length) return baseEvents;
        return baseEvents.map((e) => {
            const patch = liveScores[e.id];
            if (!patch) return e;
            return {
                ...e,
                homeScore:   patch.homeScore   ?? e.homeScore,
                awayScore:   patch.awayScore   ?? e.awayScore,
                status:      patch.status      ?? e.status,
                statusShort: patch.statusShort ?? e.statusShort,
                state:       patch.state       ?? e.state,
                finished:    patch.finished    ?? e.finished,
                live:        patch.live        ?? e.live,
            };
        });
    }, [baseEvents, liveScores]);

    const filtered = useMemo(() => {
        let out = allEvents;
        if (sportFilter !== 'all') {
            out = out.filter((e) => (e.sport || 'Other') === sportFilter);
        }
        if (dayFilter === -1) {
            // LIVE NOW — anything with explicit live flag, or ESPN state==='in',
            // or kickoff within the last 3h that isn't finished.
            const now = Math.floor(Date.now() / 1000);
            out = out.filter((e) =>
                e.live === true || e.state === 'in' ||
                (e.ts <= now + 600 && e.ts >= now - 3 * 3600 && !e.finished),
            );
        } else if (dayFilter >= 0) {
            const today0 = midnight(0);
            const start = today0 + dayFilter * 86400;
            const end = start + 86400;
            out = out.filter((e) => e.ts >= start && e.ts < end);
        }
        return out;
    }, [allEvents, sportFilter, dayFilter]);

    /* Group filtered fixtures by league (preserve order by earliest kickoff). */
    const groups = useMemo(() => {
        const m = new Map();
        for (const e of filtered) {
            const k = e.leagueId || e.league || 'other';
            if (!m.has(k)) {
                m.set(k, {
                    id: k,
                    name: e.league || 'Other',
                    sport: e.sport || '',
                    badge: e.leagueBadge || '',
                    events: [],
                });
            }
            const g = m.get(k);
            // Backfill the league badge from any event that has one.
            if (!g.badge && e.leagueBadge) g.badge = e.leagueBadge;
            g.events.push(e);
        }
        return Array.from(m.values()).sort((a, b) => {
            const aT = a.events[0]?.ts || 0;
            const bT = b.events[0]?.ts || 0;
            return aT - bT;
        });
    }, [filtered]);

    /* Available days (today + next 6 with content) for the date strip. */
    const availableDays = useMemo(() => {
        const today0 = midnight(0);
        const out = [{ key: 0, label: 'Today',     date: today0,             count: 0 }];
        for (let i = 1; i < 7; i++) {
            const s = today0 + i * 86400;
            out.push({ key: i, label: dayLabel(s), date: s, count: 0 });
        }
        for (const ev of allEvents) {
            for (const d of out) {
                if (ev.ts >= d.date && ev.ts < d.date + 86400) {
                    d.count += 1;
                    break;
                }
            }
        }
        // Live count
        const now = Math.floor(Date.now() / 1000);
        const liveCount = allEvents.filter((e) =>
            e.live === true || e.state === 'in' ||
            (e.ts <= now + 600 && e.ts >= now - 3 * 3600 && !e.finished),
        ).length;
        return { days: out, liveCount };
    }, [allEvents]);

    /* Hero pick: prefer live-with-score → live-without-score → soonest in
       a marquee league → soonest overall. */
    const hero = useMemo(() => {
        const now = Math.floor(Date.now() / 1000);
        const isLive = (e) => (e.live === true || e.state === 'in') && !e.finished;
        const hasScore = (e) =>
            (e.homeScore !== '' && e.homeScore !== undefined && e.homeScore !== null) ||
            (e.awayScore !== '' && e.awayScore !== undefined && e.awayScore !== null);
        const liveScored = allEvents.find((e) => isLive(e) && hasScore(e));
        if (liveScored) return liveScored;
        const liveAny = allEvents.find(isLive);
        if (liveAny) return liveAny;
        const future = allEvents.filter((e) => !e.finished && e.ts > now);
        const marquee = future.find((e) => MARQUEE_LEAGUES.has(e.leagueId));
        if (marquee) return marquee;
        return future[0] || allEvents[0] || null;
    }, [allEvents]);

    const reminders = useMemo(() => {
        if (!provider) return new Set();
        return new Set(getReminders(provider.id).map((r) => `${r.streamId}:${r.startTs}`));
    }, [provider]);

    /* Refresh every 60 s so kickoff countdowns stay live. */
    useEffect(() => {
        const t = setInterval(() => setBump((b) => b + 1), 60000);
        return () => clearInterval(t);
    }, []);

    /* ─── Card actions ─── */
    const onCardEnter = useCallback(async (fx) => {
        if (!provider) return navigate('/live-tv');
        const matches = matchFixture(provider.id, fx, { limit: 1 });
        if (matches.length === 0) return;
        const top = matches[0];
        const chans = loadChannels(provider.id) || {};
        let ch = null;
        for (const k in chans) {
            ch = (chans[k] || []).find((x) => String(x.stream_id) === String(top.streamId));
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

    const onCardRemind = useCallback((fx) => {
        if (!provider) return;
        const matches = matchFixture(provider.id, fx, { limit: 1 });
        if (matches.length === 0) return;
        const top = matches[0];
        toggleReminder(provider.id, top.streamId, {
            channelName: top.channelName,
            title: fx.title,
            startTs: top.startTs || fx.ts,
            stopTs: top.stopTs || (fx.ts + 7200),
        });
        setBump((b) => b + 1);
    }, [provider]);

    /* ─── Render ─── */
    return (
        <div data-testid="sports-page" style={{
            position: 'fixed', inset: 0,
            background:
                'radial-gradient(ellipse 1200px 700px at 18% -10%, rgba(93,200,255,0.10) 0%, transparent 55%),' +
                'radial-gradient(ellipse 900px 600px at 95% 110%, rgba(255,136,85,0.06) 0%, transparent 60%),' +
                '#06080F',
            color: '#E6EAF2', overflow: 'hidden',
        }}>
            <SideNav />
            <main style={{ position: 'absolute', inset: '0 0 0 100px',
                            display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
                {/* Title strip */}
                <header style={{ padding: '18px 32px 6px 32px',
                                  display: 'flex', alignItems: 'baseline', gap: 14 }}>
                    <BackBtn onClick={() => navigate('/')} />
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 9,
                                    fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                                    letterSpacing: '0.36em', color: ACCENT_DEFAULT }}>
                        <Trophy size={13} color={ACCENT_DEFAULT} />
                        SPORTS GUIDE
                    </div>
                    <span style={{ fontSize: 13, color: '#7d8493' }}>
                        Fixtures, scores &amp; where to watch, across the world.
                    </span>
                    {data?.fetched_at && (
                        <span style={{ marginLeft: 'auto',
                                        fontFamily: 'monospace', fontSize: 10,
                                        letterSpacing: '0.22em', color: '#5e6473' }}>
                            UPDATED · {fmtClock(data.fetched_at)}
                        </span>
                    )}
                </header>

                <div style={{ flex: 1, overflowY: 'auto', overflowX: 'hidden',
                                padding: '12px 32px 64px 32px' }}>
                    {loading && <LoadingBlock />}
                    {!loading && err && <ErrorBlock err={err} />}
                    {!loading && !err && (
                        <>
                            {hero && (
                                <HeroFixture
                                    fixture={hero}
                                    provider={provider}
                                    onPlay={onCardEnter}
                                    onRemind={onCardRemind}
                                    reminders={reminders}
                                    cacheVer={cacheVer}
                                />
                            )}

                            <FilterStrip
                                sportsMeta={sportsMeta}
                                sportFilter={sportFilter}
                                onSport={setSportFilter}
                                allEvents={allEvents}
                            />

                            <DateStrip
                                days={availableDays.days}
                                liveCount={availableDays.liveCount}
                                dayFilter={dayFilter}
                                onDay={setDayFilter}
                            />

                            {groups.length === 0 ? (
                                <EmptyBlock />
                            ) : (
                                groups.map((g) => (
                                    <LeagueBlock
                                        key={g.id}
                                        group={g}
                                        provider={provider}
                                        onPlay={onCardEnter}
                                        onRemind={onCardRemind}
                                        reminders={reminders}
                                        cacheVer={cacheVer}
                                    />
                                ))
                            )}
                        </>
                    )}
                </div>
            </main>
            <style>{`
                @keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }
                @keyframes pulse-live {
                    0%   { opacity: 1;    box-shadow: 0 0 0 0 rgba(255,77,94,0.55); }
                    50%  { opacity: 0.85; box-shadow: 0 0 0 6px rgba(255,77,94,0.0);  }
                    100% { opacity: 1;    box-shadow: 0 0 0 0 rgba(255,77,94,0.0);  }
                }
            `}</style>
            <DPadHint
                storageKey="sports"
                items={[
                    { keys: '←', label: 'BACK' },
                    { keys: '↑↓←→', label: 'NAVIGATE' },
                    { keys: 'OK', label: 'WATCH' },
                    { keys: 'HOLD OK', label: 'REMIND' },
                ]}
            />
        </div>
    );
}

/* ─────────────────────────────────── Hero ─────────────────────────────────── */

const HeroFixture = React.memo(function HeroFixture({ fixture, provider, onPlay, onRemind, reminders, cacheVer }) {
    const accent = SPORT_ACCENT[fixture.sport] || ACCENT_DEFAULT;
    const matches = useMemo(
        () => (provider ? matchFixture(provider.id, fixture, { limit: 4 }) : []),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [provider, fixture, cacheVer],
    );
    const live = isLiveNow(fixture) || fixture.live || fixture.state === 'in';
    const reminded = matches.length > 0 && reminders.has(`${matches[0].streamId}:${matches[0].startTs}`);
    const fxArt = fixture.thumb || fixture.poster;
    const hasScore = (fixture.homeScore !== '' && fixture.homeScore !== undefined && fixture.homeScore !== null)
                  || (fixture.awayScore !== '' && fixture.awayScore !== undefined && fixture.awayScore !== null);
    const showLiveScore  = live && hasScore;
    const showFinalScore = (fixture.finished || fixture.state === 'post') && hasScore;

    return (
        <article
            tabIndex={0}
            data-focusable="true"
            data-initial-focus="true"
            onClick={() => onPlay(fixture)}
            style={{
                position: 'relative',
                borderRadius: 20,
                overflow: 'hidden',
                marginBottom: 22,
                background:
                    'linear-gradient(135deg, rgba(13,18,32,0.0) 0%, rgba(13,18,32,0.0) 30%, rgba(13,18,32,0.65) 60%, rgba(13,18,32,0.92) 100%),' +
                    `linear-gradient(115deg, ${accent}26 0%, transparent 40%),` +
                    (fxArt ? `url(${proxy(fxArt, 1200, 65)})` : '#0E1424'),
                backgroundSize: 'cover, cover, cover',
                backgroundPosition: 'center',
                border: `1px solid ${accent}55`,
                boxShadow: `0 22px 60px -28px ${accent}88, inset 0 1px 0 rgba(255,255,255,0.06)`,
                minHeight: 260,
                padding: 28,
                display: 'grid',
                gridTemplateColumns: '1fr auto',
                gap: 24,
                cursor: 'pointer',
                outline: 'none',
                transition: 'transform 0.18s ease, box-shadow 0.18s ease',
            }}
            onFocus={(e) => {
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.boxShadow = `0 30px 80px -28px ${accent}, inset 0 0 0 2px ${accent}`;
            }}
            onBlur={(e) => {
                e.currentTarget.style.transform = 'none';
                e.currentTarget.style.boxShadow = `0 22px 60px -28px ${accent}88, inset 0 1px 0 rgba(255,255,255,0.06)`;
            }}
        >
            {/* LEFT — fixture info */}
            <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'space-between', gap: 18, minWidth: 0 }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                    {/* League pill */}
                    <div style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}>
                        {fixture.leagueBadge && (
                            <img
                                src={proxy(fixture.leagueBadge, 56, 65)} alt="" width={28} height={28}
                                onError={(e) => { e.currentTarget.style.display = 'none'; }}
                                style={{ width: 28, height: 28, objectFit: 'contain' }}
                            />
                        )}
                        <div style={{
                            display: 'inline-flex', alignItems: 'center', gap: 8,
                            padding: '5px 11px', borderRadius: 999,
                            background: 'rgba(255,255,255,0.07)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                            letterSpacing: '0.28em', color: '#E6EAF2',
                        }}>
                            <span style={{ fontSize: 14, lineHeight: 1 }}>
                                {SPORT_EMOJI[fixture.sport] || '🏆'}
                            </span>
                            {(fixture.league || fixture.sport || '').toUpperCase()}
                        </div>
                        {live && <LivePill />}
                    </div>

                    {/* Teams (massive) */}
                    <h1 style={{
                        margin: 0,
                        fontSize: 'clamp(28px, 3.4vw, 50px)',
                        fontWeight: 800,
                        lineHeight: 1.06,
                        letterSpacing: '-0.012em',
                        color: '#FFFFFF',
                        textShadow: '0 2px 30px rgba(0,0,0,0.6)',
                    }}>
                        {fixture.home && fixture.away ? (
                            <>
                                <span>{fixture.home}</span>{' '}
                                <span style={{ color: accent, fontWeight: 700, opacity: 0.85 }}>vs</span>{' '}
                                <span>{fixture.away}</span>
                            </>
                        ) : (
                            fixture.title || 'Featured event'
                        )}
                    </h1>

                    {/* Sub-line */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 18, flexWrap: 'wrap',
                                    color: '#C0C8D8', fontSize: 14, fontWeight: 600 }}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                            <Clock size={14} color={accent} />
                            {fmtFull(fixture.ts)}
                        </span>
                        {fixture.venue && (
                            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                                <MapPin size={13} color="#7d8493" />
                                {fixture.venue}{fixture.country ? `, ${fixture.country}` : ''}
                            </span>
                        )}
                        {!live && <CountdownBadge ts={fixture.ts} accent={accent} />}
                    </div>
                </div>

                {/* Bottom row: WATCH on + actions */}
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                    {matches.length > 0 ? (
                        <>
                            <button
                                onClick={(e) => { e.stopPropagation(); onPlay(fixture); }}
                                style={{
                                    display: 'inline-flex', alignItems: 'center', gap: 8,
                                    padding: '11px 18px',
                                    background: '#FFFFFF',
                                    color: '#0A0F1A',
                                    border: 'none', borderRadius: 999,
                                    fontSize: 13, fontWeight: 800,
                                    letterSpacing: '0.06em',
                                    cursor: 'pointer',
                                    boxShadow: '0 8px 24px rgba(255,255,255,0.18)',
                                }}
                            >
                                <Play size={13} fill="#0A0F1A" />
                                Watch on {matches[0].channelName.toUpperCase()}
                            </button>
                            {matches.length > 1 && (
                                <span style={{ fontFamily: 'monospace', fontSize: 11,
                                                letterSpacing: '0.18em', color: '#7d8493' }}>
                                    + {matches.length - 1} MORE CHANNEL{matches.length > 2 ? 'S' : ''}
                                </span>
                            )}
                            <button
                                onClick={(e) => { e.stopPropagation(); onRemind(fixture); }}
                                style={{
                                    display: 'inline-flex', alignItems: 'center', gap: 6,
                                    padding: '10px 14px',
                                    background: reminded ? 'rgba(255,200,80,0.18)' : 'rgba(255,255,255,0.05)',
                                    color: reminded ? '#FFC850' : '#E6EAF2',
                                    border: '1px solid ' + (reminded ? 'rgba(255,200,80,0.55)' : 'rgba(255,255,255,0.10)'),
                                    borderRadius: 999,
                                    fontSize: 12, fontWeight: 700,
                                    letterSpacing: '0.06em',
                                    cursor: 'pointer',
                                }}
                            >
                                <Bell size={12} fill={reminded ? '#FFC850' : 'none'}
                                       color={reminded ? '#FFC850' : '#E6EAF2'} />
                                {reminded ? 'REMINDER SET' : 'REMIND ME'}
                            </button>
                        </>
                    ) : (
                        <div style={{
                            display: 'inline-flex', alignItems: 'center', gap: 8,
                            padding: '10px 14px',
                            background: 'rgba(255,255,255,0.03)',
                            border: '1px dashed rgba(255,255,255,0.12)',
                            borderRadius: 999,
                            fontSize: 12, fontWeight: 600,
                            color: '#7d8493', letterSpacing: '0.04em',
                        }}>
                            <Tv size={12} color="#7d8493" />
                            Not on any of your channels yet.
                        </div>
                    )}
                </div>
            </div>

            {/* RIGHT — score panel (live/final) OR team badges face-off (pre) */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 14, minWidth: 0 }}>
                <TeamBadge name={fixture.home} badge={fixture.homeBadge} accent={accent} large />
                {showLiveScore || showFinalScore ? (
                    <div style={{
                        display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
                        padding: '8px 18px',
                        minWidth: 140,
                    }}>
                        <div style={{
                            fontFamily: 'monospace',
                            fontSize: 'clamp(38px, 4.2vw, 64px)',
                            fontWeight: 900,
                            color: '#FFFFFF',
                            letterSpacing: '-0.04em',
                            lineHeight: 1,
                            textShadow: `0 4px 32px ${accent}66`,
                        }}>
                            {fixture.homeScore || '0'}
                            <span style={{
                                color: accent,
                                opacity: 0.55,
                                margin: '0 12px',
                                fontWeight: 600,
                            }}>–</span>
                            {fixture.awayScore || '0'}
                        </div>
                        <div style={{
                            fontFamily: 'monospace', fontSize: 10, fontWeight: 800,
                            letterSpacing: '0.18em',
                            color: showLiveScore ? '#FF4D5E' : '#9DA5B5',
                            textAlign: 'center',
                        }}>
                            {(fixture.statusShort || fixture.status || (showFinalScore ? 'FINAL' : 'LIVE')).toUpperCase()}
                        </div>
                    </div>
                ) : (
                    <div style={{
                        fontFamily: 'monospace', fontSize: 18, fontWeight: 900,
                        color: accent, letterSpacing: '0.04em',
                        textShadow: `0 0 18px ${accent}55`,
                    }}>VS</div>
                )}
                <TeamBadge name={fixture.away} badge={fixture.awayBadge} accent={accent} large />
            </div>
        </article>
    );
});

const TeamBadge = React.memo(function TeamBadge({ name, badge, accent, large }) {
    const size = large ? 96 : 56;
    return (
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, minWidth: 0 }}>
            <div style={{
                width: size, height: size,
                borderRadius: '50%',
                background: 'radial-gradient(circle at 30% 25%, rgba(255,255,255,0.10), rgba(255,255,255,0.02))',
                border: `1px solid ${accent}55`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                overflow: 'hidden',
                boxShadow: `0 8px 24px rgba(0,0,0,0.45), inset 0 0 0 1px rgba(255,255,255,0.03)`,
                flexShrink: 0,
            }}>
                {badge ? (
                    <img
                        src={proxy(badge, large ? 200 : 120, 80)}
                        alt=""
                        onError={(e) => { e.currentTarget.style.display = 'none'; }}
                        style={{ width: '78%', height: '78%', objectFit: 'contain', filter: 'drop-shadow(0 4px 8px rgba(0,0,0,0.4))' }}
                    />
                ) : (
                    <span style={{ fontFamily: 'monospace', fontSize: large ? 28 : 18, fontWeight: 800,
                                    color: accent, letterSpacing: '-0.02em' }}>
                        {(name || '?').split(/\s+/).map((w) => w[0] || '').join('').slice(0, 3).toUpperCase()}
                    </span>
                )}
            </div>
            {large && (
                <div style={{ fontSize: 11, fontWeight: 700, color: '#9DA5B5',
                                letterSpacing: '0.04em',
                                maxWidth: 110, textAlign: 'center',
                                lineHeight: 1.15,
                                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {(name || '').toUpperCase()}
                </div>
            )}
        </div>
    );
});

/* ─────────────────────────────────── Filters ─────────────────────────────────── */

function FilterStrip({ sportsMeta, sportFilter, onSport, allEvents }) {
    return (
        <section style={{ marginBottom: 14 }}>
            <div style={{ fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                            letterSpacing: '0.34em', color: '#7d8493',
                            marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Award size={11} color="#7d8493" />
                BROWSE BY SPORT
            </div>
            <div style={{ display: 'flex', gap: 8, overflowX: 'auto', overflowY: 'hidden',
                            paddingBottom: 3 }}>
                <SportPill
                    label="All Sports"
                    emoji="🏆"
                    accent={ACCENT_DEFAULT}
                    active={sportFilter === 'all'}
                    count={allEvents.length}
                    onClick={() => onSport('all')}
                />
                {sportsMeta.map((s) => (
                    <SportPill
                        key={s.name}
                        label={s.name}
                        emoji={SPORT_EMOJI[s.name] || s.emoji || '🏆'}
                        accent={SPORT_ACCENT[s.name] || s.color || ACCENT_DEFAULT}
                        active={sportFilter === s.name}
                        count={s.count}
                        onClick={() => onSport(s.name)}
                    />
                ))}
            </div>
        </section>
    );
}

const SportPill = React.memo(function SportPill({ label, emoji, accent, active, count, onClick }) {
    return (
        <button
            data-focusable="true"
            tabIndex={0}
            onClick={onClick}
            style={{
                display: 'inline-flex', alignItems: 'center', gap: 8,
                height: 44, padding: '0 16px',
                whiteSpace: 'nowrap',
                background: active ? `${accent}22` : 'rgba(255,255,255,0.025)',
                border: `1px solid ${active ? accent : 'rgba(255,255,255,0.08)'}`,
                borderRadius: 999,
                color: active ? '#FFF' : '#9DA5B5',
                fontSize: 13, fontWeight: 700,
                cursor: 'pointer',
                outline: 'none',
                transition: 'background 0.15s, color 0.15s, border 0.15s',
            }}
            onFocus={(e) => {
                e.currentTarget.style.background = `${accent}33`;
                e.currentTarget.style.borderColor = accent;
                e.currentTarget.style.color = '#FFF';
            }}
            onBlur={(e) => {
                e.currentTarget.style.background = active ? `${accent}22` : 'rgba(255,255,255,0.025)';
                e.currentTarget.style.borderColor = active ? accent : 'rgba(255,255,255,0.08)';
                e.currentTarget.style.color = active ? '#FFF' : '#9DA5B5';
            }}
        >
            <span style={{ fontSize: 16, lineHeight: 1 }}>{emoji}</span>
            <span>{label}</span>
            {count > 0 && (
                <span style={{ fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                color: active ? accent : '#5e6473', letterSpacing: '0.08em' }}>
                    {count}
                </span>
            )}
        </button>
    );
});

function DateStrip({ days, liveCount, dayFilter, onDay }) {
    return (
        <section style={{ marginBottom: 22 }}>
            <div style={{ fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                            letterSpacing: '0.34em', color: '#7d8493',
                            marginBottom: 8, display: 'flex', alignItems: 'center', gap: 8 }}>
                <Calendar size={11} color="#7d8493" />
                WHEN
            </div>
            <div style={{ display: 'flex', gap: 8, overflowX: 'auto', overflowY: 'hidden',
                            paddingBottom: 3 }}>
                {liveCount > 0 && (
                    <DatePill
                        label="LIVE NOW"
                        sub={`${liveCount} match${liveCount !== 1 ? 'es' : ''}`}
                        accent="#FF4D5E"
                        active={dayFilter === -1}
                        live
                        onClick={() => onDay(-1)}
                    />
                )}
                <DatePill
                    label="All Upcoming"
                    sub="next 14 days"
                    accent="#FFC850"
                    active={dayFilter === -2}
                    onClick={() => onDay(-2)}
                />
                {days.map((d) => (
                    <DatePill
                        key={d.key}
                        label={d.label}
                        sub={d.count > 0 ? `${d.count} fixtures` : 'no fixtures'}
                        accent={ACCENT_DEFAULT}
                        active={dayFilter === d.key}
                        disabled={d.count === 0}
                        onClick={() => onDay(d.key)}
                    />
                ))}
            </div>
        </section>
    );
}

const DatePill = React.memo(function DatePill({ label, sub, accent, active, live, disabled, onClick }) {
    return (
        <button
            data-focusable="true"
            tabIndex={0}
            onClick={disabled ? undefined : onClick}
            disabled={disabled}
            style={{
                display: 'inline-flex', flexDirection: 'column', alignItems: 'flex-start', gap: 1,
                height: 54, padding: '0 18px',
                whiteSpace: 'nowrap', justifyContent: 'center',
                background: active ? `${accent}22` : 'rgba(255,255,255,0.022)',
                border: `1px solid ${active ? accent : 'rgba(255,255,255,0.08)'}`,
                borderRadius: 14,
                color: active ? '#FFF' : '#9DA5B5',
                cursor: disabled ? 'not-allowed' : 'pointer',
                opacity: disabled ? 0.45 : 1,
                outline: 'none', position: 'relative',
                transition: 'background 0.15s, color 0.15s, border 0.15s',
            }}
            onFocus={(e) => {
                if (disabled) return;
                e.currentTarget.style.background = `${accent}33`;
                e.currentTarget.style.borderColor = accent;
                e.currentTarget.style.color = '#FFF';
            }}
            onBlur={(e) => {
                if (disabled) return;
                e.currentTarget.style.background = active ? `${accent}22` : 'rgba(255,255,255,0.022)';
                e.currentTarget.style.borderColor = active ? accent : 'rgba(255,255,255,0.08)';
                e.currentTarget.style.color = active ? '#FFF' : '#9DA5B5';
            }}
        >
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                            fontSize: 13, fontWeight: 800, letterSpacing: '0.02em' }}>
                {live && (
                    <span style={{ width: 7, height: 7, borderRadius: '50%',
                                    background: '#FF4D5E', animation: 'pulse-live 1.6s ease-in-out infinite' }} />
                )}
                {label}
            </span>
            <span style={{ fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                            letterSpacing: '0.18em', color: '#7d8493' }}>
                {sub}
            </span>
        </button>
    );
});

/* ─────────────────────────────────── League block ─────────────────────────────────── */

const LeagueBlock = React.memo(function LeagueBlock({ group, provider, onPlay, onRemind, reminders, cacheVer }) {
    const accent = SPORT_ACCENT[group.sport] || ACCENT_DEFAULT;
    return (
        <section style={{ marginBottom: 22 }}>
            <header style={{
                display: 'flex', alignItems: 'center', gap: 14,
                padding: '12px 18px',
                background:
                    `linear-gradient(90deg, ${accent}16 0%, transparent 60%),` +
                    'rgba(255,255,255,0.02)',
                border: '1px solid rgba(255,255,255,0.05)',
                borderLeft: `3px solid ${accent}`,
                borderRadius: '12px 12px 4px 4px',
                marginBottom: 10,
            }}>
                <div style={{
                    width: 46, height: 46, borderRadius: 10,
                    background: 'rgba(255,255,255,0.03)',
                    border: '1px solid rgba(255,255,255,0.06)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    overflow: 'hidden', flexShrink: 0,
                }}>
                    {group.badge ? (
                        <img src={proxy(group.badge, 96, 70)} alt=""
                                onError={(e) => { e.currentTarget.style.display = 'none'; }}
                                style={{ width: '78%', height: '78%', objectFit: 'contain' }} />
                    ) : (
                        <span style={{ fontSize: 22 }}>{SPORT_EMOJI[group.sport] || '🏆'}</span>
                    )}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                    <h2 style={{ margin: 0, fontSize: 17, fontWeight: 800, color: '#FFFFFF',
                                    letterSpacing: '-0.01em' }}>
                        {group.name}
                    </h2>
                    <div style={{ fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                    letterSpacing: '0.24em', color: accent, marginTop: 2 }}>
                        {(group.sport || '').toUpperCase()} · {group.events.length} FIXTURE{group.events.length !== 1 ? 'S' : ''}
                    </div>
                </div>
            </header>
            <div style={{
                display: 'grid',
                gridTemplateColumns: 'repeat(auto-fill, minmax(440px, 1fr))',
                gap: 12,
            }}>
                {group.events.map((ev) => (
                    <FixtureCard
                        key={ev.id}
                        fixture={ev}
                        provider={provider}
                        onPlay={onPlay}
                        onRemind={onRemind}
                        reminders={reminders}
                        cacheVer={cacheVer}
                    />
                ))}
            </div>
        </section>
    );
});

/* ─────────────────────────────────── Fixture Card ─────────────────────────────────── */

const FixtureCard = React.memo(function FixtureCard({ fixture, provider, onPlay, onRemind, reminders, cacheVer }) {
    const accent = SPORT_ACCENT[fixture.sport] || ACCENT_DEFAULT;
    const matches = useMemo(
        () => (provider ? matchFixture(provider.id, fixture, { limit: 4 }) : []),
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [provider, fixture, cacheVer],
    );
    const live = isLiveNow(fixture) || fixture.live || fixture.state === 'in';
    const reminded = matches.length > 0 && reminders.has(`${matches[0].streamId}:${matches[0].startTs}`);
    const hasScore = (fixture.homeScore !== '' && fixture.homeScore !== undefined && fixture.homeScore !== null)
                  || (fixture.awayScore !== '' && fixture.awayScore !== undefined && fixture.awayScore !== null);
    const showLiveScore  = live && hasScore;
    const showFinalScore = (fixture.finished || fixture.state === 'post') && hasScore;
    const showScore      = showLiveScore || showFinalScore;
    const liveWatchable  = live && matches.length > 0;

    /* Press tracking for hold-OK = reminder.  We set
       `data-long-pressed="true"` on the card element when the long
       press fires so useSpatialFocus' onKeyUp skips its automatic
       click() — otherwise a long press would BOTH set a reminder
       AND play the channel. */
    const pressRef = useRef({ count: 0, fired: false });
    const onKeyDown = (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const p = pressRef.current;
        p.count += 1;
        if (p.count >= 6 && !p.fired) {
            p.fired = true;
            e.currentTarget.setAttribute('data-long-pressed', 'true');
            onRemind(fixture);
        }
    };
    const onKeyUp = (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        const p = pressRef.current;
        p.count = 0; p.fired = false;
        // Don't call onPlay here — the global useSpatialFocus keyup
        // will fire onClick (which the card wires to onPlay) only
        // if data-long-pressed is not set.  Avoids duplicate plays.
    };

    return (
        <article
            data-focusable="true"
            tabIndex={0}
            onKeyDown={onKeyDown}
            onKeyUp={onKeyUp}
            onClick={() => { if (matches.length > 0) onPlay(fixture); }}
            style={{
                position: 'relative',
                background:
                    'linear-gradient(135deg, rgba(20,28,46,0.85) 0%, rgba(14,20,36,0.95) 100%)',
                border: '1px solid rgba(255,255,255,0.06)',
                borderRadius: 14,
                padding: '14px 16px 12px 16px',
                display: 'flex', flexDirection: 'column', gap: 10,
                cursor: matches.length > 0 ? 'pointer' : 'default',
                outline: 'none',
                transition: 'transform 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease',
                overflow: 'hidden',
            }}
            onFocus={(e) => {
                e.currentTarget.style.transform = 'translateY(-2px)';
                e.currentTarget.style.borderColor = accent;
                e.currentTarget.style.boxShadow = `0 14px 32px -16px ${accent}88, inset 0 0 0 1px ${accent}55`;
            }}
            onBlur={(e) => {
                e.currentTarget.style.transform = 'none';
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.06)';
                e.currentTarget.style.boxShadow = 'none';
            }}
        >
            {/* Accent bar */}
            <div style={{
                position: 'absolute', left: 0, top: 0, bottom: 0, width: 3,
                background: accent,
                boxShadow: live ? `0 0 14px ${accent}` : 'none',
            }} />
            {/* Header: time + status pill */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'space-between' }}>
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
                    <div style={{
                        fontFamily: 'monospace', fontSize: 16, fontWeight: 800,
                        color: '#FFFFFF', letterSpacing: '-0.01em',
                    }}>
                        {fmt(fixture.ts)}
                    </div>
                    <div style={{ fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                                    color: '#5e6473', letterSpacing: '0.22em' }}>
                        {dayLabel(fixture.ts)}
                    </div>
                </div>
                {live ? (
                    <LivePill />
                ) : fixture.finished ? (
                    <FinalPill />
                ) : (
                    <CountdownBadge ts={fixture.ts} accent={accent} compact />
                )}
            </div>

            {/* Teams row */}
            {fixture.home && fixture.away ? (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr auto 1fr',
                                alignItems: 'center', gap: 10 }}>
                    <TeamRow side="home" name={fixture.home} badge={fixture.homeBadge}
                              score={showScore ? fixture.homeScore : ''} accent={accent} live={showLiveScore} />
                    <div style={{
                        fontFamily: 'monospace', fontSize: showScore ? 22 : 11, fontWeight: 900,
                        color: showLiveScore ? '#FF4D5E' : accent,
                        letterSpacing: showScore ? '-0.04em' : '0.1em', textAlign: 'center',
                        lineHeight: 1,
                        textShadow: showLiveScore ? `0 0 16px ${accent}66` : 'none',
                    }}>
                        {showScore ? '–' : 'VS'}
                    </div>
                    <TeamRow side="away" name={fixture.away} badge={fixture.awayBadge}
                              score={showScore ? fixture.awayScore : ''} accent={accent} live={showLiveScore} />
                </div>
            ) : (
                <div style={{ fontSize: 16, fontWeight: 800, color: '#FFFFFF',
                                lineHeight: 1.2 }}>
                    {fixture.title || 'Event'}
                </div>
            )}

            {/* Live progress strip — shows period/quarter/minute */}
            {showLiveScore && fixture.statusShort && (
                <div style={{
                    display: 'inline-flex', alignSelf: 'flex-start',
                    alignItems: 'center', gap: 6,
                    padding: '3px 10px', borderRadius: 999,
                    background: 'rgba(255,77,94,0.12)',
                    border: '1px solid rgba(255,77,94,0.4)',
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 800,
                    letterSpacing: '0.12em', color: '#FF4D5E',
                }}>
                    <span style={{ width: 6, height: 6, borderRadius: '50%',
                                    background: '#FF4D5E',
                                    animation: 'pulse-live 1.6s ease-in-out infinite' }} />
                    {fixture.statusShort.toUpperCase()}
                </div>
            )}

            {/* Venue line */}
            {fixture.venue && (
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                fontSize: 11, fontWeight: 600, color: '#7d8493',
                                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    <MapPin size={11} color="#5e6473" />
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {fixture.venue}{fixture.city ? `, ${fixture.city}` : (fixture.country ? `, ${fixture.country}` : '')}
                    </span>
                </div>
            )}

            {/* WATCH ON row */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap',
                            paddingTop: 6, borderTop: '1px solid rgba(255,255,255,0.04)' }}>
                {liveWatchable ? (
                    /* Live game with a matching channel → prominent CTA */
                    <>
                        <div style={{
                            display: 'inline-flex', alignItems: 'center', gap: 8,
                            padding: '6px 12px', borderRadius: 999,
                            background: 'rgba(255,77,94,0.16)',
                            border: '1px solid rgba(255,77,94,0.55)',
                            fontSize: 11, fontWeight: 800,
                            letterSpacing: '0.04em', color: '#FFFFFF',
                        }}>
                            <Play size={11} fill="#FFFFFF" />
                            WATCH LIVE · {matches[0].channelName.toUpperCase()}
                        </div>
                        {matches.length > 1 && (
                            <span style={{ fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                                            letterSpacing: '0.18em', color: '#7d8493' }}>
                                +{matches.length - 1} MORE
                            </span>
                        )}
                    </>
                ) : matches.length > 0 ? (
                    <>
                        <span style={{ fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                                        letterSpacing: '0.22em', color: accent }}>
                            WATCH ON
                        </span>
                        {matches.slice(0, 3).map((m) => (
                            <ChannelChip key={m.streamId} channel={m} accent={accent} />
                        ))}
                        {matches.length > 3 && (
                            <span style={{ fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                                            letterSpacing: '0.18em', color: '#5e6473' }}>
                                +{matches.length - 3}
                            </span>
                        )}
                        {reminded && (
                            <span style={{ marginLeft: 'auto', display: 'inline-flex',
                                            alignItems: 'center', gap: 4,
                                            fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                                            letterSpacing: '0.18em', color: '#FFC850' }}>
                                <Bell size={10} fill="#FFC850" color="#FFC850" />
                                REMINDED
                            </span>
                        )}
                    </>
                ) : (
                    <span style={{ fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                                    letterSpacing: '0.22em', color: '#5e6473' }}>
                        NOT ON YOUR CHANNELS
                    </span>
                )}
            </div>
        </article>
    );
});

const TeamRow = React.memo(function TeamRow({ side, name, badge, score, accent, live }) {
    const reverse = side === 'away';
    return (
        <div style={{
            display: 'flex',
            flexDirection: reverse ? 'row-reverse' : 'row',
            alignItems: 'center', gap: 10,
            minWidth: 0,
        }}>
            <div style={{
                width: 38, height: 38, borderRadius: 9,
                background: 'rgba(255,255,255,0.03)',
                border: '1px solid rgba(255,255,255,0.06)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                overflow: 'hidden', flexShrink: 0,
            }}>
                {badge ? (
                    <img
                        src={proxy(badge, 80, 75)} alt=""
                        onError={(e) => { e.currentTarget.style.display = 'none'; }}
                        style={{ width: '80%', height: '80%', objectFit: 'contain' }}
                    />
                ) : (
                    <span style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 800,
                                    color: accent }}>
                        {(name || '?').split(/\s+/).map((w) => w[0] || '').join('').slice(0, 3).toUpperCase()}
                    </span>
                )}
            </div>
            <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0,
                            alignItems: reverse ? 'flex-end' : 'flex-start' }}>
                <div style={{
                    fontSize: 13, fontWeight: 700, color: '#FFFFFF',
                    lineHeight: 1.15,
                    maxWidth: 170,
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    textAlign: reverse ? 'right' : 'left',
                }}>
                    {name}
                </div>
                {score !== '' && (
                    <div style={{
                        fontFamily: 'monospace', fontSize: 24, fontWeight: 900,
                        color: '#FFFFFF', letterSpacing: '-0.04em', lineHeight: 1,
                        textShadow: live ? `0 0 14px ${accent}66` : 'none',
                    }}>
                        {score}
                    </div>
                )}
            </div>
        </div>
    );
});

const ChannelChip = React.memo(function ChannelChip({ channel, accent }) {
    return (
        <div style={{
            display: 'inline-flex', alignItems: 'center', gap: 6,
            padding: '4px 8px 4px 5px',
            background: `${accent}14`,
            border: `1px solid ${accent}44`,
            borderRadius: 999,
            maxWidth: 180,
        }}>
            <div style={{
                width: 18, height: 14, borderRadius: 3,
                background: 'rgba(255,255,255,0.05)',
                overflow: 'hidden', position: 'relative', flexShrink: 0,
            }}>
                {channel.channelIcon && (
                    <img src={proxy(channel.channelIcon, 40, 60)} alt="" width={18} height={14}
                          onError={(e) => { e.currentTarget.style.display = 'none'; }}
                          style={{ position: 'absolute', inset: 0, width: '100%', height: '100%',
                                    objectFit: 'contain' }} />
                )}
            </div>
            <span style={{
                fontSize: 10, fontWeight: 800, color: '#FFFFFF',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                letterSpacing: '0.02em',
            }}>
                {channel.channelName}
            </span>
        </div>
    );
});

/* ─────────────────────────────────── Atoms ─────────────────────────────────── */

function LivePill() {
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 5,
            padding: '3px 9px', borderRadius: 999,
            background: 'rgba(255,77,94,0.18)',
            border: '1px solid rgba(255,77,94,0.55)',
            fontFamily: 'monospace', fontSize: 9, fontWeight: 800,
            letterSpacing: '0.22em', color: '#FF4D5E',
        }}>
            <span style={{ width: 6, height: 6, borderRadius: '50%',
                            background: '#FF4D5E',
                            animation: 'pulse-live 1.6s ease-in-out infinite' }} />
            LIVE
        </span>
    );
}

function FinalPill() {
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center',
            padding: '3px 9px', borderRadius: 999,
            background: 'rgba(255,255,255,0.06)',
            border: '1px solid rgba(255,255,255,0.10)',
            fontFamily: 'monospace', fontSize: 9, fontWeight: 800,
            letterSpacing: '0.22em', color: '#9DA5B5',
        }}>
            FT
        </span>
    );
}

function CountdownBadge({ ts, accent, compact }) {
    const now = Math.floor(Date.now() / 1000);
    const diff = ts - now;
    if (diff <= 0) return null;
    const h = Math.floor(diff / 3600);
    const m = Math.floor((diff % 3600) / 60);
    let txt = '';
    if (h >= 24) {
        const d = Math.floor(h / 24);
        txt = `in ${d}d`;
    } else if (h >= 1) {
        txt = `in ${h}h ${m.toString().padStart(2, '0')}m`;
    } else {
        txt = `in ${m}m`;
    }
    return (
        <span style={{
            display: 'inline-flex', alignItems: 'center', gap: 5,
            padding: compact ? '3px 8px' : '4px 10px', borderRadius: 999,
            background: `${accent}14`,
            border: `1px solid ${accent}44`,
            fontFamily: 'monospace',
            fontSize: compact ? 9 : 10, fontWeight: 700,
            letterSpacing: '0.16em', color: accent,
        }}>
            <Clock size={compact ? 9 : 10} color={accent} />
            {txt.toUpperCase()}
        </span>
    );
}

function BackBtn({ onClick }) {
    return (
        <button
            onClick={onClick}
            data-focusable="true" tabIndex={0}
            style={{
                display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                width: 38, height: 38, borderRadius: 999,
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                color: '#9DA5B5', cursor: 'pointer', outline: 'none',
                transition: 'background 0.15s, color 0.15s, border 0.15s',
            }}
            onFocus={(e) => {
                e.currentTarget.style.background = 'rgba(93,200,255,0.14)';
                e.currentTarget.style.borderColor = '#5DC8FF';
                e.currentTarget.style.color = '#FFF';
            }}
            onBlur={(e) => {
                e.currentTarget.style.background = 'rgba(255,255,255,0.04)';
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.08)';
                e.currentTarget.style.color = '#9DA5B5';
            }}
        >
            <ChevronLeft size={18} />
        </button>
    );
}

function LoadingBlock() {
    return (
        <div style={{ padding: 80, display: 'flex', flexDirection: 'column',
                        alignItems: 'center', gap: 14, color: '#9DA5B5' }}>
            <Loader2 size={28} color="#5DC8FF" style={{ animation: 'spin 1.2s linear infinite' }} />
            <div style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                            letterSpacing: '0.34em' }}>
                LOADING FIXTURES…
            </div>
            <div style={{ fontSize: 12 }}>
                Pulling schedules across {30}+ leagues worldwide.
            </div>
        </div>
    );
}

function ErrorBlock({ err }) {
    return (
        <div style={{ padding: 40, display: 'flex', flexDirection: 'column',
                        alignItems: 'center', gap: 14, color: '#FF6B7A' }}>
            <AlertTriangle size={28} color="#FF6B7A" />
            <div style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                            letterSpacing: '0.34em' }}>
                COULD NOT LOAD FIXTURES
            </div>
            <div style={{ fontSize: 12, color: '#9DA5B5' }}>
                {err}
            </div>
        </div>
    );
}

function EmptyBlock() {
    return (
        <div style={{ padding: 50, display: 'flex', flexDirection: 'column',
                        alignItems: 'center', gap: 12, color: '#7d8493',
                        background: 'rgba(255,255,255,0.018)',
                        border: '1px dashed rgba(255,255,255,0.07)',
                        borderRadius: 14 }}>
            <Radio size={22} color="#5e6473" />
            <div style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                            letterSpacing: '0.30em' }}>
                NOTHING IN THIS WINDOW
            </div>
            <div style={{ fontSize: 12, textAlign: 'center', maxWidth: 360, lineHeight: 1.5 }}>
                Try “All Sports”, switch to another day, or come back later. Fixtures refresh every 30 minutes.
            </div>
        </div>
    );
}

/* ─────────────────────────────────── Time helpers ─────────────────────────────────── */

function midnight(offsetDays = 0) {
    const d = new Date();
    d.setHours(0, 0, 0, 0);
    return Math.floor(d.getTime() / 1000) + offsetDays * 86400;
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

function fmtClock(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    return `${String(h).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')} ${ap}`;
}

function fmtFull(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const day = days[d.getDay()];
    const date = d.getDate();
    const month = months[d.getMonth()];
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    return `${day} ${date} ${month} · ${String(h).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')} ${ap}`;
}

function dayLabel(ts) {
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    const today = new Date(); today.setHours(0, 0, 0, 0);
    const target = new Date(d); target.setHours(0, 0, 0, 0);
    const diff = Math.round((target - today) / 86400000);
    if (diff === 0) return 'Today';
    if (diff === 1) return 'Tomorrow';
    const days = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
    return days[d.getDay()];
}

function isLiveNow(fx) {
    const now = Math.floor(Date.now() / 1000);
    return fx.ts <= now + 60 && fx.ts >= now - 3 * 3600 && !fx.finished;
}
