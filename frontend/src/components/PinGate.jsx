import React, { useState, useRef, useEffect } from 'react';
import { X } from 'lucide-react';

/**
 * Modal-style 4-digit PIN gate.  Used by ProfileSelect to confirm
 * a parent before activating a PIN-protected profile.  Auto-focuses
 * the first digit and submits the moment all four are filled.
 *
 * Props:
 *   title:        headline (e.g. "Enter Mum's PIN")
 *   subtitle:     subline
 *   onSuccess(pin) — fired only on full 4-digit input; parent
 *                   verifies the value, then either dismisses or
 *                   sets `error` and resets via `resetSignal`.
 *   onCancel():   user pressed X / Esc
 *   error:        external error string (e.g. "Wrong PIN")
 *   resetSignal:  any value change clears the digits + error focus
 */
export default function PinGate({
    title,
    subtitle,
    onSuccess,
    onCancel,
    error,
    resetSignal,
}) {
    const [pin, setPin] = useState(['', '', '', '']);
    const inputs = useRef([]);

    useEffect(() => {
        const t = setTimeout(() => inputs.current[0]?.focus(), 80);
        return () => clearTimeout(t);
    }, []);

    useEffect(() => {
        setPin(['', '', '', '']);
        const t = setTimeout(() => inputs.current[0]?.focus(), 60);
        return () => clearTimeout(t);
    }, [resetSignal]);

    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape') onCancel?.();
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [onCancel]);

    const onDigit = (i, val) => {
        const v = val.replace(/[^\d]/g, '').slice(-1);
        const next = [...pin];
        next[i] = v;
        setPin(next);
        if (v && i < 3) inputs.current[i + 1]?.focus();
        if (next.every((d) => d)) onSuccess?.(next.join(''));
    };

    return (
        <div
            data-testid="pin-gate"
            className="fixed inset-0 z-[80] flex items-center justify-center"
            style={{
                background: 'rgba(6,8,15,0.86)',
                backdropFilter: 'blur(10px)',
            }}
        >
            <div
                className="relative flex flex-col items-center"
                style={{
                    width: 'min(440px, 92vw)',
                    padding: '40px 32px 36px',
                    borderRadius: 24,
                    background:
                        'linear-gradient(180deg, rgba(26,34,56,0.96), rgba(16,22,40,0.96))',
                    border: '1px solid rgba(255,255,255,0.10)',
                    boxShadow: '0 24px 64px rgba(0,0,0,0.55)',
                }}
            >
                <button
                    data-testid="pin-gate-close"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={onCancel}
                    className="absolute top-4 right-4 flex items-center justify-center rounded-full"
                    style={{
                        width: 36,
                        height: 36,
                        background: 'rgba(255,255,255,0.08)',
                        border: '1px solid rgba(255,255,255,0.12)',
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    <X size={16} strokeWidth={2} />
                </button>

                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)',
                        marginBottom: 14,
                    }}
                >
                    PROFILE LOCKED
                </div>
                <h2
                    className="vesper-display text-center"
                    style={{
                        fontSize: 28,
                        letterSpacing: '-0.025em',
                        lineHeight: 1.05,
                        marginBottom: 8,
                    }}
                >
                    {title}
                </h2>
                {subtitle && (
                    <p
                        className="text-center"
                        style={{
                            color: 'var(--vesper-text-2)',
                            fontSize: 14,
                            marginBottom: 24,
                        }}
                    >
                        {subtitle}
                    </p>
                )}

                <div className="flex gap-3 mb-5">
                    {pin.map((d, i) => (
                        <input
                            key={i}
                            data-testid={`pin-gate-digit-${i}`}
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
                                width: 56,
                                height: 70,
                                fontSize: 30,
                                fontWeight: 700,
                                borderRadius: 12,
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
                        data-testid="pin-gate-error"
                        style={{
                            color: '#FCA5A5',
                            fontSize: 13,
                            background: 'rgba(239,68,68,0.12)',
                            padding: '6px 14px',
                            borderRadius: 999,
                            border: '1px solid rgba(239,68,68,0.32)',
                        }}
                    >
                        {error}
                    </div>
                )}
            </div>
        </div>
    );
}
