import React, { useState, useRef, useEffect } from 'react';
import { Loader2, Tv, Server, User, Lock, ArrowRight, AlertCircle, Trash2 } from 'lucide-react';
import TVKeyboard from './TVKeyboard';
import { authenticate, saveProvider, listProviders, setActiveProvider, removeProvider } from '@/lib/xtream';

/**
 * Xtream Codes provider login.
 *
 * Steps: pick existing OR enter new (Name → URL → Username → Password).
 * Each step uses the themed on-screen TVKeyboard so the Android IME
 * never appears.  After a successful authenticate() the provider is
 * saved to localStorage and the parent calls `onAuthed`.
 */
const STEPS = ['name', 'url', 'username', 'password'];

const LABELS = {
    name: { eyebrow: 'STEP 1 · LABEL', title: 'Give it a name', icon: Tv, placeholder: 'My provider' },
    url:  { eyebrow: 'STEP 2 · SERVER', title: 'Server URL', icon: Server, placeholder: 'http://server.com:8080' },
    username: { eyebrow: 'STEP 3 · USERNAME', title: 'Your username', icon: User, placeholder: 'username' },
    password: { eyebrow: 'STEP 4 · PASSWORD', title: 'Your password', icon: Lock, placeholder: 'password' },
};

export default function XtreamLogin({ onAuthed, onCancel }) {
    const existing = listProviders();
    const [view, setView] = useState(existing.length ? 'pick' : 'form');
    const [step, setStep] = useState(0);
    const [busy, setBusy] = useState(false);
    const [err, setErr] = useState('');
    const [form, setForm] = useState({
        name: '',
        url: '',
        username: '',
        password: '',
    });

    const current = STEPS[step];
    const Spec = LABELS[current];

    const setField = (v) => setForm((f) => ({ ...f, [current]: v }));

    const submitStep = async () => {
        const v = (form[current] || '').trim();
        if (v.length < 1) return;
        setErr('');
        if (step < STEPS.length - 1) {
            setStep(step + 1);
            return;
        }
        // Final step — authenticate
        try {
            setBusy(true);
            // Parse URL into host + port + scheme
            let host = form.url.trim();
            let scheme = 'http';
            let port = '80';
            if (host.startsWith('https://')) { scheme = 'https'; port = '443'; host = host.slice(8); }
            else if (host.startsWith('http://')) { scheme = 'http'; host = host.slice(7); }
            host = host.replace(/\/$/, '');
            if (host.includes(':')) { [host, port] = host.split(':'); port = port.split('/')[0]; }
            const provider = {
                name: form.name.trim(),
                host, port, scheme,
                username: form.username.trim(),
                password: form.password,
            };
            const res = await authenticate(provider);
            if (!res?.ok) throw new Error('auth failed');
            const saved = saveProvider({ ...provider, providerId: res.providerId });
            setActiveProvider(saved.id);
            onAuthed?.(saved);
        } catch (e) {
            setErr(e?.response?.data?.detail || e?.message || 'Could not connect. Check your details and try again.');
        } finally {
            setBusy(false);
        }
    };

    // List view — existing providers
    if (view === 'pick') {
        return (
            <div data-testid="xtream-pick" className="flex flex-col" style={{ gap: 20, maxWidth: 880 }}>
                <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                    LIVE TV · Pick a provider
                </div>
                <h1 className="vesper-display" style={{ fontSize: 'clamp(36px, 4vw, 56px)', letterSpacing: '-0.025em', lineHeight: 1 }}>
                    Your providers
                </h1>
                <div className="flex flex-col" style={{ gap: 10, marginTop: 12 }}>
                    {existing.map((p) => (
                        <ProviderRow
                            key={p.id}
                            provider={p}
                            onPick={() => { setActiveProvider(p.id); onAuthed?.(p); }}
                            onDelete={() => { removeProvider(p.id); const left = listProviders(); if (left.length === 0) setView('form'); }}
                        />
                    ))}
                    <button
                        data-testid="xtream-add-new"
                        data-focusable="true"
                        data-focus-style="tile"
                        tabIndex={0}
                        onClick={() => { setView('form'); setStep(0); setForm({ name: '', url: '', username: '', password: '' }); }}
                        className="rounded-2xl text-left flex items-center gap-4"
                        style={{
                            padding: '18px 22px',
                            border: '1px dashed rgba(var(--vesper-blue-rgb), 0.55)',
                            background: 'rgba(var(--vesper-blue-rgb), 0.06)',
                            cursor: 'pointer', color: 'var(--vesper-text)',
                        }}
                    >
                        <div style={{ width: 40, height: 40, borderRadius: '50%', background: 'rgba(var(--vesper-blue-rgb),0.18)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--vesper-blue-bright)', fontSize: 22, fontWeight: 300 }}>+</div>
                        <div>
                            <div style={{ fontSize: 18, fontWeight: 700 }}>Add another provider</div>
                            <div style={{ fontSize: 13, color: 'var(--vesper-text-2)' }}>Connect a new Xtream Codes server</div>
                        </div>
                    </button>
                </div>
                {onCancel && (
                    <button
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onClick={onCancel}
                        className="vesper-mono"
                        style={{
                            alignSelf: 'flex-start', marginTop: 8,
                            padding: '8px 16px', background: 'transparent', border: 'none',
                            color: 'var(--vesper-text-3)', fontSize: 11, letterSpacing: '0.22em',
                            textTransform: 'uppercase', cursor: 'pointer',
                        }}
                    >
                        Back
                    </button>
                )}
            </div>
        );
    }

    // Wizard form view
    return (
        <div data-testid="xtream-login" className="flex flex-col items-center" style={{ width: '100%' }}>
            <div className="flex flex-col items-center" style={{ maxWidth: 780, width: '100%', gap: 8 }}>
                <div style={{
                    width: 64, height: 64, borderRadius: 999,
                    background: 'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb),0.4), rgba(var(--vesper-blue-rgb),0.1) 70%)',
                    border: '2px solid rgba(var(--vesper-blue-rgb),0.55)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: 'var(--vesper-blue-bright)',
                }}>
                    <Spec.icon size={26} strokeWidth={1.8} />
                </div>
                <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                    {Spec.eyebrow}
                </div>
                <h2 className="vesper-display" style={{ fontSize: 'clamp(22px, 2.4vw, 34px)', letterSpacing: '-0.02em' }}>
                    {Spec.title}
                </h2>

                {/* Display-only pill (no IME) */}
                <div
                    data-testid={`xtream-input-${current}`}
                    className="flex items-center gap-3"
                    style={{
                        width: '100%', maxWidth: 560, height: 50, padding: '0 22px', borderRadius: 999,
                        background: 'linear-gradient(180deg, rgba(255,255,255,0.07), rgba(255,255,255,0.03))',
                        border: '1px solid rgba(var(--vesper-blue-rgb), 0.35)',
                        marginTop: 4,
                    }}
                >
                    <div style={{
                        flex: 1, fontSize: 17, fontWeight: 500, letterSpacing: '-0.01em',
                        color: form[current] ? 'var(--vesper-text)' : 'var(--vesper-text-3)',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    }}>
                        {current === 'password' && form[current] ? '•'.repeat(form[current].length) : (form[current] || Spec.placeholder)}
                        <span aria-hidden="true" style={{
                            display: 'inline-block', width: 2, height: 18, marginLeft: 4,
                            verticalAlign: 'middle', background: 'var(--vesper-blue-bright)',
                            animation: 'vesperPulse 1100ms infinite', borderRadius: 1,
                        }} />
                    </div>
                </div>

                {/* Progress dots */}
                <div className="flex gap-2" style={{ marginTop: 6 }}>
                    {STEPS.map((s, i) => (
                        <span key={s} style={{
                            width: i === step ? 22 : 8, height: 4, borderRadius: 999,
                            background: i <= step ? 'var(--vesper-blue-bright)' : 'rgba(255,255,255,0.12)',
                            transition: 'width 180ms',
                        }} />
                    ))}
                </div>

                <div style={{ marginTop: 8, width: '100%', maxWidth: 720 }}>
                    <TVKeyboard
                        value={form[current]}
                        onChange={setField}
                        onSubmit={submitStep}
                        maxLength={current === 'url' ? 120 : 60}
                        variant={current === 'url' ? 'name' : 'name'}
                    />
                </div>

                {err && (
                    <div data-testid="xtream-err" className="flex items-center gap-2 vesper-mono" style={{ color: '#FF6B6B', fontSize: 12, letterSpacing: '0.16em', textTransform: 'uppercase', marginTop: 6 }}>
                        <AlertCircle size={14} /> {err}
                    </div>
                )}

                <div className="flex items-center gap-3" style={{ marginTop: 8 }}>
                    {step > 0 && (
                        <button
                            data-focusable="true"
                            data-focus-style="quiet"
                            tabIndex={0}
                            onClick={() => { setStep(step - 1); setErr(''); }}
                            className="vesper-mono"
                            style={{
                                padding: '11px 22px', borderRadius: 999, fontSize: 12,
                                letterSpacing: '0.22em', textTransform: 'uppercase',
                                background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)',
                                color: 'var(--vesper-text)', cursor: 'pointer',
                            }}
                        >
                            Back
                        </button>
                    )}
                    <button
                        data-testid="xtream-next"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus={!form[current] ? undefined : 'true'}
                        tabIndex={0}
                        onClick={submitStep}
                        disabled={busy || !form[current]}
                        className="flex items-center gap-2 rounded-full font-sans font-semibold"
                        style={{
                            height: 48, padding: '0 28px', fontSize: 15,
                            background: form[current] && !busy ? 'var(--vesper-blue)' : 'rgba(var(--vesper-blue-rgb),0.25)',
                            color: 'var(--vesper-bg-0)', border: 'none',
                            opacity: form[current] && !busy ? 1 : 0.6,
                            cursor: form[current] && !busy ? 'pointer' : 'not-allowed',
                        }}
                    >
                        {busy ? <Loader2 size={16} className="vesper-spin" /> : null}
                        {step === STEPS.length - 1 ? 'Connect' : 'Next'}
                        {!busy && <ArrowRight size={16} strokeWidth={2.5} />}
                    </button>
                </div>
            </div>
        </div>
    );
}

function ProviderRow({ provider, onPick, onDelete }) {
    const [confirm, setConfirm] = useState(false);
    const timer = useRef(null);
    const startPress = () => {
        if (timer.current) clearTimeout(timer.current);
        timer.current = setTimeout(() => { timer.current = null; setConfirm(true); }, 700);
    };
    const cancelPress = () => { if (timer.current) { clearTimeout(timer.current); timer.current = null; } };
    useEffect(() => () => cancelPress(), []);

    if (confirm) {
        return (
            <div style={{
                padding: '16px 20px', borderRadius: 16,
                border: '1px solid rgba(255,107,107,0.55)',
                background: 'rgba(255,107,107,0.10)',
                display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14,
            }}>
                <div style={{ fontSize: 14, color: 'var(--vesper-text)' }}>Remove <b>{provider.name}</b>?</div>
                <div className="flex gap-2">
                    <button
                        data-focusable="true" data-focus-style="pill" data-initial-focus="true" tabIndex={0}
                        onClick={() => setConfirm(false)}
                        style={{ padding: '7px 14px', borderRadius: 999, background: 'rgba(255,255,255,0.10)', color: '#fff', fontWeight: 600, fontSize: 12, border: 'none' }}
                    >
                        Cancel
                    </button>
                    <button
                        data-focusable="true" data-focus-style="pill" tabIndex={0}
                        onClick={() => { setConfirm(false); onDelete(); }}
                        style={{ padding: '7px 14px', borderRadius: 999, background: '#FF6B6B', color: '#fff', fontWeight: 600, fontSize: 12, border: 'none' }}
                    >
                        Remove
                    </button>
                </div>
            </div>
        );
    }

    return (
        <button
            data-testid={`xtream-provider-${provider.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onPick}
            onMouseDown={startPress}
            onMouseUp={cancelPress}
            onMouseLeave={cancelPress}
            onKeyDown={(e) => { if (e.key === 'Enter' && !e.repeat) startPress(); }}
            onKeyUp={(e) => {
                if (e.key === 'Enter') {
                    const wasShort = !!timer.current;
                    cancelPress();
                    if (wasShort) onPick();
                }
            }}
            className="rounded-2xl text-left flex items-center gap-4"
            style={{
                padding: '18px 22px',
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                cursor: 'pointer', color: 'var(--vesper-text)',
            }}
        >
            <div style={{ width: 40, height: 40, borderRadius: '50%', background: 'rgba(var(--vesper-blue-rgb),0.18)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--vesper-blue-bright)' }}>
                <Tv size={18} strokeWidth={2} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 16, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{provider.name}</div>
                <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.18em', color: 'var(--vesper-text-3)', marginTop: 2 }}>
                    {provider.host}{provider.port && provider.port !== '80' && provider.port !== '443' ? `:${provider.port}` : ''}
                </div>
            </div>
            <div className="vesper-mono" style={{ fontSize: 9, letterSpacing: '0.22em', color: 'var(--vesper-text-3)' }}>
                HOLD = REMOVE
            </div>
            <Trash2 size={14} color="var(--vesper-text-3)" strokeWidth={1.8} />
        </button>
    );
}
