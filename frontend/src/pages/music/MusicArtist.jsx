import React, { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { Play } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

export default function MusicArtist() {
    const { id } = useParams();
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);
    const { controls } = useMusicPlayer();

    useEffect(() => {
        setData(null);
        musicAPI.artist(id).then((r) => setData(r.data || r)).catch((e) => setErr(e.message || 'failed'));
    }, [id]);

    if (err) return <div className="tunes-empty">Couldn't load artist — {err}</div>;
    if (!data) return <div className="tunes-empty">Loading…</div>;

    const top = data.top_tracks || [];

    return (
        <div data-testid="music-artist">
            <div style={{
                display: 'grid',
                gridTemplateColumns: '280px 1fr',
                gap: 36,
                alignItems: 'end',
                marginBottom: 32,
            }}>
                <img
                    src={data.picture || ''}
                    alt={data.name}
                    style={{
                        width: 280, height: 280, objectFit: 'cover',
                        borderRadius: '50%',
                        boxShadow: '0 30px 60px rgba(0,0,0,0.55)',
                    }}
                />
                <div>
                    <p style={{ fontSize: 12, letterSpacing: '0.22em', textTransform: 'uppercase', color: 'var(--tunes-accent-3)', margin: 0 }}>
                        Artist {data.nb_fan ? `· ${data.nb_fan.toLocaleString()} fans` : ''}
                    </p>
                    <h1 className="tunes-page-title" style={{ fontSize: 60, margin: '6px 0 18px' }}>{data.name}</h1>
                    <button
                        className="tunes-btn-primary"
                        onClick={() => top.length && controls.playTrack(top[0], top)}
                        data-testid="tunes-artist-play"
                    >
                        <Play size={18} style={{ marginLeft: 2 }} />
                        Play top tracks
                    </button>
                </div>
            </div>

            {top.length > 0 && (
                <section className="tunes-section">
                    <h2 className="tunes-section__title">Popular</h2>
                    <div className="tunes-track-list">
                        {top.slice(0, 10).map((t, i) => (
                            <div data-focusable="true" data-focus-style="tile"
                                key={t.id}
                                className="tunes-track-row"
                                tabIndex={0}
                                onClick={() => controls.playTrack(t, top)}
                                onKeyDown={(e) => { if (e.key === 'Enter') controls.playTrack(t, top); }}
                                data-testid={`tunes-artist-track-${t.id}`}
                            >
                                <div data-focusable="true" data-focus-style="tile" tabIndex={0} className="tunes-track-row__num">{i + 1}</div>
                                <img src={t.album?.cover || ''} alt="" className="tunes-track-row__art" loading="lazy" />
                                <div>
                                    <p className="tunes-track-row__title">{t.title}</p>
                                    <p className="tunes-track-row__artist">{t.album?.title || ''}</p>
                                </div>
                                <span className="tunes-track-row__duration">{fmtDur(t.duration)}</span>
                            </div>
                        ))}
                    </div>
                </section>
            )}

            {data.albums?.length > 0 && (
                <section className="tunes-section">
                    <h2 className="tunes-section__title">Discography</h2>
                    <div className="tunes-grid">
                        {data.albums.map((a) => (
                            <Link data-focusable="true" data-focus-style="tile" tabIndex={0} key={a.id} to={`/music/album/${a.id}`} className="tunes-card">
                                <img src={a.cover || ''} alt="" className="tunes-card__art" loading="lazy" />
                                <div data-focusable="true" data-focus-style="tile" tabIndex={0} className="tunes-card__body">
                                    <p className="tunes-card__title">{a.title}</p>
                                    <p className="tunes-card__subtitle">{a.release_date?.slice(0, 4) || ''}</p>
                                </div>
                            </Link>
                        ))}
                    </div>
                </section>
            )}
        </div>
    );
}
