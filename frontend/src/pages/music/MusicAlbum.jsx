import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Play, Pause } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

export default function MusicAlbum() {
    const { id } = useParams();
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);
    const { state, controls } = useMusicPlayer();

    useEffect(() => {
        setData(null);
        musicAPI.album(id)
            .then((res) => setData(res.data || res))
            .catch((e) => setErr(e.message || 'failed to load'));
    }, [id]);

    if (err) return <div className="tunes-empty">Couldn't load album — {err}</div>;
    if (!data) return <div className="tunes-empty">Loading album…</div>;

    const tracks = data.tracks || [];
    const totalSec = tracks.reduce((s, t) => s + (t.duration || 0), 0);
    const totalMin = Math.round(totalSec / 60);

    return (
        <div data-testid="music-album">
            <div style={{
                display: 'grid',
                gridTemplateColumns: '300px 1fr',
                gap: 36,
                alignItems: 'end',
                marginBottom: 32,
            }}>
                <img
                    src={data.cover || ''}
                    alt={data.title}
                    style={{
                        width: 300, height: 300, objectFit: 'cover',
                        borderRadius: 18,
                        boxShadow: '0 30px 60px rgba(0,0,0,0.55)',
                    }}
                />
                <div>
                    <p style={{ fontSize: 12, letterSpacing: '0.22em', textTransform: 'uppercase', color: 'var(--tunes-accent-3)', margin: 0 }}>Album</p>
                    <h1 className="tunes-page-title" style={{ fontSize: 52, margin: '6px 0 14px' }}>{data.title}</h1>
                    <p className="tunes-page-subtitle" style={{ marginBottom: 6 }}>
                        <strong style={{ color: '#fff' }}>{data.artist?.name}</strong>
                        {data.release_date && ` · ${data.release_date.slice(0,4)}`}
                        {tracks.length ? ` · ${tracks.length} tracks` : ''}
                        {totalMin ? ` · ${totalMin} min` : ''}
                    </p>
                    <button
                        className="tunes-btn-primary"
                        onClick={() => tracks.length && controls.playTrack(tracks[0], tracks)}
                        data-testid="tunes-album-play"
                        style={{ marginTop: 18 }}
                    >
                        <Play size={18} style={{ marginLeft: 2 }} />
                        Play album
                    </button>
                </div>
            </div>

            <div className="tunes-track-list">
                {tracks.map((t, i) => {
                    const isCurrent = state.current?.id === t.id;
                    const isPlaying = isCurrent && state.isPlaying;
                    return (
                        <div
                            key={t.id}
                            className={'tunes-track-row' + (isCurrent ? ' tunes-track-row--playing' : '')}
                            tabIndex={0}
                            onClick={() => { if (isCurrent) controls.toggle(); else controls.playTrack(t, tracks); }}
                            onKeyDown={(e) => { if (e.key === 'Enter') { if (isCurrent) controls.toggle(); else controls.playTrack(t, tracks); } }}
                            data-testid={`tunes-album-track-${t.id}`}
                        >
                            <div className="tunes-track-row__num">
                                {isPlaying ? <Pause size={14} /> : (i + 1)}
                            </div>
                            <img src={t.album?.cover || data.cover} alt="" className="tunes-track-row__art" loading="lazy" />
                            <div>
                                <p className="tunes-track-row__title">{t.title}</p>
                                <p className="tunes-track-row__artist">{t.artist?.name}</p>
                            </div>
                            <span className="tunes-track-row__duration">{fmtDur(t.duration)}</span>
                        </div>
                    );
                })}
            </div>
        </div>
    );
}
