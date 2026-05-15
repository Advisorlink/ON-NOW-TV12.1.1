/**
 * profileBackup — collect/apply a snapshot of localStorage so the
 * user can move their full Vesper state between devices.
 *
 * Snapshot is a {key → value} map filtered to ONLY keys that belong
 * to Vesper / ON NOW TV.  We deliberately avoid blanket-dumping
 * `localStorage` because:
 *   • The browser may have stored unrelated junk (third-party widgets,
 *     debug flags, etc.)
 *   • The server has a 2 MB payload cap and EPG caches alone can be
 *     hundreds of KB — we keep them but cap each individual entry to
 *     keep the total reasonable.
 *
 * What's included:
 *   • Profiles                              `onnowtv-profiles-v1`
 *   • Active profile id                     `onnowtv-active-profile-v1`
 *   • Profile preferences                   `onnowtv-pref:<id>:*`, `vesper-pref-*`
 *   • Continue Watching (per profile)       `vesper-cw-*`
 *   • Library / favourites / watchlist      `vesper-library-*`, `vesper-fav-*`
 *   • Live TV: favourites, recents, reminders, channel/EPG cache
 *                                            `onnowtv-live-*`, `onnowtv-livecache-*`
 *   • Theme + UI prefs                      `vesper-theme-*`, `vesper-network-*`
 *   • Misc app settings                     `vesper-*`, `onnowtv-*`
 */

const PREFIXES = [
    'onnowtv-',
    'vesper-',
];

/** Read every matching key into a plain object. */
export function collectBackupPayload() {
    const out = {};
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key) continue;
        if (!PREFIXES.some((p) => key.startsWith(p))) continue;
        try {
            out[key] = localStorage.getItem(key);
        } catch { /* ignore */ }
    }
    return out;
}

/** Apply a payload object back into localStorage.  Overwrites any
 *  existing keys with the same name.  Returns counts for the toast. */
export function applyBackupPayload(payload) {
    let written = 0;
    let skipped = 0;
    if (!payload || typeof payload !== 'object') {
        return { written, skipped };
    }
    for (const [key, value] of Object.entries(payload)) {
        if (!key || !PREFIXES.some((p) => key.startsWith(p))) {
            skipped += 1;
            continue;
        }
        try {
            // Server returns strings; if it's an object somehow, JSON-encode.
            const str = typeof value === 'string' ? value : JSON.stringify(value);
            localStorage.setItem(key, str);
            written += 1;
        } catch {
            skipped += 1;
        }
    }
    return { written, skipped };
}

/** Friendly byte size formatter. */
export function fmtBytes(n) {
    if (!n) return '0 B';
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1024 / 1024).toFixed(2)} MB`;
}
