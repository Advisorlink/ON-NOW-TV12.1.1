/**
 * Live TV — V2.3 design rebuild.
 *
 *   ── Layout ──
 *     Hero (fixed at top, never scrolls):
 *       • Full-bleed TMDB programme backdrop (debounced, cached)
 *       • Eyebrow · Channel name · NOW time · synopsis · progress bar
 *       • UP NEXT line · big "Watch full-screen" pill
 *       • Top-right utility buttons (★ favourite · ⟳ refresh · ↪ exit)
 *     Body (only this scrolls inside its 3 columns):
 *       • Categories pill list (Favourites pinned, then real cats)
 *       • Channels pill cards (logo + ch# + name + NOW + progress)
 *       • Guide column grouped by TODAY / TOMORROW (reminder rows)
 *
 *   ── Perf ──
 *     Same proven primitives from v2.2.2:
 *       • Hot data in refs, only `sel` state in the hot path
 *       • All row + column components React.memo'd
 *       • Stable rowFn callbacks via useCallback
 *       • Stable EMPTY_ARRAY for "no EPG" case
 *       • Guide column debounced 120 ms — settles, then renders
 *       • TMDB backdrop fires only after settle + cache by title
 *
 *   ── Keypad ──
 *     Initial focus: col 0 (categories), idx 0.
 *     ←/→ moves between columns; ↑/↓ within column; Enter plays a
 *     channel or toggles a reminder; F toggles favourite.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Star, Calendar, Bell, RefreshCw, LogOut, Search } from 'lucide-react';
import SideNav from '@/components/SideNav';
import DPadHint from '@/components/DPadHint';
import XtreamLogin from '@/components/XtreamLogin';
import LiveTVBoot from '@/components/LiveTVBoot';
import useIsMobile from '@/lib/useIsMobile';
import {
    getActiveProvider,
    authenticate,
    getCategories,
    getStreams,
    getStreamUrl,
    getFullEpg,
    getXmltvEpg,
} from '@/lib/xtream';
import {
    getFavorites as getFavList,
    toggleFavorite,
} from '@/lib/liveFavorites';
import { getRecents, pushRecent } from '@/lib/liveRecents';
import {
    getReminders,
    toggleReminder,
    pruneStale,
} from '@/lib/liveReminders';
import {
    loadCategories,
    saveCategories,
    loadChannels,
    saveChannels,
    loadEpg,
    mergeAndSaveEpg,
    waitForHydration,
} from '@/lib/liveCache';
import { bootInstantBundle } from '@/lib/instantBundle';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import useBackHandler from '@/hooks/useBackHandler';
import ConfirmModal from '@/components/ConfirmModal';
import Host from '@/lib/host';

const ROW_H = 32;            // category row height
const CHAN_H = 54;           // channel card height
const GUIDE_ROW_H = 40;      // guide row height
const BUFFER = 4;
const FAV_CAT = '__fav__';
const REC_CAT = '__rec__';
const REM_CAT = '__rem__';
const EMPTY_ARRAY = [];

/* ─────────────────────────── Page shell ─────────────────────────── */

export default function LiveTV() {
    const [provider, setProvider] = useState(() => getActiveProvider());
    const handleLogout = useCallback(() => setProvider(null), []);
    const handleAuthed = useCallback((p) => setProvider(p), []);

    // Remote BACK key → Home.  Mounted at the SHELL level (not inside
    // the auth-gated <Grid/> block) so the user can also press BACK
    // on the LiveTVAuth screen to bail out.
    useBackHandler('/');

    return (
        <div data-testid="livetv-page" style={{
            position: 'fixed',
            inset: 0,
            background: '#0A0F1A',
            color: '#E6EAF2',
            overflow: 'hidden',
        }}>
            <SideNav />
            <main style={{
                position: 'absolute',
                inset: '0 0 0 100px',
                display: 'flex',
                flexDirection: 'column',
                overflow: 'hidden',
            }}>
                {provider
                    ? <Grid provider={provider} onLogout={handleLogout} />
                    : <XtreamLogin onAuthed={handleAuthed} />}
            </main>
            {provider && (
                <DPadHint
                    storageKey="livetv"
                    items={[
                        { keys: '←', label: 'BACK' },
                        { keys: '↑↓←→', label: 'NAVIGATE' },
                        { keys: 'OK', label: 'WATCH' },
                        { keys: 'HOLD OK', label: 'FAVOURITE' },
                    ]}
                />
            )}
        </div>
    );
}

/* ─────────────────────────────── Grid ─────────────────────────────── */

function Grid({ provider, onLogout }) {
    const navigate = useNavigate();

    /* Hot data — never re-rendered. */
    const cats = useRef([]);
    const channelsByCat = useRef(new Map());
    const epg = useRef(new Map());

    /* Synchronous hydrate. */
    if (cats.current.length === 0) {
        const c = loadCategories(provider.id) || [];
        const ch = loadChannels(provider.id) || {};
        const e = loadEpg(provider.id) || {};
        cats.current = c;
        for (const k in ch) channelsByCat.current.set(k, ch[k]);
        const nowSec = Math.floor(Date.now() / 1000);
        for (const sid in e) {
            const arr = (e[sid] || []).filter((it) => Number(it.stopTimestamp || 0) > nowSec);
            if (arr.length) epg.current.set(Number(sid) || sid, arr);
        }
    }

    /* Selection — initial focus on TOP CATEGORY. */
    const [sel, setSel] = useState(() => ({
        col: 0,                 // Categories column
        catIdx: 0,              // Top of the list (Favourites or first cat)
        chanIdx: 0,
        guideIdx: 0,
    }));

    const [query, setQuery] = useState('');
    const [syncing, setSyncing] = useState(false);
    /* Boot progress: drives the full-screen <LiveTVBoot/> splash that
     * blocks Live TV until the cache is ≥ EPG_BOOT_TARGET full.  Each
     * stage is one of pending | active | done | failed.  Stages:
     *   auth         → authenticating with the Xtream server
     *   categories   → category list fetched
     *   channels     → channels for every category fetched
     *   epg          → EPG (now/next + 12h) for >= 50 % of channels
     * Once the `epg` stage is `done`, the splash dismisses and the
     * grid mounts.  EPG keeps loading in the background for the rest.
     */
    const [bootStages, setBootStages] = useState(() => ([
        { id: 'auth',       label: 'Connecting to your provider', status: 'pending', detail: '' },
        { id: 'categories', label: 'Loading categories',          status: 'pending', detail: '' },
        { id: 'channels',   label: 'Loading channels',            status: 'pending', detail: '' },
        { id: 'epg',        label: 'Loading TV guide (NOW & NEXT)',status: 'pending', detail: '' },
    ]));
    /* Counters drive the big animated numbers in <LiveTVBoot/>. */
    const [bootCounters, setBootCounters] = useState({
        categoriesDone: 0,
        categoriesTotal: 0,
        channelsCount: 0,
        epgDone: 0,
        epgTotal: 0,
    });
    const setStage = useCallback((id, status, detail) => {
        setBootStages((prev) => prev.map((s) =>
            s.id === id ? { ...s, status, detail: detail ?? s.detail } : s
        ));
    }, []);
    /* `bootBlocked` = should we show the splash?  Stays true until
     * the `epg` stage flips to `done` (≥ 50 % EPG cached). */
    const [bootBlocked, setBootBlocked] = useState(() => {
        // If we already have categories + channels + some EPG cached
        // from a previous session, skip the splash entirely.
        const cached = cats.current.length > 0
                       && channelsByCat.current.size > 0
                       && epg.current.size > 0;
        return !cached;
    });
    /* Pending confirmation dialog state.  Shape: { kind, title, body, onConfirm }.
     * Set by long-press OK on items that would *remove* a saved
     * entry (favourite, reminder).  Cleared on cancel or confirm. */
    const [pendingConfirm, setPendingConfirm] = useState(null);

    /* `bump` — cheap re-render counter incremented whenever the
     * background EPG prefetch fills `epg.current` or a long-press
     * mutates the favourites / reminders refs.  Lives above the
     * `channels` / `allChannels` useMemos because BOTH read it in
     * their dependency arrays (TDZ would otherwise fire on first
     * render — caught by the error boundary as "Cannot access
     * 'bump' before initialization"). */
    const [bump, setBump] = useState(0);
    const rerender = useCallback(() => setBump((b) => b + 1), []);

    const [favs, setFavs] = useState(() => new Set(getFavList(provider.id).map(String)));
    const [recents, setRecents] = useState(() => getRecents(provider.id).map(String));
    const [reminders, setReminders] = useState(() => pruneStale(provider.id));
    const reminderKeys = useMemo(
        () => new Set(reminders.map((r) => r.id)),
        [reminders],
    );

    /* Unique stream IDs that have at least one reminder set — used
     * both as a sidebar entry count and to resolve the channel
     * list when the user selects the Reminders pseudo-category. */
    const reminderStreamIds = useMemo(() => {
        const seen = new Set();
        for (const r of reminders) seen.add(String(r.streamId));
        return seen;
    }, [reminders]);

    const sidebarCats = useMemo(
        () => buildSidebarCats(cats.current, favs.size, recents.length, reminderStreamIds.size, channelsByCat.current),
        [favs, recents, reminderStreamIds, cats.current.length, channelsByCat.current.size],
    );

    const allChannels = useMemo(() => {
        const cat = sidebarCats[sel.catIdx];
        if (!cat) return EMPTY_ARRAY;
        if (cat.id === FAV_CAT) return resolveByIds(favs, channelsByCat.current);
        if (cat.id === REC_CAT) return resolveByIds(new Set(recents), channelsByCat.current, recents);
        if (cat.id === REM_CAT) return resolveByIds(reminderStreamIds, channelsByCat.current);
        return channelsByCat.current.get(cat.id) || EMPTY_ARRAY;
    }, [sel.catIdx, favs, recents, reminderStreamIds, sidebarCats, channelsByCat.current.size]);

    const channels = useMemo(() => {
        const q = query.trim().toLowerCase();
        if (!q) return allChannels;
        const isNum = /^\d+$/.test(q);

        // Tier 1 — channels matching by name / number (always shown first).
        const nameMatches = allChannels.filter((c) => {
            const n = (c.name || '').toLowerCase();
            return n.includes(q) || (isNum && c.num != null && String(c.num).includes(q));
        });

        // Tier 2 — channels whose EPG has a programme title or
        // description containing the query.  Decorate each match
        // with `_matchedProgramme` so the card can surface what
        // matched (e.g. "Toronto Raptors vs Lakers" instead of the
        // currently airing show).  Avoids duplicates from Tier 1.
        const seen = new Set(nameMatches.map((c) => String(c.stream_id)));
        const epgMatches = [];
        if (q.length >= 3 && !isNum) {
            for (const arr of channelsByCat.current.values()) {
                for (const c of (arr || [])) {
                    const key = String(c.stream_id);
                    if (seen.has(key)) continue;
                    const items = epg.current.get(c.stream_id) || [];
                    const hit = items.find((it) => {
                        const t = (it.title || '').toLowerCase();
                        const d = (it.description || '').toLowerCase();
                        return t.includes(q) || d.includes(q);
                    });
                    if (hit) {
                        epgMatches.push({ ...c, _matchedProgramme: hit });
                        seen.add(key);
                    }
                }
            }
        }
        return [...nameMatches, ...epgMatches];
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [allChannels, query, bump]);

    const focusedChannel = channels[Math.min(sel.chanIdx, channels.length - 1)] || null;

    /* Debounced guide channel (120 ms after focus settles).  Skips
     * re-renders of the right column during fast scrubbing. */
    const [debouncedChannel, setDebouncedChannel] = useState(focusedChannel);
    useEffect(() => {
        const t = setTimeout(() => setDebouncedChannel(focusedChannel), 120);
        return () => clearTimeout(t);
    }, [focusedChannel]);

    /* On-demand EPG fetch — fires 200 ms after the user settles on
     * a channel that has no cached EPG.  Lands in epg.current
     * (same map the prefetch fills), so a subsequent focus on the
     * same channel is instant.  Cancelled if the user moves on.
     *
     * Empty results are ALSO cached (as []) so channels the
     * provider doesn't ship EPG for don't re-fetch every time the
     * user lands on them — that was the 1-2 second "wait" the user
     * was seeing on channels like USA Entertainment.  Of 14,220
     * channels in the managed catalogue, only ~3,100 have any EPG
     * data at all (the provider just doesn't index the rest).
     */
    const epgReqId = useRef(0);
    useEffect(() => {
        const ch = debouncedChannel;
        if (!ch) return undefined;
        if (epg.current.has(ch.stream_id)) return undefined;
        const myReq = ++epgReqId.current;
        const t = setTimeout(async () => {
            try {
                const items = await getFullEpg(provider, ch.stream_id, 200);
                if (epgReqId.current !== myReq) return;
                const arr = (items && items.length) ? items : [];
                /* Always cache so future focuses skip the network. */
                epg.current.set(ch.stream_id, arr);
                if (arr.length) {
                    mergeAndSaveEpg(provider.id, { [ch.stream_id]: arr });
                }
                rerender();
            } catch {
                /* Network error — cache empty so we don't hammer. */
                if (epgReqId.current === myReq) {
                    epg.current.set(ch.stream_id, []);
                    rerender();
                }
            }
        }, 200);
        return () => clearTimeout(t);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [debouncedChannel, provider]);

    /* Visible-channels EPG prefetch — fills in EPG for the first
     * ~20 channels of the active category in PARALLEL so progress
     * bars + "NOW" lines appear across all of them within a couple
     * of seconds, not one-at-a-time.  Re-runs whenever the user
     * lands on a new category.  Batches re-renders every 4
     * completions so the UI fills in incrementally (you see the
     * top cards light up first, then the rest as they arrive). */
    useEffect(() => {
        const sample = channels.slice(0, 20);
        const missing = sample.filter((c) => !epg.current.has(c.stream_id));
        if (missing.length === 0) return undefined;
        let cancel = false;
        (async () => {
            const CONC = 6;
            let cursor = 0;
            let sinceLastFlush = 0;
            const worker = async () => {
                while (!cancel) {
                    const i = cursor++;
                    if (i >= missing.length) return;
                    const ch = missing[i];
                    try {
                        const items = await getFullEpg(provider, ch.stream_id, 200);
                        if (cancel) return;
                        if (items && items.length) {
                            epg.current.set(ch.stream_id, items);
                            mergeAndSaveEpg(provider.id, { [ch.stream_id]: items });
                            sinceLastFlush += 1;
                            // Incremental UI update — every 4 hits
                            // we re-render so cards light up as
                            // they arrive instead of waiting for
                            // the whole batch.
                            if (sinceLastFlush >= 4) {
                                sinceLastFlush = 0;
                                if (!cancel) rerender();
                            }
                        }
                    } catch { /* swallow */ }
                }
            };
            const workers = [];
            for (let i = 0; i < CONC; i++) workers.push(worker());
            await Promise.all(workers);
            if (!cancel && sinceLastFlush > 0) rerender();
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sel.catIdx, channels.length]);

    const guideItems = debouncedChannel
        ? (epg.current.get(debouncedChannel.stream_id) || EMPTY_ARRAY)
        : EMPTY_ARRAY;

    /* Group guide entries by day for the TODAY / TOMORROW headers. */
    const guideGroups = useMemo(() => groupByDay(guideItems), [guideItems]);
    /* Blue label above the GUIDE header — shows today's date in
     * the format the user asked for ("TODAY · WED 15 MAY"). */
    const guideTodayLabel = useMemo(() => {
        const d = new Date();
        return `TODAY · ${formatDayLabel(d)}`;
    }, []);

    /* ───────── Background sync ───────── */

    /**
     * Push the current Live TV state (categories + channels + EPG +
     * provider info) into the native player's SharedPreferences so
     * the in-player **Live Guide overlay** has everything it needs
     * to render channel switching while a stream is playing.
     *
     * Called whenever channels or EPG change in a meaningful way.
     * Safe to call repeatedly — the native side just overwrites.
     * No-op outside the Android WebView (the bridge method is gated
     * on `window.OnNowTV?.setLiveGuide`).
     */
    const pushLiveGuideToNative = useCallback(() => {
        const bridge = typeof window !== 'undefined' ? window.OnNowTV : null;
        if (!bridge || typeof bridge.setLiveGuide !== 'function') return;
        try {
            /* Build categories payload — keep it light, the native
             * side only needs id, name, and a quick channel count. */
            const categoriesPayload = (cats.current || []).map((c) => ({
                id: String(c.category_id),
                name: String(c.category_name || ''),
                count: (channelsByCat.current.get(c.category_id) || []).length,
            }));

            /* Build channels payload — one entry per channel, with
             * just the fields the overlay renders.  Stream URL is
             * pre-built here (instead of re-fetched in Kotlin) so
             * channel switching is instant. */
            const channelsPayload = [];
            for (const [catId, arr] of channelsByCat.current.entries()) {
                for (const c of (arr || [])) {
                    /* The Xtream stream URL pattern is stable; we
                     * build it here so the native side never needs
                     * to make another /stream-url call. */
                    const scheme = provider.scheme || 'http';
                    const portPart = provider.port && provider.port !== '80' && provider.port !== '443'
                        ? `:${provider.port}` : '';
                    const streamUrl =
                        `${scheme}://${provider.host}${portPart}/live/` +
                        `${encodeURIComponent(provider.username)}/` +
                        `${encodeURIComponent(provider.password)}/` +
                        `${c.stream_id}.ts`;
                    channelsPayload.push({
                        stream_id: String(c.stream_id),
                        name: String(c.name || ''),
                        logo: String(c.stream_icon || ''),
                        category_id: String(catId),
                        epg_channel_id: String(c.epg_channel_id || ''),
                        stream_url: streamUrl,
                    });
                }
            }

            /* v2.7.78 — EPG payload sizing depends on which native
             * bridge is available.  Detect FIRST, then build the
             * appropriate-size payload — never blindly push 40 MB
             * of JSON into SharedPreferences (silent truncation +
             * corruption risk on older APKs that haven't been
             * upgraded yet).
             *
             *   • NEW APK (`setLiveGuideEpg` present): full 72-hour
             *     EPG, every programme field, written to a file via
             *     the dedicated bridge.  No size limit.
             *   • OLD APK / mobile browser: legacy 6-hour, 4-per-
             *     channel trim that fits inside the SharedPreferences
             *     ceiling.  Worse than the new path but still better
             *     than crashing.
             */
            const hasFileBridge = typeof bridge.setLiveGuideEpg === 'function';
            const epgPayload = {};
            const nowSec = Math.floor(Date.now() / 1000);
            const legacyHorizonSec = nowSec + 6 * 3600;
            for (const [sid, list] of epg.current.entries()) {
                if (!Array.isArray(list) || list.length === 0) continue;
                const kept = [];
                for (const it of list) {
                    if ((it.stopTimestamp || 0) < nowSec) continue;
                    if (!hasFileBridge) {
                        // Legacy path — keep the original safety
                        // bounds so SharedPreferences never gets
                        // overflowed.
                        if ((it.startTimestamp || 0) > legacyHorizonSec) break;
                    }
                    kept.push({
                        title: it.title || '',
                        desc: it.desc || it.description || '',
                        season: it.season || '',
                        episode: it.episode || '',
                        episodeTitle: it.episodeTitle || it.sub_title || '',
                        year: it.year || '',
                        rating: it.rating || '',
                        category: it.category || '',
                        startTimestamp: it.startTimestamp || 0,
                        stopTimestamp: it.stopTimestamp || 0,
                    });
                    if (!hasFileBridge && kept.length >= 4) break;
                }
                if (kept.length > 0) {
                    epgPayload[String(sid)] = kept;
                }
            }

            /* Favourites array (stream IDs starred by the user) so
             * the native overlay can show a "★ Favourites" pill. */
            const favs = (getFavList(provider.id) || []).map((s) => String(s));

            /* Push the EPG via the appropriate bridge.  Order
             * matters: write the EPG file FIRST so a fast client
             * D-padding into a channel right when the splash
             * dismisses is guaranteed to find a fully flushed EPG
             * on disk.  `setLiveGuideEpg` is synchronous on the new
             * APK — the JS thread blocks until the atomic rename
             * has completed. */
            const epgJson = JSON.stringify(epgPayload);
            const epgChannelsCount = Object.keys(epgPayload).length;
            if (hasFileBridge) {
                let epgWriteOk = false;
                try {
                    const ack = bridge.setLiveGuideEpg(epgJson);
                    if (typeof ack === 'string') {
                        try {
                            const parsed = JSON.parse(ack);
                            epgWriteOk = !!parsed.ok;
                            // eslint-disable-next-line no-console
                            console.info('[liveGuide] EPG file write:', parsed);
                        } catch {
                            epgWriteOk = true;
                        }
                    } else {
                        epgWriteOk = true;
                    }
                } catch { /* ignore */ }
                /* Only blank the legacy SharedPreferences "epg" key
                 * when the file write actually succeeded; otherwise
                 * keep whatever was in there as a last-resort
                 * fallback. */
                bridge.setLiveGuide(
                    String(provider.id || ''),
                    JSON.stringify(categoriesPayload),
                    JSON.stringify(channelsPayload),
                    epgWriteOk ? '{}' : epgJson,
                    JSON.stringify(favs),
                );
            } else {
                /* Legacy path — single bridge call with inline EPG.
                 * The 4-per-channel / 6-hour trim above keeps the
                 * payload well under the SharedPreferences ceiling. */
                bridge.setLiveGuide(
                    String(provider.id || ''),
                    JSON.stringify(categoriesPayload),
                    JSON.stringify(channelsPayload),
                    epgJson,
                    JSON.stringify(favs),
                );
            }
            // eslint-disable-next-line no-console
            console.info(
                '[liveGuide] pushed to native:',
                channelsPayload.length, 'channels,',
                epgChannelsCount, 'with EPG',
                hasFileBridge ? '(file bridge, full 72h)' : '(legacy bridge, 6h × 4 per channel)',
            );
        } catch (e) {
            /* Bridge errors are silent — the overlay will just be
             * empty / stale until the next push. */
            // eslint-disable-next-line no-console
            console.debug('pushLiveGuideToNative failed:', e);
        }
    }, [provider]);

    useEffect(() => {
        let cancel = false;
        /* The legacy per-channel EPG fetch was used when no
         * server-side bundle existed.  Now that the backend
         * pre-warms a 2,338-channel / 72-hour EPG bundle and
         * `instantBundle.js` seeds it into our cache on app boot,
         * the splash is dismissed the moment the bundle lands —
         * we no longer wait for an EPG threshold. */
        (async () => {
            setSyncing(true);
            try {
                /* v2.7.77 — Await the IndexedDB hydration first.
                 *
                 * Without this, we'd see memCache empty and re-fetch
                 * the 6 MB bundle from the backend on every cold
                 * boot — exactly the 30–40 s delay the user
                 * complained about.  IDB hydration typically
                 * resolves in <100 ms once the OS file system is
                 * warm. */
                try { await waitForHydration(); } catch { /* ignore */ }
                if (cancel) return;

                /* INSTANT BUNDLE — short-circuit the entire boot
                 * flow when the backend has a pre-warmed snapshot.
                 * If our local cache is empty, try fetching the
                 * bundle once (10 s budget) before falling back to
                 * per-channel Xtream calls.  When the bundle lands,
                 * it writes through to localStorage; we then re-
                 * read into the in-memory refs and dismiss the
                 * boot splash immediately. */
                // v2.7.77 — Re-read cache AFTER hydration completes.
                const memCats   = loadCategories(provider.id) || [];
                const memChans  = loadChannels(provider.id) || {};
                const memEpgMap = loadEpg(provider.id) || {};
                const cacheEmpty =
                    memCats.length === 0
                    || Object.keys(memChans).length === 0
                    || Object.keys(memEpgMap).length === 0;
                if (cacheEmpty) {
                    try {
                        /* v2.7.80 — Enforce a minimum splash time so
                         * even on fast connections (where the
                         * bundle comes down in <500 ms) the user
                         * actually sees the loading screen.
                         * Otherwise the splash dismisses
                         * imperceptibly and the user thinks
                         * nothing happened. */
                        const minSplashMs = 1500;
                        const splashStartedAt = Date.now();
                        /* v2.7.78 — First-time launch budget bumped
                         * to 90 s.  The user explicitly asked for a
                         * "loading page that could take up to a
                         * minute" so the EPG is FULLY cached before
                         * the grid renders.  Once cached, every
                         * subsequent launch is instant (no fetch,
                         * just hydrate from IndexedDB).
                         *
                         * Warm-cache launches use the much shorter
                         * 8 s budget via `bootInstantBundle()`'s
                         * own /meta fast-path; this only applies on
                         * cold first launches. */
                        const applied = await Promise.race([
                            bootInstantBundle(),
                            new Promise((r) => setTimeout(() => r(false), 90000)),
                        ]);
                        if (cancel) return;
                        if (applied) {
                            /* Re-seed the in-memory refs from
                             * the just-populated localStorage. */
                            const c  = loadCategories(provider.id) || [];
                            const ch = loadChannels(provider.id) || {};
                            const e  = loadEpg(provider.id) || {};
                            cats.current = c;
                            channelsByCat.current.clear();
                            for (const k in ch) channelsByCat.current.set(k, ch[k]);
                            epg.current.clear();
                            for (const sid in e) {
                                const arr = e[sid];
                                if (Array.isArray(arr) && arr.length) {
                                    epg.current.set(Number(sid) || sid, arr);
                                }
                            }
                            setStage('auth',       'done', 'Instant bundle');
                            setStage('categories', 'done', `${c.length} categories`);
                            const totalChans = Object.values(ch).reduce(
                                (n, arr) => n + (Array.isArray(arr) ? arr.length : 0), 0,
                            );
                            setStage('channels',   'done', `${totalChans} channels`);
                            setStage('epg',        'done', `${epg.current.size} channels with EPG`);
                            setBootCounters({
                                categoriesDone:  c.length,
                                categoriesTotal: c.length,
                                channelsCount:   totalChans,
                                epgDone:         epg.current.size,
                                epgTotal:        epg.current.size,
                            });
                            /* Hand off to the native overlay FIRST
                             * so the box's player has the EPG ready
                             * BEFORE the user can D-pad into a
                             * channel.  Only then dismiss the
                             * splash. */
                            setStage('epg', 'active', 'Caching guide on device…');
                            try { pushLiveGuideToNative(); } catch { /* ignore */ }
                            setStage('epg', 'done',
                                `${epg.current.size} channels cached`);
                            /* Hold the splash for the remainder of
                             * the minimum-visible time so the user
                             * can actually see "First-time setup —
                             * caching your full TV guide" instead
                             * of a 200 ms flash. */
                            const elapsed = Date.now() - splashStartedAt;
                            const remaining = minSplashMs - elapsed;
                            if (remaining > 0) {
                                await new Promise((r) => setTimeout(r, remaining));
                            }
                            setBootBlocked(false);
                            rerender();
                            return; // we're done — skip the legacy flow
                        }
                    } catch (exc) {
                        // bundle path failed; fall through to legacy
                        // eslint-disable-next-line no-console
                        console.debug('instant_bundle fallback:', exc);
                    }
                }

                /* AUTH (legacy fallback path — only runs if the
                 * bundle was unreachable or empty). */
                setStage('auth', 'active', '');
                await authenticate(provider);
                if (cancel) return;
                setStage('auth', 'done', '');

                /* CATEGORIES */
                setStage('categories', 'active', '');
                const list = await getCategories(provider, 'live');
                if (cancel) return;
                if (Array.isArray(list) && list.length) {
                    cats.current = list;
                    saveCategories(provider.id, list);
                    rerender();
                    setBootCounters((c) => ({ ...c, categoriesTotal: list.length }));
                    setStage('categories', 'done', `${list.length} categories`);
                } else {
                    setStage('categories', 'failed', 'No categories returned');
                }

                /* CHANNELS */
                setStage('channels', 'active', '');
                const fetched = {};
                const BATCH = 4;
                let chCount = 0;
                let catsDone = 0;
                for (let i = 0; i < list.length; i += BATCH) {
                    if (cancel) return;
                    const slice = list.slice(i, i + BATCH);
                    await Promise.all(slice.map(async (cat) => {
                        try {
                            const ch = await getStreams(provider, 'live', cat.category_id);
                            const arr = Array.isArray(ch) ? ch : [];
                            channelsByCat.current.set(cat.category_id, arr);
                            fetched[cat.category_id] = arr;
                            chCount += arr.length;
                        } catch { /* keep stale */ }
                    }));
                    catsDone = Math.min(i + BATCH, list.length);
                    setBootCounters((c) => ({ ...c, categoriesDone: catsDone, channelsCount: chCount }));
                    setStage('channels', 'active',
                        `${catsDone}/${list.length} categories · ${chCount} channels`);
                }
                if (cancel) return;
                if (Object.keys(fetched).length) saveChannels(provider.id, fetched);
                rerender();
                setStage('channels', 'done', `${chCount} channels`);

                /* Push channel list + categories to the native player
                 * so the in-player Live Guide overlay can render. */
                pushLiveGuideToNative();

                /* EPG */
                setStage('epg', 'active', '');
                const sids = [];
                const seen = new Set();
                /* Also build a stream_id → epg_channel_id map so we
                 * can apply the XMLTV index back to the right cache
                 * key (epg.current is keyed by stream_id). */
                const sidToEpgId = new Map();
                for (const arr of channelsByCat.current.values()) {
                    for (const c of (arr || [])) {
                        const k = String(c.stream_id);
                        if (!seen.has(k)) { seen.add(k); sids.push(c.stream_id); }
                        const eid = (c.epg_channel_id || '').trim();
                        if (eid) sidToEpgId.set(String(c.stream_id), eid);
                    }
                }
                /* Pre-fill: count channels we already have EPG for. */
                let epgDone = sids.filter((sid) => epg.current.has(sid)).length;
                const epgTotal = sids.length || 1;
                setBootCounters((c) => ({ ...c, epgDone, epgTotal }));
                setStage('epg', 'active', `${epgDone}/${epgTotal} channels`);

                /* Dismiss the splash immediately as soon as we get
                 * here — we already have channels + categories, so
                 * the grid is paintable.  EPG keeps filling in the
                 * background. */
                setBootBlocked(false);

                /* FAST PATH — try the full XMLTV download first.  A
                 * single ~3-5 MB gzipped request returns the entire
                 * EPG for all 14 000 channels in one shot.  If it
                 * succeeds we apply it, save the cache, dismiss the
                 * splash, and skip the per-channel loop entirely.
                 *
                 * Hard budget of 30 s — if the fetch + parse hasn't
                 * resolved by then we fall back to the per-channel
                 * loop.  Prevents the boot screen from hanging when
                 * the IPTV server is unreachable from this device
                 * (e.g., when testing in preview mode where the
                 * Emergent pod is firewalled from the user's
                 * provider). */
                let xmltvOK = false;
                try {
                    setStage('epg', 'active', 'Downloading XMLTV…');
                    const xml = await Promise.race([
                        getXmltvEpg(provider, {
                            directTimeoutMs: 15000,
                            proxyTimeoutMs: 20000,
                        }),
                        new Promise((_, reject) =>
                            setTimeout(() => reject(new Error('xmltv overall budget exceeded')), 30000)
                        ),
                    ]);
                    if (cancel) return;
                    if (xml && xml.error) {
                        /* eslint-disable-next-line no-console */
                        console.debug('XMLTV fast-path returned error:', xml.error);
                    }
                    if (xml && xml.epg && Object.keys(xml.epg).length > 0) {
                        /* Map XMLTV channel IDs onto our stream_ids.
                         * The IPTV-server normally sets
                         * channel.epg_channel_id to the XMLTV id. */
                        const mergedBuffer = {};
                        let merged = 0;
                        for (const [sid, eid] of sidToEpgId.entries()) {
                            const items = xml.epg[eid];
                            if (items && items.length) {
                                /* Drop programmes that ended more
                                 * than 6 h ago — keeps cache lean. */
                                const nowSec = Math.floor(Date.now() / 1000);
                                const filtered = items.filter(
                                    (it) => it.stopTimestamp > nowSec - 6 * 3600,
                                );
                                if (filtered.length) {
                                    epg.current.set(sid, filtered);
                                    mergedBuffer[sid] = filtered;
                                    merged += 1;
                                }
                            }
                        }
                        if (merged > 0) {
                            mergeAndSaveEpg(provider.id, mergedBuffer);
                            epgDone = merged;
                            setBootCounters((c) => ({ ...c, epgDone }));
                            /* Push freshly-merged EPG to the native
                             * player so the in-player Live Guide
                             * overlay can show Now/Next for every
                             * channel. */
                            pushLiveGuideToNative();
                            /* Surface where the EPG came from so the
                               user can see when the new server-side
                               cache kicks in (~600 KB gzipped vs the
                               3–10 MB direct fetch — huge win on the
                               HK1 box).  cacheAgeSec is the seconds
                               since the server last refreshed the
                               persisted copy. */
                            const srcLabel = xml.source === 'backend-cached'
                                ? `cached on server (${Math.round((xml.cacheAgeSec || 0) / 60)} min old)`
                                : xml.source === 'backend-live'
                                ? 'live via backend'
                                : 'direct from provider';
                            setStage('epg', 'done',
                                `${merged}/${epgTotal} channels · ${srcLabel}`);
                            xmltvOK = true;
                            setBootBlocked(false);
                            /* Rerender so the boot screen flips off and
                             * channel cards pick up the new EPG immediately. */
                            rerender();
                        }
                    }
                } catch (xmltvErr) {
                    /* eslint-disable-next-line no-console */
                    console.debug('XMLTV fast-path failed; will use per-channel loop.', xmltvErr);
                    setStage('epg', 'active',
                        `XMLTV failed (${xmltvErr?.message?.slice(0, 60) || 'error'}), using fallback…`);
                }

                if (xmltvOK) {
                    /* XMLTV succeeded — no per-channel loop needed.
                     * The cache is now complete for every channel
                     * whose epg_channel_id was in the XMLTV index. */
                } else {

                /* No HARD_CAP — keep loading EPG for ALL channels.
                 * The user explicitly asked: "as long as we need to". */
                const buffer = {};
                let bufferDirty = 0;
                let cursor = 0;
                const flush = () => {
                    if (bufferDirty === 0) return;
                    mergeAndSaveEpg(provider.id, buffer);
                    for (const k in buffer) delete buffer[k];
                    bufferDirty = 0;
                };
                const worker = async () => {
                    while (!cancel) {
                        const i = cursor++;
                        if (i >= sids.length) return;
                        const sid = sids[i];
                        try {
                            const items = await getFullEpg(provider, sid, 200);
                            if (cancel) return;
                            if (items && items.length) {
                                epg.current.set(sid, items);
                                buffer[sid] = items;
                                bufferDirty += 1;
                                if (bufferDirty >= 25) flush();
                            }
                        } catch { /* swallow */ }
                        epgDone += 1;
                        const frac = epgDone / epgTotal;
                        setBootCounters((c) => ({ ...c, epgDone }));
                        setStage('epg', frac >= 1 ? 'done' : 'active',
                            `${epgDone}/${epgTotal} channels`);
                    }
                };
                const workers = [];
                for (let i = 0; i < 6; i++) workers.push(worker());
                await Promise.all(workers);
                if (!cancel) {
                    flush();
                    setStage('epg', 'done', `${epgDone}/${epgTotal} channels`);
                    setBootBlocked(false);
                }

                } /* end of !xmltvOK branch */
            } finally {
                if (!cancel) setSyncing(false);
            }
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [provider]);

    /* ───────── Handlers ───────── */
    const doToggleFav = useCallback(() => {
        if (!focusedChannel) return;
        toggleFavorite(provider.id, focusedChannel.stream_id);
        setFavs(new Set(getFavList(provider.id).map(String)));
    }, [focusedChannel, provider]);

    /* Wrapper used by long-press and the F key.  If the channel is
     * ALREADY favourited, prompts for confirmation before removing.
     * Adding a new favourite still happens silently. */
    const onToggleFav = useCallback(() => {
        if (!focusedChannel) return;
        const isFav = favs.has(String(focusedChannel.stream_id));
        if (!isFav) {
            doToggleFav();
            return;
        }
        setPendingConfirm({
            kind: 'unfavourite',
            title: 'Remove from favourites?',
            body: `${focusedChannel.name} will be removed from your Favourites list.`,
            onConfirm: () => {
                doToggleFav();
                setPendingConfirm(null);
            },
        });
    }, [focusedChannel, favs, doToggleFav]);

    const doToggleReminder = useCallback((item, ch) => {
        const channel = ch || debouncedChannel;
        if (!channel || !item?.startTimestamp) return;
        toggleReminder(provider.id, channel.stream_id, {
            channelName: channel.name,
            title: item.title,
            startTs: item.startTimestamp,
            stopTs: item.stopTimestamp,
        });
        setReminders(getReminders(provider.id));
    }, [debouncedChannel, provider]);

    /* Wrapper — confirms before removing an existing reminder. */
    const onToggleReminder = useCallback((item) => {
        if (!debouncedChannel || !item?.startTimestamp) return;
        const sid = debouncedChannel.stream_id;
        const id = `${Number(sid) || sid}:${Number(item.startTimestamp) || item.startTimestamp}`;
        const isOn = reminderKeys.has(id);
        if (!isOn) {
            doToggleReminder(item);
            return;
        }
        setPendingConfirm({
            kind: 'unremind',
            title: 'Remove this reminder?',
            body: `${item.title || 'Untitled programme'} on ${debouncedChannel.name} will no longer trigger a notification.`,
            onConfirm: () => {
                doToggleReminder(item);
                setPendingConfirm(null);
            },
        });
    }, [debouncedChannel, reminderKeys, doToggleReminder]);

    const playChannel = useCallback(async (ch) => {
        if (!ch) return;
        const url = await getStreamUrl(provider, 'live', ch.stream_id, 'ts');
        if (!url) return;
        try {
            pushRecent(provider.id, ch.stream_id);
            setRecents(getRecents(provider.id).map(String));
        } catch { /* ignore */ }
        if (Host.playVideo({
            url, title: ch.name, type: 'live',
            cwId: `live:${provider.id}:${ch.stream_id}`,
        })) return;
        navigate(`/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(ch.name)}&type=live`);
    }, [provider, navigate]);

    const onRefresh = useCallback(() => {
        // Cheapest meaningful "refresh" — clear in-memory + persistent
        // EPG, then trigger a sync by re-running the effect.  Refs
        // are reset so the next render reads from disk again.
        try {
            localStorage.removeItem(`onnowtv-livecache-v1:${provider.id}:epg`);
        } catch { /* ignore */ }
        epg.current.clear();
        rerender();
    }, [provider, rerender]);

    /* ───────── Keyboard ─────────
     *
     * Long-press support for Enter/Space — most TV remotes report
     * a held button as repeated keydown events.  When the same
     * Enter key arrives ≥ 6 times in quick succession (≈ 600 ms),
     * we interpret it as a long-press and toggle the favourite
     * on the focused channel instead of playing it.
     *
     * On keyup we reset the counter so a fresh press starts over.
     * The play / reminder action only fires on keyup (so short
     * taps still work as before, and a long-press doesn't both
     * play AND favourite).
     */
    const pressRef = useRef({ key: '', count: 0, fired: false });

    useEffect(() => {
        const onKey = (e) => {
            // Confirm dialog has its own handler — let it handle
            // keys exclusively while open.
            if (pendingConfirm) return;
            const tag = (document.activeElement?.tagName || '').toLowerCase();

            if (tag === 'input') {
                if (e.key === 'ArrowDown') {
                    e.preventDefault();
                    document.activeElement.blur();
                    setSel((s) => ({ ...s, col: 1, chanIdx: 0 }));
                    return;
                }
                if (e.key === 'Escape') {
                    e.preventDefault();
                    document.activeElement.blur();
                    if (query) setQuery('');
                    return;
                }
                return;
            }

            if (e.key === '/') {
                e.preventDefault();
                document.querySelector('[data-testid="live-tv-search"]')?.focus();
                return;
            }
            if (e.key === 'f' || e.key === 'F') {
                e.preventDefault();
                onToggleFav();
                return;
            }

            /* When focus is currently INSIDE the SideNav (the user
             * just pressed LEFT at the categories column to surface
             * it), intercept arrow keys here so the menu is fully
             * navigable without leaning on the spatial-focus engine
             * (which isn't mounted on this page). */
            const activeNav = document.activeElement?.closest?.('[data-testid="side-nav"]');
            if (activeNav) {
                if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
                    e.preventDefault();
                    e.stopPropagation();
                    const items = Array.from(
                        activeNav.querySelectorAll('[data-focusable="true"]'),
                    );
                    const i = items.indexOf(document.activeElement);
                    if (i < 0) return;
                    const next = e.key === 'ArrowDown'
                        ? Math.min(items.length - 1, i + 1)
                        : Math.max(0, i - 1);
                    if (next === i) return;
                    const target = items[next];
                    items.forEach((el) => el.removeAttribute('data-focused'));
                    target.setAttribute('data-focused', 'true');
                    try { target.focus({ preventScroll: true }); }
                    catch { target.focus(); }
                    return;
                }
                if (e.key === 'ArrowRight') {
                    e.preventDefault();
                    e.stopPropagation();
                    /* Return to the LiveTV grid — drop the focus
                     * ring on the SideNav and put native focus back
                     * on the page so the next keydown re-enters the
                     * grid-handling branch below. */
                    document
                        .querySelectorAll('[data-testid="side-nav"] [data-focused="true"]')
                        .forEach((el) => el.removeAttribute('data-focused'));
                    try { document.activeElement.blur(); } catch { /* ignore */ }
                    setSel((s) => ({ ...s, col: 0 }));
                    return;
                }
                /* ArrowLeft / Enter / Space at the SideNav: leave
                 * the default behaviour to the button (Enter fires
                 * onClick) and don't fall through into the grid
                 * handler. */
                return;
            }

            const key = e.key;
            if (key !== 'ArrowUp' && key !== 'ArrowDown' &&
                key !== 'ArrowLeft' && key !== 'ArrowRight' &&
                key !== 'Enter' && key !== ' ') return;

            e.preventDefault();
            e.stopPropagation();

            /* Enter / Space — track repeat count.  Action fires on
             * keyup; long-press toggles favourite. */
            if (key === 'Enter' || key === ' ') {
                const p = pressRef.current;
                if (p.key !== key) {
                    p.key = key;
                    p.count = 1;
                    p.fired = false;
                } else {
                    p.count += 1;
                }
                // Long-press threshold: 6 repeats ≈ 600 ms on most
                // TV firmwares.  Fire the favourite toggle once,
                // mark `fired` so the keyup handler skips its
                // default play action.
                if (p.count >= 6 && !p.fired && sel.col === 1 && focusedChannel) {
                    p.fired = true;
                    onToggleFav();
                }
                return;
            }

            setSel((s) => {
                if (key === 'ArrowLeft') {
                    if (s.col === 0) {
                        /* Hand focus over to the SideNav.  On Chrome 52
                         * (HK1 box) `:focus-visible` doesn't fire on
                         * programmatic focus, so the nav button stays
                         * visually unfocused even though it owns
                         * native focus.  Mirror the spatial focus
                         * engine's contract: also stamp
                         * `data-focused="true"` (CSS hooks that), and
                         * clear it from any other element. */
                        const nav = document.querySelector('[data-testid="side-nav"] [data-focusable="true"]');
                        if (nav) {
                            document
                                .querySelectorAll('[data-focused="true"]')
                                .forEach((el) => {
                                    if (el !== nav) el.removeAttribute('data-focused');
                                });
                            nav.setAttribute('data-focused', 'true');
                            try { nav.focus({ preventScroll: true }); }
                            catch { nav.focus(); }
                        }
                        return s;
                    }
                    return { ...s, col: s.col - 1 };
                }
                if (key === 'ArrowRight') {
                    if (s.col === 2) return s;
                    if (s.col === 0 && channels.length === 0) return s;
                    return { ...s, col: s.col + 1 };
                }
                if (key === 'ArrowUp') {
                    if (s.col === 0) {
                        return { ...s, catIdx: prevNavigableIdx(sidebarCats, s.catIdx) };
                    }
                    if (s.col === 1) return { ...s, chanIdx: Math.max(0, s.chanIdx - 1) };
                    return { ...s, guideIdx: prevNavigableIdx(guideGroups, s.guideIdx) };
                }
                if (key === 'ArrowDown') {
                    if (s.col === 0) {
                        return { ...s, catIdx: nextNavigableIdx(sidebarCats, s.catIdx) };
                    }
                    if (s.col === 1) return { ...s, chanIdx: Math.min(channels.length - 1, s.chanIdx + 1) };
                    return { ...s, guideIdx: nextNavigableIdx(guideGroups, s.guideIdx) };
                }
                return s;
            });
        };

        const onKeyUp = (e) => {
            if (e.key !== 'Enter' && e.key !== ' ') return;
            const p = pressRef.current;
            const wasLongPress = p.fired;
            // Reset state regardless.
            p.key = '';
            p.count = 0;
            p.fired = false;
            if (wasLongPress) return; // long-press already handled the favourite
            // Short tap — perform the column's default action.
            if (sel.col === 1 && focusedChannel) {
                playChannel(focusedChannel);
            } else if (sel.col === 2) {
                const it = guideGroups[sel.guideIdx];
                if (it && !it._kind && !it.kind) onToggleReminder(it);
            }
        };

        window.addEventListener('keydown', onKey, true);
        window.addEventListener('keyup', onKeyUp, true);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            window.removeEventListener('keyup', onKeyUp, true);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [sel, channels, guideGroups, focusedChannel, provider, query, sidebarCats, onToggleFav, onToggleReminder, playChannel, pendingConfirm]);

    useEffect(() => { setSel((s) => ({ ...s, chanIdx: 0, guideIdx: 0 })); }, [sel.catIdx]);
    useEffect(() => { setSel((s) => ({ ...s, guideIdx: 0 })); }, [sel.chanIdx, allChannels]);

    useEffect(() => {
        if (sel.chanIdx >= channels.length && channels.length > 0) {
            setSel((s) => ({ ...s, chanIdx: 0 }));
        }
    }, [channels.length, sel.chanIdx]);

    /* Stable row renderers. */
    const renderCategory = useCallback(
        (c, i, focused) => <CategoryRow key={c.id} cat={c} focused={focused} />,
        [],
    );
    const renderChannel = useCallback(
        (c, i, focused) => {
            const nextEpg = epg.current.get(c.stream_id);
            const now = nextEpg?.[0] || null;
            const matched = c._matchedProgramme || null;
            return (
                <ChannelCard
                    key={c.stream_id}
                    ch={c}
                    focused={focused}
                    isFav={favs.has(String(c.stream_id))}
                    now={now}
                    matched={matched}
                />
            );
        },
        // `bump` is critical here: when prefetch fills epg.current,
        // rerender() bumps the counter, this callback regenerates,
        // Column receives a new rowFn, and every visible card
        // gets a fresh `now` prop — overriding React.memo.
        // eslint-disable-next-line react-hooks/exhaustive-deps
        [favs, bump],
    );
    const renderGuide = useCallback(
        (it, i, focused) => (
            <GuideRow
                key={`${it._kind || 'row'}-${it.id || it.startTimestamp || i}`}
                item={it}
                focused={focused}
                isReminded={it.startTimestamp ? reminderKeys.has(`${Number(debouncedChannel?.stream_id) || debouncedChannel?.stream_id}:${Number(it.startTimestamp) || it.startTimestamp}`) : false}
            />
        ),
        [reminderKeys, debouncedChannel],
    );

    const activeCat = sidebarCats[sel.catIdx] || sidebarCats[0];
    const focusedNow = focusedChannel ? (epg.current.get(focusedChannel.stream_id)?.[0] || null) : null;
    const focusedNext = focusedChannel ? (epg.current.get(focusedChannel.stream_id)?.[1] || null) : null;

    const isMobile = useIsMobile();

    /* While the splash is up, render LiveTVBoot INSTEAD of the grid
     * — the splash is full-screen and intentionally blocks all
     * interaction so the user can't D-pad into an empty grid. */
    if (bootBlocked) {
        return (
            <LiveTVBoot
                onSkip={() => setBootBlocked(false)}
            />
        );
    }

    return (
        <div style={{
            display: 'flex',
            flexDirection: 'column',
            height: '100%',
            minHeight: 0,
        }}>
            <Hero
                channel={focusedChannel}
                now={focusedNow}
                next={focusedNext}
                isFav={focusedChannel ? favs.has(String(focusedChannel.stream_id)) : false}
                syncing={syncing}
                onToggleFav={onToggleFav}
                onRefresh={onRefresh}
                onLogout={onLogout}
            />
            <div style={{
                display: 'grid',
                gridTemplateColumns: isMobile ? '1fr' : '230px 1fr 320px',
                gap: isMobile ? 8 : 14,
                padding: isMobile ? '0 12px 12px 12px' : '0 24px 24px 24px',
                flex: 1,
                minHeight: 0,
            }}>
                {/* Mobile: a simple top tab strip lets the user pick
                    which column to display (categories vs channels vs
                    guide).  TV: all three columns are visible
                    simultaneously and D-pad LEFT/RIGHT moves between
                    them. */}
                {isMobile && (
                    <div
                        data-testid="livetv-mobile-tabs"
                        style={{
                            display: 'flex',
                            gap: 6,
                            padding: '8px 0',
                            position: 'sticky',
                            top: 0,
                            zIndex: 5,
                            background: 'rgba(6,8,15,0.96)',
                        }}
                    >
                        {[
                            { id: 0, label: 'Categories' },
                            { id: 1, label: 'Channels' },
                            { id: 2, label: 'Guide' },
                        ].map((t) => {
                            const active = sel.col === t.id;
                            return (
                                <button
                                    key={t.id}
                                    onClick={() => setSel((s) => ({ ...s, col: t.id }))}
                                    style={{
                                        flex: 1,
                                        padding: '10px 8px',
                                        background: active ? 'rgba(93,200,255,0.18)' : 'rgba(255,255,255,0.04)',
                                        border: active ? '1px solid rgba(93,200,255,0.55)' : '1px solid rgba(255,255,255,0.10)',
                                        borderRadius: 10,
                                        color: active ? '#FFFFFF' : '#9DA5B5',
                                        fontSize: 12, fontWeight: 700, letterSpacing: '0.04em',
                                        cursor: 'pointer',
                                        outline: 'none',
                                        WebkitTapHighlightColor: 'transparent',
                                    }}
                                >
                                    {t.label}
                                </button>
                            );
                        })}
                    </div>
                )}

                <div style={{
                    display: isMobile && sel.col !== 0 ? 'none' : 'flex',
                    flexDirection: 'column', minHeight: 0, gap: 8,
                }}>
                    <Column
                        testid="cats"
                        isFocused={sel.col === 0}
                        items={sidebarCats}
                        idx={sel.catIdx}
                        rowHeight={ROW_H}
                        rowFn={renderCategory}
                        onTap={(item, i) => {
                            if (item?.kind === 'header') return;
                            setSel((s) => ({ ...s, col: 1, catIdx: i, chanIdx: 0 }));
                        }}
                    />
                </div>
                <div style={{
                    display: isMobile && sel.col !== 1 ? 'none' : 'flex',
                    flexDirection: 'column', minHeight: 0, gap: 8,
                }}>
                    <SearchRow
                        query={query}
                        onChange={setQuery}
                        resultCount={channels.length}
                        totalCount={allChannels.length}
                    />
                    <Column
                        testid="channels"
                        isFocused={sel.col === 1}
                        items={channels}
                        idx={sel.chanIdx}
                        rowHeight={CHAN_H}
                        rowFn={renderChannel}
                        onTap={(item, i) => {
                            /* Mobile tap-to-explore: the first tap on
                             * a channel SELECTS it (showing its guide
                             * column on phones); a second tap on the
                             * same row plays.  This matches the
                             * expected "see what's on" → "watch it"
                             * mental model users tested on phones.
                             * On a TV browser (D-pad), this is a no-
                             * op because tap doesn't fire — OK still
                             * goes through the keyboard handler. */
                            if (isMobile) {
                                const alreadySelected = sel.chanIdx === i;
                                if (alreadySelected) {
                                    if (item) playChannel(item);
                                } else {
                                    /* Show guide column (col 2) for
                                       the tapped channel. */
                                    setSel((s) => ({ ...s, col: 2, chanIdx: i, guideIdx: 0 }));
                                }
                            } else {
                                setSel((s) => ({ ...s, col: 1, chanIdx: i }));
                                if (item) playChannel(item);
                            }
                        }}
                    />
                </div>
                <div style={{
                    display: isMobile && sel.col !== 2 ? 'none' : 'flex',
                    flexDirection: 'column', minHeight: 0, gap: 8,
                }}>
                    {/* Mobile-only top action bar: lets the user
                        either watch the selected channel or go back
                        to the channel list without playing.  This is
                        critical because on mobile the only way to
                        "see what else is on" without auto-tuning is
                        to first select the channel (col 2) — there
                        was previously no way to back out without
                        playing. */}
                    {isMobile && focusedChannel && (
                        <div style={{
                            display: 'flex', gap: 10, alignItems: 'center',
                            padding: '0 4px 4px 4px',
                        }}>
                            <button
                                data-testid="livetv-mobile-back-to-channels"
                                onClick={() => setSel((s) => ({ ...s, col: 1 }))}
                                style={{
                                    height: 40, padding: '0 14px',
                                    borderRadius: 999,
                                    background: 'rgba(255,255,255,0.06)',
                                    border: '1px solid rgba(255,255,255,0.12)',
                                    color: '#E6EAF2',
                                    fontSize: 13, fontWeight: 600,
                                    WebkitTapHighlightColor: 'transparent',
                                    cursor: 'pointer',
                                }}
                            >
                                ← Channels
                            </button>
                            <button
                                data-testid="livetv-mobile-watch"
                                onClick={() => playChannel(focusedChannel)}
                                style={{
                                    flex: 1,
                                    height: 44,
                                    borderRadius: 999,
                                    background: 'var(--vesper-blue)',
                                    border: 'none',
                                    color: 'var(--vesper-bg-0)',
                                    fontSize: 14, fontWeight: 800,
                                    letterSpacing: '0.04em',
                                    WebkitTapHighlightColor: 'transparent',
                                    cursor: 'pointer',
                                }}
                            >
                                ▶  WATCH {focusedChannel.name?.slice(0, 18).toUpperCase() || 'CHANNEL'}
                            </button>
                        </div>
                    )}
                    {/* Top blue date label with live clock */}
                    <GuideTopBar todayLabel={guideTodayLabel} />
                    <GuideHeader channelName={debouncedChannel?.name || ''} />
                    <Column
                        testid="guide"
                        isFocused={sel.col === 2}
                        items={guideGroups}
                        idx={sel.guideIdx}
                        rowHeight={GUIDE_ROW_H}
                        rowFn={renderGuide}
                        onTap={(item, i) => {
                            setSel((s) => ({ ...s, col: 2, guideIdx: i }));
                            /* Tapping the currently-airing programme
                               row (kind: 'now') tunes the channel.
                               Tapping future programmes is a no-op on
                               mobile (no catch-up support yet) — but
                               the user can use the WATCH button
                               above for an unambiguous play action. */
                            if (!isMobile && item && item._kind === 'now' && focusedChannel) {
                                playChannel(focusedChannel);
                            }
                        }}
                    />
                </div>
            </div>
            <ConfirmModal
                open={!!pendingConfirm}
                title={pendingConfirm?.title || ''}
                body={pendingConfirm?.body || ''}
                onConfirm={pendingConfirm?.onConfirm}
                onCancel={() => setPendingConfirm(null)}
            />
        </div>
    );
}

/* ─────────────────────────────── Hero ─────────────────────────────── */

const Hero = React.memo(function Hero({
    channel, now, next, isFav, syncing,
    onToggleFav, onRefresh, onLogout,
}) {
    const tmdb = useProgrammeBackdrop(now?.title || '', channel?.name || '');
    const backdropUrl = tmdb?.backdrop
        ? proxyImg(tmdb.backdrop, 1200, 60)
        : '';

    const progress = computeProgress(now);
    const nowTime = formatTime(now?.startTimestamp);
    const nextTime = formatTime(next?.startTimestamp);

    return (
        <section style={{
            position: 'relative',
            minHeight: 240,
            padding: '24px 32px 14px 32px',
            overflow: 'hidden',
            flexShrink: 0,
        }}>
            {/* TMDB backdrop layer */}
            {backdropUrl && (
                <div
                    aria-hidden="true"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        backgroundImage: `linear-gradient(90deg, #0A0F1A 0%, rgba(10,15,26,0.85) 38%, rgba(10,15,26,0.2) 100%), url(${backdropUrl})`,
                        backgroundSize: 'auto, cover',
                        backgroundPosition: 'center, center',
                        backgroundRepeat: 'no-repeat, no-repeat',
                    }}
                />
            )}
            {/* Bottom fade so hero blends into body */}
            <div aria-hidden="true" style={{
                position: 'absolute',
                inset: 'auto 0 0 0',
                height: 80,
                background: 'linear-gradient(180deg, transparent 0%, #0A0F1A 100%)',
            }} />

            {/* Top-right utility cluster */}
            <div style={{
                position: 'absolute',
                top: 24,
                right: 32,
                display: 'flex',
                gap: 8,
                zIndex: 2,
            }}>
                <HeroIconButton
                    label={isFav ? 'Unfavourite' : 'Favourite'}
                    onClick={onToggleFav}
                    accent={isFav ? '#FFC850' : undefined}
                >
                    <Star size={16} fill={isFav ? '#FFC850' : 'none'} color={isFav ? '#FFC850' : '#9DA5B5'} />
                </HeroIconButton>
                <HeroIconButton label="Refresh" onClick={onRefresh}>
                    <RefreshCw size={15} color={syncing ? '#5DC8FF' : '#9DA5B5'} />
                </HeroIconButton>
                <HeroIconButton label="Sign out" onClick={onLogout}>
                    <LogOut size={15} color="#9DA5B5" />
                </HeroIconButton>
            </div>

            <div style={{ position: 'relative', maxWidth: 720, zIndex: 1 }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                    letterSpacing: '0.32em', color: '#5DC8FF', marginBottom: 10,
                }}>
                    LIVE TV{channel?.num != null ? ` · CH ${channel.num}` : ''}
                </div>
                <h1 style={{
                    margin: 0,
                    fontSize: 'clamp(36px, 4vw, 56px)',
                    fontWeight: 800,
                    lineHeight: 1.05,
                    letterSpacing: '-0.025em',
                    color: '#fff',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                }}>
                    {channel?.name || 'Live TV'}
                </h1>

                {now ? (
                    <>
                        <div style={{
                            marginTop: 16,
                            fontSize: 13,
                            color: '#E6EAF2',
                            display: 'flex', alignItems: 'baseline', gap: 12,
                            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        }}>
                            <span style={{
                                fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                                letterSpacing: '0.24em', color: '#5DC8FF',
                            }}>
                                NOW · {nowTime}
                            </span>
                            <span style={{ fontWeight: 700, fontSize: 14 }}>
                                {now.title || 'Untitled'}
                            </span>
                        </div>
                        {now.description && (
                            <div style={{
                                marginTop: 8,
                                fontSize: 13,
                                color: '#9DA5B5',
                                lineHeight: 1.45,
                                maxWidth: 640,
                                overflow: 'hidden',
                                display: '-webkit-box',
                                WebkitLineClamp: 3,
                                WebkitBoxOrient: 'vertical',
                                textOverflow: 'ellipsis',
                            }}>
                                {now.description}
                            </div>
                        )}
                        <div style={{
                            marginTop: 12,
                            width: '100%', maxWidth: 540,
                            height: 3, background: 'rgba(255,255,255,0.10)', borderRadius: 2,
                            overflow: 'hidden',
                        }}>
                            <div style={{
                                width: `${progress}%`,
                                height: '100%',
                                background: '#5DC8FF',
                            }} />
                        </div>
                        {next && (
                            <div style={{
                                marginTop: 8,
                                fontFamily: 'monospace',
                                fontSize: 10,
                                letterSpacing: '0.2em',
                                color: '#7d8493',
                                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                                maxWidth: 540,
                            }}>
                                UP NEXT · {nextTime} · {next.title || 'Untitled'}
                            </div>
                        )}
                    </>
                ) : channel ? (
                    <div style={{
                        marginTop: 16,
                        fontFamily: 'monospace', fontSize: 11,
                        letterSpacing: '0.24em', color: '#7d8493',
                    }}>
                        LOADING PROGRAMME GUIDE…
                    </div>
                ) : null}
            </div>
        </section>
    );
});

const HeroIconButton = React.memo(function HeroIconButton({ children, label, onClick, accent }) {
    return (
        <button
            type="button"
            aria-label={label}
            onClick={onClick}
            style={{
                width: 40, height: 40,
                borderRadius: 999,
                background: accent ? 'rgba(255,200,80,0.12)' : 'rgba(20,28,42,0.85)',
                border: '1px solid ' + (accent ? 'rgba(255,200,80,0.45)' : 'rgba(255,255,255,0.10)'),
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                cursor: 'pointer',
            }}
        >
            {children}
        </button>
    );
});

/* ─────────────────────────── Search row ─────────────────────────── */

const SearchRow = React.memo(function SearchRow({ query, onChange, resultCount, totalCount }) {
    const has = !!query.trim();
    return (
        <div style={{
            padding: '10px 16px',
            display: 'flex', alignItems: 'center', gap: 12,
            background: 'rgba(20,28,42,0.6)',
            border: '1px solid ' + (has ? 'rgba(93,200,255,0.45)' : 'rgba(255,255,255,0.07)'),
            borderRadius: 14,
            minHeight: 50,
        }}>
            <Search size={14} color={has ? '#5DC8FF' : '#7d8493'} />
            <input
                data-testid="live-tv-search"
                type="text"
                value={query}
                onChange={(e) => onChange(e.target.value)}
                placeholder="Search channels & guide, e.g. “Toronto Raptors”, “BBC News”"
                style={{
                    flex: 1, minWidth: 0,
                    background: 'transparent', border: 'none', outline: 'none',
                    color: '#fff', fontSize: 13,
                }}
            />
            <span style={{
                fontFamily: 'monospace', fontSize: 10, fontWeight: 700, letterSpacing: '0.16em',
                color: has ? '#5DC8FF' : '#7d8493',
            }}>
                {has ? `${resultCount} / ${totalCount}` : `${totalCount} CHANNELS`}
            </span>
        </div>
    );
});

/* ─────────────────────── Guide top bar (date + clock) ─────────────────────── */

const GuideTopBar = React.memo(function GuideTopBar({ todayLabel }) {
    const clock = useClock();
    return (
        <div style={{
            padding: '4px 4px 0 4px',
            display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
            gap: 8,
        }}>
            <span style={{
                fontFamily: 'monospace', fontSize: 11, fontWeight: 700,
                letterSpacing: '0.32em',
                color: '#5DC8FF',
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {todayLabel}
            </span>
            <span style={{
                fontFamily: 'monospace', fontSize: 13, fontWeight: 800,
                letterSpacing: '0.06em',
                color: '#fff',
                fontVariantNumeric: 'tabular-nums',
                whiteSpace: 'nowrap',
                flexShrink: 0,
            }}>
                {clock}
            </span>
        </div>
    );
});

function useClock() {
    const [now, setNow] = useState(() => formatClock(new Date()));
    useEffect(() => {
        const t = setInterval(() => setNow(formatClock(new Date())), 30_000);
        return () => clearInterval(t);
    }, []);
    return now;
}
function formatClock(d) {
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${String(h).padStart(2, '0')}:${mm} ${ap}`;
}

/* ─────────────────────── Guide column header ─────────────────────── */

const GuideHeader = React.memo(function GuideHeader({ channelName }) {
    return (
        <div style={{
            padding: '10px 16px',
            display: 'flex', alignItems: 'center', gap: 10,
            background: 'rgba(20,28,42,0.6)',
            border: '1px solid rgba(255,255,255,0.07)',
            borderRadius: 14,
            minHeight: 50,
            whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>
            <Calendar size={14} color="#7d8493" />
            <span style={{
                fontFamily: 'monospace', fontSize: 11, letterSpacing: '0.24em',
                color: '#7d8493', fontWeight: 700,
            }}>
                GUIDE
            </span>
            <span style={{ color: '#5e6473' }}>·</span>
            <span style={{
                color: '#E6EAF2', fontSize: 13, fontWeight: 600,
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                flex: 1, minWidth: 0,
            }}>
                {channelName || '–'}
            </span>
        </div>
    );
});

/* ──────────────────── Column (virtualised) ──────────────────── */

const Column = React.memo(function Column({ testid, isFocused, items, idx, rowHeight, rowFn, onTap }) {
    const containerRef = useRef(null);
    const [scrollTop, setScrollTop] = useState(0);

    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        const top = idx * rowHeight;
        const bottom = top + rowHeight;
        const viewTop = el.scrollTop;
        const viewBottom = viewTop + el.clientHeight;
        /* Smooth-scroll the focused row into view.  Matches the
         * inertial feel of the Home shelves so D-pad spam (or a
         * finger drag) feels fluid rather than jumping row-by-row. */
        const target =
            top < viewTop
                ? top
                : bottom > viewBottom
                ? bottom - el.clientHeight
                : null;
        if (target !== null) {
            try {
                el.scrollTo({ top: target, behavior: 'smooth' });
            } catch {
                el.scrollTop = target;
            }
        }
    }, [idx, rowHeight]);

    useEffect(() => {
        const el = containerRef.current;
        if (!el) return;
        let pending = false;
        const onScroll = () => {
            if (pending) return;
            pending = true;
            requestAnimationFrame(() => {
                setScrollTop(el.scrollTop);
                pending = false;
            });
        };
        el.addEventListener('scroll', onScroll);
        return () => el.removeEventListener('scroll', onScroll);
    }, []);

    const view = containerRef.current?.clientHeight || 600;
    const start = Math.max(0, Math.floor(scrollTop / rowHeight) - BUFFER);
    const end = Math.min(items.length, Math.ceil((scrollTop + view) / rowHeight) + BUFFER);
    const visible = [];
    for (let i = start; i < end; i++) {
        visible.push({ item: items[i], i });
    }

    return (
        <div
            data-testid={`live-tv-${testid}`}
            ref={containerRef}
            style={{
                flex: 1,
                overflowY: 'auto',
                overflowX: 'hidden',
                position: 'relative',
            }}
        >
            <div style={{ height: items.length * rowHeight, position: 'relative' }}>
                {visible.map(({ item, i }) => (
                    <div
                        key={item?.id || item?.stream_id || item?.startTimestamp || i}
                        data-testid={`${testid}-row-${i}`}
                        onClick={onTap ? (() => onTap(item, i)) : undefined}
                        style={{
                            position: 'absolute',
                            top: i * rowHeight,
                            left: 0, right: 0,
                            height: rowHeight,
                            padding: '0 0 6px 0',
                            cursor: onTap ? 'pointer' : undefined,
                            WebkitTapHighlightColor: 'transparent',
                        }}
                    >
                        {rowFn(item, i, isFocused && i === idx)}
                    </div>
                ))}
            </div>
        </div>
    );
});

/* ─────────────────────────── Rows ─────────────────────────── */

const CategoryRow = React.memo(function CategoryRow({ cat, focused }) {
    if (cat.kind === 'header') {
        return (
            <div style={{
                height: '100%',
                padding: '14px 16px 4px 16px',
                fontFamily: 'monospace', fontSize: 10, letterSpacing: '0.24em',
                color: '#5e6473', fontWeight: 700,
                display: 'flex', alignItems: 'center',
            }}>
                {cat.name}
            </div>
        );
    }
    const isFav = cat.id === FAV_CAT;
    const isRem = cat.id === REM_CAT;
    const accent = isFav ? '#FFC850' : isRem ? '#FFC850' : '#5DC8FF';
    return (
        <div style={{
            height: '100%',
            padding: '0 12px',
            display: 'flex', alignItems: 'center', gap: 8,
            background: focused
                ? (isFav || isRem ? 'rgba(255,200,80,0.10)' : 'rgba(20,28,42,0.85)')
                : 'rgba(20,28,42,0.5)',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.06)'),
            boxShadow: focused ? `0 0 0 1px ${accent}` : 'none',
            borderRadius: 10,
            color: focused ? '#fff' : '#9DA5B5',
            fontWeight: focused ? 700 : 600,
            fontSize: 12,
        }}>
            {isFav && (
                <Star size={12} color={accent} fill={focused ? accent : 'none'} />
            )}
            {isRem && (
                <Bell size={11} color={accent} fill={focused ? accent : 'none'} />
            )}
            <span style={{
                flex: 1, minWidth: 0,
                whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>
                {cat.name}
            </span>
            {cat.count > 0 && (
                <span style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    color: focused ? accent : '#5e6473',
                }}>
                    {cat.count}
                </span>
            )}
        </div>
    );
});

const ChannelCard = React.memo(function ChannelCard({ ch, focused, isFav, now, matched }) {
    const accent = '#5DC8FF';
    const progress = computeProgress(matched || now);
    /* When the row appeared because of an EPG search match, surface
     * the matched programme in the slot where NOW normally lives,
     * tagged differently so the user can see *why* this channel
     * came up in their search results. */
    const isMatchView = !!matched;
    const labelText = isMatchView ? 'MATCH' : 'NOW';
    const labelColor = isMatchView ? '#5DC8FF' : '#FF4D5E';
    const displayItem = matched || now;
    return (
        <div style={{
            height: '100%',
            padding: '0 14px',
            display: 'flex', alignItems: 'center', gap: 14,
            background: focused
                ? 'linear-gradient(180deg, rgba(93,200,255,0.10) 0%, rgba(20,28,42,0.65) 100%)'
                : 'linear-gradient(180deg, rgba(255,255,255,0.04) 0%, rgba(20,28,42,0.55) 100%)',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.07)'),
            boxShadow: focused
                ? '0 0 0 1px rgba(93,200,255,0.35), inset 0 1px 0 rgba(255,255,255,0.08)'
                : 'inset 0 1px 0 rgba(255,255,255,0.04)',
            borderRadius: 14,
            position: 'relative',
            overflow: 'hidden',
        }}>
            <span style={{
                fontFamily: 'monospace', fontSize: 12, fontWeight: 700,
                color: focused ? accent : '#7d8493',
                minWidth: 30, textAlign: 'right',
            }}>
                {ch.num ?? ''}
            </span>
            <span style={{
                width: 44, height: 30, flexShrink: 0,
                background: 'rgba(255,255,255,0.04)',
                borderRadius: 5,
                overflow: 'hidden',
                position: 'relative',
            }}>
                {ch.stream_icon && (
                    <img
                        src={proxyImg(ch.stream_icon, 44, 50)}
                        alt=""
                        width={44}
                        height={30}
                        loading="lazy"
                        decoding="async"
                        referrerPolicy="no-referrer"
                        onError={(e) => { e.currentTarget.style.display = 'none'; }}
                        style={{
                            position: 'absolute', inset: 0,
                            width: '100%', height: '100%',
                            objectFit: 'contain',
                            padding: 2,
                        }}
                    />
                )}
            </span>
            {/* Title + NOW line + (right-aligned) progress bar.  The
                progress bar lives inside this column so it aligns
                with the title text (starts under "NOW") rather than
                spanning the whole card width. */}
            <div style={{ flex: 1, minWidth: 0,
                            display: 'flex', flexDirection: 'column', gap: 2, position: 'relative' }}>
                <span style={{
                    fontSize: 13, fontWeight: 700,
                    color: focused ? '#fff' : '#E6EAF2',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    lineHeight: 1.15,
                }}>
                    {ch.name}
                </span>
                {displayItem ? (
                    <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 6,
                        fontSize: 10,
                        color: '#9DA5B5',
                        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                        minWidth: 0,
                        lineHeight: 1.15,
                    }}>
                        <span style={{
                            fontFamily: 'monospace', fontSize: 8, fontWeight: 800,
                            letterSpacing: '0.16em', color: '#fff',
                            padding: '1px 5px',
                            background: labelColor,
                            borderRadius: 2,
                            flexShrink: 0,
                        }}>
                            {labelText}
                        </span>
                        <span style={{
                            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap',
                            flex: 1, minWidth: 0,
                        }}>
                            {displayItem.title || 'Untitled'}
                        </span>
                    </span>
                ) : (
                    <span style={{
                        fontFamily: 'monospace', fontSize: 8, letterSpacing: '0.2em',
                        color: '#5e6473', lineHeight: 1.15,
                    }}>
                        NO GUIDE DATA
                    </span>
                )}
                <div style={{
                    marginTop: 3,
                    height: 2,
                    background: 'rgba(255,255,255,0.08)',
                    borderRadius: 1,
                    overflow: 'hidden',
                }}>
                    <div style={{
                        width: `${progress}%`,
                        height: '100%',
                        background: accent,
                    }} />
                </div>
            </div>
            {isFav && <Star size={12} color="#FFC850" fill="#FFC850" style={{ flexShrink: 0 }} />}
        </div>
    );
});

const GuideRow = React.memo(function GuideRow({ item, focused, isReminded }) {
    if (item._kind === 'header') {
        return (
            <div style={{
                height: '100%',
                padding: '14px 16px 4px 16px',
                fontFamily: 'monospace', fontSize: 10, letterSpacing: '0.24em',
                color: '#5e6473', fontWeight: 700,
                display: 'flex', alignItems: 'center',
            }}>
                {item.label}
            </div>
        );
    }
    const start = Number(item.startTimestamp) || 0;
    const stop = Number(item.stopTimestamp) || 0;
    const nowSec = Math.floor(Date.now() / 1000);
    const isLive = nowSec >= start && nowSec < stop;
    const isPast = stop > 0 && stop <= nowSec;
    const accent = isLive ? '#5DC8FF' : (isReminded ? '#FFC850' : '#5DC8FF');
    const timeStr = formatTime(start);
    const [hhmm, ampm] = splitHHMM_AMPM(timeStr);

    return (
        <div style={{
            height: '100%',
            padding: '0 10px',
            display: 'flex', gap: 8, alignItems: 'stretch',
            background: 'rgba(20,28,42,0.55)',
            border: '1px solid ' + (focused ? accent : 'rgba(255,255,255,0.06)'),
            boxShadow: focused ? `0 0 0 1px ${accent}` : 'none',
            borderRadius: 10,
            opacity: isPast ? 0.55 : 1,
            overflow: 'hidden',
        }}>
            <div style={{
                width: 38, flexShrink: 0,
                display: 'flex', flexDirection: 'column',
                alignItems: 'center', justifyContent: 'center', gap: 0,
                color: isLive ? accent : '#9DA5B5',
            }}>
                <span style={{ fontFamily: 'monospace', fontSize: 11, fontWeight: 700, letterSpacing: '0.02em', lineHeight: 1.1 }}>
                    {hhmm}
                </span>
                <span style={{ fontFamily: 'monospace', fontSize: 8, fontWeight: 700, letterSpacing: '0.1em', color: '#5e6473', lineHeight: 1.1 }}>
                    {ampm}
                </span>
            </div>
            <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column',
                            justifyContent: 'center', gap: 2 }}>
                <span style={{
                    fontSize: 12, fontWeight: 600,
                    color: isLive ? '#fff' : '#E6EAF2',
                    whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
                    lineHeight: 1.2,
                }}>
                    {item.title || 'Untitled'}
                </span>
                {!isPast && (
                    <span style={{
                        display: 'inline-flex', alignItems: 'center', gap: 4,
                        fontFamily: 'monospace', fontSize: 8, fontWeight: 700,
                        letterSpacing: '0.14em', color: isReminded ? '#FFC850' : '#7d8493',
                        lineHeight: 1.1,
                    }}>
                        <Bell size={9} color={isReminded ? '#FFC850' : '#7d8493'}
                                fill={isReminded ? '#FFC850' : 'none'} />
                        {isReminded ? 'REMIND ON' : 'OK TO REMIND'}
                    </span>
                )}
            </div>
        </div>
    );
});

/* ─────────────────────────── Helpers ─────────────────────────── */

function formatTime(ts) {
    // 12-hour with AM/PM for the design.
    if (!ts) return '';
    const d = new Date(Number(ts) * 1000);
    let h = d.getHours();
    const ap = h >= 12 ? 'PM' : 'AM';
    h = h % 12;
    if (h === 0) h = 12;
    const mm = String(d.getMinutes()).padStart(2, '0');
    return `${String(h).padStart(2, '0')}:${mm} ${ap}`;
}

function splitHHMM_AMPM(timeStr) {
    if (!timeStr) return ['', ''];
    const parts = timeStr.split(' ');
    return [parts[0] || '', parts[1] || ''];
}

function computeProgress(item) {
    if (!item) return 0;
    const start = Number(item.startTimestamp) || 0;
    const stop = Number(item.stopTimestamp) || 0;
    if (stop <= start) return 0;
    const nowSec = Math.floor(Date.now() / 1000);
    if (nowSec <= start) return 0;
    if (nowSec >= stop) return 100;
    return Math.round(((nowSec - start) / (stop - start)) * 100);
}

function proxyImg(url, width = 36, quality = 50) {
    if (!url) return '';
    const base = process.env.REACT_APP_BACKEND_URL;
    if (!base) return url;
    return `${base}/api/img-proxy?url=${encodeURIComponent(url)}&w=${width}&q=${quality}`;
}

function buildSidebarCats(rawCats, favCount, recCount, remCount, channelsMap) {
    const out = [];
    out.push({ id: FAV_CAT, name: 'Favourites', count: favCount });
    if (recCount > 0) out.push({ id: REC_CAT, name: 'Recently Watched', count: recCount });
    if (remCount > 0) out.push({ id: REM_CAT, name: 'Reminders', count: remCount });
    if (rawCats.length > 0) {
        out.push({ id: 'h-cats', kind: 'header', name: 'CHANNEL GROUPS' });
    }
    for (const c of rawCats) {
        out.push({
            id: c.category_id,
            name: c.category_name,
            count: channelsMap.get(c.category_id)?.length || 0,
        });
    }
    return out;
}

function resolveByIds(idsSet, channelsMap, orderedKeys) {
    const lookup = new Map();
    for (const arr of channelsMap.values()) {
        for (const ch of (arr || [])) lookup.set(String(ch.stream_id), ch);
    }
    if (orderedKeys) {
        const out = [];
        for (const k of orderedKeys) {
            const ch = lookup.get(String(k));
            if (ch) out.push(ch);
        }
        return out;
    }
    const out = [];
    for (const k of idsSet) {
        const ch = lookup.get(String(k));
        if (ch) out.push(ch);
    }
    return out;
}

/** Inject TOMORROW / dated headers into an EPG list.  The TODAY
 *  group is implicit — its label sits above the GUIDE header in
 *  the UI, not inside the scrollable list.  This keeps the column
 *  clean: it's all upcoming items, with a date divider only when
 *  programmes span into a new day. */
function groupByDay(items) {
    if (!items || items.length === 0) return EMPTY_ARRAY;
    const out = [];
    const today = startOfDay(new Date()).getTime() / 1000;
    const tomorrow = today + 86400;
    const dayAfter = tomorrow + 86400;
    let lastBucket = 'TODAY';   // assume we start with today
    for (const it of items) {
        const start = Number(it.startTimestamp) || 0;
        let bucket;
        if (start >= today && start < tomorrow) bucket = 'TODAY';
        else if (start >= tomorrow && start < dayAfter) bucket = 'TOMORROW';
        else bucket = formatDayLabel(new Date(start * 1000));
        if (bucket !== lastBucket) {
            out.push({ _kind: 'header', id: `h-${bucket}`, label: bucket });
            lastBucket = bucket;
        }
        out.push(it);
    }
    return out;
}

function startOfDay(d) {
    const x = new Date(d);
    x.setHours(0, 0, 0, 0);
    return x;
}

function formatDayLabel(d) {
    const days = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
    const months = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
    return `${days[d.getDay()]} ${d.getDate()} ${months[d.getMonth()]}`;
}

/** Skip non-focusable section headers when arrow-navigating. */
function isHeaderItem(it) {
    return !!(it && (it.kind === 'header' || it._kind === 'header'));
}
function nextNavigableIdx(arr, from) {
    for (let i = from + 1; i < arr.length; i++) {
        if (!isHeaderItem(arr[i])) return i;
    }
    return from;
}
function prevNavigableIdx(arr, from) {
    for (let i = from - 1; i >= 0; i--) {
        if (!isHeaderItem(arr[i])) return i;
    }
    return from;
}
