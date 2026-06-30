/**
 * streamOrder — shared cascade-priority ordering for the stream list
 * passed to the native player.
 *
 * Order (lowest score = highest priority):
 *   1. EasyNews++  (Usenet direct, usually instant first-frame)
 *   2. Torrentio   (debrid-cached when available)
 *   3. EP-STREM / Plexio (premium direct)
 *   4. Everything else
 *
 * Within each addon source we further prefer:
 *   - 1080p > others (4K demoted; oversized for the user's bandwidth)
 *   - direct > torrent
 *   - English-strict > English > other languages
 *   - under-3 GB > oversized
 *
 * Used by:
 *   - Detail.jsx (movies)            → orderStreams(streams)
 *   - SeriesEpisodes.jsx (TV shows)  → orderStreams(episodeStreams)
 *
 * Critical so the in-player Stream Picker shows the SAME cascade on
 * every title (movies + episodes), and so the 10-second buffer
 * watchdog walks streams in this priority order when one stalls.
 */
import { is1080p, is4K } from '@/lib/streamMeta';

const SIZE_CAP_GB = 3.0;

export const isEasyNews = (s) =>
    /easy[\s_-]?news/i.test(
        `${s?._addon_id || ''} ${s?._addon_name || ''} ${s?._addon_source || ''} ${s?.name || ''}`
    );

export const isTorrentio = (s) =>
    /torrentio/i.test(`${s?._addon_id || ''} ${s?._addon_name || ''}`);

export const isEpStrem = (s) =>
    /plexio|ep[\s-]?strem/i.test(
        `${s?._addon_id || ''} ${s?._addon_name || ''} ${s?.name || ''}`
    );

const streamMode = (s) => {
    if (s?.url) return 'direct';
    if (s?.externalUrl) return 'external';
    if (s?.infoHash) return 'torrent';
    return 'unknown';
};

/** Score one stream — lower is higher priority. */
function scoreStream(s) {
    const dir    = streamMode(s) === 'direct' ? 0 : 1;
    const eng    = s?._is_english !== false ? 0 : 1;
    const strict = s?._english_strict === true ? 0 : 1;
    const four   = is4K(s) ? 1 : 0;
    const ten    = is1080p(s) ? 0 : 1;
    const sized  = typeof s?._size_gb !== 'number' || s._size_gb <= SIZE_CAP_GB ? 0 : 1;
    // Addon source dominates: 0 = EasyNews++, 1 = Torrentio, 2 = EP-STREM/Plexio,
    // 3 = anything else.  ×100 weight so source ranking can't be swamped by
    // a 1080p hit on an inferior addon.
    const src =
        isEasyNews(s)   ? 0 :
        isTorrentio(s)  ? 1 :
        isEpStrem(s)    ? 2 :
        3;
    return src * 100 + ten * 20 + four * 50 + dir * 4 + strict * 2 + eng + sized * 10;
}

/**
 * Returns a NEW array with the streams sorted in cascade priority.
 * Input is returned as-is when null / empty / not an array.
 */
export function orderStreams(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return streams;
    return [...streams]
        .map((s, i) => ({ s, i, key: scoreStream(s) }))
        .sort((a, b) => a.key - b.key || a.i - b.i)
        .map((x) => x.s);
}

/**
 * Pick the best autoplay candidate for a single movie / episode.
 * Tiered cascade matching the v2.10.80 user-defined priority:
 *   T1  EasyNews++  1080p English-strict direct
 *   T2  EasyNews++  any  1080p
 *   T3  Torrentio   1080p direct, English-strict, ≤ 3 GB
 *   T4  EP-STREM / Plexio direct, English
 *   T5  any addon  1080p strict English ≤ 3 GB
 *   T6  any        1080p English ≤ 3 GB
 *   T7  null  (picker stays open)
 */
export function pickAutoplayCandidate(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return null;
    const non4k    = streams.filter((s) => !is4K(s));
    const strict   = (s) => s?._english_strict === true;
    const english  = (s) => s?._is_english !== false;
    const direct   = (s) => streamMode(s) === 'direct';
    const underCap = (s) => typeof s?._size_gb !== 'number' || s._size_gb <= SIZE_CAP_GB;

    return (
        non4k.find((s) => isEasyNews(s) && is1080p(s) && direct(s) && strict(s)) ||
        non4k.find((s) => isEasyNews(s) && is1080p(s) && direct(s) && english(s)) ||
        non4k.find((s) => isEasyNews(s) && is1080p(s) && english(s)) ||
        non4k.find((s) => isEasyNews(s) && is1080p(s)) ||
        non4k.find((s) => isTorrentio(s) && direct(s) && is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => isTorrentio(s) && is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => isTorrentio(s) && is1080p(s) && english(s) && underCap(s)) ||
        non4k.find((s) => isEpStrem(s) && direct(s) && english(s)) ||
        non4k.find((s) => isEpStrem(s) && english(s)) ||
        non4k.find((s) => direct(s) && is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => is1080p(s) && english(s) && underCap(s)) ||
        null
    );
}
