import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { getKidsConfig, saveKidsConfig } from '@/lib/profiles';

/**
 * <KidsSetup/> — first-time configuration popup for the Kids
 * profile.  Shown automatically the first time a user activates
 * Kids and the PIN is still empty.
 *
 * Three-step inline wizard (no router transitions):
 *   1. Choose a 4-digit PIN.
 *   2. Confirm the PIN.
 *   3. Pick max content rating (movies + TV).
 *
 * On completion: writes the kids-config to localStorage and
 * navigates to "/" so the kid lands on KidsHome.
 *
 * Until the wizard is finished, the kid is held on this screen by
 * App.js routing — they cannot reach Home / Movies / TV without
 * configured parental controls.
 */
const MOVIE_RATINGS = [
    { v: 'G',     label: 'G — All ages (toddlers safe)' },
    { v: 'PG',    label: 'PG — Parental Guidance (7+)' },
    { v: 'M',     label: 'M — Mature themes (13+, AU)' },
    { v: 'PG-13', label: 'PG-13 — Older kids (13+, US)' },
];
const TV_RATINGS = [
    { v: 'TV-Y',  label: 'TV-Y — Tiny tots (1-3 yrs)' },
    { v: 'TV-Y7', label: 'TV-Y7 — Little ones (4-7 yrs)' },
    { v: 'TV-G',  label: 'TV-G — All ages' },
    { v: 'TV-PG', label: 'TV-PG — Parental Guidance' },
];

export default function KidsSetup() {
    useSpatialFocus();
    const navigate = useNavigate();
    const cfg = getKidsConfig();
    const [step, setStep] = useState(1);
    const [pin, setPin]     = useState(['', '', '', '']);
    const [pin2, setPin2]   = useState(['', '', '', '']);
    const [movieMax, setMovieMax] = useState(cfg.maxRatingMovie || 'PG');
    const [tvMax, setTvMax]       = useState(cfg.maxRatingSeries || 'TV-PG');
    const [error, setError] = useState('');
    const refs = useRef([]);

    useEffect(() => {
        // Auto-focus the first PIN box on mount + each step change.
        const t = setTimeout(() => refs.current[0]?.focus(), 300);
        return () => clearTimeout(t);
    }, [step]);

    const onDigit = (target, i, val) => {
        const v = val.replace(/[^\d]/g, '').slice(-1);
        const next = [...target];
        next[i] = v;
        return next;
    };

    const handleDigit1 = (i, val) => {
        const next = onDigit(pin, i, val);
        setPin(next);
        setError('');
        if (next[i] && i < 3) refs.current[i + 1]?.focus();
        if (next.every((d) => d)) {
            // Move to confirm step.
            setStep(2);
        }
    };
    const handleDigit2 = (i, val) => {
        const next = onDigit(pin2, i, val);
        setPin2(next);
        setError('');
        if (next[i] && i < 3) refs.current[i + 1]?.focus();
        if (next.every((d) => d)) {
            if (next.join('') !== pin.join('')) {
                setError("PINs don't match.  Start again.");
                setPin(['', '', '', '']);
                setPin2(['', '', '', '']);
                setStep(1);
                refs.current[0]?.focus();
            } else {
                setStep(3);
            }
        }
    };

    const finish = () => {
        saveKidsConfig({
            pin: pin.join(''),
            maxRatingMovie: movieMax,
            maxRatingSeries: tvMax,
            contentTypes: 'both',
        });
        navigate('/', { replace: true });
    };

    return (
        <div
            data-testid="kids-setup"
            className="relative w-screen h-[100dvh] flex flex-col items-center justify-center overflow-hidden"
            style={{
                background:
                    'radial-gradient(ellipse 80% 60% at 50% 20%, rgba(43,182,255,0.18) 0%, transparent 65%), var(--vesper-bg-0)',
            }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)',
                    marginBottom: 14,
                }}
            >
                KIDS · FIRST-TIME SETUP
            </div>
            <h1
                className="vesper-display"
                style={{
                    fontSize: 'clamp(34px, 4.2vw, 56px)',
                    letterSpacing: '-0.02em',
                    lineHeight: 1.05,
                    marginBottom: 14,
                    textAlign: 'center',
                    padding: '0 24px',
                }}
            >
                {step === 1 && 'Set a parent PIN'}
                {step === 2 && 'Confirm your PIN'}
                {step === 3 && 'Pick max content rating'}
            </h1>
            <p
                style={{
                    color: 'var(--vesper-text-2)',
                    marginBottom: 36,
                    maxWidth: 520,
                    textAlign: 'center',
                    padding: '0 24px',
                }}
            >
                {step === 1 && 'Used to exit Kids mode.  Keep it somewhere safe — the kid should never see it.'}
                {step === 2 && 'Enter the same 4 digits again to lock it in.'}
                {step === 3 && 'Only content at or below these ratings will show inside Kids mode.  You can change this later.'}
            </p>

            {(step === 1 || step === 2) && (
                <div className="flex gap-4 mb-6" key={step /* re-mount on step change */}>
                    {(step === 1 ? pin : pin2).map((d, i) => (
                        <input
                            key={i}
                            data-testid={`kids-setup-pin-${step}-${i}`}
                            data-focusable="true"
                            data-focus-style="pill"
                            ref={(el) => (refs.current[i] = el)}
                            type="tel"
                            inputMode="numeric"
                            maxLength={1}
                            value={d}
                            onChange={(e) =>
                                step === 1
                                    ? handleDigit1(i, e.target.value)
                                    : handleDigit2(i, e.target.value)
                            }
                            onKeyDown={(e) => {
                                if (e.key === 'Backspace' && !d && i > 0)
                                    refs.current[i - 1]?.focus();
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
            )}

            {step === 3 && (
                <div className="flex flex-col gap-6 w-full" style={{ maxWidth: 560, padding: '0 24px' }}>
                    <RatingPicker
                        label="Movies"
                        options={MOVIE_RATINGS}
                        value={movieMax}
                        onChange={setMovieMax}
                    />
                    <RatingPicker
                        label="TV Shows"
                        options={TV_RATINGS}
                        value={tvMax}
                        onChange={setTvMax}
                    />
                    <button
                        data-testid="kids-setup-finish"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={finish}
                        className="flex items-center justify-center gap-3 rounded-full mt-4 self-center"
                        style={{
                            height: 56,
                            padding: '0 36px',
                            background: 'var(--vesper-blue)',
                            color: '#04060B',
                            fontSize: 15,
                            fontWeight: 700,
                            letterSpacing: '0.04em',
                            border: 'none',
                        }}
                    >
                        <ShieldCheck size={18} strokeWidth={2.4} />
                        Finish setup
                    </button>
                </div>
            )}

            {error && (
                <div
                    data-testid="kids-setup-error"
                    style={{
                        color: '#FCA5A5',
                        fontSize: 14,
                        background: 'rgba(239,68,68,0.12)',
                        padding: '8px 16px',
                        borderRadius: 999,
                        border: '1px solid rgba(239,68,68,0.32)',
                        marginTop: 8,
                    }}
                >
                    {error}
                </div>
            )}
        </div>
    );
}

function RatingPicker({ label, options, value, onChange }) {
    return (
        <div>
            <div
                style={{
                    fontSize: 11,
                    letterSpacing: '0.22em',
                    color: 'var(--vesper-text-3)',
                    textTransform: 'uppercase',
                    marginBottom: 10,
                }}
            >
                {label}
            </div>
            <div className="flex flex-wrap gap-2">
                {options.map((opt) => {
                    const sel = opt.v === value;
                    return (
                        <button
                            key={opt.v}
                            data-testid={`kids-rating-${label.toLowerCase()}-${opt.v}`}
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => onChange(opt.v)}
                            className="rounded-full"
                            style={{
                                padding: '10px 18px',
                                fontSize: 14,
                                fontWeight: sel ? 700 : 500,
                                background: sel ? 'var(--vesper-blue)' : 'rgba(255,255,255,0.06)',
                                color: sel ? '#04060B' : 'var(--vesper-text)',
                                border: sel
                                    ? '1px solid var(--vesper-blue)'
                                    : '1px solid rgba(255,255,255,0.18)',
                            }}
                        >
                            {opt.label}
                        </button>
                    );
                })}
            </div>
        </div>
    );
}
