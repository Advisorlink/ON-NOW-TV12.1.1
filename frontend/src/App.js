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
import V2AIResolve from '@/pages/V2AIResolve';
import Settings from '@/pages/Settings';
import Library from '@/pages/Library';
import ProfileSelect from '@/pages/ProfileSelect';
import ProfileEdit from '@/pages/ProfileEdit';
import ProfileLoad from '@/pages/ProfileLoad';
import KidsHome from '@/pages/KidsHome';
import KidsExitPin from '@/pages/KidsExitPin';
import KidsSetup from '@/pages/KidsSetup';
import KidsSettings from '@/pages/KidsSettings';
import WatchTogether from '@/pages/WatchTogether';
// v2.8.90 — ON NOW V2 Free-to-Air EPG page (Brisbane AU FTA).
import FreeToAir from '@/pages/FreeToAir';
import LiveTV from '@/pages/LiveTV';
// v2.8.127 — Sports Guide rebuilt as a native screen in V2 Live TV.
// The /sports React route is retired; users open it from the trophy
// icon in the V2 Live TV side rail now.
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
import { getActiveProfile, isKidsActive, getKidsConfig, isKidsApp } from '@/lib/profiles';
import { AVATARS } from '@/lib/avatars';
import ErrorBoundary from '@/components/ErrorBoundary';
import MobileBottomNav from '@/components/MobileBottomNav';
// v2.10.77 — UpdateGate import removed; in-app update prompt killed
// at user request, updates now flow ONLY through the Launcher.
import Onboarding, { hasSeenOnboarding } from '@/components/Onboarding';
import BootSplash from '@/components/BootSplash';
import { AuthProvider } from '@/contexts/AuthContext';
import LoginGate from '@/components/LoginGate';
import Person from '@/pages/Person';
// v2.8.43 — ON NOW TV TUNES (music app)
import MusicLayout from '@/pages/music/MusicLayout';
import MusicHome from '@/pages/music/MusicHome';
import MusicSearch from '@/pages/music/MusicSearch';
import MusicAlbum from '@/pages/music/MusicAlbum';
import MusicArtist from '@/pages/music/MusicArtist';
import RadioBrowse from '@/pages/music/RadioBrowse';
import PodcastBrowse from '@/pages/music/PodcastBrowse';
import PodcastDetail from '@/pages/music/PodcastDetail';
import MusicLibrary from '@/pages/music/MusicLibrary';
// v2.8.60 — V2 Karaoke + Australia radio
import AustraliaRadio from '@/pages/music/AustraliaRadio';
// v2.8.74 — New karaoke flow (full party experience with QR/guest
// joining + challenges + random singer mode).  Legacy `/music/karaoke/
// play/:trackId` deep-link still resolves via KaraokeLegacyStage.
import { KaraokeStage as KaraokeLegacyStage } from '@/pages/music/Karaoke';
import KaraokeHome from '@/pages/music/KaraokeHome';
import KaraokeSingYourOwn from '@/pages/music/KaraokeSingYourOwn';
import KaraokePartyPicker from '@/pages/music/KaraokePartyPicker';
import KaraokeFriendsLobby from '@/pages/music/KaraokeFriendsLobby';
import KaraokeGuestJoin from '@/pages/music/KaraokeGuestJoin';
import KaraokeChallenge from '@/pages/music/KaraokeChallenge';
import KaraokeUpNext from '@/pages/music/KaraokeUpNext';
import KaraokeStage from '@/pages/music/KaraokeStage';
import KaraokeDesignGallery from '@/pages/music/KaraokeDesignGallery';
import { YouTubeIFrameHost } from '@/components/music/YouTubeIFrameHost';
import { LogOut } from 'lucide-react';
import useIsMobile from '@/lib/useIsMobile';

// v2.8.64 — Globally-mounted YouTube IFrame Player host.  Renders
// the offscreen iframe target ONCE for the whole app so audio
// keeps playing across SPA route changes (including in/out of
// the KaraokeStage which lives OUTSIDE MusicLayout).
function GlobalYouTubeHost() {
    return <YouTubeIFrameHost />;
}
import useKidsBackGuard from '@/hooks/useKidsBackGuard';
import useKidsKioskGuard from '@/hooks/useKidsKioskGuard';
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
        let profileParam = params.get('profile');
        const ACTIVE_KEY = 'onnowtv-active-profile-v1';
        const LAST_NON_KIDS_KEY = 'onnowtv-last-non-kids-profile';
        const KIDS_CFG_KEY = 'onnowtv-kids-config-v1';

        // v2.10.63 — Detect the host APK so we can refuse to render
        // Kids UI inside Vesper.  Kids has been a STANDALONE APK
        // (tv.onnowtv.kids) since v2.9.2 — Vesper is the Movies/TV
        // app and must never enter Kids mode, no matter what stale
        // localStorage value lingers from the pre-v2.9.2 era when
        // Kids was a profile inside Vesper.  Stashed on window so
        // HomeRouter (and anything else that reads it) doesn't have
        // to repeat the OnNowTV.getHostPackage probe.
        const hostPackage = (() => {
            try {
                if (typeof window.OnNowTV?.getHostPackage === 'function') {
                    return String(window.OnNowTV.getHostPackage() || '');
                }
            } catch { /* not in a native shell */ }
            return '';
        })();
        window.__vesperHostPackage = hostPackage;
        const isVesperHost = hostPackage === 'tv.onnowtv.app';
        const isKidsHost   = hostPackage === 'tv.onnowtv.kids';

        // v2.10.71 — VESPER HARD-GUARD.  When the host APK is the
        // Movies/TV one (`tv.onnowtv.app`), the Kids context must
        // be impossible to enter, no matter what stale state the
        // device carries.  This runs at module-load BEFORE any
        // React component reads `__vesperBootProfileKids` or the
        // active-profile localStorage entries:
        //   • Strip `?profile=kids` from the URL synchronously.
        //   • Strip the equivalent `vesper_route` style query.
        //   • Treat any incoming `profile=kids` as if it never
        //     arrived (so `profileParam` below sees null).
        // The Movies/TV launcher tile fires a clean intent, but if
        // a legacy launcher or stale WebView lastUrl ever carried
        // the kids query through, this guard guarantees Vesper
        // still boots into its own UI.
        if (isVesperHost && profileParam === 'kids') {
            try {
                params.delete('profile');
                const search = params.toString();
                const cleanUrl = window.location.pathname +
                    (search ? `?${search}` : '') +
                    (window.location.hash || '');
                window.history.replaceState({}, '', cleanUrl);
            } catch { /* ignore */ }
            profileParam = null;
        }

        // v2.10.68 — Persist the Kids boot context as a module-load
        // flag.  `DeepLinkHandler` strips `?profile=kids` from the
        // URL via `history.replaceState` immediately on mount, so
        // any component that later re-renders and reads
        // `window.location.search` will see an EMPTY string and
        // mis-detect the host.  Stash the truth here once, before
        // React mounts, and let LoginScreen / profile helpers
        // consult this flag for the lifetime of the page.
        //
        // v2.10.71 — Vesper host is NEVER kids context regardless
        // of any URL or localStorage hint.
        window.__vesperBootProfileKids = isVesperHost
            ? false
            : (profileParam === 'kids' || isKidsHost);

        // v2.10.63 — Sweep ALL keys matching the active-profile
        // prefix (`onnowtv-active-profile-v1`, plus the per-account
        // suffixed variants like `:JOHN`) so the boot-time clear
        // actually clears them all.  The previous version cleared
        // only the base key, but `getActiveProfileId()` reads the
        // PER-ACCOUNT suffixed key when the user is signed in —
        // which meant the clear missed it and Vesper would keep
        // landing on Kids UI forever for logged-in operators.
        const findAllActiveKeys = () => {
            const out = [];
            try {
                for (let i = 0; i < localStorage.length; i++) {
                    const k = localStorage.key(i);
                    if (k && k.indexOf(ACTIVE_KEY) === 0) out.push(k);
                }
            } catch { /* storage disabled */ }
            return out;
        };

        // v2.10.67 — UNCONDITIONAL kids sweep.  The previous v2.10.63
        // fix gated this on `isVesperHost === true`, which only works
        // when the NEW Vesper APK with the `getHostPackage` bridge
        // is installed.  Users still on the v2.10.17-era Vesper APK
        // have NO bridge → hostPackage is '' → guard doesn't fire →
        // they keep landing in KidsHome despite our "fix" because
        // their account-suffixed localStorage still says `=kids`.
        //
        // New rule that works for ALL running APK versions:
        // unless this is an EXPLICIT kids context (`?profile=kids`
        // deep-link OR confirmed Kids host package), sweep every
        // profile key whose value is 'kids'.  The Kids APK always
        // boots with `?profile=kids` in its URL, so this only
        // strips stale state from Vesper / Tunes / FTA / browser.
        const isExplicitKidsContext = profileParam === 'kids' || isKidsHost;
        if (!isExplicitKidsContext) {
            try {
                for (const k of findAllActiveKeys()) {
                    if (localStorage.getItem(k) === 'kids') {
                        localStorage.removeItem(k);
                    }
                }
            } catch { /* ignore */ }
        }

        // v2.10.71 — VESPER hard-guard sweep.  When we're booting
        // inside the Movies/TV APK, also nuke any persisted kids
        // CONFIG (PIN, ratings) and the last-non-kids breadcrumb.
        // The Kids APK is fully standalone now — its config lives
        // in its OWN sandboxed WebView storage and Vesper has no
        // legitimate use for kids state on disk.  Nuking it ensures
        // a one-time clean-up for users updating from pre-v2.9.2
        // Vesper builds and prevents any future leak from turning
        // Vesper into a kids surface.
        if (isVesperHost) {
            try {
                localStorage.removeItem(KIDS_CFG_KEY);
                localStorage.removeItem(LAST_NON_KIDS_KEY);
                // Per-account-suffixed kids configs too.
                const toRemove = [];
                for (let i = 0; i < localStorage.length; i++) {
                    const k = localStorage.key(i);
                    if (!k) continue;
                    if (k.indexOf(KIDS_CFG_KEY) === 0) toRemove.push(k);
                }
                toRemove.forEach((k) => localStorage.removeItem(k));
            } catch { /* ignore */ }
        }


        // v2.8.23 — V2 AI deep-link.  The Launcher's V2 AI voice
        // assistant opens Vesper with `?v2ai=<title>&type=movie|series&autoplay=1`.
        // Rewrite this to the SPA route `/v2ai-play?title=...&type=...`
        // BEFORE React Router mounts so the user lands on the
        // V2AIResolve page that handles the search → resolve → play
        // flow.  Works under both BrowserRouter (preview) and
        // HashRouter (APK with file://).
        const v2aiTitle = params.get('v2ai');
        if (v2aiTitle) {
            const v2aiType = (params.get('type') || 'movie').toLowerCase();
            const target =
                `/v2ai-play?title=${encodeURIComponent(v2aiTitle)}` +
                `&type=${encodeURIComponent(v2aiType)}`;
            // HashRouter detection — same logic as everywhere else
            // in this file: under `file://` we run hash routing.
            const isFile = window.location.protocol === 'file:';
            if (isFile) {
                window.location.hash = '#' + target;
                // Also strip the v2ai query so refresh doesn't loop.
                history.replaceState(
                    null, '',
                    window.location.pathname + window.location.hash,
                );
            } else {
                history.replaceState(null, '', target);
            }
            // CRITICAL: V2 AI deep-links carry no `?profile=` — they
            // expect to land on whatever profile the user already had
            // active.  Skip the v2.8.5 "clear active profile on cold
            // boot" logic so the V2AIResolve page isn't bounced into
            // /profiles right before it can run its search.
            window.__vesperSkipProfileClear = true;
        }

        // v2.8.4 — Kids PIN lockdown.  If the user is currently in
        // Kids mode AND has a PIN set, IGNORE any `profile=exit-kids`
        // deep-link from the launcher — the kid must enter the PIN
        // via the in-app gate (`/kids/exit-pin`).  Without this,
        // pressing Home, opening the Movies / TV tile on the launcher,
        // and returning to Vesper would silently drop the kid out of
        // Kids mode with no security check.
        const cur = (() => {
            try { return localStorage.getItem(ACTIVE_KEY); }
            catch { return null; }
        })();
        const kidsPin = (() => {
            try {
                const raw = localStorage.getItem(KIDS_CFG_KEY);
                if (!raw) return '';
                const parsed = JSON.parse(raw);
                return (parsed && parsed.pin) || '';
            } catch { return ''; }
        })();
        const lockedInKids = cur === 'kids' && kidsPin && kidsPin.length === 4;

        if (profileParam === 'kids') {
            // Remember the user's adult profile BEFORE we switch to
            // Kids — so a later Movies/TV tap restores them, not
            // dump them on the profile picker.
            if (cur && cur !== 'kids') {
                localStorage.setItem(LAST_NON_KIDS_KEY, cur);
            }
            localStorage.setItem(ACTIVE_KEY, 'kids');
        } else if (profileParam === 'exit-kids') {
            if (lockedInKids) {
                // PIN-protected — refuse the silent switch.  The
                // route handler below will strip the query so a
                // refresh doesn't keep re-trying.  The kid stays in
                // Kids mode and must hit "Exit Kids" → enter PIN.
            } else {
                const previous = localStorage.getItem(LAST_NON_KIDS_KEY);
                if (previous) {
                    localStorage.setItem(ACTIVE_KEY, previous);
                } else {
                    // No adult profile remembered → clear so the
                    // ProfileSelect picker takes over.
                    localStorage.removeItem(ACTIVE_KEY);
                }
            }
        } else {
            // v2.8.5 — Per user spec: every time Vesper boots without
            // a `?profile=…` deep-link, ALWAYS land on the profile
            // picker.  Previously the last-used profile auto-resumed
            // (sticky session) — but the user explicitly wants to
            // see the picker on every cold boot so a child can never
            // sneak into a grown-up profile, and grown-ups always
            // get a fresh choice.  The Kids tile on the launcher
            // still bypasses this via `?profile=kids`.
            //
            // v2.8.23 — V2 AI deep-links carry no `?profile=` either
            // (the user is invoking the assistant FROM the launcher,
            // not switching profiles), so honour the in-flight
            // skip-flag set up by the v2ai handler above.
            //
            // v2.10.63 — Sweep ALL profile keys (base + per-account
            // suffixed) instead of just the base.  Account-scoped
            // suffixed keys were surviving the clear and causing
            // Vesper to land on Kids UI forever for signed-in users.
            if (!window.__vesperSkipProfileClear) {
                try {
                    for (const k of findAllActiveKeys()) {
                        localStorage.removeItem(k);
                    }
                } catch { /* ignore */ }
            }
        }
    } catch { /* ignore — malformed URL / disabled storage */ }
}

const Router =
    typeof window !== 'undefined' && (
        window.location.protocol === 'file:' ||
        // v2.8.72 — Tunes APK loads via WebViewAssetLoader at
        // `https://appassets.androidplatform.net/assets/web/index.html`.
        // BrowserRouter would try to interpret `/assets/web/index.html`
        // as the route which breaks navigation; HashRouter routes on
        // `#/music` etc. correctly regardless of the URL path.
        window.location.hostname === 'appassets.androidplatform.net'
    )
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
        // v2.8.4 — First-time Kids activation: if no PIN is set yet,
        // FORCE the user through `/kids/setup` before any content
        // renders.  The setup wizard writes the PIN + content
        // ratings, then redirects to "/".  This means a kid can
        // never reach the Movies / TV browse routes until a
        // responsible adult has configured the parental controls.
        const kidsCfg = getKidsConfig();
        const needsSetup = !kidsCfg.pin || kidsCfg.pin.length !== 4;
        if (needsSetup && location.pathname !== '/kids/setup') {
            return <Navigate to="/kids/setup" replace />;
        }
        const allowedKids = [
            '/',
            '/play',
            '/title/',
            '/search',
            '/resolve/',
            '/kids/exit-pin',
            '/kids/settings',
            '/kids/setup',
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
    // v2.10.68 — DENY-BY-DEFAULT kids guard.  Kids UI renders only
    // when the module-load boot flag confirms we booted in Kids
    // context (`?profile=kids` deep-link or Kids APK host package).
    // The boot flag is captured BEFORE DeepLinkHandler strips the
    // query string, so it remains accurate for the lifetime of the
    // page.  Any other context (Vesper TV, Tunes, FTA, plain
    // browser) gets bounced to the profile picker even if stale
    // localStorage still says active=kids.
    if (isKidsActive() && typeof window !== 'undefined') {
        if (window.__vesperBootProfileKids !== true) {
            try {
                for (let i = localStorage.length - 1; i >= 0; i--) {
                    const k = localStorage.key(i);
                    if (k && k.indexOf('onnowtv-active-profile-v1') === 0 &&
                        localStorage.getItem(k) === 'kids') {
                        localStorage.removeItem(k);
                    }
                }
            } catch { /* ignore */ }
            return <Navigate to="/profiles" replace />;
        }
    }
    return isKidsActive() ? <KidsHome /> : <Home />;
}

/* Routes where the mobile bottom-nav should be hidden — full-bleed
   experiences (player, profile gates, watch party).

   v2.8.68 — `/music` added: the ON NOW Tunes app is a STANDALONE
   product with its own bottom tab bar (Home / Search / Karaoke /
   Radio / Library) rendered inside MusicLayout.  Vesper's
   MobileBottomNav (Home/Search/Live/Library/More) must not bleed
   into the music app — they're conceptually two different
   experiences and the user is sideloading them as separate APKs. */
const MOBILE_NAV_HIDDEN_PREFIXES = [
    '/play',
    '/profiles',
    '/kids/exit-pin',
    '/kids/setup',
    '/watch-together',
    '/resolve/',
    '/music',
];

function MobilePlatformRoot({ children }) {
    const isMobile = useIsMobile();
    const location = useLocation();
    // v2.8.9 — Global Kids sandbox back-button guard.  Active on
    // every route (the hook itself bails out when Kids isn't
    // active or no PIN is set).  This means clicking a movie tile
    // inside Kids → Detail page → hardware Back → guard intercepts
    // and forces the PIN gate instead of letting the user pop
    // backwards into an adult catalogue.
    useKidsBackGuard();
    // v2.8.78 — Kiosk-grade HOME-button guard.  When kids+PIN is
    // active, intercept any route escape and any
    // visibility/focus-resume event so the user can't get out of
    // the sandbox by pressing HOME on the remote.
    useKidsKioskGuard();
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
       the PIN gate itself (the gate IS the exit).

       v2.8.68 — Also hidden on /music routes: the music app is a
       standalone product without a Kids profile sandbox, so the
       Exit pill from Vesper would be irrelevant + confusing. */
    const kidsActive = isMobile && isKidsActive() &&
        !location.pathname.startsWith('/kids/exit-pin') &&
        !location.pathname.startsWith('/music');

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

function VesperOnlyChrome() {
    // v2.8.68 — Vesper's app-wide chrome (toasts, modal pickers,
    // dev badges, reminders, feature nudges) should NOT appear in
    // the standalone ON NOW Tunes app.  The music app is a separate
    // product with its own UX; bleeding Vesper toasts into it makes
    // it feel like one half-broken app instead of two clean ones.
    const location = useLocation();
    if (location.pathname.startsWith('/music')) return null;
    // v2.10.69 — Kids APK is a separate product too — no Vesper
    // chrome at all (no toasts, no nudges, no reminders, no dev
    // badge).  The user explicitly said "the Kids section is the
    // Kids section… that's it".
    if (isKidsApp()) return null;
    // v2.10.5 — Suppress all notifications + nudges while a Kids
    // profile is active.  Kids should never see "new episode of
    // The Boys is out" toasts, addon hit alerts, or growth-style
    // feature nudges.  ReminderWatcher (calendar EPG reminders)
    // also gets silenced to keep the experience distraction-free.
    if (isKidsActive()) {
        return <DevModeBadge />;
    }
    return (
        <>
            <DevModeBadge />
            <NewEpisodeToast />
            <AddToListModal />
            <ReminderWatcher />
            <NotifyHitWatcher />
            <FeatureNudge />
        </>
    );
}

/**
 * v2.10.69 — Standalone Kids APK route tree.  Mounted whenever the
 * page boots in Kids context (`window.__vesperBootProfileKids ===
 * true`, set by the App.js module-load IIFE before React mounts).
 *
 * Contract per user spec:
 *   • NO Vesper login screen.  NO shared credentials.  NO JWT.
 *   • NO profile picker.  Kids is the only product on this APK.
 *   • First launch (PIN not configured) → /kids/setup (PIN +
 *     content rating wizard).
 *   • Every subsequent launch → KidsHome at "/".
 *   • Exit is gated by /kids/exit-pin — correct PIN calls the
 *     native bridge to finish() back to the launcher.  Wrong PIN
 *     just clears and re-prompts.
 *   • Only the routes a child legitimately needs: Home, Search,
 *     Detail, Player, Resolve, Settings, Setup, Exit-PIN.
 *     Everything else (Sources, Library, /music, /fta, /live-tv,
 *     /watch-together, /profiles, /v2ai-play, /networks, /person)
 *     is hard-removed from the route table so a curious kid
 *     can't type their way out.  Unknown paths → "/".
 */
function KidsAppRoutes() {
    // v2.10.69 — Re-read the kids config on every render AND
    // subscribe to its change event so the "first-run setup ⇒
    // KidsHome" hand-off is reactive.  Without the subscription
    // the `<Routes>` inside us re-renders on every navigation but
    // our own function body doesn't, so `pinConfigured` stays
    // false and `<Navigate to="/kids/setup">` loops back to setup
    // forever even after the PIN is saved.
    const [cfg, setCfg] = useState(() => getKidsConfig());
    useEffect(() => {
        const sync = () => setCfg(getKidsConfig());
        window.addEventListener('vesper:kids-config-change', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('vesper:kids-config-change', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);
    const pinConfigured = !!(cfg.pin && cfg.pin.length === 4);
    return (
        <Routes>
            <Route
                path="/"
                element={
                    pinConfigured
                        ? <KidsHome />
                        : <Navigate to="/kids/setup" replace />
                }
            />
            <Route path="/kids/setup"    element={<KidsSetup />} />
            <Route path="/kids/settings" element={<KidsSettings />} />
            <Route path="/kids/exit-pin" element={<KidsExitPin />} />
            <Route path="/search"        element={<Search />} />
            <Route path="/title/:type/:id" element={<Detail />} />
            <Route path="/title/:id"     element={<Detail />} />
            <Route path="/play"          element={<Player />} />
            <Route path="/resolve/:type/:id" element={<Resolve />} />
            {/* Catch-all: anything else bounces back to KidsHome */}
            <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
    );
}

function App() {
    // v2.10.83 — Install the global focus-bookmark listener ONCE at
    // app boot.  Records the data-testid + scroll positions of any
    // [data-focusable] tile that gets clicked or Enter-pressed, so
    // useFocusRestore on Home / Network / Library / Catalog / Person
    // can return the highlight to that exact tile when the user
    // presses BACK from the detail page.
    React.useEffect(() => {
        try {
            // Lazy-imported to keep this file's import graph stable.
            // eslint-disable-next-line global-require
            const { installFocusBookmarkListener } = require('@/hooks/useFocusRestore');
            installFocusBookmarkListener();
        } catch { /* hook lookup failed — non-fatal */ }
    }, []);
    return (
        <div className="App">
            <ErrorBoundary>
                <ThemeProvider>
                    <Router>
                        <AuthProvider>
                            <DeepLinkHandler />
                            <MobilePlatformRoot>
                                <LoginGate>
                                    <VesperOnlyChrome />
                                    {isKidsApp() ? (
                                        <KidsAppRoutes />
                                    ) : (
                                        <Routes>
                                <Route path="/profiles" element={<RequireProfile><ProfileSelect /></RequireProfile>} />
                                <Route path="/profiles/new" element={<RequireProfile><ProfileEdit /></RequireProfile>} />
                                <Route path="/profiles/edit/:id" element={<RequireProfile><ProfileEdit /></RequireProfile>} />
                                <Route path="/profiles/load" element={<RequireProfile><ProfileLoad /></RequireProfile>} />
                                <Route path="/kids/exit-pin" element={<RequireProfile><KidsExitPin /></RequireProfile>} />
                                <Route path="/kids/setup" element={<RequireProfile><KidsSetup /></RequireProfile>} />
                                <Route path="/kids/settings" element={<RequireProfile><KidsSettings /></RequireProfile>} />

                                <Route path="/" element={<RequireProfile><HomeRouter /></RequireProfile>} />
                                <Route path="/sources" element={<RequireProfile><Sources /></RequireProfile>} />
                                <Route path="/search" element={<RequireProfile><Search /></RequireProfile>} />
                                <Route path="/networks/:slug" element={<RequireProfile><Network /></RequireProfile>} />
                                <Route path="/resolve/:type/:id" element={<RequireProfile><Resolve /></RequireProfile>} />
                                <Route path="/v2ai-play" element={<RequireProfile><V2AIResolve /></RequireProfile>} />
                                <Route path="/library" element={<RequireProfile><Library /></RequireProfile>} />
                                {/* v2.8.90 — Free-to-Air EPG (will move to a
                                    standalone APK; for now lives at /fta inside
                                    the existing Vesper React app). */}
                                <Route path="/fta" element={<FreeToAir />} />
                                <Route path="/settings" element={<RequireProfile><Settings /></RequireProfile>} />
                                <Route path="/title/:type/:id" element={<RequireProfile><Detail /></RequireProfile>} />
                                <Route path="/title/:id" element={<RequireProfile><Detail /></RequireProfile>} />
                                <Route path="/play" element={<RequireProfile><Player /></RequireProfile>} />
                                <Route path="/watch-together" element={<RequireProfile><WatchTogether /></RequireProfile>} />
                                <Route path="/live-tv" element={<RequireProfile><LiveTV /></RequireProfile>} />
                                <Route path="/person/:tmdbId" element={<RequireProfile><Person /></RequireProfile>} />

                                {/* v2.8.43 — ON NOW TV TUNES (music app).
                                    Routes are NOT wrapped in <RequireProfile> because
                                    the Tunes APK boots directly here and has its
                                    own first-run flow (no Vesper profile picker). */}
                                <Route path="/music" element={<MusicLayout />}>
                                    <Route index             element={<MusicHome />} />
                                    <Route path="search"     element={<MusicSearch />} />
                                    <Route path="radio"      element={<RadioBrowse />} />
                                    <Route path="radio/au"   element={<AustraliaRadio />} />
                                    <Route path="podcasts"   element={<PodcastBrowse />} />
                                    <Route path="library"    element={<MusicLibrary />} />
                                    {/* v2.8.74 — New karaoke flow.  KaraokeHome
                                        is the 4-tile entry; sub-routes below
                                        cover every screen in the user's
                                        supplied design pack. */}
                                    <Route path="karaoke"                      element={<KaraokeHome />} />
                                    <Route path="karaoke/sing"                 element={<KaraokeSingYourOwn />} />
                                    <Route path="karaoke/party"                element={<KaraokePartyPicker />} />
                                    <Route path="karaoke/party/friends"        element={<KaraokeFriendsLobby />} />
                                    <Route path="karaoke/party/stage"          element={<KaraokeStage />} />
                                    <Route path="karaoke/up-next"              element={<KaraokeUpNext />} />
                                    <Route path="karaoke/challenge"            element={<KaraokeChallenge />} />
                                    <Route path="karaoke/designs"              element={<KaraokeDesignGallery />} />
                                    <Route path="album/:id"  element={<MusicAlbum />} />
                                    <Route path="artist/:id" element={<MusicArtist />} />
                                    <Route path="podcast/:feedUrl" element={<PodcastDetail />} />
                                </Route>
                                {/* v2.8.74 — Mobile guest join page (outside
                                    music layout — no sidebar/queue chrome,
                                    just the join + song-picker UI).  This
                                    is what the QR code on the TV resolves
                                    to: `https://onnowtv.duckdns.org/karaoke/
                                    join/KARAOKE-1234`. */}
                                <Route path="/karaoke/join/:code" element={<KaraokeGuestJoin />} />
                                {/* v2.8.60 — Legacy karaoke deep link.  Still
                                    resolves so external links from older
                                    versions of the app keep working — the
                                    component just bounces the user back to
                                    /music/karaoke and triggers playback. */}
                                <Route path="/music/karaoke/play/:trackId" element={<KaraokeLegacyStage />} />
                            </Routes>
                                    )}
                            {/* v2.8.64 — YouTube IFrame Player host
                                lifted from MusicLayout to App level
                                so it stays mounted for /music/karaoke/
                                play/* (which lives OUTSIDE MusicLayout
                                to take the full screen).  Without
                                this, the music engine had no iframe
                                target on the Karaoke stage and audio
                                stayed at 0:00. */}
                            <GlobalYouTubeHost />
                            {/* v2.10.77 — In-app UpdateGate REMOVED at
                                user request.  Updates are now ONLY
                                driven by the Launcher (tile UPDATE
                                pill).  Vesper / Movies opens straight
                                into content with no "Update available"
                                popup ever.  Component file kept on
                                disk for the native progress bridge
                                wiring (used by Launcher's APK install
                                flow), but no longer mounted in the
                                React tree. */}
                            <OnboardingGate />
                            <BootSplash />
                                </LoginGate>
                            </MobilePlatformRoot>
                        </AuthProvider>
                    </Router>
                </ThemeProvider>
            </ErrorBoundary>
        </div>
    );
}

export default App;
