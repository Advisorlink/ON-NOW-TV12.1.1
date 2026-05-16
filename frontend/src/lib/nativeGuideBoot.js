/**
 * pushNativeGuideFromCache()
 *
 * Pushes whatever Live TV data we already have cached in
 * localStorage straight to the native player via the
 * `window.OnNowTV.setLiveGuide()` bridge.  Called as soon as the
 * React app boots so the in-player Live Guide overlay has EPG +
 * channel data the *moment* the user opens it — even if they go
 * straight to a channel from Continue Watching or the Hero and
 * never visit the Live TV page during this session.
 *
 * No-op when:
 *   • Not running inside the Android WebView (no bridge).
 *   • No active Xtream provider configured.
 *   • No cached channels yet.
 *
 * Idempotent and silent on failure.
 */
import { getActiveProvider } from './xtream';
import { loadCategories, loadChannels, loadEpg } from './liveCache';

export function pushNativeGuideFromCache() {
    if (typeof window === 'undefined') return;
    const bridge = window.OnNowTV;
    if (!bridge || typeof bridge.setLiveGuide !== 'function') return;

    const provider = getActiveProvider();
    if (!provider) return;

    const cats = loadCategories(provider.id) || [];
    const chans = loadChannels(provider.id) || {};
    const epg = loadEpg(provider.id) || {};

    if (cats.length === 0 && Object.keys(chans).length === 0) return;

    try {
        const categoriesPayload = cats.map((c) => ({
            id: String(c.category_id),
            name: String(c.category_name || ''),
            count: (chans[c.category_id] || []).length,
        }));

        const channelsPayload = [];
        for (const catId of Object.keys(chans)) {
            for (const c of (chans[catId] || [])) {
                const scheme = provider.scheme || 'http';
                const portPart =
                    provider.port && provider.port !== '80' && provider.port !== '443'
                        ? `:${provider.port}` : '';
                const streamUrl =
                    `${scheme}://${provider.host}${portPart}/live/` +
                    `${encodeURIComponent(provider.username)}/` +
                    `${encodeURIComponent(provider.password)}/` +
                    `${c.stream_id}.ts`;
                channelsPayload.push({
                    stream_id: String(c.stream_id),
                    name: String(c.name || ''),
                    logo: String(c.stream_icon || ''),
                    category_id: String(catId),
                    epg_channel_id: String(c.epg_channel_id || ''),
                    stream_url: streamUrl,
                });
            }
        }

        // Trim EPG to next 6 hours, top 4 entries per channel — same
        // budget as LiveTV.jsx so SharedPreferences stays small.
        const epgPayload = {};
        const nowSec = Math.floor(Date.now() / 1000);
        const horizon = nowSec + 6 * 3600;
        for (const sid of Object.keys(epg)) {
            const list = epg[sid] || [];
            const trimmed = [];
            for (const it of list) {
                if (Number(it.stopTimestamp || 0) < nowSec) continue;
                if (Number(it.startTimestamp || 0) > horizon) break;
                trimmed.push({
                    title: it.title || '',
                    startTimestamp: it.startTimestamp || 0,
                    stopTimestamp: it.stopTimestamp || 0,
                });
                if (trimmed.length >= 4) break;
            }
            if (trimmed.length) epgPayload[sid] = trimmed;
        }

        bridge.setLiveGuide(
            String(provider.id || ''),
            JSON.stringify(categoriesPayload),
            JSON.stringify(channelsPayload),
            JSON.stringify(epgPayload),
        );
    } catch {
        /* silent — overlay will just be empty until next LiveTV visit */
    }
}
