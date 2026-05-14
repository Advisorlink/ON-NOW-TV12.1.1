/**
 * Live TV — Favourites + Reminders (per-provider, localStorage).
 *
 * Favourites store the FULL minimal channel object (stream_id, name,
 * num, stream_icon, category_id) so the "Favourites" virtual
 * category can render entirely from localStorage — no provider
 * round-trip needed, no mega-fetch of every channel just to filter.
 * This is the TV Mate pattern: each list view fetches the bare
 * minimum it needs, never the entire catalog.
 *
 * Stored under:
 *   onnowtv-xtream-favs__{providerId}      → [{ stream_id, name, num, stream_icon, category_id }]
 *   onnowtv-xtream-reminders__{providerId} → { [key]: { channelId, channelName, title, start, end } }
 */

const FAV_KEY = (pid) => `onnowtv-xtream-favs__${pid}`;
const REM_KEY = (pid) => `onnowtv-xtream-reminders__${pid}`;

/* --------------------------- favourites --------------------------- */

export function listFavourites(providerId) {
    if (!providerId) return [];
    try {
        const raw = localStorage.getItem(FAV_KEY(providerId));
        const arr = raw ? JSON.parse(raw) : [];
        return Array.isArray(arr) ? arr : [];
    } catch {
        return [];
    }
}

export function listFavouriteIds(providerId) {
    return new Set(listFavourites(providerId).map((c) => String(c.stream_id)));
}

export function isFavourite(providerId, streamId) {
    return listFavouriteIds(providerId).has(String(streamId));
}

export function toggleFavourite(providerId, channel) {
    const list = listFavourites(providerId);
    const id = String(channel.stream_id);
    const idx = list.findIndex((c) => String(c.stream_id) === id);
    if (idx >= 0) {
        list.splice(idx, 1);
    } else {
        list.push({
            stream_id: channel.stream_id,
            name: channel.name,
            num: channel.num,
            stream_icon: channel.stream_icon,
            category_id: channel.category_id,
        });
    }
    localStorage.setItem(FAV_KEY(providerId), JSON.stringify(list));
    window.dispatchEvent(new CustomEvent('vesper:xtream-favs-change'));
    return idx < 0;  // true if we just added
}

/* --------------------------- reminders --------------------------- */

function reminderKey(streamId, startTs) {
    return `${streamId}@${startTs}`;
}

function readReminders(providerId) {
    if (!providerId) return {};
    try {
        const raw = localStorage.getItem(REM_KEY(providerId));
        return raw ? JSON.parse(raw) : {};
    } catch {
        return {};
    }
}

function writeReminders(providerId, m) {
    localStorage.setItem(REM_KEY(providerId), JSON.stringify(m));
    window.dispatchEvent(new CustomEvent('vesper:xtream-reminders-change'));
}

export function listReminders(providerId) {
    return readReminders(providerId);
}

export function hasReminder(providerId, streamId, startTs) {
    return Boolean(readReminders(providerId)[reminderKey(streamId, startTs)]);
}

export function toggleReminder(providerId, payload) {
    // payload = { streamId, startTs, stopTs, title, channelName }
    const all = readReminders(providerId);
    const k = reminderKey(payload.streamId, payload.startTs);
    if (all[k]) {
        delete all[k];
        cancelTimer(k);
    } else {
        all[k] = { ...payload, setAt: Date.now() };
        scheduleTimer(k, all[k]);
    }
    writeReminders(providerId, all);
    return Boolean(all[k]);
}

/* --------------------------- alarm timers --------------------------- */

const timers = new Map();

function scheduleTimer(key, r) {
    cancelTimer(key);
    const fireAt = (Number(r.startTs) * 1000) - 60_000; // 1 min before
    const delay = Math.max(0, fireAt - Date.now());
    if (delay > 24 * 3600 * 1000) {
        // > 24 h away — don't burn a setTimeout; the boot pass will
        // re-schedule when the user opens the app within range.
        return;
    }
    const tid = setTimeout(() => fire(r), delay);
    timers.set(key, tid);
}

function cancelTimer(key) {
    const t = timers.get(key);
    if (t) clearTimeout(t);
    timers.delete(key);
}

function fire(r) {
    const msg = `📺 ${r.channelName} · ${r.title || 'starting soon'} in 1 minute`;
    try {
        if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
            new Notification(r.channelName || 'Live TV', { body: r.title || 'starting now' });
            return;
        }
        if (window?.AndroidApp?.notify) {
            window.AndroidApp.notify(r.channelName || 'Live TV', r.title || 'starting now');
            return;
        }
    } catch { /* ignore */ }
    // Fallback — log so the user at least sees it in the console.
    console.info('[reminder]', msg);
}

/**
 * Boot pass — call once on app load (or when the user enters /live-tv).
 * Re-schedules all reminders for the active provider that are within
 * the next 24 h.  Reminders further out are scheduled on subsequent
 * boots.
 */
export function rehydrateReminders(providerId) {
    const all = readReminders(providerId);
    Object.entries(all).forEach(([k, r]) => {
        const fireAt = (Number(r.startTs) * 1000) - 60_000;
        if (fireAt < Date.now() - 60_000) {
            // Already past — clean it up so the list doesn't grow forever.
            delete all[k];
            return;
        }
        scheduleTimer(k, r);
    });
    writeReminders(providerId, all);
    // Best-effort: ask for permission so future notifications fire.
    try {
        if (typeof Notification !== 'undefined' && Notification.permission === 'default') {
            Notification.requestPermission().catch(() => {});
        }
    } catch { /* ignore */ }
}
