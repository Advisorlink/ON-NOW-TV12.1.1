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
import { Download, RefreshCw, ExternalLink } from 'lucide-react';
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

    // The running APK exposes its versionName at boot.  Outside the
    // WebView this is undefined, which means we should NEVER show the
    // gate (web users can't install an APK).
    const running = typeof window !== 'undefined' && window.__APP_VERSION__;

    useEffect(() => {
        if (!running) return undefined;
        let cancel = false;

        const check = async (force = false) => {
            try {
                if (!force) {
                    const cached = localStorage.getItem(CACHE_KEY);
                    if (cached) {
                        const parsed = JSON.parse(cached);
                        if (parsed && Date.now() - parsed.ts < CHECK_INTERVAL_MS) {
                            if (!cancel) setInfo(parsed.data);
                            return;
                        }
                    }
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
        return () => {
            cancel = true;
            clearInterval(id);
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
                    setDlError(payload || 'Install failed — try the manual link below.');
                    break;
                default:
                    break;
            }
        };
        return () => { delete window.__onUpdateEvent; };
    }, []);

    if (!running) return null;
    if (!info || !info.version || !info.apk_url) return null;
    if (compareSemver(running, info.version) >= 0) return null;

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
                'the new APK and install it manually — just this once.'
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
            setDlError('Copy failed — open the link manually: ' + info.apk_url);
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
                /* Solid base layer so nothing behind us bleeds
                 * through — fixes the profile-picker-showing-up
                 * issue reported on v2.6.2. */
                background: '#06080F',
                zIndex: 99999,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: '32px 24px',
                overflowY: 'auto',
            }}
        >
            {/* Glow accent layer on top of the solid base. */}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    inset: 0,
                    background:
                        'radial-gradient(ellipse 90% 60% at 50% -10%, rgba(93,200,255,0.22) 0%, rgba(93,200,255,0.05) 35%, transparent 70%)',
                    pointerEvents: 'none',
                }}
            />
            <div
                style={{
                    position: 'relative',
                    maxWidth: 540,
                    width: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    textAlign: 'center',
                    gap: 18,
                }}
            >
                <div
                    style={{
                        width: 72, height: 72, borderRadius: 22,
                        background: 'rgba(93,200,255,0.12)',
                        border: '1px solid rgba(93,200,255,0.55)',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        marginBottom: 8,
                    }}
                >
                    <Download size={32} color="#5DC8FF" strokeWidth={2.4} />
                </div>

                <div className="vesper-mono" style={{
                    fontSize: 11, letterSpacing: '0.34em',
                    color: '#5DC8FF', fontWeight: 700,
                }}>
                    UPDATE REQUIRED
                </div>
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(30px, 5vw, 44px)',
                        letterSpacing: '-0.02em',
                        lineHeight: 1.1,
                        color: '#fff',
                        margin: 0,
                    }}
                >
                    A new version of ON&nbsp;NOW&nbsp;TV V2 is available.
                </h1>

                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 13, letterSpacing: '0.16em',
                        color: '#9DA5B5', marginTop: 6,
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
                            padding: '16px 18px',
                            background: 'rgba(255,255,255,0.03)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            borderRadius: 14,
                            fontSize: 13,
                            lineHeight: 1.55,
                            color: '#C7CFDB',
                            textAlign: 'left',
                            maxHeight: 220,
                            overflowY: 'auto',
                            whiteSpace: 'pre-wrap',
                            fontFamily: 'monospace',
                            width: '100%',
                        }}
                    >
                        {trimNotes(info.notes)}
                    </div>
                )}

                {/* Progress bar — visible while downloading. */}
                {busy && progress >= 0 && (
                    <div
                        data-testid="update-gate-progress"
                        style={{
                            width: '100%',
                            height: 6,
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.08)',
                            overflow: 'hidden',
                            marginTop: 6,
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

                <button
                    data-testid="update-gate-install"
                    onClick={handleInstall}
                    disabled={busy && stage !== 'error'}
                    style={{
                        marginTop: 18,
                        height: 56,
                        padding: '0 32px',
                        borderRadius: 999,
                        background: 'var(--vesper-blue, #5DC8FF)',
                        border: 'none',
                        color: '#06080F',
                        fontSize: 15,
                        fontWeight: 800,
                        letterSpacing: '0.06em',
                        textTransform: 'uppercase',
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 10,
                        cursor: busy ? 'progress' : 'pointer',
                        opacity: busy && stage !== 'error' ? 0.85 : 1,
                        WebkitTapHighlightColor: 'transparent',
                    }}
                >
                    {busy && stage !== 'error' ? (
                        <>
                            <RefreshCw size={18} className="vesper-spin" />
                            {statusLabel}
                        </>
                    ) : (
                        <>
                            <Download size={18} />
                            {statusLabel}
                        </>
                    )}
                </button>

                {/* Always-visible fallback row (Open in browser / Copy
                 * link) so the user is NEVER stuck if the native
                 * installer fails — this is how v2.6.2 users will
                 * sideload v2.6.3 manually for the one-time
                 * bootstrap. */}
                <div style={{
                    display: 'flex', flexDirection: 'row', gap: 10,
                    marginTop: 4, flexWrap: 'wrap', justifyContent: 'center',
                }}>
                    <button
                        data-testid="update-gate-open-browser"
                        onClick={openExternal}
                        style={{
                            height: 40, padding: '0 18px', borderRadius: 999,
                            background: 'transparent',
                            border: '1px solid rgba(255,255,255,0.22)',
                            color: '#C7CFDB', fontSize: 12,
                            letterSpacing: '0.08em', textTransform: 'uppercase',
                            display: 'inline-flex', alignItems: 'center', gap: 8,
                            cursor: 'pointer',
                        }}
                    >
                        <ExternalLink size={14} />
                        Open in browser
                    </button>
                    <button
                        data-testid="update-gate-copy-url"
                        onClick={copyApkUrl}
                        style={{
                            height: 40, padding: '0 18px', borderRadius: 999,
                            background: 'transparent',
                            border: '1px solid rgba(255,255,255,0.22)',
                            color: '#C7CFDB', fontSize: 12,
                            letterSpacing: '0.08em', textTransform: 'uppercase',
                            cursor: 'pointer',
                        }}
                    >
                        Copy download link
                    </button>
                </div>

                {dlError && (
                    <div
                        data-testid="update-gate-error"
                        style={{
                            marginTop: 10,
                            fontSize: 12,
                            color: '#FCA5A5',
                            lineHeight: 1.5,
                            maxWidth: 460,
                        }}
                    >
                        {dlError}
                    </div>
                )}

                <div
                    style={{
                        marginTop: 14,
                        fontSize: 12,
                        color: 'var(--vesper-text-3, #6B7587)',
                        letterSpacing: '0.04em',
                        lineHeight: 1.5,
                        textAlign: 'center',
                    }}
                >
                    The app will reopen automatically once the update finishes installing.
                </div>
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
