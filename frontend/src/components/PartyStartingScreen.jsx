import React from 'react';
import { Users, Sparkles } from 'lucide-react';

/**
 * Full-screen takeover that hides the entire Player UI (video, top
 * bar, controls, preview, etc.) until a Watch Together party has
 * fully synced and the server flips status to `playing`.
 *
 * Guests should NEVER see the underlying player while their party
 * is forming.  The host also stays on this screen until every guest
 * has reported ready — keeping both sides locked together so
 * playback starts in lock-step.
 *
 * Props
 * -----
 *  • visible        — when false the component renders nothing
 *  • phase          — `buffering` | `waiting` | `countdown` | `playing`
 *  • countdown      — integer seconds (only meaningful when phase==='countdown')
 *  • role           — `host` | `guest`
 *  • partyCode      — short 6-char code, shown as a status pill
 *  • title          — fallback title until cinemeta arrives
 *  • previewMeta    — `{title, poster, background, year, genres, synopsis}`
 *  • members        — `[{id, name, avatar, ready}]` to render the
 *                     "everyone's ready" rail
 */
export default function PartyStartingScreen({
    visible,
    phase,
    countdown,
    role,
    partyCode,
    title,
    previewMeta,
    members,
}) {
    if (!visible) return null;

    const backdrop = previewMeta?.background || previewMeta?.poster;

    const headline =
        phase === 'countdown'
            ? 'Starting now'
            : phase === 'waiting'
            ? 'Waiting on your party'
            : role === 'host'
            ? 'Your party is starting'
            : 'Get ready to watch';

    const subline =
        phase === 'countdown'
            ? 'Sit back, the show is about to begin.'
            : phase === 'waiting'
            ? role === 'host'
                ? "We're waiting on your guests to buffer.  Playback will begin the instant everyone's ready."
                : 'Hang tight, we sync with the host so every frame lands in lock-step.'
            : role === 'host'
            ? 'Loading the stream on every screen at the same time…'
            : "You're joining a Watch Party.  Loading the same stream the host picked…";

    return (
        <div
            data-testid="party-starting-screen"
            className="fixed inset-0"
            style={{
                zIndex: 70, // ABOVE every other player overlay
                color: '#fff',
                overflow: 'hidden',
                background: backdrop
                    ? `linear-gradient(180deg, rgba(6,8,15,0.78) 0%, rgba(6,8,15,0.92) 50%, #06080F 100%), url(${backdrop}) center/cover no-repeat`
                    : 'radial-gradient(ellipse at center, #0e1830 0%, #06080F 70%)',
            }}
        >
            {/* Cinematic vignette + grain overlay */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background:
                        'radial-gradient(ellipse at center, transparent 35%, rgba(6,8,15,0.85) 95%)',
                    mixBlendMode: 'multiply',
                }}
            />

            {/* Subtle scanline shimmer */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background:
                        'repeating-linear-gradient(180deg, rgba(255,255,255,0.012) 0px, rgba(255,255,255,0.012) 1px, transparent 1px, transparent 4px)',
                }}
            />

            {/* Animated pulsing glow ring around the centre */}
            <div
                className="absolute pointer-events-none"
                style={{
                    left: '50%',
                    top: '50%',
                    transform: 'translate(-50%, -50%)',
                    width: 'min(620px, 60vw)',
                    height: 'min(620px, 60vw)',
                    borderRadius: '50%',
                    background:
                        'radial-gradient(circle, rgba(93,200,255,0.22) 0%, rgba(93,200,255,0.05) 40%, transparent 70%)',
                    animation: 'vesperPartyPulse 3.2s ease-in-out infinite',
                }}
            />

            {/* Top eyebrow — brand mark + party code pill */}
            <div
                className="absolute top-0 left-0 right-0 flex items-center justify-between"
                style={{
                    padding: 'clamp(20px, 2vw, 36px) clamp(28px, 3vw, 56px)',
                }}
            >
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 12,
                        letterSpacing: '0.32em',
                        textTransform: 'uppercase',
                        color: 'rgba(255,255,255,0.7)',
                    }}
                >
                    <span style={{ color: 'var(--vesper-blue-bright)', fontWeight: 700 }}>
                        ON&nbsp;NOW&nbsp;TV
                    </span>
                    <span style={{ margin: '0 10px', opacity: 0.4 }}>·</span>
                    Watch Party
                </div>
                {partyCode && (
                    <div
                        className="vesper-mono flex items-center gap-2"
                        style={{
                            padding: '8px 16px',
                            borderRadius: 999,
                            background: 'rgba(var(--vesper-blue-rgb), 0.18)',
                            border: '1px solid rgba(var(--vesper-blue-rgb), 0.5)',
                            color: 'var(--vesper-blue-bright)',
                            fontSize: 12,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            fontWeight: 700,
                            backdropFilter: 'blur(8px)',
                        }}
                    >
                        <span
                            style={{
                                display: 'inline-block',
                                width: 8,
                                height: 8,
                                borderRadius: '50%',
                                background: 'var(--vesper-blue-bright)',
                                boxShadow: '0 0 12px var(--vesper-blue-bright)',
                                animation: 'vesperPartyPulseDot 1.2s ease-in-out infinite',
                            }}
                        />
                        {partyCode} · {role === 'host' ? 'HOST' : 'GUEST'}
                    </div>
                )}
            </div>

            {/* Center stage */}
            <div
                className="absolute inset-0 flex flex-col items-center justify-center text-center"
                style={{ padding: '0 clamp(24px, 4vw, 80px)' }}
            >
                {/* Big pulsing icon */}
                <div
                    style={{
                        position: 'relative',
                        width: 'clamp(96px, 9vw, 132px)',
                        height: 'clamp(96px, 9vw, 132px)',
                        marginBottom: 'clamp(28px, 3vw, 44px)',
                        animation: 'vesperPartyBounce 2.6s ease-in-out infinite',
                    }}
                >
                    <div
                        style={{
                            position: 'absolute',
                            inset: 0,
                            borderRadius: '50%',
                            background:
                                'radial-gradient(circle, rgba(93,200,255,0.5) 0%, rgba(93,200,255,0.15) 50%, transparent 75%)',
                            filter: 'blur(18px)',
                        }}
                    />
                    <div
                        className="flex items-center justify-center"
                        style={{
                            position: 'relative',
                            width: '100%',
                            height: '100%',
                            borderRadius: '50%',
                            background:
                                'linear-gradient(135deg, rgba(93,200,255,0.28) 0%, rgba(93,200,255,0.08) 100%)',
                            border: '1.5px solid rgba(93,200,255,0.55)',
                            boxShadow:
                                '0 0 0 1px rgba(93,200,255,0.18), 0 0 60px rgba(93,200,255,0.35)',
                        }}
                    >
                        {phase === 'countdown' ? (
                            <div
                                className="vesper-display"
                                style={{
                                    fontSize: 'clamp(48px, 5vw, 72px)',
                                    fontWeight: 800,
                                    color: 'var(--vesper-blue-bright)',
                                    textShadow: '0 0 24px rgba(93,200,255,0.7)',
                                    lineHeight: 1,
                                }}
                            >
                                {Math.max(1, countdown || 1)}
                            </div>
                        ) : (
                            <Users
                                size={56}
                                strokeWidth={1.6}
                                style={{ color: 'var(--vesper-blue-bright)' }}
                            />
                        )}
                    </div>
                </div>

                {/* Eyebrow */}
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 12,
                        letterSpacing: '0.36em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue-bright)',
                        marginBottom: 18,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 8,
                    }}
                >
                    <Sparkles size={14} strokeWidth={2} />
                    Watch Party
                    <Sparkles size={14} strokeWidth={2} />
                </div>

                {/* Headline */}
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(40px, 5vw, 80px)',
                        letterSpacing: '-0.035em',
                        lineHeight: 0.98,
                        color: '#fff',
                        textShadow: '0 6px 24px rgba(0,0,0,0.6)',
                        marginBottom: 14,
                        maxWidth: '20ch',
                    }}
                >
                    {headline}
                </h1>

                {/* Subline */}
                <p
                    style={{
                        fontSize: 'clamp(14px, 1.15vw, 18px)',
                        lineHeight: 1.55,
                        color: 'rgba(255,255,255,0.78)',
                        maxWidth: '58ch',
                        marginBottom: 'clamp(28px, 3vw, 44px)',
                    }}
                >
                    {subline}
                </p>

                {/* Title we're loading */}
                {(previewMeta?.title || title) && (
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.28em',
                            textTransform: 'uppercase',
                            color: 'rgba(255,255,255,0.55)',
                            marginBottom: 10,
                        }}
                    >
                        Now loading
                    </div>
                )}
                {(previewMeta?.title || title) && (
                    <div
                        className="font-sans"
                        style={{
                            fontSize: 'clamp(18px, 1.6vw, 24px)',
                            fontWeight: 600,
                            color: '#fff',
                            marginBottom: 'clamp(28px, 3vw, 44px)',
                            maxWidth: '40ch',
                            letterSpacing: '-0.01em',
                        }}
                    >
                        {previewMeta?.title || title}
                    </div>
                )}

                {/* Member rail — only renders when we have data */}
                {Array.isArray(members) && members.length > 0 && (
                    <div
                        className="flex items-center justify-center gap-3 flex-wrap"
                        style={{ maxWidth: 720 }}
                    >
                        {members.map((m) => (
                            <MemberChip key={m.id} member={m} />
                        ))}
                    </div>
                )}
            </div>

            {/* Bottom shimmer bar */}
            <div
                className="absolute bottom-0 left-0 right-0"
                style={{ height: 3, background: 'rgba(255,255,255,0.05)' }}
            >
                <div
                    style={{
                        height: '100%',
                        width:
                            phase === 'countdown'
                                ? '100%'
                                : phase === 'waiting'
                                ? '78%'
                                : '32%',
                        background:
                            'linear-gradient(90deg, rgba(93,200,255,0) 0%, rgba(93,200,255,0.95) 50%, rgba(93,200,255,0) 100%)',
                        transition: 'width 800ms ease',
                        animation: 'vesperPartyShimmer 1.8s linear infinite',
                    }}
                />
            </div>

            {/* Local CSS keyframes (kept in the component so it's
                fully self-contained — no global CSS surgery). */}
            <style>{`
                @keyframes vesperPartyPulse {
                    0%, 100% { transform: translate(-50%, -50%) scale(1); opacity: 1; }
                    50%      { transform: translate(-50%, -50%) scale(1.08); opacity: 0.65; }
                }
                @keyframes vesperPartyPulseDot {
                    0%, 100% { opacity: 1; transform: scale(1); }
                    50%      { opacity: 0.5; transform: scale(0.86); }
                }
                @keyframes vesperPartyBounce {
                    0%, 100% { transform: translateY(0); }
                    50%      { transform: translateY(-6px); }
                }
                @keyframes vesperPartyShimmer {
                    0%   { background-position: -100% 0; }
                    100% { background-position: 200% 0; }
                }
            `}</style>
        </div>
    );
}

function MemberChip({ member }) {
    const ready = !!member?.ready;
    return (
        <div
            data-testid={`party-member-${member?.id || 'x'}`}
            className="flex items-center gap-2"
            style={{
                padding: '8px 14px 8px 8px',
                borderRadius: 999,
                background: ready
                    ? 'rgba(62, 224, 122, 0.14)'
                    : 'rgba(255, 255, 255, 0.06)',
                border: ready
                    ? '1px solid rgba(62, 224, 122, 0.45)'
                    : '1px solid rgba(255,255,255,0.12)',
                transition: 'background 400ms ease, border-color 400ms ease',
            }}
        >
            <div
                style={{
                    width: 26,
                    height: 26,
                    borderRadius: '50%',
                    background:
                        'linear-gradient(135deg, rgba(255,255,255,0.16) 0%, rgba(255,255,255,0.04) 100%)',
                    border: '1px solid rgba(255,255,255,0.18)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    fontSize: 12,
                    fontWeight: 700,
                    color: 'rgba(255,255,255,0.85)',
                    textTransform: 'uppercase',
                }}
            >
                {(member?.name || '?').trim()[0] || '?'}
            </div>
            <div
                className="font-sans"
                style={{
                    fontSize: 13,
                    color: '#fff',
                    fontWeight: 500,
                    maxWidth: 120,
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                }}
            >
                {member?.name || 'Guest'}
            </div>
            <span
                style={{
                    fontSize: 10,
                    letterSpacing: '0.18em',
                    textTransform: 'uppercase',
                    color: ready ? '#3ee07a' : 'rgba(255,255,255,0.5)',
                    fontWeight: 700,
                }}
            >
                {ready ? '· Ready' : '· Buffering'}
            </span>
        </div>
    );
}
