/**
 * FeatureNudge — friendly bottom-right toast that suggests an unused
 * feature ~3 days after install (then ~7 days between subsequent
 * nudges).  See `lib/engagement.js` for the rules.
 *
 * Mounted globally in App.js.  Self-gates: only renders on the Home
 * route, only ONCE per app session, only after a 6-second idle delay
 * (so it doesn't pop the moment the user opens the app).
 */

import React, { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Bookmark, UserRound, Clock, Sparkles, UsersRound, X } from 'lucide-react';
import {
    NUDGE_FEATURES,
    pickNextNudge,
    markNudgeShown,
    snoozeNudge,
    muteNudgeForever,
} from '../lib/engagement';
import { hasSeenOnboarding } from './Onboarding';
import useIsMobile from '../lib/useIsMobile';

/* Module-level flag so we render AT MOST one nudge per app session
   even if the user navigates Home → Library → Home and the
   FeatureNudge re-mounts.  Cleared on page reload (= new session). */
let SESSION_SHOWN = false;

const ICONS = {
    bookmark: Bookmark,
    'user-round': UserRound,
    clock: Clock,
    sparkles: Sparkles,
    'users-round': UsersRound,
};

export default function FeatureNudge() {
    const location = useLocation();
    const navigate = useNavigate();
    const isMobile = useIsMobile();
    const [nudge, setNudge] = useState(null);
    const [isPreview, setIsPreview] = useState(false);
    /* v2.12.15 — Refs for D-pad focus management:
       • tryBtnRef       — primary "Try it" button, gets focus on mount
       • prevFocusRef    — whatever tile was focused BEFORE the nudge
                           opened, so we can hand focus back cleanly
                           when the user dismisses it (otherwise the
                           spatial-focus engine has no last-known
                           element and jumps somewhere random). */
    const tryBtnRef = useRef(null);
    const prevFocusRef = useRef(null);

    /* Only consider showing on the Home route — feels weird to pop a
       nudge while the user is mid-search or mid-playback. */
    const onHome = location.pathname === '/';

    useEffect(() => {
        if (!onHome) return;
        if (SESSION_SHOWN) return;
        // v2.12.14 — Never fire the first tip while the onboarding
        // slides are still on screen.  The user's spec: tips must
        // appear AFTER onboarding, once they've actually entered the
        // app.  We branch:
        //   • Onboarding already done → keep the existing 6-second
        //     idle delay so the tip doesn't slap the user in the face
        //     on Home mount.
        //   • Onboarding still open   → don't run any timer; wait for
        //     the `vesper:onboarding-complete` event, then fire after
        //     a short 1.5-second delay so the closing animation
        //     doesn't collide with the toast entrance.
        const alreadyDone = hasSeenOnboarding();

        const fire = () => {
            if (SESSION_SHOWN) return;
            const next = pickNextNudge();
            if (!next) return;
            SESSION_SHOWN = true;
            markNudgeShown(next.key);
            setNudge(next);
            setIsPreview(false);
        };

        if (alreadyDone) {
            const t = setTimeout(fire, 6000);
            return () => clearTimeout(t);
        }
        let postDelay;
        const onComplete = () => {
            postDelay = setTimeout(fire, 1500);
        };
        window.addEventListener('vesper:onboarding-complete', onComplete);
        return () => {
            window.removeEventListener('vesper:onboarding-complete', onComplete);
            if (postDelay) clearTimeout(postDelay);
        };
    }, [onHome]);

    /* Preview path — fired by Settings → Tips → "Preview".  Bypasses
       all gates (3-day grace, 7-day spacing, once-per-session) and
       does NOT mark the nudge as shown, so the real cool-down isn't
       affected by a test fire.  Works on ANY route, not just Home. */
    useEffect(() => {
        const handler = (e) => {
            const key = e?.detail?.key;
            if (!key) return;
            const feature = NUDGE_FEATURES.find((f) => f.key === key);
            if (!feature) return;
            setNudge(feature);
            setIsPreview(true);
        };
        window.addEventListener('vesper:nudge-preview', handler);
        return () => window.removeEventListener('vesper:nudge-preview', handler);
    }, []);

    /* v2.12.15 — Whenever the nudge becomes visible, hand D-pad focus
       to the primary "Try it" button so the remote can act on it
       without the user having to blindly guess where the highlight
       jumped to.  Also wires a global BACK/ESCAPE handler that
       dismisses the toast (same behaviour as tapping "Maybe later")
       so a single BACK press on the remote always closes the tip.
       Runs whenever `nudge` transitions from null → object. */
    useEffect(() => {
        if (!nudge) return undefined;
        // Remember whatever tile was focused BEFORE we hijack focus,
        // so we can hand it back when the toast closes.
        const ae = document.activeElement;
        if (
            ae &&
            ae !== document.body &&
            typeof ae.focus === 'function' &&
            /* Don't try to restore focus to our own about-to-mount
               buttons or to a stale button we're replacing. */
            !(ae.closest && ae.closest('[data-testid="feature-nudge"]'))
        ) {
            prevFocusRef.current = ae;
        }
        // Focus the primary CTA on the next frame so the ref is
        // attached and any entrance animation has committed the
        // element to layout (WebView on cheap boxes sometimes drops
        // a same-tick focus() on freshly-mounted nodes).
        const raf = requestAnimationFrame(() => {
            const btn = tryBtnRef.current;
            if (!btn) return;
            try {
                btn.focus({ preventScroll: true });
                // Mirror the data-focused ring for Android WebView
                // where :focus-visible is unreliable — matches the
                // pattern used across the spatial focus engine.
                btn.setAttribute('data-focused', 'true');
            } catch { /* ignore */ }
        });
        // BACK / ESCAPE = dismiss the tip (same as "Maybe later").
        // Capture-phase + stopPropagation so the app-wide back
        // handlers (Home has one that routes to profile picker)
        // don't fire underneath us.
        const onKey = (e) => {
            const k = e.key;
            if (k === 'Escape' || k === 'Backspace' || k === 'GoBack' || k === 'BrowserBack') {
                e.preventDefault();
                e.stopPropagation();
                if (!isPreview) snoozeNudge(nudge.key);
                setNudge(null);
                // Inline restore — closures don't have access to
                // restorePrevFocus() defined below this effect.
                const prev = prevFocusRef.current;
                prevFocusRef.current = null;
                if (prev && prev.isConnected) {
                    try { prev.focus({ preventScroll: true }); } catch { /* ignore */ }
                }
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => {
            cancelAnimationFrame(raf);
            window.removeEventListener('keydown', onKey, true);
        };
    }, [nudge, isPreview]);

    if (!nudge) return null;

    const Icon = ICONS[nudge.iconName] || Sparkles;

    /* v2.12.15 — Restore focus to whatever tile was highlighted
       before the nudge popped, so the D-pad user isn't stranded on
       a detached button after dismissing.  No-op on mobile / touch. */
    const restorePrevFocus = () => {
        const prev = prevFocusRef.current;
        prevFocusRef.current = null;
        if (!prev) return;
        try {
            // Only restore if the element is still on-screen and
            // still focusable — pages that navigated away shouldn't
            // reach back and grab focus.
            if (prev.isConnected && typeof prev.focus === 'function') {
                prev.focus({ preventScroll: true });
            }
        } catch { /* ignore */ }
    };

    const handleTry = () => {
        // Don't restore prev-focus: we're navigating away.
        prevFocusRef.current = null;
        setNudge(null);
        navigate(nudge.actionPath);
    };
    const handleLater = () => {
        if (!isPreview) snoozeNudge(nudge.key);
        setNudge(null);
        restorePrevFocus();
    };
    const handleMute = () => {
        if (!isPreview) muteNudgeForever(nudge.key);
        setNudge(null);
        restorePrevFocus();
    };

    /* Layout differs slightly per platform:
        - TV / desktop: fixed bottom-right toast, focusable buttons
                        so D-pad can hit them
        - Mobile:       full-width card hovering above bottom-nav,
                        thumb-friendly tap targets                  */
    const containerStyle = isMobile
        ? {
              position: 'fixed',
              left: 12,
              right: 12,
              bottom: 'calc(80px + env(safe-area-inset-bottom, 0px))',
              zIndex: 130,
          }
        : {
              position: 'fixed',
              right: 28,
              bottom: 28,
              maxWidth: 380,
              zIndex: 130,
          };

    return (
        <div
            data-testid="feature-nudge"
            data-nudge-key={nudge.key}
            style={containerStyle}
            className="vesper-nudge-enter"
        >
            <div
                style={{
                    background: 'rgba(12, 22, 38, 0.96)',
                    border: '1px solid rgba(56, 184, 255, 0.32)',
                    borderRadius: 18,
                    boxShadow: '0 24px 48px -16px rgba(0,0,0,0.6), 0 0 0 1px rgba(56,184,255,0.08)',
                    padding: '18px 18px 16px 18px',
                    backdropFilter: 'blur(18px)',
                    WebkitBackdropFilter: 'blur(18px)',
                    position: 'relative',
                }}
            >
                <button
                    data-testid="feature-nudge-close"
                    data-focusable="true"
                    onClick={handleMute}
                    aria-label="Don't show this again"
                    title="Don't show this again"
                    style={{
                        position: 'absolute',
                        top: 10,
                        right: 10,
                        width: 28,
                        height: 28,
                        borderRadius: 999,
                        background: 'transparent',
                        border: '1px solid rgba(255,255,255,0.12)',
                        color: 'var(--vesper-text-2)',
                        display: 'grid',
                        placeItems: 'center',
                        cursor: 'pointer',
                    }}
                >
                    <X size={14} strokeWidth={2.2} />
                </button>

                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
                    <div
                        style={{
                            width: 44,
                            height: 44,
                            flexShrink: 0,
                            borderRadius: 12,
                            background: 'rgba(56, 184, 255, 0.14)',
                            display: 'grid',
                            placeItems: 'center',
                            color: 'var(--vesper-blue)',
                        }}
                    >
                        <Icon size={22} strokeWidth={1.8} />
                    </div>
                    <div style={{ minWidth: 0, flex: 1 }}>
                        <div
                            style={{
                                color: 'var(--vesper-text-2)',
                                fontSize: 11,
                                letterSpacing: 1.5,
                                textTransform: 'uppercase',
                                marginBottom: 4,
                            }}
                        >
                            {isPreview ? 'Preview · A quick tip' : 'A quick tip'}
                        </div>
                        <div
                            style={{
                                color: 'var(--vesper-text-1)',
                                fontSize: 16,
                                fontWeight: 600,
                                lineHeight: 1.25,
                                marginBottom: 6,
                                paddingRight: 24,
                            }}
                        >
                            {nudge.title}
                        </div>
                        <div
                            style={{
                                color: 'var(--vesper-text-2)',
                                fontSize: 13,
                                lineHeight: 1.5,
                            }}
                        >
                            {nudge.body}
                        </div>
                    </div>
                </div>

                <div
                    style={{
                        display: 'flex',
                        gap: 8,
                        marginTop: 14,
                        justifyContent: 'flex-end',
                    }}
                >
                    <button
                        data-testid="feature-nudge-later"
                        data-focusable="true"
                        onClick={handleLater}
                        style={{
                            background: 'transparent',
                            color: 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.16)',
                            borderRadius: 999,
                            padding: '9px 16px',
                            fontSize: 13,
                            fontWeight: 500,
                            cursor: 'pointer',
                        }}
                    >
                        Maybe later
                    </button>
                    <button
                        data-testid="feature-nudge-try"
                        data-focusable="true"
                        ref={tryBtnRef}
                        onClick={handleTry}
                        style={{
                            background: 'var(--vesper-blue)',
                            color: '#04060B',
                            border: '1px solid transparent',
                            borderRadius: 999,
                            padding: '9px 18px',
                            fontSize: 13,
                            fontWeight: 700,
                            cursor: 'pointer',
                        }}
                    >
                        {nudge.actionLabel}
                    </button>
                </div>
            </div>
        </div>
    );
}
