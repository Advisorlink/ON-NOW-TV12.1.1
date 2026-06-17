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
    User,
    Settings as SettingsIcon,
    Music2,
} from 'lucide-react';
import { MiniPlayer } from '../../components/music/MiniPlayer';
import { ResolverDebug } from '../../components/music/ResolverDebug';
import MusicWelcome from '../../components/music/MusicWelcome';
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
                clearDwell();
                dwellTimer.current = setTimeout(() => {
                    setExpanded(true);
                    dwellTimer.current = null;
                }, 300);
            }}
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget)) {
                    clearDwell();
                    setExpanded(false);
                }
            }}
            onMouseEnter={() => {
                clearDwell();
                dwellTimer.current = setTimeout(() => setExpanded(true), 220);
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
            </div>

            <div className="tunes-nav__spacer" />

            {/* Profile / settings affordances at the bottom of the rail. */}
            <div className="tunes-nav__items">
                <NavLink
                    to="/music/library"
                    className="tunes-nav__item"
                    data-testid="tunes-nav-profile"
                    data-focusable="true"
                    data-focus-style="nav"
                    tabIndex={0}
                >
                    <span className="tunes-nav__item-icon">
                        <User size={22} strokeWidth={1.7} />
                    </span>
                    <span className="tunes-nav__item-label">Profile</span>
                </NavLink>
                <NavLink
                    to="/music/library"
                    className="tunes-nav__item"
                    data-testid="tunes-nav-settings"
                    data-focusable="true"
                    data-focus-style="nav"
                    tabIndex={0}
                >
                    <span className="tunes-nav__item-icon">
                        <SettingsIcon size={22} strokeWidth={1.7} />
                    </span>
                    <span className="tunes-nav__item-label">Settings</span>
                </NavLink>
            </div>

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
            {/* v2.8.85 — KaraokeMicReceiver is now mounted ONLY on
                KaraokeStage so we don't accidentally open multiple
                peer connections.  See /pages/music/KaraokeStage.jsx. */}
            {/* YouTubeIFrameHost lifted to App.js — global mount. */}
        </div>
    );
}
