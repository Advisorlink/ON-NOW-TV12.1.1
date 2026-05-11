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
import { qualityBadge, qualityTags, toneColors } from '@/lib/streamMeta';

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

export default function SeriesEpisodes({ meta, parentId }) {
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
        () => seasons[0]?.season ?? 1
    );
    useEffect(() => {
        // Reset when meta changes
        if (seasons.length && !seasons.find((s) => s.season === activeSeason)) {
            setActiveSeason(seasons[0].season);
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [seasons]);

    const currentSeason = seasons.find((s) => s.season === activeSeason);

    const [openEpisodeId, setOpenEpisodeId] = useState(null);
    const [episodeStreams, setEpisodeStreams] = useState({});
    const [loadingEpisodeId, setLoadingEpisodeId] = useState(null);
    const [copied, setCopied] = useState(null);

    const handleEpisodeClick = async (ep) => {
        if (openEpisodeId === ep.id) {
            setOpenEpisodeId(null);
            return;
        }
        setOpenEpisodeId(ep.id);
        if (episodeStreams[ep.id]) return;
        setLoadingEpisodeId(ep.id);
        try {
            const res = await Vesper.getStreams('series', ep.id);
            setEpisodeStreams((s) => ({
                ...s,
                [ep.id]: { streams: res?.streams || [], diagnostics: res?.diagnostics || [] },
            }));
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
        if (mode === 'direct') {
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
            if (
                Host.playVideo({
                    url: stream.url,
                    title,
                    type: 'series',
                    subtitleUrl,
                    // Use the series cover (not the episode thumbnail)
                    // on the native player's loading screen — the user
                    // wants the show identity, not the per-episode
                    // still frame, while the stream buffers.
                    poster: meta?.poster || '',
                    backdrop: meta?.background || meta?.poster || '',
                    synopsis: ep.overview || meta?.description || '',
                    year: ep.firstAired ? String(ep.firstAired).slice(0, 4) : (meta?.releaseInfo || ''),
                    rating: meta?.imdbRating || '',
                    runtime: ep.runtime || meta?.runtime || '',
                    genres: meta?.genres || [],
                })
            ) return;
            navigate(
                `/play?url=${encodeURIComponent(
                    stream.url
                )}&title=${encodeURIComponent(title)}&type=series&imdbId=${encodeURIComponent(ep.id)}`
            );
        } else if (mode === 'external') {
            try {
                window.open(stream.externalUrl, '_blank', 'noopener,noreferrer');
            } catch {
                /* popup blocked */
            }
        } else if (mode === 'torrent') {
            const magnet = buildMagnet(stream, `${meta?.name || ''} S${ep.season}E${ep.episode}`);
            if (magnet) {
                try {
                    window.location.href = magnet;
                } catch {
                    /* no handler */
                }
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
            {/* Season picker */}
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

            <div
                data-testid="season-picker"
                className="flex flex-wrap"
                style={{ gap: 'clamp(8px, 0.7vw, 12px)', marginBottom: 24 }}
            >
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
                            tabIndex={0}
                            onClick={() => setActiveSeason(season)}
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

            {/* Episode list */}
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

    return (
        <li
            ref={cardRef}
            className="rounded-2xl overflow-hidden"
            style={{
                background: open ? 'rgba(17,24,39,0.7)' : 'rgba(17,24,39,0.4)',
                border: open
                    ? '1px solid rgba(93,200,255,0.35)'
                    : '1px solid rgba(255,255,255,0.06)',
                transition: 'background-color 200ms ease, border-color 200ms ease',
            }}
        >
            <button
                data-testid={`episode-${ep.season}-${ep.episode}`}
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
                                    'linear-gradient(135deg, rgba(93,200,255,0.08) 0%, rgba(93,200,255,0) 100%)',
                                color: 'rgba(93,200,255,0.55)',
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
                                background: 'rgba(93,200,255,0.92)',
                                color: '#06080f',
                                opacity: open ? 1 : 0,
                                transform: open ? 'scale(1)' : 'scale(0.85)',
                                transition: 'opacity 200ms ease, transform 200ms ease',
                                boxShadow: '0 0 40px rgba(93,200,255,0.5)',
                            }}
                        >
                            <Play size={20} fill="currentColor" />
                        </span>
                    </div>
                </div>

                {/* Text */}
                <div className="min-w-0 flex-1 flex flex-col">
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
                        <ul className="flex flex-col" style={{ gap: 12 }}>
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
                                                        : `${accent.startsWith('#') ? accent : 'rgba(93,200,255,1)'}22`,
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
                                                                            ? 'rgba(93,200,255,0.12)'
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
