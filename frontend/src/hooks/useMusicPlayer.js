// useMusicPlayer.js — Global music-player state with a singleton
// HTMLAudioElement and a queue.  Plays anything with an audio URL:
// Deezer 30-second previews, Radio Browser live streams, podcast
// RSS enclosures.  Subscribers re-render on every state change.
//
// API:
//   useMusicPlayer() → { state, controls }
//
//   state.kind         'track' | 'radio' | 'episode' | null
//   state.current      { id, title, subtitle, artwork, audio_url, ... }
//   state.queue        array of upcoming items (kind=track only)
//   state.queueIndex   index into queue
//   state.isPlaying    boolean
//   state.position     seconds elapsed (NaN for live radio)
//   state.duration     seconds total   (NaN for live radio)
//
//   controls.playTrack(track, queue?)   → set queue + play
//   controls.playRadio(station)         → swap to live stream
//   controls.playEpisode(episode, podcast?)
//   controls.pause() / resume() / toggle()
//   controls.next() / previous()
//   controls.seek(seconds)
//   controls.setVolume(0..1)
import { useEffect, useState } from 'react';
import { resolveTrackStream } from '../lib/musicResolver';

class PlayerEngine {
    constructor() {
        this.audio = typeof window !== 'undefined' ? new Audio() : null;
        this.listeners = new Set();
        this.state = {
            kind: null,
            current: null,
            queue: [],
            queueIndex: -1,
            isPlaying: false,
            position: 0,
            duration: 0,
            volume: 0.85,
        };
        // v2.8.53 — YouTube IFrame Player API instance (lazy-built).
        // Tracks resolved via the native bridge as
        // `source: 'youtube-iframe'` play through here instead of
        // the HTML5 <audio> element.
        this.yt = null;
        this.ytReady = false;
        this.ytPollTimer = null;
        this.activeEngine = 'audio'; // 'audio' | 'youtube'
        if (!this.audio) return;
        this.audio.preload = 'auto';
        this.audio.volume = this.state.volume;
        this.audio.addEventListener('play',  () => {
            if (this.activeEngine === 'audio') this.update({ isPlaying: true });
        });
        this.audio.addEventListener('pause', () => {
            if (this.activeEngine === 'audio') this.update({ isPlaying: false });
        });
        this.audio.addEventListener('ended', () => {
            if (this.activeEngine === 'audio') this.next();
        });
        this.audio.addEventListener('timeupdate', () => {
            if (this.activeEngine !== 'audio') return;
            this.update({
                position: this.audio.currentTime || 0,
                duration: this.audio.duration || 0,
            });
        });
        this.audio.addEventListener('error', (e) => {
            const code = this.audio?.error?.code;
            const src = this.audio?.src || '';
            // eslint-disable-next-line no-console
            console.warn('[music-player] audio error', { code, src: src.slice(0, 120) });
            // v2.12.10 — Selective auto-recovery.  Only switch to
            // YouTube IFrame Player when the error is a GENUINE
            // decode/unsupported-format failure (codes 3 + 4).
            // The previous v2.12.6 handler fired on EVERY error
            // event — including `MEDIA_ERR_ABORTED` (code 1, which
            // fires every time we change `audio.src` to load a new
            // track) and `MEDIA_ERR_NETWORK` (code 2, transient
            // Wi-Fi glitches).  That over-triggered the IFrame
            // fallback so every song ended up on IFrame + ads
            // instead of the ad-free NewPipe direct URL.
            //
            //   code 1 = MEDIA_ERR_ABORTED         → ignore (source change)
            //   code 2 = MEDIA_ERR_NETWORK         → ignore (transient)
            //   code 3 = MEDIA_ERR_DECODE          → recover (real codec bug)
            //   code 4 = MEDIA_ERR_SRC_NOT_SUPPORTED → recover (bad URL/format)
            if (code !== 3 && code !== 4) return;
            if (this.activeEngine !== 'audio') return;
            const cur = this.state.current;
            if (!cur) return;
            // Also skip recovery if this src was set less than 500 ms
            // ago — that's a race between _setSource() and the WebView
            // firing a stale error from the PREVIOUS src.
            const setAt = this._lastSetSourceAt || 0;
            if (Date.now() - setAt < 500) return;
            // Guard against loops — only auto-recover once per track.
            if (cur._audioErrorRetried) {
                if (cur.preview_url && cur.preview_url !== this.audio?.src) {
                    // Try the 30-second Deezer preview as last resort.
                    this.update({
                        current: { ...cur, _streamSource: 'preview', _isFullTrack: false },
                    });
                    this._setSource(cur.preview_url);
                }
                return;
            }
            // First recovery attempt.
            if (cur._ytId || cur._streamSource === 'newpipe' || cur._streamSource === 'youtube-direct') {
                const ytId = cur._ytId || cur._resolvedYtId;
                if (ytId) {
                    // eslint-disable-next-line no-console
                    console.info('[music-player] recovering via YouTube IFrame for', ytId);
                    this.update({
                        current: {
                            ...cur,
                            _audioErrorRetried: true,
                            _streamSource: 'youtube-iframe',
                            _isFullTrack: true,
                            _ytId: ytId,
                        },
                    });
                    this._playYouTubeVideo(ytId);
                    return;
                }
            }
            // No yt_id available — try Deezer preview.
            if (cur.preview_url) {
                // eslint-disable-next-line no-console
                console.info('[music-player] recovering via Deezer preview');
                this.update({
                    current: { ...cur, _audioErrorRetried: true, _streamSource: 'preview', _isFullTrack: false },
                });
                this._setSource(cur.preview_url);
            }
        });
    }
    subscribe(fn) {
        this.listeners.add(fn);
        return () => this.listeners.delete(fn);
    }
    update(patch) {
        this.state = { ...this.state, ...patch };
        this.listeners.forEach((fn) => fn(this.state));
    }
    _setSource(url) {
        if (!this.audio || !url) return;
        this._stopYouTube();
        this.activeEngine = 'audio';
        // v2.12.10 — Timestamp the src change so the audio-error
        // handler above can distinguish a "real" decode error from
        // the spurious MEDIA_ERR_ABORTED event that fires when we
        // swap the src to a new track.
        this._lastSetSourceAt = Date.now();
        try {
            this.audio.src = url;
            this.audio.play().catch(() => {});
        } catch (e) { /* swallow */ }
    }

    // ── YouTube IFrame Player adapter ──────────────────────────
    //
    // v2.8.53 — Tracks resolved as `source: 'youtube-iframe'` play
    // through YouTube's own IFrame Player API instead of HTML5
    // <audio>.  This is the only path that consistently works in
    // late 2025 / early 2026 — every "InnerTube + ANDROID/IOS/
    // TVHTML5 client" extraction route now requires PoToken /
    // visitor-data that we can't generate without bundling a JS
    // interpreter.  YouTube's own iframe player handles PoToken,
    // signatures, ads, and signed CDN URLs internally — and it's
    // 100 % within their published API terms.

    _ensureYouTubePlayer(initialVideoId = null) {
        if (typeof window === 'undefined') return Promise.resolve(null);
        if (this.yt && this.ytReady) return Promise.resolve(this.yt);
        // YouTubeIFrameHost mounts the script + host div; we
        // wait on its promise.
        const apiPromise = window.__ytApiLoadingPromise
            || Promise.resolve(window.YT);
        return apiPromise.then((YT) => {
            if (!YT || !YT.Player) return null;
            if (this.yt && this.ytReady) return this.yt;
            return new Promise((resolve) => {
                const opts = {
                    height: '1',
                    width: '1',
                    playerVars: {
                        // v2.8.65 — `autoplay: 1` triggers BROWSER
                        // muted-autoplay policy ("trusted but quiet")
                        // even right after a user click.  We let the
                        // explicit `playVideo() + unMute()` on onReady
                        // do the work instead — it preserves the
                        // user-gesture sound permission.
                        autoplay: 0,
                        // v2.8.67 — Explicitly request unmuted playback.
                        // Without this YouTube defaults to whatever the
                        // last-known mute-state was for the embed
                        // (some browsers persist it via cookies), which
                        // is exactly what was leaving Karaoke silent on
                        // the HK1 box.
                        mute: 0,
                        controls: 0,
                        disablekb: 1,
                        playsinline: 1,
                        modestbranding: 1,
                        rel: 0,
                    },
                    events: {
                        onReady: () => {
                            this.ytReady = true;
                            try {
                                this.yt.setVolume(Math.round(this.state.volume * 100));
                                // v2.8.65 — explicitly unMute. YT
                                // autoplay-muted policy on many
                                // browsers/devices.
                                this.yt.unMute();
                                this.yt.playVideo();
                            } catch { /* ignore */ }
                            resolve(this.yt);
                        },
                        onStateChange: (e) => this._onYouTubeStateChange(e),
                        onError: (e) => {
                            // eslint-disable-next-line no-console
                            console.warn('[music-player] yt error', e?.data);
                        },
                    },
                };
                // v2.8.64 — pass videoId at construction.  Some
                // browsers silently ignore `loadVideoById` calls
                // on players that were never given an initial
                // videoId, leaving the iframe stuck at /embed/?.
                if (initialVideoId) opts.videoId = initialVideoId;
                this.yt = new YT.Player('onnowtv-ytplayer-target', opts);
            });
        });
    }

    _onYouTubeStateChange(e) {
        if (!window.YT || !window.YT.PlayerState) return;
        const { PLAYING, PAUSED, ENDED, BUFFERING } = window.YT.PlayerState;
        if (this.activeEngine !== 'youtube') return;
        if (e.data === PLAYING) {
            // v2.8.67 — On every PLAYING transition, force-unmute and
            // re-apply the user volume.  YouTube sometimes flips the
            // player back to muted between BUFFERING → PLAYING (the
            // "auto-mute when no user gesture detected" heuristic),
            // and we need to undo that the moment playback starts.
            // Belt-and-braces retry pattern: immediate + 250 ms +
            // 750 ms in case the API queues the call.
            this._forceUnmuteRetry();
            this.update({ isPlaying: true });
            this._startYouTubeTimeUpdates();
        } else if (e.data === PAUSED) {
            this.update({ isPlaying: false });
            this._stopYouTubeTimeUpdates();
        } else if (e.data === ENDED) {
            this._stopYouTubeTimeUpdates();
            this.next();
        } else if (e.data === BUFFERING) {
            this._startYouTubeTimeUpdates();
        }
    }

    _forceUnmuteRetry() {
        const tryUnmute = () => {
            if (!this.yt || !this.ytReady) return;
            // v2.8.81 — Respect an explicit mute request (Silent
            // Spotlight challenge).  Without this guard, the
            // auto-unmute loop fights the spotlight mute and the
            // audio never actually drops out.
            if (this.state.muted) return;
            try {
                if (typeof this.yt.isMuted === 'function' && this.yt.isMuted()) {
                    this.yt.unMute();
                }
                this.yt.setVolume(Math.round(this.state.volume * 100));
            } catch { /* ignore */ }
        };
        tryUnmute();
        setTimeout(tryUnmute, 250);
        setTimeout(tryUnmute, 750);
        setTimeout(tryUnmute, 1500);
    }

    _startYouTubeTimeUpdates() {
        if (this.ytPollTimer) return;
        this.ytPollTimer = setInterval(() => {
            if (this.activeEngine !== 'youtube' || !this.yt || !this.ytReady) return;
            try {
                const pos = this.yt.getCurrentTime() || 0;
                const dur = this.yt.getDuration() || 0;
                this.update({ position: pos, duration: dur });
            } catch { /* ignore */ }
        }, 500);
    }
    _stopYouTubeTimeUpdates() {
        if (this.ytPollTimer) {
            clearInterval(this.ytPollTimer);
            this.ytPollTimer = null;
        }
    }
    _stopYouTube() {
        this._stopYouTubeTimeUpdates();
        if (this.yt && this.ytReady) {
            try { this.yt.stopVideo(); } catch { /* ignore */ }
        }
    }
    async _playYouTubeVideo(videoId) {
        if (this.audio) {
            try { this.audio.pause(); this.audio.src = ''; } catch { /* ignore */ }
        }
        this.activeEngine = 'youtube';
        const firstInit = !this.yt;
        const player = await this._ensureYouTubePlayer(firstInit ? videoId : null);
        if (!player) {
            console.warn('[music-player] YouTube IFrame API failed to load');
            return;
        }
        if (firstInit) {
            // Already loaded with the correct videoId in constructor.
            try {
                player.setVolume(Math.round(this.state.volume * 100));
                // v2.8.65 — explicitly unMute.  YouTube auto-mutes
                // autoplaying videos on mobile + some desktop policies
                // even when the user clicked; we re-arm audio on every
                // playback.
                player.unMute();
            } catch { /* ignore */ }
            return;
        }
        try {
            player.loadVideoById(videoId);
            player.setVolume(Math.round(this.state.volume * 100));
            player.unMute();
            setTimeout(() => {
                try {
                    player.playVideo();
                    player.unMute();
                } catch { /* ignore */ }
            }, 120);
        } catch (e) {
            console.warn('[music-player] loadVideoById failed', e);
        }
    }
    playTrack(track, queue = null, opts = null) {
        if (!track) return;
        const newQueue = queue && queue.length ? queue : [track];
        const idx = newQueue.findIndex((t) => t.id === track.id);
        const finalIdx = idx >= 0 ? idx : 0;
        // v2.8.81 — Karaoke instrumental: when the Karaoke flow
        // sets sessionStorage `tunes-karaoke-mode`, default `karaoke`
        // to true so the resolver fetches an instrumental version.
        // The user can flip vocals back on via `setKaraokeInstrumental`.
        let karaoke = opts && typeof opts.karaoke === 'boolean'
            ? opts.karaoke
            : null;
        if (karaoke === null) {
            try { karaoke = sessionStorage.getItem('tunes-karaoke-mode') === '1'; }
            catch { karaoke = false; }
        }
        this.update({
            kind: 'track',
            current: { ...track, _resolving: true, _isFullTrack: false, _karaoke: karaoke },
            queue: newQueue,
            queueIndex: finalIdx,
            karaokeInstrumental: karaoke,
        });
        this._loadTrackStream(track, { karaoke });
    }
    async _loadTrackStream(track, opts) {
        const resolved = await resolveTrackStream(track, opts);

        // Confirm the user hasn't navigated away
        if (!this.state.current || this.state.current.id !== track.id) return;

        // YouTube IFrame route — no audio URL, just a videoId.
        if (resolved && resolved.source === 'youtube-iframe' && resolved.yt_id) {
            this.update({
                current: {
                    ...track,
                    _resolving: false,
                    _isFullTrack: true,
                    _streamSource: 'youtube-iframe',
                    _ytId: resolved.yt_id,
                },
            });
            this._playYouTubeVideo(resolved.yt_id);
            return;
        }

        if (resolved?.stream_url) {
            this.update({
                current: {
                    ...track,
                    _resolving: false,
                    _isFullTrack: !!resolved.is_full_track,
                    _streamSource: resolved.source,
                    // v2.12.6 — Stash yt_id so the <audio> error
                    // handler can fall through to the YouTube
                    // IFrame Player using the SAME video if HTML5
                    // <audio> can't decode the native URL (WebM
                    // Opus on old WebViews, expired signature, HLS
                    // manifest, etc.).
                    _ytId: resolved.yt_id || null,
                    _resolvedYtId: resolved.yt_id || null,
                },
            });
            this._setSource(resolved.stream_url);
        } else {
            // Nothing playable.  Update state so the UI can show
            // "unavailable" — and don't try to play anything.
            this.update({
                current: {
                    ...track,
                    _resolving: false,
                    _isFullTrack: false,
                    _streamSource: 'unavailable',
                },
            });
        }
    }
    playRadio(station) {
        if (!station) return;
        const item = {
            id: station.id,
            title: station.name,
            subtitle: station.country || '',
            artwork: station.favicon || null,
            audio_url: station.stream_url,
            _raw: station,
        };
        this.update({
            kind: 'radio',
            current: item,
            queue: [],
            queueIndex: -1,
        });
        this._setSource(station.stream_url);
    }
    playEpisode(episode, podcast = null) {
        if (!episode) return;
        const item = {
            id: episode.id,
            title: episode.title,
            subtitle: podcast?.title || '',
            artwork: episode.artwork || podcast?.artwork,
            audio_url: episode.audio_url,
            _raw: episode,
        };
        this.update({
            kind: 'episode',
            current: item,
            queue: [],
            queueIndex: -1,
        });
        this._setSource(episode.audio_url);
    }
    pause() {
        if (this.activeEngine === 'youtube' && this.yt && this.ytReady) {
            try { this.yt.pauseVideo(); } catch { /* ignore */ }
            return;
        }
        this.audio?.pause();
    }
    resume() {
        if (this.activeEngine === 'youtube' && this.yt && this.ytReady) {
            try { this.yt.playVideo(); this._forceUnmuteRetry(); } catch { /* ignore */ }
            return;
        }
        this.audio?.play().catch(() => {});
    }
    toggle() {
        if (this.activeEngine === 'youtube' && this.yt && this.ytReady) {
            try {
                if (this.state.isPlaying) this.yt.pauseVideo();
                else { this.yt.playVideo(); this._forceUnmuteRetry(); }
            } catch { /* ignore */ }
            return;
        }
        if (!this.audio) return;
        if (this.state.isPlaying) this.audio.pause();
        else this.audio.play().catch(() => {});
    }
    next() {
        const { queue, queueIndex, kind } = this.state;
        if (kind !== 'track' || !queue.length) return;
        const ni = queueIndex + 1;
        if (ni >= queue.length) {
            this.audio?.pause();
            this.update({ isPlaying: false });
            return;
        }
        const nxt = queue[ni];
        this.update({
            current: { ...nxt, _resolving: true, _isFullTrack: false },
            queueIndex: ni,
        });
        this._loadTrackStream(nxt);
    }
    previous() {
        const { queue, queueIndex, kind } = this.state;
        if (kind !== 'track' || queueIndex <= 0) {
            try { if (this.audio) this.audio.currentTime = 0; } catch {/*ignore*/}
            return;
        }
        const ni = queueIndex - 1;
        const prev = queue[ni];
        this.update({
            current: { ...prev, _resolving: true, _isFullTrack: false },
            queueIndex: ni,
        });
        this._loadTrackStream(prev);
    }
    seek(seconds) {
        if (this.activeEngine === 'youtube' && this.yt && this.ytReady) {
            try { this.yt.seekTo(seconds, true); } catch { /* ignore */ }
            return;
        }
        if (!this.audio) return;
        try { this.audio.currentTime = seconds; } catch {/*ignore*/}
    }
    setVolume(v) {
        const vv = Math.max(0, Math.min(1, v));
        if (this.audio) this.audio.volume = vv;
        if (this.yt && this.ytReady) {
            try { this.yt.setVolume(Math.round(vv * 100)); } catch { /* ignore */ }
        }
        this.update({ volume: vv });
    }

    // v2.8.81 — Independent mute control that does NOT touch
    // state.volume.  Used by the Silent Spotlight karaoke challenge:
    // we need to drop the audio for ~7 s and bring it back at the
    // user's previously-set volume.  Using setVolume(0) overwrote
    // the persistent volume so it could never be restored.
    setMuted(muted) {
        const m = !!muted;
        if (this.audio) {
            try { this.audio.muted = m; } catch { /* ignore */ }
        }
        if (this.yt && this.ytReady) {
            try {
                if (m) this.yt.mute();
                else this.yt.unMute();
            } catch { /* ignore */ }
        }
        // Track in state so the UI can reflect mute (e.g. spotlight
        // banner) but DON'T touch volume.
        this.update({ muted: m });
    }

    // v2.8.81 — Vocals toggle for karaoke mode.  Re-resolves the
    // CURRENT track with the karaoke flag flipped and reloads it.
    setKaraokeInstrumental(on) {
        const t = this.state.current;
        if (!t) return;
        this.update({
            karaokeInstrumental: !!on,
            current: { ...t, _karaoke: !!on, _resolving: true },
        });
        this._loadTrackStream(t, { karaoke: !!on });
    }
}

const engine = (typeof window !== 'undefined' && (window.__musicEngine || (window.__musicEngine = new PlayerEngine()))) || new PlayerEngine();

export function useMusicPlayer() {
    const [state, setState] = useState(engine.state);
    useEffect(() => engine.subscribe(setState), []);
    return {
        state,
        controls: {
            playTrack: engine.playTrack.bind(engine),
            playRadio: engine.playRadio.bind(engine),
            playEpisode: engine.playEpisode.bind(engine),
            pause: engine.pause.bind(engine),
            resume: engine.resume.bind(engine),
            toggle: engine.toggle.bind(engine),
            next: engine.next.bind(engine),
            previous: engine.previous.bind(engine),
            seek: engine.seek.bind(engine),
            setVolume: engine.setVolume.bind(engine),
            setMuted: engine.setMuted.bind(engine),
            setKaraokeInstrumental: engine.setKaraokeInstrumental.bind(engine),
        },
    };
}
