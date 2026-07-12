/**
 * vesperCloudSync — silent per-account cloud backup for Vesper.
 *
 * v2.16.18 — Mirrors the ON NOW V2 Live TV cloud-sync pattern:
 *   • Every mutation of a synced `localStorage` key schedules a
 *     debounced push to `/api/vesper/sync/push` (2.5 s trailing).
 *   • On `pagehide` / `beforeunload` we flush immediately so the
 *     final edit before a hard reload / app kill actually reaches
 *     the server.
 *   • Successful pushes fire a discreet "Saved to profile ✓" toast.
 *   • The essential-key filter is shared with `profileBackup.js`
 *     so we never sync bulky regenerable caches (TMDB posters, EPG
 *     blobs, etc.).
 *
 * Enable/disable via {@link enableVesperCloudSync}/{@link disable} —
 * they get called from AuthContext when the user's auth status
 * flips.  When disabled the localStorage monkey-patch is left in
 * place (idempotent) but no HTTP traffic fires.
 *
 * Restore is orchestrated separately by {@link CloudRestoreDialog}
 * shown from AuthContext.login().
 */

import { getToken } from '@/lib/auth';
import { collectBackupPayload } from '@/lib/profileBackup';

const API = process.env.REACT_APP_BACKEND_URL;
const DEBOUNCE_MS = 2500;

/** Global sync-event names — SideNav listens for these to briefly
 *  overlay a green tick on the profile avatar (v2.16.21 replaces
 *  the invasive sonner "Saved to profile ✓" toast the user asked
 *  us to kill).  Consumers should treat these as fire-and-forget. */
const EVENT_OK = 'vesper:cloud-sync-success';
const EVENT_ERR = 'vesper:cloud-sync-error';

function emit(name, detail) {
    try {
        window.dispatchEvent(new CustomEvent(name, { detail: detail || null }));
    } catch { /* SSR / test env */ }
}

/* Prefixes / exact keys that should trigger a debounced push when
 * they change.  Kept in sync with ESSENTIAL_KEYS/PREFIXES from
 * `lib/profileBackup.js` — profileBackup owns the actual list of
 * WHAT gets pushed (a full snapshot is collected on every push, so
 * the trigger list can be conservative). */
const TRIGGER_PREFIXES = [
    'onnowtv-profiles-v1',
    'onnowtv-active-profile-v1',
    'onnowtv-kids-config-v1',
    'onnowtv-continue-watching-v1',
    'onnowtv-watched-v1',
    'vesper-library',
    'onnowtv-theme',
    'onnowtv-viewing-style-v1',
    'onnowtv-live-favorites-v1',
    'onnowtv-live-reminders-v1',
    'onnowtv-live-recents-v1',
    'onnowtv-pref:',
    'vesper-pref-',
    // Addon selection persists which stream sources the user picked.
    'vesper-addons',
    'onnowtv-addons',
];

let enabled = false;
let pendingTimer = null;
let installed = false;

/* v2.16.23 — Push-suspend flag.  When true, `schedulePush` and
 * `pushNow` are hard-noops so the client can never overwrite the
 * server snapshot before the user has had a chance to accept /
 * decline the pull-on-login restore dialog.
 *
 * The historical bug: user logs into a fresh install → any
 * incidental localStorage write between login and the dialog
 * (theme boot, provider hydration, EPG cache marker …) armed the
 * 2.5 s debounce → the debounce fired → pushed a NEAR-EMPTY
 * payload → SERVER SNAPSHOT OVERWRITTEN → dialog then showed
 * "1 profile" (or 0) even though the server originally had 3.
 *
 * Solution: pushes are suspended from `enableVesperCloudSync()`
 * until `resumeVesperCloudSync()` is called by AuthContext once
 * the restore dialog has been dismissed one way or the other.  If
 * the pull returns no snapshot (nothing to offer) we resume
 * immediately from the login callback. */
let pushSuspended = true;

function shouldTrigger(key) {
    if (typeof key !== 'string' || !key) return false;
    return TRIGGER_PREFIXES.some((p) => key.startsWith(p));
}

function schedulePush() {
    if (!enabled) return;
    if (pushSuspended) return;
    if (pendingTimer) window.clearTimeout(pendingTimer);
    pendingTimer = window.setTimeout(() => {
        pendingTimer = null;
        pushNow(false);
    }, DEBOUNCE_MS);
}

/** POST the current localStorage snapshot to the cloud.  Returns
 *  a Promise that resolves true on 2xx, false on any failure.
 *  `silent=true` suppresses the sync-success/sync-error event so
 *  the sidebar tick doesn't fire on the pagehide-flush teardown.
 *  Also honours the push-suspend flag — see `pushSuspended` above. */
export async function pushNow(silent = false) {
    if (!enabled) return false;
    if (pushSuspended) return false;
    const token = getToken();
    if (!token) return false;
    let payload;
    try {
        // profileBackup.collectBackupPayload() returns the flat
        // {key → value} map DIRECTLY (see /lib/profileBackup.js) —
        // do NOT unwrap `.payload`, that field doesn't exist.
        payload = collectBackupPayload();
    } catch (e) {
        console.warn('[vesperCloudSync] collect failed', e);
        return false;
    }
    if (!payload || !Object.keys(payload).length) return false;
    try {
        const res = await fetch(`${API}/api/vesper/sync/push`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `Bearer ${token}`,
            },
            body: JSON.stringify({
                data: payload,
                client_updated_at: Date.now(),
            }),
            // pagehide flush needs keepalive so the browser doesn't
            // cancel the request mid-teardown.
            keepalive: true,
        });
        if (!res.ok) {
            emit(EVENT_ERR, { status: res.status });
            return false;
        }
        emit(EVENT_OK, { bytes: payload && JSON.stringify(payload).length });
        return true;
    } catch (e) {
        console.warn('[vesperCloudSync] push failed', e);
        emit(EVENT_ERR, { networkError: true });
        return false;
    }
}

/** Fetch the last cloud snapshot for the current user.  Resolves to
 *  `{found, data, updated_at, key_count, approx_bytes}` or null on
 *  any error / when signed out. */
export async function pullOnce() {
    const token = getToken();
    if (!token) return null;
    try {
        const res = await fetch(`${API}/api/vesper/sync/pull`, {
            headers: { Authorization: `Bearer ${token}` },
        });
        if (!res.ok) return null;
        const j = await res.json();
        return j && j.found ? j : null;
    } catch {
        return null;
    }
}

/** Meta-only pull — no payload, just size + timestamp.  Used by the
 *  "Last synced …" indicator without shipping the whole snapshot. */
export async function fetchMeta() {
    const token = getToken();
    if (!token) return null;
    try {
        const res = await fetch(`${API}/api/vesper/sync/meta`, {
            headers: { Authorization: `Bearer ${token}` },
        });
        if (!res.ok) return null;
        const j = await res.json();
        return j && j.found ? j : null;
    } catch {
        return null;
    }
}

/* Monkey-patch localStorage.setItem / removeItem so every write to
 * a synced key triggers the debounced push.  Idempotent — we only
 * wrap once even if `enable/disable` is toggled multiple times. */
function installHooks() {
    if (installed) return;
    installed = true;
    const origSet = Storage.prototype.setItem;
    const origDel = Storage.prototype.removeItem;
    Storage.prototype.setItem = function patchedSetItem(k, v) {
        try {
            origSet.call(this, k, v);
        } finally {
            if (this === window.localStorage && shouldTrigger(k)) {
                schedulePush();
            }
        }
    };
    Storage.prototype.removeItem = function patchedRemoveItem(k) {
        try {
            origDel.call(this, k);
        } finally {
            if (this === window.localStorage && shouldTrigger(k)) {
                schedulePush();
            }
        }
    };
    // Cross-tab writes (`storage` event) — mirror by scheduling a
    // push on this tab too so whichever tab is foregrounded ends up
    // pushing the merged state.
    window.addEventListener('storage', (e) => {
        if (shouldTrigger(e.key)) schedulePush();
    });
    // Flush before the tab is closed / reloaded so the very last
    // edit never gets orphaned inside the 2.5 s debounce window.
    const flush = () => {
        if (pendingTimer) {
            window.clearTimeout(pendingTimer);
            pendingTimer = null;
        }
        // `pushNow(silent=true)` uses fetch keepalive so the request
        // survives the pagehide teardown.  We deliberately don't
        // await it — pagehide handlers can't block.
        pushNow(true);
    };
    window.addEventListener('pagehide', flush);
    window.addEventListener('beforeunload', flush);
}

/** Enable the cloud sync loop for the currently authenticated user.
 *  Safe to call multiple times.
 *  v2.16.23 — Pushes are SUSPENDED by default when the loop is
 *  first enabled after login; call `resumeVesperCloudSync()` once
 *  the pull-on-login restore dialog has been dismissed (either
 *  action).  This prevents the "3 profiles turn into 1" bug where
 *  incidental writes during the login → dialog window would
 *  overwrite the server snapshot before the user could restore. */
export function enableVesperCloudSync() {
    installHooks();
    enabled = true;
    pushSuspended = true;
    // v2.16.18 — Diagnostic hook so support / QA can force a sync
    // from the browser console (`window.__vesperCloudSync.forceFlush()`).
    // Kept intentionally — reveals no secrets, and being able to poke
    // the sync loop from the field is worth the ~200 bytes.
    try {
        window.__vesperCloudSync = {
            pushNow, pullOnce, fetchMeta, forceFlush,
            get enabled() { return enabled; },
            get pending() { return !!pendingTimer; },
            get suspended() { return pushSuspended; },
        };
    } catch { /* SSR guard */ }
}

/** Resume pushes after the initial pull-on-login has been resolved.
 *  Cancels any push that was queued while suspended (they were
 *  no-ops on schedule anyway; this just clears the pending flag). */
export function resumeVesperCloudSync() {
    pushSuspended = false;
}

/** Suspend cloud sync (used on logout).  Leaves the monkey-patch in
 *  place — flipping `enabled=false` is enough to no-op every push. */
export function disableVesperCloudSync() {
    enabled = false;
    pushSuspended = true;
    if (pendingTimer) {
        window.clearTimeout(pendingTimer);
        pendingTimer = null;
    }
}

/** Force-flush any queued push immediately, bypassing the debounce.
 *  Wire onto explicit "Sync now" buttons if / when we add one. */
export async function forceFlush() {
    if (pendingTimer) {
        window.clearTimeout(pendingTimer);
        pendingTimer = null;
    }
    return pushNow(false);
}
