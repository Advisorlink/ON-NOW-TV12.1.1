/**
 * CloudRestoreDialog — the "we found your data on the cloud" prompt
 * shown once at sign-in when the fresh device has a snapshot waiting
 * on the Vesper cloud sync.
 *
 * v2.16.18 — Designed to feel INVITING, not administrative.  Big
 * cinematic hero, cyan Vesper accent, subtle animated cloud sparkles,
 * per-category rows that stagger in.  Categories rendered by
 * `summarizeBackupPayload()`:
 *   • Profiles         (with the profile names inline)
 *   • Continue Watching
 *   • Library favourites / Watch Later
 *   • Live TV favourites
 *   • Reminders
 *
 * Two big actions:
 *   • "Restore my library"  — applies the snapshot, then reloads.
 *   • "Start fresh"         — dismisses.  User can always sync-up later.
 */

import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
    Cloud, Sparkles, Users, PlayCircle, Heart, Tv, Bell, Check, X, Loader2,
} from 'lucide-react';
import { summarizeBackupPayload, applyBackupPayload, fmtBytes } from '@/lib/profileBackup';

const ACCENT = '#5DC8FF';
const ACCENT_DEEP = '#2EA8E8';

function relativeTime(ms) {
    if (!ms) return '';
    const delta = Date.now() - ms;
    if (delta < 60_000) return 'just now';
    if (delta < 3600_000) return `${Math.floor(delta / 60_000)} min ago`;
    if (delta < 86400_000) return `${Math.floor(delta / 3600_000)} h ago`;
    return `${Math.floor(delta / 86400_000)} d ago`;
}

/**
 * @param {object} props
 * @param {object} props.snapshot       - Pull response {data, updated_at, approx_bytes, key_count}
 * @param {() => void} props.onDismiss  - Called for both Restore-complete and Start-fresh.
 */
export default function CloudRestoreDialog({ snapshot, onDismiss }) {
    const [closing, setClosing] = useState(false);
    const [busy, setBusy] = useState(false);
    const restoreBtnRef = useRef(null);

    // Land focus on the primary CTA so a TV remote or keyboard Enter
    // just works.
    useEffect(() => {
        const t = setTimeout(() => restoreBtnRef.current?.focus(), 260);
        return () => clearTimeout(t);
    }, []);

    // Prevent background scroll while open.
    useEffect(() => {
        const prev = document.body.style.overflow;
        document.body.style.overflow = 'hidden';
        return () => { document.body.style.overflow = prev; };
    }, []);

    // Compute the summary once — snapshot won't change mid-render.
    const summary = useMemo(
        () => summarizeBackupPayload(snapshot?.data || {}),
        [snapshot],
    );

    const rows = useMemo(() => {
        const items = [];
        if (summary.profileCount > 0) {
            items.push({
                key: 'profiles',
                icon: Users,
                title: `${summary.profileCount} ${summary.profileCount === 1 ? 'profile' : 'profiles'}`,
                sub: summary.profileNames && summary.profileNames.length
                    ? summary.profileNames.join(' · ')
                    : 'Everyone\u2019s avatars & kids settings',
            });
        }
        if (summary.cwCount > 0) {
            items.push({
                key: 'cw',
                icon: PlayCircle,
                title: `${summary.cwCount} Continue Watching ${summary.cwCount === 1 ? 'item' : 'items'}`,
                sub: 'Pick up exactly where you left off',
            });
        }
        if (summary.libraryCount > 0) {
            items.push({
                key: 'library',
                icon: Heart,
                title: `${summary.libraryCount} in your Library`,
                sub: 'Favourites, Watch Later & saved actors',
            });
        }
        if (summary.liveFavourites > 0) {
            items.push({
                key: 'live',
                icon: Tv,
                title: `${summary.liveFavourites} Live TV ${summary.liveFavourites === 1 ? 'favourite' : 'favourites'}`,
                sub: 'Your top channels, ready to zap',
            });
        }
        if (summary.reminders > 0) {
            items.push({
                key: 'reminders',
                icon: Bell,
                title: `${summary.reminders} EPG ${summary.reminders === 1 ? 'reminder' : 'reminders'}`,
                sub: 'Shows we\u2019ll ping you when they start',
            });
        }
        return items;
    }, [summary]);

    const handleRestore = async () => {
        if (busy) return;
        setBusy(true);
        try {
            applyBackupPayload(snapshot?.data || {});
            // Small delay so the user sees the success state before
            // we blow the page away with the reload.
            await new Promise((r) => setTimeout(r, 480));
            // Full reload — every store re-reads localStorage on mount.
            window.location.reload();
        } catch (e) {
            console.error('[CloudRestoreDialog] applyBackupPayload failed', e);
            setBusy(false);
        }
    };

    const handleStartFresh = () => {
        if (busy) return;
        setClosing(true);
        setTimeout(onDismiss, 220);
    };

    const updatedRel = relativeTime(snapshot?.updated_at);
    const sizeStr = fmtBytes(snapshot?.approx_bytes || 0);

    return (
        <div
            data-testid="cloud-restore-dialog"
            style={{
                position: 'fixed',
                inset: 0,
                zIndex: 100,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                padding: '4vh 4vw',
                background:
                    'radial-gradient(ellipse at 30% 20%, rgba(93,200,255,0.18) 0%, rgba(4,16,30,0) 55%),' +
                    'radial-gradient(ellipse at 80% 90%, rgba(46,168,232,0.22) 0%, rgba(4,16,30,0) 60%),' +
                    'rgba(2,6,14,0.86)',
                backdropFilter: 'blur(28px) saturate(1.15)',
                WebkitBackdropFilter: 'blur(28px) saturate(1.15)',
                opacity: closing ? 0 : 1,
                transition: 'opacity 220ms ease',
            }}
        >
            {/* Floating "spark" particles for a whisper of motion.  Pure
                CSS animation — no dependencies. */}
            <div style={sparkleStyle} aria-hidden />

            <div
                style={{
                    width: 'min(680px, 100%)',
                    borderRadius: 28,
                    padding: '38px 40px 34px',
                    background:
                        'linear-gradient(180deg, rgba(14,26,44,0.94) 0%, rgba(6,14,26,0.94) 100%)',
                    border: '1px solid rgba(93,200,255,0.22)',
                    boxShadow:
                        '0 40px 80px -20px rgba(0,0,0,0.7), ' +
                        `0 0 0 1px rgba(93,200,255,0.06), ` +
                        `0 0 60px -18px rgba(93,200,255,0.35)`,
                    transform: closing ? 'scale(0.96) translateY(6px)' : 'scale(1) translateY(0)',
                    opacity: closing ? 0 : 1,
                    transition:
                        'transform 260ms cubic-bezier(0.2, 0.8, 0.2, 1),' +
                        ' opacity 260ms ease',
                    color: '#EAF3FF',
                    maxHeight: '92vh',
                    overflowY: 'auto',
                }}
            >
                {/* Hero */}
                <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 22 }}>
                    <div style={heroIconStyle}>
                        <Cloud size={28} strokeWidth={1.6} color={ACCENT} />
                        <Sparkles
                            size={14}
                            color={ACCENT}
                            style={{
                                position: 'absolute',
                                top: 6,
                                right: 6,
                                filter: 'drop-shadow(0 0 6px rgba(93,200,255,0.9))',
                            }}
                        />
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                        <div
                            style={{
                                fontSize: 11,
                                letterSpacing: 2.4,
                                textTransform: 'uppercase',
                                color: ACCENT,
                                fontWeight: 600,
                                marginBottom: 4,
                            }}
                        >
                            Welcome back
                        </div>
                        <div
                            style={{
                                fontSize: 26,
                                fontWeight: 700,
                                lineHeight: 1.2,
                                background: `linear-gradient(90deg, #FFFFFF 0%, ${ACCENT} 120%)`,
                                WebkitBackgroundClip: 'text',
                                WebkitTextFillColor: 'transparent',
                            }}
                        >
                            We found your library in the cloud
                        </div>
                    </div>
                </div>

                <p
                    style={{
                        fontSize: 15,
                        lineHeight: 1.55,
                        color: 'rgba(200,220,240,0.82)',
                        margin: '0 0 22px',
                        maxWidth: 520,
                    }}
                >
                    Everything you’ve added, watched, or favourited on your other devices —
                    ready to slide right back in.
                </p>

                {/* Category rows */}
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginBottom: 26 }}>
                    {rows.length === 0 ? (
                        <div style={emptyStyle}>Your snapshot is empty — nothing to restore.</div>
                    ) : (
                        rows.map((row, i) => (
                            <RestoreRow
                                key={row.key}
                                icon={row.icon}
                                title={row.title}
                                sub={row.sub}
                                delay={i * 60}
                            />
                        ))
                    )}
                </div>

                {/* Meta footer */}
                <div
                    style={{
                        fontSize: 12,
                        color: 'rgba(160,185,215,0.62)',
                        marginBottom: 22,
                        letterSpacing: 0.3,
                    }}
                >
                    Last saved {updatedRel} · {sizeStr}
                </div>

                {/* Actions */}
                <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                    <button
                        ref={restoreBtnRef}
                        onClick={handleRestore}
                        disabled={busy || rows.length === 0}
                        data-testid="cloud-restore-confirm"
                        style={{
                            flex: '1 1 260px',
                            minHeight: 54,
                            padding: '0 22px',
                            borderRadius: 14,
                            fontSize: 15.5,
                            fontWeight: 600,
                            color: '#04101E',
                            border: 'none',
                            cursor: busy || rows.length === 0 ? 'not-allowed' : 'pointer',
                            background: `linear-gradient(135deg, ${ACCENT} 0%, ${ACCENT_DEEP} 100%)`,
                            boxShadow: `0 12px 30px -10px ${ACCENT}88`,
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 10,
                            opacity: busy ? 0.85 : (rows.length === 0 ? 0.45 : 1),
                            outline: 'none',
                            transition: 'transform 120ms ease, box-shadow 120ms ease',
                        }}
                        onFocus={(e) => {
                            e.currentTarget.style.transform = 'translateY(-1px)';
                            e.currentTarget.style.boxShadow = `0 18px 40px -10px ${ACCENT}bb`;
                        }}
                        onBlur={(e) => {
                            e.currentTarget.style.transform = 'translateY(0)';
                            e.currentTarget.style.boxShadow = `0 12px 30px -10px ${ACCENT}88`;
                        }}
                    >
                        {busy ? (
                            <>
                                <Loader2 size={18} className="animate-spin" />
                                Restoring…
                            </>
                        ) : (
                            <>
                                <Check size={19} strokeWidth={2.4} />
                                Restore my library
                            </>
                        )}
                    </button>
                    <button
                        onClick={handleStartFresh}
                        disabled={busy}
                        data-testid="cloud-restore-dismiss"
                        style={{
                            flex: '0 1 180px',
                            minHeight: 54,
                            padding: '0 22px',
                            borderRadius: 14,
                            fontSize: 14.5,
                            fontWeight: 500,
                            color: 'rgba(200,220,240,0.92)',
                            border: '1px solid rgba(140,170,200,0.28)',
                            background: 'rgba(20,32,52,0.55)',
                            cursor: busy ? 'not-allowed' : 'pointer',
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            gap: 8,
                            transition: 'background 120ms ease, border-color 120ms ease',
                            outline: 'none',
                        }}
                        onFocus={(e) => {
                            e.currentTarget.style.background = 'rgba(30,46,70,0.75)';
                            e.currentTarget.style.borderColor = 'rgba(140,170,200,0.55)';
                        }}
                        onBlur={(e) => {
                            e.currentTarget.style.background = 'rgba(20,32,52,0.55)';
                            e.currentTarget.style.borderColor = 'rgba(140,170,200,0.28)';
                        }}
                    >
                        <X size={17} strokeWidth={2} />
                        Start fresh
                    </button>
                </div>
            </div>

            {/* Local keyframe styles for staggered row entrance + sparkle drift */}
            <style>{`
                @keyframes vc-row-in {
                    from { opacity: 0; transform: translateY(6px); }
                    to   { opacity: 1; transform: translateY(0); }
                }
                @keyframes vc-sparkle-drift {
                    0%   { transform: translateY(0) translateX(0); }
                    50%  { transform: translateY(-12px) translateX(6px); }
                    100% { transform: translateY(0) translateX(0); }
                }
            `}</style>
        </div>
    );
}

function RestoreRow({ icon: Icon, title, sub, delay }) {
    return (
        <div
            style={{
                display: 'flex',
                alignItems: 'center',
                gap: 14,
                padding: '13px 16px',
                borderRadius: 14,
                background: 'linear-gradient(90deg, rgba(93,200,255,0.06) 0%, rgba(93,200,255,0.02) 100%)',
                border: '1px solid rgba(93,200,255,0.12)',
                opacity: 0,
                animation: `vc-row-in 480ms cubic-bezier(0.2,0.8,0.2,1) ${delay}ms forwards`,
            }}
        >
            <div
                style={{
                    width: 42,
                    height: 42,
                    borderRadius: 12,
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: 'linear-gradient(135deg, rgba(93,200,255,0.18) 0%, rgba(46,168,232,0.10) 100%)',
                    border: '1px solid rgba(93,200,255,0.24)',
                    flexShrink: 0,
                }}
            >
                <Icon size={20} strokeWidth={1.8} color={ACCENT} />
            </div>
            <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 14.5, fontWeight: 600, color: '#EAF3FF', lineHeight: 1.25 }}>
                    {title}
                </div>
                <div
                    style={{
                        fontSize: 12.5,
                        color: 'rgba(160,185,215,0.75)',
                        marginTop: 2,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                    }}
                >
                    {sub}
                </div>
            </div>
        </div>
    );
}

const heroIconStyle = {
    position: 'relative',
    width: 60,
    height: 60,
    borderRadius: 18,
    background:
        'radial-gradient(circle at 30% 30%, rgba(93,200,255,0.28) 0%, rgba(93,200,255,0.08) 60%, transparent 100%)',
    border: '1px solid rgba(93,200,255,0.35)',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    boxShadow: '0 8px 24px -8px rgba(93,200,255,0.5), inset 0 0 20px rgba(93,200,255,0.08)',
    flexShrink: 0,
};

const emptyStyle = {
    padding: '18px 20px',
    borderRadius: 14,
    background: 'rgba(140,170,200,0.06)',
    border: '1px dashed rgba(140,170,200,0.2)',
    color: 'rgba(160,185,215,0.75)',
    fontSize: 14,
    textAlign: 'center',
};

const sparkleStyle = {
    position: 'absolute',
    inset: 0,
    pointerEvents: 'none',
    background:
        'radial-gradient(1.5px 1.5px at 22% 34%, rgba(93,200,255,0.55) 0%, transparent 50%),' +
        'radial-gradient(1px 1px at 78% 22%, rgba(255,255,255,0.6) 0%, transparent 50%),' +
        'radial-gradient(1.2px 1.2px at 12% 78%, rgba(93,200,255,0.5) 0%, transparent 50%),' +
        'radial-gradient(1px 1px at 88% 74%, rgba(255,255,255,0.5) 0%, transparent 50%),' +
        'radial-gradient(1.4px 1.4px at 55% 88%, rgba(93,200,255,0.4) 0%, transparent 50%)',
    animation: 'vc-sparkle-drift 8s ease-in-out infinite',
};
