/**
 * <StreamPickerModal/> — v2.7.22
 *
 * Centred popup that lists available streams for a movie.  Opens
 * when the user clicks the "Choose stream" CTA on the Detail page
 * (Autoplay OFF mode).
 *
 * UX:
 *   • Cinematic blurred backdrop matching StreamUnavailableModal.
 *   • Card centred at 720 px wide × ~70 vh tall.
 *   • Scrollable list of streams (5-6 visible at a time).
 *   • First stream auto-focused on open so D-pad can immediately
 *     walk the list.
 *   • The currently-playing stream (lastStreamIdx) gets a "● CURRENT"
 *     badge + cyan border so the user can tell at a glance which
 *     stream they're testing against.
 *   • Press OK → playStream + close modal.
 *   • Press Back / Escape → close modal.
 *
 * Props:
 *   streams        – Array<StremioStream>
 *   currentIdx     – number | null (last-played index, gets CURRENT badge)
 *   onPick         – (stream, idx) => void
 *   onClose        – () => void
 *   accent         – CSS color for the badge tile (default cyan)
 *   meta           – { background?, poster? } for the blurred backdrop
 */

import React, { useEffect, useRef } from 'react';
import { qualityBadge } from '@/lib/streamMeta';
import * as img from '@/lib/img';

// Inline streamMode helper — matches Detail.jsx's definition.
const streamMode = (s) => {
    if (s?.url) return 'direct';
    if (s?.externalUrl) return 'external';
    if (s?.infoHash) return 'torrent';
    return 'unknown';
};

const toneColors = {
    sd: {
        bg: 'rgba(255,255,255,0.04)',
        fg: 'var(--vesper-text-2)',
        border: 'rgba(255,255,255,0.08)',
    },
    hd: {
        bg: 'rgba(93,200,255,0.12)',
        fg: 'var(--vesper-blue-bright)',
        border: 'rgba(93,200,255,0.32)',
    },
    fhd: {
        bg: 'rgba(93,200,255,0.18)',
        fg: '#7FDCFF',
        border: 'rgba(93,200,255,0.45)',
    },
    uhd: {
        bg: 'rgba(255,210,138,0.16)',
        fg: '#ffd28a',
        border: 'rgba(255,210,138,0.4)',
    },
};

export default function StreamPickerModal({
    streams,
    currentIdx,
    onPick,
    onClose,
    accent = 'var(--vesper-blue-bright)',
    meta,
}) {
    const listRef = useRef(null);

    // Auto-focus the first stream as soon as the modal mounts.  We
    // use a microtask + rAF so the DOM nodes are mounted before we
    // try to set focus.
    useEffect(() => {
        const tick = () => {
            // Sweep any stale focus elsewhere on the page.
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (!el.closest('[data-testid="stream-picker-modal"]')) {
                        el.removeAttribute('data-focused');
                    }
                });
            const first = listRef.current?.querySelector(
                '[data-testid^="modal-stream-"]'
            );
            if (first) {
                first.setAttribute('data-focused', 'true');
                try { first.focus({ preventScroll: false }); }
                catch { /* ignore */ }
            }
        };
        // Defer one rAF so children mount first.
        const id = requestAnimationFrame(tick);
        return () => cancelAnimationFrame(id);
    }, [streams]);

    const handleKey = (e) => {
        if (e.key === 'Escape' || e.key === 'Backspace') {
            e.preventDefault();
            onClose?.();
        }
    };

    /* Cinematic backdrop — same recipe as StreamUnavailableModal so
     * the two modals feel like one design system. */
    const bgUrl =
        (meta?.background && img.backdrop(meta.background)) ||
        (meta?.poster && img.poster(meta.poster)) ||
        '';

    return (
        <div
            data-testid="stream-picker-modal"
            onKeyDown={handleKey}
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 9000,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
            }}
        >
            {bgUrl && (
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        backgroundImage: `url(${bgUrl})`,
                        backgroundSize: 'cover',
                        backgroundPosition: 'center 30%',
                        filter: 'blur(28px) saturate(110%)',
                        transform: 'scale(1.1)',
                        opacity: 0.45,
                    }}
                />
            )}
            <div
                aria-hidden="true"
                onClick={onClose}
                style={{
                    position: 'absolute',
                    inset: 0,
                    background:
                        'radial-gradient(ellipse 1200px 700px at 50% 50%,' +
                        ' rgba(93,200,255,0.10) 0%, transparent 60%),' +
                        ' rgba(6,8,15,0.85)',
                    backdropFilter: 'blur(12px)',
                    WebkitBackdropFilter: 'blur(12px)',
                }}
            />

            {/* Main card */}
            <div
                style={{
                    position: 'relative',
                    width: 'min(760px, 92vw)',
                    maxHeight: '78vh',
                    display: 'flex',
                    flexDirection: 'column',
                    padding: '32px 32px 24px',
                    borderRadius: 24,
                    background:
                        'linear-gradient(160deg, rgba(15,22,38,0.92) 0%, rgba(6,8,15,0.94) 100%)',
                    border: '1px solid rgba(93,200,255,0.28)',
                    boxShadow:
                        '0 40px 100px rgba(0,0,0,0.75),' +
                        ' inset 0 1px 0 rgba(255,255,255,0.12),' +
                        ' 0 0 80px rgba(93,200,255,0.18)',
                    color: '#E6EAF2',
                    animation:
                        'vesper-stream-unavail-in 280ms cubic-bezier(.16,1,.3,1) both',
                }}
            >
                <div className="flex items-center justify-between mb-5">
                    <div>
                        <div
                            className="vesper-eyebrow"
                            style={{ color: 'var(--vesper-blue-bright)' }}
                        >
                            Available streams
                        </div>
                        <div
                            className="vesper-display"
                            style={{
                                fontSize: 22,
                                letterSpacing: '-0.02em',
                                marginTop: 4,
                            }}
                        >
                            Choose a stream to test
                        </div>
                    </div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            color: 'var(--vesper-text-3)',
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                        }}
                    >
                        {streams.length} found
                    </div>
                </div>

                {/* Scrollable list — body of modal */}
                <div
                    ref={listRef}
                    data-testid="stream-picker-modal-list"
                    style={{
                        overflowY: 'auto',
                        overflowX: 'hidden',
                        flex: 1,
                        paddingRight: 6,
                        marginRight: -6,
                    }}
                >
                    <ul className="space-y-3">
                        {streams.map((s, i) => {
                            const mode = streamMode(s);
                            const badge = qualityBadge(s);
                            const rawLabel = s.title || s.name || '(untitled)';
                            const titleLine = rawLabel.split('\n')[0];
                            const isCurrent = i === currentIdx;
                            return (
                                <li key={i}>
                                    <button
                                        data-testid={`modal-stream-${i}`}
                                        data-focusable="true"
                                        data-focus-style="pill"
                                        tabIndex={0}
                                        onClick={() => onPick?.(s, i)}
                                        className="w-full text-left flex items-start gap-4"
                                        style={{
                                            padding: '14px 18px',
                                            borderRadius: 12,
                                            background: isCurrent
                                                ? 'rgba(93,200,255,0.10)'
                                                : 'rgba(13,18,28,0.78)',
                                            border: isCurrent
                                                ? '1px solid var(--vesper-blue-bright)'
                                                : '1px solid rgba(255,255,255,0.06)',
                                            boxShadow:
                                                '0 6px 18px rgba(0,0,0,0.28)',
                                        }}
                                    >
                                        <span
                                            className="shrink-0 flex flex-col items-center justify-center"
                                            style={{
                                                width: badge ? 56 : 40,
                                                minHeight: 48,
                                                borderRadius: 10,
                                                background: badge
                                                    ? toneColors[badge.tone].bg
                                                    : 'rgba(93,200,255,0.16)',
                                                color: badge
                                                    ? toneColors[badge.tone].fg
                                                    : accent,
                                                border: badge
                                                    ? `1px solid ${toneColors[badge.tone].border}`
                                                    : 'none',
                                                padding: '6px 4px',
                                            }}
                                        >
                                            {badge ? (
                                                <span
                                                    style={{
                                                        fontSize:
                                                            badge.label.length <= 3 ? 16 : 12,
                                                        fontWeight: 800,
                                                        letterSpacing: '-0.02em',
                                                    }}
                                                >
                                                    {badge.label}
                                                </span>
                                            ) : (
                                                <span style={{ fontSize: 14, fontWeight: 700 }}>
                                                    {mode === 'direct' ? '◉' : '⛓'}
                                                </span>
                                            )}
                                        </span>
                                        <div className="min-w-0 flex-1">
                                            <div className="flex items-center gap-2 flex-wrap">
                                                <div
                                                    style={{
                                                        fontSize: 14,
                                                        fontWeight: 500,
                                                        lineHeight: 1.35,
                                                        color: 'var(--vesper-text)',
                                                        wordBreak: 'break-word',
                                                        display: '-webkit-box',
                                                        WebkitBoxOrient: 'vertical',
                                                        WebkitLineClamp: 2,
                                                        overflow: 'hidden',
                                                        flex: '1 1 auto',
                                                        minWidth: 0,
                                                    }}
                                                >
                                                    {titleLine}
                                                </div>
                                                {isCurrent && (
                                                    <span
                                                        data-testid={`modal-stream-${i}-current`}
                                                        className="vesper-mono shrink-0"
                                                        style={{
                                                            fontSize: 9,
                                                            fontWeight: 800,
                                                            letterSpacing: '0.14em',
                                                            padding: '3px 8px',
                                                            borderRadius: 4,
                                                            background: 'var(--vesper-blue-bright)',
                                                            color: 'var(--vesper-bg-0)',
                                                            whiteSpace: 'nowrap',
                                                        }}
                                                    >
                                                        ● CURRENT
                                                    </span>
                                                )}
                                            </div>
                                            <div
                                                className="vesper-mono"
                                                style={{
                                                    fontSize: 10,
                                                    color: 'var(--vesper-text-3)',
                                                    letterSpacing: '0.08em',
                                                    marginTop: 4,
                                                    textTransform: 'uppercase',
                                                }}
                                            >
                                                {mode === 'direct'
                                                    ? 'direct stream'
                                                    : mode === 'torrent'
                                                    ? 'magnet / torrent'
                                                    : mode}
                                            </div>
                                        </div>
                                    </button>
                                </li>
                            );
                        })}
                    </ul>
                </div>

                {/* Footer hint */}
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 16,
                        fontSize: 11,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.12em',
                        textTransform: 'uppercase',
                        textAlign: 'center',
                    }}
                >
                    OK to play &nbsp;·&nbsp; BACK to close
                </div>
            </div>
        </div>
    );
}
