/**
 * MusicWelcome.jsx — first-launch onboarding for ON NOW Tunes.
 *
 * Why this exists: the user reported that tapping the Music tile
 * on the launcher used to bounce them into Vesper's login screen,
 * then later into Google's bare OAuth card — both confusing.  In
 * v2.10.35 we bypassed Vesper auth for `/music/*` entirely; this
 * component is the second half of that fix.  It tells the user
 * UP-FRONT how the Music app works so the eventual Google sign-in
 * isn't a surprise:
 *
 *   • ON NOW Tunes streams from YouTube under the hood.
 *   • You'll be asked to sign in with a Google account once.
 *   • You don't have to use your real account — a throwaway / fake
 *     account created just for this works perfectly.
 *   • We never see your password.  Sign-in happens directly with
 *     Google on their own page.
 *
 * Shown once per device (gated by a localStorage flag).  On
 * Continue, the flag is set and the welcome unmounts — the user
 * lands on Music Home with no further interruptions.
 *
 * Visual design matches the rest of the Vesper / Tunes look:
 * glass card with cyan accent, big gradient headline, three icon
 * bullets explaining the deal.
 */
import React from 'react';
import { Music2, Youtube, UserPlus, ShieldCheck, ExternalLink, ArrowRight } from 'lucide-react';

const STORAGE_KEY = 'onnowtv-tunes-welcome-seen-v1';

export default function MusicWelcome() {
    const [visible, setVisible] = React.useState(() => {
        if (typeof window === 'undefined') return false;
        // v2.10.42 — Two reasons the Welcome should show:
        //   1. First launch (localStorage flag absent).
        //   2. Running on the Tunes APK AND the box is not yet
        //      signed in to YouTube.  We can't let the user reach
        //      Music Home without a YouTube session — the IFrame
        //      Player would silently fail every play attempt.  By
        //      showing the Welcome again we get them through
        //      `bridge.startYouTubeSignIn()` on dismiss.
        // Browser users (no native bridge) just get the first-launch
        // gating, since they have no YouTube cookie state to check.
        try {
            const flagSet = window.localStorage.getItem(STORAGE_KEY) === '1';
            const bridge = window.OnNowTV;
            const isNative = !!(bridge && typeof bridge.isNative === 'function' && bridge.isNative());
            if (isNative && typeof bridge.isSignedInToYouTube === 'function') {
                const signedIn = !!bridge.isSignedInToYouTube();
                // Native + not signed in → ALWAYS show.
                if (!signedIn) return true;
                // Native + signed in → only on first launch.
                return !flagSet;
            }
            // Browser (no native bridge) — first-launch only.
            return !flagSet;
        } catch {
            return true;
        }
    });

    const dismiss = React.useCallback(() => {
        try { window.localStorage.setItem(STORAGE_KEY, '1'); }
        catch { /* private mode etc — fall through */ }
        setVisible(false);

        // v2.10.42 — On native bridge (the Tunes APK), kick off the
        // YouTube sign-in flow now that the user has seen the
        // Welcome.  Previously the native bootFlow jumped straight
        // to YouTube without giving the React Welcome a chance to
        // render at all; now the order is correct: Welcome →
        // user-confirm → YouTube sign-in.
        //
        // No-op when running in a browser (no native bridge), and
        // also no-op if the box is already signed in (the bridge
        // method itself early-returns).
        try {
            const bridge = window.OnNowTV;
            if (bridge && typeof bridge.startYouTubeSignIn === 'function') {
                const alreadySignedIn = (
                    typeof bridge.isSignedInToYouTube === 'function' &&
                    bridge.isSignedInToYouTube()
                );
                if (!alreadySignedIn) {
                    bridge.startYouTubeSignIn();
                }
            }
        } catch { /* bridge unavailable — ignore */ }
    }, []);

    // Auto-focus the Continue button so D-pad users can hit OK
    // immediately without an extra Right press.
    const continueRef = React.useRef(null);
    React.useEffect(() => {
        if (!visible) return;
        const t = window.setTimeout(() => {
            try { continueRef.current?.focus(); } catch { /* no-op */ }
        }, 80);
        return () => window.clearTimeout(t);
    }, [visible]);

    if (!visible) return null;

    return (
        <div
            data-testid="music-welcome"
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 9999,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: 'clamp(16px, 2.5vw, 32px)',
                background: 'radial-gradient(120% 100% at 30% 10%, rgba(93,200,255,0.18) 0%, rgba(8,12,24,0.92) 45%, rgba(4,6,14,0.98) 80%)',
            }}
        >
            <div
                style={{
                    /* v2.10.37 — Compact for 16:9 TV viewports.
                       Previous size (720 × auto with 56px padding +
                       three full bullets) overflowed the safe area
                       on 1080p TVs once title bar + system insets
                       were taken into account.  Tightened to fit
                       comfortably inside `92vh - 64px` with room to
                       spare on 720p panels. */
                    width: 'min(560px, 100%)',
                    maxHeight: '88vh',
                    overflowY: 'auto',
                    borderRadius: 18,
                    padding: 'clamp(20px, 2.4vw, 32px)',
                    background:
                        'linear-gradient(180deg, rgba(20,28,46,0.88) 0%, rgba(10,15,28,0.92) 100%)',
                    border: '1px solid rgba(93,200,255,0.22)',
                    boxShadow:
                        '0 24px 80px -16px rgba(0,0,0,0.7), 0 0 0 1px rgba(255,255,255,0.04) inset',
                }}
            >
                {/* Icon badge */}
                <div
                    aria-hidden="true"
                    style={{
                        width: 48,
                        height: 48,
                        borderRadius: 14,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        background: 'linear-gradient(135deg, rgba(93,200,255,0.28), rgba(93,200,255,0.06))',
                        border: '1px solid rgba(93,200,255,0.45)',
                        marginBottom: 16,
                    }}
                >
                    <Music2 size={24} strokeWidth={2.2} style={{ color: '#5DC8FF' }} />
                </div>

                {/* Eyebrow */}
                <div
                    style={{
                        textTransform: 'uppercase',
                        fontFamily: 'monospace',
                        letterSpacing: '0.32em',
                        fontSize: 11,
                        color: '#5DC8FF',
                        marginBottom: 8,
                    }}
                >
                    Welcome to Tunes
                </div>

                {/* Headline — gradient text */}
                <h1
                    style={{
                        margin: '0 0 10px 0',
                        fontSize: 'clamp(22px, 2.4vw, 30px)',
                        fontWeight: 800,
                        lineHeight: 1.1,
                        letterSpacing: '-0.02em',
                        backgroundImage:
                            'linear-gradient(90deg, #FFFFFF 0%, #BFE2FF 60%, #5DC8FF 100%)',
                        WebkitBackgroundClip: 'text',
                        backgroundClip: 'text',
                        color: 'transparent',
                    }}
                >
                    Music, powered by YouTube.
                </h1>

                {/* Subhead */}
                <p
                    style={{
                        margin: '0 0 18px 0',
                        fontSize: 'clamp(13px, 0.9vw, 15px)',
                        lineHeight: 1.5,
                        color: 'rgba(232,238,250,0.78)',
                    }}
                >
                    Your music, podcasts and radio stream from YouTube under
                    the hood. You only sign in once.
                </p>

                {/* Bullets — tightened */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 20 }}>
                    <Bullet
                        icon={Youtube}
                        title="Streams from YouTube"
                        body="Millions of free tracks, podcasts and radio stations. No extra subscription needed."
                    />
                    <Bullet
                        icon={UserPlus}
                        title="You don't need your real account"
                        body={
                            <>
                                A brand-new throwaway Google account works perfectly.{' '}
                                <a
                                    href="https://accounts.google.com/signup"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    style={{ color: '#5DC8FF', textDecoration: 'underline' }}
                                >
                                    Create one
                                    <ExternalLink
                                        size={11}
                                        style={{
                                            display: 'inline-block',
                                            marginLeft: 3,
                                            verticalAlign: '-1px',
                                        }}
                                    />
                                </a>{' '}
                                in 30 seconds.
                            </>
                        }
                    />
                    <Bullet
                        icon={ShieldCheck}
                        title="We never see your password"
                        body="Sign-in happens directly with Google. We only receive a permission token."
                    />
                </div>

                {/* Continue button — compact */}
                <button
                    ref={continueRef}
                    type="button"
                    data-testid="music-welcome-continue"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={dismiss}
                    style={{
                        width: '100%',
                        padding: '12px 22px',
                        borderRadius: 999,
                        border: 'none',
                        cursor: 'pointer',
                        background:
                            'linear-gradient(135deg, #5DC8FF 0%, #3aa6e0 70%, #2c89be 100%)',
                        color: '#04121F',
                        fontSize: 14,
                        fontWeight: 800,
                        letterSpacing: '0.02em',
                        boxShadow:
                            '0 16px 36px -12px rgba(93,200,255,0.55), 0 0 0 1px rgba(255,255,255,0.12) inset',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        gap: 8,
                    }}
                >
                    Got it. Let&apos;s go
                    <ArrowRight size={16} strokeWidth={2.4} />
                </button>

                <p
                    style={{
                        margin: '12px 0 0 0',
                        textAlign: 'center',
                        fontSize: 11,
                        color: 'rgba(168,181,199,0.55)',
                    }}
                >
                    Shown only on first launch.  Manage YouTube sign-in from Tunes &rsaquo; Settings.
                </p>
            </div>
        </div>
    );
}

function Bullet({ icon: Icon, title, body }) {
    return (
        <div
            style={{
                display: 'flex',
                gap: 12,
                alignItems: 'flex-start',
                padding: '10px 12px',
                borderRadius: 11,
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(255,255,255,0.06)',
            }}
        >
            <div
                aria-hidden="true"
                style={{
                    width: 30,
                    height: 30,
                    borderRadius: 8,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: 'rgba(93,200,255,0.12)',
                    border: '1px solid rgba(93,200,255,0.30)',
                    flexShrink: 0,
                }}
            >
                <Icon size={15} strokeWidth={2} style={{ color: '#5DC8FF' }} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
                <div
                    style={{
                        fontSize: 13,
                        fontWeight: 700,
                        color: 'rgba(245,248,255,0.95)',
                        marginBottom: 2,
                        letterSpacing: '-0.01em',
                    }}
                >
                    {title}
                </div>
                <div
                    style={{
                        fontSize: 12,
                        lineHeight: 1.45,
                        color: 'rgba(186,196,214,0.78)',
                    }}
                >
                    {body}
                </div>
            </div>
        </div>
    );
}
