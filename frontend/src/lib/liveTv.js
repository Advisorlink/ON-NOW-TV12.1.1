/* Native Live TV module bridge (present only in the ON NOW one-app Android build). */
export function hasLiveTv() {
    try { return !!window.OnNowTV?.hasLiveTv?.(); } catch { return false; }
}
export function openLiveTv() {
    try { window.OnNowTV?.openLiveTv?.(); return true; } catch { return false; }
}
export function openLiveTvSettings() {
    try { window.OnNowTV?.openLiveTvSettings?.(); return true; } catch { return false; }
}
