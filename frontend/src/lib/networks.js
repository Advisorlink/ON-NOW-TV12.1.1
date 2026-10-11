/**
 * Network branding (slug, wordmark, accent, gradient, customLogo).
 *
 * `customLogo` (optional) — when present, the NetworksShelf tile
 * renders the user-supplied artwork at /networks/<slug>.webp as the
 * full-bleed tile background (replacing both the TMDB logo and the
 * gradient).  Files live in `/app/frontend/public/networks/` and
 * ship with the React bundle.
 *
 * `region` (optional, default 'US') — which country's catalog to
 * ask TMDB about via `/discover/{type}?watch_region=…`.  Without
 * this field Binge & Stan return *zero* titles (they're AU-only
 * services and TMDB tags their provider ids accordingly), and
 * Paramount+ effectively empties out under US (TMDB's provider id
 * 531 only carries a handful of titles under US, vs ~570 movies +
 * 471 TV episodes under AU).  Restored in v2.10.82 after the field
 * was accidentally dropped in v2.10.36.
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
        // TMDB's US Paramount+ provider returns ~8 titles total; AU
        // returns ~570 movies + 471 TV episodes for the same
        // network — 70× content boost for the user's AU audience.
        region: 'AU',
    },
    {
        slug: 'binge',
        name: 'Binge',
        wordmark: 'BiNGE',
        accent: '#ff3d8a',
        background:
            'linear-gradient(135deg, #03001a 0%, #410066 50%, #ff3d8a 100%)',
        customLogo: '/networks/binge.webp',
        // Foxtel-owned AU-only service.  Empty in every other region.
        region: 'AU',
    },
    {
        slug: 'stan',
        name: 'Stan',
        wordmark: 'stan.',
        accent: '#1e6dff',
        background:
            'linear-gradient(135deg, #000010 0%, #001e6e 55%, #1e6dff 100%)',
        customLogo: '/networks/stan.webp',
        // AU-only.  Empty in every other region.
        region: 'AU',
    },
    // ---- 2026-10-11 — restored "new networks" with house-style
    // full-bleed brand banners in /public/networks ----
    {
        slug: 'hayu',
        name: 'Hayu',
        wordmark: 'hayu',
        accent: '#ff2d8c',
        background:
            'linear-gradient(135deg, #1a0010 0%, #6b0037 55%, #ff2d8c 100%)',
        customLogo: '/networks/hayu.webp',
        region: 'AU',
    },
    {
        slug: 'peacock',
        name: 'Peacock',
        wordmark: 'peacock',
        accent: '#ffffff',
        background:
            'linear-gradient(135deg, #050505 0%, #15151a 55%, #2a2a33 100%)',
        customLogo: '/networks/peacock.webp',
        region: 'US',
    },
    {
        slug: 'crunchyroll',
        name: 'Crunchyroll',
        wordmark: 'crunchyroll',
        accent: '#f47521',
        background:
            'linear-gradient(135deg, #2a1200 0%, #7a3400 55%, #f47521 100%)',
        customLogo: '/networks/crunchyroll.webp',
        region: 'US',
    },
    {
        slug: 'britbox',
        name: 'BritBox',
        wordmark: 'BritBox',
        accent: '#3b7bff',
        background:
            'linear-gradient(135deg, #040a1f 0%, #0b2a7a 55%, #3b7bff 100%)',
        customLogo: '/networks/britbox.webp',
        region: 'AU',
    },
    {
        slug: 'foxtel-now',
        name: 'Foxtel Now',
        wordmark: 'FOXTEL',
        accent: '#ff5a1f',
        background:
            'linear-gradient(135deg, #1b0800 0%, #5a1a00 55%, #ff5a1f 100%)',
        customLogo: '/networks/foxtel-now.webp',
        region: 'AU',
    },
    {
        slug: 'abc-iview',
        name: 'ABC iview',
        wordmark: 'iview',
        accent: '#19b5fe',
        background:
            'linear-gradient(135deg, #001a2a 0%, #004d78 55%, #19b5fe 100%)',
        customLogo: '/networks/abc-iview.webp',
        region: 'AU',
    },
    {
        slug: 'sbs-on-demand',
        name: 'SBS On Demand',
        wordmark: 'SBS',
        accent: '#ffcd00',
        background:
            'linear-gradient(135deg, #1a1400 0%, #6b5400 55%, #ffcd00 100%)',
        customLogo: '/networks/sbs-on-demand.webp',
        region: 'AU',
    },
    {
        slug: '9now',
        name: '9Now',
        wordmark: '9Now',
        accent: '#2a7bff',
        background:
            'linear-gradient(135deg, #020a1c 0%, #0a2f73 55%, #2a7bff 100%)',
        customLogo: '/networks/9now.webp',
        region: 'AU',
    },
    {
        slug: '7plus',
        name: '7plus',
        wordmark: '7plus',
        accent: '#ff1e1e',
        background:
            'linear-gradient(135deg, #1c0202 0%, #6b0606 55%, #ff1e1e 100%)',
        customLogo: '/networks/7plus.webp',
        region: 'AU',
    },
    {
        slug: '10-play',
        name: '10 Play',
        wordmark: '10 play',
        accent: '#00a7e1',
        background:
            'linear-gradient(135deg, #00131c 0%, #004b66 55%, #00a7e1 100%)',
        customLogo: '/networks/10-play.webp',
        region: 'AU',
    },
    {
        slug: 'shudder',
        name: 'Shudder',
        wordmark: 'SHUDDER',
        accent: '#e3132b',
        background:
            'linear-gradient(135deg, #140000 0%, #560008 55%, #e3132b 100%)',
        customLogo: '/networks/shudder.webp',
        region: 'AU',
    },
];

export const findNetwork = (slug) =>
    NETWORKS.find((n) => n.slug === slug) || null;
