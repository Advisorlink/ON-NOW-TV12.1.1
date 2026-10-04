import React, { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Zap, MoreHorizontal } from 'lucide-react';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';
import { getActiveProfile } from '@/lib/profiles';
import { AvatarCircle } from '@/lib/avatars';
import { CompactNavMenu } from '@/components/CompactNavMenu';
import useTopNavMotion from '@/hooks/useTopNavMotion';
import './topNavMotion.css';

/**
 * <TopNav/> — alternative to the left rail (Settings → "Top menu
 * bar").  A glass pill centred along the top holding icon-only
 * buttons; the label slides out beside the icon while it is
 * focused/hovered.  Reached with UP from the top row (never LEFT).
 */
export default function TopNav({ items }) {
    const motion = useTopNavMotion();
    const [compact, setCompact] = useState(() => window.matchMedia('(max-width: 600px)').matches);
    const [moreOpen, setMoreOpen] = useState(false);
    useEffect(() => {
        const resize = () => { setCompact(window.matchMedia('(max-width: 600px)').matches); setMoreOpen(false); };
        window.addEventListener('resize', resize);
        return () => window.removeEventListener('resize', resize);
    }, []);
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
    const toggleAutoplay = () => {
        const next = !autoplay;
        setAutoplay1080p(next);
        setAutoplay(next);
    };
    const closeMore = () => {
        setMoreOpen(false);
        document.querySelector('[data-testid="top-nav-more"]')?.focus({ preventScroll: true });
    };

    return (
        <nav
            data-testid="top-nav"
            data-compact={compact ? 'true' : undefined}
            className="fixed left-0 right-0 top-0 z-40 flex justify-center pointer-events-none"
            style={{ paddingTop: 14 }}
            onKeyDown={motion.onKeyDown}
            onBlur={motion.onBlur}
            onFocus={(e) => {
                if (e.target.closest('[data-focus-trap="true"]')) return;
                motion.follow(e.target);
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
                ref={motion.barRef}
                data-testid="top-nav-items"
                className="top-nav-bar vesper-glass flex items-center gap-1 rounded-full pointer-events-auto"
                style={{
                    padding: '6px 10px',
                    border: '1px solid var(--vesper-line)',
                    boxShadow: '0 12px 40px rgba(0,0,0,0.35)',
                }}
            >
                <span ref={motion.markerRef} data-testid="top-nav-focus-indicator" aria-hidden="true" className="top-nav-focus-indicator" />
                {(compact ? items.slice(0, 4) : items).map((item) => (
                    <TopNavItem
                        key={item.id}
                        testid={`top-nav-${item.id}`}
                        icon={item.icon}
                        label={item.label}
                        active={isActive(item)}
                        onClick={() => go(item.path)}
                    />
                ))}
                {!compact && <span
                    aria-hidden="true"
                    style={{ width: 1, height: 22, background: 'var(--vesper-line)', margin: '0 6px' }}
                />}
                {compact ? <TopNavItem
                    testid="top-nav-more"
                    icon={MoreHorizontal}
                    label="More"
                    active={moreOpen || items.slice(4).some(isActive)}
                    onClick={() => setMoreOpen((open) => !open)}
                /> : <TopNavItem
                    testid="top-nav-autoplay"
                    icon={Zap}
                    label={autoplay ? 'Auto play · ON' : 'Auto play · OFF'}
                    active={autoplay}
                    accent
                    onClick={toggleAutoplay}
                />}
                <button
                    type="button"
                    data-focusable="true"
                    data-focus-style="pill"
                    data-testid="top-nav-profile"
                    aria-label="Switch profile"
                    onClick={() => go('/profiles')}
                    className="top-nav-item flex items-center justify-center rounded-full"
                    style={{ width: 40, flexShrink: 0, height: 40, marginLeft: 2, background: 'transparent' }}
                >
                    <AvatarCircle avatarId={activeProfile?.avatarId} size={28} />
                </button>
            </div>
            {compact && moreOpen && <CompactNavMenu items={items.slice(4)} isActive={isActive} go={go} autoplay={autoplay} toggleAutoplay={toggleAutoplay} onClose={closeMore} />}
        </nav>
    );
}

function TopNavItem({ icon: Icon, label, active, accent = false, onClick, testid }) {
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
            className="top-nav-item relative flex items-center justify-center rounded-full"
            style={{
                width: 40,
                flexShrink: 0,
                height: 40,
                color,
                background: active ? 'color-mix(in srgb, currentColor 14%, transparent)' : 'transparent',
            }}
        >
            <Icon size={20} strokeWidth={active ? 2.4 : 2} />
            {/* Label pops in UNDER the icon — fixed-size buttons, so the
                bar never grows, shifts or re-centres. */}
                <span
                    data-testid={`${testid}-label`}
                    className="top-nav-label vesper-glass font-sans rounded-full"
                    style={{
                        position: 'absolute',
                        top: 46,
                        left: '50%',
                        transform: 'translateX(-50%)',
                        padding: '4px 12px',
                        fontSize: 13,
                        fontWeight: 600,
                        whiteSpace: 'nowrap',
                        color: 'var(--vesper-text)',
                        border: '1px solid var(--vesper-line)',
                        pointerEvents: 'none',
                    }}
                >
                    {label}
                </span>
        </button>
    );
}
