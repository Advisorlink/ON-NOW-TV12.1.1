import React, { useEffect, useRef, useState, useMemo, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import Hls from 'hls.js';
import {
    ArrowLeft,
    Loader2,
    Play,
    Pause,
    Volume2,
    VolumeX,
    Subtitles as SubtitlesIcon,
    Check,
    X as CloseIcon,
    ExternalLink,
} from 'lucide-react';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import usePartyReactions from '@/hooks/usePartyReactions';
import PartyReactions from '@/components/PartyReactions';
import VoiceReactionButton from '@/components/VoiceReactionButton';
import PartyStartingScreen from '@/components/PartyStartingScreen';
import PartyHostControls from '@/components/PartyHostControls';
import PlayerOverlay from '@/components/PlayerOverlay';
import Host from '@/lib/host';
import { API } from '@/lib/api';
import { getActiveProfile } from '@/lib/profiles';
import { getAvatar } from '@/lib/avatars';

/** Convert OpenSubtitles SRT body into WebVTT the <track> element can read. */
function srtToVtt(srt) {
    const body = (srt || '')
        .replace(/\r\n/g, '\n')
        .replace(/^\uFEFF/, '')
        .replace(/(\d{2}:\d{2}:\d{2}),(\d{3})/g, '$1.$2');
    return 'WEBVTT\n\n' + body;
}

async function fetchSubtitleAsVttBlob(url) {
    const res = await fetch(url, { mode: 'cors', cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const text = await res.text();
    const isAlreadyVtt = /^\s*WEBVTT/i.test(text);
    const vtt = isAlreadyVtt ? text : srtToVtt(text);
    const blob = new Blob([vtt], { type: 'text/vtt' });
    return URL.createObjectURL(blob);
}

const langLabel = (code = '') => {
    const c = code.toLowerCase().slice(0, 3);
    const map = {
        eng: 'English',
        en: 'English',
        spa: 'Spanish',
        es: 'Spanish',
        fre: 'French',
        fra: 'French',
        fr: 'French',
        ger: 'German',
        deu: 'German',
        de: 'German',
        ita: 'Italian',
        it: 'Italian',
        por: 'Portuguese',
        pt: 'Portuguese',
        rus: 'Russian',
        ru: 'Russian',
        ara: 'Arabic',
        ar: 'Arabic',
        hin: 'Hindi',
        hi: 'Hindi',
        chi: 'Chinese',
        zho: 'Chinese',
        zh: 'Chinese',
        jpn: 'Japanese',
        ja: 'Japanese',
        kor: 'Korean',
        ko: 'Korean',
        tur: 'Turkish',
        tr: 'Turkish',
        dut: 'Dutch',
        nld: 'Dutch',
        nl: 'Dutch',
        pol: 'Polish',
        pl: 'Polish',
        swe: 'Swedish',
        sv: 'Swedish',
    };
    return map[c] || code.toUpperCase();
};

export default function Player() {
    useSpatialFocus();
    const navigate = useNavigate();
    const [params] = useSearchParams();
    const url = params.get('url');
    const title = params.get('title') || 'Now Playing';
    const type = params.get('type');
    const imdbId = params.get('imdbId');
    // Watch Together — only present when arrived via a party.
    const partyCode = params.get('party') || '';
    const partyStartPositionMs = parseInt(params.get('position_ms') || '0', 10) || 0;

    const videoRef = useRef(null);
    const hlsRef = useRef(null);
    const trackElRef = useRef(null);
    const trackBlobRef = useRef(null);
    const [playing, setPlaying] = useState(false);
    const [muted, setMuted] = useState(true);
    const [showUnmuteHint, setShowUnmuteHint] = useState(true);
    const [error, setError] = useState(null);
    const [loading, setLoading] = useState(true);

    // Subtitle picker state
    const [subs, setSubs] = useState([]);
    const [subsLoading, setSubsLoading] = useState(false);
    const [activeSubId, setActiveSubId] = useState(null); // null = off
    const [pickerOpen, setPickerOpen] = useState(false);
    const [autoSubApplied, setAutoSubApplied] = useState(false);

    // Cinematic preview meta (poster, synopsis, year, rating)
    const [previewMeta, setPreviewMeta] = useState(null);
    const [streamReady, setStreamReady] = useState(false);
    const [showPreview, setShowPreview] = useState(true);

    /* Live playback state mirrored from the <video> element so the
     * custom PlayerOverlay can render the scrubber / play-pause /
     * time labels without owning the video itself.  Updated by the
     * timeupdate / play / pause / loadedmetadata / progress events
     * wired below. */
    const [vidPaused,   setVidPaused]   = useState(true);
    const [vidCurrent,  setVidCurrent]  = useState(0);
    const [vidDuration, setVidDuration] = useState(0);
    const [vidBuffered, setVidBuffered] = useState(0);

    // Mirror streamReady into a ref so the watch-party WebSocket
    // open-handler can read the LATEST value (state closures would
    // capture the initial `false`).
    const streamReadyRef = useRef(false);
    useEffect(() => { streamReadyRef.current = streamReady; }, [streamReady]);
    // Tracks whether we've sent the `ready` handshake for the current
    // stream URL; reset whenever the URL changes so a fresh stream
    // re-handshakes the party.
    const partyReadySentRef = useRef(false);
    useEffect(() => { partyReadySentRef.current = false; }, [url]);

    /* Whenever we mount a fresh party stream, snap the takeover
     * back to the "buffering" phase.  This guarantees a clean
     * sequence buffering → waiting → countdown → playing every
     * time the host picks a new movie. */
    useEffect(() => {
        if (!partyCode) return;
        setPartyPhase('buffering');
        setPartyCountdown(0);
    }, [url, partyCode]);

    const startPlayback = async () => {
        const v = videoRef.current;
        if (!v) return;
        // Inside the Android wrapper, WebView allows unmuted autoplay
        // (we set mediaPlaybackRequiresUserGesture=false in MainActivity).
        // Start unmuted directly so HK1 box users hear sound immediately.
        const tryUnmuted = Host.isAndroid;
        v.muted = !tryUnmuted;
        try {
            await v.play();
            if (!v.muted) {
                setMuted(false);
                setShowUnmuteHint(false);
            } else {
                setMuted(true);
                setShowUnmuteHint(true);
            }
        } catch {
            // Browser blocked unmuted autoplay — fall back to muted
            v.muted = true;
            try {
                await v.play();
            } catch {
                /* still failed */
            }
            setMuted(true);
            setShowUnmuteHint(true);
        }
    };

    useEffect(() => {
        const v = videoRef.current;
        if (!v || !url) return;

        setError(null);
        setLoading(true);

        // Phone playback compatibility check.  The native libVLC
        // Activity (on the HK1 box) can demux magnet: URIs via the
        // bittorrent demuxer — phones can't.  Surface a helpful
        // message instead of silently spinning.
        if (url.startsWith('magnet:')) {
            setError(
                'Torrent streams (magnet links) need the Android TV ' +
                'box to play.  On a phone, please pick a "Direct" ' +
                'stream from the streams list; those work everywhere.'
            );
            setLoading(false);
            return undefined;
        }

        const isHls = url.toLowerCase().includes('.m3u8');
        const cleanup = [];

        if (isHls && Hls.isSupported()) {
            const hls = new Hls({ enableWorker: true });
            hls.loadSource(url);
            hls.attachMedia(v);
            hls.on(Hls.Events.MANIFEST_PARSED, () => {
                setLoading(false);
                setStreamReady(true);
                /* In a party we DON'T autoplay — the countdown
                 * handler will trigger v.play() so all members
                 * unpause in lock-step.  Outside a party we play
                 * immediately. */
                if (!partyCode) startPlayback();
            });
            hls.on(Hls.Events.ERROR, (_, data) => {
                if (data.fatal) {
                    setError(`Playback error: ${data.details || data.type}`);
                    hls.destroy();
                }
            });
            hlsRef.current = hls;
        } else {
            v.src = url;
            const onLoaded = () => {
                setLoading(false);
                setStreamReady(true);
                if (!partyCode) startPlayback();
            };
            const onErr = () => {
                setError('Could not load this stream.');
                setLoading(false);
            };
            v.addEventListener('loadedmetadata', onLoaded);
            v.addEventListener('error', onErr);
            cleanup.push(() => {
                v.removeEventListener('loadedmetadata', onLoaded);
                v.removeEventListener('error', onErr);
            });
        }

        const onPlay = () => setPlaying(true);
        const onPause = () => setPlaying(false);
        const onVolume = () => setMuted(v.muted);
        v.addEventListener('play', onPlay);
        v.addEventListener('pause', onPause);
        v.addEventListener('volumechange', onVolume);

        return () => {
            v.removeEventListener('play', onPlay);
            v.removeEventListener('pause', onPause);
            v.removeEventListener('volumechange', onVolume);
            cleanup.forEach((f) => f());
            if (hlsRef.current) {
                hlsRef.current.destroy();
                hlsRef.current = null;
            }
            if (trackBlobRef.current) {
                URL.revokeObjectURL(trackBlobRef.current);
                trackBlobRef.current = null;
            }
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [url]);

    // Auto-unmute on first user gesture
    useEffect(() => {
        if (!showUnmuteHint) return;
        const unmute = () => {
            const v = videoRef.current;
            if (!v) return;
            v.muted = false;
            setMuted(false);
            setShowUnmuteHint(false);
        };
        const handler = (e) => {
            if (e.type === 'keydown' && e.repeat) return;
            unmute();
        };
        window.addEventListener('keydown', handler, { once: true });
        window.addEventListener('click', handler, { once: true });
        return () => {
            window.removeEventListener('keydown', handler);
            window.removeEventListener('click', handler);
        };
    }, [showUnmuteHint]);

    // Fetch available subtitles whenever we have an imdbId
    useEffect(() => {
        if (!imdbId || !type) return;
        let cancel = false;
        setSubsLoading(true);
        (async () => {
            try {
                const r = await fetch(
                    `${API}/subtitles/${encodeURIComponent(
                        type
                    )}/${encodeURIComponent(imdbId)}`,
                    { cache: 'no-store' }
                );
                const data = await r.json();
                if (cancel) return;
                const list = Array.isArray(data?.subtitles) ? data.subtitles : [];
                // Dedupe by url, English first
                const seen = new Set();
                const cleaned = [];
                for (const s of list) {
                    if (!s?.url || seen.has(s.url)) continue;
                    seen.add(s.url);
                    cleaned.push({
                        id: s.id || s.url,
                        url: s.url,
                        lang: s.lang || s.language || 'unknown',
                    });
                }
                cleaned.sort((a, b) => {
                    const ae = /^en/i.test(a.lang) ? 0 : 1;
                    const be = /^en/i.test(b.lang) ? 0 : 1;
                    return ae - be;
                });
                setSubs(cleaned);
            } catch {
                if (!cancel) setSubs([]);
            } finally {
                if (!cancel) setSubsLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, imdbId]);

    // Group subtitle entries by language for the picker UI
    const subsByLang = useMemo(() => {
        const groups = new Map();
        for (const s of subs) {
            const k = (s.lang || 'unknown').toLowerCase();
            if (!groups.has(k))
                groups.set(k, { lang: s.lang, label: langLabel(s.lang), items: [] });
            groups.get(k).items.push(s);
        }
        return Array.from(groups.values());
    }, [subs]);

    // Pull cinematic preview meta (poster, synopsis, year, rating)
    // — gives the loading screen a real cover instead of a black void.
    useEffect(() => {
        if (!imdbId) return;
        let cancel = false;
        const baseId = imdbId.split(':')[0];
        const fetchType = type === 'series' ? 'series' : 'movie';
        (async () => {
            try {
                const r = await fetch(
                    `https://v3-cinemeta.strem.io/meta/${fetchType}/${baseId}.json`,
                    { mode: 'cors', cache: 'force-cache' }
                );
                if (!r.ok) return;
                const data = await r.json();
                if (cancel) return;
                const m = data?.meta;
                if (m) {
                    setPreviewMeta({
                        title: m.name,
                        poster: m.poster,
                        background: m.background || m.poster,
                        year: m.releaseInfo,
                        runtime: m.runtime,
                        rating: m.imdbRating,
                        synopsis: m.description,
                        genres: Array.isArray(m.genres)
                            ? m.genres.slice(0, 3)
                            : [],
                    });
                }
            } catch {
                /* swallow */
            }
        })();
        return () => {
            cancel = true;
        };
    }, [imdbId, type]);

    // Hide preview screen 2s after stream is ready, so the viewer
    // gets a beat to see what's loading even on fast connections.
    useEffect(() => {
        if (!streamReady) return;
        const t = setTimeout(() => setShowPreview(false), 2000);
        return () => clearTimeout(t);
    }, [streamReady]);

    const togglePlay = () => {
        const v = videoRef.current;
        if (!v) return;
        if (v.paused) v.play().catch(() => {});
        else v.pause();
    };

    const toggleMute = () => {
        const v = videoRef.current;
        if (!v) return;
        v.muted = !v.muted;
        setMuted(v.muted);
        if (!v.muted) setShowUnmuteHint(false);
    };

    const removeActiveTrack = () => {
        const v = videoRef.current;
        if (!v) return;
        if (trackElRef.current) {
            try {
                v.removeChild(trackElRef.current);
            } catch {
                /* ignore */
            }
            trackElRef.current = null;
        }
        if (trackBlobRef.current) {
            URL.revokeObjectURL(trackBlobRef.current);
            trackBlobRef.current = null;
        }
        setActiveSubId(null);
    };

    const applySubtitle = async (sub) => {
        const v = videoRef.current;
        if (!v) return;
        try {
            removeActiveTrack();
            const blobUrl = await fetchSubtitleAsVttBlob(sub.url);
            const trackEl = document.createElement('track');
            trackEl.kind = 'subtitles';
            trackEl.label = langLabel(sub.lang);
            trackEl.srclang = (sub.lang || 'en').slice(0, 2).toLowerCase();
            trackEl.src = blobUrl;
            trackEl.default = true;
            v.appendChild(trackEl);
            // Force-show the new track
            const onLoad = () => {
                for (let i = 0; i < v.textTracks.length; i += 1) {
                    v.textTracks[i].mode =
                        v.textTracks[i].label === trackEl.label ? 'showing' : 'disabled';
                }
            };
            trackEl.addEventListener('load', onLoad, { once: true });
            // Some browsers don't fire 'load' — force it on next tick too
            setTimeout(onLoad, 400);

            trackElRef.current = trackEl;
            trackBlobRef.current = blobUrl;
            setActiveSubId(sub.id);
            setPickerOpen(false);
        } catch (e) {
            setError(`Couldn't load that subtitle (${e?.message || 'fetch failed'}).`);
            setTimeout(() => setError(null), 4000);
        }
    };

    // Auto-apply the first English subtitle as soon as it's available.
    // Runs once per <Player> mount; user can switch via the picker.
    useEffect(() => {
        if (autoSubApplied) return;
        if (!streamReady) return;
        if (subs.length === 0) return;
        const eng = subs.find((s) => /^en/i.test(s.lang || ''));
        if (!eng) return;
        setAutoSubApplied(true);
        applySubtitle(eng);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [streamReady, subs, autoSubApplied]);

    // -----------------------------------------------------------------
    // Watch Together — party sync.
    //
    // Architecture (host-authoritative):
    //   • Host emits play / pause / seek over the same WebSocket so
    //     every guest mirrors the action.
    //   • Guests are passive listeners — they apply the broadcast
    //     state to their <video> and otherwise do nothing.
    //   • Host also broadcasts a `playing_now` heartbeat every 2s
    //     so late-joiners pick up the right position when they
    //     load the Player.
    //   • Tolerance: a guest only seeks when its position drifts
    //     more than 1.5 s from the host's so we don't fight
    //     normal HLS buffering jitter.
    // -----------------------------------------------------------------
    const partyRoleRef = useRef('guest');
    // v2.6.77: also track partyRole as STATE so React re-renders
    // when we learn the role.  The ref alone doesn't trigger a
    // re-render → the derived `isPartyHost` / `isPartyGuest` flags
    // were stuck at `false` after first render, which meant the
    // host menu never mounted AND the popcorn artwork stayed as
    // the (default 'guest') popcorn instead of switching to the
    // host-loading.png for hosts.
    const [partyRoleState, setPartyRoleState] = useState('guest');
    const partyMemberIdRef = useRef('');
    const partyWsRef = useRef(null);
    const partyArmedRef = useRef(false);   // ignore the first 'play' that we
                                            // trigger on countdown so we don't
                                            // echo it back as a 'resume'.
    const [partyStatus, setPartyStatus] = useState('connecting');
    const [partyCountdown, setPartyCountdown] = useState(0);

    /* Phase machine controlling the full-screen takeover overlay.
     *   buffering  → just mounted, loading the stream
     *   waiting    → our <video> is ready, server is waiting on other members
     *   countdown  → 3-2-1 in progress
     *   playing    → actual playback running (overlay hidden)
     * Only meaningful when `partyCode` is set; otherwise the
     * regular player UI shows. */
    const [partyPhase, setPartyPhase] = useState('buffering');
    const [partyMembers, setPartyMembers] = useState([]);

    /* Chrome (top bar + "watching with" pill) — hidden during
     * playback so the screen stays clean.  Revealed on any user
     * activity (move/click/touch/key) and auto-hidden after 3 s of
     * idle.  Always visible while a picker is open OR while the
     * party takeover is up (so the BACK button stays reachable). */
    const [chromeVisible, setChromeVisible] = useState(true);
    useEffect(() => {
        let idleTimer = null;
        const reveal = () => {
            setChromeVisible(true);
            if (idleTimer) clearTimeout(idleTimer);
            idleTimer = setTimeout(() => setChromeVisible(false), 3000);
        };
        reveal();
        const events = ['mousemove', 'mousedown', 'touchstart', 'keydown', 'wheel'];
        events.forEach((ev) => window.addEventListener(ev, reveal, { passive: true }));
        return () => {
            if (idleTimer) clearTimeout(idleTimer);
            events.forEach((ev) => window.removeEventListener(ev, reveal));
        };
    }, []);

    // Floating emoji reactions during party playback.  Reactions are
    // added by spawnReaction() (called from WS onmessage OR locally by
    // usePartyReactions) and auto-removed after the float animation
    // completes (2.6 s).
    const [partyReactions, setPartyReactions] = useState([]);
    const spawnReaction = React.useCallback((emoji, memberName) => {
        const bubble = PartyReactions.nextBubble(emoji, memberName);
        setPartyReactions((prev) => [...prev, bubble]);
        setTimeout(() => {
            setPartyReactions((prev) => prev.filter((b) => b.id !== bubble.id));
        }, 2700);
    }, []);
    // v2.7.55 — Voice-message bubbles stay on screen 8 s minimum
    // per user spec (vs. ~3 s for emoji reactions).
    const spawnVoiceBubble = React.useCallback((text, memberName, avatarEmoji) => {
        const bubble = PartyReactions.nextVoiceBubble(text, memberName, avatarEmoji);
        setPartyReactions((prev) => [...prev, bubble]);
        setTimeout(() => {
            setPartyReactions((prev) => prev.filter((b) => b.id !== bubble.id));
        }, 8200);
    }, []);

    // Current user's avatar emoji (used to label outgoing voice
    // messages so receivers see which party member sent them).
    const [partySelfAvatarEmoji, setPartySelfAvatarEmoji] = useState('');
    useEffect(() => {
        try {
            const raw = sessionStorage.getItem('vesper-party-self-avatar-emoji') || '';
            if (raw) setPartySelfAvatarEmoji(raw);
        } catch { /* private mode */ }
    }, [partyCode]);

    // Hook up the D-pad-hold-2-seconds reaction sender.  Only active
    // when we're in a party (partyCode set).
    usePartyReactions({
        enabled: !!partyCode,
        wsRef: partyWsRef,
        onLocalFire: (emoji) => spawnReaction(emoji, 'YOU'),
    });

    useEffect(() => {
        if (!partyCode) return;
        // Pull role + member id captured by the WatchTogether lobby.
        let role = 'guest';
        let memberId = '';
        try {
            role = sessionStorage.getItem('vesper-party-role') || 'guest';
            memberId = sessionStorage.getItem('vesper-party-member-id') || '';
        } catch { /* private mode */ }
        partyRoleRef.current = role;
        setPartyRoleState(role);
        partyMemberIdRef.current = memberId;

        const wsBase = (process.env.REACT_APP_BACKEND_URL || window.location.origin)
            .replace(/^http/, 'ws');

        /* v2.6.73: Auto-reconnect on close.  User reported "after
           sending multiple emojis one after the other, it stops
           sending them completely" — the badge flipped to OFFLINE,
           meaning the WS dropped and never came back.  We now keep
           a 1.5 s / 3 s / 5 s / 8 s backoff loop until reconnected.
           Stays disabled once the party is over (cleanup flag). */
        let cancelled = false;
        let reconnectTimer = null;
        let reconnectAttempt = 0;
        let activeWs = null;

        const send = (m) => {
            const w = activeWs;
            if (w && w.readyState === 1) w.send(JSON.stringify(m));
        };

        function scheduleReconnect() {
            if (cancelled) return;
            const delays = [1500, 3000, 5000, 8000];
            const delay = delays[Math.min(reconnectAttempt, delays.length - 1)];
            reconnectAttempt += 1;
            // Don't show "DISCONNECTED" for too long — flip to
            // "RECONNECTING…" so the user knows we're on it.
            setPartyStatus('reconnecting');
            reconnectTimer = setTimeout(connect, delay);
        }

        function connect() {
            if (cancelled) return;
            const ws = new WebSocket(`${wsBase}/api/watch-party/ws/${partyCode}`);
            partyWsRef.current = ws;
            activeWs = ws;

            ws.onopen = () => {
                if (cancelled) { try { ws.close(); } catch { /* ignore */ } return; }
                reconnectAttempt = 0;
                const profile = getActiveProfile() || {};
                send({
                    type: 'hello',
                    role,
                    member_id: memberId || undefined,
                    name: profile.name || 'Guest',
                    avatar: profile.avatarId || 'a1',
                });
                setPartyStatus('connected');
                // If the stream was already buffered before the WS opened,
                // fire ready right away so the party can advance.
                if (streamReadyRef.current && !partyReadySentRef.current) {
                    partyReadySentRef.current = true;
                    send({ type: 'ready', member_id: partyMemberIdRef.current });
                }
            };

            ws.onclose = () => {
                if (partyWsRef.current === ws) partyWsRef.current = null;
                activeWs = null;
                if (cancelled) return;
                scheduleReconnect();
            };

            ws.onerror = () => {
                // Let the onclose handler do the reconnect logic.
                try { ws.close(); } catch { /* ignore */ }
            };

            ws.onmessage = (e) => {
                let msg; try { msg = JSON.parse(e.data); } catch { return; }
                handlePartyMessage(msg, send);
            };
        }

        // The message handler is hoisted into a named function so
        // we can re-bind it after each reconnect without rebuilding
        // the closure.  `send` is passed in because it changes when
        // the underlying ws changes.
        function handlePartyMessage(msg, send) {
            const v = videoRef.current;
            if (msg.type === 'joined') {
                partyMemberIdRef.current = msg.member_id;
                try { sessionStorage.setItem('vesper-party-member-id', msg.member_id); }
                catch { /* ignore */ }
                return;
            }
            // Incoming emoji reaction from ANY party member.
            if (msg.type === 'reaction' && msg.emoji) {
                const myId = partyMemberIdRef.current;
                if (msg.member && msg.member.id === myId) return;
                spawnReaction(msg.emoji, msg.member?.name || '');
                return;
            }
            // v2.7.55 — Incoming voice message (transcribed by /api/stt).
            if (msg.type === 'voice_message' && msg.text) {
                const myId = partyMemberIdRef.current;
                if (msg.member && msg.member.id === myId) return; // already echoed locally
                spawnVoiceBubble(
                    msg.text,
                    msg.member?.name || '',
                    msg.member?.avatar_emoji || '',
                );
                return;
            }
            if (msg.type !== 'state') return;
            if (!v) return;

            const rosterFromServer = Array.isArray(msg.members)
                ? msg.members
                : Array.isArray(msg.member_list)
                ? msg.member_list
                : null;
            if (rosterFromServer) {
                setPartyMembers(
                    rosterFromServer.map((m) => ({
                        id:     m.id || m.member_id || '',
                        name:   m.name || 'Guest',
                        avatar: m.avatar || '',
                        ready:  !!m.ready,
                    })),
                );
            }

            if (msg.status === 'loading') {
                setPartyCountdown(0);
                setPartyPhase('waiting');
                return;
            }

            if (msg.status === 'countdown' && msg.at_ms) {
                const remaining = Math.max(0, msg.at_ms - Date.now());
                setPartyCountdown(Math.ceil(remaining / 1000));
                setPartyPhase('countdown');
                if (role === 'guest') {
                    const targetSec = (msg.position_ms || 0) / 1000;
                    if (Math.abs((v.currentTime || 0) - targetSec) > 1.5) {
                        try { v.currentTime = targetSec; } catch { /* ignore */ }
                    }
                    const fire = () => {
                        partyArmedRef.current = false;
                        v.play().catch(() => {});
                        setPartyCountdown(0);
                        setPartyPhase('playing');
                    };
                    if (remaining <= 0) fire();
                    else setTimeout(fire, remaining);
                } else if (role === 'host') {
                    const fire = () => {
                        partyArmedRef.current = false;
                        v.play().catch(() => {});
                        setPartyCountdown(0);
                        setPartyPhase('playing');
                    };
                    if (remaining <= 0) fire();
                    else setTimeout(fire, remaining);
                }
                return;
            }

            setPartyCountdown(0);

            if (msg.status === 'playing' || msg.status === 'paused') {
                setPartyPhase('playing');
            }

            if (role === 'guest') {
                if (msg.status === 'paused') {
                    const targetSec = (msg.position_ms || 0) / 1000;
                    if (Math.abs((v.currentTime || 0) - targetSec) > 1.5) {
                        try { v.currentTime = targetSec; } catch { /* ignore */ }
                    }
                    if (!v.paused) {
                        partyArmedRef.current = false;
                        try { v.pause(); } catch { /* ignore */ }
                    }
                } else if (msg.status === 'playing') {
                    const targetSec = (msg.position_ms || 0) / 1000;
                    if (Math.abs((v.currentTime || 0) - targetSec) > 1.5) {
                        try { v.currentTime = targetSec; } catch { /* ignore */ }
                    }
                    if (v.paused) {
                        partyArmedRef.current = false;
                        v.play().catch(() => {});
                    }
                }
            }
        }

        // Host emits play/pause/seek as the user interacts.  We track
        // a small "armed" flag to avoid echoing programmatic state
        // changes (e.g. our own countdown-trigger play) back as a
        // resume — that would cause an infinite re-broadcast loop.
        const onPlay = () => {
            if (role !== 'host') return;
            if (!partyArmedRef.current) { partyArmedRef.current = true; return; }
            const v = videoRef.current; if (!v) return;
            send({ type: 'resume', position_ms: Math.floor((v.currentTime || 0) * 1000), lead_ms: 800 });
        };
        const onPause = () => {
            if (role !== 'host') return;
            if (!partyArmedRef.current) { partyArmedRef.current = true; return; }
            const v = videoRef.current; if (!v) return;
            if (v.ended) return;
            send({ type: 'pause', position_ms: Math.floor((v.currentTime || 0) * 1000) });
        };
        const onSeeked = () => {
            if (role !== 'host') return;
            if (!partyArmedRef.current) return;
            const v = videoRef.current; if (!v) return;
            send({ type: 'seek', position_ms: Math.floor((v.currentTime || 0) * 1000) });
        };
        const v = videoRef.current;
        v?.addEventListener('play', onPlay);
        v?.addEventListener('pause', onPause);
        v?.addEventListener('seeked', onSeeked);

        // Host heartbeat — keep server's position fresh.
        let heartbeat = null;
        if (role === 'host') {
            heartbeat = setInterval(() => {
                const vv = videoRef.current;
                if (!vv || vv.paused) return;
                send({ type: 'playing_now', position_ms: Math.floor((vv.currentTime || 0) * 1000) });
            }, 2000);
        }

        return () => {
            cancelled = true;
            if (reconnectTimer) clearTimeout(reconnectTimer);
            if (heartbeat) clearInterval(heartbeat);
            v?.removeEventListener('play', onPlay);
            v?.removeEventListener('pause', onPause);
            v?.removeEventListener('seeked', onSeeked);
            try { activeWs?.close(); } catch { /* ignore */ }
            partyWsRef.current = null;
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [partyCode]);

    // Host-side: if the URL provided a position_ms (e.g. resume of a
    // mid-party rejoin) we seek there once the stream is ready.
    useEffect(() => {
        if (!partyCode) return;
        if (!streamReady) return;
        if (!partyStartPositionMs) return;
        const v = videoRef.current;
        if (!v) return;
        const targetSec = partyStartPositionMs / 1000;
        if (Math.abs((v.currentTime || 0) - targetSec) > 1.5) {
            try { v.currentTime = targetSec; } catch { /* ignore */ }
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [streamReady, partyCode]);

    // Watch-party HANDSHAKE — every member sends `ready` once their
    // <video> has buffered enough to start playback.  The server
    // collects these and only then flips `status` to `countdown`.
    // Without this the party hangs forever in `loading` after the
    // host hits Start.  `partyReadySentRef` resets when `url` changes
    // so re-picks re-handshake.
    useEffect(() => {
        if (!partyCode) return;
        if (!streamReady) return;
        const ws = partyWsRef.current;
        if (!ws || ws.readyState !== 1) return;
        if (partyReadySentRef.current) return;
        partyReadySentRef.current = true;
        try {
            ws.send(JSON.stringify({ type: 'ready', member_id: partyMemberIdRef.current }));
        } catch { /* ignore */ }
        /* We're locally ready — bump the takeover overlay into
         * "waiting on other members" mode.  Stays until the server
         * sends a `countdown` (everyone ready) or `playing` state. */
        setPartyPhase((p) => (p === 'buffering' ? 'waiting' : p));
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [streamReady, partyCode, url]);

    /* Wire video element events → React state so the custom
     * PlayerOverlay scrubber + play/pause icon stay in sync with
     * the real <video> playback at all times.  Re-binds whenever
     * a new stream URL is mounted. */
    useEffect(() => {
        const v = videoRef.current;
        if (!v) return undefined;
        const onPlay   = () => setVidPaused(false);
        const onPause  = () => setVidPaused(true);
        const onTime   = () => setVidCurrent(v.currentTime || 0);
        const onMeta   = () => setVidDuration(v.duration || 0);
        const onProg   = () => {
            try {
                if (v.buffered && v.buffered.length > 0) {
                    setVidBuffered(v.buffered.end(v.buffered.length - 1));
                }
            } catch { /* ignore */ }
        };
        v.addEventListener('play',           onPlay);
        v.addEventListener('pause',          onPause);
        v.addEventListener('timeupdate',     onTime);
        v.addEventListener('loadedmetadata', onMeta);
        v.addEventListener('durationchange', onMeta);
        v.addEventListener('progress',       onProg);
        // Snapshot once on mount in case events already fired
        // before we attached (race on hot-reload).
        setVidPaused(!!v.paused);
        setVidCurrent(v.currentTime || 0);
        setVidDuration(v.duration || 0);
        return () => {
            v.removeEventListener('play',           onPlay);
            v.removeEventListener('pause',          onPause);
            v.removeEventListener('timeupdate',     onTime);
            v.removeEventListener('loadedmetadata', onMeta);
            v.removeEventListener('durationchange', onMeta);
            v.removeEventListener('progress',       onProg);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [url]);

    /* Imperative helpers passed to PlayerOverlay. */
    const handlePlayPause = useCallback(() => {
        const v = videoRef.current;
        if (!v) return;
        if (v.paused) { v.play().catch(() => {}); }
        else          { v.pause(); }
    }, []);
    const handleSeekRel = useCallback((delta) => {
        const v = videoRef.current;
        if (!v) return;
        const dur = v.duration || 0;
        v.currentTime = Math.max(0, Math.min(dur || v.currentTime + delta, (v.currentTime || 0) + delta));
    }, []);
    const handleSeekAbs = useCallback((sec) => {
        const v = videoRef.current;
        if (!v) return;
        try { v.currentTime = Math.max(0, sec); } catch { /* ignore */ }
    }, []);

    /* ---------------------------------------------------------------
     * HOST PARTY MENU (web-only) — v2.6.75
     *
     * Native Kotlin player has its own 5-button menu in
     * VlcPlayerActivity.kt; this is the JS counterpart so guests on
     * phones / hosts on the web preview get the SAME controls.
     *
     * `hostMenuVisible` flips on tap / OK and auto-hides after 6 s.
     * `hostLocked` consumes all player surface interactions until
     * the user holds OK for 2 s.
     * ------------------------------------------------------------- */
    const [hostMenuVisible, setHostMenuVisible] = useState(true); // v2.6.87: always visible
    const [hostLocked, setHostLocked] = useState(false);
    const hostMenuTimerRef = useRef(null);
    const hostUnlockTimerRef = useRef(null);
    const partyRole = partyRoleState; // re-renders when WS sets the role
    const isPartyHost = !!partyCode && partyRoleState === 'host';
    const isPartyGuest = !!partyCode && partyRoleState === 'guest';

    /* v2.6.87 — user wanted the host menu + reactions remote to
     * stay on screen for the entire party session.  The auto-hide
     * timers (6 s) made this impossible — every time you weren't
     * pressing keys, the dock would vanish.  Now both `openHostMenu`
     * and `refreshHostMenuAutoHide` are no-ops: the dock is mounted
     * once and stays until BACK exits the player.  We leave the
     * functions in place so legacy callers don't blow up. */
    const refreshHostMenuAutoHide = useCallback(() => {
        // intentionally empty — auto-hide is disabled
    }, []);

    const openHostMenu = useCallback(() => {
        setHostMenuVisible(true);
    }, []);

    const closeHostMenu = useCallback(() => {
        // Kept as a no-op — pressing BACK exits the activity entirely.
    }, []);

    const sendPartyWs = useCallback((payload) => {
        const ws = partyWsRef.current;
        if (ws && ws.readyState === 1) {
            try { ws.send(JSON.stringify(payload)); } catch { /* ignore */ }
        }
    }, []);

    /* Host menu actions — mirror Kotlin handleHostMenuPick() exactly. */
    const onHostTogglePause = useCallback(() => {
        const v = videoRef.current;
        if (!v) return;
        if (v.paused) {
            v.play().catch(() => {});
            sendPartyWs({
                type: 'resume',
                position_ms: Math.floor((v.currentTime || 0) * 1000),
                lead_ms: 800,
            });
        } else {
            v.pause();
            sendPartyWs({
                type: 'pause',
                position_ms: Math.floor((v.currentTime || 0) * 1000),
            });
        }
        closeHostMenu();
    }, [sendPartyWs, closeHostMenu]);

    const onHostSkip30 = useCallback(() => {
        const v = videoRef.current;
        if (!v) return;
        const dur = v.duration || 0;
        const target = Math.min((v.currentTime || 0) + 30, Math.max(dur - 2, 0));
        try { v.currentTime = target; } catch { /* ignore */ }
        sendPartyWs({
            type: 'play',
            position_ms: Math.floor(target * 1000),
            lead_ms: 1200,
        });
        closeHostMenu();
    }, [sendPartyWs, closeHostMenu]);

    const onHostCatchUp = useCallback(() => {
        const v = videoRef.current;
        if (!v) return;
        sendPartyWs({
            type: 'play',
            position_ms: Math.floor((v.currentTime || 0) * 1000),
            lead_ms: 1500,
        });
        closeHostMenu();
        try {
            import('sonner').then(({ toast }) => {
                toast.success('Re-syncing party…');
            });
        } catch { /* sonner not loaded */ }
    }, [sendPartyWs, closeHostMenu]);

    const onHostLock = useCallback(() => {
        setHostLocked(true);
        closeHostMenu();
        try {
            import('sonner').then(({ toast }) => {
                toast.info('Locked — hold OK 2 s to unlock');
            });
        } catch { /* sonner not loaded */ }
    }, [closeHostMenu]);

    const onHostSubs = useCallback(() => {
        setPickerOpen(true);
        closeHostMenu();
    }, [closeHostMenu]);

    /* While locked, document-level Enter/Space hold for 2 s unlocks
       the host's screen.  We track press start time and clear on
       release / unlock. */
    useEffect(() => {
        if (!hostLocked) return undefined;
        let holdStart = 0;
        const onDown = (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            if (e.repeat) return;
            holdStart = Date.now();
            // After 2 s of continuous hold, unlock.
            hostUnlockTimerRef.current = setTimeout(() => {
                if (holdStart > 0) {
                    setHostLocked(false);
                    try {
                        import('sonner').then(({ toast }) => {
                            toast.success('Screen unlocked');
                        });
                    } catch { /* ignore */ }
                }
            }, 2_000);
        };
        const onUp = (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            holdStart = 0;
            if (hostUnlockTimerRef.current) {
                clearTimeout(hostUnlockTimerRef.current);
                hostUnlockTimerRef.current = null;
            }
        };
        // Use CAPTURE so we run before the spatial-focus engine and
        // any other listeners — preventing focus from moving while
        // we're holding for unlock.
        document.addEventListener('keydown', onDown, true);
        document.addEventListener('keyup', onUp, true);
        return () => {
            document.removeEventListener('keydown', onDown, true);
            document.removeEventListener('keyup', onUp, true);
            if (hostUnlockTimerRef.current) clearTimeout(hostUnlockTimerRef.current);
        };
    }, [hostLocked]);

    /* Cleanup the auto-hide timer on unmount. */
    useEffect(() => () => {
        if (hostMenuTimerRef.current) clearTimeout(hostMenuTimerRef.current);
        if (hostUnlockTimerRef.current) clearTimeout(hostUnlockTimerRef.current);
    }, []);

    /* Click on the video while in party-host mode opens the menu
       (or closes it if already open).  Replaces the default
       handlePlayPause behaviour for hosts.  Guests get NOTHING —
       view-only; only emoji reactions (handled at document level)
       and subtitle tap work. */
    const handleVideoClick = useCallback(() => {
        if (hostLocked) return;
        if (isPartyHost) {
            if (hostMenuVisible) closeHostMenu();
            else openHostMenu();
            return;
        }
        if (isPartyGuest) {
            // Guest tap → open subtitles picker only.
            setPickerOpen(true);
            return;
        }
        handlePlayPause();
    }, [hostLocked, isPartyHost, isPartyGuest, hostMenuVisible, openHostMenu, closeHostMenu, handlePlayPause]);

    if (!url) {
        return (
            <CenterMsg>
                <div style={{ color: '#ffb5b5' }}>No stream URL provided.</div>
                <button
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    className="mt-6 h-12 px-5 rounded-full"
                    style={{
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.12)',
                    }}
                >
                    Back
                </button>
            </CenterMsg>
        );
    }

    /* While a party is forming, the dedicated PartyStartingScreen
     * fully replaces the cinematic preview to avoid showing two
     * loading screens stacked. */
    const partyTakeoverVisible = !!partyCode && partyPhase !== 'playing';

    return (
        <div
            data-testid="player-page"
            className="fixed inset-0 z-50"
            style={{ background: '#000' }}
        >
            <video
                ref={videoRef}
                data-testid="player-video"
                playsInline
                crossOrigin="anonymous"
                onClick={handleVideoClick}
                className="absolute inset-0 w-full h-full object-contain"
                style={{
                    cursor: hostLocked ? 'not-allowed' : 'pointer',
                    pointerEvents: hostLocked ? 'none' : 'auto',
                }}
            />

            {/* Premium movie playback overlay — replaces the
                browser-default `<video controls>` chrome with our
                cinematic info card (title, meta chips, synopsis on
                pause) + custom scrubber + play/pause/skip controls.
                Auto-hides with the rest of the chrome during
                playback, always visible while paused.  Suppressed
                during the party takeover and during the loading
                preview.  Also suppressed when the current member
                is a HOST or GUEST of a watch party (the dedicated
                PartyHostControls / view-only mode handles UI). */}
            {!partyTakeoverVisible && !showPreview && !partyCode && (
                <PlayerOverlay
                    visible={chromeVisible || pickerOpen}
                    paused={vidPaused}
                    currentTime={vidCurrent}
                    duration={vidDuration}
                    buffered={vidBuffered}
                    title={title}
                    previewMeta={previewMeta}
                    onPlayPause={handlePlayPause}
                    onSeek={handleSeekRel}
                    onSeekTo={handleSeekAbs}
                />
            )}

            {/* HOST PARTY CONTROLS — H3 curved dock + R4 orbital
                remote.  v2.6.87: both elements are ALWAYS visible
                during the party session (user feedback: "the
                controls don't need to disappear, they can stay
                there forever"). */}
            {isPartyHost && !partyTakeoverVisible && (
                <PartyHostControls
                    paused={vidPaused}
                    locked={hostLocked}
                    visible={hostMenuVisible}
                    onTogglePause={onHostTogglePause}
                    onSkip30={onHostSkip30}
                    onCatchUp={onHostCatchUp}
                    onLock={onHostLock}
                    onUnlock={() => setHostLocked(false)}
                    onSubs={onHostSubs}
                    onAutoHideRefresh={refreshHostMenuAutoHide}
                />
            )}

            {/* GUEST PARTY CONTROLS — same visual language as the
                host (H3 curved dock + R4 orbital remote) but with
                a guest-appropriate 2-button menu: Catch Up (re-sync
                if drift > 1.5 s) + Subtitles.  v2.6.87. */}
            {isPartyGuest && !partyTakeoverVisible && (
                <PartyHostControls
                    role="guest"
                    paused={vidPaused}
                    locked={false}
                    visible
                    onCatchUp={() => {
                        /* Guest's "Catch Up" jumps the local element
                         * to the server's authoritative position
                         * (corrected by ping/pong offset). */
                        try {
                            const v = videoRef.current;
                            if (v && partyStateRef.current?.position_ms != null) {
                                const target = partyStateRef.current.position_ms / 1000;
                                if (Math.abs(v.currentTime - target) > 0.4) {
                                    v.currentTime = target;
                                }
                            }
                        } catch { /* ignore */ }
                    }}
                    onSubs={onHostSubs}
                    onAutoHideRefresh={() => {}}
                />
            )}


            {/* Watch-party full-screen takeover.  Hides every other
                player surface (top bar, controls, preview, etc.)
                while the party is buffering / waiting on members /
                counting down.  Disappears once the server flips
                status to `playing`. */}
            <PartyStartingScreen
                visible={partyTakeoverVisible}
                phase={partyPhase}
                partyCode={partyCode}
                role={partyRoleState}
            />

            {/* Watch-party floating emoji reactions overlay.  Sits
                above the video but below the top bar so the BACK
                button remains pressable.  pointer-events: none. */}
            {partyCode && <PartyReactions reactions={partyReactions} />}

            {/* v2.7.55 — Voice-to-text reaction button.  Floats in
                the bottom-right corner during party playback.  Hold
                to record (max 10 s) → transcribed via Whisper →
                broadcast to all party members as a text bubble. */}
            {partyCode && (
                <div
                    style={{
                        position: 'fixed',
                        right: '3vw',
                        bottom: '10vh',
                        zIndex: 96,
                        pointerEvents: 'auto',
                    }}
                >
                    <VoiceReactionButton
                        wsRef={partyWsRef}
                        avatarEmoji={partySelfAvatarEmoji || ''}
                        onLocalEcho={(text) => spawnVoiceBubble(text, 'YOU', partySelfAvatarEmoji || '')}
                    />
                </div>
            )}

            {/* Top bar — auto-hides during playback so the screen
                stays cinematic clean.  Forced visible while a
                picker is open or the party takeover is up. */}
            <div
                className="absolute top-0 left-0 right-0 z-10 flex items-center gap-4 p-6"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(0,0,0,0.7) 0%, rgba(0,0,0,0) 100%)',
                    opacity: (chromeVisible || pickerOpen || partyTakeoverVisible) ? 1 : 0,
                    pointerEvents:
                        (chromeVisible || pickerOpen || partyTakeoverVisible) ? 'auto' : 'none',
                    transition: 'opacity 280ms ease',
                }}
            >
                <button
                    data-testid="player-back"
                    data-focusable="true"
                    data-focus-style="quiet"
                    data-initial-focus="true"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    aria-label="Back"
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 48,
                        height: 48,
                        background: 'rgba(17,24,39,0.7)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.1)',
                    }}
                >
                    <ArrowLeft size={20} />
                </button>
                <div className="min-w-0">
                    <div
                        className="font-sans font-medium truncate"
                        style={{ fontSize: 19 }}
                    >
                        {title}
                    </div>
                    <div
                        className="vesper-eyebrow truncate"
                        style={{ fontSize: 10, maxWidth: '60vw' }}
                    >
                        {url}
                    </div>
                </div>
                {partyCode && (
                    <WatchingWithCard
                        partyCode={partyCode}
                        myMemberId={partyMemberIdRef.current}
                        members={partyMembers}
                        connected={partyStatus === 'connected'}
                    />
                )}
            </div>

            {/* Watch-Together countdown overlay — every member sees
                this in their browser when the host hits Start. */}
            {/* Watch-Together countdown overlay — every member sees
                this in their browser when the host hits Start.
                Suppressed while the new full-screen takeover is
                visible (the takeover renders the countdown number
                inside its own central icon). */}
            {partyCode && partyCountdown > 0 && !partyTakeoverVisible && (
                <div
                    data-testid="player-party-countdown"
                    className="absolute inset-0 z-40 flex flex-col items-center justify-center"
                    style={{
                        background: 'radial-gradient(ellipse at center, rgba(6,8,15,0.55) 0%, rgba(6,8,15,0.92) 100%)',
                        pointerEvents: 'none',
                    }}
                >
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 12, letterSpacing: '0.32em', textTransform: 'uppercase',
                            color: 'var(--vesper-blue-bright)', marginBottom: 18,
                        }}
                    >
                        Starting the party
                    </div>
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(120px, 16vw, 220px)',
                            fontWeight: 800,
                            color: 'var(--vesper-blue-bright)',
                            textShadow: '0 0 60px rgba(var(--vesper-blue-rgb),0.7)',
                            lineHeight: 1,
                        }}
                    >
                        {partyCountdown}
                    </div>
                </div>
            )}

            {/* Cinematic preview / loading screen.  Hidden when a
                party takeover is active so the user only ever sees
                ONE loading screen at a time. */}
            {showPreview && !partyTakeoverVisible && (
                <div
                    data-testid="player-preview"
                    className="absolute inset-0 z-30"
                    style={{
                        background: previewMeta?.background
                            ? `linear-gradient(180deg, rgba(6,8,15,0.6) 0%, rgba(6,8,15,0.95) 100%), url(${previewMeta.background}) center/cover no-repeat`
                            : 'radial-gradient(ellipse at center, #0a1020 0%, #06080f 70%)',
                        opacity: streamReady ? 0 : 1,
                        transition: 'opacity 700ms ease',
                    }}
                >
                    <div className="absolute inset-0 flex items-center justify-center">
                        <div
                            className="flex items-end gap-10"
                            style={{
                                paddingLeft: 'clamp(40px, 6vw, 120px)',
                                paddingRight: 'clamp(40px, 6vw, 120px)',
                                maxWidth: '1280px',
                            }}
                        >
                            {previewMeta?.poster && (
                                <img
                                    src={previewMeta.poster}
                                    alt=""
                                    className="rounded-2xl shrink-0"
                                    style={{
                                        width: 'clamp(180px, 18vw, 280px)',
                                        aspectRatio: '2/3',
                                        objectFit: 'cover',
                                        boxShadow:
                                            '0 24px 80px rgba(0,0,0,0.6), 0 0 0 1px rgba(255,255,255,0.06)',
                                    }}
                                />
                            )}
                            <div className="flex-1 min-w-0">
                                <div
                                    className="vesper-eyebrow"
                                    style={{
                                        marginBottom: 12,
                                        color: 'var(--vesper-blue)',
                                    }}
                                >
                                    Now playing · ON NOW TV V2
                                </div>
                                <h1
                                    className="vesper-display"
                                    style={{
                                        fontSize: 'clamp(36px, 4.4vw, 64px)',
                                        letterSpacing: '-0.035em',
                                        lineHeight: 0.98,
                                        color: '#fff',
                                    }}
                                >
                                    {previewMeta?.title || title}
                                </h1>

                                {(previewMeta?.year ||
                                    previewMeta?.rating ||
                                    previewMeta?.runtime) && (
                                    <div
                                        className="flex items-center gap-3 mt-3 vesper-meta flex-wrap"
                                        style={{ fontSize: 14 }}
                                    >
                                        {previewMeta?.year && (
                                            <span style={{ color: 'var(--vesper-blue)' }}>
                                                {previewMeta.year}
                                            </span>
                                        )}
                                        {previewMeta?.rating && (
                                            <>
                                                <Bullet />
                                                <span>★ {previewMeta.rating}</span>
                                            </>
                                        )}
                                        {previewMeta?.runtime && (
                                            <>
                                                <Bullet />
                                                <span>{previewMeta.runtime}</span>
                                            </>
                                        )}
                                        {previewMeta?.genres?.map((g) => (
                                            <React.Fragment key={g}>
                                                <Bullet />
                                                <span>{g}</span>
                                            </React.Fragment>
                                        ))}
                                    </div>
                                )}

                                {previewMeta?.synopsis && (
                                    <p
                                        className="mt-4 max-w-[58ch]"
                                        style={{
                                            fontSize: 'clamp(13px, 1vw, 16px)',
                                            lineHeight: 1.55,
                                            color: 'var(--vesper-text-2)',
                                            display: '-webkit-box',
                                            WebkitLineClamp: 3,
                                            WebkitBoxOrient: 'vertical',
                                            overflow: 'hidden',
                                        }}
                                    >
                                        {previewMeta.synopsis}
                                    </p>
                                )}

                                {/* Status pills */}
                                <div className="flex items-center gap-3 mt-6 flex-wrap">
                                    <StatusPill
                                        active={streamReady}
                                        label={streamReady ? 'Stream ready' : 'Loading stream'}
                                    />
                                    <StatusPill
                                        active={!subsLoading && subs.length > 0}
                                        loading={subsLoading}
                                        label={
                                            subsLoading
                                                ? 'Loading subtitles'
                                                : subs.length > 0
                                                ? `${subs.filter((s) => /^en/i.test(s.lang || '')).length} English subtitles`
                                                : imdbId
                                                ? 'No subtitles found'
                                                : 'Subtitles unavailable'
                                        }
                                    />
                                </div>
                            </div>
                        </div>
                    </div>

                    {/* Buffering shimmer at bottom */}
                    <div
                        className="absolute bottom-0 left-0 right-0 h-1 overflow-hidden"
                        style={{ background: 'rgba(255,255,255,0.04)' }}
                    >
                        <div
                            className="vesper-shimmer h-full"
                            style={{
                                width: streamReady ? '100%' : '40%',
                                background:
                                    'linear-gradient(90deg, var(--vesper-blue) 0%, rgba(var(--vesper-blue-rgb),0.5) 100%)',
                                transition: 'width 600ms ease',
                            }}
                        />
                    </div>
                </div>
            )}

            {showUnmuteHint && !loading && !showPreview && (
                <button
                    data-testid="unmute-hint"
                    onClick={toggleMute}
                    className="absolute inset-0 z-20 flex flex-col items-center justify-center gap-4"
                    style={{
                        background:
                            'radial-gradient(ellipse at center, rgba(6,8,15,0.55) 0%, rgba(6,8,15,0.85) 100%)',
                        cursor: 'pointer',
                        border: 'none',
                        color: 'var(--vesper-text)',
                    }}
                >
                    <div
                        className="flex items-center justify-center rounded-full vesper-pulse"
                        style={{
                            width: 132,
                            height: 132,
                            background:
                                'radial-gradient(circle, rgba(var(--vesper-blue-rgb),0.95) 0%, rgba(var(--vesper-blue-rgb),0.7) 70%, rgba(var(--vesper-blue-rgb),0) 100%)',
                            color: '#06080f',
                            boxShadow:
                                '0 0 80px rgba(var(--vesper-blue-rgb),0.7), 0 0 120px rgba(var(--vesper-blue-rgb),0.35)',
                        }}
                    >
                        <VolumeX size={56} strokeWidth={2.2} />
                    </div>
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 'clamp(40px, 5vw, 64px)',
                            letterSpacing: '-0.02em',
                            textShadow: '0 4px 24px rgba(0,0,0,0.6)',
                        }}
                    >
                        Click to unmute
                    </div>
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 14,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-2)',
                        }}
                    >
                        Or press any key on your remote
                    </div>
                </button>
            )}

            {error && (
                <div className="absolute inset-x-0 bottom-32 flex justify-center z-30">
                    <div
                        className="px-6 py-4 rounded-xl"
                        style={{
                            background: 'rgba(255,80,80,0.12)',
                            border: '1px solid rgba(255,80,80,0.45)',
                            color: '#ffb5b5',
                            fontSize: 16,
                        }}
                    >
                        {error}
                    </div>
                </div>
            )}

            {/* Subtitle picker overlay */}
            {pickerOpen && (
                <div
                    data-testid="subtitle-picker"
                    className="absolute inset-0 z-40 flex items-center justify-end"
                    style={{
                        background:
                            'linear-gradient(90deg, rgba(0,0,0,0) 0%, rgba(0,0,0,0.55) 50%, rgba(0,0,0,0.85) 100%)',
                    }}
                    onClick={() => setPickerOpen(false)}
                >
                    <div
                        className="vesper-glass"
                        style={{
                            width: 460,
                            maxHeight: '82vh',
                            margin: 32,
                            borderRadius: 20,
                            display: 'flex',
                            flexDirection: 'column',
                            background: 'rgba(10,14,24,0.92)',
                            border: '1px solid rgba(255,255,255,0.08)',
                            backdropFilter: 'blur(24px)',
                        }}
                        onClick={(e) => e.stopPropagation()}
                    >
                        <header
                            className="flex items-center justify-between"
                            style={{ padding: '20px 24px' }}
                        >
                            <div>
                                <div className="vesper-eyebrow">Subtitles</div>
                                <div
                                    className="vesper-display mt-1"
                                    style={{ fontSize: 22, letterSpacing: '-0.02em' }}
                                >
                                    Choose a track
                                </div>
                            </div>
                            <button
                                data-testid="subtitle-picker-close"
                                data-focusable="true"
                                data-focus-style="quiet"
                                tabIndex={0}
                                onClick={() => setPickerOpen(false)}
                                aria-label="Close"
                                className="flex items-center justify-center rounded-full"
                                style={{
                                    width: 40,
                                    height: 40,
                                    background: 'rgba(255,255,255,0.06)',
                                    border: '1px solid rgba(255,255,255,0.1)',
                                    color: 'var(--vesper-text-2)',
                                }}
                            >
                                <CloseIcon size={16} />
                            </button>
                        </header>

                        <div
                            className="flex-1 overflow-y-auto"
                            style={{ padding: '0 16px 16px' }}
                        >
                            <SubRow
                                testId="subtitle-off"
                                active={activeSubId === null}
                                title="Off"
                                detail="No captions"
                                onClick={removeActiveTrack}
                            />

                            {subsLoading && subs.length === 0 ? (
                                <div
                                    className="flex items-center gap-3 px-3 py-4"
                                    style={{
                                        color: 'var(--vesper-text-2)',
                                        fontSize: 14,
                                    }}
                                >
                                    <Loader2 className="vesper-spin" size={16} />
                                    Loading subtitles…
                                </div>
                            ) : subs.length === 0 ? (
                                <div
                                    className="px-3 py-5"
                                    style={{
                                        color: 'var(--vesper-text-3)',
                                        fontSize: 14,
                                    }}
                                >
                                    No subtitles found. Make sure the
                                    OpenSubtitles addon is installed on Sources.
                                </div>
                            ) : (
                                subsByLang.map((g) => (
                                    <div key={g.lang} className="mt-3">
                                        <div
                                            className="vesper-mono px-3 mb-1"
                                            style={{
                                                fontSize: 11,
                                                letterSpacing: '0.22em',
                                                textTransform: 'uppercase',
                                                color: 'var(--vesper-text-3)',
                                            }}
                                        >
                                            {g.label} · {g.items.length}
                                        </div>
                                        {g.items.slice(0, 6).map((s, i) => (
                                            <SubRow
                                                key={s.id}
                                                testId={`subtitle-${g.lang}-${i}`}
                                                active={activeSubId === s.id}
                                                title={`${g.label} · option ${i + 1}`}
                                                detail={s.url
                                                    .split('/')
                                                    .slice(-1)[0]
                                                    .slice(0, 60)}
                                                onClick={() => applySubtitle(s)}
                                            />
                                        ))}
                                    </div>
                                ))
                            )}
                        </div>
                    </div>
                </div>
            )}

            {/* Bottom right action cluster */}
            <div className="absolute bottom-8 right-8 z-10 flex items-center gap-3">
                {Host.isAndroid && url && !partyCode && (
                    <button
                        data-testid="player-external"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => {
                            Host.playExternal({
                                url,
                                title: previewMeta?.title || title,
                                type,
                            });
                        }}
                        aria-label="Open in external player"
                        className="flex items-center gap-2 h-12 px-5 rounded-full font-sans font-medium"
                        style={{
                            background:
                                'linear-gradient(180deg, rgba(var(--vesper-blue-rgb),0.95) 0%, rgba(var(--vesper-blue-rgb),0.75) 100%)',
                            color: '#06080f',
                            fontSize: 14,
                            boxShadow:
                                '0 4px 24px rgba(var(--vesper-blue-rgb),0.35), 0 0 0 1px rgba(var(--vesper-blue-rgb),0.4) inset',
                        }}
                    >
                        <ExternalLink size={16} strokeWidth={2.4} />
                        Open in VLC
                    </button>
                )}
                {imdbId && (
                    <button
                        data-testid="player-subtitles"
                        data-focusable="true"
                        data-focus-style="quiet"
                        tabIndex={0}
                        onClick={() => setPickerOpen((s) => !s)}
                        aria-label="Subtitles"
                        className="flex items-center justify-center rounded-full"
                        style={{
                            width: 48,
                            height: 48,
                            background: 'rgba(17,24,39,0.85)',
                            color:
                                activeSubId !== null
                                    ? 'var(--vesper-blue)'
                                    : 'var(--vesper-text)',
                            border: '1px solid rgba(255,255,255,0.15)',
                            position: 'relative',
                        }}
                    >
                        <SubtitlesIcon size={18} />
                        {activeSubId !== null && (
                            <span
                                style={{
                                    position: 'absolute',
                                    top: 6,
                                    right: 6,
                                    width: 8,
                                    height: 8,
                                    borderRadius: '50%',
                                    background: 'var(--vesper-blue)',
                                    boxShadow:
                                        '0 0 10px rgba(var(--vesper-blue-rgb),0.9)',
                                }}
                            />
                        )}
                    </button>
                )}
                <button
                    data-testid="player-mute"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={toggleMute}
                    aria-label={muted ? 'Unmute' : 'Mute'}
                    className="flex items-center justify-center rounded-full"
                    style={{
                        width: 48,
                        height: 48,
                        background: 'rgba(17,24,39,0.85)',
                        color: muted ? 'var(--vesper-blue)' : 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.15)',
                    }}
                >
                    {muted ? <VolumeX size={18} /> : <Volume2 size={18} />}
                </button>
                <button
                    data-testid="player-toggle"
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={togglePlay}
                    className="flex items-center gap-2 h-12 px-6 rounded-full"
                    style={{
                        background: 'rgba(17,24,39,0.85)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.15)',
                    }}
                >
                    {playing ? <Pause size={18} /> : <Play size={18} />}
                    {playing ? 'Pause' : 'Play'}
                </button>
            </div>
        </div>
    );
}

/**
 * Top-right "Watching together with…" card shown in the player's
 * auto-hiding top bar when a Watch Party is active.  Lists the
 * OTHER members (not the local viewer) with their avatar circle.
 * Falls back to initial-letter discs if avatar lookup misses.
 */
function WatchingWithCard({ partyCode, myMemberId, members, connected }) {
    const others = (members || []).filter((m) => m.id && m.id !== myMemberId);
    const headline = others.length === 0
        ? 'Watching alone'
        : others.length === 1
        ? `Watching with ${others[0].name || 'a friend'}`
        : `Watching with ${others.length} friends`;
    return (
        <div
            data-testid="watching-with-card"
            className="ml-auto flex items-center gap-3 rounded-2xl"
            style={{
                padding: '8px 14px 8px 10px',
                background: 'rgba(8, 11, 20, 0.78)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.35)',
                backdropFilter: 'blur(10px)',
                boxShadow: '0 8px 24px rgba(0,0,0,0.45)',
            }}
        >
            {/* Avatar stack — overlapping circles up to 3 then a +N pill */}
            {others.length > 0 && (
                <div className="flex items-center" style={{ marginRight: 2 }}>
                    {others.slice(0, 3).map((m, idx) => (
                        <MiniAvatar
                            key={m.id}
                            member={m}
                            style={{ marginLeft: idx === 0 ? 0 : -10, zIndex: 10 - idx }}
                        />
                    ))}
                    {others.length > 3 && (
                        <div
                            className="vesper-mono"
                            style={{
                                marginLeft: -10,
                                width: 30,
                                height: 30,
                                borderRadius: '50%',
                                background: 'rgba(var(--vesper-blue-rgb), 0.22)',
                                border: '2px solid rgba(8, 11, 20, 0.78)',
                                color: 'var(--vesper-blue-bright)',
                                fontSize: 10,
                                fontWeight: 700,
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                zIndex: 5,
                            }}
                        >
                            +{others.length - 3}
                        </div>
                    )}
                </div>
            )}

            <div className="flex flex-col" style={{ minWidth: 0, maxWidth: 220 }}>
                <span
                    className="vesper-mono"
                    style={{
                        fontSize: 9,
                        letterSpacing: '0.26em',
                        textTransform: 'uppercase',
                        color: 'var(--vesper-blue-bright)',
                        marginBottom: 2,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 6,
                    }}
                >
                    <span
                        style={{
                            display: 'inline-block',
                            width: 6,
                            height: 6,
                            borderRadius: '50%',
                            background: connected ? '#3ee07a' : '#f7c948',
                            boxShadow: '0 0 6px currentColor',
                        }}
                    />
                    Watch Party · {partyCode}
                </span>
                <span
                    className="font-sans"
                    style={{
                        fontSize: 13,
                        color: '#fff',
                        fontWeight: 500,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                    }}
                >
                    {headline}
                </span>
            </div>
        </div>
    );
}

function MiniAvatar({ member, style }) {
    const av = member?.avatar ? getAvatar(member.avatar) : null;
    const src = av?.src || '';
    const initial = (member?.name || '?').trim()[0] || '?';
    return (
        <div
            style={{
                width: 30,
                height: 30,
                borderRadius: '50%',
                overflow: 'hidden',
                position: 'relative',
                border: '2px solid rgba(8, 11, 20, 0.78)',
                background:
                    'linear-gradient(135deg, rgba(93,200,255,0.35) 0%, rgba(93,200,255,0.1) 100%)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                ...style,
            }}
        >
            {src ? (
                <img
                    src={src}
                    alt=""
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    onError={(e) => { e.currentTarget.style.display = 'none'; }}
                />
            ) : (
                <span
                    className="vesper-display"
                    style={{
                        fontSize: 13,
                        color: 'var(--vesper-blue-bright)',
                        fontWeight: 700,
                        textTransform: 'uppercase',
                    }}
                >
                    {initial}
                </span>
            )}
        </div>
    );
}



const SubRow = ({ testId, active, title, detail, onClick }) => (
    <button
        data-testid={testId}
        data-focusable="true"
        data-focus-style="pill"
        tabIndex={0}
        onClick={onClick}
        className="w-full text-left flex items-center gap-3 px-3 rounded-xl"
        style={{
            height: 56,
            background: active ? 'rgba(var(--vesper-blue-rgb),0.12)' : 'rgba(255,255,255,0.02)',
            border: active
                ? '1px solid rgba(var(--vesper-blue-rgb),0.45)'
                : '1px solid rgba(255,255,255,0.04)',
            color: active ? 'var(--vesper-text)' : 'var(--vesper-text-2)',
            marginTop: 6,
        }}
    >
        <span
            className="flex items-center justify-center rounded-full"
            style={{
                width: 28,
                height: 28,
                background: active ? 'var(--vesper-blue)' : 'rgba(255,255,255,0.08)',
                color: active ? '#06080f' : 'var(--vesper-text-2)',
                flexShrink: 0,
            }}
        >
            {active ? <Check size={14} /> : null}
        </span>
        <span className="min-w-0 flex-1">
            <span
                className="block font-sans font-medium"
                style={{ fontSize: 15, lineHeight: 1.2 }}
            >
                {title}
            </span>
            <span
                className="block vesper-mono truncate"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.06em',
                    color: 'var(--vesper-text-3)',
                    marginTop: 2,
                }}
            >
                {detail}
            </span>
        </span>
    </button>
);

const CenterMsg = ({ children }) => (
    <div
        className="w-screen h-screen flex flex-col items-center justify-center"
        style={{
            color: 'var(--vesper-text-2)',
            fontSize: 18,
            gap: 12,
            background: 'var(--vesper-bg-0)',
        }}
    >
        {children}
    </div>
);


const Bullet = () => (
    <span
        className="inline-block w-1 h-1 rounded-full"
        style={{ background: 'rgba(255,255,255,0.32)' }}
    />
);

const StatusPill = ({ active, loading, label }) => (
    <div
        className="flex items-center gap-2 rounded-full"
        style={{
            paddingLeft: 14,
            paddingRight: 16,
            height: 36,
            background: active
                ? 'rgba(var(--vesper-blue-rgb),0.14)'
                : 'rgba(255,255,255,0.04)',
            border: active
                ? '1px solid rgba(var(--vesper-blue-rgb),0.4)'
                : '1px solid rgba(255,255,255,0.08)',
            color: active ? 'var(--vesper-blue)' : 'var(--vesper-text-2)',
            fontSize: 12,
            fontWeight: 500,
            letterSpacing: '0.02em',
        }}
    >
        {loading ? (
            <Loader2 size={12} className="vesper-spin" />
        ) : active ? (
            <Check size={12} strokeWidth={2.5} />
        ) : (
            <span
                className="block w-2 h-2 rounded-full"
                style={{ background: 'rgba(255,255,255,0.25)' }}
            />
        )}
        {label}
    </div>
);
