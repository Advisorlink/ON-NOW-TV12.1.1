import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Check, Delete, X } from 'lucide-react';
import useTriviaSocket from './useTriviaSocket';
import useTriviaSounds from './useTriviaSounds';
import './trivia.css';

const OPT_KEYS = ['A', 'B', 'C', 'D'];
const buzzMs = (ms) => { try { navigator.vibrate?.(ms); } catch { /* ignore */ } };

const avatarGrad = (name) => {
    let h = 0;
    for (const c of name || '?') h = (h * 31 + c.charCodeAt(0)) % 360;
    return `linear-gradient(135deg, hsl(${h} 65% 52%), hsl(${(h + 45) % 360} 65% 40%))`;
};

// v2.19.4 — user spec: the question must show on the phone too, not
// just on the TV.
function QuestionPanel({ q }) {
    if (!q) return null;
    const kind = q.kind || 'text';
    return (
        <div className="px-4 py-3 rounded-2xl text-center relative shrink-0"
            data-testid="phone-question-text"
            style={{ background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)' }}>
            {kind === 'anagram' ? (
                <>
                    <p className="text-xs uppercase tracking-[0.25em] mb-1" style={{ color: 'var(--text-muted)' }}>Unscramble</p>
                    <p className="tri-mono text-2xl font-bold tracking-[0.18em]">{q.text}</p>
                    {q.hint && <p className="text-sm mt-1 font-semibold" style={{ color: 'var(--primary-soft)' }}>Hint: {q.hint}</p>}
                </>
            ) : kind === 'emoji' ? (
                <>
                    <p className="text-4xl leading-tight">{q.text}</p>
                    {q.hint && <p className="text-sm mt-1 font-semibold" style={{ color: 'var(--primary-soft)' }}>Hint: {q.hint}</p>}
                </>
            ) : (
                <p className="text-lg font-semibold leading-snug">{q.text}</p>
            )}
        </div>
    );
}

function NumberPad({ onLock, unit, sounds }) {
    const [val, setVal] = useState('');
    const tap = (k) => {
        buzzMs(12);
        sounds?.play('click', 0.4);
        if (k === 'del') setVal((v) => v.slice(0, -1));
        else setVal((v) => (v.length >= 9 || (k === '.' && v.includes('.')) ? v : v + k));
    };
    const num = parseFloat(val);
    return (
        <div className="flex-1 flex flex-col gap-3 relative min-h-0" data-testid="phone-number-pad">
            <div className="flex items-baseline justify-center gap-2 py-5 rounded-2xl"
                style={{ background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)' }}>
                <span className="tri-mono text-4xl font-bold" style={{ color: val ? 'var(--text-main)' : 'var(--text-muted)' }}>{val || '···'}</span>
                {unit ? <span className="font-semibold" style={{ color: 'var(--text-muted)' }}>{unit}</span> : null}
            </div>
            <div className="tri-dialer flex-1">
                {['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', 'del'].map((k) => (
                    <button key={k} onClick={() => tap(k)}
                        data-testid={`phone-num-${k === 'del' ? 'del' : k === '.' ? 'dot' : k}`}
                        className="flex items-center justify-center">
                        {k === 'del' ? <Delete size={26} /> : k}
                    </button>
                ))}
            </div>
            <button disabled={Number.isNaN(num)} onClick={() => onLock(num)}
                data-testid="phone-num-lock"
                className="tri-tactile tri-cta py-4 text-xl font-bold uppercase tracking-widest disabled:opacity-40">
                Lock it in
            </button>
        </div>
    );
}

export default function TriviaPlay() {
    const [params] = useSearchParams();
    const room = (params.get('room') || '').toUpperCase();
    const [name, setName] = useState(() => {
        try { return localStorage.getItem('trivia-name') || ''; } catch { return ''; }
    });
    const [joined, setJoined] = useState(false);
    const pid = useMemo(() => {
        try { return sessionStorage.getItem('trivia-pid') || null; } catch { return null; }
    }, []);
    const { state, send, connected, privateMsg } = useTriviaSocket(
        joined ? room : null, 'player', name, pid,
    );
    const [picked, setPicked] = useState(null);
    const sounds = useTriviaSounds(false);

    const phase = state?.phase || 'lobby';
    const q = state?.q;
    const inQuestion = phase === 'question';
    useEffect(() => { setPicked(null); }, [q?.index, inQuestion]);

    // correct/wrong sting when private reveal feedback lands
    const lastMsgRef = useRef(null);
    useEffect(() => {
        if (privateMsg && privateMsg !== lastMsgRef.current && privateMsg.correct !== undefined) {
            lastMsgRef.current = privateMsg;
            sounds.play(privateMsg.correct ? 'correct' : 'wrong', 0.7);
        }
    }, [privateMsg, sounds]);

    const join = (e) => {
        e.preventDefault();
        if (!name.trim() || !room) return;
        try { localStorage.setItem('trivia-name', name.trim()); } catch { /* ignore */ }
        try { document.documentElement.requestFullscreen?.(); } catch { /* ignore */ }
        buzzMs(20);
        sounds.play('click', 0.5);
        setJoined(true);
    };

    if (!joined) {
        return (
            <div className="trivia-root flex flex-col items-center justify-center p-6 gap-7" data-testid="phone-join-screen">
                <div className="tri-bgfx" />
                <h1 className="text-4xl font-bold tracking-tight text-center relative">
                    ON NOW <span style={{ color: 'var(--primary)' }}>TRIVIA</span>
                </h1>
                <div className="flex items-center gap-2.5 px-5 py-2.5 rounded-full relative"
                    style={{ background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)' }}>
                    <span style={{ color: 'var(--text-muted)' }}>Room</span>
                    <span className="tri-mono font-bold text-lg tracking-widest" style={{ color: 'var(--primary)' }}>{room || '—'}</span>
                </div>
                <form onSubmit={join} className="w-full max-w-sm flex flex-col gap-4 relative">
                    <input value={name} onChange={(e) => setName(e.target.value)}
                        placeholder="Your name" maxLength={18} autoFocus
                        data-testid="phone-name-input"
                        className="w-full text-2xl font-semibold text-center rounded-2xl px-5 py-5 focus:outline-none"
                        style={{
                            background: 'var(--bg-surface)',
                            border: '1px solid var(--border-subtle)',
                            color: 'var(--text-main)',
                            caretColor: 'var(--primary)',
                        }} />
                    <button type="submit" disabled={!name.trim() || !room}
                        data-testid="phone-join-btn"
                        className="tri-tactile tri-cta w-full py-5 text-xl font-bold uppercase tracking-widest disabled:opacity-40">
                        Join the game
                    </button>
                    {!room && <p className="text-center" style={{ color: 'var(--error)' }}>No room code — scan the QR on the TV</p>}
                </form>
            </div>
        );
    }

    const me = (state?.players || []).find((p) => p.name === name.trim()) ||
        (state?.players || []).find((p) => p.id === pid);
    const iAnswered = picked !== null;
    const isBuzzer = q?.mode === 'buzzer';
    const isNumber = q?.qtype === 'number';
    const iHoldBuzz = isBuzzer && q?.buzz?.holder && me && q.buzz.holder === me.id;
    const buzzLocked = isBuzzer && me && (q?.buzz?.locked || []).includes(me.id);

    const answerTap = (i) => {
        setPicked(i);
        buzzMs(30);
        sounds.play('click', 0.5);
        send({ type: 'answer', answer: i });
    };

    return (
        <div className="trivia-root flex flex-col p-4 gap-4" style={{ height: '100dvh', overflow: 'hidden' }} data-testid="phone-game-root">
            <div className="tri-bgfx" />
            <div className="flex items-center justify-between text-sm font-semibold px-4 py-2.5 rounded-full relative"
                style={{ background: 'var(--bg-surface)', border: '1px solid var(--border-subtle)' }}>
                <span className="truncate">{name}</span>
                <span className="tri-mono flex items-center gap-1.5" style={{ color: 'var(--text-muted)' }}>
                    <i className="w-2 h-2 rounded-full inline-block"
                        style={{ backgroundColor: connected ? 'var(--success)' : 'var(--error)' }} />
                    {connected ? room : 'reconnecting…'}
                </span>
                <span className="tri-mono" style={{ color: 'var(--primary)' }} data-testid="phone-score">{me?.score ?? 0} pts</span>
            </div>

            {phase === 'question' && q && <QuestionPanel q={q} />}

            {(phase === 'lobby' || phase === 'loading') && (
                <div className="flex-1 flex flex-col items-center justify-center gap-4 relative" data-testid="phone-waiting">
                    <div className="w-20 h-20 rounded-full tri-pop flex items-center justify-center font-bold text-3xl text-white"
                        style={{ background: avatarGrad(name) }}>{(name || '?')[0].toUpperCase()}</div>
                    <h2 className="text-3xl font-bold text-center">You&apos;re in!</h2>
                    <p className="tri-pulse text-lg text-center" style={{ color: 'var(--text-muted)' }}>
                        Look at the TV — waiting for the host to start
                    </p>
                </div>
            )}

            {phase === 'countdown' && (
                <div className="flex-1 flex items-center justify-center relative">
                    <h2 className="text-4xl font-bold tri-pulse">Get ready…</h2>
                </div>
            )}

            {phase === 'question' && q && !isBuzzer && isNumber && (
                iAnswered ? (
                    <div className="flex-1 flex flex-col items-center justify-center gap-3 relative" data-testid="phone-locked-in">
                        <h2 className="tri-mono text-5xl font-bold" style={{ color: 'var(--primary)' }}>
                            {Number(picked).toLocaleString()}
                        </h2>
                        <p className="tri-pulse" style={{ color: 'var(--text-muted)' }}>Guess locked in…</p>
                    </div>
                ) : (
                    <NumberPad unit={q.unit} sounds={sounds}
                        onLock={(v) => { setPicked(v); buzzMs(30); send({ type: 'answer', answer: v }); }} />
                )
            )}

            {phase === 'question' && q && !isBuzzer && !isNumber && (
                iAnswered ? (
                    <div className="flex-1 flex flex-col items-center justify-center gap-3 relative" data-testid="phone-locked-in">
                        <span className="w-20 h-20 rounded-2xl flex items-center justify-center tri-mono text-4xl font-bold tri-pop"
                            style={{ backgroundColor: 'var(--primary)', color: 'var(--on-primary)', boxShadow: '0 14px 40px var(--primary-glow)' }}>
                            {OPT_KEYS[picked]}
                        </span>
                        <h2 className="text-2xl font-bold">Locked in!</h2>
                        <p className="tri-pulse" style={{ color: 'var(--text-muted)' }}>Fingers crossed…</p>
                    </div>
                ) : (
                    <div className="flex-1 flex flex-col gap-3 relative min-h-0">
                        {q.image && !q.blur && (
                            <img src={q.image} alt="" data-testid="phone-question-image"
                                className="h-28 mx-auto rounded-xl object-contain"
                                style={{ border: '1px solid var(--border-subtle)' }} />
                        )}
                        <div className={`flex-1 grid gap-3 ${q.options.length === 2 ? 'grid-rows-2' : 'grid-cols-2 grid-rows-2'}`}>
                            {q.options.map((opt, i) => (
                                <button key={i} data-testid={`phone-answer-${i}`}
                                    onClick={() => answerTap(i)}
                                    className="tri-tactile flex flex-col items-center justify-center gap-1.5 p-3">
                                    <span className="tri-card-letter" style={{ fontSize: '4rem', right: 8, bottom: -10 }}>{OPT_KEYS[i]}</span>
                                    <span className="tri-mono text-2xl font-bold relative" style={{ color: 'var(--primary)' }}>{OPT_KEYS[i]}</span>
                                    <span className="text-lg font-semibold leading-tight text-center relative">{opt}</span>
                                </button>
                            ))}
                        </div>
                    </div>
                )
            )}

            {phase === 'question' && q && isBuzzer && (
                <div className="flex-1 flex flex-col items-center justify-center gap-5 relative">
                    {iHoldBuzz ? (
                        <div className="w-full grid grid-cols-2 grid-rows-2 gap-3 flex-1" data-testid="phone-buzz-answers">
                            {q.options.map((opt, i) => (
                                <button key={i} onClick={() => answerTap(i)}
                                    className="tri-tactile flex flex-col items-center justify-center gap-1.5 p-3">
                                    <span className="tri-mono text-xl font-bold" style={{ color: 'var(--primary)' }}>{OPT_KEYS[i]}</span>
                                    <span className="text-base font-semibold leading-tight text-center">{opt}</span>
                                </button>
                            ))}
                        </div>
                    ) : (
                        <>
                            <button className="tri-buzzer font-bold text-4xl text-white uppercase tracking-widest"
                                data-testid="phone-buzzer"
                                disabled={!!q.buzz?.holder || buzzLocked}
                                onClick={() => { buzzMs(60); sounds.play('buzz', 0.7); send({ type: 'buzz' }); }}>
                                BUZZ
                            </button>
                            <p className="font-semibold text-center" style={{ color: 'var(--text-muted)' }}>
                                {buzzLocked ? 'Locked out this question' : q.buzz?.holder_name ? `${q.buzz.holder_name} buzzed first…` : 'First buzz answers!'}
                            </p>
                        </>
                    )}
                </div>
            )}

            {(phase === 'reveal' || phase === 'leaderboard') && (
                <div className="flex-1 flex flex-col items-center justify-center gap-3 relative" data-testid="phone-feedback">
                    {privateMsg && privateMsg.correct !== undefined ? (
                        <>
                            <span className={`w-20 h-20 rounded-full flex items-center justify-center tri-pop ${privateMsg.correct || isNumber ? '' : 'tri-shake'}`}
                                style={{ backgroundColor: privateMsg.correct ? 'var(--success-bg)' : isNumber ? 'rgba(249,115,22,0.15)' : 'var(--error-bg)' }}>
                                {privateMsg.correct
                                    ? <Check size={40} strokeWidth={3} style={{ color: 'var(--success)' }} />
                                    : <X size={40} strokeWidth={3} style={{ color: isNumber ? 'var(--primary)' : 'var(--error)' }} />}
                            </span>
                            <h2 className="text-4xl font-bold"
                                style={{ color: privateMsg.correct ? 'var(--success)' : isNumber ? 'var(--primary-soft)' : 'var(--error)' }}>
                                {privateMsg.correct
                                    ? (isNumber ? 'CLOSEST!' : 'CORRECT!')
                                    : (isNumber ? 'NICE TRY' : 'WRONG')}
                            </h2>
                            {privateMsg.gained !== 0 && (
                                <p className="tri-mono text-3xl font-bold" style={{ color: 'var(--text-main)' }}>
                                    {privateMsg.gained > 0 ? `+${privateMsg.gained}` : privateMsg.gained}
                                </p>
                            )}
                            <p className="text-lg font-semibold" style={{ color: 'var(--text-muted)' }}>
                                You&apos;re #{privateMsg.rank} · {privateMsg.score} pts
                            </p>
                        </>
                    ) : (
                        <p className="tri-pulse text-xl" style={{ color: 'var(--text-muted)' }}>Look at the TV…</p>
                    )}
                </div>
            )}

            {phase === 'podium' && (
                <div className="flex-1 flex flex-col items-center justify-center gap-4 relative" data-testid="phone-podium">
                    <h2 className="text-4xl font-bold tri-big-in">Game over!</h2>
                    <p className="text-xl font-semibold">
                        Final score: <span className="tri-mono" style={{ color: 'var(--primary)' }}>{me?.score ?? 0}</span>
                    </p>
                    <p className="tri-pulse" style={{ color: 'var(--text-muted)' }}>Waiting for the host to play again…</p>
                </div>
            )}
        </div>
    );
}
