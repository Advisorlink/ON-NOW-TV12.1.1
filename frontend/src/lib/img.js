/**
 * Cheap, predictable image URL "downscaler" used on the HK1 wrapper
 * to keep the app feeling snappy at 1080p without losing visual
 * quality.  All transformations are pure string ops — no network
 * calls, no SDK weight.
 *
 *   - Metahub (Cinemeta posters/backgrounds):
 *       /large/  -> /medium/
 *       /original/ -> /medium/
 *   - TMDB (network + trending fallbacks):
 *       /w500/ poster -> /w342/
 *       /w1280/ backdrop -> /w780/
 *       /original/ backdrop -> /w780/
 *
 * Returns the URL unchanged on desktop browsers so the high-end
 * preview stays crisp.
 */

import Host from '@/lib/host';

const isLite = () => Host.isAndroid || Host.isLowEnd;

export function poster(url) {
    if (!url || !isLite()) return url;
    return url
        .replace('/poster/large/', '/poster/medium/')
        .replace('/poster/original/', '/poster/medium/')
        .replace('image.tmdb.org/t/p/w500/', 'image.tmdb.org/t/p/w342/')
        .replace('image.tmdb.org/t/p/original/', 'image.tmdb.org/t/p/w342/');
}

export function backdrop(url) {
    if (!url || !isLite()) return url;
    return url
        .replace('/background/large/', '/background/medium/')
        .replace('/background/original/', '/background/medium/')
        .replace('image.tmdb.org/t/p/w1280/', 'image.tmdb.org/t/p/w780/')
        .replace('image.tmdb.org/t/p/original/', 'image.tmdb.org/t/p/w780/');
}
