// v2.8.78 — Kids Settings page (PIN-gated).
//
// User feedback: "put a settings menu for the kids' ratings and all
// that sort of stuff … only accessible with putting the pin number
// in."
//
// Flow:
//   1. User opens /kids/settings → PIN entry phase (4 digits).
//   2. On correct PIN → settings phase: edit content types + max
//      ratings + change PIN.  Saved via existing `saveKidsConfig`.
//
// While inside this page the parent has "passed the gate" but we
// DO NOT clear `__vesperKidsLocked` — we want them to be able to
// tweak settings and click "Back to Kids" without re-PINing the
// whole exit flow.  Closing the page navigates straight back to
// /kids (the home), preserving kids-mode.

import React, { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Save, Tv, Film, Layers, Shield } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { getKidsConfig, saveKidsConfig } from '@/lib/profiles';

const MOVIE_RATINGS = ['G', 'PG', 'M', 'PG-13'];
const TV_RATINGS    = ['TV-Y', 'TV-Y7', 'TV-G', 'TV-PG', 'TV-14'];

export default function KidsSettings() {
    useSpatialFocus();
    const navigate = useNavigate();
    const cfg = getKidsConfig();
    const hasPin = !!(cfg.pin && cfg.pin.length === 4);

    const [phase, setPhase] = useState(hasPin ? 'pin' : 'settings');
    const [pin, setPin] = useState(['', '', '', '']);
    const [pinErr, setPinErr] = useState('');
    const inputs = useRef([]);

    const [draft, setDraft] = useState({
        contentTypes:    cfg.contentTypes    || 'both',
        maxRatingMovie:  cfg.maxRatingMovie  || 'PG',
        maxRatingSeries: cfg.maxRatingSeries || 'TV-PG',
    });
    const [saved, setSaved] = useState(false);

    useEffect(() => {
        if (phase !== 'pin') return;
        const t = setTimeout(() => inputs.current[0]?.focus(), 250);
        return () => clearTimeout(t);
    }, [phase]);

    const onDigit = (i, val) => {
        const v = val.replace(/[^\d]/g, '').slice(-1);
        const next = [...pin]; next[i] = v;
        setPin(next); setPinErr('');
        if (v && i < 3) inputs.current[i + 1]?.focus();
        if (next.every((d) => d)) tryUnlock(next.join(''));
    };
    const tryUnlock = (entered) => {
        if (entered === cfg.pin) { setPhase('settings'); return; }
        setPinErr('Incorrect PIN. Try again.');
        setPin(['', '', '', '']);
        inputs.current[0]?.focus();
    };

    const apply = (patch) => setDraft((d) => ({ ...d, ...patch }));
    const onSave = () => {
        saveKidsConfig(draft);
        setSaved(true);
        setTimeout(() => setSaved(false), 1500);
    };

    if (phase === 'pin') {
        return (
            <div data-testid="kids-settings-pin" className="vesper-kids-pin-root">
                <button
                    data-focusable="true" data-focus-style="quiet" tabIndex={0}
                    onClick={() => navigate('/kids')}
                    className="vesper-kids-pin-back"
                >
                    <ArrowLeft size={16} strokeWidth={2} /> Back to Kids
                </button>
                <div className="vesper-mono" style={{
                    fontSize: 11, letterSpacing: '0.32em',
                    color: 'var(--vesper-blue-bright)', marginBottom: 16,
                }}>
                    PARENT GATE
                </div>
                <h1 className="vesper-display" style={{
                    fontSize: 'clamp(32px, 4vw, 52px)',
                    letterSpacing: '-0.025em', lineHeight: 1, marginBottom: 10,
                }}>
                    Enter PIN to edit Kids settings
                </h1>
                <p style={{ color: 'var(--vesper-text-2)', marginBottom: 32 }}>
                    4-digit PIN required to change ratings &amp; content
                </p>
                <div className="flex gap-4 mb-6">
                    {pin.map((d, i) => (
                        <input
                            key={i}
                            data-testid={`kids-settings-pin-${i}`}
                            data-focusable="true" data-focus-style="pill"
                            ref={(el) => (inputs.current[i] = el)}
                            type="tel" inputMode="numeric" maxLength={1}
                            value={d}
                            onChange={(e) => onDigit(i, e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Backspace' && !d && i > 0) inputs.current[i - 1]?.focus();
                            }}
                            className="text-center"
                            style={{
                                width: 60, height: 76, fontSize: 32, fontWeight: 700,
                                borderRadius: 14,
                                background: 'rgba(255,255,255,0.06)',
                                border: '1px solid rgba(255,255,255,0.18)',
                                color: 'var(--vesper-text)', outline: 'none',
                            }}
                        />
                    ))}
                </div>
                {pinErr && (
                    <div data-testid="kids-settings-pin-error" style={{
                        color: '#FCA5A5', fontSize: 14,
                        background: 'rgba(239,68,68,0.12)',
                        padding: '8px 16px', borderRadius: 999,
                        border: '1px solid rgba(239,68,68,0.32)',
                    }}>{pinErr}</div>
                )}
            </div>
        );
    }

    return (
        <div data-testid="kids-settings" className="vesper-kids-settings-root">
            <header className="vesper-kids-settings-head">
                <button
                    data-focusable="true" data-focus-style="quiet" tabIndex={0}
                    onClick={() => navigate('/kids')}
                    data-testid="kids-settings-back"
                    className="vesper-kids-pin-back"
                    style={{ position: 'static' }}
                >
                    <ArrowLeft size={16} strokeWidth={2} /> Back to Kids
                </button>
                <div style={{ flex: 1 }}>
                    <p className="vesper-mono" style={{
                        fontSize: 11, letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)', margin: 0,
                    }}>KIDS SETTINGS</p>
                    <h1 className="vesper-display" style={{
                        fontSize: 'clamp(28px, 3.4vw, 44px)',
                        letterSpacing: '-0.02em', margin: '4px 0 0',
                    }}>Pick what your kids can watch</h1>
                </div>
                <button
                    data-focusable="true" data-focus-style="pill" tabIndex={0}
                    onClick={onSave} data-testid="kids-settings-save"
                    className="vesper-kids-save-btn"
                >
                    <Save size={16} strokeWidth={2.2} />
                    {saved ? 'Saved!' : 'Save'}
                </button>
            </header>

            <section className="vesper-kids-settings-grid">
                {/* Content types */}
                <div className="vesper-kids-settings-card">
                    <div className="vesper-kids-settings-card__head">
                        <Layers size={20} />
                        <div>
                            <p className="vesper-kids-settings-card__eyebrow">CATALOG</p>
                            <h2 className="vesper-kids-settings-card__title">Content types</h2>
                        </div>
                    </div>
                    <p className="vesper-kids-settings-card__hint">
                        Show movies, TV shows, or both inside Kids.
                    </p>
                    <div className="vesper-kids-settings-options">
                        {[
                            { val: 'both',   label: 'Both',       icon: Layers },
                            { val: 'movies', label: 'Movies only',icon: Film },
                            { val: 'series', label: 'TV only',    icon: Tv },
                        ].map(({ val, label, icon: Icon }) => (
                            <button
                                key={val}
                                data-focusable="true" data-focus-style="tile" tabIndex={0}
                                data-testid={`kids-settings-types-${val}`}
                                className={`vesper-kids-option${draft.contentTypes === val ? ' is-active' : ''}`}
                                onClick={() => apply({ contentTypes: val })}
                            >
                                <Icon size={20} strokeWidth={1.6} />
                                <span>{label}</span>
                            </button>
                        ))}
                    </div>
                </div>

                {/* Movie rating */}
                <div className="vesper-kids-settings-card">
                    <div className="vesper-kids-settings-card__head">
                        <Film size={20} />
                        <div>
                            <p className="vesper-kids-settings-card__eyebrow">MAX RATING</p>
                            <h2 className="vesper-kids-settings-card__title">Movies</h2>
                        </div>
                    </div>
                    <p className="vesper-kids-settings-card__hint">
                        Hide movies rated higher than this.
                    </p>
                    <div className="vesper-kids-settings-options vesper-kids-settings-options--row">
                        {MOVIE_RATINGS.map((r) => (
                            <button
                                key={r}
                                data-focusable="true" data-focus-style="tile" tabIndex={0}
                                data-testid={`kids-settings-movie-${r}`}
                                className={`vesper-kids-rating${draft.maxRatingMovie === r ? ' is-active' : ''}`}
                                onClick={() => apply({ maxRatingMovie: r })}
                            >{r}</button>
                        ))}
                    </div>
                </div>

                {/* TV rating */}
                <div className="vesper-kids-settings-card">
                    <div className="vesper-kids-settings-card__head">
                        <Tv size={20} />
                        <div>
                            <p className="vesper-kids-settings-card__eyebrow">MAX RATING</p>
                            <h2 className="vesper-kids-settings-card__title">TV shows</h2>
                        </div>
                    </div>
                    <p className="vesper-kids-settings-card__hint">
                        Hide TV shows rated higher than this.
                    </p>
                    <div className="vesper-kids-settings-options vesper-kids-settings-options--row">
                        {TV_RATINGS.map((r) => (
                            <button
                                key={r}
                                data-focusable="true" data-focus-style="tile" tabIndex={0}
                                data-testid={`kids-settings-tv-${r}`}
                                className={`vesper-kids-rating${draft.maxRatingSeries === r ? ' is-active' : ''}`}
                                onClick={() => apply({ maxRatingSeries: r })}
                            >{r}</button>
                        ))}
                    </div>
                </div>

                <div className="vesper-kids-settings-card">
                    <div className="vesper-kids-settings-card__head">
                        <Shield size={20} />
                        <div>
                            <p className="vesper-kids-settings-card__eyebrow">PARENT PIN</p>
                            <h2 className="vesper-kids-settings-card__title">Change PIN</h2>
                        </div>
                    </div>
                    <p className="vesper-kids-settings-card__hint">
                        Update the 4-digit PIN that exits Kids mode.
                    </p>
                    <button
                        data-focusable="true" data-focus-style="pill" tabIndex={0}
                        data-testid="kids-settings-change-pin"
                        onClick={() => navigate('/kids/setup')}
                        className="vesper-kids-save-btn"
                        style={{ alignSelf: 'flex-start' }}
                    >Open PIN setup</button>
                </div>
            </section>
        </div>
    );
}
