// V2 Karaoke — landing screen ONLY.
//
// v2.8.69 — Complete rearchitecture.  The previous design routed
// karaoke through a SEPARATE `/music/karaoke/play/:trackId` route
// outside MusicLayout, which (a) re-mounted the layout tree on every
// karaoke session and (b) introduced fragile iframe-init timing that
// regularly left the user with synced lyrics but no audio.
//
// New flow (the user's own suggestion — kudos):
//
//   1. /music/karaoke
//        Landing screen — search box + curated picks + recently
//        played karaoke songs.  Lives INSIDE MusicLayout.
//
//   2. User taps a song tile
//        ➜  controls.playTrack(track, [track])
//        ➜  sessionStorage.setItem('tunes-karaoke-mode', '1')
//        ➜  window.dispatchEvent('tunes:open-fullscreen')
//
//      That's it.  The track plays through the IDENTICAL engine
//      that powers every other music tile in the app.  The
//      FullScreenPlayer opens, sees the karaoke-mode flag, and
//      swaps its side-panel lyrics for a centered, full-bleed
//      karaoke ticker overlay.  Audio just works because we're
//      using the same proven code path.
//
//   3. User taps the X (or Esc)
//        ➜  sessionStorage.removeItem('tunes-karaoke-mode')
//        ➜  FullScreenPlayer closes.  Audio keeps playing in the
//           MiniPlayer if the user wants to continue listening.
//
// The legacy KaraokeStage component (still exported below as a
// thin redirect) is kept so the App.js route still resolves —
// but it just bounces back to /music/karaoke and triggers the
// new flow.  Any old deep-links keep working.

import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Mic, Search as SearchIcon } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

const KARAOKE_FLAG_KEY = 'tunes-karaoke-mode';

/** Starts karaoke playback via the standard music pipeline + opens
 *  the FullScreenPlayer in karaoke mode.  Used by both the landing
 *  tiles and the legacy deep-link fallback. */
function startKaraokeFor(controls, track) {
    if (!track) return;
    try { sessionStorage.setItem(KARAOKE_FLAG_KEY, '1'); } catch { /* ignore */ }
    // Same call regular music tiles make — audio resolves through
    // the proven engine (native bridge → backend → yt-iframe).
    controls.playTrack(track, [track]);
    // Defer the fullscreen open by one tick so the MiniPlayer has
    // a chance to mount its event listener if it was previously
    // collapsed.
    setTimeout(() => {
        try { window.dispatchEvent(new CustomEvent('tunes:open-fullscreen')); }
        catch { /* ignore */ }
    }, 30);
}

export default function KaraokePage() {
    const [q, setQ] = useState('');
    const [results, setResults] = useState(null);
    const [picks, setPicks] = useState(null);
    const [loading, setLoading] = useState(false);
    const { controls } = useMusicPlayer();

    useEffect(() => {
        let active = true;
        const seeds = [
            'queen bohemian rhapsody',
            'adele rolling in the deep',
            'whitney houston i will always love you',
            'bon jovi livin on a prayer',
            'taylor swift shake it off',
            'journey dont stop believin',
            'ed sheeran perfect',
            'celine dion my heart will go on',
        ];
        Promise.all(
            seeds.map((s) =>
                musicAPI.search(s)
                    .then((sr) => (sr?.data?.tracks || sr?.tracks || [])[0])
                    .catch(() => null)
            )
        ).then((seedResults) => {
            if (!active) return;
            setPicks((seedResults || []).filter(Boolean));
        });
        const safety = setTimeout(() => {
            if (active) setPicks((cur) => (cur === null ? [] : cur));
        }, 6000);
        return () => { active = false; clearTimeout(safety); };
    }, []);

    useEffect(() => {
        const t = q.trim();
        if (!t) { setResults(null); return; }
        let active = true;
        setLoading(true);
        const handle = setTimeout(async () => {
            try {
                const r = await musicAPI.search(t);
                if (!active) return;
                setResults(r.data?.tracks || r.tracks || []);
            } catch {
                if (active) setResults([]);
            } finally {
                if (active) setLoading(false);
            }
        }, 280);
        return () => { active = false; clearTimeout(handle); };
    }, [q]);

    return (
        <div className="tunes-karaoke" data-testid="music-karaoke">
            <section className="karaoke-party-hero" data-testid="karaoke-hero">
                <div className="karaoke-party-hero__bg" />
                <div className="karaoke-party-hero__neon" />
                <div className="karaoke-party-hero__scrim" />
                <div className="karaoke-party-hero__content">
                    <span className="karaoke-party-hero__mic" aria-hidden="true">
                        <Mic size={26} strokeWidth={2.2} />
                    </span>
                    <p className="karaoke-party-hero__eyebrow">PICK YOUR JAM · SING IT LOUD</p>
                    <h1 className="karaoke-party-hero__title">
                        Tonight, You&apos;re&nbsp;
                        <em>The Star</em>
                    </h1>
                    <p className="karaoke-party-hero__subtitle">
                        Any song. Live lyrics. Your voice. Pick a tune below,
                        grab the mic and steal the show.
                    </p>
                </div>
            </section>

            <div className="karaoke-search">
                <SearchIcon size={20} className="karaoke-search__icon" />
                <input
                    placeholder="Search any song to sing…"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    data-testid="karaoke-search-input"
                    data-focusable="true"
                    data-focus-style="pill"
                />
            </div>

            {results !== null && (
                <section
                    className="tunes-shelf"
                    data-testid="shelf-page"
                    data-shelf-id="karaoke-results"
                >
                    <header className="tunes-shelf__header">
                        <div className="tunes-shelf__header-left">
                            <span className="tunes-shelf__eyebrow">YOUR PICK</span>
                            <h2 className="tunes-shelf__title">
                                {loading ? 'Searching…' : `${results.length} song${results.length === 1 ? '' : 's'} ready to belt out`}
                            </h2>
                        </div>
                    </header>
                    <div className="tunes-shelf__rail vesper-shelf" data-shelf-rail>
                        {results.map((t) => (
                            <KaraokeSongCard
                                key={t.id}
                                track={t}
                                onClick={() => startKaraokeFor(controls, t)}
                            />
                        ))}
                    </div>
                    {!loading && results.length === 0 && (
                        <p className="tunes-empty">Nothing matched. Try another song.</p>
                    )}
                </section>
            )}

            {results === null && (
                <section
                    className="tunes-shelf"
                    data-testid="shelf-crowd-pleasers"
                    data-shelf-id="karaoke-picks"
                >
                    <header className="tunes-shelf__header">
                        <div className="tunes-shelf__header-left">
                            <span className="tunes-shelf__eyebrow">FAN FAVES · BELT-IT-OUT BANGERS</span>
                            <h2 className="tunes-shelf__title">Crowd-Pleasers</h2>
                        </div>
                    </header>
                    {picks === null && <p className="tunes-empty">Warming up the stage…</p>}
                    <div className="tunes-shelf__rail vesper-shelf" data-shelf-rail>
                        {(picks || []).map((t) => (
                            <KaraokeSongCard
                                key={t.id}
                                track={t}
                                onClick={() => startKaraokeFor(controls, t)}
                            />
                        ))}
                    </div>
                </section>
            )}
        </div>
    );
}

function KaraokeSongCard({ track, onClick }) {
    const cover = track?.album?.cover_medium || track?.album?.cover || track?.artwork;
    return (
        <button
            type="button"
            className="tunes-tile"
            onClick={onClick}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`karaoke-song-${track.id}`}
        >
            {cover ? (
                <img
                    src={cover}
                    alt={track.title}
                    className="tunes-tile__art"
                    loading="lazy"
                    decoding="async"
                />
            ) : (
                <div className="tunes-tile__art-fallback">
                    {(track.title || '?')[0]}
                </div>
            )}
            <div className="tunes-tile__scrim" />
            <div
                className="tunes-tile__mic-badge"
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    top: 10,
                    right: 10,
                    width: 36,
                    height: 36,
                    borderRadius: '50%',
                    background: 'linear-gradient(180deg, var(--vesper-blue-bright), var(--vesper-blue))',
                    color: '#0a0118',
                    display: 'grid',
                    placeItems: 'center',
                    boxShadow: '0 4px 14px rgba(0,0,0,0.45), 0 0 0 1px rgba(255,255,255,0.10)',
                }}
            >
                <Mic size={18} strokeWidth={2.2} />
            </div>
            <div className="tunes-tile__caption">
                <p className="tunes-tile__title">{track.title}</p>
                <p className="tunes-tile__subtitle">{track.artist?.name || ''}</p>
            </div>
        </button>
    );
}

/**
 * Legacy `/music/karaoke/play/:trackId` deep-link target.
 *
 * v2.8.69 — Was a 200+ line standalone full-screen karaoke component
 * mounted OUTSIDE MusicLayout, which is exactly what was producing
 * the audio-vs-lyrics desync.  Replaced with a thin redirect: fetch
 * the track, start it via the normal pipeline, then bounce to
 * /music/karaoke so the FullScreenPlayer overlay (in karaoke mode)
 * is what the user actually sees.
 */
export function KaraokeStage() {
    const { trackId } = useParams();
    const navigate = useNavigate();
    const { controls } = useMusicPlayer();
    const passedTrack = (window.history.state?.usr?.track) || null;

    useEffect(() => {
        let active = true;
        const launch = (track) => {
            if (!active || !track) return;
            startKaraokeFor(controls, track);
            // Land the user on the karaoke landing page so the
            // FullScreenPlayer overlay (which is mounted by the
            // MiniPlayer inside MusicLayout) has a host route.
            navigate('/music/karaoke', { replace: true });
        };
        if (passedTrack) { launch(passedTrack); return undefined; }
        musicAPI.search(trackId).then((r) => {
            const found = (r?.data?.tracks || r?.tracks || [])
                .find((t) => String(t.id) === String(trackId));
            launch(found || null);
        }).catch(() => { navigate('/music/karaoke', { replace: true }); });
        return () => { active = false; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return (
        <div
            className="tunes-karaoke-stage tunes-karaoke-stage--loading"
            data-testid="karaoke-stage-redirect"
        >
            <p>Opening karaoke…</p>
        </div>
    );
}
