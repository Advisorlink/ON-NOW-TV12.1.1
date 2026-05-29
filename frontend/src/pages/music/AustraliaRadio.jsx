// Australia Radio — curated landing for Aussie stations.
//
// /music/radio/au
//
// Layout:
//   1. Hero: big "🇦🇺 Australia" headline with a sunset gradient
//      and a list of how many stations are available.
//   2. Pinned shelf: 8 famous Aussie stations the user is most
//      likely to want (Triple J, Hot Tomato, Triple M, Nova,
//      KIIS, ABC News, smoothfm, 2GB) — fetched by name so the
//      ordering stays predictable regardless of Radio Browser's
//      voting.
//   3. Browse-all grid: every other Australian station from
//      Radio Browser, ordered by popularity (votes).
//
// All stations play directly when tapped — same engine as the
// rest of the Tunes app (HTML5 audio for radio streams).

import React, { useEffect, useState } from 'react';
import { Radio as RadioIcon, Disc3 } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

// Curated list of well-known Aussie stations.  The names are matched
// case-insensitive against the Radio Browser response so we always
// show them in this preferred order at the top — independent of
// Radio Browser's vote counts (which fluctuate).
const PINNED_AU = [
    'triple j',          // youth alternative
    'hot tomato',        // Gold Coast top 40
    'triple m',
    'nova 96.9',
    'kiis 1065',         // Sydney
    'abc news',
    'smoothfm',
    '2gb',
];

export default function AustraliaRadio() {
    const [stations, setStations] = useState(null);
    const [error, setError] = useState(null);
    const { controls } = useMusicPlayer();

    useEffect(() => {
        let active = true;
        musicAPI.radioTop({ country: 'AU', limit: 200 }).then((r) => {
            if (!active) return;
            setStations(r.data || []);
        }).catch(() => {
            if (active) setError('Could not load stations');
        });
        return () => { active = false; };
    }, []);

    const lowerName = (s) => (s.name || '').toLowerCase();
    const pinned = (stations || []).filter((s) =>
        PINNED_AU.some((p) => lowerName(s).includes(p))
    ).sort((a, b) => {
        // Sort within the pinned set by the order in PINNED_AU.
        const idx = (st) => PINNED_AU.findIndex((p) => lowerName(st).includes(p));
        return idx(a) - idx(b);
    });
    // Dedupe: pinned names can match more than one station; keep
    // the highest-voted match per name.
    const dedupedPinned = [];
    const seen = new Set();
    for (const s of pinned) {
        const tag = PINNED_AU.find((p) => lowerName(s).includes(p));
        if (tag && !seen.has(tag)) {
            seen.add(tag);
            dedupedPinned.push(s);
        }
    }
    const pinnedIds = new Set(dedupedPinned.map((s) => s.id));
    const rest = (stations || []).filter((s) => !pinnedIds.has(s.id));

    const onPlay = (station) => {
        controls.playRadio(station);
        // Tell Radio Browser this station got played — drives their
        // voting / popularity ranking.
        musicAPI.radioClick?.(station.id);
    };

    return (
        <div className="tunes-au" data-testid="music-au">
            <header className="tunes-au__hero">
                <div className="tunes-au__hero-flag" aria-hidden="true">🇦🇺</div>
                <div>
                    <h1 className="tunes-au__title">Australia</h1>
                    <p className="tunes-au__subtitle">
                        {stations === null
                            ? 'Loading stations…'
                            : `${stations.length}+ live Australian radio stations`}
                    </p>
                </div>
            </header>

            {error && <p className="tunes-empty">{error}</p>}

            {dedupedPinned.length > 0 && (
                <section className="tunes-section" data-testid="shelf-page" data-shelf-id="au-pinned">
                    <h2 className="tunes-section__title">Top picks</h2>
                    <div className="tunes-au__grid">
                        {dedupedPinned.map((s) => (
                            <StationCard key={s.id} station={s} pinned onPlay={onPlay} />
                        ))}
                    </div>
                </section>
            )}

            {rest.length > 0 && (
                <section className="tunes-section" data-testid="shelf-page" data-shelf-id="au-all">
                    <h2 className="tunes-section__title">All Australian stations</h2>
                    <div className="tunes-au__grid">
                        {rest.map((s) => (
                            <StationCard key={s.id} station={s} onPlay={onPlay} />
                        ))}
                    </div>
                </section>
            )}
        </div>
    );
}

function StationCard({ station, pinned, onPlay }) {
    return (
        <button
            type="button"
            className={'tunes-au-card' + (pinned ? ' is-pinned' : '')}
            onClick={() => onPlay(station)}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`au-station-${station.id}`}
        >
            <div className="tunes-au-card__logo">
                {station.favicon
                    ? <img src={station.favicon} alt="" loading="lazy"
                           onError={(e) => { e.currentTarget.style.display = 'none'; }} />
                    : <RadioIcon size={24} />}
            </div>
            <div className="tunes-au-card__body">
                <p className="tunes-au-card__name">{station.name}</p>
                <p className="tunes-au-card__meta">
                    {station.tags?.slice(0, 2).join(' · ') || station.state || 'Australia'}
                </p>
            </div>
            <div className="tunes-au-card__play">
                <Disc3 size={18} />
            </div>
        </button>
    );
}
