import React, { useState, useEffect } from 'react';
import '@/index.css';
import { BrowserRouter, HashRouter, Routes, Route, Navigate, useLocation, useNavigate } from 'react-router-dom';
import Home from '@/pages/Home';
import Sources from '@/pages/Sources';
import Detail from '@/pages/Detail';
import Player from '@/pages/Player';
import Search from '@/pages/Search';
import Network from '@/pages/Network';
import Resolve from '@/pages/Resolve';
import Settings from '@/pages/Settings';
import Library from '@/pages/Library';
import ProfileSelect from '@/pages/ProfileSelect';
import ProfileEdit from '@/pages/ProfileEdit';
import KidsHome from '@/pages/KidsHome';
import KidsExitPin from '@/pages/KidsExitPin';
import WatchTogether from '@/pages/WatchTogether';
import LiveTV from '@/pages/LiveTV';
import SportsGuide from '@/pages/SportsGuide';
import DevModeBadge from '@/components/DevModeBadge';
import NewEpisodeToast from '@/components/NewEpisodeToast';
import AddToListModal from '@/components/AddToListModal';
import ReminderWatcher from '@/components/ReminderWatcher';
import { ThemeProvider } from '@/themes/ThemeProvider';
import { getActiveProfile, isKidsActive } from '@/lib/profiles';
import { AVATARS } from '@/lib/avatars';
import ErrorBoundary from '@/components/ErrorBoundary';
import MobileBottomNav from '@/components/MobileBottomNav';
import UpdateGate from '@/components/UpdateGate';
import Person from '@/pages/Person';
import { LogOut } from 'lucide-react';
import useIsMobile from '@/lib/useIsMobile';

/* Warm the DiceBear avatar HTTP cache on app boot.  Runs once at
   module load — the 48 character-portrait PNGs (~11 KB each) are
   fetched in the background so by the time the user opens the
   Profile picker / wizard every tile is already cached. */
if (typeof window !== 'undefined') {
    try {
        AVATARS
            .filter((a) => a.src && !a.hidden)
            .forEach((a) => {
                const img = new Image();
                img.decoding = 'async';
                img.src = a.src;
            });
    } catch { /* ignore */ }
}

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

/* Routes where the mobile bottom-nav should be hidden — full-bleed
   experiences (player, profile gates, watch party). */
const MOBILE_NAV_HIDDEN_PREFIXES = [
    '/play',
    '/profiles',
    '/kids/exit-pin',
    '/watch-together',
    '/resolve/',
];

function MobilePlatformRoot({ children }) {
    const isMobile = useIsMobile();
    const location = useLocation();
    /* Tag the document body so global CSS (in index.css) can branch
       on platform without every component having to know. */
    useEffect(() => {
        if (typeof document === 'undefined') return undefined;
        document.body.setAttribute('data-platform', isMobile ? 'mobile' : 'tv');
        document.documentElement.setAttribute(
            'data-platform', isMobile ? 'mobile' : 'tv'
        );
        return () => {
            document.body.removeAttribute('data-platform');
            document.documentElement.removeAttribute('data-platform');
        };
    }, [isMobile]);

    const showBottomNav = isMobile &&
        !MOBILE_NAV_HIDDEN_PREFIXES.some((p) =>
            p.endsWith('/')
                ? location.pathname.startsWith(p)
                : location.pathname === p || location.pathname.startsWith(p + '/'));

    /* In kids mode on mobile we hide the desktop KidsSideNav (no
       room on a phone screen) — but that ALSO hides the "Exit Kids"
       button.  Render a floating Exit pill in the top-right so the
       parent can still get out.  Only shown in kids mode, not on
       the PIN gate itself (the gate IS the exit). */
    const kidsActive = isMobile && isKidsActive() &&
        !location.pathname.startsWith('/kids/exit-pin');

    return (
        <>
            {children}
            {showBottomNav && <MobileBottomNav />}
            {kidsActive && <KidsExitPill />}
        </>
    );
}

/**
 * Floating "Exit Kids" pill rendered in the top-right corner of
 * the kids-mode mobile UI.  Replaces the desktop KidsSideNav's Exit
 * row which is hidden on phones for space reasons.
 *
 * Sits above all other content (z-index 65) with a safe-area inset
 * so the notch / camera-cutout on the Fold 7 doesn't overlap it.
 */
function KidsExitPill() {
    const navigate = useNavigate();
    return (
        <button
            data-testid="kids-mobile-exit"
            onClick={() => navigate('/kids/exit-pin')}
            style={{
                position: 'fixed',
                top: 'calc(12px + env(safe-area-inset-top, 0px))',
                right: 'calc(12px + env(safe-area-inset-right, 0px))',
                zIndex: 65,
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                height: 38,
                padding: '0 14px',
                borderRadius: 999,
                background: 'rgba(6,8,15,0.85)',
                border: '1px solid rgba(255,255,255,0.15)',
                color: '#fff',
                fontSize: 12,
                fontWeight: 700,
                letterSpacing: '0.04em',
                WebkitTapHighlightColor: 'transparent',
                cursor: 'pointer',
                boxShadow: '0 4px 12px rgba(0,0,0,0.45)',
            }}
        >
            <LogOut size={14} strokeWidth={2.4} />
            <span>EXIT KIDS</span>
        </button>
    );
}

function App() {
    return (
        <div className="App">
            <ErrorBoundary>
                <ThemeProvider>
                    <Router>
                        <MobilePlatformRoot>
                            <DevModeBadge />
                            <NewEpisodeToast />
                            <AddToListModal />
                            <ReminderWatcher />
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
                                <Route path="/library" element={<RequireProfile><Library /></RequireProfile>} />
                                <Route path="/settings" element={<RequireProfile><Settings /></RequireProfile>} />
                                <Route path="/title/:type/:id" element={<RequireProfile><Detail /></RequireProfile>} />
                                <Route path="/title/:id" element={<RequireProfile><Detail /></RequireProfile>} />
                                <Route path="/play" element={<RequireProfile><Player /></RequireProfile>} />
                                <Route path="/watch-together" element={<RequireProfile><WatchTogether /></RequireProfile>} />
                                <Route path="/live-tv" element={<RequireProfile><LiveTV /></RequireProfile>} />
                                <Route path="/sports" element={<RequireProfile><SportsGuide /></RequireProfile>} />
                                <Route path="/person/:tmdbId" element={<RequireProfile><Person /></RequireProfile>} />
                            </Routes>
                            <UpdateGate />
                        </MobilePlatformRoot>
                    </Router>
                </ThemeProvider>
            </ErrorBoundary>
        </div>
    );
}

export default App;
