/**
 * notifyScanner.js — background re-check for "notify me" library items.
 *
 * Once on app boot, iterates the user's `notifyList` and for each
 * entry asks the backend if any installed addon now returns streams
 * for that title.  If we find a fresh hit, we surface a toast via
 * `sonner` and mark the entry `notifiedAt` so we don't spam the
 * user on every cold start.
 *
 * Cheap: at most 1 HTTP request per notify-list entry per app boot.
 * Throttled to a max of 6 concurrent requests so we don't hammer
 * the backend if the user has a 50-title notify list.
 */
import axios from 'axios';
import { toast } from 'sonner';
import {
    listNotifyList,
    markNotifyListChecked,
    removeFromNotifyList,
} from '@/lib/library';

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

    let foundCount = 0;
    const found = [];
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
                if (hits.length > 0) {
                    found.push(entry);
                    foundCount += 1;
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

    if (foundCount > 0) {
        if (foundCount === 1) {
            const e = found[0];
            toast.success(`${e.meta?.name || 'Your movie'} is now streaming!`, {
                description: 'Open Library → Notify List to watch.',
                duration: 10_000,
            });
        } else {
            toast.success(`${foundCount} of your saved titles are now streaming!`, {
                description: 'Open Library → Notify List to see them.',
                duration: 10_000,
            });
        }
        /* Auto-remove items the user has watched once they've been
         * notified — we don't want them stuck in the list forever.
         * We DO leave them for one cycle so the user has time to
         * see the notification first. */
        found.forEach((e) => {
            if (e.notifiedAt) {
                /* Already notified previously → safe to clear. */
                removeFromNotifyList(e.id);
            }
        });
    }
}
