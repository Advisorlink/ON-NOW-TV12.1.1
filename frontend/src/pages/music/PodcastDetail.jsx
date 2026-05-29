import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Play, Pause } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

export default function PodcastDetail() {
    const { feedUrl } = useParams();
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);
    const { state, controls } = useMusicPlayer();

    useEffect(() => {
        setData(null);
        const url = decodeURIComponent(feedUrl);
        musicAPI.podcastEpisodes(url).then((r) => setData(r.data || r)).catch((e) => setErr(e.message || 'failed'));
    }, [feedUrl]);

    if (err) return <div className="tunes-empty">Couldn't load podcast — {err}</div>;
    if (!data) return <div className="tunes-empty">Loading episodes…</div>;

    const { podcast, episodes } = data;

    return (
        <div data-testid="music-podcast-detail">
            <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: 36, alignItems: 'end', marginBottom: 32 }}>
                <img src={podcast?.artwork || ''} alt={podcast?.title} style={{ width: 280, height: 280, objectFit: 'cover', borderRadius: 18, boxShadow: '0 30px 60px rgba(0,0,0,0.55)' }} />
                <div>
                    <p style={{ fontSize: 12, letterSpacing: '0.22em', textTransform: 'uppercase', color: '#fed7aa', margin: 0 }}>Podcast</p>
                    <h1 className="tunes-page-title" style={{ fontSize: 48, margin: '6px 0 14px' }}>{podcast?.title}</h1>
                    <p className="tunes-page-subtitle" style={{ maxWidth: 720 }}>
                        {(podcast?.description || '').replace(/<[^>]+>/g, '').slice(0, 280)}
                        {(podcast?.description || '').length > 280 ? '…' : ''}
                    </p>
                    <p className="tunes-page-subtitle" style={{ marginTop: 8 }}>
                        <strong style={{ color: '#fff' }}>{podcast?.author}</strong> · {episodes.length} episodes
                    </p>
                </div>
            </div>

            <h2 className="tunes-section__title">Episodes</h2>
            <div className="tunes-track-list">
                {episodes.map((ep, i) => {
                    const isCurrent = state.kind === 'episode' && state.current?.id === ep.id;
                    return (
                        <div
                            key={ep.id}
                            className={'tunes-track-row' + (isCurrent ? ' tunes-track-row--playing' : '')}
                            style={{ gridTemplateColumns: '32px 1fr auto' }}
                            tabIndex={0}
                            onClick={() => { if (isCurrent) controls.toggle(); else controls.playEpisode(ep, podcast); }}
                            onKeyDown={(e) => { if (e.key === 'Enter') { if (isCurrent) controls.toggle(); else controls.playEpisode(ep, podcast); } }}
                            data-testid={`tunes-podcast-episode-${i}`}
                        >
                            <div className="tunes-track-row__num">
                                {isCurrent && state.isPlaying ? <Pause size={14} /> : <Play size={14} />}
                            </div>
                            <div>
                                <p className="tunes-track-row__title">{ep.title}</p>
                                <p className="tunes-track-row__artist" style={{ display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', whiteSpace: 'normal' }}>
                                    {(ep.description || '').replace(/<[^>]+>/g, '').slice(0, 200)}
                                </p>
                            </div>
                            <span className="tunes-track-row__duration">{ep.duration || ''}</span>
                        </div>
                    );
                })}
            </div>
        </div>
    );
}
