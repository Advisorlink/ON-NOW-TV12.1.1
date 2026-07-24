// ON NOW TV TUNES — Music Home (Vesper-style)
// =============================================================
// Layout (top → bottom):
//   1. Full-bleed rotating hero billboard
//   2. Horizontal shelves with one-line snap focus:
//        Trending Now (square album covers, text-on-cover)
//        Top Charts (square track tiles)
//        Top Artists (round)
//        New Releases (square)
//        Moods   (horizontal shelf, like Continue Watching)
//        Browse Genres (grid, full-width)
//
// Tiles follow Vesper's exact pattern: a single button with the
// title + artist OVERLAID on the bottom of the cover via a dark
// gradient scrim — so the focus ring only wraps the cover, never
// a separate text block.
import React, { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
    Play, Plus, Info, Sun, Moon, Flame, Zap, Heart, Headphones,
} from 'lucide-react';
import { musicAPI, isRealArt } from '../../lib/music-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';

/* -- Image helpers --------------------------------------------
 * v2.8.61 — Deezer CDN URLs contain the image size in the URL
 * itself (e.g. `…/cover/<hash>/1000x1000-000000-80-0-0.jpg`).
 * We rewrite `1000x1000` → `500x500` so each tile decodes in
 * ~30 ms on the HK1 box instead of 200 ms+ for the 1000² source.
 * Visually identical at the 220 px max tile width we render. */
function smallerDeezerUrl(url) {
    if (!url || typeof url !== 'string') return url;
    return url.replace(/\/1000x1000-/, '/500x500-');
}
const albumCover = (a) => smallerDeezerUrl(
    a?.cover_big || a?.cover_medium || a?.cover || a?.cover_xl || ''
);
const artistPic = (a) => {
    const raw = a?.picture_big || a?.picture_medium || a?.picture || a?.picture_xl || '';
    return isRealArt(raw) ? smallerDeezerUrl(raw) : '';
};
/* Hero uses XL because the image is rendered at full viewport
 * width — XL is still only one image so the decode cost is
 * amortised across the hero's 10 s rotation window. */
const heroBg = (a) => a?.cover_xl || a?.picture_xl || a?.cover || a?.picture || '';

/* -- Hero rotating billboard ---------------------------------- */
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
        <section
            className="tunes-hero"
            data-testid="hero-billboard"
        >
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
                    {/* v2.10.10 — Sharp cover thumbnail rides on top
                        of the blurred wallpaper so the actual album
                        art is visible at native aspect ratio rather
                        than getting cropped to a 16:9 strip. */}
                    {slide.bgImage && (
                        <div
                            className="tunes-hero__cover"
                            style={{ backgroundImage: `url(${slide.bgImage})` }}
                            aria-hidden="true"
                        />
                    )}
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
                                    onClick={async () => {
                                        // v2.8.73 — Play ALWAYS plays.  Previous code
                                        // navigated to /music/album/{id} for album-kind
                                        // slides which hit the user's "Couldn't load
                                        // album HTTP 404" error.  Users expect Play to
                                        // make music start, period.  For album/artist
                                        // slides we fetch the entry and play its first
                                        // track — the explicit album-detail navigation
                                        // is reachable via the "More Info" button.
                                        try {
                                            if (slide.kind === 'track' && slide.track) {
                                                controls.playTrack(slide.track, [slide.track]);
                                                return;
                                            }
                                            if (slide.kind === 'album') {
                                                const rawId = String(slide.id).replace(/^album-/, '');
                                                const r = await musicAPI.album(rawId);
                                                const album = r?.data || r;
                                                const tracks = album?.tracks || [];
                                                if (tracks.length) {
                                                    controls.playTrack(tracks[0], tracks);
                                                    return;
                                                }
                                            }
                                            if (slide.kind === 'artist') {
                                                const rawId = String(slide.id).replace(/^artist-/, '');
                                                const r = await musicAPI.artist(rawId);
                                                const artist = r?.data || r;
                                                const tops = artist?.top_tracks || artist?.tracks || [];
                                                if (tops.length) {
                                                    controls.playTrack(tops[0], tops);
                                                    return;
                                                }
                                            }
                                        } catch { /* swallow — Play button never errors out */ }
                                    }}
                                    onKeyDown={handleKey}
                                    data-testid={`tunes-hero-play-${slide.id}`}
                                >
                                    <Play size={18} fill="#0a0118" />
                                    Play
                                </button>
                                <button
                                    type="button"
                                    className="tunes-btn tunes-btn--ghost"
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onClick={() => {
                                        // v2.12.9 — Hero slide ids are PREFIXED ("album-123",
                                        // "artist-45") — strip before routing, otherwise the
                                        // detail page fetches /api/music/album/album-123 → 404.
                                        if (slide.kind === 'album') navigate(`/music/album/${String(slide.id).replace(/^album-/, '')}`);
                                        else if (slide.kind === 'artist') navigate(`/music/artist/${String(slide.id).replace(/^artist-/, '')}`);
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

/* -- Album tile (text overlaid on cover) ---------------------- */
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
            {cover ? (
                <img
                    src={cover}
                    alt={album.title}
                    className="tunes-tile__art"
                    loading="lazy"
                    decoding="async"
                />
            ) : (
                <div className="tunes-tile__art-fallback">
                    {(album.title || '?')[0]}
                </div>
            )}
            <div className="tunes-tile__scrim" />
            <div className="tunes-tile__caption">
                <p className="tunes-tile__title">{album.title}</p>
                <p className="tunes-tile__subtitle">{album.artist?.name || ''}</p>
            </div>
        </Link>
    );
}

/* -- Track tile (uses album cover; clicking plays the track) -- */
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
            <div className="tunes-tile__caption">
                <p className="tunes-tile__title">{track.title}</p>
                <p className="tunes-tile__subtitle">{track.artist?.name || ''}</p>
            </div>
        </button>
    );
}

/* -- Artist tile (round, caption below) ----------------------- */
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
            <div className="tunes-tile__art-wrap">
                {pic ? (
                    <img
                        src={pic}
                        alt={artist.name}
                        className="tunes-tile__art"
                        loading="lazy"
                        decoding="async"
                    />
                ) : (
                    <div className="tunes-tile__art-fallback">
                        {(artist.name || '?')[0]}
                    </div>
                )}
            </div>
            <div className="tunes-tile__caption">
                <p className="tunes-tile__title">{artist.name}</p>
                <p className="tunes-tile__subtitle">
                    {artist.nb_fan ? `${Math.round(artist.nb_fan / 1000)}K fans` : 'Artist'}
                </p>
            </div>
        </Link>
    );
}

/* -- Generic shelf header + horizontal rail ------------------- *
 * We add `vesper-shelf` to the rail so useSpatialFocus's
 * `horizontalScroller()` lookup and `targetPage.querySelector(
 * '.vesper-shelf, [data-shelf-rail]')` bookmark logic find it
 * and apply identical edge-comfort scroll + per-rail column
 * memory as the Vesper home shelves.
 */
function Shelf({ eyebrow, title, children, testId }) {
    return (
        <section className="tunes-shelf" data-testid={testId || 'shelf-page'}>
            <header className="tunes-shelf__header">
                <div className="tunes-shelf__header-left">
                    {eyebrow && <span className="tunes-shelf__eyebrow">{eyebrow}</span>}
                    <h2 className="tunes-shelf__title">{title}</h2>
                </div>
            </header>
            <div className="tunes-shelf__rail vesper-shelf" data-shelf-rail>
                {children}
            </div>
        </section>
    );
}

/* -- Charts & Decades (wide-rectangle presets, at the bottom of
 *    the home feed, below Moods / Genres) ---
 *
 * v2.8.66 — User request: "top 100 Australia, top 100 USA, plus
 * decades like 80s / 90s / 2000s / golden oldies, as wide tiles
 * like Continue Watching, right before the Moods row".
 * v2.8.68 — Refresh: real photography backdrops (skylines for
 * country charts, era-defining stock for decades) with a soft
 * black scrim, and a tiny country-flag chip pinned top-right
 * on the country tiles.  Previous plain-gradient tiles felt
 * "old" per user feedback.
 *
 * All artwork is served from Unsplash CDN (unsplash.com/photos/…)
 * so we don't need to host or transcode anything.  Every image
 * is licensed for free commercial use.
 */
const CHART_PRESETS = [
    {
        id: 'top-au',      title: 'Top 100 Australia', subtitle: 'CHART TOPPERS',
        flag: '🇦🇺',
        // Sydney Opera House at sunset
        image: 'https://images.unsplash.com/photo-1523482580672-f109ba8cb9be?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(0,0,0,0) 40%, rgba(0,0,0,0.7) 100%)',
    },
    {
        id: 'top-us',      title: 'Top 100 USA',       subtitle: 'BILLBOARD HOT',
        flag: '🇺🇸',
        // NYC skyline
        image: 'https://images.unsplash.com/photo-1496442226666-8d4d0e62e6e9?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(0,0,0,0) 40%, rgba(0,0,0,0.7) 100%)',
    },
    {
        id: 'top-uk',      title: 'Top 100 UK',        subtitle: 'OFFICIAL CHART',
        flag: '🇬🇧',
        // London / Big Ben at dusk
        image: 'https://images.unsplash.com/photo-1513635269975-59663e0ac1ad?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(0,0,0,0) 40%, rgba(0,0,0,0.7) 100%)',
    },
    {
        id: 'decade-2020s', title: "The 2020s",         subtitle: "NOW PLAYING",
        emblem: "'20s",
        // Modern neon / hologram vibes
        image: 'https://images.unsplash.com/photo-1518676590629-3dcba9c5a555?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(255,0,110,0.2) 0%, rgba(58,134,255,0.35) 60%, rgba(0,0,0,0.85) 100%)',
    },
    {
        id: 'decade-2010s', title: "The 2010s",         subtitle: "STREAMING ERA",
        emblem: "'10s",
        // Festival lights / Coachella-esque
        image: 'https://images.unsplash.com/photo-1516450360452-9312f5e86fc7?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(6,214,160,0.15) 0%, rgba(17,138,178,0.35) 60%, rgba(0,0,0,0.85) 100%)',
    },
    {
        id: 'decade-2000s', title: "The 2000s",         subtitle: "MP3 ERA",
        emblem: "'00s",
        // iPod-era product / retro tech
        image: 'https://images.unsplash.com/photo-1461360370896-922624d12aa1?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(204,43,94,0.15) 0%, rgba(117,58,136,0.35) 60%, rgba(0,0,0,0.85) 100%)',
    },
    {
        id: 'decade-90s',   title: "The '90s",           subtitle: "CD ERA",
        emblem: "'90s",
        // CDs / cassettes / retro
        image: 'https://images.unsplash.com/photo-1483412033650-1015ddeb83d1?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(67,206,162,0.2) 0%, rgba(24,90,157,0.4) 60%, rgba(0,0,0,0.85) 100%)',
    },
    {
        id: 'decade-80s',   title: "The '80s",           subtitle: "SYNTH & NEON",
        emblem: "'80s",
        // Retro neon synthwave
        image: 'https://images.unsplash.com/photo-1518972559570-7cc1309f3229?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(249,83,198,0.2) 0%, rgba(185,29,115,0.45) 60%, rgba(0,0,0,0.85) 100%)',
    },
    {
        id: 'decade-70s',   title: "The '70s",           subtitle: "DISCO & ROCK",
        emblem: "'70s",
        // Vinyl records / groovy
        image: 'https://images.unsplash.com/photo-1461360228754-6e81c478b882?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(255,128,8,0.2) 0%, rgba(255,200,55,0.35) 55%, rgba(0,0,0,0.85) 100%)',
    },
    {
        id: 'oldies',       title: "Golden Oldies",       subtitle: "'50s & '60s CLASSICS",
        emblem: '★',
        // Vintage vinyl / jukebox
        image: 'https://images.unsplash.com/photo-1470019693664-1d202d2c0907?w=800&q=75',
        tint: 'linear-gradient(180deg, rgba(212,175,55,0.2) 0%, rgba(124,95,22,0.5) 60%, rgba(0,0,0,0.9) 100%)',
    },
];

function ChartTile({ preset }) {
    const navigate = useNavigate();
    return (
        <button
            type="button"
            className="tunes-tile tunes-tile--chart"
            data-testid={`tunes-chart-${preset.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => navigate(`/music/chart/${preset.id}`)}
        >
            <img
                src={preset.image}
                alt=""
                loading="lazy"
                className="tunes-tile__chart-photo"
            />
            <div
                className="tunes-tile__chart-tint"
                style={{ background: preset.tint }}
            />
            {preset.flag && (
                <span className="tunes-tile__chart-flag" aria-hidden="true">
                    {preset.flag}
                </span>
            )}
            {preset.emblem && !preset.flag && (
                <span className="tunes-tile__chart-emblem" aria-hidden="true">
                    {preset.emblem}
                </span>
            )}
            <div className="tunes-tile__caption">
                <p className="tunes-tile__subtitle">{preset.subtitle}</p>
                <p className="tunes-tile__title">{preset.title}</p>
            </div>
        </button>
    );
}

/* -- Moods (horizontal one-line shelf, like Continue Watching) - */
const MOODS = [
    { id: 'chill',     title: 'Chill',     subtitle: 'EASY LISTENING', bg: 'linear-gradient(135deg, #5b8def 0%, #2a82b8 100%)', icon: Moon,       q: 'chill lofi' },
    { id: 'energetic', title: 'Energetic', subtitle: 'HIGH BPM',        bg: 'linear-gradient(135deg, #ff5e62 0%, #ff9966 100%)', icon: Flame,       q: 'workout' },
    { id: 'romantic',  title: 'Romantic',  subtitle: 'SLOW BURN',       bg: 'linear-gradient(135deg, #d65db1 0%, #845ec2 100%)', icon: Heart,       q: 'romantic love songs' },
    { id: 'focus',     title: 'Focus',     subtitle: 'LOCK IN',          bg: 'linear-gradient(135deg, #1e3c72 0%, #2a5298 100%)', icon: Headphones,  q: 'focus instrumental' },
    { id: 'party',     title: 'Party',     subtitle: 'TURN IT UP',       bg: 'linear-gradient(135deg, #f7971e 0%, #ffd200 100%)', icon: Zap,         q: 'party hits' },
    { id: 'sunshine',  title: 'Sunshine',  subtitle: 'FEEL-GOOD VIBES',  bg: 'linear-gradient(135deg, #ff9a44 0%, #fc6076 100%)', icon: Sun,         q: 'feel good' },
];

function MoodTile({ mood }) {
    const Icon = mood.icon;
    const navigate = useNavigate();
    return (
        <button
            type="button"
            className="tunes-tile tunes-tile--mood"
            style={{ background: mood.bg }}
            data-testid={`tunes-mood-${mood.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={() => navigate(`/music/search?q=${encodeURIComponent(mood.q)}`)}
        >
            <span className="tunes-tile__mood-icon" aria-hidden="true">
                <Icon size={44} strokeWidth={1.6} />
            </span>
            <div className="tunes-tile__scrim" />
            <div className="tunes-tile__caption">
                <p className="tunes-tile__title">{mood.title}</p>
                <p className="tunes-tile__subtitle">{mood.subtitle}</p>
            </div>
        </button>
    );
}

function GenreTile({ genre }) {
    return (
        <Link
            to={`/music/genre/${genre.id}`}
            state={{ name: genre.name }}
            className="tunes-genre-tile"
            data-testid={`tunes-genre-${genre.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            style={{ backgroundImage: genre.picture_medium ? `url(${genre.picture_medium})` : (genre.picture ? `url(${genre.picture})` : undefined) }}
        >
            <span className="tunes-genre-tile__label">{genre.name}</span>
        </Link>
    );
}

/* -- Build the rotating hero slides from the home payload ----- */
function buildHeroSlides(home) {
    if (!home) return [];
    const slides = [];

    const artistsById = {};
    (home.shelves?.find((s) => s.id === 'top-artists')?.items || []).forEach((a) => {
        artistsById[a.id] = a;
    });
    const bestBg = (artist, fallback) => {
        if (artist) {
            const pic = artist.picture_xl || artistsById[artist.id]?.picture_xl;
            if (pic && isRealArt(pic)) return pic;
        }
        return fallback;
    };

    const topTracks = home.shelves?.find((s) => s.id === 'top-tracks')?.items || [];
    topTracks.slice(0, 2).forEach((t, i) => {
        const cover = heroBg(t.album);
        const bg = bestBg(t.artist, cover);
        if (!bg) return;
        slides.push({
            id: `track-${t.id}`,
            kind: 'track',
            track: t,
            title: t.title,
            artist: t.artist?.name || '',
            eyebrow: i === 0 ? 'TRENDING · NOW PLAYING' : 'CHARTING NOW',
            meta: [t.album?.title || 'Single', t.duration ? `${Math.round(t.duration / 60)} min` : null].filter(Boolean),
            synopsis: t.album?.title
                ? `From the album "${t.album.title}". Streaming now on ON NOW TV Tunes.`
                : 'A breakout single climbing the charts this week.',
            bgImage: bg,
        });
    });

    const newReleases = home.shelves?.find((s) => s.id === 'new-releases')?.items || [];
    newReleases.slice(0, 2).forEach((a) => {
        const cover = heroBg(a);
        const bg = bestBg(a.artist, cover);
        if (!bg) return;
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
                ? `A sonic odyssey by ${a.artist?.name}. "${a.title}" is out now.`
                : '',
            bgImage: bg,
        });
    });

    if (slides.length < 3) {
        const topAlbums = home.shelves?.find((s) => s.id === 'top-albums')?.items || [];
        topAlbums.slice(0, 5 - slides.length).forEach((a) => {
            const cover = heroBg(a);
            const bg = bestBg(a.artist, cover);
            if (!bg) return;
            slides.push({
                id: `album-${a.id}`,
                kind: 'album',
                title: a.title,
                artist: a.artist?.name || '',
                eyebrow: 'TRENDING ALBUM',
                meta: ['Top chart', a.nb_tracks ? `${a.nb_tracks} songs` : null].filter(Boolean),
                synopsis: `"${a.title}" by ${a.artist?.name} is trending now.`,
                bgImage: bg,
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
                            <div className="tunes-shelf__rail vesper-shelf">
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

            {/* v2.8.69 — Charts & Eras shelf lives directly under
                New Releases per user request: they wanted the country
                / decade tiles to feel like a continuation of the
                "For You" catalogue, not a separate section at the
                bottom of the page. */}
            <Shelf eyebrow="TOP 100 & DECADES" title="Charts &amp; Eras" testId="shelf-charts-decades">
                {CHART_PRESETS.map((p) => <ChartTile key={p.id} preset={p} />)}
            </Shelf>

            <Shelf eyebrow="HOW DO YOU FEEL" title="Moods" testId="shelf-moods">
                {MOODS.map((m) => <MoodTile key={m.id} mood={m} />)}
            </Shelf>

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
