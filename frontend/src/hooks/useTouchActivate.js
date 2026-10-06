import { useRef } from 'react';

/** Handle an actual stationary touch release, not a scroll. Some Android
 * WebViews lose the later compatibility click during focus/layout changes.
 * Mouse/keyboard keep normal click semantics; the follow-up tap click is
 * consumed once, so pointerup + click can never launch two players.
 */
export default function useTouchActivate(action) {
    const current = useRef(action);
    current.current = action;
    const start = useRef(null);
    const lastTouch = useRef(-Infinity);
    const cancelTouch = () => {
        if (start.current) lastTouch.current = performance.now();
        start.current = null;
    };
    const begin = (x, y, id, target) => {
        start.current = { x, y, id, target, at: performance.now() };
    };
    const move = (x, y) => {
        const p = start.current;
        if (p && Math.hypot(x - p.x, y - p.y) > 10) cancelTouch();
    };
    const end = (x, y, id, event) => {
        const p = start.current;
        start.current = null;
        if (!p || p.id !== id || performance.now() - p.at > 900) return;
        const r = p.target.getBoundingClientRect();
        if (Math.hypot(x - p.x, y - p.y) > 10 || x < r.left || x > r.right || y < r.top || y > r.bottom) return;
        lastTouch.current = performance.now();
        current.current?.(event);
    };
    const pointerSupported = typeof window !== 'undefined' && 'PointerEvent' in window;
    return {
        onPointerDown: (e) => {
            if (e.isPrimary === false) { cancelTouch(); return; }
            if (e.isPrimary !== false && (e.pointerType === 'touch' || e.pointerType === 'pen')) begin(e.clientX, e.clientY, e.pointerId, e.currentTarget);
        },
        onPointerMove: (e) => move(e.clientX, e.clientY),
        onPointerCancel: cancelTouch,
        onPointerUp: (e) => end(e.clientX, e.clientY, e.pointerId, e),
        onTouchStart: (e) => {
            if (!pointerSupported && e.touches.length !== 1) { cancelTouch(); return; }
            if (!pointerSupported && e.touches.length === 1) { const t = e.touches[0]; begin(t.clientX, t.clientY, t.identifier, e.currentTarget); }
        },
        onTouchMove: (e) => { if (!pointerSupported && e.touches[0]) move(e.touches[0].clientX, e.touches[0].clientY); },
        onTouchCancel: cancelTouch,
        onTouchEnd: (e) => {
            if (!pointerSupported && e.changedTouches[0]) { const t = e.changedTouches[0]; end(t.clientX, t.clientY, t.identifier, e); }
        },
        onClick: (e) => {
            if (e.detail !== 0 && performance.now() - lastTouch.current < 800) {
                e.preventDefault(); e.stopPropagation(); return;
            }
            current.current?.(e);
        },
    };
}