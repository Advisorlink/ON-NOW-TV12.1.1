import React, { useEffect, useState } from 'react';
import { Outlet, NavLink } from 'react-router-dom';
import { Home, Search, Radio, Mic, Library, Music2, Maximize, Minimize } from 'lucide-react';
import { MiniPlayer } from '../../components/music/MiniPlayer';
import { ResolverDebug } from '../../components/music/ResolverDebug';
import { YouTubeIFrameHost } from '../../components/music/YouTubeIFrameHost';
import useSpatialFocus from '../../hooks/useSpatialFocus';
import './tunes.css';

/** Cross-browser Fullscreen API helpers. */
function requestFs() {
    const el = document.documentElement;
    const r = el.requestFullscreen
        || el.webkitRequestFullscreen
        || el.mozRequestFullScreen
        || el.msRequestFullscreen;
    if (r) { try { return r.call(el); } catch { /* ignore */ } }
    return null;
}
function exitFs() {
    const d = document;
    const e = d.exitFullscreen
        || d.webkitExitFullscreen
        || d.mozCancelFullScreen
        || d.msExitFullscreen;
    if (e && (d.fullscreenElement || d.webkitFullscreenElement)) {
        try { return e.call(d); } catch { /* ignore */ }
    }
    return null;
}
function isFs() {
    return !!(document.fullscreenElement
        || document.webkitFullscreenElement
        || document.mozFullScreenElement
        || document.msFullscreenElement);
}

/** Vertical nav for the Tunes app — same flavor on mobile/TV. */
function TunesNav() {
    const [fs, setFs] = useState(false);
    useEffect(() => {
        const update = () => setFs(isFs());
        document.addEventListener('fullscreenchange', update);
        document.addEventListener('webkitfullscreenchange', update);
        update();
        return () => {
            document.removeEventListener('fullscreenchange', update);
            document.removeEventListener('webkitfullscreenchange', update);
        };
    }, []);
    const items = [
        { to: '/music',          label: 'Home',     icon: Home,    end: true },
        { to: '/music/search',   label: 'Search',   icon: Search },
        { to: '/music/radio',    label: 'Radio',    icon: Radio },
        { to: '/music/podcasts', label: 'Podcasts', icon: Mic },
        { to: '/music/library',  label: 'Library',  icon: Library },
    ];
    return (
        <nav className="tunes-nav" data-testid="side-nav" data-tunes-nav="true">
            <div className="tunes-nav__brand">
                <div className="tunes-nav__logo">
                    <Music2 size={22} color="#fff" />
                </div>
                <div>
                    <div className="tunes-nav__title">ON NOW</div>
                    <div className="tunes-nav__subtitle">Tunes</div>
                </div>
            </div>
            {items.map(({ to, label, icon: Icon, end }) => (
                <NavLink
                    key={to}
                    to={to}
                    end={end}
                    className={({ isActive }) =>
                        'tunes-nav__item' + (isActive ? ' tunes-nav__item--active' : '')
                    }
                    data-testid={`tunes-nav-${label.toLowerCase()}`}
                    data-focusable="true"
                    data-focus-style="nav"
                    tabIndex={0}
                >
                    <Icon size={22} />
                    <span>{label}</span>
                </NavLink>
            ))}
            <div style={{ flex: 1 }} />
            <button
                type="button"
                className="tunes-nav__item"
                onClick={() => (fs ? exitFs() : requestFs())}
                data-testid="tunes-nav-fullscreen"
                data-focusable="true"
                data-focus-style="nav"
                tabIndex={0}
                style={{
                    background: 'transparent',
                    border: '2px solid transparent',
                    cursor: 'pointer',
                    width: '100%',
                    fontFamily: 'inherit',
                }}
                aria-label={fs ? 'Exit fullscreen' : 'Enter fullscreen'}
                title={fs ? 'Exit fullscreen' : 'Enter fullscreen'}
            >
                {fs ? <Minimize size={22} /> : <Maximize size={22} />}
                <span>{fs ? 'Exit fullscreen' : 'Full screen'}</span>
            </button>
        </nav>
    );
}

/** Wraps every Music route — provides nav + sticky mini-player. */
export default function MusicLayout() {
    // v2.8.44 — Wire D-pad / remote-control spatial navigation.
    useSpatialFocus();

    // v2.8.46 — Defensive "scroll-follows-focus" inside the Music
    // app.  The shared spatial-focus hook tries to scroll the
    // document, but only when its math says the row is off-screen.
    // For the music app's grid layouts (which don't use shelf-page
    // snap), some focus changes don't trigger the hook's scroll —
    // the user reported "arrow-down doesn't scroll the page".
    // This effect listens for ANY focus change inside /music and
    // calls scrollIntoView({block:'center'}) so the focused tile
    // is always centered in the viewport.  Idempotent with the
    // hook's own scroll — they coexist.
    useEffect(() => {
        const onFocus = (e) => {
            const el = e.target;
            if (!(el instanceof HTMLElement)) return;
            // Only scroll for our own focusable surfaces, not the
            // entire document.
            if (!el.closest('.tunes-root')) return;
            if (!el.matches('[data-focusable="true"], [data-focusable="true"] *')) return;
            const focusable = el.matches('[data-focusable="true"]')
                ? el
                : el.closest('[data-focusable="true"]');
            if (!focusable) return;
            // Use rAF so we don't fight the hook's scrollBy in the
            // same tick.
            requestAnimationFrame(() => {
                try {
                    focusable.scrollIntoView({
                        behavior: 'smooth',
                        block: 'center',
                        inline: 'nearest',
                    });
                } catch { /* ignore */ }
            });
        };
        document.addEventListener('focusin', onFocus, true);
        return () => document.removeEventListener('focusin', onFocus, true);
    }, []);

    return (
        <div className="tunes-root" data-testid="music-layout">
            <div className="tunes-shell">
                <TunesNav />
                <main className="tunes-main">
                    <Outlet />
                </main>
            </div>
            <MiniPlayer />
            <ResolverDebug />
            <YouTubeIFrameHost />
        </div>
    );
}
