/**
 * <PartyJoiningScreen/> — the ONE thing the user sees on their
 * device after they / the host clicks "Start Party" and right up
 * until the player launches.  Replaces the entire Detail page
 * (NOT an overlay) so there is literally no picker behind it, no
 * "Play 1080p" button to confuse the user, no race conditions.
 *
 * Cancel / Retry are the only escape hatches and they are both
 * explicit, deliberate touches — nothing else on the screen reacts
 * to taps.
 *
 * Visual: full-bleed blurred poster as backdrop, neon cyan accent,
 * monospace progress tags ("PARTY · LOADING STREAM").  Mobile +
 * TV-safe sizing.
 */
import React from 'react';
import { Loader2, X, RefreshCw } from 'lucide-react';

export default function PartyJoiningScreen({
    title,
    poster,
    backdrop,
    loading,
    noStreams,
    onCancel,
    onRetry,
}) {
    const stage = noStreams
        ? "Couldn't find a stream"
        : loading
            ? 'Loading stream from your sources…'
            : 'Almost there — handing off to the player…';

    const tagText = noStreams ? 'PARTY · NO STREAM' : 'PARTY · LOADING';
    const tagColor = noStreams ? '#FCA5A5' : '#5DC8FF';

    return (
        <div
            data-testid="party-joining-screen"
            style={{
                position: 'fixed', inset: 0, zIndex: 50,
                background: '#06080F',
                color: '#E6EAF2',
                overflow: 'hidden',
            }}
        >
            {/* Blurred backdrop poster */}
            {(backdrop || poster) && (
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', inset: 0,
                        backgroundImage: `url(${backdrop || poster})`,
                        backgroundSize: 'cover',
                        backgroundPosition: 'center',
                        filter: 'blur(24px) brightness(0.45) saturate(1.05)',
                        transform: 'scale(1.15)',
                    }}
                />
            )}
            {/* Dark vignette so text is always readable */}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute', inset: 0,
                    background:
                        'radial-gradient(ellipse 80% 60% at 50% 50%, rgba(6,8,15,0.55) 0%, rgba(6,8,15,0.88) 60%, rgba(6,8,15,0.95) 100%)',
                }}
            />
            {/* Cyan glow accent */}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    top: '-15%', left: '50%',
                    transform: 'translateX(-50%)',
                    width: 720, height: 720, borderRadius: '50%',
                    background:
                        'radial-gradient(circle, rgba(93,200,255,0.22) 0%, rgba(93,200,255,0.06) 35%, transparent 65%)',
                    pointerEvents: 'none',
                }}
            />

            <div
                style={{
                    position: 'relative',
                    height: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    padding: '32px 24px',
                    textAlign: 'center',
                    gap: 24,
                }}
            >
                {/* Poster card */}
                {poster && (
                    <div
                        style={{
                            width: 'clamp(140px, 28vw, 220px)',
                            aspectRatio: '2 / 3',
                            borderRadius: 18,
                            backgroundImage: `url(${poster})`,
                            backgroundSize: 'cover',
                            backgroundPosition: 'center',
                            border: '1px solid rgba(255,255,255,0.12)',
                            boxShadow:
                                '0 24px 60px rgba(0,0,0,0.55), 0 0 0 1px rgba(93,200,255,0.08), 0 0 80px rgba(93,200,255,0.22)',
                        }}
                    />
                )}

                {/* Eyebrow tag */}
                <div
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        fontWeight: 700,
                        letterSpacing: '0.36em',
                        color: tagColor,
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 10,
                    }}
                >
                    {!noStreams && (
                        <Loader2
                            className="vesper-spin"
                            size={12}
                            style={{ color: tagColor }}
                        />
                    )}
                    {tagText}
                </div>

                {/* Title */}
                <h1
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(24px, 4vw, 38px)',
                        lineHeight: 1.1,
                        letterSpacing: '-0.01em',
                        margin: 0,
                        color: '#fff',
                        maxWidth: 720,
                    }}
                >
                    {title || 'Your watch party is starting'}
                </h1>

                {/* Status copy */}
                <div
                    style={{
                        fontSize: 14,
                        color: '#9DA5B5',
                        maxWidth: 560,
                        lineHeight: 1.55,
                    }}
                >
                    {stage}
                </div>

                {/* Retry / cancel actions */}
                <div
                    style={{
                        marginTop: 18,
                        display: 'flex',
                        gap: 12,
                        flexWrap: 'wrap',
                        justifyContent: 'center',
                    }}
                >
                    {noStreams && (
                        <button
                            data-testid="party-joining-retry"
                            data-focusable="true"
                            tabIndex={0}
                            onClick={onRetry}
                            style={{
                                height: 48, padding: '0 22px',
                                borderRadius: 999,
                                background: '#5DC8FF',
                                color: '#06080F',
                                border: 'none',
                                fontSize: 14, fontWeight: 800,
                                letterSpacing: '0.04em',
                                textTransform: 'uppercase',
                                display: 'inline-flex',
                                alignItems: 'center', gap: 8,
                                cursor: 'pointer',
                            }}
                        >
                            <RefreshCw size={16} />
                            Retry
                        </button>
                    )}
                    <button
                        data-testid="party-joining-cancel"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={onCancel}
                        style={{
                            height: 48, padding: '0 22px',
                            borderRadius: 999,
                            background: 'transparent',
                            border: '1px solid rgba(255,255,255,0.22)',
                            color: '#C7CFDB',
                            fontSize: 13, fontWeight: 600,
                            letterSpacing: '0.04em',
                            textTransform: 'uppercase',
                            display: 'inline-flex',
                            alignItems: 'center', gap: 8,
                            cursor: 'pointer',
                        }}
                    >
                        <X size={16} />
                        Leave party
                    </button>
                </div>
            </div>
        </div>
    );
}
