/**
 * navLoader — imperative, zero-React-cost full-screen "title loading"
 * overlay.  Used to mask the JS mount cost of the heavyweight
 * `Detail` route on low-end Android TV WebViews where, after a
 * poster tile tap, React can take 200–400 ms to unmount Home +
 * mount Detail before Detail's own spinner paints.  The user
 * reported the app "feels slow" — nothing visible happens during
 * that gap.
 *
 * Why imperative (DOM) instead of React state?
 *   • `setState` → reconcile → commit → paint.  On a 2944-line
 *     Detail.jsx + a 100-tile Home shelf, the reconcile step alone
 *     can take ~80–120 ms on a HK1 box.
 *   • Direct DOM `style.display = 'flex'` → paint on the next
 *     frame, no React work at all.  Empirically ~16–32 ms.
 *
 * Lifecycle:
 *   • `showNavLoader()` is called SYNCHRONOUSLY just before a
 *     `navigate('/title/...')` call from any tile component.
 *   • `hideNavLoader()` is called from Detail / Player in a
 *     `useLayoutEffect` so it fires after the first commit but
 *     before the browser paints — meaning the new page's own
 *     spinner replaces this overlay seamlessly.
 *   • Safety: auto-hide on `popstate` (back button) + a 6-second
 *     hard timeout so a route that fails to mount never leaves
 *     the overlay stranded on screen.
 */

const OVERLAY_ID = 'vesper-nav-loader';
const AUTO_HIDE_MS = 6000;

let overlayEl = null;
let autoHideTimer = null;
let installed = false;

function ensureOverlay() {
    if (overlayEl) return overlayEl;
    if (typeof document === 'undefined') return null;
    const el = document.createElement('div');
    el.id = OVERLAY_ID;
    el.setAttribute('data-testid', 'nav-loader');
    el.setAttribute('aria-hidden', 'true');
    // Inline styles — no class lookup, no stylesheet dependency.
    Object.assign(el.style, {
        position: 'fixed',
        inset: '0',
        display: 'none',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '22px',
        background: 'rgba(6, 8, 15, 0.94)',
        zIndex: '999999',
        pointerEvents: 'none',
        opacity: '0',
        transition: 'opacity 120ms linear',
    });
    // SVG spinner — same 270° arc + 90° gap as <SpinningLogo>, but
    // inlined so showing the overlay doesn't trigger any React
    // mount or asset fetch.  CSS keyframe drives the rotation.
    el.innerHTML = `
        <span style="display:inline-block;width:88px;height:88px;line-height:0;color:#5DC8FF;animation:vesper-nav-spin 900ms linear infinite;">
            <svg viewBox="0 0 48 48" width="100%" height="100%" fill="none" style="display:block;overflow:visible;filter:drop-shadow(0 0 12px rgba(93,200,255,0.55));" aria-hidden="true">
                <circle cx="24" cy="24" r="18" stroke="currentColor" stroke-opacity="0.18" stroke-width="5"></circle>
                <circle cx="24" cy="24" r="18" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-dasharray="85 28"></circle>
            </svg>
        </span>
        <div style="font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;font-size:13px;letter-spacing:0.34em;text-transform:uppercase;color:rgba(255,255,255,0.72);">
            Loading title<span class="vesper-dots" aria-hidden="true">…</span>
        </div>
        <div aria-hidden="true" style="width:clamp(180px, 18vw, 280px);height:2px;background:linear-gradient(90deg, transparent 0%, rgba(93,200,255,0.85) 50%, transparent 100%);background-size:200% 100%;animation:vesper-splash-sweep 1.6s ease-in-out infinite;border-radius:999px;box-shadow:0 0 12px rgba(93,200,255,0.45);"></div>
    `;
    // Inject the keyframe once — vesper-splash-sweep already exists
    // in index.css but our own `vesper-nav-spin` is a fresh,
    // overlay-local keyframe so we don't depend on app CSS.
    if (!document.getElementById('vesper-nav-loader-style')) {
        const style = document.createElement('style');
        style.id = 'vesper-nav-loader-style';
        style.textContent = `@keyframes vesper-nav-spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }`;
        document.head.appendChild(style);
    }
    document.body.appendChild(el);
    overlayEl = el;
    return el;
}

function installGlobalGuards() {
    if (installed) return;
    installed = true;
    if (typeof window === 'undefined') return;
    // Back button → always hide.  The user has explicitly chosen
    // to leave the destination so leaving the overlay on screen
    // would feel broken.
    window.addEventListener('popstate', () => hideNavLoader());
    // Ditto for hashchange (HashRouter is used on Android).
    window.addEventListener('hashchange', () => hideNavLoader());
}

export function showNavLoader() {
    const el = ensureOverlay();
    if (!el) return;
    installGlobalGuards();
    if (autoHideTimer) {
        clearTimeout(autoHideTimer);
        autoHideTimer = null;
    }
    el.style.display = 'flex';
    // Force a reflow so the opacity transition runs from 0 → 1
    // even when the same element is being re-shown back-to-back.
    void el.offsetWidth;
    el.style.opacity = '1';
    autoHideTimer = setTimeout(() => hideNavLoader(), AUTO_HIDE_MS);
}

export function hideNavLoader() {
    if (autoHideTimer) {
        clearTimeout(autoHideTimer);
        autoHideTimer = null;
    }
    if (!overlayEl) return;
    overlayEl.style.opacity = '0';
    // Wait for the fade-out before flipping display:none so the
    // next show() doesn't see a stale frame.
    setTimeout(() => {
        if (overlayEl && overlayEl.style.opacity === '0') {
            overlayEl.style.display = 'none';
        }
    }, 140);
}
