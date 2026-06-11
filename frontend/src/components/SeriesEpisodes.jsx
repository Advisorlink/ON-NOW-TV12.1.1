import React, { useMemo, useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    Play,
    Loader2,
    ExternalLink,
    Magnet,
    Info,
    Copy,
    Star,
} from 'lucide-react';
import { Vesper } from '@/lib/api';
import { API } from '@/lib/api';
import Host from '@/lib/host';
import { qualityBadge, qualityTags, toneColors, is1080p } from '@/lib/streamMeta';
import { getAutoplay1080p } from '@/lib/prefs';
import * as cw from '@/lib/continueWatching';

/**
 * Cinematic seasons + episodes browser for TV series.
 *
 *  - Season picker: pill chips along the top, scrollable horizontally
 *    when there are many seasons.  Season 0 (specials) hidden by
 *    default.
 *  - Episode list: full-width cards with 16:9 thumbnails on the left,
 *    title / overview / released / rating / runtime on the right.
 *    Selecting an episode reveals the per-episode stream list inline
 *    so the viewer never loses page context.
 *  - All elements are D-pad focusable and use Vesper's blue accent.
 */
const streamMode = (s) => {
    if (s?.url) return 'direct';
    if (s?.externalUrl) return 'external';
    if (s?.infoHash) return 'torrent';
    return 'unknown';
};

const buildMagnet = (s, fallbackName = '') => {
    if (!s?.infoHash) return null;
    const name = s.name || s.title || fallbackName || 'video';
    const trackers = Array.isArray(s.sources)
        ? s.sources
              .filter((t) => typeof t === 'string' && t.startsWith('tracker:'))
              .map((t) => `&tr=${encodeURIComponent(t.slice('tracker:'.length))}`)
              .join('')
        : '';
    return `magnet:?xt=urn:btih:${s.infoHash}&dn=${encodeURIComponent(
        name
    )}${trackers}`;
};

const fmtRuntime = (val) => {
    if (!val) return null;
    if (typeof val === 'string') return val;
    const m = parseInt(val, 10);
    if (!m) return null;
    if (m < 60) return `${m}m`;
    return `${Math.floor(m / 60)}h ${m % 60}m`;
};

const fmtDate = (iso) => {
    if (!iso) return null;
    try {
        return new Date(iso).toLocaleDateString(undefined, {
            year: 'numeric',
            month: 'short',
            day: 'numeric',
        });
    } catch {
        return null;
    }
};

export default function SeriesEpisodes({
    meta,
    parentId,
    initialSeason,
    highlightEpisode,
    onEpisodesShownChange,
    // v2.7.31 — optional React node rendered as the FIRST item in
    // the Seasons pill row (currently the Trailer pill).  Lets the
    // parent Detail.jsx keep ownership of the trailer state/handler
    // while visually anchoring it next to the season chips.
    leadingPill,
}) {
    const navigate = useNavigate();
    const videos = useMemo(() => {
        const list = Array.isArray(meta?.videos) ? meta.videos : [];
        // Drop bonus content (season 0) unless that's literally the
        // only thing the show has.
        const withSeasons = list.filter((v) => v && typeof v.season === 'number');
        const hasReal = withSeasons.some((v) => v.season > 0);
        return hasReal ? withSeasons.filter((v) => v.season > 0) : withSeasons;
    }, [meta]);

    const seasons = useMemo(() => {
        const map = new Map();
        for (const v of videos) {
            if (!map.has(v.season)) map.set(v.season, []);
            map.get(v.season).push(v);
        }
        // Sort each season's episodes
        for (const arr of map.values()) {
            arr.sort((a, b) => (a.episode || 0) - (b.episode || 0));
        }
        return Array.from(map.entries())
            .sort(([a], [b]) => a - b)
            .map(([season, eps]) => ({ season, eps }));
    }, [videos]);

    const [activeSeason, setActiveSeason] = useState(
        () => initialSeason || seasons[0]?.season || 1
    );
    /* Episodes are HIDDEN by default — the user lands on the
     * series page and just sees season pills + Cast row below.
     * Pressing OK on a season pill reveals that season's
     * episodes AND tells the parent to hide the Cast row. */
    const [episodesShown, setEpisodesShown] = useState(
        () => !!(initialSeason && highlightEpisode)
    );
    useEffect(() => {
        if (onEpisodesShownChange) onEpisodesShownChange(episodesShown);
    }, [episodesShown, onEpisodesShownChange]);
    useEffect(() => {
        // Reset when meta changes
        if (seasons.length && !seasons.find((s) => s.season === activeSeason)) {
            setActiveSeason(seasons[0].season);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [seasons]);

    /* When the native player tells us to focus a specific episode
     * (e.g., after EndReached fires for the previous episode), set
     * the season + scroll the episode into view + give it focus.
     * Runs once when the season's episodes have hydrated. */
    useEffect(() => {
        if (!initialSeason || !highlightEpisode) return;
        if (!seasons.length) return;
        if (activeSeason !== initialSeason) {
            setActiveSeason(initialSeason);
            return;  // re-runs on next render after the season change
        }
        // Defer one frame so SeriesEpisodes has rendered the new
        // season's episode list before we try to focus an episode
        // inside it.
        const t = setTimeout(() => {
            const id = `ep-card-${initialSeason}-${highlightEpisode}`;
            const el = document.querySelector(`[data-episode-id="${id}"]`);
            if (el) {
                try {
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    el.focus({ preventScroll: true });
                    el.setAttribute('data-focused', 'true');
                } catch { /* ignore */ }
            }
        }, 250);
        return () => clearTimeout(t);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [initialSeason, highlightEpisode, seasons, activeSeason]);

    const currentSeason = seasons.find((s) => s.season === activeSeason);

    const [openEpisodeId, setOpenEpisodeId] = useState(null);
    const [episodeStreams, setEpisodeStreams] = useState({});
    const [loadingEpisodeId, setLoadingEpisodeId] = useState(null);
    const [copied, setCopied] = useState(null);

    // Episode-stream-list-scoped D-pad override.  When focus is
    // inside ANY expanded episode's stream list, ArrowUp/Down walks
    // sibling streams in DOM order so the user never accidentally
    // jumps to the next episode card mid-scroll.  At the top/bottom
    // edge of a stream list the handler bails so the global
    // spatial focus picks up and walks onto the next episode.
    useEffect(() => {
        const onKey = (e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            const active = document.activeElement;
            if (!active) return;
            const list = active.closest('[data-stream-list="true"]');
            if (!list) return;
            const items = Array.from(
                list.querySelectorAll('button[data-focusable="true"]')
            ).filter((el) => !el.hasAttribute('disabled') && /-stream-\d+$/.test(el.getAttribute('data-testid') || ''));
            if (items.length === 0) return;
            let idx = items.indexOf(active);
            if (idx === -1) {
                const parentLi = active.closest('li');
                if (!parentLi) return;
                const sibling = parentLi.querySelector(
                    'button[data-focusable="true"]'
                );
                if (!sibling) return;
                idx = items.indexOf(sibling);
                if (idx === -1) return;
            }
            const nextIdx = e.key === 'ArrowDown' ? idx + 1 : idx - 1;
            if (nextIdx < 0 || nextIdx >= items.length) return; // edge — fall through
            e.preventDefault();
            e.stopPropagation();
            const next = items[nextIdx];
            try { next.focus({ preventScroll: false }); } catch (err) { /* ignore */ }
            next.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== next) el.removeAttribute('data-focused');
                });
            try {
                next.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            } catch (err) { /* ignore */ }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, []);

    // ---------------------------------------------------------------
    // SEASON-PICKER D-pad (v2.7.32)
    // ---------------------------------------------------------------
    // With the season picker now a single-line horizontal scroll
    // strip, the spatial focus engine handles LEFT / RIGHT walking
    // natively.  No row-wrap handler needed — the previous wrapped
    // 2nd row was the bug we just removed.  See `data-testid=
    // "season-picker"` style block below for the layout change.

    const pickAutoplayCandidate = (streamsArr) => {
        if (!Array.isArray(streamsArr) || streamsArr.length === 0) return null;
        // User spec: any stream that even mentions "1080" anywhere
        // in the title/name/description counts as 1080p autoplay.
        // Prefer direct-mode streams, then fall back to any 1080.
        return (
            streamsArr.find(
                (s) => streamMode(s) === 'direct' && is1080p(s)
            ) ||
            streamsArr.find((s) => is1080p(s)) ||
            null
        );
    };

    const handleEpisodeClick = async (ep) => {
        const autoplay = getAutoplay1080p();
        if (openEpisodeId === ep.id && !autoplay) {
            setOpenEpisodeId(null);
            return;
        }
        setOpenEpisodeId(ep.id);
        // Reuse cached streams if we already fetched this episode.
        const cached = episodeStreams[ep.id];
        if (cached) {
            if (autoplay) {
                const cand = pickAutoplayCandidate(cached.streams);
                if (cand) {
                    playStream(cand, ep);
                    return;
                }
            }
            return;
        }
        setLoadingEpisodeId(ep.id);
        try {
            const res = await Vesper.getStreams('series', ep.id);
            const streamsArr = res?.streams || [];
            setEpisodeStreams((s) => ({
                ...s,
                [ep.id]: {
                    streams: streamsArr,
                    diagnostics: res?.diagnostics || [],
                },
            }));
            // Autoplay: as soon as streams resolve, fire 1080p if
            // we have it.  Falls back to the expanded stream list
            // when no 1080p candidate is available.
            if (autoplay) {
                const cand = pickAutoplayCandidate(streamsArr);
                if (cand) {
                    playStream(cand, ep);
                }
            }
        } catch {
            setEpisodeStreams((s) => ({
                ...s,
                [ep.id]: { streams: [], diagnostics: [] },
            }));
        } finally {
            setLoadingEpisodeId(null);
        }
    };

    const playStream = async (stream, ep) => {
        const mode = streamMode(stream);
        if (mode === 'direct' || mode === 'torrent') {
            // Resolve the playable URL — direct streams carry a
            // `url`; torrents become a magnet: URI that libVLC's
            // bittorrent demuxer ingests natively.
            const playUrl =
                mode === 'direct'
                    ? stream.url
                    : buildMagnet(stream, `${meta?.name || ''} S${ep.season}E${ep.episode}`);
            if (!playUrl) return;
            const title = `${meta?.name || ''} · S${ep.season}E${ep.episode} · ${ep.name || ''}`;
            // Pre-fetch English subtitle for this exact episode
            let subtitleUrl = '';
            try {
                const r = await fetch(
                    `${API}/subtitles/series/${encodeURIComponent(ep.id)}`,
                    { cache: 'no-store' }
                );
                if (r.ok) {
                    const data = await r.json();
                    const list = Array.isArray(data?.subtitles)
                        ? data.subtitles
                        : [];
                    const eng = list.find((s) => /^en/i.test(s.lang || ''));
                    if (eng?.url) subtitleUrl = eng.url;
                }
            } catch {
                /* ignore */
            }
            const cwId = `${meta?.id || ''}:s${ep.season}e${ep.episode}`;
            const existing = cw.getEntries().find((e) => e.id === cwId);
            cw.upsert({
                id: cwId,
                type: 'series',
                title,
                backdrop: meta?.background || meta?.poster || '',
                poster: meta?.poster || '',
                synopsis: ep.overview || meta?.description || '',
                year: ep.firstAired ? String(ep.firstAired).slice(0, 4) : (meta?.releaseInfo || ''),
                rating: meta?.imdbRating || '',
                runtime: ep.runtime || meta?.runtime || '',
                genres: meta?.genres || [],
                streamUrl: playUrl,
                subtitleUrl,
                positionMs: existing?.positionMs || 0,
                durationMs: existing?.durationMs || 0,
                route: `/title/series/${meta?.id || ''}`,
            });
            if (
                Host.playVideo({
                    url: playUrl,
                    title,
                    type: 'series',
                    subtitleUrl,
                    // v2.7.28 — full cover-art fallback chain.
                    poster:
                        meta?.poster ||
                        meta?.posterUrl ||
                        meta?.background ||
                        meta?.backdrop ||
                        '',
                    backdrop:
                        meta?.background ||
                        meta?.backdrop ||
                        meta?.poster ||
                        '',
                    synopsis:
                        ep.overview ||
                        meta?.description ||
                        meta?.overview ||
                        '',
                    year: ep.firstAired ? String(ep.firstAired).slice(0, 4) : (meta?.releaseInfo || ''),
                    rating: meta?.imdbRating || '',
                    runtime: ep.runtime || meta?.runtime || '',
                    genres: meta?.genres || [],
                    cwId,
                    startAtMs: existing?.positionMs || 0,
                })
            ) return;
            navigate(
                `/play?url=${encodeURIComponent(
                    playUrl
                )}&title=${encodeURIComponent(title)}&type=series&imdbId=${encodeURIComponent(ep.id)}`
            );
        } else if (mode === 'external') {
            try {
                window.open(stream.externalUrl, '_blank', 'noopener,noreferrer');
            } catch {
                /* popup blocked */
            }
        }
    };

    const copyMagnet = async (stream, ep) => {
        const magnet = buildMagnet(stream, `${meta?.name || ''} S${ep.season}E${ep.episode}`);
        if (!magnet) return;
        try {
            await navigator.clipboard.writeText(magnet);
            setCopied(stream.infoHash);
            setTimeout(() => setCopied(null), 1800);
        } catch {
            window.prompt('Copy this magnet link:', magnet);
        }
    };

    if (!seasons.length) {
        return (
            <div
                className="vesper-glass rounded-2xl p-6 mt-10"
                style={{ color: 'var(--vesper-text-2)' }}
            >
                Episode information isn&apos;t available from Cinemeta for this
                series yet.
            </div>
        );
    }

    return (
        <section data-testid="series-episodes" className="mt-10">
            {/* Season picker — heading + count line hidden when
                episodes aren't yet shown (the user is still in the
                "browse seasons + cast" stage and we want a clean
                Autoplay-style hero look). */}
            {episodesShown && (
                <div className="flex items-baseline justify-between mb-6 flex-wrap gap-4">
                    <h3
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(20px, 1.8vw, 28px)',
                            letterSpacing: '-0.02em',
                        }}
                    >
                        Seasons & episodes
                    </h3>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-3)',
                        }}
                    >
                        {seasons.length} season{seasons.length === 1 ? '' : 's'} ·{' '}
                        {currentSeason?.eps.length || 0} episode
                        {(currentSeason?.eps.length || 0) === 1 ? '' : 's'} this
                        season
                    </div>
                </div>
            )}

            <div
                data-testid="season-picker"
                // v2.7.32 — switched from `flex-wrap` (which created
                // a 2nd row of pills that got hidden behind the
                // absolute-positioned Cast lane) to a single-line
                // horizontal scroll strip.  Matches Netflix / Apple
                // TV season picker pattern and what the original
                // file header comment promised: "scrollable
                // horizontally when there are many seasons".  The
                // spatial focus engine handles LEFT / RIGHT walking
                // and scrolls the focused pill into view.
                className="flex items-center"
                style={{
                    gap: 'clamp(8px, 0.7vw, 12px)',
                    marginBottom: 24,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    flexWrap: 'nowrap',
                    scrollPaddingLeft: 16,
                    scrollPaddingRight: 16,
                    /* v2.8.88 — Top + bottom padding so the focused
                       pill's 1.08× scale has breathing room.  Before
                       this fix overflowY:hidden was cropping the
                       top + bottom edges of the focused season pill
                       (user reported "seasons are a little bit
                       cropped at the top and cropped at the bottom"). */
                    paddingTop: 10,
                    paddingBottom: 10,
                    /* Compensate for the new padding so neighbouring
                       rails don't shift on this page. */
                    marginTop: -10,
                    marginBottom: 14,
                }}
            >
                {leadingPill}
                {seasons.map(({ season }) => {
                    const active = season === activeSeason;
                    const label =
                        season === 0 ? 'Specials' : `Season ${season}`;
                    return (
                        <button
                            key={season}
                            data-testid={`season-${season}`}
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus={season === activeSeason && !episodesShown ? 'true' : undefined}
                            tabIndex={0}
                            onFocus={(e) => {
                                // Scroll the focused pill into view
                                // so D-pad walking reveals off-screen
                                // seasons in the horizontal strip.
                                try {
                                    e.currentTarget.scrollIntoView({
                                        behavior: 'smooth',
                                        block: 'nearest',
                                        inline: 'center',
                                    });
                                } catch (err) { /* ignore */ }
                            }}
                            onClick={() => {
                                if (episodesShown && season === activeSeason) {
                                    /* Toggling the same active season
                                       collapses the episode list and
                                       brings the Cast row back. */
                                    setEpisodesShown(false);
                                } else {
                                    setActiveSeason(season);
                                    setEpisodesShown(true);
                                }
                            }}
                            className="font-sans font-semibold rounded-full"
                            style={{
                                height: 'clamp(36px, 3vw, 44px)',
                                paddingLeft: 'clamp(16px, 1.4vw, 22px)',
                                paddingRight: 'clamp(16px, 1.4vw, 22px)',
                                fontSize: 'clamp(13px, 0.95vw, 15px)',
                                background: active
                                    ? 'var(--vesper-blue)'
                                    : 'rgba(255,255,255,0.04)',
                                color: active
                                    ? 'var(--vesper-bg-0)'
                                    : 'var(--vesper-text-2)',
                                border: active
                                    ? '1px solid transparent'
                                    : '1px solid rgba(255,255,255,0.08)',
                                transition:
                                    'background-color 180ms ease, color 180ms ease',
                            }}
                        >
                            {label}
                        </button>
                    );
                })}
            </div>

            {/* Episode list — gated.  When user is browsing
                seasons + cast (initial state), this stays hidden. */}
            {!episodesShown ? null : (
            <ul
                data-testid="episode-list"
                className="flex flex-col"
                style={{ gap: 'clamp(12px, 1vw, 18px)' }}
            >
                {(currentSeason?.eps || []).map((ep) => {
                    const open = openEpisodeId === ep.id;
                    const data = episodeStreams[ep.id];
                    const isLoading = loadingEpisodeId === ep.id;
                    return (
                        <EpisodeCard
                            key={ep.id}
                            ep={ep}
                            open={open}
                            onClick={() => handleEpisodeClick(ep)}
                            data={data}
                            isLoading={isLoading}
                            parentId={parentId}
                            playStream={playStream}
                            copyMagnet={copyMagnet}
                            copied={copied}
                        />
                    );
                })}
            </ul>
            )}
        </section>
    );
}

/* ------------------------------------------------------------------ */
/*  Single episode card                                                */
/* ------------------------------------------------------------------ */
function EpisodeCard({
    ep,
    open,
    onClick,
    data,
    isLoading,
    playStream,
    copyMagnet,
    copied,
    parentId,
}) {
    const cardRef = useRef(null);
    useEffect(() => {
        if (open && cardRef.current) {
            cardRef.current.scrollIntoView({
                behavior: 'smooth',
                block: 'nearest',
            });
        }
    }, [open]);

    const runtime = fmtRuntime(ep.runtime);
    const released = fmtDate(ep.released || ep.firstAired);
    // CW key is "<parentId>:s<N>e<M>" — same as the resume entry
    // upsert in handleEpisodeClick / playStream below.  If the user
    // has watched this episode all the way through we flag it with
    // a "WATCHED" badge on the thumbnail.
    const cwIdForEp = `${parentId || ''}:s${ep.season}e${ep.episode}`;
    const watched = cw.isWatched(cwIdForEp);
    const progress = cw.getProgress(cwIdForEp);
    const pct =
        !watched && progress?.durationMs && progress?.positionMs
            ? Math.min(
                  100,
                  Math.max(0, (progress.positionMs / progress.durationMs) * 100)
              )
            : 0;

    return (
        <li
            ref={cardRef}
            // v2.10.46-e — Dropped `overflow-hidden` so the focused
            // button's `transform: scale(1.04)` (from
            // `data-focus-style="quiet"`) isn't clipped on the left
            // edge.  User reported episode rows "getting cut off a
            // little bit" on the left — the LI was clipping the
            // inner button's overflow growth.  The inner thumbnail
            // already has `rounded-xl` of its own so the rounded
            // look survives.
            className="rounded-2xl"
            style={{
                background: open ? 'rgba(17,24,39,0.7)' : 'rgba(17,24,39,0.4)',
                border: open
                    ? '1px solid rgba(var(--vesper-blue-rgb),0.35)'
                    : '1px solid rgba(255,255,255,0.06)',
                transition: 'background-color 200ms ease, border-color 200ms ease',
            }}
        >
            <button
                data-testid={`episode-${ep.season}-${ep.episode}`}
                data-episode-id={`ep-card-${ep.season}-${ep.episode}`}
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={onClick}
                className="w-full text-left flex items-stretch gap-5"
                style={{
                    padding: 'clamp(12px, 0.9vw, 18px)',
                    cursor: 'pointer',
                }}
            >
                {/* Thumbnail */}
                <div
                    className="relative shrink-0 overflow-hidden rounded-xl"
                    style={{
                        width: 'clamp(180px, 18vw, 280px)',
                        aspectRatio: '16 / 9',
                        background: 'rgba(0,0,0,0.5)',
                        border: '1px solid rgba(255,255,255,0.05)',
                    }}
                >
                    {ep.thumbnail ? (
                        <img
                            src={ep.thumbnail}
                            alt={ep.name || `Episode ${ep.episode}`}
                            loading="lazy"
                            className="absolute inset-0 w-full h-full object-cover"
                        />
                    ) : (
                        <div
                            className="absolute inset-0 flex items-center justify-center"
                            style={{
                                background:
                                    'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.08) 0%, rgba(var(--vesper-blue-rgb),0) 100%)',
                                color: 'rgba(var(--vesper-blue-rgb),0.55)',
                                fontFamily: '"Geist", system-ui, sans-serif',
                                fontWeight: 700,
                                fontSize: 'clamp(34px, 3vw, 56px)',
                                letterSpacing: '-0.04em',
                            }}
                        >
                            S{ep.season}·E{ep.episode}
                        </div>
                    )}
                    <div
                        className="absolute inset-0 pointer-events-none"
                        style={{
                            background:
                                'linear-gradient(180deg, rgba(0,0,0,0) 60%, rgba(0,0,0,0.55) 100%)',
                        }}
                    />
                    <div
                        className="absolute bottom-2 left-2 rounded-md px-2 py-1 vesper-mono"
                        style={{
                            background: 'rgba(6,8,15,0.78)',
                            color: '#fff',
                            fontSize: 'clamp(9px, 0.62vw, 11px)',
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        S{ep.season} · E{ep.episode}
                    </div>
                    {watched && (
                        <div
                            data-testid={`watched-${ep.season}-${ep.episode}`}
                            className="absolute top-2 right-2 flex items-center gap-1.5 vesper-mono"
                            style={{
                                /* v2.10.46-d — Restored the original
                                 * GREEN palette for "Watched" so it's
                                 * distinct from the blue UI accent.
                                 * User reported the blue badge from
                                 * the June-4 fallback was blending
                                 * with the rest of the page. */
                                background: 'rgba(34, 197, 94, 0.94)',
                                color: '#06080F',
                                fontSize: 'clamp(9px, 0.62vw, 11px)',
                                letterSpacing: '0.18em',
                                textTransform: 'uppercase',
                                fontWeight: 700,
                                padding: '4px 9px 4px 7px',
                                borderRadius: 6,
                                boxShadow:
                                    '0 4px 14px rgba(34, 197, 94, 0.45)',
                            }}
                        >
                            <svg
                                width="11"
                                height="11"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="3.2"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <polyline points="20 6 9 17 4 12" />
                            </svg>
                            Watched
                        </div>
                    )}
                    {!watched && pct > 0 && (
                        /* v2.10.46-d — Yellow "Watching" badge: lost
                         * in the June-4 rollback, restored from the
                         * pre-rollback build.  Shown when the user
                         * has any progress on the episode but hasn't
                         * crossed the watched threshold. */
                        <div
                            data-testid={`watching-${ep.season}-${ep.episode}`}
                            className="absolute top-2 right-2 flex items-center gap-1.5 vesper-mono"
                            style={{
                                background: 'rgba(250, 204, 21, 0.95)',
                                color: '#06080F',
                                fontSize: 'clamp(9px, 0.62vw, 11px)',
                                letterSpacing: '0.18em',
                                textTransform: 'uppercase',
                                fontWeight: 700,
                                padding: '4px 9px 4px 7px',
                                borderRadius: 6,
                                boxShadow:
                                    '0 4px 14px rgba(250, 204, 21, 0.40)',
                            }}
                        >
                            <svg
                                width="11"
                                height="11"
                                viewBox="0 0 24 24"
                                fill="none"
                                stroke="currentColor"
                                strokeWidth="2.8"
                                strokeLinecap="round"
                                strokeLinejoin="round"
                            >
                                <circle cx="12" cy="12" r="10" />
                                <polyline points="12 6 12 12 16 14" />
                            </svg>
                            Watching
                        </div>
                    )}
                    {pct > 0 && (
                        <div
                            data-testid={`ep-progress-${ep.season}-${ep.episode}`}
                            className="absolute"
                            style={{
                                left: 0,
                                right: 0,
                                bottom: 0,
                                height: 4,
                                background: 'rgba(255,255,255,0.16)',
                            }}
                        >
                            <div
                                style={{
                                    width: `${pct}%`,
                                    height: '100%',
                                    background: 'var(--vesper-blue)',
                                    boxShadow:
                                        '0 0 12px var(--vesper-blue-glow)',
                                }}
                            />
                        </div>
                    )}
                    <div
                        className="absolute inset-0 flex items-center justify-center pointer-events-none"
                        style={{
                            background: open
                                ? 'rgba(0,0,0,0.35)'
                                : 'rgba(0,0,0,0)',
                            transition: 'background-color 200ms ease',
                        }}
                    >
                        <span
                            className="flex items-center justify-center rounded-full"
                            style={{
                                width: 56,
                                height: 56,
                                background: 'rgba(var(--vesper-blue-rgb),0.92)',
                                color: '#06080f',
                                opacity: open ? 1 : 0,
                                transform: open ? 'scale(1)' : 'scale(0.85)',
                                transition: 'opacity 200ms ease, transform 200ms ease',
                                boxShadow: '0 0 40px rgba(var(--vesper-blue-rgb),0.5)',
                            }}
                        >
                            <Play size={20} fill="currentColor" />
                        </span>
                    </div>
                </div>

                {/* Text */}
                <div className="min-w-0 flex-1 flex flex-col" style={{ opacity: watched ? 0.68 : 1 }}>
                    <div
                        className="flex items-baseline gap-3 flex-wrap"
                        style={{ marginBottom: 6 }}
                    >
                        <h4
                            className="font-sans truncate"
                            style={{
                                fontSize: 'clamp(16px, 1.3vw, 22px)',
                                fontWeight: 600,
                                letterSpacing: '-0.015em',
                                color: 'var(--vesper-text)',
                            }}
                        >
                            {ep.name || `Episode ${ep.episode}`}
                        </h4>
                        {ep.rating && parseFloat(ep.rating) > 0 ? (
                            <span
                                className="vesper-meta inline-flex items-center gap-1"
                                style={{ fontSize: 13 }}
                            >
                                <Star
                                    size={11}
                                    fill="currentColor"
                                    style={{ color: 'var(--vesper-blue)' }}
                                />
                                <span style={{ color: 'var(--vesper-text-2)' }}>
                                    {ep.rating}
                                </span>
                            </span>
                        ) : null}
                    </div>
                    <div
                        className="vesper-meta flex items-center gap-3 flex-wrap"
                        style={{ fontSize: 13, marginBottom: 10 }}
                    >
                        {released && (
                            <span style={{ color: 'var(--vesper-blue)' }}>
                                {released}
                            </span>
                        )}
                        {released && runtime && <Bullet />}
                        {runtime && <span>{runtime}</span>}
                    </div>
                    {ep.overview && (
                        <p
                            className="font-sans"
                            style={{
                                fontSize: 'clamp(13px, 0.95vw, 15px)',
                                lineHeight: 1.55,
                                color: 'var(--vesper-text-2)',
                                display: '-webkit-box',
                                WebkitLineClamp: open ? 99 : 3,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {ep.overview}
                        </p>
                    )}
                </div>
            </button>

            {/* Inline streams panel */}
            {open && (
                <div
                    data-testid={`episode-streams-${ep.season}-${ep.episode}`}
                    style={{
                        padding:
                            'clamp(0px, 0.9vw, 18px) clamp(12px, 0.9vw, 18px) clamp(16px, 1.2vw, 22px) clamp(208px, 19vw, 313px)',
                    }}
                >
                    {isLoading ? (
                        <div
                            className="flex items-center gap-3"
                            style={{ color: 'var(--vesper-text-2)', fontSize: 14 }}
                        >
                            <Loader2 className="vesper-spin" size={16} />
                            Searching streams for S{ep.season}E{ep.episode}…
                        </div>
                    ) : !data ? null : data.streams.length === 0 ? (
                        <div
                            className="rounded-xl p-4"
                            style={{
                                background: 'rgba(255,255,255,0.03)',
                                border: '1px solid rgba(255,255,255,0.06)',
                                color: 'var(--vesper-text-2)',
                                fontSize: 14,
                            }}
                        >
                            No streams available for this episode from your
                            installed addons.
                        </div>
                    ) : (
                        <ul
                            className="flex flex-col"
                            style={{ gap: 12 }}
                            data-stream-list="true"
                        >
                            {data.streams.slice(0, 30).map((s, i) => {
                                const mode = streamMode(s);
                                const ModeIcon =
                                    mode === 'direct'
                                        ? Play
                                        : mode === 'external'
                                        ? ExternalLink
                                        : mode === 'torrent'
                                        ? Magnet
                                        : Info;
                                const accent =
                                    mode === 'direct'
                                        ? 'var(--vesper-blue)'
                                        : mode === 'external'
                                        ? '#a8e3ff'
                                        : mode === 'torrent'
                                        ? '#ffd28a'
                                        : 'var(--vesper-text-3)';
                                const rawLabel =
                                    s.title || s.name || s._addon_name || 'Stream';
                                const labelLines = rawLabel.split('\n');
                                const titleLine = labelLines[0];
                                const metaLines = labelLines.slice(1)
                                    .map((l) => l.trim())
                                    .filter(Boolean);
                                const chips = [];
                                const SEED = /👤\s*(\d+[\d.,]*)/u;
                                const SIZE = /💾\s*([^\s][^⚙⚡]+?)(?=\s+[⚙⚡]|$)/u;
                                const TRACKER = /⚙\s*([^\s][^👤💾⚡]+?)$/u;
                                const LANG_LINE = /^([A-Z]{2}(\s*\/\s*[A-Z]{2})+)$/i;
                                for (const ml of metaLines) {
                                    const seed = ml.match(SEED);
                                    const size = ml.match(SIZE);
                                    const trk = ml.match(TRACKER);
                                    const lang = ml.match(LANG_LINE);
                                    if (seed) chips.push({ k: 'seed', v: `${seed[1]} seeders` });
                                    if (size) chips.push({ k: 'size', v: size[1].trim() });
                                    if (trk) chips.push({ k: 'trk', v: trk[1].trim() });
                                    if (lang) chips.push({ k: 'lang', v: lang[1].toUpperCase() });
                                }
                                const isCopied =
                                    mode === 'torrent' && copied === s.infoHash;
                                const badge = qualityBadge(s);
                                const tags = qualityTags(s);
                                return (
                                    <li
                                        key={i}
                                        className="flex items-stretch gap-2"
                                    >
                                        <button
                                            data-testid={`ep-${ep.season}-${ep.episode}-stream-${i}`}
                                            data-focusable="true"
                                            data-focus-style="pill"
                                            tabIndex={0}
                                            onClick={() => playStream(s, ep)}
                                            className="flex-1 text-left flex items-start gap-4"
                                            style={{
                                                padding: '16px 20px',
                                                borderRadius: 14,
                                                background:
                                                    'rgba(13,18,28,0.78)',
                                                border:
                                                    '1px solid rgba(255,255,255,0.06)',
                                                boxShadow:
                                                    '0 6px 18px rgba(0,0,0,0.28)',
                                            }}
                                        >
                                            <span
                                                className="flex flex-col items-center justify-center shrink-0"
                                                style={{
                                                    width: badge ? 60 : 40,
                                                    minHeight: 52,
                                                    borderRadius: 12,
                                                    background: badge
                                                        ? toneColors[badge.tone].bg
                                                        : `${accent.startsWith('#') ? accent : 'rgba(var(--vesper-blue-rgb),1)'}22`,
                                                    color: badge
                                                        ? toneColors[badge.tone].fg
                                                        : accent,
                                                    border: badge
                                                        ? `1px solid ${toneColors[badge.tone].border}`
                                                        : 'none',
                                                    marginTop: 2,
                                                    gap: 3,
                                                    padding: '6px 4px',
                                                }}
                                            >
                                                {badge ? (
                                                    <span
                                                        style={{
                                                            fontFamily:
                                                                'var(--theme-font-display, "Geist", system-ui, sans-serif)',
                                                            fontSize:
                                                                badge.label.length <= 3 ? 16 : 12,
                                                            fontWeight: 800,
                                                            letterSpacing: '-0.02em',
                                                            lineHeight: 1,
                                                        }}
                                                    >
                                                        {badge.label}
                                                    </span>
                                                ) : (
                                                    <ModeIcon
                                                        size={16}
                                                        fill={
                                                            mode === 'direct'
                                                                ? 'currentColor'
                                                                : 'none'
                                                        }
                                                    />
                                                )}
                                            </span>
                                            <div className="min-w-0 flex-1">
                                                <div
                                                    style={{
                                                        fontFamily:
                                                            'var(--theme-font-body, "Geist", system-ui, sans-serif)',
                                                        fontSize: 14,
                                                        fontWeight: 500,
                                                        lineHeight: 1.35,
                                                        color: 'var(--vesper-text)',
                                                        wordBreak: 'break-word',
                                                        display: '-webkit-box',
                                                        WebkitBoxOrient: 'vertical',
                                                        WebkitLineClamp: 2,
                                                        overflow: 'hidden',
                                                    }}
                                                >
                                                    {titleLine}
                                                </div>

                                                {tags.length > 0 && (
                                                    <div
                                                        className="flex flex-wrap items-center"
                                                        style={{
                                                            gap: 6,
                                                            marginTop: 10,
                                                        }}
                                                    >
                                                        {tags.map((t, ti) => {
                                                            const c = toneColors[t.tone];
                                                            return (
                                                                <span
                                                                    key={ti}
                                                                    className="vesper-mono"
                                                                    style={{
                                                                        fontSize: 10,
                                                                        fontWeight: 700,
                                                                        letterSpacing: '0.1em',
                                                                        padding: '3px 8px',
                                                                        borderRadius: 4,
                                                                        background: c.bg,
                                                                        color: c.fg,
                                                                        border: `1px solid ${c.border}`,
                                                                        whiteSpace: 'nowrap',
                                                                    }}
                                                                >
                                                                    {t.label}
                                                                </span>
                                                            );
                                                        })}
                                                    </div>
                                                )}

                                                {chips.length > 0 && (
                                                    <div
                                                        className="flex flex-wrap items-center"
                                                        style={{
                                                            gap: 8,
                                                            marginTop: 10,
                                                        }}
                                                    >
                                                        {chips.map((c, ci) => (
                                                            <span
                                                                key={ci}
                                                                className="vesper-mono"
                                                                style={{
                                                                    fontSize: 11,
                                                                    letterSpacing: '0.06em',
                                                                    padding: '4px 10px',
                                                                    borderRadius: 999,
                                                                    background:
                                                                        c.k === 'seed'
                                                                            ? 'rgba(var(--vesper-blue-rgb),0.12)'
                                                                            : c.k === 'size'
                                                                            ? 'rgba(255,210,138,0.12)'
                                                                            : c.k === 'lang'
                                                                            ? 'rgba(255,255,255,0.06)'
                                                                            : 'rgba(255,255,255,0.05)',
                                                                    color:
                                                                        c.k === 'seed'
                                                                            ? 'var(--vesper-blue)'
                                                                            : c.k === 'size'
                                                                            ? '#ffd28a'
                                                                            : 'var(--vesper-text-2)',
                                                                    border: '1px solid rgba(255,255,255,0.05)',
                                                                    whiteSpace: 'nowrap',
                                                                }}
                                                            >
                                                                {c.v}
                                                            </span>
                                                        ))}
                                                    </div>
                                                )}

                                                <div
                                                    className="vesper-mono"
                                                    style={{
                                                        fontSize: 10,
                                                        color: 'var(--vesper-text-3)',
                                                        letterSpacing: '0.18em',
                                                        textTransform: 'uppercase',
                                                        marginTop: chips.length > 0 ? 10 : 8,
                                                    }}
                                                >
                                                    <span style={{ color: accent }}>
                                                        {mode}
                                                    </span>
                                                    {' · '}
                                                    {s._addon_name || 'addon'}
                                                    {s.behaviorHints?.bingeGroup
                                                        ? ` · ${s.behaviorHints.bingeGroup
                                                            .replace(/torrentio\|?/i, '')
                                                            .split('|')
                                                            .filter(Boolean)
                                                            .join(' · ')}`
                                                        : ''}
                                                </div>
                                            </div>
                                        </button>
                                        {mode === 'torrent' && (
                                            <button
                                                data-focusable="true"
                                                data-focus-style="quiet"
                                                tabIndex={0}
                                                onClick={() => copyMagnet(s, ep)}
                                                aria-label="Copy magnet"
                                                className="shrink-0 flex items-center justify-center"
                                                style={{
                                                    width: 50,
                                                    borderRadius: 14,
                                                    background:
                                                        'rgba(13,18,28,0.78)',
                                                    color: isCopied
                                                        ? 'var(--vesper-blue)'
                                                        : 'var(--vesper-text-2)',
                                                    border:
                                                        '1px solid rgba(255,255,255,0.06)',
                                                }}
                                            >
                                                <Copy size={14} />
                                            </button>
                                        )}
                                    </li>
                                );
                            })}
                            {data.streams.length > 30 && (
                                <li
                                    className="vesper-mono"
                                    style={{
                                        fontSize: 11,
                                        color: 'var(--vesper-text-3)',
                                        letterSpacing: '0.18em',
                                        textTransform: 'uppercase',
                                        marginTop: 8,
                                    }}
                                >
                                    + {data.streams.length - 30} more streams
                                    not shown
                                </li>
                            )}
                        </ul>
                    )}
                </div>
            )}
        </li>
    );
}

const Bullet = () => (
    <span
        className="inline-block w-1 h-1 rounded-full"
        style={{ background: 'rgba(255,255,255,0.32)' }}
    />
);
