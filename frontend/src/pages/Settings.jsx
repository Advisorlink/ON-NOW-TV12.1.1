import React from 'react';
import { useNavigate } from 'react-router-dom';
import {
    ArrowLeft, Check, Users, ShieldCheck, Code2, ExternalLink,
    Cloud, Download, Upload, Copy, Loader2, KeyRound, AlertTriangle,
    Sparkles,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';
import FullscreenButton from '@/components/FullscreenButton';
import { THEMES } from '@/themes/themes';
import { useTheme } from '@/themes/ThemeProvider';
import { getAutoplay1080p, setAutoplay1080p } from '@/lib/prefs';
import {
    getKidsConfig,
    saveKidsConfig,
    clearActiveProfile,
} from '@/lib/profiles';
import {
    collectBackupPayload,
    applyBackupPayload,
    fmtBytes,
} from '@/lib/profileBackup';
import { replayOnboarding } from '@/components/Onboarding';

/**
 * Settings → Appearance → Theme picker + Playback toggles.
 */
export default function Settings() {
    useSpatialFocus();
    // BACK from remote → return to Home.
    useBackHandler('/');
    const navigate = useNavigate();
    const { themeId, setThemeId } = useTheme();
    const [autoplay, setAutoplay] = React.useState(getAutoplay1080p());
    const [kidsCfg, setKidsCfgState] = React.useState(getKidsConfig());
    const [savedFlash, setSavedFlash] = React.useState(0);
    /* Developer "Unlock" toggle — testing aid that surfaces diagnostic
     * info on the Home page's Upcoming row + any other testing
     * surfaces.  Lives in localStorage so it survives reloads.  Read
     * elsewhere with `localStorage.getItem('onnowtv-dev-unlock') === '1'`. */
    const [devUnlock, setDevUnlock] = React.useState(() => {
        try { return localStorage.getItem('onnowtv-dev-unlock') === '1'; }
        catch { return false; }
    });
    const toggleDevUnlock = React.useCallback(() => {
        setDevUnlock((cur) => {
            const next = !cur;
            try {
                localStorage.setItem('onnowtv-dev-unlock', next ? '1' : '0');
            } catch { /* ignore */ }
            window.dispatchEvent(new Event('onnowtv:dev-unlock-changed'));
            return next;
        });
    }, []);

    /* v2.6.71: Update gate's "Back up first" button stashes
       sessionStorage.vesper-settings-jump-to = 'backup' before
       navigating here.  On mount we scroll the user straight to the
       backup section + focus its first action so they can save a
       backup before installing a new APK. */
    React.useEffect(() => {
        let target = '';
        try { target = sessionStorage.getItem('vesper-settings-jump-to') || ''; }
        catch { /* private mode */ }
        if (target !== 'backup') return;
        try { sessionStorage.removeItem('vesper-settings-jump-to'); }
        catch { /* ignore */ }
        // Defer to next frame so the layout has settled.
        const t = setTimeout(() => {
            const anchor = document.getElementById('backup-section');
            if (anchor) {
                anchor.scrollIntoView({ behavior: 'smooth', block: 'start' });
                // Focus the first focusable inside the backup panel
                // so OK on the remote / mouse activates the save flow
                // immediately.
                const focusable = document.querySelector(
                    '#backup-section ~ * [data-focusable="true"]'
                ) || document.querySelector('[data-testid="backup-save-btn"]');
                if (focusable) focusable.focus({ preventScroll: true });
            }
        }, 250);
        return () => clearTimeout(t);
    }, []);

    // ROW-aware vertical navigation override for Settings.
    //
    // The user's spec is dead simple: Down/Up must jump to the
    // next VISUAL LINE — never sideways.  A choice row contains
    // several pills (G / PG / PG-13 / M15) on the same horizontal
    // line; pressing Down from one of those pills must skip past
    // every sibling on the same line and land on the first
    // focusable of the next row down.  Same logic in reverse for
    // Up.  Capture-phase listener so we beat useSpatialFocus on
    // this page only.
    React.useEffect(() => {
        const root = document.querySelector('[data-testid="settings-scroll"]');
        if (!root) return undefined;

        const onKey = (e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            const active = document.activeElement;
            if (!active || !root.contains(active)) return;

            const list = Array.from(
                root.querySelectorAll('[data-focusable="true"]')
            ).filter((el) => {
                if (el.hasAttribute('disabled')) return false;
                const r = el.getBoundingClientRect();
                return r.width > 0 && r.height > 0;
            });
            if (list.length === 0) return;

            const curRect = active.getBoundingClientRect();
            const curCenterX = curRect.left + curRect.width / 2;
            const ROW_TOL = 6; // px — items within this Y tolerance count as "same row"

            // Score every candidate and keep the geometrically
            // closest one that sits on a DIFFERENT row in the
            // requested direction.
            let best = null;
            let bestScore = Infinity;
            for (const el of list) {
                if (el === active) continue;
                const r = el.getBoundingClientRect();
                if (e.key === 'ArrowDown') {
                    // Must start strictly BELOW the current row.
                    if (r.top < curRect.bottom - ROW_TOL) continue;
                } else {
                    // ArrowUp — must end strictly ABOVE current row.
                    if (r.bottom > curRect.top + ROW_TOL) continue;
                }
                const elCenterX = r.left + r.width / 2;
                const dy =
                    e.key === 'ArrowDown'
                        ? r.top - curRect.bottom
                        : curRect.top - r.bottom;
                const dx = Math.abs(elCenterX - curCenterX);
                // Strongly prefer vertical proximity; use horizontal
                // distance as tiebreaker so the column the user is
                // in is preserved when possible.
                const score = Math.max(0, dy) * 4 + dx;
                if (score < bestScore) {
                    bestScore = score;
                    best = el;
                }
            }
            if (!best) return;

            e.preventDefault();
            e.stopPropagation();
            try { best.focus({ preventScroll: false }); } catch (err) { /* ignore */ }
            best.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== best) el.removeAttribute('data-focused');
                });
            try {
                best.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
            } catch (err) { /* ignore */ }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, []);

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
                the same cinematic, TV-tuned layout; only the accent
                changes.  Your choice is saved instantly.
            </p>

            <div
                style={{
                    display: 'grid',
                    gridTemplateColumns: 'repeat(auto-fill, minmax(200px, 1fr))',
                    gap: 'clamp(12px, 1.1vw, 18px)',
                }}
            >
                {THEMES.map((t, i) => (
                    <ThemeCard
                        key={t.id}
                        theme={t}
                        active={themeId === t.id}
                        initialFocus={i === 0}
                        onPick={() => setThemeId(t.id)}
                    />
                ))}
            </div>

            {/* ---- Playback section ---- */}
            <div
                style={{
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 10,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    marginTop: 56,
                    marginBottom: 6,
                }}
            >
                Settings · Playback
            </div>
            <h2
                style={{
                    fontFamily: 'var(--theme-font-display, "Geist", sans-serif)',
                    fontSize: 'clamp(20px, 1.8vw, 28px)',
                    fontWeight: 700,
                    letterSpacing: '-0.02em',
                    lineHeight: 1,
                    marginBottom: 18,
                }}
            >
                Streams
            </h2>

            <ToggleRow
                testid="autoplay-1080p"
                title="Auto play"
                description="Skip the sources list and instantly play the best available stream when you press Play.  Falls back to the source picker if nothing playable is available."
                value={autoplay}
                onToggle={toggleAutoplay}
            />

            <ToggleRow
                testid="dev-unlock"
                title="Unlock (testing)"
                description="Reveal diagnostic info on the home Upcoming row and any hidden testing surfaces.  Use this if something looks missing — the row will surface its API status so you can tell whether the backend has the endpoint deployed."
                value={devUnlock}
                onToggle={toggleDevUnlock}
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
                    borderRadius: 14,
                    padding: '14px 18px',
                    marginBottom: 12,
                }}
            >
                <div>
                    <div style={{ fontSize: 14, fontWeight: 600 }}>
                        Switch profile
                    </div>
                    <div
                        style={{
                            fontSize: 11.5,
                            color: 'var(--vesper-text-2)',
                            marginTop: 3,
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

            {/* ---- WELCOME TOUR ---- */}
            <SectionHeader
                eyebrow="Settings · Help"
                title="Welcome tour"
                icon={Sparkles}
            />
            <div
                data-testid="onboarding-replay-row"
                className="vesper-glass rounded-2xl flex items-center gap-4"
                style={{ padding: '18px 22px', marginBottom: 18 }}
            >
                <div
                    className="flex items-center justify-center shrink-0"
                    style={{
                        width: 44, height: 44, borderRadius: '50%',
                        background:
                            'linear-gradient(135deg, rgba(93,200,255,0.28) 0%, rgba(93,200,255,0.06) 100%)',
                        border: '1px solid rgba(93,200,255,0.45)',
                    }}
                >
                    <Sparkles size={20} style={{ color: 'var(--vesper-blue-bright)' }} />
                </div>
                <div className="flex-1 min-w-0">
                    <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--vesper-text)' }}>
                        Replay welcome tour
                    </div>
                    <div style={{ fontSize: 13, color: 'var(--vesper-text-2)', marginTop: 2 }}>
                        Walk through every feature again: D-pad controls, library, calendar, watch together and more.
                    </div>
                </div>
                <button
                    data-testid="settings-replay-onboarding"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => replayOnboarding()}
                    className="flex items-center gap-2 rounded-full font-sans shrink-0"
                    style={{
                        padding: '10px 22px',
                        background:
                            'linear-gradient(135deg, var(--vesper-blue) 0%, #4FB8F0 100%)',
                        color: '#06080F',
                        border: 'none',
                        fontSize: 14,
                        fontWeight: 700,
                        cursor: 'pointer',
                        boxShadow: '0 6px 18px rgba(93,200,255,0.35)',
                    }}
                >
                    <Sparkles size={15} />
                    Replay
                </button>
            </div>

            {/* ---- BACKUP & RESTORE ---- */}
            <SectionHeader
                eyebrow="Settings · Account"
                title="Backup &amp; Restore"
                icon={ShieldCheck}
                anchorId="backup-section"
            />
            <BackupPanel />

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

function BackupPanel() {
    const navigate = useNavigate();
    const API = process.env.REACT_APP_BACKEND_URL;
    const [mode, setMode] = React.useState('idle'); // idle | save-pin | save-result | restore-code | restore-pin | restore-confirm
    const [pin, setPin] = React.useState('');
    const [code, setCode] = React.useState('');
    const [busy, setBusy] = React.useState(false);
    const [err, setErr] = React.useState('');
    const [result, setResult] = React.useState(null);   // {code, expires_at, size_bytes}
    const [restorePreview, setRestorePreview] = React.useState(null);

    const reset = React.useCallback(() => {
        setMode('idle');
        setPin('');
        setCode('');
        setErr('');
        setResult(null);
        setRestorePreview(null);
    }, []);

    const handleStartSave = () => {
        setPin('');
        setErr('');
        setMode('save-pin');
    };

    const handleStartRestore = () => {
        setCode('');
        setPin('');
        setErr('');
        setMode('restore-code');
    };

    const doSave = async (pinDigits) => {
        setBusy(true);
        setErr('');
        try {
            const payload = collectBackupPayload();
            const res = await fetch(`${API}/api/backup/save`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ payload, pin: pinDigits }),
            });
            if (!res.ok) {
                const j = await res.json().catch(() => ({}));
                throw new Error(j.detail || `Save failed (HTTP ${res.status}).`);
            }
            const j = await res.json();
            setResult(j);
            setMode('save-result');
        } catch (e) {
            setErr(e.message || 'Could not save backup.');
            setMode('save-pin');
        } finally {
            setBusy(false);
        }
    };

    const doFetchRestore = async (pinDigits) => {
        setBusy(true);
        setErr('');
        try {
            const res = await fetch(`${API}/api/backup/restore`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ code, pin: pinDigits }),
            });
            if (!res.ok) {
                const j = await res.json().catch(() => ({}));
                throw new Error(j.detail || `Restore failed (HTTP ${res.status}).`);
            }
            const j = await res.json();
            setRestorePreview(j);
            setMode('restore-confirm');
        } catch (e) {
            setErr(e.message || 'Could not fetch backup.');
            setMode('restore-pin');
        } finally {
            setBusy(false);
        }
    };

    const doApplyRestore = async () => {
        if (!restorePreview?.payload) return;
        applyBackupPayload(restorePreview.payload);
        // Hard reload so every page re-reads the new localStorage.
        try { clearActiveProfile(); } catch { /* ignore */ }
        setTimeout(() => {
            window.location.href = '/';
        }, 250);
    };

    // PIN entry pinpad reused for both save + restore PIN screens.
    const onPinComplete = (digits) => {
        if (mode === 'save-pin') doSave(digits);
        else if (mode === 'restore-pin') doFetchRestore(digits);
    };

    return (
        <div
            data-testid="backup-panel"
            style={{
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 16,
                padding: '22px 24px',
                marginBottom: 16,
            }}
        >
            {mode === 'idle' && (
                <div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 6 }}>
                        <Cloud size={18} style={{ color: 'var(--vesper-blue)' }} />
                        <div style={{ fontSize: 17, fontWeight: 600 }}>
                            Save your profile to the cloud
                        </div>
                    </div>
                    <div style={{ fontSize: 13, color: 'var(--vesper-text-2, #9DA5B5)',
                                    marginBottom: 18, maxWidth: 640 }}>
                        Lock in everything: your profiles, Continue Watching,
                        libraries, favourites, Live TV setup, reminders, theme.
                        You get a 6-character code locked with a 4-digit PIN.
                        Re-install the app or set up a new TV box, just enter
                        the code and PIN to get everything back exactly how you
                        left it.
                    </div>
                    <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                        <BigBtn testid="backup-save-btn" tone="primary"
                                icon={Upload} label="Save backup"
                                onClick={handleStartSave} />
                        <BigBtn testid="backup-restore-btn" tone="ghost"
                                icon={Download} label="Restore from code"
                                onClick={handleStartRestore} />
                    </div>
                </div>
            )}

            {mode === 'save-pin' && (
                <PinStep
                    title="Choose a 4-digit PIN"
                    subtitle="You'll need this PIN to restore later.  Pick something you can remember; there's no way to recover a forgotten PIN."
                    pin={pin}
                    setPin={setPin}
                    onComplete={onPinComplete}
                    onCancel={reset}
                    busy={busy}
                    err={err}
                />
            )}

            {mode === 'save-result' && result && (
                <SaveResult result={result} onDone={reset} />
            )}

            {mode === 'restore-code' && (
                <CodeStep
                    code={code}
                    setCode={setCode}
                    onNext={() => { setErr(''); setMode('restore-pin'); }}
                    onCancel={reset}
                    err={err}
                />
            )}

            {mode === 'restore-pin' && (
                <PinStep
                    title="Enter your PIN"
                    subtitle={`PIN for backup code ${code}.  4 digits.`}
                    pin={pin}
                    setPin={setPin}
                    onComplete={onPinComplete}
                    onCancel={reset}
                    busy={busy}
                    err={err}
                />
            )}

            {mode === 'restore-confirm' && restorePreview && (
                <RestoreConfirm
                    preview={restorePreview}
                    onConfirm={doApplyRestore}
                    onCancel={reset}
                />
            )}
        </div>
    );
}

function BigBtn({ testid, tone, icon: Icon, label, onClick }) {
    const primary = tone === 'primary';
    return (
        <button
            data-testid={testid}
            data-focusable="true"
            tabIndex={0}
            onClick={onClick}
            style={{
                display: 'inline-flex', alignItems: 'center', gap: 10,
                padding: '12px 22px', borderRadius: 12,
                border: primary
                    ? '1px solid rgba(93,200,255,0.55)'
                    : '1px solid rgba(255,255,255,0.16)',
                background: primary
                    ? 'rgba(93,200,255,0.18)'
                    : 'rgba(255,255,255,0.04)',
                color: '#FFFFFF',
                fontSize: 14, fontWeight: 700, letterSpacing: '0.02em',
                cursor: 'pointer', outline: 'none',
            }}
        >
            <Icon size={16} />
            {label}
        </button>
    );
}

function PinStep({ title, subtitle, pin, setPin, onComplete, onCancel, busy, err }) {
    const onDigit = (d) => {
        if (busy) return;
        if (pin.length >= 4) return;
        const next = pin + d;
        setPin(next);
        if (next.length === 4) {
            // Defer so React can paint the 4th dot before the request fires.
            setTimeout(() => onComplete(next), 100);
        }
    };
    const onBack = () => {
        if (busy) return;
        setPin(pin.slice(0, -1));
    };
    return (
        <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 6 }}>
                <KeyRound size={18} style={{ color: 'var(--vesper-blue)' }} />
                <div style={{ fontSize: 17, fontWeight: 600 }}>{title}</div>
            </div>
            <div style={{ fontSize: 13, color: 'var(--vesper-text-2, #9DA5B5)',
                            marginBottom: 18, maxWidth: 640 }}>
                {subtitle}
            </div>
            <PinDots pin={pin} />
            {err && (
                <div style={{ display: 'inline-flex', alignItems: 'center', gap: 6,
                                marginTop: 14, padding: '8px 12px', borderRadius: 8,
                                background: 'rgba(255,107,122,0.16)',
                                border: '1px solid rgba(255,107,122,0.45)',
                                color: '#FF8896', fontSize: 12, fontWeight: 600 }}>
                    <AlertTriangle size={13} />
                    {err}
                </div>
            )}
            <div style={{ marginTop: 18 }}>
                <Pinpad onDigit={onDigit} onBack={onBack} onCancel={onCancel} busy={busy} />
            </div>
        </div>
    );
}

function PinDots({ pin }) {
    return (
        <div style={{ display: 'flex', gap: 14 }}>
            {[0, 1, 2, 3].map((i) => {
                const filled = i < pin.length;
                return (
                    <div key={i}
                        style={{
                            width: 22, height: 22, borderRadius: '50%',
                            background: filled
                                ? 'var(--vesper-blue, #5DC8FF)'
                                : 'rgba(255,255,255,0.05)',
                            border: filled
                                ? '1px solid var(--vesper-blue, #5DC8FF)'
                                : '1px solid rgba(255,255,255,0.18)',
                            transition: 'background 0.12s, border 0.12s',
                        }} />
                );
            })}
        </div>
    );
}

function Pinpad({ onDigit, onBack, onCancel, busy }) {
    const rows = [
        ['1', '2', '3'],
        ['4', '5', '6'],
        ['7', '8', '9'],
        ['cancel', '0', 'back'],
    ];
    return (
        <div style={{ display: 'inline-grid',
                        gridTemplateColumns: 'repeat(3, 70px)',
                        gap: 8 }}>
            {rows.flat().map((k) => {
                if (k === 'cancel') {
                    return (
                        <PinKey key="cancel" label="Cancel" onClick={onCancel} busy={busy} testid="backup-pin-cancel" />
                    );
                }
                if (k === 'back') {
                    return (
                        <PinKey key="back" label="⌫" onClick={onBack} busy={busy} testid="backup-pin-back" />
                    );
                }
                return (
                    <PinKey key={k} label={k}
                            onClick={() => onDigit(k)} busy={busy}
                            testid={`backup-pin-${k}`} />
                );
            })}
        </div>
    );
}

function PinKey({ label, onClick, busy, testid }) {
    return (
        <button
            data-testid={testid}
            data-focusable="true"
            tabIndex={0}
            disabled={busy}
            onClick={onClick}
            style={{
                width: 70, height: 56, borderRadius: 12,
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.10)',
                color: '#FFFFFF',
                fontFamily: 'monospace', fontSize: 18, fontWeight: 700,
                cursor: busy ? 'not-allowed' : 'pointer', outline: 'none',
                opacity: busy ? 0.5 : 1,
                transition: 'background 0.12s, border 0.12s',
            }}
            onFocus={(e) => { e.currentTarget.style.background = 'rgba(93,200,255,0.18)'; e.currentTarget.style.borderColor = 'rgba(93,200,255,0.55)'; }}
            onBlur={(e) => { e.currentTarget.style.background = 'rgba(255,255,255,0.04)'; e.currentTarget.style.borderColor = 'rgba(255,255,255,0.10)'; }}
        >
            {label}
        </button>
    );
}

function SaveResult({ result, onDone }) {
    const [copied, setCopied] = React.useState(false);
    const onCopy = () => {
        try {
            navigator.clipboard.writeText(result.code).then(() => {
                setCopied(true);
                setTimeout(() => setCopied(false), 2000);
            });
        } catch { /* ignore */ }
    };
    return (
        <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                <Check size={20} style={{ color: '#7AE2A8' }} />
                <div style={{ fontSize: 17, fontWeight: 700 }}>Backup saved</div>
            </div>
            <div style={{ fontSize: 13, color: 'var(--vesper-text-2, #9DA5B5)',
                            marginBottom: 20, maxWidth: 640 }}>
                Write this code down and remember your PIN.  You'll need both
                to restore.  Expires {result.expires_at?.slice(0, 10)}.
            </div>
            <div data-testid="backup-saved-code"
                 style={{
                    display: 'inline-flex', alignItems: 'center', gap: 14,
                    padding: '18px 26px',
                    background: 'rgba(93,200,255,0.12)',
                    border: '1px solid rgba(93,200,255,0.40)',
                    borderRadius: 14,
                    fontFamily: 'monospace', fontSize: 'clamp(28px, 3vw, 44px)',
                    fontWeight: 800, letterSpacing: '0.2em', color: '#FFFFFF',
                 }}>
                {result.code}
            </div>
            <div style={{ marginTop: 18, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <BigBtn testid="backup-copy-code" tone="ghost" icon={copied ? Check : Copy}
                        label={copied ? 'Copied' : 'Copy code'} onClick={onCopy} />
                <BigBtn testid="backup-done" tone="primary" icon={Check}
                        label="Done" onClick={onDone} />
            </div>
            <div style={{ marginTop: 14, fontSize: 12,
                            color: 'var(--vesper-text-2, #9DA5B5)' }}>
                Backup size: {fmtBytes(result.size_bytes)}
            </div>
        </div>
    );
}

function CodeStep({ code, setCode, onNext, onCancel, err }) {
    const onChange = (e) => {
        const v = e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 6);
        setCode(v);
    };
    return (
        <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 6 }}>
                <Download size={18} style={{ color: 'var(--vesper-blue)' }} />
                <div style={{ fontSize: 17, fontWeight: 600 }}>Enter your backup code</div>
            </div>
            <div style={{ fontSize: 13, color: 'var(--vesper-text-2, #9DA5B5)',
                            marginBottom: 18, maxWidth: 640 }}>
                6 characters, uppercase letters and numbers.
            </div>
            <input
                data-testid="backup-code-input"
                data-focusable="true"
                tabIndex={0}
                autoFocus
                value={code}
                onChange={onChange}
                placeholder="ABCXYZ"
                maxLength={6}
                style={{
                    width: 'min(420px, 90vw)',
                    padding: '14px 18px',
                    borderRadius: 12,
                    background: 'rgba(255,255,255,0.04)',
                    border: '1px solid rgba(255,255,255,0.16)',
                    color: '#FFFFFF',
                    fontFamily: 'monospace',
                    fontSize: 26, fontWeight: 800,
                    letterSpacing: '0.22em',
                    textAlign: 'center',
                    outline: 'none',
                }}
            />
            {err && (
                <div style={{ marginTop: 12, fontSize: 12, color: '#FF8896', fontWeight: 600 }}>
                    {err}
                </div>
            )}
            <div style={{ marginTop: 18, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <BigBtn testid="backup-restore-cancel" tone="ghost" icon={ArrowLeft}
                        label="Cancel" onClick={onCancel} />
                <BigBtn testid="backup-restore-next" tone="primary" icon={KeyRound}
                        label="Next" onClick={() => code.length === 6 && onNext()} />
            </div>
        </div>
    );
}

function RestoreConfirm({ preview, onConfirm, onCancel }) {
    const created = preview?.created_at?.slice(0, 10) || '–';
    return (
        <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                <Check size={20} style={{ color: '#7AE2A8' }} />
                <div style={{ fontSize: 17, fontWeight: 700 }}>Backup found</div>
            </div>
            <div style={{ fontSize: 13, color: 'var(--vesper-text-2, #9DA5B5)',
                            marginBottom: 20, maxWidth: 640 }}>
                Created on <strong style={{ color: '#FFFFFF' }}>{created}</strong>.
                Restoring will overwrite this device's current profiles,
                Continue Watching, libraries, themes, everything.  The app
                will reload once the restore is applied.
            </div>
            <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
                <BigBtn testid="backup-restore-cancel-2" tone="ghost" icon={ArrowLeft}
                        label="Cancel" onClick={onCancel} />
                <BigBtn testid="backup-restore-apply" tone="primary" icon={Download}
                        label="Restore and reload" onClick={onConfirm} />
            </div>
            <div style={{ marginTop: 14, fontSize: 12,
                            color: 'var(--vesper-text-2, #9DA5B5)' }}>
                Backup size: {fmtBytes(preview.size_bytes || 0)}
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
                    bridges, just always running the very latest
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
                    (Native bridge not detected; this control only
                    persists across launches inside the Android app.)
                </div>
            )}

            {/* New-episode notification test trigger.  Fires a
                synthesized toast so you can verify the look + the
                "Watch Later" → rail flow without waiting for a real
                episode to air. */}
            <TestNewEpisodeButton />
        </div>
    );
}

function TestNewEpisodeButton() {
    const SAMPLES = [
        {
            showId: 'tt0944947',
            showMeta: {
                name: 'Game of Thrones',
                poster: 'https://image.tmdb.org/t/p/w500/u3bZgnGQ9T01Hi2I9w0pVTuvqVj.jpg',
                background: 'https://image.tmdb.org/t/p/w1280/2OMB0ynKlyIenMJWI2Dy9IWT4c.jpg',
            },
            episode: {
                season: 8,
                number: 6,
                name: 'The Iron Throne',
                aired: '2019-05-19',
                thumbnail: 'https://image.tmdb.org/t/p/w500/zb6fM1CX41D9rF9hdgclu0peUmy.jpg',
            },
        },
        {
            showId: 'tt4574334',
            showMeta: {
                name: 'Stranger Things',
                poster: 'https://image.tmdb.org/t/p/w500/49WJfeN0moxb9IPfGn8AIqMGskD.jpg',
                background: 'https://image.tmdb.org/t/p/w1280/56v2KjBlU4XaOv9rVYEQypROD7P.jpg',
            },
            episode: {
                season: 4,
                number: 9,
                name: 'The Piggyback',
                aired: '2022-07-01',
                thumbnail: 'https://image.tmdb.org/t/p/w500/agQRbX4mU3yEbU3PCK7vGS0YPpA.jpg',
            },
        },
        {
            showId: 'tt7366338',
            showMeta: {
                name: 'Chernobyl',
                poster: 'https://image.tmdb.org/t/p/w500/hlLXt2tOPT6RRnjiUmoxyG1LTFi.jpg',
                background: 'https://image.tmdb.org/t/p/w1280/jzAEXMRkfFp4S5RGfvKGOL9z76m.jpg',
            },
            episode: {
                season: 1,
                number: 5,
                name: 'Vichnaya Pamyat',
                aired: '2019-06-03',
                thumbnail: 'https://image.tmdb.org/t/p/w500/y5fAuLBQ4FYxsDi0SwHFkXOzZqK.jpg',
            },
        },
    ];

    const onFire = () => {
        // Pick a random sample so a clicker can stack a few in the
        // Watch Later rail just by tapping the button repeatedly.
        const s = SAMPLES[Math.floor(Math.random() * SAMPLES.length)];
        // Tag with a unique season+number suffix so each click
        // qualifies as a "new" episode even if you keep firing the
        // same sample.
        const seasonBump = Math.floor(Math.random() * 99) + 1;
        const payload = {
            ...s,
            episode: { ...s.episode, number: seasonBump },
        };
        window.dispatchEvent(
            new CustomEvent('vesper:new-episode-test', { detail: payload })
        );
    };

    return (
        <div
            style={{
                marginTop: 24,
                paddingTop: 22,
                borderTop: '1px dashed rgba(255,255,255,0.1)',
            }}
        >
            <div
                style={{
                    fontSize: 15,
                    fontWeight: 600,
                    color: 'var(--vesper-text)',
                    marginBottom: 4,
                }}
            >
                Fire test notification
            </div>
            <div
                style={{
                    fontSize: 13,
                    color: 'var(--vesper-text-2)',
                    lineHeight: 1.55,
                    marginBottom: 12,
                }}
            >
                Pops a fake "new episode" toast in the top-right
                corner so you can practise the Play / Watch Later
                flow without waiting for real episodes to air.  Tap
                repeatedly to stack the Watch Later rail in My
                Library.
            </div>
            <button
                data-testid="dev-fire-test-toast"
                data-focusable="true"
                data-focus-style="pill"
                tabIndex={0}
                onClick={onFire}
                className="flex items-center gap-2 rounded-full"
                style={{
                    height: 44,
                    padding: '0 22px',
                    background: 'rgba(var(--vesper-blue-rgb), 0.14)',
                    color: 'var(--vesper-blue-bright)',
                    border: '1px solid rgba(var(--vesper-blue-rgb), 0.45)',
                    fontSize: 14,
                    fontWeight: 600,
                    letterSpacing: '0.01em',
                }}
            >
                Fire test notification
            </button>
        </div>
    );
}

function SectionHeader({ eyebrow, title, icon: Icon, anchorId }) {
    return (
        <>
            <div
                id={anchorId}
                style={{
                    fontFamily: 'var(--theme-font-mono, monospace)',
                    fontSize: 10,
                    letterSpacing: '0.32em',
                    textTransform: 'uppercase',
                    color: 'var(--theme-accent, var(--vesper-blue))',
                    marginTop: 44,
                    marginBottom: 6,
                    scrollMarginTop: 80,
                }}
            >
                {eyebrow}
            </div>
            <h2
                style={{
                    fontFamily:
                        'var(--theme-font-display, "Geist", sans-serif)',
                    fontSize: 'clamp(20px, 1.8vw, 28px)',
                    fontWeight: 700,
                    letterSpacing: '-0.02em',
                    lineHeight: 1,
                    marginBottom: 18,
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 10,
                }}
            >
                {Icon && (
                    <Icon
                        size={20}
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
                borderRadius: 14,
                padding: '14px 18px',
                marginBottom: 10,
            }}
        >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 14, flexWrap: 'wrap' }}>
                <div style={{ flex: '1 1 260px' }}>
                    <div style={{ fontSize: 14, fontWeight: 600 }}>{title}</div>
                    <div
                        style={{
                            marginTop: 3,
                            fontSize: 11.5,
                            color: 'var(--vesper-text-2)',
                            lineHeight: 1.4,
                        }}
                    >
                        {description}
                    </div>
                </div>
                <div className="flex gap-1.5 flex-wrap">
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
                                    height: 32,
                                    padding: '0 13px',
                                    borderRadius: 999,
                                    fontSize: 12,
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
            className="w-full flex items-center justify-between gap-5 text-left"
            style={{
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 14,
                padding: '14px 18px',
            }}
        >
            <div className="flex-1 min-w-0">
                <div
                    style={{
                        fontSize: 14,
                        fontWeight: 600,
                        letterSpacing: '-0.01em',
                        color: 'var(--vesper-text)',
                    }}
                >
                    {title}
                </div>
                <div
                    style={{
                        marginTop: 4,
                        fontSize: 11.5,
                        lineHeight: 1.45,
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
                    width: 44,
                    height: 26,
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
                        left: value ? 21 : 3,
                        width: 20,
                        height: 20,
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

function ThemeCard({ theme, active, initialFocus, onPick }) {
    const p = theme.preview;
    return (
        <button
            data-testid={`theme-${theme.id}`}
            data-focusable="true"
            data-focus-style="tile"
            {...(initialFocus ? { 'data-initial-focus': 'true' } : {})}
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
                border: '1px solid rgba(var(--vesper-blue-rgb),0.45)',
                color: 'var(--vesper-blue-bright)',
                fontSize: 14,
                fontWeight: 600,
                letterSpacing: '0.01em',
                boxShadow: '0 14px 36px rgba(0,0,0,0.45), 0 0 24px rgba(var(--vesper-blue-rgb),0.35)',
            }}
        >
            <Check size={16} strokeWidth={3} />
            Saved · Kids home updated
        </div>
    );
}
