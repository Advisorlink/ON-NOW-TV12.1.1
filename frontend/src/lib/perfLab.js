/** Rendering / loading strategies for the TV box.  Defaults are the
 *  setup chosen after A/B testing on the HK1 (2026-10-12); the Settings
 *  lab UI was removed, but every knob stays switchable via setPerf().
 *  Persisted in localStorage; applied as <html> classes for CSS and
 *  read directly by hooks/components for behaviour. */
const KEY = 'vesper-perf-lab-v2';
export const PERF_CHANGE = 'vesper:perf-change';
import * as cache from '@/lib/cache';

export const PERF_OPTIONS = {
    rowLoading: {
        label: 'Row loading',
        hint: 'How the For You page fetches its catalogue rows',
        values: [
            { id: 'progressive', label: 'All rows, paint as they arrive', sub: 'original · 1 re-render per row' },
            { id: 'batched', label: 'All rows, paint in batches', sub: 'one paint per 1.5 s' },
            { id: 'lazy', label: 'Only rows on screen', sub: 'fetch when scrolled near' },
        ],
        default: 'lazy',
    },
    itemsPerRow: {
        label: 'Titles per row',
        hint: 'How many titles each row keeps in memory',
        values: [
            { id: '60', label: '60', sub: 'original' },
            { id: '30', label: '30', sub: 'lighter' },
            { id: '18', label: '18', sub: 'lightest' },
        ],
        default: '18',
    },
    recycle: {
        label: 'Tile recycling',
        hint: 'Only tiles near the focused one are real; the rest are empty cells (RecyclerView style)',
        values: [
            { id: 'off', label: 'Off', sub: 'every tile mounted' },
            { id: 'normal', label: 'Normal', sub: '−6 … +12 around focus' },
            { id: 'tight', label: 'Tight', sub: '−3 … +9 around focus' },
        ],
        default: 'tight',
    },
    layers: {
        label: 'GPU layers',
        hint: 'Which tiles get their own compositor layer',
        values: [
            { id: 'focused', label: 'Focused tile only', sub: '~10 layers' },
            { id: 'all', label: 'Every tile', sub: 'original · ~300 layers' },
        ],
        default: 'focused',
    },
    blur: {
        label: 'Glass blur',
        hint: 'backdrop-filter blur on nav, badges and pills',
        values: [
            { id: 'off', label: 'Off', sub: 'solid translucent' },
            { id: 'on', label: 'On', sub: 'original look' },
        ],
        default: 'off',
    },
    heroMotion: {
        label: 'Hero motion',
        hint: 'Slow Ken Burns zoom on the big artwork',
        values: [
            { id: 'off', label: 'Off', sub: 'static' },
            { id: 'on', label: 'On', sub: 'animated' },
        ],
        default: 'off',
    },
    heroFollow: {
        label: 'Hero follows focus',
        hint: 'The big artwork at the top swaps to whichever cover is focused — a full-width image decode per move',
        values: [
            { id: 'on', label: 'Every move', sub: 'original · 200 ms settle' },
            { id: 'delayed', label: 'After you stop', sub: '700 ms settle' },
            { id: 'off', label: 'Off', sub: 'hero never swaps' },
        ],
        default: 'delayed',
    },
    shelfMotion: {
        label: 'Row slide animation',
        hint: 'Covers glide 180 ms when the row moves (animates every visible tile)',
        values: [
            { id: 'flip', label: 'Glide', sub: 'original' },
            { id: 'instant', label: 'Instant', sub: 'jump, no animation' },
        ],
        default: 'instant',
    },
    posterSize: {
        label: 'Poster image size',
        hint: 'Pixel size of each cover image the box has to download and decode',
        values: [
            { id: 'medium', label: 'Medium', sub: 'original · w342' },
            { id: 'small', label: 'Small', sub: 'w185 · ¼ of the pixels' },
        ],
        default: 'small',
    },
    hud: {
        label: 'Performance HUD',
        hint: 'Key-press → paint latency, long tasks, FPS and active settings in the corner',
        values: [
            { id: 'off', label: 'Hidden', sub: '' },
            { id: 'on', label: 'Shown', sub: 'top-right' },
        ],
        default: 'off',
    },
};

function read() {
    try { return JSON.parse(localStorage.getItem(KEY) || '{}') || {}; } catch { return {}; }
}

export function getPerf(key) {
    const opt = PERF_OPTIONS[key];
    const v = read()[key];
    return opt.values.some((x) => x.id === v) ? v : opt.default;
}

export function getAllPerf() {
    return Object.fromEntries(Object.keys(PERF_OPTIONS).map((k) => [k, getPerf(k)]));
}

/** Row-loading / per-row limits only matter on a cold fetch, so the
 *  persisted shelf cache must go when they change — otherwise the
 *  cached rows short-circuit the strategy and nothing changes. */
function dropShelfCaches() {
    cache.clearPrefix('shelves:');
}

export function setPerf(key, value) {
    const next = { ...read(), [key]: value };
    try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* ignore */ }
    if (key === 'rowLoading' || key === 'itemsPerRow') dropShelfCaches();
    applyPerfClasses();
    window.dispatchEvent(new CustomEvent(PERF_CHANGE, { detail: { key, value } }));
}

export function resetPerf() {
    try { localStorage.removeItem(KEY); } catch { /* ignore */ }
    dropShelfCaches();
    applyPerfClasses();
    window.dispatchEvent(new CustomEvent(PERF_CHANGE, { detail: { key: '*', value: null } }));
}

/** <html class="perf-layers-all perf-blur-on perf-hero-on"> — CSS reads these. */
export function applyPerfClasses() {
    if (typeof document === 'undefined') return;
    const c = document.documentElement.classList;
    c.toggle('perf-layers-all', getPerf('layers') === 'all');
    c.toggle('perf-blur-on', getPerf('blur') === 'on');
    c.toggle('perf-hero-on', getPerf('heroMotion') === 'on');
}

export function perfLabel(key, value = getPerf(key)) {
    return PERF_OPTIONS[key].values.find((v) => v.id === value)?.label || value;
}

applyPerfClasses();
