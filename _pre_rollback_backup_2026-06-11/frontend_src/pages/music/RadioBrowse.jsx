import React, { useEffect, useState } from 'react';
import { Radio as RadioIcon, Play } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

export default function RadioBrowse() {
    const [stations, setStations] = useState(null);
    const [genres, setGenres] = useState([]);
    const [activeTag, setActiveTag] = useState(null);
    const { state, controls } = useMusicPlayer();

    useEffect(() => {
        musicAPI.radioGenres().then((r) => setGenres((r.data || r || []).slice(0, 20))).catch(() => setGenres([]));
    }, []);

    useEffect(() => {
        setStations(null);
        const load = activeTag
            ? musicAPI.radioByTag(activeTag, 60)
            : musicAPI.radioTop({ limit: 60 });
        load.then((r) => setStations(r.data || r || [])).catch(() => setStations([]));
    }, [activeTag]);

    const playing = state.kind === 'radio' && state.current?.id;

    return (
        <div data-testid="music-radio" className="tunes-section--radio">
            <h1 className="tunes-page-title" style={{
                background: 'linear-gradient(90deg, #fff 0%, #7dd3fc 100%)',
                WebkitBackgroundClip: 'text',
                backgroundClip: 'text',
                color: 'transparent',
            }}>Live Radio</h1>
            <p className="tunes-page-subtitle">30,000+ stations from around the world. Tap any to start streaming.</p>

            <div className="tunes-chips">
                <button
                    type="button"
                    className={'tunes-chip tunes-chip--radio' + (!activeTag ? ' tunes-chip--active' : '')}
                    onClick={() => setActiveTag(null)}
                    data-testid="tunes-radio-chip-all"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                >Top stations</button>
                {genres.map((g) => (
                    <button
                        key={g.name}
                        type="button"
                        className={'tunes-chip tunes-chip--radio' + (activeTag === g.name ? ' tunes-chip--active' : '')}
                        onClick={() => setActiveTag(g.name)}
                        data-testid={`tunes-radio-chip-${g.name}`}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                    >{g.name}</button>
                ))}
            </div>

            {!stations && <div className="tunes-empty">Loading stations…</div>}

            {stations && stations.length === 0 && (
                <div className="tunes-empty">No stations found for this tag.</div>
            )}

            {stations && stations.length > 0 && (
                <div className="tunes-grid">
                    {stations.map((s) => {
                        const isCurrent = playing === s.id;
                        return (
                            <button
                                key={s.id}
                                type="button"
                                className="tunes-card"
                                onClick={() => { controls.playRadio(s); musicAPI.radioClick(s.id); }}
                                data-testid={`tunes-radio-${s.id}`}
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                style={{ textAlign: 'left', cursor: 'pointer', borderColor: isCurrent ? 'var(--tunes-radio)' : undefined }}
                            >
                                <div className="tunes-card__art"
                                    style={{
                                        background: s.favicon
                                            ? `center/contain no-repeat url(${s.favicon}), linear-gradient(135deg, #064a59, #0a0118)`
                                            : 'linear-gradient(135deg, #064a59, #0a0118)',
                                        display: 'grid', placeItems: 'center',
                                    }}
                                >
                                    {!s.favicon && <RadioIcon size={56} color="rgba(255,255,255,0.55)" />}
                                </div>
                                <div className="tunes-card__body">
                                    <p className="tunes-card__title">{s.name}</p>
                                    <p className="tunes-card__subtitle">
                                        {[s.country, s.bitrate ? `${s.bitrate}kbps` : ''].filter(Boolean).join(' · ')}
                                    </p>
                                </div>
                            </button>
                        );
                    })}
                </div>
            )}
        </div>
    );
}
