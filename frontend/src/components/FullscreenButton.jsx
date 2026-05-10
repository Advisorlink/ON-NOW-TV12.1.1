import React from 'react';
import { Maximize2, Minimize2 } from 'lucide-react';
import useFullscreen from '@/hooks/useFullscreen';
import Host from '@/lib/host';

export default function FullscreenButton() {
    const { isFs, toggle } = useFullscreen();
    // Already fullscreen inside the OnNowTV WebView wrapper — the
    // browser fullscreen API just shows a "press ESC to exit" banner
    // there which we don't want.
    if (Host.isAndroid || Host.isOnNowTV) return null;
    return (
        <button
            data-testid="fullscreen-button"
            data-focusable="true"
            data-focus-style="quiet"
            tabIndex={0}
            onClick={toggle}
            aria-label={isFs ? 'Exit fullscreen' : 'Enter fullscreen'}
            className="fixed top-6 right-8 z-[60] flex items-center justify-center rounded-full"
            style={{
                width: 52,
                height: 52,
                background: 'rgba(17,24,39,0.6)',
                border: '1px solid rgba(255,255,255,0.1)',
                color: 'var(--vesper-text-2)',
                backdropFilter: 'blur(8px)',
            }}
        >
            {isFs ? (
                <Minimize2 size={20} strokeWidth={1.6} />
            ) : (
                <Maximize2 size={20} strokeWidth={1.6} />
            )}
        </button>
    );
}
