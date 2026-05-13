import React, { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import {
    Play,
    ArrowLeft,
    Loader2,
    ExternalLink,
    Copy,
    Magnet,
    Info,
    Plus,
    Check,
} from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import SeriesEpisodes from '@/components/SeriesEpisodes';
import Host from '@/lib/host';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { API, Vesper } from '@/lib/api';
import { qualityBadge, qualityTags, toneColors } from '@/lib/streamMeta';
import { getAutoplay1080p } from '@/lib/prefs';
import { isKidsActive } from '@/lib/profiles';
import * as cw from '@/lib/continueWatching';
import {
    isInLibrary,
    addToLibrary,
    removeFromLibrary,
} from '@/lib/library';

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

export default function Detail() {
    useSpatialFocus();
    const { type, id } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const resumeRequested = useMemo(
        () => new URLSearchParams(location.search).get('resume') === '1',
        [location.search]
    );
    // ?autoplay=1 lands here from the hero's Play button — when the
    // Autoplay 1080p setting is on, we pick the first 1080p direct
    // stream the moment the streams list resolves and start playback
    // automatically, skipping the source picker.
    const autoplayRequested = useMemo(
        () => new URLSearchParams(location.search).get('autoplay') === '1',
        [location.search]
    );

    const [meta, setMeta] = useState(null);
    const [streams, setStreams] = useState([]);
    const [diagnostics, setDiagnostics] = useState([]);
    const [loading, setLoading] = useState(true);
    const [streamLoading, setStreamLoading] = useState(true);
    const [err, setErr] = useState(null);
    const [copied, setCopied] = useState(null);

    useEffect(() => {
        let cancel = false;
        (async () => {
            setLoading(true);
            setErr(null);
            try {
                const m = await Vesper.getMeta(type, id);
                if (!cancel) setMeta(m?.data?.meta || null);
            } catch (e) {
                if (!cancel) setErr(e?.response?.data?.detail || 'Metadata unavailable');
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, id]);

    useEffect(() => {
        if (type === 'series') {
            // Series streams are fetched per-episode inside <SeriesEpisodes>
            setStreamLoading(false);
            return;
        }
        let cancel = false;
        (async () => {
            setStreamLoading(true);
            try {
                const s = await Vesper.getStreams(type, id);
                if (!cancel) {
                    setStreams(s?.streams || []);
                    setDiagnostics(s?.diagnostics || []);
                }
            } catch {
                if (!cancel) {
                    setStreams([]);
                    setDiagnostics([]);
                }
            } finally {
                if (!cancel) setStreamLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, id]);

    // ---------- AUTOPLAY 1080p — derived state ----------
    // `autoplayEnabled` reflects the live preference.  We read it
    // through state so a toggle from the side-nav re-renders Detail
    // (cross-component pref sync via storage event, see below).
    const [autoplayEnabled, setAutoplayEnabledState] = useState(
        isKidsActive() || getAutoplay1080p()
    );
    useEffect(() => {
        const onStorage = () =>
            setAutoplayEnabledState(isKidsActive() || getAutoplay1080p());
        window.addEventListener('storage', onStorage);
        // Also poll once per second — the side-nav toggle writes to
        // localStorage in the SAME window which doesn't fire `storage`
        // (that event only fires for OTHER windows).
        const i = setInterval(onStorage, 1000);
        return () => {
            window.removeEventListener('storage', onStorage);
            clearInterval(i);
        };
    }, []);

    // Pick the best 1080p candidate from the resolved streams list.
    // Prefer direct mode; fall back to any 1080p stream.  Null means
    // "no 1080p available → fall back to manual picker".
    const autoplayCandidate = useMemo(() => {
        if (type !== 'movie') return null;
        if (!streams || streams.length === 0) return null;
        return (
            streams.find(
                (s) =>
                    streamMode(s) === 'direct' &&
                    qualityBadge(s)?.label === '1080p'
            ) ||
            streams.find((s) => qualityBadge(s)?.label === '1080p') ||
            null
        );
    }, [streams, type]);

    // Manual trigger for the on-page Play button.
    const triggerAutoplay = () => {
        if (autoplayCandidate) playStream(autoplayCandidate);
    };

    // ---------- AUTOPLAY (via ?autoplay=1 URL) ----------
    // Fires automatically when the user arrived via hero Play / a
    // CW resume etc.  Falls back silently to the manual picker if
    // no 1080p candidate is found.
    const autoplayFiredRef = React.useRef(false);
    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!autoplayRequested) return;
        if (type === 'series') return; // series uses per-episode flow
        if (streamLoading) return;
        if (!getAutoplay1080p()) return;
        if (!autoplayCandidate) return;
        autoplayFiredRef.current = true;
        const t = setTimeout(() => playStream(autoplayCandidate), 0);
        return () => clearTimeout(t);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [streams, streamLoading, autoplayRequested, type, autoplayCandidate]);

    const playStream = async (stream) => {
        const mode = streamMode(stream);
        if (mode === 'direct') {
            // Look up any previously-saved position so we can resume.
            const cwList = cw.getEntries();
            const existing = cwList.find((e) => e.id === id);
            const startAtMs =
                resumeRequested && existing?.positionMs
                    ? existing.positionMs
                    : 0;
            // Native libVLC Activity — handles every codec Stremio does.
            // Fetch a default English subtitle URL up front so the
            // native player can attach it on launch.
            let subtitleUrl = '';
            try {
                const r = await fetch(
                    `${API}/subtitles/${type}/${encodeURIComponent(id)}`,
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
                /* swallow — player still works without subs */
            }
            // Track / refresh the Continue Watching entry up front so
            // the show appears on Home even before the native player
            // reports any progress.
            cw.upsert({
                id,
                type,
                title: meta?.name || '',
                backdrop: meta?.background || meta?.poster || '',
                poster: meta?.poster || '',
                synopsis: meta?.description || '',
                year: meta?.releaseInfo || meta?.year || '',
                rating: meta?.imdbRating || '',
                runtime: meta?.runtime || '',
                genres: meta?.genres || [],
                streamUrl: stream.url,
                subtitleUrl,
                positionMs: existing?.positionMs || 0,
                durationMs: existing?.durationMs || 0,
                route: `/title/${type}/${id}`,
            });
            if (
                Host.playVideo({
                    url: stream.url,
                    title: meta?.name || '',
                    type: type,
                    subtitleUrl,
                    poster: meta?.poster || '',
                    backdrop: meta?.background || meta?.poster || '',
                    synopsis: meta?.description || '',
                    year: meta?.releaseInfo || meta?.year || '',
                    rating: meta?.imdbRating || '',
                    runtime: meta?.runtime || '',
                    genres: meta?.genres || [],
                    startAtMs: startAtMs,
                    cwId: id,
                })
            ) {
                return;
            }
            navigate(
                `/play?url=${encodeURIComponent(
                    stream.url
                )}&title=${encodeURIComponent(meta?.name || '')}&type=${encodeURIComponent(
                    type
                )}&imdbId=${encodeURIComponent(id)}`
            );
        } else if (mode === 'external') {
            try {
                window.open(stream.externalUrl, '_blank', 'noopener,noreferrer');
            } catch {
                /* popup blocked */
            }
        } else if (mode === 'torrent') {
            const magnet = buildMagnet(stream, meta?.name);
            if (magnet) {
                try {
                    window.location.href = magnet;
                } catch {
                    /* no handler installed for magnet: */
                }
            }
        }
    };

    const copyMagnet = async (stream) => {
        const magnet = buildMagnet(stream, meta?.name);
        if (!magnet) return;
        try {
            await navigator.clipboard.writeText(magnet);
            setCopied(stream.infoHash);
            setTimeout(() => setCopied(null), 1800);
        } catch {
            /* no clipboard permission — show prompt fallback */
            window.prompt('Copy this magnet link:', magnet);
        }
    };

    if (loading) {
        return (
            <CenterMsg>
                <Loader2 className="vesper-spin" size={28} /> Loading metadata…
            </CenterMsg>
        );
    }

    if (err || !meta) {
        return (
            <CenterMsg>
                <div style={{ color: '#ffb5b5' }}>{err || 'Not found'}</div>
                <button
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    className="mt-6 h-12 px-5 rounded-full"
                    style={{
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.12)',
                    }}
                >
                    Back
                </button>
            </CenterMsg>
        );
    }

    const playable = streams.filter(
        (s) => streamMode(s) === 'direct' || streamMode(s) === 'external'
    );
    const torrentCount = streams.filter(
        (s) => streamMode(s) === 'torrent'
    ).length;

    return (
        <div
            data-testid="detail-page"
            data-kids-theme={isKidsActive() ? '1' : undefined}
            className={`relative w-screen h-[100dvh] min-h-screen overflow-hidden ${
                isKidsActive() ? 'vesper-kids-root' : ''
            }`}
            style={isKidsActive() ? { background: 'var(--vesper-bg-0)' } : undefined}
        >
            <FullscreenButton />

            {/* Backdrop */}
            <div
                className="absolute inset-0"
                style={{
                    backgroundImage: `url(${meta.background || meta.poster || ''})`,
                    backgroundSize: 'cover',
                    backgroundPosition: 'center',
                    filter: 'brightness(0.6) saturate(1.1)',
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(180deg,
                        rgba(6,8,15,0.55) 0%,
                        rgba(6,8,15,0.4) 30%,
                        rgba(6,8,15,0.85) 70%,
                        var(--vesper-bg-0) 100%)`,
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(90deg,
                        rgba(6,8,15,0.92) 0%,
                        rgba(6,8,15,0.65) 35%,
                        rgba(6,8,15,0.1) 70%,
                        rgba(6,8,15,0) 100%)`,
                }}
            />

            <main
                className="relative z-10 w-full h-full overflow-y-auto"
                style={{ padding: '64px 80px 80px 80px' }}
            >
                <div className="flex items-center gap-3 mb-8">
                    <button
                        data-testid="back-button"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={() => navigate(-1)}
                        className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
                        style={{
                            background: 'rgba(17,24,39,0.6)',
                            color: 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            fontSize: 13,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        <ArrowLeft size={16} /> Back
                    </button>
                    <LibraryToggleButton meta={meta} type={type} id={id} />
                </div>

                <div className="max-w-[60vw] vesper-fade-up">
                    {meta.imdb_id && (
                        <div className="vesper-eyebrow mb-4">
                            {type} · {meta.imdb_id}
                        </div>
                    )}
                    <h1
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(56px, 6vw, 92px)',
                            letterSpacing: '-0.035em',
                        }}
                    >
                        {meta.name}
                    </h1>

                    <div
                        className="flex items-center gap-3 mt-4 vesper-meta flex-wrap"
                        style={{ fontSize: 18 }}
                    >
                        {meta.releaseInfo && (
                            <span style={{ color: 'var(--vesper-blue)' }}>
                                {meta.releaseInfo}
                            </span>
                        )}
                        {meta.runtime && <Bullet />}
                        {meta.runtime && <span>{meta.runtime}</span>}
                        {meta.imdbRating && <Bullet />}
                        {meta.imdbRating && <span>★ {meta.imdbRating}</span>}
                        {meta.genres?.length > 0 && <Bullet />}
                        {meta.genres?.length > 0 && (
                            <span>{meta.genres.slice(0, 3).join(' · ')}</span>
                        )}
                    </div>

                    {meta.description && (
                        <p
                            className="mt-6 max-w-[58ch]"
                            style={{
                                fontSize: 18,
                                lineHeight: 1.6,
                                color: 'var(--vesper-text-2)',
                            }}
                        >
                            {meta.description}
                        </p>
                    )}

                    {/* AUTOPLAY MODE — when on, show a big Play
                        button that fires the same ?autoplay=1 flow
                        (auto-pick first 1080p direct stream).  The
                        streams list stays hidden until the user
                        either turns Autoplay off (from the sidebar)
                        or no 1080p stream is available, in which
                        case the streams list reappears as a manual
                        fallback. */}
                    {type === 'movie' && autoplayEnabled && (
                        <div className="mt-8 flex items-center gap-3 flex-wrap">
                            <button
                                data-testid="detail-play-autoplay"
                                data-focusable="true"
                                data-focus-style="pill"
                                data-initial-focus="true"
                                tabIndex={0}
                                onClick={triggerAutoplay}
                                disabled={
                                    streamLoading || autoplayCandidate === null
                                }
                                className="flex items-center gap-2.5 rounded-full font-sans font-semibold"
                                style={{
                                    height: 'clamp(50px, 4vw, 60px)',
                                    paddingLeft: 'clamp(24px, 1.8vw, 32px)',
                                    paddingRight: 'clamp(28px, 2.2vw, 38px)',
                                    fontSize: 'clamp(15px, 1.15vw, 18px)',
                                    background:
                                        streamLoading ||
                                        autoplayCandidate === null
                                            ? 'rgba(255,255,255,0.10)'
                                            : 'var(--vesper-blue)',
                                    color:
                                        streamLoading ||
                                        autoplayCandidate === null
                                            ? 'var(--vesper-text-2)'
                                            : 'var(--vesper-bg-0)',
                                    opacity:
                                        streamLoading ||
                                        autoplayCandidate === null
                                            ? 0.7
                                            : 1,
                                }}
                            >
                                {streamLoading ? (
                                    <>
                                        <Loader2
                                            className="vesper-spin"
                                            size={18}
                                        />
                                        Finding 1080p…
                                    </>
                                ) : autoplayCandidate ? (
                                    <>
                                        <Play size={18} fill="currentColor" />
                                        Play 1080p
                                    </>
                                ) : (
                                    <>
                                        <Play size={18} />
                                        No 1080p stream found
                                    </>
                                )}
                            </button>
                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 11,
                                    color: 'var(--vesper-text-3)',
                                    letterSpacing: '0.18em',
                                    textTransform: 'uppercase',
                                    paddingLeft: 8,
                                }}
                            >
                                Autoplay ON · turn off in side menu for picker
                            </div>
                        </div>
                    )}

                    {/* Stream picker (movies) / Episode browser (series) */}
                    {type === 'series' ? (
                        <SeriesEpisodes meta={meta} parentId={id} />
                    ) : autoplayEnabled && autoplayCandidate ? (
                        // Autoplay is on AND we have a 1080p candidate
                        // → hide the manual stream picker entirely.
                        null
                    ) : (
                    <section
                        data-testid="stream-picker"
                        className="mt-10"
                    >
                        <h3
                            className="vesper-display mb-5"
                            style={{ fontSize: 26, letterSpacing: '-0.02em' }}
                        >
                            Available streams
                            {!streamLoading && (
                                <span
                                    className="ml-3 vesper-mono"
                                    style={{
                                        fontSize: 12,
                                        color: 'var(--vesper-text-3)',
                                        letterSpacing: '0.22em',
                                        textTransform: 'uppercase',
                                    }}
                                >
                                    {streams.length > 0
                                        ? `${streams.length} found · ${playable.length} playable`
                                        : '0 found'}
                                </span>
                            )}
                        </h3>

                        {streamLoading ? (
                            <div
                                className="flex items-center gap-3"
                                style={{ color: 'var(--vesper-text-2)' }}
                            >
                                <Loader2 className="vesper-spin" size={18} />
                                Searching installed addons…
                            </div>
                        ) : streams.length === 0 ? (
                            <div className="vesper-glass rounded-2xl p-6">
                                <div
                                    style={{
                                        color: 'var(--vesper-text)',
                                        fontSize: 17,
                                        fontWeight: 500,
                                    }}
                                >
                                    No streams returned by your installed
                                    addons.
                                </div>
                                {diagnostics.length > 0 && (
                                    <ul
                                        className="mt-4 space-y-2"
                                        data-testid="stream-diagnostics"
                                    >
                                        {diagnostics.map((d) => (
                                            <li
                                                key={d.addon.id}
                                                className="flex items-center justify-between gap-4 vesper-mono"
                                                style={{
                                                    fontSize: 13,
                                                    color: 'var(--vesper-text-2)',
                                                    letterSpacing: '0.06em',
                                                }}
                                            >
                                                <span>{d.addon.name}</span>
                                                <span
                                                    style={{
                                                        color: d.error
                                                            ? '#ffb5b5'
                                                            : d.count > 0
                                                            ? 'var(--vesper-blue-bright)'
                                                            : 'var(--vesper-text-3)',
                                                    }}
                                                >
                                                    {d.error
                                                        ? `failed · ${d.error}`
                                                        : d.skipped
                                                        ? `skipped · ${d.skipped}`
                                                        : `${d.count} streams`}
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                )}
                                <div
                                    className="mt-5"
                                    style={{
                                        fontSize: 14,
                                        color: 'var(--vesper-text-3)',
                                    }}
                                >
                                    Tip: many stream addons return zero
                                    results for unreleased or very recent
                                    titles. Try a well-known catalogue movie
                                    to verify.
                                </div>
                            </div>
                        ) : (
                            <>
                                {torrentCount > 0 && (
                                    <div
                                        data-testid="torrent-banner"
                                        className="vesper-glass rounded-xl px-5 py-4 mb-4 flex items-start gap-3"
                                        style={{
                                            borderColor:
                                                'rgba(255,200,80,0.25)',
                                        }}
                                    >
                                        <Info
                                            size={18}
                                            strokeWidth={1.6}
                                            style={{
                                                color: '#ffd28a',
                                                marginTop: 3,
                                                flexShrink: 0,
                                            }}
                                        />
                                        <div
                                            style={{
                                                fontSize: 14,
                                                lineHeight: 1.55,
                                                color: 'var(--vesper-text-2)',
                                            }}
                                        >
                                            <strong
                                                style={{
                                                    color: 'var(--vesper-text)',
                                                }}
                                            >
                                                {torrentCount} torrent
                                                {torrentCount === 1 ? '' : 's'}{' '}
                                                returned.
                                            </strong>{' '}
                                            Torrent streams (info-hash) can&apos;t
                                            be played directly in this client.
                                            Use the magnet button to copy /
                                            open in an external app, or
                                            configure a debrid service
                                            (Real-Debrid, AllDebrid,
                                            Premiumize) inside your Torrentio
                                            URL — Torrentio will then return
                                            direct HTTP streams that play
                                            here.
                                        </div>
                                    </div>
                                )}

                                <ul
                                    className="flex flex-col"
                                    style={{ gap: 14 }}
                                    data-testid="stream-list"
                                >
                                    {streams.slice(0, 60).map((s, i) => {
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
                                        const isCopied =
                                            mode === 'torrent' &&
                                            copied === s.infoHash;
                                        const badge = qualityBadge(s);
                                        const tags = qualityTags(s);
                                        const rawLabel =
                                            s.title || s.name || s._addon_name || 'Stream';
                                        // Split Torrentio's title field:
                                        // line 1 = filename, lines 2+ = metadata
                                        // (seeders / size / source / langs).
                                        // We only want the filename in the heading
                                        // so multi-line file titles stay clean.
                                        const labelLines = rawLabel.split('\n');
                                        const titleLine = labelLines[0];
                                        const metaLines = labelLines.slice(1)
                                            .map((l) => l.trim())
                                            .filter(Boolean);
                                        // Pull seeders/size/tracker out of the
                                        // meta lines (Torrentio puts them on
                                        // line 2 as "👤 24  💾 5.38 GB  ⚙ ThePirateBay").
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
                                        return (
                                            <li
                                                key={i}
                                                className="flex items-stretch gap-2"
                                            >
                                                <button
                                                    data-testid={`stream-${i}`}
                                                    data-focusable="true"
                                                    data-focus-style="pill"
                                                    tabIndex={0}
                                                    onClick={() =>
                                                        playStream(s)
                                                    }
                                                    className="flex-1 text-left flex items-start gap-4"
                                                    style={{
                                                        padding: '18px 22px',
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
                                                            width: badge ? 64 : 44,
                                                            minHeight: 56,
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
                                                            gap: 4,
                                                            padding: '8px 4px',
                                                        }}
                                                    >
                                                        {badge ? (
                                                            <span
                                                                style={{
                                                                    fontFamily:
                                                                        'var(--theme-font-display, "Geist", system-ui, sans-serif)',
                                                                    fontSize:
                                                                        badge.label.length <= 3 ? 18 : 13,
                                                                    fontWeight: 800,
                                                                    letterSpacing: '-0.02em',
                                                                    lineHeight: 1,
                                                                }}
                                                            >
                                                                {badge.label}
                                                            </span>
                                                        ) : (
                                                            <ModeIcon
                                                                size={18}
                                                                fill={
                                                                    mode === 'direct'
                                                                        ? 'currentColor'
                                                                        : 'none'
                                                                }
                                                            />
                                                        )}
                                                        <ModeIcon
                                                            size={badge ? 10 : 0}
                                                            style={{
                                                                opacity: badge ? 0.55 : 0,
                                                                display: badge ? 'block' : 'none',
                                                            }}
                                                            fill={mode === 'direct' ? 'currentColor' : 'none'}
                                                        />
                                                    </span>

                                                    <div className="min-w-0 flex-1">
                                                        <div
                                                            style={{
                                                                fontFamily:
                                                                    'var(--theme-font-body, "Geist", system-ui, sans-serif)',
                                                                fontSize: 15,
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

                                                        {/* Quality tag pills (HDR / DV / Atmos / REMUX) */}
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

                                                        {/* Metadata chip row */}
                                                        {chips.length > 0 && (
                                                            <div
                                                                className="flex flex-wrap items-center"
                                                                style={{
                                                                    gap: 8,
                                                                    marginTop: 12,
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

                                                        {/* Bottom tagline */}
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
                                                        data-testid={`copy-magnet-${i}`}
                                                        data-focusable="true"
                                                        data-focus-style="quiet"
                                                        tabIndex={0}
                                                        onClick={() =>
                                                            copyMagnet(s)
                                                        }
                                                        aria-label="Copy magnet"
                                                        title="Copy magnet link"
                                                        className="shrink-0 flex items-center justify-center"
                                                        style={{
                                                            width: 52,
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
                                                        <Copy size={16} />
                                                    </button>
                                                )}
                                            </li>
                                        );
                                    })}
                                </ul>

                                {streams.length > 60 && (
                                    <div
                                        className="vesper-mono mt-4"
                                        style={{
                                            fontSize: 12,
                                            color: 'var(--vesper-text-3)',
                                            letterSpacing: '0.18em',
                                            textTransform: 'uppercase',
                                        }}
                                    >
                                        + {streams.length - 60} more streams
                                        not shown
                                    </div>
                                )}
                            </>
                        )}
                    </section>
                    )}
                </div>
            </main>
        </div>
    );
}

const Bullet = () => (
    <span
        className="inline-block w-1 h-1 rounded-full"
        style={{ background: 'rgba(255,255,255,0.32)' }}
    />
);

/**
 * "Add to My List" toggle.  Reads/writes the per-profile library
 * via `lib/library`.  Live-syncs with the `vesper:library-change`
 * event so add/remove anywhere flips the icon state here too.
 *
 * Theme-accented: the active (in-library) state uses the active
 * theme's bright accent, the unset state is a muted glass pill.
 */
function LibraryToggleButton({ meta, type, id }) {
    const [inList, setInList] = React.useState(() => isInLibrary(id));

    React.useEffect(() => {
        const sync = () => setInList(isInLibrary(id));
        window.addEventListener('vesper:library-change', sync);
        sync();
        return () => window.removeEventListener('vesper:library-change', sync);
    }, [id]);

    if (!meta) return null;

    const onToggle = () => {
        if (inList) {
            removeFromLibrary(id);
        } else {
            addToLibrary(id, {
                type: type === 'series' ? 'series' : 'movie',
                meta: {
                    name: meta.name,
                    poster: meta.poster,
                    year: meta.releaseInfo || meta.year,
                },
            });
        }
    };

    return (
        <button
            data-testid={inList ? 'library-remove' : 'library-add'}
            data-focusable="true"
            data-focus-style="pill"
            tabIndex={0}
            onClick={onToggle}
            className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
            style={{
                background: inList
                    ? 'rgba(var(--vesper-blue-rgb), 0.18)'
                    : 'rgba(17,24,39,0.6)',
                color: inList
                    ? 'var(--vesper-blue-bright)'
                    : 'var(--vesper-text-2)',
                border: inList
                    ? '1px solid rgba(var(--vesper-blue-rgb), 0.55)'
                    : '1px solid rgba(255,255,255,0.12)',
                fontSize: 13,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
            }}
        >
            {inList ? <Check size={16} strokeWidth={2.4} /> : <Plus size={16} strokeWidth={2.2} />}
            {inList ? 'In My List' : 'Add to My List'}
        </button>
    );
}

const CenterMsg = ({ children }) => (
    <div
        className="w-screen h-[100dvh] min-h-screen flex flex-col items-center justify-center"
        style={{ color: 'var(--vesper-text-2)', fontSize: 18, gap: 12 }}
    >
        {children}
    </div>
);
