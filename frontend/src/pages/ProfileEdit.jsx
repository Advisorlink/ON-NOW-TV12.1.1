import React, { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, Lock, Unlock, UserCircle, Palette } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { saveProfile, listProfiles } from '@/lib/profiles';
import { AVATARS, AvatarCircle } from '@/lib/avatars';
import TVKeyboard from '@/components/TVKeyboard';
import { THEMES, DEFAULT_THEME_ID } from '@/themes/themes';

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
    // Chosen theme for this profile.  For an existing profile we
    // read from the scoped storage written by ThemeProvider; for a
    // brand-new profile we default to Vesper Neon.
    const [chosenTheme, setChosenTheme] = useState(() => {
        if (existing) {
            try {
                const v = localStorage.getItem(`onnowtv-theme:${existing.id}`);
                if (v && THEMES.some((t) => t.id === v)) return v;
            } catch { /* ignore */ }
        }
        return DEFAULT_THEME_ID;
    });
    // Wizard step.  New profiles run name → avatar → theme → pin;
    // editing an existing profile lands on the avatar step (name
    // already known) and a Back/Next still walks the full chain.
    const [step, setStep] = useState(existing ? 'avatar' : 'name');
    // Pending avatar pick — when the user clicks an avatar tile,
    // we DON'T immediately apply it.  Instead we pop a "Save this
    // as your icon?" Yes/No confirm.  Eliminates accidental
    // commits when scrolling the grid with a TV remote.
    const [pendingAvatar, setPendingAvatar] = useState(null);
    // "Would you like to add a password to this account?" Yes/Skip
    // prompt that fires after the theme is confirmed.
    const [pinPromptOpen, setPinPromptOpen] = useState(false);
    // When the user clicks "Yes, add a PIN" on the prompt, this
    // opens a dedicated 4-digit entry modal.  When they hit OK,
    // we persist the profile with the new PIN, flash a "PIN
    // saved" toast, and return to the picker.
    const [pinEntryOpen, setPinEntryOpen] = useState(false);
    const [pinSavedToast, setPinSavedToast] = useState(false);

    /**
     * Persist the profile + write the chosen theme into the
     * per-profile scoped storage so ThemeProvider applies it the
     * moment this profile becomes active.
     */
    const persistAndExit = (pinOverride) => {
        const trimmed = name.trim() || 'Profile';
        const cleanPin = (
            pinOverride !== undefined ? pinOverride : pin || ''
        ).replace(/\D/g, '');
        if (cleanPin && cleanPin.length !== 4) return;
        const saved = saveProfile({
            id: existing?.id,
            name: trimmed,
            avatarId,
            pin: cleanPin,
            createdAt: existing?.createdAt,
        });
        try {
            localStorage.setItem(`onnowtv-theme:${saved.id}`, chosenTheme);
        } catch { /* ignore */ }
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
                padding:
                    step === 'name'
                        ? 'clamp(20px, 2vw, 32px) clamp(28px, 4vw, 64px)'
                        : 'clamp(40px, 5vw, 80px)',
                height: '100dvh',
                overflowY: step === 'name' ? 'hidden' : 'auto',
                overflowX: 'hidden',
            }}
        >
            <header
                className="flex items-center gap-4"
                style={{
                    marginBottom: step === 'name' ? 8 : 40,
                }}
            >
                <button
                    data-testid="profile-back"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={() => {
                        // Back inside the wizard: walk one step
                        // back through name → avatar → theme; from
                        // the name step (or when editing) exit to
                        // the profile picker.
                        if (step === 'theme') {
                            setStep('avatar');
                        } else if (step === 'avatar' && !existing) {
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
                {step !== 'name' && (
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
                                : step === 'theme'
                                ? 'NEW PROFILE · STEP 3 OF 4'
                                : 'NEW PROFILE · STEP 2 OF 4'}
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
                            {`Hi, ${name.trim() || 'there'}`}
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
                        {step === 'theme' && (
                            <div
                                style={{
                                    marginTop: 8,
                                    color: 'var(--vesper-text-2)',
                                    fontSize: 15,
                                }}
                            >
                                Pick a colour that suits your room — you can
                                change it any time from Settings.
                            </div>
                        )}
                    </div>
                )}
            </header>

            {step === 'name' ? (
                <NameStep
                    name={name}
                    setName={setName}
                    onNext={onNameNext}
                    avatarId={avatarId}
                />
            ) : step === 'theme' ? (
                <ThemeStep
                    chosenTheme={chosenTheme}
                    onPick={setChosenTheme}
                    onNext={() => setPinPromptOpen(true)}
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
                        // After the avatar is confirmed, walk to
                        // the theme step (the next step in the
                        // wizard) instead of jumping straight to
                        // the PIN prompt.
                        setTimeout(() => setStep('theme'), 60);
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
                        const saved = saveProfile({
                            id: existing?.id,
                            name: trimmed,
                            avatarId,
                            pin: newPin,
                            createdAt: existing?.createdAt,
                        });
                        try {
                            localStorage.setItem(
                                `onnowtv-theme:${saved.id}`,
                                chosenTheme
                            );
                        } catch { /* ignore */ }
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

function NameStep({ name, setName, onNext, avatarId }) {
    const canContinue = !!name.trim();
    return (
        <div
            data-testid="profile-step-name"
            className="flex flex-col items-center"
            style={{
                flex: 1,
                minHeight: 0,
                width: '100%',
                justifyContent: 'flex-start',
                paddingTop: 6,
            }}
        >
            {/* Faint blue glow behind the card */}
            <div
                style={{
                    position: 'absolute',
                    inset: '8% 18% auto 18%',
                    height: '32vh',
                    background:
                        'radial-gradient(60% 60% at 50% 0%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 70%)',
                    pointerEvents: 'none',
                    filter: 'blur(20px)',
                }}
            />

            <div
                className="flex flex-col items-center"
                style={{
                    maxWidth: 760,
                    width: '100%',
                    position: 'relative',
                    zIndex: 1,
                    gap: 10,
                }}
            >
                <AvatarCircle avatarId={avatarId} size={84} ring />

                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)',
                        textTransform: 'uppercase',
                    }}
                >
                    Step 1 of 4 · pick a name
                </div>

                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(26px, 3vw, 44px)',
                        letterSpacing: '-0.02em',
                        lineHeight: 1.05,
                        textAlign: 'center',
                    }}
                >
                    What should we{' '}
                    <span
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            textShadow:
                                '0 0 14px rgba(var(--vesper-blue-rgb),0.55)',
                        }}
                    >
                        call
                    </span>{' '}
                    you?
                </h2>

                {/* Display "input" — value preview only.  We DON'T
                    use a real <input>, so the Android IME never
                    pops up.  Typing happens entirely through the
                    custom TVKeyboard below. */}
                <div
                    data-testid="profile-name-display"
                    className="flex items-center gap-3"
                    style={{
                        width: '100%',
                        maxWidth: 560,
                        height: 64,
                        padding: '0 24px',
                        borderRadius: 999,
                        background:
                            'linear-gradient(180deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0.03) 100%)',
                        border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                        boxShadow:
                            '0 10px 36px rgba(var(--vesper-blue-rgb),0.18)',
                        marginTop: 4,
                    }}
                >
                    <UserCircle
                        size={22}
                        strokeWidth={1.6}
                        color="var(--vesper-blue-bright)"
                    />
                    <div
                        className="vesper-display"
                        data-testid="profile-name"
                        style={{
                            flex: 1,
                            fontSize: 24,
                            fontWeight: 500,
                            letterSpacing: '-0.01em',
                            color: name
                                ? 'var(--vesper-text)'
                                : 'var(--vesper-text-3)',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                        }}
                    >
                        {name || 'Your name…'}
                        <span
                            aria-hidden="true"
                            style={{
                                display: 'inline-block',
                                width: 2,
                                height: 22,
                                marginLeft: 4,
                                verticalAlign: 'middle',
                                background: 'var(--vesper-blue-bright)',
                                animation: 'vesperPulse 1100ms infinite',
                                borderRadius: 1,
                            }}
                        />
                    </div>
                    <span
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.22em',
                            color: 'var(--vesper-text-3)',
                            textTransform: 'uppercase',
                        }}
                    >
                        {name.length}/20
                    </span>
                </div>

                {/* Themed on-screen keyboard.  Replaces the Android
                    IME entirely — typing routes through TVKeyboard
                    which dispatches plain setName calls. */}
                <div style={{ marginTop: 4, width: '100%', maxWidth: 720 }}>
                    <TVKeyboard
                        value={name}
                        onChange={(v) => setName(v.slice(0, 20))}
                        onSubmit={() => {
                            if (canContinue) onNext();
                        }}
                        maxLength={20}
                        variant="name"
                    />
                </div>

                <button
                    data-testid="profile-name-next"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    disabled={!canContinue}
                    onClick={onNext}
                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
                    style={{
                        marginTop: 4,
                        height: 50,
                        padding: '0 30px',
                        fontSize: 15,
                        background: canContinue
                            ? 'var(--vesper-blue)'
                            : 'rgba(var(--vesper-blue-rgb),0.25)',
                        color: 'var(--vesper-bg-0)',
                        border: 'none',
                        opacity: canContinue ? 1 : 0.6,
                        cursor: canContinue ? 'pointer' : 'not-allowed',
                        boxShadow: canContinue
                            ? '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)'
                            : 'none',
                    }}
                >
                    Next: choose an avatar
                    <ArrowRight size={16} strokeWidth={2.5} />
                </button>
            </div>
        </div>
    );
}

function ThemeStep({ chosenTheme, onPick, onNext }) {
    return (
        <div
            data-testid="profile-step-theme"
            className="flex flex-col"
            style={{ width: '100%', maxWidth: 1180 }}
        >
            <h2
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-text-3)',
                    marginBottom: 16,
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                }}
            >
                <Palette size={14} strokeWidth={1.8} />
                PICK A COLOUR · {THEMES.length} THEMES
            </h2>

            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns:
                        'repeat(auto-fill, minmax(200px, 1fr))',
                    gap: 'clamp(12px, 1.1vw, 18px)',
                    marginBottom: 28,
                }}
            >
                {THEMES.map((t) => (
                    <ProfileThemeCard
                        key={t.id}
                        theme={t}
                        active={chosenTheme === t.id}
                        initialFocus={chosenTheme === t.id}
                        onPick={() => onPick(t.id)}
                    />
                ))}
            </div>

            <button
                data-testid="profile-theme-next"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onNext}
                className="self-start flex items-center gap-2 rounded-full font-sans font-semibold"
                style={{
                    height: 50,
                    padding: '0 30px',
                    fontSize: 15,
                    background: 'var(--vesper-blue)',
                    color: 'var(--vesper-bg-0)',
                    border: 'none',
                    boxShadow:
                        '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)',
                }}
            >
                Next: profile PIN
                <ArrowRight size={16} strokeWidth={2.5} />
            </button>
        </div>
    );
}

function ProfileThemeCard({ theme, active, initialFocus, onPick }) {
    const p = theme.preview;
    return (
        <button
            data-testid={`profile-theme-${theme.id}`}
            data-focusable="true"
            data-focus-style="tile"
            {...(initialFocus ? { 'data-initial-focus': 'true' } : {})}
            tabIndex={0}
            onClick={onPick}
            className="relative text-left overflow-hidden"
            style={{
                aspectRatio: '5 / 4',
                background: p.background,
                borderRadius: 14,
                border: active
                    ? `2px solid ${p.accent}`
                    : '1px solid rgba(255,255,255,0.08)',
                padding: 'clamp(12px, 1.1vw, 18px)',
                color: '#fff',
                boxShadow: active
                    ? `0 0 0 3px ${p.accent}33, 0 18px 36px rgba(0,0,0,0.4)`
                    : '0 12px 24px rgba(0,0,0,0.3)',
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'space-between',
            }}
        >
            <div>
                <div
                    style={{
                        fontFamily:
                            'var(--theme-font-mono, "JetBrains Mono", monospace)',
                        fontSize: 9,
                        letterSpacing: '0.28em',
                        textTransform: 'uppercase',
                        color: p.accent,
                        marginBottom: 5,
                    }}
                >
                    Theme · {theme.layout}
                </div>
                <div
                    style={{
                        fontFamily: `"${p.wordmark.font}", serif`,
                        fontSize: 'clamp(18px, 1.6vw, 26px)',
                        fontWeight: p.wordmark.weight,
                        color: p.wordmark.color,
                        letterSpacing: '-0.02em',
                        lineHeight: 1,
                    }}
                >
                    {theme.name}
                </div>
            </div>

            <div
                style={{
                    fontSize: 11,
                    lineHeight: 1.35,
                    color: 'rgba(255,255,255,0.78)',
                    maxWidth: '28ch',
                }}
            >
                {theme.tagline}
            </div>

            {/* Faux UI swatches */}
            <div className="flex items-end gap-1.5 mt-2">
                {[1, 2, 3, 4].map((i) => (
                    <div
                        key={i}
                        style={{
                            flex: 1,
                            height: 22 + i * 4,
                            background:
                                i === 1 ? p.accent : 'rgba(255,255,255,0.12)',
                            borderRadius: 6,
                        }}
                    />
                ))}
            </div>

            {active && (
                <div
                    className="absolute"
                    style={{
                        top: 10,
                        right: 10,
                        width: 24,
                        height: 24,
                        borderRadius: '50%',
                        background: p.accent,
                        color: '#fff',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                    }}
                >
                    <Check size={12} strokeWidth={3} />
                </div>
            )}
        </button>
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
    const [pinStr, setPinStr] = React.useState('');

    React.useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                if (e.key === 'Backspace' && pinStr.length > 0) {
                    return;
                }
                e.preventDefault();
                e.stopPropagation();
                onCancel();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onCancel, pinStr]);

    const canSave = pinStr.length === 4;

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
                    padding: '28px 52px 28px',
                    minWidth: 480,
                    boxShadow:
                        '0 30px 80px rgba(0,0,0,0.55), 0 0 0 1px rgba(var(--vesper-blue-rgb),0.18)',
                }}
            >
                <div
                    style={{
                        width: 64,
                        height: 64,
                        borderRadius: 999,
                        background: 'rgba(var(--vesper-blue-rgb),0.16)',
                        border: '1px solid rgba(var(--vesper-blue-rgb),0.5)',
                        color: 'var(--vesper-blue-bright)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        marginBottom: 12,
                    }}
                >
                    <Lock size={26} strokeWidth={2} />
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        color: 'var(--vesper-blue-bright)',
                        textTransform: 'uppercase',
                        marginBottom: 4,
                    }}
                >
                    Set profile PIN
                </div>
                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(20px, 2vw, 26px)',
                        letterSpacing: '-0.02em',
                        lineHeight: 1.15,
                        textAlign: 'center',
                        marginBottom: 14,
                    }}
                >
                    Enter a 4-digit PIN
                </h2>

                {/* Display-only digit boxes — values come from the
                    TVKeyboard below, NOT the Android IME. */}
                <div className="flex" style={{ gap: 12, marginBottom: 16 }}>
                    {[0, 1, 2, 3].map((i) => (
                        <div
                            key={i}
                            data-testid={`enter-pin-${i}`}
                            className="vesper-display flex items-center justify-center"
                            style={{
                                width: 56,
                                height: 64,
                                borderRadius: 14,
                                background: 'rgba(255,255,255,0.06)',
                                border: `1px solid ${
                                    pinStr.length === i
                                        ? 'rgba(var(--vesper-blue-rgb),0.6)'
                                        : 'rgba(255,255,255,0.14)'
                                }`,
                                color: 'var(--vesper-text)',
                                fontSize: 28,
                                boxShadow:
                                    pinStr.length === i
                                        ? '0 0 0 3px rgba(var(--vesper-blue-rgb),0.15)'
                                        : 'none',
                            }}
                        >
                            {pinStr[i] ? '•' : ''}
                        </div>
                    ))}
                </div>

                <div style={{ width: 280, marginBottom: 18 }}>
                    <TVKeyboard
                        value={pinStr}
                        onChange={(v) => setPinStr(v.slice(0, 4))}
                        onSubmit={() => {
                            if (canSave) onSave(pinStr);
                        }}
                        maxLength={4}
                        variant="pin"
                    />
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
                            height: 44,
                            padding: '0 22px',
                            fontSize: 14,
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
                        onClick={() => canSave && onSave(pinStr)}
                        className="rounded-full font-sans font-semibold"
                        style={{
                            height: 44,
                            padding: '0 22px',
                            fontSize: 14,
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

