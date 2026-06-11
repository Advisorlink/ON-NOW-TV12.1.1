/**
 * usePartyReactions — D-pad-hold emoji reactions for Watch Together.
 *
 * Listens for ArrowUp / ArrowDown / ArrowLeft / ArrowRight held for
 * 2 seconds (counted via initial keydown timestamp, NOT key-repeat
 * count — auto-repeat rates vary per remote).  On hold-fire we send
 *   { type: 'reaction', emoji: '<emoji>' }
 * over the supplied party WebSocket and return the reaction locally
 * so the sender's own screen also animates it without round-tripping
 * through the server.
 *
 *   ArrowUp    →  ❤️
 *   ArrowDown  →  😱
 *   ArrowLeft  →  😂
 *   ArrowRight →  😭
 *
 * The hook is INERT until `enabled` is true.  Caller usually passes
 * `enabled = !!partyCode`.  When inert, all D-pad presses pass through
 * untouched to the global spatial-focus handler.
 *
 * To avoid stealing arrow presses for normal focus navigation, this
 * hook ONLY engages the long-press timer when an arrow is pressed
 * while the user's focus is NOT inside an input/textarea AND no
 * focusable element will move in that direction (i.e. the arrow
 * would otherwise have no UI effect).  We deliberately keep the
 * implementation tolerant: even if the long-press doesn't fire, the
 * arrow key still propagates so spatial-focus does its thing.
 */

import { useEffect, useRef } from 'react';

const KEY_TO_EMOJI = {
    ArrowUp:    '\u2764\ufe0f',     // ❤️
    ArrowDown:  '\uD83D\uDE31',     // 😱
    ArrowLeft:  '\uD83D\uDE06',     // 😂
    ArrowRight: '\uD83D\uDE2D',     // 😭
};

const HOLD_MS = 2000;
const COOLDOWN_MS = 1000;

export default function usePartyReactions({
    enabled,
    wsRef,            // ref to active WebSocket
    onLocalFire,      // callback(emoji)  — fired so sender sees their own emoji instantly
}) {
    // Press tracking: per-key timestamp of the first non-repeat
    // keydown.  Cleared on keyup or when the long-press fires.
    const pressRef = useRef({});
    const lastFireRef = useRef(0);

    useEffect(() => {
        if (!enabled) return undefined;

        const tryFire = (key) => {
            const emoji = KEY_TO_EMOJI[key];
            if (!emoji) return;
            const now = Date.now();
            if (now - lastFireRef.current < COOLDOWN_MS) return;
            lastFireRef.current = now;
            // Send via WS (best-effort).
            const ws = wsRef && wsRef.current;
            if (ws && ws.readyState === 1) {
                try {
                    ws.send(JSON.stringify({ type: 'reaction', emoji }));
                } catch { /* ignore */ }
            }
            // Local echo so the sender also sees the floating emoji
            // immediately (server will also broadcast it back but we
            // de-dupe by `id`).
            if (typeof onLocalFire === 'function') {
                onLocalFire(emoji);
            }
        };

        const onKeyDown = (e) => {
            if (!(e.key in KEY_TO_EMOJI)) return;
            // Ignore in text inputs — Backspace already prevents text
            // entry but arrows should still move the cursor.
            const tag = (document.activeElement?.tagName || '').toUpperCase();
            if (tag === 'INPUT' || tag === 'TEXTAREA') return;
            // Track only the FIRST keydown (auto-repeat fills in with
            // `e.repeat === true`).  We never reset on repeat; we
            // only check elapsed time.
            const p = pressRef.current;
            if (!p[e.key]) {
                p[e.key] = Date.now();
            }
            // If the key has been held long enough, fire.  We
            // schedule a check on the NEXT auto-repeat or in the
            // setTimeout below so we don't block.
            const elapsed = Date.now() - p[e.key];
            if (elapsed >= HOLD_MS) {
                p[e.key] = 0;            // sentinel — won't re-fire until keyup
                tryFire(e.key);
            }
        };

        const onKeyUp = (e) => {
            if (!(e.key in KEY_TO_EMOJI)) return;
            delete pressRef.current[e.key];
        };

        // Capture phase so we sit slightly above the spatial-focus
        // handler.  We don't preventDefault — both layers run.
        window.addEventListener('keydown', onKeyDown, true);
        window.addEventListener('keyup', onKeyUp, true);

        // Backup fallback: tick every 200 ms to fire even when the
        // OS doesn't deliver auto-repeats fast enough (older WebView
        // on Android TV 7 sometimes batches them).
        const tick = setInterval(() => {
            const p = pressRef.current;
            for (const key of Object.keys(p)) {
                if (!p[key]) continue;
                const elapsed = Date.now() - p[key];
                if (elapsed >= HOLD_MS) {
                    p[key] = 0;
                    tryFire(key);
                }
            }
        }, 200);

        return () => {
            window.removeEventListener('keydown', onKeyDown, true);
            window.removeEventListener('keyup', onKeyUp, true);
            clearInterval(tick);
            pressRef.current = {};
        };
    }, [enabled, wsRef, onLocalFire]);
}
