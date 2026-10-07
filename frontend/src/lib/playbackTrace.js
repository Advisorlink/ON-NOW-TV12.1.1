/** Rolling trail of native playback handoff events (web + Android stages).
 * Survives SPA navigation via sessionStorage. No URLs or secrets stored. */
const KEY = 'vesper-playback-trace';
const MAX = 60;
let entries = [];
const listeners = new Set();

try {
    const raw = sessionStorage.getItem(KEY);
    if (raw) entries = JSON.parse(raw).slice(-MAX);
} catch { entries = []; }

function persist() {
    try { sessionStorage.setItem(KEY, JSON.stringify(entries)); } catch { /* quota / private mode */ }
}

export function recordPlaybackEvent(detail = {}) {
    entries = entries.concat({
        at: Date.now(),
        requestId: String(detail.requestId || ''),
        status: String(detail.status || 'unknown'),
        message: String(detail.message || '').slice(0, 300),
        t: typeof detail.t === 'number' ? detail.t : null,
        seq: typeof detail.seq === 'number' ? detail.seq : null,
        appVersion: detail.appVersion || '',
    }).slice(-MAX);
    persist();
    listeners.forEach((fn) => fn(entries));
}

export function getPlaybackTrace() { return entries; }

export function clearPlaybackTrace() {
    entries = [];
    persist();
    listeners.forEach((fn) => fn(entries));
}

export function subscribePlaybackTrace(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}

/** Entries for the most recent request only (what the user just tapped). */
export function latestRequestTrace() {
    const last = [...entries].reverse().find((e) => e.requestId);
    if (!last) return entries;
    return entries.filter((e) => e.requestId === last.requestId);
}

export function formatPlaybackTrace(list = latestRequestTrace()) {
    return list.map((e) => {
        const rel = e.t == null ? '' : ` +${e.t}ms`;
        const ver = e.appVersion ? ` [${e.appVersion}]` : '';
        return `${new Date(e.at).toISOString().slice(11, 23)}${rel} ${e.status}${e.message ? ' — ' + e.message : ''}${ver}`;
    }).join('\n');
}

if (typeof window !== 'undefined' && !window.__vesperPlaybackTraceInstalled) {
    window.__vesperPlaybackTraceInstalled = true;
    window.addEventListener('vesper:native-playback', (e) => recordPlaybackEvent(e.detail || {}));
}
