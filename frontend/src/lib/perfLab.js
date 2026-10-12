/** Performance lab — runtime-switchable rendering / loading strategies
 *  so the TV box can be A/B tested without rebuilding the APK.
 *  Persisted in localStorage; applied as <html> classes for CSS and
 *  read directly by hooks/components for behaviour. */
const KEY = 'vesper-perf-lab-v1';
export const PERF_CHANGE = 'vesper:perf-change';

export const PERF_OPTIONS = {
    rowLoading: {
        label: 'Row loading',
        hint: 'How the For You page fetches its catalogue rows',
        values: [
            { id: 'progressive', label: 'All rows, paint as they arrive', sub: 'original · 1 re-render per row' },
            { id: 'batched', label: 'All rows, paint in batches', sub: 'one paint per 1.5 s' },
            { id: 'lazy', label: 'Only rows on screen', sub: 'fetch when scrolled near' },
        ],
        default: 'batched',
    },
    itemsPerRow: {
        label: 'Titles per row',
        hint: 'How many titles each row keeps in memory',
        values: [
            { id: '60', label: '60', sub: 'original' },
            { id: '30', label: '30', sub: 'lighter' },
            { id: '18', label: '18', sub: 'lightest' },
        ],
        default: '30',
    },
    recycle: {
        label: 'Tile recycling',
        hint: 'Only tiles near the focused one are real; the rest are empty cells (RecyclerView style)',
        values: [
            { id: 'off', label: 'Off', sub: 'every tile mounted' },
            { id: 'normal', label: 'Normal', sub: '−6 … +12 around focus' },
            { id: 'tight', label: 'Tight', sub: '−3 … +9 around focus' },
        ],
        default: 'normal',
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
    hud: {
        label: 'Performance HUD',
        hint: 'Live FPS, DOM size and active settings in the corner',
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

export function setPerf(key, value) {
    const next = { ...read(), [key]: value };
    try { localStorage.setItem(KEY, JSON.stringify(next)); } catch { /* ignore */ }
    applyPerfClasses();
    window.dispatchEvent(new CustomEvent(PERF_CHANGE, { detail: { key, value } }));
}

export function resetPerf() {
    try { localStorage.removeItem(KEY); } catch { /* ignore */ }
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
