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
 * Outside the WebView OR while the version-check API returns null /
 * fails, the gate is a no-op — we never want a transient backend
 * blip to lock a user out of the app.
 */
import React, { useEffect, useState } from 'react';
import { Download, RefreshCw } from 'lucide-react';
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
    const [dlError, setDlError] = useState(null);

    // The running APK exposes its versionName at boot.  Outside the
    // WebView this is undefined, which means we should NEVER show the
    // gate (web users can't install an APK).
    const running = typeof window !== 'undefined' && window.__APP_VERSION__;

    useEffect(() => {
        if (!running) return undefined;
        let cancel = false;

        const check = async (force = false) => {
            // Use the cached payload first (avoids GitHub's 60-req/hour
            // rate limit when the user re-launches the app in quick
            // succession).
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

    if (!running) return null;
    if (!info || !info.version || !info.apk_url) return null;
    if (compareSemver(running, info.version) >= 0) return null;

    const handleInstall = async () => {
        setBusy(true);
        setDlError(null);
        try {
            // Two paths for installing the APK:
            // 1. Native bridge — if MainActivity registered
            //    `window.OnNowTV.installApk(url)`, it can download +
            //    invoke the PackageInstaller silently (next build).
            // 2. Fallback — open the APK URL.  The WebView fires the
            //    OS download manager, which then prompts the user to
            //    install.  Works on every Android since 7.
            if (window.OnNowTV?.installApk) {
                window.OnNowTV.installApk(info.apk_url);
            } else if (window.OnNowTV?.openExternal) {
                window.OnNowTV.openExternal(info.apk_url);
            } else {
                // WebView's <a download> typically triggers the OS
                // download manager.  Use top-level navigation so the
                // download survives even if we close this view.
                window.location.href = info.apk_url;
            }
        } catch (e) {
            setDlError(e?.message || 'Could not start the download.');
            setBusy(false);
        }
    };

    return (
        <div
            data-testid="update-gate"
            style={{
                position: 'fixed',
                inset: 0,
                background: 'radial-gradient(ellipse at top, rgba(93,200,255,0.15) 0%, rgba(6,8,15,0.95) 50%, #06080F 100%)',
                zIndex: 9999,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: '32px 24px',
            }}
        >
            <div
                style={{
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

                <button
                    data-testid="update-gate-install"
                    onClick={handleInstall}
                    disabled={busy}
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
                        opacity: busy ? 0.7 : 1,
                        WebkitTapHighlightColor: 'transparent',
                    }}
                >
                    {busy ? (
                        <>
                            <RefreshCw size={18} className="vesper-spin" />
                            Downloading…
                        </>
                    ) : (
                        <>
                            <Download size={18} />
                            Download and install
                        </>
                    )}
                </button>

                {dlError && (
                    <div
                        data-testid="update-gate-error"
                        style={{
                            marginTop: 10,
                            fontSize: 12,
                            color: '#FCA5A5',
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
