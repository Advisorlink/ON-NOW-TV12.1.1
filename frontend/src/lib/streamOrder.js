/**
 * streamOrder — shared cascade-priority ordering for the stream list
 * passed to the native player.
 *
 * Order (lowest score = highest priority):
 *   1. EasyNews++  (Usenet direct, usually instant first-frame)
 *   2. Torrentio   (debrid-cached when available)
 *   3. Everything else
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
 *
 * v2.13.23 — Removed EP-STREM / Plexio tier.  User no longer has the
 * addon installed and asked for it to be stripped from the cascade.
 */
import { is1080p, is4K, isAV1 } from '@/lib/streamMeta';

const SIZE_CAP_GB = 3.0;

export const isEasyNews = (s) =>
    /easy[\s_-]?news/i.test(
        `${s?._addon_id || ''} ${s?._addon_name || ''} ${s?._addon_source || ''} ${s?.name || ''}`
    );

export const isTorrentio = (s) =>
    /torrentio/i.test(`${s?._addon_id || ''} ${s?._addon_name || ''}`);

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
    // Addon source dominates: 0 = EasyNews++, 1 = Torrentio,
    // 2 = anything else.  ×100 weight so source ranking can't be
    // swamped by a 1080p hit on an inferior addon.
    const src =
        isEasyNews(s)   ? 0 :
        isTorrentio(s)  ? 1 :
        2;
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
 * v2.13.24 — User spec update: sweet spot is 2-4 GB (was 1-3), and
 * within that band prefer the LOWEST-size link.  Smaller files hit
 * first frame sooner on debrid because there's less container to
 * seek past.  Source order (EasyNews++ → Torrentio → any) still
 * dominates — size is only the tiebreaker WITHIN a source tier.
 *
 * v2.13.23 — Removed EP-STREM / Plexio tier (user no longer has the
 * addon installed).
 *
 * Cascade (each tier size-clamped to 2.0-4.0 GB, tie-broken by
 * SMALLEST size first):
 *   T1  EasyNews++ 1080p
 *   T2  Torrentio  1080p, English, NOT uncached
 *   T3  any addon  1080p, English
 *   T4  null  (picker stays open)
 *
 * Uncached debrid links, 4K, and AV1 encodes are excluded up-front
 * — they either need a slow cloud unlock, exceed the box's decoder,
 * or blow past the sweet-spot size band anyway.
 */
export function pickAutoplayCandidate(streams) {
    if (!Array.isArray(streams) || streams.length === 0) return null;
    // v2.13.11 — NEVER autoplay an uncached debrid "download" link
    // (cloud transfer before playback = 30 s+ dead air).
    // v2.13.18 — nor an AV1 encode (no hardware decoder on the box).
    const candidates = streams.filter((s) => !is4K(s) && !isAV1(s) && !isUncachedDownload(s));
    const english = (s) => s?._is_english !== false;
    // v2.13.24 — Sweet spot 2.0-4.0 GB.  Streams with no `_size_gb`
    // tag are excluded so autoplay never fires an untagged monster.
    const inBand = (s) =>
        typeof s?._size_gb === 'number' && s._size_gb >= 2.0 && s._size_gb <= 4.0;
    // Sort candidates by size ascending so a 2.1 GB copy always beats
    // a 3.9 GB copy of the same title within any given source tier.
    const bySmallest = [...candidates].sort((a, b) => {
        const ga = typeof a?._size_gb === 'number' ? a._size_gb : Number.MAX_VALUE;
        const gb = typeof b?._size_gb === 'number' ? b._size_gb : Number.MAX_VALUE;
        return ga - gb;
    });

    return (
        bySmallest.find((s) => inBand(s) && isEasyNews(s) && is1080p(s)) ||
        bySmallest.find((s) => inBand(s) && isTorrentio(s) && is1080p(s) && english(s)) ||
        bySmallest.find((s) => inBand(s) && is1080p(s) && english(s)) ||
        null
    );
}
