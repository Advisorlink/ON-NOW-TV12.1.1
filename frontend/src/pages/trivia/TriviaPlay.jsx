import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import useTriviaSocket from './useTriviaSocket';
import useTriviaSounds from './useTriviaSounds';
import './trivia.css';

const OPT_COLORS = ['#FF007A', '#E1FF00', '#00F0FF', '#00FF66'];
const OPT_KEYS = ['A', 'B', 'C', 'D'];
const buzzMs = (ms) => { try { navigator.vibrate?.(ms); } catch { /* ignore */ } };

function NumberPad({ onLock, unit, sounds }) {
    const [val, setVal] = useState('');
    const tap = (k) => {
        buzzMs(12);
        sounds?.play('click', 0.4);
        if (k === '⌫') setVal((v) => v.slice(0, -1));
        else setVal((v) => (v.length >= 9 || (k === '.' && v.includes('.')) ? v : v + k));
    };
    const num = parseFloat(val);
    return (
        <div className="flex-1 flex flex-col gap-3 relative min-h-0" data-testid="phone-number-pad">
            <div className="tri-glass flex items-baseline justify-center gap-2 py-4">
                <span className="tri-mono text-4xl font-extrabold" style={{ color: '#00F0FF' }}>{val || '···'}</span>
                {unit ? <span className="text-zinc-400 font-bold">{unit}</span> : null}
            </div>
            <div className="grid grid-cols-3 gap-2 flex-1">
                {['1', '2', '3', '4', '5', '6', '7', '8', '9', '.', '0', '⌫'].map((k) => (
                    <button key={k} onClick={() => tap(k)}
                        data-testid={`phone-num-${k === '⌫' ? 'del' : k === '.' ? 'dot' : k}`}
                        className="tri-tactile tri-mono text-3xl font-extrabold"
                        style={{ backgroundColor: 'rgba(255,255,255,0.09)', color: '#fff' }}>{k}</button>
                ))}
            </div>
            <button disabled={Number.isNaN(num)} onClick={() => onLock(num)}
                data-testid="phone-num-lock"
                className="tri-tactile py-4 text-2xl font-black uppercase tracking-widest disabled:opacity-40"
                style={{ backgroundColor: '#00F0FF', color: '#000' }}>
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
    useEffect(() => { setPicked(null); }, [q?.index, phase === 'question']);

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
            <div className="trivia-root flex flex-col items-center justify-center p-6 gap-6" data-testid="phone-join-screen">
                <div className="tri-bgfx" />
                <h1 className="text-4xl font-black tracking-tighter text-center">
                    ON NOW <span style={{ color: '#00F0FF' }}>TRIVIA</span>
                </h1>
                <p className="text-zinc-400 text-center">
                    Room <span className="tri-mono font-extrabold" style={{ color: '#00F0FF' }}>{room || '—'}</span>
                    {' '}· your phone becomes the buzzer
                </p>
                <form onSubmit={join} className="w-full max-w-sm flex flex-col gap-4 relative">
                    <input value={name} onChange={(e) => setName(e.target.value)}
                        placeholder="Your name" maxLength={18} autoFocus
                        data-testid="phone-name-input"
                        className="w-full text-2xl font-bold text-center rounded-2xl px-5 py-5 bg-white/10 border border-white/15 text-white placeholder-zinc-500 focus:outline-none focus:border-cyan-400" />
                    <button type="submit" disabled={!name.trim() || !room}
                        data-testid="phone-join-btn"
                        className="tri-tactile w-full py-5 text-2xl font-black uppercase tracking-widest disabled:opacity-40"
                        style={{ backgroundColor: '#00F0FF', color: '#000' }}>
                        Save &amp; Join
                    </button>
                    {!room && <p className="text-red-400 text-center">No room code — scan the QR on the TV</p>}
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
            <div className="flex items-center justify-between text-sm font-bold relative">
                <span className="truncate">{name}</span>
                <span className="tri-mono" style={{ color: connected ? '#00FF66' : '#FF3B30' }}>
                    {connected ? `● ${room}` : 'reconnecting…'}
                </span>
                <span className="tri-mono" style={{ color: '#00F0FF' }} data-testid="phone-score">{me?.score ?? 0} pts</span>
            </div>

            {(phase === 'lobby' || phase === 'loading') && (
                <div className="flex-1 flex flex-col items-center justify-center gap-4 relative" data-testid="phone-waiting">
                    <div className="w-16 h-16 rounded-full tri-pop flex items-center justify-center font-black text-3xl text-black"
                        style={{ backgroundColor: me?.color || '#00F0FF' }}>{(name || '?')[0].toUpperCase()}</div>
                    <h2 className="text-3xl font-black text-center">You&apos;re in!</h2>
                    <p className="text-zinc-400 tri-pulse text-lg text-center">Look at the TV — waiting for the host to start</p>
                </div>
            )}

            {phase === 'countdown' && (
                <div className="flex-1 flex items-center justify-center relative">
                    <h2 className="text-4xl font-black tri-pulse">Get ready…</h2>
                </div>
            )}

            {phase === 'question' && q && !isBuzzer && isNumber && (
                iAnswered ? (
                    <div className="flex-1 flex flex-col items-center justify-center gap-3 relative" data-testid="phone-locked-in">
                        <h2 className="tri-mono text-5xl font-extrabold" style={{ color: '#00F0FF' }}>
                            {Number(picked).toLocaleString()}
                        </h2>
                        <p className="text-zinc-400 tri-pulse">Guess locked in…</p>
                    </div>
                ) : (
                    <NumberPad unit={q.unit} sounds={sounds}
                        onLock={(v) => { setPicked(v); buzzMs(30); send({ type: 'answer', answer: v }); }} />
                )
            )}

            {phase === 'question' && q && !isBuzzer && !isNumber && (
                iAnswered ? (
                    <div className="flex-1 flex flex-col items-center justify-center gap-3 relative" data-testid="phone-locked-in">
                        <h2 className="text-4xl font-black" style={{ color: OPT_COLORS[picked % 4] }}>
                            {OPT_KEYS[picked]} locked in!
                        </h2>
                        <p className="text-zinc-400 tri-pulse">Fingers crossed…</p>
                    </div>
                ) : (
                    <div className="flex-1 flex flex-col gap-3 relative min-h-0">
                        {q.image && (
                            <img src={q.image} alt="" data-testid="phone-question-image"
                                className="h-28 mx-auto rounded-xl object-contain border border-white/15" />
                        )}
                        <div className={`flex-1 grid gap-3 ${q.options.length === 2 ? 'grid-rows-2' : 'grid-cols-2 grid-rows-2'}`}>
                            {q.options.map((opt, i) => (
                                <button key={i} data-testid={`phone-answer-${i}`}
                                    onClick={() => answerTap(i)}
                                    className="tri-tactile flex flex-col items-center justify-center gap-1 p-3"
                                    style={{ backgroundColor: OPT_COLORS[i % 4], color: (i === 1 || i === 3) ? '#000' : '#fff' }}>
                                    <span className="tri-mono text-3xl font-extrabold">{OPT_KEYS[i]}</span>
                                    <span className="text-lg font-bold leading-tight text-center">{opt}</span>
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
                                    className="tri-tactile flex flex-col items-center justify-center gap-1 p-3"
                                    style={{ backgroundColor: OPT_COLORS[i % 4], color: (i === 1 || i === 3) ? '#000' : '#fff' }}>
                                    <span className="tri-mono text-2xl font-extrabold">{OPT_KEYS[i]}</span>
                                    <span className="text-base font-bold leading-tight text-center">{opt}</span>
                                </button>
                            ))}
                        </div>
                    ) : (
                        <>
                            <button className="tri-buzzer font-black text-4xl text-white uppercase"
                                data-testid="phone-buzzer"
                                disabled={!!q.buzz?.holder || buzzLocked}
                                onClick={() => { buzzMs(60); sounds.play('buzz', 0.7); send({ type: 'buzz' }); }}>
                                BUZZ
                            </button>
                            <p className="text-zinc-400 font-bold text-center">
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
                            <h2 className={`text-5xl font-black tri-pop ${privateMsg.correct || isNumber ? '' : 'tri-shake'}`}
                                style={{ color: privateMsg.correct ? '#00FF66' : isNumber ? '#E1FF00' : '#FF3B30' }}>
                                {privateMsg.correct
                                    ? (isNumber ? 'CLOSEST!' : 'CORRECT!')
                                    : (isNumber ? 'NICE TRY' : 'WRONG')}
                            </h2>
                            {privateMsg.gained !== 0 && (
                                <p className="tri-mono text-3xl font-extrabold" style={{ color: '#00F0FF' }}>
                                    {privateMsg.gained > 0 ? `+${privateMsg.gained}` : privateMsg.gained}
                                </p>
                            )}
                            <p className="text-zinc-400 text-xl font-bold">You&apos;re #{privateMsg.rank} · {privateMsg.score} pts</p>
                        </>
                    ) : (
                        <p className="text-zinc-400 tri-pulse text-xl">Look at the TV…</p>
                    )}
                </div>
            )}

            {phase === 'podium' && (
                <div className="flex-1 flex flex-col items-center justify-center gap-4 relative" data-testid="phone-podium">
                    <h2 className="text-4xl font-black tri-big-in">Game over!</h2>
                    <p className="text-zinc-300 text-xl font-bold">Final score: <span className="tri-mono" style={{ color: '#00F0FF' }}>{me?.score ?? 0}</span></p>
                    <p className="text-zinc-500 tri-pulse">Waiting for the host to play again…</p>
                </div>
            )}
        </div>
    );
}
