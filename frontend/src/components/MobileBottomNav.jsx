/**
 * <MobileBottomNav/> — mobile-mode bottom tab bar that replaces the
 * desktop / TV <SideNav/>.  Sticky, 5 primary destinations:
 *   Home · Search · Live TV · Library · More
 *
 * The "More" tab opens a full-width bottom sheet that surfaces every
 * secondary destination from the desktop side rail — Sports, TV
 * Shows, Movies, Watch Together, Profiles, Sources, Settings — so
 * phone users never have to fish around for them.
 *
 * Designed to feel like a native iOS / Android tab bar: tap targets
 * ≥ 44 px, monochrome glyphs with a coloured active state, no hover.
 *
 * Pages that need a back-arrow (Detail, Player, Watch Together,
 * Sources) hide this nav by simply not rendering it; the global
 * `<SideNavBar/>` wrapper in App.js checks the route.
 */

import React, { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
    Home as HomeIcon,
    Search as SearchIcon,
    Tv,
    BookOpen,
    Menu,
    Trophy,
    Users,
    Plug,
    Settings as SettingsIcon,
    Film,
    Tv2,
    X,
    UserCircle,
} from 'lucide-react';

const TABS = [
    { path: '/',        label: 'Home',    Icon: HomeIcon },
    { path: '/search',  label: 'Search',  Icon: SearchIcon },
    { path: '/live-tv', label: 'Live',    Icon: Tv },
    { path: '/library', label: 'Library', Icon: BookOpen },
    { path: '__more__', label: 'More',    Icon: Menu },
];

/**
 * Items inside the "More" bottom sheet.  Kept in sync with SideNav
 * so phone users have feature-parity with TV.
 */
const MORE_ITEMS = [
    { label: 'Sports Guide',     path: '/sports',         Icon: Trophy,      hint: 'Live scores + fixtures' },
    { label: 'TV Shows',         path: '/?filter=series', Icon: Tv2,         hint: 'Series in your library' },
    { label: 'Movies',           path: '/?filter=movie',  Icon: Film,        hint: 'Films in your library' },
    { label: 'Watch Together',   path: '/watch-together', Icon: Users,       hint: 'Co-watch with friends in sync' },
    { label: 'Profiles',         path: '/profiles',       Icon: UserCircle,  hint: 'Switch or manage profiles' },
    { label: 'Sources',          path: '/sources',        Icon: Plug,        hint: 'IPTV + Stremio add-ons' },
    { label: 'Settings',         path: '/settings',       Icon: SettingsIcon, hint: 'App preferences' },
];

function activeFor(pathname) {
    if (pathname === '/' || pathname.startsWith('/home')) return '/';
    for (const t of TABS) {
        if (t.path === '__more__' || t.path === '/') continue;
        if (pathname.startsWith(t.path)) return t.path;
    }
    return null;
}

export default function MobileBottomNav() {
    const { pathname } = useLocation();
    const navigate = useNavigate();
    const active = activeFor(pathname);
    const [moreOpen, setMoreOpen] = useState(false);

    const handleTab = (path) => {
        if (path === '__more__') {
            setMoreOpen(true);
            return;
        }
        setMoreOpen(false);
        navigate(path);
    };

    return (
        <>
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
                    const isActive = path === '__more__' ? moreOpen : active === path;
                    return (
                        <button
                            key={path}
                            data-testid={`mobile-nav-${label.toLowerCase()}`}
                            onClick={() => handleTab(path)}
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
                                minHeight: 48,
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

            {moreOpen && (
                <MoreSheet
                    pathname={pathname}
                    onClose={() => setMoreOpen(false)}
                    onPick={(path) => { setMoreOpen(false); navigate(path); }}
                />
            )}
        </>
    );
}

/**
 * Bottom-sheet style menu that slides up from below the tab bar.
 * Renders the full secondary-nav list (Sports, Watch Together,
 * Profiles, Sources, Settings, etc.) as touch-friendly rows.
 */
function MoreSheet({ pathname, onClose, onPick }) {
    // Lock background scroll while the sheet is open so the user
    // doesn't accidentally scroll the page behind it.
    React.useEffect(() => {
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = prev; };
    }, []);

    return (
        <div
            data-testid="mobile-more-sheet"
            onClick={onClose}
            style={{
                position: 'fixed',
                inset: 0,
                background: 'rgba(6,8,15,0.72)',
                zIndex: 70,
                animation: 'vesper-mob-sheet-fade 160ms ease-out',
            }}
        >
            <div
                onClick={(e) => e.stopPropagation()}
                style={{
                    position: 'absolute',
                    left: 0, right: 0, bottom: 0,
                    background: '#0A0F1A',
                    borderTopLeftRadius: 24, borderTopRightRadius: 24,
                    padding: '14px 18px calc(28px + env(safe-area-inset-bottom, 0px)) 18px',
                    maxHeight: '78vh',
                    overflowY: 'auto',
                    borderTop: '1px solid rgba(255,255,255,0.08)',
                    animation: 'vesper-mob-sheet-slide 240ms cubic-bezier(0.22, 1, 0.36, 1)',
                }}
            >
                {/* Drag handle */}
                <div
                    aria-hidden="true"
                    style={{
                        width: 40, height: 4, borderRadius: 999,
                        background: 'rgba(255,255,255,0.18)',
                        margin: '0 auto 18px auto',
                    }}
                />

                <div className="flex items-center justify-between" style={{ marginBottom: 14 }}>
                    <div className="flex flex-col" style={{ gap: 2 }}>
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 10, letterSpacing: '0.32em',
                                color: '#5DC8FF', fontWeight: 700,
                            }}
                        >
                            MORE
                        </span>
                        <span
                            style={{
                                fontSize: 22, fontWeight: 700,
                                color: '#fff', letterSpacing: '-0.01em',
                            }}
                        >
                            Everything else
                        </span>
                    </div>
                    <button
                        onClick={onClose}
                        data-testid="mobile-more-close"
                        style={{
                            width: 40, height: 40, borderRadius: 999,
                            border: '1px solid rgba(255,255,255,0.12)',
                            background: 'rgba(255,255,255,0.04)',
                            color: '#9DA5B5',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            cursor: 'pointer',
                            WebkitTapHighlightColor: 'transparent',
                        }}
                        aria-label="Close"
                    >
                        <X size={18} />
                    </button>
                </div>

                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                    {MORE_ITEMS.map(({ label, path, Icon, hint }) => {
                        const isActive = pathname === path.split('?')[0];
                        return (
                            <li key={path} style={{ marginBottom: 8 }}>
                                <button
                                    data-testid={`mobile-more-${label.toLowerCase().replace(/\s+/g, '-')}`}
                                    onClick={() => onPick(path)}
                                    style={{
                                        width: '100%',
                                        display: 'flex', alignItems: 'center', gap: 14,
                                        padding: '14px 16px',
                                        background: isActive ? 'rgba(93,200,255,0.10)' : 'rgba(255,255,255,0.03)',
                                        border: isActive
                                            ? '1px solid rgba(93,200,255,0.4)'
                                            : '1px solid rgba(255,255,255,0.06)',
                                        borderRadius: 14,
                                        color: isActive ? '#fff' : '#E6EAF2',
                                        textAlign: 'left',
                                        cursor: 'pointer',
                                        WebkitTapHighlightColor: 'transparent',
                                        minHeight: 60,
                                    }}
                                >
                                    <span
                                        style={{
                                            width: 38, height: 38, borderRadius: 12,
                                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                                            background: isActive ? 'rgba(93,200,255,0.18)' : 'rgba(255,255,255,0.06)',
                                            color: isActive ? '#5DC8FF' : '#9DA5B5',
                                            flexShrink: 0,
                                        }}
                                    >
                                        <Icon size={18} strokeWidth={2} />
                                    </span>
                                    <span className="flex flex-col" style={{ gap: 2, flex: 1, minWidth: 0 }}>
                                        <span style={{ fontSize: 15, fontWeight: 600, color: '#fff' }}>
                                            {label}
                                        </span>
                                        <span
                                            style={{
                                                fontSize: 12, color: '#7d8493',
                                                overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                                            }}
                                        >
                                            {hint}
                                        </span>
                                    </span>
                                </button>
                            </li>
                        );
                    })}
                </ul>
            </div>
        </div>
    );
}
