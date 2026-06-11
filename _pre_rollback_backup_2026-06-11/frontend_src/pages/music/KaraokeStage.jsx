// ON NOW TV Tunes — Karaoke Stage (v2.8.74).
//
// Per the supplied "Karaoke Player" design.
//
// Renders ON TOP of the normal music FullScreenPlayer by opening the
// player in karaoke mode + injecting a top-of-screen HUD with:
//   - Now Singing avatar pill (member name + points)
//   - Challenge Active pill (centered, only when challenge != null)
//   - Up Next card (next singer's avatar + song title)
//
// We DON'T render lyrics here — the FullScreenPlayer's
// KaraokeLyricsOverlay already does that (centered ticker, pink-glow
// active line, etc.).  This page just wires up the party HUD and the
// "advance queue when song ends" behaviour.

import React, { useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Mic, Music, MicOff } from 'lucide-react';
import {
    karaokeAPI, readPartySession, challengeMeta,
} from '../../lib/karaoke-party-api';
import { useMusicPlayer } from '../../hooks/useMusicPlayer';
import KaraokeMicReceiver from '../../components/KaraokeMicReceiver';

const KARAOKE_FLAG_KEY = 'tunes-karaoke-mode';

export default function KaraokeStage() {
    const navigate = useNavigate();
    const { state, controls } = useMusicPlayer();
    const [party, setParty] = useState(null);
    const lastTrackRef = useRef(null);
    const pollRef = useRef(null);

    // Boot: load party state.
    useEffect(() => {
        let alive = true;
        const sess = readPartySession();
        if (!sess.code) { navigate('/music/karaoke'); return undefined; }
        karaokeAPI.get(sess.code)
            .then((r) => alive && setParty(r.party))
            .catch(() => alive && navigate('/music/karaoke'));
        return () => { alive = false; };
    }, [navigate]);

    // v2.8.85 — Long-poll the party so we react when the singer's
    // phone flips `mic_armed` from true → false (= "mic on, ready").
    // Without this the TV never knew it was time to start playing.
    useEffect(() => {
        const sess = readPartySession();
        if (!sess.code) return undefined;
        let alive = true;
        let since = 0;
        (async () => {
            while (alive) {
                try {
                    const r = await karaokeAPI.poll(sess.code, since);
                    if (!alive) break;
                    if (r.party && !r.unchanged) {
                        since = r.party.updated_at;
                        setParty(r.party);
                    }
                } catch {
                    await new Promise((res) => setTimeout(res, 3000));
                }
            }
        })();
        pollRef.current = () => { alive = false; };
        return () => { alive = false; };
    }, []);

    // v2.8.85 — Start playback ONLY when the mic is no longer armed
    // (singer tapped "Turn on your mic" on their phone → backend
    // flipped mic_armed=false via /mic/on).  Previously the TV
    // auto-played the moment `party.current` was set, which meant
    // the song started before the singer was ready.
    useEffect(() => {
        if (!party?.current) return;
        if (party.mic_armed) return;  // wait for singer's mic
        if (lastTrackRef.current === party.current.id) return;
        lastTrackRef.current = party.current.id;
        try { sessionStorage.setItem(KARAOKE_FLAG_KEY, '1'); } catch { /* ignore */ }
        const track = {
            id: party.current.track_id,
            title: party.current.title,
            artist: { name: party.current.artist },
            album: party.current.cover
                ? { cover: party.current.cover, cover_medium: party.current.cover, cover_big: party.current.cover }
                : {},
        };
        controls.playTrack(track, [track]);
        setTimeout(() => {
            try { window.dispatchEvent(new CustomEvent('tunes:open-fullscreen')); }
            catch { /* ignore */ }
        }, 50);
    }, [party?.current?.id, party?.mic_armed, controls]);

    // When the song finishes, auto-advance the party queue.
    const wasPlayingRef = useRef(false);
    useEffect(() => {
        if (state.isPlaying) wasPlayingRef.current = true;
        if (
            wasPlayingRef.current
            && !state.isPlaying
            && state.position > 0
            && state.duration > 0
            && state.position >= state.duration - 1
        ) {
            wasPlayingRef.current = false;
            const sess = readPartySession();
            if (sess.code) {
                karaokeAPI.advance(sess.code)
                    .then((r) => setParty(r.party))
                    .catch(() => {});
            }
        }
    }, [state.isPlaying, state.position, state.duration]);

    // Background poll for updates (challenge toggle, queue changes).
    useEffect(() => {
        if (!party) return undefined;
        let alive = true;
        let since = party.updated_at;
        const loop = async () => {
            while (alive) {
                try {
                    const r = await karaokeAPI.poll(party.code, since);
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
    }, [party?.code]);

    if (!party) return null;

    const nowSinging = party.current
        ? party.members.find((m) => m.id === party.current.member_id)
        : null;
    const upNextEntry = party.queue[0] || null;
    const upNextMember = upNextEntry
        ? party.members.find((m) => m.id === upNextEntry.member_id)
        : null;
    const challenge = challengeMeta(party.challenge);

    return (
        <div className="kk-stage-hud" data-testid="karaoke-stage-hud">
            {/* v2.8.85 — Mount the WebRTC mic receiver + "Up next:
                Alex" waiting overlay here on the TV side.  Until the
                singer taps "Turn on your mic" on their phone, the
                overlay covers the screen and the song does NOT
                start playing (KaraokeStage's playTrack effect is
                gated on `!party.mic_armed`). */}
            <KaraokeMicReceiver partySession={{ code: party.code }} />

            {nowSinging && (
                <div className="kk-stage-hud__now" data-testid="karaoke-now-singing">
                    <Avatar member={nowSinging} size={56} />
                    <div>
                        <p className="kk-stage-hud__label">NOW SINGING</p>
                        <p className="kk-stage-hud__name">
                            {nowSinging.name}
                            <span className="kk-stage-hud__points">
                                ★ {nowSinging.points.toLocaleString()}
                            </span>
                        </p>
                    </div>
                </div>
            )}

            {challenge && (
                <div className="kk-stage-hud__challenge" data-testid="karaoke-challenge-active">
                    <MicOff size={18} />
                    <div>
                        <p className="kk-stage-hud__label">CHALLENGE ACTIVE</p>
                        <p className="kk-stage-hud__name">{challenge.title}</p>
                    </div>
                </div>
            )}

            {upNextEntry && upNextMember && (
                <div className="kk-stage-hud__upnext" data-testid="karaoke-up-next-pill">
                    <p className="kk-stage-hud__label">UP NEXT</p>
                    <div className="kk-stage-hud__upnext-body">
                        <Avatar member={upNextMember} size={44} />
                        <div>
                            <p className="kk-stage-hud__name">{upNextMember.name}</p>
                            <p className="kk-stage-hud__song">{upNextEntry.title}</p>
                            <p className="kk-stage-hud__song-sub">{upNextEntry.artist}</p>
                        </div>
                        <Music size={18} className="kk-stage-hud__upnext-note" />
                    </div>
                </div>
            )}
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
        >{initial}</span>
    );
}
