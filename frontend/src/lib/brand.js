import { REBORN_WORDMARK_DATA as rebornWordmark, REBORN_MARK_DATA as rebornMark } from '@/assets/brand/rebornAssets';

// Reborn Play = the combined VOD + Live TV app (tv.onnowtv.one).
export const REBORN_PACKAGE = 'tv.onnowtv.one';

export function isRebornBrand() {
    try {
        if (window.__vesperHostPackage === REBORN_PACKAGE) return true;
        if (typeof window.OnNowTV?.getHostPackage === 'function') {
            return String(window.OnNowTV.getHostPackage() || '') === REBORN_PACKAGE;
        }
    } catch { /* not in a native shell */ }
    try {
        if (new URLSearchParams(window.location.search).get('brand') === 'reborn') sessionStorage.setItem('reborn-brand', '1');
        return sessionStorage.getItem('reborn-brand') === '1';
    } catch { return false; }
}

export function applyBrand() {
    if (isRebornBrand()) {
        document.documentElement.setAttribute('data-brand', 'reborn');
        document.title = 'Reborn Play';
    }
}

export const REBORN_WORDMARK = rebornWordmark;
export const REBORN_MARK = rebornMark;

export function brandName() { return isRebornBrand() ? 'Reborn Play' : 'ON NOW TV'; }
export function brandFull() { return isRebornBrand() ? 'Reborn Play' : 'ON NOW TV V2'; }

/** Cloud-sync scope: Reborn Play keeps its own cloud profiles, never Vesper's. */
export function appScopeHeader() { return isRebornBrand() ? { 'X-App-Scope': 'reborn' } : {}; }
