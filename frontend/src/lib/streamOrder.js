/**
 * streamOrder — shared cascade-priority ordering for the stream list
 * passed to the native player.
 *
 * Order (USER SPEC 2026-10-12, lowest score = highest priority):
 *   1. ON NOW TV Direct (the user's own IPTV VOD link)
 *   2. Torrentio   (debrid-cached when available)
 *   3. MediaFusion
 *   4. EasyNews++  (demoted — "isn't really working very well right now")
 *   5. Everything else (EP-STREM / Plexio, custom addons)
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

/** "ON NOW TV Direct" — the user's own IPTV VOD link.  Always first. */
export const isOnNowDirect = (s) => s?._onnow_direct === true || s?._addon_id === 'onnowtv-direct';
export const isTorrentio = (s) =>
    /torrentio/i.test(`${s?._addon_id || ''} ${s?._addon_name || ''}`);
export const isMediaFusion = (s) =>
    /media[\s_-]?fusion/i.test(`${s?._addon_id || ''} ${s?._addon_name || ''} ${s?._addon_source || ''}`);

/** 0 = ON NOW Direct … 4 = anything else — the cascade's source rank. */
export const sourceRank = (s) =>
    isOnNowDirect(s)  ? 0 :
    isTorrentio(s)    ? 1 :
    isMediaFusion(s)  ? 2 :
    isEasyNews(s)     ? 3 :
    4;

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
    // Addon source dominates (×100) so source ranking can't be swamped
    // by a 1080p hit on a lower-priority addon.
    const src = sourceRank(s);
    return dl * 1000 + src * 100 + ten * 20 + four * 50 + av1 * 50 + dir * 4 + strict * 2 + eng + sized * 10;
}

/**
 * Returns a NEW array with the streams sorted in cascade priority.
 * Input is returned as-is when null / empty / not an array.
 *
 * USER SPEC — source blocks in cascade order (ON NOW Direct →
 * Torrentio → MediaFusion → EasyNews++ → rest); inside each block the
 * smallest 1080p (FHD) link comes first, then sub-1080p / AV1 copies
 * (no AV1 hardware decoder on the box).  Uncached debrid "download"
 * links (30 s+ cloud transfer) sink below every source.  Unknown size
 * sorts after sized links; quality score breaks ties.
 */
export function orderStreams(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return streams;
    // Sixth bucket: uncached debrid downloads sink below EVERY source.
    const groups = [[], [], [], [], [], []];
    streams.forEach((s, i) =>
        groups[isUncachedDownload(s) ? 5 : sourceRank(s)].push({ s, i, key: scoreStream(s) })
    );
    const bySmallestFhd = (a, b) => {
        const fa = is1080p(a.s) && !is4K(a.s) && !isAV1(a.s) ? 0 : 1;
        const fb = is1080p(b.s) && !is4K(b.s) && !isAV1(b.s) ? 0 : 1;
        if (fa !== fb) return fa - fb;
        const ga = typeof a.s?._size_gb === 'number' ? a.s._size_gb : Number.MAX_VALUE;
        const gb = typeof b.s?._size_gb === 'number' ? b.s._size_gb : Number.MAX_VALUE;
        return ga - gb || a.key - b.key || a.i - b.i;
    };
    groups[0].sort((a, b) => a.i - b.i);
    for (let g = 1; g < groups.length; g += 1) groups[g].sort(bySmallestFhd);
    return groups.flat().map((x) => x.s);
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
