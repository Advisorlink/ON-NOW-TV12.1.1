import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Gauge, RotateCw, ArrowLeft, Check, X } from 'lucide-react';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';
import { measurePing, measureDownload, speedVerdict, pingVerdict, QUALITY_TIERS } from '@/lib/speedTest';

const GAUGE_MAX = 150;
const R = 84;
const CIRC = Math.PI * R * 1.5;

/** Pop-up internet speed test (ping + download) opened from the top bar. */
export default function SpeedTestModal({ onClose }) {
    const [phase, setPhase] = useState('ping');
    const [ping, setPing] = useState(null);
    const [mbps, setMbps] = useState(0);
    const [progress, setProgress] = useState(0);
    const [error, setError] = useState('');
    const [run, setRun] = useState(0);
    const [focusIdx, setFocusIdx] = useState(1);
    const againRef = useRef(null);
    const closeRef = useRef(null);
    const abortRef = useRef(null);

    useNativeBackTrap(true, () => { onClose(); return false; });
    useEffect(() => {
        try { window.dispatchEvent(new CustomEvent('vesper:hide-trailer-preview')); } catch { /* ignore */ }
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace' || e.key === 'GoBack' || e.keyCode === 4) {
                e.preventDefault();
                e.stopPropagation();
                triggerTrapBack();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, []);

    useEffect(() => {
        const ctrl = new AbortController();
        abortRef.current?.abort();
        abortRef.current = ctrl;
        setPhase('ping'); setPing(null); setMbps(0); setProgress(0); setError('');
        (async () => {
            try {
                const ms = await measurePing({ signal: ctrl.signal });
                if (ctrl.signal.aborted) return;
                setPing(ms);
                setPhase('download');
                const res = await measureDownload({
                    signal: ctrl.signal,
                    onProgress: (p) => { if (!ctrl.signal.aborted) { setMbps(p.mbps); setProgress(p.progress); } },
                });
                if (ctrl.signal.aborted) return;
                setMbps(res.mbps); setProgress(1); setPhase('done');
            } catch {
                if (ctrl.signal.aborted) return;
                setError('Could not reach the speed-test server. Check the internet connection and try again.');
                setPhase('error');
            }
        })();
        return () => ctrl.abort();
    }, [run]);

    useEffect(() => {
        const t = setTimeout(() => (focusIdx === 0 ? againRef : closeRef).current?.focus({ preventScroll: true }), 80);
        return () => clearTimeout(t);
    }, [focusIdx]);

    const finished = phase === 'done' || phase === 'error';
    const verdict = speedVerdict(mbps);
    const tone = phase === 'done' ? verdict.tone : phase === 'error' ? '#FF8E8E' : '#5DC8FF';
    const arc = Math.min(1, mbps / GAUGE_MAX);
    const status = phase === 'ping' ? 'Measuring ping…'
        : phase === 'download' ? 'Measuring download speed…'
            : phase === 'done' ? verdict.label : error;

    return createPortal(
        <div
            data-testid="speed-test-modal"
            data-focus-trap="true"
            role="dialog"
            aria-modal="true"
            aria-label="Speed test"
            onKeyDown={(e) => {
                if (e.key === 'ArrowLeft') { e.preventDefault(); if (finished) setFocusIdx(0); }
                if (e.key === 'ArrowRight') { e.preventDefault(); setFocusIdx(1); }
            }}
            style={{ position: 'fixed', inset: 0, zIndex: 9000, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(6,8,15,0.82)', backdropFilter: 'blur(14px)', WebkitBackdropFilter: 'blur(14px)' }}
        >
            <div
                style={{
                    position: 'relative', width: 'min(640px, 92vw)', padding: '36px 40px 32px', borderRadius: 28,
                    background: 'linear-gradient(160deg, rgba(15,22,38,0.92) 0%, rgba(6,8,15,0.94) 100%)',
                    border: `1px solid ${tone}55`, boxShadow: `0 40px 100px rgba(0,0,0,0.75), 0 0 80px ${tone}22`,
                    color: '#E6EAF2', animation: 'vesper-speed-in 320ms cubic-bezier(.16,1,.3,1) both', textAlign: 'center',
                }}
            >
                <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.42em', color: tone, fontWeight: 800, textTransform: 'uppercase', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10 }}>
                    <Gauge size={16} /> Speed test
                </div>

                <div style={{ position: 'relative', width: 240, height: 200, margin: '18px auto 6px' }}>
                    <svg width="240" height="200" viewBox="0 0 240 200" aria-hidden="true">
                        <path d={describeArc(120, 120, R)} fill="none" stroke="rgba(255,255,255,0.08)" strokeWidth="14" strokeLinecap="round" />
                        <path
                            d={describeArc(120, 120, R)} fill="none" stroke={tone} strokeWidth="14" strokeLinecap="round"
                            strokeDasharray={CIRC} strokeDashoffset={CIRC * (1 - arc)}
                            style={{ transition: 'stroke-dashoffset 260ms ease, stroke 300ms ease', filter: `drop-shadow(0 0 10px ${tone}99)` }}
                        />
                    </svg>
                    <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', paddingTop: 12 }}>
                        <div data-testid="speed-test-mbps" style={{ fontSize: 56, fontWeight: 800, lineHeight: 1, letterSpacing: '-0.03em', color: '#fff' }}>
                            {phase === 'ping' || phase === 'error' ? '—' : mbps.toFixed(mbps >= 100 ? 0 : 1)}
                        </div>
                        <div style={{ fontSize: 13, fontWeight: 700, color: '#9DA5B5', letterSpacing: '0.18em', marginTop: 6 }}>MBPS DOWNLOAD</div>
                    </div>
                </div>

                <div data-testid="speed-test-status" style={{ fontSize: 20, fontWeight: 700, color: tone, minHeight: 28 }}>{status}</div>
                <div data-testid="speed-test-ping" style={{ fontSize: 15, color: '#9DA5B5', marginTop: 8 }}>
                    {ping == null ? 'Ping —' : `Ping ${ping} ms · ${pingVerdict(ping)}`}
                </div>

                <div data-testid="speed-test-quality" style={{ display: 'flex', justifyContent: 'center', gap: 12, marginTop: 20 }}>
                    {QUALITY_TIERS.map((tier) => {
                        const known = phase === 'done';
                        const ok = known && mbps >= tier.min;
                        const color = !known ? '#6B7384' : ok ? '#7CF1A1' : '#FF8E8E';
                        return (
                            <div
                                key={tier.label}
                                data-testid={`speed-test-quality-${tier.label.toLowerCase()}`}
                                data-ok={known ? String(ok) : undefined}
                                style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '8px 16px', borderRadius: 999, border: `1px solid ${color}55`, background: `${color}14`, color, fontSize: 14, fontWeight: 800, letterSpacing: '0.06em', transition: 'color 300ms ease, background 300ms ease, border-color 300ms ease' }}
                            >
                                <span style={{ display: 'inline-flex', width: 20, height: 20, borderRadius: '50%', alignItems: 'center', justifyContent: 'center', background: known ? color : 'rgba(255,255,255,0.08)', color: '#06080F' }}>
                                    {known ? (ok ? <Check size={14} strokeWidth={3} /> : <X size={14} strokeWidth={3} />) : null}
                                </span>
                                {tier.label}
                                <span style={{ fontSize: 11, fontWeight: 600, color: '#6B7384', letterSpacing: 0 }}>{tier.min}+</span>
                            </div>
                        );
                    })}
                </div>

                <div style={{ height: 4, borderRadius: 4, background: 'rgba(255,255,255,0.08)', margin: '22px auto 0', width: 320, overflow: 'hidden' }}>
                    <div style={{ height: '100%', width: `${Math.round((phase === 'ping' ? 0.08 : phase === 'error' ? 1 : 0.1 + progress * 0.9) * 100)}%`, background: tone, transition: 'width 220ms linear, background 300ms ease' }} />
                </div>

                <div style={{ display: 'flex', gap: 14, justifyContent: 'center', marginTop: 28 }}>
                    {finished && (
                        <PillButton innerRef={againRef} testid="speed-test-again-btn" primary active={focusIdx === 0} onFocus={() => setFocusIdx(0)} onClick={() => { setFocusIdx(1); setRun((r) => r + 1); }}>
                            <RotateCw size={18} /> Test again
                        </PillButton>
                    )}
                    <PillButton innerRef={closeRef} testid="speed-test-close-btn" active={focusIdx === 1} onFocus={() => setFocusIdx(1)} onClick={onClose}>
                        <ArrowLeft size={18} /> {finished ? 'Close' : 'Cancel'}
                    </PillButton>
                </div>
                <div style={{ fontSize: 12, color: '#6B7384', marginTop: 20 }}>Tested against Cloudflare's global speed-test network</div>
            </div>
            <style>{`@keyframes vesper-speed-in { from { opacity: 0; transform: translateY(24px) scale(0.96); } to { opacity: 1; transform: none; } }`}</style>
        </div>,
        document.body,
    );
}

function describeArc(cx, cy, r) {
    const a0 = (135 * Math.PI) / 180;
    const a1 = (405 * Math.PI) / 180;
    const x0 = cx + r * Math.cos(a0); const y0 = cy + r * Math.sin(a0);
    const x1 = cx + r * Math.cos(a1); const y1 = cy + r * Math.sin(a1);
    return `M ${x0} ${y0} A ${r} ${r} 0 1 1 ${x1} ${y1}`;
}

function PillButton({ innerRef, testid, primary = false, active, onFocus, onClick, children }) {
    const lit = active;
    return (
        <button
            ref={innerRef}
            type="button"
            data-testid={testid}
            data-focusable="true"
            tabIndex={0}
            onFocus={onFocus}
            onMouseEnter={onFocus}
            onClick={(e) => { e.stopPropagation(); onClick(); }}
            style={{
                padding: '14px 26px', borderRadius: 999, cursor: 'pointer', fontFamily: 'inherit', fontSize: 14, fontWeight: 800,
                letterSpacing: '0.14em', textTransform: 'uppercase', display: 'inline-flex', alignItems: 'center', gap: 10, outline: 'none',
                background: lit ? (primary ? 'linear-gradient(160deg, #5DC8FF 0%, #1a78b8 100%)' : 'rgba(255,255,255,0.14)') : (primary ? 'rgba(93,200,255,0.18)' : 'rgba(255,255,255,0.05)'),
                color: lit ? (primary ? '#06080F' : '#fff') : (primary ? '#5DC8FF' : '#9DA5B5'),
                border: lit ? '1px solid rgba(255,255,255,0.4)' : '1px solid rgba(255,255,255,0.12)',
                boxShadow: lit ? '0 0 28px rgba(93,200,255,0.45)' : 'none',
                transform: lit ? 'translateY(-2px) scale(1.04)' : 'none',
                transition: 'transform 200ms cubic-bezier(.16,1,.3,1), box-shadow 200ms ease, background 200ms ease, color 200ms ease',
            }}
        >
            {children}
        </button>
    );
}
