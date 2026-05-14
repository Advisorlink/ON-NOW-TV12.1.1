/**
 * Watch Together — full party flow in a single page component.
 *
 * Four logical screens, all driven by local state (no React Router
 * sub-routing) so the user can move back/forward via the in-page
 * back button without losing state:
 *   1. 'landing' — Host vs Join cards.
 *   2. 'host'    — Pick a movie via search → confirm → get code.
 *   3. 'join'    — 6-character code entry pad.
 *   4. 'room'    — Live party with sticky member rail + status +
 *                  countdown, plus a Start / Pause / Resume button.
 *                  When the host hits Start, we open Detail via
 *                  navigate('/title/...') with a query flag so the
 *                  Detail page auto-resolves a 1080p stream and
 *                  routes to the Player which propagates play/
 *                  pause/seek events back through the same socket.
 */
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
    ArrowLeft, ArrowRight, Users, Plus, KeyRound, Play, Pause,
    Copy, Check, Search as SearchIcon, Loader2, Sparkles,
} from 'lucide-react';
import { API } from '@/lib/api';
import { getActiveProfile } from '@/lib/profiles';
import { AvatarCircle } from '@/lib/avatars';
import TVKeyboard from '@/components/TVKeyboard';
import SideNav from '@/components/SideNav';

/** WebSocket URL derived from REACT_APP_BACKEND_URL.  Falls back
 *  to wss:// when the page is loaded over https://. */
function wsUrlFor(code) {
    const base = (process.env.REACT_APP_BACKEND_URL || window.location.origin)
        .replace(/^http/, 'ws');
    return `${base}/api/watch-party/ws/${code}`;
}

export default function WatchTogether() {
    const navigate = useNavigate();
    const profile = getActiveProfile() || {};
    const [view, setView] = useState('landing');
    const [partyCode, setPartyCode] = useState(null);
    const [partyState, setPartyState] = useState(null);
    const wsRef = useRef(null);
    const myMemberIdRef = useRef(null);

    /** Open the websocket for the chosen code & role.  Idempotent
     *  while we're still mounted. */
    const connect = (code, role) => {
        const ws = new WebSocket(wsUrlFor(code));
        wsRef.current = ws;
        ws.onopen = () => {
            ws.send(JSON.stringify({
                type: 'hello',
                role,
                name: profile.name || 'Guest',
                avatar: profile.avatarId || 'a1',
            }));
        };
        ws.onmessage = (e) => {
            let payload;
            try { payload = JSON.parse(e.data); } catch { return; }
            if (payload.type === 'joined') {
                myMemberIdRef.current = payload.member_id;
            } else if (payload.type === 'state') {
                setPartyState(payload);
                if (payload.status === 'countdown' || payload.status === 'playing') {
                    if (payload.movie) {
                        // When the host triggers play we route
                        // every client through the Player with the
                        // party code so the Player can rejoin the
                        // socket and stay in sync.
                        const target = payload.movie;
                        const url = `/title/${target.media_type}/${target.tmdb_id}?party=${code}&at_ms=${payload.at_ms}&position_ms=${payload.position_ms}`;
                        navigate(url);
                    }
                }
            }
        };
        ws.onclose = () => {
            wsRef.current = null;
        };
    };

    useEffect(() => () => {
        if (wsRef.current) try { wsRef.current.close(); } catch { /* ignore */ }
    }, []);

    const send = (msg) => {
        if (wsRef.current && wsRef.current.readyState === 1) {
            wsRef.current.send(JSON.stringify(msg));
        }
    };

    const startHost = async () => {
        const r = await fetch(`${API}/watch-party/create`, { method: 'POST' });
        const j = await r.json();
        setPartyCode(j.code);
        setView('room');
        connect(j.code, 'host');
    };

    const joinAs = (code) => {
        setPartyCode(code);
        setView('room');
        connect(code, 'guest');
    };

    return (
        <div
            className="relative w-screen flex"
            style={{
                background: 'var(--vesper-bg-0)',
                height: '100dvh',
                color: 'var(--vesper-text)',
                overflow: 'hidden',
            }}
            data-testid="watch-together"
        >
            <SideNav />
            <main
                className="flex-1 overflow-y-auto"
                style={{
                    paddingLeft: 'clamp(140px, 10vw, 200px)',
                    paddingRight: 'clamp(40px, 5vw, 80px)',
                    paddingTop: 'clamp(32px, 4vw, 64px)',
                    paddingBottom: 64,
                }}
            >
                {view === 'landing' && (
                    <Landing
                        onHost={startHost}
                        onJoin={() => setView('join')}
                    />
                )}
                {view === 'join' && (
                    <JoinView
                        onBack={() => setView('landing')}
                        onJoin={joinAs}
                    />
                )}
                {view === 'room' && partyCode && (
                    <Room
                        code={partyCode}
                        state={partyState}
                        myMemberId={myMemberIdRef.current}
                        onPickMovie={(movie) => send({ type: 'pick', payload: movie })}
                        onStart={() => send({ type: 'play', lead_ms: 3000 })}
                        onBack={() => {
                            if (wsRef.current) try { wsRef.current.close(); } catch { /* ignore */ }
                            setPartyCode(null);
                            setPartyState(null);
                            setView('landing');
                        }}
                    />
                )}
            </main>
        </div>
    );
}

/* --------------------------- Landing ---------------------------- */

function Landing({ onHost, onJoin }) {
    return (
        <div data-testid="watch-together-landing" className="flex flex-col" style={{ gap: 24 }}>
            <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                <Users size={14} strokeWidth={1.8} style={{ display: 'inline', marginRight: 8, verticalAlign: 'middle' }} />
                WATCH TOGETHER
            </div>
            <h1 className="vesper-display" style={{ fontSize: 'clamp(36px, 5vw, 72px)', letterSpacing: '-0.025em', lineHeight: 1.02 }}>
                Pick a movie. <span style={{ color: 'var(--vesper-blue-bright)' }}>Share a code.</span><br />
                And we&apos;ll <em style={{ fontStyle: 'normal' }}>press play at the same time</em> for you.
            </h1>
            <p style={{ color: 'var(--vesper-text-2)', fontSize: 16, maxWidth: '60ch' }}>
                Host a private party, send the 6-character code to friends, and the
                movie starts on every screen the moment you hit play.  Pause, resume
                and seek stay perfectly in sync.
            </p>
            <div className="grid" style={{ gridTemplateColumns: 'repeat(2, minmax(280px, 1fr))', gap: 'clamp(16px, 1.8vw, 28px)', marginTop: 12, maxWidth: 920 }}>
                <ChoiceCard
                    testid="watch-together-host"
                    title="Host a party"
                    subtitle="Pick the movie and share a code"
                    icon={<Plus size={32} strokeWidth={1.6} />}
                    onClick={onHost}
                    primary
                />
                <ChoiceCard
                    testid="watch-together-join"
                    title="Join a party"
                    subtitle="Enter a friend's 6-character code"
                    icon={<KeyRound size={32} strokeWidth={1.6} />}
                    onClick={onJoin}
                />
            </div>
        </div>
    );
}

function ChoiceCard({ testid, title, subtitle, icon, onClick, primary }) {
    return (
        <button
            data-testid={testid}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={onClick}
            className="text-left rounded-2xl flex flex-col"
            style={{
                padding: 'clamp(24px, 2.5vw, 36px)',
                gap: 16,
                background: primary
                    ? 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.55), rgba(var(--vesper-blue-rgb),0.18) 70%)'
                    : 'rgba(255,255,255,0.04)',
                border: primary
                    ? '1px solid rgba(var(--vesper-blue-rgb),0.7)'
                    : '1px solid rgba(255,255,255,0.08)',
                cursor: 'pointer',
                color: 'var(--vesper-text)',
                boxShadow: primary ? '0 18px 50px -20px rgba(var(--vesper-blue-rgb),0.6)' : 'none',
                minHeight: 200,
            }}
        >
            <div style={{
                width: 56, height: 56, borderRadius: '50%',
                background: primary ? 'var(--vesper-blue)' : 'rgba(var(--vesper-blue-rgb),0.18)',
                color: primary ? 'var(--vesper-bg-0)' : 'var(--vesper-blue-bright)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>{icon}</div>
            <div>
                <div style={{ fontSize: 'clamp(20px, 2vw, 28px)', fontWeight: 700, letterSpacing: '-0.01em' }}>{title}</div>
                <div style={{ color: 'var(--vesper-text-2)', fontSize: 14, marginTop: 4 }}>{subtitle}</div>
            </div>
        </button>
    );
}

/* --------------------------- Join ------------------------------- */

function JoinView({ onBack, onJoin }) {
    const [code, setCode] = useState('');
    const [error, setError] = useState('');
    const canJoin = code.replace(/[^A-Z0-9]/g, '').length === 6;
    const submit = async () => {
        const clean = code.replace(/[^A-Z0-9]/g, '').toUpperCase();
        if (clean.length !== 6) return;
        try {
            const r = await fetch(`${API}/watch-party/state/${clean}`);
            const j = await r.json();
            if (j.error === 'not_found') {
                setError('No party with that code.  Double-check with your host.');
                return;
            }
            onJoin(clean);
        } catch {
            setError('Couldn\u2019t reach the party server.');
        }
    };
    return (
        <div data-testid="watch-together-join-view" className="flex flex-col items-center" style={{ width: '100%', position: 'relative' }}>
            <button
                data-testid="watch-together-join-back"
                data-focusable="true"
                tabIndex={0}
                onClick={onBack}
                className="flex items-center justify-center rounded-full self-start"
                style={{ width: 48, height: 48, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--vesper-text-2)', marginBottom: 12 }}
            >
                <ArrowLeft size={20} />
            </button>
            {/* Faint blue glow behind the card */}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    inset: '40px 18% auto 18%',
                    height: '32vh',
                    background: 'radial-gradient(60% 60% at 50% 0%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 70%)',
                    pointerEvents: 'none',
                    filter: 'blur(20px)',
                }}
            />
            <div className="flex flex-col items-center" style={{ maxWidth: 760, width: '100%', position: 'relative', zIndex: 1, gap: 10 }}>
                <div style={{
                    width: 84, height: 84, borderRadius: 999,
                    background: 'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb),0.35) 0%, rgba(var(--vesper-blue-rgb),0.12) 70%)',
                    border: '2px solid rgba(var(--vesper-blue-rgb),0.55)',
                    boxShadow: '0 12px 36px rgba(var(--vesper-blue-rgb),0.35)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: 'var(--vesper-blue-bright)',
                }}>
                    <KeyRound size={36} strokeWidth={1.8} />
                </div>
                <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)', textTransform: 'uppercase' }}>
                    Join a party
                </div>
                <h1 className="vesper-display" style={{ fontSize: 'clamp(26px, 3vw, 44px)', letterSpacing: '-0.02em', lineHeight: 1.05, textAlign: 'center' }}>
                    Enter the{' '}
                    <span style={{ color: 'var(--vesper-blue-bright)', textShadow: '0 0 14px rgba(var(--vesper-blue-rgb),0.55)' }}>
                        6-character code
                    </span>
                </h1>
                {/* Display-only code preview pill — mirrors search input */}
                <div
                    data-testid="watch-together-code"
                    className="flex items-center gap-3"
                    style={{
                        width: '100%', maxWidth: 480, height: 64, padding: '0 24px',
                        borderRadius: 999,
                        background: 'linear-gradient(180deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0.03) 100%)',
                        border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                        boxShadow: '0 10px 36px rgba(var(--vesper-blue-rgb),0.18)',
                        marginTop: 4,
                        justifyContent: 'center',
                    }}
                >
                    <span style={{
                        fontFamily: '"JetBrains Mono", monospace',
                        fontSize: 28, fontWeight: 700,
                        letterSpacing: '0.32em',
                        color: code ? 'var(--vesper-blue-bright)' : 'var(--vesper-text-3)',
                        textShadow: code ? '0 0 14px rgba(var(--vesper-blue-rgb),0.45)' : 'none',
                    }}>
                        {code.padEnd(6, '·')}
                    </span>
                </div>
                <div style={{ marginTop: 4, width: '100%', maxWidth: 720 }}>
                    <TVKeyboard
                        value={code}
                        onChange={(v) => { setCode(v.toUpperCase().slice(0, 6)); if (error) setError(''); }}
                        onSubmit={submit}
                        maxLength={6}
                        variant="name"
                    />
                </div>
                {error && (
                    <div style={{ color: '#FCA5A5', fontSize: 13 }}>{error}</div>
                )}
                <button
                    data-testid="watch-together-join-submit"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={submit}
                    disabled={!canJoin}
                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
                    style={{
                        marginTop: 4,
                        height: 50, padding: '0 30px', fontSize: 15,
                        background: canJoin ? 'var(--vesper-blue)' : 'rgba(var(--vesper-blue-rgb),0.25)',
                        color: 'var(--vesper-bg-0)',
                        border: 'none',
                        opacity: canJoin ? 1 : 0.6,
                        cursor: canJoin ? 'pointer' : 'not-allowed',
                        boxShadow: canJoin ? '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)' : 'none',
                    }}
                >
                    <KeyRound size={16} strokeWidth={2.5} />
                    Join party
                    <ArrowRight size={16} strokeWidth={2.5} />
                </button>
            </div>
        </div>
    );
}

/* --------------------------- Room ------------------------------- */

function Room({ code, state, myMemberId, onPickMovie, onStart, onBack }) {
    const [copied, setCopied] = useState(false);
    const me = state?.members?.find((m) => m.id === myMemberId);
    const iAmHost = !!me?.is_host;
    const movie = state?.movie;
    const status = state?.status || 'lobby';

    return (
        <div data-testid="watch-together-room" className="flex flex-col" style={{ gap: 24, maxWidth: 1240 }}>
            <div className="flex items-center" style={{ gap: 14 }}>
                <button
                    data-testid="watch-together-room-back"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={onBack}
                    className="flex items-center justify-center rounded-full"
                    style={{ width: 48, height: 48, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--vesper-text-2)' }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div>
                    <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                        PARTY CODE
                    </div>
                    <div className="flex items-center" style={{ gap: 14 }}>
                        <span
                            data-testid="watch-together-room-code"
                            style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 'clamp(36px, 4.4vw, 64px)', letterSpacing: 'clamp(4px, 0.6vw, 10px)', fontWeight: 800, color: 'var(--vesper-text)' }}
                        >
                            {code}
                        </span>
                        <button
                            data-testid="watch-together-copy-code"
                            data-focusable="true"
                            tabIndex={0}
                            onClick={() => {
                                navigator.clipboard?.writeText(code);
                                setCopied(true);
                                setTimeout(() => setCopied(false), 1400);
                            }}
                            className="rounded-full flex items-center gap-2"
                            style={{ height: 38, padding: '0 14px', fontSize: 13, fontWeight: 600, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--vesper-text)' }}
                        >
                            {copied ? <Check size={14} /> : <Copy size={14} />}
                            {copied ? 'Copied' : 'Copy'}
                        </button>
                    </div>
                </div>
            </div>

            {/* Members rail */}
            <div className="flex flex-wrap" style={{ gap: 14 }}>
                {(state?.members || []).map((m) => (
                    <div
                        key={m.id}
                        data-testid={`party-member-${m.id}`}
                        className="flex items-center"
                        style={{
                            padding: '8px 14px 8px 8px',
                            borderRadius: 999,
                            background: m.is_host ? 'rgba(var(--vesper-blue-rgb),0.16)' : 'rgba(255,255,255,0.05)',
                            border: m.is_host ? '1px solid rgba(var(--vesper-blue-rgb),0.45)' : '1px solid rgba(255,255,255,0.08)',
                            gap: 10,
                        }}
                    >
                        <AvatarCircle avatarId={m.avatar} size={36} />
                        <div style={{ fontSize: 14 }}>
                            <strong style={{ color: 'var(--vesper-text)' }}>{m.name}</strong>
                            {m.is_host && <span style={{ marginLeft: 6, fontSize: 10, fontWeight: 700, letterSpacing: '0.16em', color: 'var(--vesper-blue-bright)' }}>HOST</span>}
                        </div>
                    </div>
                ))}
                {(!state?.members || state.members.length === 0) && (
                    <div style={{ color: 'var(--vesper-text-3)', fontSize: 14 }}>Waiting for members to join…</div>
                )}
            </div>

            {/* Movie picker (host only) / Preview (guests) */}
            {!movie ? (
                iAmHost ? (
                    <MoviePicker onPick={onPickMovie} />
                ) : (
                    <div
                        data-testid="watch-together-waiting-host"
                        style={{
                            padding: 'clamp(24px, 3vw, 40px)',
                            borderRadius: 16,
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            color: 'var(--vesper-text-2)',
                            fontSize: 16,
                        }}
                    >
                        <Loader2 className="vesper-spin" size={20} style={{ display: 'inline', marginRight: 8, verticalAlign: 'middle' }} />
                        Waiting for the host to pick a movie…
                    </div>
                )
            ) : (
                <MoviePreview
                    movie={movie}
                    status={status}
                    iAmHost={iAmHost}
                    onStart={onStart}
                />
            )}
        </div>
    );
}

function MoviePicker({ onPick }) {
    const [q, setQ] = useState('');
    const [results, setResults] = useState([]);
    const [busy, setBusy] = useState(false);
    const [searched, setSearched] = useState(false);
    const submit = async () => {
        if (q.trim().length < 2) return;
        setBusy(true);
        try {
            const r = await fetch(`${API}/tmdb/search?q=${encodeURIComponent(q.trim())}`);
            const j = await r.json();
            setResults(Array.isArray(j?.data) ? j.data : []);
            setSearched(true);
        } catch { setResults([]); } finally { setBusy(false); }
    };
    const canSearch = q.trim().length >= 2 && !busy;
    return (
        <div className="flex flex-col" style={{ gap: 24, width: '100%' }}>
            {/* Search card — mirrors the Search page's centered card */}
            {(!searched || results.length === 0) && !busy && (
                <div
                    data-testid="party-search-card"
                    className="flex flex-col items-center"
                    style={{ width: '100%', position: 'relative', marginBottom: 4 }}
                >
                    <div
                        aria-hidden="true"
                        style={{
                            position: 'absolute',
                            inset: '0 18% auto 18%',
                            height: '32vh',
                            background: 'radial-gradient(60% 60% at 50% 0%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 70%)',
                            pointerEvents: 'none',
                            filter: 'blur(20px)',
                        }}
                    />
                    <div className="flex flex-col items-center" style={{ maxWidth: 760, width: '100%', position: 'relative', zIndex: 1, gap: 10 }}>
                        <div style={{
                            width: 84, height: 84, borderRadius: 999,
                            background: 'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb),0.35) 0%, rgba(var(--vesper-blue-rgb),0.12) 70%)',
                            border: '2px solid rgba(var(--vesper-blue-rgb),0.55)',
                            boxShadow: '0 12px 36px rgba(var(--vesper-blue-rgb),0.35)',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            color: 'var(--vesper-blue-bright)',
                        }}>
                            <SearchIcon size={36} strokeWidth={1.8} />
                        </div>
                        <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)', textTransform: 'uppercase' }}>
                            Host a party · Pick a title
                        </div>
                        <h2 className="vesper-display" style={{ fontSize: 'clamp(26px, 3vw, 44px)', letterSpacing: '-0.02em', lineHeight: 1.05, textAlign: 'center' }}>
                            What do you want to{' '}
                            <span style={{ color: 'var(--vesper-blue-bright)', textShadow: '0 0 14px rgba(var(--vesper-blue-rgb),0.55)' }}>
                                watch
                            </span>{' '}
                            together?
                        </h2>
                        <div
                            data-testid="party-search-input-wrap"
                            className="flex items-center gap-3"
                            style={{
                                width: '100%', maxWidth: 560, height: 64, padding: '0 24px',
                                borderRadius: 999,
                                background: 'linear-gradient(180deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0.03) 100%)',
                                border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                                boxShadow: '0 10px 36px rgba(var(--vesper-blue-rgb),0.18)',
                                marginTop: 4,
                            }}
                        >
                            <SearchIcon size={20} strokeWidth={2} color="var(--vesper-blue-bright)" />
                            <div
                                data-testid="party-search-input"
                                className="vesper-display"
                                style={{
                                    flex: 1, fontSize: 22, fontWeight: 500, letterSpacing: '-0.01em',
                                    color: q ? 'var(--vesper-text)' : 'var(--vesper-text-3)',
                                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                }}
                            >
                                {q || 'Title, actor, keyword…'}
                                <span aria-hidden="true" style={{
                                    display: 'inline-block', width: 2, height: 22, marginLeft: 4,
                                    verticalAlign: 'middle', background: 'var(--vesper-blue-bright)',
                                    animation: 'vesperPulse 1100ms infinite', borderRadius: 1,
                                }} />
                            </div>
                            <span className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.22em', color: 'var(--vesper-text-3)', textTransform: 'uppercase' }}>
                                {q.length}/60
                            </span>
                        </div>
                        <div style={{ marginTop: 4, width: '100%', maxWidth: 720 }}>
                            <TVKeyboard value={q} onChange={(v) => { setQ(v); if (searched) setSearched(false); }} onSubmit={submit} maxLength={60} variant="name" />
                        </div>
                        <button
                            data-testid="party-search-submit"
                            data-focusable="true"
                            tabIndex={0}
                            onClick={submit}
                            disabled={!canSearch}
                            className="flex items-center gap-2 rounded-full font-sans font-semibold"
                            style={{
                                marginTop: 4,
                                height: 50, padding: '0 30px', fontSize: 15,
                                background: canSearch ? 'var(--vesper-blue)' : 'rgba(var(--vesper-blue-rgb),0.25)',
                                color: 'var(--vesper-bg-0)', border: 'none',
                                opacity: canSearch ? 1 : 0.6,
                                cursor: canSearch ? 'pointer' : 'not-allowed',
                                boxShadow: canSearch ? '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)' : 'none',
                            }}
                        >
                            {busy ? <Loader2 className="vesper-spin" size={16} strokeWidth={2.5} /> : <SearchIcon size={16} strokeWidth={2.5} />}
                            Search
                            {!busy && <ArrowRight size={16} strokeWidth={2.5} />}
                        </button>
                    </div>
                </div>
            )}
            {busy ? (
                <div className="flex items-center gap-3" style={{ color: 'var(--vesper-text-2)', justifyContent: 'center', marginTop: 24 }}>
                    <Loader2 className="vesper-spin" size={20} /> Searching…
                </div>
            ) : searched && results.length > 0 ? (
                <>
                    <h2 className="vesper-display" style={{ fontSize: 24, letterSpacing: '-0.02em', marginBottom: 12 }}>
                        {results.length} result{results.length === 1 ? '' : 's'} for &ldquo;{q}&rdquo;
                    </h2>
                    <div className="flex flex-wrap" style={{ gap: 14 }}>
                        {results.map((item) => (
                            <button
                                key={`${item.type}-${item.tmdb_id}`}
                                data-testid={`party-pick-${item.type}-${item.tmdb_id}`}
                                data-focusable="true"
                                tabIndex={0}
                                onClick={() => onPick({
                                    tmdb_id: String(item.tmdb_id),
                                    media_type: item.type === 'series' ? 'tv' : 'movie',
                                    title: item.title,
                                    poster: item.poster,
                                    year: item.year || '',
                                })}
                                className="rounded-xl overflow-hidden text-left"
                                style={{ width: 'clamp(120px, 10vw, 160px)', aspectRatio: '2 / 3', padding: 0, background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)' }}
                            >
                                {item.poster && (
                                    <img src={item.poster} alt="" loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                                )}
                            </button>
                        ))}
                    </div>
                </>
            ) : searched && results.length === 0 ? (
                <p style={{ color: 'var(--vesper-text-2)', textAlign: 'center' }}>
                    No results for &ldquo;{q}&rdquo;.
                </p>
            ) : null}
        </div>
    );
}

function MoviePreview({ movie, status, iAmHost, onStart }) {
    return (
        <div
            data-testid="watch-together-preview"
            className="flex"
            style={{ gap: 'clamp(20px, 2.4vw, 36px)', padding: 'clamp(20px, 2vw, 32px)', borderRadius: 18, background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)' }}
        >
            {movie.poster && (
                <img src={movie.poster} alt="" style={{ width: 180, height: 270, borderRadius: 12, objectFit: 'cover', flexShrink: 0 }} />
            )}
            <div className="flex flex-col" style={{ gap: 16, flex: 1 }}>
                <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.28em', color: 'var(--vesper-blue-bright)' }}>
                    SELECTED · {movie.media_type === 'tv' ? 'TV SHOW' : 'MOVIE'}
                </div>
                <h2 className="vesper-display" style={{ fontSize: 'clamp(26px, 3vw, 44px)', letterSpacing: '-0.02em', lineHeight: 1.04 }}>
                    {movie.title}
                </h2>
                {movie.year && (
                    <div style={{ color: 'var(--vesper-text-2)' }}>{movie.year}</div>
                )}
                <div style={{ color: 'var(--vesper-text-2)', maxWidth: '60ch' }}>
                    {status === 'lobby'
                        ? (iAmHost
                            ? 'When everyone is ready, hit Start — your party will see a 3-2-1 countdown then the movie will play in sync.'
                            : 'Your host will start the movie shortly.  Hang tight!')
                        : status === 'countdown'
                        ? 'Get ready — the party is starting in 3, 2, 1…'
                        : 'Playing now.'}
                </div>
                {iAmHost && status !== 'playing' && (
                    <button
                        data-testid="watch-together-start"
                        data-focusable="true"
                        tabIndex={0}
                        onClick={onStart}
                        className="flex items-center gap-2 rounded-full font-sans font-semibold self-start"
                        style={{
                            height: 56, padding: '0 32px', fontSize: 16,
                            background: 'var(--vesper-blue)', color: 'var(--vesper-bg-0)',
                            border: 'none', boxShadow: '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)',
                        }}
                    >
                        <Play size={18} strokeWidth={2.5} fill="currentColor" />
                        Start the party
                    </button>
                )}
            </div>
        </div>
    );
}
