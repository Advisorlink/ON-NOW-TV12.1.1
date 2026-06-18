// ON NOW TV TUNES — Album detail page (Vesper-style)
// =============================================================
// Layout matches the Neon Dreams reference:
//   - Cover top-left + ALBUM eyebrow + huge title + cyan artist
//   - Genre · Year · songs · Dolby chip meta row
//   - Short synopsis
//   - Play Album (white pill) / Shuffle / Add to Library / ⋯
//   - Track list with cyan-highlighted current row
import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
    Play, Pause, Plus, Shuffle, MoreHorizontal, Check,
} from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import useTuneTap from '../../hooks/useTuneTap';
import {
    isMusicLiked, toggleMusicLike, subscribeMusicLibrary,
} from '../../lib/music-library';

function fmtDur(secs) {
    const m = Math.floor((secs || 0) / 60);
    const s = String((secs || 0) % 60).padStart(2, '0');
    return `${m}:${s}`;
}

function albumCover(a) {
    return a?.cover_xl || a?.cover_big || a?.cover_medium || a?.cover || '';
}

/* v2.10.54 — AlbumTrackRow extracted so we can call `useTuneTap`
   per-row (hooks can't be called inside a `.map()` callback).
   Quick tap plays the track; re-tap on the currently-playing row
   opens FullScreen; press-and-hold opens the "Add to library"
   modal. */
function AlbumTrackRow({ t, i, tracks, isFirst, firstTrackRef, hasAutoFocusedRef, dataId }) {
    const { state } = useMusicPlayer();
    const isCurrent = state.current?.id === t.id;
    const isPlaying = isCurrent && state.isPlaying;
    const isExplicit = t.explicit_lyrics;
    const tap = useTuneTap({ kind: 'track', item: t, list: tracks });
    return (
        <div
            ref={(node) => {
                if (!isFirst || !node) return;
                firstTrackRef.current = node;
                if (hasAutoFocusedRef.current !== dataId) {
                    hasAutoFocusedRef.current = dataId;
                    window.requestAnimationFrame(() => {
                        try { node.focus({ preventScroll: false }); }
                        catch { /* ignore */ }
                    });
                }
            }}
            className={
                'tunes-track-row' +
                (isCurrent ? ' tunes-track-row--playing' : '')
            }
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            data-testid={`tunes-album-track-${t.id}`}
            {...tap}
        >
            <div className="tunes-track-row__num">
                {isCurrent
                    ? (isPlaying
                        ? <Pause size={14} />
                        : <Play size={14} fill="currentColor" />)
                    : (i + 1)}
            </div>
            <div>
                <p className="tunes-track-row__title">{t.title}</p>
            </div>
            <span style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                {isExplicit && (
                    <span className="tunes-track-row__pill">E</span>
                )}
            </span>
            <span className="tunes-track-row__time">{fmtDur(t.duration)}</span>
        </div>
    );
}

export default function MusicAlbum() {
    const { id } = useParams();
    const navigate = useNavigate();
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);
    const [liked, setLiked] = useState(false);
    const { state, controls } = useMusicPlayer();
    // v2.10.37 — User asked: "when you choose an album, it should
    // focus on the top song to start with".  We grab a ref to the
    // first track row and call .focus() once the tracks render.
    // Picking the first SONG (not the Play Album button) is more
    // useful for D-pad users — pressing Down/OK from there starts
    // playback of that exact song, and pressing Up takes them back
    // to the action row.  We auto-focus exactly once per album
    // load (gated on `data.id`) so a freshly-clicked Play button
    // doesn't get stolen back on every render.
    const firstTrackRef = React.useRef(null);
    const hasAutoFocusedRef = React.useRef(null);

    useEffect(() => {
        setData(null);
        setErr(null);
        // Reset the auto-focus guard whenever the album id changes
        // so re-navigating between albums in the same session
        // refocuses each new album's first track.
        hasAutoFocusedRef.current = null;
        // v2.10.54 — One-shot auto-retry on transient 404/500 from
        // Deezer's /album endpoint (rate-limit / edge-cache miss).
        // User: "you have to go back out of it and back into it"
        // before the album loads.  Retry once 600 ms later.
        let cancelled = false;
        const fetchOnce = () => musicAPI.album(id).then((r) => r.data || r);
        fetchOnce()
            .then((d) => { if (!cancelled) setData(d); })
            .catch(() => new Promise((res) => setTimeout(res, 600))
                .then(fetchOnce)
                .then((d) => { if (!cancelled) setData(d); })
                .catch((e) => { if (!cancelled) setErr(e?.message || 'failed to load'); }),
            );
        return () => { cancelled = true; };
    }, [id]);

    useEffect(() => {
        if (!data) return;
        const update = () => setLiked(isMusicLiked('album', data.id));
        update();
        return subscribeMusicLibrary(update);
    }, [data]);

    if (err) return <div className="tunes-empty">Couldn&apos;t load album. {err}</div>;
    if (!data) {
        return (
            <div data-testid="music-album">
                <div className="tunes-album__head">
                    <div className="tunes-skel" style={{ aspectRatio: '1 / 1' }} />
                    <div>
                        <div className="tunes-skel" style={{ height: 14, width: 80, marginBottom: 12 }} />
                        <div className="tunes-skel" style={{ height: 64, width: 420, marginBottom: 14 }} />
                        <div className="tunes-skel" style={{ height: 22, width: 240 }} />
                    </div>
                </div>
            </div>
        );
    }

    const tracks = data.tracks || [];
    const totalSec = tracks.reduce((s, t) => s + (t.duration || 0), 0);
    const totalMin = Math.round(totalSec / 60);
    const cover = albumCover(data);

    return (
        <div className="tunes-album" data-testid="music-album">
            <div
                className="tunes-album__backdrop"
                style={cover ? { backgroundImage: `url(${cover})` } : undefined}
            />

            {/* v2.10.10 — Two-column layout: sticky header on the
                LEFT, scrolling track list on the RIGHT. Cover + meta
                + actions all stay anchored to the viewport while
                the user paddles through the track list. */}
            <div className="tunes-album__layout">
                <div className="tunes-album__head">
                    <div className="tunes-album__cover">
                        {cover && <img src={cover} alt={data.title} />}
                    </div>
                    <div className="tunes-album__info">
                        <p className="tunes-album__eyebrow">Album</p>
                        <h1 className="tunes-album__title">{data.title}</h1>
                        <p className="tunes-album__artist">
                            by{' '}
                            <span
                                role="link"
                                tabIndex={0}
                                onClick={() => data.artist && navigate(`/music/artist/${data.artist.id}`)}
                                style={{ textTransform: 'uppercase', cursor: 'pointer' }}
                            >
                                {data.artist?.name || ''}
                            </span>
                        </p>
                        <div className="tunes-album__meta">
                            {data.genres?.[0]?.name && <span>{data.genres[0].name}</span>}
                            {data.release_date && (
                                <>
                                    <span className="tunes-hero__meta-dot" />
                                    <span>{data.release_date.slice(0, 4)}</span>
                                </>
                        )}
                        {tracks.length > 0 && (
                            <>
                                <span className="tunes-hero__meta-dot" />
                                <span>{tracks.length} Songs</span>
                            </>
                        )}
                        {totalMin > 0 && (
                            <>
                                <span className="tunes-hero__meta-dot" />
                                <span>{totalMin} min</span>
                            </>
                        )}
                    </div>
                    {data.description && (
                        <p className="tunes-album__synopsis">{data.description}</p>
                    )}
                    <div className="tunes-album__actions" data-hero-actions>
                        <button
                            type="button"
                            className="tunes-btn tunes-btn--primary"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => tracks.length && controls.playTrack(tracks[0], tracks)}
                            data-testid="tunes-album-play"
                        >
                            <Play size={18} fill="#0a0118" />
                            Play Album
                        </button>
                        <button
                            type="button"
                            className="tunes-btn tunes-btn--ghost"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => {
                                if (!tracks.length) return;
                                const shuf = [...tracks].sort(() => Math.random() - 0.5);
                                controls.playTrack(shuf[0], shuf);
                            }}
                            data-testid="tunes-album-shuffle"
                        >
                            <Shuffle size={16} />
                            Shuffle
                        </button>
                        <button
                            type="button"
                            className="tunes-btn tunes-btn--ghost"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => toggleMusicLike('album', data)}
                            data-testid="tunes-album-like"
                        >
                            {liked ? <Check size={16} /> : <Plus size={16} />}
                            {liked ? 'In Library' : 'Add to Library'}
                        </button>
                        <button
                            type="button"
                            className="tunes-iconbtn"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            style={{ width: 48, height: 48, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.14)' }}
                            data-testid="tunes-album-more"
                            aria-label="More"
                        >
                            <MoreHorizontal size={20} />
                        </button>
                    </div>
                </div>
            </div>

            <div className="tunes-track-list">
                {tracks.map((t, i) => (
                    <AlbumTrackRow
                        key={t.id}
                        t={t}
                        i={i}
                        tracks={tracks}
                        isFirst={i === 0}
                        firstTrackRef={firstTrackRef}
                        hasAutoFocusedRef={hasAutoFocusedRef}
                        dataId={data.id}
                    />
                ))}
            </div>
            </div>
        </div>
    );
}
