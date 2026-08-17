import React, { useEffect, useMemo, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import { Camera, Check, Play, Volume2, VolumeX, X } from 'lucide-react';
import useTriviaSocket from './useTriviaSocket';
import useTriviaSounds from './useTriviaSounds';
import './trivia.css';

const API = process.env.REACT_APP_BACKEND_URL;
const OPT_KEYS = ['A', 'B', 'C', 'D'];
const CONFETTI = ['#F97316', '#4F46E5', '#10B981', '#F8F9FA'];

const avatarGrad = (name) => {
    let h = 0;
    for (const c of name || '?') h = (h * 31 + c.charCodeAt(0)) % 360;
    return `linear-gradient(135deg, hsl(${h} 65% 52%), hsl(${(h + 45) % 360} 65% 40%))`;
};

function TimerBar({ q, sounds }) {
    const [pct, setPct] = useState(100);
    const lastSec = useRef(null);
    useEffect(() => {
        if (!q?.deadline) return undefined;
        const t = setInterval(() => {
            const remain = Math.max(0, q.deadline * 1000 - Date.now());
            const p = Math.min(100, (remain / (q.duration * 1000)) * 100);
            setPct(p);
            const sec = Math.ceil(remain / 1000);
            if (p < 30 && p > 0 && sec !== lastSec.current && sec > 0) {
                lastSec.current = sec;
                sounds?.play('tick', 0.4);
            }
        }, 200);
        return () => clearInterval(t);
    }, [q?.deadline, q?.duration, sounds]);
    return (
        <div className="tri-timer" data-testid="tv-timer-bar">
            <i style={{ width: `${pct}%`, backgroundColor: pct > 20 ? 'var(--text-main)' : 'var(--error)' }} />
        </div>
    );
}

function Chip({ p, i }) {
    return (
        <div className="tri-pop flex items-center gap-3 pl-1.5 pr-5 py-1.5 rounded-full"
            style={{ background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', animationDelay: `${i * 40}ms` }}
            data-testid="tv-player-chip">
            <span className="w-10 h-10 rounded-full flex items-center justify-center font-bold text-white text-lg shrink-0"
                style={{ background: avatarGrad(p.name) }}>{(p.name || '?')[0].toUpperCase()}</span>
            <span className="font-semibold text-xl truncate">{p.name}</span>
            {!p.connected && <span className="text-sm ml-auto" style={{ color: 'var(--text-muted)' }}>offline</span>}
        </div>
    );
}

function OptionCard({ opt, i, phase, q, size = 'lg' }) {
    const isCorrect = phase === 'reveal' && i === q.correct;
    const dim = phase === 'reveal' && i !== q.correct;
    const pad = size === 'lg' ? 'px-8 py-7' : 'px-6 py-4';
    const txt = size === 'lg' ? 'text-3xl' : 'text-2xl';
    return (
        <div className={`tri-card flex items-center gap-4 ${pad} ${isCorrect ? 'tri-card-correct' : ''} ${dim ? 'tri-card-dim' : ''}`}
            data-testid={`tv-option-${i}`}>
            <span className="tri-card-letter">{OPT_KEYS[i]}</span>
            <span className={`font-semibold ${txt} relative leading-snug`}>{opt}</span>
            {isCorrect && (
                <span className="ml-auto relative flex items-center justify-center w-10 h-10 rounded-full shrink-0"
                    style={{ backgroundColor: 'var(--success)' }}>
                    <Check size={24} color="#fff" strokeWidth={3} />
                </span>
            )}
        </div>
    );
}

function QuestionStage({ q, phase }) {
    const kind = q.kind || 'text';
    if (kind === 'anagram') {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-7">
                <p className="font-semibold text-2xl uppercase tracking-[0.3em]" style={{ color: 'var(--text-muted)' }}>Unscramble the word</p>
                <div className="flex gap-3 flex-wrap justify-center" data-testid="tv-question-text">
                    {q.text.split('').map((ch, i) => (
                        <span key={i} className="tri-tile tri-pop" style={{ animationDelay: `${i * 60}ms` }}>{ch}</span>
                    ))}
                </div>
                {q.hint && <p className="text-2xl font-semibold" style={{ color: 'var(--primary-soft)' }}>Hint: {q.hint}</p>}
            </div>
        );
    }
    if (kind === 'emoji') {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-7">
                <p className="font-semibold text-2xl uppercase tracking-[0.3em]" style={{ color: 'var(--text-muted)' }}>Crack the emoji code</p>
                <div className="text-[7rem] leading-none tri-pop" data-testid="tv-question-text">{q.text}</div>
                {q.hint && <p className="text-2xl font-semibold" style={{ color: 'var(--primary-soft)' }}>Hint: {q.hint}</p>}
            </div>
        );
    }
    if (kind === 'number') {
        return (
            <div className="flex-1 flex flex-col items-center justify-center gap-7">
                <p className="font-semibold text-2xl uppercase tracking-[0.3em]" style={{ color: 'var(--text-muted)' }}>Closest number wins</p>
                <h1 className="text-5xl lg:text-6xl font-bold tracking-tight text-center max-w-5xl"
                    data-testid="tv-question-text">{q.text}</h1>
                {phase === 'question' && (
                    <p className="text-3xl font-bold tri-pulse" style={{ color: 'var(--primary)' }}>
                        Enter your best guess on your phone
                    </p>
                )}
            </div>
        );
    }
    return (
        <h1 className={`text-5xl lg:text-6xl font-bold tracking-tight leading-tight text-center my-auto max-w-6xl mx-auto ${phase === 'reveal' ? '' : 'tri-rise'}`}
            data-testid="tv-question-text">{q.text}</h1>
    );
}

function NumberReveal({ q }) {
    const guesses = [...(q.results || [])]
        .filter((r) => r.answer !== null && r.answer !== undefined)
        .sort((a, b) => Math.abs(a.answer - q.correct) - Math.abs(b.answer - q.correct));
    return (
        <div className="flex flex-col items-center gap-5" data-testid="tv-number-reveal">
            <div className="tri-glass px-12 py-6 text-center tri-pop">
                <p className="font-semibold uppercase tracking-widest text-xl" style={{ color: 'var(--text-muted)' }}>The answer was</p>
                <p className="tri-mono text-6xl font-bold" style={{ color: 'var(--success)' }}>
                    {Number(q.correct).toLocaleString()}{q.unit ? ` ${q.unit}` : ''}
                </p>
            </div>
            <div className="flex gap-3 flex-wrap justify-center">
                {guesses.map((r, i) => (
                    <span key={r.id} className="tri-glass px-5 py-2.5 font-semibold text-xl tri-pop flex items-center gap-2"
                        style={{ color: i === 0 ? 'var(--success)' : 'var(--text-main)', animationDelay: `${i * 80}ms` }}>
                        {r.name}: <span className="tri-mono">{Number(r.answer).toLocaleString()}</span>
                        <span style={{ color: 'var(--primary-soft)' }}>+{r.gained}</span>
                    </span>
                ))}
                {!guesses.length && <span className="font-semibold text-xl" style={{ color: 'var(--text-muted)' }}>Nobody dared to guess…</span>}
            </div>
        </div>
    );
}

function ResultChips({ q }) {
    return (
        <div className="flex gap-3 flex-wrap justify-center" data-testid="tv-reveal-results">
            {(q.results || []).map((r) => (
                <span key={r.id} className="tri-glass px-5 py-2.5 font-semibold text-xl tri-pop flex items-center gap-2"
                    style={{ color: r.correct ? 'var(--success)' : 'var(--error)' }}>
                    {r.correct ? <Check size={20} strokeWidth={3} /> : <X size={20} strokeWidth={3} />}
                    {r.name} {r.correct ? `+${r.gained}` : r.gained < 0 ? r.gained : ''}
                </span>
            ))}
        </div>
    );
}

export default function TriviaTV() {
    const [code, setCode] = useState(null);
    const { state, send } = useTriviaSocket(code, 'tv');
    const [sel, setSel] = useState({ category: 0, mode: 'classic', rounds: 10 });
    const [muted, setMuted] = useState(() => {
        try { return localStorage.getItem('trivia-muted') === '1'; } catch { return false; }
    });
    const sounds = useTriviaSounds(muted);
    const rootRef = useRef(null);
    const createdRoomRef = useRef(false);

    useEffect(() => {
        // Guard: StrictMode double-runs this effect — without it two
        // rooms get created and the TV can end up split between them.
        if (createdRoomRef.current) return;
        createdRoomRef.current = true;
        fetch(`${API}/api/trivia/rooms`, { method: 'POST' })
            .then((r) => r.json()).then((d) => setCode(d.code)).catch(() => {});
    }, []);

    // Simple linear D-pad navigation across focusable controls.
    useEffect(() => {
        const onKey = (e) => {
            if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(e.key)) return;
            const els = Array.from(rootRef.current?.querySelectorAll('[data-tvfocus]') || []);
            if (!els.length) return;
            e.preventDefault();
            const idx = els.indexOf(document.activeElement);
            const fwd = e.key === 'ArrowRight' || e.key === 'ArrowDown';
            const next = idx < 0 ? 0 : Math.min(els.length - 1, Math.max(0, idx + (fwd ? 1 : -1)));
            els[next].focus();
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, []);

    const joinUrl = useMemo(() => `${API}/trivia/play?room=${code}`, [code]);
    const phase = state?.phase || 'lobby';
    const players = state?.players || [];
    const q = state?.q;
    const isPicCat = typeof sel.category === 'string';
    const isPuzzle = sel.mode === 'puzzle';

    // ── phase-driven soundtrack ──
    const prevPhase = useRef(null);
    useEffect(() => {
        if (phase === 'lobby' && !muted) sounds.loop('lobby', 0.28);
        else sounds.stop('lobby');
        if (prevPhase.current === phase) return;
        prevPhase.current = phase;
        if (phase === 'question') sounds.play('whoosh', 0.6);
        else if (phase === 'reveal') {
            if (q?.qtype === 'number') sounds.play('correct', 0.85);
            else sounds.play((q?.results || []).some((r) => r.correct) ? 'correct' : 'wrong', 0.85);
        } else if (phase === 'leaderboard') sounds.play('whoosh', 0.45);
        else if (phase === 'podium') sounds.play('fanfare', 1);
    }, [phase, muted, q, sounds]);

    const buzzHolder = q?.buzz?.holder || null;
    useEffect(() => { if (buzzHolder) sounds.play('buzz', 0.85); }, [buzzHolder, sounds]);

    const start = () => send({ type: 'start', ...sel });
    const toggleMute = () => setMuted((m) => {
        const n = !m;
        try { localStorage.setItem('trivia-muted', n ? '1' : '0'); } catch { /* ignore */ }
        return n;
    });

    const pickCategory = (c) => setSel((s) => ({
        ...s,
        category: c.id,
        mode: c.picture && s.mode === 'blitz' ? 'classic' : s.mode,
    }));
    const pickMode = (m) => setSel((s) => ({
        ...s,
        mode: m.id,
        category: m.id === 'blitz' && typeof s.category === 'string' ? 0 : s.category,
    }));

    const isPictureQ = q?.kind === 'picture';

    return (
        <div ref={rootRef} className="trivia-root relative overflow-hidden" data-testid="trivia-tv-root">
            <div className="tri-bgfx" />
            <button onClick={toggleMute} data-tvfocus tabIndex={0} data-testid="tv-mute-btn"
                className="tri-glass fixed top-6 right-6 z-20 p-3.5 flex items-center justify-center"
                style={{ color: muted ? 'var(--text-muted)' : 'var(--primary)' }}
                aria-label={muted ? 'Unmute sounds' : 'Mute sounds'}>
                {muted ? <VolumeX size={26} /> : <Volume2 size={26} />}
            </button>

            {phase === 'lobby' && (
                <div className="grid grid-cols-12 gap-8 p-12 min-h-screen relative">
                    <div className="col-span-4 tri-glass p-9 flex flex-col items-center gap-6 self-start">
                        <h1 className="text-4xl font-bold tracking-tight">
                            ON NOW <span style={{ color: 'var(--primary)' }}>TRIVIA</span>
                        </h1>
                        <p className="text-center text-xl leading-relaxed" style={{ color: 'var(--text-muted)' }}>
                            Scan with your phone to join — it becomes your buzzer
                        </p>
                        <div className="bg-white p-4 rounded-2xl shadow-2xl">
                            {code && <QRCodeSVG value={joinUrl} size={216} data-testid="tv-qr" />}
                        </div>
                        <div className="tri-mono text-6xl font-bold tracking-[0.25em] pl-2"
                            style={{ color: 'var(--primary)' }} data-testid="tv-room-code">{code || '····'}</div>
                        <p className="text-sm break-all text-center" style={{ color: 'var(--text-muted)' }}>{joinUrl}</p>
                    </div>
                    <div className="col-span-8 flex flex-col gap-7">
                        <div className="tri-glass p-7">
                            <h2 className="tri-head text-3xl font-semibold mb-4">Category</h2>
                            {isPuzzle && (
                                <p className="mb-4 text-xl" style={{ color: 'var(--text-muted)' }} data-testid="tv-puzzle-note">
                                    Puzzle Party brings its own mix — anagrams, emoji riddles and closest-number showdowns.
                                </p>
                            )}
                            <div className="flex flex-wrap gap-3"
                                style={isPuzzle ? { opacity: 0.3, pointerEvents: 'none' } : undefined}>
                                {(state?.categories || []).map((c) => (
                                    <button key={c.id} data-tvfocus tabIndex={0}
                                        data-testid={`tv-cat-${c.id}`}
                                        onClick={() => pickCategory(c)}
                                        className={`tri-chip px-5 py-3 font-semibold text-xl flex items-center gap-2.5 ${sel.category === c.id ? 'tri-chip-on' : ''}`}>
                                        {c.picture && <Camera size={19} style={{ color: sel.category === c.id ? '#fff' : 'var(--secondary)' }} />}
                                        {c.name}
                                    </button>
                                ))}
                            </div>
                            <h2 className="tri-head text-3xl font-semibold mt-8 mb-4">Game mode</h2>
                            <div className="flex flex-wrap gap-3 items-center">
                                {(state?.modes || []).map((m) => {
                                    const blocked = m.id === 'blitz' && isPicCat;
                                    return (
                                        <button key={m.id} data-tvfocus tabIndex={0}
                                            data-testid={`tv-mode-${m.id}`}
                                            onClick={() => pickMode(m)}
                                            className={`tri-chip tri-chip-alt px-5 py-3 font-semibold text-xl ${sel.mode === m.id ? 'tri-chip-on' : ''}`}
                                            title={blocked ? 'Picture rounds are multiple-choice — blitz swaps back to Mixed Bag' : undefined}
                                            style={{ opacity: blocked ? 0.45 : 1 }}>{m.label}</button>
                                    );
                                })}
                                <span className="w-px h-9 mx-1" style={{ background: 'var(--border-subtle)' }} />
                                {[5, 10, 15].map((n) => (
                                    <button key={n} data-tvfocus tabIndex={0}
                                        data-testid={`tv-rounds-${n}`}
                                        onClick={() => setSel((s) => ({ ...s, rounds: n }))}
                                        className={`tri-chip px-5 py-3 font-semibold text-xl ${sel.rounds === n ? 'tri-chip-on' : ''}`}>
                                        {n} Qs
                                    </button>
                                ))}
                                <button data-tvfocus tabIndex={0} onClick={start}
                                    disabled={!players.length}
                                    data-testid="tv-start-btn"
                                    className="ml-auto px-10 py-3.5 rounded-2xl font-bold text-2xl flex items-center gap-3 disabled:opacity-30 transition-transform hover:scale-[1.03]"
                                    style={{ backgroundColor: 'var(--primary)', color: '#fff', boxShadow: '0 14px 40px var(--primary-glow)' }}>
                                    <Play size={24} fill="#fff" /> Start
                                </button>
                            </div>
                        </div>
                        <div>
                            <h2 className="tri-head text-3xl font-semibold mb-4">
                                Players <span style={{ color: 'var(--primary)' }}>{players.length}</span>
                            </h2>
                            <div className="grid grid-cols-3 gap-3" data-testid="tv-player-grid">
                                {players.map((p, i) => <Chip key={p.id} p={p} i={i} />)}
                                {!players.length && (
                                    <p className="text-2xl tri-pulse col-span-3" style={{ color: 'var(--text-muted)' }}>
                                        Waiting for players to scan…
                                    </p>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {phase === 'loading' && (
                <div className="min-h-screen flex items-center justify-center">
                    <h1 className="text-6xl font-bold tri-pulse">Loading questions…</h1>
                </div>
            )}

            {phase === 'countdown' && (
                <Count deadline={q?.deadline || state?.now} sounds={sounds} />
            )}

            {(phase === 'question' || phase === 'reveal') && q && (
                <div className="min-h-screen flex flex-col p-12 gap-7 relative">
                    <div className="flex items-center justify-between font-semibold text-2xl pr-20" style={{ color: 'var(--text-muted)' }}>
                        <span>Question <span style={{ color: 'var(--text-main)' }}>{q.index}</span> / {q.total}</span>
                        <span>{players.filter((p) => p.answered).length} / {players.length} answered</span>
                    </div>

                    {q.mode === 'buzzer' && phase === 'question' && (
                        <div className="text-center text-3xl font-bold" style={{ color: 'var(--primary)' }}>
                            {q.buzz?.holder_name
                                ? `${q.buzz.holder_name} buzzed in — answering…`
                                : 'BUZZ IN on your phone to answer!'}
                        </div>
                    )}

                    {isPictureQ ? (
                        <>
                            <div className="flex-1 flex items-center gap-10 min-h-0">
                                <div className="flex-[1.2] flex items-center justify-center min-h-0">
                                    <img src={q.image} alt=""
                                        data-testid="tv-question-image"
                                        className={`tri-picture ${q.blur && phase === 'question' ? 'tri-unblur' : ''}`}
                                        style={q.blur && phase === 'question'
                                            ? { animationDuration: `${q.duration || 20}s` }
                                            : { filter: 'none' }} />
                                </div>
                                <div className="flex-1 flex flex-col justify-center gap-4">
                                    <h1 className="text-4xl font-bold tracking-tight mb-3" data-testid="tv-question-text">{q.text}</h1>
                                    {q.options.map((opt, i) => (
                                        <OptionCard key={i} opt={opt} i={i} phase={phase} q={q} size="sm" />
                                    ))}
                                </div>
                            </div>
                            {phase === 'question' ? <TimerBar q={q} sounds={sounds} /> : <ResultChips q={q} />}
                        </>
                    ) : q.qtype === 'number' ? (
                        <>
                            <QuestionStage q={q} phase={phase} />
                            {phase === 'question'
                                ? <TimerBar q={q} sounds={sounds} />
                                : <NumberReveal q={q} />}
                        </>
                    ) : (
                        <>
                            <QuestionStage q={q} phase={phase} />
                            <div className="grid grid-cols-2 gap-5 max-w-6xl w-full mx-auto">
                                {q.options.map((opt, i) => (
                                    <OptionCard key={i} opt={opt} i={i} phase={phase} q={q} size="lg" />
                                ))}
                            </div>
                            {phase === 'question' ? <TimerBar q={q} sounds={sounds} /> : <ResultChips q={q} />}
                        </>
                    )}
                </div>
            )}

            {phase === 'leaderboard' && (
                <div className="min-h-screen flex flex-col items-center justify-center p-14 gap-4">
                    <h1 className="text-6xl font-bold mb-6">Leaderboard</h1>
                    <div className="w-full max-w-3xl flex flex-col gap-3" data-testid="tv-leaderboard">
                        {[...players].sort((a, b) => b.score - a.score).map((p, i) => (
                            <div key={p.id} className="tri-glass tri-rise flex items-center gap-5 px-7 py-4"
                                style={{ animationDelay: `${i * 70}ms`, borderColor: i === 0 ? 'rgba(249,115,22,0.5)' : undefined }}>
                                <span className="tri-mono text-3xl font-bold w-10" style={{ color: i === 0 ? 'var(--primary)' : 'var(--text-muted)' }}>{i + 1}</span>
                                <span className="w-11 h-11 rounded-full flex items-center justify-center font-bold text-white text-xl"
                                    style={{ background: avatarGrad(p.name) }}>{(p.name || '?')[0].toUpperCase()}</span>
                                <span className="text-2xl font-semibold">{p.name}</span>
                                <span className="ml-auto tri-mono text-3xl font-bold">{p.score}</span>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {phase === 'podium' && (
                <div className="min-h-screen flex flex-col items-center justify-center gap-10 relative overflow-hidden">
                    {Array.from({ length: 40 }).map((_, i) => (
                        <span key={i} className="tri-confetti" style={{
                            left: `${(i * 73) % 100}%`,
                            backgroundColor: CONFETTI[i % 4],
                            animationDuration: `${2.4 + (i % 5) * 0.5}s`,
                            animationDelay: `${(i % 10) * 0.25}s`,
                        }} />
                    ))}
                    <h1 className="text-7xl font-bold tri-big-in">Winners</h1>
                    <div className="flex items-end gap-6" data-testid="tv-podium">
                        {(state?.podium || []).map((p, i) => (
                            <div key={i} className="tri-glass tri-pop flex flex-col items-center gap-3 px-12"
                                style={{
                                    paddingTop: 30,
                                    paddingBottom: 30 + (2 - i) * 34,
                                    animationDelay: `${i * 0.2}s`,
                                    order: i === 0 ? 1 : i === 1 ? 0 : 2,
                                    borderTop: `4px solid ${i === 0 ? 'var(--primary)' : i === 1 ? 'var(--secondary)' : 'rgba(255,255,255,0.2)'}`,
                                }}>
                                <span className="text-5xl">{['🥇', '🥈', '🥉'][i]}</span>
                                <span className="text-3xl font-bold">{p.name}</span>
                                <span className="tri-mono text-2xl font-bold" style={{ color: i === 0 ? 'var(--primary)' : 'var(--text-muted)' }}>{p.score}</span>
                            </div>
                        ))}
                    </div>
                    <button data-tvfocus tabIndex={0} onClick={() => send({ type: 'back_to_lobby' })}
                        data-testid="tv-play-again-btn"
                        className="px-12 py-4 rounded-2xl font-bold text-2xl transition-transform hover:scale-[1.03]"
                        style={{ backgroundColor: 'var(--primary)', color: '#fff', boxShadow: '0 14px 40px var(--primary-glow)' }}>
                        Play again
                    </button>
                </div>
            )}
        </div>
    );
}

function Count({ deadline, sounds }) {
    const [n, setN] = useState(3);
    useEffect(() => {
        const t = setInterval(() => {
            setN(Math.max(1, Math.ceil((deadline * 1000 - Date.now()) / 1000)));
        }, 150);
        return () => clearInterval(t);
    }, [deadline]);
    useEffect(() => { sounds?.play('tick', 0.7); }, [n, sounds]);
    return (
        <div className="min-h-screen flex items-center justify-center">
            <span key={n} className="tri-big-in tri-mono text-[15rem] font-bold" style={{ color: 'var(--primary)' }}>{n}</span>
        </div>
    );
}
