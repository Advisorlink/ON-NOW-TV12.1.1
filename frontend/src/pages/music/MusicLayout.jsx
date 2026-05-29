import React from 'react';
import { Outlet, NavLink } from 'react-router-dom';
import { Home, Search, Radio, Mic, Library, Music2 } from 'lucide-react';
import { MiniPlayer } from '../../components/music/MiniPlayer';
import useSpatialFocus from '../../hooks/useSpatialFocus';
import './tunes.css';

/** Vertical nav for the Tunes app — same flavor on mobile/TV. */
function TunesNav() {
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
        </nav>
    );
}

/** Wraps every Music route — provides nav + sticky mini-player. */
export default function MusicLayout() {
    // v2.8.44 — Wire D-pad / remote-control spatial navigation.
    // Every focusable element below the Music routes will be
    // arrow-key navigable thanks to this hook.  Same engine
    // Vesper has used since v1.6.
    useSpatialFocus();
    return (
        <div className="tunes-root" data-testid="music-layout">
            <div className="tunes-shell">
                <TunesNav />
                <main className="tunes-main">
                    <Outlet />
                </main>
            </div>
            <MiniPlayer />
        </div>
    );
}
