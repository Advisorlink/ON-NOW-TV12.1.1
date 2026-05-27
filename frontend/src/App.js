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
import ProfileLoad from '@/pages/ProfileLoad';
import KidsHome from '@/pages/KidsHome';
import KidsExitPin from '@/pages/KidsExitPin';
import WatchTogether from '@/pages/WatchTogether';
import LiveTV from '@/pages/LiveTV';
import SportsGuide from '@/pages/SportsGuide';
import DevModeBadge from '@/components/DevModeBadge';
import NewEpisodeToast from '@/components/NewEpisodeToast';
import AddToListModal from '@/components/AddToListModal';
import ReminderWatcher from '@/components/ReminderWatcher';
import FeatureNudge from '@/components/FeatureNudge';
// v2.7.90 — DebugTouchOverlay intentionally removed.  It was a
// one-build diagnostic with `position: fixed; z-index: 999999`
// at the top of the screen, which sat ABOVE the auto-update
// modal and prevented the "Download new version" prompt from
// being visible.  Re-enable only when actively diagnosing
// touch issues.
import NotifyHitWatcher from '@/components/NotifyHitWatcher';
import DeepLinkHandler from '@/components/DeepLinkHandler';
import { ThemeProvider } from '@/themes/ThemeProvider';
import { getActiveProfile, isKidsActive } from '@/lib/profiles';
import { AVATARS } from '@/lib/avatars';
import ErrorBoundary from '@/components/ErrorBoundary';
import MobileBottomNav from '@/components/MobileBottomNav';
import UpdateGate from '@/components/UpdateGate';
import Onboarding, { hasSeenOnboarding } from '@/components/Onboarding';
import BootSplash from '@/components/BootSplash';
import Person from '@/pages/Person';
import { LogOut } from 'lucide-react';
import useIsMobile from '@/lib/useIsMobile';
import { runNotifyScanner } from '@/lib/notifyScanner';

/* Live TV plumbing removed per user request — every bundle / EPG /
 * native-guide bootstrap has been deleted with it.  The /live-tv
 * route now renders a "Coming Soon" placeholder while we rebuild
 * the experience inside the new native Android launcher project. */
if (typeof window !== 'undefined') {
    setTimeout(() => {
        runNotifyScanner();
    }, 4000);
}

/* Warm the DiceBear avatar HTTP cache on app boot.  Runs once at
   module load — the character-portrait PNGs (~11 KB each) are
   fetched in the background so by the time the user opens the
   Profile picker / wizard every tile is already cached.

   v2.8.0 — DEFERRED to after first paint via requestIdleCallback
   (falls back to setTimeout 2500 ms).  Was firing 36 simultaneous
   HTTPS requests to api.dicebear.com at module-load time, which
   on a slow HK1 box was thrashing the network and starving the
   initial backend calls — contributing to the slow first-boot. */
if (typeof window !== 'undefined') {
    const warmAvatars = () => {
        try {
            AVATARS
                .filter((a) => a.src && !a.hidden)
                .forEach((a) => {
                    const img = new Image();
                    img.decoding = 'async';
                    img.src = a.src;
                });
        } catch { /* ignore */ }
    };
    if (typeof window.requestIdleCallback === 'function') {
        window.requestIdleCallback(warmAvatars, { timeout: 4000 });
    } else {
        setTimeout(warmAvatars, 2500);
    }
}

/* v2.7.96 — SYNCHRONOUS deep-link profile activation.
 *
 * The native ON NOW Launcher's KIDS tile fires Vesper with the URL
 * `file:///android_asset/web/index.html?profile=kids`.  React's
 * `<DeepLinkHandler>` reads this in a `useEffect` and calls
 * `setActiveProfile('kids')`, but its effect runs BEFORE
 * `<RequireProfile>` registers its `vesper:profile-change` listener,
 * so the profile flip is missed and the user lands on the regular
 * adult Home.
 *
 * Fix: read the `?profile=…` URL param at module-load time — BEFORE
 * any React component renders — and write the active-profile slot
 * directly into localStorage.  By the time `<RequireProfile>` first
 * reads `getActiveProfile()` in its `useState` initializer, the
 * value is already correct.  `<DeepLinkHandler>` still runs
 * afterwards to navigate to `/` and strip the param from the URL
 * so a refresh stays consistent without re-triggering it.
 *
 * v2.7.97 — Two-way: also accept `profile=exit-kids` from the
 * Movies/TV launcher tile, which restores the LAST non-kids profile
 * (stored on every kids transition) so the user lands back on their
 * adult home — not stuck in kids mode forever.
 *
 * Key kept in sync with `/lib/profiles.js → KEY_ACTIVE`.
 */
if (typeof window !== 'undefined') {
    try {
        const params = new URLSearchParams(window.location.search);
        const profileParam = params.get('profile');
        const ACTIVE_KEY = 'onnowtv-active-profile-v1';
        const LAST_NON_KIDS_KEY = 'onnowtv-last-non-kids-profile';
        if (profileParam === 'kids') {
            // Remember the user's adult profile BEFORE we switch to
            // Kids — so a later Movies/TV tap restores them, not
            // dump them on the profile picker.
            const current = localStorage.getItem(ACTIVE_KEY);
            if (current && current !== 'kids') {
                localStorage.setItem(LAST_NON_KIDS_KEY, current);
            }
            localStorage.setItem(ACTIVE_KEY, 'kids');
        } else if (profileParam === 'exit-kids') {
            const previous = localStorage.getItem(LAST_NON_KIDS_KEY);
            if (previous) {
                localStorage.setItem(ACTIVE_KEY, previous);
            } else {
                // No adult profile remembered → clear so the
                // ProfileSelect picker takes over.
                localStorage.removeItem(ACTIVE_KEY);
            }
        }
    } catch { /* ignore — malformed URL / disabled storage */ }
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
    '/profiles/load',
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

    /* v2.7.50 — Save the current route to the native bridge whenever
       it changes, so MainActivity can restore it on cold-start when
       Android killed it during ExoPlayer playback.  No-op outside
       the Android WebView (the bridge is null on web preview). */
    useEffect(() => {
        try {
            const bridge = typeof window !== 'undefined' ? window.OnNowTV : null;
            if (!bridge || !('saveRoute' in bridge)) return;
            const hashPath = `#${location.pathname}${location.search || ''}`;
            bridge.saveRoute(hashPath);
        } catch { /* ignore — bridge unavailable */ }
    }, [location.pathname, location.search]);

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

/**
 * Gates the welcome tour: shows it once after a non-kids profile
 * is active and the user hasn't seen it yet.  Also listens for a
 * `vesper:onboarding-replay` event so the Settings → "Replay
 * welcome tour" button can re-open it on demand.
 */
function OnboardingGate() {
    const location = useLocation();
    const [open, setOpen] = React.useState(false);

    React.useEffect(() => {
        const check = () => {
            const profile = getActiveProfile();
            // Don't run on the profile picker / edit screens — wait
            // until the user has actually entered the app.  Kids
            // profiles skip the tour entirely (it'd confuse the
            // wee ones).
            if (!profile) return;
            if (profile.kids) return;
            const onProfilesRoute =
                location.pathname.startsWith('/profiles') ||
                location.pathname.startsWith('/kids/');
            if (onProfilesRoute) return;
            if (hasSeenOnboarding()) return;
            setOpen(true);
        };
        // First check (next tick so React Router has resolved).
        const t = setTimeout(check, 250);
        const onReplay = () => setOpen(true);
        const onProfileChange = () => setTimeout(check, 250);
        window.addEventListener('vesper:onboarding-replay', onReplay);
        window.addEventListener('vesper:profile-change', onProfileChange);
        return () => {
            clearTimeout(t);
            window.removeEventListener('vesper:onboarding-replay', onReplay);
            window.removeEventListener('vesper:profile-change', onProfileChange);
        };
    }, [location.pathname]);

    return <Onboarding open={open} onClose={() => setOpen(false)} />;
}

function App() {
    return (
        <div className="App">
            <ErrorBoundary>
                <ThemeProvider>
                    <Router>
                        <DeepLinkHandler />
                        <MobilePlatformRoot>
                            <DevModeBadge />
                            <NewEpisodeToast />
                            <AddToListModal />
                            <ReminderWatcher />
                            <NotifyHitWatcher />
                            <FeatureNudge />
                            <Routes>
                                <Route path="/profiles" element={<RequireProfile><ProfileSelect /></RequireProfile>} />
                                <Route path="/profiles/new" element={<RequireProfile><ProfileEdit /></RequireProfile>} />
                                <Route path="/profiles/edit/:id" element={<RequireProfile><ProfileEdit /></RequireProfile>} />
                                <Route path="/profiles/load" element={<RequireProfile><ProfileLoad /></RequireProfile>} />
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
                            {/* Auto-update gate re-enabled in v2.6.22.
                                Every device running v2.6.21+ will
                                check on launch + every 6 h, prompt
                                a one-tap install when a newer APK
                                is published to GitHub Releases.
                                Combined with the stable debug
                                keystore (bootstrap-keystore workflow)
                                future upgrades install in-place. */}
                            <UpdateGate />
                            <OnboardingGate />
                            <BootSplash />
                        </MobilePlatformRoot>
                    </Router>
                </ThemeProvider>
            </ErrorBoundary>
        </div>
    );
}

export default App;
