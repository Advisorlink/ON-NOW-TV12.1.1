import React from 'react';
import { useLocation } from 'react-router-dom';
import {
    Sparkles, X, Layers, Clapperboard, LayoutGrid, PlusCircle, Zap, Search, Settings2, Gift, Check,
} from 'lucide-react';
import { APP_VERSION, WHATS_NEW } from '@/lib/appVersion';

const SEEN_KEY = 'onnowtv-whatsnew-seen-v1';
const ICONS = {
    layers: Layers, clapperboard: Clapperboard, 'layout-grid': LayoutGrid, 'plus-circle': PlusCircle,
    zap: Zap, search: Search, 'settings-2': Settings2, gift: Gift,
};

/**
 * "What's New" popup — shown once per app version on the profile
 * picker right after sign-in.  Vesper (Movies/TV) only.
 */
export default function WhatsNewModal() {
    const location = useLocation();
    const [open, setOpen] = React.useState(false);
    const gotItRef = React.useRef(null);

    React.useEffect(() => {
        const path = location.pathname || '/';
        const onProfiles = path.startsWith('/profiles');
        const blocked =
            path.startsWith('/trivia') ||
            path.startsWith('/music') ||
            path.startsWith('/kids') ||
            path.startsWith('/karaoke');
        if (!onProfiles || blocked) return undefined;
        let seen = '';
        try { seen = localStorage.getItem(SEEN_KEY) || ''; } catch { /* ignore */ }
        if (seen === APP_VERSION) return undefined;
        if (!WHATS_NEW[APP_VERSION]) return undefined;
        const t = setTimeout(() => setOpen(true), 1100);
        return () => clearTimeout(t);
    }, [location.pathname]);

    const dismiss = () => {
        try { localStorage.setItem(SEEN_KEY, APP_VERSION); } catch { /* ignore */ }
        setOpen(false);
    };

    React.useEffect(() => {
        if (!open) return undefined;
        const t = setTimeout(() => {
            try { gotItRef.current?.focus(); } catch { /* ignore */ }
        }, 120);
        return () => clearTimeout(t);
    }, [open]);

    if (!open) return null;
    const release = WHATS_NEW[APP_VERSION];
    const twoCols = release.items.length > 4;

    return (
        <div
            data-testid="whats-new-modal"
            className="fixed inset-0 flex items-center justify-center"
            style={{
                zIndex: 9999,
                background: 'rgba(3,5,10,0.78)',
                backdropFilter: 'blur(10px)',
                WebkitBackdropFilter: 'blur(10px)',
                padding: 24,
            }}
            onClick={dismiss}
        >
            <div
                data-focus-trap="true"
                onClick={(e) => e.stopPropagation()}
                className="relative overflow-hidden"
                style={{
                    width: 'min(1040px, 94vw)',
                    maxHeight: '90vh',
                    display: 'flex',
                    flexDirection: 'column',
                    borderRadius: 28,
                    background: 'linear-gradient(180deg, #0c1220 0%, #070a12 100%)',
                    border: '1px solid rgba(var(--vesper-blue-rgb), 0.35)',
                    boxShadow: '0 40px 120px rgba(0,0,0,0.75), 0 0 0 1px rgba(255,255,255,0.04) inset',
                    animation: 'vesperFadeUp 420ms cubic-bezier(0.2, 0.8, 0.2, 1) both',
                }}
            >
                {/* Header band */}
                <div
                    className="relative shrink-0"
                    style={{
                        padding: '30px 38px 26px',
                        background:
                            'radial-gradient(120% 180% at 0% 0%, rgba(var(--vesper-blue-rgb), 0.42) 0%, rgba(var(--vesper-blue-rgb), 0.08) 45%, transparent 70%), radial-gradient(70% 120% at 100% 100%, rgba(255,120,60,0.22) 0%, transparent 60%)',
                        borderBottom: '1px solid rgba(255,255,255,0.07)',
                    }}
                >
                    <div
                        aria-hidden="true"
                        className="absolute pointer-events-none"
                        style={{
                            right: -40, top: -60, width: 260, height: 260, borderRadius: '50%',
                            background: 'radial-gradient(circle, rgba(var(--vesper-blue-rgb), 0.35) 0%, transparent 65%)',
                            filter: 'blur(10px)',
                        }}
                    />
                    <button
                        data-testid="whats-new-close"
                        data-focusable="true"
                        data-focus-style="bare"
                        tabIndex={0}
                        onClick={dismiss}
                        aria-label="Close"
                        className="absolute flex items-center justify-center rounded-full"
                        style={{
                            position: 'absolute', top: 18, right: 18, width: 38, height: 38,
                            background: 'rgba(255,255,255,0.08)',
                            border: '1px solid rgba(255,255,255,0.14)',
                            color: 'var(--vesper-text-2)', cursor: 'pointer',
                        }}
                    >
                        <X size={16} />
                    </button>

                    <div className="flex items-center gap-3" style={{ marginBottom: 12 }}>
                        <span
                            className="vesper-mono inline-flex items-center gap-2"
                            style={{
                                fontSize: 11, letterSpacing: '0.28em', textTransform: 'uppercase',
                                color: '#06080F', background: 'var(--vesper-blue-bright)',
                                padding: '6px 12px', borderRadius: 999, fontWeight: 700,
                            }}
                        >
                            <Sparkles size={13} /> What&rsquo;s new
                        </span>
                        <span className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.24em', textTransform: 'uppercase', color: 'var(--vesper-text-3)' }}>
                            v{APP_VERSION}{release.date ? ` · ${release.date}` : ''}
                        </span>
                    </div>
                    <h2
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(28px, 3.4vw, 44px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 1.02,
                            color: '#fff',
                            maxWidth: '20ch',
                            textShadow: '0 4px 24px rgba(0,0,0,0.45)',
                        }}
                    >
                        {release.headline || 'We just updated the app'}
                    </h2>
                </div>

                {/* Feature grid */}
                <div
                    className="flex-1"
                    style={{
                        overflowY: 'auto',
                        padding: '26px 38px 10px',
                        display: 'grid',
                        gridTemplateColumns: twoCols ? '1fr 1fr' : '1fr',
                        gap: 14,
                    }}
                >
                    {release.items.map((it, i) => {
                        const Icon = ICONS[it.icon] || Check;
                        return (
                            <div
                                key={i}
                                data-testid={`whats-new-item-${i}`}
                                className="flex items-start gap-4"
                                style={{
                                    padding: '16px 18px',
                                    borderRadius: 18,
                                    background: 'rgba(255,255,255,0.035)',
                                    border: '1px solid rgba(255,255,255,0.07)',
                                    animation: `vesperFadeUp 480ms cubic-bezier(0.2, 0.8, 0.2, 1) ${120 + i * 55}ms both`,
                                }}
                            >
                                <div
                                    className="flex items-center justify-center shrink-0"
                                    style={{
                                        width: 44, height: 44, borderRadius: 14,
                                        background: 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.35) 0%, rgba(var(--vesper-blue-rgb),0.08) 100%)',
                                        border: '1px solid rgba(var(--vesper-blue-rgb),0.45)',
                                        color: 'var(--vesper-blue-bright)',
                                    }}
                                >
                                    <Icon size={20} />
                                </div>
                                <div className="flex-1 min-w-0">
                                    <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--vesper-text)', letterSpacing: '-0.01em' }}>
                                        {it.title}
                                    </div>
                                    {it.detail && (
                                        <div style={{ fontSize: 13.5, color: 'var(--vesper-text-2)', marginTop: 4, lineHeight: 1.45 }}>
                                            {it.detail}
                                        </div>
                                    )}
                                </div>
                            </div>
                        );
                    })}
                </div>

                <div className="shrink-0" style={{ padding: '18px 38px 30px' }}>
                    <button
                        data-testid="whats-new-got-it"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        ref={gotItRef}
                        tabIndex={0}
                        onClick={dismiss}
                        className="font-sans rounded-full"
                        style={{
                            width: '100%',
                            padding: '15px 0',
                            background: 'linear-gradient(135deg, var(--vesper-blue) 0%, #4FB8F0 100%)',
                            color: '#06080F',
                            border: 'none',
                            fontSize: 16,
                            fontWeight: 800,
                            letterSpacing: '-0.01em',
                            cursor: 'pointer',
                            boxShadow: '0 10px 28px rgba(var(--vesper-blue-rgb),0.4)',
                        }}
                    >
                        Let&rsquo;s go
                    </button>
                </div>
            </div>
        </div>
    );
}
