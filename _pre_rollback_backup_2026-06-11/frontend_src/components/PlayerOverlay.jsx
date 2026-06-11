/**
 * PlayerOverlay — the cinematic on-screen controls + info panel
 * shown over the movie player.  Replaces the browser-default
 * `<video controls>` chrome with a premium overlay that matches
 * the rest of the app's aesthetic.
 *
 * Two layered surfaces:
 *
 *  1. INFO CARD (only when paused or after first tap, full
 *     metadata: TITLE, meta chips, synopsis).  Visually echoes
 *     the cinematic loading screen so the player feels continuous
 *     with the pre-roll.
 *
 *  2. CONTROL BAR (bottom — always part of the auto-hide chrome).
 *     Custom premium scrubber, time labels, skip-back-10s, big
 *     glowing play/pause, skip-forward-30s.
 *
 * Auto-hide rules:
 *  • Both layers visible while `chromeVisible` is true (chrome
 *    state is driven by user activity at the page level — any
 *    mousemove / keydown / click / touch reveals it, then 3 s
 *    of idle hides it again).
 *  • Info card stays visible even past idle if `paused === true`
 *    so the user always sees "what they're watching" when the
 *    movie is paused.
 *  • Hidden entirely while the party takeover is up (its own
 *    full-screen experience takes the focus).
 *
 * Controls API for the parent Player.jsx:
 *  • togglePlay()   — pause/resume
 *  • seek(delta)    — current += delta seconds (clamped)
 *  • seekTo(sec)    — jump to absolute second
 */
import React from 'react';
import {
    Play,
    Pause,
    RotateCcw,
    RotateCw,
} from 'lucide-react';

function fmtTime(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) return '00:00';
    const s = Math.floor(seconds);
    const h = Math.floor(s / 3600);
    const m = Math.floor((s % 3600) / 60);
    const sec = s % 60;
    const mm = String(m).padStart(2, '0');
    const ss = String(sec).padStart(2, '0');
    return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export default function PlayerOverlay({
    visible,            // chrome auto-hide controller
    paused,             // whether the <video> is currently paused
    currentTime,        // seconds
    duration,           // seconds
    buffered,           // seconds buffered ahead of currentTime
    title,
    previewMeta,        // { title, year, genres, synopsis, ... }
    onPlayPause,
    onSeek,             // (deltaSeconds) => void
    onSeekTo,           // (absoluteSeconds) => void
}) {
    /* Info card shows whenever chrome is visible OR the movie is
     * paused (so the user always sees what's on while paused). */
    const showInfo = !!(visible || paused);
    /* The control bar follows chrome only — when playing, it
     * auto-hides with the rest of the chrome.  When paused it
     * also stays visible (so users can hit play with a click). */
    const showControls = !!(visible || paused);

    /* Derived progress 0..1 */
    const safeDur   = Number.isFinite(duration) && duration > 0 ? duration : 0;
    const safeCur   = Math.min(safeDur, Math.max(0, currentTime || 0));
    const safeBuf   = Math.min(safeDur, Math.max(safeCur, buffered || 0));
    const progress  = safeDur > 0 ? safeCur / safeDur : 0;
    const bufFrac   = safeDur > 0 ? safeBuf / safeDur : 0;
    const remaining = Math.max(0, safeDur - safeCur);

    const handleScrubClick = (e) => {
        if (!onSeekTo || !safeDur) return;
        const rect = e.currentTarget.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const frac = Math.max(0, Math.min(1, x / rect.width));
        onSeekTo(frac * safeDur);
    };

    const titleText = previewMeta?.title || title || '';
    const synopsis  = previewMeta?.synopsis || '';
    const year      = previewMeta?.year;
    const runtimeMin = Math.round((safeDur || 0) / 60);
    const genres    = Array.isArray(previewMeta?.genres)
        ? previewMeta.genres.slice(0, 3).join(' · ')
        : '';

    return (
        <>
            {/* ────────────────────────────────────────────────
                INFO CARD (mid-lower-third)
                ──────────────────────────────────────────────── */}
            <div
                data-testid="player-overlay-info"
                className="absolute inset-0 pointer-events-none"
                style={{
                    opacity: showInfo ? 1 : 0,
                    transition: 'opacity 320ms ease',
                    zIndex: 9,
                }}
            >
                {/* Soft cinematic gradient that gently darkens the
                    bottom half of the picture so the text reads. */}
                <div
                    style={{
                        position: 'absolute', inset: 0,
                        background:
                            'linear-gradient(180deg, rgba(0,0,0,0) 30%, rgba(0,0,0,0.55) 80%, rgba(0,0,0,0.78) 100%)',
                        pointerEvents: 'none',
                    }}
                />
                {/* Content stack — anchored bottom-left, lifted clear
                    of the control bar. */}
                <div
                    className="absolute"
                    style={{
                        left:   'clamp(28px, 4vw, 80px)',
                        right:  'clamp(28px, 4vw, 80px)',
                        bottom: 'clamp(108px, 12vh, 168px)',
                        color:  '#fff',
                        textShadow: '0 4px 24px rgba(0,0,0,0.7)',
                        maxWidth: 920,
                    }}
                >
                    {/* Eyebrow */}
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11, letterSpacing: '0.32em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-blue-bright)',
                            marginBottom: 14,
                            display: 'flex', alignItems: 'center', gap: 10,
                            fontWeight: 700,
                        }}
                    >
                        <span
                            style={{
                                display: 'inline-block',
                                width: 8, height: 8, borderRadius: '50%',
                                background: paused ? '#f7c948' : '#3ee07a',
                                boxShadow: paused
                                    ? '0 0 10px #f7c948'
                                    : '0 0 10px #3ee07a',
                            }}
                        />
                        {paused ? 'Paused' : 'Now Playing'}
                        <span style={{ margin: '0 4px', opacity: 0.4 }}>·</span>
                        ON&nbsp;NOW&nbsp;TV
                    </div>

                    {/* Title (acts as the logo until we wire TMDB
                        logo images — typography alone reads beautifully). */}
                    <h1
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(36px, 4.4vw, 68px)',
                            lineHeight: 0.98,
                            letterSpacing: '-0.035em',
                            marginBottom: 14,
                            color: '#fff',
                            maxWidth: '18ch',
                        }}
                    >
                        {titleText}
                    </h1>

                    {/* Meta chips row */}
                    <div
                        className="flex items-center flex-wrap"
                        style={{ gap: 10, marginBottom: 16 }}
                    >
                        {year && <MetaChip>{year}</MetaChip>}
                        {runtimeMin > 0 && (
                            <MetaChip>{Math.floor(runtimeMin / 60) > 0
                                ? `${Math.floor(runtimeMin / 60)}h ${runtimeMin % 60}m`
                                : `${runtimeMin}m`}
                            </MetaChip>
                        )}
                        {previewMeta?.rating && (
                            <MetaChip glow>★ {previewMeta.rating}</MetaChip>
                        )}
                        {genres && <MetaChip>{genres}</MetaChip>}
                    </div>

                    {/* Synopsis — only shows when paused (during play
                        the meta row is enough to identify what's on
                        without dragging the user out of the movie). */}
                    {paused && synopsis && (
                        <p
                            style={{
                                fontSize: 'clamp(14px, 1.05vw, 17px)',
                                lineHeight: 1.55,
                                color: 'rgba(255,255,255,0.85)',
                                maxWidth: '64ch',
                                display: '-webkit-box',
                                WebkitLineClamp: 3,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {synopsis}
                        </p>
                    )}
                </div>
            </div>

            {/* ────────────────────────────────────────────────
                BOTTOM CONTROL BAR
                ──────────────────────────────────────────────── */}
            <div
                data-testid="player-overlay-controls"
                className="absolute left-0 right-0"
                style={{
                    bottom: 0,
                    padding: 'clamp(20px, 2.4vw, 36px) clamp(28px, 4vw, 80px)',
                    opacity: showControls ? 1 : 0,
                    pointerEvents: showControls ? 'auto' : 'none',
                    transition: 'opacity 280ms ease',
                    zIndex: 11,
                    background:
                        'linear-gradient(0deg, rgba(0,0,0,0.78) 0%, rgba(0,0,0,0) 100%)',
                }}
            >
                {/* Scrubber row */}
                <div
                    style={{
                        display: 'flex', alignItems: 'center',
                        gap: 16, marginBottom: 20,
                    }}
                >
                    {/* Current time */}
                    <span
                        className="vesper-mono"
                        style={{
                            fontSize: 13, letterSpacing: '0.12em',
                            color: '#fff', fontWeight: 700,
                            minWidth: 60, textAlign: 'left',
                            fontVariantNumeric: 'tabular-nums',
                        }}
                    >
                        {fmtTime(safeCur)}
                    </span>
                    {/* The scrubber itself */}
                    <button
                        data-testid="player-overlay-scrubber"
                        onClick={handleScrubClick}
                        aria-label="Seek"
                        style={{
                            position: 'relative',
                            flex: 1,
                            height: 32,
                            background: 'transparent',
                            border: 'none',
                            cursor: safeDur > 0 ? 'pointer' : 'default',
                            padding: 0,
                            display: 'flex',
                            alignItems: 'center',
                        }}
                    >
                        {/* Track */}
                        <div
                            style={{
                                position: 'relative',
                                width: '100%',
                                height: 4,
                                background: 'rgba(255,255,255,0.18)',
                                borderRadius: 999,
                                overflow: 'visible',
                            }}
                        >
                            {/* Buffered ahead of current */}
                            <div
                                style={{
                                    position: 'absolute', left: 0, top: 0, bottom: 0,
                                    width: `${bufFrac * 100}%`,
                                    background: 'rgba(255,255,255,0.32)',
                                    borderRadius: 999,
                                }}
                            />
                            {/* Played */}
                            <div
                                style={{
                                    position: 'absolute', left: 0, top: 0, bottom: 0,
                                    width: `${progress * 100}%`,
                                    background:
                                        'linear-gradient(90deg, var(--vesper-blue) 0%, var(--vesper-blue-bright) 100%)',
                                    borderRadius: 999,
                                    boxShadow: '0 0 12px rgba(93,200,255,0.55)',
                                }}
                            />
                            {/* Thumb */}
                            <div
                                style={{
                                    position: 'absolute',
                                    left: `calc(${progress * 100}% - 9px)`,
                                    top: -7, width: 18, height: 18,
                                    borderRadius: '50%',
                                    background: '#fff',
                                    boxShadow: '0 0 0 4px rgba(93,200,255,0.45), 0 4px 12px rgba(0,0,0,0.6)',
                                    transition: 'left 100ms linear',
                                }}
                            />
                        </div>
                    </button>
                    {/* Remaining time */}
                    <span
                        className="vesper-mono"
                        style={{
                            fontSize: 13, letterSpacing: '0.12em',
                            color: 'rgba(255,255,255,0.8)', fontWeight: 700,
                            minWidth: 70, textAlign: 'right',
                            fontVariantNumeric: 'tabular-nums',
                        }}
                    >
                        -{fmtTime(remaining)}
                    </span>
                </div>

                {/* Buttons row */}
                <div
                    style={{
                        display: 'flex', alignItems: 'center',
                        justifyContent: 'center', gap: 28,
                    }}
                >
                    <ControlButton
                        testId="player-overlay-rewind"
                        ariaLabel="Skip back 10 seconds"
                        onClick={() => onSeek?.(-10)}
                    >
                        <RotateCcw size={22} strokeWidth={2.2} />
                        <ControlSubLabel>10</ControlSubLabel>
                    </ControlButton>

                    <ControlButton
                        testId="player-overlay-playpause"
                        ariaLabel={paused ? 'Play' : 'Pause'}
                        onClick={onPlayPause}
                        primary
                    >
                        {paused
                            ? <Play  size={32} strokeWidth={2.4} fill="#06080F" />
                            : <Pause size={32} strokeWidth={2.4} fill="#06080F" />}
                    </ControlButton>

                    <ControlButton
                        testId="player-overlay-forward"
                        ariaLabel="Skip forward 30 seconds"
                        onClick={() => onSeek?.(30)}
                    >
                        <RotateCw size={22} strokeWidth={2.2} />
                        <ControlSubLabel>30</ControlSubLabel>
                    </ControlButton>
                </div>
            </div>
        </>
    );
}

/* -- small primitives ----------------------------------------- */

function MetaChip({ children, glow }) {
    return (
        <span
            className="vesper-mono"
            style={{
                padding: '6px 12px',
                borderRadius: 999,
                fontSize: 11,
                letterSpacing: '0.22em',
                textTransform: 'uppercase',
                fontWeight: 700,
                color: glow ? 'var(--vesper-blue-bright)' : 'rgba(255,255,255,0.92)',
                background: glow
                    ? 'rgba(var(--vesper-blue-rgb), 0.22)'
                    : 'rgba(255,255,255,0.10)',
                border: glow
                    ? '1px solid rgba(var(--vesper-blue-rgb), 0.55)'
                    : '1px solid rgba(255,255,255,0.14)',
                backdropFilter: 'blur(8px)',
            }}
        >
            {children}
        </span>
    );
}

function ControlButton({ testId, ariaLabel, onClick, primary, children }) {
    const size = primary ? 76 : 56;
    return (
        <button
            data-testid={testId}
            data-focusable="true"
            data-focus-style="quiet"
            tabIndex={0}
            onClick={onClick}
            aria-label={ariaLabel}
            className="flex items-center justify-center"
            style={{
                position: 'relative',
                width: size, height: size,
                borderRadius: '50%',
                background: primary
                    ? 'linear-gradient(135deg, var(--vesper-blue) 0%, #4FB8F0 100%)'
                    : 'rgba(17, 24, 39, 0.78)',
                color: primary ? '#06080F' : '#fff',
                border: primary
                    ? '1px solid rgba(168,228,255,0.55)'
                    : '1px solid rgba(255,255,255,0.12)',
                cursor: 'pointer',
                boxShadow: primary
                    ? '0 14px 40px rgba(93,200,255,0.55), 0 0 0 1px rgba(255,255,255,0.08) inset'
                    : '0 6px 18px rgba(0,0,0,0.45)',
                backdropFilter: primary ? undefined : 'blur(10px)',
                transition: 'transform 200ms ease, box-shadow 200ms ease',
            }}
        >
            {children}
        </button>
    );
}

function ControlSubLabel({ children }) {
    return (
        <span
            className="vesper-mono"
            style={{
                position: 'absolute',
                bottom: 11,
                left: '50%',
                transform: 'translateX(-50%)',
                fontSize: 8,
                letterSpacing: '0.18em',
                fontWeight: 700,
                color: 'rgba(255,255,255,0.65)',
                pointerEvents: 'none',
            }}
        >
            {children}
        </span>
    );
}
