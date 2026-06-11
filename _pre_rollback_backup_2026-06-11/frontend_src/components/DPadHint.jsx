/**
 * <DPadHint /> — small, dismissable on-screen controls cheat-sheet.
 *
 * Mounts in the corner of any page that wires it in.  Shows for ~5
 * seconds on first load, then fades.  After the user has seen it 3
 * times globally we stop showing it altogether (they've internalised
 * the controls).  Auto-resets to 0 if the user clears app data.
 *
 * Props:
 *   • items   — Array of hint chips to show.  Each chip has `keys`
 *               (string or React node) + `label`.  e.g.
 *                 [{ keys: '←', label: 'BACK' },
 *                  { keys: '↑↓←→', label: 'NAVIGATE' },
 *                  { keys: 'OK', label: 'PLAY' },
 *                  { keys: 'HOLD OK', label: 'REMIND' }]
 *   • storageKey — optional; per-page namespace so each page's hint
 *               increments its own counter (so a user who has seen
 *               the Home hint 3 times can still see the Sports hint
 *               on their first visit there).
 */

import React, { useEffect, useState } from 'react';

const GLOBAL_KEY = 'vesper-dpad-hint-views';
const MAX_VIEWS = 3;

function readCount(key) {
    try {
        const raw = localStorage.getItem(key);
        return raw ? parseInt(raw, 10) || 0 : 0;
    } catch { return 0; }
}
function bumpCount(key) {
    try { localStorage.setItem(key, String(readCount(key) + 1)); } catch { /* ignore */ }
}

export default function DPadHint({ items, storageKey, durationMs = 5000 }) {
    const fullKey = storageKey ? `${GLOBAL_KEY}:${storageKey}` : GLOBAL_KEY;
    const [visible, setVisible] = useState(() => readCount(fullKey) < MAX_VIEWS);

    useEffect(() => {
        if (!visible) return undefined;
        bumpCount(fullKey);
        const t = setTimeout(() => setVisible(false), durationMs);
        return () => clearTimeout(t);
    }, [visible, fullKey, durationMs]);

    if (!visible) return null;

    return (
        <div
            data-testid="dpad-hint"
            style={{
                position: 'fixed',
                bottom: 22,
                right: 28,
                display: 'flex',
                gap: 10,
                padding: '10px 16px',
                background: 'rgba(8,11,20,0.92)',
                border: '1px solid rgba(255,255,255,0.10)',
                borderRadius: 999,
                boxShadow: '0 12px 28px rgba(0,0,0,0.55), inset 0 1px 0 rgba(255,255,255,0.04)',
                zIndex: 100,
                pointerEvents: 'none',
                opacity: visible ? 1 : 0,
                transition: 'opacity 0.5s ease',
                fontFamily: 'monospace',
                fontSize: 11,
                color: '#9DA5B5',
                letterSpacing: '0.04em',
                whiteSpace: 'nowrap',
            }}
        >
            {items.map((it, i) => (
                <React.Fragment key={i}>
                    {i > 0 && (
                        <span style={{ color: '#3a3f4d', alignSelf: 'center' }}>·</span>
                    )}
                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
                        <span style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            minWidth: 22,
                            height: 22,
                            padding: '0 7px',
                            background: 'rgba(93,200,255,0.16)',
                            border: '1px solid rgba(93,200,255,0.42)',
                            borderRadius: 6,
                            fontWeight: 700,
                            color: '#5DC8FF',
                            fontSize: 10,
                            letterSpacing: '0.04em',
                        }}>
                            {it.keys}
                        </span>
                        <span style={{
                            fontWeight: 700,
                            letterSpacing: '0.18em',
                            color: '#E6EAF2',
                        }}>
                            {it.label}
                        </span>
                    </span>
                </React.Fragment>
            ))}
        </div>
    );
}
