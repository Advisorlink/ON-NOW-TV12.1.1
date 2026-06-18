// ON NOW TV TUNES — Vesper-style music app shell
// =============================================================
// Mirrors Vesper's SideNav UX exactly:
//   - Collapsed 76 px rail, expands to 248 px on focus dwell
//   - Glowing cyan "V2" emblem when collapsed
//   - Smooth icon → label reveal when expanded
import React, { useEffect, useRef, useState } from 'react';
import { Outlet, NavLink, useLocation, useNavigate } from 'react-router-dom';
import {
    Home as HomeIcon,
    Search,
    Compass,
    Radio,
    Library,
    Mic2,
    Mic,
    Music2,
    Maximize2,
} from 'lucide-react';
import { MiniPlayer } from '../../components/music/MiniPlayer';
import { ResolverDebug } from '../../components/music/ResolverDebug';
import MusicWelcome from '../../components/music/MusicWelcome';
import MusicAddToLibraryModal from '../../components/music/MusicAddToLibraryModal';
import useSpatialFocus from '../../hooks/useSpatialFocus';
import './tunes.css';
import './karaoke.css';
import './karaoke-party.css';
import './karaoke-design-gallery.css';

const NAV_ITEMS = [
    { to: '/music',          label: 'Home',       icon: HomeIcon, end: true,  id: 'home' },
    { to: '/music/search',   label: 'Search',     icon: Search,                 id: 'search' },
    { to: '/music/karaoke',  label: 'Karaoke',    icon: Mic2,                   id: 'karaoke' },
    { to: '/music/radio',    label: 'Radio',      icon: Radio,                  id: 'radio' },
    { to: '/music/radio/au', label: 'Australia',  icon: Compass,                id: 'australia' },
    { to: '/music/podcasts', label: 'Podcasts',   icon: Mic,                    id: 'podcasts' },
    { to: '/music/library',  label: 'Library',    icon: Library,                id: 'library' },
];

const THEME_STORAGE_KEY = 'onnowtv-tunes-theme';

function readStoredTheme() {
    if (typeof window === 'undefined') return 'pink';
    try {
        const v = window.localStorage.getItem(THEME_STORAGE_KEY);
        return v === 'electric-blue' ? 'electric-blue' : 'pink';
    } catch { return 'pink'; }
}

function TunesNav({ theme, onThemeChange }) {
    const [expanded, setExpanded] = useState(false);
    const dwellTimer = useRef(null);
    const location = useLocation();
    const navigate = useNavigate();

    const clearDwell = () => {
        if (dwellTimer.current) {
            clearTimeout(dwellTimer.current);
            dwellTimer.current = null;
        }
    };

    useEffect(() => () => clearDwell(), []);

    const handleNavClick = (path) => {
        clearDwell();
        setExpanded(false);
        if (document.activeElement?.blur) document.activeElement.blur();
        navigate(path);
    };

    return (
        <nav
            className="tunes-nav"
            data-testid="side-nav"
            data-tunes-nav="true"
            data-expanded={expanded}
            /* v2.10.35 — Opt this whole side-rail out of the global
               spatial-focus row-pin logic.  Bug: when the rail's
               own scroll container wasn't actually overflowing (the
               common case on a 1080p+ TV), `verticalScroller(el)`
               walked up the DOM, skipped the nav, and resolved to
               `.tunes-root` — so pressing Down on a rail item
               scrolled the MAIN content area to keep the rail item
               at ~22% of viewport height.  The fix the hook already
               supports: ANY element with `data-no-row-snap="true"`
               in its ancestor chain bypasses the row-pin scroll
               math entirely.  Focus still moves correctly between
               rail items; nothing in the main pane moves. */
            data-no-row-snap="true"
            onFocus={(e) => {
                if (!e.target.matches('[data-focusable="true"]')) return;
                // v2.10.40 — INSTANT expand on focus (no dwell delay).
                // User said the rail felt chunky/laggy.  220ms+ delays
                // were the culprit.  Pop out the moment focus lands.
                clearDwell();
                setExpanded(true);
            }}
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) {
                    clearDwell();
                    setExpanded(false);
                }
            }}
            onMouseEnter={() => {
                // v2.10.40 — INSTANT expand on hover too.
                clearDwell();
                setExpanded(true);
            }}
            onMouseLeave={() => {
                clearDwell();
                setExpanded(false);
            }}
        >
            {/* Brand — music-note emblem + ON NOW TV wordmark on expand.
                v2.8.68 — Was "V2" (Vesper-style) — replaced with a
                pink/blue ♪ emblem so the standalone music app looks
                like its OWN product, not a Vesper variant. */}
            <div className="tunes-nav__brand">
                <div className="tunes-nav__brand-emblem" data-testid="tunes-nav-brand">
                    ♪
                </div>
                <div className="tunes-nav__brand-wordmark">
                    ON&nbsp;NOW&nbsp;TV
                    <span>Tunes</span>
                </div>
            </div>

            <div className="tunes-nav__items">
                {NAV_ITEMS.map(({ to, label, icon: Icon, end, id }) => {
                    const isActive = end
                        ? location.pathname === to
                        : location.pathname.startsWith(to);
                    return (
                        <NavLink
                            key={to}
                            to={to}
                            end={end}
                            onClick={(e) => { e.preventDefault(); handleNavClick(to); }}
                            className={
                                'tunes-nav__item' +
                                (isActive ? ' tunes-nav__item--active' : '')
                            }
                            data-testid={`tunes-nav-${id}`}
                            data-focusable="true"
                            data-focus-style="nav"
                            tabIndex={0}
                        >
                            <span className="tunes-nav__item-icon">
                                <Icon size={22} strokeWidth={1.7} />
                            </span>
                            <span className="tunes-nav__item-label">{label}</span>
                        </NavLink>
                    );
                })}
                {/* v2.10.39 — Full Screen affordance.  Sits directly
                    UNDER the Library entry inside the main nav block
                    (per user request "put full screen button under
                    librarie").  Dispatches the same `tunes:open-fullscreen`
                    event MiniPlayer listens for, so it works on every
                    Music sub-page regardless of whether the mini
                    player has been expanded before. */}
                <button
                    type="button"
                    className="tunes-nav__item"
                    data-testid="tunes-nav-fullscreen"
                    data-focusable="true"
                    data-focus-style="nav"
                    tabIndex={0}
                    onClick={() => {
                        try {
                            window.dispatchEvent(new CustomEvent('tunes:open-fullscreen'));
                        } catch { /* ignore */ }
                    }}
                >
                    <span className="tunes-nav__item-icon">
                        <Maximize2 size={22} strokeWidth={1.7} />
                    </span>
                    <span className="tunes-nav__item-label">Full Screen</span>
                </button>
            </div>

            <div className="tunes-nav__spacer" />

            {/* v2.10.46 — Profile + Settings rail items removed per
                user request.  Their per-pixel weight on the rail
                isn't worth the cognitive load on TV-remote users
                who'd rather see one less row to scroll past. */}

            {/* Theme picker — only visible when expanded. */}
            <div className="tunes-nav__theme" data-testid="tunes-theme-toggle">
                <div className="tunes-nav__theme-label">Accent</div>
                <div className="tunes-nav__theme-row">
                    <button
                        type="button"
                        className="tunes-nav__theme-btn"
                        data-active={theme === 'electric-blue'}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => onThemeChange('electric-blue')}
                        data-testid="theme-btn-electric-blue"
                    >
                        Electric Blue
                    </button>
                    <button
                        type="button"
                        className="tunes-nav__theme-btn"
                        data-active={theme === 'pink'}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => onThemeChange('pink')}
                        data-testid="theme-btn-pink"
                    >
                        Pink
                    </button>
                </div>
            </div>

            {/* v2.10.41 — Visible build-version badge.  Sits at the bottom
                of the expanded rail so the user can verify at a glance
                that their sideloaded APK actually picked up the latest
                React bundle.  Pulls from REACT_APP_VESPER_BUILD_VERSION
                which the GitHub Actions workflow bakes in at build time
                (CHANGELOG.md top heading + workflow run number).  Falls
                back to "dev" when running locally. */}
            <div className="tunes-nav__build" data-testid="tunes-build-version">
                <span className="tunes-nav__build-label">Build</span>
                <span className="tunes-nav__build-value">
                    {process.env.REACT_APP_VESPER_BUILD_VERSION || 'dev'}
                </span>
            </div>
        </nav>
    );
}

export default function MusicLayout() {
    useSpatialFocus();

    const location = useLocation();
    const rootRef = useRef(null);

    const [theme, setTheme] = useState(() => readStoredTheme());
    const changeTheme = (next) => {
        if (next !== 'pink' && next !== 'electric-blue') return;
        setTheme(next);
        try { window.localStorage.setItem(THEME_STORAGE_KEY, next); } catch { /* ignore */ }
    };

    // v2.8.68 — Tag <body> while the music app is mounted so the
    // global Vesper mobile rules (padding-bottom: 58px reserved for
    // Vesper's MobileBottomNav, hover styles, etc.) can be overridden
    // for the standalone music app.  CSS targets via
    // `body[data-music-app="true"]`.
    useEffect(() => {
        if (typeof document === 'undefined') return undefined;
        document.body.setAttribute('data-music-app', 'true');
        return () => document.body.removeAttribute('data-music-app');
    }, []);

    // v2.10.37 — RESET SCROLL on every route change inside /music.
    // The canonical scroll container is `.tunes-root` (height:
    // 100dvh; overflow-y: auto) — NOT `.tunes-main`, which is just
    // a flex child with no overflow.  Snap to (0,0) so the page
    // header is on-screen the moment the user lands.  Also reset
    // window scroll defensively in case any ancestor is scrolling.
    useEffect(() => {
        try {
            rootRef.current?.scrollTo({ top: 0, left: 0, behavior: 'auto' });
            window.scrollTo({ top: 0, left: 0, behavior: 'auto' });
        } catch { /* private mode / no scrollTo support — ignore */ }
    }, [location.pathname]);

    // v2.10.43 — React-driven BACK key handler for the Tunes APK.
    //
    // The native `MainActivity.onBackPressed()` evaluates
    // `window.__onnowtv_handleBack()` BEFORE doing its own
    // `webView.goBack()`.  React returns "1" if it consumed the
    // BACK (closed an overlay / collapsed the player), or "0" to
    // let native fall back to history-back / app-exit.
    //
    // We check overlays in priority order:
    //   1. FullScreenPlayer expanded?  (Maximize2-icon rail item / mini-player open
    //       dispatches `tunes:open-fullscreen`; the close event is
    //       `tunes:close-fullscreen`.)
    //   2. Welcome popup visible?  (Renders `data-testid="music-welcome"`
    //       in the DOM only while open.)
    //
    // We deliberately do NOT route-pop here.  React Router's normal
    // browser-history `goBack` is handled by native via
    // `webView.goBack()` AFTER React returns "0".
    useEffect(() => {
        if (typeof window === 'undefined') return undefined;
        window.__onnowtv_handleBack = () => {
            // 1. Add-to-library modal open?  Closes via its own
            //    Cancel button (which clicks the X).  Highest
            //    priority — overlays render above FullScreen.
            const addModalCancel = document.querySelector(
                '[data-testid="tunes-add-modal-cancel-x"]',
            );
            if (addModalCancel) {
                addModalCancel.click();
                return true;
            }
            // 2. FullScreenPlayer overlay open?  Its root has
            //    `data-testid="tunes-fullplayer"`.
            const fsRoot = document.querySelector('[data-testid="tunes-fullplayer"]');
            if (fsRoot) {
                window.dispatchEvent(new CustomEvent('tunes:close-fullscreen'));
                return true;
            }
            // 3. Welcome popup open?  Click its Continue button —
            //    that runs the dismiss handler which sets the
            //    localStorage flag AND kicks off the YouTube
            //    sign-in via the native bridge.  Equivalent to
            //    the user pressing OK with their remote's center
            //    key, just routed through BACK.
            const welcomeContinue = document.querySelector(
                '[data-testid="music-welcome-continue"]',
            );
            if (welcomeContinue) {
                welcomeContinue.click();
                return true;
            }
            return false;
        };
        return () => {
            try { delete window.__onnowtv_handleBack; }
            catch { window.__onnowtv_handleBack = undefined; }
        };
    }, []);

    // No extra focusin scroll handler.  Vesper's useSpatialFocus()
    // already handles edge-comfort horizontal scroll inside rails
    // and row-pin vertical scroll between shelves with hardware-
    // accelerated `behavior: 'auto'` calls — adding our own
    // `scrollIntoView({behavior: 'smooth'})` here was racing
    // Vesper's logic and producing the chunky up/down feel.

    return (
        <div
            ref={rootRef}
            className="tunes-root"
            data-theme={theme}
            data-testid="music-layout"
        >
            <div className="tunes-shell">
                <TunesNav theme={theme} onThemeChange={changeTheme} />
                <main className="tunes-main">
                    <Outlet />
                </main>
            </div>
            <MiniPlayer />
            <ResolverDebug />
            {/* v2.10.35 — First-launch welcome.  Explains the YouTube
                integration up front so the Google sign-in that
                follows isn't a surprise.  Self-gates on a
                localStorage flag → only renders on first visit. */}
            <MusicWelcome />
            {/* v2.10.47 — Long-press → "Add to library" modal.
                Listens for `tunes:request-add-to-library` events
                from any tile inside the music shell. */}
            <MusicAddToLibraryModal />
            {/* v2.8.85 — KaraokeMicReceiver is now mounted ONLY on
                KaraokeStage so we don't accidentally open multiple
                peer connections.  See /pages/music/KaraokeStage.jsx. */}
            {/* YouTubeIFrameHost lifted to App.js — global mount. */}
        </div>
    );
}
