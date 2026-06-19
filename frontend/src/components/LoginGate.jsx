/**
 * LoginGate — renders <LoginScreen /> when the user is not
 * authenticated.  When authenticated (or while the initial /me check
 * is in-flight against a cached token), it renders its children
 * untouched.
 *
 * Place this INSIDE <Router> so <LoginScreen> can call useNavigate().
 *
 * v2.10.58 — The Music app (Tunes APK + the `/music/*` routes in
 * the unified Vesper bundle) must be **completely independent** of
 * Vesper authentication.  User: "the music app shouldn't have
 * anything to do with Vesper apart from just the look itself".  We
 * bypass the gate for any path under `/music`, `/karaoke` (which is
 * a child of /music) so a brand-new Tunes APK install boots
 * straight into MusicHome without ever seeing the Vesper login or
 * profile picker.  Vesper's own routes still require sign-in.
 */
import React from 'react';
import { useLocation } from 'react-router-dom';
import { useAuth } from '@/contexts/AuthContext';
import LoginScreen from '@/components/LoginScreen';

export default function LoginGate({ children }) {
    const { status } = useAuth();
    const { pathname } = useLocation();
    // v2.10.58 — Music app is a standalone product (Tunes APK).
    // Skip the Vesper login gate for any `/music/*` route so the
    // user never sees the Sign In page while in the music app.
    if (pathname === '/music' || pathname.startsWith('/music/')) {
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
