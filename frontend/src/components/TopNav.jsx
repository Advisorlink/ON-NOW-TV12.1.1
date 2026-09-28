import React, { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Zap } from 'lucide-react';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';
import { getActiveProfile } from '@/lib/profiles';
import { AvatarCircle } from '@/lib/avatars';

/**
 * <TopNav/> — alternative to the left rail (Settings → "Top menu
 * bar").  A glass pill centred along the top holding icon-only
 * buttons; the label slides out beside the icon while it is
 * focused/hovered.  Reached with UP from the top row (never LEFT).
 */
export default function TopNav({ items }) {
    // Lets pages that start at the very top (Search columns) make
    // room for the bar via `html.vesper-topnav` in index.css.
    useEffect(() => {
        document.documentElement.classList.add('vesper-topnav');
        return () => document.documentElement.classList.remove('vesper-topnav');
    }, []);
    const location = useLocation();
    const navigate = useNavigate();
    const currentFilter = new URLSearchParams(location.search).get('filter');
    const [autoplay, setAutoplay] = useState(getAutoplay1080p());
    const [profileRev, setProfileRev] = useState(0);
    useEffect(() => {
        const onChange = () => setProfileRev((r) => r + 1);
        window.addEventListener('vesper:profile-change', onChange);
        return () => window.removeEventListener('vesper:profile-change', onChange);
    }, []);
    const activeProfile = useMemo(() => {
        try { return getActiveProfile(); } catch { return null; }
    }, [profileRev, location.pathname]);

    const isActive = (item) => {
        if (item.id === 'home') return location.pathname === '/' && !currentFilter;
        if (item.id === 'tv') return location.pathname === '/' && currentFilter === 'series';
        if (item.id === 'movies') return location.pathname === '/' && currentFilter === 'movie';
        return location.pathname === item.path;
    };

    const go = (path) => {
        const currentFull = location.pathname + (location.search || '');
        if (currentFull === path || (location.pathname === path && !location.search)) return;
        try { document.activeElement?.blur?.(); } catch { /* ignore */ }
        navigate(path);
    };

    return (
        <nav
            data-testid="top-nav"
            className="fixed left-0 right-0 top-0 z-40 flex justify-center pointer-events-none"
            style={{ paddingTop: 14 }}
            onFocus={(e) => {
                // Entering the bar from the page (UP from a row): land
                // on the CURRENT page's icon rather than whichever icon
                // happened to be geometrically nearest.
                if (e.currentTarget.contains(e.relatedTarget)) return;
                const activeItem = items.find(isActive);
                if (!activeItem) return;
                const el = e.currentTarget.querySelector(`[data-testid="top-nav-${activeItem.id}"]`);
                if (el && el !== e.target) {
                    try { el.focus({ preventScroll: true }); } catch { /* ignore */ }
                }
            }}
        >
            <div
                className="vesper-glass flex items-center gap-1 rounded-full pointer-events-auto"
                style={{
                    padding: '6px 10px',
                    border: '1px solid var(--vesper-line)',
                    boxShadow: '0 12px 40px rgba(0,0,0,0.35)',
                }}
            >
                {items.map((item) => (
                    <TopNavItem
                        key={item.id}
                        testid={`top-nav-${item.id}`}
                        icon={item.icon}
                        label={item.label}
                        active={isActive(item)}
                        onClick={() => go(item.path)}
                    />
                ))}
                <span
                    aria-hidden="true"
                    style={{ width: 1, height: 22, background: 'var(--vesper-line)', margin: '0 6px' }}
                />
                <TopNavItem
                    testid="top-nav-autoplay"
                    icon={Zap}
                    label={autoplay ? 'Auto play · ON' : 'Auto play · OFF'}
                    active={autoplay}
                    accent
                    onClick={() => {
                        const next = !autoplay;
                        setAutoplay1080p(next);
                        setAutoplay(next);
                    }}
                />
                <button
                    type="button"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-testid="top-nav-profile"
                    aria-label="Switch profile"
                    onClick={() => go('/profiles')}
                    className="flex items-center justify-center rounded-full"
                    style={{ width: 40, height: 40, marginLeft: 2, background: 'transparent' }}
                >
                    <AvatarCircle avatarId={activeProfile?.avatarId} size={28} />
                </button>
            </div>
        </nav>
    );
}

function TopNavItem({ icon: Icon, label, active, accent = false, onClick, testid }) {
    const [open, setOpen] = useState(false);
    const color = active
        ? (accent ? '#FFC350' : 'var(--vesper-accent, #5DC8FF)')
        : 'var(--vesper-text-2)';
    return (
        <button
            type="button"
            data-focusable="true"
            data-focus-style="pill"
            data-testid={testid}
            aria-label={label}
            title={label}
            onClick={onClick}
            onFocus={() => setOpen(true)}
            onBlur={() => setOpen(false)}
            onMouseEnter={() => setOpen(true)}
            onMouseLeave={() => setOpen(false)}
            className="flex items-center rounded-full overflow-hidden"
            style={{
                height: 40,
                padding: open ? '0 16px 0 12px' : '0 10px',
                gap: open ? 8 : 0,
                color,
                background: active ? 'color-mix(in srgb, currentColor 14%, transparent)' : 'transparent',
                transition: 'background-color 120ms',
                whiteSpace: 'nowrap',
            }}
        >
            <Icon size={20} strokeWidth={active ? 2.4 : 2} />
            <span
                className="font-sans"
                style={{
                    fontSize: 14,
                    fontWeight: 600,
                    maxWidth: open ? 180 : 0,
                    opacity: open ? 1 : 0,
                    overflow: 'hidden',
                    transition: 'none',
                    color: 'var(--vesper-text)',
                }}
            >
                {label}
            </span>
        </button>
    );
}
