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
 *
 * USER SPEC — smallest-FHD-first, EasyNews++ block leading:
 *   • EasyNews++ links first: 1080p (FHD) sorted by size ASC, then
 *     sub-1080p "HD" copies.
 *   • Everything else follows the SAME rule — lowest-size 1080p
 *     first (e.g. smallest Torrentio cached 1080p when there's no
 *     Easy++) — with two safety sinks that always drop to the
 *     bottom: uncached debrid "download" links (30 s+ cloud
 *     transfer) and AV1 encodes (no hardware decoder on the box).
 *   • Unknown size sorts after sized links; quality score breaks ties.
 */
export function orderStreams(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return streams;
    const easy = [];
    const rest = [];
    streams.forEach((s, i) =>
        (isEasyNews(s) ? easy : rest).push({ s, i, key: scoreStream(s) })
    );
    const bySmallestFhd = (a, b) => {
        const fa = is1080p(a.s) && !is4K(a.s) && !isAV1(a.s) ? 0 : 1;
        const fb = is1080p(b.s) && !is4K(b.s) && !isAV1(b.s) ? 0 : 1;
        if (fa !== fb) return fa - fb;
        const ga = typeof a.s?._size_gb === 'number' ? a.s._size_gb : Number.MAX_VALUE;
        const gb = typeof b.s?._size_gb === 'number' ? b.s._size_gb : Number.MAX_VALUE;
        return ga - gb || a.key - b.key || a.i - b.i;
    };
    easy.sort(bySmallestFhd);
    rest.sort((a, b) => {
        const da = isUncachedDownload(a.s) ? 1 : 0;
        const db = isUncachedDownload(b.s) ? 1 : 0;
        if (da !== db) return da - db;
        return bySmallestFhd(a, b);
    });
    return [...easy, ...rest].map((x) => x.s);
}

/**
 * Pick the best autoplay candidate for a single movie / episode.
 *
 * RESTORED to the v2.7.37 / 11-Jun-2026 rollback-day tiers (the
 * state the user signed off as "selecting everything properly",
 * BEFORE any EasyNews++ special-casing existed):
 *   1. EP-STREM / Plexio direct link (premium addon)
 *   2. Torrentio ≤ 3 GB, strict English, 1080p (direct preferred)
 *   3. Any addon, strict English, 1080p, ≤ 3 GB
 *   4. Any English 1080p ≤ 3 GB (last resort)
 *   5. null → user sees the picker
 */
export function pickAutoplayCandidate(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return null;
    const non4k   = streams.filter((s) => !is4K(s));
    const strict  = (s) => s?._english_strict === true;
    const english = (s) => s?._is_english !== false;
    const direct  = (s) => streamMode(s) === 'direct';
    // Size guard — null size = unknown; we ALLOW unknowns through
    // (rare for Torrentio, common for direct CDN addons that don't
    // expose filesize).
    const underCap = (s) =>
        typeof s?._size_gb !== 'number' || s._size_gb <= SIZE_CAP_GB;

    return (
        // Tier 1 — EP-STREM (Plexio) direct, English
        non4k.find((s) => isEpStrem(s) && direct(s) && english(s)) ||
        non4k.find((s) => isEpStrem(s) && english(s)) ||
        // Tier 2 — Torrentio under 3 GB, strict English, 1080p direct
        non4k.find((s) => isTorrentio(s) && direct(s) && is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => isTorrentio(s) && is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => isTorrentio(s) && is1080p(s) && english(s) && underCap(s)) ||
        // Tier 3 — any addon, strict English, 1080p, under cap
        non4k.find((s) => direct(s) && is1080p(s) && strict(s) && underCap(s)) ||
        non4k.find((s) => is1080p(s) && strict(s) && underCap(s)) ||
        // Tier 4 — any English 1080p under cap (multi-lang ok)
        non4k.find((s) => is1080p(s) && english(s) && underCap(s)) ||
        null
    );
}
