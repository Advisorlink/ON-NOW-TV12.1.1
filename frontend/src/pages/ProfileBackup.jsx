/**
 * <ProfileBackup/> — profile-less backup-save page.
 *
 * Reached from the ProfileSelect screen via the new
 * "Back up profiles" pill (v2.10.98 — operator spec).
 *
 * Why a separate page?  The operator wanted the backup capability
 * surfaced ON the main profile-picker screen so a user can save a
 * snapshot BEFORE clicking Update on Vesper.  The Settings →
 * Backup panel already exists, but it's gated behind RequireProfile
 * — useless to a fresh user with no active profile yet.  This
 * route is in NO_PROFILE_REQUIRED so it works from the picker.
 *
 * Behaviour matches Settings → Backup exactly:
 *   1. PIN entry  — 4 digits, the user picks.
 *   2. Save       — POST /api/backup/save { payload, pin }.
 *   3. Show code  — the 6-character code is the user's restore
 *                   handle (combined with the PIN they just set).
 *
 * Reuses `collectBackupPayload()` from /lib/profileBackup so the
 * payload shape is byte-identical to the Settings flow — a code
 * saved here restores cleanly from Settings → Backup on another
 * device and vice-versa.
 */
import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
    ArrowLeft, CloudUpload, Loader2, KeyRound,
    Check, Copy, ShieldCheck,
} from 'lucide-react';
import PinGate from '@/components/PinGate';
import { collectBackupPayload, summarizeBackupPayload } from '@/lib/profileBackup';

export default function ProfileBackup() {
    const navigate = useNavigate();
    const API = process.env.REACT_APP_BACKEND_URL;

    // Steps: idle (intro) → pin → saving → done.
    const [step, setStep] = React.useState('idle');
    const [busy, setBusy] = React.useState(false);
    const [err, setErr] = React.useState('');
    const [result, setResult] = React.useState(null);
    const [pinReset, setPinReset] = React.useState(0);
    const [copied, setCopied] = React.useState(false);

    const onSubmitPin = async (pinDigits) => {
        setBusy(true);
        setErr('');
        try {
            const payload = collectBackupPayload();
            const res = await fetch(`${API}/api/backup/save`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ payload, pin: pinDigits }),
            });
            if (!res.ok) {
                const j = await res.json().catch(() => ({}));
                throw new Error(j.detail || `Save failed (HTTP ${res.status}).`);
            }
            const j = await res.json();
            setResult({ ...j, summary: summarizeBackupPayload(payload) });
            setStep('done');
        } catch (e) {
            setErr(e.message || 'Could not save backup.');
            setPinReset((n) => n + 1);    // clear PIN input, stay on PIN step
        } finally {
            setBusy(false);
        }
    };

    const copyCode = async () => {
        if (!result?.code) return;
        try {
            await navigator.clipboard.writeText(result.code);
            setCopied(true);
            setTimeout(() => setCopied(false), 1800);
        } catch { /* ignore */ }
    };

    /* Backdrop wrapper styled to mirror ProfileLoad. */
    return (
        <div
            data-testid="profile-backup"
            style={{
                minHeight: '100vh',
                background:
                    'radial-gradient(60% 80% at 20% 0%, rgba(93,200,255,0.10) 0%, rgba(6,8,15,0) 60%), ' +
                    'radial-gradient(50% 70% at 80% 100%, rgba(255,255,255,0.05) 0%, rgba(6,8,15,0) 60%), ' +
                    'var(--vesper-bg-0, #06080F)',
                color: 'var(--vesper-text-1, #E6EAF0)',
                padding: 'clamp(24px, 5vw, 56px)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 22,
            }}
        >
            <button
                data-testid="profile-backup-back"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={() => navigate('/profiles')}
                style={{
                    position: 'absolute', top: 28, left: 28,
                    height: 40, padding: '0 14px', borderRadius: 999,
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.16)',
                    color: 'var(--vesper-text-1)', fontSize: 13,
                    display: 'inline-flex', alignItems: 'center', gap: 8,
                    cursor: 'pointer',
                }}
            >
                <ArrowLeft size={16} />
                Back
            </button>

            {step === 'idle' && (
                <div style={panelStyle}>
                    <div style={iconCircleStyle}>
                        <CloudUpload size={32} style={{ color: '#5DC8FF' }} />
                    </div>
                    <h1 className="vesper-display" style={titleStyle}>
                        Back up your profiles
                    </h1>
                    <p style={blurbStyle}>
                        Save your profiles, Continue Watching, libraries,
                        favourites, Live TV setup, reminders and theme to the
                        cloud.  You&rsquo;ll get a 6-character code locked
                        with a 4-digit PIN you choose &mdash; keep both
                        somewhere safe.  Re-install the app or set up a new
                        TV box, just enter the code + PIN to get everything
                        back exactly how you left it.
                    </p>
                    <button
                        data-testid="profile-backup-start"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={() => setStep('pin')}
                        style={primaryBtnStyle}
                    >
                        <KeyRound size={16} />
                        Choose a PIN &amp; save
                    </button>
                </div>
            )}

            {step === 'pin' && (
                <PinGate
                    title="Choose a 4-digit PIN"
                    subtitle="You'll need this PIN to restore later. Pick something memorable — there's no recovery for a forgotten PIN."
                    onSuccess={onSubmitPin}
                    onCancel={() => { setErr(''); setStep('idle'); }}
                    error={err}
                    resetSignal={pinReset}
                />
            )}

            {busy && step !== 'pin' && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <Loader2 size={20} className="vesper-spin" />
                    <span>Saving backup&hellip;</span>
                </div>
            )}

            {step === 'done' && result && (
                <div style={panelStyle}>
                    <div style={iconCircleStyle}>
                        <ShieldCheck size={32} style={{ color: '#5DC8FF' }} />
                    </div>
                    <h1 className="vesper-display" style={titleStyle}>
                        Backup saved
                    </h1>
                    <p style={blurbStyle}>
                        Write the code below somewhere safe.  You&rsquo;ll
                        need it together with the PIN you just chose to
                        restore.
                    </p>
                    <div
                        data-testid="profile-backup-code"
                        style={{
                            margin: '14px 0',
                            padding: '18px 28px',
                            borderRadius: 14,
                            background: 'rgba(93,200,255,0.10)',
                            border: '1px solid rgba(93,200,255,0.36)',
                            color: '#8de0ff',
                            fontFamily: 'var(--vesper-font-mono, ui-monospace, SFMono-Regular, monospace)',
                            fontSize: 36, letterSpacing: '0.18em',
                            fontWeight: 700,
                        }}
                    >
                        {result.code}
                    </div>
                    {result.summary && (
                        <div
                            data-testid="profile-backup-summary"
                            style={{
                                margin: '0 0 12px',
                                padding: '12px 18px',
                                borderRadius: 12,
                                background: 'rgba(255,255,255,0.04)',
                                border: '1px solid rgba(255,255,255,0.10)',
                                fontSize: 13, lineHeight: 1.6,
                                color: 'var(--vesper-text-2, #9DA5B5)',
                                textAlign: 'left', maxWidth: 460,
                            }}
                        >
                            <div style={{ fontWeight: 700, color: 'var(--vesper-text-1, #E6EAF0)', marginBottom: 4 }}>
                                What&rsquo;s inside this backup
                            </div>
                            <div>
                                {result.summary.profileCount} profile{result.summary.profileCount === 1 ? '' : 's'}
                                {result.summary.profileNames.length > 0 && (
                                    <> ({result.summary.profileNames.join(', ')})</>
                                )}
                            </div>
                            <div>{result.summary.cwCount} Continue Watching item{result.summary.cwCount === 1 ? '' : 's'}</div>
                            <div>{result.summary.libraryCount} library item{result.summary.libraryCount === 1 ? '' : 's'}</div>
                            {(result.summary.liveFavourites > 0 || result.summary.reminders > 0) && (
                                <div>
                                    {result.summary.liveFavourites} Live TV favourite{result.summary.liveFavourites === 1 ? '' : 's'}
                                    {result.summary.reminders > 0 && <> · {result.summary.reminders} reminder{result.summary.reminders === 1 ? '' : 's'}</>}
                                </div>
                            )}
                        </div>
                    )}
                    {result.expires_at && (
                        <p style={{ ...blurbStyle, fontSize: 12, opacity: 0.7, marginBottom: 12 }}>
                            Expires {new Date(result.expires_at).toLocaleString()}
                        </p>
                    )}
                    <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }}>
                        <button
                            data-testid="profile-backup-copy"
                            data-focusable="true"
                            data-focus-style="pill"
                            data-initial-focus="true"
                            tabIndex={0}
                            onClick={copyCode}
                            style={primaryBtnStyle}
                        >
                            {copied ? <Check size={16} /> : <Copy size={16} />}
                            {copied ? 'Copied' : 'Copy code'}
                        </button>
                        <button
                            data-testid="profile-backup-done"
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => navigate('/profiles')}
                            style={ghostBtnStyle}
                        >
                            Done
                        </button>
                    </div>
                </div>
            )}
        </div>
    );
}

/* ─────────── Local styles (kept inline; this page is self-contained) ─────────── */

const panelStyle = {
    maxWidth: 540,
    width: '100%',
    background: 'rgba(255,255,255,0.04)',
    border: '1px solid rgba(255,255,255,0.08)',
    borderRadius: 20,
    padding: '32px 36px',
    textAlign: 'center',
    display: 'flex',
    flexDirection: 'column',
    alignItems: 'center',
    gap: 8,
};

const iconCircleStyle = {
    width: 64, height: 64, borderRadius: 999,
    background: 'rgba(93,200,255,0.10)',
    border: '1px solid rgba(93,200,255,0.36)',
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    marginBottom: 8,
};

const titleStyle = {
    fontSize: 'clamp(26px, 3vw, 36px)',
    letterSpacing: '-0.02em',
    marginBottom: 6,
    color: 'var(--vesper-text-1, #E6EAF0)',
};

const blurbStyle = {
    fontSize: 14,
    lineHeight: 1.55,
    color: 'var(--vesper-text-2, #9DA5B5)',
    maxWidth: 460,
    margin: '0 auto 18px',
};

const primaryBtnStyle = {
    height: 50, padding: '0 22px', borderRadius: 999,
    background: 'var(--vesper-blue, #5DC8FF)', color: 'var(--vesper-bg-0, #06080F)',
    border: 'none', fontSize: 14, fontWeight: 700,
    letterSpacing: '0.04em', textTransform: 'uppercase',
    display: 'inline-flex', alignItems: 'center', gap: 8,
    cursor: 'pointer',
};

const ghostBtnStyle = {
    height: 50, padding: '0 22px', borderRadius: 999,
    background: 'rgba(255,255,255,0.06)', color: 'var(--vesper-text-1, #E6EAF0)',
    border: '1px solid rgba(255,255,255,0.18)',
    fontSize: 14, fontWeight: 600,
    cursor: 'pointer',
};
