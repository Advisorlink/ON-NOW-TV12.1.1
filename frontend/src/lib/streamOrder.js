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
import { is1080p, is4K, isAV1 } from '@/lib/streamMeta';

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

/** True for debrid links that are NOT cached on the provider —
 * playing one triggers a cloud download that takes 30 s-to-minutes
 * before a single byte of video flows.  Always rank these LAST and
 * never autoplay them. */
export const isUncachedDownload = (s) => s?._pm_uncached === true;

/** Score one stream — lower is higher priority. */
function scoreStream(s) {
    const dir    = streamMode(s) === 'direct' ? 0 : 1;
    const eng    = s?._is_english !== false ? 0 : 1;
    const strict = s?._english_strict === true ? 0 : 1;
    const four   = is4K(s) ? 1 : 0;
    // v2.13.18 — AV1 demoted like 4K: no hardware decoder on most
    // TV boxes → ExoPlayer decoder-init failure → VLC software-decode
    // fallback that takes 20-30 s to first frame.
    const av1    = isAV1(s) ? 1 : 0;
    const ten    = is1080p(s) ? 0 : 1;
    const sized  = typeof s?._size_gb !== 'number' || s._size_gb <= SIZE_CAP_GB ? 0 : 1;
    // v2.13.11 — uncached debrid "download" links go to the very
    // bottom of every list (×1000 dominates all other weights).
    const dl     = isUncachedDownload(s) ? 1 : 0;
    // Addon source dominates: 0 = EasyNews++, 1 = Torrentio, 2 = EP-STREM/Plexio,
    // 3 = anything else.  ×100 weight so source ranking can't be swamped by
    // a 1080p hit on an inferior addon.
    const src =
        isEasyNews(s)   ? 0 :
        isTorrentio(s)  ? 1 :
        isEpStrem(s)    ? 2 :
        3;
    return dl * 1000 + src * 100 + ten * 20 + four * 50 + av1 * 50 + dir * 4 + strict * 2 + eng + sized * 10;
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
 *
 * v2.13.20 — User spec: "as soon as there's an EasyNews++ link that's
 * around the 2 GB mark, that's it — just play it, no more thinking."
 * The previous cascade let EasyNews++ win with NO size check (T1-T4),
 * so a 15 GB EasyNews++ remux or a 500 MB potato encode could beat
 * a healthy 2 GB one.  New top tier explicitly targets the sweet-spot
 * size band (1.0-3.0 GB, ideal ~2 GB) so autoplay lands on the copy
 * that plays instantly on debrid.
 *
 * Cascade:
 *   T0  EasyNews++ SWEET-SPOT  (1.0-3.0 GB, direct)      ← target ~2 GB
 *   T1  EasyNews++ 1080p direct English-strict
 *   T2  EasyNews++ 1080p direct English
 *   T3  EasyNews++ 1080p English
 *   T4  EasyNews++ 1080p (any)
 *   T5  Torrentio  1080p direct English-strict ≤ 3 GB
 *   T6  Torrentio  1080p English-strict ≤ 3 GB
 *   T7  Torrentio  1080p English ≤ 3 GB
 *   T8  EP-STREM / Plexio direct English
 *   T9  EP-STREM / Plexio English
 *   T10 any 1080p direct English-strict ≤ 3 GB
 *   T11 any 1080p English-strict ≤ 3 GB
 *   T12 any 1080p English ≤ 3 GB
 *   T13 null  (picker stays open)
 */
export function pickAutoplayCandidate(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return null;
    // v2.13.11 — NEVER autoplay an uncached debrid "download" link
    // (cloud transfer before playback = 30 s+ dead air).
    // v2.13.18 — nor an AV1 encode (no hardware decoder on the box).
    const non4k    = streams.filter((s) => !is4K(s) && !isAV1(s) && !isUncachedDownload(s));
    const strict   = (s) => s?._english_strict === true;
    const english  = (s) => s?._is_english !== false;
    const direct   = (s) => streamMode(s) === 'direct';
    const underCap = (s) => typeof s?._size_gb !== 'number' || s._size_gb <= SIZE_CAP_GB;
    // v2.13.20 — Sweet-spot size band for "instant-start" streams.
    // 1.0-3.0 GB covers 1080p TV episodes (~1.5-2.5 GB) and
    // compressed 1080p movies (~2-3 GB).  Streams outside this band
    // are typically either potato encodes (< 800 MB, low bitrate) or
    // remuxes / raw scene releases (> 4 GB, long debrid unlock).
    const idealSize = (s) =>
        typeof s?._size_gb === 'number' && s._size_gb >= 1.0 && s._size_gb <= 3.0;

    return (
        // T0 — EasyNews++ in the sweet-spot size band.  User spec:
        // "just play the 2 GB one and start streaming."  This tier
        // ignores English-strict / direct-only filters because
        // EasyNews++ is essentially always direct + English anyway.
        non4k.find((s) => isEasyNews(s) && is1080p(s) && idealSize(s)) ||
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
