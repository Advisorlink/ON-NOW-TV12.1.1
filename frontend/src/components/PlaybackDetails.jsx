import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Info, X } from 'lucide-react';
import { PlaybackButton } from './PlaybackButton';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';

export const PlaybackDetails = () => {
    const [open, setOpen] = useState(false);
    const [stage, setStage] = useState('No play request yet');
    const trigger = useRef(null);
    const request = useRef({ id: null, terminal: false });
    const dismiss = () => {
        setOpen(false);
        requestAnimationFrame(() => trigger.current?.focus({ preventScroll: true }));
    };
    useEffect(() => {
        const update = (e) => {
            const detail = e.detail || {};
            if (detail.status === 'dispatching') request.current = { id: detail.requestId, terminal: false };
            if (request.current.terminal || (request.current.id && detail.requestId !== request.current.id)) return;
            setStage(detail.status || 'unknown');
            if (detail.status === 'returned' || detail.status === 'failed') request.current.terminal = true;
        };
        window.addEventListener('vesper:native-playback', update);
        return () => window.removeEventListener('vesper:native-playback', update);
    }, []);
    useNativeBackTrap(open, () => { dismiss(); return false; });
    let info = null;
    try { info = JSON.parse(window.OnNowTV?.getPlaybackHostInfo?.() || 'null'); } catch { /* older APK */ }
    return <>
        <PlaybackButton ref={trigger} data-testid="playback-details-open" data-focusable="true" data-focus-style="quiet" onClick={() => setOpen(true)} className="mt-4 inline-flex items-center gap-2 rounded px-2 py-2" style={{ fontSize: 12, color: 'var(--vesper-text-3)' }}><Info size={14} /> Playback details</PlaybackButton>
        {open && createPortal(<div data-testid="playback-details-overlay" className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(0,0,0,.8)' }}>
            <section data-testid="playback-details-dialog" data-focus-trap="true" role="dialog" aria-modal="true" aria-label="Playback details" className="w-full max-w-md rounded-lg p-6" style={{ background: 'var(--vesper-bg-1)' }} onKeyDown={(e) => {
                if (['Escape', 'Backspace', 'GoBack'].includes(e.key)) { e.preventDefault(); e.stopPropagation(); triggerTrapBack(); }
                if (e.key === 'Tab') { e.preventDefault(); e.stopPropagation(); e.currentTarget.querySelector('[data-testid="playback-details-close"]')?.focus(); }
            }}>
                <h2 data-testid="playback-details-title" className="text-lg font-semibold">Playback details</h2>
                <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
                    <dt>Web build</dt><dd data-testid="playback-web-build">touch-player-3</dd>
                    <dt>Android app</dt><dd data-testid="playback-native-build">{info ? `${info.version} (${info.build})` : 'Legacy / browser'}</dd>
                    <dt>Player connection</dt><dd data-testid="playback-protocol">{info ? `Protocol ${info.protocol}` : 'Legacy bridge'}</dd>
                    <dt>Android tap recovery</dt><dd data-testid="playback-touch-recovery">{info?.touchRecovery ? 'Available' : 'Not in this APK'}</dd>
                    <dt>Last native stage</dt><dd data-testid="playback-native-stage" className="break-words">{stage}</dd>
                </dl>
                <PlaybackButton data-testid="playback-details-close" data-focusable="true" data-initial-focus="true" data-focus-style="pill" autoFocus onClick={() => triggerTrapBack()} className="mt-6 flex items-center gap-2 rounded px-4 py-2" style={{ background: 'var(--vesper-blue)', color: 'var(--vesper-bg-0)' }}><X size={16} /> Close</PlaybackButton>
            </section>
        </div>, document.body)}
    </>;
};