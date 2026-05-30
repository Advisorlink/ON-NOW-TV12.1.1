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
            // eslint-disable-next-line no-console
            console.warn('[music-player] audio error', e);
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
    playTrack(track, queue = null) {
        if (!track) return;
        const newQueue = queue && queue.length ? queue : [track];
        const idx = newQueue.findIndex((t) => t.id === track.id);
        const finalIdx = idx >= 0 ? idx : 0;
        this.update({
            kind: 'track',
            current: { ...track, _resolving: true, _isFullTrack: false },
            queue: newQueue,
            queueIndex: finalIdx,
        });
        // v2.8.47 — Try resolving the full-track URL via the backend.
        // If a full track is found we swap to it; otherwise we play
        // the 30-second Deezer preview as a graceful fallback.
        this._loadTrackStream(track);
    }
    async _loadTrackStream(track) {
        // v2.8.53 — Resolver chain:
        //   1) Native bridge — returns either a direct stream_url
        //      (legacy code paths) OR `source: 'youtube-iframe'`
        //      with a yt_id, which routes through YouTube's IFrame
        //      Player API (only consistently-working full-track
        //      path in late 2025 / early 2026).
        //   2) Backend `/api/music/stream/{id}` (admin cookies →
        //      JioSaavn → Audius → preview).
        //   3) Deezer 30-s preview as last resort.
        const resolved = await resolveTrackStream(track);

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
            try { this.yt.playVideo(); } catch { /* ignore */ }
            return;
        }
        this.audio?.play().catch(() => {});
    }
    toggle() {
        if (this.activeEngine === 'youtube' && this.yt && this.ytReady) {
            try {
                if (this.state.isPlaying) this.yt.pauseVideo();
                else this.yt.playVideo();
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
        },
    };
}
