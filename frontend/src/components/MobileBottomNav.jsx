/**
 * <MobileBottomNav/> — mobile-mode bottom tab bar that replaces the
 * desktop / TV <SideNav/>.  Sticky, 5 primary destinations:
 *   Home · Sports · Live · Library · Settings
 *
 * Designed to feel like a native iOS / Android tab bar: tap targets
 * ≥ 44 px, monochrome glyphs with a coloured active state, no hover.
 *
 * Pages that need a back-arrow (Detail, Player, Watch Together,
 * Sources) hide this nav by simply not rendering it; the global
 * `<SideNavBar/>` wrapper in App.js checks the route.
 */

import React from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Home as HomeIcon, Trophy, Tv, BookOpen, Settings } from 'lucide-react';

const TABS = [
    { path: '/',         label: 'Home',     Icon: HomeIcon },
    { path: '/sports',   label: 'Sports',   Icon: Trophy },
    { path: '/live-tv',  label: 'Live',     Icon: Tv },
    { path: '/library',  label: 'Library',  Icon: BookOpen },
    { path: '/settings', label: 'Settings', Icon: Settings },
];

function activeFor(pathname) {
    if (pathname === '/' || pathname.startsWith('/home')) return '/';
    for (const t of TABS) {
        if (t.path !== '/' && pathname.startsWith(t.path)) return t.path;
    }
    return null;
}

export default function MobileBottomNav() {
    const { pathname } = useLocation();
    const navigate = useNavigate();
    const active = activeFor(pathname);

    return (
        <nav
            data-testid="mobile-bottom-nav"
            style={{
                position: 'fixed',
                left: 0, right: 0, bottom: 0,
                height: 'calc(58px + env(safe-area-inset-bottom, 0px))',
                paddingBottom: 'env(safe-area-inset-bottom, 0px)',
                display: 'flex',
                background: 'rgba(6,8,15,0.96)',
                borderTop: '1px solid rgba(255,255,255,0.06)',
                zIndex: 60,
            }}
        >
            {TABS.map(({ path, label, Icon }) => {
                const isActive = active === path;
                return (
                    <button
                        key={path}
                        data-testid={`mobile-nav-${label.toLowerCase()}`}
                        onClick={() => navigate(path)}
                        style={{
                            flex: 1,
                            display: 'flex', flexDirection: 'column',
                            alignItems: 'center', justifyContent: 'center',
                            gap: 3,
                            background: 'transparent',
                            border: 'none',
                            padding: '8px 4px',
                            color: isActive ? '#5DC8FF' : '#7d8493',
                            fontSize: 10, fontWeight: 700,
                            letterSpacing: '0.04em',
                            cursor: 'pointer',
                            outline: 'none',
                            WebkitTapHighlightColor: 'transparent',
                            position: 'relative',
                        }}
                    >
                        <Icon
                            size={22}
                            strokeWidth={isActive ? 2.4 : 2}
                            color={isActive ? '#5DC8FF' : '#7d8493'}
                        />
                        <span>{label}</span>
                        {isActive && (
                            <span
                                aria-hidden="true"
                                style={{
                                    position: 'absolute',
                                    top: 4, height: 3,
                                    width: 24, borderRadius: 999,
                                    background: '#5DC8FF',
                                    boxShadow: '0 0 10px rgba(93,200,255,0.55)',
                                }}
                            />
                        )}
                    </button>
                );
            })}
        </nav>
    );
}
