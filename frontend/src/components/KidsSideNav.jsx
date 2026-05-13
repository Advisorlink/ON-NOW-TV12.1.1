import React, { useState, useEffect } from 'react';
import {
    Home as HomeIcon,
    Search,
    Film,
    Tv,
    LogOut,
    Sparkles,
} from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import Host from '@/lib/host';
import { getKidsConfig } from '@/lib/profiles';

/**
 * Kid-themed side rail.  Same spatial-focus contract as the main
 * SideNav (data-focusable + data-focus-style="nav") so the existing
 * D-pad navigation logic keeps working.  Visually it's a playful
 * gradient with rounded chunky icons and sunshine yellow as the
 * signal accent instead of neon blue.
 *
 * Menu items respect the user's `contentTypes` preference: if a
 * parent has chosen "TV Shows only", the Movies item disappears
 * entirely (and vice versa).  Home is always shown but its content
 * is also filtered by the same preference.
 */
const ALL_ITEMS = [
    { id: 'kids-home', label: 'For You', icon: HomeIcon, filter: null, type: 'always' },
    { id: 'kids-movies', label: 'Movies', icon: Film, filter: 'movie', type: 'movie' },
    { id: 'kids-cartoons', label: 'TV', icon: Tv, filter: 'series', type: 'series' },
    { id: 'kids-search', label: 'Search', icon: Search, path: '/search', type: 'always' },
];

export default function KidsSideNav() {
    const [expanded, setExpanded] = useState(false);
    const [cfg, setCfg] = useState(getKidsConfig());
    const location = useLocation();
    const navigate = useNavigate();
    const activeFilter = new URLSearchParams(location.search).get('filter');
    const activePath = location.pathname;

    useEffect(() => {
        const sync = () => setCfg(getKidsConfig());
        window.addEventListener('vesper:kids-config-change', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('vesper:kids-config-change', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);

    const items = ALL_ITEMS.filter((it) => {
        if (it.type === 'always') return true;
        if (cfg.contentTypes === 'movies') return it.type === 'movie';
        if (cfg.contentTypes === 'series') return it.type === 'series';
        return true;
    });

    return (
        <nav
            data-testid="kids-side-nav"
            onFocus={() => setExpanded(true)}
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget))
                    setExpanded(false);
            }}
            className="fixed left-0 top-0 bottom-0 z-40 flex flex-col py-7 transition-[width,background] duration-300"
            style={{
                width: expanded ? '244px' : '76px',
                background: expanded
                    ? 'linear-gradient(180deg, rgba(80,30,120,0.96) 0%, rgba(40,15,80,0.92) 60%, rgba(20,8,50,0) 100%)'
                    : 'transparent',
                backdropFilter: expanded ? 'blur(14px)' : 'none',
            }}
        >
            {/* Brand mark — playful star/sparkle */}
            <div className="flex items-center gap-3 pl-3 pr-3 mb-8 select-none">
                <div
                    className="shrink-0 w-10 h-10 rounded-2xl flex items-center justify-center"
                    style={{
                        background:
                            'linear-gradient(135deg, #FFD43B 0%, #FF6BCB 100%)',
                        boxShadow: '0 0 18px rgba(255,212,59,0.45)',
                    }}
                >
                    <Sparkles size={20} strokeWidth={2.5} color="#fff" />
                </div>
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
                            color: '#fff',
                        }}
                    >
                        Kids{' '}
                        <span style={{ color: '#FFD43B' }}>Mode</span>
                    </div>
                    <div
                        className="vesper-eyebrow"
                        style={{ fontSize: 9, color: '#FFC8E5' }}
                    >
                        let&apos;s have fun
                    </div>
                </div>
            </div>

            <div className="flex flex-col gap-1 px-3">
                {items.map((item) => {
                    const Icon = item.icon;
                    const itemPath = item.path || '/';
                    const isActive = item.path
                        ? activePath.startsWith(item.path)
                        : activePath === '/' && activeFilter === item.filter;
                    return (
                        <button
                            key={item.id}
                            data-testid={`nav-${item.id}`}
                            data-focusable="true"
                            data-focus-style="nav"
                            tabIndex={0}
                            onClick={() => {
                                if (item.path) navigate(item.path);
                                else {
                                    navigate(
                                        item.filter
                                            ? `/?filter=${item.filter}`
                                            : '/'
                                    );
                                }
                            }}
                            className="relative flex items-center gap-4 h-12 px-2 rounded-2xl text-left"
                            style={{
                                color: isActive
                                    ? '#FFD43B'
                                    : 'rgba(255,255,255,0.78)',
                                background: isActive
                                    ? 'rgba(255,212,59,0.10)'
                                    : 'transparent',
                            }}
                        >
                            <span
                                className="flex items-center justify-center w-10 h-10 shrink-0 rounded-xl"
                                style={{
                                    background: isActive
                                        ? 'linear-gradient(135deg, #FFD43B, #FF6BCB)'
                                        : 'rgba(255,255,255,0.08)',
                                }}
                            >
                                <Icon
                                    size={20}
                                    strokeWidth={2.2}
                                    color={isActive ? '#fff' : '#fff'}
                                />
                            </span>
                            <span
                                className="font-sans text-[15px] font-semibold overflow-hidden whitespace-nowrap transition-opacity duration-300"
                                style={{ opacity: expanded ? 1 : 0 }}
                            >
                                {item.label}
                            </span>
                        </button>
                    );
                })}

                {/* Exit Kids — opens PIN gate */}
                <button
                    data-testid="kids-exit"
                    data-focusable="true"
                    data-focus-style="nav"
                    tabIndex={0}
                    onClick={() => navigate('/kids/exit-pin')}
                    className="relative flex items-center gap-4 h-12 px-2 mt-6 rounded-2xl text-left"
                    style={{
                        color: 'rgba(255,255,255,0.65)',
                    }}
                >
                    <span
                        className="flex items-center justify-center w-10 h-10 shrink-0 rounded-xl"
                        style={{
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.10)',
                        }}
                    >
                        <LogOut size={18} strokeWidth={2} color="#fff" />
                    </span>
                    <span
                        className="font-sans text-[15px] font-medium overflow-hidden whitespace-nowrap transition-opacity duration-300"
                        style={{ opacity: expanded ? 1 : 0 }}
                    >
                        Exit Kids
                    </span>
                </button>
            </div>

            <div className="mt-auto pl-7 pr-4">
                <div
                    className="vesper-mono transition-opacity duration-300"
                    style={{
                        opacity: expanded ? 0.55 : 0,
                        fontSize: 11,
                        color: '#FFC8E5',
                        letterSpacing: '0.18em',
                        textTransform: 'uppercase',
                        lineHeight: 1.6,
                    }}
                >
                    Kid-safe content
                    <br />
                    <span style={{ color: 'rgba(255,255,255,0.40)' }}>
                        {Host.isAndroid ? 'On TV' : 'On device'}
                    </span>
                </div>
            </div>
        </nav>
    );
}
