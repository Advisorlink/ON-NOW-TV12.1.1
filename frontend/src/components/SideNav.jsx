import React, { useState } from 'react';
import {
    Home as HomeIcon,
    Search,
    Library,
    Plug,
    Settings,
    Tv,
    Film,
    Radio,
    Zap,
    Users,
} from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';

const NAV = [
    { id: 'home', label: 'Home', icon: HomeIcon, path: '/' },
    { id: 'tv', label: 'TV Shows', icon: Tv, path: '/?filter=series' },
    { id: 'movies', label: 'Movies', icon: Film, path: '/?filter=movie' },
    { id: 'live-tv', label: 'Live TV', icon: Radio, path: '/live-tv' },
    { id: 'search', label: 'Search', icon: Search, path: '/search' },
    { id: 'library', label: 'My Library', icon: Library, path: '/library' },
    { id: 'watch-together', label: 'Watch Together', icon: Users, path: '/watch-together' },
    // v2.6.78: removed the user-facing "Sources" entry — addon
    // configuration is now an internal-only flow.  Power-users can
    // still reach /sources directly via URL bar but it no longer
    // clutters the main nav for end-users.
    { id: 'settings', label: 'Settings', icon: Settings, path: '/settings' },
];

export default function SideNav() {
    const [expanded, setExpanded] = useState(false);
    // When the user clicks a nav item we briefly force the rail
    // to collapsed regardless of focus state.  Without this the
    // SideNav lingers expanded on slow TV boxes for the 80–300 ms
    // between the click and the focus actually moving into the
    // new page — making tab swaps feel sluggish.  Cleared once
    // the new route mounts (location pathname change).
    const [navigatingAway, setNavigatingAway] = useState(false);
    const [autoplay, setAutoplay] = useState(getAutoplay1080p());
    const location = useLocation();
    const navigate = useNavigate();
    const currentFilter = new URLSearchParams(location.search).get('filter');
    const activePath = location.pathname;

    // 300 ms dwell timer — the rail only expands after the focus
    // has been on a nav button for a moment.  This prevents
    // accidental "I tapped LEFT at the leftmost tile and the menu
    // popped open" surprises — a quick LEFT-then-RIGHT round trip
    // never actually surfaces the rail.
    const dwellTimer = React.useRef(null);
    const clearDwell = () => {
        if (dwellTimer.current) {
            clearTimeout(dwellTimer.current);
            dwellTimer.current = null;
        }
    };
    React.useEffect(() => () => clearDwell(), []);

    // Reset the force-collapse flag whenever the route actually
    // changes — by then Home's focus-retry effect has begun to
    // move focus into the grid so natural onBlur collapse takes
    // over.
    React.useEffect(() => {
        if (!navigatingAway) return undefined;
        const t = setTimeout(() => setNavigatingAway(false), 350);
        return () => clearTimeout(t);
    }, [location.pathname, location.search, navigatingAway]);

    const handleNavClick = (path) => {
        clearDwell();
        // v2.8.88 — Compare full URL (pathname + search), not just
        // pathname.  Movies (`/?filter=movie`), TV Shows
        // (`/?filter=series`) and Home (`/`) all share pathname `/`,
        // so the old equality check made the in-app Home menu item
        // a no-op when the user was already in Movies or TV Shows.
        // Now Home properly takes them back to the For You feed.
        const currentFull = location.pathname + (location.search || '');
        const samePath = currentFull === path
            || (location.pathname === path && !location.search);
        if (samePath) {
            /* Same-page click — don't collapse, just let focus
             * settle.  Avoids the flicker the user sees when they
             * land back on the page they're already on. */
            return;
        }
        setExpanded(false);
        setNavigatingAway(true);
        // Drop focus off the SideNav button so onFocus doesn't
        // immediately re-expand the rail on the next paint.
        if (
            document.activeElement &&
            typeof document.activeElement.blur === 'function'
        ) {
            document.activeElement.blur();
        }
        navigate(path);
    };

    const toggleAutoplay = () => {
        const next = !autoplay;
        setAutoplay1080p(next);
        setAutoplay(next);
    };

    const isExpanded = expanded && !navigatingAway;

    return (
        <nav
            data-testid="side-nav"
            onFocus={(e) => {
                // Don't fire when the rail itself or an outer wrapper
                // bubbled — only when focus genuinely moves into a
                // focusable child.
                if (!e.target.matches('[data-focusable="true"]')) return;
                if (navigatingAway) return;
                // 300 ms dwell — quick LEFT-RIGHT round trips never
                // actually expand the rail.
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
            className="fixed left-0 top-0 bottom-0 z-40 flex flex-col py-7 transition-[width,background] duration-300"
            style={{
                width: isExpanded ? '240px' : '76px',
                background: isExpanded
                    // Solid layered fade instead of backdrop-filter blur —
                    // Chrome 52 on HK1 doesn't accelerate `backdrop-filter`
                    // and it causes visible compositor jank on D-pad scroll.
                    ? 'linear-gradient(90deg, rgba(8,11,20,0.98) 0%, rgba(8,11,20,0.95) 60%, rgba(8,11,20,0.0) 100%)'
                    : 'transparent',
            }}
        >
            {/* Brand mark — single "ON NOW TV2" wordmark where the
                V and the 2 glow in the active theme accent.  The
                white "ON NOW T" prefix only renders when the rail
                is expanded; when collapsed only the glowing "V2"
                emblem is visible. */}
            <div className="flex items-baseline pl-3 pr-3 mb-10 select-none" style={{ height: 56 }}>
                <div
                    className="vesper-display whitespace-nowrap overflow-hidden"
                    style={{
                        flex: '0 1 auto',
                        marginRight: isExpanded ? 2 : 0,
                        opacity: isExpanded ? 1 : 0,
                        maxWidth: isExpanded ? 200 : 0,
                        // Snap-in after the rail finishes expanding so
                        // the prefix doesn't crowd the V2 emblem mid-
                        // animation.
                        transition:
                            'opacity 200ms ease 80ms, max-width 240ms ease, margin-right 240ms ease',
                        fontSize: 22,
                        lineHeight: 1,
                        letterSpacing: '-0.025em',
                        fontWeight: 700,
                        color: 'var(--vesper-text)',
                    }}
                >
                    ON NOW&nbsp;T
                </div>
                <div
                    className="vesper-display shrink-0"
                    style={{
                        // The glowing V2 emblem.  Same letterform on
                        // both collapsed and expanded — sits as the
                        // trailing pair of "ON NOW TV2" when expanded,
                        // and as the standalone icon when collapsed.
                        fontSize: 32,
                        lineHeight: 1,
                        fontWeight: 800,
                        letterSpacing: '-0.04em',
                        color: 'var(--vesper-blue-bright)',
                        textShadow:
                            '0 0 12px rgba(var(--vesper-blue-rgb), 0.65), 0 0 28px rgba(var(--vesper-blue-rgb), 0.4)',
                    }}
                >
                    V2
                </div>
            </div>

            {/* Items */}
            <div className="flex flex-col gap-1 px-3">
                {NAV.map((item) => {
                    const Icon = item.icon;
                    // For the home / TV Shows / Movies items we need
                    // to disambiguate by `?filter=…` query because
                    // they all share the `/` pathname.
                    const itemFilter = (() => {
                        const i = item.path.indexOf('?');
                        if (i < 0) return null;
                        return new URLSearchParams(item.path.slice(i + 1)).get(
                            'filter'
                        );
                    })();
                    const itemPathname = item.path.split('?')[0];
                    let isActive = false;
                    if (itemPathname === '/') {
                        isActive =
                            activePath === '/' && currentFilter === itemFilter;
                    } else {
                        isActive =
                            activePath === itemPathname ||
                            activePath.startsWith(itemPathname);
                    }
                    return (
                        <button
                            key={item.id}
                            data-testid={`nav-${item.id}`}
                            data-focusable="true"
                            data-focus-style="nav"
                            tabIndex={0}
                            onClick={() => handleNavClick(item.path)}
                            className={`relative flex items-center gap-4 h-11 px-2 rounded-lg text-left ${
                                isActive
                                    ? 'text-vesper-text'
                                    : 'text-vesper-text2'
                            }`}
                        >
                            <span className="flex items-center justify-center w-9 h-9 shrink-0">
                                <Icon
                                    size={20}
                                    strokeWidth={1.7}
                                    style={{
                                        color: isActive
                                            ? 'var(--vesper-blue)'
                                            : 'currentColor',
                                    }}
                                />
                            </span>
                            <span
                                className="font-sans text-[15px] font-medium overflow-hidden whitespace-nowrap transition-opacity duration-300"
                                style={{ opacity: isExpanded ? 1 : 0 }}
                            >
                                {item.label}
                            </span>
                        </button>
                    );
                })}

                {/* Autoplay toggle — sits right after Settings.
                    Same visual treatment as nav items, but tapping
                    flips the localStorage flag instead of routing. */}
                <button
                    data-testid="nav-autoplay"
                    data-focusable="true"
                    data-focus-style="nav"
                    tabIndex={0}
                    onClick={toggleAutoplay}
                    className="relative flex items-center gap-4 h-11 px-2 rounded-lg text-left"
                    style={{
                        color: autoplay
                            ? 'var(--vesper-blue-bright)'
                            : 'var(--vesper-text-2)',
                    }}
                >
                    <span className="flex items-center justify-center w-9 h-9 shrink-0">
                        <Zap
                            size={20}
                            strokeWidth={autoplay ? 2.2 : 1.7}
                            fill={autoplay ? 'currentColor' : 'none'}
                            style={{
                                color: autoplay
                                    ? 'var(--vesper-blue)'
                                    : 'currentColor',
                            }}
                        />
                    </span>
                    <span
                        className="font-sans text-[15px] font-medium overflow-hidden whitespace-nowrap transition-opacity duration-300 flex items-center gap-2"
                        style={{ opacity: isExpanded ? 1 : 0 }}
                    >
                        Autoplay
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 10,
                                letterSpacing: '0.18em',
                                padding: '2px 7px',
                                borderRadius: 999,
                                background: autoplay
                                    ? 'rgba(var(--vesper-blue-rgb),0.18)'
                                    : 'rgba(255,255,255,0.08)',
                                color: autoplay
                                    ? 'var(--vesper-blue-bright)'
                                    : 'var(--vesper-text-3)',
                                border: autoplay
                                    ? '1px solid rgba(var(--vesper-blue-rgb),0.45)'
                                    : '1px solid rgba(255,255,255,0.12)',
                            }}
                        >
                            {autoplay ? 'ON' : 'OFF'}
                        </span>
                    </span>
                </button>
            </div>
        </nav>
    );
}
