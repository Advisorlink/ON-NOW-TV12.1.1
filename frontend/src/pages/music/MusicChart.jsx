/**
 * MusicChart — the destination page for the Charts & Eras shelf
 * on the Music home screen.  Renders up to 100 tracks fetched
 * from the `/api/music/chart-preset/{id}` backend endpoint,
 * reusing the exact same track-row markup as MusicAlbum so
 * every playback / like / D-pad affordance behaves identically.
 *
 * v2.8.66 — Added so "Top 100 Australia", "The '80s", etc. land
 * on a real chart page instead of a generic search results view.
 */
import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Play, Pause, Plus, Shuffle, Check } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

/* Hand-picked gradient palettes MATCHING the tile gradients on
 * the home shelf so the chart page feels like an extension of
 * the tile the user clicked.  Kept inline (rather than a CSS
 * class) because the palette is data-driven. */
const PRESET_BG = {
    'top-au':      'linear-gradient(135deg, #0057b7 0%, #ffd800 55%, #e30613 100%)',
    'top-us':      'linear-gradient(135deg, #0a3161 0%, #b31942 100%)',
    'top-uk':      'linear-gradient(135deg, #012169 0%, #c8102e 100%)',
    'decade-2020s':'linear-gradient(135deg, #ff006e 0%, #8338ec 50%, #3a86ff 100%)',
    'decade-2010s':'linear-gradient(135deg, #06d6a0 0%, #118ab2 100%)',
    'decade-2000s':'linear-gradient(135deg, #cc2b5e 0%, #753a88 100%)',
    'decade-90s':  'linear-gradient(135deg, #43cea2 0%, #185a9d 100%)',
    'decade-80s':  'linear-gradient(135deg, #f953c6 0%, #b91d73 60%, #202038 100%)',
    'decade-70s':  'linear-gradient(135deg, #ff8008 0%, #ffc837 100%)',
    'oldies':      'linear-gradient(135deg, #d4af37 0%, #7c5f16 100%)',
};

export default function MusicChart() {
    const { id } = useParams();
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);
    const { state, controls } = useMusicPlayer();

    useEffect(() => {
        setData(null);
        setErr(null);
        musicAPI.chartPreset(id)
            .then((r) => setData(r.data || r))
            .catch((e) => setErr(e.message || 'failed to load'));
    }, [id]);

    if (err) return <div className="tunes-empty" data-testid="tunes-chart-error">Couldn&apos;t load chart — {err}</div>;
    if (!data) return <div className="tunes-empty" data-testid="tunes-chart-loading">Loading chart…</div>;

    const tracks = data.tracks || [];
    const bg = PRESET_BG[id] || 'linear-gradient(135deg, #333 0%, #000 100%)';

    return (
        <div className="tunes-album" data-testid="tunes-chart-page">
            {/* Reuse album backdrop treatment with the preset gradient. */}
            <div
                className="tunes-album__backdrop"
                style={{ backgroundImage: bg, opacity: 0.6 }}
            />
            <div className="tunes-album__layout">
                <div className="tunes-album__head">
                    <div
                        className="tunes-album__cover"
                        style={{
                            background: bg,
                            display: 'grid',
                            placeItems: 'center',
                            aspectRatio: '1 / 1',
                        }}
                    >
                        <span
                            style={{
                                fontSize: '3.2vw',
                                fontWeight: 900,
                                color: 'rgba(255,255,255,0.95)',
                                textShadow: '0 4px 24px rgba(0,0,0,0.4)',
                                letterSpacing: '-0.02em',
                                textAlign: 'center',
                                lineHeight: 1.05,
                                padding: '0 8%',
                            }}
                        >
                            {data.title}
                        </span>
                    </div>
                    <div className="tunes-album__info">
                        <p className="tunes-album__eyebrow">Chart</p>
                        <h1 className="tunes-album__title">{data.title}</h1>
                        <p className="tunes-album__artist" style={{ textTransform: 'uppercase' }}>
                            {tracks.length} songs · updated live
                        </p>
                        <div className="tunes-album__meta">
                            <span>{data.subtitle || 'Curated chart'}</span>
                        </div>
                        <div className="tunes-album__actions" data-hero-actions>
                            <button
                                type="button"
                                className="tunes-btn tunes-btn--primary"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => tracks.length && controls.playTrack(tracks[0], tracks)}
                                data-testid="tunes-chart-play"
                            >
                                <Play size={18} fill="#0a0118" />
                                Play All
                            </button>
                            <button
                                type="button"
                                className="tunes-btn tunes-btn--ghost"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => {
                                    if (!tracks.length) return;
                                    const shuf = [...tracks].sort(() => Math.random() - 0.5);
                                    controls.playTrack(shuf[0], shuf);
                                }}
                                data-testid="tunes-chart-shuffle"
                            >
                                <Shuffle size={16} />
                                Shuffle
                            </button>
                        </div>
                    </div>
                </div>

                <div className="tunes-track-list">
                    {tracks.length === 0 ? (
                        <div className="tunes-empty" style={{ padding: 24 }}>
                            No tracks available right now — please try again in a minute.
                        </div>
                    ) : tracks.map((t, i) => {
                        const isCurrent = state.current?.id === t.id;
                        const isPlaying = isCurrent && state.isPlaying;
                        const isExplicit = t.explicit_lyrics;
                        return (
                            <div
                                key={t.id}
                                className={
                                    'tunes-track-row' +
                                    (isCurrent ? ' tunes-track-row--playing' : '')
                                }
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                onClick={() => {
                                    if (isCurrent) controls.toggle();
                                    else controls.playTrack(t, tracks);
                                }}
                                onKeyDown={(e) => {
                                    if (e.key === 'Enter') {
                                        if (isCurrent) controls.toggle();
                                        else controls.playTrack(t, tracks);
                                    }
                                }}
                                data-testid={`tunes-chart-track-${t.id}`}
                            >
                                <div className="tunes-track-row__num">
                                    {isCurrent
                                        ? (isPlaying
                                            ? <Pause size={14} />
                                            : <Play size={14} fill="currentColor" />)
                                        : (i + 1)}
                                </div>
                                {t.album?.cover_small && (
                                    <img
                                        src={t.album.cover_small}
                                        alt=""
                                        style={{
                                            width: 44,
                                            height: 44,
                                            borderRadius: 6,
                                            objectFit: 'cover',
                                            flexShrink: 0,
                                        }}
                                    />
                                )}
                                <div style={{ minWidth: 0 }}>
                                    <p className="tunes-track-row__title">{t.title}</p>
                                    <p
                                        style={{
                                            color: 'rgba(255,255,255,0.55)',
                                            fontSize: 13,
                                            margin: 0,
                                            overflow: 'hidden',
                                            textOverflow: 'ellipsis',
                                            whiteSpace: 'nowrap',
                                        }}
                                    >
                                        {t.artist?.name || ''}
                                    </p>
                                </div>
                                <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                                    {isExplicit && (
                                        <span className="tunes-track-row__pill">E</span>
                                    )}
                                </span>
                                <span className="tunes-track-row__time">{fmtDur(t.duration)}</span>
                                <button
                                    type="button"
                                    className={
                                        'tunes-track-row__add' +
                                        (isCurrent ? ' tunes-track-row__add--added' : '')
                                    }
                                    aria-label="Add to playlist"
                                    onClick={(e) => e.stopPropagation()}
                                    data-testid={`tunes-chart-track-add-${t.id}`}
                                >
                                    {isCurrent ? <Check size={14} /> : <Plus size={14} />}
                                </button>
                            </div>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}
