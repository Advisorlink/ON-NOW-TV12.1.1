/**
 * <HdTrailerVideo/> — plays YouTube's HD DASH pair (video-only 1080p/720p
 * + m4a audio) INSIDE the WebView with two synced media elements, so the
 * fullscreen trailer stays in the app (no native player hand-off) and is
 * never limited to the ≤720p/360p muxed file.  When `audioUrl` is empty
 * the single URL is treated as muxed and plays as-is.
 */
import React, { useEffect, useRef, useState } from 'react';

const HARD_RESYNC = 0.25;
const NUDGE = 0.06;

export default function HdTrailerVideo({
    videoUrl,
    audioUrl = '',
    startAt = 0,
    controls = false,
    videoRef,
    onPlaying,
    onEnded,
    onError,
    style,
}) {
    const vRef = useRef(null);
    const aRef = useRef(null);
    const [visible, setVisible] = useState(false);
    const dual = !!audioUrl;

    useEffect(() => {
        if (videoRef) videoRef.current = vRef.current;
    });

    useEffect(() => {
        const v = vRef.current;
        const a = aRef.current;
        if (!v || !a || !dual) return undefined;
        let audioStalled = false;
        const align = (force) => {
            if (!force && Math.abs(v.currentTime - a.currentTime) < 0.1) return;
            try { a.currentTime = v.currentTime; } catch { /* ignore */ }
        };
        const playAudio = () => {
            if (v.paused || v.ended) return;
            align(false);
            a.play().catch(() => {});
        };
        const onPlay = () => playAudio();
        const onPause = () => a.pause();
        const onSeeking = () => { a.pause(); align(true); };
        const onSeeked = () => { if (!v.paused) playAudio(); };
        const onWaiting = () => a.pause();
        const onPlayingV = () => { if (a.paused) playAudio(); };
        const onRate = () => { a.playbackRate = v.playbackRate; };
        const onEndedV = () => a.pause();
        const onAudioWaiting = () => {
            // Our own seeks fire 'waiting' too — only a real stall pauses video.
            if (v.paused || a.seeking) return;
            audioStalled = true;
            v.pause();
        };
        const onAudioReady = () => {
            if (!audioStalled) return;
            audioStalled = false;
            v.play().catch(() => {});
        };
        v.addEventListener('play', onPlay);
        v.addEventListener('pause', onPause);
        v.addEventListener('seeking', onSeeking);
        v.addEventListener('seeked', onSeeked);
        v.addEventListener('waiting', onWaiting);
        v.addEventListener('playing', onPlayingV);
        v.addEventListener('ratechange', onRate);
        v.addEventListener('ended', onEndedV);
        a.addEventListener('waiting', onAudioWaiting);
        a.addEventListener('canplay', onAudioReady);
        a.addEventListener('playing', onAudioReady);
        const tick = setInterval(() => {
            if (v.paused || v.ended || a.paused || audioStalled) return;
            const drift = v.currentTime - a.currentTime;
            const abs = Math.abs(drift);
            if (abs > HARD_RESYNC) {
                align(true);
                a.playbackRate = v.playbackRate;
            } else if (abs > NUDGE) {
                a.playbackRate = v.playbackRate * (drift > 0 ? 1.04 : 0.96);
            } else if (a.playbackRate !== v.playbackRate) {
                a.playbackRate = v.playbackRate;
            }
        }, 300);
        return () => {
            clearInterval(tick);
            v.removeEventListener('play', onPlay);
            v.removeEventListener('pause', onPause);
            v.removeEventListener('seeking', onSeeking);
            v.removeEventListener('seeked', onSeeked);
            v.removeEventListener('waiting', onWaiting);
            v.removeEventListener('playing', onPlayingV);
            v.removeEventListener('ratechange', onRate);
            v.removeEventListener('ended', onEndedV);
            a.removeEventListener('waiting', onAudioWaiting);
            a.removeEventListener('canplay', onAudioReady);
            a.removeEventListener('playing', onAudioReady);
            a.pause();
        };
    }, [videoUrl, audioUrl, dual]);

    return (
        <>
            <video
                ref={vRef}
                key={videoUrl}
                data-testid="trailer-video-hd"
                data-dual={dual ? 'true' : 'false'}
                src={videoUrl}
                muted={dual}
                autoPlay
                playsInline
                controls={controls}
                onLoadedMetadata={(e) => {
                    if (startAt > 0) {
                        try { e.currentTarget.currentTime = startAt; } catch { /* ignore */ }
                    }
                }}
                onPlaying={() => {
                    setVisible(true);
                    onPlaying?.();
                }}
                onEnded={onEnded}
                onError={onError}
                style={{
                    position: 'absolute',
                    inset: 0,
                    width: '100%',
                    height: '100%',
                    background: '#000',
                    objectFit: 'contain',
                    opacity: visible ? 1 : 0,
                    transition: 'opacity 200ms ease-in',
                    ...style,
                }}
            />
            {dual && (
                <audio
                    ref={aRef}
                    key={audioUrl}
                    data-testid="trailer-audio-hd"
                    src={audioUrl}
                    preload="auto"
                    onError={onError}
                    style={{ display: 'none' }}
                />
            )}
        </>
    );
}
