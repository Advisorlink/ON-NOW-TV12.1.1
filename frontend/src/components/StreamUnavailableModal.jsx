import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { BellRing, BellOff, ArrowLeft, Sparkles } from 'lucide-react';
import { addToNotifyList, removeFromNotifyList, isInNotifyList } from '@/lib/library';
import * as img from '@/lib/img';

/**
 * <StreamUnavailableModal/> — shown when the user taps Play on
 * a movie/show that has NO streams available across any installed
 * addon.  Replaces the old "Playback error" snackbar with a
 * cinematic, polished CTA flow.
 *
 * Props:
 *   id      – Cinemeta id (e.g. "tt15239678") — used as the
 *             notify-list key
 *   meta    – the same metadata blob Detail.jsx already has
 *             ({ name, poster, background, releaseInfo, ... })
 *   onClose – fires when user dismisses
 *
 * The user can:
 *   • "Notify me when it's available" → adds to notifyList
 *   • "Already added" toggle → removes from notifyList
 *   • "Got it / Back" → closes the modal
 */
export default function StreamUnavailableModal({ id, meta, onClose }) {
    const [added, setAdded] = useState(() => isInNotifyList(id));
    const primaryBtnRef = useRef(null);
    const backBtnRef = useRef(null);
    const [focusIdx, setFocusIdx] = useState(0);

    useEffect(() => {
        const t = setTimeout(() => {
            (focusIdx === 0 ? primaryBtnRef : backBtnRef).current?.focus({ preventScroll: true });
        }, 80);
        return () => clearTimeout(t);
    }, [focusIdx]);

    const handleNotifyToggle = () => {
        if (added) {
            removeFromNotifyList(id);
            setAdded(false);
        } else {
            addToNotifyList(id, {
                type: meta?.type || 'movie',
                meta: {
                    name: meta?.name,
                    poster: meta?.poster,
                    background: meta?.background,
                    releaseInfo: meta?.releaseInfo || meta?.year,
                },
            });
            setAdded(true);
            /* Auto-dismiss once added so the user gets out of the
             * modal and back to the Detail page.  They'll see the
             * "in your reminder list" pill on the Play button and
             * the toast confirmation on the next app boot when
             * streams drop. */
            window.setTimeout(() => {
                if (typeof onClose === 'function') onClose();
            }, 350);
            return;
        }
    };

    /* Choose backdrop — prefer the wide art, fall back to poster. */
    const bgUrl =
        (meta?.background && img.backdrop(meta.background)) ||
        (meta?.poster && img.poster(meta.poster)) ||
        '';

    // v2.7.49 — portal to <body> so position:fixed escapes any
    // ancestor stacking context (transform / filter / will-change on
    // shelves was making the modal anchor to the shelf instead of the
    // viewport, which is why it appeared cut off at the bottom).
    if (typeof document === 'undefined') return null;
    return createPortal(
        <div
            data-testid="stream-unavailable-modal"
            onKeyDown={(e) => {
                if (e.key === 'ArrowLeft') {
                    e.preventDefault();
                    setFocusIdx(0);
                } else if (e.key === 'ArrowRight') {
                    e.preventDefault();
                    setFocusIdx(1);
                } else if (e.key === 'Escape' || e.key === 'Backspace') {
                    e.preventDefault();
                    onClose?.();
                }
            }}
            style={{
                position: 'fixed', inset: 0, zIndex: 9000,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}
        >
            {/* Cinematic blurred backdrop layer */}
            {bgUrl && (
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', inset: 0,
                        backgroundImage: `url(${bgUrl})`,
                        backgroundSize: 'cover',
                        backgroundPosition: 'center 30%',
                        filter: 'blur(28px) saturate(110%)',
                        transform: 'scale(1.1)',
                        opacity: 0.55,
                    }}
                />
            )}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute', inset: 0,
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
                    width: 'min(720px, 90vw)',
                    padding: '40px 44px 36px',
                    borderRadius: 28,
                    background:
                        'linear-gradient(160deg, rgba(15,22,38,0.85) 0%, rgba(6,8,15,0.88) 100%)',
                    border: '1px solid rgba(93,200,255,0.28)',
                    boxShadow:
                        '0 40px 100px rgba(0,0,0,0.75),' +
                        ' inset 0 1px 0 rgba(255,255,255,0.12),' +
                        ' 0 0 80px rgba(93,200,255,0.18)',
                    color: '#E6EAF2',
                    animation: 'vesper-stream-unavail-in 320ms cubic-bezier(.16,1,.3,1) both',
                }}
            >
                {/* Sparkle icon at top */}
                <div
                    style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        width: 88, height: 88, borderRadius: '50%',
                        background:
                            'radial-gradient(circle at 30% 22%, rgba(255,255,255,0.45) 0%, rgba(255,255,255,0) 50%),' +
                            ' linear-gradient(160deg, #5DC8FF 0%, #1a78b8 100%)',
                        boxShadow:
                            'inset 0 2px 0 rgba(255,255,255,0.5),' +
                            ' inset 0 -10px 18px rgba(0,40,80,0.5),' +
                            ' 0 0 40px rgba(93,200,255,0.7),' +
                            ' 0 12px 32px rgba(93,200,255,0.4)',
                        color: '#06080F',
                        margin: '0 auto 22px',
                    }}
                >
                    <Sparkles size={36} strokeWidth={2.4} />
                </div>

                <div
                    className="vesper-mono"
                    style={{
                        textAlign: 'center',
                        fontSize: 11,
                        letterSpacing: '0.42em',
                        color: '#5DC8FF',
                        fontWeight: 800,
                        textTransform: 'uppercase',
                        marginBottom: 14,
                        textShadow: '0 0 14px rgba(93,200,255,0.6)',
                    }}
                >
                    Coming soon
                </div>

                <h2
                    style={{
                        textAlign: 'center',
                        fontSize: 32,
                        fontWeight: 800,
                        lineHeight: 1.1,
                        letterSpacing: '-0.018em',
                        marginBottom: 14,
                    }}
                >
                    {meta?.name || 'This title'} isn’t streaming&nbsp;just&nbsp;yet
                </h2>

                <p
                    style={{
                        textAlign: 'center',
                        fontSize: 16,
                        lineHeight: 1.5,
                        color: '#9DA5B5',
                        marginBottom: 32,
                        maxWidth: 560,
                        margin: '0 auto 32px',
                    }}
                >
                    {added
                        ? "It's in your notify list — we'll alert you the moment a stream pops up on any of your sources."
                        : 'Add it to your notify list and we’ll alert you the moment a stream pops up on any of your sources.'}
                </p>

                {/* Buttons row */}
                <div style={{ display: 'flex', gap: 14, justifyContent: 'center' }}>
                    {/* Notify me / Already added — primary cyan bubble */}
                    <button
                        ref={primaryBtnRef}
                        data-testid="stream-unavail-notify-btn"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={(e) => { e.stopPropagation(); handleNotifyToggle(); }}
                        onFocus={() => setFocusIdx(0)}
                        onMouseEnter={() => setFocusIdx(0)}
                        style={{
                            position: 'relative',
                            padding: '16px 28px',
                            borderRadius: 999,
                            border: 'none',
                            cursor: 'pointer',
                            fontFamily: 'inherit',
                            fontSize: 14,
                            fontWeight: 800,
                            letterSpacing: '0.14em',
                            textTransform: 'uppercase',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 10,
                            background: focusIdx === 0
                                ? 'radial-gradient(circle at 30% 22%, rgba(255,255,255,0.5) 0%, rgba(255,255,255,0) 50%),' +
                                  ' linear-gradient(160deg, #5DC8FF 0%, #1a78b8 100%)'
                                : 'rgba(93,200,255,0.18)',
                            color: focusIdx === 0 ? '#06080F' : '#5DC8FF',
                            boxShadow: focusIdx === 0
                                ? 'inset 0 2px 0 rgba(255,255,255,0.45),' +
                                  ' inset 0 -10px 18px rgba(0,40,80,0.4),' +
                                  ' 0 0 32px rgba(93,200,255,0.7),' +
                                  ' 0 12px 28px rgba(93,200,255,0.4)'
                                : '0 6px 18px rgba(0,0,0,0.4)',
                            border: focusIdx === 0
                                ? 'none'
                                : '1px solid rgba(93,200,255,0.4)',
                            transform: focusIdx === 0 ? 'translateY(-2px) scale(1.04)' : 'scale(1)',
                            transition:
                                'transform 220ms cubic-bezier(.16,1,.3,1),' +
                                ' box-shadow 200ms ease, background 200ms ease, color 200ms ease',
                            outline: 'none',
                        }}
                    >
                        {added ? <BellOff size={18} /> : <BellRing size={18} />}
                        {added ? 'Remove from notify list' : 'Notify me when ready'}
                    </button>

                    {/* Back / dismiss */}
                    <button
                        ref={backBtnRef}
                        data-testid="stream-unavail-back-btn"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={(e) => { e.stopPropagation(); onClose?.(); }}
                        onFocus={() => setFocusIdx(1)}
                        onMouseEnter={() => setFocusIdx(1)}
                        style={{
                            padding: '16px 24px',
                            borderRadius: 999,
                            cursor: 'pointer',
                            fontFamily: 'inherit',
                            fontSize: 14,
                            fontWeight: 800,
                            letterSpacing: '0.14em',
                            textTransform: 'uppercase',
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 10,
                            background: focusIdx === 1
                                ? 'rgba(255,255,255,0.12)'
                                : 'rgba(255,255,255,0.04)',
                            color: focusIdx === 1 ? '#FFFFFF' : '#9DA5B5',
                            border: focusIdx === 1
                                ? '1px solid rgba(255,255,255,0.35)'
                                : '1px solid rgba(255,255,255,0.08)',
                            outline: 'none',
                            transition:
                                'background 200ms ease, color 200ms ease,' +
                                ' border-color 200ms ease',
                        }}
                    >
                        <ArrowLeft size={18} />
                        Back
                    </button>
                </div>
            </div>

            <style>{`
@keyframes vesper-stream-unavail-in {
    from { opacity: 0; transform: translateY(24px) scale(0.96); }
    to   { opacity: 1; transform: translateY(0) scale(1); }
}
            `}</style>
        </div>,
        document.body,
    );
}
