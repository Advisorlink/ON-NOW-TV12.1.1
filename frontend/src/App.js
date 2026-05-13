import React, { useState, useEffect } from 'react';
import '@/index.css';
import { BrowserRouter, HashRouter, Routes, Route, Navigate, useLocation } from 'react-router-dom';
import Home from '@/pages/Home';
import Sources from '@/pages/Sources';
import Detail from '@/pages/Detail';
import Player from '@/pages/Player';
import Search from '@/pages/Search';
import Network from '@/pages/Network';
import Resolve from '@/pages/Resolve';
import Settings from '@/pages/Settings';
import ProfileSelect from '@/pages/ProfileSelect';
import ProfileEdit from '@/pages/ProfileEdit';
import KidsHome from '@/pages/KidsHome';
import KidsExitPin from '@/pages/KidsExitPin';
import { ThemeProvider } from '@/themes/ThemeProvider';
import { getActiveProfile, isKidsActive } from '@/lib/profiles';

const Router =
    typeof window !== 'undefined' && window.location.protocol === 'file:'
        ? HashRouter
        : BrowserRouter;

function NotImplemented({ name }) {
    return (
        <div
            className="w-screen h-[100dvh] min-h-screen flex items-center justify-center"
            style={{ background: 'var(--vesper-bg-0)' }}
        >
            <div className="vesper-display" style={{ fontSize: 56, letterSpacing: '-0.03em' }}>
                {name} <span style={{ color: 'var(--vesper-blue)' }}>·</span> coming next
            </div>
        </div>
    );
}

/**
 * Routes that don't require an active profile (profile picker,
 * editor, kids exit gate).  Everything else funnels through the
 * RequireProfile guard.
 */
const NO_PROFILE_REQUIRED = [
    '/profiles',
    '/profiles/new',
    '/profiles/edit',
    '/kids/exit-pin',
];

function RequireProfile({ children }) {
    const location = useLocation();
    const [active, setActive] = useState(getActiveProfile());

    useEffect(() => {
        const sync = () => setActive(getActiveProfile());
        window.addEventListener('vesper:profile-change', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('vesper:profile-change', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);

    // Kids profile is sandboxed FIRST — before any "exempt" path check.
    // Otherwise a kid could type /profiles into the URL bar and walk
    // straight out of the kid-safe area.  The ONLY way out is
    // /kids/exit-pin, which then clears the active profile after a
    // correct PIN.
    if (active && active.kids) {
        const allowedKids = [
            '/',
            '/play',
            '/title/',
            '/search',
            '/resolve/',
            '/kids/exit-pin',
        ];
        const ok = allowedKids.some((p) =>
            p === '/' ? location.pathname === '/' : location.pathname.startsWith(p)
        );
        if (!ok) return <Navigate to="/" replace />;
        return children;
    }

    const pathExempt = NO_PROFILE_REQUIRED.some((p) =>
        location.pathname.startsWith(p)
    );
    if (pathExempt) return children;

    if (!active) return <Navigate to="/profiles" replace />;
    return children;
}

function HomeRouter() {
    return isKidsActive() ? <KidsHome /> : <Home />;
}

function App() {
    return (
        <div className="App">
            <ThemeProvider>
                <Router>
                    <Routes>
                        <Route path="/profiles" element={<RequireProfile><ProfileSelect /></RequireProfile>} />
                        <Route path="/profiles/new" element={<RequireProfile><ProfileEdit /></RequireProfile>} />
                        <Route path="/profiles/edit/:id" element={<RequireProfile><ProfileEdit /></RequireProfile>} />
                        <Route path="/kids/exit-pin" element={<RequireProfile><KidsExitPin /></RequireProfile>} />

                        <Route path="/" element={<RequireProfile><HomeRouter /></RequireProfile>} />
                        <Route path="/sources" element={<RequireProfile><Sources /></RequireProfile>} />
                        <Route path="/search" element={<RequireProfile><Search /></RequireProfile>} />
                        <Route path="/networks/:slug" element={<RequireProfile><Network /></RequireProfile>} />
                        <Route path="/resolve/:type/:id" element={<RequireProfile><Resolve /></RequireProfile>} />
                        <Route path="/library" element={<RequireProfile><NotImplemented name="My Library" /></RequireProfile>} />
                        <Route path="/settings" element={<RequireProfile><Settings /></RequireProfile>} />
                        <Route path="/title/:type/:id" element={<RequireProfile><Detail /></RequireProfile>} />
                        <Route path="/title/:id" element={<RequireProfile><Detail /></RequireProfile>} />
                        <Route path="/play" element={<RequireProfile><Player /></RequireProfile>} />
                    </Routes>
                </Router>
            </ThemeProvider>
        </div>
    );
}

export default App;
