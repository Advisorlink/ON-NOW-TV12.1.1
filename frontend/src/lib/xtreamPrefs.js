/**
 * Live TV — Favourites + Reminders (per-provider, localStorage).
 *
 * Stored under:
 *   onnowtv-xtream-favs__{providerId}        → Set<string> of stream_ids
 *   onnowtv-xtream-reminders__{providerId}   → { [key]: { channelId, channelName, title, start, end } }
 *
 * Reminders fire a system Notification (web) or — in the native APK
 * — bubble up via `Host.notify(...)` if the bridge exposes it.
 * If neither is available we no-op silently.
 */

const FAV_KEY = (pid) => `onnowtv-xtream-favs__${pid}`;
const REM_KEY = (pid) => `onnowtv-xtream-reminders__${pid}`;

/* --------------------------- favourites --------------------------- */

export function listFavouriteIds(providerId) {
    if (!providerId) return new Set();
    try {
        const raw = localStorage.getItem(FAV_KEY(providerId));
        const arr = raw ? JSON.parse(raw) : [];
        return new Set(Array.isArray(arr) ? arr : []);
    } catch {
        return new Set();
    }
}

export function isFavourite(providerId, streamId) {
    return listFavouriteIds(providerId).has(String(streamId));
}

export function toggleFavourite(providerId, streamId) {
    const s = listFavouriteIds(providerId);
    const id = String(streamId);
    if (s.has(id)) s.delete(id);
    else s.add(id);
    localStorage.setItem(FAV_KEY(providerId), JSON.stringify([...s]));
    window.dispatchEvent(new CustomEvent('vesper:xtream-favs-change'));
    return s.has(id);
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
