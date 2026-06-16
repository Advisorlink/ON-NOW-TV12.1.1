/**
 * LoginScreen — premium TV / 16:9 sign-in surface for Vesper v2.
 *
 * Displays only `username` + `password` fields (no DNS).  On success
 * AuthContext updates → LoginGate hands control back to the router →
 * user lands on /profiles.
 *
 * Design language matches the rest of the app:
 *   • Cinematic dark backdrop with cyan glow halo
 *   • Glass-morphism login card centred on the right
 *   • Monospace eyebrow + display title
 *   • D-pad-friendly focus rings via useSpatialFocus
 */
import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, Lock, User, LogIn, Eye, EyeOff, AlertCircle, Stethoscope } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAuth } from '@/contexts/AuthContext';

export default function LoginScreen() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { login } = useAuth();

    const [username, setUsername] = React.useState('');
    const [password, setPassword] = React.useState('');
    const [showPass, setShowPass] = React.useState(false);
    const [busy, setBusy] = React.useState(false);
    const [err, setErr] = React.useState('');
    const userRef = React.useRef(null);

    // Autofocus username on mount.
    React.useEffect(() => {
        const t = setTimeout(() => {
            try { userRef.current?.focus(); } catch { /* ignore */ }
        }, 220);
        return () => clearTimeout(t);
    }, []);

    const submit = React.useCallback(async (e) => {
        e?.preventDefault?.();
        if (busy) return;
        const u = username.trim();
        const p = password;
        if (!u || !p) {
            setErr('Enter your username and password to continue');
            return;
        }
        setBusy(true);
        setErr('');
        try {
            await login(u, p);
            // AuthContext flips to "authenticated" → LoginGate unmounts.
            // We also navigate to /profiles explicitly so the URL is
            // clean instead of whatever deep link the user landed on.
            navigate('/profiles', { replace: true });
        } catch (ex) {
            setErr(ex.message || 'Login failed');
        } finally {
            setBusy(false);
        }
    }, [busy, username, password, login, navigate]);

    return (
        <div
            data-testid="login-screen"
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 1000,
                background: 'var(--vesper-bg-0, #050810)',
                color: 'var(--vesper-text, #e8eef8)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                overflow: 'hidden',
            }}
        >
            <BackdropArt />

            {/* Centered glass card */}
            <div
                style={{
                    position: 'relative',
                    zIndex: 2,
                    width: 'min(560px, 92vw)',
                    maxHeight: '94vh',
                    padding: 'clamp(28px, 4vw, 48px)',
                    background:
                        'linear-gradient(160deg, rgba(20,30,60,0.62) 0%, rgba(8,14,32,0.85) 100%)',
                    border: '1px solid rgba(93,200,255,0.22)',
                    borderRadius: 28,
                    backdropFilter: 'blur(22px) saturate(140%)',
                    WebkitBackdropFilter: 'blur(22px) saturate(140%)',
                    boxShadow:
                        '0 30px 80px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.06), 0 0 60px rgba(93,200,255,0.12)',
                }}
            >
                <Header />

                <form onSubmit={submit} style={{ marginTop: 28 }}>
                    {/* Username */}
                    <Field
                        icon={User}
                        label="Username"
                        testid="login-username"
                    >
                        <input
                            ref={userRef}
                            data-testid="login-username-input"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            type="text"
                            autoComplete="username"
                            autoCapitalize="none"
                            autoCorrect="off"
                            spellCheck={false}
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            placeholder="Enter your username"
                            style={inputStyle}
                        />
                    </Field>

                    {/* Password with show/hide toggle */}
                    <Field
                        icon={Lock}
                        label="Password"
                        testid="login-password"
                        trailing={
                            <button
                                type="button"
                                data-testid="login-toggle-show"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => setShowPass((v) => !v)}
                                style={{
                                    background: 'transparent',
                                    border: 'none',
                                    color: 'var(--vesper-text-2, rgba(255,255,255,0.6))',
                                    padding: 6,
                                    cursor: 'pointer',
                                    display: 'inline-flex',
                                    alignItems: 'center',
                                }}
                                aria-label={showPass ? 'Hide password' : 'Show password'}
                            >
                                {showPass ? <EyeOff size={18} /> : <Eye size={18} />}
                            </button>
                        }
                    >
                        <input
                            data-testid="login-password-input"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            type={showPass ? 'text' : 'password'}
                            autoComplete="current-password"
                            value={password}
                            onChange={(e) => setPassword(e.target.value)}
                            placeholder="Enter your password"
                            style={inputStyle}
                        />
                    </Field>

                    {err && (
                        <div
                            data-testid="login-error"
                            role="alert"
                            style={{
                                marginTop: 14,
                                padding: '10px 14px',
                                borderRadius: 12,
                                background: 'rgba(255,90,90,0.10)',
                                border: '1px solid rgba(255,90,90,0.35)',
                                color: '#ffb4b4',
                                display: 'flex',
                                gap: 10,
                                alignItems: 'center',
                                fontSize: 13,
                                fontWeight: 500,
                            }}
                        >
                            <AlertCircle size={16} />
                            <span>{err}</span>
                        </div>
                    )}

                    <button
                        type="submit"
                        data-testid="login-submit"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        disabled={busy}
                        style={{
                            marginTop: 22,
                            width: '100%',
                            height: 54,
                            padding: '0 22px',
                            borderRadius: 999,
                            border: 'none',
                            background: busy
                                ? 'rgba(93,200,255,0.45)'
                                : 'linear-gradient(135deg, #5DC8FF 0%, #2EA8E8 100%)',
                            color: '#04101e',
                            fontSize: 16,
                            fontWeight: 700,
                            letterSpacing: '0.01em',
                            cursor: busy ? 'wait' : 'pointer',
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 10,
                            boxShadow:
                                '0 12px 30px rgba(46,168,232,0.4), inset 0 1px 0 rgba(255,255,255,0.4)',
                            transition: 'transform 120ms ease, opacity 120ms ease',
                            opacity: busy ? 0.85 : 1,
                        }}
                    >
                        {busy ? (
                            <>
                                <Loader2 size={18} className="animate-spin" />
                                Signing in…
                            </>
                        ) : (
                            <>
                                <LogIn size={18} strokeWidth={2.4} />
                                Sign in
                            </>
                        )}
                    </button>
                </form>

                <Footer />
                <DiagnosticsLink />
            </div>

            <style>{`
                @keyframes vesperLoginFade {
                    from { opacity: 0; transform: translateY(12px); }
                    to   { opacity: 1; transform: translateY(0);   }
                }
                @keyframes vesperLoginGlow {
                    0%, 100% { opacity: 0.55; transform: scale(1); }
                    50%      { opacity: 0.9;  transform: scale(1.04); }
                }
                .animate-spin { animation: spin 0.8s linear infinite; }
                @keyframes spin { to { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
}

const inputStyle = {
    width: '100%',
    height: 50,
    padding: '0 16px',
    borderRadius: 14,
    background: 'rgba(255,255,255,0.06)',
    border: '1px solid rgba(255,255,255,0.12)',
    color: 'var(--vesper-text, #e8eef8)',
    fontSize: 15.5,
    fontWeight: 500,
    outline: 'none',
    letterSpacing: '0.01em',
    transition: 'border-color 140ms ease, background 140ms ease',
};

function Field({ icon: Icon, label, testid, trailing, children }) {
    return (
        <div style={{ marginTop: 16 }}>
            <label
                htmlFor={testid}
                className="vesper-mono"
                style={{
                    display: 'block',
                    fontSize: 10,
                    fontWeight: 700,
                    letterSpacing: '0.28em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-blue-bright, #5DC8FF)',
                    marginBottom: 8,
                }}
            >
                {label}
            </label>
            <div
                style={{
                    position: 'relative',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.10)',
                    borderRadius: 16,
                    padding: '4px 12px',
                }}
            >
                <Icon
                    size={18}
                    strokeWidth={2}
                    style={{ color: 'rgba(255,255,255,0.5)', flex: '0 0 auto' }}
                />
                <div style={{ flex: 1, minWidth: 0 }}>{children}</div>
                {trailing}
            </div>
        </div>
    );
}

function Header() {
    return (
        <div style={{ animation: 'vesperLoginFade 540ms ease both' }}>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-blue-bright, #5DC8FF)',
                    fontWeight: 700,
                }}
            >
                Vesper · v2
            </div>
            <h1
                className="vesper-display"
                style={{
                    margin: '8px 0 6px 0',
                    fontSize: 'clamp(28px, 3vw, 40px)',
                    lineHeight: 1.05,
                    letterSpacing: '-0.028em',
                    color: '#fff',
                    textShadow: '0 6px 24px rgba(0,0,0,0.55)',
                }}
            >
                Welcome back
            </h1>
            <p
                style={{
                    margin: 0,
                    fontSize: 14,
                    lineHeight: 1.5,
                    color: 'var(--vesper-text-2, rgba(255,255,255,0.7))',
                    maxWidth: '38ch',
                }}
            >
                Sign in to your account to access movies, TV shows
                and your library.
            </p>
        </div>
    );
}

function Footer() {
    return (
        <div
            style={{
                marginTop: 26,
                paddingTop: 18,
                borderTop: '1px solid rgba(255,255,255,0.08)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                fontSize: 11.5,
                color: 'var(--vesper-text-3, rgba(255,255,255,0.45))',
                letterSpacing: '0.02em',
            }}
        >
            <span>Need an account? Contact your administrator.</span>
            <span
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.28em',
                    color: 'var(--vesper-blue-bright, #5DC8FF)',
                    fontWeight: 700,
                }}
            >
                SECURE
            </span>
        </div>
    );
}

function DiagnosticsLink() {
    // v2.10.30 — Surfaces the native DiagnosticsActivity directly
    // on the login screen.  Reason: the user's broken HK1 box can
    // sometimes not get past login at all (typing breaks, modals
    // mis-position).  The fly-mouse cursor still works on the
    // login card, so a clickable link here lets them launch the
    // native diagnostic dump screen even when the rest of the
    // WebView is unusable.  Only renders inside the Android app —
    // hidden on the web preview / browser.
    const isAndroid = typeof window !== 'undefined' &&
        !!window.OnNowTV && typeof window.OnNowTV.openDiagnostics === 'function';
    if (!isAndroid) return null;
    return (
        <div
            style={{
                marginTop: 14,
                display: 'flex',
                justifyContent: 'center',
            }}
        >
            <button
                type="button"
                data-testid="login-open-diagnostics"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={() => {
                    try { window.OnNowTV.openDiagnostics(); }
                    catch { /* ignore */ }
                }}
                style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '8px 16px',
                    background: 'rgba(255,193,84,0.08)',
                    border: '1px solid rgba(255,193,84,0.32)',
                    color: '#FFC857',
                    borderRadius: 999,
                    fontSize: 12,
                    fontWeight: 600,
                    letterSpacing: '0.02em',
                    cursor: 'pointer',
                }}
            >
                <Stethoscope size={14} strokeWidth={2.2} />
                Device diagnostics
            </button>
        </div>
    );
}

function BackdropArt() {
    return (
        <>
            {/* Outer cinematic gradient backdrop */}
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    inset: 0,
                    background:
                        'radial-gradient(circle at 20% 30%, rgba(93,200,255,0.18) 0%, transparent 55%), ' +
                        'radial-gradient(circle at 80% 80%, rgba(120,80,255,0.18) 0%, transparent 50%), ' +
                        'linear-gradient(135deg, #050810 0%, #0a1428 50%, #050810 100%)',
                    zIndex: 0,
                }}
            />
            {/* Subtle grain */}
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    inset: 0,
                    backgroundImage:
                        'radial-gradient(rgba(255,255,255,0.025) 1px, transparent 1px)',
                    backgroundSize: '3px 3px',
                    mixBlendMode: 'overlay',
                    pointerEvents: 'none',
                    zIndex: 1,
                }}
            />
            {/* Pulsing cyan halo behind the card */}
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    left: '50%',
                    top: '50%',
                    width: 'min(720px, 96vw)',
                    height: 'min(720px, 96vw)',
                    transform: 'translate(-50%, -50%)',
                    background:
                        'radial-gradient(circle, rgba(93,200,255,0.18) 0%, transparent 60%)',
                    filter: 'blur(8px)',
                    zIndex: 1,
                    pointerEvents: 'none',
                    animation: 'vesperLoginGlow 6s ease-in-out infinite',
                }}
            />
        </>
    );
}
