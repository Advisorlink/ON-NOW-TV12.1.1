import React, { useEffect, useMemo, useRef, useState } from 'react';
import { QRCodeSVG } from 'qrcode.react';
import useTriviaSocket from './useTriviaSocket';
import './trivia.css';

const API = process.env.REACT_APP_BACKEND_URL;
const OPT_COLORS = ['#FF007A', '#E1FF00', '#00F0FF', '#00FF66'];
const OPT_KEYS = ['A', 'B', 'C', 'D'];

function TimerBar({ q }) {
    const [pct, setPct] = useState(100);
    useEffect(() => {
        if (!q?.deadline) return undefined;
        const t = setInterval(() => {
            const remain = Math.max(0, q.deadline * 1000 - Date.now());
            setPct(Math.min(100, (remain / (q.duration * 1000)) * 100));
        }, 200);
        return () => clearInterval(t);
    }, [q?.deadline, q?.duration]);
    const color = pct > 50 ? '#00F0FF' : pct > 22 ? '#E1FF00' : '#FF3B30';
    return (
        <div className="tri-timer w-full" data-testid="tv-timer-bar">
            <i style={{ width: `${pct}%`, backgroundColor: color }} />
        </div>
    );
}

function Chip({ p, i }) {
    return (
        <div className="tri-glass tri-pop flex items-center gap-3 px-4 py-3"
            style={{ animationDelay: `${i * 40}ms` }} data-testid="tv-player-chip">
            <span className="w-9 h-9 rounded-full flex items-center justify-center font-black text-black text-lg shrink-0"
                style={{ backgroundColor: p.color }}>{(p.name || '?')[0].toUpperCase()}</span>
            <span className="font-bold text-xl truncate">{p.name}</span>
            {!p.connected && <span className="text-xs text-zinc-500 ml-auto">offline</span>}
        </div>
    );
}

export default function TriviaTV() {
    const [code, setCode] = useState(null);
    const { state, send } = useTriviaSocket(code, 'tv');
    const [sel, setSel] = useState({ category: 0, mode: 'classic', rounds: 10 });
    const rootRef = useRef(null);

    useEffect(() => {
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

    const start = () => send({ type: 'start', ...sel });

    return (
        <div ref={rootRef} className="trivia-root relative overflow-hidden" data-testid="trivia-tv-root">
            <div className="tri-bgfx" />
            {phase === 'lobby' && (
                <div className="grid grid-cols-12 gap-8 p-10 min-h-screen relative">
                    <div className="col-span-4 tri-glass p-8 flex flex-col items-center gap-5 self-start">
                        <h1 className="text-4xl font-black tracking-tighter">
                            ON NOW <span style={{ color: '#00F0FF' }}>TRIVIA</span>
                        </h1>
                        <p className="text-zinc-400 text-center">Scan with your phone to join — it becomes your buzzer</p>
                        <div className="bg-white p-4 rounded-2xl">
                            {code && <QRCodeSVG value={joinUrl} size={220} data-testid="tv-qr" />}
                        </div>
                        <div className="tri-mono text-6xl font-extrabold tracking-[0.3em]"
                            style={{ color: '#00F0FF' }} data-testid="tv-room-code">{code || '····'}</div>
                        <p className="text-zinc-500 text-sm break-all text-center">{joinUrl}</p>
                    </div>
                    <div className="col-span-8 flex flex-col gap-6">
                        <div className="tri-glass p-6">
                            <h2 className="tri-head text-2xl font-bold mb-3">Category</h2>
                            <div className="flex flex-wrap gap-3">
                                {(state?.categories || []).map((c) => (
                                    <button key={c.id} data-tvfocus tabIndex={0}
                                        data-testid={`tv-cat-${c.id}`}
                                        onClick={() => setSel((s) => ({ ...s, category: c.id }))}
                                        className="px-5 py-3 rounded-xl font-bold text-lg transition-colors"
                                        style={{
                                            backgroundColor: sel.category === c.id ? '#00F0FF' : 'rgba(255,255,255,0.06)',
                                            color: sel.category === c.id ? '#000' : '#fff',
                                        }}>{c.name}</button>
                                ))}
                            </div>
                            <h2 className="tri-head text-2xl font-bold mt-6 mb-3">Game mode</h2>
                            <div className="flex flex-wrap gap-3">
                                {(state?.modes || []).map((m) => (
                                    <button key={m.id} data-tvfocus tabIndex={0}
                                        data-testid={`tv-mode-${m.id}`}
                                        onClick={() => setSel((s) => ({ ...s, mode: m.id }))}
                                        className="px-5 py-3 rounded-xl font-bold text-lg"
                                        style={{
                                            backgroundColor: sel.mode === m.id ? '#FF007A' : 'rgba(255,255,255,0.06)',
                                            color: sel.mode === m.id ? '#fff' : '#fff',
                                            boxShadow: sel.mode === m.id ? '0 0 22px rgba(255,0,122,0.5)' : 'none',
                                        }}>{m.label}</button>
                                ))}
                                {[5, 10, 15].map((n) => (
                                    <button key={n} data-tvfocus tabIndex={0}
                                        data-testid={`tv-rounds-${n}`}
                                        onClick={() => setSel((s) => ({ ...s, rounds: n }))}
                                        className="px-5 py-3 rounded-xl font-bold text-lg"
                                        style={{ backgroundColor: sel.rounds === n ? '#00FF66' : 'rgba(255,255,255,0.06)', color: sel.rounds === n ? '#000' : '#fff' }}>
                                        {n} Qs
                                    </button>
                                ))}
                                <button data-tvfocus tabIndex={0} onClick={start}
                                    disabled={!players.length}
                                    data-testid="tv-start-btn"
                                    className="ml-auto px-10 py-3 rounded-xl font-black text-xl uppercase tracking-widest disabled:opacity-30"
                                    style={{ backgroundColor: '#00F0FF', color: '#000', boxShadow: '0 0 30px rgba(0,240,255,0.5)' }}>
                                    Start ▶
                                </button>
                            </div>
                        </div>
                        <div>
                            <h2 className="tri-head text-2xl font-bold mb-3">
                                Players <span style={{ color: '#00F0FF' }}>{players.length}</span>
                            </h2>
                            <div className="grid grid-cols-3 gap-3" data-testid="tv-player-grid">
                                {players.map((p, i) => <Chip key={p.id} p={p} i={i} />)}
                                {!players.length && (
                                    <p className="text-zinc-500 text-lg tri-pulse col-span-3">Waiting for players to scan…</p>
                                )}
                            </div>
                        </div>
                    </div>
                </div>
            )}

            {phase === 'loading' && (
                <div className="min-h-screen flex items-center justify-center">
                    <h1 className="text-6xl font-black tri-pulse">Loading questions…</h1>
                </div>
            )}

            {phase === 'countdown' && (
                <Count deadline={q?.deadline || state?.now} />
            )}

            {(phase === 'question' || phase === 'reveal') && q && (
                <div className="min-h-screen flex flex-col p-10 gap-8 relative">
                    <div className="flex items-center justify-between text-zinc-400 font-bold text-xl">
                        <span>Question {q.index} / {q.total}</span>
                        <span>{players.filter((p) => p.answered).length} / {players.length} answered</span>
                    </div>
                    <h1 className={`text-5xl lg:text-6xl font-black tracking-tight leading-tight text-center my-auto ${phase === 'reveal' ? '' : 'tri-rise'}`}
                        data-testid="tv-question-text">{q.text}</h1>
                    {q.mode === 'buzzer' && phase === 'question' && (
                        <div className="text-center text-3xl font-black" style={{ color: '#FF3B30' }}>
                            {q.buzz?.holder_name
                                ? `🔒 ${q.buzz.holder_name} buzzed in — answering…`
                                : 'BUZZ IN on your phone to answer!'}
                        </div>
                    )}
                    <div className="grid grid-cols-2 gap-5">
                        {q.options.map((opt, i) => {
                            const isCorrect = phase === 'reveal' && i === q.correct;
                            const dim = phase === 'reveal' && i !== q.correct;
                            return (
                                <div key={i}
                                    className={`rounded-2xl px-8 py-7 flex items-center gap-5 ${isCorrect ? 'tri-pop' : ''}`}
                                    data-testid={`tv-option-${i}`}
                                    style={{
                                        backgroundColor: OPT_COLORS[i % 4],
                                        color: i === 1 ? '#000' : i === 3 ? '#000' : '#fff',
                                        opacity: dim ? 0.18 : 1,
                                        boxShadow: isCorrect ? '0 0 40px rgba(0,255,102,0.8)' : 'none',
                                        transition: 'opacity 0.3s ease',
                                    }}>
                                    <span className="tri-mono text-3xl font-extrabold">{OPT_KEYS[i]}</span>
                                    <span className="text-3xl font-bold">{opt}</span>
                                    {isCorrect && <span className="ml-auto text-3xl">✓</span>}
                                </div>
                            );
                        })}
                    </div>
                    {phase === 'question'
                        ? <TimerBar q={q} />
                        : (
                            <div className="flex gap-3 flex-wrap justify-center" data-testid="tv-reveal-results">
                                {(q.results || []).map((r) => (
                                    <span key={r.id} className="tri-glass px-4 py-2 font-bold tri-pop"
                                        style={{ color: r.correct ? '#00FF66' : '#FF3B30' }}>
                                        {r.name} {r.correct ? `+${r.gained}` : r.gained < 0 ? r.gained : '✗'}
                                    </span>
                                ))}
                            </div>
                        )}
                </div>
            )}

            {phase === 'leaderboard' && (
                <div className="min-h-screen flex flex-col items-center justify-center p-12 gap-4">
                    <h1 className="text-6xl font-black mb-6">Leaderboard</h1>
                    <div className="w-full max-w-3xl flex flex-col gap-3" data-testid="tv-leaderboard">
                        {[...players].sort((a, b) => b.score - a.score).map((p, i) => (
                            <div key={p.id} className="tri-glass tri-rise flex items-center gap-5 px-6 py-4"
                                style={{ animationDelay: `${i * 70}ms` }}>
                                <span className="tri-mono text-3xl font-extrabold w-10" style={{ color: i === 0 ? '#E1FF00' : '#fff' }}>{i + 1}</span>
                                <span className="w-10 h-10 rounded-full flex items-center justify-center font-black text-black text-xl"
                                    style={{ backgroundColor: p.color }}>{(p.name || '?')[0].toUpperCase()}</span>
                                <span className="text-2xl font-bold">{p.name}</span>
                                <span className="ml-auto tri-mono text-3xl font-extrabold" style={{ color: '#00F0FF' }}>{p.score}</span>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {phase === 'podium' && (
                <div className="min-h-screen flex flex-col items-center justify-center gap-8 relative overflow-hidden">
                    {Array.from({ length: 40 }).map((_, i) => (
                        <span key={i} className="tri-confetti" style={{
                            left: `${(i * 73) % 100}%`,
                            backgroundColor: OPT_COLORS[i % 4],
                            animationDuration: `${2.4 + (i % 5) * 0.5}s`,
                            animationDelay: `${(i % 10) * 0.25}s`,
                        }} />
                    ))}
                    <h1 className="text-7xl font-black tri-big-in">🏆 Winners</h1>
                    <div className="flex items-end gap-6" data-testid="tv-podium">
                        {(state?.podium || []).map((p, i) => (
                            <div key={i} className="tri-glass tri-pop flex flex-col items-center gap-3 px-10"
                                style={{ paddingTop: 28, paddingBottom: 28 + (2 - i) * 30, animationDelay: `${i * 0.2}s`, order: i === 0 ? 1 : i === 1 ? 0 : 2 }}>
                                <span className="text-5xl">{['🥇', '🥈', '🥉'][i]}</span>
                                <span className="text-3xl font-black">{p.name}</span>
                                <span className="tri-mono text-2xl font-extrabold" style={{ color: '#00F0FF' }}>{p.score}</span>
                            </div>
                        ))}
                    </div>
                    <button data-tvfocus tabIndex={0} onClick={() => send({ type: 'back_to_lobby' })}
                        data-testid="tv-play-again-btn"
                        className="px-10 py-4 rounded-xl font-black text-2xl uppercase tracking-widest"
                        style={{ backgroundColor: '#00F0FF', color: '#000' }}>
                        Play again
                    </button>
                </div>
            )}
        </div>
    );
}

function Count({ deadline }) {
    const [n, setN] = useState(3);
    useEffect(() => {
        const t = setInterval(() => {
            setN(Math.max(1, Math.ceil((deadline * 1000 - Date.now()) / 1000)));
        }, 150);
        return () => clearInterval(t);
    }, [deadline]);
    return (
        <div className="min-h-screen flex items-center justify-center">
            <span key={n} className="tri-big-in text-[16rem] font-black" style={{ color: '#00F0FF' }}>{n}</span>
        </div>
    );
}
