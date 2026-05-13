import React from 'react';
import { useNavigate } from 'react-router-dom';
import { ArrowLeft, Check, Users, ShieldCheck, Code2, ExternalLink } from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import FullscreenButton from '@/components/FullscreenButton';
import { THEMES } from '@/themes/themes';
import { useTheme } from '@/themes/ThemeProvider';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';
import {
    getKidsConfig,
    saveKidsConfig,
    clearActiveProfile,
} from '@/lib/profiles';

/**
 * Settings → Appearance → Theme picker + Playback toggles.
 */
export default function Settings() {
    useSpatialFocus();
    const navigate = useNavigate();
    const { themeId, setThemeId } = useTheme();
    const [autoplay, setAutoplay] = React.useState(getAutoplay1080p());
    const [kidsCfg, setKidsCfgState] = React.useState(getKidsConfig());
    const [savedFlash, setSavedFlash] = React.useState(0);

    const toggleAutoplay = () => {
        const next = !autoplay;
        setAutoplay1080p(next);
        setAutoplay(next);
    };

    const updateKids = (patch) => {
        const next = saveKidsConfig(patch);
        setKidsCfgState(next);
        setSavedFlash((x) => x + 1);
    };

    return (
        <div
            data-testid="settings-page"
            className="relative w-screen"
            style={{
                /* `h-[100dvh] overflow-y-auto` on a single div fails
                   to scroll inside Android 7's WebView (`dvh` is
                   buggy and the container's max-content height
                   defeats the overflow rule).  Use a hard pixel
                   height via 100vh + an inner scroll wrapper, plus
                   ensure D-pad focus auto-scrolls down through the
                   sections. */
                height: '100vh',
                background: 'var(--vesper-bg-0)',
                color: 'var(--vesper-text)',
                fontFamily: 'var(--theme-font-body, "Geist", system-ui, sans-serif)',
                overflow: 'hidden',
            }}
        >
            <FullscreenButton />
            <SavedToast trigger={savedFlash} />
            <div
                data-testid="settings-scroll"
                style={{
                    height: '100%',
                    overflowY: 'auto',
                    overflowX: 'hidden',
                    WebkitOverflowScrolling: 'touch',
                    padding: 'clamp(40px, 5vw, 80px) clamp(40px, 6vw, 96px) 80px',
                }}
            >

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
                Pick the colour that suits your room.  Every theme keeps
                the same cinematic, TV-tuned layout — only the accent
                changes.  Your choice is saved instantly.
            </p>

            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))',
                    gap: 'clamp(12px, 1.1vw, 18px)',
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

            {/* ---- PROFILES ---- */}
            <SectionHeader
                eyebrow="Settings · Profiles"
                title="Who's watching"
                icon={Users}
            />
            <button
                data-testid="switch-profile"
                data-focusable="true"
                data-focus-style="tile"
                tabIndex={0}
                onClick={() => {
                    clearActiveProfile();
                    navigate('/profiles');
                }}
                className="w-full flex items-center justify-between text-left"
                style={{
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.08)',
                    borderRadius: 16,
                    padding: '18px 24px',
                    marginBottom: 16,
                }}
            >
                <div>
                    <div style={{ fontSize: 17, fontWeight: 600 }}>
                        Switch profile
                    </div>
                    <div
                        style={{
                            fontSize: 13,
                            color: 'var(--vesper-text-2)',
                            marginTop: 4,
                        }}
                    >
                        Return to the profile picker
                    </div>
                </div>
                <span
                    className="vesper-mono"
                    style={{
                        fontSize: 11,
                        letterSpacing: '0.22em',
                        color: 'var(--vesper-blue-bright)',
                    }}
                >
                    →
                </span>
            </button>

            {/* ---- KIDS ---- */}
            <SectionHeader
                eyebrow="Settings · Kids mode"
                title="Family controls"
                icon={ShieldCheck}
            />

            <PinRow
                testid="kids-pin"
                pin={kidsCfg.pin}
                onChange={(pin) => updateKids({ pin })}
            />

            <ChoiceRow
                testid="kids-content"
                title="Show in Kids mode"
                description="Which content types appear in the Kids home."
                value={kidsCfg.contentTypes}
                options={[
                    { value: 'both', label: 'Both' },
                    { value: 'movies', label: 'Movies only' },
                    { value: 'series', label: 'TV Shows only' },
                ]}
                onChange={(v) => updateKids({ contentTypes: v })}
            />

            <ChoiceRow
                testid="kids-movie-rating"
                title="Max movie rating"
                description="Movies with this rating or stricter will be shown."
                value={kidsCfg.maxRatingMovie}
                options={[
                    { value: 'G', label: 'G' },
                    { value: 'PG', label: 'PG' },
                    { value: 'PG-13', label: 'PG-13' },
                    { value: 'M15', label: 'M15' },
                ]}
                onChange={(v) => updateKids({ maxRatingMovie: v })}
            />

            <ChoiceRow
                testid="kids-series-rating"
                title="Max TV rating"
                description="TV shows with this rating or stricter will be shown."
                value={kidsCfg.maxRatingSeries}
                options={[
                    { value: 'TV-Y', label: 'TV-Y' },
                    { value: 'TV-Y7', label: 'TV-Y7' },
                    { value: 'TV-G', label: 'TV-G' },
                    { value: 'TV-PG', label: 'TV-PG' },
                    { value: 'TV-14', label: 'TV-14' },
                    { value: 'M15', label: 'M15' },
                ]}
                onChange={(v) => updateKids({ maxRatingSeries: v })}
            />

            {/* ---- DEVELOPER ---- */}
            <SectionHeader
                eyebrow="Settings · Developer"
                title="Live preview"
                icon={Code2}
            />
            <DeveloperPanel />
            </div>
        </div>
    );
}

function DeveloperPanel() {
    const [busy, setBusy] = React.useState(false);
    const PREVIEW_URL =
        'https://rebrand-app-5.preview.emergentagent.com/';

    // Live preview is only useful inside the Android wrapper.  In a
    // desktop browser this panel just explains what it does.
    const isAndroid =
        typeof window !== 'undefined' &&
        !!(window.OnNowTV && window.OnNowTV.setDevUrl);

    const onLoadLive = () => {
        if (busy) return;
        setBusy(true);
        try {
            if (isAndroid) {
                window.OnNowTV.setDevUrl(PREVIEW_URL);
            } else {
                window.location.href = PREVIEW_URL;
            }
        } catch {
            setBusy(false);
        }
    };

    return (
        <div
            data-testid="dev-panel"
            className="w-full"
            style={{
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 16,
                padding: '22px 24px',
                marginBottom: 16,
            }}
        >
            <div style={{ marginBottom: 14 }}>
                <div
                    style={{
                        fontSize: 17,
                        fontWeight: 600,
                        color: 'var(--vesper-text)',
                        marginBottom: 4,
                    }}
                >
                    Load live preview
                </div>
                <div
                    style={{
                        fontSize: 13,
                        color: 'var(--vesper-text-2)',
                        lineHeight: 1.55,
                    }}
                >
                    Switch the app to load the live preview URL
                    instead of the bundled offline copy.  Same
                    fullscreen experience, same D-pad, same native
                    bridges — just always running the very latest
                    build straight from the web.  Tap the pink{' '}
                    <b style={{ color: '#FF6BCB' }}>DEV · Exit</b>{' '}
                    badge in the top-right at any time to return to
                    the bundled app.
                </div>
                <div
                    style={{
                        fontSize: 11,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.02em',
                        marginTop: 8,
                        fontFamily:
                            'var(--theme-font-mono, ui-monospace, monospace)',
                    }}
                >
                    {PREVIEW_URL}
                </div>
            </div>
            <button
                data-testid="dev-load-preview"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onLoadLive}
                disabled={busy}
                className="flex items-center gap-2 rounded-full"
                style={{
                    height: 44,
                    padding: '0 22px',
                    background: 'var(--vesper-blue)',
                    color: 'var(--vesper-bg-0)',
                    border: 'none',
                    fontSize: 14,
                    fontWeight: 700,
                    letterSpacing: '0.01em',
                    cursor: busy ? 'wait' : 'pointer',
                    opacity: busy ? 0.6 : 1,
                }}
            >
                <ExternalLink size={16} strokeWidth={2.4} />
                {busy ? 'Switching…' : 'Load live preview'}
            </button>
            {!isAndroid && (
                <div
                    style={{
                        marginTop: 12,
                        fontSize: 11,
                        color: 'var(--vesper-text-3)',
                    }}
                >
                    (Native bridge not detected — this control only
                    persists across launches inside the Android app.)
                </div>
            )}
        </div>
    );
}

function SectionHeader({ eyebrow, title, icon: Icon }) {
    return (
        <>
            <div
                style={{
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 11,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    marginTop: 56,
                    marginBottom: 8,
                }}
            >
                {eyebrow}
            </div>
            <h2
                style={{
                    fontFamily:
                        'var(--theme-font-display, "Geist", sans-serif)',
                    fontSize: 'clamp(26px, 3vw, 44px)',
                    fontWeight: 700,
                    letterSpacing: '-0.025em',
                    lineHeight: 0.95,
                    marginBottom: 24,
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 14,
                }}
            >
                {Icon && (
                    <Icon
                        size={28}
                        strokeWidth={1.8}
                        style={{ color: 'var(--vesper-blue)' }}
                    />
                )}
                {title}
            </h2>
        </>
    );
}

function ChoiceRow({ testid, title, description, value, options, onChange }) {
    return (
        <div
            style={{
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 16,
                padding: '18px 24px',
                marginBottom: 12,
            }}
        >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 320px' }}>
                    <div style={{ fontSize: 17, fontWeight: 600 }}>{title}</div>
                    <div
                        style={{
                            marginTop: 4,
                            fontSize: 12.5,
                            color: 'var(--vesper-text-2)',
                            lineHeight: 1.45,
                        }}
                    >
                        {description}
                    </div>
                </div>
                <div className="flex gap-2 flex-wrap">
                    {options.map((opt) => {
                        const active = opt.value === value;
                        return (
                            <button
                                key={opt.value}
                                data-testid={`${testid}-${opt.value}`}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => onChange(opt.value)}
                                style={{
                                    height: 38,
                                    padding: '0 16px',
                                    borderRadius: 999,
                                    fontSize: 13,
                                    fontWeight: 600,
                                    background: active
                                        ? 'var(--vesper-blue)'
                                        : 'rgba(255,255,255,0.08)',
                                    color: active
                                        ? 'var(--vesper-bg-0)'
                                        : 'var(--vesper-text-2)',
                                    border: active
                                        ? 'none'
                                        : '1px solid rgba(255,255,255,0.14)',
                                }}
                            >
                                {opt.label}
                            </button>
                        );
                    })}
                </div>
            </div>
        </div>
    );
}

function PinRow({ testid, pin, onChange }) {
    const [editing, setEditing] = React.useState(false);
    const [draft, setDraft] = React.useState('');
    const save = () => {
        if (draft.length === 4 || draft.length === 0) {
            onChange(draft);
            setDraft('');
            setEditing(false);
        }
    };
    return (
        <div
            style={{
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 16,
                padding: '18px 24px',
                marginBottom: 12,
            }}
        >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 320px' }}>
                    <div style={{ fontSize: 17, fontWeight: 600 }}>
                        Parent PIN
                    </div>
                    <div
                        style={{
                            marginTop: 4,
                            fontSize: 12.5,
                            color: 'var(--vesper-text-2)',
                            lineHeight: 1.45,
                        }}
                    >
                        4-digit PIN required to exit Kids mode. Leave blank
                        to disable.
                    </div>
                </div>
                <div className="flex items-center gap-3">
                    {editing ? (
                        <>
                            <input
                                data-testid={`${testid}-input`}
                                data-focusable="true"
                                data-focus-style="pill"
                                type="tel"
                                inputMode="numeric"
                                maxLength={4}
                                value={draft}
                                onChange={(e) =>
                                    setDraft(e.target.value.replace(/\D/g, '').slice(0, 4))
                                }
                                placeholder="••••"
                                className="text-center"
                                style={{
                                    width: 120,
                                    height: 44,
                                    borderRadius: 12,
                                    fontSize: 22,
                                    fontWeight: 700,
                                    letterSpacing: '0.4em',
                                    background: 'rgba(255,255,255,0.06)',
                                    border: '1px solid rgba(255,255,255,0.18)',
                                    color: 'var(--vesper-text)',
                                    outline: 'none',
                                }}
                            />
                            <button
                                data-testid={`${testid}-save`}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={save}
                                style={{
                                    height: 44,
                                    padding: '0 18px',
                                    borderRadius: 999,
                                    fontSize: 14,
                                    fontWeight: 600,
                                    background: 'var(--vesper-blue)',
                                    color: 'var(--vesper-bg-0)',
                                }}
                            >
                                Save
                            </button>
                        </>
                    ) : (
                        <>
                            <span
                                className="vesper-mono"
                                style={{
                                    fontSize: 14,
                                    color: pin
                                        ? 'var(--vesper-blue-bright)'
                                        : 'var(--vesper-text-3)',
                                    letterSpacing: '0.32em',
                                }}
                            >
                                {pin ? '••••' : 'NOT SET'}
                            </span>
                            <button
                                data-testid={`${testid}-edit`}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => {
                                    setDraft('');
                                    setEditing(true);
                                }}
                                style={{
                                    height: 38,
                                    padding: '0 16px',
                                    borderRadius: 999,
                                    fontSize: 13,
                                    fontWeight: 600,
                                    background: 'rgba(255,255,255,0.08)',
                                    color: 'var(--vesper-text-2)',
                                    border: '1px solid rgba(255,255,255,0.14)',
                                }}
                            >
                                {pin ? 'Change' : 'Set PIN'}
                            </button>
                        </>
                    )}
                </div>
            </div>
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
                aspectRatio: '5 / 4',
                background: p.background,
                borderRadius: 14,
                border: active
                    ? `2px solid ${p.accent}`
                    : '1px solid rgba(255,255,255,0.08)',
                padding: 'clamp(12px, 1.1vw, 18px)',
                color: '#fff',
                boxShadow: active
                    ? `0 0 0 3px ${p.accent}33, 0 18px 36px rgba(0,0,0,0.4)`
                    : '0 12px 24px rgba(0,0,0,0.3)',
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
                        fontSize: 9,
                        letterSpacing: '0.28em',
                        textTransform: 'uppercase',
                        color: p.accent,
                        marginBottom: 5,
                    }}
                >
                    Theme · {theme.layout}
                </div>
                <div
                    style={{
                        fontFamily: `"${p.wordmark.font}", serif`,
                        fontSize: 'clamp(18px, 1.6vw, 26px)',
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
                    fontSize: 11,
                    lineHeight: 1.35,
                    color: 'rgba(255,255,255,0.78)',
                    maxWidth: '28ch',
                }}
            >
                {theme.tagline}
            </div>

            {/* Faux UI swatches — give a visual sense of the layout */}
            <div className="flex items-end gap-1.5 mt-2">
                {[1, 2, 3, 4].map((i) => (
                    <div
                        key={i}
                        style={{
                            flex: 1,
                            height: 22 + i * 4,
                            background: i === 1 ? p.accent : 'rgba(255,255,255,0.12)',
                            borderRadius:
                                theme.id === 'arcade' ? 0 : theme.id === 'paper' ? 3 : 6,
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
                        top: 10,
                        right: 10,
                        width: 24,
                        height: 24,
                        borderRadius: '50%',
                        background: p.accent,
                        color: '#fff',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                    }}
                >
                    <Check size={12} strokeWidth={3} />
                </div>
            )}
        </button>
    );
}


function SavedToast({ trigger }) {
    const [visible, setVisible] = React.useState(false);
    React.useEffect(() => {
        if (!trigger) return undefined;
        setVisible(true);
        const t = setTimeout(() => setVisible(false), 1600);
        return () => clearTimeout(t);
    }, [trigger]);
    if (!visible) return null;
    return (
        <div
            data-testid="saved-toast"
            className="fixed z-[60] flex items-center gap-2"
            style={{
                bottom: 'clamp(24px, 3vw, 40px)',
                left: '50%',
                transform: 'translateX(-50%)',
                padding: '12px 22px',
                borderRadius: 999,
                background: 'rgba(20,28,48,0.95)',
                border: '1px solid rgba(93,200,255,0.45)',
                color: 'var(--vesper-blue-bright)',
                fontSize: 14,
                fontWeight: 600,
                letterSpacing: '0.01em',
                boxShadow: '0 14px 36px rgba(0,0,0,0.45), 0 0 24px rgba(93,200,255,0.35)',
            }}
        >
            <Check size={16} strokeWidth={3} />
            Saved — Kids home updated
        </div>
    );
}
