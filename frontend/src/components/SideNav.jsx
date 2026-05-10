import React, { useState } from 'react';
import {
    Home as HomeIcon,
    Search,
    Library,
    Plug,
    Settings,
    Star,
} from 'lucide-react';
import { NAV } from '@/data/mockCatalog';

const ICONS = {
    home: HomeIcon,
    search: Search,
    library: Library,
    plug: Plug,
    settings: Settings,
};

export default function SideNav({ active = 'home', onNavigate = () => {} }) {
    const [expanded, setExpanded] = useState(false);

    return (
        <nav
            data-testid="side-nav"
            onFocus={() => setExpanded(true)}
            onBlur={(e) => {
                // collapse only when focus leaves the entire nav
                if (!e.currentTarget.contains(e.relatedTarget))
                    setExpanded(false);
            }}
            className="fixed left-0 top-0 bottom-0 z-40 flex flex-col py-10 transition-[width] duration-300"
            style={{
                width: expanded ? '320px' : '96px',
                background: expanded
                    ? 'linear-gradient(90deg, rgba(10,13,20,0.96) 0%, rgba(10,13,20,0.85) 60%, rgba(10,13,20,0) 100%)'
                    : 'transparent',
                backdropFilter: expanded ? 'blur(8px)' : 'none',
            }}
        >
            {/* Brand mark */}
            <div className="flex items-center gap-4 px-7 mb-12 select-none">
                <div
                    className="flex items-center justify-center w-12 h-12 rounded-full"
                    style={{
                        background: 'rgba(229,138,89,0.12)',
                        border: '1px solid rgba(229,138,89,0.5)',
                    }}
                >
                    <Star
                        size={22}
                        strokeWidth={1.5}
                        className="text-vesper-copper"
                        fill="currentColor"
                    />
                </div>
                <div
                    className="overflow-hidden whitespace-nowrap transition-opacity duration-300"
                    style={{ opacity: expanded ? 1 : 0 }}
                >
                    <div className="vesper-display text-3xl tracking-tight">
                        Vesper
                    </div>
                    <div
                        className="vesper-eyebrow"
                        style={{ fontSize: 11, letterSpacing: '0.32em' }}
                    >
                        Vespertine
                    </div>
                </div>
            </div>

            {/* Items */}
            <div className="flex flex-col gap-1 px-3">
                {NAV.map((item) => {
                    const Icon = ICONS[item.icon] || HomeIcon;
                    const isActive = active === item.id;
                    return (
                        <button
                            key={item.id}
                            data-testid={`nav-${item.id}`}
                            data-focusable="true"
                            data-focus-style="nav"
                            tabIndex={0}
                            onClick={() => onNavigate(item.id)}
                            className={`relative flex items-center gap-5 h-16 px-4 rounded-md text-left ${
                                isActive
                                    ? 'text-vesper-text'
                                    : 'text-vesper-text2'
                            }`}
                        >
                            <span className="flex items-center justify-center w-12 h-12 shrink-0">
                                <Icon
                                    size={26}
                                    strokeWidth={1.5}
                                    style={{
                                        color: isActive
                                            ? 'var(--vesper-copper)'
                                            : 'currentColor',
                                    }}
                                />
                            </span>
                            <span
                                className="font-sans text-[22px] font-medium overflow-hidden whitespace-nowrap transition-opacity duration-300"
                                style={{ opacity: expanded ? 1 : 0 }}
                            >
                                {item.label}
                            </span>
                        </button>
                    );
                })}
            </div>

            <div className="mt-auto px-7">
                <div
                    className="vesper-eyebrow transition-opacity duration-300"
                    style={{
                        opacity: expanded ? 1 : 0,
                        fontSize: 11,
                        letterSpacing: '0.32em',
                    }}
                >
                    HK1 · TV
                </div>
            </div>
        </nav>
    );
}
