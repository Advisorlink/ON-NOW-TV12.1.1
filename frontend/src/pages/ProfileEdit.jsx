import React, { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Check, Lock, Unlock } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { saveProfile, listProfiles } from '@/lib/profiles';
import { AVATARS, AvatarCircle } from '@/lib/avatars';

/**
 * Profile create / edit page.
 *  - /profiles/new  → creates a new profile
 *  - /profiles/edit/:id → edits existing (or redirects if not found)
 *
 * 30 avatar choices in a responsive grid, plus a name input.
 * D-pad-driven: focus starts on name field, Tab/Down moves into
 * the avatar grid, Enter on Save persists + returns to picker.
 */
export default function ProfileEdit() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { id: editingId } = useParams();
    const existing = editingId
        ? listProfiles().find((p) => p.id === editingId && !p.kids)
        : null;

    const [name, setName] = useState(existing?.name || '');
    const [avatarId, setAvatarId] = useState(existing?.avatarId || AVATARS[0].id);
    const [pin, setPin] = useState(existing?.pin || '');

    const onSave = () => {
        const trimmed = name.trim() || 'Profile';
        const cleanPin = (pin || '').replace(/\D/g, '');
        if (cleanPin && cleanPin.length !== 4) return; // ignore partial PIN
        saveProfile({
            id: existing?.id,
            name: trimmed,
            avatarId,
            pin: cleanPin,
            createdAt: existing?.createdAt,
        });
        navigate('/profiles');
    };

    const visibleAvatars = AVATARS.filter((a) => !a.hidden);

    return (
        <div
            data-testid="profile-edit"
            className="relative w-screen flex flex-col"
            style={{
                background: 'var(--vesper-bg-0)',
                padding: 'clamp(40px, 5vw, 80px)',
                // ProfileEdit needs to scroll on its own because
                // both #root and .App wrappers are `overflow: hidden`
                // (to keep Home's horizontal shelves from scrolling
                // the whole document).  Without a local scroller,
                // avatars below the viewport are unreachable by
                // D-pad even though focus moves to them.
                height: '100dvh',
                overflowY: 'auto',
                overflowX: 'hidden',
            }}
        >
            <header className="flex items-center gap-4 mb-10">
                <button
                    data-testid="profile-back"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={() => navigate('/profiles')}
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 48,
                        height: 48,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.12)',
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div>
                    <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.28em', color: 'var(--vesper-blue-bright)' }}>
                        {existing ? 'EDIT PROFILE' : 'NEW PROFILE'}
                    </div>
                    <h1 className="vesper-display" style={{ fontSize: 'clamp(32px, 4vw, 56px)', letterSpacing: '-0.025em', lineHeight: 1, marginTop: 6 }}>
                        Make it yours
                    </h1>
                </div>
            </header>

            <div className="flex items-center gap-8 mb-10">
                <AvatarCircle avatarId={avatarId} size={120} />
                <div className="flex-1">
                    <label
                        className="vesper-mono block"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-3)',
                            marginBottom: 8,
                        }}
                    >
                        Display Name
                    </label>
                    <input
                        data-testid="profile-name"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        type="text"
                        value={name}
                        onChange={(e) => setName(e.target.value)}
                        placeholder="e.g. Alex"
                        maxLength={20}
                        className="w-full rounded-lg"
                        style={{
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.16)',
                            color: 'var(--vesper-text)',
                            fontSize: 24,
                            padding: '14px 18px',
                            outline: 'none',
                        }}
                    />
                </div>
                <button
                    data-testid="profile-save"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onSave}
                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
                    style={{
                        height: 56,
                        padding: '0 28px',
                        fontSize: 16,
                        background: 'var(--vesper-blue)',
                        color: 'var(--vesper-bg-0)',
                    }}
                >
                    <Check size={18} strokeWidth={2.5} />
                    Save
                </button>
            </div>

            <ProfilePinField pin={pin} onChange={setPin} />

            <h2
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-text-3)',
                    marginBottom: 16,
                }}
            >
                CHOOSE AN AVATAR · {visibleAvatars.length}
            </h2>

            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'repeat(auto-fill, minmax(96px, 1fr))',
                    gap: 16,
                }}
            >
                {visibleAvatars.map((a) => {
                    const active = a.id === avatarId;
                    return (
                        <button
                            key={a.id}
                            data-testid={`avatar-pick-${a.id}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            tabIndex={0}
                            onClick={() => setAvatarId(a.id)}
                            className="rounded-full flex items-center justify-center"
                            style={{
                                width: 96,
                                height: 96,
                                border: 'none',
                                padding: 0,
                                background: 'transparent',
                                position: 'relative',
                            }}
                        >
                            <AvatarCircle avatarId={a.id} size={96} ring={active} />
                            {active && (
                                <span
                                    style={{
                                        position: 'absolute',
                                        bottom: -4,
                                        right: -4,
                                        width: 28,
                                        height: 28,
                                        borderRadius: '50%',
                                        background: 'var(--vesper-blue)',
                                        color: 'var(--vesper-bg-0)',
                                        display: 'flex',
                                        alignItems: 'center',
                                        justifyContent: 'center',
                                        boxShadow:
                                            '0 0 0 3px var(--vesper-bg-0), 0 6px 18px rgba(var(--vesper-blue-rgb),0.6)',
                                    }}
                                >
                                    <Check size={16} strokeWidth={3} />
                                </span>
                            )}
                        </button>
                    );
                })}
            </div>
        </div>
    );
}

function ProfilePinField({ pin, onChange }) {
    const [enabled, setEnabled] = useState(pin.length === 4);
    const [digits, setDigits] = useState(() => {
        const arr = ['', '', '', ''];
        for (let i = 0; i < Math.min(pin.length, 4); i++) arr[i] = pin[i];
        return arr;
    });
    const refs = useRef([]);

    const sync = (next) => {
        setDigits(next);
        const joined = next.join('');
        onChange(joined.length === 4 ? joined : '');
    };

    const onDigit = (i, val) => {
        const v = val.replace(/[^\d]/g, '').slice(-1);
        const next = [...digits];
        next[i] = v;
        sync(next);
        if (v && i < 3) refs.current[i + 1]?.focus();
    };

    const toggle = () => {
        const nextEnabled = !enabled;
        setEnabled(nextEnabled);
        if (!nextEnabled) {
            sync(['', '', '', '']);
        } else {
            setTimeout(() => refs.current[0]?.focus(), 80);
        }
    };

    return (
        <section
            data-testid="profile-pin-section"
            className="mb-10"
            style={{
                padding: '20px 22px',
                borderRadius: 16,
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.10)',
            }}
        >
            <div className="flex items-center justify-between mb-3">
                <div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.22em',
                            color: 'var(--vesper-text-3)',
                            textTransform: 'uppercase',
                        }}
                    >
                        Profile lock
                    </div>
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 20,
                            letterSpacing: '-0.015em',
                            marginTop: 4,
                        }}
                    >
                        {enabled ? 'PIN required to enter' : 'No PIN (open access)'}
                    </div>
                    <div
                        style={{
                            color: 'var(--vesper-text-2)',
                            fontSize: 13,
                            marginTop: 4,
                            maxWidth: 540,
                        }}
                    >
                        Add a 4-digit PIN so the kids (or anyone else) can&apos;t
                        switch into this profile without you.
                    </div>
                </div>
                <button
                    data-testid="profile-pin-toggle"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={toggle}
                    className="flex items-center gap-2 rounded-full"
                    style={{
                        height: 40,
                        padding: '0 16px',
                        fontSize: 13,
                        fontWeight: 600,
                        background: enabled
                            ? 'var(--vesper-blue)'
                            : 'rgba(255,255,255,0.08)',
                        color: enabled
                            ? 'var(--vesper-bg-0)'
                            : 'var(--vesper-text-2)',
                        border: '1px solid rgba(255,255,255,0.12)',
                    }}
                >
                    {enabled ? (
                        <Lock size={14} strokeWidth={2.4} />
                    ) : (
                        <Unlock size={14} strokeWidth={2} />
                    )}
                    {enabled ? 'PIN on' : 'Set a PIN'}
                </button>
            </div>

            {enabled && (
                <div className="flex gap-3 mt-3">
                    {digits.map((d, i) => (
                        <input
                            key={i}
                            data-testid={`profile-pin-${i}`}
                            data-focusable="true"
                            data-focus-style="pill"
                            ref={(el) => (refs.current[i] = el)}
                            type="tel"
                            inputMode="numeric"
                            maxLength={1}
                            value={d}
                            onChange={(e) => onDigit(i, e.target.value)}
                            onKeyDown={(e) => {
                                if (e.key === 'Backspace' && !d && i > 0)
                                    refs.current[i - 1]?.focus();
                            }}
                            className="text-center"
                            style={{
                                width: 56,
                                height: 64,
                                fontSize: 28,
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
            )}
        </section>
    );
}

