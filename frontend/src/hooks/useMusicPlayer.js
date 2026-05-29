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
        if (!this.audio) return;
        this.audio.preload = 'auto';
        this.audio.volume = this.state.volume;
        this.audio.addEventListener('play',  () => this.update({ isPlaying: true }));
        this.audio.addEventListener('pause', () => this.update({ isPlaying: false }));
        this.audio.addEventListener('ended', () => this.next());
        this.audio.addEventListener('timeupdate', () => this.update({
            position: this.audio.currentTime || 0,
            duration: this.audio.duration || 0,
        }));
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
        try {
            this.audio.src = url;
            this.audio.play().catch(() => {});
        } catch (e) { /* swallow */ }
    }
    playTrack(track, queue = null) {
        if (!track) return;
        const newQueue = queue && queue.length ? queue : [track];
        const idx = newQueue.findIndex((t) => t.id === track.id);
        const finalIdx = idx >= 0 ? idx : 0;
        this.update({
            kind: 'track',
            current: track,
            queue: newQueue,
            queueIndex: finalIdx,
        });
        this._setSource(track.preview_url);
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
    pause() { this.audio?.pause(); }
    resume() { this.audio?.play().catch(() => {}); }
    toggle() {
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
        this.update({ current: nxt, queueIndex: ni });
        this._setSource(nxt.preview_url);
    }
    previous() {
        const { queue, queueIndex, kind } = this.state;
        if (kind !== 'track' || queueIndex <= 0) {
            try { if (this.audio) this.audio.currentTime = 0; } catch {/*ignore*/}
            return;
        }
        const ni = queueIndex - 1;
        const prev = queue[ni];
        this.update({ current: prev, queueIndex: ni });
        this._setSource(prev.preview_url);
    }
    seek(seconds) {
        if (!this.audio) return;
        try { this.audio.currentTime = seconds; } catch {/*ignore*/}
    }
    setVolume(v) {
        if (!this.audio) return;
        const vv = Math.max(0, Math.min(1, v));
        this.audio.volume = vv;
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
