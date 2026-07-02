/**
 * LoginScreen — premium TV / 16:9 sign-in surface for the ON NOW suite.
 *
 * v2.12.10 — Redesigned to match the ON NOW V2 LIVE TV login
 * (`onnowtv-livetv/res/layout/activity_login.xml`): split layout with
 * a cinematic themed photo backdrop + brand pitch on the LEFT and the
 * glass auth card on the RIGHT.  The same React bundle serves every
 * APK shell, so the artwork/copy/accent are picked at runtime:
 *   • Vesper  → movies & TV wall artwork, cyan accent
 *   • Music   → neon stage / headphones artwork, electric-pink accent
 *   • Kids    → Vesper artwork with kid-friendly copy
 *
 * Displays only `username` + `password` (no DNS).  On success
 * AuthContext updates → LoginGate hands control back to the router →
 * Vesper lands on /profiles, Music stays on /music.
 */
import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Loader2, Lock, User, LogIn, Eye, EyeOff, AlertCircle } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAuth } from '@/contexts/AuthContext';
import { isMusicApp } from '@/lib/profiles';
import Host from '@/lib/host';

/* ------------------------------------------------------------------ *
 * Per-app themes (Live TV look, product-specific art + copy)
 * ------------------------------------------------------------------ */
const THEMES = {
    vesper: {
        eyebrow: 'ON NOW TV · V2',
        accent: '#5DC8FF',
        accentRgb: '93, 200, 255',
        btnGradient: 'linear-gradient(135deg, #5DC8FF 0%, #2EA8E8 100%)',
        btnText: '#04101e',
        bg: 'login/vesper-bg.webp',
        headline: 'Movies, TV shows,\nand everything\nworth watching.',
        pitchSub: 'Sign in with the username and password you were given by ON NOW TV.',
        bullets: [
            'Movies & full TV series · streaming in HD',
            'Trailers, collections & watchlists',
            'Kids profiles · safe viewing for little ones',
        ],
        cardSub: 'Sign in to your account to access movies, TV shows and your library.',
        brandLine: 'ON NOW TV · V2',
    },
    music: {
        eyebrow: 'ON NOW · V2 MUSIC',
        accent: '#FF2D7F',
        accentRgb: '255, 45, 127',
        btnGradient: 'linear-gradient(135deg, #FF2D7F 0%, #C81B63 100%)',
        btnText: '#fff',
        bg: 'login/music-bg.webp',
        headline: 'Every song,\nevery artist,\nevery mood.',
        pitchSub: 'Sign in with the username and password you were given by ON NOW TV.',
        bullets: [
            'Full-length tracks · albums & top charts',
            'Karaoke nights · lyrics synced on screen',
            'Radio & podcasts · always on',
        ],
        cardSub: 'Sign in to your account to start listening.',
        brandLine: 'ON NOW TV · V2 MUSIC',
    },
    kids: {
        eyebrow: 'ON NOW · KIDS',
        accent: '#5DC8FF',
        accentRgb: '93, 200, 255',
        btnGradient: 'linear-gradient(135deg, #5DC8FF 0%, #2EA8E8 100%)',
        btnText: '#04101e',
        bg: 'login/vesper-bg.webp',
        headline: 'Shows made\njust for you.',
        pitchSub: 'Sign in to start watching shows just for you.',
        bullets: [
            'Cartoons & movies · picked for kids',
            'Safe viewing · grown-ups stay in control',
            'Your own profile · your own favourites',
        ],
        cardSub: 'Sign in to start watching shows just for you.',
        brandLine: 'ON NOW · KIDS',
    },
};

/* Host-aware product detection (see v2.10.68 notes): the same React
 * bundle is served to every APK shell (Vesper / Kids / Tunes) and to
 * plain browsers, so the login figures out which product it's
 * branding for at runtime. */
function resolveTheme() {
    if (typeof window !== 'undefined') {
        const kids = (() => {
            if (window.__vesperBootProfileKids === true) return true;
            try {
                const sp = new URLSearchParams(window.location.search);
                if (sp.get('profile') === 'kids') return true;
            } catch { /* parse failure: keep default */ }
            return window.__vesperHostPackage === 'tv.onnowtv.kids';
        })();
        if (kids) return THEMES.kids;
        if (isMusicApp()) return THEMES.music;
    }
    return THEMES.vesper;
}

export default function LoginScreen() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { login } = useAuth();
    const theme = React.useMemo(resolveTheme, []);

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
            // v2.12.9 — Music context (the standalone Tunes APK boots
            // at #/music) goes STRAIGHT into the music app after login.
            // It must never bounce to Vesper's /profiles picker.
            if (isMusicApp()) {
                navigate('/music', { replace: true });
            } else {
                // We also navigate to /profiles explicitly so the URL is
                // clean instead of whatever deep link the user landed on.
                navigate('/profiles', { replace: true });
            }
        } catch (ex) {
            setErr(ex.message || 'Login failed');
        } finally {
            setBusy(false);
        }
    }, [busy, username, password, login, navigate]);

    return (
        <div
            data-testid="login-screen"
            data-accent={theme === THEMES.music ? 'music' : 'default'}
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 1000,
                background: '#05080F',
                color: 'var(--vesper-text, #e8eef8)',
                overflow: 'hidden',
            }}
        >
            <Backdrop theme={theme} />

            {/* ───── 2-column content (pitch LEFT · auth card RIGHT) ───── */}
            <div className="onnow-login-columns">
                {/* LEFT: brand pitch */}
                <div className="onnow-login-pitch" style={{ animation: 'vesperLoginFade 600ms ease both' }}>
                    {/* Brand chip */}
                    <div
                        style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 9,
                            padding: '7px 14px',
                            borderRadius: 12,
                            background: 'rgba(12,20,34,0.72)',
                            border: '1px solid rgba(255,255,255,0.10)',
                        }}
                    >
                        <span
                            aria-hidden
                            style={{ width: 6, height: 6, background: theme.accent, display: 'inline-block' }}
                        />
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 11,
                                fontWeight: 700,
                                letterSpacing: '0.28em',
                                color: theme.accent,
                                textTransform: 'uppercase',
                            }}
                        >
                            {theme.eyebrow}
                        </span>
                    </div>

                    <h1
                        style={{
                            margin: '24px 0 0',
                            whiteSpace: 'pre-line',
                            fontFamily: "'Geist', system-ui, sans-serif",
                            fontWeight: 900,
                            fontSize: 'clamp(34px, 3.6vw, 52px)',
                            lineHeight: 1.06,
                            letterSpacing: '-0.025em',
                            color: '#fff',
                            textShadow: '0 8px 32px rgba(0,0,0,0.75)',
                        }}
                    >
                        {theme.headline}
                    </h1>

                    <p
                        style={{
                            margin: '18px 0 0',
                            maxWidth: '46ch',
                            fontSize: 15.5,
                            lineHeight: 1.55,
                            color: 'rgba(214,226,244,0.82)',
                            textShadow: '0 2px 12px rgba(0,0,0,0.7)',
                        }}
                    >
                        {theme.pitchSub}
                    </p>

                    {/* Feature bullets */}
                    <div style={{ marginTop: 32, display: 'flex', flexDirection: 'column', gap: 12 }}>
                        {theme.bullets.map((b) => (
                            <div key={b} style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                                <span
                                    aria-hidden
                                    style={{
                                        width: 6,
                                        height: 6,
                                        flex: '0 0 auto',
                                        background: theme.accent,
                                        boxShadow: `0 0 10px rgba(${theme.accentRgb}, 0.8)`,
                                    }}
                                />
                                <span
                                    style={{
                                        fontSize: 14,
                                        color: 'rgba(232,240,252,0.92)',
                                        textShadow: '0 2px 10px rgba(0,0,0,0.7)',
                                    }}
                                >
                                    {b}
                                </span>
                            </div>
                        ))}
                    </div>
                </div>

                {/* RIGHT: glass auth card */}
                <div
                    className="onnow-login-card"
                    style={{
                        animation: 'vesperLoginFade 600ms ease 120ms both',
                        background:
                            'linear-gradient(160deg, rgba(14,22,42,0.78) 0%, rgba(6,10,22,0.90) 100%)',
                        border: `1px solid rgba(${theme.accentRgb}, 0.22)`,
                        borderRadius: 24,
                        backdropFilter: 'blur(22px) saturate(140%)',
                        WebkitBackdropFilter: 'blur(22px) saturate(140%)',
                        boxShadow:
                            `0 30px 80px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.06), 0 0 60px rgba(${theme.accentRgb}, 0.10)`,
                    }}
                >
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 10,
                            letterSpacing: '0.32em',
                            textTransform: 'uppercase',
                            color: theme.accent,
                            fontWeight: 700,
                        }}
                    >
                        STEP 01
                    </div>
                    <h2
                        className="vesper-display"
                        style={{
                            margin: '8px 0 6px 0',
                            fontSize: 'clamp(24px, 2.2vw, 30px)',
                            lineHeight: 1.08,
                            letterSpacing: '-0.02em',
                            color: '#fff',
                        }}
                    >
                        Sign in to your account
                    </h2>
                    <p
                        style={{
                            margin: 0,
                            fontSize: 13,
                            lineHeight: 1.5,
                            color: 'rgba(214,226,244,0.66)',
                            maxWidth: '42ch',
                        }}
                    >
                        {theme.cardSub}
                    </p>

                    <form onSubmit={submit} style={{ marginTop: 20 }}>
                        {/* Username */}
                        <Field icon={User} label="Username" testid="login-username" accent={theme.accent}>
                            <input
                                ref={userRef}
                                data-testid="login-username-input"
                                data-focusable="true"
                                // v2.10.83 — focus ring lives on the
                                // wrapper via `.vesper-login-field:focus-within`
                                // so it draws around icon + input together
                                // without crowding the icon.  `bare` style
                                // suppresses any input-level outline.
                                data-focus-style="bare"
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
                            accent={theme.accent}
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
                                // v2.10.83 — see username input note above.
                                data-focus-style="bare"
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
                                    ? `rgba(${theme.accentRgb}, 0.45)`
                                    : theme.btnGradient,
                                color: theme.btnText,
                                fontSize: 16,
                                fontWeight: 700,
                                letterSpacing: '0.01em',
                                cursor: busy ? 'wait' : 'pointer',
                                display: 'inline-flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                gap: 10,
                                boxShadow:
                                    `0 12px 30px rgba(${theme.accentRgb}, 0.35), inset 0 1px 0 rgba(255,255,255,0.4)`,
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

                    {/* Footer */}
                    <div
                        style={{
                            marginTop: 22,
                            paddingTop: 16,
                            borderTop: '1px solid rgba(255,255,255,0.08)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            fontSize: 11.5,
                            color: 'var(--vesper-text-3, rgba(255,255,255,0.45))',
                            letterSpacing: '0.02em',
                        }}
                    >
                        <span>Need an account? Contact ON NOW TV Support.</span>
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 10,
                                letterSpacing: '0.28em',
                                color: theme.accent,
                                fontWeight: 700,
                            }}
                        >
                            SECURE
                        </span>
                    </div>
                </div>
            </div>

            {/* Bottom-left brand line (matches Live TV login) */}
            <div
                className="vesper-mono onnow-login-brandline"
                style={{
                    position: 'absolute',
                    left: 'clamp(24px, 5vw, 96px)',
                    bottom: 32,
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'rgba(120,140,175,0.75)',
                    zIndex: 3,
                    pointerEvents: 'none',
                }}
            >
                {theme.brandLine}
            </div>

            <style>{`
                /* Kill the generic 'bare' focus fill INSIDE the login
                   fields — the wrapper already draws the glow ring, so
                   the extra inner box read as a box-within-a-box. */
                .vesper-login-field [data-focus-style='bare']:focus-visible,
                .vesper-login-field [data-focus-style='bare'][data-focused='true'] {
                    background: transparent !important;
                    border-color: transparent !important;
                }
                /* Music context — focus ring glows PINK, not cyan. */
                [data-testid="login-screen"][data-accent="music"] .vesper-login-field:focus-within {
                    border-color: #FF2D7F !important;
                    background: rgba(255, 45, 127, 0.06) !important;
                    box-shadow:
                        0 0 0 1.5px #FF2D7F,
                        0 0 24px 4px rgba(255, 45, 127, 0.40),
                        0 0 48px 12px rgba(255, 45, 127, 0.18) !important;
                }
                .onnow-login-columns {
                    position: relative;
                    z-index: 2;
                    height: 100%;
                    display: flex;
                    align-items: center;
                    gap: clamp(32px, 4vw, 72px);
                    padding: 0 clamp(24px, 5vw, 96px);
                }
                .onnow-login-pitch {
                    flex: 1.05 1 0;
                    min-width: 0;
                }
                .onnow-login-card {
                    flex: 0 1 480px;
                    max-width: 500px;
                    min-width: 380px;
                    max-height: 92vh;
                    overflow-y: auto;
                    padding: clamp(24px, 3vh, 36px) clamp(24px, 2.5vw, 36px);
                }
                @media (max-width: 980px) {
                    .onnow-login-pitch { display: none; }
                    .onnow-login-brandline { display: none; }
                    .onnow-login-columns { justify-content: center; }
                    .onnow-login-card { min-width: 0; width: min(520px, 94vw); }
                }
                @keyframes vesperLoginFade {
                    from { opacity: 0; transform: translateY(12px); }
                    to   { opacity: 1; transform: translateY(0);   }
                }
                .animate-spin { animation: spin 0.8s linear infinite; }
                @keyframes spin { to { transform: rotate(360deg); } }
            `}</style>
        </div>
    );
}

const inputStyle = {
    width: '100%',
    height: 44,
    padding: '0 16px',
    borderRadius: 14,
    background: 'transparent',
    border: 'none',
    color: 'var(--vesper-text, #e8eef8)',
    fontSize: 15.5,
    fontWeight: 500,
    outline: 'none',
    letterSpacing: '0.01em',
};

function Field({ icon: Icon, label, testid, trailing, accent, children }) {
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
                    color: accent || 'var(--vesper-blue-bright, #5DC8FF)',
                    marginBottom: 8,
                }}
            >
                {label}
            </label>
            <div
                // v2.10.83 — Focus ring lives on the WRAPPER (via
                // `:focus-within` in CSS), not on the inner <input>.
                // Previously the input itself wore `data-focus-style="pill"`,
                // so the 2 px outline ring hugged the input tightly and
                // crowded the 18 px icon to its left.  Promoting the
                // ring up to the wrapper means it draws around the
                // icon + input together with proper breathing room.
                className="vesper-login-field"
                style={{
                    position: 'relative',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 14,
                    background: 'rgba(10,16,30,0.75)',
                    border: '1px solid rgba(255,255,255,0.10)',
                    borderRadius: 14,
                    padding: '4px 16px',
                    transition:
                        'border-color 160ms ease, background-color 160ms ease, box-shadow 160ms ease',
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

/* Themed photo backdrop + scrims.  The photo sits full-bleed with a
 * left-heavy dark gradient so the pitch copy reads cleanly, exactly
 * like the Live TV login's vignette over its orbital art. */
function Backdrop({ theme }) {
    return (
        <>
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    inset: 0,
                    backgroundImage: `url(${Host.publicAsset(theme.bg)})`,
                    backgroundSize: 'cover',
                    backgroundPosition: 'center',
                    zIndex: 0,
                }}
            />
            {/* Vignette + left-to-right scrim so text reads clean */}
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    inset: 0,
                    background:
                        'linear-gradient(90deg, rgba(5,8,15,0.90) 0%, rgba(5,8,15,0.62) 42%, rgba(5,8,15,0.55) 100%), ' +
                        'linear-gradient(180deg, rgba(5,8,15,0.35) 0%, transparent 30%, transparent 62%, rgba(5,8,15,0.75) 100%)',
                    zIndex: 1,
                }}
            />
            {/* Accent glow anchored behind the pitch copy */}
            <div
                aria-hidden
                style={{
                    position: 'absolute',
                    left: '-8%',
                    top: '18%',
                    width: 'min(760px, 60vw)',
                    height: 'min(760px, 60vw)',
                    background: `radial-gradient(circle, rgba(${theme.accentRgb}, 0.14) 0%, transparent 60%)`,
                    filter: 'blur(10px)',
                    zIndex: 1,
                    pointerEvents: 'none',
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
        </>
    );
}
