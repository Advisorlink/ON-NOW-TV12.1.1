/**
 * <ProfileLoad/> — premium, focused page for restoring profiles
 * + settings from a previously-saved backup.
 *
 * Reuses /api/backup/restore (same endpoint Settings → Backups
 * uses), so any 6-character code + 4-digit PIN produced from
 * Settings on another device works here.  Critical UX detail:
 * this route is in NO_PROFILE_REQUIRED, so the user can reach it
 * before ANY profile exists — that's the whole point.
 *
 * Flow:
 *   1. Code entry      — 6 characters (A-Z 0-9).
 *   2. PIN entry       — 4 digits.
 *   3. Confirm preview — show what'll be restored (profile count,
 *                        library count, etc.) so the user knows
 *                        what they're about to overwrite.
 *   4. Apply           — applyBackupPayload + hard reload.
 */
import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
    ArrowLeft, Download, Loader2, AlertTriangle,
    KeyRound, ShieldCheck, Check,
} from 'lucide-react';
import PinGate from '@/components/PinGate';
import { applyBackupPayload, summarizeBackupPayload } from '@/lib/profileBackup';
import { clearActiveProfile } from '@/lib/profiles';

export default function ProfileLoad() {
    const navigate = useNavigate();
    const API = process.env.REACT_APP_BACKEND_URL;

    // Steps: code → pin → confirm → applying.
    const [step, setStep] = React.useState('code');
    const [code, setCode] = React.useState('');
    const [busy, setBusy] = React.useState(false);
    const [err, setErr] = React.useState('');
    const [preview, setPreview] = React.useState(null);
    const [pinReset, setPinReset] = React.useState(0);

    const fetchBackup = async (pinDigits) => {
        setBusy(true);
        setErr('');
        try {
            const res = await fetch(`${API}/api/backup/restore`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code, pin: pinDigits }),
            });
            if (!res.ok) {
                const j = await res.json().catch(() => ({}));
                throw new Error(j.detail || `Server replied ${res.status}.`);
            }
            const j = await res.json();
            setPreview(j);
            setStep('confirm');
        } catch (e) {
            setErr(e.message || 'Could not load that backup.');
            setPinReset((x) => x + 1);
        } finally {
            setBusy(false);
        }
    };

    const applyAndReload = () => {
        if (!preview?.payload) return;
        setStep('applying');
        applyBackupPayload(preview.payload);
        try { clearActiveProfile(); } catch { /* ignore */ }
        // Hard reload so every page re-reads the new localStorage.
        setTimeout(() => { window.location.href = '/profiles'; }, 600);
    };

    return (
        <div
            data-testid="profile-load"
            className="relative w-screen h-[100dvh] flex flex-col items-center"
            style={{
                background:
                    'radial-gradient(circle at 50% 0%, rgba(93,200,255,0.18) 0%, transparent 55%), #06080F',
                color: '#E6EAF2',
                overflow: 'hidden',
            }}
        >
            {/* Top bar with Back button */}
            <div
                style={{
                    position: 'absolute', top: 0, left: 0, right: 0,
                    padding: 'clamp(20px, 3vh, 36px)',
                    display: 'flex', alignItems: 'center', gap: 14,
                    zIndex: 5,
                }}
            >
                <button
                    data-testid="profile-load-back"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={() => navigate('/profiles')}
                    style={{
                        height: 44, padding: '0 18px', borderRadius: 999,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.14)',
                        color: '#C7CFDB',
                        fontSize: 13, fontWeight: 600,
                        display: 'inline-flex', alignItems: 'center', gap: 8,
                        cursor: 'pointer',
                    }}
                >
                    <ArrowLeft size={16} />
                    Back
                </button>
            </div>

            <div
                className="flex-1 flex flex-col items-center justify-center w-full"
                style={{ padding: '32px 24px', maxWidth: 640, margin: '0 auto' }}
            >
                {step === 'code' && (
                    <CodeStep
                        code={code}
                        setCode={setCode}
                        onNext={() => { setErr(''); setStep('pin'); }}
                        err={err}
                    />
                )}

                {step === 'pin' && (
                    <PinGate
                        title="Enter your backup PIN"
                        subtitle={`4-digit PIN for backup code ${code}.`}
                        onSuccess={fetchBackup}
                        onCancel={() => { setErr(''); setStep('code'); }}
                        error={err}
                        resetSignal={pinReset}
                    />
                )}

                {step === 'confirm' && preview && (
                    <ConfirmStep
                        preview={preview}
                        onConfirm={applyAndReload}
                        onCancel={() => { setStep('code'); setCode(''); setPreview(null); }}
                    />
                )}

                {step === 'applying' && (
                    <div style={{ textAlign: 'center' }}>
                        <Loader2 size={42} className="vesper-spin" style={{ color: '#5DC8FF', marginBottom: 18 }} />
                        <div className="vesper-display" style={{ fontSize: 26, fontWeight: 600, marginBottom: 6 }}>
                            Restoring your profile…
                        </div>
                        <div style={{ fontSize: 14, color: '#9DA5B5' }}>
                            One moment. The app will reopen automatically.
                        </div>
                    </div>
                )}
            </div>
        </div>
    );
}

/* ---------------- CODE ENTRY ---------------- */

function CodeStep({ code, setCode, onNext, err }) {
    const KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    const submit = () => {
        if (code.length !== 6) return;
        onNext();
    };
    return (
        <>
            <div
                style={{
                    width: 78, height: 78, borderRadius: 22,
                    background: 'rgba(93,200,255,0.10)',
                    border: '1px solid rgba(93,200,255,0.45)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    marginBottom: 20,
                }}
            >
                <Download size={36} color="#5DC8FF" strokeWidth={2.2} />
            </div>

            <div className="vesper-mono" style={{
                fontSize: 11, letterSpacing: '0.36em',
                color: '#5DC8FF', fontWeight: 700, marginBottom: 12,
            }}>
                LOAD EXISTING PROFILE
            </div>
            <h1
                className="vesper-display"
                style={{
                    fontSize: 'clamp(32px, 4.4vw, 48px)',
                    lineHeight: 1.05, letterSpacing: '-0.02em',
                    textAlign: 'center', margin: 0, marginBottom: 12,
                }}
            >
                Enter your backup code
            </h1>
            <p style={{
                fontSize: 14, color: '#9DA5B5',
                textAlign: 'center', marginBottom: 28, maxWidth: 460,
            }}>
                The 6-character code you saved under Settings → Backups
                on your other device.
            </p>

            {/* 6 character slots */}
            <div
                data-testid="profile-load-code-slots"
                style={{
                    display: 'flex', gap: 10, marginBottom: 22,
                    justifyContent: 'center', flexWrap: 'wrap',
                }}
            >
                {[0, 1, 2, 3, 4, 5].map((i) => {
                    const ch = code[i];
                    const filled = !!ch;
                    const active = code.length === i;
                    return (
                        <div
                            key={i}
                            style={{
                                width: 52, height: 64, borderRadius: 12,
                                background: filled
                                    ? 'rgba(93,200,255,0.10)'
                                    : 'rgba(255,255,255,0.04)',
                                border: '1.5px solid ' + (active
                                    ? '#5DC8FF'
                                    : filled
                                        ? 'rgba(93,200,255,0.45)'
                                        : 'rgba(255,255,255,0.14)'),
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                fontFamily: 'monospace',
                                fontSize: 26, fontWeight: 700,
                                color: filled ? '#fff' : 'rgba(255,255,255,0.18)',
                                boxShadow: active
                                    ? '0 0 0 4px rgba(93,200,255,0.18)'
                                    : 'none',
                                transition: 'border 120ms, box-shadow 120ms',
                            }}
                        >
                            {ch || ''}
                        </div>
                    );
                })}
            </div>

            {/* On-screen keypad (TV-friendly).  Hard-coded so we don't
                depend on the TVKeyboard component's currently-fragile
                touch handling. */}
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(9, 1fr)',
                    gap: 6,
                    maxWidth: 520,
                    width: '100%',
                    marginBottom: 16,
                }}
            >
                {KEYS.split('').map((k) => (
                    <button
                        key={k}
                        data-testid={`profile-load-key-${k}`}
                        data-focusable="true"
                        tabIndex={0}
                        disabled={code.length >= 6}
                        onClick={() => {
                            if (code.length < 6) setCode((code + k).toUpperCase());
                        }}
                        style={{
                            height: 44, borderRadius: 8,
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            color: '#E6EAF2',
                            fontFamily: 'monospace',
                            fontSize: 15, fontWeight: 600,
                            cursor: code.length >= 6 ? 'not-allowed' : 'pointer',
                            opacity: code.length >= 6 ? 0.45 : 1,
                        }}
                    >
                        {k}
                    </button>
                ))}
            </div>

            <div style={{ display: 'flex', gap: 12, justifyContent: 'center' }}>
                <button
                    data-testid="profile-load-backspace"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={() => setCode(code.slice(0, -1))}
                    disabled={code.length === 0}
                    style={{
                        height: 44, padding: '0 18px', borderRadius: 999,
                        background: 'rgba(255,255,255,0.04)',
                        border: '1px solid rgba(255,255,255,0.14)',
                        color: '#C7CFDB', fontSize: 13, fontWeight: 600,
                        opacity: code.length === 0 ? 0.45 : 1,
                        cursor: code.length === 0 ? 'not-allowed' : 'pointer',
                    }}
                >
                    Backspace
                </button>
                <button
                    data-testid="profile-load-next"
                    data-focusable="true"
                    data-initial-focus={code.length === 6 ? 'true' : undefined}
                    tabIndex={0}
                    onClick={submit}
                    disabled={code.length !== 6}
                    style={{
                        height: 44, padding: '0 26px', borderRadius: 999,
                        background: code.length === 6
                            ? '#5DC8FF' : 'rgba(255,255,255,0.06)',
                        color: code.length === 6 ? '#06080F' : '#6B7587',
                        border: 'none',
                        fontSize: 14, fontWeight: 800,
                        letterSpacing: '0.04em',
                        textTransform: 'uppercase',
                        cursor: code.length === 6 ? 'pointer' : 'not-allowed',
                    }}
                >
                    Continue
                </button>
            </div>

            {err && (
                <div
                    data-testid="profile-load-error"
                    style={{
                        marginTop: 18,
                        padding: '10px 14px',
                        background: 'rgba(255,107,122,0.12)',
                        border: '1px solid rgba(255,107,122,0.36)',
                        borderRadius: 10,
                        color: '#FF8896', fontSize: 12, fontWeight: 600,
                        display: 'inline-flex', alignItems: 'center', gap: 8,
                    }}
                >
                    <AlertTriangle size={14} />
                    {err}
                </div>
            )}
        </>
    );
}

/* ---------------- CONFIRM STEP ---------------- */

function ConfirmStep({ preview, onConfirm, onCancel }) {
    // Real contents of the snapshot — profile NAMES + item counts —
    // so the user sees exactly what they're about to load.
    const s = summarizeBackupPayload(preview?.payload || {});
    return (
        <>
            <div
                style={{
                    width: 78, height: 78, borderRadius: 22,
                    background: 'rgba(72,201,127,0.10)',
                    border: '1px solid rgba(72,201,127,0.45)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    marginBottom: 20,
                }}
            >
                <ShieldCheck size={36} color="#48C97F" strokeWidth={2.2} />
            </div>
            <div className="vesper-mono" style={{
                fontSize: 11, letterSpacing: '0.36em',
                color: '#48C97F', fontWeight: 700, marginBottom: 12,
            }}>
                BACKUP FOUND
            </div>
            <h1
                className="vesper-display"
                style={{
                    fontSize: 'clamp(28px, 3.8vw, 40px)',
                    lineHeight: 1.05, letterSpacing: '-0.02em',
                    textAlign: 'center', margin: 0, marginBottom: 8,
                }}
            >
                Restore this backup?
            </h1>
            <p style={{
                fontSize: 14, color: '#9DA5B5',
                textAlign: 'center', marginBottom: 22, maxWidth: 480,
            }}>
                This will replace any profiles and settings currently on
                this device with the saved snapshot.
            </p>
            <div
                style={{
                    display: 'grid', gap: 10,
                    gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))',
                    width: '100%', maxWidth: 460, marginBottom: 14,
                }}
            >
                <Stat label="Profiles" value={s.profileCount} />
                <Stat label="Library items" value={s.libraryCount} />
                <Stat label="Continue watching" value={s.cwCount} />
            </div>
            {s.profileNames.length > 0 && (
                <div
                    data-testid="profile-load-summary-names"
                    style={{
                        fontSize: 13, color: '#C7CFDB', textAlign: 'center',
                        marginBottom: 24, maxWidth: 460, lineHeight: 1.6,
                    }}
                >
                    Loading profiles:{' '}
                    <strong style={{ color: '#FFFFFF' }}>{s.profileNames.join(', ')}</strong>
                    {(s.liveFavourites > 0 || s.reminders > 0) && (
                        <div style={{ fontSize: 12, color: '#9DA5B5', marginTop: 2 }}>
                            plus {s.liveFavourites} Live TV favourite{s.liveFavourites === 1 ? '' : 's'}
                            {s.reminders > 0 && <> and {s.reminders} reminder{s.reminders === 1 ? '' : 's'}</>}
                        </div>
                    )}
                </div>
            )}
            <div style={{ display: 'flex', gap: 12 }}>
                <button
                    data-testid="profile-load-cancel"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={onCancel}
                    style={{
                        height: 48, padding: '0 22px', borderRadius: 999,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.14)',
                        color: '#C7CFDB',
                        fontSize: 13, fontWeight: 600,
                        cursor: 'pointer',
                    }}
                >
                    Cancel
                </button>
                <button
                    data-testid="profile-load-confirm"
                    data-focusable="true"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={onConfirm}
                    style={{
                        height: 48, padding: '0 26px', borderRadius: 999,
                        background: '#5DC8FF',
                        color: '#06080F',
                        border: 'none',
                        fontSize: 14, fontWeight: 800,
                        letterSpacing: '0.04em',
                        textTransform: 'uppercase',
                        display: 'inline-flex', alignItems: 'center', gap: 8,
                        cursor: 'pointer',
                    }}
                >
                    <Check size={16} />
                    Restore and reload
                </button>
            </div>
        </>
    );
}

function Stat({ label, value }) {
    return (
        <div
            style={{
                padding: '14px 16px', borderRadius: 14,
                background: 'rgba(255,255,255,0.03)',
                border: '1px solid rgba(255,255,255,0.08)',
                textAlign: 'center',
            }}
        >
            <div
                className="vesper-display"
                style={{ fontSize: 26, fontWeight: 700, color: '#fff' }}
            >
                {value}
            </div>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10, letterSpacing: '0.24em',
                    color: '#6B7587', textTransform: 'uppercase', marginTop: 4,
                }}
            >
                {label}
            </div>
        </div>
    );
}
