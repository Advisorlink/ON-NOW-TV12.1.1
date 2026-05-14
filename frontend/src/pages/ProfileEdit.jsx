import React, { useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ArrowRight, Check, Lock, Unlock, UserCircle, Palette, Sparkles, Play, Loader2 } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { saveProfile, listProfiles } from '@/lib/profiles';
import { AVATARS, AVATAR_CATEGORIES, AVATAR_BUILDER_OPTIONS, AvatarCircle, buildCustomDiceBearUrl, saveCustomAvatar, loadCustomAvatars } from '@/lib/avatars';
import TVKeyboard from '@/components/TVKeyboard';
import { THEMES, DEFAULT_THEME_ID } from '@/themes/themes';
import { writeViewingStyleForProfile } from '@/lib/viewingStyle';
import { API } from '@/lib/api';

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
    // Viewing-style draft — genre tmdb ids and chosen items.  Step
    // 4 of the wizard lets the user fill these in, but they can
    // also just press Skip and we persist an empty draft.
    const [viewingStyle, setViewingStyle] = useState({
        movieGenres: [],
        tvGenres: [],
        items: [],
    });
    // Autoplay 1080p toggle chosen during step 5.  Default off; if
    // the user taps Yes on the Autoplay prompt it flips to true.
    const [autoplayChoice, setAutoplayChoice] = useState(false);
    // Autoplay yes/skip modal (step 5).
    const [autoplayPromptOpen, setAutoplayPromptOpen] = useState(false);
    // "Would you like to add a password to this account?" Yes/Skip
    // prompt that fires after the autoplay step.
    const [pinPromptOpen, setPinPromptOpen] = useState(false);
    // When the user clicks "Yes, add a PIN" on the prompt, this
    // opens a dedicated 4-digit entry modal.  When they hit OK,
    // we persist the profile with the new PIN, flash a "PIN
    // saved" toast, and return to the picker.
    const [pinEntryOpen, setPinEntryOpen] = useState(false);
    const [pinSavedToast, setPinSavedToast] = useState(false);
    // Build-Your-Own avatar sub-step overlay.  Lives on top of
    // the avatar grid when open.  See <BuildAvatarOverlay/> below.
    const [builderOpen, setBuilderOpen] = useState(false);

    /**
     * Persist every choice the user made during the wizard onto
     * the new (or edited) profile: theme, viewing-style, autoplay
     * preference, and finally the optional PIN.  All scoped keys
     * are written using the saved profile's id so they live in
     * the new profile's namespace from the moment it activates.
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
        try {
            localStorage.setItem(
                `onnowtv-autoplay-1080p:${saved.id}`,
                autoplayChoice ? '1' : '0'
            );
        } catch { /* ignore */ }
        writeViewingStyleForProfile(saved.id, viewingStyle);
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
                        // Back walks the wizard chain one step at
                        // a time: viewing-style → theme → avatar →
                        // name → /profiles.  Editing skips name.
                        if (step === 'viewing-style') {
                            setStep('theme');
                        } else if (step === 'theme') {
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
                                : step === 'viewing-style'
                                ? 'NEW PROFILE · STEP 4 OF 6'
                                : step === 'theme'
                                ? 'NEW PROFILE · STEP 3 OF 6'
                                : 'NEW PROFILE · STEP 2 OF 6'}
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
                        {step === 'viewing-style' && (
                            <div
                                style={{
                                    marginTop: 8,
                                    color: 'var(--vesper-text-2)',
                                    fontSize: 15,
                                }}
                            >
                                Tell us what you love watching — we&apos;ll fill
                                your <strong style={{ color: 'var(--vesper-blue-bright)' }}>For You</strong> rail with fresh picks.
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
                    onNext={() => setStep('viewing-style')}
                />
            ) : step === 'viewing-style' ? (
                <ViewingStyleStep
                    value={viewingStyle}
                    onChange={setViewingStyle}
                    onNext={() => setAutoplayPromptOpen(true)}
                    onSkip={() => {
                        setViewingStyle({
                            movieGenres: [],
                            tvGenres: [],
                            items: [],
                        });
                        setAutoplayPromptOpen(true);
                    }}
                />
            ) : (
                <AvatarStep
                    visibleAvatars={visibleAvatars}
                    avatarId={avatarId}
                    onPick={(id) => setPendingAvatar(id)}
                    onOpenBuilder={() => setBuilderOpen(true)}
                />
            )}

            {builderOpen && (
                <BuildAvatarOverlay
                    onCancel={() => setBuilderOpen(false)}
                    onSave={(record) => {
                        // The new custom avatar is now in
                        // localStorage; jump straight into the
                        // SaveAvatarConfirm flow so the user sees
                        // the same "Save this as your icon?" prompt
                        // they get for every other avatar pick.
                        setBuilderOpen(false);
                        setTimeout(() => setPendingAvatar(record.id), 80);
                    }}
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

            {autoplayPromptOpen && (
                <AutoplayPrompt
                    onYes={() => {
                        setAutoplayChoice(true);
                        setAutoplayPromptOpen(false);
                        setPinPromptOpen(true);
                    }}
                    onNo={() => {
                        setAutoplayChoice(false);
                        setAutoplayPromptOpen(false);
                        setPinPromptOpen(true);
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
                            localStorage.setItem(
                                `onnowtv-autoplay-1080p:${saved.id}`,
                                autoplayChoice ? '1' : '0'
                            );
                        } catch { /* ignore */ }
                        writeViewingStyleForProfile(saved.id, viewingStyle);
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

    // Warm the DiceBear PNG cache the moment the user lands on
    // step 1 so by the time they advance to the avatar grid (step
    // 2) every bonus character portrait is already in the browser
    // HTTP cache — no flash of empty discs.  Emoji avatars don't
    // need this because they're inline glyphs.
    React.useEffect(() => {
        const imageAvatars = AVATARS.filter((a) => a.src && !a.hidden);
        imageAvatars.forEach((a) => {
            const img = new Image();
            img.decoding = 'async';
            img.src = a.src;
        });
    }, []);

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
                    Step 1 of 6 · pick a name
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

/* --------------------------- Viewing Style --------------------------- */

/**
 * Step 4 of 6 — "Choose your viewing style".
 *
 * Two-pane layout:
 *   LEFT  — TMDB genre tiles (movie + tv combined, tagged by type)
 *           with a tap-to-toggle add interaction.
 *   RIGHT — when a genre tile is "open", the top 10 popular titles
 *           in that genre appear with poster + title.  Each title
 *           can be added to the user's draft viewing-style list.
 *
 * The user can also just press Skip — we then persist an empty
 * viewing-style record and the Home "For You" rail hides itself.
 */
function ViewingStyleStep({ value, onChange, onNext, onSkip }) {
    const [movieGenres, setMovieGenres] = React.useState([]);
    const [tvGenres, setTvGenres] = React.useState([]);
    const [loadingGenres, setLoadingGenres] = React.useState(true);
    const [activeGenre, setActiveGenre] = React.useState(null); // {id, name, media}
    const [genreItems, setGenreItems] = React.useState({}); // keyed by `${media}:${id}`
    const [loadingItems, setLoadingItems] = React.useState(false);

    React.useEffect(() => {
        let cancel = false;
        (async () => {
            setLoadingGenres(true);
            try {
                const [m, t] = await Promise.all([
                    fetch(`${API}/tmdb/genres/movie`).then((r) => r.json()),
                    fetch(`${API}/tmdb/genres/tv`).then((r) => r.json()),
                ]);
                if (cancel) return;
                setMovieGenres(m?.data || []);
                setTvGenres(t?.data || []);
            } catch { /* ignore */ } finally {
                if (!cancel) setLoadingGenres(false);
            }
        })();
        return () => { cancel = true; };
    }, []);

    const openGenre = async (g, media) => {
        setActiveGenre({ ...g, media });
        const key = `${media}:${g.id}`;
        if (genreItems[key]) return;
        setLoadingItems(true);
        try {
            const r = await fetch(
                `${API}/tmdb/by-genre/${media}/${g.id}?limit=20`
            );
            const j = await r.json();
            setGenreItems((prev) => ({ ...prev, [key]: j?.data || [] }));
        } catch { /* ignore */ } finally {
            setLoadingItems(false);
        }
    };

    const toggleGenre = (g, media) => {
        const arr = media === 'movie' ? value.movieGenres : value.tvGenres;
        const has = arr.includes(g.id);
        const nextArr = has ? arr.filter((x) => x !== g.id) : [...arr, g.id];
        onChange({
            ...value,
            ...(media === 'movie'
                ? { movieGenres: nextArr }
                : { tvGenres: nextArr }),
        });
    };

    const toggleItem = (it, media) => {
        const has = value.items.some(
            (x) => x.tmdb_id === it.tmdb_id && x.type === (media === 'movie' ? 'movie' : 'series')
        );
        const nextItems = has
            ? value.items.filter(
                  (x) => !(x.tmdb_id === it.tmdb_id && x.type === (media === 'movie' ? 'movie' : 'series'))
              )
            : [
                  ...value.items,
                  {
                      tmdb_id: it.tmdb_id,
                      type: media === 'movie' ? 'movie' : 'series',
                      title: it.title,
                      poster: it.poster,
                      year: it.year,
                  },
              ];
        onChange({ ...value, items: nextItems });
    };

    const activeKey = activeGenre ? `${activeGenre.media}:${activeGenre.id}` : null;
    const activeList = activeKey ? genreItems[activeKey] || [] : [];

    const totalPicks =
        value.movieGenres.length + value.tvGenres.length + value.items.length;

    return (
        <div
            data-testid="profile-step-viewing-style"
            className="flex flex-col"
            style={{ width: '100%', maxWidth: 1280 }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-text-3)',
                    marginBottom: 12,
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                }}
            >
                <Sparkles size={14} strokeWidth={1.8} />
                CHOOSE YOUR VIEWING STYLE · {totalPicks} picks
            </div>

            {/* Helper banner — explicitly tells the user how the
                step works.  Stays visible the entire time so they
                can refer back to it after picking a few items. */}
            <div
                data-testid="viewing-style-helper"
                className="flex items-start"
                style={{
                    gap: 14,
                    padding: '14px 18px',
                    marginBottom: 18,
                    borderRadius: 14,
                    background:
                        'linear-gradient(90deg, rgba(var(--vesper-blue-rgb),0.12) 0%, rgba(var(--vesper-blue-rgb),0.02) 100%)',
                    border: '1px solid rgba(var(--vesper-blue-rgb),0.32)',
                }}
            >
                <div
                    className="shrink-0 flex items-center justify-center rounded-full"
                    style={{
                        width: 36,
                        height: 36,
                        background: 'rgba(var(--vesper-blue-rgb),0.18)',
                        color: 'var(--vesper-blue-bright)',
                    }}
                >
                    <Sparkles size={16} strokeWidth={2} />
                </div>
                <div style={{ lineHeight: 1.45 }}>
                    <div
                        style={{
                            fontSize: 14,
                            fontWeight: 700,
                            color: 'var(--vesper-text)',
                            marginBottom: 2,
                            letterSpacing: '-0.005em',
                        }}
                    >
                        How this works
                    </div>
                    <div
                        style={{
                            fontSize: 13,
                            color: 'var(--vesper-text-2)',
                            maxWidth: '70ch',
                        }}
                    >
                        Tap any <strong style={{ color: 'var(--vesper-blue-bright)' }}>genre</strong> on the left to see its top 20 most-watched titles, then tap the <strong style={{ color: 'var(--vesper-blue-bright)' }}>movies</strong> or <strong style={{ color: 'var(--vesper-blue-bright)' }}>TV shows</strong> you love — we&apos;ll add them to your <strong style={{ color: 'var(--vesper-blue-bright)' }}>For You</strong> rail.  Skip if you&apos;d rather decide later.
                    </div>
                </div>
            </div>

            {/* Genre grid + side panel */}
            <div
                className="grid"
                style={{
                    gridTemplateColumns:
                        'minmax(280px, 1fr) minmax(380px, 1.4fr)',
                    gap: 'clamp(20px, 1.6vw, 36px)',
                    alignItems: 'start',
                    marginBottom: 28,
                }}
            >
                {/* LEFT — Genre tiles */}
                <div
                    style={{
                        display: 'flex',
                        flexDirection: 'column',
                        gap: 18,
                    }}
                >
                    <GenreSection
                        label="Movies"
                        genres={movieGenres}
                        media="movie"
                        loading={loadingGenres}
                        selected={value.movieGenres}
                        activeId={
                            activeGenre?.media === 'movie' ? activeGenre.id : null
                        }
                        onOpen={(g) => openGenre(g, 'movie')}
                        onToggle={(g) => toggleGenre(g, 'movie')}
                    />
                    <GenreSection
                        label="TV Shows"
                        genres={tvGenres}
                        media="tv"
                        loading={loadingGenres}
                        selected={value.tvGenres}
                        activeId={
                            activeGenre?.media === 'tv' ? activeGenre.id : null
                        }
                        onOpen={(g) => openGenre(g, 'tv')}
                        onToggle={(g) => toggleGenre(g, 'tv')}
                    />
                </div>

                {/* RIGHT — Top 10 in the selected genre */}
                <div
                    data-testid="viewing-style-titles"
                    style={{
                        background: 'rgba(255,255,255,0.03)',
                        border: '1px solid rgba(255,255,255,0.08)',
                        borderRadius: 18,
                        padding: '18px 20px 22px',
                        minHeight: 460,
                    }}
                >
                    {!activeGenre ? (
                        <div
                            className="flex flex-col items-center justify-center text-center"
                            style={{
                                minHeight: 420,
                                color: 'var(--vesper-text-3)',
                                gap: 10,
                            }}
                        >
                            <Sparkles size={26} strokeWidth={1.6} />
                            <div style={{ fontSize: 15, maxWidth: 280 }}>
                                Pick a genre on the left to see the top 20
                                most-watched titles in it — tap any to add it to
                                your For You rail.
                            </div>
                        </div>
                    ) : (
                        <>
                            <div
                                style={{
                                    fontSize: 18,
                                    fontWeight: 700,
                                    letterSpacing: '-0.01em',
                                    marginBottom: 4,
                                }}
                            >
                                Top 20 in {activeGenre.name}
                            </div>
                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 11,
                                    letterSpacing: '0.22em',
                                    color: 'var(--vesper-text-3)',
                                    marginBottom: 16,
                                    textTransform: 'uppercase',
                                }}
                            >
                                {activeGenre.media === 'movie' ? 'Movies' : 'TV Shows'}
                            </div>
                            {loadingItems ? (
                                <div
                                    className="flex items-center gap-2"
                                    style={{
                                        color: 'var(--vesper-text-2)',
                                        fontSize: 14,
                                    }}
                                >
                                    <Loader2 className="vesper-spin" size={16} />
                                    Loading…
                                </div>
                            ) : (
                                <div
                                    className="grid"
                                    style={{
                                        gridTemplateColumns:
                                            'repeat(auto-fill, minmax(96px, 1fr))',
                                        gap: 12,
                                    }}
                                >
                                    {activeList.map((it) => {
                                        const added = value.items.some(
                                            (x) =>
                                                x.tmdb_id === it.tmdb_id &&
                                                x.type ===
                                                    (activeGenre.media === 'movie'
                                                        ? 'movie'
                                                        : 'series')
                                        );
                                        return (
                                            <button
                                                key={`${activeGenre.media}-${it.tmdb_id}`}
                                                data-testid={`viewing-style-item-${it.tmdb_id}`}
                                                data-focusable="true"
                                                data-focus-style="tile"
                                                tabIndex={0}
                                                onClick={() =>
                                                    toggleItem(it, activeGenre.media)
                                                }
                                                className="relative overflow-hidden text-left"
                                                style={{
                                                    aspectRatio: '2 / 3',
                                                    borderRadius: 10,
                                                    background:
                                                        'rgba(255,255,255,0.04)',
                                                    border: added
                                                        ? '2px solid var(--vesper-blue)'
                                                        : '1px solid rgba(255,255,255,0.08)',
                                                    boxShadow: added
                                                        ? '0 0 0 3px rgba(var(--vesper-blue-rgb),0.18)'
                                                        : 'none',
                                                    padding: 0,
                                                    cursor: 'pointer',
                                                }}
                                            >
                                                {it.poster ? (
                                                    <img
                                                        src={it.poster}
                                                        alt={it.title}
                                                        loading="lazy"
                                                        className="absolute inset-0 w-full h-full object-cover"
                                                    />
                                                ) : (
                                                    <div
                                                        className="absolute inset-0 flex items-center justify-center"
                                                        style={{
                                                            color: 'var(--vesper-text-3)',
                                                            fontSize: 12,
                                                            padding: 4,
                                                            textAlign: 'center',
                                                        }}
                                                    >
                                                        {it.title}
                                                    </div>
                                                )}
                                                <div
                                                    className="absolute"
                                                    style={{
                                                        top: 6,
                                                        right: 6,
                                                        width: 24,
                                                        height: 24,
                                                        borderRadius: '50%',
                                                        background: added
                                                            ? 'var(--vesper-blue)'
                                                            : 'rgba(6,8,15,0.7)',
                                                        color: added
                                                            ? 'var(--vesper-bg-0)'
                                                            : '#fff',
                                                        display: 'flex',
                                                        alignItems: 'center',
                                                        justifyContent: 'center',
                                                        border: '1px solid rgba(255,255,255,0.18)',
                                                    }}
                                                >
                                                    {added ? (
                                                        <Check
                                                            size={12}
                                                            strokeWidth={3}
                                                        />
                                                    ) : (
                                                        <span
                                                            style={{
                                                                fontSize: 14,
                                                                lineHeight: 1,
                                                                fontWeight: 700,
                                                            }}
                                                        >
                                                            +
                                                        </span>
                                                    )}
                                                </div>
                                                <div
                                                    className="absolute bottom-0 left-0 right-0"
                                                    style={{
                                                        background:
                                                            'linear-gradient(0deg, rgba(0,0,0,0.9) 0%, rgba(0,0,0,0) 100%)',
                                                        padding: '20px 8px 8px',
                                                        fontSize: 11,
                                                        color: '#fff',
                                                        fontWeight: 600,
                                                        lineHeight: 1.2,
                                                    }}
                                                >
                                                    {it.title}
                                                </div>
                                            </button>
                                        );
                                    })}
                                </div>
                            )}
                        </>
                    )}
                </div>
            </div>

            {/* Action row */}
            <div className="flex items-center" style={{ gap: 12 }}>
                <button
                    data-testid="viewing-style-skip"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onSkip}
                    className="rounded-full font-sans font-semibold"
                    style={{
                        height: 50,
                        padding: '0 26px',
                        fontSize: 15,
                        background: 'rgba(255,255,255,0.08)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.14)',
                    }}
                >
                    Skip
                </button>
                <button
                    data-testid="viewing-style-next"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onNext}
                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
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
                    Save & continue
                    <ArrowRight size={16} strokeWidth={2.5} />
                </button>
            </div>
        </div>
    );
}

function GenreSection({ label, genres, media, loading, selected, activeId, onOpen, onToggle }) {
    return (
        <div>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.32em',
                    color: 'var(--vesper-text-3)',
                    marginBottom: 10,
                    textTransform: 'uppercase',
                }}
            >
                {label}
            </div>
            {loading ? (
                <div
                    className="flex items-center gap-2"
                    style={{ color: 'var(--vesper-text-2)', fontSize: 13 }}
                >
                    <Loader2 className="vesper-spin" size={14} />
                    Loading genres…
                </div>
            ) : (
                <div
                    className="flex flex-wrap"
                    style={{ gap: 8 }}
                >
                    {genres.map((g) => {
                        const isSelected = selected.includes(g.id);
                        const isOpen = activeId === g.id;
                        return (
                            <button
                                key={g.id}
                                data-testid={`viewing-style-genre-${media}-${g.id}`}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => {
                                    onOpen(g);
                                    onToggle(g);
                                }}
                                className="rounded-full font-sans"
                                style={{
                                    height: 36,
                                    padding: '0 14px',
                                    fontSize: 13,
                                    fontWeight: 600,
                                    background: isSelected
                                        ? 'var(--vesper-blue)'
                                        : isOpen
                                        ? 'rgba(var(--vesper-blue-rgb),0.18)'
                                        : 'rgba(255,255,255,0.06)',
                                    color: isSelected
                                        ? 'var(--vesper-bg-0)'
                                        : 'var(--vesper-text)',
                                    border: isSelected
                                        ? 'none'
                                        : isOpen
                                        ? '1px solid rgba(var(--vesper-blue-rgb),0.55)'
                                        : '1px solid rgba(255,255,255,0.12)',
                                    cursor: 'pointer',
                                }}
                            >
                                {g.name}
                            </button>
                        );
                    })}
                </div>
            )}
        </div>
    );
}



function AvatarStep({ visibleAvatars, avatarId, onPick, onOpenBuilder }) {
    // User-built custom avatars from localStorage.  Refreshed on
    // mount so a newly-built one shows up the next time the user
    // visits step 2.
    const customAvatars = React.useMemo(
        () => (typeof window !== 'undefined' ? loadCustomAvatars() : []),
        []
    );

    // ID of the avatar the user is currently hovering with the
    // D-pad.  Drives the sticky preview circle pinned at the top
    // of the step so the user always sees what they're picking
    // even as they scroll several rows down.
    const [focusedId, setFocusedId] = React.useState(avatarId);

    // Scoped D-pad navigation for the entire avatar step.  This
    // sidesteps the global spatial-focus engine getting stuck
    // when a row scrolls horizontally and its bounding boxes go
    // off-screen.  We walk focusable buttons in pure DOM order:
    //   ArrowRight / Left → previous / next button in the SAME row.
    //   ArrowDown / Up    → previous / next ROW, preserving the
    //                       current X column when possible.
    // Each move also horizontally + vertically `scrollIntoView()`s
    // the target so it's always visible.
    const containerRef = React.useRef(null);
    React.useEffect(() => {
        const root = containerRef.current;
        if (!root) return undefined;

        const getRows = () => {
            const rows = Array.from(
                root.querySelectorAll('[data-avatar-row="true"]')
            );
            return rows
                .map((r) =>
                    Array.from(
                        r.querySelectorAll('[data-focusable="true"]')
                    ).filter((el) => !el.hasAttribute('disabled'))
                )
                .filter((list) => list.length > 0);
        };

        const focusTarget = (target) => {
            try { target.focus({ preventScroll: false }); } catch { /* ignore */ }
            target.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== target) el.removeAttribute('data-focused');
                });
            // Keep the focused tile in view both horizontally
            // (so the user can always see what they're on) and
            // vertically (slide the row up under the sticky
            // preview).  block:'center' lets the page scroll so
            // each new row crystallises directly below the
            // preview.
            try {
                target.scrollIntoView({
                    behavior: 'smooth',
                    block: 'center',
                    inline: 'center',
                });
            } catch { /* ignore */ }
            const tid = target.getAttribute('data-avatar-id');
            if (tid) setFocusedId(tid);
        };

        const onKey = (e) => {
            if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key)) {
                return;
            }
            const active = document.activeElement;
            if (!active || !root.contains(active)) return;
            const rows = getRows();
            if (rows.length === 0) return;

            // Locate current row + column.
            let rowIdx = -1;
            let colIdx = -1;
            for (let r = 0; r < rows.length; r++) {
                const c = rows[r].indexOf(active);
                if (c !== -1) {
                    rowIdx = r;
                    colIdx = c;
                    break;
                }
            }
            if (rowIdx === -1) return;

            let target = null;
            if (e.key === 'ArrowRight') {
                if (colIdx + 1 < rows[rowIdx].length) {
                    target = rows[rowIdx][colIdx + 1];
                }
            } else if (e.key === 'ArrowLeft') {
                if (colIdx - 1 >= 0) {
                    target = rows[rowIdx][colIdx - 1];
                }
            } else {
                const dir = e.key === 'ArrowDown' ? 1 : -1;
                const nextRowIdx = rowIdx + dir;
                if (nextRowIdx >= 0 && nextRowIdx < rows.length) {
                    // Preserve X column.  Use the active's screen
                    // X center to pick the closest item on the
                    // next row.
                    const cx =
                        active.getBoundingClientRect().left +
                        active.getBoundingClientRect().width / 2;
                    target = rows[nextRowIdx].reduce((best, el) => {
                        const r = el.getBoundingClientRect();
                        const dx = Math.abs(r.left + r.width / 2 - cx);
                        if (!best || dx < best.dx) return { el, dx };
                        return best;
                    }, null)?.el || rows[nextRowIdx][0];
                }
            }
            if (!target) return;

            e.preventDefault();
            e.stopPropagation();
            focusTarget(target);
        };

        // Mirror focus changes from any other source (clicks,
        // initial mount) into focusedId so the sticky preview
        // stays in sync.
        const onFocusIn = (e) => {
            const tid = e.target?.getAttribute?.('data-avatar-id');
            if (tid) setFocusedId(tid);
        };

        window.addEventListener('keydown', onKey, true);
        root.addEventListener('focusin', onFocusIn);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            root.removeEventListener('focusin', onFocusIn);
        };
    }, []);

    // Compute label of the focused avatar (custom · category) so
    // the sticky preview reads as more than just an icon.
    const focusedMeta = React.useMemo(() => {
        if (!focusedId) return { label: 'PICK ANY AVATAR' };
        if (focusedId === 'build-new') return { label: 'BUILD YOUR OWN' };
        if (focusedId.startsWith('custom-')) return { label: 'CUSTOM · MADE BY YOU' };
        for (const cat of AVATAR_CATEGORIES) {
            if (cat.items.some((a) => a.id === focusedId)) {
                return { label: cat.label.toUpperCase() };
            }
        }
        return { label: 'YOUR AVATAR' };
    }, [focusedId]);

    return (
        <div
            ref={containerRef}
            data-testid="profile-step-avatar"
            style={{ width: '100%', position: 'relative' }}
        >
            {/* Sticky preview header — pinned to the top of the
                step's scroll viewport so it remains visible as
                the rows slide up underneath when the user D-pads
                down.  Reads the currently-FOCUSED avatar (not the
                last-saved one), so the user always sees exactly
                what they're about to confirm. */}
            <div
                data-testid="avatar-sticky-preview"
                className="flex items-center"
                style={{
                    position: 'sticky',
                    top: -6,
                    zIndex: 30,
                    padding: '14px 16px',
                    marginBottom: 14,
                    gap: 18,
                    background:
                        'linear-gradient(180deg, var(--vesper-bg-0) 0%, var(--vesper-bg-0) 85%, rgba(6,8,15,0) 100%)',
                    borderBottom: '1px solid rgba(255,255,255,0.06)',
                    backdropFilter: 'blur(12px)',
                }}
            >
                <div style={{ flexShrink: 0 }}>
                    <AvatarCircle avatarId={focusedId || avatarId} size={92} ring />
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.32em',
                            color: 'var(--vesper-blue-bright)',
                        }}
                    >
                        {focusedMeta.label}
                    </div>
                    <h2
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(20px, 2vw, 30px)',
                            letterSpacing: '-0.02em',
                            lineHeight: 1.05,
                            marginTop: 4,
                        }}
                    >
                        Pick your avatar
                    </h2>
                    <div
                        style={{
                            fontSize: 13,
                            color: 'var(--vesper-text-2)',
                            marginTop: 4,
                        }}
                    >
                        {visibleAvatars.length} avatars · {AVATAR_CATEGORIES.length} categories
                    </div>
                </div>
            </div>

            {/* Build-Your-Own + custom row -----------------------*/}
            <section
                data-testid="avatar-row-custom"
                data-avatar-row="true"
                style={{ paddingTop: 4, paddingBottom: 6 }}
            >
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 10,
                        letterSpacing: '0.28em',
                        color: 'var(--vesper-blue-bright)',
                        textTransform: 'uppercase',
                        marginBottom: 8,
                        marginLeft: 4,
                    }}
                >
                    Make Your Own
                </div>
                <div
                    className="vesper-shelf flex"
                    style={{
                        gap: 14,
                        overflowX: 'auto',
                        paddingLeft: 4,
                        paddingRight: 16,
                        paddingTop: 6,
                        paddingBottom: 8,
                    }}
                >
                    <button
                        data-testid="avatar-build-your-own"
                        data-focusable="true"
                        data-focus-style="tile"
                        data-initial-focus="true"
                        data-avatar-id="build-new"
                        tabIndex={0}
                        onClick={onOpenBuilder}
                        className="rounded-full flex items-center justify-center shrink-0"
                        style={{
                            width: 120,
                            height: 120,
                            border: '2px dashed rgba(var(--vesper-blue-rgb), 0.6)',
                            background:
                                'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb), 0.18), rgba(6,8,15,0.6))',
                            color: 'var(--vesper-blue-bright)',
                            cursor: 'pointer',
                            padding: 0,
                            position: 'relative',
                            scrollMarginLeft: 200,
                            scrollMarginRight: 60,
                        }}
                    >
                        <div className="flex flex-col items-center" style={{ gap: 6 }}>
                            <UserCircle size={32} strokeWidth={1.6} />
                            <div
                                style={{
                                    fontSize: 11,
                                    fontWeight: 700,
                                    letterSpacing: '0.06em',
                                    textTransform: 'uppercase',
                                    color: 'var(--vesper-blue-bright)',
                                }}
                            >
                                Build
                            </div>
                        </div>
                    </button>

                    {customAvatars.map((a) => {
                        const active = a.id === avatarId;
                        return (
                            <button
                                key={a.id}
                                data-testid={`avatar-pick-${a.id}`}
                                data-focusable="true"
                                data-focus-style="tile"
                                data-avatar-id={a.id}
                                tabIndex={0}
                                onClick={() => onPick(a.id)}
                                className="rounded-full flex items-center justify-center shrink-0"
                                style={{
                                    width: 120,
                                    height: 120,
                                    border: 'none',
                                    padding: 0,
                                    background: 'transparent',
                                    position: 'relative',
                                    scrollMarginLeft: 200,
                                    scrollMarginRight: 60,
                                }}
                            >
                                <AvatarCircle avatarId={a.id} size={120} ring={active} />
                                {active && (
                                    <span
                                        style={{
                                            position: 'absolute',
                                            bottom: -2,
                                            right: -2,
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
            </section>

            {/* Categorised horizontal rows. */}
            <div
                className="flex flex-col"
                style={{ gap: 6, paddingRight: 8 }}
            >
                {AVATAR_CATEGORIES.map((cat, rowIdx) => (
                    <AvatarRow
                        key={cat.id}
                        category={cat}
                        avatarId={avatarId}
                        onPick={onPick}
                        rowIdx={rowIdx + 1}
                    />
                ))}
            </div>
        </div>
    );
}

function AvatarRow({ category, avatarId, onPick, rowIdx }) {
    return (
        <section
            data-testid={`avatar-row-${category.id}`}
            data-avatar-row="true"
            style={{ paddingTop: 4, paddingBottom: 6, scrollMarginTop: 130 }}
        >
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.28em',
                    color: 'var(--vesper-text-3)',
                    textTransform: 'uppercase',
                    marginBottom: 8,
                    marginLeft: 4,
                }}
            >
                {category.label}
            </div>
            <div
                className="vesper-shelf flex"
                style={{
                    gap: 14,
                    overflowX: 'auto',
                    paddingLeft: 4,
                    paddingRight: 16,
                    paddingTop: 6,
                    paddingBottom: 8,
                }}
            >
                {category.items.map((a, i) => {
                    const active = a.id === avatarId;
                    // initial focus only when we're on the very
                    // first AvatarRow AND no Build-Your-Own row
                    // was rendered above (rowIdx===0 == build row
                    // exists, so AvatarRow always gets rowIdx≥1).
                    const isInitial = false;
                    return (
                        <button
                            key={a.id}
                            data-testid={`avatar-pick-${a.id}`}
                            data-focusable="true"
                            data-focus-style="tile"
                            data-avatar-id={a.id}
                            data-initial-focus={isInitial ? 'true' : undefined}
                            tabIndex={0}
                            onClick={() => onPick(a.id)}
                            className="rounded-full flex items-center justify-center shrink-0"
                            style={{
                                width: 120,
                                height: 120,
                                border: 'none',
                                padding: 0,
                                background: 'transparent',
                                position: 'relative',
                                // Keeps the horizontally-focused
                                // tile a comfortable distance from
                                // the row edge so the user can see
                                // it next to its neighbours.
                                scrollMarginLeft: 200,
                                scrollMarginRight: 60,
                                // Sticky preview is ~140 px tall —
                                // scroll the next row up under it
                                // when D-pad walks down so the
                                // focused tile sits well below the
                                // preview, not under it.
                                scrollMarginTop: 160,
                                scrollMarginBottom: 60,
                            }}
                        >
                            <AvatarCircle avatarId={a.id} size={120} ring={active} />
                            {active && (
                                <span
                                    style={{
                                        position: 'absolute',
                                        bottom: -2,
                                        right: -2,
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
        </section>
    );
}

/**
 * Full-screen "Build Your Own" avatar overlay.  Renders a big
 * preview circle on top and chip rows below for hair / eyes /
 * mouth / accessories etc.  Each chip choice rebuilds the
 * DiceBear URL on the fly so the preview updates instantly.
 *
 * Save → persist the avatar to localStorage via saveCustomAvatar
 * and hand the record back to the parent so it can drop into
 * the same SaveAvatarConfirm flow used for every other avatar.
 */
function BuildAvatarOverlay({ onCancel, onSave }) {
    const [opts, setOpts] = React.useState({
        top: 'shortFlat',
        hairColor: '4a312c',
        eyes: 'happy',
        eyebrows: 'default',
        mouth: 'smile',
        facialHair: 'blank',
        accessories: 'blank',
        skinColor: 'edb98a',
        backgroundColor: '4f46e5',
    });
    const previewUrl = React.useMemo(
        () => buildCustomDiceBearUrl({ ...opts, seed: 'preview' }),
        [opts]
    );
    const set = (k) => (v) => setOpts((p) => ({ ...p, [k]: v }));
    return (
        <div
            data-testid="build-avatar-overlay"
            className="fixed inset-0 z-50 flex flex-col"
            style={{
                background:
                    'radial-gradient(60% 60% at 50% 0%, rgba(var(--vesper-blue-rgb),0.25), transparent), var(--vesper-bg-0)',
                padding: 'clamp(28px, 3vw, 48px)',
                overflowY: 'auto',
            }}
        >
            <div className="flex items-center" style={{ gap: 16, marginBottom: 22 }}>
                <button
                    data-testid="build-avatar-back"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={onCancel}
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
                            letterSpacing: '0.32em',
                            color: 'var(--vesper-blue-bright)',
                        }}
                    >
                        BUILD YOUR OWN AVATAR
                    </div>
                    <h1
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(28px, 3vw, 44px)',
                            letterSpacing: '-0.02em',
                            lineHeight: 1.05,
                            marginTop: 4,
                        }}
                    >
                        Make it <span style={{ color: 'var(--vesper-blue-bright)' }}>yours</span>
                    </h1>
                </div>
            </div>

            {/* Live preview */}
            <div className="flex items-center" style={{ gap: 'clamp(24px, 3vw, 40px)', marginBottom: 24 }}>
                <div
                    data-testid="build-avatar-preview"
                    className="shrink-0"
                    style={{
                        width: 220,
                        height: 220,
                        borderRadius: '50%',
                        overflow: 'hidden',
                        background: `#${opts.backgroundColor}`,
                        border: '3px solid var(--vesper-blue-bright)',
                        boxShadow: `0 0 0 6px rgba(var(--vesper-blue-rgb),0.18), 0 30px 60px -20px rgba(var(--vesper-blue-rgb),0.6)`,
                    }}
                >
                    <img
                        src={previewUrl}
                        alt="preview"
                        loading="eager"
                        decoding="async"
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    />
                </div>
                <div style={{ flex: 1, color: 'var(--vesper-text-2)', fontSize: 14, maxWidth: '60ch' }}>
                    Tap any option below to instantly remix your avatar — hair,
                    eyes, mouth, glasses, the works.  When you&apos;re happy, hit
                    <strong style={{ color: 'var(--vesper-blue-bright)' }}> Save</strong> and we&apos;ll add it
                    to your custom row.
                </div>
            </div>

            {/* Option chip groups */}
            <div className="flex flex-col" style={{ gap: 14, marginBottom: 28 }}>
                <ChipRow label="Hair" group="top" value={opts.top} onSet={set('top')} />
                <ChipRow label="Hair color" group="hairColor" value={opts.hairColor} onSet={set('hairColor')} swatches />
                <ChipRow label="Skin" group="skinColor" value={opts.skinColor} onSet={set('skinColor')} swatches />
                <ChipRow label="Eyes" group="eyes" value={opts.eyes} onSet={set('eyes')} />
                <ChipRow label="Eyebrows" group="eyebrows" value={opts.eyebrows} onSet={set('eyebrows')} />
                <ChipRow label="Mouth" group="mouth" value={opts.mouth} onSet={set('mouth')} />
                <ChipRow label="Facial hair" group="facialHair" value={opts.facialHair} onSet={set('facialHair')} />
                <ChipRow label="Glasses" group="accessories" value={opts.accessories} onSet={set('accessories')} />
                <ChipRow label="Background" group="backgroundColor" value={opts.backgroundColor} onSet={set('backgroundColor')} swatches />
            </div>

            <div className="flex" style={{ gap: 12 }}>
                <button
                    data-testid="build-avatar-cancel"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={onCancel}
                    className="rounded-full font-sans font-semibold"
                    style={{
                        height: 50,
                        padding: '0 28px',
                        fontSize: 15,
                        background: 'rgba(255,255,255,0.08)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.14)',
                    }}
                >
                    Cancel
                </button>
                <button
                    data-testid="build-avatar-save"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => {
                        const record = saveCustomAvatar(opts);
                        onSave(record);
                    }}
                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
                    style={{
                        height: 50,
                        padding: '0 30px',
                        fontSize: 15,
                        background: 'var(--vesper-blue)',
                        color: 'var(--vesper-bg-0)',
                        border: 'none',
                        boxShadow: '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)',
                    }}
                >
                    Save & use this avatar
                    <ArrowRight size={16} strokeWidth={2.5} />
                </button>
            </div>
        </div>
    );
}

function ChipRow({ label, group, value, onSet, swatches }) {
    const options = AVATAR_BUILDER_OPTIONS[group] || [];
    return (
        <div>
            <div
                className="vesper-mono"
                style={{
                    fontSize: 10,
                    letterSpacing: '0.28em',
                    color: 'var(--vesper-text-3)',
                    textTransform: 'uppercase',
                    marginBottom: 8,
                    marginLeft: 4,
                }}
            >
                {label}
            </div>
            <div
                className="vesper-shelf flex"
                style={{
                    gap: 10,
                    overflowX: 'auto',
                    paddingLeft: 4,
                    paddingRight: 16,
                    paddingTop: 4,
                    paddingBottom: 8,
                }}
            >
                {options.map((opt) => {
                    const active = opt === value;
                    return (
                        <button
                            key={opt}
                            data-testid={`build-chip-${group}-${opt}`}
                            data-focusable="true"
                            data-focus-style="pill"
                            tabIndex={0}
                            onClick={() => onSet(opt)}
                            className="shrink-0 rounded-full"
                            style={{
                                height: swatches ? 44 : 36,
                                minWidth: swatches ? 44 : undefined,
                                padding: swatches ? 0 : '0 14px',
                                fontSize: 12,
                                fontWeight: 600,
                                color: active
                                    ? 'var(--vesper-bg-0)'
                                    : 'var(--vesper-text)',
                                background: swatches
                                    ? `#${opt}`
                                    : active
                                    ? 'var(--vesper-blue)'
                                    : 'rgba(255,255,255,0.06)',
                                border: active
                                    ? `2px solid var(--vesper-blue-bright)`
                                    : '1px solid rgba(255,255,255,0.12)',
                                cursor: 'pointer',
                                whiteSpace: 'nowrap',
                            }}
                        >
                            {swatches ? '' : opt}
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

function AutoplayPrompt({ onYes, onNo }) {
    return (
        <ConfirmModal
            testId="autoplay-prompt"
            eyebrow="Playback · Step 5 of 6"
            title="Autoplay 1080p streams?"
            body="When you tap Play, we'll skip the source list and instantly start the first 1080p stream we find.  You can change this any time in Settings."
            yesLabel="Yes, autoplay 1080p"
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
                <Play size={28} strokeWidth={2} fill="currentColor" />
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

