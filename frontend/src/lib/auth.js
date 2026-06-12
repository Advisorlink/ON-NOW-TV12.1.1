/**
 * Vesper v2 — Auth client helpers.
 *
 *   getToken()        → reads the JWT from localStorage (or null)
 *   setToken(t)       → persists + broadcasts a `vesper:auth-change` event
 *   clearToken()      → clears + broadcasts
 *   apiLogin(u, p)    → POST /api/auth/login, persists token on success
 *   apiMe()           → GET /api/auth/me using the current token
 *   apiLogout()       → POST /api/auth/logout, clears local state
 *   getAccount()      → reads the cached account from localStorage
 *
 * The token lives in `localStorage` under `vesper-auth-token-v1` and the
 * cached account under `vesper-auth-account-v1`.  All other modules that
 * need to hit the API should call `authHeader()` to grab the bearer.
 */

const API = process.env.REACT_APP_BACKEND_URL;
const TOKEN_KEY = 'vesper-auth-token-v1';
const ACCOUNT_KEY = 'vesper-auth-account-v1';

function _broadcast() {
    try {
        window.dispatchEvent(new Event('vesper:auth-change'));
    } catch { /* ignore */ }
}

export function getToken() {
    try { return localStorage.getItem(TOKEN_KEY) || null; } catch { return null; }
}

export function setToken(token) {
    try {
        if (token) localStorage.setItem(TOKEN_KEY, token);
        else localStorage.removeItem(TOKEN_KEY);
    } catch { /* ignore */ }
    _broadcast();
}

export function getAccount() {
    try {
        const raw = localStorage.getItem(ACCOUNT_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch { return null; }
}

export function setAccount(account) {
    try {
        if (account) localStorage.setItem(ACCOUNT_KEY, JSON.stringify(account));
        else localStorage.removeItem(ACCOUNT_KEY);
    } catch { /* ignore */ }
    _broadcast();
}

export function clearToken() {
    setToken(null);
    setAccount(null);
}

export function authHeader() {
    const t = getToken();
    return t ? { Authorization: `Bearer ${t}` } : {};
}

/* ----- API calls ---------------------------------------------------- */

function _formatApiError(detail) {
    if (detail == null) return 'Something went wrong. Please try again.';
    if (typeof detail === 'string') return detail;
    if (Array.isArray(detail)) {
        return detail
            .map((e) => (e && typeof e.msg === 'string' ? e.msg : JSON.stringify(e)))
            .filter(Boolean)
            .join(' ');
    }
    if (detail && typeof detail.msg === 'string') return detail.msg;
    return String(detail);
}

export async function apiLogin(username, password) {
    const res = await fetch(`${API}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
        const msg = _formatApiError(data.detail) || `HTTP ${res.status}`;
        const err = new Error(msg);
        err.status = res.status;
        throw err;
    }
    setToken(data.access_token);
    setAccount(data.account);
    return data;
}

export async function apiMe() {
    const t = getToken();
    if (!t) return null;
    const res = await fetch(`${API}/api/auth/me`, {
        headers: { Authorization: `Bearer ${t}` },
    });
    if (res.status === 401) {
        clearToken();
        return null;
    }
    if (!res.ok) return null;
    const data = await res.json().catch(() => null);
    if (data?.account) setAccount(data.account);
    return data?.account || null;
}

export async function apiLogout() {
    const t = getToken();
    if (t) {
        try {
            await fetch(`${API}/api/auth/logout`, {
                method: 'POST',
                headers: { Authorization: `Bearer ${t}` },
            });
        } catch { /* ignore */ }
    }
    clearToken();
}
