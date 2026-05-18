/**
 * Onboarding — premium full-screen welcome tour shown once after a
 * client logs in to a profile.  Walks the user through every major
 * feature (movies/TV, library, calendar, search, watch together,
 * profiles, settings).  Explicitly excludes Live TV.  Sources are
 * intentionally excluded too: end clients don't manage their own
 * catalogues.
 *
 * Visuals per step:
 *   - LEFT column: eyebrow + headline + body + nav buttons + progress
 *   - RIGHT column: a "scene" panel that swaps between a 3D D-pad
 *     (for control-explainer steps) and a feature mockup with mock
 *     data (for feature steps).  The D-pad always pulses the
 *     button the current step is teaching.
 *
 * Public API
 * ----------
 *   <Onboarding open onClose />        // mount the overlay
 *   hasSeenOnboarding(): boolean       // gate first-launch
 *   markOnboardingSeen()               // call when finished
 *   replayOnboarding()                 // dispatched by Settings
 */
import React, { useEffect, useMemo, useState } from 'react';
import {
    SkipForward,
    PlayCircle,
    BookmarkPlus,
    CalendarDays,
    SearchIcon,
    Users,
    Tv2,
    Film,
    UserCircle,
    Settings as SettingsIcon,
    Sparkles,
    ChevronRight,
    ChevronLeft,
    Check,
    Heart,
    ShieldCheck,
    KeyRound,
} from 'lucide-react';

const STORAGE_KEY = 'vesper-onboarding-seen-v1';

/* ---------------------------------------------------------------- */
/* Step deck.                                                       */
/*                                                                  */
/* `scene` chooses which mockup renders on the right.  Set to       */
/* `dpad` for control-explainer steps that should keep the D-pad    */
/* as the hero.                                                     */
/* ---------------------------------------------------------------- */
const STEPS = [
    {
        id: 'welcome',
        glow: 'center',
        scene: 'dpad',
        icon: Sparkles,
        eyebrow: 'Welcome to On Now TV',
        title: 'A 60-second tour',
        body:
            "We'll show you the controls and every shortcut we've built so you can move through the app like a pro.  Skip anytime, replay from Settings.",
    },
    {
        // NEW (v2.6.65) — surfaces the fact that the entire app is
        // designed around the remote, so the user never needs to
        // wake the mouse cursor on their TV box.  Renders an actual
        // photo of the user's remote next to a "no mouse" icon.
        id: 'no-mouse',
        glow: 'center',
        scene: 'no-mouse',
        icon: KeyRound,
        eyebrow: 'Designed for the remote',
        title: 'No more need for that pesky mouse',
        body:
            "Every screen, every list, every popup is built around the four arrows + OK on your remote.  Hold OK to save, press BACK to step back, and the in-app voice search means you'll basically never need to wake the mouse cursor again.",
    },
    {
        id: 'navigation',
        glow: 'right',
        scene: 'dpad',
        icon: PlayCircle,
        eyebrow: '01 · Move around',
        title: 'D-pad arrows navigate everything',
        body:
            "Use UP, DOWN, LEFT, RIGHT to glide between cards, rows and menus.  Every tile glows when it's focused so you always know where you are.",
    },
    {
        id: 'select',
        glow: 'enter',
        scene: 'dpad',
        icon: PlayCircle,
        eyebrow: '02 · Open & play',
        title: 'OK opens, plays, confirms',
        body:
            'Tap OK to open a title, start playback, or confirm a choice.  In the player it pauses and resumes; second nature after a press or two.',
    },
    {
        id: 'longpress',
        glow: 'enter-hold',
        scene: 'longpress',
        icon: BookmarkPlus,
        eyebrow: '03 · Save for later',
        title: 'Hold OK to save anything',
        body:
            'Press and HOLD the OK button on any poster (Movies, TV Shows, even Network catalogues) to drop it into your Library or Watch List.',
    },
    {
        id: 'tv',
        glow: 'up',
        scene: 'tv',
        icon: Tv2,
        eyebrow: '04 · TV Shows',
        title: 'Newest seasons, every network',
        body:
            "The TV tab pulls the top 100 newest releases.  Tap a genre chip to deep-dive into every romance, every thriller, the lot.",
    },
    {
        id: 'movies',
        glow: 'down',
        scene: 'movies',
        icon: Film,
        eyebrow: '05 · Movies',
        title: 'Same magic, big screen edition',
        body:
            'Newest cinema, blockbusters, classics: sorted by what just dropped.  Browse fast, hold OK to stash titles for date night.',
    },
    {
        id: 'library',
        glow: 'right',
        scene: 'library',
        icon: BookmarkPlus,
        eyebrow: '06 · My Library',
        title: 'Everything you saved, one tap away',
        body:
            'Your Library keeps every show you follow and every movie or episode you queued for later, all in one place.  Hop in any time and resume the journey.',
    },
    {
        id: 'calendar',
        glow: 'enter',
        scene: 'calendar',
        icon: CalendarDays,
        eyebrow: '07 · Episode calendar',
        title: 'Never miss a new episode',
        body:
            'Inside the Library, the Calendar view shows when every TV show in your watch list drops a new episode.  Push OK on a date to see what airs that night.',
    },
    {
        id: 'search',
        glow: 'left',
        scene: 'search',
        icon: SearchIcon,
        eyebrow: '08 · Search',
        title: 'Anything, instantly',
        body:
            'Open Search from the side rail and start typing.  Results stream in live across every source.  TV shows, movies, actors, you name it.',
    },
    {
        id: 'watchtogether',
        glow: 'enter',
        scene: 'watchtogether',
        icon: Users,
        eyebrow: '09 · Watch Together',
        title: 'Movie night, every night',
        body:
            'Start a Watch Party, share a 6-character code, and watch in perfect sync with anyone, anywhere.  Hold UP, DOWN, LEFT or RIGHT for 2 seconds during playback to send a reaction.',
    },
    {
        id: 'profiles',
        glow: 'up',
        scene: 'profiles',
        icon: UserCircle,
        eyebrow: '10 · Profiles',
        title: 'One device, every household',
        body:
            "Each viewer gets their own profile with their own avatar, saves and theme.  Set one up for every member of the family in under a minute.",
    },
    {
        id: 'kids',
        glow: 'enter',
        scene: 'kids',
        icon: ShieldCheck,
        eyebrow: '11 · Kids Only',
        title: 'A safe room for the wee ones',
        body:
            'Switch a profile to Kids Mode and the whole app changes: bright cinematic shelves, curated kid-safe titles, a chunky colourful UI, and a 4-digit PIN so they can\'t wander out.  Pick the maximum movie rating (G, PG, PG-13) and TV level, and we filter everything to match.',
    },
    {
        id: 'settings',
        glow: 'left',
        scene: 'settings',
        icon: SettingsIcon,
        eyebrow: '12 · Settings',
        title: 'Tune it your way',
        body:
            'Themes, autoplay, kids ratings, backup and restore: everything lives in Settings.  Backup your profile to a code, restore on any new device in seconds.',
    },
    {
        id: 'wrap',
        glow: 'back',
        scene: 'dpad',
        icon: Check,
        eyebrow: "You're ready",
        title: 'Enjoy the show',
        body:
            'Press BACK from any screen to go home.  Need a refresher?  Settings then "Replay welcome tour".  Have fun in there.',
    },
];

/* ================================================================ */
/* Public helpers                                                   */
/* ================================================================ */
export function hasSeenOnboarding() {
    try { return !!localStorage.getItem(STORAGE_KEY); } catch { return false; }
}
export function markOnboardingSeen() {
    try { localStorage.setItem(STORAGE_KEY, String(Date.now())); } catch { /* ignore */ }
}
export function replayOnboarding() {
    try { localStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
    window.dispatchEvent(new CustomEvent('vesper:onboarding-replay'));
}

/* ================================================================ */
/* Component                                                        */
/* ================================================================ */
export default function Onboarding({ open, onClose }) {
    const [step, setStep] = useState(0);
    const last = STEPS.length - 1;
    const s = STEPS[step] || STEPS[0];

    useEffect(() => { if (open) setStep(0); }, [open]);

    useEffect(() => {
        if (!open) return undefined;
        const onKey = (e) => {
            if (e.key === 'ArrowRight' || e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                if (step >= last) finish();
                else setStep((i) => Math.min(last, i + 1));
            } else if (e.key === 'ArrowLeft') {
                e.preventDefault();
                setStep((i) => Math.max(0, i - 1));
            } else if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                finish();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, step, last]);

    const finish = () => { markOnboardingSeen(); if (onClose) onClose(); };
    const progress = useMemo(() => ((step + 1) / STEPS.length) * 100, [step]);
    if (!open) return null;
    const Icon = s.icon;

    return (
        <div
            data-testid="onboarding-overlay"
            className="fixed inset-0 flex items-center justify-center"
            style={{
                zIndex: 90,
                background:
                    'radial-gradient(ellipse at 30% 20%, #0c1a36 0%, #06080F 55%, #03050C 100%)',
                color: '#fff',
                overflow: 'hidden',
            }}
        >
            <BackdropOrbs />

            <button
                data-testid="onboarding-skip"
                onClick={finish}
                className="absolute flex items-center gap-2 rounded-full vesper-mono"
                style={{
                    top: 'clamp(28px, 2.4vw, 44px)', right: 'clamp(28px, 2.4vw, 44px)',
                    padding: '10px 18px', background: 'rgba(255,255,255,0.06)',
                    color: 'rgba(255,255,255,0.85)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    fontSize: 11, letterSpacing: '0.22em',
                    textTransform: 'uppercase', fontWeight: 700,
                    cursor: 'pointer', zIndex: 5,
                }}
            >
                <SkipForward size={14} /> Skip tour
            </button>

            <div
                className="absolute vesper-mono"
                style={{
                    top: 'clamp(28px, 2.4vw, 44px)',
                    left: 'clamp(28px, 2.4vw, 44px)',
                    fontSize: 11, letterSpacing: '0.32em',
                    textTransform: 'uppercase', color: 'rgba(255,255,255,0.7)',
                }}
            >
                <span style={{ color: 'var(--vesper-blue-bright)', fontWeight: 700 }}>
                    ON&nbsp;NOW&nbsp;TV
                </span>
                <span style={{ margin: '0 10px', opacity: 0.4 }}>·</span>
                Welcome tour
            </div>

            <div
                className="relative flex items-center"
                style={{
                    gap: 'clamp(48px, 5vw, 88px)',
                    padding: '0 clamp(56px, 5vw, 96px)',
                    maxWidth: 1560, width: '100%',
                    justifyContent: 'center',
                }}
            >
                {/* LEFT: copy */}
                <div className="flex-1" style={{ minWidth: 0, maxWidth: 680 }}>
                    <div
                        className="flex items-center gap-3 mb-4"
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontSize: 12, letterSpacing: '0.32em',
                            textTransform: 'uppercase', fontWeight: 700,
                        }}
                    >
                        <span
                            style={{
                                width: 38, height: 38, borderRadius: '50%',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                background:
                                    'linear-gradient(135deg, rgba(93,200,255,0.28) 0%, rgba(93,200,255,0.08) 100%)',
                                border: '1.5px solid rgba(93,200,255,0.55)',
                                boxShadow: '0 0 24px rgba(93,200,255,0.45)',
                            }}
                        >
                            <Icon size={18} strokeWidth={2.2} />
                        </span>
                        {s.eyebrow}
                    </div>

                    <h1
                        className="vesper-display"
                        key={`title-${step}`}
                        style={{
                            fontSize: 'clamp(36px, 4vw, 64px)',
                            letterSpacing: '-0.035em', lineHeight: 0.98,
                            color: '#fff',
                            textShadow: '0 6px 24px rgba(0,0,0,0.6)',
                            marginBottom: 18, animation: 'vesperOnbFade 480ms ease',
                        }}
                    >
                        {s.title}
                    </h1>

                    <p
                        key={`body-${step}`}
                        style={{
                            fontSize: 'clamp(14px, 1.1vw, 17px)',
                            lineHeight: 1.55,
                            color: 'rgba(255,255,255,0.8)',
                            maxWidth: '58ch',
                            marginBottom: 'clamp(24px, 2.6vw, 36px)',
                            animation: 'vesperOnbFade 540ms ease',
                        }}
                    >
                        {s.body}
                    </p>

                    <div className="flex items-center gap-6 flex-wrap" style={{ marginTop: 4 }}>
                        <button
                            data-testid="onboarding-prev"
                            onClick={() => setStep((i) => Math.max(0, i - 1))}
                            disabled={step === 0}
                            className="flex items-center gap-2 rounded-full"
                            style={{
                                padding: '12px 22px',
                                background: 'rgba(255,255,255,0.06)',
                                color: step === 0 ? 'rgba(255,255,255,0.35)' : '#fff',
                                border: '1px solid rgba(255,255,255,0.12)',
                                fontSize: 14, fontWeight: 600,
                                cursor: step === 0 ? 'default' : 'pointer',
                                opacity: step === 0 ? 0.5 : 1,
                                transition: 'opacity 200ms ease',
                            }}
                        >
                            <ChevronLeft size={16} /> Back
                        </button>
                        <button
                            data-testid="onboarding-next"
                            onClick={() => (step >= last ? finish() : setStep((i) => i + 1))}
                            className="flex items-center gap-2 rounded-full font-sans"
                            style={{
                                padding: '14px 26px',
                                background:
                                    'linear-gradient(135deg, var(--vesper-blue) 0%, #4FB8F0 100%)',
                                color: '#06080F', border: 'none',
                                fontSize: 15, fontWeight: 700, cursor: 'pointer',
                                boxShadow: '0 10px 28px rgba(93,200,255,0.45)',
                            }}
                        >
                            {step >= last ? "Let's go" : 'Next'}
                            {step < last && <ChevronRight size={18} />}
                            {step >= last && <Check size={18} />}
                        </button>
                        <div
                            className="vesper-mono ml-auto"
                            style={{
                                fontSize: 11, letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                color: 'rgba(255,255,255,0.6)', fontWeight: 700,
                            }}
                        >
                            Step {step + 1} of {STEPS.length}
                        </div>
                    </div>

                    <div
                        style={{
                            height: 4, background: 'rgba(255,255,255,0.06)',
                            borderRadius: 999, marginTop: 22, overflow: 'hidden',
                        }}
                    >
                        <div
                            style={{
                                width: `${progress}%`, height: '100%',
                                background:
                                    'linear-gradient(90deg, rgba(93,200,255,0) 0%, var(--vesper-blue-bright) 50%, rgba(93,200,255,0) 100%)',
                                borderRadius: 999,
                                transition: 'width 480ms cubic-bezier(0.4, 0.0, 0.2, 1)',
                                boxShadow: '0 0 12px rgba(93,200,255,0.6)',
                            }}
                        />
                    </div>
                </div>

                {/* RIGHT: dynamic scene */}
                <div
                    className="shrink-0"
                    style={{ width: 'clamp(320px, 32vw, 540px)' }}
                    key={`scene-${step}`}
                >
                    <SceneSwitcher step={s} />
                </div>
            </div>

            <style>{`
                @keyframes vesperOnbFade {
                    from { opacity: 0; transform: translateY(6px); }
                    to   { opacity: 1; transform: translateY(0); }
                }
                @keyframes vesperOnbGlow {
                    0%, 100% { opacity: 0.85; }
                    50%      { opacity: 1; }
                }
                @keyframes vesperOnbPulse {
                    0%, 100% { transform: scale(1); }
                    50%      { transform: scale(1.06); }
                }
                @keyframes vesperOnbFloat {
                    0%, 100% { transform: translateY(0); }
                    50%      { transform: translateY(-4px); }
                }
                @keyframes vesperOnbRingPulse {
                    0%, 100% { box-shadow: 0 0 0 0 rgba(93,200,255,0.5); }
                    50%      { box-shadow: 0 0 0 8px rgba(93,200,255,0); }
                }
                @keyframes vesperOnbCursor {
                    0%, 100% { opacity: 1; }
                    50%      { opacity: 0; }
                }
            `}</style>
        </div>
    );
}

/* ---------------------------------------------------------------- */
/* SceneSwitcher — dispatches to the right mock visualisation       */
/* ---------------------------------------------------------------- */
function SceneSwitcher({ step }) {
    const scene = step.scene || 'dpad';
    const compact = scene !== 'dpad';
    return (
        <div
            style={{
                position: 'relative',
                animation: 'vesperOnbFade 540ms ease',
            }}
        >
            {scene === 'dpad' && <DPad3D glow={step.glow} />}
            {scene === 'no-mouse' && <SceneNoMouse />}
            {scene === 'longpress' && <SceneLongPress />}
            {scene === 'tv' && <SceneShelf kind="tv" />}
            {scene === 'movies' && <SceneShelf kind="movies" />}
            {scene === 'library' && <SceneLibrary />}
            {scene === 'calendar' && <SceneCalendar />}
            {scene === 'search' && <SceneSearch />}
            {scene === 'watchtogether' && <SceneWatchTogether />}
            {scene === 'profiles' && <SceneProfiles />}
            {scene === 'kids' && <SceneKids />}
            {scene === 'settings' && <SceneSettings />}

            {/* Mini D-pad indicator on feature scenes so the user
                always sees which button they'd press for the feature. */}
            {compact && (
                <div
                    style={{
                        position: 'absolute',
                        right: -8, bottom: -8,
                        width: 'clamp(120px, 11vw, 168px)',
                        opacity: 0.95,
                        filter: 'drop-shadow(0 8px 24px rgba(0,0,0,0.5))',
                    }}
                >
                    <DPad3D glow={step.glow} />
                </div>
            )}
        </div>
    );
}

/* ---------------------------------------------------------------- */
/* BackdropOrbs                                                     */
/* ---------------------------------------------------------------- */
function BackdropOrbs() {
    return (
        <>
            <div
                className="absolute pointer-events-none"
                style={{
                    width: 720, height: 720,
                    left: '-200px', top: '-180px',
                    background: 'radial-gradient(circle, rgba(93,200,255,0.22) 0%, transparent 70%)',
                    filter: 'blur(40px)',
                }}
            />
            <div
                className="absolute pointer-events-none"
                style={{
                    width: 600, height: 600,
                    right: '-120px', bottom: '-160px',
                    background: 'radial-gradient(circle, rgba(93,200,255,0.16) 0%, transparent 70%)',
                    filter: 'blur(36px)',
                }}
            />
        </>
    );
}

/* ================================================================ */
/* MOCK SCENES                                                      */
/* ================================================================ */

/* Mini poster tile used by Shelf / Library / WT mockups */
function MockPoster({ title, year, gradient, focused, progress, w = 138, h = 200 }) {
    return (
        <div
            style={{
                position: 'relative',
                width: w, height: h,
                borderRadius: 12,
                overflow: 'hidden',
                background: gradient || 'linear-gradient(135deg, #1c2540 0%, #0d1226 100%)',
                border: focused
                    ? '2px solid var(--vesper-blue-bright)'
                    : '1px solid rgba(255,255,255,0.06)',
                boxShadow: focused
                    ? '0 0 0 4px rgba(93,200,255,0.18), 0 16px 36px rgba(93,200,255,0.35)'
                    : '0 8px 20px rgba(0,0,0,0.4)',
                transform: focused ? 'scale(1.06)' : 'scale(1)',
                transition: 'transform 300ms ease, box-shadow 300ms ease',
                flexShrink: 0,
            }}
        >
            {/* Faux poster gloss */}
            <div
                style={{
                    position: 'absolute', inset: 0,
                    background:
                        'linear-gradient(180deg, rgba(255,255,255,0.06) 0%, transparent 30%, rgba(0,0,0,0.55) 100%)',
                }}
            />
            <div
                style={{
                    position: 'absolute', left: 10, right: 10, bottom: 10,
                    color: '#fff',
                }}
            >
                <div
                    className="font-sans"
                    style={{
                        fontSize: 13, fontWeight: 700,
                        textShadow: '0 2px 6px rgba(0,0,0,0.7)',
                        lineHeight: 1.1,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                    }}
                >
                    {title}
                </div>
                {year && (
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 9, letterSpacing: '0.16em',
                            color: 'rgba(255,255,255,0.6)', marginTop: 2,
                        }}
                    >
                        {year}
                    </div>
                )}
            </div>
            {typeof progress === 'number' && (
                <div
                    style={{
                        position: 'absolute', left: 8, right: 8, bottom: 4,
                        height: 3, borderRadius: 2,
                        background: 'rgba(255,255,255,0.18)',
                        overflow: 'hidden',
                    }}
                >
                    <div
                        style={{
                            width: `${progress}%`, height: '100%',
                            background: 'var(--vesper-blue-bright)',
                            boxShadow: '0 0 6px var(--vesper-blue-bright)',
                        }}
                    />
                </div>
            )}
        </div>
    );
}

function ScenePanel({ children, eyebrow, height = 360 }) {
    return (
        <div
            style={{
                position: 'relative',
                height,
                borderRadius: 24,
                padding: 22,
                background:
                    'linear-gradient(160deg, rgba(20,28,52,0.85) 0%, rgba(8,12,24,0.95) 100%)',
                border: '1px solid rgba(255,255,255,0.08)',
                boxShadow: '0 24px 60px rgba(0,0,0,0.5), inset 0 1px 0 rgba(255,255,255,0.05)',
                overflow: 'hidden',
            }}
        >
            {/* Top sheen */}
            <div
                style={{
                    position: 'absolute', inset: 0,
                    background:
                        'radial-gradient(ellipse at top, rgba(93,200,255,0.12) 0%, transparent 60%)',
                    pointerEvents: 'none',
                }}
            />
            {eyebrow && (
                <div
                    className="vesper-mono"
                    style={{
                        position: 'relative',
                        fontSize: 9, letterSpacing: '0.28em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue-bright)',
                        fontWeight: 700, marginBottom: 12,
                    }}
                >
                    {eyebrow}
                </div>
            )}
            <div style={{ position: 'relative', height: '100%' }}>{children}</div>
        </div>
    );
}

/* === SceneNoMouse — photo of the user's actual remote next to a
   "no mouse" icon, hammering home that the entire app is built
   around the D-pad and they'll basically never need to wake the
   cursor on their TV box. === */
function SceneNoMouse() {
    return (
        <ScenePanel eyebrow="Built for your remote" height={420}>
            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr auto 1fr',
                    alignItems: 'center',
                    gap: 24,
                    height: '100%',
                    padding: '6px 12px',
                }}
            >
                {/* LEFT — actual photo of the user's remote, cyan glow */}
                <div
                    style={{
                        position: 'relative',
                        display: 'flex',
                        justifyContent: 'flex-end',
                        alignItems: 'center',
                    }}
                >
                    <div
                        style={{
                            position: 'relative',
                            padding: 18,
                            borderRadius: 28,
                            background:
                                'radial-gradient(circle at center, rgba(93,200,255,0.22) 0%, rgba(93,200,255,0) 70%)',
                        }}
                    >
                        <img
                            src="/onboarding/remote.png"
                            alt="Your remote"
                            style={{
                                height: 320,
                                width: 'auto',
                                filter: 'drop-shadow(0 18px 40px rgba(0,0,0,0.5))',
                                animation: 'vesperOnbFloat 4.2s ease-in-out infinite',
                            }}
                        />
                        {/* OK glow ring drawn over the centre of the remote */}
                        <div
                            style={{
                                position: 'absolute',
                                top: '47%',
                                left: '50%',
                                width: 84,
                                height: 84,
                                marginTop: -42,
                                marginLeft: -42,
                                borderRadius: 999,
                                border: '2px solid rgba(93,200,255,0.55)',
                                boxShadow: '0 0 24px rgba(93,200,255,0.35)',
                                animation: 'vesperOnbRingPulse 2.0s ease-out infinite',
                                pointerEvents: 'none',
                            }}
                        />
                        <div
                            className="vesper-mono"
                            style={{
                                position: 'absolute',
                                bottom: -8,
                                left: 0, right: 0,
                                textAlign: 'center',
                                fontSize: 9,
                                letterSpacing: '0.32em',
                                color: 'rgba(93,200,255,0.9)',
                                textTransform: 'uppercase',
                            }}
                        >
                            Your remote
                        </div>
                    </div>
                </div>

                {/* MIDDLE — chunky arrow indicating the switch */}
                <div
                    style={{
                        fontSize: 32,
                        color: 'rgba(255,255,255,0.45)',
                        fontFamily: 'monospace',
                        userSelect: 'none',
                    }}
                >
                    →
                </div>

                {/* RIGHT — mouse icon with a red ✕ through it */}
                <div
                    style={{
                        position: 'relative',
                        display: 'flex',
                        justifyContent: 'flex-start',
                        alignItems: 'center',
                    }}
                >
                    <div
                        style={{
                            position: 'relative',
                            width: 220,
                            height: 220,
                            borderRadius: 28,
                            background:
                                'radial-gradient(circle at center, rgba(255,81,81,0.16) 0%, rgba(255,81,81,0) 70%)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                        }}
                    >
                        {/* Mouse SVG — neutral grey so the red cross
                            stands out. */}
                        <svg
                            width="140"
                            height="180"
                            viewBox="0 0 140 180"
                            style={{
                                opacity: 0.55,
                                filter: 'drop-shadow(0 6px 18px rgba(0,0,0,0.45))',
                            }}
                        >
                            <defs>
                                <linearGradient id="mouseBody" x1="0" y1="0" x2="0" y2="1">
                                    <stop offset="0%" stopColor="#2a2f3a" />
                                    <stop offset="100%" stopColor="#0e121b" />
                                </linearGradient>
                            </defs>
                            <path
                                d="M70 6c-32 0-58 24-58 58v50c0 32 26 60 58 60s58-28 58-60V64c0-34-26-58-58-58z"
                                fill="url(#mouseBody)"
                                stroke="rgba(255,255,255,0.18)"
                                strokeWidth="2"
                            />
                            {/* Scroll wheel */}
                            <rect
                                x="64"
                                y="36"
                                width="12"
                                height="28"
                                rx="6"
                                fill="rgba(255,255,255,0.22)"
                            />
                            {/* Vertical seam */}
                            <line
                                x1="70" y1="6" x2="70" y2="36"
                                stroke="rgba(255,255,255,0.12)"
                                strokeWidth="2"
                            />
                            <line
                                x1="70" y1="64" x2="70" y2="124"
                                stroke="rgba(255,255,255,0.10)"
                                strokeWidth="1.4"
                            />
                            {/* Tail / cable */}
                            <path
                                d="M70 6 C 70 -6, 80 -10, 86 -16"
                                fill="none"
                                stroke="rgba(255,255,255,0.18)"
                                strokeWidth="2"
                            />
                        </svg>

                        {/* Big red ✕ overlay */}
                        <svg
                            width="220"
                            height="220"
                            viewBox="0 0 220 220"
                            style={{
                                position: 'absolute',
                                inset: 0,
                                pointerEvents: 'none',
                            }}
                        >
                            <line
                                x1="36" y1="36" x2="184" y2="184"
                                stroke="#ff4d4d"
                                strokeWidth="11"
                                strokeLinecap="round"
                                style={{
                                    filter: 'drop-shadow(0 0 14px rgba(255,77,77,0.55))',
                                }}
                            />
                            <line
                                x1="184" y1="36" x2="36" y2="184"
                                stroke="#ff4d4d"
                                strokeWidth="11"
                                strokeLinecap="round"
                                style={{
                                    filter: 'drop-shadow(0 0 14px rgba(255,77,77,0.55))',
                                }}
                            />
                        </svg>

                        <div
                            className="vesper-mono"
                            style={{
                                position: 'absolute',
                                bottom: -8,
                                left: 0, right: 0,
                                textAlign: 'center',
                                fontSize: 9,
                                letterSpacing: '0.32em',
                                color: 'rgba(255,107,107,0.92)',
                                textTransform: 'uppercase',
                            }}
                        >
                            No mouse needed
                        </div>
                    </div>
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneLongPress — finger holding OK + poster lifting into library === */
function SceneLongPress() {
    return (
        <ScenePanel eyebrow="Hold OK to save">
            <div className="flex items-center justify-center" style={{ paddingTop: 14 }}>
                <div style={{ position: 'relative' }}>
                    <MockPoster
                        title="Severance"
                        year="2025"
                        gradient="linear-gradient(135deg, #1a3457 0%, #0a1126 100%)"
                        focused
                        w={160} h={240}
                    />
                    {/* Long-press ring */}
                    <div
                        style={{
                            position: 'absolute', inset: -6, borderRadius: 14,
                            border: '2px solid var(--vesper-blue-bright)',
                            animation: 'vesperOnbRingPulse 1.4s ease-out infinite',
                            pointerEvents: 'none',
                        }}
                    />
                    {/* "Added" pill animating up */}
                    <div
                        style={{
                            position: 'absolute', top: -22, right: -22,
                            background: 'rgba(62, 224, 122, 0.18)',
                            border: '1px solid rgba(62, 224, 122, 0.55)',
                            color: '#3ee07a',
                            padding: '6px 12px',
                            borderRadius: 999,
                            fontSize: 11, fontWeight: 700,
                            letterSpacing: '0.18em', textTransform: 'uppercase',
                            display: 'flex', alignItems: 'center', gap: 6,
                            animation: 'vesperOnbFloat 2.4s ease-in-out infinite',
                        }}
                    >
                        <Check size={12} /> In Library
                    </div>
                </div>
            </div>
            <div
                className="vesper-mono"
                style={{
                    position: 'absolute', bottom: 18, left: 0, right: 0,
                    textAlign: 'center', fontSize: 10, letterSpacing: '0.24em',
                    textTransform: 'uppercase', color: 'rgba(255,255,255,0.55)',
                    fontWeight: 700,
                }}
            >
                Hold OK · adds to Library
            </div>
        </ScenePanel>
    );
}

/* === SceneShelf — Movies / TV horizontal row mockup === */
function SceneShelf({ kind }) {
    const isTv = kind === 'tv';
    const tiles = isTv
        ? [
              { t: 'Slow Horses', y: '2025', g: 'linear-gradient(135deg, #4a2a1c 0%, #1a0e08 100%)' },
              { t: 'The Bear',    y: '2025', g: 'linear-gradient(135deg, #5a1a1a 0%, #1c0808 100%)' },
              { t: 'Shogun',      y: '2024', g: 'linear-gradient(135deg, #2a1c4a 0%, #100820 100%)' },
              { t: 'Severance',   y: '2025', g: 'linear-gradient(135deg, #1a3457 0%, #0a1126 100%)' },
          ]
        : [
              { t: 'Dune: Part Two',    y: '2024', g: 'linear-gradient(135deg, #5a3a1c 0%, #1c1208 100%)' },
              { t: 'Oppenheimer',       y: '2023', g: 'linear-gradient(135deg, #2c2c2c 0%, #0a0a0a 100%)' },
              { t: 'The Substance',     y: '2024', g: 'linear-gradient(135deg, #4a1a3a 0%, #1c0820 100%)' },
              { t: 'Anora',             y: '2024', g: 'linear-gradient(135deg, #1c3a4a 0%, #08161c 100%)' },
          ];
    return (
        <ScenePanel eyebrow={isTv ? 'TV · Newest 100' : 'Movies · Newest releases'} height={380}>
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 14 }}>
                {/* Genre chips row */}
                <div className="flex items-center gap-2">
                    {['All', 'Drama', 'Thriller', 'Sci-Fi'].map((g, i) => (
                        <div
                            key={g}
                            className="vesper-mono"
                            style={{
                                padding: '6px 12px',
                                borderRadius: 999,
                                fontSize: 10, letterSpacing: '0.18em',
                                textTransform: 'uppercase', fontWeight: 700,
                                background: i === 0
                                    ? 'rgba(93,200,255,0.22)'
                                    : 'rgba(255,255,255,0.05)',
                                border: i === 0
                                    ? '1px solid rgba(93,200,255,0.55)'
                                    : '1px solid rgba(255,255,255,0.08)',
                                color: i === 0
                                    ? 'var(--vesper-blue-bright)'
                                    : 'rgba(255,255,255,0.6)',
                            }}
                        >
                            {g}
                        </div>
                    ))}
                </div>
                {/* Posters */}
                <div
                    style={{
                        display: 'flex', gap: 14,
                        overflow: 'hidden',
                        paddingTop: 10, paddingBottom: 18,
                    }}
                >
                    {tiles.map((tile, idx) => (
                        <MockPoster
                            key={tile.t}
                            title={tile.t}
                            year={tile.y}
                            gradient={tile.g}
                            focused={idx === 1}
                            w={120} h={180}
                        />
                    ))}
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 'auto',
                        fontSize: 10, letterSpacing: '0.24em',
                        textTransform: 'uppercase', color: 'rgba(255,255,255,0.55)',
                        fontWeight: 700,
                    }}
                >
                    {isTv ? 'UP to browse TV · ' : 'DOWN to browse Movies · '} OK to open
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneLibrary === */
function SceneLibrary() {
    return (
        <ScenePanel eyebrow="My Library">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 16, height: '100%' }}>
                {/* Tab pills */}
                <div className="flex items-center gap-2">
                    {['Following', 'Watch List', 'Saved'].map((t, i) => (
                        <div
                            key={t}
                            className="vesper-mono"
                            style={{
                                padding: '7px 14px',
                                borderRadius: 999,
                                fontSize: 10, letterSpacing: '0.18em',
                                textTransform: 'uppercase', fontWeight: 700,
                                background: i === 0
                                    ? 'rgba(93,200,255,0.22)'
                                    : 'rgba(255,255,255,0.05)',
                                border: i === 0
                                    ? '1px solid rgba(93,200,255,0.55)'
                                    : '1px solid rgba(255,255,255,0.08)',
                                color: i === 0
                                    ? 'var(--vesper-blue-bright)'
                                    : 'rgba(255,255,255,0.6)',
                            }}
                        >
                            {t}
                        </div>
                    ))}
                </div>
                <div style={{ display: 'flex', gap: 14 }}>
                    <MockPoster
                        title="Severance"
                        year="Series · S2"
                        gradient="linear-gradient(135deg, #1a3457 0%, #0a1126 100%)"
                        w={130} h={196}
                        focused
                    />
                    <MockPoster
                        title="The Bear"
                        year="Series · S3"
                        gradient="linear-gradient(135deg, #5a1a1a 0%, #1c0808 100%)"
                        w={130} h={196}
                    />
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 'auto',
                        fontSize: 10, letterSpacing: '0.22em',
                        textTransform: 'uppercase', color: 'rgba(255,255,255,0.55)',
                        fontWeight: 700,
                    }}
                >
                    Saved shows · queued episodes
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneCalendar === */
function SceneCalendar() {
    const weeks = 4;
    const days = 7;
    const today = 12;
    const eps = { 5: 'BR', 9: 'SH', 12: 'TB', 19: 'SE', 23: 'BR' };
    return (
        <ScenePanel eyebrow="Episode calendar · February">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8, height: '100%' }}>
                {/* Weekday header */}
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: `repeat(${days}, 1fr)`,
                        gap: 6,
                    }}
                >
                    {['M', 'T', 'W', 'T', 'F', 'S', 'S'].map((d, i) => (
                        <div
                            key={i}
                            className="vesper-mono"
                            style={{
                                textAlign: 'center', fontSize: 9,
                                letterSpacing: '0.2em',
                                color: 'rgba(255,255,255,0.4)',
                                fontWeight: 700,
                            }}
                        >
                            {d}
                        </div>
                    ))}
                </div>
                {/* Day grid */}
                <div
                    style={{
                        display: 'grid',
                        gridTemplateColumns: `repeat(${days}, 1fr)`,
                        gap: 6,
                        flex: 1,
                    }}
                >
                    {Array.from({ length: weeks * days }, (_, i) => {
                        const dayNum = i + 1;
                        const hasEp = !!eps[dayNum];
                        const isToday = dayNum === today;
                        return (
                            <div
                                key={i}
                                style={{
                                    position: 'relative',
                                    borderRadius: 8,
                                    background: isToday
                                        ? 'rgba(93,200,255,0.22)'
                                        : hasEp
                                        ? 'rgba(255,255,255,0.05)'
                                        : 'rgba(255,255,255,0.02)',
                                    border: isToday
                                        ? '1.5px solid var(--vesper-blue-bright)'
                                        : '1px solid rgba(255,255,255,0.05)',
                                    display: 'flex',
                                    flexDirection: 'column',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    minHeight: 36,
                                    boxShadow: isToday
                                        ? '0 0 16px rgba(93,200,255,0.4)'
                                        : 'none',
                                }}
                            >
                                <span
                                    style={{
                                        fontSize: 11, fontWeight: 700,
                                        color: isToday
                                            ? '#fff'
                                            : hasEp
                                            ? '#fff'
                                            : 'rgba(255,255,255,0.4)',
                                    }}
                                >
                                    {dayNum}
                                </span>
                                {hasEp && (
                                    <span
                                        style={{
                                            position: 'absolute', bottom: 3,
                                            width: 4, height: 4, borderRadius: '50%',
                                            background: isToday
                                                ? '#fff'
                                                : 'var(--vesper-blue-bright)',
                                            boxShadow: '0 0 6px var(--vesper-blue-bright)',
                                        }}
                                    />
                                )}
                            </div>
                        );
                    })}
                </div>
                {/* Today's episode card */}
                <div
                    style={{
                        marginTop: 8, padding: '10px 14px',
                        borderRadius: 10,
                        background: 'rgba(93,200,255,0.1)',
                        border: '1px solid rgba(93,200,255,0.35)',
                        display: 'flex', alignItems: 'center', gap: 10,
                    }}
                >
                    <div
                        style={{
                            width: 6, height: 6, borderRadius: '50%',
                            background: 'var(--vesper-blue-bright)',
                            boxShadow: '0 0 8px var(--vesper-blue-bright)',
                            flexShrink: 0,
                        }}
                    />
                    <div style={{ flex: 1, minWidth: 0 }}>
                        <div
                            style={{
                                fontSize: 12, fontWeight: 700, color: '#fff',
                                whiteSpace: 'nowrap',
                                overflow: 'hidden',
                                textOverflow: 'ellipsis',
                            }}
                        >
                            The Bear · S3 E5
                        </div>
                        <div
                            className="vesper-mono"
                            style={{
                                fontSize: 9, letterSpacing: '0.2em',
                                color: 'rgba(255,255,255,0.6)',
                                marginTop: 1, textTransform: 'uppercase',
                            }}
                        >
                            Tonight · New episode
                        </div>
                    </div>
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneSearch === */
function SceneSearch() {
    const results = [
        { t: 'Stranger Things',  meta: 'TV Series · 2016', focused: true },
        { t: 'Strange Way of Life', meta: 'Movie · 2023' },
        { t: 'Stranger',          meta: 'Movie · 2017' },
    ];
    return (
        <ScenePanel eyebrow="Search · Everywhere">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, height: '100%' }}>
                {/* Search box */}
                <div
                    style={{
                        display: 'flex', alignItems: 'center', gap: 10,
                        padding: '12px 16px', borderRadius: 12,
                        background: 'rgba(0,0,0,0.5)',
                        border: '1px solid rgba(93,200,255,0.45)',
                        boxShadow: '0 0 18px rgba(93,200,255,0.18)',
                    }}
                >
                    <SearchIcon size={16} style={{ color: 'var(--vesper-blue-bright)' }} />
                    <span style={{ fontSize: 15, fontWeight: 500, color: '#fff' }}>
                        Stranger
                    </span>
                    <span
                        style={{
                            display: 'inline-block', width: 2, height: 16,
                            background: 'var(--vesper-blue-bright)',
                            animation: 'vesperOnbCursor 1s steps(1) infinite',
                        }}
                    />
                </div>
                {/* Results */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                    {results.map((r) => (
                        <div
                            key={r.t}
                            style={{
                                padding: '10px 14px',
                                borderRadius: 10,
                                background: r.focused
                                    ? 'rgba(93,200,255,0.16)'
                                    : 'rgba(255,255,255,0.04)',
                                border: r.focused
                                    ? '1.5px solid var(--vesper-blue-bright)'
                                    : '1px solid rgba(255,255,255,0.06)',
                                display: 'flex', flexDirection: 'column',
                                boxShadow: r.focused
                                    ? '0 0 14px rgba(93,200,255,0.3)'
                                    : 'none',
                                transition: 'all 300ms ease',
                            }}
                        >
                            <span
                                style={{
                                    fontSize: 13, fontWeight: 700,
                                    color: '#fff',
                                }}
                            >
                                {r.t}
                            </span>
                            <span
                                className="vesper-mono"
                                style={{
                                    fontSize: 9, letterSpacing: '0.18em',
                                    color: 'rgba(255,255,255,0.55)',
                                    textTransform: 'uppercase', marginTop: 2,
                                }}
                            >
                                {r.meta}
                            </span>
                        </div>
                    ))}
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneWatchTogether === */
function SceneWatchTogether() {
    return (
        <ScenePanel eyebrow="Watch Party">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14, height: '100%' }}>
                {/* Party code pill */}
                <div
                    style={{
                        alignSelf: 'center',
                        padding: '12px 24px',
                        borderRadius: 999,
                        background:
                            'linear-gradient(135deg, rgba(93,200,255,0.32) 0%, rgba(93,200,255,0.12) 100%)',
                        border: '1.5px solid rgba(93,200,255,0.55)',
                        boxShadow: '0 0 24px rgba(93,200,255,0.4)',
                        display: 'flex', alignItems: 'center', gap: 12,
                    }}
                >
                    <Users size={20} style={{ color: 'var(--vesper-blue-bright)' }} />
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 24,
                            letterSpacing: '0.3em',
                            color: '#fff',
                            fontWeight: 700,
                        }}
                    >
                        7K2M9P
                    </div>
                </div>
                {/* Avatar pair */}
                <div className="flex items-center justify-center gap-4" style={{ marginTop: 8 }}>
                    {[
                        { name: 'You',  color: '#5dc8ff', initial: 'Y' },
                        { name: 'Guest', color: '#f7c948', initial: 'G' },
                    ].map((p, i) => (
                        <div
                            key={p.name}
                            style={{
                                display: 'flex', flexDirection: 'column',
                                alignItems: 'center', gap: 6,
                                animation: `vesperOnbFloat 2.4s ease-in-out infinite ${i * 0.4}s`,
                            }}
                        >
                            <div
                                style={{
                                    width: 56, height: 56,
                                    borderRadius: '50%',
                                    background: `linear-gradient(135deg, ${p.color}45 0%, ${p.color}15 100%)`,
                                    border: `1.5px solid ${p.color}`,
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    fontSize: 22, fontWeight: 700,
                                    color: p.color,
                                    boxShadow: `0 0 18px ${p.color}55`,
                                }}
                            >
                                {p.initial}
                            </div>
                            <span
                                className="vesper-mono"
                                style={{
                                    fontSize: 9, letterSpacing: '0.2em',
                                    color: 'rgba(255,255,255,0.7)',
                                    textTransform: 'uppercase', fontWeight: 700,
                                }}
                            >
                                {p.name}
                            </span>
                        </div>
                    ))}
                </div>
                {/* Floating heart reaction */}
                <div
                    style={{
                        position: 'absolute',
                        right: 24, top: 64,
                        animation: 'vesperOnbFloat 2.6s ease-in-out infinite',
                    }}
                >
                    <Heart size={26} fill="#ff5d8f" stroke="#ff5d8f" />
                </div>
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 'auto',
                        textAlign: 'center',
                        fontSize: 10, letterSpacing: '0.24em',
                        textTransform: 'uppercase', color: 'rgba(255,255,255,0.55)',
                        fontWeight: 700,
                    }}
                >
                    Share the code · Watch in sync
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneProfiles === */
function SceneProfiles() {
    const profiles = [
        { name: 'Brother', color: '#5dc8ff', focused: true },
        { name: 'Guest',   color: '#f7c948' },
        { name: 'Lily',    color: '#3ee07a', kids: true },
    ];
    return (
        <ScenePanel eyebrow="Profiles">
            <div
                style={{
                    display: 'flex', justifyContent: 'center', alignItems: 'center',
                    gap: 22, height: '100%',
                }}
            >
                {profiles.map((p) => (
                    <div
                        key={p.name}
                        style={{
                            display: 'flex', flexDirection: 'column',
                            alignItems: 'center', gap: 10,
                            transform: p.focused ? 'scale(1.1)' : 'scale(0.95)',
                            transition: 'transform 320ms ease',
                            opacity: p.focused ? 1 : 0.7,
                        }}
                    >
                        <div
                            style={{
                                width: 72, height: 72,
                                borderRadius: '50%',
                                background: `linear-gradient(135deg, ${p.color}45 0%, ${p.color}15 100%)`,
                                border: p.focused
                                    ? `2.5px solid ${p.color}`
                                    : '1.5px solid rgba(255,255,255,0.15)',
                                boxShadow: p.focused
                                    ? `0 0 24px ${p.color}66`
                                    : 'none',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                fontSize: 26, fontWeight: 700, color: p.color,
                            }}
                        >
                            {p.name[0]}
                        </div>
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 9, letterSpacing: '0.2em',
                                color: 'rgba(255,255,255,0.8)',
                                textTransform: 'uppercase', fontWeight: 700,
                            }}
                        >
                            {p.name}
                        </span>
                        {p.kids && (
                            <span
                                className="vesper-mono"
                                style={{
                                    fontSize: 8, letterSpacing: '0.22em',
                                    background: 'rgba(62, 224, 122, 0.16)',
                                    border: '1px solid rgba(62, 224, 122, 0.4)',
                                    color: '#3ee07a',
                                    padding: '3px 8px', borderRadius: 999,
                                    textTransform: 'uppercase', fontWeight: 700,
                                }}
                            >
                                Kids
                            </span>
                        )}
                    </div>
                ))}
            </div>
        </ScenePanel>
    );
}

/* === SceneKids — bright, chunky, kid-safe shelf with PIN lock === */
function SceneKids() {
    const kidTiles = [
        { t: 'Bluey',        sub: 'Family · G',  g: 'linear-gradient(135deg, #ffb84d 0%, #e85d04 100%)' },
        { t: 'Paw Patrol',   sub: 'Animated · G', g: 'linear-gradient(135deg, #2ec4ff 0%, #0e6ba8 100%)' },
        { t: 'Mario Bros',   sub: 'Movie · PG',  g: 'linear-gradient(135deg, #ff5d8f 0%, #c2185b 100%)' },
    ];
    return (
        <ScenePanel eyebrow="Kids Only">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14, height: '100%' }}>
                {/* Header strip: KID-SAFE shield + PIN lock indicator */}
                <div className="flex items-center justify-between">
                    <div
                        className="flex items-center gap-2"
                        style={{
                            padding: '7px 12px 7px 8px',
                            borderRadius: 999,
                            background: 'rgba(62, 224, 122, 0.16)',
                            border: '1px solid rgba(62, 224, 122, 0.55)',
                            boxShadow: '0 0 14px rgba(62, 224, 122, 0.25)',
                        }}
                    >
                        <ShieldCheck size={14} style={{ color: '#3ee07a' }} />
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 10, letterSpacing: '0.24em',
                                textTransform: 'uppercase', color: '#3ee07a',
                                fontWeight: 700,
                            }}
                        >
                            Kid-Safe
                        </span>
                    </div>
                    <div
                        className="flex items-center gap-2"
                        style={{
                            padding: '6px 12px',
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.12)',
                        }}
                    >
                        <KeyRound size={12} style={{ color: 'rgba(255,255,255,0.7)' }} />
                        <span
                            className="vesper-mono"
                            style={{
                                fontSize: 10, letterSpacing: '0.32em',
                                color: 'rgba(255,255,255,0.85)', fontWeight: 700,
                            }}
                        >
                            · · · ·
                        </span>
                    </div>
                </div>

                {/* Rating pills */}
                <div className="flex items-center gap-2 flex-wrap">
                    {[
                        { label: 'G',     active: true  },
                        { label: 'PG',    active: true  },
                        { label: 'PG-13', active: false },
                        { label: 'TV-Y',  active: true  },
                        { label: 'TV-G',  active: true  },
                    ].map((r) => (
                        <div
                            key={r.label}
                            className="vesper-mono"
                            style={{
                                padding: '5px 11px',
                                borderRadius: 999,
                                fontSize: 9, letterSpacing: '0.18em',
                                textTransform: 'uppercase', fontWeight: 700,
                                background: r.active
                                    ? 'rgba(62, 224, 122, 0.18)'
                                    : 'rgba(255,255,255,0.04)',
                                border: r.active
                                    ? '1px solid rgba(62, 224, 122, 0.5)'
                                    : '1px solid rgba(255,255,255,0.08)',
                                color: r.active ? '#3ee07a' : 'rgba(255,255,255,0.35)',
                                opacity: r.active ? 1 : 0.6,
                            }}
                        >
                            {r.label}
                        </div>
                    ))}
                </div>

                {/* Chunky bright tiles */}
                <div
                    style={{
                        display: 'flex', gap: 12,
                        paddingTop: 6, paddingBottom: 6,
                    }}
                >
                    {kidTiles.map((t, idx) => (
                        <div
                            key={t.t}
                            style={{
                                position: 'relative',
                                flex: 1, minWidth: 0,
                                aspectRatio: '3 / 4',
                                borderRadius: 18,
                                background: t.g,
                                border: idx === 1
                                    ? '3px solid #fff'
                                    : '2px solid rgba(255,255,255,0.18)',
                                boxShadow: idx === 1
                                    ? '0 16px 36px rgba(255,255,255,0.18), 0 0 0 5px rgba(255,255,255,0.18)'
                                    : '0 10px 22px rgba(0,0,0,0.45)',
                                transform: idx === 1 ? 'scale(1.05)' : 'scale(1)',
                                transition: 'transform 280ms ease',
                                display: 'flex', flexDirection: 'column',
                                justifyContent: 'flex-end',
                                padding: 12,
                                overflow: 'hidden',
                            }}
                        >
                            {/* Sticker shine */}
                            <div
                                style={{
                                    position: 'absolute', inset: 0,
                                    background:
                                        'linear-gradient(180deg, rgba(255,255,255,0.18) 0%, transparent 30%)',
                                }}
                            />
                            <div
                                className="font-sans"
                                style={{
                                    position: 'relative',
                                    fontSize: 14, fontWeight: 800,
                                    color: '#fff',
                                    textShadow: '0 2px 8px rgba(0,0,0,0.6)',
                                    lineHeight: 1.1,
                                }}
                            >
                                {t.t}
                            </div>
                            <div
                                className="vesper-mono"
                                style={{
                                    position: 'relative',
                                    fontSize: 9, letterSpacing: '0.18em',
                                    color: 'rgba(255,255,255,0.9)',
                                    marginTop: 3, fontWeight: 700,
                                    textTransform: 'uppercase',
                                }}
                            >
                                {t.sub}
                            </div>
                        </div>
                    ))}
                </div>

                {/* Footer caption */}
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 'auto',
                        fontSize: 10, letterSpacing: '0.24em',
                        textTransform: 'uppercase',
                        color: 'rgba(255,255,255,0.55)',
                        fontWeight: 700,
                    }}
                >
                    PIN-locked · Curated · Bright UI
                </div>
            </div>
        </ScenePanel>
    );
}

/* === SceneSettings === */
function SceneSettings() {
    return (
        <ScenePanel eyebrow="Settings">
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {/* Theme picker */}
                <SettingRow label="Theme">
                    <div className="flex items-center gap-2">
                        {['#5dc8ff', '#f7c948', '#3ee07a', '#ff5d8f'].map((c, i) => (
                            <div
                                key={c}
                                style={{
                                    width: 22, height: 22, borderRadius: '50%',
                                    background: c,
                                    border: i === 0
                                        ? '2px solid #fff'
                                        : '1px solid rgba(255,255,255,0.15)',
                                    boxShadow: i === 0
                                        ? `0 0 12px ${c}99`
                                        : 'none',
                                }}
                            />
                        ))}
                    </div>
                </SettingRow>
                {/* Autoplay toggle */}
                <SettingRow label="Autoplay 1080p">
                    <Toggle on />
                </SettingRow>
                {/* Kids PIN */}
                <SettingRow label="Kids PIN">
                    <span
                        className="vesper-mono"
                        style={{
                            fontSize: 11, letterSpacing: '0.18em',
                            color: 'rgba(255,255,255,0.7)', fontWeight: 700,
                        }}
                    >
                        · · · ·
                    </span>
                </SettingRow>
                {/* Backup */}
                <SettingRow label="Backup profile" focused>
                    <span
                        className="vesper-mono"
                        style={{
                            fontSize: 10, letterSpacing: '0.18em',
                            color: 'var(--vesper-blue-bright)', fontWeight: 700,
                            textTransform: 'uppercase',
                        }}
                    >
                        Save code →
                    </span>
                </SettingRow>
            </div>
        </ScenePanel>
    );
}

function SettingRow({ label, focused, children }) {
    return (
        <div
            style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                padding: '12px 14px',
                background: focused
                    ? 'rgba(93,200,255,0.14)'
                    : 'rgba(255,255,255,0.03)',
                border: focused
                    ? '1.5px solid var(--vesper-blue-bright)'
                    : '1px solid rgba(255,255,255,0.05)',
                borderRadius: 10,
                boxShadow: focused
                    ? '0 0 14px rgba(93,200,255,0.3)'
                    : 'none',
            }}
        >
            <span style={{ fontSize: 12, fontWeight: 600, color: '#fff' }}>{label}</span>
            {children}
        </div>
    );
}

function Toggle({ on }) {
    return (
        <div
            style={{
                width: 36, height: 20, borderRadius: 999,
                background: on ? 'rgba(93,200,255,0.6)' : 'rgba(255,255,255,0.12)',
                position: 'relative',
                transition: 'background 280ms ease',
            }}
        >
            <div
                style={{
                    position: 'absolute', top: 2,
                    left: on ? 18 : 2,
                    width: 16, height: 16, borderRadius: '50%',
                    background: '#fff',
                    transition: 'left 280ms ease',
                    boxShadow: '0 2px 6px rgba(0,0,0,0.4)',
                }}
            />
        </div>
    );
}

/* ================================================================ */
/* 3D circular D-pad — kept compact-able via container width        */
/* ================================================================ */
function DPad3D({ glow }) {
    const isUp = glow === 'up';
    const isDown = glow === 'down';
    const isLeft = glow === 'left';
    const isRight = glow === 'right';
    const isEnter = glow === 'enter';
    const isEnterHold = glow === 'enter-hold';
    const isBack = glow === 'back';
    const allCenter = glow === 'center';

    return (
        <div
            style={{
                position: 'relative',
                width: '100%',
                aspectRatio: '1 / 1',
                animation: allCenter ? 'vesperOnbPulse 3s ease-in-out infinite' : undefined,
            }}
        >
            <div
                style={{
                    position: 'absolute',
                    inset: '-12%',
                    borderRadius: '50%',
                    background:
                        'radial-gradient(circle, rgba(93,200,255,0.25) 0%, transparent 60%)',
                    filter: 'blur(18px)',
                    pointerEvents: 'none',
                }}
            />
            <svg viewBox="0 0 400 400" style={{ width: '100%', height: '100%', display: 'block' }}>
                <defs>
                    <radialGradient id="onbBody" cx="40%" cy="35%" r="65%">
                        <stop offset="0%" stopColor="#1c2540" />
                        <stop offset="55%" stopColor="#0d1226" />
                        <stop offset="100%" stopColor="#05070f" />
                    </radialGradient>
                    <linearGradient id="onbHighlight" x1="50%" y1="0%" x2="50%" y2="100%">
                        <stop offset="0%" stopColor="rgba(255,255,255,0.22)" />
                        <stop offset="50%" stopColor="rgba(255,255,255,0.04)" />
                        <stop offset="100%" stopColor="rgba(255,255,255,0)" />
                    </linearGradient>
                    <linearGradient id="onbBtn" x1="50%" y1="0%" x2="50%" y2="100%">
                        <stop offset="0%" stopColor="#293454" />
                        <stop offset="100%" stopColor="#0e1426" />
                    </linearGradient>
                    <linearGradient id="onbBtnHot" x1="50%" y1="0%" x2="50%" y2="100%">
                        <stop offset="0%" stopColor="#7FD9FF" />
                        <stop offset="100%" stopColor="#3DAFE8" />
                    </linearGradient>
                    <radialGradient id="onbCenter" cx="50%" cy="40%" r="55%">
                        <stop offset="0%" stopColor="#34416a" />
                        <stop offset="80%" stopColor="#0c1224" />
                    </radialGradient>
                    <radialGradient id="onbCenterHot" cx="50%" cy="40%" r="55%">
                        <stop offset="0%" stopColor="#A8E4FF" />
                        <stop offset="100%" stopColor="#3DAFE8" />
                    </radialGradient>
                    <filter id="onbShadow" x="-20%" y="-20%" width="140%" height="140%">
                        <feGaussianBlur in="SourceAlpha" stdDeviation="6" />
                        <feOffset dx="0" dy="8" result="off" />
                        <feComponentTransfer><feFuncA type="linear" slope="0.55" /></feComponentTransfer>
                        <feMerge><feMergeNode /><feMergeNode in="SourceGraphic" /></feMerge>
                    </filter>
                    <filter id="onbHotGlow" x="-50%" y="-50%" width="200%" height="200%">
                        <feGaussianBlur stdDeviation="6" result="blur" />
                        <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
                    </filter>
                </defs>

                <circle
                    cx="200" cy="200" r="170"
                    fill="url(#onbBody)"
                    stroke="rgba(255,255,255,0.06)"
                    strokeWidth="1.5"
                    filter="url(#onbShadow)"
                />
                <circle cx="200" cy="200" r="150" fill="none" stroke="rgba(255,255,255,0.08)" strokeWidth="1" />
                <ellipse cx="200" cy="120" rx="135" ry="70" fill="url(#onbHighlight)" style={{ pointerEvents: 'none' }} />

                <DpadArrow dir="up"    hot={isUp    || allCenter} />
                <DpadArrow dir="down"  hot={isDown  || allCenter} />
                <DpadArrow dir="left"  hot={isLeft  || allCenter} />
                <DpadArrow dir="right" hot={isRight || allCenter} />

                <g>
                    <circle
                        cx="200" cy="200" r="50"
                        fill={isEnter || isEnterHold || allCenter ? 'url(#onbCenterHot)' : 'url(#onbCenter)'}
                        stroke={isEnter || isEnterHold || allCenter
                            ? 'rgba(168,228,255,0.8)'
                            : 'rgba(255,255,255,0.1)'}
                        strokeWidth="1.5"
                        filter={isEnter || isEnterHold || allCenter ? 'url(#onbHotGlow)' : undefined}
                        style={{
                            transition: 'all 320ms ease',
                            animation: isEnterHold ? 'vesperOnbGlow 0.9s ease-in-out infinite' : undefined,
                        }}
                    />
                    <text
                        x="200" y="208"
                        textAnchor="middle"
                        style={{
                            fontFamily: 'Geist Mono, ui-monospace, monospace',
                            fontSize: 18, fontWeight: 700, letterSpacing: '0.18em',
                            fill: isEnter || isEnterHold || allCenter ? '#06080F' : 'rgba(255,255,255,0.8)',
                            transition: 'fill 320ms ease',
                        }}
                    >
                        OK
                    </text>
                </g>

                <g transform="translate(312, 312)" filter={isBack ? 'url(#onbHotGlow)' : undefined}>
                    <rect
                        x="-32" y="-16" width="64" height="32" rx="16"
                        fill={isBack ? 'url(#onbBtnHot)' : 'url(#onbBtn)'}
                        stroke={isBack ? 'rgba(168,228,255,0.85)' : 'rgba(255,255,255,0.1)'}
                        strokeWidth="1.5"
                        style={{ transition: 'all 320ms ease' }}
                    />
                    <text
                        x="0" y="5"
                        textAnchor="middle"
                        style={{
                            fontFamily: 'Geist Mono, ui-monospace, monospace',
                            fontSize: 11, fontWeight: 700, letterSpacing: '0.22em',
                            fill: isBack ? '#06080F' : 'rgba(255,255,255,0.8)',
                            transition: 'fill 320ms ease',
                        }}
                    >
                        BACK
                    </text>
                </g>
            </svg>
        </div>
    );
}

function DpadArrow({ dir, hot }) {
    const transform = {
        up:    'translate(200, 200) rotate(0)',
        right: 'translate(200, 200) rotate(90)',
        down:  'translate(200, 200) rotate(180)',
        left:  'translate(200, 200) rotate(270)',
    }[dir];
    return (
        <g transform={transform} filter={hot ? 'url(#onbHotGlow)' : undefined}>
            <rect
                x="-32" y="-118" width="64" height="50" rx="14"
                fill={hot ? 'url(#onbBtnHot)' : 'url(#onbBtn)'}
                stroke={hot ? 'rgba(168,228,255,0.85)' : 'rgba(255,255,255,0.1)'}
                strokeWidth="1.5"
                style={{ transition: 'all 320ms ease' }}
            />
            <path
                d="M-14,-86 L0,-104 L14,-86 Z"
                fill={hot ? '#06080F' : 'rgba(255,255,255,0.82)'}
                style={{ transition: 'fill 320ms ease' }}
            />
        </g>
    );
}
