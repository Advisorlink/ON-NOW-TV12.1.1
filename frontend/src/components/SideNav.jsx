import React, { useState } from 'react';
import {
    Home as HomeIcon,
    Search,
    Library,
    Plug,
    Settings,
    Tv,
    Film,
    Zap,
} from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import Host from '@/lib/host';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';

const NAV = [
    { id: 'home', label: 'Home', icon: HomeIcon, path: '/' },
    { id: 'tv', label: 'TV Shows', icon: Tv, path: '/?filter=series' },
    { id: 'movies', label: 'Movies', icon: Film, path: '/?filter=movie' },
    { id: 'search', label: 'Search', icon: Search, path: '/search' },
    { id: 'library', label: 'My Library', icon: Library, path: '/library' },
    { id: 'sources', label: 'Sources', icon: Plug, path: '/sources' },
    { id: 'settings', label: 'Settings', icon: Settings, path: '/settings' },
];

export default function SideNav() {
    const [expanded, setExpanded] = useState(false);
    const [autoplay, setAutoplay] = useState(getAutoplay1080p());
    const location = useLocation();
    const navigate = useNavigate();
    const currentFilter = new URLSearchParams(location.search).get('filter');
    const activePath = location.pathname;

    const toggleAutoplay = () => {
        const next = !autoplay;
        setAutoplay1080p(next);
        setAutoplay(next);
    };

    return (
        <nav
            data-testid="side-nav"
            onFocus={() => setExpanded(true)}
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget))
                    setExpanded(false);
            }}
            className="fixed left-0 top-0 bottom-0 z-40 flex flex-col py-7 transition-[width,background] duration-300"
            style={{
                width: expanded ? '240px' : '76px',
                background: expanded
                    ? 'linear-gradient(90deg, rgba(10,14,26,0.96) 0%, rgba(10,14,26,0.82) 60%, rgba(10,14,26,0) 100%)'
                    : 'transparent',
                backdropFilter: expanded ? 'blur(14px)' : 'none',
            }}
        >
            {/* Brand mark */}
            <div className="flex items-center gap-3 pl-3 pr-3 mb-8 select-none">
                <img
                    src={Host.publicAsset('/brand/onnowtv-logo.png')}
                    alt="ON NOW TV V2"
                    className="shrink-0 w-10 h-10 object-contain"
                    style={{ filter: 'drop-shadow(0 0 14px rgba(93,200,255,0.3))' }}
                />
                <div
                    className="overflow-hidden whitespace-nowrap transition-opacity duration-300"
                    style={{ opacity: expanded ? 1 : 0 }}
                >
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 16,
                            lineHeight: 1.05,
                            letterSpacing: '-0.02em',
                        }}
                    >
                        ON NOW TV{' '}
                        <span style={{ color: 'var(--vesper-blue)' }}>V2</span>
                    </div>
                    <div className="vesper-eyebrow" style={{ fontSize: 9 }}>
                        for HK1 · TV
                    </div>
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
                            onClick={() => navigate(item.path)}
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
                                style={{ opacity: expanded ? 1 : 0 }}
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
                        style={{ opacity: expanded ? 1 : 0 }}
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
                                    ? 'rgba(93,200,255,0.18)'
                                    : 'rgba(255,255,255,0.08)',
                                color: autoplay
                                    ? 'var(--vesper-blue-bright)'
                                    : 'var(--vesper-text-3)',
                                border: autoplay
                                    ? '1px solid rgba(93,200,255,0.45)'
                                    : '1px solid rgba(255,255,255,0.12)',
                            }}
                        >
                            {autoplay ? 'ON' : 'OFF'}
                        </span>
                    </span>
                </button>
            </div>

            <div className="mt-auto pl-7 pr-4">
                <div
                    className="vesper-mono transition-opacity duration-300"
                    style={{
                        opacity: expanded ? 0.6 : 0,
                        fontSize: 11,
                        color: 'var(--vesper-text-2)',
                        letterSpacing: '0.18em',
                        textTransform: 'uppercase',
                        lineHeight: 1.6,
                    }}
                >
                    Press F for fullscreen
                    <br />
                    <span style={{ color: 'var(--vesper-text-3)' }}>
                        v1.2.0 · libVLC ·{' '}
                        {Host.isAndroid
                            ? 'BUNDLED ✓'
                            : window.location.protocol === 'file:'
                            ? 'FILE://'
                            : 'WEB'}
                    </span>
                </div>
            </div>
        </nav>
    );
}
