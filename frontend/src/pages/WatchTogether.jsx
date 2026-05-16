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
import useSpatialFocus from '@/hooks/useSpatialFocus';
import useBackHandler from '@/hooks/useBackHandler';

/** WebSocket URL derived from REACT_APP_BACKEND_URL.  Falls back
 *  to wss:// when the page is loaded over https://. */
/* Watch Together diagnostic breadcrumbs.  Keep a short rolling log
 * in localStorage so we can inspect AFTER the user reports a bug.
 * Mirrored from Detail.jsx — same key, same shape. */
function partyBreadcrumb(event, info = {}) {
    try {
        // eslint-disable-next-line no-console
        console.log('[watch-party]', event, info);
        const key = 'vesper-party-breadcrumbs';
        const arr = JSON.parse(localStorage.getItem(key) || '[]');
        arr.push({ t: Date.now(), event, info });
        while (arr.length > 80) arr.shift();
        localStorage.setItem(key, JSON.stringify(arr));
    } catch { /* ignore */ }
}

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

    // Global spatial D-pad — same hook used by Home / Detail / Search.
    useSpatialFocus();
    // BACK key → Home.
    useBackHandler('/');

    /** Open the websocket for the chosen code & role.  Idempotent
     *  while we're still mounted. */
    const connect = (code, role) => {
        partyBreadcrumb('lobby:ws-connect', { code, role });
        const ws = new WebSocket(wsUrlFor(code));
        wsRef.current = ws;
        // Track navigation so we only fire it once per countdown.
        let navigated = false;
        ws.onopen = () => {
            partyBreadcrumb('lobby:ws-open', { code, role });
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
                partyBreadcrumb('lobby:joined', { mid: payload.member_id });
                // Stash so the Player can rejoin the same party
                // socket as the same member (host vs guest).
                try {
                    sessionStorage.setItem('vesper-party-code', code);
                    sessionStorage.setItem('vesper-party-role', role);
                    sessionStorage.setItem('vesper-party-member-id', payload.member_id);
                } catch { /* private mode */ }
            } else if (payload.type === 'state') {
                setPartyState(payload);
                if (!navigated && (payload.status === 'loading' || payload.status === 'countdown' || payload.status === 'playing') && payload.movie) {
                    navigated = true;
                    partyBreadcrumb('lobby:navigate', {
                        status: payload.status,
                        media: payload.movie?.media_type,
                        title: payload.movie?.title,
                    });
                    const target = payload.movie;
                    // Close the lobby socket — Player will reopen it
                    // (we can only have one socket per member at a
                    // time, otherwise the server kicks us out).
                    try { ws.close(); } catch { /* ignore */ }
                    wsRef.current = null;
                    /* TV-show episodes route through /title/series/{imdb}
                       with extra season+episode params so Detail.jsx
                       can fire the episode-specific autoplay.  When
                       the host hasn't provided an imdb_id (older
                       client), fall back to /resolve/tv which looks
                       it up before redirecting (preserves the query
                       string). */
                    const partyQS = `party=${code}&autoplay=1&at_ms=${payload.at_ms}&position_ms=${payload.position_ms}`;
                    const epQS = (target.media_type === 'tv' && target.season != null && target.episode != null)
                        ? `&season=${target.season}&episode=${target.episode}`
                        : '';
                    let url;
                    if (target.media_type === 'tv' && target.imdb_id && target.season != null && target.episode != null) {
                        url = `/title/series/${target.imdb_id}?${partyQS}${epQS}`;
                    } else {
                        url = `/resolve/${target.media_type}/${target.tmdb_id}?${partyQS}${epQS}`;
                    }
                    navigate(url);
                }
            }
        };
        ws.onclose = () => {
            partyBreadcrumb('lobby:ws-close', { code });
            if (wsRef.current === ws) wsRef.current = null;
        };
        ws.onerror = () => {
            partyBreadcrumb('lobby:ws-error', { code });
        };
    };

    useEffect(() => () => {
        if (wsRef.current) try { wsRef.current.close(); } catch { /* ignore */ }
    }, []);

    const send = (msg) => {
        if (wsRef.current && wsRef.current.readyState === 1) {
            wsRef.current.send(JSON.stringify(msg));
            return true;
        }
        return false;
    };

    /**
     * Reliable send that waits up to `timeoutMs` for the WebSocket
     * to reach OPEN before transmitting.  Without this, a host who
     * clicks Start Party milliseconds after the lobby has loaded
     * silently drops the `play` message — the server never flips
     * to `loading`, so neither member navigates and the party hangs.
     * The user has reported this exact symptom multiple times.
     */
    const sendReliable = async (msg, timeoutMs = 2500) => {
        const deadline = Date.now() + timeoutMs;
        partyBreadcrumb('lobby:send-start', { type: msg.type });
        while (Date.now() < deadline) {
            const ws = wsRef.current;
            if (ws && ws.readyState === 1) {
                try {
                    ws.send(JSON.stringify(msg));
                    partyBreadcrumb('lobby:send-ok', { type: msg.type });
                    return true;
                } catch (e) {
                    partyBreadcrumb('lobby:send-error', { type: msg.type, err: String(e).slice(0, 120) });
                    return false;
                }
            }
            await new Promise((r) => setTimeout(r, 80));
        }
        partyBreadcrumb('lobby:send-timeout', {
            type: msg.type,
            wsState: wsRef.current ? wsRef.current.readyState : 'no-ws',
        });
        return false;
    };

    // Re-entrancy guard — prevents a double-click (or React.StrictMode
    // dev double-invoke) from firing two parallel POSTs whose Response
    // body can only be read once.  Without this we saw transient
    // "body stream already read" errors in the testing agent.
    const creatingRef = useRef(false);
    const startHost = async () => {
        if (creatingRef.current) return;
        creatingRef.current = true;
        try {
            const r = await fetch(`${API}/watch-party/create`, { method: 'POST' });
            const j = await r.json();
            setPartyCode(j.code);
            setView('room');
            connect(j.code, 'host');
        } catch (err) {
            console.warn('watch-party create failed', err);
        } finally {
            creatingRef.current = false;
        }
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
                    paddingTop: view === 'room' ? 'clamp(16px, 2vw, 28px)' : 'clamp(32px, 4vw, 64px)',
                    paddingBottom: 32,
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
                        onPickMovie={(movie) => sendReliable({ type: 'pick', payload: movie })}
                        onStart={() => sendReliable({ type: 'play', lead_ms: 3000 })}
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
        <div data-testid="watch-together-landing" className="flex flex-col" style={{ gap: 18, maxWidth: 980 }}>
            <div className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                <Users size={14} strokeWidth={1.8} style={{ display: 'inline', marginRight: 8, verticalAlign: 'middle' }} />
                WATCH TOGETHER
            </div>
            <h1 className="vesper-display" style={{ fontSize: 'clamp(28px, 3.2vw, 48px)', letterSpacing: '-0.022em', lineHeight: 1.08 }}>
                Pick a Movie<span style={{ color: 'var(--vesper-text-3)' }}>/</span>Show.{' '}
                <span style={{ color: 'var(--vesper-blue-bright)' }}>Share a code.</span>{' '}
                And we will <em style={{ fontStyle: 'normal' }}>push play</em> for you<span style={{ color: 'var(--vesper-blue-bright)' }}>…</span>
            </h1>
            <p style={{ color: 'var(--vesper-text-2)', fontSize: 14, maxWidth: '62ch', lineHeight: 1.5 }}>
                Host a private party, send the 6-character code to friends, and the
                movie starts on every screen the moment you hit play.  Pause, resume
                and seek stay perfectly in sync.
            </p>
            <div
                className="grid"
                style={{
                    gridTemplateColumns: 'repeat(2, minmax(260px, 1fr))',
                    gap: 'clamp(14px, 1.4vw, 22px)',
                    marginTop: 8,
                    maxWidth: 760,
                }}
            >
                <ChoiceCard
                    testid="watch-together-host"
                    title="Host a party"
                    subtitle="Pick the movie and share a code"
                    icon={<Plus size={26} strokeWidth={1.7} />}
                    onClick={onHost}
                    primary
                    initialFocus
                />
                <ChoiceCard
                    testid="watch-together-join"
                    title="Join a party"
                    subtitle="Enter a friend's 6-character code"
                    icon={<KeyRound size={26} strokeWidth={1.7} />}
                    onClick={onJoin}
                />
            </div>
        </div>
    );
}

function ChoiceCard({ testid, title, subtitle, icon, onClick, primary, initialFocus }) {
    return (
        <button
            data-testid={testid}
            data-focusable="true"
            data-focus-style="tile"
            data-initial-focus={initialFocus ? 'true' : undefined}
            tabIndex={0}
            onClick={onClick}
            className="text-left rounded-2xl flex flex-col"
            style={{
                padding: 'clamp(18px, 1.8vw, 26px)',
                gap: 12,
                background: primary
                    ? 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.55), rgba(var(--vesper-blue-rgb),0.18) 70%)'
                    : 'rgba(255,255,255,0.04)',
                border: primary
                    ? '1px solid rgba(var(--vesper-blue-rgb),0.7)'
                    : '1px solid rgba(255,255,255,0.08)',
                cursor: 'pointer',
                color: 'var(--vesper-text)',
                boxShadow: primary ? '0 18px 50px -20px rgba(var(--vesper-blue-rgb),0.6)' : 'none',
                minHeight: 156,
            }}
        >
            <div style={{
                width: 46, height: 46, borderRadius: '50%',
                background: primary ? 'var(--vesper-blue)' : 'rgba(var(--vesper-blue-rgb),0.18)',
                color: primary ? 'var(--vesper-bg-0)' : 'var(--vesper-blue-bright)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
            }}>{icon}</div>
            <div>
                <div style={{ fontSize: 'clamp(18px, 1.5vw, 22px)', fontWeight: 700, letterSpacing: '-0.01em' }}>{title}</div>
                <div style={{ color: 'var(--vesper-text-2)', fontSize: 13, marginTop: 3 }}>{subtitle}</div>
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
        <div data-testid="watch-together-room" className="flex flex-col" style={{ gap: 14, maxWidth: 1240 }}>
            <div className="flex items-center" style={{ gap: 14 }}>
                <button
                    data-testid="watch-together-room-back"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={onBack}
                    className="flex items-center justify-center rounded-full"
                    style={{ width: 40, height: 40, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--vesper-text-2)' }}
                >
                    <ArrowLeft size={18} />
                </button>
                <div>
                    <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                        PARTY CODE
                    </div>
                    <div className="flex items-center" style={{ gap: 12 }}>
                        <span
                            data-testid="watch-together-room-code"
                            style={{ fontFamily: '"JetBrains Mono", monospace', fontSize: 'clamp(22px, 2.4vw, 34px)', letterSpacing: 'clamp(3px, 0.4vw, 6px)', fontWeight: 800, color: 'var(--vesper-text)' }}
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
                            style={{ height: 30, padding: '0 12px', fontSize: 12, fontWeight: 600, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--vesper-text)' }}
                        >
                            {copied ? <Check size={12} /> : <Copy size={12} />}
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
    /* When the host taps a TV result we don't broadcast the pick
       immediately — we open an in-line season+episode picker first
       so the whole party autoplays the SAME episode.  Movies skip
       this step entirely. */
    const [pendingShow, setPendingShow] = useState(null);
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

    /* If the host has already tapped a TV result, render the
       episode picker instead of the search results. */
    if (pendingShow) {
        return (
            <EpisodePicker
                show={pendingShow}
                onBack={() => setPendingShow(null)}
                onPick={(payload) => {
                    onPick(payload);
                    setPendingShow(null);
                }}
            />
        );
    }

    const handlePickResult = (item) => {
        const base = {
            tmdb_id: String(item.tmdb_id),
            media_type: item.type === 'series' ? 'tv' : 'movie',
            title: item.title,
            poster: item.poster,
            year: item.year || '',
        };
        if (item.type === 'series') {
            setPendingShow(base);
        } else {
            onPick(base);
        }
    };
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
                            height: '24vh',
                            background: 'radial-gradient(60% 60% at 50% 0%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 70%)',
                            pointerEvents: 'none',
                            filter: 'blur(20px)',
                        }}
                    />
                    <div className="flex flex-col items-center" style={{ maxWidth: 760, width: '100%', position: 'relative', zIndex: 1, gap: 6 }}>
                        <div style={{
                            width: 56, height: 56, borderRadius: 999,
                            background: 'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb),0.35) 0%, rgba(var(--vesper-blue-rgb),0.12) 70%)',
                            border: '2px solid rgba(var(--vesper-blue-rgb),0.55)',
                            boxShadow: '0 12px 36px rgba(var(--vesper-blue-rgb),0.35)',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            color: 'var(--vesper-blue-bright)',
                        }}>
                            <SearchIcon size={26} strokeWidth={1.8} />
                        </div>
                        <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)', textTransform: 'uppercase' }}>
                            Host a party · Pick a title
                        </div>
                        <h2 className="vesper-display" style={{ fontSize: 'clamp(20px, 2.2vw, 30px)', letterSpacing: '-0.02em', lineHeight: 1.05, textAlign: 'center' }}>
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
                                width: '100%', maxWidth: 560, height: 52, padding: '0 20px',
                                borderRadius: 999,
                                background: 'linear-gradient(180deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0.03) 100%)',
                                border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                                boxShadow: '0 10px 36px rgba(var(--vesper-blue-rgb),0.18)',
                                marginTop: 2,
                            }}
                        >
                            <SearchIcon size={18} strokeWidth={2} color="var(--vesper-blue-bright)" />
                            <div
                                data-testid="party-search-input"
                                className="vesper-display"
                                style={{
                                    flex: 1, fontSize: 18, fontWeight: 500, letterSpacing: '-0.01em',
                                    color: q ? 'var(--vesper-text)' : 'var(--vesper-text-3)',
                                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                }}
                            >
                                {q || 'Title, actor, keyword…'}
                                <span aria-hidden="true" style={{
                                    display: 'inline-block', width: 2, height: 18, marginLeft: 4,
                                    verticalAlign: 'middle', background: 'var(--vesper-blue-bright)',
                                    animation: 'vesperPulse 1100ms infinite', borderRadius: 1,
                                }} />
                            </div>
                            <span className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.22em', color: 'var(--vesper-text-3)', textTransform: 'uppercase' }}>
                                {q.length}/60
                            </span>
                        </div>
                        <div style={{ marginTop: 2, width: '100%', maxWidth: 720 }}>
                            <TVKeyboard value={q} onChange={(v) => { setQ(v); if (searched) setSearched(false); }} onSubmit={submit} maxLength={60} variant="name" />
                        </div>
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
                                onClick={() => handlePickResult(item)}
                                className="rounded-xl overflow-hidden text-left relative"
                                style={{ width: 'clamp(120px, 10vw, 160px)', aspectRatio: '2 / 3', padding: 0, background: 'rgba(255,255,255,0.04)', border: '1px solid rgba(255,255,255,0.08)' }}
                            >
                                {item.poster && (
                                    <img src={item.poster} alt="" loading="lazy" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                                )}
                                {item.type === 'series' && (
                                    <span
                                        className="vesper-mono"
                                        style={{
                                            position: 'absolute',
                                            top: 8, left: 8,
                                            padding: '3px 8px', borderRadius: 999,
                                            background: 'rgba(6,8,15,0.78)',
                                            border: '1px solid rgba(93,200,255,0.55)',
                                            color: '#8de0ff',
                                            fontSize: 9, letterSpacing: '0.18em',
                                            fontWeight: 700,
                                        }}
                                    >
                                        TV
                                    </span>
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

/* --------------------------- EpisodePicker -------------------------- */

/**
 * After the host picks a TV show, EpisodePicker shows season pills
 * and an episode list so the host can choose the exact episode the
 * party will watch together.
 *
 * Resolves the show's IMDB id (via /api/tmdb/imdb/tv/{tmdb_id}) and
 * fetches Stremio-format metadata (which includes a `videos` array
 * of episodes, each with `season`, `episode`, `name`, `overview`,
 * `released`, `thumbnail`).  The host taps an episode → we call
 * onPick({ ...show, season, episode, episode_title, imdb_id }).
 */
function EpisodePicker({ show, onBack, onPick }) {
    const [imdbId, setImdbId] = useState(null);
    const [meta, setMeta] = useState(null);
    const [busy, setBusy] = useState(true);
    const [err, setErr] = useState(null);
    const [season, setSeason] = useState(1);

    useEffect(() => {
        let cancel = false;
        (async () => {
            setBusy(true);
            setErr(null);
            try {
                const r = await fetch(`${API}/tmdb/imdb/tv/${show.tmdb_id}`, {
                    cache: 'force-cache',
                });
                const data = await r.json();
                const imdb = data?.imdb_id;
                if (!imdb) {
                    if (!cancel) {
                        setErr('Couldn\u2019t resolve the show id.');
                        setBusy(false);
                    }
                    return;
                }
                if (cancel) return;
                setImdbId(imdb);
                /* Fetch Stremio-format meta so we get the
                   season/episode list with names & overviews. */
                const m = await fetch(`${API}/meta/series/${imdb}`);
                const mj = await m.json();
                const fullMeta = mj?.data?.meta || null;
                if (cancel) return;
                if (!fullMeta) {
                    setErr('No episode list available for this show.');
                    setBusy(false);
                    return;
                }
                setMeta(fullMeta);
                /* Default to the first non-zero season. */
                const seasons = Array.from(
                    new Set((fullMeta.videos || [])
                        .map((v) => Number(v.season))
                        .filter((n) => n > 0))
                ).sort((a, b) => a - b);
                if (seasons.length) setSeason(seasons[0]);
                setBusy(false);
            } catch (e) {
                if (!cancel) {
                    setErr('Couldn\u2019t reach the metadata server.');
                    setBusy(false);
                }
            }
        })();
        return () => { cancel = true; };
    }, [show.tmdb_id]);

    const seasons = useMemo(() => {
        if (!meta?.videos) return [];
        return Array.from(
            new Set(meta.videos.map((v) => Number(v.season)).filter((n) => n > 0))
        ).sort((a, b) => a - b);
    }, [meta]);

    const episodes = useMemo(() => {
        if (!meta?.videos) return [];
        return meta.videos
            .filter((v) => Number(v.season) === season)
            .sort((a, b) => Number(a.episode) - Number(b.episode));
    }, [meta, season]);

    const handlePickEpisode = (ep) => {
        onPick({
            ...show,
            imdb_id: imdbId,
            season: Number(ep.season),
            episode: Number(ep.episode),
            episode_title: ep.name || `Episode ${ep.episode}`,
        });
    };

    return (
        <div data-testid="watch-together-episode-picker" className="flex flex-col" style={{ gap: 20, width: '100%' }}>
            <div className="flex items-center" style={{ gap: 14 }}>
                <button
                    data-testid="watch-together-episode-back"
                    data-focusable="true"
                    tabIndex={0}
                    onClick={onBack}
                    className="flex items-center justify-center rounded-full"
                    style={{ width: 40, height: 40, background: 'rgba(255,255,255,0.06)', border: '1px solid rgba(255,255,255,0.12)', color: 'var(--vesper-text-2)' }}
                >
                    <ArrowLeft size={18} />
                </button>
                <div>
                    <div className="vesper-mono" style={{ fontSize: 10, letterSpacing: '0.32em', color: 'var(--vesper-blue-bright)' }}>
                        PICK AN EPISODE
                    </div>
                    <h2 className="vesper-display" style={{ fontSize: 'clamp(22px, 2.4vw, 34px)', letterSpacing: '-0.02em', lineHeight: 1.05 }}>
                        {show.title}
                    </h2>
                </div>
            </div>

            {busy && (
                <div className="flex items-center gap-3" style={{ color: 'var(--vesper-text-2)', justifyContent: 'center', marginTop: 24 }}>
                    <Loader2 className="vesper-spin" size={20} /> Loading episode list…
                </div>
            )}

            {!busy && err && (
                <div style={{ color: '#FCA5A5', fontSize: 14 }}>{err}</div>
            )}

            {!busy && !err && meta && (
                <>
                    {/* Season pills */}
                    <div
                        data-testid="watch-together-season-pills"
                        className="flex flex-wrap"
                        style={{ gap: 8 }}
                    >
                        {seasons.map((s) => {
                            const isActive = s === season;
                            return (
                                <button
                                    key={s}
                                    data-testid={`watch-together-season-${s}`}
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onClick={() => setSeason(s)}
                                    className="rounded-full vesper-mono"
                                    style={{
                                        padding: '8px 16px',
                                        background: isActive ? 'rgba(93,200,255,0.20)' : 'rgba(255,255,255,0.05)',
                                        border: isActive ? '1px solid rgba(93,200,255,0.55)' : '1px solid rgba(255,255,255,0.12)',
                                        color: isActive ? '#8de0ff' : 'var(--vesper-text-2)',
                                        fontSize: 12, letterSpacing: '0.16em',
                                        textTransform: 'uppercase', fontWeight: 700,
                                    }}
                                >
                                    Season {s}
                                </button>
                            );
                        })}
                    </div>

                    {/* Episode list */}
                    <div
                        data-testid="watch-together-episode-list"
                        className="flex flex-col"
                        style={{ gap: 10, maxHeight: '52vh', overflowY: 'auto' }}
                    >
                        {episodes.map((ep) => (
                            <button
                                key={`${ep.season}-${ep.episode}`}
                                data-testid={`watch-together-episode-${ep.season}-${ep.episode}`}
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => handlePickEpisode(ep)}
                                className="text-left rounded-xl flex items-stretch"
                                style={{
                                    padding: 0,
                                    background: 'rgba(255,255,255,0.04)',
                                    border: '1px solid rgba(255,255,255,0.08)',
                                    overflow: 'hidden',
                                }}
                            >
                                {ep.thumbnail && (
                                    <img
                                        src={ep.thumbnail}
                                        alt=""
                                        loading="lazy"
                                        style={{
                                            width: 160,
                                            aspectRatio: '16 / 9',
                                            objectFit: 'cover',
                                            flexShrink: 0,
                                        }}
                                    />
                                )}
                                <div className="flex flex-col" style={{ padding: '14px 18px', flex: 1, gap: 6 }}>
                                    <div className="vesper-mono" style={{
                                        fontSize: 10, letterSpacing: '0.22em',
                                        color: 'var(--vesper-blue-bright)',
                                        textTransform: 'uppercase',
                                    }}>
                                        S{String(ep.season).padStart(2, '0')} · E{String(ep.episode).padStart(2, '0')}
                                    </div>
                                    <div style={{
                                        fontSize: 16, fontWeight: 600, lineHeight: 1.2,
                                        color: 'var(--vesper-text)',
                                    }}>
                                        {ep.name || `Episode ${ep.episode}`}
                                    </div>
                                    {ep.overview && (
                                        <div style={{
                                            fontSize: 13, lineHeight: 1.45,
                                            color: 'var(--vesper-text-2)',
                                            display: '-webkit-box',
                                            WebkitLineClamp: 2,
                                            WebkitBoxOrient: 'vertical',
                                            overflow: 'hidden',
                                        }}>
                                            {ep.overview}
                                        </div>
                                    )}
                                </div>
                            </button>
                        ))}
                        {episodes.length === 0 && (
                            <div style={{ color: 'var(--vesper-text-3)', textAlign: 'center', padding: 24 }}>
                                No episodes found for this season.
                            </div>
                        )}
                    </div>
                </>
            )}
        </div>
    );
}

function MoviePreview({ movie, status, iAmHost, onStart }) {
    const startBtnRef = React.useRef(null);
    const [starting, setStarting] = React.useState(false);
    const [startError, setStartError] = React.useState('');
    /* Reset the error / starting flag whenever the server-reported
     * status moves out of 'lobby' (party kicked off OK) so the
     * button reflects reality. */
    React.useEffect(() => {
        if (status !== 'lobby') {
            setStartError('');
        }
    }, [status]);
    /* For TV shows, surface which episode the host queued up so
       guests know exactly what they're about to watch. */
    const episodeTag = movie?.media_type === 'tv' && movie?.season != null && movie?.episode != null
        ? `S${String(movie.season).padStart(2, '0')}E${String(movie.episode).padStart(2, '0')}`
        : null;
    // Imperative focus: as soon as the host's "Start the party"
    // button mounts (i.e. the moment after picking a movie), focus
    // it so the user can press OK on the remote without needing to
    // hunt for the button with the D-pad.  Retries cover any race
    // with the spatial-focus engine settling.
    React.useEffect(() => {
        if (!iAmHost || status === 'playing') return;
        const tries = [0, 60, 200, 500];
        const timers = tries.map((ms) =>
            setTimeout(() => {
                const el = startBtnRef.current;
                if (el && document.activeElement !== el) {
                    el.focus({ preventScroll: false });
                    // Strip stale focus indicators from other tiles
                    document.querySelectorAll('[data-focused="true"]').forEach((n) => {
                        if (n !== el) n.removeAttribute('data-focused');
                    });
                    el.setAttribute('data-focused', 'true');
                }
            }, ms)
        );
        return () => timers.forEach(clearTimeout);
    }, [iAmHost, status]);
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
                    {episodeTag && <span style={{ marginLeft: 10, color: '#fff' }}>· {episodeTag}</span>}
                </div>
                <h2 className="vesper-display" style={{ fontSize: 'clamp(26px, 3vw, 44px)', letterSpacing: '-0.02em', lineHeight: 1.04 }}>
                    {movie.title}
                </h2>
                {episodeTag && movie.episode_title && (
                    <div className="vesper-mono" style={{
                        fontSize: 12, letterSpacing: '0.14em', color: '#8de0ff',
                        textTransform: 'uppercase',
                    }}>
                        {episodeTag} · {movie.episode_title}
                    </div>
                )}
                {movie.year && (
                    <div style={{ color: 'var(--vesper-text-2)' }}>{movie.year}</div>
                )}
                <div style={{ color: 'var(--vesper-text-2)', maxWidth: '60ch' }}>
                    {status === 'lobby'
                        ? (iAmHost
                            ? `When everyone is ready, hit Start — your party will see a 3-2-1 countdown then the ${movie.media_type === 'tv' ? 'episode' : 'movie'} will play in sync.`
                            : `Your host will start the ${movie.media_type === 'tv' ? 'episode' : 'movie'} shortly.  Hang tight!`)
                        : status === 'countdown'
                        ? 'Get ready — the party is starting in 3, 2, 1…'
                        : 'Playing now.'}
                </div>
                {iAmHost && status !== 'playing' && (
                    <button
                        ref={startBtnRef}
                        data-testid="watch-together-start"
                        data-focusable="true"
                        data-initial-focus="true"
                        tabIndex={0}
                        disabled={starting || status === 'loading' || status === 'countdown'}
                        onClick={async () => {
                            if (starting) return;
                            setStarting(true);
                            try {
                                const ok = await onStart();
                                if (!ok) {
                                    setStartError('Connection still warming up — try again in a second.');
                                    setStarting(false);
                                }
                                // On success we keep `starting` true so the
                                // button stays disabled until the state
                                // transitions to 'loading' (server flip).
                            } catch (e) {
                                setStartError('Could not start the party. Try again.');
                                setStarting(false);
                            }
                        }}
                        className="flex items-center gap-2 rounded-full font-sans font-semibold self-start"
                        style={{
                            height: 56, padding: '0 32px', fontSize: 16,
                            background: 'var(--vesper-blue)', color: 'var(--vesper-bg-0)',
                            border: 'none', boxShadow: '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)',
                            opacity: (starting || status === 'loading' || status === 'countdown') ? 0.7 : 1,
                            cursor: (starting || status === 'loading' || status === 'countdown') ? 'wait' : 'pointer',
                        }}
                    >
                        {(status === 'loading' || status === 'countdown') ? (
                            <Loader2 className="vesper-spin" size={18} />
                        ) : starting ? (
                            <Loader2 className="vesper-spin" size={18} />
                        ) : (
                            <Play size={18} strokeWidth={2.5} fill="currentColor" />
                        )}
                        {status === 'loading' ? 'Starting party…'
                            : status === 'countdown' ? 'Get ready…'
                            : starting ? 'Sending…'
                            : 'Start the party'}
                    </button>
                )}
                {startError && (
                    <div
                        data-testid="watch-together-start-error"
                        style={{
                            color: '#FFB5B5', fontSize: 13,
                            background: 'rgba(255,99,99,0.08)',
                            border: '1px solid rgba(255,99,99,0.35)',
                            padding: '8px 14px', borderRadius: 10,
                            alignSelf: 'flex-start',
                        }}
                    >
                        {startError}
                    </div>
                )}
            </div>
        </div>
    );
}
