import React, { useEffect, useRef, useState } from 'react';
import { Tv, Maximize2 } from 'lucide-react';
import { getStreamUrl } from '@/lib/xtream';

const bridge = () => (typeof window !== 'undefined' ? window.OnNowTV : null);
export const hasNativePreview = () => {
    try { return typeof bridge()?.showLivePreview === 'function' && !!bridge()?.hasLivePreview?.(); } catch { return false; }
};

/* Floating live preview of the focused channel (native ExoPlayer overlay in the APK). */
export default function LivePreview({ provider, channel, paused = false, delayMs = 650 }) {
    const boxRef = useRef(null);
    const [state, setState] = useState('idle');
    const url = provider && channel ? getStreamUrl(provider, 'live', channel.stream_id, 'ts') : null;

    const rect = () => {
        const r = boxRef.current?.getBoundingClientRect();
        if (!r) return null;
        return [r.left, r.top, r.width, r.height, document.documentElement.clientWidth];
    };

    useEffect(() => {
        if (!hasNativePreview()) return undefined;
        if (!url || paused) { bridge().hideLivePreview(); setState('idle'); return undefined; }
        setState('buffering');
        const t = setTimeout(() => {
            const r = rect();
            if (r) bridge().showLivePreview(url, ...r);
        }, delayMs);
        return () => clearTimeout(t);
    }, [url, paused, delayMs]);

    useEffect(() => {
        if (!hasNativePreview()) return undefined;
        const onState = (e) => setState(String(e.detail || 'idle'));
        const onResume = () => { const r = rect(); if (url && !paused && r) bridge().showLivePreview(url, ...r); };
        const onLayout = () => { const r = rect(); if (r) bridge().moveLivePreview(...r); };
        window.addEventListener('reborn:live-preview', onState);
        window.addEventListener('reborn:resume', onResume);
        window.addEventListener('resize', onLayout);
        return () => {
            window.removeEventListener('reborn:live-preview', onState);
            window.removeEventListener('reborn:resume', onResume);
            window.removeEventListener('resize', onLayout);
        };
    }, [url, paused]);

    useEffect(() => () => { try { bridge()?.hideLivePreview?.(); } catch { /* web */ } }, []);

    const native = hasNativePreview();
    return (
        <div
            ref={boxRef}
            data-testid="live-preview-box"
            style={{
                position: 'relative', width: '100%', aspectRatio: '16 / 9', borderRadius: 14, overflow: 'hidden',
                background: 'linear-gradient(160deg, rgba(var(--vesper-blue-rgb),0.18), rgba(0,0,0,0.65))',
                border: '1px solid rgba(var(--vesper-blue-rgb),0.45)',
                boxShadow: '0 0 0 1px rgba(255,255,255,0.04), 0 18px 48px rgba(0,0,0,0.55), 0 0 32px rgba(var(--vesper-blue-rgb),0.25)',
            }}
        >
            {(!native || state !== 'playing') && (
                <div className="absolute inset-0 flex flex-col items-center justify-center gap-2" style={{ color: 'var(--vesper-text-2)' }}>
                    {channel?.stream_icon ? (
                        <img src={channel.stream_icon} alt="" style={{ height: '34%', maxWidth: '55%', objectFit: 'contain', filter: 'drop-shadow(0 4px 12px rgba(0,0,0,0.6))' }} />
                    ) : <Tv size={40} />}
                    <span className="vesper-mono" style={{ fontSize: 11, letterSpacing: '0.18em', textTransform: 'uppercase' }}>
                        {!native ? 'Preview on your TV box' : state === 'error' ? 'Reconnecting…' : 'Tuning…'}
                    </span>
                </div>
            )}
            <div className="absolute flex items-center gap-1.5" style={{ right: 10, bottom: 8, padding: '4px 9px', borderRadius: 999, background: 'rgba(0,0,0,0.55)', color: 'var(--vesper-text)', fontSize: 11, pointerEvents: 'none' }}>
                <Maximize2 size={12} /> OK · Full screen
            </div>
        </div>
    );
}
