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

/* Keys we MUST keep — never drop these even if we're over budget. */
const ESSENTIAL_KEYS = new Set([
    'onnowtv-profiles-v1',
    'onnowtv-active-profile-v1',
    'onnowtv-provider-v1',     // saved Xtream credentials
    // v2.10.24 — User-uploaded avatar blobs (PNG / JPEG / animated
    // GIF) live here.  Bypass the 128 KB per-key cap so a 512×512
    // animated GIF (typically 200–800 KB) survives a backup-and-
    // restore round-trip onto a new device.
    'onnowtv-custom-avatars-v1',
]);
const ESSENTIAL_PREFIXES = [
    'onnowtv-pref:',
    'vesper-library-',
    'vesper-fav-',
    'vesper-cw-',           // Continue Watching state
    'onnowtv-live-fav-',    // Live TV favourites
    'onnowtv-live-rem-',    // EPG reminders
    'vesper-pref-',
    'vesper-theme-',
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
    return { written, skipped };
}

/** Friendly byte size formatter. */
export function fmtBytes(n) {
    if (!n) return '0 B';
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
    return `${(n / 1024 / 1024).toFixed(2)} MB`;
}
