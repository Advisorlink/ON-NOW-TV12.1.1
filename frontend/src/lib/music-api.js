// ON NOW TV TUNES — Music API client
// =============================================================
// All requests go through REACT_APP_BACKEND_URL/api/music/*
// (the same backend as Vesper, mounted in server.py).
const BASE = `${process.env.REACT_APP_BACKEND_URL || ''}/api/music`;

async function jget(path) {
    const r = await fetch(`${BASE}${path}`, { credentials: 'omit' });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.json();
}

/* Deezer serves a light-grey person silhouette when an artist/album
 * has no artwork (URL has an empty md5 segment, e.g. /images/artist//
 * 250x250-...jpg).  Treat those as "no picture" so the UI can render
 * a themed fallback instead of a white blob on the dark theme. */
export function isRealArt(url) {
    if (!url) return false;
    if (url.includes('d41d8cd98f00b204e9800998ecf8427e')) return false;
    return !/\/images\/\w+\/\//.test(url);
}

export const musicAPI = {
    home: () => jget('/home'),
    search: (q) => jget(`/search?q=${encodeURIComponent(q)}`),
    album: (id) => jget(`/album/${id}`),
    artist: (id) => jget(`/artist/${id}`),
    genre: (id) => jget(`/genre/${id}`),

    radioTop: ({ country, limit = 50 } = {}) =>
        jget(`/radio/top?${country ? `country=${country}&` : ''}limit=${limit}`),
    radioGenres: () => jget('/radio/genres'),
    radioByTag: (tag, limit = 50) =>
        jget(`/radio/by-tag/${encodeURIComponent(tag)}?limit=${limit}`),
    radioCountries: () => jget('/radio/countries'),
    radioClick: (id) =>
        fetch(`${BASE}/radio/click/${id}`, { method: 'POST' }).catch(() => {}),

    podcastsTop: ({ country = 'us', genre } = {}) =>
        jget(`/podcasts/top?country=${country}${genre ? `&genre=${genre}` : ''}`),
    podcastsSearch: (q, limit = 20) =>
        jget(`/podcasts/search?q=${encodeURIComponent(q)}&limit=${limit}`),
    podcastEpisodes: (feedUrl) =>
        jget(`/podcasts/episodes?feed_url=${encodeURIComponent(feedUrl)}`),

    /** v2.8.60 — Synced lyrics for the V2 Karaoke screen.
     *  Returns `{ synced: [{ t, text }], plain, instrumental }`. */
    lyrics: ({ artist, title, album, duration } = {}) => {
        const params = new URLSearchParams({ artist: artist || '', title: title || '' });
        if (album)    params.set('album', album);
        if (duration) params.set('duration', String(Math.round(duration)));
        return jget(`/lyrics?${params.toString()}`);
    },
};
