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
 * reads — so the Live TV grid paints fully-populated the moment
 * the user opens it, with zero per-client Xtream round-trips.
 *
 * The xtream auto-seed in `lib/xtream.js` still runs as a fallback
 * for clients that can't reach the backend (offline, dev, etc.) —
 * but when the bundle responds, it wins.
 */
import { saveCategories, saveChannels, mergeAndSaveEpg } from './liveCache';
import { getFavorites } from './liveFavorites';

const API = process.env.REACT_APP_BACKEND_URL;
const PROVIDERS_KEY = 'onnowtv-xtream-providers-v1';
const ACTIVE_KEY    = 'onnowtv-active-xtream-provider-v1';

/* The bundle's "managed-XXX" provider ID maps directly to the
 * provider record we seed into localStorage so the existing LiveTV
 * page treats it like any user-configured provider. */
function getProviders() {
    try {
        const raw = localStorage.getItem(PROVIDERS_KEY);
        return raw ? JSON.parse(raw) : [];
    } catch { return []; }
}
function setProviders(list) {
    try { localStorage.setItem(PROVIDERS_KEY, JSON.stringify(list)); } catch { /* ignore */ }
}

/**
 * Fetch the backend bundle and write it through to localStorage.
 * Returns true when the bundle was successfully applied so callers
 * can skip the legacy "fetch from Xtream" path.
 */
export async function bootInstantBundle() {
    if (!API) return false;
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

    /* 1. Register the managed provider in localStorage so the
     *    LiveTV page can find it.  We add the username/password
     *    fields as empty strings — they're not needed because each
     *    channel record already ships with its full `stream_url`. */
    const provider = {
        id:         bundle.provider.id,
        name:       bundle.provider.name || 'On Now TV',
        scheme:     bundle.provider.scheme || 'https',
        host:       bundle.provider.host,
        port:       bundle.provider.port || '443',
        username:   '__managed__',
        password:   '__managed__',
        managed:    true,
        addedAt:    Date.now(),
    };
    const existing = getProviders();
    const filtered = existing.filter((p) => p && p.id !== provider.id);
    setProviders([provider, ...filtered]);
    if (!localStorage.getItem(ACTIVE_KEY)) {
        try { localStorage.setItem(ACTIVE_KEY, provider.id); } catch { /* ignore */ }
    }

    /* 2. Categories — shape matches what `liveCache.saveCategories`
     *    expects from the legacy LiveTV.jsx loader. */
    const cats = bundle.categories.map((c) => ({
        category_id:   c.id,
        category_name: c.name,
    }));
    saveCategories(provider.id, cats);

    /* 3. Channels — bucketed per category_id so the LiveTV grid
     *    can render category-by-category without filtering on
     *    every render. */
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
            /* Pre-built stream URL — the client just plays this.
             * Means clients never need to know the Xtream creds. */
            direct_source:  ch.stream_url,
        });
    }
    saveChannels(provider.id, byCat);

    /* 4. EPG — merge into the existing cache (keeps anything we
     *    already had for channels the backend doesn't yet cover). */
    mergeAndSaveEpg(provider.id, bundle.epg || {});

    /* 5. Stamp the bundle metadata so the LiveTV page can show a
     *    "last refreshed N min ago" hint if we want it later. */
    try {
        localStorage.setItem(
            'onnowtv-instant-bundle-meta',
            JSON.stringify({
                generated_at:        bundle.generated_at,
                channels_fetched_at: bundle.channels_fetched_at,
                epg_fetched_at:      bundle.epg_fetched_at,
                applied_at:          Date.now(),
                channel_count:       bundle.channels.length,
            }),
        );
    } catch { /* ignore */ }

    // Touch favorites to keep the helper happy (and we already
    // import it so tree-shaking can drop it cleanly).
    void getFavorites(provider.id);

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
