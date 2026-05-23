/**
 * FeatureNudge — friendly bottom-right toast that suggests an unused
 * feature ~3 days after install (then ~7 days between subsequent
 * nudges).  See `lib/engagement.js` for the rules.
 *
 * Mounted globally in App.js.  Self-gates: only renders on the Home
 * route, only ONCE per app session, only after a 6-second idle delay
 * (so it doesn't pop the moment the user opens the app).
 */

import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Bookmark, UserRound, Clock, Sparkles, UsersRound, X } from 'lucide-react';
import {
    pickNextNudge,
    markNudgeShown,
    snoozeNudge,
    muteNudgeForever,
} from '../lib/engagement';
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

    /* Only consider showing on the Home route — feels weird to pop a
       nudge while the user is mid-search or mid-playback. */
    const onHome = location.pathname === '/';

    useEffect(() => {
        if (!onHome) return;
        if (SESSION_SHOWN) return;
        const timer = setTimeout(() => {
            const next = pickNextNudge();
            if (!next) return;
            SESSION_SHOWN = true;
            markNudgeShown(next.key);
            setNudge(next);
        }, 6000);
        return () => clearTimeout(timer);
    }, [onHome]);

    if (!nudge) return null;

    const Icon = ICONS[nudge.iconName] || Sparkles;

    const handleTry = () => {
        setNudge(null);
        navigate(nudge.actionPath);
    };
    const handleLater = () => {
        snoozeNudge(nudge.key);
        setNudge(null);
    };
    const handleMute = () => {
        muteNudgeForever(nudge.key);
        setNudge(null);
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
                            A quick tip
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
