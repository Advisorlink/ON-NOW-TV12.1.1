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

// v2.8.87 — CRITICAL FIX.  The previous version used relative URLs
// (`/api/karaoke/party/.../mic/signal`) which is fatal on the APK:
// the WebView is loaded from `https://appassets.androidplatform.net/`
// so relative `/api/...` resolves to that origin, gets intercepted
// by `WebViewAssetLoader`, and returns 404 → the TV's WebRTC answer
// and ICE candidates are SILENTLY DROPPED → the phone never reaches
// `connected` state → user reports "no sound from the phone reaches
// the TV speakers."  Always go through REACT_APP_BACKEND_URL.
const API_BASE = (process.env.REACT_APP_BACKEND_URL || '').replace(/\/$/, '');

// v2.8.131 — TURBO MODE RECEIVER.
//
// Listens on the raw-PCM relay WebSocket (`/api/karaoke/mic-stream/
// {code}?role=tv`).  Plays incoming Int16 frames through Web Audio
// with a SMALL ring buffer (we deliberately don't buffer for
// network jitter — we want sub-50 ms latency, and a glitch is far
// better than the smoothness of WebRTC's 100 ms jitter buffer).
//
// This is a JS stopgap.  The Phase 3 native Android receiver in
// the karaoke APK will be even tighter (~10 ms via AudioTrack).
class TurboReceiver {
    constructor(code) {
        this.code = code;
        this.ws = null;
        this.ctx = null;
        this.sampleRate = 24000;  // default; replaced on `fmt` msg
        this.nextStartTime = 0;
        this.scheduledAhead = 0.040;  // 40 ms playout cushion
    }

    start() {
        const wsProto = (window.location.protocol === 'https:') ? 'wss:' : 'ws:';
        // We deliberately use the SAME-ORIGIN protocol/host so the
        // WS goes through whatever proxy the React app loads over.
        // On a native APK (Capacitor / WebView) we fall back to the
        // REACT_APP_BACKEND_URL host.
        const apiUrl = (process.env.REACT_APP_BACKEND_URL || window.location.origin)
            .replace(/^https?:/, wsProto);
        const url = apiUrl.replace(/\/$/, '') + '/api/karaoke/mic-stream/' + this.code;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';
        ws.onopen = () => {
            try {
                ws.send(JSON.stringify({ role: 'tv', code: this.code }));
            } catch (e) { /* ignore */ }
            console.info('[turbo-rx] connected', url);
        };
        ws.onmessage = (e) => this.onMessage(e);
        ws.onclose = () => { console.info('[turbo-rx] closed'); this.cleanupCtx(); };
        ws.onerror = (e) => { console.warn('[turbo-rx] error', e); };
        this.ws = ws;
    }

    ensureCtx() {
        if (this.ctx) return this.ctx;
        const AC = window.AudioContext || window.webkitAudioContext;
        // sampleRate must match the wire format; create the context
        // at that rate so we don't pay for a resampling stage.
        try {
            this.ctx = new AC({ latencyHint: 'interactive', sampleRate: this.sampleRate });
        } catch (e) {
            // Some browsers throw on unsupported sample rates;
            // fall back to default and let Web Audio resample.
            this.ctx = new AC({ latencyHint: 'interactive' });
        }
        this.nextStartTime = this.ctx.currentTime + this.scheduledAhead;
        return this.ctx;
    }

    onMessage(e) {
        if (typeof e.data === 'string') {
            try {
                const msg = JSON.parse(e.data);
                if (msg.type === 'fmt' && msg.sampleRate) {
                    this.sampleRate = msg.sampleRate;
                    console.info('[turbo-rx] fmt', msg);
                }
            } catch (err) { /* ignore */ }
            return;
        }
        // Binary frame: Int16 little-endian PCM at `sampleRate`.
        const ctx = this.ensureCtx();
        const i16 = new Int16Array(e.data);
        if (i16.length === 0) return;
        // Convert Int16 → Float32.
        const f32 = new Float32Array(i16.length);
        for (let i = 0; i < i16.length; i++) f32[i] = i16[i] / 32768;
        // Build a tiny AudioBuffer and schedule it.
        const buf = ctx.createBuffer(1, f32.length, this.sampleRate);
        buf.copyToChannel(f32, 0);
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(ctx.destination);
        // Schedule slightly ahead of `currentTime` to avoid an
        // underrun.  If we've drifted (network blip), snap back to
        // a fresh cushion so we don't build up latency over time.
        const now = ctx.currentTime;
        if (this.nextStartTime < now + 0.005) {
            this.nextStartTime = now + this.scheduledAhead;
        }
        src.start(this.nextStartTime);
        this.nextStartTime += buf.duration;
    }

    cleanupCtx() {
        if (this.ctx) {
            try { this.ctx.close(); } catch (e) {}
            this.ctx = null;
        }
    }

    stop() {
        try { if (this.ws) this.ws.close(); } catch (e) {}
        this.ws = null;
        this.cleanupCtx();
    }
}

async function postSignal(code, kind, payload) {
    try {
        await fetch(`${API_BASE}/api/karaoke/party/${code}/mic/signal`, {
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
    // v2.8.131 — Turbo mode receiver (raw PCM over WebSocket).
    // Lives in parallel with the WebRTC path: whichever the singer
    // opts into on their phone, that's what we hear on the TV.
    const turboRef = useRef(null);

    const code = partySession?.code || readPartySession()?.code;

    // v2.8.131 — Open the Turbo WS whenever we have a party code.
    // The backend silently drops bytes if no phone is sending, so
    // it's safe to keep the socket open for the whole session.
    useEffect(() => {
        if (!code) return undefined;
        const rx = new TurboReceiver(code);
        try { rx.start(); } catch (e) { console.warn('[turbo-rx] start failed', e); }
        turboRef.current = rx;
        return () => {
            try { rx.stop(); } catch (e) { /* ignore */ }
            if (turboRef.current === rx) turboRef.current = null;
        };
    }, [code]);

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
                // v2.8.130 — Reverted to plain <audio> playback.
                // v2.8.124 tried Web Audio routing for lower latency
                // but on Android WebView (the only environment that
                // matters for this app) `ctx.state === 'running'`
                // didn't guarantee the stream actually reached the
                // speakers — the user reported total audio loss on
                // every preset except "Safe".  The TV-side jitter
                // buffer fix that still works (and that we keep) is
                // `playoutDelayHint = 0` set on the audio receiver,
                // which forces the WebRTC stack to use its smallest
                // playout buffer.
                const stream = e.streams[0];
                if (audioElRef.current) {
                    audioElRef.current.srcObject = stream;
                    audioElRef.current.muted = false;
                    audioElRef.current.volume = 1.0;
                    const playPromise = audioElRef.current.play();
                    if (playPromise) {
                        playPromise.catch((err) => {
                            console.warn('[karaoke-mic] audio.play() blocked:', err);
                        });
                    }
                }
                console.info('[karaoke-mic] remote track attached via <audio>', {
                    kind: e.track.kind,
                    enabled: e.track.enabled,
                    muted: e.track.muted,
                });
            };
            pc.onicecandidate = (e) => {
                if (e.candidate) {
                    postSignal(code, 'ice', { candidate: e.candidate, to_id: singerId });
                }
            };
            pc.oniceconnectionstatechange = () => {
                console.info('[karaoke-mic] ICE state →', pc.iceConnectionState);
            };
            pc.onconnectionstatechange = () => {
                if (!pcRef.current) return;
                const s = pcRef.current.connectionState;
                console.info('[karaoke-mic] PC state →', s);
                setPcState(s);
            };
            await pc.setRemoteDescription(sig.payload.sdp);
            remoteDescSetRef.current = true;
            // v2.8.109 — Tell the WebRTC jitter buffer to keep
            // playout delay minimal.  Chromium honours this; other
            // engines ignore it (no harm).
            try {
                pc.getReceivers().forEach((r) => {
                    if ('playoutDelayHint' in r) r.playoutDelayHint = 0;
                });
            } catch { /* ignore */ }
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
                        {/* v2.8.87 — Live WebRTC state badge so we can
                            see whether the peer connection is forming
                            without an adb logcat session. */}
                        <p
                            className="kk-mic-waiting__diag"
                            data-testid="tv-mic-pc-state"
                            style={{
                                marginTop: 16,
                                fontSize: 12,
                                opacity: 0.55,
                                letterSpacing: 1.5,
                                textTransform: 'uppercase',
                            }}
                        >
                            mic link · {pcState}
                        </p>
                    </div>
                </div>
            )}
            {/* Always render a tiny corner diag pill once we've seen
                the peer connection lifecycle — visible only AFTER the
                waiting overlay closes so we know whether the mic
                actually connected.  Hidden when idle. */}
            {!showWaiting && party && party.current_singer_id && pcState !== 'idle' && (
                <div
                    data-testid="tv-mic-corner-state"
                    style={{
                        position: 'fixed',
                        right: 12,
                        bottom: 12,
                        zIndex: 9000,
                        background: pcState === 'connected'
                            ? 'rgba(50, 200, 110, 0.9)'
                            : 'rgba(255, 180, 50, 0.9)',
                        color: '#0a0c14',
                        padding: '6px 10px',
                        borderRadius: 999,
                        fontSize: 11,
                        fontWeight: 700,
                        letterSpacing: 1,
                        textTransform: 'uppercase',
                    }}
                >
                    Mic · {pcState}
                </div>
            )}
        </>
    );
}
