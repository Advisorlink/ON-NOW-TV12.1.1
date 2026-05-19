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
                if (hits.length > 0 && !entry.notifiedAt) {
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
