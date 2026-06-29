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
import {
    qualityBadge,
    qualityTags,
    sizeLabel,
    toneColors,
    nardResolutionIcon,
    nardChips,
    nardMetaLine,
} from '@/lib/streamMeta';
import * as img from '@/lib/img';

// Inline streamMode helper — matches Detail.jsx's definition.
const streamMode = (s) => {
    if (s?.url) return 'direct';
    if (s?.externalUrl) return 'external';
    if (s?.infoHash) return 'torrent';
    return 'unknown';
};

// Safe tone-color lookup — never throws if `qualityBadge` returns a
// tone key we don't know about (defensive against future additions
// to streamMeta.js QUALITY_PATTERNS).
const NEUTRAL_TONE = {
    bg: 'rgba(93,200,255,0.16)',
    fg: 'var(--vesper-blue-bright)',
    border: 'rgba(93,200,255,0.35)',
};
const safeTone = (tone) => toneColors?.[tone] || NEUTRAL_TONE;

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
                <div className="flex items-start justify-between mb-5 gap-4">
                    <div className="flex items-start gap-4 min-w-0 flex-1">
                        {/* v2.10.74 — Poster thumb so the user can see
                            what they're picking a link FOR.  Falls
                            back to a placeholder when the meta layer
                            had no poster (e.g. cinemeta miss). */}
                        {meta?.poster ? (
                            <img
                                src={meta.poster}
                                alt=""
                                style={{
                                    width: 64,
                                    height: 96,
                                    objectFit: 'cover',
                                    borderRadius: 8,
                                    flexShrink: 0,
                                    border: '1px solid rgba(255,255,255,0.10)',
                                    boxShadow: '0 6px 18px rgba(0,0,0,0.40)',
                                }}
                            />
                        ) : null}
                        <div className="min-w-0 flex-1">
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
                                    overflow: 'hidden',
                                    textOverflow: 'ellipsis',
                                    whiteSpace: 'nowrap',
                                }}
                            >
                                {/* Cinemeta returns `name`/`description`;
                                    older callers may pass `title`/`synopsis`.
                                    Accept either shape so we don't have to
                                    rename fields all over Detail.jsx. */}
                                {meta?.title || meta?.name || 'Choose a stream to test'}
                            </div>
                            {/* v2.10.74 — Movie synopsis lives under
                                the title so the operator knows the
                                plot before committing to a link.
                                Clamped to 3 lines to keep the modal
                                compact. */}
                            {(meta?.synopsis || meta?.description) && (
                                <div
                                    data-testid="stream-picker-synopsis"
                                    style={{
                                        marginTop: 8,
                                        fontSize: 12,
                                        lineHeight: 1.45,
                                        color: 'var(--vesper-text-2)',
                                        display: '-webkit-box',
                                        WebkitBoxOrient: 'vertical',
                                        WebkitLineClamp: 3,
                                        overflow: 'hidden',
                                    }}
                                >
                                    {meta?.synopsis || meta?.description}
                                </div>
                            )}
                        </div>
                    </div>
                    <div
                        className="vesper-mono shrink-0"
                        style={{
                            fontSize: 11,
                            color: 'var(--vesper-text-3)',
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            paddingTop: 6,
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
                            // v2.10.78 — NardBadges PNG icon set.
                            // Left "badge tile" is now the resolution
                            // PNG (4K / FHD / HD).  The secondary chip
                            // row uses PNG icons for release type, HDR
                            // family, audio codec, channels, video
                            // codec — matching the look the user
                            // pointed to in vowl313/NardBadges.
                            const resIcon = nardResolutionIcon(s);
                            const chips = nardChips(s).slice(0, 8);
                            // Backwards-compat: keep text-quality
                            // badge for streams that don't carry a
                            // resolution token (rare).
                            const fallbackBadge = !resIcon ? qualityBadge(s) : null;
                            const metaLine = nardMetaLine(s);
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
                                                width: 64,
                                                minHeight: 48,
                                                borderRadius: 10,
                                                background: resIcon || fallbackBadge
                                                    ? 'rgba(255,255,255,0.04)'
                                                    : 'rgba(93,200,255,0.16)',
                                                color: fallbackBadge
                                                    ? safeTone(fallbackBadge.tone).fg
                                                    : accent,
                                                border: fallbackBadge
                                                    ? `1px solid ${safeTone(fallbackBadge.tone).border}`
                                                    : '1px solid rgba(255,255,255,0.08)',
                                                padding: '6px 4px',
                                            }}
                                        >
                                            {resIcon ? (
                                                <img
                                                    data-testid={`modal-stream-${i}-res-icon`}
                                                    src={resIcon.url}
                                                    alt={resIcon.label}
                                                    style={{
                                                        height: 26,
                                                        width: 'auto',
                                                        objectFit: 'contain',
                                                        imageRendering: '-webkit-optimize-contrast',
                                                    }}
                                                    loading="lazy"
                                                />
                                            ) : fallbackBadge ? (
                                                <span
                                                    style={{
                                                        fontSize:
                                                            fallbackBadge.label.length <= 3 ? 16 : 12,
                                                        fontWeight: 800,
                                                        letterSpacing: '-0.02em',
                                                    }}
                                                >
                                                    {fallbackBadge.label}
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
                                                className="flex items-center gap-2 flex-wrap"
                                                style={{
                                                    marginTop: 8,
                                                }}
                                            >
                                                {/* v2.10.78 — NardBadges PNG chip row.
                                                    Release type (BluRay / WebDL / Remux),
                                                    HDR family (DV / HDR10+ / HDR10 / HDR),
                                                    audio codec (Atmos / DTS / DD+ / …),
                                                    channel count, video codec, 3D —
                                                    rendered as 22 px PNG icons hosted on
                                                    github.com/vowl313/NardBadges so every
                                                    addon's streams (Torrentio, EasyNews++,
                                                    Plexio, MediaFusion…) share the same
                                                    visual vocabulary. */}
                                                {chips.map((c) => (
                                                    <img
                                                        key={`chip-${c.group}-${c.label}`}
                                                        data-testid={`modal-stream-${i}-chip-${c.group}`}
                                                        src={c.url}
                                                        alt={c.label}
                                                        title={c.label}
                                                        style={{
                                                            height: 22,
                                                            width: 'auto',
                                                            objectFit: 'contain',
                                                            borderRadius: 4,
                                                            imageRendering: '-webkit-optimize-contrast',
                                                        }}
                                                        loading="lazy"
                                                    />
                                                ))}
                                            </div>
                                            <div
                                                className="vesper-mono flex items-center gap-2.5 flex-wrap"
                                                style={{
                                                    fontSize: 11,
                                                    color: 'var(--vesper-text-3)',
                                                    letterSpacing: '0.04em',
                                                    marginTop: 8,
                                                }}
                                            >
                                                {/* v2.10.78 — NardBadges-style meta line:
                                                    🔌 ADDON  ·  ⚡ Cached  ·  💾 Size  ·  🌱 Seeders.
                                                    Emoji-led so each token reads at a
                                                    glance even at low contrast. */}
                                                {metaLine.map((m, mi) => (
                                                    <span
                                                        key={`meta-${mi}-${m.text}`}
                                                        data-testid={`modal-stream-${i}-meta-${m.icon}`}
                                                        style={{
                                                            display: 'inline-flex',
                                                            alignItems: 'center',
                                                            gap: 4,
                                                            fontWeight: m.icon === '⚡' ? 700 : 500,
                                                            color: m.icon === '⚡'
                                                                ? '#7AEB8A'
                                                                : 'var(--vesper-text-2)',
                                                        }}
                                                    >
                                                        <span aria-hidden="true">{m.icon}</span>
                                                        <span>{m.text}</span>
                                                    </span>
                                                ))}
                                                {s._is_english && (
                                                    <span
                                                        data-testid={`modal-stream-${i}-eng`}
                                                        style={{
                                                            display: 'inline-flex',
                                                            alignItems: 'center',
                                                            gap: 4,
                                                            color: 'var(--vesper-text-2)',
                                                            fontWeight: 500,
                                                        }}
                                                    >
                                                        <span aria-hidden="true">🇬🇧</span>
                                                        <span>ENG</span>
                                                    </span>
                                                )}
                                                <span style={{ opacity: 0.55 }}>
                                                    {mode === 'direct'
                                                        ? '◉ direct'
                                                        : mode === 'torrent'
                                                        ? 'magnet / torrent'
                                                        : mode}
                                                </span>
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
