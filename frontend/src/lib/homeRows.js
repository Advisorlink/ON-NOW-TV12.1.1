/**
 * Home-screen row arrangement — per-profile order + hidden rows,
 * edited in Settings → Home screen and applied by Home.jsx.
 */
import { readScopedString, writeScopedString } from './profileScope';

const KEY = 'onnowtv-home-rows-v1';

export const HOME_ROWS = [
    { id: 'cw',          label: 'Continue Watching',  hint: 'Pick up where you left off' },
    { id: 'foryou',      label: 'Similar to what you love', hint: 'Picks from your viewing style' },
    { id: 'networks',    label: 'Browse by Network',  hint: 'Netflix, Disney+, Max, Apple TV+…' },
    { id: 'studios',     label: 'Studios',            hint: 'Marvel, DC, Pixar, A24…' },
    { id: 'movie-year',  label: 'New movies',         hint: 'This year\u2019s releases' },
    { id: 'series-year', label: 'New series',         hint: 'This year\u2019s shows' },
    { id: 'movie-top',   label: 'Popular movies',     hint: 'Most watched right now' },
    { id: 'series-top',  label: 'Popular series',     hint: 'Most watched right now' },
    { id: 'upcoming',    label: 'Upcoming movies',    hint: 'Coming in the next 60 days' },
];

const DEFAULT_ORDER = HOME_ROWS.map((r) => r.id);

export function getHomeRowPrefs() {
    try {
        const raw = readScopedString(KEY);
        const p = raw ? JSON.parse(raw) : null;
        const known = new Set(DEFAULT_ORDER);
        const order = Array.isArray(p?.order) ? p.order.filter((id) => known.has(id)) : [];
        DEFAULT_ORDER.forEach((id) => { if (!order.includes(id)) order.push(id); });
        const hidden = Array.isArray(p?.hidden) ? p.hidden.filter((id) => known.has(id)) : [];
        return { order, hidden };
    } catch {
        return { order: [...DEFAULT_ORDER], hidden: [] };
    }
}

export function setHomeRowPrefs(prefs) {
    try {
        writeScopedString(KEY, JSON.stringify({ order: prefs.order, hidden: prefs.hidden }));
        window.dispatchEvent(new Event('vesper:home-rows-change'));
    } catch { /* ignore */ }
}

export function moveHomeRow(id, dir) {
    const p = getHomeRowPrefs();
    const i = p.order.indexOf(id);
    const j = i + dir;
    if (i < 0 || j < 0 || j >= p.order.length) return p;
    [p.order[i], p.order[j]] = [p.order[j], p.order[i]];
    setHomeRowPrefs(p);
    return p;
}

export function toggleHomeRow(id) {
    const p = getHomeRowPrefs();
    p.hidden = p.hidden.includes(id) ? p.hidden.filter((x) => x !== id) : [...p.hidden, id];
    setHomeRowPrefs(p);
    return p;
}

export function resetHomeRows() {
    const p = { order: [...DEFAULT_ORDER], hidden: [] };
    setHomeRowPrefs(p);
    return p;
}

/** Order + filter a list of `{ id, node }` rows by the saved prefs.
 *  Rows with ids not in the registry (dev-unlock addon shelves) keep
 *  their natural position after the known ones. */
export function arrangeHomeRows(rows) {
    const { order, hidden } = getHomeRowPrefs();
    const rank = new Map(order.map((id, i) => [id, i]));
    const hide = new Set(hidden);
    return rows
        .filter((r) => r && !hide.has(r.id))
        .map((r, i) => ({ r, k: rank.has(r.id) ? rank.get(r.id) : 1000 + i }))
        .sort((a, b) => a.k - b.k)
        .map((x) => x.r);
}
