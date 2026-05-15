/**
 * Live TV programme reminders — keyed by provider id.
 *
 *   shape: { [providerId]: Reminder[] }
 *   Reminder: { id, streamId, channelName, title, startTs, stopTs }
 *
 * `id` is `${streamId}:${startTs}` so re-toggling the same programme
 * is a no-op.  Stale reminders (stop time past) are pruned once
 * per session on first read.
 */

const KEY = 'onnowtv-live-reminders-v1';
let cache = null;

function load() {
    if (cache) return cache;
    try {
        const raw = localStorage.getItem(KEY);
        cache = raw ? JSON.parse(raw) : {};
        if (typeof cache !== 'object' || cache === null) cache = {};
    } catch { cache = {}; }
    return cache;
}
function persist() {
    try { localStorage.setItem(KEY, JSON.stringify(cache)); } catch { /* ignore */ }
}
function makeId(streamId, startTs) { return `${streamId}:${startTs}`; }

export function getReminders(providerId) {
    const c = load();
    return Array.isArray(c[providerId]) ? c[providerId] : [];
}

export function toggleReminder(providerId, streamId, info) {
    if (providerId == null || streamId == null || !info?.startTs) return false;
    const c = load();
    const list = Array.isArray(c[providerId]) ? c[providerId].slice() : [];
    const id = makeId(streamId, info.startTs);
    const idx = list.findIndex((r) => r.id === id);
    let on;
    if (idx >= 0) { list.splice(idx, 1); on = false; }
    else {
        list.unshift({
            id,
            streamId: Number(streamId) || streamId,
            channelName: info.channelName || '',
            title: info.title || '',
            startTs: info.startTs,
            stopTs: info.stopTs || 0,
        });
        on = true;
    }
    c[providerId] = list;
    persist();
    return on;
}

export function pruneStale(providerId) {
    const c = load();
    const list = Array.isArray(c[providerId]) ? c[providerId] : [];
    if (list.length === 0) return [];
    const nowSec = Math.floor(Date.now() / 1000);
    const next = list.filter((r) => Number(r.stopTs || 0) >= nowSec);
    if (next.length !== list.length) {
        c[providerId] = next;
        persist();
    }
    return next;
}
