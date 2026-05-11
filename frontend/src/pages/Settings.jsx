import React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Check } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import FullscreenButton from '@/components/FullscreenButton';
import { THEMES } from '@/themes/themes';
import { useTheme } from '@/themes/ThemeProvider';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';

/**
 * Settings → Appearance → Theme picker + Playback toggles.
 */
export default function Settings() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { themeId, setThemeId } = useTheme();
    const [autoplay, setAutoplay] = React.useState(getAutoplay1080p());

    const toggleAutoplay = () => {
        const next = !autoplay;
        setAutoplay1080p(next);
        setAutoplay(next);
    };

    return (
        <div
            data-testid="settings-page"
            className="relative w-screen min-h-[100dvh] overflow-y-auto"
            style={{
                background: 'var(--vesper-bg-0)',
                color: 'var(--vesper-text)',
                padding: 'clamp(40px, 5vw, 80px) clamp(40px, 6vw, 96px)',
                fontFamily: 'var(--theme-font-body, "Geist", system-ui, sans-serif)',
            }}
        >
            <FullscreenButton />

            <button
                data-focusable="true"
                data-focus-style="pill"
                data-initial-focus="true"
                tabIndex={0}
                onClick={() => navigate('/')}
                className="inline-flex items-center gap-2"
                style={{
                    padding: '10px 18px',
                    background: 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    borderRadius: 999,
                    color: 'var(--vesper-text-2)',
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 12,
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    marginBottom: 28,
                }}
            >
                <ArrowLeft size={14} /> Back
            </button>

            <div
                style={{
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    marginBottom: 8,
                }}
            >
                Settings · Appearance
            </div>

            <h1
                style={{
                    fontFamily: 'var(--theme-font-display, "Geist", sans-serif)',
                    fontSize: 'clamp(40px, 4.6vw, 72px)',
                    fontWeight: 700,
                    letterSpacing: '-0.025em',
                    lineHeight: 0.95,
                    marginBottom: 12,
                }}
            >
                Theme
            </h1>

            <p
                style={{
                    fontSize: 'clamp(14px, 1.05vw, 17px)',
                    lineHeight: 1.55,
                    color: 'var(--vesper-text-2)',
                    maxWidth: '60ch',
                    marginBottom: 40,
                }}
            >
                ON NOW TV V2 ships with Vesper Neon — a cinematic, neon-blue
                aesthetic tuned for big-screen TV viewing. More themes coming
                soon.
            </p>

            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))',
                    gap: 'clamp(16px, 1.6vw, 28px)',
                }}
            >
                {THEMES.map((t) => (
                    <ThemeCard
                        key={t.id}
                        theme={t}
                        active={themeId === t.id}
                        onPick={() => setThemeId(t.id)}
                    />
                ))}
            </div>

            {/* ---- Playback section ---- */}
            <div
                style={{
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    marginTop: 64,
                    marginBottom: 8,
                }}
            >
                Settings · Playback
            </div>
            <h2
                style={{
                    fontFamily: 'var(--theme-font-display, "Geist", sans-serif)',
                    fontSize: 'clamp(28px, 3.2vw, 48px)',
                    fontWeight: 700,
                    letterSpacing: '-0.025em',
                    lineHeight: 0.95,
                    marginBottom: 24,
                }}
            >
                Streams
            </h2>

            <ToggleRow
                testid="autoplay-1080p"
                title="Autoplay 1080p"
                description="Skip the sources list and instantly play the first 1080p stream when you press Play.  Falls back to the source picker if no 1080p stream is available."
                value={autoplay}
                onToggle={toggleAutoplay}
            />
        </div>
    );
}

function ToggleRow({ testid, title, description, value, onToggle }) {
    return (
        <button
            data-testid={`toggle-${testid}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onToggle}
            className="w-full flex items-center justify-between gap-6 text-left"
            style={{
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 16,
                padding: '20px 24px',
            }}
        >
            <div className="flex-1 min-w-0">
                <div
                    style={{
                        fontSize: 18,
                        fontWeight: 600,
                        letterSpacing: '-0.01em',
                        color: 'var(--vesper-text)',
                    }}
                >
                    {title}
                </div>
                <div
                    style={{
                        marginTop: 6,
                        fontSize: 13,
                        lineHeight: 1.5,
                        color: 'var(--vesper-text-2)',
                        maxWidth: '70ch',
                    }}
                >
                    {description}
                </div>
            </div>
            <span
                style={{
                    flex: '0 0 auto',
                    width: 56,
                    height: 32,
                    borderRadius: 999,
                    background: value
                        ? 'var(--vesper-blue)'
                        : 'rgba(255,255,255,0.12)',
                    position: 'relative',
                    transition: 'background 220ms ease',
                }}
            >
                <span
                    style={{
                        position: 'absolute',
                        top: 3,
                        left: value ? 27 : 3,
                        width: 26,
                        height: 26,
                        borderRadius: '50%',
                        background: '#fff',
                        transition: 'left 220ms ease',
                        boxShadow: '0 2px 6px rgba(0,0,0,0.35)',
                    }}
                />
            </span>
        </button>
    );
}

function ThemeCard({ theme, active, onPick }) {
    const p = theme.preview;
    return (
        <button
            data-testid={`theme-${theme.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onPick}
            className="relative text-left overflow-hidden"
            style={{
                aspectRatio: '4 / 3',
                background: p.background,
                borderRadius: 18,
                border: active
                    ? `3px solid ${p.accent}`
                    : '1px solid rgba(255,255,255,0.08)',
                padding: 'clamp(18px, 1.8vw, 28px)',
                color: '#fff',
                boxShadow: active
                    ? `0 0 0 4px ${p.accent}33, 0 30px 60px rgba(0,0,0,0.4)`
                    : '0 20px 40px rgba(0,0,0,0.3)',
                display: 'flex',
                flexDirection: 'column',
                justifyContent: 'space-between',
            }}
        >
            <div>
                <div
                    style={{
                        fontFamily:
                            'var(--theme-font-mono, "JetBrains Mono", monospace)',
                        fontSize: 11,
                        letterSpacing: '0.32em',
                        textTransform: 'uppercase',
                        color: p.accent,
                        marginBottom: 8,
                    }}
                >
                    Theme · {theme.layout}
                </div>
                <div
                    style={{
                        fontFamily: `"${p.wordmark.font}", serif`,
                        fontSize: 'clamp(28px, 2.6vw, 40px)',
                        fontWeight: p.wordmark.weight,
                        color: p.wordmark.color,
                        letterSpacing: '-0.02em',
                        lineHeight: 1,
                    }}
                >
                    {theme.name}
                </div>
            </div>

            <div
                style={{
                    fontSize: 13,
                    lineHeight: 1.45,
                    color: 'rgba(255,255,255,0.85)',
                    maxWidth: '32ch',
                }}
            >
                {theme.tagline}
            </div>

            {/* Faux UI swatches — give a visual sense of the layout */}
            <div className="flex items-end gap-2 mt-3">
                {[1, 2, 3, 4].map((i) => (
                    <div
                        key={i}
                        style={{
                            flex: 1,
                            height: 36 + i * 6,
                            background: i === 1 ? p.accent : 'rgba(255,255,255,0.12)',
                            borderRadius:
                                theme.id === 'arcade' ? 0 : theme.id === 'paper' ? 4 : 8,
                            border:
                                theme.id === 'arcade'
                                    ? `1px solid ${p.accent}66`
                                    : 'none',
                            clipPath:
                                theme.id === 'arcade'
                                    ? 'polygon(4px 0, 100% 0, calc(100% - 4px) 100%, 0 100%)'
                                    : 'none',
                        }}
                    />
                ))}
            </div>

            {active && (
                <div
                    className="absolute"
                    style={{
                        top: 14,
                        right: 14,
                        width: 32,
                        height: 32,
                        borderRadius: '50%',
                        background: p.accent,
                        color: '#fff',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                    }}
                >
                    <Check size={16} strokeWidth={3} />
                </div>
            )}
        </button>
    );
}
