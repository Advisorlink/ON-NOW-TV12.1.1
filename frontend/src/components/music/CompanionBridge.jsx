import { useEffect, useRef } from 'react';
import { useLocation } from 'react-router-dom';
import { API } from '@/lib/api';
import { useMusicPlayer } from '@/hooks/useMusicPlayer';

/**
 * <CompanionBridge/> — v2.18.0 glue between the Tunes box shell and
 * the phone Companion app.  Renders nothing.  Three jobs:
 *   1. `?companionPlayTrack=<id>` deep-link → fetch + play the track.
 *   2. `onnow-companion-cmd` window events (dispatched by the native
 *      shell when the phone injects media keys) → engine controls.
 *   3. Report now-playing state to `window.OnNowTV.nowPlaying` so the
 *      phone shows a live player card with art + scrubber.
 */
export const CompanionBridge = () => {
    const { state, controls } = useMusicPlayer();
    const location = useLocation();
    const handledRef = useRef(false);
    const ctrlRef = useRef(controls);
    ctrlRef.current = controls;
    const stRef = useRef(state);
    stRef.current = state;

    useEffect(() => {
        if (handledRef.current) return;
        const params = new URLSearchParams(location.search);
        const tid = params.get('companionPlayTrack');
        if (!tid) return;
        handledRef.current = true;
        fetch(`${API}/music/track/${encodeURIComponent(tid)}`)
            .then((r) => (r.ok ? r.json() : null))
            .then((d) => {
                if (d?.track) ctrlRef.current.playTrack(d.track, [d.track]);
            })
            .catch(() => {});
    }, [location.search]);

    useEffect(() => {
        const onCmd = (e) => {
            const cmd = e?.detail?.cmd;
            const c = ctrlRef.current;
            if (cmd === 'play_pause') c.toggle();
            else if (cmd === 'play') c.resume();
            else if (cmd === 'pause') c.pause();
            else if (cmd === 'next') c.next();
            else if (cmd === 'prev') c.previous();
        };
        window.addEventListener('onnow-companion-cmd', onCmd);
        return () => window.removeEventListener('onnow-companion-cmd', onCmd);
    }, []);

    const report = (s) => {
        const bridge = window.OnNowTV;
        if (!bridge || typeof bridge.nowPlaying !== 'function') return;
        const cur = s.current;
        const payload = !cur
            ? { cleared: true }
            : {
                  title: cur.title || cur.name || '',
                  artist:
                      (cur.artist && (cur.artist.name || cur.artist)) || '',
                  poster:
                      (cur.album && cur.album.cover) || cur.artwork || cur.cover || '',
                  position_ms: Math.round((s.position || 0) * 1000) || 0,
                  duration_ms: Math.round((s.duration || 0) * 1000) || 0,
                  playing: !!s.playing,
                  live: !s.duration || !Number.isFinite(s.duration),
              };
        try {
            bridge.nowPlaying(JSON.stringify(payload));
        } catch {
            /* bridge is best-effort */
        }
    };

    /* eslint-disable react-hooks/exhaustive-deps */
    useEffect(() => {
        report(state);
    }, [state.current, state.playing]);
    /* eslint-enable react-hooks/exhaustive-deps */

    useEffect(() => {
        const t = setInterval(() => {
            const s = stRef.current;
            if (s.current && s.playing) report(s);
        }, 5000);
        return () => clearInterval(t);
    }, []);

    return null;
};

export default CompanionBridge;
