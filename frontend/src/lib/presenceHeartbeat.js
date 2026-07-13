/**
 * presenceHeartbeat — client-side ping loop for the launcher-admin
 * "Live · Who's watching right now" tab.
 *
 * Fires one heartbeat every 30 s while the user is watching a movie,
 * TV episode, music track, Kids show, FTA channel, etc., and one
 * explicit `end` on cleanup so the session drops off the live table
 * within seconds instead of waiting for the 90 s active-window
 * timeout on the server.
 *
 * v2.16.29 — Shared by:
 *   • the video Player (movies / series / FTA)
 *   • the singleton music engine (tunes)
 * Same React bundle is loaded inside the Kids and Tunes Android
 * WebView shells, so `_appFromLocation()` sniffs the route and
 * tags heartbeats correctly for each surface.
 */
import { getToken } from '@/lib/auth';

const API = process.env.REACT_APP_BACKEND_URL;
const HEARTBEAT_INTERVAL_MS = 30_000;

/** Local UUID; we don't need cryptographic strength here. */
function _uuid() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        return crypto.randomUUID();
    }
    return 'p_' + Math.random().toString(36).slice(2) + Date.now().toString(36);
}

/**
 * Classify the current URL into an app label the backend understands.
 * Vesper React runs unmodified inside the Kids + Tunes WebView shells,
 * so we can't rely on a build-time flag — we check `location` at
 * heartbeat time.
 */
function _appFromLocation() {
    if (typeof window === 'undefined') return 'movies';
    const raw = ((window.location.hash || '') + ' ' + (window.location.pathname || '')).toLowerCase();
    if (raw.includes('/kids'))  return 'kids';
    if (raw.includes('/music')) return 'tunes';
    if (raw.includes('/fta'))   return 'fta';
    // Karaoke lives under /music too so it lands as `tunes` naturally.
    return 'movies';
}

/**
 * One active heartbeat slot at a time — video and music are mutually
 * exclusive in practice (video pauses audio and vice versa) so keeping
 * two parallel sessions open would just muddy the admin table.  If a
 * new `start` fires while another is running, the old one gets an
 * explicit end first.
 */
let _current = null;   // { sessionId, timer, payload }

async function _postHeartbeat(payload) {
    const token = getToken();
    if (!token) return; // Anonymous users can't be traced; keep silent.
    try {
        await fetch(`${API}/api/presence/heartbeat`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${token}`,
            },
            body: JSON.stringify(payload),
            keepalive: true,
        });
    } catch (_err) {
        // Best-effort; a missed heartbeat just delays the live-table
        // update by 30 s.  Don't spam the console.
    }
}

async function _postEnd(sessionId, app) {
    const token = getToken();
    if (!token) return;
    try {
        await fetch(`${API}/api/presence/end`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${token}`,
            },
            body: JSON.stringify({ session_id: sessionId, app }),
            keepalive: true,
        });
    } catch (_err) { /* best-effort */ }
}

/**
 * Begin heartbeats for a new watching session.
 *
 * @param {object} args
 * @param {string} args.contentKind — 'movie' | 'series' | 'live_channel' | 'fta_channel' | 'music_track' | 'kids_show'
 * @param {string} args.contentTitle — human-readable title shown to admin
 * @param {string} [args.contentId] — stable id (imdbId, track id, station id, …)
 * @param {object} [args.contentMeta] — free-form extra fields (season/episode, quality, …)
 * @param {string} [args.deviceHint] — free-form device label (persisted on server row)
 * @param {string} [args.appOverride] — force an app label instead of auto-detect
 * @returns {string} sessionId — pass to `stopPresence` when playback ends
 */
export function startPresence(args) {
    const {
        contentKind, contentTitle, contentId,
        contentMeta, deviceHint, appOverride,
    } = args || {};
    if (!contentKind || !contentTitle) return null;

    // Kill any prior session BEFORE spinning up a new one.
    if (_current) {
        _postEnd(_current.sessionId, _current.payload.app);
        clearInterval(_current.timer);
        _current = null;
    }

    const sessionId = _uuid();
    const app = appOverride || _appFromLocation();
    const payload = {
        session_id: sessionId,
        app,
        content_kind: contentKind,
        content_id: contentId || '',
        content_title: contentTitle,
        content_meta: contentMeta || {},
        device_hint: deviceHint || '',
    };
    // Fire immediately so the admin sees the row within a second.
    _postHeartbeat(payload);
    const timer = setInterval(() => _postHeartbeat(payload), HEARTBEAT_INTERVAL_MS);
    _current = { sessionId, timer, payload };
    return sessionId;
}

/** Update the payload (e.g. metadata changed while still playing).  */
export function updatePresence(patch) {
    if (!_current || !patch) return;
    Object.assign(_current.payload, patch);
    _postHeartbeat(_current.payload);
}

/** End the current session (if any) and stop the heartbeat loop.   */
export function stopPresence() {
    if (!_current) return;
    _postEnd(_current.sessionId, _current.payload.app);
    clearInterval(_current.timer);
    _current = null;
}

/* Belt-and-braces: send a final end when the tab is being closed or
   backgrounded (Android WebView fires pagehide when the launcher
   swaps apps).  keepalive:true on the fetch keeps the request alive
   past the unload. */
if (typeof window !== 'undefined') {
    const flush = () => stopPresence();
    window.addEventListener('pagehide', flush);
    window.addEventListener('beforeunload', flush);
}
