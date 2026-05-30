// ON NOW TV Tunes — Friends Sing Along lobby (v2.8.74).
//
// 3-column layout exactly per the supplied "Friends Sing Along" design:
//
//   [ QR code panel  ]  [ Joined (N) list ]  [ Up Next (N) queue ]
//
// Top bar: PARTY LOBBY eyebrow + Friends Sing Along title + party code.
// Bottom bar: persistent music transport (rendered by MusicLayout) +
// a big START SINGING button bottom-right.
//
// On first mount we create a fresh party via the backend, store the
// code+host_id in localStorage, and start polling for live state.
// The polling uses long-poll so the UI updates the instant a guest
// joins or queues a song.

import React, { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { QRCodeSVG } from 'qrcode.react';
import { Music, Mic, Loader2, Shuffle } from 'lucide-react';
import {
    karaokeAPI, readPartySession, writePartySession, clearPartySession,
} from '../../lib/karaoke-party-api';

const HOST_DEFAULT_NAME = 'You';

export default function KaraokeFriendsLobby() {
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const wantRandom = params.get('mode') === 'random';

    const [party, setParty] = useState(null);
    const [error, setError] = useState(null);
    const [bootstrapping, setBootstrapping] = useState(true);
    const [hostId, setHostId] = useState(null);
    const sinceRef = useRef(0);

    /* ----------------------------------------------------------
     * Bootstrap: reuse the in-progress party if one exists in
     * localStorage AND is still alive; otherwise create a fresh
     * one with the user as the host.
     * --------------------------------------------------------- */
    useEffect(() => {
        let alive = true;
        const bootstrap = async () => {
            const session = readPartySession();
            try {
                if (session.code && session.memberId) {
                    const { party: existing } = await karaokeAPI.get(session.code);
                    if (alive) {
                        setParty(existing);
                        setHostId(session.memberId);
                        sinceRef.current = existing.updated_at;
                        setBootstrapping(false);
                        return;
                    }
                }
            } catch { /* fall through to fresh create */ }
            try {
                const { party: created, host_id } = await karaokeAPI.create(HOST_DEFAULT_NAME);
                if (!alive) return;
                if (wantRandom) {
                    try {
                        const r = await karaokeAPI.setMode(created.code, 'random');
                        setParty(r.party);
                        sinceRef.current = r.party.updated_at;
                    } catch {
                        setParty(created);
                        sinceRef.current = created.updated_at;
                    }
                } else {
                    setParty(created);
                    sinceRef.current = created.updated_at;
                }
                setHostId(host_id);
                writePartySession({ code: created.code, memberId: host_id });
            } catch (e) {
                if (alive) setError(e.message || 'Failed to create party');
            } finally {
                if (alive) setBootstrapping(false);
            }
        };
        bootstrap();
        return () => { alive = false; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    /* ----------------------------------------------------------
     * Long-poll for live updates.  Re-arms itself after every
     * response so the lobby stays in sync with guest activity.
     * --------------------------------------------------------- */
    useEffect(() => {
        if (!party) return undefined;
        let alive = true;
        const loop = async () => {
            while (alive) {
                try {
                    const r = await karaokeAPI.poll(party.code, sinceRef.current);
                    if (!alive) break;
                    if (r.party && !r.unchanged) {
                        setParty(r.party);
                        sinceRef.current = r.party.updated_at;
                    }
                } catch {
                    await new Promise((res) => setTimeout(res, 3000));
                }
            }
        };
        loop();
        return () => { alive = false; };
    }, [party?.code]);  // restart only on party code change

    const toggleMode = useCallback(async () => {
        if (!party) return;
        try {
            const next = party.mode === 'random' ? 'normal' : 'random';
            const r = await karaokeAPI.setMode(party.code, next);
            setParty(r.party);
            sinceRef.current = r.party.updated_at;
        } catch { /* ignore */ }
    }, [party]);

    const startSinging = useCallback(async () => {
        if (!party || !party.queue.length) return;
        try {
            const r = await karaokeAPI.advance(party.code);
            setParty(r.party);
            sinceRef.current = r.party.updated_at;
            navigate('/music/karaoke/party/stage');
        } catch { /* ignore */ }
    }, [party, navigate]);

    const leaveParty = useCallback(() => {
        clearPartySession();
        navigate('/music/karaoke');
    }, [navigate]);

    if (bootstrapping) {
        return (
            <div className="kk-bootstrap" data-testid="karaoke-lobby-boot">
                <Loader2 className="kk-spin" size={42} />
                <p>Spinning up your party…</p>
            </div>
        );
    }
    if (error || !party) {
        return (
            <div className="tunes-empty" data-testid="karaoke-lobby-error">
                Couldn&apos;t set up the party — {error || 'unknown error'}
            </div>
        );
    }

    const joinUrl = party.join_url;

    return (
        <div className="kk-lobby" data-testid="karaoke-lobby">
            <header className="kk-lobby__head">
                <div>
                    <p className="kk-hero__eyebrow">PARTY LOBBY</p>
                    <h1 className="kk-lobby__title">
                        Friends <em>Sing Along</em>
                    </h1>
                    <p className="kk-lobby__sub">
                        Scan, join, and let&apos;s make this a night to remember.
                    </p>
                </div>
                <div className="kk-lobby__code-card">
                    <p className="kk-hero__eyebrow">PARTY CODE</p>
                    <p className="kk-lobby__code" data-testid="karaoke-lobby-code">
                        {party.code}
                    </p>
                    <p className="kk-lobby__code-help">
                        Share this code with your friends!
                    </p>
                </div>
            </header>

            <div className="kk-lobby__grid">
                {/* Column 1 — QR card */}
                <section
                    className="kk-lobby__qr-panel"
                    data-testid="karaoke-lobby-qr"
                >
                    <p className="kk-lobby__qr-eyebrow">✦ SCAN TO JOIN ✦</p>
                    <p className="kk-lobby__qr-help">
                        Scan the QR code to <br /> join this karaoke party.
                    </p>
                    <div className="kk-lobby__qr-frame">
                        <QRCodeSVG
                            value={joinUrl}
                            size={260}
                            level="M"
                            bgColor="#ffffff"
                            fgColor="#000000"
                            includeMargin
                        />
                    </div>
                    <p className="kk-lobby__qr-url" title={joinUrl}>{joinUrl}</p>
                </section>

                {/* Column 2 — Joined list */}
                <section className="kk-lobby__joined" data-testid="karaoke-lobby-joined">
                    <header>
                        <span className="kk-lobby__dot" /> JOINED ({party.members.length})
                    </header>
                    <ul>
                        {party.members.map((m) => (
                            <li key={m.id}>
                                <Avatar member={m} />
                                <span className="kk-member__name">{m.name}</span>
                                {m.is_host && <span className="kk-host-pill">HOST</span>}
                            </li>
                        ))}
                    </ul>
                </section>

                {/* Column 3 — Queue */}
                <section className="kk-lobby__queue" data-testid="karaoke-lobby-queue">
                    <header>
                        <Music size={16} /> UP NEXT ({party.queue.length})
                    </header>
                    {party.queue.length === 0 && (
                        <p className="kk-empty">
                            Nothing queued yet — guests can scan the QR code and add their songs.
                        </p>
                    )}
                    <ol>
                        {party.queue.map((q, i) => (
                            <li key={q.id}>
                                <span className="kk-queue__num">{i + 1}</span>
                                {q.cover && (
                                    <img
                                        src={q.cover}
                                        alt={q.title}
                                        className="kk-queue__art"
                                        loading="lazy"
                                    />
                                )}
                                <div className="kk-queue__meta">
                                    <p className="kk-queue__title">{q.title}</p>
                                    <p className="kk-queue__artist">{q.artist}</p>
                                </div>
                                <div className="kk-queue__by">
                                    <span>Added by</span>
                                    <Avatar
                                        member={party.members.find((m) => m.id === q.member_id)
                                                || { name: q.member_name }}
                                        size={28}
                                    />
                                </div>
                            </li>
                        ))}
                    </ol>
                </section>
            </div>

            <div className="kk-lobby__actions" data-hero-actions>
                <button
                    type="button"
                    className="kk-btn kk-btn--ghost"
                    onClick={toggleMode}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    data-testid="karaoke-lobby-mode"
                >
                    <Shuffle size={18} />
                    {party.mode === 'random' ? 'Random Queue' : 'Normal Queue'}
                </button>
                <button
                    type="button"
                    className="kk-btn kk-btn--ghost"
                    onClick={leaveParty}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    data-testid="karaoke-lobby-leave"
                >
                    End Party
                </button>
                <button
                    type="button"
                    className="kk-btn kk-btn--primary kk-lobby__start"
                    onClick={startSinging}
                    disabled={party.queue.length === 0}
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    data-testid="karaoke-lobby-start"
                >
                    <Mic size={20} />
                    START SINGING
                </button>
            </div>
        </div>
    );
}

function Avatar({ member, size = 40 }) {
    const initial = (member?.name || '?')[0]?.toUpperCase();
    if (member?.avatar) {
        return (
            <img
                src={member.avatar}
                alt={member.name}
                className="kk-avatar"
                style={{ width: size, height: size }}
            />
        );
    }
    return (
        <span
            className="kk-avatar kk-avatar--initial"
            style={{ width: size, height: size, fontSize: size * 0.42 }}
            aria-hidden="true"
        >
            {initial}
        </span>
    );
}
