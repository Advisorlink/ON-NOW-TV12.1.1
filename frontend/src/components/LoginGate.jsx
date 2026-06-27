/**
 * LoginGate — renders <LoginScreen /> when the user is not
 * authenticated.  When authenticated (or while the initial /me check
 * is in-flight against a cached token), it renders its children
 * untouched.
 *
 * Place this INSIDE <Router> so <LoginScreen> can call useNavigate().
 *
 * v2.10.69 — Standalone Kids APK COMPLETELY bypasses this gate.
 * The Kids app is its own product with its own PIN flow (set by
 * KidsSetup, enforced by KidsExitPin); it shares NOTHING with the
 * Vesper auth system.  No shared login screen, no Vesper credentials,
 * no /api/auth/login round-trip.  The KidsHome page only talks to
 * the public TMDB-backed `/api/tmdb/kids/*` endpoints which require
 * no JWT.
 */
import React from 'react';
import { useAuth } from '@/contexts/AuthContext';
import LoginScreen from '@/components/LoginScreen';
import { isKidsApp } from '@/lib/profiles';

export default function LoginGate({ children }) {
    const { status } = useAuth();
    // Kids APK: skip the gate entirely.
    if (isKidsApp()) return children;
    if (status === 'guest') {
        return <LoginScreen />;
    }
    // 'authenticated' OR 'checking' — render the app.  The cached
    // token means a returning user never sees a flash of LoginScreen
    // between launches; if /me invalidates it later we'll demote to
    // 'guest' and the LoginScreen takes over.
    return children;
}
