// Mock catalog data — used until the real Stremio / Plex / Jellyfin
// integrations are wired in.  Imagery from /app/design_guidelines.json
// (curated cinematic Unsplash / Pexels assets).

const BACKDROPS = {
    cityNight:
        'https://images.unsplash.com/photo-1760662564270-a55ad0a8df2c?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjY2NzF8MHwxfHNlYXJjaHw0fHxjaW5lbWF0aWMlMjBuaWdodCUyMGxhbmRzY2FwZXxlbnwwfHx8fDE3Nzg0MjAwMTl8MA&ixlib=rb-4.1.0&q=85',
    foggyAlley:
        'https://images.pexels.com/photos/19943722/pexels-photo-19943722.jpeg?auto=compress&cs=tinysrgb&dpr=2&h=1080&w=1920',
    vintageLamps:
        'https://images.pexels.com/photos/14541770/pexels-photo-14541770.jpeg?auto=compress&cs=tinysrgb&dpr=2&h=1080&w=1920',
    redCarGarage:
        'https://images.unsplash.com/photo-1713341869534-540b1f39c35f?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjY2NzF8MHwxfHNlYXJjaHwyfHxjaW5lbWF0aWMlMjBuaWdodCUyMGxhbmRzY2FwZXxlbnwwfHx8fDE3Nzg0MjAwMTl8MA&ixlib=rb-4.1.0&q=85',
    streetLights:
        'https://images.unsplash.com/photo-1739461720624-c152c22dc207?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjY2NzF8MHwxfHNlYXJjaHwxfHxjaW5lbWF0aWMlMjBuaWdodCUyMGxhbmRzY2FwZXxlbnwwfHx8fDE3Nzg0MjAwMTl8MA&ixlib=rb-4.1.0&q=85',
};

const POSTERS = [
    'https://images.unsplash.com/photo-1727188211338-297f08d42827?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjA3MDB8MHwxfHNlYXJjaHwzfHxtb29keSUyMGNpbmVtYXRpYyUyMHBvcnRyYWl0fGVufDB8fHx8MTc3ODQyMDAxOXww&ixlib=rb-4.1.0&q=85',
    'https://images.pexels.com/photos/34742154/pexels-photo-34742154.jpeg?auto=compress&cs=tinysrgb&dpr=2&h=900&w=600',
    'https://images.unsplash.com/photo-1727188149972-ea9e1888a7d9?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjA3MDB8MHwxfHNlYXJjaHwyfHxtb29keSUyMGNpbmVtYXRpYyUyMHBvcnRyYWl0fGVufDB8fHx8MTc3ODQyMDAxOXww&ixlib=rb-4.1.0&q=85',
    'https://images.pexels.com/photos/33681513/pexels-photo-33681513.jpeg?auto=compress&cs=tinysrgb&dpr=2&h=900&w=600',
    'https://images.unsplash.com/photo-1727188150465-7824c4f2ce41?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjA3MDB8MHwxfHNlYXJjaHwxfHxtb29keSUyMGNpbmVtYXRpYyUyMHBvcnRyYWl0fGVufDB8fHx8MTc3ODQyMDAxOXww&ixlib=rb-4.1.0&q=85',
    'https://images.unsplash.com/photo-1727188211688-9d2890fca22c?crop=entropy&cs=srgb&fm=jpg&ixid=M3w4NjA3MDB8MHwxfHNlYXJjaHw0fHxtb29keSUyMGNpbmVtYXRpYyUyMHBvcnRyYWl0fGVufDB8fHx8MTc3ODQyMDAxOXww&ixlib=rb-4.1.0&q=85',
];

const poster = (i) => POSTERS[i % POSTERS.length];

export const HEROES = [
    {
        id: 'h1',
        title: 'Lantern in the Tide',
        eyebrow: 'Featured · Drama',
        year: 2025,
        runtime: '2h 14m',
        rating: 'TV-MA',
        genres: ['Drama', 'Mystery'],
        synopsis:
            'A dockworker stumbles on a derelict lighthouse logbook and finds the night reading him back. A slow-burn study of memory, fog, and the small towns we vanish into.',
        backdrop: BACKDROPS.vintageLamps,
        sources: ['Stremio · Cinemeta', 'Plex'],
    },
    {
        id: 'h2',
        title: 'Vespers',
        eyebrow: 'New Tonight · Limited Series',
        year: 2026,
        runtime: '6 episodes',
        rating: 'TV-MA',
        genres: ['Thriller', 'Noir'],
        synopsis:
            'In a city that only sleeps at dawn, a recovering forger trades counterfeits for confessions. A neon-soaked chamber piece told one evening at a time.',
        backdrop: BACKDROPS.cityNight,
        sources: ['Jellyfin', 'Stremio · Torrentio'],
    },
    {
        id: 'h3',
        title: 'The Quiet Houses',
        eyebrow: 'Critics Pick',
        year: 2024,
        runtime: '1h 52m',
        rating: 'R',
        genres: ['Horror', 'Atmospheric'],
        synopsis:
            'Four houses, one phone number, no one answering. A patient ghost story for the long dark hours of winter.',
        backdrop: BACKDROPS.foggyAlley,
        sources: ['Stremio · Cinemeta'],
    },
    {
        id: 'h4',
        title: 'Carbon Saint',
        eyebrow: 'Trending',
        year: 2025,
        runtime: '2h 04m',
        rating: 'R',
        genres: ['Crime', 'Neo-Noir'],
        synopsis:
            'A getaway driver inherits a parking garage and the catalogue of sins it stores. Every car, a confession.',
        backdrop: BACKDROPS.redCarGarage,
        sources: ['Plex', 'Stremio · Real-Debrid'],
    },
    {
        id: 'h5',
        title: 'Streetlight Sermons',
        eyebrow: 'Documentary · 2025',
        year: 2025,
        runtime: '1h 38m',
        rating: 'PG-13',
        genres: ['Documentary'],
        synopsis:
            'Six poets walk six cities at 3am, talking only to streetlights. A small, luminous film about the public solitude of insomniacs.',
        backdrop: BACKDROPS.streetLights,
        sources: ['Jellyfin'],
    },
];

const titles = {
    continueWatching: [
        ['Lantern in the Tide', 'S2 · E4 · 24m left'],
        ['The Quiet Houses', '1h 12m left'],
        ['Vespers', 'S1 · E2 · 38m left'],
        ['Carbon Saint', '1h 04m left'],
    ],
    trending: [
        ['Vespers', '2026 · Series'],
        ['Carbon Saint', '2025 · Film'],
        ['Lantern in the Tide', '2025 · Film'],
        ['Streetlight Sermons', '2025 · Doc'],
        ['The Quiet Houses', '2024 · Horror'],
        ['Halcyon Drift', '2024 · Sci-Fi'],
        ['Salt & Sodium', '2025 · Drama'],
        ['Pale Architects', '2026 · Mystery'],
    ],
    newThisWeek: [
        ['Pale Architects', 'Premiered Tue'],
        ['Carbon Saint', 'Premiered Mon'],
        ['Streetlight Sermons', 'Premiered Sun'],
        ['Halcyon Drift', 'Premiered Wed'],
        ['Salt & Sodium', 'Premiered Fri'],
        ['Vespers', 'Episode 2 added'],
        ['The Quiet Houses', '4K remaster added'],
        ['Lantern in the Tide', 'Director cut added'],
    ],
    cinematic: [
        ['The Quiet Houses', 'Atmospheric'],
        ['Lantern in the Tide', 'Slow Burn'],
        ['Streetlight Sermons', 'Lyrical'],
        ['Halcyon Drift', 'Cosmic'],
        ['Pale Architects', 'Mannered'],
        ['Salt & Sodium', 'Tender'],
    ],
    fromYourSources: [
        ['Vespers', 'Plex · 4K HDR'],
        ['Carbon Saint', 'Jellyfin · 1080p'],
        ['The Quiet Houses', 'Stremio · 4K'],
        ['Lantern in the Tide', 'Plex · 4K'],
        ['Halcyon Drift', 'Stremio · 1080p'],
        ['Streetlight Sermons', 'Jellyfin · 1080p'],
    ],
};

const toItems = (rows, prefix) =>
    rows.map(([title, sub], i) => ({
        id: `${prefix}-${i}`,
        title,
        sub,
        poster: poster(i + (prefix.length % POSTERS.length)),
    }));

export const SHELVES = [
    {
        id: 'continue',
        title: 'Continue Watching',
        items: toItems(titles.continueWatching, 'cw'),
    },
    {
        id: 'trending',
        title: 'Trending Tonight',
        items: toItems(titles.trending, 'tr'),
    },
    {
        id: 'new',
        title: 'New This Week',
        items: toItems(titles.newThisWeek, 'nw'),
    },
    {
        id: 'cinematic',
        title: "Curator's Picks · Cinematic",
        items: toItems(titles.cinematic, 'ci'),
    },
    {
        id: 'sources',
        title: 'From Your Sources',
        items: toItems(titles.fromYourSources, 'sr'),
    },
];

export const NAV = [
    { id: 'home', label: 'Home', icon: 'home' },
    { id: 'search', label: 'Search', icon: 'search' },
    { id: 'library', label: 'My Library', icon: 'library' },
    { id: 'sources', label: 'Sources', icon: 'plug' },
    { id: 'settings', label: 'Settings', icon: 'settings' },
];
