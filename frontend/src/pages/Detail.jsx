import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Play, ArrowLeft, Loader2, Tv } from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { Vesper } from '@/lib/api';

export default function Detail() {
    useSpatialFocus();
    const { type, id } = useParams();
    const navigate = useNavigate();

    const [meta, setMeta] = useState(null);
    const [streams, setStreams] = useState([]);
    const [loading, setLoading] = useState(true);
    const [streamLoading, setStreamLoading] = useState(true);
    const [err, setErr] = useState(null);

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
        let cancel = false;
        (async () => {
            setStreamLoading(true);
            try {
                const s = await Vesper.getStreams(type, id);
                if (!cancel) setStreams(s?.streams || []);
            } catch {
                if (!cancel) setStreams([]);
            } finally {
                if (!cancel) setStreamLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, id]);

    const playStream = (stream) => {
        const url = stream.url;
        if (!url) return;
        navigate(
            `/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(
                meta?.name || ''
            )}`
        );
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

    const playable = streams.filter((s) => !!s.url);

    return (
        <div data-testid="detail-page" className="relative w-screen h-screen overflow-hidden">
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

                    {/* Stream picker */}
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
                                    {playable.length} sources
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
                        ) : playable.length === 0 ? (
                            <div
                                className="vesper-glass rounded-2xl p-6"
                                style={{ color: 'var(--vesper-text-2)' }}
                            >
                                No playable streams yet — install a stream addon (Torrentio,
                                etc) on the Sources screen.
                            </div>
                        ) : (
                            <ul className="flex flex-col gap-3">
                                {playable.slice(0, 12).map((s, i) => (
                                    <li key={i}>
                                        <button
                                            data-testid={`stream-${i}`}
                                            data-focusable="true"
                                            data-focus-style="pill"
                                            tabIndex={0}
                                            onClick={() => playStream(s)}
                                            className="w-full text-left flex items-center gap-4 px-5 h-16 rounded-xl"
                                            style={{
                                                background: 'rgba(17,24,39,0.7)',
                                                border: '1px solid rgba(255,255,255,0.08)',
                                            }}
                                        >
                                            <span
                                                className="flex items-center justify-center w-10 h-10 rounded-full shrink-0"
                                                style={{
                                                    background:
                                                        'rgba(93,200,255,0.15)',
                                                    color: 'var(--vesper-blue)',
                                                }}
                                            >
                                                <Play size={16} fill="currentColor" />
                                            </span>
                                            <div className="min-w-0 flex-1">
                                                <div
                                                    className="font-sans font-medium truncate"
                                                    style={{ fontSize: 17 }}
                                                >
                                                    {s.name || s.title || s._addon_name || 'Stream'}
                                                </div>
                                                {s.description && (
                                                    <div
                                                        className="vesper-mono truncate"
                                                        style={{
                                                            fontSize: 12,
                                                            color: 'var(--vesper-text-3)',
                                                            letterSpacing: '0.04em',
                                                        }}
                                                    >
                                                        {s.description}
                                                    </div>
                                                )}
                                            </div>
                                            <span
                                                className="vesper-eyebrow shrink-0"
                                                style={{ fontSize: 10 }}
                                            >
                                                {s._addon_name || 'addon'}
                                            </span>
                                        </button>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </section>
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
        className="w-screen h-screen flex flex-col items-center justify-center"
        style={{ color: 'var(--vesper-text-2)', fontSize: 18, gap: 12 }}
    >
        {children}
    </div>
);
