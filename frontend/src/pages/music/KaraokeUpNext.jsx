// ON NOW TV Tunes — Up Next standalone page (v2.8.74).
//
// Shows the current party's queue + history.  Reached via the "Up
// Next" tile on the karaoke home.  Read-only — the host manages the
// queue from inside the lobby/stage.

import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Mic, Music } from 'lucide-react';
import {
    karaokeAPI, readPartySession,
} from '../../lib/karaoke-party-api';

export default function KaraokeUpNext() {
    const [party, setParty] = useState(null);
    const [missing, setMissing] = useState(false);

    useEffect(() => {
        let alive = true;
        const sess = readPartySession();
        if (!sess.code) { setMissing(true); return undefined; }
        karaokeAPI.get(sess.code)
            .then((r) => alive && setParty(r.party))
            .catch(() => alive && setMissing(true));
        return () => { alive = false; };
    }, []);

    if (missing) {
        return (
            <div className="kk-empty-state" data-testid="karaoke-up-next-empty">
                <h2>No active party</h2>
                <p>Start a karaoke party first to see who&apos;s singing next.</p>
                <Link to="/music/karaoke/party" className="kk-btn kk-btn--primary">
                    <Mic size={18} /> Start a Party
                </Link>
            </div>
        );
    }
    if (!party) return <div className="tunes-empty">Loading…</div>;

    return (
        <div className="kk-upnext" data-testid="karaoke-up-next">
            <header>
                <p className="kk-hero__eyebrow">QUEUE · {party.code}</p>
                <h1 className="kk-sing__title">Up Next</h1>
            </header>

            {party.current && (
                <section className="kk-upnext__now">
                    <p className="kk-hero__eyebrow">NOW SINGING</p>
                    <NowEntry q={party.current} />
                </section>
            )}

            <section className="kk-upnext__list">
                <header><Music size={16} /> Queue ({party.queue.length})</header>
                {party.queue.length === 0 && (
                    <p className="kk-empty">No songs queued.</p>
                )}
                <ol>
                    {party.queue.map((q, i) => (
                        <li key={q.id}>
                            <span className="kk-queue__num">{i + 1}</span>
                            {q.cover && <img src={q.cover} alt="" loading="lazy" />}
                            <div className="kk-queue__meta">
                                <p>{q.title}</p>
                                <small>{q.artist} · {q.member_name}</small>
                            </div>
                        </li>
                    ))}
                </ol>
            </section>
        </div>
    );
}

function NowEntry({ q }) {
    return (
        <div className="kk-now-entry">
            {q.cover && <img src={q.cover} alt="" />}
            <div>
                <p className="kk-queue__title">{q.title}</p>
                <p className="kk-queue__artist">{q.artist}</p>
                <p className="kk-queue__by">{q.member_name}</p>
            </div>
        </div>
    );
}
