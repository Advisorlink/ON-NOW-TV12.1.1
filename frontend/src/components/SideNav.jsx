import React, { useState } from 'react';
import {
    Home as HomeIcon,
    Search,
    Library,
    Plug,
    Settings,
} from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import Host from '@/lib/host';

const NAV = [
    { id: 'home', label: 'Home', icon: HomeIcon, path: '/' },
    { id: 'search', label: 'Search', icon: Search, path: '/search' },
    { id: 'library', label: 'My Library', icon: Library, path: '/library' },
    { id: 'sources', label: 'Sources', icon: Plug, path: '/sources' },
    { id: 'settings', label: 'Settings', icon: Settings, path: '/settings' },
];

export default function SideNav() {
    const [expanded, setExpanded] = useState(false);
    const location = useLocation();
    const navigate = useNavigate();
    const activePath = location.pathname;

    return (
        <nav
            data-testid="side-nav"
            onFocus={() => setExpanded(true)}
            onBlur={(e) => {
                if (!e.currentTarget.contains(e.relatedTarget))
                    setExpanded(false);
            }}
            className="fixed left-0 top-0 bottom-0 z-40 flex flex-col py-9 transition-[width,background] duration-300"
            style={{
                width: expanded ? '320px' : '108px',
                background: expanded
                    ? 'linear-gradient(90deg, rgba(10,14,26,0.96) 0%, rgba(10,14,26,0.85) 60%, rgba(10,14,26,0) 100%)'
                    : 'transparent',
                backdropFilter: expanded ? 'blur(14px)' : 'none',
            }}
        >
            {/* Brand mark */}
            <div className="flex items-center gap-3 pl-5 pr-4 mb-12 select-none">
                <img
                    src="/brand/onnowtv-logo.png"
                    alt="ON NOW TV V2"
                    className="shrink-0 w-14 h-14 object-contain"
                    style={{ filter: 'drop-shadow(0 0 16px rgba(93,200,255,0.35))' }}
                />
                <div
                    className="overflow-hidden whitespace-nowrap transition-opacity duration-300"
                    style={{ opacity: expanded ? 1 : 0 }}
                >
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 22,
                            lineHeight: 1.05,
                            letterSpacing: '-0.02em',
                        }}
                    >
                        ON NOW TV{' '}
                        <span style={{ color: 'var(--vesper-blue)' }}>V2</span>
                    </div>
                    <div className="vesper-eyebrow" style={{ fontSize: 10 }}>
                        for HK1 · TV
                    </div>
                </div>
            </div>

            {/* Items */}
            <div className="flex flex-col gap-1 px-4">
                {NAV.map((item) => {
                    const Icon = item.icon;
                    const isActive =
                        activePath === item.path ||
                        (item.path !== '/' && activePath.startsWith(item.path));
                    return (
                        <button
                            key={item.id}
                            data-testid={`nav-${item.id}`}
                            data-focusable="true"
                            data-focus-style="nav"
                            tabIndex={0}
                            onClick={() => navigate(item.path)}
                            className={`relative flex items-center gap-5 h-14 px-3 rounded-lg text-left ${
                                isActive
                                    ? 'text-vesper-text'
                                    : 'text-vesper-text2'
                            }`}
                        >
                            <span className="flex items-center justify-center w-12 h-12 shrink-0">
                                <Icon
                                    size={24}
                                    strokeWidth={1.6}
                                    style={{
                                        color: isActive
                                            ? 'var(--vesper-blue)'
                                            : 'currentColor',
                                    }}
                                />
                            </span>
                            <span
                                className="font-sans text-[20px] font-medium overflow-hidden whitespace-nowrap transition-opacity duration-300"
                                style={{ opacity: expanded ? 1 : 0 }}
                            >
                                {item.label}
                            </span>
                        </button>
                    );
                })}
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
                        v1.1.2 ·{' '}
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
