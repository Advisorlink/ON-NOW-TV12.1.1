/**
 * DebugTouchOverlay — visible-on-screen diagnostic strip.
 *
 * Shows live device state at the top-right of the screen so we can
 * see exactly what the WebView reports without needing adb logcat.
 *
 * Toggled on via:
 *   - URL query    ?debug=1
 *   - localStorage  vesper-debug-overlay = "1"
 *
 * Displays:
 *   - APK version (window.__APP_VERSION__)
 *   - innerWidth / innerHeight
 *   - matchMedia (pointer: coarse) result
 *   - navigator.maxTouchPoints
 *   - body[data-platform] value
 *   - Live touch event log (last touch event type + coords)
 *   - touch-action computed style on a sampled poster button
 */

import React, { useEffect, useState } from 'react';

export default function DebugTouchOverlay() {
    const enabled = (() => {
        try {
            if (window.location.search.indexOf('debug=1') !== -1) return true;
            if (localStorage.getItem('vesper-debug-overlay') === '1') return true;
            /* v2.7.89 — Enabled by default for ONE BUILD so the user
               can see live state on their phone without needing to
               flip a switch.  Will be disabled by default in v2.7.90. */
            return true;
        } catch {
            return true;
        }
    })();
    const [state, setState] = useState(() => snapshot());
    const [lastTouch, setLastTouch] = useState('—');

    useEffect(() => {
        if (!enabled) return;
        const refresh = () => setState(snapshot());
        refresh();
        const id = setInterval(refresh, 600);
        const onTouch = (e) => {
            const t = e.touches?.[0] || e.changedTouches?.[0];
            const tgt = e.target;
            const tagName = tgt?.tagName?.toLowerCase() || '?';
            const isPoster = !!tgt?.closest?.('[data-testid^="poster-"], [data-testid^="network-tile-"]');
            const ta = tgt?.closest?.('button, [data-focusable="true"]');
            const computedTA = ta ? getComputedStyle(ta).touchAction : 'n/a';
            const x = t ? Math.round(t.clientX) : '-';
            const y = t ? Math.round(t.clientY) : '-';
            setLastTouch(
                `${e.type} ${tagName}${isPoster ? '*' : ''} @${x},${y} ta=${computedTA}`
            );
        };
        document.addEventListener('touchstart', onTouch, { passive: true });
        document.addEventListener('touchmove', onTouch, { passive: true });
        document.addEventListener('touchend', onTouch, { passive: true });
        return () => {
            clearInterval(id);
            document.removeEventListener('touchstart', onTouch);
            document.removeEventListener('touchmove', onTouch);
            document.removeEventListener('touchend', onTouch);
        };
    }, [enabled]);

    if (!enabled) return null;

    return (
        <div
            data-testid="debug-touch-overlay"
            style={{
                position: 'fixed',
                top: 'calc(8px + env(safe-area-inset-top, 0px))',
                right: 8,
                left: 8,
                zIndex: 999999,
                background: 'rgba(0, 0, 0, 0.86)',
                border: '1px solid #2BB6FF',
                borderRadius: 10,
                color: '#9DDCFF',
                fontFamily: 'ui-monospace, monospace',
                fontSize: 10,
                lineHeight: 1.35,
                padding: '8px 10px',
                pointerEvents: 'none',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-all',
            }}
        >
            <div style={{ color: '#2BB6FF', fontWeight: 700, marginBottom: 2 }}>
                Vesper Debug · v2.7.89
            </div>
            <div>app: {state.appVersion}</div>
            <div>viewport: {state.w}×{state.h}</div>
            <div>pointer:coarse = {String(state.coarse)}</div>
            <div>maxTouchPoints = {state.touchPoints}</div>
            <div>data-platform = {state.platform || '(unset)'}</div>
            <div>UA: {state.ua.slice(0, 90)}</div>
            <div style={{ marginTop: 4, color: '#FFD700' }}>last touch:</div>
            <div>{lastTouch}</div>
        </div>
    );
}

function snapshot() {
    return {
        appVersion: window.__APP_VERSION__ || '(web)',
        w: window.innerWidth,
        h: window.innerHeight,
        coarse: window.matchMedia?.('(pointer: coarse)').matches ?? null,
        touchPoints: navigator.maxTouchPoints,
        platform: document.body?.getAttribute('data-platform') || '',
        ua: navigator.userAgent || '',
    };
}
