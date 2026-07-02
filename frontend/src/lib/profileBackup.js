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

import { accountSuffix } from '@/lib/profileScope';

const PREFIXES = [
    'onnowtv-',
    'vesper-',
];

/**
 * Keys / prefixes to EXCLUDE from backups.  These are regenerable
 * server-side caches — shipping them across devices bloats the
 * payload past the server's BSON limit (and they're stale by
 * lunchtime anyway).
 *
 *   • `onnowtv-livecache-*` — channel logos + EPG per provider.
 *     Re-warmed from `/api/xtream/cached-epg` on first Live TV open.
 *   • `onnowtv-channelcache-*` — Xtream channel-list cache.
 *   • `vesper-tmdb-*` — TMDB poster / network art cache.
 *   • `vesper-recent-*` — last 50 plays per profile (kept locally,
 *     not worth syncing across devices).
 *   • `onnowtv-xmltv-*` — gzipped XMLTV blobs that some older builds
 *     persisted to localStorage instead of the modern in-memory
 *     cache.  Always megabytes in size, always regenerable.
 *   • `onnowtv-bootcache-*` — Live TV boot splash snapshot.
 *   • `vesper-poster-*` / `vesper-backdrop-*` — TMDB art blobs that
 *     a previous build wrote as base64 strings.
 */
const EXCLUDE_PREFIXES = [
    'onnowtv-livecache-',
    'onnowtv-channelcache-',
    'onnowtv-xmltv-',
    'onnowtv-bootcache-',
    'vesper-tmdb-',
    'vesper-recent-',
    'vesper-poster-',
    'vesper-backdrop-',
    'vesper-party-breadcrumbs', // diagnostic log, regenerates per session
];

/* Cap per-key size.  Even if a key wasn't explicitly excluded, drop
   it if it's bigger than this — almost certainly cache pollution. */
const MAX_PER_KEY_BYTES = 128 * 1024; // 128 KB

/* Target total payload size.  If we end up over this after the basic
   filtering, we progressively trim — dropping the LARGEST remaining
   non-essential keys first.  This is a generous limit; the backend
   stores the payload gzipped so the actual wire size is way smaller. */
const TARGET_TOTAL_BYTES = 4 * 1024 * 1024; // 4 MB raw → ~500 KB gzipped

/* Keys we MUST keep — never drop these even if we're over budget.
   v2.13.5 — This list previously named keys that DON'T EXIST
   (`vesper-cw-`, `vesper-library-`, `vesper-theme-`…), so Continue
   Watching / library / theme were treated as droppable cache and the
   restore preview always counted ZERO of them.  These are the REAL
   key families (all profile-scoped variants match by prefix). */
const ESSENTIAL_KEYS = new Set([
    'onnowtv-provider-v1',     // saved Xtream credentials
    // v2.10.24 — User-uploaded avatar blobs (PNG / JPEG / animated
    // GIF) live here.  Bypass the 128 KB per-key cap so a 512×512
    // animated GIF (typically 200–800 KB) survives a backup-and-
    // restore round-trip onto a new device.
    'onnowtv-custom-avatars-v1',
    'onnowtv-music-library-v1',
    'onnowtv-music-playlists-v1',
]);
const ESSENTIAL_PREFIXES = [
    'onnowtv-profiles-v1',            // profiles (+ :account variants)
    'onnowtv-active-profile-v1',
    'onnowtv-kids-config-v1',
    'onnowtv-continue-watching-v1',   // Continue Watching, every profile scope
    'onnowtv-watched-v1',             // watched flags, every profile scope
    'vesper-library',                 // favourites + watch-later, every scope
    'onnowtv-theme',                  // per-profile theme choice
    'onnowtv-viewing-style-v1',       // profile-setup taste picks
    'onnowtv-live-favorites-v1',      // Live TV favourites
    'onnowtv-live-reminders-v1',      // EPG reminders
    'onnowtv-pref:',
    'vesper-pref-',
];

/** Best-effort byte count for a string (UTF-8). */
function bytesOf(s) {
    if (s == null) return 0;
    /* TextEncoder gives an exact count when available; otherwise the
       char-count is a good-enough overestimate. */
    try {
        return new TextEncoder().encode(s).length;
    } catch {
        return s.length;
    }
}

function isEssential(key) {
    if (ESSENTIAL_KEYS.has(key)) return true;
    return ESSENTIAL_PREFIXES.some((p) => key.startsWith(p));
}

/** Read every matching key into a plain object.  Skips the bulky
 *  regenerable caches listed in EXCLUDE_PREFIXES so the backup fits
 *  comfortably inside the server's BSON-document limit.
 *
 *  Returns `{ payload, dropped, totalBytes }` so the UI can show
 *  a friendly diagnostic if anything had to be trimmed. */
export function collectBackupPayload() {
    /* Pass 1 — filter by prefix + per-key size cap. */
    const candidates = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key) continue;
        if (!PREFIXES.some((p) => key.startsWith(p))) continue;
        if (EXCLUDE_PREFIXES.some((p) => key.startsWith(p))) continue;
        let value;
        try {
            value = localStorage.getItem(key);
        } catch { continue; }
        if (value == null) continue;
        const size = bytesOf(value);
        if (size > MAX_PER_KEY_BYTES && !isEssential(key)) {
            /* Drop oversize non-essential blobs (usually cache
               pollution from older builds). */
            continue;
        }
        candidates.push({ key, value, size, essential: isEssential(key) });
    }

    /* Pass 2 — progressive trim if we're still over budget.  Sort
       by (essential first, then ascending size); keep adding until
       we hit the target.  Essential keys are always included even
       if we end up over budget. */
    candidates.sort((a, b) => {
        if (a.essential !== b.essential) return a.essential ? -1 : 1;
        return a.size - b.size;
    });

    const out = {};
    const dropped = [];
    let total = 0;
    for (const c of candidates) {
        if (c.essential) {
            out[c.key] = c.value;
            total += c.size;
        } else if (total + c.size <= TARGET_TOTAL_BYTES) {
            out[c.key] = c.value;
            total += c.size;
        } else {
            dropped.push(c.key);
        }
    }

    return Object.assign(out, {
        /* Diagnostic only — Object.assign onto a plain object so the
           caller can iterate values without these leaking into the
           saved set.  Stripped by the wrapper before posting. */
    });
}

/* Some callers (the Settings page) want diagnostic info — kept
   separately so collectBackupPayload remains a flat map of
   string→string suitable for JSON.stringify. */
export function collectBackupPayloadWithStats() {
    const payload = collectBackupPayload();
    const totalBytes = Object.entries(payload).reduce(
        (s, [, v]) => s + bytesOf(v), 0
    );
    return { payload, totalBytes };
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
    normalizeAccountKeys(payload);
    return { written, skipped };
}

/* Account-scoped core keys (profiles.js suffixes these with the
   signed-in username).  A backup made under account A must still
   surface its profiles when restored on a box signed into account B
   — remap whichever variant carries data onto BOTH the current
   account's suffixed key and the unsuffixed fallback. */
const ACCOUNT_SCOPED_BASES = [
    'onnowtv-profiles-v1',
    'onnowtv-active-profile-v1',
    'onnowtv-kids-config-v1',
];

function normalizeAccountKeys(payload) {
    const suffix = accountSuffix();
    for (const base of ACCOUNT_SCOPED_BASES) {
        let val = payload[`${base}${suffix}`] ?? payload[base];
        if (val == null) {
            const alt = Object.keys(payload).find((k) => k.startsWith(`${base}:`));
            if (alt) val = payload[alt];
        }
        if (val == null) continue;
        const str = typeof val === 'string' ? val : JSON.stringify(val);
        try {
            localStorage.setItem(base, str);
            if (suffix) localStorage.setItem(`${base}${suffix}`, str);
        } catch { /* ignore */ }
    }
}

/** Human-readable summary of what a backup payload contains, shown
 *  after saving and before restoring so the user can see exactly
 *  what's inside (profiles by name, Continue Watching entries,
 *  library items, Live TV favourites, reminders). */
export function summarizeBackupPayload(payload) {
    const profiles = new Map();
    let cwCount = 0;
    let libraryCount = 0;
    let liveFavourites = 0;
    let reminders = 0;
    let keyCount = 0;
    for (const [key, raw] of Object.entries(payload || {})) {
        if (typeof raw !== 'string') continue;
        keyCount += 1;
        try {
            if (key.startsWith('onnowtv-profiles-v1')) {
                for (const p of JSON.parse(raw) || []) {
                    if (p && p.id && !profiles.has(p.id)) {
                        profiles.set(p.id, p.name || 'Profile');
                    }
                }
            } else if (key.startsWith('onnowtv-continue-watching-v1')) {
                const v = JSON.parse(raw);
                if (Array.isArray(v)) cwCount += v.length;
            } else if (key.startsWith('vesper-library')) {
                const v = JSON.parse(raw) || {};
                const favs = v.favorites || {};
                const wl = v.watchLater || [];
                libraryCount += Array.isArray(favs) ? favs.length : Object.keys(favs).length;
                libraryCount += Array.isArray(wl) ? wl.length : Object.keys(wl).length;
            } else if (key.startsWith('onnowtv-live-favorites-v1')) {
                const v = JSON.parse(raw);
                liveFavourites += Array.isArray(v) ? v.length : Object.keys(v || {}).length;
            } else if (key.startsWith('onnowtv-live-reminders-v1')) {
                const v = JSON.parse(raw);
                reminders += Array.isArray(v) ? v.length : Object.keys(v || {}).length;
            }
        } catch { /* unparseable — still backed up, just not counted */ }
    }
    return {
        profileCount: profiles.size,
        profileNames: Array.from(profiles.values()),
        cwCount,
        libraryCount,
        liveFavourites,
        reminders,
        keyCount,
    };
}

/** Friendly byte size formatter. */
export function fmtBytes(n) {
    if (!n) return '0 B';
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1024 / 1024).toFixed(2)} MB`;
}
