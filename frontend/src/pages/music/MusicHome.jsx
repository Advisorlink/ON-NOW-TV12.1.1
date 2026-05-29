// ON NOW TV TUNES — Music Home (Vesper-style)
// =============================================================
// Layout (top → bottom):
//   1. Rotating hero billboard with trending albums (cyan glow)
//   2. "Trending Now" shelf — square album covers
//   3. "Top Artists" shelf — circular artist tiles
//   4. "Top Charts" shelf — square album covers
//   5. "Moods" grid — coloured mood tiles
//   6. "Browse Genres" grid — photographic genre tiles
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
    Play, Plus, Info, Sparkles, Sun, Moon, Flame, Zap, Heart, Headphones,
} from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

/** Visual heuristic: best image URL for a Deezer album. */
function albumCover(a) {
    return a?.cover_xl || a?.cover_big || a?.cover_medium || a?.cover || '';
}

/** Map Deezer artist picture sizes. */
function artistPic(a) {
    return a?.picture_xl || a?.picture_big || a?.picture_medium || a?.picture || '';
}

/**
 * Hero billboard — auto-rotates every ~9.5 s through a list of trending
 * albums and artists.  Blurred backdrop + framed cover on the right +
 * eyebrow/title/meta on the left.
 */
function MusicHero({ slides }) {
    const [idx, setIdx] = useState(0);
    const navigate = useNavigate();
    const { controls } = useMusicPlayer();

    useEffect(() => { setIdx(0); }, [slides]);

    useEffect(() => {
        if (!slides || slides.length <= 1) return;
        const t = setInterval(
            () => setIdx((i) => (i + 1) % slides.length),
            9500,
        );
        return () => clearInterval(t);
    }, [slides]);

    if (!slides || !slides.length) return null;

    const handleKey = (e) => {
        if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
        e.preventDefault();
        e.stopPropagation();
        const container = e.currentTarget.closest('[data-hero-actions]');
        if (!container) return;
        const btns = Array.from(container.querySelectorAll('[data-focusable="true"]'));
        const cur = btns.indexOf(e.currentTarget);
        if (cur < 0) return;
        const next = e.key === 'ArrowRight' ? cur + 1 : cur - 1;
        btns[Math.max(0, Math.min(btns.length - 1, next))]?.focus();
    };

    return (
        <section className="tunes-hero" data-testid="tunes-hero">
            {slides.map((slide, i) => (
                <div
                    key={slide.id}
                    className="tunes-hero__slide"
                    data-active={i === idx}
                    aria-hidden={i !== idx}
                >
                    <div
                        className="tunes-hero__bg"
                        style={{ backgroundImage: `url(${slide.bgImage})` }}
                    />
                    <div className="tunes-hero__bg-ring" />
                    <div className="tunes-hero__scrim-y" />
                    <div className="tunes-hero__scrim-x" />

                    <div className="tunes-hero__content">
                        <div className="tunes-hero__text">
                            <p className="tunes-hero__eyebrow">{slide.eyebrow}</p>
                            <h1 className="tunes-hero__title">
                                {slide.titlePrefix && (
                                    <>{slide.titlePrefix}<br /></>
                                )}
                                <em>{slide.title}</em>
                            </h1>
                            <div className="tunes-hero__meta">
                                <strong>{slide.artist}</strong>
                                {slide.meta?.map((m, mi) => (
                                    <React.Fragment key={mi}>
                                        <span className="tunes-hero__meta-dot" />
                                        <span>{m}</span>
                                    </React.Fragment>
                                ))}
                            </div>
                            {slide.synopsis && (
                                <p className="tunes-hero__synopsis">
                                    {slide.synopsis}
                                </p>
                            )}
                            <div className="tunes-hero__buttons" data-hero-actions>
                                <button
                                    type="button"
                                    className="tunes-btn tunes-btn--primary"
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onClick={() => {
                                        if (slide.kind === 'album') {
                                            navigate(`/music/album/${slide.id}`);
                                        } else if (slide.kind === 'artist') {
                                            navigate(`/music/artist/${slide.id}`);
                                        } else if (slide.kind === 'track' && slide.track) {
                                            controls.playTrack(slide.track, [slide.track]);
                                        }
                                    }}
                                    onKeyDown={handleKey}
                                    data-testid={`tunes-hero-play-${slide.id}`}
                                >
                                    <Play size={18} fill="#06080f" />
                                    Play
                                </button>
                                <button
                                    type="button"
                                    className="tunes-btn tunes-btn--ghost"
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onClick={() => {
                                        if (slide.kind === 'album') navigate(`/music/album/${slide.id}`);
                                        else if (slide.kind === 'artist') navigate(`/music/artist/${slide.id}`);
                                    }}
                                    onKeyDown={handleKey}
                                    data-testid={`tunes-hero-info-${slide.id}`}
                                >
                                    <Info size={16} />
                                    More Info
                                </button>
                                <button
                                    type="button"
                                    className="tunes-btn tunes-btn--outline"
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onKeyDown={handleKey}
                                    data-testid={`tunes-hero-list-${slide.id}`}
                                >
                                    <Plus size={16} />
                                    Add to Library
                                </button>
                            </div>
                        </div>

                        <div className="tunes-hero__art-wrap" aria-hidden="true">
                            <img
                                src={slide.coverImage}
                                alt={slide.title}
                                className="tunes-hero__art"
                                loading="eager"
                            />
                        </div>
                    </div>
                </div>
            ))}

            <div className="tunes-hero__dots">
                {slides.map((_, i) => (
                    <span
                        key={i}
                        className="tunes-hero__dot"
                        data-active={i === idx}
                    />
                ))}
            </div>
        </section>
    );
}

/** Square album tile (cover + title + subtitle). */
function AlbumTile({ album }) {
    const cover = albumCover(album);
    return (
        <Link
            to={`/music/album/${album.id}`}
            className="tunes-tile"
            data-testid={`tunes-album-${album.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
        >
            <div className="tunes-tile__art-frame">
                {cover ? (
                    <img
                        src={cover}
                        alt={album.title}
                        className="tunes-tile__art"
                        loading="lazy"
                    />
                ) : (
                    <div className="tunes-tile__art-fallback">
                        {(album.title || '?')[0]}
                    </div>
                )}
            </div>
            <p className="tunes-tile__title">{album.title}</p>
            <p className="tunes-tile__subtitle">{album.artist?.name || ''}</p>
        </Link>
    );
}

/** Track tile (still uses album cover — clicking plays the track). */
function TrackTile({ track, queue }) {
    const { controls } = useMusicPlayer();
    const cover = albumCover(track.album);
    return (
        <button
            type="button"
            className="tunes-tile"
            data-testid={`tunes-track-${track.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => controls.playTrack(track, queue)}
        >
            <div className="tunes-tile__art-frame">
                {cover ? (
                    <img
                        src={cover}
                        alt={track.title}
                        className="tunes-tile__art"
                        loading="lazy"
                    />
                ) : (
                    <div className="tunes-tile__art-fallback">
                        {(track.title || '?')[0]}
                    </div>
                )}
            </div>
            <p className="tunes-tile__title">{track.title}</p>
            <p className="tunes-tile__subtitle">{track.artist?.name || ''}</p>
        </button>
    );
}

/** Round artist tile. */
function ArtistTile({ artist }) {
    const pic = artistPic(artist);
    return (
        <Link
            to={`/music/artist/${artist.id}`}
            className="tunes-tile tunes-tile--artist"
            data-testid={`tunes-artist-${artist.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
        >
            <div className="tunes-tile__art-frame">
                {pic ? (
                    <img
                        src={pic}
                        alt={artist.name}
                        className="tunes-tile__art"
                        loading="lazy"
                    />
                ) : (
                    <div className="tunes-tile__art-fallback">
                        {(artist.name || '?')[0]}
                    </div>
                )}
            </div>
            <p className="tunes-tile__title">{artist.name}</p>
            <p className="tunes-tile__subtitle">
                {artist.nb_fan ? `${Math.round(artist.nb_fan / 1000)}K fans` : 'Artist'}
            </p>
        </Link>
    );
}

/** Generic shelf header + horizontal scroll rail. */
function Shelf({ eyebrow, title, children, testId }) {
    return (
        <section
            className="tunes-shelf"
            data-testid={testId || 'shelf-page'}
        >
            <div className="tunes-shelf__header">
                <div className="tunes-shelf__header-left">
                    {eyebrow && <span className="tunes-shelf__eyebrow">{eyebrow}</span>}
                    <h2 className="tunes-shelf__title">{title}</h2>
                </div>
            </div>
            <div className="tunes-shelf__rail">{children}</div>
        </section>
    );
}

/** Curated mood tiles — six emotion-based "ways to listen". */
const MOODS = [
    { id: 'chill',     title: 'Chill',     subtitle: 'Easy listening',   color: 'linear-gradient(135deg, #5b8def, #2a82b8)', icon: Moon,       q: 'chill lofi' },
    { id: 'energetic', title: 'Energetic', subtitle: 'High BPM',          color: 'linear-gradient(135deg, #ff5e62, #ff9966)', icon: Flame,       q: 'workout' },
    { id: 'romantic',  title: 'Romantic',  subtitle: 'Slow burn',         color: 'linear-gradient(135deg, #d65db1, #845ec2)', icon: Heart,       q: 'romantic love songs' },
    { id: 'focus',     title: 'Focus',     subtitle: 'Lock in',            color: 'linear-gradient(135deg, #1e3c72, #2a5298)', icon: Headphones,  q: 'focus instrumental' },
    { id: 'party',     title: 'Party',     subtitle: 'Turn it up',         color: 'linear-gradient(135deg, #f7971e, #ffd200)', icon: Zap,         q: 'party hits' },
    { id: 'sunshine',  title: 'Sunshine',  subtitle: 'Feel-good vibes',    color: 'linear-gradient(135deg, #ff9a44, #fc6076)', icon: Sun,         q: 'feel good' },
];

function MoodTile({ mood }) {
    const Icon = mood.icon;
    const navigate = useNavigate();
    return (
        <button
            type="button"
            className="tunes-mood-tile"
            style={{ background: mood.color }}
            data-testid={`tunes-mood-${mood.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => navigate(`/music/search?q=${encodeURIComponent(mood.q)}`)}
        >
            <p className="tunes-mood-tile__title">{mood.title}</p>
            <p className="tunes-mood-tile__subtitle">{mood.subtitle}</p>
            <span className="tunes-mood-tile__icon">
                <Icon size={36} strokeWidth={1.5} />
            </span>
        </button>
    );
}

function GenreTile({ genre }) {
    return (
        <Link
            to={`/music/genre/${genre.id}`}
            className="tunes-genre-tile"
            data-testid={`tunes-genre-${genre.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            style={{ backgroundImage: genre.picture ? `url(${genre.picture})` : undefined }}
        >
            <span className="tunes-genre-tile__label">{genre.name}</span>
        </Link>
    );
}

/** Build the rotating hero slides from the home payload.
 *  Picks up to five albums or top tracks to feature. */
function buildHeroSlides(home) {
    if (!home) return [];
    const slides = [];

    // Top tracks (use the album they belong to as a "trending" feature).
    const topTracks = home.shelves?.find((s) => s.id === 'top-tracks')?.items || [];
    topTracks.slice(0, 2).forEach((t, i) => {
        const cover = albumCover(t.album);
        if (!cover) return;
        slides.push({
            id: `track-${t.id}`,
            kind: 'track',
            track: t,
            title: t.title,
            artist: t.artist?.name || '',
            eyebrow: i === 0 ? 'TRENDING · NOW PLAYING' : 'CHARTING NOW',
            meta: [t.album?.title || 'Single', t.duration ? `${Math.round(t.duration / 60)} min` : null].filter(Boolean),
            synopsis: t.album?.title
                ? `From the album "${t.album.title}".  Streaming now on ON NOW TV Tunes.`
                : 'A breakout single climbing the charts this week.',
            coverImage: cover,
            bgImage: cover,
        });
    });

    // New releases / Top albums
    const newReleases = home.shelves?.find((s) => s.id === 'new-releases')?.items || [];
    newReleases.slice(0, 2).forEach((a) => {
        const cover = albumCover(a);
        if (!cover) return;
        slides.push({
            id: `album-${a.id}`,
            kind: 'album',
            title: a.title,
            artist: a.artist?.name || '',
            eyebrow: 'NEW ALBUM',
            meta: [
                a.nb_tracks ? `${a.nb_tracks} songs` : null,
                a.release_date ? a.release_date.slice(0, 4) : null,
            ].filter(Boolean),
            synopsis: a.title
                ? `A sonic odyssey by ${a.artist?.name}.  "${a.title}" is out now.`
                : '',
            coverImage: cover,
            bgImage: cover,
        });
    });

    // Top albums fallback
    if (slides.length < 3) {
        const topAlbums = home.shelves?.find((s) => s.id === 'top-albums')?.items || [];
        topAlbums.slice(0, 5 - slides.length).forEach((a) => {
            const cover = albumCover(a);
            if (!cover) return;
            slides.push({
                id: `album-${a.id}`,
                kind: 'album',
                title: a.title,
                artist: a.artist?.name || '',
                eyebrow: 'TRENDING ALBUM',
                meta: ['Top chart', a.nb_tracks ? `${a.nb_tracks} songs` : null].filter(Boolean),
                synopsis: `"${a.title}" by ${a.artist?.name} is trending now.`,
                coverImage: cover,
                bgImage: cover,
            });
        });
    }

    return slides;
}

export default function MusicHome() {
    const [data, setData] = useState(null);
    const [err, setErr] = useState(null);

    useEffect(() => {
        musicAPI.home()
            .then((res) => setData(res.data || res))
            .catch((e) => setErr(e.message || 'failed to load'));
    }, []);

    const heroSlides = useMemo(() => buildHeroSlides(data), [data]);

    const trendingAlbums =
        data?.shelves?.find((s) => s.id === 'top-albums')?.items
        || data?.shelves?.find((s) => s.id === 'new-releases')?.items
        || [];
    const topTracks = data?.shelves?.find((s) => s.id === 'top-tracks')?.items || [];
    const topArtists = data?.shelves?.find((s) => s.id === 'top-artists')?.items || [];
    const newReleases = data?.shelves?.find((s) => s.id === 'new-releases')?.items || [];

    return (
        <div data-testid="music-home">
            {heroSlides.length > 0 && <MusicHero slides={heroSlides} />}

            {!data && !err && (
                <>
                    <div className="tunes-skel" style={{ height: 520, margin: 0, borderRadius: 0 }} />
                    {[1, 2, 3].map((i) => (
                        <section key={i} className="tunes-shelf">
                            <div className="tunes-shelf__header">
                                <div className="tunes-skel" style={{ height: 30, width: 240, marginBottom: 14 }} />
                            </div>
                            <div className="tunes-shelf__rail">
                                {[1, 2, 3, 4, 5, 6].map((j) => (
                                    <div key={j} className="tunes-skel" style={{ flexShrink: 0, width: 200, aspectRatio: '1 / 1' }} />
                                ))}
                            </div>
                        </section>
                    ))}
                </>
            )}

            {err && <div className="tunes-empty">Couldn&apos;t load music — {err}</div>}

            {trendingAlbums.length > 0 && (
                <Shelf eyebrow="TRENDING" title="Trending Now" testId="shelf-trending">
                    {trendingAlbums.slice(0, 18).map((a) => (
                        <AlbumTile key={a.id} album={a} />
                    ))}
                </Shelf>
            )}

            {topTracks.length > 0 && (
                <Shelf eyebrow="HOT 100" title="Top Charts" testId="shelf-charts">
                    {topTracks.slice(0, 18).map((t) => (
                        <TrackTile key={t.id} track={t} queue={topTracks} />
                    ))}
                </Shelf>
            )}

            {topArtists.length > 0 && (
                <Shelf eyebrow="ARTISTS" title="Top Artists" testId="shelf-artists">
                    {topArtists.slice(0, 18).map((a) => (
                        <ArtistTile key={a.id} artist={a} />
                    ))}
                </Shelf>
            )}

            {newReleases.length > 0 && (
                <Shelf eyebrow="FRESH" title="New Releases" testId="shelf-new">
                    {newReleases.slice(0, 18).map((a) => (
                        <AlbumTile key={a.id} album={a} />
                    ))}
                </Shelf>
            )}

            <section className="tunes-section" data-testid="shelf-moods">
                <div className="tunes-section__head">
                    <h2 className="tunes-section__title">Moods</h2>
                    <p className="tunes-section__subtitle">
                        Pick a vibe and we&apos;ll curate the perfect mix.
                    </p>
                </div>
                <div className="tunes-mood-grid">
                    {MOODS.map((m) => <MoodTile key={m.id} mood={m} />)}
                </div>
            </section>

            {data?.genres?.length > 0 && (
                <section className="tunes-section" data-testid="shelf-genres">
                    <div className="tunes-section__head">
                        <h2 className="tunes-section__title">Browse Genres</h2>
                    </div>
                    <div className="tunes-genre-grid">
                        {data.genres.map((g) => <GenreTile key={g.id} genre={g} />)}
                    </div>
                </section>
            )}

            <div style={{ height: 60 }} />
        </div>
    );
}
