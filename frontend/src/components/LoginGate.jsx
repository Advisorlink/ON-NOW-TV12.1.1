/**
 * LoginGate — renders <LoginScreen /> when the user is not
 * authenticated.  When authenticated (or while the initial /me check
 * is in-flight against a cached token), it renders its children
 * untouched.
 *
 * Place this INSIDE <Router> so <LoginScreen> can call useNavigate().
 */
import React from 'react';
import { useAuth } from '@/contexts/AuthContext';
import LoginScreen from '@/components/LoginScreen';

export default function LoginGate({ children }) {
    const { status } = useAuth();
    if (status === 'guest') {
        return <LoginScreen />;
    }
    // 'authenticated' OR 'checking' — render the app.  The cached
    // token means a returning user never sees a flash of LoginScreen
    // between launches; if /me invalidates it later we'll demote to
    // 'guest' and the LoginScreen takes over.
    return children;
}
