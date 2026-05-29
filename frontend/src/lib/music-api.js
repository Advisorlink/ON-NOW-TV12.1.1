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
};
