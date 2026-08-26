import React from 'react';
import { useLocation } from 'react-router-dom';
import { Sparkles, Check, X } from 'lucide-react';
import { APP_VERSION, WHATS_NEW } from '@/lib/appVersion';

const SEEN_KEY = 'onnowtv-whatsnew-seen-v1';

/**
 * "What's New" popup — shown once per app version the first time the
 * app opens after an update, so users can see what changed.  Vesper
 * (Movies/TV) only; skipped on the Trivia / Music / Kids surfaces and
 * on the login / profile-picker screens.
 */
export default function WhatsNewModal() {
    const location = useLocation();
    const [open, setOpen] = React.useState(false);

    React.useEffect(() => {
        const path = location.pathname || '/';
        const excluded =
            path.startsWith('/trivia') ||
            path.startsWith('/music') ||
            path.startsWith('/kids') ||
            path.startsWith('/karaoke') ||
            path.startsWith('/profiles') ||
            path.startsWith('/login');
        if (excluded) return undefined;
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

    if (!open) return null;
    const release = WHATS_NEW[APP_VERSION];

    return (
        <div
            data-testid="whats-new-modal"
            className="fixed inset-0 flex items-center justify-center"
            style={{
                zIndex: 9999,
                background: 'rgba(4,6,12,0.72)',
                backdropFilter: 'blur(6px)',
                WebkitBackdropFilter: 'blur(6px)',
                padding: 24,
            }}
            onClick={dismiss}
        >
            <div
                className="vesper-glass rounded-3xl"
                onClick={(e) => e.stopPropagation()}
                style={{
                    width: 'min(560px, 92vw)',
                    maxHeight: '86vh',
                    overflowY: 'auto',
                    padding: '32px 34px',
                    border: '1px solid rgba(93,200,255,0.28)',
                    boxShadow: '0 24px 80px rgba(0,0,0,0.6)',
                    position: 'relative',
                }}
            >
                <button
                    data-testid="whats-new-close"
                    onClick={dismiss}
                    aria-label="Close"
                    className="absolute flex items-center justify-center rounded-full"
                    style={{
                        top: 18, right: 18, width: 36, height: 36,
                        background: 'rgba(255,255,255,0.06)',
                        border: '1px solid rgba(255,255,255,0.12)',
                        color: 'var(--vesper-text-2)', cursor: 'pointer',
                    }}
                >
                    <X size={16} />
                </button>

                <div
                    className="vesper-mono flex items-center gap-2"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.28em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue-bright)',
                        marginBottom: 10,
                    }}
                >
                    <Sparkles size={14} />
                    What's new · v{APP_VERSION}
                    {release.date ? ` · ${release.date}` : ''}
                </div>

                <h2
                    className="vesper-display"
                    style={{
                        fontSize: 'clamp(26px, 3vw, 34px)',
                        letterSpacing: '-0.03em',
                        lineHeight: 1.05,
                        marginBottom: 22,
                        color: 'var(--vesper-text)',
                    }}
                >
                    We just updated the app
                </h2>

                <div className="flex flex-col" style={{ gap: 16 }}>
                    {release.items.map((it, i) => (
                        <div key={i} className="flex items-start gap-3">
                            <div
                                className="flex items-center justify-center shrink-0"
                                style={{
                                    width: 30, height: 30, borderRadius: '50%',
                                    marginTop: 2,
                                    background:
                                        'linear-gradient(135deg, rgba(93,200,255,0.30) 0%, rgba(93,200,255,0.06) 100%)',
                                    border: '1px solid rgba(93,200,255,0.5)',
                                    color: 'var(--vesper-blue-bright)',
                                }}
                            >
                                <Check size={16} />
                            </div>
                            <div className="flex-1 min-w-0">
                                <div style={{ fontSize: 16, fontWeight: 700, color: 'var(--vesper-text)' }}>
                                    {it.title}
                                </div>
                                {it.detail && (
                                    <div style={{ fontSize: 13.5, color: 'var(--vesper-text-2)', marginTop: 2, lineHeight: 1.4 }}>
                                        {it.detail}
                                    </div>
                                )}
                            </div>
                        </div>
                    ))}
                </div>

                <button
                    data-testid="whats-new-got-it"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={dismiss}
                    className="font-sans rounded-full"
                    style={{
                        marginTop: 28,
                        width: '100%',
                        padding: '14px 0',
                        background: 'linear-gradient(135deg, var(--vesper-blue) 0%, #4FB8F0 100%)',
                        color: '#06080F',
                        border: 'none',
                        fontSize: 15,
                        fontWeight: 700,
                        cursor: 'pointer',
                        boxShadow: '0 8px 22px rgba(93,200,255,0.4)',
                    }}
                >
                    Got it
                </button>
            </div>
        </div>
    );
}
