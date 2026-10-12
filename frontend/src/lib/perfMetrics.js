/** Performance metrics that capture D-pad chunkiness (not idle FPS):
 *  key-press → paint latency, long main-thread tasks and slow frames.
 *  Ref-counted: HUD and benchmark both start/stop it. */
const MAX_PRESSES = 40;
const listeners = new Set();
let active = 0;
let raf = 0;
let lastFrame = 0;
let observer = null;
let fpsTimer = 0;
let framesThisSecond = 0;

const state = {
    presses: [],
    longTasks: 0,
    longTaskMs: 0,
    slowFrames: 0,
    frames: 0,
    fps: 0,
};

const notify = () => listeners.forEach((fn) => { try { fn(); } catch { /* ignore */ } });

const settled = new Set();
/** Resolve after the frame following the current task has painted. */
export function afterPaint() {
    return new Promise((resolve) => {
        requestAnimationFrame(() => setTimeout(resolve, 0));
    });
}

function onKeyDown(e) {
    if (!e.key || !e.key.startsWith('Arrow')) return;
    const t0 = performance.now();
    const p = afterPaint().then(() => {
        settled.delete(p);
        state.presses.push(performance.now() - t0);
        if (state.presses.length > MAX_PRESSES) state.presses.shift();
        notify();
    });
    settled.add(p);
}

function frameTick(t) {
    if (lastFrame) {
        state.frames += 1;
        framesThisSecond += 1;
        if (t - lastFrame > 34) state.slowFrames += 1;
    }
    lastFrame = t;
    raf = requestAnimationFrame(frameTick);
}

export function startMetrics() {
    active += 1;
    if (active > 1) return;
    window.addEventListener('keydown', onKeyDown, true);
    lastFrame = 0;
    raf = requestAnimationFrame(frameTick);
    fpsTimer = setInterval(() => { state.fps = framesThisSecond; framesThisSecond = 0; notify(); }, 1000);
    try {
        observer = new PerformanceObserver((list) => {
            list.getEntries().forEach((en) => { state.longTasks += 1; state.longTaskMs += en.duration; });
            notify();
        });
        observer.observe({ type: 'longtask', buffered: false });
    } catch { observer = null; }
}

export function stopMetrics() {
    active = Math.max(0, active - 1);
    if (active > 0) return;
    window.removeEventListener('keydown', onKeyDown, true);
    cancelAnimationFrame(raf);
    clearInterval(fpsTimer);
    if (observer) { try { observer.disconnect(); } catch { /* ignore */ } observer = null; }
}

export function resetMetrics() {
    state.presses = [];
    state.longTasks = 0;
    state.longTaskMs = 0;
    state.slowFrames = 0;
    state.frames = 0;
    notify();
}

export function subscribe(fn) {
    listeners.add(fn);
    return () => listeners.delete(fn);
}

export function getSnapshot() {
    const p = state.presses;
    const sorted = [...p].sort((a, b) => a - b);
    const avg = p.length ? p.reduce((a, b) => a + b, 0) / p.length : 0;
    const at = (q) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * q))] : 0);
    const p90 = at(0.9);
    return {
        presses: p.length,
        avgMs: Math.round(avg),
        medianMs: Math.round(at(0.5)),
        worstMs: Math.round(sorted[sorted.length - 1] || 0),
        p90Ms: Math.round(p90),
        longTasks: state.longTasks,
        longTaskMs: Math.round(state.longTaskMs),
        slowFrames: state.slowFrames,
        frames: state.frames,
        fps: state.fps,
    };
}

export function gradeFor(avgMs) {
    if (!avgMs) return { label: '—', color: '#9FB3C8' };
    if (avgMs <= 25) return { label: 'Smooth', color: '#7CF1A1' };
    if (avgMs <= 50) return { label: 'OK', color: '#B8F17C' };
    if (avgMs <= 100) return { label: 'Chunky', color: '#FFD36B' };
    return { label: 'Very chunky', color: '#FF6B6B' };
}
