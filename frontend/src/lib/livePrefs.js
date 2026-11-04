/* Live TV guide preferences — group visibility, parental locks, player defaults. */
import { isKidsActive, getActiveProfile, listProfiles, profileHasPin } from '@/lib/profiles';

const KEY = 'reborn-live-prefs-v1';

const DEFAULTS = {
    hiddenGroups: [],
    lockedGroups: [],
    defaultGroup: 'favourites',
    openFullscreen: false,
};

export function getLivePrefs() {
    try { return { ...DEFAULTS, ...(JSON.parse(localStorage.getItem(KEY) || '{}')) }; } catch { return { ...DEFAULTS }; }
}

export function setLivePrefs(patch) {
    const next = { ...getLivePrefs(), ...patch };
    localStorage.setItem(KEY, JSON.stringify(next));
    window.dispatchEvent(new CustomEvent('reborn:live-prefs'));
    return next;
}

export function toggleInList(key, id) {
    const p = getLivePrefs();
    const set = new Set((p[key] || []).map(String));
    const sid = String(id);
    if (set.has(sid)) set.delete(sid); else set.add(sid);
    return setLivePrefs({ [key]: [...set] });
}

export function isGroupLocked(id) {
    return getLivePrefs().lockedGroups.map(String).includes(String(id));
}

export function visibleGroups(rawCats) {
    const p = getLivePrefs();
    const hidden = new Set(p.hiddenGroups.map(String));
    const locked = new Set(p.lockedGroups.map(String));
    const kids = isKidsActive();
    return (rawCats || []).filter((c) => {
        const id = String(c.category_id);
        if (hidden.has(id)) return false;
        if (kids && locked.has(id)) return false;
        return true;
    });
}

/** PIN that unlocks locked groups: the active profile's PIN, else any adult profile PIN. */
export function lockPinProfile() {
    const active = getActiveProfile();
    if (profileHasPin(active)) return active;
    return listProfiles().find((p) => !p.kids && profileHasPin(p)) || null;
}
