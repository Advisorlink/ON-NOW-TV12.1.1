/* Native Live TV module bridge (present only in the ON NOW one-app Android build). */
import { isRebornBrand } from '@/lib/brand';

/** Live TV entry is shown when the web guide (Reborn Play) or the native module is available. */
export function hasLiveTv() {
    if (isRebornBrand()) return true;
    try { return !!window.OnNowTV?.hasLiveTv?.(); } catch { return false; }
}
/** True when the guide should be the native module rather than the in-app web guide. */
export function prefersNativeLiveTv() {
    try { return !!window.OnNowTV?.hasLiveTv?.(); } catch { return false; }
}
/** Reborn Play: pull channels + guide natively right after sign-in (shows the loading screen). */
export function prefetchNativeLiveTv() {
    try { if (window.OnNowTV?.hasLiveTv?.()) { window.OnNowTV.prefetchLiveTv?.(); return true; } } catch { /* web */ }
    return false;
}
export function openLiveTv() {
    try { window.OnNowTV?.openLiveTv?.(); return true; } catch { return false; }
}
export function openLiveTvSettings() {
    try { window.OnNowTV?.openLiveTvSettings?.(); return true; } catch { return false; }
}
