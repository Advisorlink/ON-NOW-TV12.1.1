/**
 * AuthContext — lightweight global auth state for Vesper v2.
 *
 * Status values:
 *   'checking'      — initial mount, /api/auth/me in flight
 *   'authenticated' — `account` is populated
 *   'guest'         — no token / token rejected
 *
 * Exposes:
 *   { status, account, login(u,p), logout(), refresh() }
 *
 * Wire-up: `<AuthProvider>` once at the top of App.js.
 * Consumers: `const { status, account, logout } = useAuth();`
 */
import React from 'react';
import {
    getToken,
    getAccount,
    apiMe,
    apiLogin,
    apiLogout,
} from '@/lib/auth';

const AuthContext = React.createContext({
    status: 'checking',
    account: null,
    login: async () => {},
    logout: async () => {},
    refresh: async () => {},
});

export function AuthProvider({ children }) {
    // If we have a cached token + account in localStorage, assume
    // authenticated immediately so the login screen doesn't flash
    // between reloads.  We still verify against /me in the
    // background and demote to 'guest' if the token has been
    // revoked.
    const [status, setStatus] = React.useState(() =>
        getToken() ? 'authenticated' : 'guest',
    );
    const [account, setAccount] = React.useState(() => getAccount());

    const refresh = React.useCallback(async () => {
        const t = getToken();
        if (!t) {
            setStatus('guest');
            setAccount(null);
            return;
        }
        const acc = await apiMe();
        if (acc) {
            setStatus('authenticated');
            setAccount(acc);
        } else {
            setStatus('guest');
            setAccount(null);
        }
    }, []);

    // Initial verify + listen for cross-tab / cross-component changes.
    React.useEffect(() => {
        refresh();
        const onChange = () => refresh();
        window.addEventListener('vesper:auth-change', onChange);
        window.addEventListener('storage', onChange);
        return () => {
            window.removeEventListener('vesper:auth-change', onChange);
            window.removeEventListener('storage', onChange);
        };
    }, [refresh]);

    const login = React.useCallback(async (username, password) => {
        const data = await apiLogin(username, password);
        setStatus('authenticated');
        setAccount(data.account);
        return data;
    }, []);

    const logout = React.useCallback(async () => {
        await apiLogout();
        setStatus('guest');
        setAccount(null);
    }, []);

    const value = React.useMemo(
        () => ({ status, account, login, logout, refresh }),
        [status, account, login, logout, refresh],
    );

    return (
        <AuthContext.Provider value={value}>
            {children}
        </AuthContext.Provider>
    );
}

export function useAuth() {
    return React.useContext(AuthContext);
}
