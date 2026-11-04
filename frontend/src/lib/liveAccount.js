/* One sign-in → Live TV provider is configured from the account and the guide is pre-loaded. */
import { saveProvider, setActiveProvider, getActiveProvider, authenticate, getCategories, getStreams } from '@/lib/xtream';
import { saveCategories, saveChannels, loadEpg } from '@/lib/liveCache';
import { bootInstantBundle } from '@/lib/instantBundle';

export const ACCOUNT_PROVIDER_ID = 'account';
const KEY_PRELOADED = 'reborn-live-preloaded-at';

export function applyAccountIptv(iptv, username, password) {
    if (!iptv?.host) return null;
    const xu = (iptv.xtream_username || '').trim() || username;
    const prev = getActiveProvider();
    const xp = (iptv.xtream_password || '').trim() || password || (prev?.id === ACCOUNT_PROVIDER_ID && prev.username === xu ? prev.password : '');
    if (!xu || !xp) return null;
    const provider = saveProvider({
        id: ACCOUNT_PROVIDER_ID,
        name: 'Reborn Play',
        host: iptv.host,
        port: iptv.port || '',
        scheme: iptv.scheme || 'https',
        username: xu,
        password: xp,
    });
    setActiveProvider(ACCOUNT_PROVIDER_ID);
    if (prev?.username !== xu || prev?.password !== xp) localStorage.removeItem(KEY_PRELOADED);
    return provider;
}

let inflight = null;
export function preloadLiveTv(provider = getActiveProvider(), { force = false } = {}) {
    if (!provider) return Promise.resolve(false);
    const last = Number(localStorage.getItem(KEY_PRELOADED) || 0);
    if (!force && Date.now() - last < 6 * 3600e3) return Promise.resolve(true);
    if (inflight) return inflight;
    inflight = (async () => {
        try {
            authenticate(provider).catch(() => {});
            // Server-side bundle = channels + categories + guide in one download.
            const bundled = await bootInstantBundle().catch(() => false);
            if (bundled && Object.keys(loadEpg(provider.id) || {}).length) {
                localStorage.setItem(KEY_PRELOADED, String(Date.now()));
                window.dispatchEvent(new CustomEvent('reborn:live-preloaded'));
                return true;
            }
            const [cats, chans] = await Promise.all([
                getCategories(provider, 'live'),
                getStreams(provider, 'live'),
            ]);
            if (Array.isArray(cats) && cats.length) saveCategories(provider.id, cats);
            if (Array.isArray(chans) && chans.length) {
                const byCat = {};
                for (const ch of chans) {
                    const cid = String(ch.category_id ?? '');
                    (byCat[cid] ||= []).push(ch);
                }
                saveChannels(provider.id, byCat);
            }
            localStorage.setItem(KEY_PRELOADED, String(Date.now()));
            window.dispatchEvent(new CustomEvent('reborn:live-preloaded'));
            return true;
        } catch {
            return false;
        } finally {
            inflight = null;
        }
    })();
    return inflight;
}
