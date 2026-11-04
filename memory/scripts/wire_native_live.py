import os
os.chdir('/app/frontend/src')
p = 'lib/liveTv.js'; s = open(p).read()
old = """export function prefersNativeLiveTv() {
    if (isRebornBrand()) return false;
    try { return !!window.OnNowTV?.hasLiveTv?.(); } catch { return false; }
}"""
new = """export function prefersNativeLiveTv() {
    try { return !!window.OnNowTV?.hasLiveTv?.(); } catch { return false; }
}
/** Reborn Play: pull channels + guide natively right after sign-in (shows the loading screen). */
export function prefetchNativeLiveTv() {
    try { if (window.OnNowTV?.hasLiveTv?.()) { window.OnNowTV.prefetchLiveTv?.(); return true; } } catch { /* web */ }
    return false;
}"""
assert old in s; s = s.replace(old, new, 1)
open(p, 'w').write(s)

p = 'lib/auth.js'; s = open(p).read()
old = """    try {
        const p = applyAccountIptv(data.iptv, username, password);
        if (p) preloadLiveTv(p, { force: true });
    } catch { /* live tv optional */ }
    return data;"""
new = """    try {
        const p = applyAccountIptv(data.iptv, username, password);
        if (p && !prefetchNativeLiveTv()) preloadLiveTv(p, { force: true });
    } catch { /* live tv optional */ }
    return data;"""
assert old in s; s = s.replace(old, new, 1)
old = """    try {
        const p = applyAccountIptv(data?.iptv, data?.account?.username, null);
        if (p) preloadLiveTv(p);
    } catch { /* live tv optional */ }"""
new = """    try {
        const p = applyAccountIptv(data?.iptv, data?.account?.username, null);
        if (p && !prefersNativeLiveTv()) preloadLiveTv(p);
    } catch { /* live tv optional */ }"""
assert old in s; s = s.replace(old, new, 1)
s = s.replace("import { applyAccountIptv, preloadLiveTv } from '@/lib/liveAccount';", "import { applyAccountIptv, preloadLiveTv } from '@/lib/liveAccount';\nimport { prefetchNativeLiveTv, prefersNativeLiveTv } from '@/lib/liveTv';", 1)
open(p, 'w').write(s)
print('ok')
