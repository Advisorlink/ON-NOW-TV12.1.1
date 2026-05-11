import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
    Play,
    ArrowLeft,
    Loader2,
    ExternalLink,
    Copy,
    Magnet,
    Info,
} from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import SeriesEpisodes from '@/components/SeriesEpisodes';
import Host from '@/lib/host';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { API, Vesper } from '@/lib/api';

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

    const playStream = async (stream) => {
        const mode = streamMode(stream);
        if (mode === 'direct') {
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
        <div data-testid="detail-page" className="relative w-screen h-[100dvh] min-h-screen overflow-hidden">
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
                <button
                    data-testid="back-button"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    className="flex items-center gap-2 h-11 px-5 rounded-full mb-8 vesper-mono"
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

                    {/* Stream picker (movies) / Episode browser (series) */}
                    {type === 'series' ? (
                        <SeriesEpisodes meta={meta} parentId={id} />
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
                                                        className="flex items-center justify-center shrink-0"
                                                        style={{
                                                            width: 40,
                                                            height: 40,
                                                            borderRadius: 999,
                                                            background: `${accent.startsWith('#') ? accent : 'rgba(93,200,255,1)'}22`,
                                                            color: accent,
                                                            marginTop: 4,
                                                        }}
                                                    >
                                                        <ModeIcon
                                                            size={16}
                                                            fill={
                                                                mode ===
                                                                'direct'
                                                                    ? 'currentColor'
                                                                    : 'none'
                                                            }
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

const CenterMsg = ({ children }) => (
    <div
        className="w-screen h-[100dvh] min-h-screen flex flex-col items-center justify-center"
        style={{ color: 'var(--vesper-text-2)', fontSize: 18, gap: 12 }}
    >
        {children}
    </div>
);
