import React, { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Info, X, Copy, Trash2 } from 'lucide-react';
import { PlaybackButton } from './PlaybackButton';
import { useNativeBackTrap, triggerTrapBack } from '@/hooks/useNativeBackTrap';
import {
    subscribePlaybackTrace, latestRequestTrace, formatPlaybackTrace, clearPlaybackTrace,
} from '@/lib/playbackTrace';

export const WEB_PLAYBACK_BUILD = 'play-diag-5';

export const PlaybackDetails = ({ className = 'mt-4' }) => {
    const [open, setOpen] = useState(false);
    const [trace, setTrace] = useState(() => latestRequestTrace());
    const [copied, setCopied] = useState(false);
    const trigger = useRef(null);
    const dismiss = () => {
        setOpen(false);
        requestAnimationFrame(() => trigger.current?.focus({ preventScroll: true }));
    };
    useEffect(() => subscribePlaybackTrace(() => setTrace(latestRequestTrace())), []);
    useNativeBackTrap(open, () => { dismiss(); return false; });
    let info = null;
    try { info = JSON.parse(window.OnNowTV?.getPlaybackHostInfo?.() || 'null'); } catch { /* older APK */ }
    const last = trace[trace.length - 1];
    const stage = last ? last.status : 'No play request yet';
    const copyTrace = async () => {
        const text = `Web ${WEB_PLAYBACK_BUILD} · Android ${info ? `${info.version} (${info.build}) api${info.androidApi}` : 'legacy'}\n${formatPlaybackTrace(trace)}`;
        try { await navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1500); }
        catch { window.prompt('Copy playback trace:', text); }
    };
    return <>
        <PlaybackButton ref={trigger} data-testid="playback-details-open" data-focusable="true" data-focus-style="quiet" onClick={() => setOpen(true)} className={`${className} inline-flex items-center gap-2 rounded px-2 py-2`} style={{ fontSize: 12, color: 'var(--vesper-text-3)' }}><Info size={14} /> Playback details</PlaybackButton>
        {open && createPortal(<div data-testid="playback-details-overlay" className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(0,0,0,.8)' }}>
            <section data-testid="playback-details-dialog" data-focus-trap="true" role="dialog" aria-modal="true" aria-label="Playback details" className="w-full max-w-lg rounded-lg p-6 max-h-[92vh] overflow-y-auto" style={{ background: 'var(--vesper-bg-1)' }} onKeyDown={(e) => {
                if (['Escape', 'Backspace', 'GoBack'].includes(e.key)) { e.preventDefault(); e.stopPropagation(); triggerTrapBack(); }
                if (e.key === 'Tab') { e.preventDefault(); e.stopPropagation(); e.currentTarget.querySelector('[data-testid="playback-details-close"]')?.focus(); }
            }}>
                <h2 data-testid="playback-details-title" className="text-lg font-semibold">Playback details</h2>
                <dl className="mt-4 grid grid-cols-2 gap-3 text-sm">
                    <dt>Web build</dt><dd data-testid="playback-web-build">{WEB_PLAYBACK_BUILD}</dd>
                    <dt>Android app</dt><dd data-testid="playback-native-build">{info ? `${info.version} (${info.build}) · API ${info.androidApi}` : 'Legacy / browser'}</dd>
                    <dt>Player connection</dt><dd data-testid="playback-protocol">{info ? `Protocol ${info.protocol}` : 'Legacy bridge'}</dd>
                    <dt>Android tap recovery</dt><dd data-testid="playback-touch-recovery">{info?.touchRecovery ? 'Available' : 'Not in this APK'}</dd>
                    <dt>Last native stage</dt><dd data-testid="playback-native-stage" className="break-words">{stage}</dd>
                </dl>
                <h3 className="mt-5 text-sm font-semibold" style={{ color: 'var(--vesper-text-2)' }}>Last play request — step by step</h3>
                <ol data-testid="playback-trace-list" className="mt-2 space-y-1 text-xs font-mono" style={{ color: 'var(--vesper-text-2)' }}>
                    {trace.length === 0 && <li data-testid="playback-trace-empty">Tap Play, then reopen this panel.</li>}
                    {trace.map((e, i) => (
                        <li key={`${e.at}-${i}`} data-testid={`playback-trace-row-${i}`} className="break-words">
                            <span style={{ color: 'var(--vesper-text-3)' }}>{e.t == null ? '' : `+${e.t}ms `}</span>
                            <span style={{ color: e.status === 'failed' ? '#ff6b6b' : 'var(--vesper-text)' }}>{e.status}</span>
                            {e.message ? <span> — {e.message}</span> : null}
                        </li>
                    ))}
                </ol>
                <div className="mt-6 flex flex-wrap gap-2">
                    <PlaybackButton data-testid="playback-details-close" data-focusable="true" data-initial-focus="true" data-focus-style="pill" autoFocus onClick={() => triggerTrapBack()} className="flex items-center gap-2 rounded px-4 py-2" style={{ background: 'var(--vesper-blue)', color: 'var(--vesper-bg-0)' }}><X size={16} /> Close</PlaybackButton>
                    <PlaybackButton data-testid="playback-details-copy" data-focusable="true" data-focus-style="pill" onClick={copyTrace} className="flex items-center gap-2 rounded px-4 py-2" style={{ background: 'var(--vesper-bg-2)', color: 'var(--vesper-text)' }}><Copy size={16} /> {copied ? 'Copied' : 'Copy'}</PlaybackButton>
                    <PlaybackButton data-testid="playback-details-clear" data-focusable="true" data-focus-style="pill" onClick={() => clearPlaybackTrace()} className="flex items-center gap-2 rounded px-4 py-2" style={{ background: 'var(--vesper-bg-2)', color: 'var(--vesper-text)' }}><Trash2 size={16} /> Clear</PlaybackButton>
                </div>
            </section>
        </div>, document.body)}
    </>;
};
