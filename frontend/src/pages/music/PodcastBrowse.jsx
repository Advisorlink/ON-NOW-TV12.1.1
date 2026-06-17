import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { musicAPI } from '../../lib/music-api';

export default function PodcastBrowse() {
    const [podcasts, setPodcasts] = useState(null);

    useEffect(() => {
        musicAPI.podcastsTop({ country: 'us' }).then((r) => setPodcasts(r.data || r || [])).catch(() => setPodcasts([]));
    }, []);

    return (
        <div data-testid="music-podcasts" className="tunes-section--podcast">
            <h1 className="tunes-page-title" style={{
                background: 'linear-gradient(90deg, #fff 0%, #fed7aa 100%)',
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                color: 'transparent',
            }}>Podcasts</h1>
            <p className="tunes-page-subtitle">Top shows from around the world. Episodes stream straight from the publisher&apos;s feed.</p>

            {!podcasts && <div className="tunes-empty">Loading top podcasts…</div>}

            {podcasts?.length > 0 && (
                <div className="tunes-grid">
                    {podcasts.map((p) => (
                        <Link
                            key={p.id}
                            to={`/music/podcast/${encodeURIComponent(p.feed_url || '')}`}
                            className="tunes-card"
                            data-testid={`tunes-podcast-${p.id}`}
                        >
                            <img src={p.artwork || ''} alt="" className="tunes-card__art" loading="lazy" />
                            <div className="tunes-card__body">
                                <p className="tunes-card__title">{p.title}</p>
                                <p className="tunes-card__subtitle">{p.artist || p.genre}</p>
                            </div>
                        </Link>
                    ))}
                </div>
            )}
        </div>
    );
}
