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
    // Wizard step.  New profiles always start at the name step;
    // when editing an existing profile we jump straight to the
    // avatar step because the name is already set.
    const [step, setStep] = useState(existing ? 'avatar' : 'name');
    // Pending avatar pick — when the user clicks an avatar tile,
    // we DON'T immediately apply it.  Instead we pop a "Save this
    // as your icon?" Yes/No confirm.  Eliminates accidental
    // commits when scrolling the grid with a TV remote.
    const [pendingAvatar, setPendingAvatar] = useState(null);
    // "Would you like to add a password to this account?" Yes/Skip
    // prompt that fires after the avatar is confirmed.
    const [pinPromptOpen, setPinPromptOpen] = useState(false);
    // When the user clicks "Yes, add a PIN" on the prompt, this
    // opens a dedicated 4-digit entry modal.  When they hit OK,
    // we persist the profile with the new PIN, flash a "PIN
    // saved" toast, and return to the picker.
    const [pinEntryOpen, setPinEntryOpen] = useState(false);
    const [pinSavedToast, setPinSavedToast] = useState(false);

    const persistAndExit = (pinOverride) => {
        const trimmed = name.trim() || 'Profile';
        const cleanPin = (
            pinOverride !== undefined ? pinOverride : pin || ''
        ).replace(/\D/g, '');
        if (cleanPin && cleanPin.length !== 4) return;
        saveProfile({
            id: existing?.id,
            name: trimmed,
            avatarId,
            pin: cleanPin,
            createdAt: existing?.createdAt,
        });
        navigate('/profiles');
    };

    const onNameNext = () => {
        const trimmed = name.trim();
        if (!trimmed) return; // require a name before continuing
        setStep('avatar');
    };

    const visibleAvatars = AVATARS.filter((a) => !a.hidden);

    return (
        <div
            data-testid="profile-edit"
            className="relative w-screen flex flex-col"
            style={{
                background: 'var(--vesper-bg-0)',
                padding: 'clamp(40px, 5vw, 80px)',
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
                    onClick={() => {
                        // Back inside the wizard: step from avatar
                        // back to name, or out to /profiles.
                        if (step === 'avatar' && !existing) {
                            setStep('name');
                        } else {
                            navigate('/profiles');
                        }
                    }}
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
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.28em',
                            color: 'var(--vesper-blue-bright)',
                        }}
                    >
                        {existing
                            ? 'EDIT PROFILE'
                            : step === 'name'
                            ? 'NEW PROFILE · STEP 1 OF 2'
                            : 'NEW PROFILE · STEP 2 OF 2'}
                    </div>
                    <h1
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(32px, 4vw, 56px)',
                            letterSpacing: '-0.025em',
                            lineHeight: 1,
                            marginTop: 6,
                        }}
                    >
                        {step === 'name'
                            ? "What's your name?"
                            : `Hi, ${name.trim() || 'there'}`}
                    </h1>
                    {step === 'avatar' && (
                        <div
                            style={{
                                marginTop: 8,
                                color: 'var(--vesper-text-2)',
                                fontSize: 15,
                            }}
                        >
                            Pick an avatar — we&apos;ll ask before
                            we save it.
                        </div>
                    )}
                </div>
            </header>

            {step === 'name' ? (
                <NameStep
                    name={name}
                    setName={setName}
                    onNext={onNameNext}
                />
            ) : (
                <AvatarStep
                    visibleAvatars={visibleAvatars}
                    avatarId={avatarId}
                    onPick={(id) => setPendingAvatar(id)}
                />
            )}

            {pendingAvatar && (
                <SaveAvatarConfirm
                    avatarId={pendingAvatar}
                    onYes={() => {
                        const picked = pendingAvatar;
                        setAvatarId(picked);
                        setPendingAvatar(null);
                        // After the avatar is confirmed, immediately
                        // pop the PIN prompt — that's the next step
                        // of the walkthrough.
                        setTimeout(() => setPinPromptOpen(true), 60);
                    }}
                    onNo={() => setPendingAvatar(null)}
                />
            )}

            {pinPromptOpen && (
                <AddPasswordPrompt
                    onYes={() => {
                        setPinPromptOpen(false);
                        setPinEntryOpen(true);
                    }}
                    onNo={() => {
                        // "Skip" — save the profile without a PIN
                        // and return to the Who's Watching screen.
                        setPinPromptOpen(false);
                        persistAndExit('');
                    }}
                />
            )}

            {pinEntryOpen && (
                <EnterPinModal
                    onCancel={() => {
                        // Back out of PIN entry returns to the
                        // password prompt rather than dropping
                        // them at the avatar grid with no save.
                        setPinEntryOpen(false);
                        setPinPromptOpen(true);
                    }}
                    onSave={(newPin) => {
                        const trimmed = name.trim() || 'Profile';
                        saveProfile({
                            id: existing?.id,
                            name: trimmed,
                            avatarId,
                            pin: newPin,
                            createdAt: existing?.createdAt,
                        });
                        setPin(newPin);
                        setPinEntryOpen(false);
                        setPinSavedToast(true);
                        setTimeout(() => {
                            setPinSavedToast(false);
                            navigate('/profiles');
                        }, 1100);
                    }}
                />
            )}

            {pinSavedToast && <PinSavedToast />}
        </div>
    );
}

/* --------------------------- Step views --------------------------- */

function NameStep({ name, setName, onNext }) {
    const canContinue = !!name.trim();
    return (
        <div
            data-testid="profile-step-name"
            className="flex flex-col items-stretch"
            style={{ maxWidth: 640, gap: 24 }}
        >
            <label
                className="vesper-mono block"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-text-3)',
                }}
            >
                Display name
            </label>
            <input
                data-testid="profile-name"
                data-focusable="true"
                data-focus-style="pill"
                data-initial-focus="true"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                onKeyDown={(e) => {
                    if (e.key === 'Enter' && canContinue) {
                        e.preventDefault();
                        onNext();
                    }
                }}
                placeholder="e.g. Alex"
                maxLength={20}
                className="w-full rounded-lg"
                style={{
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.16)',
                    color: 'var(--vesper-text)',
                    fontSize: 28,
                    padding: '20px 22px',
                    outline: 'none',
                }}
            />
            <button
                data-testid="profile-name-next"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                disabled={!canContinue}
                onClick={onNext}
                className="self-start flex items-center gap-2 rounded-full font-sans font-semibold"
                style={{
                    height: 56,
                    padding: '0 30px',
                    fontSize: 16,
                    background: canContinue
                        ? 'var(--vesper-blue)'
                        : 'rgba(var(--vesper-blue-rgb),0.35)',
                    color: 'var(--vesper-bg-0)',
                    border: 'none',
                    opacity: canContinue ? 1 : 0.6,
                    cursor: canContinue ? 'pointer' : 'not-allowed',
                    boxShadow: canContinue
                        ? '0 8px 24px rgba(var(--vesper-blue-rgb),0.35)'
                        : 'none',
                }}
            >
                Next: choose an avatar
                <ArrowLeft
                    size={16}
                    strokeWidth={2.5}
                    style={{ transform: 'rotate(180deg)' }}
                />
            </button>
        </div>
    );
}

function AvatarStep({ visibleAvatars, avatarId, onPick }) {
    return (
        <div data-testid="profile-step-avatar">
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
                            data-initial-focus={
                                a.id === visibleAvatars[0].id ? 'true' : undefined
                            }
                            tabIndex={0}
                            onClick={() => onPick(a.id)}
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
    const noBtnRef = React.useRef(null);
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

    // Imperatively focus the No / Cancel button on mount.  The
    // global `data-initial-focus` retry only runs at app boot, so
    // without this the modal opens with focus lingering on whatever
    // tile was last focused behind it — exactly the "currently it's
    // not focusing on anything" the user reported.  We call several
    // times (sync + rAF + 50/150 ms) to defeat the in-flight Enter
    // release from the long-press / click that opened the modal.
    React.useEffect(() => {
        const grab = () => {
            const btn = noBtnRef.current;
            if (!btn) return;
            // Clear data-focused from everything outside the modal.
            const modal = document.querySelector(`[data-testid="${testId}"]`);
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (!modal || !modal.contains(el))
                        el.removeAttribute('data-focused');
                });
            try {
                btn.focus({ preventScroll: true });
            } catch {
                /* ignore */
            }
            btn.setAttribute('data-focused', 'true');
        };
        grab();
        const r = requestAnimationFrame(grab);
        const t1 = setTimeout(grab, 50);
        const t2 = setTimeout(grab, 150);
        return () => {
            cancelAnimationFrame(r);
            clearTimeout(t1);
            clearTimeout(t2);
        };
    }, [testId]);

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
                        ref={noBtnRef}
                        data-testid={`${testId}-no`}
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
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
            title="Add a PIN to this profile?"
            body="A 4-digit PIN keeps your profile private. You can skip it now and add one later from the profile screen."
            yesLabel="Yes, add a PIN"
            noLabel="Skip"
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

/**
 * Focused 4-digit PIN entry modal that appears when the user
 * answers "Yes, add a PIN" on the AddPasswordPrompt.  Auto-advances
 * between digits as the user types, and the Save button enables
 * only when all 4 digits are filled.  When Save fires, the parent
 * persists the profile with the new PIN and flashes a toast.
 */
function EnterPinModal({ onCancel, onSave }) {
    const [digits, setDigits] = React.useState(['', '', '', '']);
    const refs = React.useRef([]);

    React.useEffect(() => {
        // Land focus on the first digit immediately.  Retry a
        // few times to defeat the in-flight Enter release from
        // the AddPasswordPrompt's Yes click.
        const grab = () => {
            const el = refs.current[0];
            if (!el) return;
            try { el.focus({ preventScroll: true }); } catch { /* ignore */ }
        };
        grab();
        const r = requestAnimationFrame(grab);
        const t = setTimeout(grab, 80);
        return () => {
            cancelAnimationFrame(r);
            clearTimeout(t);
        };
    }, []);

    React.useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                if (e.key === 'Backspace' && e.target?.tagName === 'INPUT') {
                    return;
                }
                e.preventDefault();
                e.stopPropagation();
                onCancel();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onCancel]);

    const setDigit = (i, val) => {
        const v = (val || '').replace(/[^\d]/g, '').slice(-1);
        const next = [...digits];
        next[i] = v;
        setDigits(next);
        if (v && i < 3) {
            try { refs.current[i + 1]?.focus(); } catch { /* ignore */ }
        }
    };

    const handleDigitKeyDown = (i, e) => {
        if (e.key === 'Backspace' && !digits[i] && i > 0) {
            try { refs.current[i - 1]?.focus(); } catch { /* ignore */ }
        }
    };

    const full = digits.join('');
    const canSave = full.length === 4;

    return (
        <div
            data-testid="enter-pin-modal"
            className="fixed inset-0 z-[70] flex items-center justify-center"
            style={{
                background: 'rgba(6,8,15,0.78)',
                backdropFilter: 'blur(8px)',
                WebkitBackdropFilter: 'blur(8px)',
                padding: 24,
            }}
            onClick={(e) => {
                if (e.target === e.currentTarget) onCancel();
            }}
        >
            <div
                className="flex flex-col items-center"
                style={{
                    background: 'rgba(11,19,34,0.96)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 24,
                    padding: '36px 56px 32px',
                    minWidth: 460,
                    boxShadow:
                        '0 30px 80px rgba(0,0,0,0.55), 0 0 0 1px rgba(var(--vesper-blue-rgb),0.18)',
                }}
            >
                <div
                    style={{
                        width: 76,
                        height: 76,
                        borderRadius: 999,
                        background: 'rgba(var(--vesper-blue-rgb),0.16)',
                        border: '1px solid rgba(var(--vesper-blue-rgb),0.5)',
                        color: 'var(--vesper-blue-bright)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        marginBottom: 18,
                    }}
                >
                    <Lock size={30} strokeWidth={2} />
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)',
                        textTransform: 'uppercase',
                        marginBottom: 6,
                    }}
                >
                    Set profile PIN
                </div>
                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(22px, 2.2vw, 30px)',
                        letterSpacing: '-0.02em',
                        lineHeight: 1.15,
                        textAlign: 'center',
                        marginBottom: 8,
                    }}
                >
                    Enter a 4-digit PIN
                </h2>
                <p
                    style={{
                        color: 'var(--vesper-text-2)',
                        fontSize: 13,
                        lineHeight: 1.4,
                        textAlign: 'center',
                        marginBottom: 22,
                    }}
                >
                    You&apos;ll be asked for this when opening this profile.
                </p>

                <div className="flex" style={{ gap: 12, marginBottom: 26 }}>
                    {digits.map((d, i) => (
                        <input
                            key={i}
                            ref={(el) => { refs.current[i] = el; }}
                            data-testid={`enter-pin-${i}`}
                            data-focusable="true"
                            data-focus-style="pill"
                            inputMode="numeric"
                            pattern="[0-9]*"
                            type="password"
                            maxLength={1}
                            value={d}
                            onChange={(e) => setDigit(i, e.target.value)}
                            onKeyDown={(e) => handleDigitKeyDown(i, e)}
                            className="vesper-display text-center"
                            style={{
                                width: 60,
                                height: 68,
                                borderRadius: 14,
                                background: 'rgba(255,255,255,0.06)',
                                border: '1px solid rgba(255,255,255,0.14)',
                                color: 'var(--vesper-text)',
                                fontSize: 28,
                                outline: 'none',
                            }}
                        />
                    ))}
                </div>

                <div className="flex" style={{ gap: 12 }}>
                    <button
                        data-testid="enter-pin-cancel"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onCancel}
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
                        Cancel
                    </button>
                    <button
                        data-testid="enter-pin-save"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        disabled={!canSave}
                        onClick={() => canSave && onSave(full)}
                        className="rounded-full font-sans font-semibold"
                        style={{
                            height: 48,
                            padding: '0 26px',
                            fontSize: 15,
                            background: canSave
                                ? 'var(--vesper-blue)'
                                : 'rgba(var(--vesper-blue-rgb),0.35)',
                            color: 'var(--vesper-bg-0)',
                            border: 'none',
                            boxShadow: canSave
                                ? '0 8px 24px rgba(var(--vesper-blue-rgb),0.35)'
                                : 'none',
                            opacity: canSave ? 1 : 0.6,
                            cursor: canSave ? 'pointer' : 'not-allowed',
                        }}
                    >
                        OK, save PIN
                    </button>
                </div>
            </div>
        </div>
    );
}

/**
 * Small confirmation pill that flashes at the bottom of the
 * screen after the PIN is persisted, so the user knows it took.
 */
function PinSavedToast() {
    return (
        <div
            data-testid="pin-saved-toast"
            className="fixed inset-x-0 z-[80] flex justify-center"
            style={{ bottom: 48, pointerEvents: 'none' }}
        >
            <div
                className="flex items-center gap-3 rounded-full"
                style={{
                    padding: '12px 22px',
                    background: 'rgba(11,19,34,0.96)',
                    border: '1px solid rgba(var(--vesper-blue-rgb),0.45)',
                    boxShadow:
                        '0 8px 28px rgba(0,0,0,0.45), 0 0 0 1px rgba(var(--vesper-blue-rgb),0.2)',
                }}
            >
                <span
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 26,
                        height: 26,
                        background: 'var(--vesper-blue)',
                        color: 'var(--vesper-bg-0)',
                    }}
                >
                    <Check size={14} strokeWidth={3} />
                </span>
                <span
                    className="font-sans font-semibold"
                    style={{ fontSize: 14, color: 'var(--vesper-text)' }}
                >
                    PIN saved
                </span>
            </div>
        </div>
    );
}

