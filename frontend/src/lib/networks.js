/**
 * Network branding (slug, wordmark, accent, gradient, customLogo).
 *
 * `customLogo` (optional) — when present, the NetworksShelf tile
 * renders the user-supplied artwork at /networks/<slug>.webp as the
 * full-bleed tile background (replacing both the TMDB logo and the
 * gradient).  Files live in `/app/frontend/public/networks/` and
 * ship with the React bundle.
 */

export const NETWORKS = [
    {
        slug: 'netflix',
        name: 'Netflix',
        wordmark: 'NETFLIX',
        accent: '#e50914',
        background:
            'linear-gradient(135deg, #1c0306 0%, #4a070b 55%, #e50914 100%)',
        customLogo: '/networks/netflix.webp',
    },
    {
        slug: 'apple-tv',
        name: 'Apple TV+',
        wordmark: 'tv+',
        accent: '#ffffff',
        background:
            'linear-gradient(135deg, #050505 0%, #1c1c1e 55%, #2c2c2e 100%)',
        customLogo: '/networks/apple-tv.webp',
    },
    {
        slug: 'disney-plus',
        name: 'Disney+',
        wordmark: 'Disney+',
        accent: '#0063e5',
        background:
            'linear-gradient(135deg, #01153d 0%, #0042a8 55%, #0063e5 100%)',
        customLogo: '/networks/disney-plus.webp',
    },
    {
        slug: 'prime-video',
        name: 'Prime Video',
        wordmark: 'prime video',
        accent: '#00a8e1',
        background:
            'linear-gradient(135deg, #00263a 0%, #006497 55%, #00a8e1 100%)',
        customLogo: '/networks/prime-video.webp',
    },
    {
        slug: 'hulu',
        name: 'Hulu',
        wordmark: 'hulu',
        accent: '#1ce783',
        background:
            'linear-gradient(135deg, #001b0c 0%, #006a37 55%, #1ce783 100%)',
        customLogo: '/networks/hulu.webp',
    },
    {
        slug: 'hbo',
        name: 'Max',
        wordmark: 'max',
        accent: '#ffffff',
        background:
            'linear-gradient(135deg, #000000 0%, #0a1842 55%, #1e6dff 100%)',
        customLogo: '/networks/hbo.webp',
    },
    {
        slug: 'paramount-plus',
        name: 'Paramount+',
        wordmark: 'P+',
        accent: '#0064ff',
        background:
            'linear-gradient(135deg, #000010 0%, #002073 55%, #0064ff 100%)',
        customLogo: '/networks/paramount-plus.webp',
    },
    {
        slug: 'binge',
        name: 'Binge',
        wordmark: 'BiNGE',
        accent: '#ff3d8a',
        background:
            'linear-gradient(135deg, #03001a 0%, #410066 50%, #ff3d8a 100%)',
        customLogo: '/networks/binge.webp',
    },
    {
        slug: 'stan',
        name: 'Stan',
        wordmark: 'stan.',
        accent: '#1e6dff',
        background:
            'linear-gradient(135deg, #000010 0%, #001e6e 55%, #1e6dff 100%)',
        customLogo: '/networks/stan.webp',
    },
];

export const findNetwork = (slug) =>
    NETWORKS.find((n) => n.slug === slug) || null;
