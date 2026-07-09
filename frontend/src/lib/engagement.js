/**
 * Feature engagement / nudge tracker.
 *
 * Watches which key product features a user has tried and surfaces a
 * small "tip" toast suggesting unused ones.  One nudge max per app
 * session; subsequent nudges space out by ≥ 7 days.
 *
 * USER SPEC — tips are PER-PROFILE: every newly started profile gets
 * the full tip cycle again (each viewer learns the app themselves),
 * while the on/off switches in Settings stay GLOBAL so "turned off in
 * settings" silences tips for every profile at once.
 *
 * Storage:
 *   `vesper-engagement-v1`               (GLOBAL)  → masterEnabled,
 *                                                    perFeatureEnabled
 *   `vesper-engagement-v1:<profileId>`   (SCOPED)  → installedAt,
 *                usedFeatures, snoozedUntil, mutedForever, lastNudgeAt
 *
 * All timestamps are ISO strings so the JSON survives roundtripping
 * untouched (vs Date.now() ms which is fine but less debuggable).
 */
import { scoped } from '@/lib/profileScope';

const KEY = 'vesper-engagement-v1';

/**
 * Registry of nudge-able features.  Each entry must have:
 *   • key:         stable id used in storage + Settings toggle id
 *   • name:        short label shown in Settings
 *   • title:       <H1> in the nudge toast
 *   • body:        one-sentence pitch (no jargon, friendly)
 *   • actionLabel: button text that leads the user to the feature
 *   • actionPath:  React Router path "Try it" navigates to
 *   • iconName:    optional lucide icon name for the toast (string)
 *
 * To add a new tracked feature: append an entry here AND call
 * `markFeatureUsed('<key>')` from the code that fires when the user
 * does the action.
 */
export const NUDGE_FEATURES = [
    {
        key: 'my_list',
        name: 'My List',
        // v2.12.14 — User spec: first tip after onboarding should
        // lead with the exact action, not a benefit-phrase.  The
        // operator's own words: "Push and hold on any cover to add
        // to your library".
        title: 'Push and hold on any cover to add it to your Library',
        body: 'Long-press OK on any poster — it lands in your Library so you can find it again with one click.',
        actionLabel: 'Browse',
        actionPath: '/',
        iconName: 'bookmark',
    },
    {
        key: 'actor',
        name: 'Follow actors',
        title: 'Follow your favourite actors',
        body: 'Open any actor on a show page and tap "Follow" — every film & series they appear in lands in your Library.',
        actionLabel: 'Search a show',
        actionPath: '/search',
        iconName: 'user-round',
    },
    {
        key: 'watch_later',
        name: 'Watch Later',
        title: 'Have something to watch tonight?',
        body: 'Push and hold OK on tonight\'s pick and choose Watch Later — it sits at the top of your Library queue, ready to play.',
        actionLabel: 'Open Library',
        actionPath: '/library',
        iconName: 'clock',
    },
    {
        key: 'viewing_style',
        name: 'For You',
        title: 'Want recommendations that actually fit?',
        body: 'Tell us your viewing style in Settings — your Home will personalise around what you genuinely love.',
        actionLabel: 'Set it',
        actionPath: '/settings',
        iconName: 'sparkles',
    },
    {
        key: 'watch_together',
        name: 'Watch Together',
        title: 'Watching with friends?',
        body: 'Host a synced Watch Party — same scene, same time, with reactions. Up to 8 people.',
        actionLabel: 'Try it',
        actionPath: '/watch-together',
        iconName: 'users-round',
    },
];

/* ─── Storage helpers ─────────────────────────────────────────── */

function nowIso() {
    return new Date().toISOString();
}

function readJsonRaw(k) {
    try {
        const raw = localStorage.getItem(k);
        return raw ? JSON.parse(raw) : null;
    } catch {
        return null;
    }
}

function readState() {
    const globalPart = readJsonRaw(KEY) || {};
    let progress = readJsonRaw(scoped(KEY));
    if (!progress) {
        /* First time THIS PROFILE is seen — seed a fresh tip cycle so
           a newly started profile gets the tips again. */
        progress = {
            installedAt: nowIso(),
            usedFeatures: {},
            snoozedUntil: {},
            mutedForever: [],
            lastNudgeAt: null,
        };
        try {
            localStorage.setItem(scoped(KEY), JSON.stringify(progress));
        } catch { /* ignore */ }
    }
    return {
        ...defaultState(),
        masterEnabled: globalPart.masterEnabled !== false,
        perFeatureEnabled: globalPart.perFeatureEnabled || {},
        ...progress,
    };
}

function writeState(state) {
    try {
        localStorage.setItem(KEY, JSON.stringify({
            masterEnabled: state.masterEnabled !== false,
            perFeatureEnabled: state.perFeatureEnabled || {},
        }));
        localStorage.setItem(scoped(KEY), JSON.stringify({
            installedAt: state.installedAt,
            usedFeatures: state.usedFeatures || {},
            snoozedUntil: state.snoozedUntil || {},
            mutedForever: state.mutedForever || [],
            lastNudgeAt: state.lastNudgeAt || null,
        }));
    } catch {
        /* localStorage full / blocked — silently no-op */
    }
}

function defaultState() {
    return {
        installedAt: null,         // set lazily on first read
        usedFeatures: {},
        snoozedUntil: {},
        mutedForever: [],
        lastNudgeAt: null,
        masterEnabled: true,
        perFeatureEnabled: {},
    };
}

/* ─── Public API ──────────────────────────────────────────────── */

/**
 * Call from the feature's action handler.  Idempotent — only the
 * FIRST call (per feature) actually records anything; subsequent
 * calls are no-ops.  Safe to call from inside a tight loop or a
 * React render path; we read+write localStorage but the write is
 * skipped after the first time.
 */
export function markFeatureUsed(key) {
    if (!key || typeof key !== 'string') return;
    const state = readState();
    if (state.usedFeatures[key]) return;   // already marked
    state.usedFeatures[key] = nowIso();
    writeState(state);
}

/**
 * Returns the next nudge to show, or null if none is eligible.
 * Caller checks the global session-flag separately (`hasShownThisSession`).
 */
export function pickNextNudge() {
    const state = readState();
    if (!state.masterEnabled) return null;

    /* USER SPEC — no multi-day grace period any more: a freshly
       started profile should see its first tip right away (one per
       session).  The 7-day spacing below still stops any spam. */

    /* 7-day spacing between subsequent nudges so they never feel
       spammy. */
    if (state.lastNudgeAt) {
        const sinceLastMs = Date.now() - Date.parse(state.lastNudgeAt);
        if (sinceLastMs < 7 * 24 * 60 * 60 * 1000) return null;
    }

    /* Pick the first feature, in declaration order, that's:
        - not in usedFeatures
        - not muted forever
        - not currently snoozed
        - not disabled by the user via Settings           */
    const nowMs = Date.now();
    for (const f of NUDGE_FEATURES) {
        if (state.usedFeatures[f.key]) continue;
        if (state.mutedForever.includes(f.key)) continue;
        const snoozeUntil = state.snoozedUntil[f.key];
        if (snoozeUntil && Date.parse(snoozeUntil) > nowMs) continue;
        if (state.perFeatureEnabled[f.key] === false) continue;
        return f;
    }
    return null;
}

/** Mark the moment a nudge was shown so the 7-day spacing kicks in. */
export function markNudgeShown(_key) {
    const state = readState();
    state.lastNudgeAt = nowIso();
    writeState(state);
}

/** "Not now" — snooze this specific nudge for 7 days. */
export function snoozeNudge(key) {
    if (!key) return;
    const state = readState();
    const until = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();
    state.snoozedUntil[key] = until;
    writeState(state);
}

/** "Don't show again" — mute this nudge forever (without affecting others). */
export function muteNudgeForever(key) {
    if (!key) return;
    const state = readState();
    if (!state.mutedForever.includes(key)) {
        state.mutedForever.push(key);
        writeState(state);
    }
}

/* ─── Settings panel helpers ──────────────────────────────────── */

export function getEngagementState() {
    return readState();
}

export function setMasterEnabled(enabled) {
    const state = readState();
    state.masterEnabled = !!enabled;
    writeState(state);
}

export function setFeatureEnabled(key, enabled) {
    if (!key) return;
    const state = readState();
    state.perFeatureEnabled[key] = !!enabled;
    /* If the user explicitly re-enables a feature in Settings,
       clear any "Don't show again" mute so the nudge can fire next
       time it becomes eligible. */
    if (enabled) {
        state.mutedForever = state.mutedForever.filter((k) => k !== key);
        delete state.snoozedUntil[key];
    }
    writeState(state);
}

export function resetEngagement() {
    /* Wipes THIS profile's tip progress — Settings → "Reset tips".
       The global on/off toggles are preserved. */
    const state = readState();
    writeState({
        ...defaultState(),
        masterEnabled: state.masterEnabled,
        perFeatureEnabled: state.perFeatureEnabled,
        installedAt: nowIso(),
    });
}

/**
 * Manually trigger a nudge for testing.  Bypasses the 3-day grace,
 * the 7-day spacing, the per-feature toggle, and the once-per-session
 * gate.  Does NOT call `markNudgeShown` so the real 7-day cool-down
 * isn't burned by a preview.
 */
export function previewNudge(key) {
    if (!key) return;
    try {
        window.dispatchEvent(new CustomEvent('vesper:nudge-preview', { detail: { key } }));
    } catch {
        /* CustomEvent not supported (unlikely on Chrome WebView 52+) */
    }
}
