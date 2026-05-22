/**
 * <PartyReactions /> — floating emoji overlay used during Watch
 * Together playback.  Each reaction is a 64-96px emoji that floats up
 * from the bottom of the screen with a slight x-drift and fades out
 * over ~3 seconds.
 *
 * Props:
 *   • reactions — Array<{id, emoji, lane, memberName?}>
 *
 * The PARENT owns the reactions array (push new items when WS sends
 * 'reaction' OR when the local hook fires) and removes them after the
 * animation completes via setTimeout.  We deliberately don't do
 * lifecycle management here — keeps the overlay dumb and the
 * dedup/timeout logic centralised.
 */

import React from 'react';

const KEYFRAMES = `
@keyframes party-reaction-float {
    0%   { transform: translate(var(--from-x, 0px), 0)         scale(0.4); opacity: 0; }
    8%   { transform: translate(var(--from-x, 0px), -10px)     scale(1.15); opacity: 1; }
    20%  { transform: translate(var(--mid-x, 0px), -20vh)      scale(1.0);  opacity: 1; }
    100% { transform: translate(var(--to-x, 0px), -70vh)        scale(0.7);  opacity: 0; }
}
`;

export default function PartyReactions({ reactions }) {
    return (
        <>
            <style>{KEYFRAMES}</style>
            <div
                aria-hidden="true"
                style={{
                    position: 'fixed',
                    inset: 0,
                    pointerEvents: 'none',
                    overflow: 'hidden',
                    zIndex: 95,
                }}
            >
                {reactions.map((r) =>
                    r.kind === 'voice'
                        ? <VoiceBubble key={r.id} reaction={r} />
                        : <ReactionBubble key={r.id} reaction={r} />
                )}
            </div>
        </>
    );
}

function ReactionBubble({ reaction }) {
    // Random horizontal drift so 5 bubbles in a row don't stack.
    const fromX = reaction._fromX ?? 0;
    const midX = reaction._midX ?? 0;
    const toX = reaction._toX ?? 0;
    return (
        <div
            style={{
                position: 'absolute',
                bottom: '8vh',
                left: `${reaction.lane}%`,
                fontSize: 72,
                lineHeight: 1,
                filter: 'drop-shadow(0 6px 14px rgba(0,0,0,0.45))',
                animation: 'party-reaction-float 2.6s cubic-bezier(0.22, 0.7, 0.4, 1) forwards',
                '--from-x': `${fromX}px`,
                '--mid-x': `${midX}px`,
                '--to-x': `${toX}px`,
                userSelect: 'none',
            }}
        >
            <span>{reaction.emoji}</span>
            {reaction.memberName && (
                <div
                    style={{
                        marginTop: 4,
                        fontFamily: 'monospace',
                        fontSize: 11,
                        fontWeight: 700,
                        color: '#FFFFFF',
                        letterSpacing: '0.04em',
                        textShadow: '0 2px 10px rgba(0,0,0,0.85)',
                        textAlign: 'center',
                        maxWidth: 96,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                    }}
                >
                    {reaction.memberName.toUpperCase()}
                </div>
            )}
        </div>
    );
}

/**
 * Helper: produces a {x, lane, drift} bundle for a new reaction.
 * Lane: 8..92 percent (avoids hugging the edges).
 */
PartyReactions.nextBubble = function nextBubble(emoji, memberName) {
    const lane = 8 + Math.random() * 84;          // viewport %
    const fromX = (Math.random() * 60) - 30;      // -30..+30
    const midX = fromX + ((Math.random() * 80) - 40);
    const toX = midX + ((Math.random() * 120) - 60);
    return {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        emoji,
        memberName: memberName || null,
        lane,
        _fromX: fromX,
        _midX: midX,
        _toX: toX,
    };
};

/**
 * v2.7.55 — Voice-message bubble (transcribed text from a party
 * member's mic hold).  Stays on screen 8 seconds (per user spec)
 * with a slow upward drift + fade.  Rendered by <VoiceBubble/>.
 */
PartyReactions.nextVoiceBubble = function nextVoiceBubble(text, memberName, avatarEmoji) {
    return {
        id: `v-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
        kind: 'voice',
        text: String(text || '').slice(0, 160),
        memberName: memberName || null,
        avatarEmoji: avatarEmoji || '',
        // Lane chosen so two voice bubbles can co-exist without
        // overlapping — column position 14..72%.
        lane: 14 + Math.random() * 58,
    };
};

function VoiceBubble({ reaction }) {
    return (
        <div
            style={{
                position: 'absolute',
                bottom: '12vh',
                left: `${reaction.lane}%`,
                maxWidth: '28vw',
                padding: '12px 18px',
                background: 'linear-gradient(135deg, rgba(11,19,34,0.92), rgba(20,40,70,0.92))',
                border: '1px solid rgba(93,200,255,0.45)',
                borderRadius: 18,
                color: '#fff',
                fontSize: 18,
                lineHeight: 1.35,
                fontWeight: 500,
                boxShadow: '0 14px 40px rgba(0,0,0,0.55), 0 0 0 1px rgba(93,200,255,0.18)',
                animation: 'party-voice-float 8s ease-in-out forwards',
                backdropFilter: 'blur(14px)',
                WebkitBackdropFilter: 'blur(14px)',
            }}
        >
            <div
                style={{
                    fontFamily: 'monospace',
                    fontSize: 11,
                    fontWeight: 700,
                    color: '#5DC8FF',
                    letterSpacing: '0.14em',
                    marginBottom: 6,
                    textTransform: 'uppercase',
                }}
            >
                {reaction.avatarEmoji ? `${reaction.avatarEmoji}  ` : ''}
                {reaction.memberName ? reaction.memberName : 'Voice'}
            </div>
            <div>{reaction.text}</div>
            <style>{`
@keyframes party-voice-float {
    0%   { transform: translateY(20px) scale(0.92); opacity: 0; }
    8%   { transform: translateY(0)    scale(1);    opacity: 1; }
    88%  { transform: translateY(-30vh) scale(1);   opacity: 1; }
    100% { transform: translateY(-44vh) scale(0.96); opacity: 0; }
}
            `}</style>
        </div>
    );
}
