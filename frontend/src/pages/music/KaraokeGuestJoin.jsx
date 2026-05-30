// ON NOW TV Tunes — Karaoke guest join page (v2.8.74).
//
// Rendered at /karaoke/join/{code} OUTSIDE the music layout.  This is
// what a guest sees on their phone after scanning the QR code from
// the TV lobby.  Two phases:
//
//   1. Name entry — guest types their name, taps Join → POSTs to
//      /api/karaoke/party/{code}/join → gets a member_id back.
//   2. Song picker — search bar + scrollable results.  Tapping a
//      result POSTs it to the queue; a tick + toast confirms.
//
// The guest's name + party + member_id persist in localStorage so
// they can refresh / kill the browser / come back later.

import React, { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Mic, Music, Plus, Check, Loader2, Search as SearchIcon, LogOut } from 'lucide-react';
import { musicAPI } from '../../lib/music-api';
import {
    karaokeAPI, readPartySession, writePartySession, clearPartySession,
} from '../../lib/karaoke-party-api';
import './karaoke-party.css';

const LS_NAME = 'tunes-karaoke-guest-name';

export default function KaraokeGuestJoin() {
    const { code: rawCode } = useParams();
    const code = (rawCode || '').toUpperCase();

    const [phase, setPhase] = useState('loading');     // loading | enter | songs | error
    const [error, setError] = useState(null);
    const [party, setParty] = useState(null);
    const [memberId, setMemberId] = useState(null);
    const [name, setName] = useState(() => localStorage.getItem(LS_NAME) || '');
    const [submitting, setSubmitting] = useState(false);

    /* Boot: check session, fetch party state. */
    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const { party: existing } = await karaokeAPI.get(code);
                if (!alive) return;
                setParty(existing);
                const sess = readPartySession();
                const stillMember = sess.code === code
                    && sess.memberId
                    && existing.members.some((m) => m.id === sess.memberId);
                if (stillMember) {
                    setMemberId(sess.memberId);
                    setPhase('songs');
                } else {
                    setPhase('enter');
                }
            } catch (e) {
                if (alive) {
                    setError(e.message || 'Failed to load party');
                    setPhase('error');
                }
            }
        })();
        return () => { alive = false; };
    }, [code]);

    /* Background poll for updates so the queue/joined list is always
       fresh after the guest has joined. */
    useEffect(() => {
        if (phase !== 'songs' || !party) return undefined;
        let alive = true;
        let since = party.updated_at;
        const loop = async () => {
            while (alive) {
                try {
                    const r = await karaokeAPI.poll(code, since);
                    if (!alive) break;
                    if (r.party && !r.unchanged) {
                        setParty(r.party);
                        since = r.party.updated_at;
                    }
                } catch {
                    await new Promise((res) => setTimeout(res, 3000));
                }
            }
        };
        loop();
        return () => { alive = false; };
    }, [phase, code, party?.updated_at]);

    const onJoin = async (e) => {
        e?.preventDefault?.();
        if (!name.trim()) return;
        setSubmitting(true);
        try {
            const r = await karaokeAPI.join(code, name.trim());
            setMemberId(r.member_id);
            setParty(r.party);
            writePartySession({ code, memberId: r.member_id });
            localStorage.setItem(LS_NAME, name.trim());
            setPhase('songs');
        } catch (err) {
            setError(err.message || 'Could not join');
        } finally {
            setSubmitting(false);
        }
    };

    const leave = () => {
        clearPartySession();
        localStorage.removeItem(LS_NAME);
        setPhase('enter');
        setMemberId(null);
    };

    if (phase === 'loading') {
        return (
            <div className="kk-guest" data-testid="karaoke-guest-loading">
                <Loader2 className="kk-spin" size={42} />
                <p>Loading party…</p>
            </div>
        );
    }
    if (phase === 'error') {
        return (
            <div className="kk-guest kk-guest--error" data-testid="karaoke-guest-error">
                <p>{error || 'Party not found'}</p>
            </div>
        );
    }
    if (phase === 'enter') {
        return (
            <GuestJoinForm
                code={code}
                name={name}
                setName={setName}
                onJoin={onJoin}
                submitting={submitting}
                error={error}
            />
        );
    }
    return (
        <GuestSongPicker
            party={party}
            memberId={memberId}
            onLeave={leave}
        />
    );
}

function GuestJoinForm({ code, name, setName, onJoin, submitting, error }) {
    return (
        <form className="kk-guest kk-guest--enter" onSubmit={onJoin} data-testid="karaoke-guest-form">
            <div className="kk-guest__hero">
                <div className="kk-guest__mic-glow"><Mic size={64} strokeWidth={1.6} /></div>
                <p className="kk-guest__eyebrow">JOIN THE PARTY</p>
                <h1 className="kk-guest__title">{code}</h1>
                <p className="kk-guest__sub">
                    Type your name and start queuing songs.
                </p>
            </div>

            <label className="kk-guest__field">
                <span>Your name</span>
                <input
                    autoFocus
                    placeholder="e.g. Jamie"
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    maxLength={40}
                    data-testid="karaoke-guest-name-input"
                />
            </label>

            {error && <p className="kk-guest__error">{error}</p>}

            <button
                type="submit"
                className="kk-btn kk-btn--primary kk-guest__cta"
                disabled={!name.trim() || submitting}
                data-testid="karaoke-guest-join-btn"
            >
                {submitting ? <Loader2 className="kk-spin" size={18} /> : <Mic size={18} />}
                Join the Party
            </button>
        </form>
    );
}

function GuestSongPicker({ party, memberId, onLeave }) {
    const me = party.members.find((m) => m.id === memberId);
    const myQueued = party.queue.filter((q) => q.member_id === memberId);
    const others = party.queue.filter((q) => q.member_id !== memberId);

    const [q, setQ] = useState('');
    const [results, setResults] = useState(null);
    const [loading, setLoading] = useState(false);
    const [adding, setAdding] = useState(null);

    useEffect(() => {
        const t = q.trim();
        if (!t) { setResults(null); return; }
        let alive = true;
        setLoading(true);
        const h = setTimeout(async () => {
            try {
                const r = await musicAPI.search(t);
                if (!alive) return;
                setResults(r.data?.tracks || r.tracks || []);
            } catch {
                if (alive) setResults([]);
            } finally {
                if (alive) setLoading(false);
            }
        }, 280);
        return () => { alive = false; clearTimeout(h); };
    }, [q]);

    const addSong = async (track) => {
        setAdding(track.id);
        try {
            await karaokeAPI.addSong(party.code, memberId, track);
        } catch { /* ignore */ }
        setTimeout(() => setAdding(null), 1200);
    };

    const removeMine = async (entryId) => {
        try { await karaokeAPI.removeSong(party.code, memberId, entryId); }
        catch { /* ignore */ }
    };

    return (
        <div className="kk-guest kk-guest--songs" data-testid="karaoke-guest-picker">
            <header className="kk-guest__bar">
                <div>
                    <p className="kk-guest__pcode">{party.code}</p>
                    <p className="kk-guest__who">Singing as <strong>{me?.name || 'Guest'}</strong></p>
                </div>
                <button
                    type="button"
                    className="kk-iconbtn"
                    onClick={onLeave}
                    aria-label="Leave party"
                    data-testid="karaoke-guest-leave"
                >
                    <LogOut size={18} />
                </button>
            </header>

            <div className="kk-guest__search">
                <SearchIcon size={20} />
                <input
                    placeholder="Search any song to queue…"
                    value={q}
                    onChange={(e) => setQ(e.target.value)}
                    data-testid="karaoke-guest-search-input"
                />
            </div>

            {results !== null && (
                <section className="kk-guest__results" data-testid="karaoke-guest-results">
                    {loading && <p className="kk-empty">Searching…</p>}
                    {!loading && results.length === 0 && <p className="kk-empty">No results.</p>}
                    {results.map((t) => (
                        <button
                            key={t.id}
                            type="button"
                            className="kk-guest__songrow"
                            onClick={() => addSong(t)}
                            disabled={adding === t.id}
                            data-testid={`karaoke-guest-song-${t.id}`}
                        >
                            <img
                                src={t.album?.cover_medium || t.album?.cover}
                                alt=""
                                loading="lazy"
                            />
                            <div className="kk-guest__songinfo">
                                <p>{t.title}</p>
                                <small>{t.artist?.name}</small>
                            </div>
                            <span className="kk-guest__add" aria-hidden="true">
                                {adding === t.id ? <Check size={20} /> : <Plus size={20} />}
                            </span>
                        </button>
                    ))}
                </section>
            )}

            {results === null && (
                <>
                    <section className="kk-guest__mine" data-testid="karaoke-guest-mine">
                        <header><Music size={16} /> Your songs ({myQueued.length})</header>
                        {myQueued.length === 0 && (
                            <p className="kk-empty">
                                Add as many songs as you like — they&apos;ll show up in the TV queue.
                            </p>
                        )}
                        {myQueued.map((q) => (
                            <button
                                key={q.id}
                                type="button"
                                className="kk-guest__queued"
                                onClick={() => removeMine(q.id)}
                                data-testid={`karaoke-guest-mine-${q.id}`}
                            >
                                {q.cover && <img src={q.cover} alt="" loading="lazy" />}
                                <div>
                                    <p>{q.title}</p>
                                    <small>{q.artist}</small>
                                </div>
                                <span className="kk-guest__remove">Remove</span>
                            </button>
                        ))}
                    </section>

                    {others.length > 0 && (
                        <section className="kk-guest__others" data-testid="karaoke-guest-others">
                            <header><Mic size={16} /> Everyone else ({others.length})</header>
                            {others.map((q) => (
                                <div key={q.id} className="kk-guest__queued kk-guest__queued--other">
                                    {q.cover && <img src={q.cover} alt="" loading="lazy" />}
                                    <div>
                                        <p>{q.title}</p>
                                        <small>{q.artist} · {q.member_name}</small>
                                    </div>
                                </div>
                            ))}
                        </section>
                    )}
                </>
            )}
        </div>
    );
}
