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
import {
    enableVesperCloudSync,
    disableVesperCloudSync,
    resumeVesperCloudSync,
    pullOnce as pullCloudSnapshot,
} from '@/lib/vesperCloudSync';
import { isRestorableSnapshot } from '@/lib/profileBackup';

const AuthContext = React.createContext({
    status: 'checking',
    account: null,
    login: async () => {},
    logout: async () => {},
    refresh: async () => {},
    cloudRestore: { snapshot: null, dismiss: () => {} },
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
    // v2.16.18 — Cloud-sync restore prompt.  Populated after a
    // successful `login()` (or on-boot resume of an authenticated
    // session that hasn't been offered restore yet) when the server
    // has a non-empty snapshot for this account.  Cleared once the
    // user picks Restore or Start-fresh.
    const [cloudSnapshot, setCloudSnapshot] = React.useState(null);
    // v2.16.23 — Set by `login()` for its full lifetime so the
    // status-based sync useEffect can defer to it.  A `setStatus
    // ('authenticated')` inside login() otherwise races the effect,
    // which was calling `resumeVesperCloudSync()` mid-pull.
    const loginInProgressRef = React.useRef(false);

    const refresh = React.useCallback(async () => {
        const t = getToken();
        if (!t) {
            setStatus('guest');
            setAccount(null);
            return;
        }
        const acc = await apiMe();
        // Critical race-condition guard: if the token was cleared
        // (e.g. logout) while /me was in flight, do NOT re-assert
        // 'authenticated' from this stale request.
        if (!getToken()) {
            setStatus('guest');
            setAccount(null);
            return;
        }
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
        loginInProgressRef.current = true;
        try {
            const data = await apiLogin(username, password);
            setStatus('authenticated');
            setAccount(data.account);
            /* Profile storage is namespaced per-account; broadcast so
             * any mounted UI (SideNav, ProfileSelect, Home) re-reads
             * the freshly-scoped list for the new user. */
            try {
                window.dispatchEvent(new CustomEvent('vesper:profile-change'));
            } catch { /* ignore */ }
            // v2.16.18 — Turn on the cloud-sync loop for this account
            // and check for a restorable snapshot in the background.
            // Result renders <CloudRestoreDialog> via the value below.
            // v2.16.23 — Push is SUSPENDED until the user resolves the
            // dialog.  If no restorable snapshot exists we resume
            // immediately so ambient writes still sync.  The boot-time
            // useEffect below detects `loginInProgressRef.current` and
            // does NOT resume for us — critical, otherwise a re-render
            // triggered by setStatus above would run enable+resume in
            // parallel with our still-pending pull, opening the exact
            // "3 profiles turn into 1" race we're trying to close.
            try {
                enableVesperCloudSync();
                const snap = await pullCloudSnapshot();
                if (snap && isRestorableSnapshot(snap.data)) {
                    setCloudSnapshot(snap);
                    // Do NOT resume yet — dismissCloudRestore /
                    // <CloudRestoreDialog>'s Restore path will resume.
                    // Restore path reloads the page → fresh boot
                    // useEffect resumes cleanly on the next mount.
                } else {
                    resumeVesperCloudSync();
                }
            } catch {
                resumeVesperCloudSync();
            }
            return data;
        } finally {
            loginInProgressRef.current = false;
        }
    }, []);

    const logout = React.useCallback(async () => {
        await apiLogout();
        setStatus('guest');
        setAccount(null);
        setCloudSnapshot(null);
        disableVesperCloudSync();
        try {
            window.dispatchEvent(new CustomEvent('vesper:profile-change'));
        } catch { /* ignore */ }
    }, []);

    // v2.16.18 — On boot, if we already have a valid token (returning
    // user) turn cloud-sync back on so their next Library / Continue
    // Watching / theme edit pushes silently.  We deliberately DO NOT
    // pull-and-prompt on every refresh: the restore dialog is a
    // one-time-per-fresh-install thing.  It fires on explicit login
    // only.
    // v2.16.23 — Boot-time resume: no dialog will show for this
    // path, so we immediately resume pushes as well.  BUT — do not
    // resume mid-login: the login() coroutine owns suspend/resume
    // during its own lifetime and will race us otherwise.
    React.useEffect(() => {
        if (loginInProgressRef.current) return;
        if (status === 'authenticated') {
            enableVesperCloudSync();
            resumeVesperCloudSync();
        } else if (status === 'guest') {
            disableVesperCloudSync();
        }
    }, [status]);

    const dismissCloudRestore = React.useCallback(() => {
        setCloudSnapshot(null);
        // v2.16.23 — Resume push loop now that the user has picked
        // (or dismissed) the restore prompt.
        resumeVesperCloudSync();
    }, []);

    const value = React.useMemo(
        () => ({
            status,
            account,
            login,
            logout,
            refresh,
            cloudRestore: {
                snapshot: cloudSnapshot,
                dismiss: dismissCloudRestore,
            },
        }),
        [status, account, login, logout, refresh, cloudSnapshot, dismissCloudRestore],
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
