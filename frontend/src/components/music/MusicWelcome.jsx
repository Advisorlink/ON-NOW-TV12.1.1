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
        try {
            return window.localStorage.getItem(STORAGE_KEY) !== '1';
        } catch {
            return true;
        }
    });

    const dismiss = React.useCallback(() => {
        try { window.localStorage.setItem(STORAGE_KEY, '1'); }
        catch { /* private mode etc — fall through */ }
        setVisible(false);
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
                padding: 'clamp(24px, 4vw, 64px)',
                background: 'radial-gradient(120% 100% at 30% 10%, rgba(93,200,255,0.18) 0%, rgba(8,12,24,0.92) 45%, rgba(4,6,14,0.98) 80%)',
            }}
        >
            <div
                style={{
                    width: 'min(720px, 100%)',
                    maxHeight: '92vh',
                    overflowY: 'auto',
                    borderRadius: 24,
                    padding: 'clamp(32px, 4vw, 56px)',
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
                        width: 64,
                        height: 64,
                        borderRadius: 18,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        background: 'linear-gradient(135deg, rgba(93,200,255,0.28), rgba(93,200,255,0.06))',
                        border: '1px solid rgba(93,200,255,0.45)',
                        marginBottom: 24,
                    }}
                >
                    <Music2 size={30} strokeWidth={2.2} style={{ color: '#5DC8FF' }} />
                </div>

                {/* Eyebrow */}
                <div
                    style={{
                        textTransform: 'uppercase',
                        fontFamily: 'monospace',
                        letterSpacing: '0.32em',
                        fontSize: 12,
                        color: '#5DC8FF',
                        marginBottom: 10,
                    }}
                >
                    Welcome to Tunes
                </div>

                {/* Headline — gradient text */}
                <h1
                    style={{
                        margin: '0 0 14px 0',
                        fontSize: 'clamp(28px, 3.4vw, 42px)',
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
                        margin: '0 0 28px 0',
                        fontSize: 'clamp(15px, 1.1vw, 17px)',
                        lineHeight: 1.55,
                        color: 'rgba(232,238,250,0.78)',
                    }}
                >
                    Quick heads-up before you start — your music, podcasts and
                    radio stream from YouTube under the hood.  You only have
                    to sign in once.
                </p>

                {/* Bullets */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 16, marginBottom: 32 }}>
                    <Bullet
                        icon={Youtube}
                        title="Streaming via YouTube"
                        body="We use YouTube's music, podcast and radio libraries to give you free access to millions of tracks — no separate subscription needed."
                    />
                    <Bullet
                        icon={UserPlus}
                        title="You don't have to use your real Google account"
                        body={
                            <>
                                Make a brand-new throwaway account just for this if you
                                want — Tunes won&apos;t mind.  You can{' '}
                                <a
                                    href="https://accounts.google.com/signup"
                                    target="_blank"
                                    rel="noopener noreferrer"
                                    style={{ color: '#5DC8FF', textDecoration: 'underline' }}
                                >
                                    create a free Google account
                                    <ExternalLink
                                        size={12}
                                        style={{
                                            display: 'inline-block',
                                            marginLeft: 4,
                                            verticalAlign: '-1px',
                                        }}
                                    />
                                </a>{' '}
                                in about 30 seconds.
                            </>
                        }
                    />
                    <Bullet
                        icon={ShieldCheck}
                        title="We never see your password"
                        body="Sign-in happens directly with Google on their own page.  ON NOW Tunes never receives or stores your password — only a permission token that lets us play the audio."
                    />
                </div>

                {/* Continue button */}
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
                        padding: '16px 24px',
                        borderRadius: 999,
                        border: 'none',
                        cursor: 'pointer',
                        background:
                            'linear-gradient(135deg, #5DC8FF 0%, #3aa6e0 70%, #2c89be 100%)',
                        color: '#04121F',
                        fontSize: 16,
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
                    Got it — let&apos;s go
                    <ArrowRight size={18} strokeWidth={2.4} />
                </button>

                <p
                    style={{
                        margin: '20px 0 0 0',
                        textAlign: 'center',
                        fontSize: 12,
                        color: 'rgba(168,181,199,0.55)',
                    }}
                >
                    You&apos;ll only see this welcome once.  You can sign in or
                    sign out of YouTube any time from Tunes &rsaquo; Settings.
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
                gap: 16,
                alignItems: 'flex-start',
                padding: '16px 18px',
                borderRadius: 14,
                background: 'rgba(255,255,255,0.025)',
                border: '1px solid rgba(255,255,255,0.06)',
            }}
        >
            <div
                aria-hidden="true"
                style={{
                    width: 36,
                    height: 36,
                    borderRadius: 10,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: 'rgba(93,200,255,0.12)',
                    border: '1px solid rgba(93,200,255,0.30)',
                    flexShrink: 0,
                }}
            >
                <Icon size={18} strokeWidth={2} style={{ color: '#5DC8FF' }} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
                <div
                    style={{
                        fontSize: 15,
                        fontWeight: 700,
                        color: 'rgba(245,248,255,0.95)',
                        marginBottom: 4,
                        letterSpacing: '-0.01em',
                    }}
                >
                    {title}
                </div>
                <div
                    style={{
                        fontSize: 13,
                        lineHeight: 1.5,
                        color: 'rgba(186,196,214,0.78)',
                    }}
                >
                    {body}
                </div>
            </div>
        </div>
    );
}
