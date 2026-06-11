import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ArrowLeft, CalendarDays, Tv, ChevronLeft, ChevronRight, Loader2 } from 'lucide-react';
import { API } from '@/lib/api';

/**
 * Library Calendar — full-screen "Coming Up" view.
 *
 * Layout:
 *   • Header with month nav (←  February 2026  →)
 *   • Big 7-col day grid for the visible month.  Each day cell
 *     shows colour-coded chips for every episode airing that day,
 *     plus a thin "today" stripe and an empty-grid placeholder.
 *   • Selected day highlights episodes in the right-hand detail
 *     panel — episode still, show name, S-E, synopsis, network.
 *   • Below the grid: a horizontally-scrolling "This week" rail
 *     for quick D-pad access to imminent episodes.
 *
 * D-pad model:
 *   • Day cells are spatial-focusable (`data-focusable="true"`,
 *     `data-focus-style="tile"`).  Arrow keys navigate the grid;
 *     Enter focuses that day's first episode chip in the side
 *     panel.  Esc/Back closes the overlay.
 *   • The "This week" rail tiles are independently focusable so
 *     users can jump from the grid down to a tile in one press.
 */
export default function LibraryCalendar({ tvFavourites = [], onClose }) {
    const [loading, setLoading] = useState(true);
    const [shows, setShows] = useState([]);     // [{imdb_id, name, ...episodes}]
    const [error, setError] = useState('');
    const [monthCursor, setMonthCursor] = useState(() => {
        const d = new Date();
        return { year: d.getFullYear(), month: d.getMonth() };
    });
    const [selectedDate, setSelectedDate] = useState(() => isoDate(new Date()));
    const initialFocusRef = useRef(null);

    // Close on Esc / Back.  Capture-phase so it wins against the
    // Library page's global Back handler.
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

    // Fetch upcoming episodes when the overlay mounts.
    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                setLoading(true);
                setError('');
                const imdbIds = tvFavourites.map((f) => f.id).filter(Boolean);
                if (imdbIds.length === 0) {
                    if (!cancel) {
                        setShows([]);
                        setLoading(false);
                    }
                    return;
                }
                const r = await fetch(`${API}/tmdb/upcoming-episodes`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ imdb_ids: imdbIds }),
                });
                if (!r.ok) throw new Error('fetch failed');
                const data = await r.json();
                if (cancel) return;
                setShows(Array.isArray(data?.shows) ? data.shows : []);
            } catch {
                if (!cancel) setError('Could not load upcoming episodes.');
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    // Flatten every episode across every show + bake a stable
    // colour per show id so chips in the grid + tiles in the rail
    // share a visual identity.
    const showColour = useMemo(() => {
        const palette = [
            '#5DC8FF', '#FF6BCB', '#FFD43B', '#7DF9A4',
            '#FF8E6B', '#A580FF', '#5DE3D9', '#FFC36B',
        ];
        const m = {};
        shows.forEach((s, i) => { m[s.imdb_id] = palette[i % palette.length]; });
        return m;
    }, [shows]);

    const allEpisodes = useMemo(() => {
        const out = [];
        shows.forEach((s) => {
            (s.episodes || []).forEach((ep) => {
                out.push({
                    ...ep,
                    show: s,
                    colour: showColour[s.imdb_id] || '#5DC8FF',
                });
            });
        });
        return out.sort((a, b) => a.air_date.localeCompare(b.air_date));
    }, [shows, showColour]);

    // index episodes by ISO date so the grid lookup is O(1)
    const byDate = useMemo(() => {
        const m = {};
        allEpisodes.forEach((e) => {
            (m[e.air_date] = m[e.air_date] || []).push(e);
        });
        return m;
    }, [allEpisodes]);

    // Auto-jump the cursor to the first month that actually has
    // episodes, so the user lands on a populated grid instead of
    // an empty current month.
    useEffect(() => {
        if (allEpisodes.length === 0) return;
        const first = allEpisodes[0].air_date;
        const d = new Date(first + 'T00:00:00Z');
        const cur = new Date();
        // Only jump forward — never to a past month.
        if (
            d.getUTCFullYear() > cur.getUTCFullYear() ||
            (d.getUTCFullYear() === cur.getUTCFullYear() && d.getUTCMonth() > cur.getUTCMonth())
        ) {
            setMonthCursor({ year: d.getUTCFullYear(), month: d.getUTCMonth() });
            setSelectedDate(first);
        } else {
            setSelectedDate(first);
        }
    }, [allEpisodes]);

    const monthGrid = useMemo(() => buildMonthGrid(monthCursor.year, monthCursor.month), [monthCursor]);

    const selectedEpisodes = byDate[selectedDate] || [];
    const upcomingWeek = allEpisodes.filter((e) => {
        const today = isoDate(new Date());
        const oneWeek = isoDate(new Date(Date.now() + 7 * 86400_000));
        return e.air_date >= today && e.air_date <= oneWeek;
    }).slice(0, 12);

    return (
        <div
            data-testid="library-calendar"
            className="fixed inset-0"
            style={{
                zIndex: 60,
                background: 'rgba(6,8,15,0.97)',
                backdropFilter: 'blur(14px)',
                WebkitBackdropFilter: 'blur(14px)',
                overflow: 'auto',
                padding: '40px 64px 80px 120px',
            }}
        >
            <Header
                year={monthCursor.year}
                month={monthCursor.month}
                onPrev={() => setMonthCursor(stepMonth(monthCursor, -1))}
                onNext={() => setMonthCursor(stepMonth(monthCursor, +1))}
                onClose={onClose}
                count={allEpisodes.length}
                initialFocusRef={initialFocusRef}
            />

            {loading ? (
                <div style={{ marginTop: 80, display: 'flex', alignItems: 'center', gap: 14, justifyContent: 'center', color: 'var(--vesper-text-2)' }}>
                    <Loader2 size={20} className="vesper-spin" /> Loading your calendar…
                </div>
            ) : error ? (
                <div style={{ marginTop: 60, color: 'var(--vesper-text-2)' }}>{error}</div>
            ) : allEpisodes.length === 0 ? (
                <EmptyState />
            ) : (
                <>
                    <div
                        className="grid"
                        style={{
                            marginTop: 30,
                            gridTemplateColumns: 'minmax(0, 1.45fr) minmax(0, 1fr)',
                            gap: 36,
                            alignItems: 'start',
                        }}
                    >
                        <MonthGrid
                            grid={monthGrid}
                            byDate={byDate}
                            selectedDate={selectedDate}
                            onSelect={setSelectedDate}
                            monthCursor={monthCursor}
                        />
                        <DetailPanel
                            date={selectedDate}
                            episodes={selectedEpisodes}
                        />
                    </div>
                    {upcomingWeek.length > 0 && (
                        <UpcomingRail episodes={upcomingWeek} />
                    )}
                </>
            )}
        </div>
    );
}

/* ============================== Sub-components ============================== */

function Header({ year, month, onPrev, onNext, onClose, count, initialFocusRef }) {
    const monthName = new Date(year, month, 1).toLocaleString('default', { month: 'long' });
    return (
        <div className="flex items-end justify-between" style={{ marginBottom: 12, gap: 36, flexWrap: 'wrap' }}>
            <div className="flex items-center gap-5">
                <button
                    ref={initialFocusRef}
                    data-testid="library-calendar-close"
                    data-focusable="true"
                    data-focus-style="quiet"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={onClose}
                    aria-label="Close calendar"
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 46, height: 46,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.12)',
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div>
                    <div
                        className="vesper-mono flex items-center gap-2"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.32em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-blue-bright)',
                            marginBottom: 6,
                        }}
                    >
                        <CalendarDays size={13} strokeWidth={2} />
                        Coming up{count ? ` · ${count} episodes` : ''}
                    </div>
                    <h2
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(36px, 4vw, 56px)',
                            letterSpacing: '-0.03em',
                            lineHeight: 0.95,
                        }}
                    >
                        Your calendar
                    </h2>
                </div>
            </div>

            <div className="flex items-center gap-3">
                <button
                    data-testid="cal-prev-month"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={onPrev}
                    aria-label="Previous month"
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 44, height: 44,
                        background: 'rgba(255,255,255,0.04)',
                        border: '1px solid rgba(255,255,255,0.10)',
                        color: 'var(--vesper-text)',
                    }}
                >
                    <ChevronLeft size={18} />
                </button>
                <div
                    className="vesper-display"
                    style={{
                        minWidth: 240,
                        textAlign: 'center',
                        fontSize: 24,
                        letterSpacing: '-0.02em',
                        color: 'var(--vesper-text)',
                    }}
                >
                    {monthName} {year}
                </div>
                <button
                    data-testid="cal-next-month"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={onNext}
                    aria-label="Next month"
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 44, height: 44,
                        background: 'rgba(255,255,255,0.04)',
                        border: '1px solid rgba(255,255,255,0.10)',
                        color: 'var(--vesper-text)',
                    }}
                >
                    <ChevronRight size={18} />
                </button>
            </div>
        </div>
    );
}

function MonthGrid({ grid, byDate, selectedDate, onSelect, monthCursor }) {
    const todayIso = isoDate(new Date());
    return (
        <div
            data-testid="cal-month-grid"
            style={{
                padding: 24,
                borderRadius: 24,
                background: 'linear-gradient(180deg, rgba(255,255,255,0.03) 0%, rgba(255,255,255,0.01) 100%)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.18)',
            }}
        >
            {/* Day-of-week header */}
            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                    gap: 8,
                    marginBottom: 10,
                }}
            >
                {['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => (
                    <div
                        key={d}
                        className="vesper-mono"
                        style={{
                            fontSize: 10,
                            letterSpacing: '0.28em',
                            color: 'var(--vesper-text-3)',
                            textAlign: 'center',
                            paddingBottom: 4,
                        }}
                    >
                        {d.toUpperCase()}
                    </div>
                ))}
            </div>

            {/* Day cells */}
            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'repeat(7, minmax(0, 1fr))',
                    gap: 8,
                }}
            >
                {grid.map((cell, i) => {
                    const dayIso = cell ? isoDateFromYMD(monthCursor.year, monthCursor.month, cell) : null;
                    const episodes = (dayIso && byDate[dayIso]) || [];
                    const isToday = dayIso === todayIso;
                    const isSelected = dayIso === selectedDate;
                    const hasEpisodes = episodes.length > 0;
                    if (!cell) {
                        // Empty leading/trailing pad cell.
                        return <div key={`pad-${i}`} style={{ aspectRatio: '1 / 1.05' }} />;
                    }
                    return (
                        <button
                            key={dayIso}
                            data-testid={`cal-day-${dayIso}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                            onClick={() => onSelect(dayIso)}
                            className="text-left relative overflow-hidden"
                            style={{
                                aspectRatio: '1 / 1.05',
                                padding: '8px 9px',
                                borderRadius: 12,
                                background: isSelected
                                    ? 'linear-gradient(160deg, rgba(var(--vesper-blue-rgb), 0.32), rgba(var(--vesper-blue-rgb), 0.10))'
                                    : hasEpisodes
                                    ? 'rgba(var(--vesper-blue-rgb), 0.07)'
                                    : 'rgba(255,255,255,0.025)',
                                border: isSelected
                                    ? '1px solid rgba(var(--vesper-blue-rgb), 0.85)'
                                    : isToday
                                    ? '1px solid rgba(var(--vesper-blue-rgb), 0.55)'
                                    : '1px solid rgba(255,255,255,0.05)',
                                cursor: 'pointer',
                                color: 'var(--vesper-text)',
                                boxShadow: isSelected ? '0 14px 36px rgba(var(--vesper-blue-rgb), 0.25)' : 'none',
                            }}
                        >
                            <div
                                className="vesper-display"
                                style={{
                                    fontSize: 18,
                                    fontWeight: isToday ? 800 : 600,
                                    letterSpacing: '-0.01em',
                                    color: isToday ? 'var(--vesper-blue-bright)' : 'var(--vesper-text)',
                                    lineHeight: 1,
                                }}
                            >
                                {cell}
                            </div>
                            {isToday && (
                                <div
                                    className="vesper-mono"
                                    style={{
                                        fontSize: 8,
                                        letterSpacing: '0.22em',
                                        color: 'var(--vesper-blue-bright)',
                                        marginTop: 2,
                                    }}
                                >
                                    TODAY
                                </div>
                            )}

                            {/* Episode chips (max 2 visible + "+N more") */}
                            {hasEpisodes && (
                                <div
                                    className="flex flex-col"
                                    style={{ gap: 3, marginTop: 6 }}
                                >
                                    {episodes.slice(0, 2).map((ep) => (
                                        <div
                                            key={`${ep.show.imdb_id}-S${ep.season}E${ep.episode}`}
                                            style={{
                                                fontSize: 10,
                                                fontWeight: 600,
                                                color: '#fff',
                                                background: `${ep.colour}33`,
                                                borderLeft: `3px solid ${ep.colour}`,
                                                padding: '2px 6px',
                                                borderRadius: 4,
                                                whiteSpace: 'nowrap',
                                                overflow: 'hidden',
                                                textOverflow: 'ellipsis',
                                                lineHeight: 1.2,
                                            }}
                                        >
                                            {ep.show.name}
                                        </div>
                                    ))}
                                    {episodes.length > 2 && (
                                        <div
                                            className="vesper-mono"
                                            style={{
                                                fontSize: 9,
                                                color: 'var(--vesper-text-3)',
                                                letterSpacing: '0.12em',
                                                paddingLeft: 4,
                                            }}
                                        >
                                            +{episodes.length - 2} MORE
                                        </div>
                                    )}
                                </div>
                            )}
                        </button>
                    );
                })}
            </div>
        </div>
    );
}

function DetailPanel({ date, episodes }) {
    if (!date) {
        return null;
    }
    const dateObj = new Date(date + 'T00:00:00Z');
    const niceDate = dateObj.toLocaleDateString('default', {
        weekday: 'long', month: 'long', day: 'numeric',
    });
    return (
        <div
            data-testid="cal-detail-panel"
            style={{
                padding: '22px 24px',
                borderRadius: 22,
                background: 'rgba(255,255,255,0.02)',
                border: '1px solid rgba(255,255,255,0.08)',
                minHeight: 360,
            }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10, letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)',
                    textTransform: 'uppercase', marginBottom: 8,
                }}
            >
                {episodes.length === 0 ? 'Nothing scheduled' : `${episodes.length} episode${episodes.length === 1 ? '' : 's'}`}
            </div>
            <h3
                className="vesper-display"
                style={{
                    fontSize: 26,
                    letterSpacing: '-0.02em',
                    lineHeight: 1.1,
                    marginBottom: 18,
                }}
            >
                {niceDate}
            </h3>
            {episodes.length === 0 ? (
                <p style={{ color: 'var(--vesper-text-2)', fontSize: 14, lineHeight: 1.55 }}>
                    No episodes from your library on this day. Pick a highlighted
                    day from the calendar to see what's airing.
                </p>
            ) : (
                <div className="flex flex-col" style={{ gap: 14 }}>
                    {episodes.map((ep) => (
                        <EpisodeCard key={`${ep.show.imdb_id}-S${ep.season}E${ep.episode}`} ep={ep} />
                    ))}
                </div>
            )}
        </div>
    );
}

function EpisodeCard({ ep }) {
    const still = ep.still_path ? `https://image.tmdb.org/t/p/w300${ep.still_path}` : null;
    const poster = ep.show.poster_path ? `https://image.tmdb.org/t/p/w154${ep.show.poster_path}` : null;
    const thumb = still || poster;
    return (
        <div
            data-testid={`cal-episode-${ep.show.imdb_id}-S${ep.season}E${ep.episode}`}
            className="flex"
            style={{
                gap: 14,
                padding: 12,
                borderRadius: 14,
                background: 'rgba(11,19,34,0.55)',
                border: `1px solid ${ep.colour}55`,
                borderLeft: `4px solid ${ep.colour}`,
            }}
        >
            {thumb && (
                <div
                    style={{
                        width: 110, height: 62,
                        flexShrink: 0,
                        borderRadius: 8,
                        background: `#0a0d18 url(${thumb}) center/cover no-repeat`,
                    }}
                />
            )}
            <div style={{ minWidth: 0, flex: 1 }}>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 9, letterSpacing: '0.28em',
                        color: ep.colour, textTransform: 'uppercase',
                        marginBottom: 3, fontWeight: 700,
                    }}
                >
                    {ep.show.network ? `${ep.show.network} · ` : ''}S{ep.season} · E{ep.episode}
                </div>
                <div
                    style={{
                        fontSize: 15, fontWeight: 700, color: 'var(--vesper-text)',
                        lineHeight: 1.25, marginBottom: 2,
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}
                >
                    {ep.show.name}
                </div>
                <div
                    style={{
                        fontSize: 12, color: 'var(--vesper-text-2)',
                        lineHeight: 1.4,
                        display: '-webkit-box',
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                    }}
                >
                    {ep.name || ep.overview || '\u00a0'}
                </div>
            </div>
        </div>
    );
}

function UpcomingRail({ episodes }) {
    return (
        <section style={{ marginTop: 40 }}>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10, letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)',
                    textTransform: 'uppercase', marginBottom: 10,
                }}
            >
                This week
            </div>
            <div
                className="flex"
                style={{
                    gap: 14,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    scrollSnapType: 'x mandatory',
                    paddingTop: 8,
                    paddingBottom: 8,
                }}
            >
                {episodes.map((ep) => {
                    const still = ep.still_path
                        ? `https://image.tmdb.org/t/p/w300${ep.still_path}`
                        : ep.show.backdrop_path
                        ? `https://image.tmdb.org/t/p/w300${ep.show.backdrop_path}`
                        : null;
                    return (
                        <div
                            key={`week-${ep.show.imdb_id}-S${ep.season}E${ep.episode}`}
                            data-testid={`cal-rail-${ep.show.imdb_id}-S${ep.season}E${ep.episode}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                            className="relative overflow-hidden"
                            style={{
                                width: 280,
                                flex: '0 0 280px',
                                scrollSnapAlign: 'start',
                                aspectRatio: '16 / 9',
                                borderRadius: 14,
                                background: '#0B1322',
                                border: '1px solid rgba(255,255,255,0.06)',
                            }}
                        >
                            {still && (
                                <img
                                    src={still}
                                    alt=""
                                    loading="lazy"
                                    style={{
                                        position: 'absolute', inset: 0,
                                        width: '100%', height: '100%',
                                        objectFit: 'cover',
                                    }}
                                />
                            )}
                            <div
                                className="absolute inset-0 pointer-events-none"
                                style={{
                                    background: 'linear-gradient(180deg, rgba(0,0,0,0) 30%, rgba(0,0,0,0.9) 100%)',
                                }}
                            />
                            <div
                                className="absolute"
                                style={{ left: 12, top: 10 }}
                            >
                                <span
                                    style={{
                                        display: 'inline-block',
                                        width: 10, height: 10, borderRadius: '50%',
                                        background: ep.colour,
                                        boxShadow: `0 0 10px ${ep.colour}`,
                                    }}
                                />
                            </div>
                            <div
                                className="absolute"
                                style={{ left: 12, right: 12, bottom: 10 }}
                            >
                                <div
                                    className="vesper-mono"
                                    style={{
                                        fontSize: 9, letterSpacing: '0.22em',
                                        color: ep.colour, textTransform: 'uppercase',
                                        fontWeight: 700, marginBottom: 2,
                                    }}
                                >
                                    {prettyDate(ep.air_date)} · S{ep.season} · E{ep.episode}
                                </div>
                                <div
                                    style={{
                                        fontSize: 15, fontWeight: 700, color: '#fff',
                                        textShadow: '0 1px 4px rgba(0,0,0,0.55)',
                                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                    }}
                                >
                                    {ep.show.name}
                                </div>
                            </div>
                        </div>
                    );
                })}
            </div>
        </section>
    );
}

function EmptyState() {
    return (
        <div
            style={{
                marginTop: 40,
                padding: '40px 36px',
                borderRadius: 22,
                background: 'rgba(255,255,255,0.02)',
                border: '1px dashed rgba(var(--vesper-blue-rgb), 0.3)',
                maxWidth: 720,
            }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 11, letterSpacing: '0.28em',
                    color: 'var(--vesper-blue-bright)', marginBottom: 10,
                }}
            >
                Nothing on the horizon
            </div>
            <h3
                className="vesper-display"
                style={{ fontSize: 28, letterSpacing: '-0.02em', marginBottom: 10 }}
            >
                <Tv size={22} strokeWidth={2} style={{ display: 'inline', marginRight: 10, color: 'var(--vesper-blue)' }} />
                No upcoming episodes
            </h3>
            <p style={{ color: 'var(--vesper-text-2)', fontSize: 14, lineHeight: 1.5, maxWidth: '52ch' }}>
                We didn't find any scheduled episodes for the shows in your library.
                Either they're between seasons, finished their run, or TMDB hasn't
                published the schedule yet.  Add a fresh series and check back —
                the calendar refreshes every time you open it.
            </p>
        </div>
    );
}

/* ============================== Helpers ============================== */

function isoDate(d) {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${dd}`;
}

function isoDateFromYMD(year, month, day) {
    const m = String(month + 1).padStart(2, '0');
    const d = String(day).padStart(2, '0');
    return `${year}-${m}-${d}`;
}

function stepMonth({ year, month }, delta) {
    let m = month + delta;
    let y = year;
    while (m < 0) { m += 12; y -= 1; }
    while (m > 11) { m -= 12; y += 1; }
    return { year: y, month: m };
}

/**
 * Build a 6-row × 7-col Monday-first grid for the given month.
 * Each entry is either a day number (1..N) or null for the
 * leading/trailing pad cells.  We always emit 42 cells so the
 * grid height is constant regardless of month length.
 */
function buildMonthGrid(year, month) {
    const first = new Date(year, month, 1);
    const last = new Date(year, month + 1, 0);
    // Monday=0, Sunday=6 (vs JS default Sunday=0).
    const lead = (first.getDay() + 6) % 7;
    const total = last.getDate();
    const cells = [];
    for (let i = 0; i < lead; i++) cells.push(null);
    for (let d = 1; d <= total; d++) cells.push(d);
    while (cells.length < 42) cells.push(null);
    return cells;
}

function prettyDate(iso) {
    try {
        const d = new Date(iso + 'T00:00:00Z');
        return d.toLocaleDateString('default', { month: 'short', day: 'numeric' });
    } catch {
        return iso;
    }
}
