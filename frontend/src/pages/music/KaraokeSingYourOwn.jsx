// ON NOW TV Tunes — Sing Your Own (v2.8.74).
//
// User taps "Sing Your Own" from the karaoke home → lands here.
// Layout (per the supplied design):
//   - Hero with stage mic backdrop + "Sing Your Own" title
//   - Big rounded search bar with neon-blue glow
//   - Top Bangers shelf (curated party-anthem covers)
//   - Popular Tonight shelf (smaller rows)
// Tapping a tile plays the song through the same pipeline as regular
// music; FullScreenPlayer opens in karaoke mode (centered lyric
// ticker) — see Karaoke.legacy.jsx for the underlying helper.

import React, { useEffect, useMemo, useState } from 'react';
import { Search as SearchIcon, Mic } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

const SOLO_KARAOKE_FLAG = 'tunes-karaoke-mode';

function startKaraokeFor(controls, track) {
    if (!track) return;
    try { sessionStorage.setItem(SOLO_KARAOKE_FLAG, '1'); } catch { /* ignore */ }
    controls.playTrack(track, [track]);
    setTimeout(() => {
        try { window.dispatchEvent(new CustomEvent('tunes:open-fullscreen')); }
        catch { /* ignore */ }
    }, 30);
}

const TOP_BANGER_SEEDS = [
    'marshmello bastille happier',
    'david guetta sia titanium',
    'drake hold on we are going home',
    'olivia rodrigo drivers license',
    'the weeknd blinding lights',
    'ed sheeran bad habits',
];

const POPULAR_SEEDS = [
    'lewis capaldi someone you loved',
    'ed sheeran perfect',
    'lady gaga bradley cooper shallow',
    'tones and i dance monkey',
    'maroon 5 memories',
    'the kid laroi justin bieber stay',
];

function useSeededTracks(seeds) {
    const [tracks, setTracks] = useState(null);
    useEffect(() => {
        let alive = true;
        Promise.all(
            seeds.map((s) =>
                musicAPI.search(s)
                    .then((r) => (r?.data?.tracks || r?.tracks || [])[0])
                    .catch(() => null)
            )
        ).then((results) => {
            if (!alive) return;
            setTracks((results || []).filter(Boolean));
        });
        const safety = setTimeout(() => alive && setTracks((c) => c ?? []), 6000);
        return () => { alive = false; clearTimeout(safety); };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);
    return tracks;
}

export default function KaraokeSingYourOwn() {
    const [q, setQ] = useState('');
    const [results, setResults] = useState(null);
    const [loading, setLoading] = useState(false);
    const { controls } = useMusicPlayer();
    const topBangers = useSeededTracks(TOP_BANGER_SEEDS);
    const popular   = useSeededTracks(POPULAR_SEEDS);

    useEffect(() => {
        const t = q.trim();
        if (!t) { setResults(null); return; }
        let alive = true;
        setLoading(true);
        const handle = setTimeout(async () => {
            try {
                const r = await musicAPI.search(t);
                if (!alive) return;
                setResults(r.data?.tracks || r.tracks || []);
            } catch {
                if (alive) setResults([]);
            } finally {
                if (alive) setLoading(false);
            }
        }, 280);
        return () => { alive = false; clearTimeout(handle); };
    }, [q]);

    const showSearch = useMemo(() => results !== null, [results]);

    return (
        <div className="kk-sing" data-testid="karaoke-sing">
            <section className="kk-sing__hero">
                <div className="kk-hero__bg" />
                <div className="kk-hero__scrim" />
                <div className="kk-sing__hero-copy">
                    <h1 className="kk-sing__title">Sing Your Own</h1>
                    <p className="kk-sing__sub">
                        Search any song, pick up the mic, <br />
                        and sing it your way. Instantly.
                    </p>
                </div>
            </section>

            <div className="kk-search">
                <SearchIcon size={22} className="kk-search__icon" />
                <input
                    placeholder="Search songs, artists, or karaoke tracks"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    data-testid="karaoke-search-input"
                    data-focusable="true"
                    data-focus-style="pill"
                />
            </div>

            {showSearch && (
                <Shelf
                    eyebrow="YOUR PICK"
                    title={loading ? 'Searching…' : `${results.length} song${results.length === 1 ? '' : 's'} ready to belt out`}
                    rail="big"
                    items={results}
                    onPick={(t) => startKaraokeFor(controls, t)}
                    testid="karaoke-results"
                />
            )}

            {!showSearch && (
                <>
                    <Shelf
                        eyebrow="TOP BANGERS"
                        title="Top Bangers"
                        rail="big"
                        items={topBangers}
                        onPick={(t) => startKaraokeFor(controls, t)}
                        testid="karaoke-top-bangers"
                    />
                    <Shelf
                        eyebrow="POPULAR TONIGHT"
                        title="Popular Tonight"
                        rail="small"
                        items={popular}
                        onPick={(t) => startKaraokeFor(controls, t)}
                        testid="karaoke-popular"
                    />
                </>
            )}
        </div>
    );
}

function Shelf({ eyebrow, title, items, onPick, rail, testid }) {
    return (
        <section className="kk-shelf" data-testid={testid}>
            <header className="kk-shelf__header">
                <p className="kk-shelf__eyebrow">{eyebrow}</p>
                <h2 className="kk-shelf__title">{title}</h2>
            </header>
            {items === null && <p className="tunes-empty">Warming up the stage…</p>}
            {items?.length === 0 && <p className="tunes-empty">Nothing here.</p>}
            <div className={`kk-shelf__rail kk-shelf__rail--${rail}`} data-shelf-rail>
                {(items || []).map((t) => (
                    <button
                        key={t.id}
                        type="button"
                        className={`kk-songcard kk-songcard--${rail}`}
                        onClick={() => onPick(t)}
                        data-focusable="true"
                        data-focus-style="tile"
                        tabIndex={0}
                        data-testid={`karaoke-song-${t.id}`}
                    >
                        <div className="kk-songcard__art-wrap">
                            <img
                                src={t.album?.cover_big || t.album?.cover_medium || t.album?.cover}
                                alt={t.title}
                                className="kk-songcard__art"
                                loading="lazy"
                            />
                            <span className="kk-songcard__mic" aria-hidden="true">
                                <Mic size={14} strokeWidth={2.2} />
                            </span>
                        </div>
                        <div className="kk-songcard__caption">
                            <p className="kk-songcard__title">{t.title}</p>
                            <p className="kk-songcard__artist">{t.artist?.name || ''}</p>
                        </div>
                    </button>
                ))}
            </div>
        </section>
    );
}
