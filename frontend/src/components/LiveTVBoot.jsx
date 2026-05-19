import React from 'react';

/**
 * <LiveTVBoot/> — minimalist Live TV bootstrap splash.
 *
 * REDESIGN (v2.6.82) — replaces the old multi-stage / X-of-50 counter
 * splash.  Now that the backend serves a pre-warmed bundle of all
 * 14k channels + EPG in one gzipped request, the per-stage counters
 * were misleading ("0/50" while in reality 14,220 channels were
 * already being seeded) and the centerpiece ring overlapped the
 * percentage text on certain TV resolutions.
 *
 * This minimal version is:
 *   • One slim spinning ring (no text behind it)
 *   • Centred brand mark
 *   • A single subtle status line ("Tuning in…")
 *   • A SKIP affordance after 10s so a stuck network can't trap you
 *
 * Props kept for backwards-compat with the LiveTV.jsx call-site:
 *   stages, counters, bootTarget — all currently ignored.  The
 *   splash itself is dumb; the LiveTV page decides when to dismiss
 *   it by flipping `bootBlocked = false`.
 */

export default function LiveTVBoot({ onSkip }) {
    return (
        <div
            data-testid="live-tv-boot"
            style={{
                position: 'absolute', inset: 0,
                background:
                    'radial-gradient(ellipse 1200px 700px at 50% 35%, ' +
                        'rgba(93,200,255,0.10) 0%, transparent 60%),' +
                    '#06080F',
                color: '#E6EAF2',
                display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center',
                padding: 48,
                overflow: 'hidden',
                zIndex: 10,
            }}
        >
            <Brand />

            {/* The spinner — its own self-contained 168×168 box with
                NOTHING overlaid in the centre.  Just an arc rotating
                360° once per second so even on a TV at 6-10 ft it
                reads cleanly as "loading", not "stuck". */}
            <div style={{
                position: 'relative',
                width: 168, height: 168,
                marginTop: 36, marginBottom: 36,
            }}>
                <svg
                    width={168} height={168}
                    viewBox="0 0 168 168"
                    style={{
                        position: 'absolute', inset: 0,
                        animation: 'lvtv-spin 1.1s linear infinite',
                    }}
                    aria-hidden="true"
                >
                    {/* faint track */}
                    <circle
                        cx={84} cy={84} r={70}
                        fill="none"
                        stroke="rgba(255,255,255,0.06)"
                        strokeWidth={4}
                    />
                    {/* spinning arc — 280° sweep */}
                    <circle
                        cx={84} cy={84} r={70}
                        fill="none"
                        stroke="url(#lvtv-grad)"
                        strokeWidth={4}
                        strokeLinecap="round"
                        strokeDasharray={`${2 * Math.PI * 70 * 0.78} ${2 * Math.PI * 70}`}
                        transform="rotate(-90 84 84)"
                    />
                    <defs>
                        <linearGradient id="lvtv-grad" x1="0" y1="0" x2="1" y2="1">
                            <stop offset="0%" stopColor="#5DC8FF" />
                            <stop offset="100%" stopColor="#FFFFFF" />
                        </linearGradient>
                    </defs>
                </svg>
            </div>

            <div
                data-testid="livetv-boot-status"
                style={{
                    fontFamily: 'monospace',
                    fontSize: 11, fontWeight: 800,
                    letterSpacing: '0.42em',
                    color: '#5DC8FF',
                    textShadow: '0 0 18px rgba(93,200,255,0.4)',
                }}
            >
                TUNING IN
            </div>

            <p style={{
                margin: '14px 0 0',
                fontSize: 14, color: '#9DA5B5',
                textAlign: 'center', maxWidth: 480,
                lineHeight: 1.55,
            }}>
                Pulling your channels and programme guide from On&nbsp;Now&nbsp;TV.
                This usually takes a couple of seconds.
            </p>

            {onSkip && <SkipButton onSkip={onSkip} />}

            <style>{LVTV_KEYFRAMES}</style>
        </div>
    );
}

function Brand() {
    return (
        <div style={{
            display: 'flex', flexDirection: 'column', alignItems: 'center',
            gap: 8,
        }}>
            <div style={{
                fontFamily: 'monospace',
                fontSize: 10, fontWeight: 800,
                letterSpacing: '0.50em',
                color: '#7d8493',
            }}>
                V2&nbsp;&nbsp;•&nbsp;&nbsp;LIVE&nbsp;TV
            </div>
            <h1 style={{
                margin: 0,
                fontSize: 'clamp(28px, 3vw, 42px)',
                fontWeight: 700, lineHeight: 1.08,
                letterSpacing: '-0.022em',
                color: '#FFFFFF',
                textAlign: 'center',
            }}>
                Preparing your TV guide
            </h1>
        </div>
    );
}

function SkipButton({ onSkip }) {
    const [show, setShow] = React.useState(false);
    const btnRef = React.useRef(null);

    React.useEffect(() => {
        const t = setTimeout(() => setShow(true), 10000);
        return () => clearTimeout(t);
    }, []);

    React.useEffect(() => {
        if (!show) return;
        const tries = [0, 80, 240];
        const timers = tries.map((ms) =>
            setTimeout(() => {
                const el = btnRef.current;
                if (el && document.activeElement !== el) {
                    try { el.focus({ preventScroll: true }); } catch { /* ignore */ }
                    el.setAttribute('data-focused', 'true');
                }
            }, ms)
        );
        return () => timers.forEach(clearTimeout);
    }, [show]);

    if (!show) return null;
    return (
        <button
            ref={btnRef}
            data-testid="livetv-boot-skip"
            data-focusable="true"
            data-focus-style="pill"
            data-initial-focus="true"
            tabIndex={0}
            onClick={onSkip}
            onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    onSkip();
                }
            }}
            style={{
                position: 'absolute',
                bottom: 56, right: 36,
                padding: '8px 14px',
                borderRadius: 999,
                background: 'rgba(255,255,255,0.04)',
                border: '1px solid rgba(255,255,255,0.10)',
                color: '#9DA5B5',
                fontSize: 11, fontWeight: 700,
                letterSpacing: '0.18em',
                cursor: 'pointer',
                outline: 'none',
                zIndex: 20,
            }}
            onFocus={(e) => {
                e.currentTarget.style.background = 'rgba(93,200,255,0.18)';
                e.currentTarget.style.borderColor = 'rgba(93,200,255,0.55)';
                e.currentTarget.style.color = '#FFFFFF';
            }}
            onBlur={(e) => {
                e.currentTarget.style.background = 'rgba(255,255,255,0.04)';
                e.currentTarget.style.borderColor = 'rgba(255,255,255,0.10)';
                e.currentTarget.style.color = '#9DA5B5';
            }}
        >
            SKIP &rarr;
        </button>
    );
}

const LVTV_KEYFRAMES = `
@keyframes lvtv-spin {
    from { transform: rotate(0deg); }
    to   { transform: rotate(360deg); }
}
`;
