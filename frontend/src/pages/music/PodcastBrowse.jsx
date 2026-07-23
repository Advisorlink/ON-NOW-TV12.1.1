/**
 * PodcastBrowse — iTunes top podcasts, filterable by country.
 *
 * v2.8.67 — Nav upgrade: country chip row (US / UK / AU / CA /
 * DE / IN) plus D-pad focusable tiles matching the pattern used
 * by RadioBrowse and MusicHome.  Also swaps the plain image tile
 * for a bigger art + subtitle card so the shelf feels first-class.
 */
import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { musicAPI } from '../../lib/music-api';

const COUNTRIES = [
    { code: 'us', label: '🇺🇸 USA' },
    { code: 'gb', label: '🇬🇧 UK' },
    { code: 'au', label: '🇦🇺 Australia' },
    { code: 'ca', label: '🇨🇦 Canada' },
    { code: 'de', label: '🇩🇪 Germany' },
    { code: 'in', label: '🇮🇳 India' },
    { code: 'nz', label: '🇳🇿 New Zealand' },
    { code: 'jp', label: '🇯🇵 Japan' },
];

export default function PodcastBrowse() {
    const [country, setCountry] = useState('us');
    const [podcasts, setPodcasts] = useState(null);
    const [err, setErr] = useState(null);

    useEffect(() => {
        setPodcasts(null);
        setErr(null);
        musicAPI.podcastsTop({ country })
            .then((r) => setPodcasts(r.data || r || []))
            .catch((e) => { setPodcasts([]); setErr(e.message || 'failed'); });
    }, [country]);

    return (
        <div data-testid="music-podcasts" className="tunes-section--podcast">
            <h1
                className="tunes-page-title"
                style={{
                    background: 'linear-gradient(90deg, #fff 0%, #fed7aa 100%)',
                    WebkitBackgroundClip: 'text',
                    backgroundClip: 'text',
                    color: 'transparent',
                }}
            >
                Podcasts
            </h1>
            <p className="tunes-page-subtitle">
                Top shows from around the world — episodes stream straight from the publisher&apos;s feed.
            </p>

            <div className="tunes-chips" data-testid="podcast-country-chips">
                {COUNTRIES.map((c) => (
                    <button
                        key={c.code}
                        type="button"
                        className={
                            'tunes-chip tunes-chip--podcast' +
                            (country === c.code ? ' tunes-chip--active' : '')
                        }
                        onClick={() => setCountry(c.code)}
                        data-testid={`tunes-podcast-country-${c.code}`}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                    >
                        {c.label}
                    </button>
                ))}
            </div>

            {!podcasts && <div className="tunes-empty">Loading top podcasts…</div>}
            {err && (
                <div className="tunes-empty" data-testid="podcast-error">
                    Couldn&apos;t load podcasts — {err}
                </div>
            )}

            {podcasts?.length > 0 && (
                <div className="tunes-grid">
                    {podcasts.map((p) => (
                        <Link
                            key={p.id}
                            to={`/music/podcast/${encodeURIComponent(p.feed_url || '')}`}
                            className="tunes-card"
                            data-testid={`tunes-podcast-${p.id}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                        >
                            <img
                                src={p.artwork || ''}
                                alt=""
                                className="tunes-card__art"
                                loading="lazy"
                            />
                            <div className="tunes-card__body">
                                <p className="tunes-card__title">{p.title}</p>
                                <p className="tunes-card__subtitle">
                                    {p.artist || p.genre}
                                </p>
                            </div>
                        </Link>
                    ))}
                </div>
            )}
        </div>
    );
}
