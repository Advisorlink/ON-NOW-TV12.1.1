/** Automated D-pad benchmark: fires a fixed script of arrow presses on
 *  the Home rails and scores the run with perfMetrics, so each
 *  Performance-lab variant gets a comparable number. */
import { afterPaint, getSnapshot, resetMetrics, startMetrics, stopMetrics } from '@/lib/perfMetrics';
import { getAllPerf, PERF_OPTIONS } from '@/lib/perfLab';

const PENDING_KEY = 'vesper-perf-bench-pending';
const RESULTS_KEY = 'vesper-perf-bench-results';
export const BENCH_DONE = 'vesper:perf-bench-done';
export const BENCH_PROGRESS = 'vesper:perf-bench-progress';

const SCRIPT = [
    ...Array(10).fill('ArrowRight'),
    ...Array(10).fill('ArrowLeft'),
    'ArrowDown', 'ArrowDown',
    ...Array(8).fill('ArrowRight'),
    'ArrowDown',
    ...Array(6).fill('ArrowLeft'),
    'ArrowUp', 'ArrowUp', 'ArrowUp',
];
export const BENCH_PRESSES = SCRIPT.length;

export function requestBenchmark() {
    try { sessionStorage.setItem(PENDING_KEY, '1'); } catch { /* ignore */ }
}
export function consumeBenchmarkRequest() {
    try {
        const v = sessionStorage.getItem(PENDING_KEY) === '1';
        if (v) sessionStorage.removeItem(PENDING_KEY);
        return v;
    } catch { return false; }
}

export function getBenchResults() {
    try { return JSON.parse(localStorage.getItem(RESULTS_KEY) || '[]') || []; } catch { return []; }
}
export function clearBenchResults() {
    try { localStorage.removeItem(RESULTS_KEY); } catch { /* ignore */ }
    window.dispatchEvent(new CustomEvent(BENCH_DONE));
}

/** "rows:lazy · poster:small" — only the settings that differ from default. */
export function describeSettings(settings) {
    const short = {
        rowLoading: 'rows', itemsPerRow: 'tiles', recycle: 'recycle', layers: 'layers', blur: 'blur',
        heroMotion: 'kenburns', heroFollow: 'herofollow', shelfMotion: 'motion', posterSize: 'poster',
    };
    const diffs = Object.entries(settings || {})
        .filter(([k, v]) => k !== 'hud' && PERF_OPTIONS[k] && PERF_OPTIONS[k].default !== v)
        .map(([k, v]) => `${short[k] || k}:${v}`);
    return diffs.length ? diffs.join(' · ') : 'all defaults';
}

const wait = (ms) => new Promise((r) => setTimeout(r, ms));

function press(key) {
    const target = document.activeElement || document.body;
    const init = { key, code: key, bubbles: true, cancelable: true };
    target.dispatchEvent(new KeyboardEvent('keydown', init));
    target.dispatchEvent(new KeyboardEvent('keyup', init));
}

export async function runBenchmark() {
    const region = document.querySelector('[data-testid="shelves-region"]');
    const first = region?.querySelector('[data-focusable="true"]');
    if (!first) throw new Error('Home rails not ready');
    startMetrics();
    try {
        first.focus({ preventScroll: true });
        first.setAttribute('data-focused', 'true');
        await wait(600);
        // Warm-up (not scored): first moves pay for cold image decodes.
        for (const k of ['ArrowRight', 'ArrowRight', 'ArrowRight', 'ArrowLeft', 'ArrowLeft', 'ArrowLeft']) {
            press(k); await afterPaint(); await wait(140);
        }
        await wait(400);
        resetMetrics();
        for (let i = 0; i < SCRIPT.length; i += 1) {
            press(SCRIPT[i]);
            await afterPaint();
            await wait(140);
            window.dispatchEvent(new CustomEvent(BENCH_PROGRESS, { detail: { done: i + 1, total: SCRIPT.length } }));
        }
        await wait(400);
        const snap = getSnapshot();
        const result = { ts: Date.now(), settings: getAllPerf(), ...snap };
        const list = [result, ...getBenchResults()].slice(0, 10);
        try { localStorage.setItem(RESULTS_KEY, JSON.stringify(list)); } catch { /* ignore */ }
        window.dispatchEvent(new CustomEvent(BENCH_DONE, { detail: result }));
        return result;
    } finally {
        stopMetrics();
    }
}
