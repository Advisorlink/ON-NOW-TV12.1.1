/**
 * Detects "a new episode is out" for TV shows in My Library.
 *
 * Cinemeta exposes a `videos` array on every series meta entry,
 * each video has:
 *   { season, episode, name, released: ISO, thumbnail, … }
 *
 * For each TV favourite, we fetch the meta on-demand (already
 * cached by api.getMeta), find the most recent episode whose
 * `released` is in the past, and if that episode's air date is
 * AFTER the favourite's `lastSeenAt` watermark AND not in the
 * `dismissed` map, it qualifies as a "new episode" to notify on.
 *
 * Designed to be CHEAP — only one Cinemeta fetch per favourite,
 * and the meta is cached on disk after first load.  Safe to call
 * on every Home / Library mount.
 */

import { Vesper } from './api';
import {
    listFavouritesByType,
    isEpisodeDismissed,
    markLibrarySeen,
} from './library';

function parseDate(iso) {
    if (!iso) return 0;
    const t = Date.parse(iso);
    return Number.isFinite(t) ? t : 0;
}

/**
 * Returns the first qualifying new-episode notification (if any).
 * Stops at the first match because we only show one toast at a time
 * — keeps things calm.  Subsequent ones surface on next mount after
 * the user has dismissed / actioned the previous one.
 */
export async function findNextNewEpisode() {
    const favs = listFavouritesByType('series');
    if (!favs.length) return null;

    const now = Date.now();
    for (const fav of favs) {
        let meta;
        try {
            const r = await Vesper.getMeta('series', fav.id);
            meta = r?.data?.meta;
        } catch {
            continue;
        }
        const videos = Array.isArray(meta?.videos) ? meta.videos : [];
        if (!videos.length) continue;

        // Find the latest aired episode (released in the past).
        let latest = null;
        for (const v of videos) {
            const t = parseDate(v.released);
            if (!t || t > now) continue;
            if (!latest || t > parseDate(latest.released)) latest = v;
        }
        if (!latest) continue;

        const aired = parseDate(latest.released);
        const seen = parseDate(fav.lastSeenAt) || parseDate(fav.addedAt);
        if (aired <= seen) continue; // nothing new since user added/watched
        if (isEpisodeDismissed(fav.id, latest.season, latest.episode)) {
            continue;
        }

        return {
            showId: fav.id,
            showMeta: {
                name: meta.name || fav.meta?.name || 'Show',
                poster: meta.poster || fav.meta?.poster,
                background: meta.background,
            },
            episode: {
                season: latest.season,
                number: latest.episode,
                name: latest.name || `S${latest.season} · E${latest.episode}`,
                aired: latest.released,
                thumbnail: latest.thumbnail,
            },
        };
    }
    return null;
}

/** Mark the show's notification as seen so we don't re-fire it. */
export function ackNewEpisode(showId) {
    markLibrarySeen(showId);
}
