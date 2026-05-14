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
    // Pending avatar pick — when the user clicks an avatar tile,
    // we DON'T immediately apply it.  Instead we pop a "Save this
    // as your icon?" Yes/No confirm.  Eliminates accidental
    // commits when scrolling the grid with a TV remote.
    const [pendingAvatar, setPendingAvatar] = useState(null);
    // "Would you like to add a password to this account?" Yes/No
    // prompt that fires when the user hits Save with no PIN set
    // (and only the FIRST time on that save attempt).
    const [pinPromptOpen, setPinPromptOpen] = useState(false);
    // Once the user has answered the PIN prompt (either Yes or
    // No), we skip the prompt on the next save click in the same
    // session so they're not nagged.
    const pinPromptAnsweredRef = useRef(false);

    const persistAndExit = () => {
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

    const onSave = () => {
        const cleanPin = (pin || '').replace(/\D/g, '');
        // If no PIN is set and the user hasn't answered the
        // "would you like a password?" prompt yet, intercept and
        // ask.  Otherwise save straight through.
        if (cleanPin.length === 0 && !pinPromptAnsweredRef.current) {
            setPinPromptOpen(true);
            return;
        }
        persistAndExit();
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
                            onClick={() => {
                                if (a.id === avatarId) return; // already picked
                                setPendingAvatar(a.id);
                            }}
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

            {pendingAvatar && (
                <SaveAvatarConfirm
                    avatarId={pendingAvatar}
                    onYes={() => {
                        setAvatarId(pendingAvatar);
                        setPendingAvatar(null);
                    }}
                    onNo={() => setPendingAvatar(null)}
                />
            )}

            {pinPromptOpen && (
                <AddPasswordPrompt
                    onYes={() => {
                        pinPromptAnsweredRef.current = true;
                        setPinPromptOpen(false);
                        // Scroll to the PIN section so the user can
                        // see / interact with it.  We also poke the
                        // toggle so the digit boxes appear.
                        setTimeout(() => {
                            const toggle = document.querySelector(
                                '[data-testid="profile-pin-toggle"]'
                            );
                            if (toggle) {
                                toggle.scrollIntoView({
                                    behavior: 'smooth',
                                    block: 'center',
                                });
                                if (
                                    !toggle
                                        .getAttribute('class')
                                        ?.includes('vesper-blue')
                                ) {
                                    // Click to enable PIN entry.
                                    toggle.click();
                                }
                                setTimeout(() => {
                                    const first = document.querySelector(
                                        '[data-testid="profile-pin-0"]'
                                    );
                                    if (first) {
                                        try {
                                            first.focus({ preventScroll: true });
                                        } catch {
                                            /* ignore */
                                        }
                                    }
                                }, 220);
                            }
                        }, 80);
                    }}
                    onNo={() => {
                        pinPromptAnsweredRef.current = true;
                        setPinPromptOpen(false);
                        // Continue saving without a PIN.
                        persistAndExit();
                    }}
                />
            )}
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



/* ------------------------- Confirmation modals ------------------------- */

function ConfirmModal({
    testId,
    eyebrow,
    title,
    body,
    yesLabel,
    noLabel,
    onYes,
    onNo,
    accent = 'var(--vesper-blue-bright)',
    children,
}) {
    // Close on Escape / Backspace (mapped to TV remote Back).
    React.useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onNo();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onNo]);

    return (
        <div
            data-testid={testId}
            className="fixed inset-0 z-[70] flex items-center justify-center"
            style={{
                background: 'rgba(6,8,15,0.78)',
                backdropFilter: 'blur(8px)',
                WebkitBackdropFilter: 'blur(8px)',
                padding: 24,
            }}
            onClick={(e) => {
                if (e.target === e.currentTarget) onNo();
            }}
        >
            <div
                className="flex flex-col items-center"
                style={{
                    background: 'rgba(11,19,34,0.96)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 24,
                    padding: '36px 52px 32px',
                    minWidth: 420,
                    maxWidth: 540,
                    boxShadow:
                        '0 30px 80px rgba(0,0,0,0.55), 0 0 0 1px rgba(var(--vesper-blue-rgb),0.18)',
                }}
            >
                {children}
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: accent,
                        textTransform: 'uppercase',
                        marginBottom: 6,
                    }}
                >
                    {eyebrow}
                </div>
                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(22px, 2.2vw, 30px)',
                        letterSpacing: '-0.02em',
                        lineHeight: 1.15,
                        textAlign: 'center',
                        marginBottom: 10,
                    }}
                >
                    {title}
                </h2>
                {body && (
                    <p
                        style={{
                            color: 'var(--vesper-text-2)',
                            fontSize: 14,
                            lineHeight: 1.5,
                            textAlign: 'center',
                            marginBottom: 24,
                            maxWidth: 360,
                        }}
                    >
                        {body}
                    </p>
                )}
                <div className="flex" style={{ gap: 12 }}>
                    <button
                        data-testid={`${testId}-no`}
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onNo}
                        className="rounded-full font-sans font-semibold"
                        style={{
                            height: 48,
                            padding: '0 26px',
                            fontSize: 15,
                            background: 'rgba(255,255,255,0.10)',
                            color: 'var(--vesper-text)',
                            border: '1px solid rgba(255,255,255,0.16)',
                        }}
                    >
                        {noLabel}
                    </button>
                    <button
                        data-testid={`${testId}-yes`}
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={onYes}
                        className="rounded-full font-sans font-semibold"
                        style={{
                            height: 48,
                            padding: '0 26px',
                            fontSize: 15,
                            background: 'var(--vesper-blue)',
                            color: 'var(--vesper-bg-0)',
                            border: 'none',
                            boxShadow:
                                '0 8px 24px rgba(var(--vesper-blue-rgb),0.35)',
                        }}
                    >
                        {yesLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}

function SaveAvatarConfirm({ avatarId, onYes, onNo }) {
    return (
        <ConfirmModal
            testId="save-avatar-confirm"
            eyebrow="New avatar"
            title="Save this as your icon?"
            body="This will be the picture next to your name on the profile screen."
            yesLabel="Yes, save"
            noLabel="No"
            onYes={onYes}
            onNo={onNo}
        >
            <div style={{ marginBottom: 18 }}>
                <AvatarCircle avatarId={avatarId} size={92} />
            </div>
        </ConfirmModal>
    );
}

function AddPasswordPrompt({ onYes, onNo }) {
    return (
        <ConfirmModal
            testId="add-password-prompt"
            eyebrow="Profile lock"
            title="Add a password to this account?"
            body="A 4-digit PIN keeps this profile private. You can skip it and add one later from the profile screen."
            yesLabel="Yes, add a PIN"
            noLabel="No, skip"
            onYes={onYes}
            onNo={onNo}
        >
            <div
                style={{
                    width: 76,
                    height: 76,
                    borderRadius: 999,
                    background: 'rgba(var(--vesper-blue-rgb), 0.16)',
                    border: '1px solid rgba(var(--vesper-blue-rgb), 0.5)',
                    color: 'var(--vesper-blue-bright)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    marginBottom: 18,
                }}
            >
                <Lock size={30} strokeWidth={2} />
            </div>
        </ConfirmModal>
    );
}
