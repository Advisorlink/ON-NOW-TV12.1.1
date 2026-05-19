/**
 * Instant Bundle — client-side bootstrap for the backend-managed
 * Live TV catalogue.
 *
 * Backend ships at `/api/xtream/instant-bundle` a gzipped JSON
 * payload containing:
 *   {
 *     provider:    { id, name, host, port, scheme },   // no creds!
 *     categories:  [{ id, name }],
 *     channels:    [{ stream_id, name, logo, category_id,
 *                     epg_channel_id, stream_url }],
 *     epg:         { [streamId]: [{ title, desc, startTimestamp,
 *                                    stopTimestamp, ... }] },
 *     generated_at, channels_fetched_at, epg_fetched_at,
 *   }
 *
 * This module fetches that payload on app boot and writes it into
 * the same localStorage shape the existing LiveTV page already
 * reads — keyed under the user's ACTIVE Xtream provider id so the
 * grid paints fully-populated the moment the user opens it, with
 * zero per-client Xtream round-trips.
 *
 * The xtream auto-seed in `lib/xtream.js` already seeds the
 * `default-njala` provider with real creds (so playback works);
 * this module just pre-warms its category/channel/EPG caches so
 * the EPG loop on Live TV launch is skipped entirely.
 */
import { saveCategories, saveChannels, mergeAndSaveEpg } from './liveCache';
import { getActiveProvider, listProviders } from './xtream';

const API = process.env.REACT_APP_BACKEND_URL;
const META_KEY = 'onnowtv-instant-bundle-meta';

/**
 * Pick the provider whose cache the bundle should seed.  The
 * bundle is built from the SAME Xtream creds that `auto-seed`'s
 * `default-njala` provider has, so we seed under whichever
 * provider points at our managed host (`njala.ddns.me` /
 * `LIVETV_HOST` in backend `.env`) so playback URLs match.
 *
 * Falls back to the active provider, then the first provider in
 * the list, then null (no-op).
 */
function pickSeedProviderId(bundleHost) {
    const host = String(bundleHost || '').toLowerCase();
    const all = listProviders();
    if (host) {
        const match = all.find(
            (p) => String(p.host || '').toLowerCase() === host,
        );
        if (match) return match.id;
    }
    const active = getActiveProvider();
    if (active) return active.id;
    if (all.length) return all[0].id;
    return null;
}

/**
 * Fetch the backend bundle and write it through to localStorage.
 * Returns true when the bundle was successfully applied so callers
 * can skip the legacy "fetch from Xtream" path.
 */
export async function bootInstantBundle() {
    if (!API) return false;

    /* Fast-path: tiny `/meta` probe first.  If the backend's
     * `epg_fetched_at` matches our local copy, the cache is fresh
     * — skip the 7 MB full-bundle fetch entirely.  This is what
     * makes 2nd-and-onwards app launches feel instant: no big
     * download, the in-memory + localStorage cache from the
     * previous session just keeps working.
     *
     * We only honour the fast-path when there's also a populated
     * provider on the user's device (so the very first run still
     * triggers the full pull). */
    try {
        const metaRes = await fetch(`${API}/api/xtream/instant-bundle/meta`, {
            cache: 'no-store',
        });
        if (metaRes.ok) {
            const serverMeta = await metaRes.json();
            const localRaw = localStorage.getItem(META_KEY);
            const localMeta = localRaw ? JSON.parse(localRaw) : null;
            const allProviders = listProviders();
            const haveLocalCache = !!(
                localMeta
                && localMeta.epg_fetched_at
                && serverMeta.epg_fetched_at === localMeta.epg_fetched_at
                && allProviders.length > 0
            );
            if (haveLocalCache) {
                /* Already in sync — touch applied_at so the "last
                 * refreshed" UI stays accurate. */
                localStorage.setItem(META_KEY, JSON.stringify({
                    ...localMeta,
                    applied_at: Date.now(),
                }));
                return true;
            }
        }
    } catch { /* meta probe failed — fall through to full fetch */ }

    let bundle;
    try {
        const res = await fetch(`${API}/api/xtream/instant-bundle`, {
            cache: 'no-store',
        });
        if (!res.ok) return false;
        bundle = await res.json();
    } catch {
        return false;
    }
    if (!bundle || !bundle.provider || !Array.isArray(bundle.channels)) {
        return false;
    }
    if (bundle.channels.length === 0) {
        /* Backend not warmed up yet — first-boot race on the
         * production pod.  Skip so we don't clobber any existing
         * locally-cached EPG with empty data. */
        return false;
    }

    /* Decide which provider id this bundle should seed under. */
    const seedId = pickSeedProviderId(bundle.provider?.host);
    if (!seedId) return false;

    /* 1. Categories — shape matches what LiveTV.jsx reads via
     *    `loadCategories(provider.id)` from liveCache.js. */
    const cats = bundle.categories.map((c) => ({
        category_id:   c.id,
        category_name: c.name,
    }));
    saveCategories(seedId, cats);

    /* 2. Channels — bucketed per category_id so the LiveTV grid
     *    can render category-by-category without filtering on
     *    every render.  We store the pre-built `stream_url` on
     *    `direct_source` so the player can also use it if the
     *    user's local creds ever stop working. */
    const byCat = {};
    for (const ch of bundle.channels) {
        const cat = String(ch.category_id || '');
        if (!byCat[cat]) byCat[cat] = [];
        byCat[cat].push({
            stream_id:      ch.stream_id,
            name:           ch.name,
            stream_icon:    ch.logo,
            epg_channel_id: ch.epg_channel_id,
            tv_archive:     ch.tv_archive || 0,
            direct_source:  ch.stream_url,
        });
    }
    saveChannels(seedId, byCat);

    /* 3. EPG — merge into the existing cache (keeps anything we
     *    already had for channels the backend doesn't yet cover). */
    if (bundle.epg && typeof bundle.epg === 'object') {
        mergeAndSaveEpg(seedId, bundle.epg);
    }

    /* 4. Stamp the bundle metadata so the LiveTV page (or a future
     *    "last refreshed N min ago" hint) can read it. */
    try {
        localStorage.setItem(
            META_KEY,
            JSON.stringify({
                generated_at:        bundle.generated_at,
                channels_fetched_at: bundle.channels_fetched_at,
                epg_fetched_at:      bundle.epg_fetched_at,
                applied_at:          Date.now(),
                channel_count:       bundle.channels.length,
                provider_id_seeded:  seedId,
            }),
        );
    } catch { /* ignore */ }

    return true;
}

/**
 * Reload only the lightweight metadata (counts + timestamps) so the
 * caller can decide whether to re-pull the full bundle.  Used by
 * the periodic refresh path to avoid downloading 700 KB every time.
 */
export async function fetchInstantBundleMeta() {
    if (!API) return null;
    try {
        const res = await fetch(`${API}/api/xtream/instant-bundle/meta`, {
            cache: 'no-store',
        });
        if (!res.ok) return null;
        return await res.json();
    } catch {
        return null;
    }
}
