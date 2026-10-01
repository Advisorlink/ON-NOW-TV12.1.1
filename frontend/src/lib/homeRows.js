/**
 * Home-screen row arrangement — per-profile order + hidden rows +
 * user-added custom categories, edited in Settings → Home screen and
 * applied by Home.jsx.
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

/** Genres offered in the "Add a category" picker (label doubles as the query). */
export const CATEGORY_GENRES = [
    'Hallmark', 'Christmas', 'Anime', 'Action', 'Adventure', 'Animation', 'Comedy', 'Crime', 'Documentary', 'Drama', 'Family',
    'Fantasy', 'History', 'Horror', 'Music', 'Mystery', 'Romance', 'Sci-Fi', 'Thriller',
    'War', 'Western', 'Kids', 'Reality', 'Based on a True Story', 'Biography', 'Bollywood',
];

export function getHomeRowPrefs() {
    try {
        const raw = readScopedString(KEY);
        const p = raw ? JSON.parse(raw) : null;
        const custom = Array.isArray(p?.custom)
            ? p.custom.filter((c) => c && typeof c.id === 'string' && c.id.startsWith('custom:') && c.query && c.label)
            : [];
        const known = new Set([...DEFAULT_ORDER, ...custom.map((c) => c.id)]);
        const order = Array.isArray(p?.order) ? p.order.filter((id) => known.has(id)) : [];
        [...DEFAULT_ORDER, ...custom.map((c) => c.id)].forEach((id) => { if (!order.includes(id)) order.push(id); });
        const hidden = Array.isArray(p?.hidden) ? p.hidden.filter((id) => known.has(id)) : [];
        return { order, hidden, custom };
    } catch {
        return { order: [...DEFAULT_ORDER], hidden: [], custom: [] };
    }
}

export function setHomeRowPrefs(prefs) {
    try {
        writeScopedString(KEY, JSON.stringify({ order: prefs.order, hidden: prefs.hidden, custom: prefs.custom || [] }));
        window.dispatchEvent(new Event('vesper:home-rows-change'));
    } catch { /* ignore */ }
}

/** Registry of every row for the given prefs: built-ins + custom categories. */
export function getHomeRows(prefs = getHomeRowPrefs()) {
    return [
        ...HOME_ROWS,
        ...prefs.custom.map((c) => ({ id: c.id, label: c.label, hint: `Your category · ${c.query}`, custom: true, query: c.query })),
    ];
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

export function addCustomHomeRow(label, query) {
    const p = getHomeRowPrefs();
    const slug = query.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    const id = `custom:${slug || Date.now()}`;
    if (p.custom.some((c) => c.id === id)) return p;
    p.custom = [...p.custom, { id, label, query }];
    p.order = [...p.order, id];
    setHomeRowPrefs(p);
    return p;
}

export function removeCustomHomeRow(id) {
    const p = getHomeRowPrefs();
    p.custom = p.custom.filter((c) => c.id !== id);
    p.order = p.order.filter((x) => x !== id);
    p.hidden = p.hidden.filter((x) => x !== id);
    setHomeRowPrefs(p);
    return p;
}

export function resetHomeRows() {
    const p = { order: [...DEFAULT_ORDER], hidden: [], custom: [] };
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
