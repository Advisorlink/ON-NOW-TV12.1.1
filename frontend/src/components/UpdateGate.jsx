/**
 * <UpdateGate/> — fullscreen forced-update gate.
 *
 * Checks /api/app/latest-version on mount + every 6 h, compares the
 * returned semver with the running APK's `versionName` (exposed by
 * the native MainActivity via `window.__APP_VERSION__`), and when
 * the bundled version is older renders a blocking dark fullscreen
 * "Update required" page over the entire app — there's no escape
 * hatch (per the user's request: "Forced … fullscreen 'Update
 * required' gate on app launch that can only be dismissed by
 * installing").
 *
 * Outside an Android WebView (`window.__APP_VERSION__` undefined) the
 * gate stays hidden so the web build remains usable.
 *
 * Native bridge (added v2.6.4):
 *   - window.OnNowTV.installApk(url)  — downloads + invokes the
 *     PackageInstaller silently.  Sends progress callbacks via
 *     `window.__onUpdateEvent(stage, info)`.
 *   - window.OnNowTV.openExternal(url) — open in the system browser
 *     as a fallback.
 */
import React, { useEffect, useState } from 'react';
import { Download, RefreshCw, ExternalLink, Tv2, X } from 'lucide-react';
import { useLocation } from 'react-router-dom';
import axios from 'axios';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;
const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;  // 6 h
const CACHE_KEY = 'onnowtv:update-info:v1';

/* Compare two semver strings (a < b → -1, a == b → 0, a > b → +1). */
function compareSemver(a, b) {
    if (!a || !b) return 0;
    const pa = String(a).split('.').map((n) => parseInt(n, 10) || 0);
    const pb = String(b).split('.').map((n) => parseInt(n, 10) || 0);
    for (let i = 0; i < Math.max(pa.length, pb.length); i += 1) {
        const x = pa[i] || 0;
        const y = pb[i] || 0;
        if (x < y) return -1;
        if (x > y) return 1;
    }
    return 0;
}

export default function UpdateGate() {
    const [info, setInfo] = useState(null);
    const [busy, setBusy] = useState(false);
    const [stage, setStage] = useState('idle');      // idle | started | progress | downloaded | error
    const [progress, setProgress] = useState(-1);
    const [dlError, setDlError] = useState(null);
    /* Dismissed-for-this-session flag — once the user hits Skip,
     * we hide the popup until next app launch. */
    const [snoozed, setSnoozed] = useState(false);
    const location = useLocation();

    // The running APK exposes its versionName at boot.  Outside the
    // WebView this is undefined, which means we should NEVER show the
    // gate (web users can't install an APK).
    const running = typeof window !== 'undefined' && window.__APP_VERSION__;

    useEffect(() => {
        if (!running) return undefined;
        let cancel = false;

        const check = async (force = false) => {
            try {
                // Use cached info as an INSTANT placeholder so we
                // don't render a blank state during the network
                // round-trip — but ALWAYS fire the live request too
                // so we pick up newly-published versions on every
                // app launch.  Previously we returned early when
                // the cache was younger than 6 h, which meant if a
                // user opened the app twice within 6 h after we
                // released a new APK, the second launch would
                // silently keep the stale "you're up to date" state
                // and the user had to clear app data to see the
                // update gate.  Always-fetch fixes that.
                if (!force) {
                    try {
                        const cached = localStorage.getItem(CACHE_KEY);
                        if (cached) {
                            const parsed = JSON.parse(cached);
                            if (parsed && parsed.data && !cancel) {
                                setInfo(parsed.data);
                            }
                        }
                    } catch { /* ignore */ }
                }
                const { data } = await axios.get(`${API}/app/latest-version`, {
                    timeout: 8000,
                });
                if (cancel) return;
                setInfo(data);
                try {
                    localStorage.setItem(
                        CACHE_KEY,
                        JSON.stringify({ ts: Date.now(), data })
                    );
                } catch {
                    /* localStorage full / disabled — silent */
                }
            } catch {
                // Soft-fail: never block the user on a flaky backend.
            }
        };

        check(false);
        const id = setInterval(() => check(true), CHECK_INTERVAL_MS);
        // Also re-check whenever the WebView becomes visible again
        // (user navigated back into the app after closing it).
        // On Android WebView, `visibilitychange` fires on
        // pause/resume of the host Activity.
        const onVisible = () => {
            if (!document.hidden) check(true);
        };
        document.addEventListener('visibilitychange', onVisible);
        window.addEventListener('focus', onVisible);
        return () => {
            cancel = true;
            clearInterval(id);
            document.removeEventListener('visibilitychange', onVisible);
            window.removeEventListener('focus', onVisible);
        };
    }, [running]);

    /* Native progress / lifecycle callbacks from WebAppInterface.
     * We install a global handler on window so the Kotlin side can
     * call us via `window.__onUpdateEvent(stage, info)`.  Cleared on
     * unmount in case the gate ever gets remounted (StrictMode dev). */
    useEffect(() => {
        if (typeof window === 'undefined') return undefined;
        window.__onUpdateEvent = (s, payload) => {
            switch (s) {
                case 'started':
                    setStage('started');
                    setProgress(-1);
                    break;
                case 'progress': {
                    const pct = parseInt(payload, 10);
                    setStage('progress');
                    setProgress(Number.isFinite(pct) ? pct : -1);
                    break;
                }
                case 'downloaded':
                    setStage('downloaded');
                    setProgress(100);
                    break;
                case 'error':
                    setStage('error');
                    setBusy(false);
                    setDlError(payload || 'Install failed. Try the manual link below.');
                    break;
                default:
                    break;
            }
        };
        return () => { delete window.__onUpdateEvent; };
    }, []);

    if (!running) return null;
    if (!info || !info.version || !info.apk_url) return null;
    if (info.min_version && compareSemver(running, info.min_version) < 0) return null;
    if (compareSemver(running, info.version) >= 0) return null;
    /* HOLD the popup until the user is INSIDE the app (i.e. has
     * picked a profile + isn't on the profile-picker / kids-PIN /
     * pre-onboarding routes).  Also stay hidden while the user
     * has SKIPPED it this session.  And NEVER show during
     * fullscreen player. */
    const path = location?.pathname || '/';
    const onPreEntry =
        path === '/profile' ||
        path.startsWith('/profile/') ||
        path === '/kids-exit-pin' ||
        path === '/auth';
    const onPlayer = path.startsWith('/play');
    if (snoozed) return null;
    if (onPreEntry) return null;
    if (onPlayer) return null;

    const handleInstall = () => {
        setBusy(true);
        setDlError(null);
        setStage('started');
        setProgress(-1);
        try {
            if (window.OnNowTV?.installApk) {
                window.OnNowTV.installApk(info.apk_url);
                return;
            }
            if (window.OnNowTV?.openExternal) {
                window.OnNowTV.openExternal(info.apk_url);
                setStage('downloaded');
                setBusy(false);
                return;
            }
            // No native bridge available — typical for v2.6.2 and
            // older builds that don't have installApk yet.  We
            // surface a clear instruction + a copy-to-clipboard
            // helper so the user can sideload manually.
            setStage('error');
            setBusy(false);
            setDlError(
                'This older version of the app cannot install updates ' +
                'automatically. Tap "Open in browser" below to download ' +
                'the new APK and install it manually, just this once.'
            );
        } catch (e) {
            setStage('error');
            setBusy(false);
            setDlError(e?.message || 'Could not start the download.');
        }
    };

    const openExternal = () => {
        try {
            if (window.OnNowTV?.openExternal) {
                window.OnNowTV.openExternal(info.apk_url);
            } else {
                window.open(info.apk_url, '_blank');
            }
        } catch (_) { /* ignore */ }
    };

    const copyApkUrl = async () => {
        try {
            await navigator.clipboard.writeText(info.apk_url);
            setDlError('Copied! Paste it into your browser or Downloader app.');
        } catch {
            setDlError('Copy failed. Open the link manually: ' + info.apk_url);
        }
    };

    const statusLabel = (() => {
        if (stage === 'started') return 'Starting download…';
        if (stage === 'progress')
            return progress >= 0 ? `Downloading… ${progress}%` : 'Downloading…';
        if (stage === 'downloaded') return 'Opening installer…';
        if (busy) return 'Downloading…';
        return 'Download and install';
    })();

    return (
        <div
            data-testid="update-gate"
            style={{
                position: 'fixed',
                inset: 0,
                background: 'rgba(6,8,15,0.78)',
                backdropFilter: 'blur(8px)',
                WebkitBackdropFilter: 'blur(8px)',
                zIndex: 99999,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 24,
                animation: 'vesper-update-gate-fade 240ms ease-out',
            }}
        >
            <style>{`
                @keyframes vesper-update-gate-fade {
                    from { opacity: 0; }
                    to   { opacity: 1; }
                }
                @keyframes vesper-update-card-rise {
                    from { opacity: 0; transform: translateY(12px) scale(0.98); }
                    to   { opacity: 1; transform: translateY(0) scale(1); }
                }
            `}</style>
            <div
                data-testid="update-gate-card"
                style={{
                    position: 'relative',
                    width: '100%',
                    maxWidth: 480,
                    maxHeight: 'calc(100vh - 64px)',
                    overflowY: 'auto',
                    background:
                        'linear-gradient(180deg, #0F1830 0%, #07101F 100%)',
                    border: '1px solid rgba(93,200,255,0.28)',
                    borderRadius: 20,
                    padding: '28px 28px 24px',
                    boxShadow:
                        '0 30px 70px rgba(0,0,0,0.65), 0 0 0 1px rgba(255,255,255,0.04), 0 0 60px rgba(93,200,255,0.18)',
                    animation: 'vesper-update-card-rise 280ms cubic-bezier(.2,.7,.2,1) both',
                }}
            >
                {/* SKIP — top-right X.  Only meaningful when NOT
                    actively downloading. */}
                {!busy && (
                    <button
                        data-testid="update-gate-skip"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => setSnoozed(true)}
                        aria-label="Skip update"
                        style={{
                            position: 'absolute',
                            top: 14, right: 14,
                            width: 32, height: 32,
                            borderRadius: '50%',
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            color: '#9DA5B5',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            cursor: 'pointer',
                            zIndex: 2,
                        }}
                    >
                        <X size={16} />
                    </button>
                )}

                {/* LOGO */}
                <div
                    style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 12,
                        marginBottom: 18,
                    }}
                >
                    <div
                        style={{
                            width: 44, height: 44, borderRadius: 12,
                            background:
                                'linear-gradient(135deg, rgba(93,200,255,0.25), rgba(93,200,255,0.05))',
                            border: '1px solid rgba(93,200,255,0.4)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                        }}
                    >
                        <Tv2 size={20} color="#5DC8FF" strokeWidth={2} />
                    </div>
                    <div style={{ display: 'flex', flexDirection: 'column' }}>
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 9,
                                letterSpacing: '0.28em',
                                color: '#5DC8FF',
                                fontWeight: 700,
                                textTransform: 'uppercase',
                            }}
                        >
                            ON NOW TV V2
                        </span>
                        <span
                            style={{
                                fontSize: 15,
                                fontWeight: 700,
                                color: '#fff',
                                letterSpacing: '-0.01em',
                            }}
                        >
                            Update available
                        </span>
                    </div>
                </div>

                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 24,
                        letterSpacing: '-0.02em',
                        lineHeight: 1.15,
                        color: '#fff',
                        margin: 0,
                    }}
                >
                    A new version is ready to install.
                </h1>

                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11, letterSpacing: '0.14em',
                        color: '#9DA5B5',
                        marginTop: 10,
                        textTransform: 'uppercase',
                    }}
                >
                    YOU HAVE&nbsp;<strong style={{ color: '#fff' }}>v{running}</strong>
                    &nbsp;·&nbsp;LATEST&nbsp;<strong style={{ color: '#5DC8FF' }}>v{info.version}</strong>
                </div>

                {info.notes && (
                    <div
                        data-testid="update-gate-notes"
                        style={{
                            marginTop: 14,
                            padding: '12px 14px',
                            background: 'rgba(255,255,255,0.03)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            borderRadius: 10,
                            fontSize: 12,
                            lineHeight: 1.55,
                            color: '#C7CFDB',
                            textAlign: 'left',
                            maxHeight: 160,
                            overflowY: 'auto',
                            whiteSpace: 'pre-wrap',
                            fontFamily: 'monospace',
                        }}
                    >
                        {trimNotes(info.notes)}
                    </div>
                )}

                {busy && progress >= 0 && (
                    <div
                        data-testid="update-gate-progress"
                        style={{
                            width: '100%',
                            height: 5,
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.08)',
                            overflow: 'hidden',
                            marginTop: 14,
                        }}
                    >
                        <div style={{
                            width: `${Math.max(0, Math.min(100, progress))}%`,
                            height: '100%',
                            background: 'linear-gradient(90deg, #5DC8FF, #7FE5FF)',
                            transition: 'width 240ms ease-out',
                        }} />
                    </div>
                )}

                <div
                    style={{
                        marginTop: 18,
                        display: 'flex',
                        gap: 10,
                        alignItems: 'center',
                    }}
                >
                    <button
                        data-testid="update-gate-install"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={handleInstall}
                        disabled={busy && stage !== 'error'}
                        style={{
                            flex: 1,
                            height: 46,
                            padding: '0 22px',
                            borderRadius: 999,
                            background: 'var(--vesper-blue, #5DC8FF)',
                            border: 'none',
                            color: '#06080F',
                            fontSize: 13,
                            fontWeight: 800,
                            letterSpacing: '0.06em',
                            textTransform: 'uppercase',
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 8,
                            cursor: busy ? 'progress' : 'pointer',
                            opacity: busy && stage !== 'error' ? 0.85 : 1,
                            WebkitTapHighlightColor: 'transparent',
                        }}
                    >
                        {busy && stage !== 'error' ? (
                            <>
                                <RefreshCw size={16} className="vesper-spin" />
                                {statusLabel}
                            </>
                        ) : (
                            <>
                                <Download size={16} />
                                Download
                            </>
                        )}
                    </button>
                    {!busy && (
                        <button
                            data-testid="update-gate-skip-btn"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => setSnoozed(true)}
                            style={{
                                height: 46,
                                padding: '0 22px',
                                borderRadius: 999,
                                background: 'transparent',
                                border: '1px solid rgba(255,255,255,0.22)',
                                color: '#C7CFDB',
                                fontSize: 12,
                                fontWeight: 700,
                                letterSpacing: '0.08em',
                                textTransform: 'uppercase',
                                cursor: 'pointer',
                            }}
                        >
                            Skip
                        </button>
                    )}
                </div>

                {/* Fallback row (manual link / copy URL) — kept
                    smaller now that the install flow is the
                    primary CTA. */}
                <div style={{
                    display: 'flex', flexDirection: 'row', gap: 8,
                    marginTop: 10, flexWrap: 'wrap', justifyContent: 'flex-start',
                }}>
                    <button
                        data-testid="update-gate-open-browser"
                        onClick={openExternal}
                        style={{
                            height: 32, padding: '0 12px', borderRadius: 999,
                            background: 'transparent',
                            border: '1px solid rgba(255,255,255,0.14)',
                            color: '#9DA5B5', fontSize: 10,
                            letterSpacing: '0.1em', textTransform: 'uppercase',
                            display: 'inline-flex', alignItems: 'center', gap: 6,
                            cursor: 'pointer',
                        }}
                    >
                        <ExternalLink size={11} />
                        Open in browser
                    </button>
                    <button
                        data-testid="update-gate-copy-url"
                        onClick={copyApkUrl}
                        style={{
                            height: 32, padding: '0 12px', borderRadius: 999,
                            background: 'transparent',
                            border: '1px solid rgba(255,255,255,0.14)',
                            color: '#9DA5B5', fontSize: 10,
                            letterSpacing: '0.1em', textTransform: 'uppercase',
                            cursor: 'pointer',
                        }}
                    >
                        Copy link
                    </button>
                </div>

                {dlError && (
                    <div
                        data-testid="update-gate-error"
                        style={{
                            marginTop: 10,
                            fontSize: 11,
                            color: '#FCA5A5',
                            lineHeight: 1.5,
                        }}
                    >
                        {dlError}
                    </div>
                )}
            </div>
        </div>
    );
}

/* Trim the raw GitHub release-notes body to the FIRST changelog
   block — anything before the second "**v" heading or until the
   first big horizontal rule.  Keeps the gate readable. */
function trimNotes(notes) {
    if (!notes) return '';
    const lines = String(notes).split('\n');
    const out = [];
    let foundFirst = false;
    for (const line of lines) {
        if (line.startsWith('**v') || line.startsWith('## v')) {
            if (foundFirst) break;
            foundFirst = true;
        }
        if (foundFirst) out.push(line);
        if (out.length > 18) break;
    }
    return out.join('\n').trim();
}
