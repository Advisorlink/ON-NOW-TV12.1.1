import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
    Sparkles, Check, ChevronRight, X, Bookmark, Film, Layers, Clapperboard, Gauge, Radio,
    ListOrdered, Info, PlusCircle, Zap, Search, Settings2, Gift, Lightbulb, EyeOff, Users, Sword,
    MoveHorizontal, LayoutGrid, Eye, Tv, Library,
} from 'lucide-react';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';

const ICONS = {
    bookmark: Bookmark, film: Film, layers: Layers, clapperboard: Clapperboard, gauge: Gauge, radio: Radio,
    'list-ordered': ListOrdered, info: Info, 'plus-circle': PlusCircle, zap: Zap, search: Search,
    'settings-2': Settings2, gift: Gift, lightbulb: Lightbulb, 'eye-off': EyeOff, users: Users, sword: Sword,
    movement: MoveHorizontal, 'layout-grid': LayoutGrid, eye: Eye, tv: Tv, library: Library, sparkles: Sparkles,
};
const ACCENTS = ['#5DC8FF', '#7CF1A1', '#FFD36B', '#FF8FAB', '#C4A3FF', '#FF9B6B', '#6BE3E3', '#9FE870'];
const hex = (h, a) => `${h}${Math.round(a * 255).toString(16).padStart(2, '0')}`;

/** Build the screens: every release except the newest becomes one
 *  "chapter" recap screen; the newest release gets one screen per feature. */
function buildScreens(releases) {
    const out = [];
    releases.forEach((r, ri) => {
        const newest = ri === releases.length - 1;
        if (newest) {
            r.release.items.forEach((item) => out.push({ kind: 'feature', version: r.version, release: r.release, item }));
        } else {
            out.push({ kind: 'chapter', version: r.version, release: r.release });
        }
    });
    return out;
}

/** "What's new" tour with Skip / Next.  Older releases (since the floor)
 *  show as one recap screen each, the current release one feature per
 *  screen.  LEFT/RIGHT moves between the two buttons, OK activates,
 *  BACK closes. */
export default function WhatsNewTour({ releases, version, onDismiss }) {
    const steps = React.useMemo(() => buildScreens(releases), [releases]);
    const [i, setI] = useState(0);
    const [dir, setDir] = useState(1);
    const nextRef = useRef(null);
    const root = useRef(null);
    const last = i === steps.length - 1;
    const screen = steps[i];
    const step = screen.kind === 'feature' ? screen.item : { title: screen.release.headline, icon: 'sparkles' };
    const release = screen.release;
    const accent = step.accent || ACCENTS[i % ACCENTS.length];
    const Icon = ICONS[step.icon] || Check;

    useNativeBackTrap(true, () => { onDismiss(); return false; });
    useEffect(() => {
        nextRef.current?.focus({ preventScroll: true });
        const contain = (e) => {
            if (root.current && !root.current.contains(e.target)) nextRef.current?.focus({ preventScroll: true });
        };
        document.addEventListener('focusin', contain);
        return () => document.removeEventListener('focusin', contain);
    }, [i]);

    const go = (n) => { setDir(n > i ? 1 : -1); setI(Math.max(0, Math.min(steps.length - 1, n))); };
    const next = () => (last ? onDismiss() : go(i + 1));

    const onKeyDown = (e) => {
        const k = e.key;
        if (['Escape', 'Backspace', 'GoBack'].includes(k)) { e.preventDefault(); e.stopPropagation(); triggerTrapBack(); return; }
        if (k === 'ArrowLeft' || k === 'ArrowRight' || k === 'Tab') {
            e.preventDefault(); e.stopPropagation();
            const skip = root.current.querySelector('[data-testid="whats-new-skip"]');
            (document.activeElement === nextRef.current ? skip : nextRef.current)?.focus({ preventScroll: true });
            return;
        }
        if (k === 'ArrowUp' || k === 'ArrowDown') { e.preventDefault(); e.stopPropagation(); }
    };

    return createPortal(
        <div data-testid="whats-new-modal" onClick={onDismiss} style={S.overlay}>
            <section
                ref={root}
                role="dialog"
                aria-modal="true"
                data-testid="whats-new-panel"
                data-focus-trap="true"
                onKeyDown={onKeyDown}
                onClick={(e) => e.stopPropagation()}
                style={{ ...S.panel, '--wn-accent': accent }}
            >
                {/* Visual half */}
                <div style={{ ...S.visual, background: `radial-gradient(120% 90% at 20% 15%, ${hex(accent, 0.42)} 0%, ${hex(accent, 0.08)} 45%, rgba(6,8,15,0) 75%), linear-gradient(160deg, #0d1322 0%, #070a12 100%)` }}>
                    <div data-testid="whats-new-step-counter" className="vesper-mono" style={S.counter}>
                        {String(i + 1).padStart(2, '0')} <span style={{ opacity: 0.45 }}>/ {String(steps.length).padStart(2, '0')}</span>
                    </div>
                    <div key={i} style={{ ...S.iconWrap, animation: 'wn-pop 420ms cubic-bezier(.2,.9,.3,1.2) both' }}>
                        <span style={{ ...S.ring, boxShadow: `0 0 0 1px ${hex(accent, 0.55)}, 0 0 60px ${hex(accent, 0.35)}, inset 0 0 40px ${hex(accent, 0.18)}` }} />
                        <span style={{ ...S.ring2, borderColor: hex(accent, 0.25) }} />
                        {screen.kind === 'chapter' ? (
                            <span data-testid="whats-new-chapter-version" className="vesper-display" style={{ position: 'relative', textAlign: 'center', color: '#fff', lineHeight: 1 }}>
                                <span className="vesper-mono" style={{ display: 'block', fontSize: 12, letterSpacing: '0.3em', color: accent, marginBottom: 10 }}>VERSION</span>
                                <span style={{ fontSize: 54, fontWeight: 800, letterSpacing: '-0.03em', textShadow: `0 0 28px ${hex(accent, 0.5)}` }}>{screen.version}</span>
                                <span style={{ display: 'block', fontSize: 13, color: 'rgba(255,255,255,0.6)', marginTop: 10 }}>{release.date}</span>
                            </span>
                        ) : (
                            <Icon size={92} strokeWidth={1.4} color={accent} aria-hidden="true" style={{ position: 'relative', filter: `drop-shadow(0 0 18px ${hex(accent, 0.6)})` }} />
                        )}
                    </div>
                    <div style={S.grain} aria-hidden="true" />
                </div>

                {/* Copy half */}
                <div style={S.copy}>
                    <div data-testid="whats-new-version" className="vesper-mono" style={S.eyebrow}>
                        <Sparkles size={13} /> {screen.kind === 'chapter' ? 'EARLIER UPDATE' : 'WHAT’S NEW'} · V{screen.version} <span style={{ opacity: 0.45 }}>· {release.date}</span>
                    </div>
                    <div key={`t${i}`} style={{ animation: `wn-slide-${dir > 0 ? 'in' : 'back'} 320ms ease-out both`, flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'center', minHeight: 0 }}>
                        {screen.kind === 'chapter' ? (
                            <>
                                <h2 data-testid="whats-new-heading" className="vesper-display" style={{ ...S.title, fontSize: 'clamp(22px, 2vw, 30px)', marginBottom: 14 }}>{release.headline}</h2>
                                <ul data-testid="whats-new-chapter-list" style={{ ...S.chapterList, gridTemplateColumns: release.items.length > 7 ? '1fr 1fr' : '1fr' }}>
                                    {release.items.map((it) => {
                                        const It = ICONS[it.icon] || Check;
                                        return (
                                            <li key={it.title} style={S.chapterItem}>
                                                <span style={{ ...S.chapterIcon, color: accent, background: hex(accent, 0.12) }}><It size={14} strokeWidth={2.2} /></span>
                                                <span style={{ minWidth: 0 }}>
                                                    <span style={{ display: 'block', fontSize: 14, fontWeight: 700, color: '#fff', lineHeight: 1.25 }}>{it.title}</span>
                                                    <span style={{ display: '-webkit-box', WebkitLineClamp: release.items.length > 7 ? 1 : 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', fontSize: 12.5, lineHeight: 1.35, color: 'rgba(226,234,245,0.68)', marginTop: 2 }}>{it.detail}</span>
                                                </span>
                                            </li>
                                        );
                                    })}
                                </ul>
                            </>
                        ) : (
                            <>
                                <h2 data-testid="whats-new-heading" className="vesper-display" style={S.title}>{step.title}</h2>
                                <p data-testid="whats-new-detail" style={S.detail}>{step.detail}</p>
                                {step.how && (
                                    <div data-testid="whats-new-how" style={{ ...S.how, borderColor: hex(accent, 0.35), background: hex(accent, 0.08) }}>
                                        <Lightbulb size={14} color={accent} />
                                        <span>{step.how}</span>
                                    </div>
                                )}
                            </>
                        )}
                    </div>

                    <div style={S.footer}>
                        <div data-testid="whats-new-progress" style={S.dots} aria-hidden="true">
                            {steps.map((_, n) => (
                                <span key={n} style={{ ...S.dot, width: n === i ? 28 : 8, background: n <= i ? accent : 'rgba(255,255,255,0.14)' }} />
                            ))}
                        </div>
                        <div style={{ display: 'flex', gap: 10 }}>
                            <button
                                type="button"
                                data-testid="whats-new-skip"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={onDismiss}
                                className="font-sans"
                                style={S.skip}
                            >
                                <X size={15} /> Skip
                            </button>
                            <button
                                ref={nextRef}
                                type="button"
                                data-testid={last ? 'whats-new-got-it' : 'whats-new-next'}
                                data-focusable="true"
                                data-focus-style="pill"
                                data-initial-focus="true"
                                tabIndex={0}
                                onClick={next}
                                className="font-sans"
                                style={{ ...S.next, background: accent, boxShadow: `0 10px 30px ${hex(accent, 0.35)}` }}
                            >
                                {last ? <>Done <Check size={17} strokeWidth={2.6} /></> : <>Next <ChevronRight size={17} strokeWidth={2.6} /></>}
                            </button>
                        </div>
                    </div>
                </div>
            </section>
            <style>{`
                @keyframes wn-pop { from { opacity: 0; transform: scale(.72) } to { opacity: 1; transform: scale(1) } }
                @keyframes wn-slide-in { from { opacity: 0; transform: translateX(28px) } to { opacity: 1; transform: none } }
                @keyframes wn-slide-back { from { opacity: 0; transform: translateX(-28px) } to { opacity: 1; transform: none } }
                @keyframes wn-fade { from { opacity: 0 } to { opacity: 1 } }
            `}</style>
        </div>,
        document.body,
    );
}

const S = {
    overlay: {
        position: 'fixed', inset: 0, zIndex: 9000, display: 'flex', alignItems: 'center', justifyContent: 'center',
        background: 'rgba(3, 5, 10, 0.86)', animation: 'wn-fade 200ms ease-out both', padding: '4vh 4vw',
    },
    panel: {
        width: 'min(1180px, 92vw)', height: 'min(640px, 86vh)', display: 'grid', gridTemplateColumns: '44% 56%',
        borderRadius: 26, overflow: 'hidden', background: '#0a0e19', border: '1px solid rgba(255,255,255,0.09)',
        boxShadow: '0 40px 120px rgba(0,0,0,0.7)',
    },
    visual: { position: 'relative', display: 'flex', alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
    counter: { position: 'absolute', top: 26, left: 30, fontSize: 13, letterSpacing: '0.24em', color: '#fff', fontWeight: 700 },
    iconWrap: { position: 'relative', width: 240, height: 240, display: 'flex', alignItems: 'center', justifyContent: 'center' },
    ring: { position: 'absolute', inset: 0, borderRadius: '50%', background: 'rgba(255,255,255,0.02)' },
    ring2: { position: 'absolute', inset: -34, borderRadius: '50%', border: '1px dashed' },
    grain: {
        position: 'absolute', inset: 0, pointerEvents: 'none', opacity: 0.35, mixBlendMode: 'overlay',
        backgroundImage: 'url("data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'120\' height=\'120\'><filter id=\'n\'><feTurbulence baseFrequency=\'0.9\' numOctaves=\'2\'/></filter><rect width=\'120\' height=\'120\' filter=\'url(%23n)\' opacity=\'0.5\'/></svg>")',
    },
    copy: { display: 'flex', flexDirection: 'column', padding: '34px 44px 30px 46px', minWidth: 0 },
    eyebrow: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 11, letterSpacing: '0.26em', color: 'var(--wn-accent)', fontWeight: 700 },
    title: { fontSize: 'clamp(30px, 2.9vw, 46px)', lineHeight: 1.05, letterSpacing: '-0.02em', fontWeight: 800, color: '#fff', margin: '0 0 18px' },
    detail: { fontSize: 'clamp(15px, 1.15vw, 19px)', lineHeight: 1.55, color: 'rgba(226, 234, 245, 0.82)', margin: 0, maxWidth: '46ch' },
    how: {
        display: 'inline-flex', alignItems: 'center', gap: 10, marginTop: 22, padding: '10px 14px', borderRadius: 12,
        border: '1px solid', fontSize: 13.5, color: 'rgba(255,255,255,0.88)', maxWidth: 'fit-content',
    },
    footer: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 20, marginTop: 24 },
    chapterList: { listStyle: 'none', margin: 0, padding: 0, display: 'grid', gap: '7px 18px', overflow: 'hidden', minHeight: 0, alignContent: 'start' },
    chapterItem: { display: 'flex', gap: 10, alignItems: 'flex-start', minWidth: 0 },
    chapterIcon: { flex: '0 0 26px', width: 26, height: 26, borderRadius: 8, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', marginTop: 1 },
    dots: { display: 'flex', alignItems: 'center', gap: 6 },
    dot: { height: 8, borderRadius: 999, transition: 'width 220ms ease, background-color 220ms ease' },
    skip: {
        display: 'inline-flex', alignItems: 'center', gap: 8, height: 48, padding: '0 20px', borderRadius: 999,
        background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.14)', color: 'rgba(255,255,255,0.85)',
        fontSize: 15, fontWeight: 600, cursor: 'pointer',
    },
    next: {
        display: 'inline-flex', alignItems: 'center', gap: 8, height: 48, padding: '0 26px 0 28px', borderRadius: 999,
        border: 'none', color: '#061018', fontSize: 15, fontWeight: 800, cursor: 'pointer',
    },
};
