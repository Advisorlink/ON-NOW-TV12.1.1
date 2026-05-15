import React from 'react';
import { Tv, Cast, ListTree, Radio, Calendar } from 'lucide-react';

/**
 * <LiveTVBoot/> — cinematic boot splash for Live TV.
 *
 * Designed to look premium on a 4K Android TV while staying GPU-cheap
 * enough for Chrome 52 / HK1: the only animated CSS properties are
 * `transform` (SVG arc rotation, marquee translate) and `opacity`.
 * No backdrop-filter, no `filter: blur`, no full-page radial layers.
 *
 * Layout (top → bottom):
 *   1. Brand wordmark + tag
 *   2. Huge circular SVG progress arc with the active phase icon +
 *      live percentage in the centre
 *   3. Three counter cards (CATEGORIES, CHANNELS, TV GUIDE)
 *   4. Four stage rows with their own slim progress bars
 *   5. Drifting marquee of TV / film glyphs at the very bottom
 *
 * Props:
 *   • stages       — [{ id, label, status, detail }, …]
 *   • counters     — { categoriesDone, categoriesTotal,
 *                      channelsCount, epgDone, epgTotal }
 *   • bootTarget   — number of EPG channels needed to dismiss the
 *                    splash.  The arc + percentage are computed
 *                    against this, NOT epgTotal, so a 14 000-channel
 *                    provider doesn't feel "stuck at 3 %".  Defaults
 *                    to 500.
 */

const PHASE_ICON = {
    auth:       Cast,
    categories: ListTree,
    channels:   Radio,
    epg:        Calendar,
};

const STAGE_FRACTION = {
    auth:       (c) => 1, // binary
    categories: (c) => c.categoriesTotal
        ? Math.min(1, c.categoriesDone / c.categoriesTotal) : 0,
    channels:   (c) => c.categoriesTotal
        ? Math.min(1, c.categoriesDone / c.categoriesTotal) : 0,
    /* EPG fraction is measured against the BOOT TARGET (e.g. 500), not
     * the full channel count.  Otherwise on a 14 000-channel provider
     * the user would stare at "3 %" for ages even though we're nearly
     * ready to dismiss the splash. */
    epg:        (c, target) => {
        const t = Math.min(target || 500, c.epgTotal || 0) || 1;
        return Math.min(1, (c.epgDone || 0) / t);
    },
};

export default function LiveTVBoot({ stages, counters, bootTarget = 500, onSkip }) {
    const activeStage = stages.find((s) => s.status === 'active')
                     || stages.find((s) => s.status === 'failed')
                     || stages.find((s) => s.status === 'pending')
                     || stages[stages.length - 1];
    const ActiveIcon = PHASE_ICON[activeStage?.id] || Tv;

    // Overall progress: weight stages 10/20/30/40 (EPG dominates).
    // `bootTarget` is threaded into the EPG stage fraction so the
    // arc fills with respect to "500 channels", not "14 000".
    const weights = { auth: 0.10, categories: 0.20, channels: 0.30, epg: 0.40 };
    let overall = 0;
    for (const s of stages) {
        const f = (STAGE_FRACTION[s.id] || (() => 0))(counters || {}, bootTarget);
        if (s.status === 'done') overall += weights[s.id] || 0;
        else if (s.status === 'active') overall += (weights[s.id] || 0) * f;
    }
    overall = Math.min(1, Math.max(0, overall));
    const pct = Math.round(overall * 100);

    // SVG ring math
    const ARC_RADIUS = 92;
    const ARC_CIRC = 2 * Math.PI * ARC_RADIUS;

    return (
        <div
            data-testid="live-tv-boot"
            style={{
                position: 'absolute', inset: 0,
                background:
                    'radial-gradient(ellipse 1200px 700px at 50% 25%, ' +
                        'rgba(93,200,255,0.10) 0%, transparent 60%),' +
                    '#06080F',
                color: '#E6EAF2',
                display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center',
                padding: '40px 36px 80px',
                overflow: 'hidden',
                zIndex: 10,
            }}
        >
            <BrandHeader />

            {/* Centerpiece arc + active phase icon. */}
            <div style={{
                position: 'relative', width: 240, height: 240,
                marginTop: 6, marginBottom: 28,
            }}>
                <svg
                    width={240} height={240}
                    viewBox="0 0 240 240"
                    style={{ position: 'absolute', inset: 0, transform: 'rotate(-90deg)' }}
                >
                    {/* Track */}
                    <circle
                        cx={120} cy={120} r={ARC_RADIUS}
                        fill="none"
                        stroke="rgba(255,255,255,0.06)"
                        strokeWidth={6}
                    />
                    {/* Filled arc */}
                    <circle
                        cx={120} cy={120} r={ARC_RADIUS}
                        fill="none"
                        stroke="url(#lvtv-grad)"
                        strokeWidth={6}
                        strokeLinecap="round"
                        strokeDasharray={ARC_CIRC}
                        strokeDashoffset={ARC_CIRC * (1 - overall)}
                        style={{ transition: 'stroke-dashoffset 0.6s cubic-bezier(0.22, 0.7, 0.4, 1)' }}
                    />
                    <defs>
                        <linearGradient id="lvtv-grad" x1="0" y1="0" x2="1" y2="1">
                            <stop offset="0%" stopColor="#5DC8FF" />
                            <stop offset="60%" stopColor="#7AA9FF" />
                            <stop offset="100%" stopColor="#FFFFFF" />
                        </linearGradient>
                    </defs>
                </svg>

                {/* Rotating dot at the tip of the arc (CSS-only) */}
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute', top: '50%', left: '50%',
                        width: 0, height: 0,
                        transform: `rotate(${overall * 360 - 90}deg)`,
                        transition: 'transform 0.6s cubic-bezier(0.22, 0.7, 0.4, 1)',
                    }}
                >
                    <div style={{
                        position: 'absolute',
                        left: ARC_RADIUS - 5,
                        top: -5,
                        width: 10, height: 10, borderRadius: '50%',
                        background: '#FFFFFF',
                    }} />
                </div>

                {/* Centre content */}
                <div style={{
                    position: 'absolute', inset: 0,
                    display: 'flex', flexDirection: 'column',
                    alignItems: 'center', justifyContent: 'center', gap: 6,
                }}>
                    <ActiveIcon size={26} color="#5DC8FF" strokeWidth={2.2} />
                    <div style={{
                        fontFamily: 'monospace',
                        fontSize: 38, fontWeight: 800,
                        letterSpacing: '-0.04em', lineHeight: 1,
                        color: '#FFFFFF',
                    }}>
                        {pct}<span style={{ color: '#5DC8FF', marginLeft: 2 }}>%</span>
                    </div>
                    <div style={{
                        fontFamily: 'monospace',
                        fontSize: 9, fontWeight: 800, letterSpacing: '0.30em',
                        color: '#7d8493',
                    }}>
                        {(activeStage?.label || 'READY').toUpperCase()}
                    </div>
                </div>
            </div>

            {/* Counter cards */}
            <div style={{
                display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)',
                gap: 14, width: 'min(640px, 92vw)', marginBottom: 24,
            }}>
                <CounterCard
                    label="CATEGORIES"
                    value={counters.categoriesDone}
                    total={counters.categoriesTotal}
                    active={activeStage?.id === 'categories'}
                />
                <CounterCard
                    label="CHANNELS"
                    value={counters.channelsCount}
                    active={activeStage?.id === 'channels'}
                />
                <CounterCard
                    label="TV GUIDE"
                    value={counters.epgDone}
                    /* Cap the visible divisor at the boot target —
                     * "237 / 500" reads cleaner than "237 / 14 273". */
                    total={Math.min(bootTarget, counters.epgTotal || bootTarget)}
                    active={activeStage?.id === 'epg'}
                />
            </div>

            {/* Stage rows */}
            <ul
                data-testid="live-tv-boot-stages"
                style={{
                    listStyle: 'none', margin: 0, padding: 0,
                    width: 'min(640px, 92vw)',
                    display: 'flex', flexDirection: 'column', gap: 8,
                }}
            >
                {stages.map((s) => (
                    <StageRow
                        key={s.id}
                        stage={s}
                        fraction={(STAGE_FRACTION[s.id] || (() => 0))(counters || {}, bootTarget)}
                    />
                ))}
            </ul>

            {/* Bottom marquee strip — drifting TV glyphs.
                Pure CSS translateX, no opacity stagger, GPU-cheap. */}
            <Marquee />

            {/* Escape hatch — visible after 10 seconds so a stuck
                provider can't hold the user captive on the splash.
                Hidden until then so it doesn't suggest the loader
                is broken when it's working normally. */}
            {onSkip && (
                <SkipButton onSkip={onSkip} />
            )}

            <style>{LVTV_KEYFRAMES}</style>
        </div>
    );
}

function SkipButton({ onSkip }) {
    const [show, setShow] = React.useState(false);
    React.useEffect(() => {
        const t = setTimeout(() => setShow(true), 10000);
        return () => clearTimeout(t);
    }, []);
    if (!show) return null;
    return (
        <button
            data-testid="livetv-boot-skip"
            data-focusable="true"
            tabIndex={0}
            onClick={onSkip}
            style={{
                position: 'absolute',
                bottom: 56,
                right: 36,
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

/* ──────────────────────────────────────────────────────────────────── */

function BrandHeader() {
    return (
        <div style={{
            display: 'flex', flexDirection: 'column', alignItems: 'center',
            gap: 6, marginBottom: 24,
        }}>
            <div style={{
                fontFamily: 'monospace',
                fontSize: 11, fontWeight: 800,
                letterSpacing: '0.42em',
                color: '#5DC8FF',
                textShadow: '0 0 18px rgba(93,200,255,0.4)',
            }}>
                V2 · ON&nbsp;NOW&nbsp;TV
            </div>
            <h1 style={{
                margin: 0,
                fontSize: 'clamp(28px, 3.0vw, 42px)',
                fontWeight: 700, lineHeight: 1.08,
                letterSpacing: '-0.022em',
                color: '#FFFFFF',
                textAlign: 'center',
            }}>
                Preparing your TV guide
            </h1>
            <p style={{
                margin: 0, marginTop: 4,
                fontSize: 13, color: '#9DA5B5',
                textAlign: 'center', maxWidth: 520, lineHeight: 1.55,
            }}>
                We're warming up your channels and the next 12 hours of
                programming. Zapping will be instant once we're done — and
                the rest keeps filling in while you watch.
            </p>
        </div>
    );
}

const CounterCard = React.memo(function CounterCard({ label, value, total, active }) {
    const showTotal = typeof total === 'number' && total > 0;
    return (
        <div
            style={{
                position: 'relative',
                padding: '14px 16px',
                background: active
                    ? 'linear-gradient(135deg, rgba(93,200,255,0.18) 0%, rgba(93,200,255,0.05) 100%)'
                    : 'rgba(255,255,255,0.025)',
                border: active
                    ? '1px solid rgba(93,200,255,0.55)'
                    : '1px solid rgba(255,255,255,0.07)',
                borderRadius: 14,
                overflow: 'hidden',
                transition: 'background 0.4s ease, border-color 0.4s ease',
            }}
        >
            {active && (
                <span
                    aria-hidden="true"
                    style={{
                        position: 'absolute', top: 10, right: 12,
                        width: 6, height: 6, borderRadius: '50%',
                        background: '#5DC8FF',
                        boxShadow: '0 0 0 0 rgba(93,200,255,0.4)',
                        animation: 'lvtv-pulse 1.4s ease-in-out infinite',
                    }}
                />
            )}
            <div style={{
                fontFamily: 'monospace',
                fontSize: 10, fontWeight: 800,
                letterSpacing: '0.28em',
                color: active ? '#5DC8FF' : '#5e6473',
                marginBottom: 6,
            }}>
                {label}
            </div>
            <div style={{
                fontFamily: 'monospace',
                fontSize: 28, fontWeight: 800,
                letterSpacing: '-0.04em',
                lineHeight: 1, color: '#FFFFFF',
                display: 'flex', alignItems: 'baseline', gap: 6,
            }}>
                <AnimatedNumber value={value || 0} />
                {showTotal && (
                    <span style={{
                        fontSize: 14, color: '#7d8493',
                        letterSpacing: '0', fontWeight: 700,
                    }}>
                        / {total}
                    </span>
                )}
            </div>
        </div>
    );
});

/** AnimatedNumber — smoothly tweens between the previous and current
 *  value over ~250 ms.  No external lib; uses a single
 *  requestAnimationFrame loop. */
const AnimatedNumber = React.memo(function AnimatedNumber({ value }) {
    const [display, setDisplay] = React.useState(value);
    const targetRef = React.useRef(value);
    const rafRef = React.useRef(0);
    React.useEffect(() => {
        targetRef.current = value;
        const start = performance.now();
        const from = display;
        const to = value;
        const dur = Math.min(450, 120 + Math.abs(to - from) * 8);
        const step = (now) => {
            const t = Math.min(1, (now - start) / dur);
            const eased = 1 - Math.pow(1 - t, 3);
            setDisplay(Math.round(from + (to - from) * eased));
            if (t < 1) rafRef.current = requestAnimationFrame(step);
        };
        rafRef.current = requestAnimationFrame(step);
        return () => cancelAnimationFrame(rafRef.current);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [value]);
    return <span>{display}</span>;
});

const StageRow = React.memo(function StageRow({ stage, fraction }) {
    const status = stage.status;
    const accent =
        status === 'done'   ? '#7AE2A8'
      : status === 'active' ? '#5DC8FF'
      : status === 'failed' ? '#FF6B7A'
                            : 'rgba(255,255,255,0.14)';
    const barFill = status === 'done' ? 1 : (status === 'active' ? fraction : 0);
    return (
        <li
            data-testid={`boot-stage-${stage.id}-${status}`}
            style={{
                position: 'relative',
                display: 'flex', alignItems: 'center', gap: 12,
                padding: '12px 14px',
                background: status === 'active'
                    ? 'rgba(93,200,255,0.05)'
                    : 'rgba(255,255,255,0.018)',
                border: status === 'active'
                    ? '1px solid rgba(93,200,255,0.35)'
                    : '1px solid rgba(255,255,255,0.05)',
                borderRadius: 10,
                overflow: 'hidden',
            }}
        >
            {/* Inline fill bar at the bottom of the row */}
            <span
                aria-hidden="true"
                style={{
                    position: 'absolute', left: 0, bottom: 0,
                    height: 2, width: `${barFill * 100}%`,
                    background: accent,
                    transition: 'width 0.5s cubic-bezier(0.22, 0.7, 0.4, 1)',
                }}
            />
            <span style={{
                width: 8, height: 8, borderRadius: '50%',
                background: accent,
                boxShadow: status === 'active'
                    ? '0 0 12px rgba(93,200,255,0.6)' : 'none',
                animation: status === 'active'
                    ? 'lvtv-pulse 1.4s ease-in-out infinite' : 'none',
                flexShrink: 0,
            }} />
            <span style={{ flex: 1, minWidth: 0 }}>
                <div style={{
                    fontSize: 13, fontWeight: 700,
                    color: status === 'pending' ? '#7d8493' : '#E6EAF2',
                    letterSpacing: '-0.005em',
                }}>
                    {stage.label}
                </div>
                {stage.detail && (
                    <div style={{
                        fontFamily: 'monospace',
                        fontSize: 10, marginTop: 3,
                        letterSpacing: '0.10em',
                        color: status === 'failed' ? '#FF8896' : '#7d8493',
                    }}>
                        {stage.detail}
                    </div>
                )}
            </span>
            <span style={{
                fontFamily: 'monospace',
                fontSize: 10, fontWeight: 700,
                letterSpacing: '0.18em',
                color: accent,
                flexShrink: 0,
            }}>
                {status === 'done' ? 'DONE' : status === 'active' ? 'NOW' : status === 'failed' ? 'FAILED' : '...'}
            </span>
        </li>
    );
});

function Marquee() {
    const glyphs = ['📺', '🎬', '⚡', '🏆', '🎙️', '🎞️', '🌍', '🎤', '🎵', '🏈', '🎮'];
    /* Render the row twice so the translation can loop seamlessly. */
    const items = [...glyphs, ...glyphs, ...glyphs];
    return (
        <div
            aria-hidden="true"
            style={{
                position: 'absolute', left: 0, right: 0, bottom: 20,
                overflow: 'hidden',
                pointerEvents: 'none',
                maskImage: 'linear-gradient(90deg, transparent 0%, #000 12%, #000 88%, transparent 100%)',
                WebkitMaskImage: 'linear-gradient(90deg, transparent 0%, #000 12%, #000 88%, transparent 100%)',
            }}
        >
            <div style={{
                display: 'inline-flex', gap: 36,
                fontSize: 22, opacity: 0.34,
                whiteSpace: 'nowrap',
                animation: 'lvtv-marquee 38s linear infinite',
                paddingLeft: 36,
            }}>
                {items.map((g, i) => (
                    <span key={i} style={{ filter: 'grayscale(20%)' }}>{g}</span>
                ))}
            </div>
        </div>
    );
}

const LVTV_KEYFRAMES = `
@keyframes lvtv-pulse {
    0%   { transform: scale(1);    opacity: 1;   box-shadow: 0 0 0 0 rgba(93,200,255,0.55); }
    50%  { transform: scale(1.18); opacity: 0.85;}
    100% { transform: scale(1);    opacity: 1; }
}
@keyframes lvtv-marquee {
    0%   { transform: translateX(0); }
    100% { transform: translateX(-50%); }
}
`;
