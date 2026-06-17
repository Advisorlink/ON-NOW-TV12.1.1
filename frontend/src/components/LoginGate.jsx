/**
 * LoginGate — renders <LoginScreen /> when the user is not
 * authenticated.  When authenticated (or while the initial /me check
 * is in-flight against a cached token), it renders its children
 * untouched.
 *
 * v2.10.35 — Music is now a STANDALONE section.  Per user request,
 * tapping the Music tile on the launcher must never show the
 * Vesper login.  Music has its own first-run experience and uses
 * YouTube under the hood — we explain that explicitly inside the
 * Music app instead of hijacking the entry path with Vesper auth.
 * Any URL beginning `/music/...` bypasses the gate completely:
 * children are rendered regardless of `status`.
 *
 * Place this INSIDE <Router> so <LoginScreen> can call useNavigate()
 * AND so we can call useLocation() to gate by route.
 */
import React from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '@/contexts/AuthContext';
import LoginScreen from '@/components/LoginScreen';

/** URL prefixes that NEVER require Vesper auth.  Add new entries
 *  here when a sub-app (Music, Kids, etc.) is split off from the
 *  Vesper main app's account system. */
const PUBLIC_PREFIXES = ['/music'];

export default function LoginGate({ children }) {
    const { status } = useAuth();
    const location = useLocation();

    const isPublic = PUBLIC_PREFIXES.some((p) =>
        location.pathname === p || location.pathname.startsWith(`${p}/`),
    );

    if (isPublic) {
        // Music app (and any future siblings) — render the routes
        // straight away.  No Vesper login screen, no flicker, no
        // "Welcome back" copy.  Each public sub-app shows its own
        // onboarding inside its own layout.
        return children;
    }

    if (status === 'guest') {
        return <LoginScreen />;
    }
    // 'authenticated' OR 'checking' — render the app.  The cached
    // token means a returning user never sees a flash of LoginScreen
    // between launches; if /me invalidates it later we'll demote to
    // 'guest' and the LoginScreen takes over.
    return children;
}
