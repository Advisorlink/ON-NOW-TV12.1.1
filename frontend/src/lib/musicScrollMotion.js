// One short, retargetable scroll per container. Focus stays synchronous;
// interrupted presses replace the target rather than queueing more movement.
const running = new Map();
export function cancelMusicScroll(root) {
    running.forEach((frame, el) => {
        if (!root || el === root || root.contains(el)) {
            cancelAnimationFrame(frame);
            running.delete(el);
        }
    });
}

export function musicScrollTo(el, left, top) {
    if (running.has(el)) cancelAnimationFrame(running.get(el));
    running.delete(el);
    const x = Math.max(0, Math.min(left, el.scrollWidth - el.clientWidth));
    const y = Math.max(0, Math.min(top, el.scrollHeight - el.clientHeight));
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        el.scrollTo({ left: x, top: y, behavior: 'auto' });
        return;
    }
    const fromX = el.scrollLeft, fromY = el.scrollTop;
    const start = performance.now();
    const step = (now) => {
        if (!el.isConnected) { running.delete(el); return; }
        const t = Math.min(1, (now - start) / 180);
        const eased = 1 - Math.pow(1 - t, 3);
        el.scrollTo({ left: fromX + (x - fromX) * eased, top: fromY + (y - fromY) * eased, behavior: 'auto' });
        if (t < 1) running.set(el, requestAnimationFrame(step));
        else running.delete(el);
    };
    running.set(el, requestAnimationFrame(step));
}