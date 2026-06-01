/**
 * notifyScanner.js — background re-check for "notify me" library items.
 *
 * Once on app boot, iterates the user's `notifyList` and for each
 * entry asks the backend if any installed addon now returns streams
 * for that title.  If we find a fresh hit, we PUSH it onto the
 * `NotifyHitWatcher` queue — the watcher then shows a top-right
 * "X just came out in HD" card with Watch / Watch later / Dismiss
 * actions.
 *
 * Cheap: at most 1 HTTP request per notify-list entry per app boot.
 * Throttled to a max of 6 concurrent requests so we don't hammer
 * the backend if the user has a 50-title notify list.
 */
import axios from 'axios';
import {
    listNotifyList,
    markNotifyListChecked,
} from '@/lib/library';
import { pushNotifyHit } from '@/components/NotifyHitWatcher';

const API = process.env.REACT_APP_BACKEND_URL;
let alreadyRan = false;

/**
 * Decide whether a stream result counts as a "real HD release" of
 * the title the user was waiting on.  Per user request (v2.8.88):
 *
 *   1. At least 3 streams come back from the addon.
 *   2. At least 3 of those streams are tagged 1080p (or higher).
 *   3. The stream titles/filenames mention the movie/show name —
 *      guards against the addon returning a trailer, a placeholder,
 *      or an unrelated file when the title isn't actually out yet.
 */
function isHdRelease(streams, expectedName) {
    if (!Array.isArray(streams) || streams.length < 3) return false;

    const HD_PATTERN = /(?:^|[\s\-_.\[(])(1080p|2160p|4k|uhd|hdr)(?:[\s\-_.\])$]|$)/i;
    const needle = String(expectedName || '')
        .replace(/\(.*?\)/g, '')
        .replace(/\b(19|20)\d{2}\b/g, '')
        .replace(/[^a-z0-9 ]+/gi, ' ')
        .trim()
        .toLowerCase();
    const needleTokens = needle.split(/\s+/).filter((w) => w.length >= 3);

    const hdMatches = streams.filter((s) => {
        const blob = [
            s?.title, s?.name, s?.description,
            s?.behaviorHints?.filename, s?.behaviorHints?.bingeGroup,
        ].filter(Boolean).join(' ');
        if (!HD_PATTERN.test(blob)) return false;
        if (!needleTokens.length) return true;
        const blobLower = blob.toLowerCase();
        // Need at least 60% of the title tokens to appear in the
        // stream blob — rules out trailers, demos, and unrelated
        // files when nothing actually matches.
        const hits = needleTokens.filter((t) => blobLower.includes(t));
        return hits.length / needleTokens.length >= 0.6;
    });

    return hdMatches.length >= 3;
}

export async function runNotifyScanner() {
    if (alreadyRan) return;
    alreadyRan = true;
    if (!API) return;

    const list = listNotifyList();
    if (!list.length) return;

    /* Only re-check items whose lastCheckedAt is older than 1 hour
     * (avoids re-running on quick reopens). */
    const HOUR = 60 * 60 * 1000;
    const dueItems = list.filter((e) => {
        if (!e.lastCheckedAt) return true;
        return Date.now() - new Date(e.lastCheckedAt).getTime() > HOUR;
    });
    if (!dueItems.length) return;

    const CONC = 6;
    const queue = [...dueItems];
    const workers = Array.from({ length: Math.min(CONC, queue.length) }, async () => {
        while (queue.length) {
            const entry = queue.shift();
            if (!entry) break;
            try {
                /* Hit the backend's "any-addon stream" endpoint —
                 * returns a non-empty array if any installed addon
                 * has playable streams for this title. */
                const url = `${API}/api/streams/${entry.type || 'movie'}/${entry.id}`;
                const res = await axios.get(url, { timeout: 12_000 });
                const hits = Array.isArray(res?.data?.streams) ? res.data.streams : [];
                // v2.8.88 — Validate HD release before alerting the
                // user.  Old logic fired the "now in HD" toast for
                // any non-empty result (including 2 weak/non-matching
                // streams); user reported false positives so we now
                // require 3+ 1080p+ streams whose titles match the
                // expected name.
                const expectedName = entry?.meta?.name || '';
                const isHd = isHdRelease(hits, expectedName);
                if (isHd && !entry.notifiedAt) {
                    // Push to the global watcher queue with all the
                    // metadata it needs to render the toast.
                    pushNotifyHit({
                        id: entry.id,
                        type: entry.type || 'movie',
                        meta: entry.meta || {},
                    });
                    markNotifyListChecked(entry.id, true);
                } else {
                    markNotifyListChecked(entry.id, false);
                }
            } catch {
                /* Network glitch — just touch lastCheckedAt so we
                 * don't retry forever in a hot loop. */
                markNotifyListChecked(entry.id, false);
            }
        }
    });
    await Promise.all(workers);
}
