// v2.8.82 — Phone-as-microphone receiver (TV side).
//
// Mounts inside the karaoke flow (Sing Your Own + KaraokeStage) and
// listens to the party state for the "next singer" arming event.
// When `current_singer_id` is set AND `mic_armed` is true, we show
// a full-screen overlay:
//
//     "Up next: [Name]
//      Waiting for them to turn on their mic on their phone."
//
// As soon as the phone publishes a WebRTC `offer`, we open a peer
// connection, accept the incoming audio track, and pipe it through
// the Web Audio API to the TV speakers (mixing on top of the music
// the player is already playing).
//
// The TV is the WebRTC ANSWERER — the phone makes the offer.  We
// keep state minimal: one peer connection at a time, torn down when
// the current singer changes or the host advances the queue.

import React, { useEffect, useRef, useState } from 'react';
import { Mic } from 'lucide-react';
import { karaokeAPI, readPartySession } from '../lib/karaoke-party-api';

const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }];

async function postSignal(code, kind, payload) {
    try {
        await fetch(`/api/karaoke/party/${code}/mic/signal`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                from_id: 'tv',
                to_id: payload?.to_id || 'tv',  // we always reply to the singer; caller overrides
                kind,
                payload: payload || {},
            }),
        });
    } catch { /* swallow */ }
}

export default function KaraokeMicReceiver({ partySession }) {
    const [party, setParty] = useState(null);
    const [pcState, setPcState] = useState('idle');
    const pcRef = useRef(null);
    const audioCtxRef = useRef(null);
    const audioElRef = useRef(null);
    const processedRef = useRef(new Set());
    const currentSingerRef = useRef(null);
    // v2.8.86 — Queue ICE candidates that arrive BEFORE the offer
    // has been processed (very common race — the phone's
    // onicecandidate fires while the offer HTTP POST is still
    // in-flight, so candidates land in the signals[] array at the
    // same time as the offer or even slightly before).  Without
    // this queue we silently drop those early candidates and the
    // peer connection never finds a working path → user reports
    // "no sound from phone reaches TV".
    const pendingIceRef = useRef([]);
    const remoteDescSetRef = useRef(false);

    const code = partySession?.code || readPartySession()?.code;

    // -- party polling --------------------------------------------------
    useEffect(() => {
        if (!code) return undefined;
        let cancelled = false;
        let since = 0;
        const loop = async () => {
            while (!cancelled) {
                try {
                    const r = await karaokeAPI.poll(code, since);
                    if (cancelled) break;
                    if (r.party && !r.unchanged) {
                        since = r.party.updated_at;
                        setParty(r.party);
                    }
                } catch {
                    await new Promise((res) => setTimeout(res, 3000));
                }
            }
        };
        loop();
        return () => { cancelled = true; };
    }, [code]);

    // -- handle current-singer changes ----------------------------------
    useEffect(() => {
        if (!party) return;
        const newSinger = party.current_singer_id || null;
        if (newSinger !== currentSingerRef.current) {
            currentSingerRef.current = newSinger;
            teardownPeer();          // singer changed → clear any prior peer
            processedRef.current = new Set();
        }
        // Process inbound signals
        (party.signals || []).forEach((sig) => {
            if (sig.to_id !== 'tv') return;
            if (processedRef.current.has(sig.id)) return;
            processedRef.current.add(sig.id);
            handleSignal(sig).catch(() => { /* ignore */ });
        });
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [party]);

    useEffect(() => () => teardownPeer(), []);

    function teardownPeer() {
        if (pcRef.current) {
            try { pcRef.current.close(); } catch { /* ignore */ }
            pcRef.current = null;
        }
        if (audioCtxRef.current) {
            try { audioCtxRef.current.close(); } catch { /* ignore */ }
            audioCtxRef.current = null;
        }
        if (audioElRef.current) {
            audioElRef.current.srcObject = null;
        }
        pendingIceRef.current = [];
        remoteDescSetRef.current = false;
        setPcState('idle');
    }

    async function handleSignal(sig) {
        const singerId = sig.from_id;
        if (sig.kind === 'offer' && sig.payload?.sdp) {
            // Tear down any prior peer (e.g. singer re-tapped)
            teardownPeer();
            const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS });
            pcRef.current = pc;
            pc.ontrack = (e) => {
                // v2.8.82 — Mix the singer's voice into the TV
                // speakers.  We need to hook it up to a real
                // <audio> element AND a Web Audio MediaStreamSource
                // for a touch of reverb later.  For now: just play
                // through the <audio> element so the user hears
                // themselves through the TV.
                const stream = e.streams[0];
                if (audioElRef.current) {
                    audioElRef.current.srcObject = stream;
                    audioElRef.current.play().catch(() => { /* autoplay */ });
                }
                // Optional reverb via Web Audio (kept minimal —
                // any AudioContext failure shouldn't break playback).
                try {
                    audioCtxRef.current = new (window.AudioContext
                        || window.webkitAudioContext)();
                    const src = audioCtxRef.current.createMediaStreamSource(stream);
                    const gain = audioCtxRef.current.createGain();
                    gain.gain.value = 1.0;
                    src.connect(gain).connect(audioCtxRef.current.destination);
                } catch { /* not critical */ }
            };
            pc.onicecandidate = (e) => {
                if (e.candidate) {
                    postSignal(code, 'ice', { candidate: e.candidate, to_id: singerId });
                }
            };
            pc.onconnectionstatechange = () => {
                if (!pcRef.current) return;
                setPcState(pcRef.current.connectionState);
            };
            await pc.setRemoteDescription(sig.payload.sdp);
            remoteDescSetRef.current = true;
            const answer = await pc.createAnswer();
            await pc.setLocalDescription(answer);
            await postSignal(code, 'answer', { sdp: pc.localDescription, to_id: singerId });
            // Flush any ICE candidates that arrived BEFORE the offer
            // was applied.  Now that the remote description is set,
            // they can be added safely.
            const queued = pendingIceRef.current;
            pendingIceRef.current = [];
            for (const cand of queued) {
                try { await pc.addIceCandidate(cand); } catch { /* drop */ }
            }
        } else if (sig.kind === 'ice' && sig.payload?.candidate) {
            // v2.8.86 — If the offer hasn't been applied yet, QUEUE
            // the candidate instead of dropping it.  This was the
            // root cause of the "WebRTC never connects" bug.
            if (!pcRef.current || !remoteDescSetRef.current) {
                pendingIceRef.current.push(sig.payload.candidate);
            } else {
                try { await pcRef.current.addIceCandidate(sig.payload.candidate); }
                catch { /* drop */ }
            }
        } else if (sig.kind === 'bye') {
            teardownPeer();
        }
    }

    // Render the "Waiting for singer" overlay only while the mic is
    // still armed (= singer hasn't tapped yet).  Once they tap, the
    // overlay disappears and the song just plays normally with the
    // singer's voice mixed in.
    const showWaiting = party
        && party.mic_armed
        && party.current_singer_id
        && pcState !== 'connected';

    const singer = party?.members?.find((m) => m.id === party?.current_singer_id);

    return (
        <>
            {/* Hidden audio element that plays the remote stream */}
            <audio ref={audioElRef} autoPlay playsInline data-testid="tv-mic-audio" />

            {showWaiting && (
                <div
                    className="kk-mic-waiting"
                    data-testid="tv-mic-waiting"
                    data-state={pcState}
                >
                    <div className="kk-mic-waiting__inner">
                        <p className="kk-mic-waiting__eyebrow">UP NEXT</p>
                        <h1 className="kk-mic-waiting__name">
                            {singer ? singer.name : 'A singer'}
                        </h1>
                        <div className="kk-mic-waiting__avatar">
                            {singer?.avatar
                                ? <img src={singer.avatar} alt="" />
                                : <span>{singer?.name?.[0]?.toUpperCase() || '?'}</span>}
                        </div>
                        <p className="kk-mic-waiting__hint">
                            <Mic size={18} />
                            Waiting for {singer ? singer.name : 'them'} to turn on their mic…
                        </p>
                        <p className="kk-mic-waiting__sub">
                            On their phone they&apos;ll see a glowing microphone &mdash;
                            once they tap it, the song begins.
                        </p>
                    </div>
                </div>
            )}
        </>
    );
}
