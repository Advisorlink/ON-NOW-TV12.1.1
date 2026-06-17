// v2.8.78 — Music Artist page TV-friendly rewrite.
//
// User feedback: "everything's blown up so big, it doesn't actually
// make sense or it's not easy to use".  The previous layout used a
// fixed 280px circular photo + 60px title + giant track rows which
// dwarfed the focusable area on a TV.  This rewrite:
//   • Hero uses clamp() across photo/title sizes so it scales 720p–4K.
//   • Tighter track rows with a clear focus ring (the focus engine
//     handles the outline; we just give it enough padding to read
//     well from the couch).
//   • Discography uses the same kk-tile-like square cards with
//     subtle blue glow so the page feels coherent with the rest of
//     the Tunes app.
//   • Page max-width capped so on a 1920px TV the content never
//     spans the full screen — easier to scan.

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
    // v2.10.40 — User asked: "when you choose an album or when you
    // choose an artist, the focus needs to go to the top line of the
    // Popular".  We grab a ref to the first track row in the Popular
    // section and auto-focus it once the data has loaded.  Gated by
    // a ref so re-renders don't steal focus back on every state
    // change.  Reset to null whenever the artist id changes so the
    // next artist's first track gets focused too.
    const firstTrackRef = React.useRef(null);
    const hasAutoFocusedRef = React.useRef(null);

    useEffect(() => {
        setData(null);
        hasAutoFocusedRef.current = null;
        musicAPI.artist(id).then((r) => setData(r.data || r)).catch((e) => setErr(e.message || 'failed'));
    }, [id]);

    if (err) return <div className="tunes-empty">Couldn&apos;t load artist. {err}</div>;
    if (!data) return <div className="tunes-empty">Loading…</div>;

    const top = data.top_tracks || [];

    return (
        <div data-testid="music-artist" className="tunes-artist-page">
            <header className="tunes-artist-hero">
                <img
                    src={data.picture || ''}
                    alt={data.name}
                    className="tunes-artist-hero__photo"
                    loading="lazy"
                />
                <div className="tunes-artist-hero__copy">
                    <p className="tunes-artist-hero__eyebrow">
                        ARTIST{data.nb_fan ? ` · ${data.nb_fan.toLocaleString()} FANS` : ''}
                    </p>
                    <h1 className="tunes-artist-hero__name">{data.name}</h1>
                    <button
                        className="tunes-btn-primary tunes-artist-hero__play"
                        onClick={() => top.length && controls.playTrack(top[0], top)}
                        data-testid="tunes-artist-play"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                    >
                        <Play size={16} style={{ marginLeft: 2 }} />
                        Play top tracks
                    </button>
                </div>
            </header>

            {top.length > 0 && (
                <section className="tunes-artist-section">
                    <h2 className="tunes-artist-section__title">Popular</h2>
                    <div className="tunes-artist-tracklist">
                        {top.slice(0, 10).map((t, i) => {
                            const isFirst = i === 0;
                            return (
                            <button
                                type="button"
                                key={t.id}
                                ref={(node) => {
                                    // v2.10.40 — Capture first track for auto-focus.
                                    if (!isFirst || !node) return;
                                    firstTrackRef.current = node;
                                    if (hasAutoFocusedRef.current !== data.id) {
                                        hasAutoFocusedRef.current = data.id;
                                        window.requestAnimationFrame(() => {
                                            try { node.focus({ preventScroll: false }); }
                                            catch { /* ignore */ }
                                        });
                                    }
                                }}
                                className="tunes-artist-trackrow"
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                onClick={() => controls.playTrack(t, top)}
                                onKeyDown={(e) => { if (e.key === 'Enter') controls.playTrack(t, top); }}
                                data-testid={`tunes-artist-track-${t.id}`}
                            >
                                <span className="tunes-artist-trackrow__num">{i + 1}</span>
                                <img
                                    src={t.album?.cover || ''}
                                    alt=""
                                    className="tunes-artist-trackrow__art"
                                    loading="lazy"
                                />
                                <span className="tunes-artist-trackrow__meta">
                                    <span className="tunes-artist-trackrow__title">{t.title}</span>
                                    <span className="tunes-artist-trackrow__album">{t.album?.title || ''}</span>
                                </span>
                                <span className="tunes-artist-trackrow__duration">{fmtDur(t.duration)}</span>
                            </button>
                            );
                        })}
                    </div>
                </section>
            )}

            {data.albums?.length > 0 && (
                <section className="tunes-artist-section">
                    <h2 className="tunes-artist-section__title">Discography</h2>
                    <div className="tunes-artist-albums">
                        {data.albums.map((a) => (
                            <Link
                                data-focusable="true"
                                data-focus-style="tile"
                                tabIndex={0}
                                key={a.id}
                                to={`/music/album/${a.id}`}
                                className="tunes-artist-albumcard"
                            >
                                <img
                                    src={a.cover || ''}
                                    alt=""
                                    className="tunes-artist-albumcard__art"
                                    loading="lazy"
                                />
                                <div className="tunes-artist-albumcard__body">
                                    <p className="tunes-artist-albumcard__title">{a.title}</p>
                                    <p className="tunes-artist-albumcard__year">{a.release_date?.slice(0, 4) || ''}</p>
                                </div>
                            </Link>
                        ))}
                    </div>
                </section>
            )}
        </div>
    );
}
