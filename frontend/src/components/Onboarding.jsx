/**
 * Onboarding — premium full-screen welcome tour shown once after a
 * client logs in to a profile.  Walks the user through every major
 * feature (movies/TV, library, calendar, search, watch together,
 * profiles, sources, settings) — explicitly EXCLUDING Live TV
 * per product brief.
 *
 * Visual focal point is a beautiful 3D circular D-pad that
 * "presses" the appropriate direction for each step (Right arrow
 * glows when advancing, Enter glows on OK-driven actions like
 * "save to library", Back glows on the wrap-up screen).
 *
 * The component is fully self-contained: it owns its own keyframes
 * + glow filters + step index state.  Mount it at the app root and
 * gate visibility on `localStorage["vesper-onboarding-seen-v1"]`
 * plus an active non-kids profile.  Manual replay is wired via a
 * Settings → "Replay welcome tour" button that clears the flag and
 * re-mounts the component.
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
    Plug,
    Settings as SettingsIcon,
    Sparkles,
    ChevronRight,
    ChevronLeft,
    Check,
} from 'lucide-react';

const STORAGE_KEY = 'vesper-onboarding-seen-v1';

/* ---------------------------------------------------------------- */
/* Step deck — each entry drives one screen of copy + which D-pad   */
/* button glows + which lucide icon shows above the headline.       */
/* ---------------------------------------------------------------- */
const STEPS = [
    {
        id: 'welcome',
        glow: 'center',
        icon: Sparkles,
        eyebrow: 'Welcome to On Now TV',
        title: 'A 60-second tour',
        body:
            "We'll show you the controls and every shortcut we've built — so you can move through the app like a pro.  Skip anytime, replay from Settings.",
    },
    {
        id: 'navigation',
        glow: 'right',
        icon: PlayCircle,
        eyebrow: '01 · Move around',
        title: 'D-pad arrows navigate everything',
        body:
            "Use UP, DOWN, LEFT, RIGHT to glide between cards, rows and menus.  Every tile glows when it's focused — so you always know where you are.",
    },
    {
        id: 'select',
        glow: 'enter',
        icon: PlayCircle,
        eyebrow: '02 · Open & play',
        title: 'OK opens, plays, confirms',
        body:
            "Tap OK to open a title, start playback, or confirm a choice.  In the player it pauses and resumes — second nature in a couple of presses.",
    },
    {
        id: 'longpress',
        glow: 'enter-hold',
        icon: BookmarkPlus,
        eyebrow: '03 · Save for later',
        title: 'Hold OK to save anything',
        body:
            "Press and HOLD the OK button on any poster — Movies, TV Shows, even Network catalogues — to drop it into your Library, Watch List or Favourites.",
    },
    {
        id: 'tv',
        glow: 'up',
        icon: Tv2,
        eyebrow: '04 · TV Shows',
        title: 'Newest seasons, every network',
        body:
            "The TV tab pulls the top 100 newest releases from every source you've installed.  Tap a genre chip to deep-dive — every romance, every thriller, the lot.",
    },
    {
        id: 'movies',
        glow: 'down',
        icon: Film,
        eyebrow: '05 · Movies',
        title: 'Same magic, big screen edition',
        body:
            "Newest cinema, blockbusters, classics — sorted by what just dropped.  Browse fast, hold OK to stash titles for date night.",
    },
    {
        id: 'library',
        glow: 'right',
        icon: BookmarkPlus,
        eyebrow: '06 · My Library',
        title: 'Everything you saved, one tap away',
        body:
            "Your Library keeps your saved movies and shows, Continue Watching, and your Watch List in one home.  Pick up exactly where you left off on any device.",
    },
    {
        id: 'calendar',
        glow: 'enter',
        icon: CalendarDays,
        eyebrow: '07 · Episode calendar',
        title: 'Never miss a new episode',
        body:
            'Inside the Library, the Calendar view shows when every TV show in your watch list drops a new episode.  Push OK on a date to see what airs that night.',
    },
    {
        id: 'search',
        glow: 'left',
        icon: SearchIcon,
        eyebrow: '08 · Search',
        title: 'Anything, instantly',
        body:
            "Open Search from the side rail (or press /) and type — results stream in live across every source.  TV shows, movies, actors, you name it.",
    },
    {
        id: 'watchtogether',
        glow: 'enter',
        icon: Users,
        eyebrow: '09 · Watch Together',
        title: 'Movie night, every night',
        body:
            "Start a Watch Party, share a 6-character code, and watch in perfect sync with anyone, anywhere.  Hold UP, DOWN, LEFT or RIGHT for 2 seconds during playback to send a reaction.",
    },
    {
        id: 'profiles',
        glow: 'up',
        icon: UserCircle,
        eyebrow: '10 · Profiles',
        title: 'One device, every household',
        body:
            "Each viewer gets their own profile — their own avatar, Continue Watching, Library and theme.  Kids mode keeps the wee ones safe behind a PIN.",
    },
    {
        id: 'sources',
        glow: 'down',
        icon: Plug,
        eyebrow: '11 · Sources',
        title: 'Bring your own catalogues',
        body:
            'Plug in any Stremio-compatible source from the Sources page — torrents, Real-Debrid, Plex, Jellyfin — they all light up the Movies and TV tabs automatically.',
    },
    {
        id: 'settings',
        glow: 'left',
        icon: SettingsIcon,
        eyebrow: '12 · Settings',
        title: 'Tune it your way',
        body:
            "Themes, autoplay, kids ratings, backup & restore — everything lives in Settings.  Backup your profile to a code, restore on any new device in seconds.",
    },
    {
        id: 'wrap',
        glow: 'back',
        icon: Check,
        eyebrow: 'You\'re ready',
        title: 'Enjoy the show',
        body:
            "Press BACK from any screen to go home.  Need a refresher?  Settings → \"Replay welcome tour\".  Have fun in there.",
    },
];

/* ================================================================ */
/* Public API: a) component, b) replay helper used by Settings.     */
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

    // Reset to step 0 whenever the overlay opens fresh.
    useEffect(() => {
        if (open) setStep(0);
    }, [open]);

    // Keyboard bindings — D-pad maps directly so the user actually
    // PRACTISES the buttons while the tour explains them.
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

    const finish = () => {
        markOnboardingSeen();
        if (onClose) onClose();
    };

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
            {/* Backdrop accents — soft glowing orbs */}
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

            {/* Skip pill — top right */}
            <button
                data-testid="onboarding-skip"
                onClick={finish}
                className="absolute flex items-center gap-2 rounded-full vesper-mono"
                style={{
                    top: 'clamp(20px, 2vw, 36px)',
                    right: 'clamp(20px, 2vw, 36px)',
                    padding: '10px 18px',
                    background: 'rgba(255,255,255,0.06)',
                    color: 'rgba(255,255,255,0.85)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    fontSize: 11,
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    fontWeight: 700,
                    cursor: 'pointer',
                    zIndex: 5,
                }}
            >
                <SkipForward size={14} />
                Skip tour
            </button>

            {/* Brand mark — top left */}
            <div
                className="absolute vesper-mono"
                style={{
                    top: 'clamp(28px, 2.4vw, 44px)',
                    left: 'clamp(28px, 2.4vw, 44px)',
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'rgba(255,255,255,0.7)',
                }}
            >
                <span style={{ color: 'var(--vesper-blue-bright)', fontWeight: 700 }}>
                    ON&nbsp;NOW&nbsp;TV
                </span>
                <span style={{ margin: '0 10px', opacity: 0.4 }}>·</span>
                Welcome tour
            </div>

            {/* Main content — two-column split */}
            <div
                className="relative flex items-center"
                style={{
                    gap: 'clamp(40px, 5vw, 96px)',
                    padding: '0 clamp(40px, 4vw, 96px)',
                    maxWidth: 1480,
                    width: '100%',
                }}
            >
                {/* LEFT — copy */}
                <div className="flex-1" style={{ minWidth: 0, maxWidth: 720 }}>
                    <div
                        className="flex items-center gap-3 mb-4"
                        style={{
                            color: 'var(--vesper-blue-bright)',
                            fontSize: 12,
                            letterSpacing: '0.32em',
                            textTransform: 'uppercase',
                            fontWeight: 700,
                        }}
                    >
                        <span
                            style={{
                                width: 38, height: 38,
                                borderRadius: '50%',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
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
                            fontSize: 'clamp(40px, 4.4vw, 72px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 0.98,
                            color: '#fff',
                            textShadow: '0 6px 24px rgba(0,0,0,0.6)',
                            marginBottom: 18,
                            animation: 'vesperOnbFade 480ms ease',
                        }}
                    >
                        {s.title}
                    </h1>

                    <p
                        key={`body-${step}`}
                        style={{
                            fontSize: 'clamp(15px, 1.18vw, 19px)',
                            lineHeight: 1.55,
                            color: 'rgba(255,255,255,0.8)',
                            maxWidth: '60ch',
                            marginBottom: 'clamp(28px, 3vw, 44px)',
                            animation: 'vesperOnbFade 540ms ease',
                        }}
                    >
                        {s.body}
                    </p>

                    {/* Progress + controls */}
                    <div
                        className="flex items-center gap-6 flex-wrap"
                        style={{ marginTop: 8 }}
                    >
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
                                fontSize: 14,
                                fontWeight: 600,
                                cursor: step === 0 ? 'default' : 'pointer',
                                opacity: step === 0 ? 0.5 : 1,
                                transition: 'opacity 200ms ease',
                            }}
                        >
                            <ChevronLeft size={16} />
                            Back
                        </button>

                        <button
                            data-testid="onboarding-next"
                            onClick={() => (step >= last ? finish() : setStep((i) => i + 1))}
                            className="flex items-center gap-2 rounded-full font-sans"
                            style={{
                                padding: '14px 26px',
                                background:
                                    'linear-gradient(135deg, var(--vesper-blue) 0%, #4FB8F0 100%)',
                                color: '#06080F',
                                border: 'none',
                                fontSize: 15,
                                fontWeight: 700,
                                cursor: 'pointer',
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
                                fontSize: 11,
                                letterSpacing: '0.22em',
                                textTransform: 'uppercase',
                                color: 'rgba(255,255,255,0.6)',
                                fontWeight: 700,
                            }}
                        >
                            Step {step + 1} of {STEPS.length}
                        </div>
                    </div>

                    {/* Progress bar */}
                    <div
                        style={{
                            height: 4,
                            background: 'rgba(255,255,255,0.06)',
                            borderRadius: 999,
                            marginTop: 22,
                            overflow: 'hidden',
                        }}
                    >
                        <div
                            style={{
                                width: `${progress}%`,
                                height: '100%',
                                background:
                                    'linear-gradient(90deg, rgba(93,200,255,0) 0%, var(--vesper-blue-bright) 50%, rgba(93,200,255,0) 100%)',
                                borderRadius: 999,
                                transition: 'width 480ms cubic-bezier(0.4, 0.0, 0.2, 1)',
                                boxShadow: '0 0 12px rgba(93,200,255,0.6)',
                            }}
                        />
                    </div>
                </div>

                {/* RIGHT — 3D D-pad */}
                <div
                    className="shrink-0 flex items-center justify-center"
                    style={{ width: 'clamp(280px, 26vw, 420px)' }}
                >
                    <DPad3D glow={s.glow} />
                </div>
            </div>

            {/* Self-contained keyframes */}
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
            `}</style>
        </div>
    );
}

/* ================================================================ */
/* 3D circular D-pad illustration with selective glow.              */
/* Built with layered SVG paths + drop-shadow filters for depth.    */
/* `glow` ∈ 'up'|'down'|'left'|'right'|'enter'|'enter-hold'|'back'  */
/*       | 'center'                                                 */
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
                animation: allCenter
                    ? 'vesperOnbPulse 3s ease-in-out infinite'
                    : undefined,
            }}
        >
            {/* Outer ambient glow */}
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

            <svg
                viewBox="0 0 400 400"
                style={{ width: '100%', height: '100%', display: 'block' }}
            >
                <defs>
                    {/* Body gradient — gives the 3D dome look */}
                    <radialGradient id="onbBody" cx="40%" cy="35%" r="65%">
                        <stop offset="0%" stopColor="#1c2540" />
                        <stop offset="55%" stopColor="#0d1226" />
                        <stop offset="100%" stopColor="#05070f" />
                    </radialGradient>
                    {/* Highlight gradient — top sheen */}
                    <linearGradient id="onbHighlight" x1="50%" y1="0%" x2="50%" y2="100%">
                        <stop offset="0%" stopColor="rgba(255,255,255,0.22)" />
                        <stop offset="50%" stopColor="rgba(255,255,255,0.04)" />
                        <stop offset="100%" stopColor="rgba(255,255,255,0)" />
                    </linearGradient>
                    {/* Arrow button gradient */}
                    <linearGradient id="onbBtn" x1="50%" y1="0%" x2="50%" y2="100%">
                        <stop offset="0%" stopColor="#293454" />
                        <stop offset="100%" stopColor="#0e1426" />
                    </linearGradient>
                    {/* Glowing arrow gradient */}
                    <linearGradient id="onbBtnHot" x1="50%" y1="0%" x2="50%" y2="100%">
                        <stop offset="0%" stopColor="#7FD9FF" />
                        <stop offset="100%" stopColor="#3DAFE8" />
                    </linearGradient>
                    {/* Center button gradient */}
                    <radialGradient id="onbCenter" cx="50%" cy="40%" r="55%">
                        <stop offset="0%" stopColor="#34416a" />
                        <stop offset="80%" stopColor="#0c1224" />
                    </radialGradient>
                    <radialGradient id="onbCenterHot" cx="50%" cy="40%" r="55%">
                        <stop offset="0%" stopColor="#A8E4FF" />
                        <stop offset="100%" stopColor="#3DAFE8" />
                    </radialGradient>

                    {/* Drop shadow filter for depth */}
                    <filter id="onbShadow" x="-20%" y="-20%" width="140%" height="140%">
                        <feGaussianBlur in="SourceAlpha" stdDeviation="6" />
                        <feOffset dx="0" dy="8" result="off" />
                        <feComponentTransfer>
                            <feFuncA type="linear" slope="0.55" />
                        </feComponentTransfer>
                        <feMerge>
                            <feMergeNode />
                            <feMergeNode in="SourceGraphic" />
                        </feMerge>
                    </filter>
                    {/* Glow filter for active buttons */}
                    <filter id="onbHotGlow" x="-50%" y="-50%" width="200%" height="200%">
                        <feGaussianBlur stdDeviation="6" result="blur" />
                        <feMerge>
                            <feMergeNode in="blur" />
                            <feMergeNode in="SourceGraphic" />
                        </feMerge>
                    </filter>
                </defs>

                {/* Outer ring */}
                <circle
                    cx="200" cy="200" r="170"
                    fill="url(#onbBody)"
                    stroke="rgba(255,255,255,0.06)"
                    strokeWidth="1.5"
                    filter="url(#onbShadow)"
                />
                {/* Inner shaped pad ring */}
                <circle
                    cx="200" cy="200" r="150"
                    fill="none"
                    stroke="rgba(255,255,255,0.08)"
                    strokeWidth="1"
                />
                {/* Top sheen */}
                <ellipse
                    cx="200" cy="120" rx="135" ry="70"
                    fill="url(#onbHighlight)"
                    style={{ pointerEvents: 'none' }}
                />

                {/* === D-pad cross (UP / DOWN / LEFT / RIGHT) === */}
                <DpadArrow dir="up"    hot={isUp    || allCenter} />
                <DpadArrow dir="down"  hot={isDown  || allCenter} />
                <DpadArrow dir="left"  hot={isLeft  || allCenter} />
                <DpadArrow dir="right" hot={isRight || allCenter} />

                {/* === Center / Enter button === */}
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
                            fontSize: 18,
                            fontWeight: 700,
                            letterSpacing: '0.18em',
                            fill: isEnter || isEnterHold || allCenter ? '#06080F' : 'rgba(255,255,255,0.8)',
                            transition: 'fill 320ms ease',
                        }}
                    >
                        OK
                    </text>
                </g>

                {/* === BACK pill (bottom right of the dial) === */}
                <g
                    transform="translate(312, 312)"
                    filter={isBack ? 'url(#onbHotGlow)' : undefined}
                >
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
                            fontSize: 11,
                            fontWeight: 700,
                            letterSpacing: '0.22em',
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
    /* All four arrows are drawn as triangle paths around the
     * center, transformed into position.  Hot state swaps fill +
     * adds the glow filter. */
    const transform = {
        up:    'translate(200, 200) rotate(0)',
        right: 'translate(200, 200) rotate(90)',
        down:  'translate(200, 200) rotate(180)',
        left:  'translate(200, 200) rotate(270)',
    }[dir];
    return (
        <g transform={transform} filter={hot ? 'url(#onbHotGlow)' : undefined}>
            {/* Pill base */}
            <rect
                x="-32" y="-118" width="64" height="50" rx="14"
                fill={hot ? 'url(#onbBtnHot)' : 'url(#onbBtn)'}
                stroke={hot ? 'rgba(168,228,255,0.85)' : 'rgba(255,255,255,0.1)'}
                strokeWidth="1.5"
                style={{ transition: 'all 320ms ease' }}
            />
            {/* Arrow chevron */}
            <path
                d="M-14,-86 L0,-104 L14,-86 Z"
                fill={hot ? '#06080F' : 'rgba(255,255,255,0.82)'}
                style={{ transition: 'fill 320ms ease' }}
            />
        </g>
    );
}
