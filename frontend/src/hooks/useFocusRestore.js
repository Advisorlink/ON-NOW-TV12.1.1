/**
 * v2.10.83 — Cross-page focus + scroll restoration for the
 * TV-remote D-pad UX.
 *
 * The bug this fixes (operator report):
 *   "I'm on the Home For-You rail.  I D-pad over to 'Office Romance',
 *    click it, the detail page opens, I press BACK — but instead of
 *    landing back on the Office Romance tile I'm dumped at the
 *    top-left of the grid.  Every Back press becomes a 12-key trek
 *    back to where I was."
 *
 * Architecture — kept zero-touch so we don't have to wire every
 * tile component manually (PosterTile, NetworkPosterTile, CastRow,
 * UpcomingMoviesShelf, ContinueWatchingShelf, RecommendationsRow,
 * HeroBillboard, TabGridView, KidsTabGridView).  Instead:
 *
 *   1. `installFocusBookmarkListener()` is called ONCE at app boot
 *      (App.js).  It attaches a CAPTURE-phase listener for `click`
 *      and `keydown` (Enter / Space) on `document`.  Whenever the
 *      user fires the activation gesture on a `[data-focusable]`
 *      element that is also inside a scrollable container, we stash
 *      `{ testId, scrollTops, scrollLefts, path }` to sessionStorage
 *      keyed by the CURRENT pathname.
 *
 *   2. `useFocusRestore(routeKey)` is called from any page that
 *      hosts a tile grid (Home, Network, Library, Catalog, Person).
 *      On mount + on every dataReady signal it reads the matching
 *      sessionStorage key, restores scroll, finds the testId via
 *      querySelector, scrolls it into view CENTER, then `.focus()`-es
 *      it so the spatial-focus highlight reappears.
 *
 *   3. Restoration retries for up to `RESTORE_WINDOW_MS` (2 s) so it
 *      works even when the data fetch is slow — every animation
 *      frame the hook re-checks if the target tile has materialised
 *      and grabs focus the moment it has.
 *
 *   4. Successful restore CLEARS the bookmark.  Without this, the
 *      next push of a NEW route would fight a stale bookmark.
 *
 *  Storage key: `vesper:focus:<pathname>` — e.g. `vesper:focus:/`,
 *  `vesper:focus:/networks/binge`, `vesper:focus:/library`.
 */

import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';

const STORAGE_PREFIX = 'vesper:focus:';
const RESTORE_WINDOW_MS = 2200;
const RESTORE_TICK_MS = 80;

/* ─────────────────────────  Internal helpers  ─────────────────── */

const storageKey = (path) => `${STORAGE_PREFIX}${path || '/'}`;

const safeReadJson = (key) => {
    try {
        const raw = sessionStorage.getItem(key);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
};

const safeWriteJson = (key, value) => {
    try {
        sessionStorage.setItem(key, JSON.stringify(value));
    } catch {
        /* sessionStorage quota / SecurityError — silently skip */
    }
};

const safeRemove = (key) => {
    try { sessionStorage.removeItem(key); } catch { /* */ }
};

/** Walk up from [el] capturing scrollTops/scrollLefts of every
 *  ancestor that has overflow scrolling.  Used so we can restore
 *  exact scroll positions (not just window scroll) — important for
 *  horizontal shelves where the bookmarked tile may be 8 tiles to
 *  the right of column 0. */
const captureScrollChain = (el) => {
    const chain = [];
    let n = el;
    while (n && n !== document.documentElement) {
        if (
            n.scrollHeight > n.clientHeight ||
            n.scrollWidth > n.clientWidth
        ) {
            const sel = scrollableSelector(n);
            if (sel) {
                chain.push({
                    sel,
                    top: n.scrollTop,
                    left: n.scrollLeft,
                });
            }
        }
        n = n.parentElement;
    }
    return {
        chain,
        windowTop: window.scrollY || 0,
        windowLeft: window.scrollX || 0,
    };
};

/** Generate a stable selector for a scrollable element.  We prefer
 *  data-testid > id > a class chain.  Returns null if we can't
 *  derive anything reliable — those scroll positions are skipped. */
const scrollableSelector = (el) => {
    const testId = el.getAttribute('data-testid');
    if (testId) return `[data-testid="${cssEscape(testId)}"]`;
    if (el.id) return `#${cssEscape(el.id)}`;
    const shelf = el.getAttribute('data-shelf-rail');
    if (shelf) return `[data-shelf-rail="${cssEscape(shelf)}"]`;
    return null;
};

/** Browser CSS.escape polyfill — sufficient for our id/testId
 *  values which are kebab-case alphanumeric. */
const cssEscape = (s) =>
    typeof CSS !== 'undefined' && CSS.escape
        ? CSS.escape(s)
        : String(s).replace(/(["\\])/g, '\\$1');

const applyScrollChain = (snapshot) => {
    if (!snapshot) return;
    if (typeof snapshot.windowTop === 'number') {
        window.scrollTo(snapshot.windowLeft || 0, snapshot.windowTop || 0);
    }
    if (Array.isArray(snapshot.chain)) {
        for (const item of snapshot.chain) {
            try {
                const el = document.querySelector(item.sel);
                if (el) {
                    el.scrollTop = item.top || 0;
                    el.scrollLeft = item.left || 0;
                }
            } catch {
                /* malformed selector — skip */
            }
        }
    }
};

/* ─────────────  Global listener — install ONCE in App.js  ─────── */

let _installed = false;

export function installFocusBookmarkListener() {
    if (_installed) return;
    if (typeof document === 'undefined') return;
    _installed = true;

    const onActivate = (e) => {
        /* Only act on real activation gestures.  Keydown handler
         * gates further on Enter/Space below. */
        if (e.type === 'keydown' && !(e.key === 'Enter' || e.key === ' ')) {
            return;
        }
        const target = e.target;
        if (!target || !target.closest) return;
        const tile = target.closest('[data-focusable="true"]');
        if (!tile) return;
        const testId = tile.getAttribute('data-testid');
        if (!testId) return;
        const path = window.location.pathname || '/';
        const snapshot = {
            testId,
            scroll: captureScrollChain(tile),
            ts: Date.now(),
        };
        safeWriteJson(storageKey(path), snapshot);
    };

    /* Capture phase + true so the bookmark is written BEFORE React's
     * onClick fires `navigate(...)` which would unmount the page. */
    document.addEventListener('click', onActivate, true);
    document.addEventListener('keydown', onActivate, true);
}

/* ─────────────────────  Per-page restoration hook  ────────────── */

/**
 * @param {{ ready?: boolean }} opts
 *
 *   `ready` — flips to true once the page's primary data fetch has
 *   resolved AND the tiles have rendered.  The hook will keep
 *   retrying for ~2 s after `ready` becomes true; many pages can
 *   simply pass `true` immediately and rely on the rAF retry loop
 *   to find the tile once it materialises.
 */
export default function useFocusRestore({ ready = true } = {}) {
    const { pathname } = useLocation();
    const restoredRef = useRef(false);
    const deadlineRef = useRef(0);

    useEffect(() => {
        restoredRef.current = false;
        deadlineRef.current = 0;
    }, [pathname]);

    useEffect(() => {
        if (!ready) return undefined;
        if (restoredRef.current) return undefined;

        const key = storageKey(pathname);
        const snap = safeReadJson(key);
        if (!snap || !snap.testId) return undefined;

        /* If the bookmark is hours old (browser left a tab open), skip
         * — restoring focus to a tile that's no longer relevant is
         * disorienting. */
        if (snap.ts && Date.now() - snap.ts > 60 * 60 * 1000) {
            safeRemove(key);
            return undefined;
        }

        deadlineRef.current = Date.now() + RESTORE_WINDOW_MS;
        let raf = 0;
        let stopped = false;

        const tryRestore = () => {
            if (stopped) return;
            if (restoredRef.current) return;
            if (Date.now() > deadlineRef.current) {
                /* Gave up — the tile never showed.  Leave the
                 * bookmark in place so a later remount can still
                 * pick it up (e.g. user reloaded mid-fetch). */
                return;
            }
            const sel = `[data-testid="${cssEscape(snap.testId)}"]`;
            const el = document.querySelector(sel);
            if (el) {
                /* Restore scroll FIRST so the bookmarked tile is
                 * actually painted on screen by the time .focus()
                 * triggers the spatial highlight. */
                applyScrollChain(snap.scroll);
                /* One more frame for layout to settle. */
                requestAnimationFrame(() => {
                    if (stopped) return;
                    try {
                        el.scrollIntoView({
                            behavior: 'auto',
                            block: 'center',
                            inline: 'center',
                        });
                    } catch { /* old browsers */ }
                    try { el.focus({ preventScroll: false }); } catch { /* */ }
                    restoredRef.current = true;
                    safeRemove(key);
                });
                return;
            }
            raf = setTimeout(tryRestore, RESTORE_TICK_MS);
        };

        /* Wait one frame after the dataReady flip so React commits
         * the tile DOM before our first selector lookup. */
        raf = setTimeout(tryRestore, RESTORE_TICK_MS);

        return () => {
            stopped = true;
            if (raf) clearTimeout(raf);
        };
    }, [pathname, ready]);
}

/* ─────────────  Public escape hatch — manual bookmark write  ─── *
 *
 * Used by code paths that navigate WITHOUT a click event (e.g. a
 * keyboard shortcut handler that programmatically calls
 * `navigate(...)`).  Most callers don't need this — the global
 * click+keydown listener catches D-pad activation by default.
 */
export function bookmarkCurrentFocus() {
    try {
        const el = document.activeElement;
        if (!el || !el.getAttribute) return;
        const testId = el.getAttribute('data-testid');
        if (!testId) return;
        const path = window.location.pathname || '/';
        safeWriteJson(storageKey(path), {
            testId,
            scroll: captureScrollChain(el),
            ts: Date.now(),
        });
    } catch { /* */ }
}

/* ─────────────  Global mount — runs on EVERY route  ──────────── *
 *
 * v2.10.85 — Drop a single instance of <GlobalFocusRestore /> below
 * <Routes> in App.js and you automatically get scroll/focus
 * restoration on every page that has data-focusable tiles — no
 * per-page wiring needed.  The rAF retry window inside
 * useFocusRestore handles pages whose data loads async; pages whose
 * tiles never appear simply leave the bookmark untouched until the
 * staleness gate purges it (1 h default).
 *
 * Internally just `useFocusRestore({ ready: true })`, which is
 * keyed on `useLocation().pathname` so it re-arms on every
 * navigation.  Replaces (and is functionally a superset of) the
 * per-page calls on Home / Network / Library / Person / Search.
 */
export function GlobalFocusRestore() {
    useFocusRestore({ ready: true });
    return null;
}
