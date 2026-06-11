import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { getKidsConfig, clearActiveProfile } from '@/lib/profiles';

/**
 * 4-digit PIN entry — used to exit the Kids profile back to the
 * profile picker.  Until correct PIN is entered, the user is
 * trapped inside the Kids experience.
 *
 * If no PIN has been set yet, this page redirects straight to the
 * picker (acts as a no-op so parents can leave Kids freely until
 * they configure a PIN).
 */
export default function KidsExitPin() {
    useSpatialFocus();
    const navigate = useNavigate();
    const cfg = getKidsConfig();
    const [pin, setPin] = useState(['', '', '', '']);
    const [error, setError] = useState('');
    const inputs = useRef([]);

    useEffect(() => {
        // No PIN configured → just let the parent leave.
        if (!cfg.pin) {
            clearActiveProfile();
            navigate('/profiles');
        }
        // Auto-focus first digit on mount.
        const t = setTimeout(() => inputs.current[0]?.focus(), 300);
        return () => clearTimeout(t);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    const onDigit = (i, val) => {
        const v = val.replace(/[^\d]/g, '').slice(-1);
        const next = [...pin];
        next[i] = v;
        setPin(next);
        setError('');
        if (v && i < 3) inputs.current[i + 1]?.focus();
        if (next.every((d) => d)) tryUnlock(next.join(''));
    };

    const tryUnlock = (entered) => {
        if (entered === cfg.pin) {
            clearActiveProfile();
            // v2.8.13 — Per user spec: after a correct PIN the user
            // should be RETURNED TO THE LAUNCHER, not bounce around
            // inside Vesper looking for an exit.  Call the native
            // bridge to finish() the Vesper Activity.  If the
            // bridge isn't available (web-only test / preview),
            // fall back to the profile picker route.
            try {
                if (window.OnNowTV && typeof window.OnNowTV.exitVesperToLauncher === 'function') {
                    window.OnNowTV.exitVesperToLauncher();
                    return;
                }
            } catch { /* ignore — bridge unavailable */ }
            navigate('/profiles');
        } else {
            setError('Incorrect PIN. Try again.');
            setPin(['', '', '', '']);
            inputs.current[0]?.focus();
        }
    };

    return (
        <div
            data-testid="kids-exit-pin"
            className="relative w-screen h-[100dvh] flex flex-col items-center justify-center"
            style={{
                background:
                    'radial-gradient(circle at 50% 30%, rgba(255,200,87,0.12) 0%, transparent 60%), var(--vesper-bg-0)',
            }}
        >
            <button
                data-testid="kids-pin-back"
                data-focusable="true"
                data-focus-style="quiet"
                tabIndex={0}
                onClick={() => navigate('/')}
                className="flex items-center gap-2 rounded-full"
                style={{
                    position: 'absolute',
                    top: 32,
                    left: 32,
                    height: 44,
                    padding: '0 18px',
                    background: 'rgba(255,255,255,0.08)',
                    border: '1px solid rgba(255,255,255,0.16)',
                    color: 'var(--vesper-text-2)',
                    fontSize: 14,
                    fontWeight: 600,
                    zIndex: 5,
                }}
            >
                <ArrowLeft size={16} strokeWidth={2} />
                Back to Kids
            </button>

            <div
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)',
                    marginBottom: 16,
                }}
            >
                PARENT GATE
            </div>
            <h1
                className="vesper-display"
                style={{
                    fontSize: 'clamp(36px, 4.4vw, 60px)',
                    letterSpacing: '-0.025em',
                    lineHeight: 1,
                    marginBottom: 12,
                }}
            >
                Enter your PIN
            </h1>
            <p style={{ color: 'var(--vesper-text-2)', marginBottom: 36 }}>
                4-digit PIN required to exit Kids mode
            </p>

            <div className="flex gap-4 mb-8">
                {pin.map((d, i) => (
                    <input
                        key={i}
                        data-testid={`pin-${i}`}
                        data-focusable="true"
                        data-focus-style="pill"
                        ref={(el) => (inputs.current[i] = el)}
                        type="tel"
                        inputMode="numeric"
                        maxLength={1}
                        value={d}
                        onChange={(e) => onDigit(i, e.target.value)}
                        onKeyDown={(e) => {
                            if (e.key === 'Backspace' && !d && i > 0)
                                inputs.current[i - 1]?.focus();
                        }}
                        className="text-center"
                        style={{
                            width: 64,
                            height: 80,
                            fontSize: 36,
                            fontWeight: 700,
                            borderRadius: 14,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.18)',
                            color: 'var(--vesper-text)',
                            outline: 'none',
                        }}
                    />
                ))}
            </div>

            {error && (
                <div
                    data-testid="pin-error"
                    style={{
                        color: '#FCA5A5',
                        fontSize: 14,
                        background: 'rgba(239,68,68,0.12)',
                        padding: '8px 16px',
                        borderRadius: 999,
                        border: '1px solid rgba(239,68,68,0.32)',
                    }}
                >
                    {error}
                </div>
            )}

            <button
                data-testid="kids-pin-stay"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={() => navigate('/')}
                className="flex items-center gap-2 rounded-full"
                style={{
                    marginTop: 28,
                    height: 48,
                    padding: '0 22px',
                    background: 'rgba(255,212,59,0.14)',
                    color: '#FFD43B',
                    border: '1px solid rgba(255,212,59,0.42)',
                    fontSize: 14,
                    fontWeight: 600,
                }}
            >
                <ArrowLeft size={16} strokeWidth={2.4} />
                Stay in Kids mode
            </button>
        </div>
    );
}
