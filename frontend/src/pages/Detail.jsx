import React, { useEffect, useMemo, useState, useRef, useCallback } from 'react';
import { useParams, useNavigate, useLocation } from 'react-router-dom';
import {
    Play,
    ArrowLeft,
    Loader2,
    ExternalLink,
    Copy,
    Magnet,
    Info,
    Check,
    Film,
    Home,
} from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import SeriesEpisodes from '@/components/SeriesEpisodes';
import CastRow from '@/components/CastRow';
import PartyJoiningScreen from '@/components/PartyJoiningScreen';
import TrailerModal from '@/components/TrailerModal';
import StreamUnavailableModal from '@/components/StreamUnavailableModal';
import StreamPickerModal from '@/components/StreamPickerModal';
import Host from '@/lib/host';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { API, Vesper } from '@/lib/api';
import { qualityBadge, qualityTags, toneColors, is1080p, is4K } from '@/lib/streamMeta';
import { getAutoplay1080p } from '@/lib/prefs';
import { isKidsActive, getActiveProfile } from '@/lib/profiles';
import { avatarEmojiById } from '@/lib/avatars';
import * as cw from '@/lib/continueWatching';
import { isInLibrary } from '@/lib/library';

const streamMode = (s) => {
    if (s?.url) return 'direct';
    if (s?.externalUrl) return 'external';
    if (s?.infoHash) return 'torrent';
    return 'unknown';
};

/* Watch Together diagnostic breadcrumbs.  Keep a short rolling log
 * in localStorage so we can inspect AFTER the user reports a bug.
 * Console.log is also called so the WebView Inspector + Android
 * `adb logcat` see them in real time.  No PII written. */
function partyBreadcrumb(event, info = {}) {
    try {
        // eslint-disable-next-line no-console
        console.log('[watch-party]', event, info);
        const key = 'vesper-party-breadcrumbs';
        const arr = JSON.parse(localStorage.getItem(key) || '[]');
        arr.push({ t: Date.now(), event, info });
        // Keep last 80 entries (~last 2 watch-party attempts).
        while (arr.length > 80) arr.shift();
        localStorage.setItem(key, JSON.stringify(arr));
    } catch { /* ignore */ }
}

const buildMagnet = (s, fallbackName = '') => {
    if (!s?.infoHash) return null;
    const name = s.name || s.title || fallbackName || 'video';
    const trackers = Array.isArray(s.sources)
        ? s.sources
              .filter((t) => typeof t === 'string' && t.startsWith('tracker:'))
              .map((t) => `&tr=${encodeURIComponent(t.slice('tracker:'.length))}`)
              .join('')
        : '';
    return `magnet:?xt=urn:btih:${s.infoHash}&dn=${encodeURIComponent(
        name
    )}${trackers}`;
};

export default function Detail() {
    useSpatialFocus();
    const { type, id } = useParams();
    const navigate = useNavigate();
    const location = useLocation();
    const resumeRequested = useMemo(
        () => new URLSearchParams(location.search).get('resume') === '1',
        [location.search]
    );
    // ?autoplay=1 lands here from the hero's Play button — when the
    // Autoplay 1080p setting is on, we pick the first 1080p direct
    // stream the moment the streams list resolves and start playback
    // automatically, skipping the source picker.
    const autoplayRequested = useMemo(
        () => new URLSearchParams(location.search).get('autoplay') === '1',
        [location.search]
    );
    // Watch Together: when ?party=CODE is in the URL we are a party
    // member.  We force the JS Player path (instead of the native
    // libVLC Activity) so the Player can pipe play/pause/seek
    // events through the party WebSocket, and we treat the title
    // as autoplay regardless of the user's Autoplay 1080p setting.
    const partyCode = useMemo(
        () => new URLSearchParams(location.search).get('party') || '',
        [location.search]
    );
    const partyAtMs = useMemo(
        () => new URLSearchParams(location.search).get('at_ms') || '',
        [location.search]
    );
    const partyPositionMs = useMemo(
        () => new URLSearchParams(location.search).get('position_ms') || '',
        [location.search]
    );
    /* Watch Together for TV shows — when a party host picks an
       episode, the URL carries `season` + `episode` numbers so we
       know exactly which episode to autoplay.  Without these the
       series detail page bails out of autoplay (the manual episode
       picker is the right UX for the non-party flow). */
    const partySeason = useMemo(
        () => new URLSearchParams(location.search).get('season') || '',
        [location.search]
    );
    const partyEpisode = useMemo(
        () => new URLSearchParams(location.search).get('episode') || '',
        [location.search]
    );
    /* Episode autoplay (NON-party flow) — set when the native
     * player pings us via SharedPreferences after a "Next Episode"
     * tap.  Triggers the same series autoplay path as Watch
     * Together but without WebSocket / party context. */
    const episodeAutoplayRequested = useMemo(
        () => new URLSearchParams(location.search).get('episodeAutoplay') === '1',
        [location.search]
    );
    /* Episode focus (NON-party flow) — set when the native player
     * pings us after EndReached so the user lands on the episode
     * picker scrolled to the next episode.  No autoplay. */
    const focusSeason = useMemo(
        () => new URLSearchParams(location.search).get('focusSeason') || '',
        [location.search]
    );
    const focusEpisode = useMemo(
        () => new URLSearchParams(location.search).get('focusEpisode') || '',
        [location.search]
    );

    const [meta, setMeta] = useState(null);
    const [streams, setStreams] = useState([]);
    const [diagnostics, setDiagnostics] = useState([]);
    const [loading, setLoading] = useState(true);
    const [streamLoading, setStreamLoading] = useState(true);
    const [err, setErr] = useState(null);
    const [copied, setCopied] = useState(null);

    /* ------------------------------------------------------------------
     * WATCH-TOGETHER · ROLE + WS COORDINATION (Detail page)
     *
     * The party WS *also* opens here in Detail (not just in Player /
     * VlcPlayerActivity) for one critical reason: HOST and GUEST must
     * watch the EXACT same file URL.
     *
     * Without this:
     *   host fetched streams → picked URL A (Plex direct).
     *   guest fetched streams → picked URL B (slow torrent).
     *   guest's libVLC could never reach `ready` → server hangs in
     *   `loading` forever → BOTH members spin a buffering wheel
     *   indefinitely.
     *
     * The new contract:
     *   HOST  — picks the best stream from the resolved list, sends
     *           a `stream` message over THIS Detail WS, then launches
     *           the native / web player.
     *   GUEST — skips its own stream fetch.  Sits on the joining
     *           screen.  Waits for an inbound `state` payload with
     *           `stream.url` set, then launches the player using
     *           the HOST's URL.
     * ------------------------------------------------------------------ */
    const partyRole = useMemo(() => {
        if (!partyCode) return '';
        try {
            return sessionStorage.getItem('vesper-party-role') || 'guest';
        } catch { return 'guest'; }
    }, [partyCode]);
    const isPartyHost = !!partyCode && partyRole === 'host';
    const isPartyGuest = !!partyCode && partyRole === 'guest';

    const partyDetailWsRef = useRef(null);
    const [partyDetailState, setPartyDetailState] = useState(null);

    useEffect(() => {
        if (!partyCode) return undefined;
        let memberId = '';
        try { memberId = sessionStorage.getItem('vesper-party-member-id') || ''; }
        catch { /* private mode */ }
        const wsBase = (process.env.REACT_APP_BACKEND_URL || window.location.origin)
            .replace(/^http/, 'ws');
        const ws = new WebSocket(`${wsBase}/api/watch-party/ws/${partyCode}`);
        partyDetailWsRef.current = ws;
        partyBreadcrumb('detail:ws-connect', { partyCode, role: partyRole });
        ws.onopen = () => {
            partyBreadcrumb('detail:ws-open', { partyCode, role: partyRole });
            try {
                ws.send(JSON.stringify({
                    type: 'hello',
                    role: partyRole,
                    member_id: memberId || undefined,
                    name: 'Detail',
                    avatar: 'a1',
                }));
            } catch { /* ignore */ }
        };
        ws.onmessage = (e) => {
            let msg; try { msg = JSON.parse(e.data); } catch { return; }
            if (msg.type === 'joined') {
                if (msg.member_id) {
                    try { sessionStorage.setItem('vesper-party-member-id', msg.member_id); }
                    catch { /* ignore */ }
                }
                return;
            }
            if (msg.type === 'state') {
                setPartyDetailState(msg);
            }
        };
        ws.onclose = () => {
            partyBreadcrumb('detail:ws-close', { partyCode });
        };
        ws.onerror = () => {
            partyBreadcrumb('detail:ws-error', { partyCode });
        };
        return () => {
            try { ws.close(); } catch { /* ignore */ }
            partyDetailWsRef.current = null;
        };
    }, [partyCode, partyRole]);

    useEffect(() => {
        let cancel = false;
        (async () => {
            setLoading(true);
            setErr(null);
            try {
                const m = await Vesper.getMeta(type, id);
                if (!cancel) setMeta(m?.data?.meta || null);
            } catch (e) {
                if (!cancel) setErr(e?.response?.data?.detail || 'Metadata unavailable');
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, id]);

    /* Resolve IMDB id → TMDB id so the Cast row can hit TMDB. */
    const [tmdbInfo, setTmdbInfo] = useState(null);
    useEffect(() => {
        let cancel = false;
        if (!id || !id.startsWith('tt')) {
            setTmdbInfo(null);
            return undefined;
        }
        (async () => {
            try {
                const r = await fetch(
                    `${process.env.REACT_APP_BACKEND_URL}/api/tmdb/find-by-imdb/${id}`,
                    { cache: 'force-cache' }
                );
                const data = await r.json();
                if (!cancel && data?.tmdb_id) {
                    setTmdbInfo({
                        tmdb_id: data.tmdb_id,
                        media_type: data.media_type,
                    });
                }
            } catch {
                /* swallow — Cast row stays hidden if tmdbInfo is null. */
            }
        })();
        return () => { cancel = true; };
    }, [id]);

    /* Focused actor (Cast row) — when an actor card is focused, the
       hero swaps from movie title + synopsis to actor name + bio. */
    const [focusedActor, setFocusedActor] = useState(null);
    /* Focused FILMOGRAPHY item — when the user has drilled into an
       actor's other work and is browsing it, this holds the focused
       film/TV title.  Hero + backdrop swap to that title. */
    const [focusedMovie, setFocusedMovie] = useState(null);
    /* CastRow's current view — 'cast' | 'filmography' | 'similar'.
       Drives the Play-button-area hint + back-nav. */
    const [castView, setCastView] = useState('cast');
    /* For TV series: true once user has revealed episodes (OK on
       a season pill).  When true we hide the Cast row. */
    const [seriesEpisodesShown, setSeriesEpisodesShown] = useState(false);

    /* Trailer popup state — when set, opens the TrailerModal.
       We fetch only on demand (avoid wasting a TMDB call). */
    const [trailerKey, setTrailerKey] = useState(null);
    const [trailerLoading, setTrailerLoading] = useState(false);
    const trailerCacheRef = useRef({});
    const openTrailer = useCallback(async () => {
        if (!tmdbInfo?.tmdb_id) return;
        const key = `${tmdbInfo.media_type}:${tmdbInfo.tmdb_id}`;
        if (trailerCacheRef.current[key]) {
            setTrailerKey(trailerCacheRef.current[key]);
            return;
        }
        try {
            setTrailerLoading(true);
            const r = await fetch(
                `${process.env.REACT_APP_BACKEND_URL}/api/tmdb/trailer/` +
                `${tmdbInfo.media_type}/${tmdbInfo.tmdb_id}`
            );
            const j = await r.json();
            const k = j?.data?.key;
            trailerCacheRef.current[key] = k || '';
            if (k) setTrailerKey(k);
        } catch { /* swallow */ } finally {
            setTrailerLoading(false);
        }
    }, [tmdbInfo]);

    /* When the user lands on Detail via the Upcoming-Movies trailer
     * card (which appends `?autoplay-trailer=1` to the URL), fire
     * the TrailerModal automatically as soon as tmdbInfo is ready.
     * Guarded by a ref so the trailer only opens once per visit
     * even if tmdbInfo re-runs (e.g. focused-actor refresh). */
    const trailerAutoFiredRef = useRef(false);
    useEffect(() => {
        if (trailerAutoFiredRef.current) return;
        if (!tmdbInfo?.tmdb_id) return;
        try {
            const hash = window.location.hash || '';
            const qIdx = hash.indexOf('?');
            const query = qIdx >= 0 ? hash.slice(qIdx + 1) : '';
            const params = new URLSearchParams(query);
            if (params.get('autoplay-trailer') === '1') {
                trailerAutoFiredRef.current = true;
                openTrailer();
            }
        } catch { /* ignore */ }
    }, [tmdbInfo, openTrailer]);
    /* Bio cache so we don't refetch the same person when focus
     * sweeps left/right.  Keyed by TMDB person id. */
    const actorBioCacheRef = useRef(new Map());
    const [focusedBio, setFocusedBio] = useState('');
    const [focusedAge, setFocusedAge] = useState('');
    const [focusedBirthplace, setFocusedBirthplace] = useState('');

    useEffect(() => {
        if (!focusedActor || !focusedActor.id) {
            setFocusedBio('');
            setFocusedAge('');
            setFocusedBirthplace('');
            return undefined;
        }
        const cache = actorBioCacheRef.current;
        const personId = focusedActor.id;
        if (cache.has(personId)) {
            const cached = cache.get(personId);
            setFocusedBio(cached.bio);
            setFocusedAge(cached.age);
            setFocusedBirthplace(cached.birthplace);
            return undefined;
        }
        let cancel = false;
        (async () => {
            try {
                const res = await fetch(
                    `${process.env.REACT_APP_BACKEND_URL}/api/tmdb/person/${personId}`
                );
                if (!res.ok) return;
                const data = await res.json();
                if (cancel) return;
                const entry = {
                    bio: (data?.biography || '').trim(),
                    age: data?.age != null ? String(data.age) : '',
                    birthplace: data?.place_of_birth || '',
                };
                cache.set(personId, entry);
                if (focusedActor && focusedActor.id === personId) {
                    setFocusedBio(entry.bio);
                    setFocusedAge(entry.age);
                    setFocusedBirthplace(entry.birthplace);
                }
            } catch { /* ignore — bio stays empty */ }
        })();
        return () => { cancel = true; };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [focusedActor?.id]);

    useEffect(() => {
        if (type === 'series') {
            // Series streams are fetched per-episode inside <SeriesEpisodes>
            setStreamLoading(false);
            return;
        }
        if (isPartyGuest) {
            // Guest never fetches its own streams — it uses the
            // host's chosen URL broadcast via the party WS.  This
            // prevents host/guest desync from picking different
            // streams (the #1 cause of stuck-loading hangs).
            setStreamLoading(false);
            setStreams([]);
            return;
        }
        let cancel = false;
        (async () => {
            setStreamLoading(true);
            if (partyCode) partyBreadcrumb('streams:fetch-start', { type, id });
            try {
                // v2.7.30 — render streams the SECOND backend cache hits,
                // then top-up with browser-direct results when ready.
                const onPartial = (partial) => {
                    if (cancel) return;
                    if (Array.isArray(partial) && partial.length > 0) {
                        setStreams(partial);
                        if (partyCode) {
                            partyBreadcrumb('streams:partial', { count: partial.length });
                        }
                    }
                };
                const s = await Vesper.getStreams(type, id, onPartial);
                if (!cancel) {
                    setStreams(s?.streams || []);
                    setDiagnostics(s?.diagnostics || []);
                    if (partyCode) {
                        partyBreadcrumb('streams:fetch-done', {
                            count: (s?.streams || []).length,
                        });
                    }
                    /* HOST: if no streams found at all, broadcast
                       `stream_error` so the guest doesn't spin
                       on the joining screen forever. */
                    if (isPartyHost && (s?.streams || []).length === 0 && type === 'movie') {
                        const ws = partyDetailWsRef.current;
                        if (ws && ws.readyState === 1) {
                            try {
                                ws.send(JSON.stringify({
                                    type: 'stream_error',
                                    reason: 'no_streams',
                                }));
                                partyBreadcrumb('host:sent-stream-error', { reason: 'no_streams' });
                            } catch { /* ignore */ }
                        }
                    }
                }
            } catch (e) {
                if (!cancel) {
                    setStreams([]);
                    setDiagnostics([]);
                    if (partyCode) partyBreadcrumb('streams:fetch-error', { err: String(e).slice(0, 200) });
                }
            } finally {
                if (!cancel) setStreamLoading(false);
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, id, partyCode, isPartyGuest, isPartyHost]);

    // When streams arrive, land focus on the first stream so that
    // pressing Down on the D-pad selects the next stream (not the
    // recommendations rail below).  Only run if focus is still on
    // something above the stream list (the hero / Play button) so
    // we don't yank focus away if the user has already manually
    // moved to a different stream.
    useEffect(() => {
        if (type !== 'movie') return;
        if (streamLoading || streams.length === 0) return;
        const t = setTimeout(() => {
            const first = document.querySelector('[data-testid="stream-0"]');
            if (!first) return;
            // Bail if the user has already moved into the stream
            // list or the stream picker is no longer visible.
            const active = document.activeElement;
            const list = document.querySelector('[data-testid="stream-list"]');
            if (list && list.contains(active)) return;
            try { first.focus({ preventScroll: true }); } catch (e) { /* ignore */ }
            first.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== first) el.removeAttribute('data-focused');
                });
        }, 220);
        return () => clearTimeout(t);
    }, [streamLoading, streams.length, type]);

    // List-scoped D-pad override.  When focus is INSIDE the stream
    // list, pressing Up/Down walks to the previous/next stream
    // button in DOM order — never escapes to whatever happens to be
    // geometrically nearby (Back button, library pill, etc.).  At
    // the top/bottom edge the handler bails so the global spatial
    // focus picks up.  Capture phase so it beats useSpatialFocus.
    useEffect(() => {
        const onKey = (e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            const list = document.querySelector('[data-testid="stream-list"]');
            if (!list) return;
            const active = document.activeElement;
            if (!active || !list.contains(active)) return;
            // Only the main "play this stream" buttons are part of
            // the row sequence; the small inline copy-magnet button
            // sits beside its parent and is handled by Left/Right.
            const items = Array.from(
                list.querySelectorAll('button[data-testid^="stream-"]')
            ).filter((el) => !el.hasAttribute('disabled'));
            if (items.length === 0) return;
            // If the user is on a copy-magnet, treat it as living
            // on the same row as its sibling stream button.
            let idx = items.indexOf(active);
            if (idx === -1) {
                const parentLi = active.closest('li');
                if (!parentLi) return;
                const sibling = parentLi.querySelector(
                    'button[data-testid^="stream-"]'
                );
                if (!sibling) return;
                idx = items.indexOf(sibling);
                if (idx === -1) return;
            }
            const nextIdx = e.key === 'ArrowDown' ? idx + 1 : idx - 1;
            if (nextIdx < 0 || nextIdx >= items.length) return; // edge — fall through
            e.preventDefault();
            e.stopPropagation();
            const next = items[nextIdx];
            try { next.focus({ preventScroll: true }); } catch (err) { /* ignore */ }
            next.setAttribute('data-focused', 'true');
            document
                .querySelectorAll('[data-focused="true"]')
                .forEach((el) => {
                    if (el !== next) el.removeAttribute('data-focused');
                });
            /* No scrollIntoView call here — the Detail page is
             * fixed-layout (hero locked, single bottom lane).
             * Moving focus between cast tiles must NEVER trigger
             * any scrolling on the page itself. */
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, []);

    /* D-pad navigation between Play CTA ↔ Cast row.
     *
     *  Play ─DOWN→ first cast actor (focusedActor set → hero swap)
     *  Cast actor ─UP→ Play CTA (focusedActor cleared → hero restored)
     *
     * Page never scrolls.  Hero stays anchored.  Cast row stays
     * anchored at the bottom.  Same pattern as the home screen. */
    useEffect(() => {
        const focusPlay = () => {
            // Clear focusedActor FIRST so the Play CTA re-renders
            // (it's conditionally hidden while an actor is focused).
            setFocusedActor(null);
            requestAnimationFrame(() => requestAnimationFrame(() => {
                let retries = 16;
                const tryFocus = () => {
                    /* Movies → Autoplay CTA.  TV series → active
                     * season pill (which becomes the "top of the
                     * page" focus target since there's no Autoplay
                     * button on series). */
                    const target =
                        document.querySelector('[data-testid^="detail-play-"]') ||
                        document.querySelector('[data-testid^="season-"][data-focusable="true"]');
                    if (target) {
                        try { target.focus({ preventScroll: true }); } catch { /* ignore */ }
                        target.setAttribute('data-focused', 'true');
                        document.querySelectorAll('[data-focused="true"]').forEach((el) => {
                            if (el !== target) el.removeAttribute('data-focused');
                        });
                        return;
                    }
                    if (--retries > 0) setTimeout(tryFocus, 50);
                };
                tryFocus();
            }));
        };
        const focusFirstActor = () => {
            requestAnimationFrame(() => {
                const target = document.querySelector('[data-testid^="cast-actor-"]');
                if (target) {
                    try { target.focus({ preventScroll: true }); } catch { /* ignore */ }
                }
            });
        };
        const onKey = (e) => {
            if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
            const active = document.activeElement;
            if (!active) return;
            const onPlay = active.matches('[data-testid^="detail-play-"]');
            const onSeasonPill = active.matches('[data-testid^="season-"][data-focusable="true"]');
            const onCast = active.matches('[data-testid^="cast-actor-"]');

            if (e.key === 'ArrowDown' && (onPlay || onSeasonPill)) {
                /* DOWN from Autoplay (movies) OR from any season
                 * pill (series) → focus first cast actor — but
                 * only if the cast row is currently mounted.  On
                 * the series "episodes shown" view the cast row
                 * is hidden, so we let the default spatial-focus
                 * handler route DOWN to the episode list. */
                const firstActor = document.querySelector('[data-testid^="cast-actor-"]');
                if (!firstActor) return;
                e.preventDefault();
                e.stopPropagation();
                focusFirstActor();
                return;
            }
            if (e.key === 'ArrowUp' && onCast) {
                e.preventDefault();
                e.stopPropagation();
                focusPlay();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, []);

    /* RESET ON NAVIGATION — when the user navigates from a
       filmography / similar card into a brand-new title's
       detail page, the same Detail component stays mounted
       (React Router just swaps params).  We need to:
        1. Clear focused-actor / focused-movie state so the hero
           doesn't show a stale actor/film from the previous
           page.
        2. Re-focus the Autoplay button so the user is back at
           the top of the navigation flow.
       Runs whenever `id` changes (initial mount included). */
    useEffect(() => {
        setFocusedActor(null);
        setFocusedMovie(null);
        setCastView('cast');
        setSeriesEpisodesShown(false);

        let cancelled = false;
        let preferredHit = false;
        let retries = 60;       // ~6 s — enough for slow remote titles

        const findCandidates = () => [
            document.querySelector('[data-testid^="detail-play-"]:not([disabled])'),
            document.querySelector('[data-focusable="true"][data-initial-focus="true"]'),
            document.querySelector('[data-testid^="season-pill-"]'),
            Array.from(document.querySelectorAll('[data-focusable="true"]'))
                .find((el) =>
                    !el.disabled &&
                    !el.closest('[data-testid="side-nav"]') &&
                    !el.closest('[data-testid="kids-side-nav"]') &&
                    el.getBoundingClientRect().width > 0
                ),
        ];
        const tryFocus = () => {
            if (cancelled) return;
            const target = findCandidates().find(Boolean);
            if (target) {
                try { target.focus({ preventScroll: true }); } catch { /* ignore */ }
                target.setAttribute('data-focused', 'true');
                document.querySelectorAll('[data-focused="true"]').forEach((el) => {
                    if (el !== target) el.removeAttribute('data-focused');
                });
                if (target.matches('[data-testid^="detail-play-"]')) {
                    preferredHit = true;
                    return;
                }
                /* For TV series (no Autoplay button) we settle
                 * on the season pill and STOP retrying.  This
                 * prevents the late-arrival watcher (or further
                 * retries) from stealing focus back to the
                 * season pill while the user is mid-navigation. */
                if (target.matches('[data-testid^="season-"]')) {
                    preferredHit = true;
                    return;
                }
            }
            if (--retries > 0) setTimeout(tryFocus, 100);
        };
        const start = setTimeout(tryFocus, 60);

        /* Late-arrival watcher: only relevant for MOVIE pages
         * where the Autoplay button mounts asynchronously after
         * streams resolve.  We additionally check that focus has
         * NOT moved to a user-driven target (cast actor, episode,
         * similar card etc.) — if it has, we let the user keep
         * their place and stop watching. */
        const watcher = setInterval(() => {
            if (cancelled || preferredHit) return;
            const ae = document.activeElement;
            const userMoved =
                ae && (
                    ae.matches('[data-testid^="cast-actor-"]') ||
                    ae.matches('[data-testid^="cast-film-"]') ||
                    ae.matches('[data-testid^="cast-similar-"]') ||
                    ae.matches('[data-testid^="episode-"]') ||
                    ae.matches('[data-testid^="season-"]')
                );
            if (userMoved) {
                preferredHit = true;
                return;
            }
            const play = document.querySelector(
                '[data-testid^="detail-play-"]:not([disabled])'
            );
            if (play) {
                try { play.focus({ preventScroll: true }); } catch { /* ignore */ }
                play.setAttribute('data-focused', 'true');
                document.querySelectorAll('[data-focused="true"]').forEach((el) => {
                    if (el !== play) el.removeAttribute('data-focused');
                });
                preferredHit = true;
            }
        }, 200);
        const stopWatcher = setTimeout(() => clearInterval(watcher), 4000);

        return () => {
            cancelled = true;
            clearTimeout(start);
            clearInterval(watcher);
            clearTimeout(stopWatcher);
        };
    }, [id]);

    // ---------- AUTOPLAY 1080p — derived state ----------
    // `autoplayEnabled` reflects the live preference.  We read it
    // through state so a toggle from the side-nav re-renders Detail
    // (cross-component pref sync via storage event, see below).
    const [autoplayEnabled, setAutoplayEnabledState] = useState(
        isKidsActive() || getAutoplay1080p()
    );
    useEffect(() => {
        const onStorage = () =>
            setAutoplayEnabledState(isKidsActive() || getAutoplay1080p());
        window.addEventListener('storage', onStorage);
        // Also poll once per second — the side-nav toggle writes to
        // localStorage in the SAME window which doesn't fire `storage`
        // (that event only fires for OTHER windows).
        const i = setInterval(onStorage, 1000);
        return () => {
            window.removeEventListener('storage', onStorage);
            clearInterval(i);
        };
    }, []);

    // Pick the best 1080p candidate from the resolved streams list.
    // We prefer direct mode + explicit 1080p label, but will fall back
    // to anything that even mentions "1080" — UNLESS it's 4K-labelled
    // (some Plex titles tag both "1080" and "4K" in the same string;
    // we'd rather wait than misfire into a too-large stream).
    const autoplayCandidate = useMemo(() => {
        if (type !== 'movie') return null;
        if (!streams || streams.length === 0) return null;
        const non4k = streams.filter((s) => !is4K(s));
        return (
            non4k.find(
                (s) => streamMode(s) === 'direct' && is1080p(s)
            ) ||
            non4k.find((s) => is1080p(s)) ||
            null
        );
    }, [streams, type]);

    // PARTY-MODE fallback: when the user is in a Watch Together
    // session we MUST start *something* — getting stuck on the
    // picker desyncs the party.  If no 1080p-labelled stream is
    // found, pick the best available: first direct stream → first
    // magnet → first anything.  Only used in party mode; regular
    // Autoplay-1080p users still see the picker when no 1080p is
    // present (that's by design).
    const partyAutoplayCandidate = useMemo(() => {
        if (type !== 'movie') return null;
        if (!partyCode) return null;
        if (!streams || streams.length === 0) return null;
        if (autoplayCandidate) return autoplayCandidate;
        return (
            streams.find((s) => streamMode(s) === 'direct') ||
            streams.find((s) => streamMode(s) === 'torrent') ||
            streams[0] ||
            null
        );
    }, [streams, type, partyCode, autoplayCandidate]);

    // Manual trigger for the on-page Play button.
    const triggerAutoplay = () => {
        if (autoplayCandidate) {
            playStream(autoplayCandidate);
            return;
        }
        /* v2.6.87 — if there's no candidate it means streams loading
         * finished and there's literally nothing playable (no addon
         * has this title yet — usually a brand-new TMDB release).
         * Replace the silent dead-button-state with a cinematic
         * "Coming Soon" modal that offers the user to add it to
         * their notify list. */
        if (!streamLoading && (!streams || streams.length === 0)) {
            setShowUnavailableModal(true);
        }
    };

    const [showUnavailableModal, setShowUnavailableModal] = useState(false);

    /* v2.7.20 — Track the LAST stream the user chose (or that
     * autoplay picked).  Used to:
     *   (a) Visually mark "CURRENT" on that stream's row in the
     *       Available-streams picker so the user can tell which
     *       stream is in the player right now.
     *   (b) Diagnose whether a specific stream is broken vs the
     *       player config — they can pick a different stream
     *       without leaving the page.
     * Stored in sessionStorage keyed by detail-page id so it
     * survives a player-launch round-trip without polluting
     * cross-session state. */
    const lastStreamKey = `onnowtv-last-stream:${id}`;
    const [lastStreamIdx, setLastStreamIdx] = useState(() => {
        try {
            const raw = sessionStorage.getItem(lastStreamKey);
            return raw == null ? null : Number(raw);
        } catch { return null; }
    });

    /* v2.7.22 — Stream picker modal state.  Opened by the
     * "Choose stream" CTA (Autoplay OFF, movie type).  When open
     * it renders <StreamPickerModal/> centred on the screen with
     * the streams list; auto-focuses stream-0; D-pad scrolls;
     * OK plays; Back closes. */
    const [showStreamPicker, setShowStreamPicker] = useState(false);
    const openStreamPicker = React.useCallback(() => {
        setShowStreamPicker(true);
    }, []);
    const closeStreamPicker = React.useCallback(() => {
        setShowStreamPicker(false);
    }, []);
    const handleStreamPick = React.useCallback(
        (stream) => {
            setShowStreamPicker(false);
            playStream(stream);
        },
        // eslint-disable-next-line react-hooks/exhaustive-deps
        []
    );

    /* AUTO-SHOW "Coming soon" modal as soon as stream-loading
     * resolves with zero playable streams — no Play click needed.
     * Previously the user had to tap Play to see this CTA, which
     * meant they sat on a Detail page with an inert button.
     * Skip in series mode (per-episode flow handles its own modal)
     * and skip when StreamUnavailableModal was already dismissed
     * once for this item (so re-renders don't re-open it after the
     * user dismissed it). */
    const unavailableSeenRef = React.useRef(false);
    useEffect(() => {
        if (type === 'series') return;
        if (streamLoading) return;
        if (streams && streams.length > 0) return;
        if (unavailableSeenRef.current) return;
        unavailableSeenRef.current = true;
        setShowUnavailableModal(true);
    }, [streamLoading, streams, type]);

    // ---------- AUTOPLAY (via ?autoplay=1 URL) ----------
    // Fires automatically when the user arrived via hero Play / a
    // CW resume etc.  Falls back silently to the manual picker if
    // no 1080p candidate is found.  In PARTY mode the fallback is
    // more aggressive: pick whatever streams are available so the
    // party doesn't desync on the picker.
    //
    // Hybrid ref+state pattern.  The REF is the synchronous "already
    // fired" guard — it doesn't trigger re-renders or cleanup races,
    // so the setTimeout(playStream, 30) inside the effect can never
    // be cancelled by its own state-update.  The STATE drives the
    // JOINING-WATCH-PARTY overlay render so React always knows when
    // to hide it.  Previously a ref-only impl would leave the
    // overlay visible because React doesn't watch refs, and a
    // state-only impl would cancel its own pending playStream via
    // cleanup as soon as `setAutoplayFired(true)` ran.
    const autoplayFiredRef = React.useRef(false);
    const [autoplayFired, setAutoplayFired] = useState(false);

    // ---------- PARTY AUTOPLAY (the bulletproof path) ----------
    // The dedicated useEffect for parties.  Watches for:
    //   • partyCode set (URL has ?party=CODE)
    //   • autoplay=1 (the WatchTogether nav always passes this)
    //   • streams have loaded (streamLoading is false)
    // and ALWAYS plays the best available stream.  No 1080p check,
    // no user-preference check, no fallback to "show picker".  In a
    // party, we MUST start something — staying on the Detail page
    // desyncs the room.  Runs in addition to the regular autoplay
    // useEffect so the older path still works for non-party uses;
    // the autoplayFiredRef guard prevents double-firing.
    //
    // NOTE: Guests skip this entirely — they wait for the host's
    // stream URL to arrive over the party WS instead (see the
    // guest-stream-receiver useEffect below).
    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!partyCode) return;
        if (!isPartyHost) return; // guests don't pick — they wait
        if (!autoplayRequested) return;
        if (type === 'series') return; // series still uses per-episode flow
        if (streamLoading) return;
        if (!streams || streams.length === 0) return;
        // STRICTLY filter out 4K — user reported buffering in
        // parties was caused by accidentally picking 4K streams that
        // the HK1 box's decoder can't keep up with.  No fallback —
        // if every stream is 4K we send `stream_error` so guests
        // bail gracefully instead of suffering a buffer-storm.
        const non4k = streams.filter((s) => !is4K(s));
        if (non4k.length === 0) {
            partyBreadcrumb('party-autoplay:all-4k-bail', {
                partyCode,
                total: streams.length,
            });
            const ws = partyDetailWsRef.current;
            if (ws && ws.readyState === 1) {
                try {
                    ws.send(JSON.stringify({
                        type: 'stream_error',
                        reason: 'only_4k_available',
                    }));
                } catch { /* ignore */ }
            }
            return;
        }
        const pool = non4k;
        // Pick the best stream: prefer 1080p direct → any 1080p →
        // first direct → first torrent → first anything.  Pool is
        // already guaranteed 4K-free.
        const pick =
            pool.find((s) => streamMode(s) === 'direct' && is1080p(s)) ||
            pool.find((s) => is1080p(s)) ||
            pool.find((s) => streamMode(s) === 'direct') ||
            pool.find((s) => streamMode(s) === 'torrent') ||
            pool[0];
        if (!pick) return;
        partyBreadcrumb('party-autoplay:fire', { partyCode, mode: streamMode(pick), name: pick.name });
        autoplayFiredRef.current = true;
        // Defer one tick so React has time to commit the streams list
        // before we navigate / launch the native player.  Use a
        // window timer (not a cleanup-tracked timeout) so a state
        // change triggered by setAutoplayFired can't cancel it.
        // NOTE: We do NOT call setAutoplayFired(true) before navigate
        // anymore — doing so would unmount the joining screen for
        // ~30ms revealing the picker (the "split-second flash" the
        // user reported).  Instead the joining screen stays mounted
        // until the page itself unmounts on navigate.
        window.setTimeout(() => playStream(pick), 30);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [partyCode, isPartyHost, autoplayRequested, streams, streamLoading, type]);

    // ---------- PARTY AUTOPLAY WATCHDOG ----------
    // Safety net: if for ANY reason the partyAutoplay useEffect above
    // didn't run within 5 seconds of streams loading (React batching
    // edge case, stale-closure, hot-reload, etc.), re-attempt the
    // pick + playStream here.  This is the difference between "guest
    // sat on the picker waiting" and "guest's player launched".
    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!partyCode) return;
        if (!isPartyHost) return; // guests are handled separately
        if (!autoplayRequested) return;
        if (type === 'series') return;
        if (streamLoading) return;
        if (!streams || streams.length === 0) return;
        const watchdog = setTimeout(() => {
            if (autoplayFiredRef.current) return;
            // STRICTLY non-4K — same hard rule as the primary picker.
            const non4k = streams.filter((s) => !is4K(s));
            if (non4k.length === 0) {
                partyBreadcrumb('party-autoplay:watchdog-all-4k-bail', { partyCode });
                return;
            }
            const pool = non4k;
            const pick =
                pool.find((s) => streamMode(s) === 'direct' && is1080p(s)) ||
                pool.find((s) => is1080p(s)) ||
                pool.find((s) => streamMode(s) === 'direct') ||
                pool.find((s) => streamMode(s) === 'torrent') ||
                pool[0];
            if (!pick) return;
            partyBreadcrumb('party-autoplay:watchdog-fire', { partyCode });
            autoplayFiredRef.current = true;
            playStream(pick);
        }, 5000);
        return () => clearTimeout(watchdog);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [partyCode, isPartyHost, autoplayRequested, streams, streamLoading, type]);

    // ---------- PARTY GUEST · STREAM RECEIVER ----------
    // Critical: this is what eliminates the "host & guest played
    // different files" desync.  Guests don't fetch streams.  They
    // sit on the joining screen and wait for the HOST to broadcast
    // its chosen `stream.url` over the party WS.  Once received,
    // guest launches its player with the SAME url.
    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!isPartyGuest) return;
        const stream = partyDetailState?.stream;
        if (!stream || !stream.url) return;
        // Wait for meta so we can pass title/poster/etc to player.
        // If meta hasn't arrived in 6s, fall through anyway — better
        // a less-polished launch than an infinite wait.
        autoplayFiredRef.current = true;
        partyBreadcrumb('guest:received-stream', {
            urlPrefix: String(stream.url).slice(0, 50),
            title: stream.title,
            type: stream.type,
        });
        const playTitle = stream.title || meta?.name || 'Now Playing';
        const playType = stream.type || type;
        const playImdb = stream.imdb_id || stream.cw_id || id;
        const positionMs = Number(stream.position_ms) || Number(partyPositionMs) || 0;
        const wsBase = (process.env.REACT_APP_BACKEND_URL || window.location.origin)
            .replace(/^http/, 'ws');
        const partyWsUrl = `${wsBase}/api/watch-party/ws/${partyCode}`;
        let memberId = '';
        try { memberId = sessionStorage.getItem('vesper-party-member-id') || ''; }
        catch { /* ignore */ }
        // Try native libVLC player first (HK1 box).  Falls through
        // to JS Player.jsx in the WebView for preview / desktop.
        const _profile = getActiveProfile() || {};
        const _avatarEmoji = avatarEmojiById(_profile.avatarId);
        if (Host.playVideo({
            url: stream.url,
            title: playTitle,
            type: playType,
            subtitleUrl: stream.subtitle_url || '',
            // v2.7.30 — TMDB metadata wins over stream-attached art.
            // Some Stremio addons embed low-res / wrong thumbs in the
            // stream payload; TMDB's poster/backdrop is always the
            // authoritative cinematic art for the title.
            poster: meta?.poster || stream.poster || '',
            backdrop: meta?.background || meta?.poster || stream.backdrop || '',
            synopsis: meta?.description || stream.synopsis || '',
            year: stream.year || meta?.releaseInfo || '',
            rating: stream.rating || meta?.imdbRating || '',
            runtime: stream.runtime || meta?.runtime || '',
            genres: meta?.genres || [],
            startAtMs: positionMs,
            cwId: playImdb,
            partyCode,
            partyRole: 'guest',
            partyMemberId: memberId || undefined,
            partyWsUrl,
            partyAvatarEmoji: _avatarEmoji,
            partyDisplayName: _profile.name || 'Guest',
        })) {
            partyBreadcrumb('guest:native-launched', {});
            /* Same back-leak fix as the host path — navigate the
             * WebView home so VLC's BACK doesn't reveal the React
             * surface still mounted underneath. */
            if (Host.isAndroid) {
                try { navigate('/'); } catch { /* ignore */ }
            }
            return;
        }
        // Web fallback
        const partyQuery = `&party=${encodeURIComponent(partyCode)}&at_ms=${encodeURIComponent(partyAtMs)}&position_ms=${positionMs}`;
        partyBreadcrumb('guest:web-fallback', {});
        navigate(
            `/play?url=${encodeURIComponent(stream.url)}` +
            `&title=${encodeURIComponent(playTitle)}` +
            `&type=${encodeURIComponent(playType)}` +
            `&imdbId=${encodeURIComponent(playImdb)}${partyQuery}`
        );
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [isPartyGuest, partyDetailState, meta, type, id, partyCode, partyAtMs, partyPositionMs, navigate]);

    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!autoplayRequested) return;
        if (type === 'series') return; // series uses per-episode flow
        if (streamLoading) return;
        if (partyCode) return; // handled by the dedicated party useEffect above
        /* If streams finished loading and there's literally nothing
         * playable, surface the cinematic "Coming Soon" modal so the
         * user can add the title to their notify list — same as the
         * manual Play button path.  This catches the autoplay flow
         * (poster-tap → Detail with ?autoplay=1) which used to fall
         * silently to the regular Detail layout when no candidate
         * was found, leaving the user staring at a movie page with
         * an inert Play button instead of an actionable prompt. */
        if (!streams || streams.length === 0) {
            setShowUnavailableModal(true);
            return;
        }
        // Non-party autoplay still requires the user's preference + a
        // 1080p stream — that's by design.
        if (!getAutoplay1080p()) return;
        if (!autoplayCandidate) return;
        autoplayFiredRef.current = true;
        setAutoplayFired(true);
        window.setTimeout(() => playStream(autoplayCandidate), 0);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [streams, streamLoading, autoplayRequested, type, autoplayCandidate, partyCode]);

    /* ---------- PARTY AUTOPLAY for TV SERIES ----------
     * When the host picks a TV show in Watch Together and selects a
     * specific season+episode, the URL carries ?party=…&season=S&
     * episode=E&autoplay=1.  Fetches the episode's streams and fires
     * the best one, mirroring the movie party-autoplay path.  Skips
     * the user's Autoplay-1080p preference and the 4K-only fallback
     * — same logic as the movie path. */
    const seriesPartyFiredRef = React.useRef(false);
    useEffect(() => {
        if (seriesPartyFiredRef.current) return;
        if (autoplayFiredRef.current) return;
        // Trigger if EITHER party autoplay OR direct episode autoplay
        // is requested.  Both flows need the same behaviour: pick the
        // best stream for a specific season+episode and play it.
        const isParty = !!partyCode && autoplayRequested;
        const isDirect = episodeAutoplayRequested && !partyCode;
        if (!isParty && !isDirect) return;
        // Guests don't fetch streams; they wait for host's URL.
        if (isPartyGuest) return;
        if (type !== 'series') return;
        const season = isParty ? partySeason : focusSeason;  // direct path also supplies via focusSeason but we use ?season= as a separate param? wait — we pass season=&episode= in the direct path too
        // The native MainActivity sends `?episodeAutoplay=1&season=&episode=`
        // — same param names as the party path, so partySeason /
        // partyEpisode are the source of truth in BOTH cases.
        if (!partySeason || !partyEpisode) return;
        if (!meta) return; // wait for the show metadata so the CW entry is rich
        seriesPartyFiredRef.current = true;
        autoplayFiredRef.current = true;
        setAutoplayFired(true);
        partyBreadcrumb('series-autoplay:fire', {
            party: !!partyCode,
            s: partySeason, e: partyEpisode,
        });
        (async () => {
            const videoId = `${id}:${partySeason}:${partyEpisode}`;
            try {
                const res = await Vesper.getStreams('series', videoId);
                const list = Array.isArray(res?.streams) ? res.streams : [];
                if (list.length === 0) {
                    seriesPartyFiredRef.current = false;
                    autoplayFiredRef.current = false;
                    setAutoplayFired(false);
                    // Tell the guests the host couldn't find streams.
                    if (isPartyHost) {
                        const ws = partyDetailWsRef.current;
                        if (ws && ws.readyState === 1) {
                            try {
                                ws.send(JSON.stringify({
                                    type: 'stream_error',
                                    reason: 'no_streams_for_episode',
                                }));
                            } catch { /* ignore */ }
                        }
                    }
                    return;
                }
                // STRICTLY filter out 4K — same hard rule as the
                // movie-party picker.  If every stream is 4K, bail
                // gracefully (broadcast stream_error and reset
                // firing flags so the user can try a different
                // episode without a buffering meltdown).
                const non4k = list.filter((s) => !is4K(s));
                if (non4k.length === 0) {
                    seriesPartyFiredRef.current = false;
                    autoplayFiredRef.current = false;
                    setAutoplayFired(false);
                    if (isPartyHost) {
                        const ws = partyDetailWsRef.current;
                        if (ws && ws.readyState === 1) {
                            try {
                                ws.send(JSON.stringify({
                                    type: 'stream_error',
                                    reason: 'only_4k_available_for_episode',
                                }));
                            } catch { /* ignore */ }
                        }
                    }
                    return;
                }
                const pool = non4k;
                const pick =
                    pool.find((s) => streamMode(s) === 'direct' && is1080p(s)) ||
                    pool.find((s) => is1080p(s)) ||
                    pool.find((s) => streamMode(s) === 'direct') ||
                    pool.find((s) => streamMode(s) === 'torrent') ||
                    pool[0];
                if (!pick) {
                    seriesPartyFiredRef.current = false;
                    autoplayFiredRef.current = false;
                    setAutoplayFired(false);
                    return;
                }
                await playStream(pick, {
                    cwId: videoId,
                    season: Number(partySeason),
                    episode: Number(partyEpisode),
                });
            } catch (_e) {
                seriesPartyFiredRef.current = false;
                autoplayFiredRef.current = false;
                setAutoplayFired(false);
            }
        })();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [partyCode, autoplayRequested, episodeAutoplayRequested, type, partySeason, partyEpisode, id, meta]);

    const playStream = async (stream, episodeOverride = null) => {
        const mode = streamMode(stream);
        /* v2.7.20 — Persist which stream the user is currently
         * playing so the picker can mark it as "CURRENT" on
         * return. */
        try {
            const idx = streams.findIndex((s) => s === stream);
            if (idx >= 0) {
                sessionStorage.setItem(lastStreamKey, String(idx));
                setLastStreamIdx(idx);
            }
        } catch { /* ignore */ }
        /* For series episodes coming from the party-autoplay path,
           use the composite videoId (`imdbId:season:episode`) for
           subtitles, CW key, and resume.  Movies pass null and
           everything works exactly like before. */
        const playId = episodeOverride?.cwId || id;
        if (mode === 'direct' || mode === 'torrent') {
            // Resolve the playable URL.  Direct streams already
            // carry a `url`; torrents are converted to a magnet:
            // URI which libVLC's bittorrent demuxer can ingest
            // natively (no external player needed).
            const playUrl =
                mode === 'direct'
                    ? stream.url
                    : buildMagnet(stream, meta?.name);
            if (!playUrl) return;
            // Look up any previously-saved position so we can resume.
            const cwList = cw.getEntries();
            const existing = cwList.find((e) => e.id === playId);
            const startAtMs =
                resumeRequested && existing?.positionMs
                    ? existing.positionMs
                    : 0;
            // Native libVLC Activity — handles every codec Stremio does.
            // Fetch a default English subtitle URL up front so the
            // native player can attach it on launch.
            let subtitleUrl = '';
            try {
                const r = await fetch(
                    `${API}/subtitles/${type}/${encodeURIComponent(playId)}`,
                    { cache: 'no-store' }
                );
                if (r.ok) {
                    const data = await r.json();
                    const list = Array.isArray(data?.subtitles)
                        ? data.subtitles
                        : [];
                    const eng = list.find((s) => /^en/i.test(s.lang || ''));
                    if (eng?.url) subtitleUrl = eng.url;
                }
            } catch {
                /* swallow — player still works without subs */
            }
            // Track / refresh the Continue Watching entry up front so
            // the show appears on Home even before the native player
            // reports any progress.
            const episodeLabel = episodeOverride
                ? `S${String(episodeOverride.season).padStart(2, '0')}E${String(episodeOverride.episode).padStart(2, '0')}`
                : '';
            cw.upsert({
                id: playId,
                type,
                title: meta?.name || '',
                episodeLabel,
                backdrop: meta?.background || meta?.poster || '',
                poster: meta?.poster || '',
                synopsis: meta?.description || '',
                year: meta?.releaseInfo || meta?.year || '',
                rating: meta?.imdbRating || '',
                runtime: meta?.runtime || '',
                genres: meta?.genres || [],
                streamUrl: playUrl,
                subtitleUrl,
                positionMs: existing?.positionMs || 0,
                durationMs: existing?.durationMs || 0,
                route: `/title/${type}/${id}`,
            });
            // Compute the WebSocket URL we want the native player
            // to open for sync.  Falls back to window.location.origin
            // so previews work too.
            const partyWsUrl = partyCode
                ? `${(process.env.REACT_APP_BACKEND_URL || window.location.origin).replace(/^http/, 'ws')}/api/watch-party/ws/${partyCode}`
                : '';
            const partyRoleLocal = partyCode
                ? (sessionStorage.getItem('vesper-party-role') || 'guest')
                : '';
            const partyMemberId = partyCode
                ? (sessionStorage.getItem('vesper-party-member-id') || '')
                : '';
            const playTitle = episodeOverride
                ? `${meta?.name || ''} · ${episodeLabel}`
                : (meta?.name || '');
            if (partyCode) {
                partyBreadcrumb('playStream:invoke', {
                    mode,
                    role: partyRoleLocal || '(none)',
                    memberId: partyMemberId ? 'present' : 'missing',
                    wsUrl: partyWsUrl ? 'present' : 'missing',
                });
            }
            // ----- WATCH-TOGETHER · HOST BROADCASTS THE STREAM URL -----
            // Critical: send the chosen stream URL to every party
            // member via the Detail WS BEFORE we navigate ourselves
            // away.  The server stashes it in `party.stream` and
            // broadcasts to the guest, whose Detail page is sitting
            // on the joining screen waiting for exactly this.
            //
            // Without this, the guest would have to run its own
            // stream resolution and might pick a different URL,
            // causing host/guest desync that hangs both members.
            if (partyCode && partyRoleLocal === 'host') {
                // Wait up to 3 s for the WS to reach OPEN state.
                // The host's Detail WS opens asynchronously on
                // mount; by the time streams have resolved it
                // usually already is, but the watchdog autoplay
                // path can fire before WS is settled.
                const waitForWsOpen = async () => {
                    const deadline = Date.now() + 3000;
                    while (Date.now() < deadline) {
                        const w = partyDetailWsRef.current;
                        if (w && w.readyState === 1) return w;
                        await new Promise((r) => setTimeout(r, 80));
                    }
                    return null;
                };
                const ws = await waitForWsOpen();
                if (ws) {
                    try {
                        ws.send(JSON.stringify({
                            type: 'stream',
                            payload: {
                                url: playUrl,
                                title: playTitle,
                                type,
                                imdb_id: playId,
                                cw_id: playId,
                                subtitle_url: subtitleUrl,
                                poster: meta?.poster || '',
                                backdrop: meta?.background || meta?.poster || '',
                                synopsis: meta?.description || '',
                                year: meta?.releaseInfo || meta?.year || '',
                                rating: meta?.imdbRating || '',
                                runtime: meta?.runtime || '',
                                season: episodeOverride?.season,
                                episode: episodeOverride?.episode,
                                episode_title: episodeOverride?.episode_title || '',
                                position_ms: Number(partyPositionMs) || startAtMs || 0,
                            },
                        }));
                        partyBreadcrumb('host:sent-stream', {
                            urlPrefix: String(playUrl).slice(0, 50),
                            title: playTitle,
                        });
                    } catch (e) {
                        partyBreadcrumb('host:sent-stream-error', {
                            err: String(e).slice(0, 120),
                        });
                    }
                    // Yield to the event loop so the WS send buffer
                    // actually flushes to the network before the
                    // page unmounts and the socket closes.  Without
                    // this, the close handshake can race the data
                    // frame and the guest never sees the stream URL.
                    await new Promise((r) => setTimeout(r, 150));
                } else {
                    partyBreadcrumb('host:stream-ws-timeout', { partyCode });
                }
            }
            if (
                Host.playVideo({
                    url: playUrl,
                    title: playTitle,
                    type: type,
                    subtitleUrl,
                    /* v2.7.28 — robust cover-art fallback chain.
                     * Different addons store backdrops under
                     * different keys.  Cinemeta uses `background`,
                     * TMDB addons use `backdrop`, some return only
                     * `poster`.  Walk the full chain so the
                     * loading screen ALWAYS has something to show. */
                    poster:
                        meta?.poster ||
                        meta?.posterUrl ||
                        meta?.poster_url ||
                        meta?.background ||
                        meta?.backdrop ||
                        '',
                    backdrop:
                        meta?.background ||
                        meta?.backdrop ||
                        meta?.backdrop_url ||
                        meta?.poster ||
                        '',
                    synopsis:
                        meta?.description ||
                        meta?.overview ||
                        meta?.synopsis ||
                        '',
                    year: meta?.releaseInfo || meta?.year || '',
                    rating: meta?.imdbRating || '',
                    runtime: meta?.runtime || '',
                    genres: meta?.genres || [],
                    startAtMs: partyCode
                        ? Number(partyPositionMs) || 0
                        : startAtMs,
                    cwId: playId,
                    partyCode: partyCode || undefined,
                    partyRole: partyRoleLocal || undefined,
                    partyMemberId: partyMemberId || undefined,
                    partyWsUrl: partyWsUrl || undefined,
                    partyAvatarEmoji: partyCode
                        ? avatarEmojiById((getActiveProfile() || {}).avatarId)
                        : undefined,
                    partyDisplayName: partyCode
                        ? ((getActiveProfile() || {}).name || (partyRoleLocal === 'host' ? 'Host' : 'Guest'))
                        : undefined,
                    // v2.7.25 — pass full streams list + the index
                    // we're about to play, so the native player can
                    // surface its in-player picker overlay.
                    streamsList: streams,
                    currentStreamIdx: streams.findIndex((s) => s === stream),
                })
            ) {
                if (partyCode) partyBreadcrumb('playStream:native-launched', {});
                /* v2.6.85 — back-leak fix.
                 *
                 * Native VLC takes over the surface immediately.  But
                 * the WebView (with Detail.jsx OR Player.jsx still
                 * mounted underneath) keeps running — its video el
                 * even keeps decoding the same stream in software!
                 * When the host presses BACK to leave VLC, the
                 * native Activity finishes and the previous WebView
                 * surface is revealed, briefly flashing the
                 * "duplicate" old-player UI before the user can hit
                 * BACK again.
                 *
                 * Routing home immediately fixes the leak: by the
                 * time VLC finishes, the WebView is on the home
                 * screen and there's nothing to "show behind." */
                if (Host.isAndroid) {
                    try { navigate('/'); } catch { /* ignore */ }
                }
                return;
            }
            // WebView fallback (preview / non-Android) — keep the
            // JS player path which already does its own party sync.
            const partyQuery = partyCode
                ? `&party=${encodeURIComponent(partyCode)}&at_ms=${encodeURIComponent(partyAtMs)}&position_ms=${encodeURIComponent(partyPositionMs)}`
                : '';
            if (partyCode) partyBreadcrumb('playStream:web-fallback', {});
            navigate(
                `/play?url=${encodeURIComponent(
                    playUrl
                )}&title=${encodeURIComponent(playTitle)}&type=${encodeURIComponent(
                    type
                )}&imdbId=${encodeURIComponent(playId)}${partyQuery}`
            );
        } else if (mode === 'external') {
            try {
                window.open(stream.externalUrl, '_blank', 'noopener,noreferrer');
            } catch {
                /* popup blocked */
            }
        }
    };

    const copyMagnet = async (stream) => {
        const magnet = buildMagnet(stream, meta?.name);
        if (!magnet) return;
        try {
            await navigator.clipboard.writeText(magnet);
            setCopied(stream.infoHash);
            setTimeout(() => setCopied(null), 1800);
        } catch {
            /* no clipboard permission — show prompt fallback */
            window.prompt('Copy this magnet link:', magnet);
        }
    };

    // ---------- PARTY MODE — DEDICATED JOINING SCREEN ----------
    // Highest-priority render path.  When the URL has `?party=…`
    // AND autoplay hasn't fired yet, we render a clean, focused
    // "Starting your party" screen and nothing else.  Critically:
    // the stream picker is NEVER mounted — there's no clickable
    // picker to confuse the user, and no race condition where they
    // tap a half-visible button behind the overlay.  We do this
    // BEFORE the `loading` / `err / !meta` checks so the user sees
    // the joining screen from the very first paint instead of a
    // plain "Loading metadata…" fragment.
    //
    // The check is `partyCode` (not `partyCode && !autoplayFired`)
    // so the joining screen stays mounted from the very first
    // paint right through to navigation.  This eliminates the
    // 30ms picker flash the user saw on the previous build —
    // setAutoplayFired(true) used to unmount the joining screen
    // before window.setTimeout(playStream, 30) actually navigated
    // away, exposing the picker for one frame.
    if (partyCode) {
        const seriesLabel = (type === 'series' && partySeason && partyEpisode)
            ? ` · S${String(partySeason).padStart(2, '0')}E${String(partyEpisode).padStart(2, '0')}`
            : '';
        const serverStreamError = partyDetailState?.stream_error || '';
        const hostNoStreams = isPartyHost
            && !streamLoading
            && streams.length === 0
            && type !== 'series';
        const guestNoStreams = isPartyGuest && !!serverStreamError;
        const noStreams = hostNoStreams || guestNoStreams;
        return (
            <PartyJoiningScreen
                title={meta ? (meta.name || '') + seriesLabel : 'Your watch party is starting'}
                poster={meta?.poster}
                backdrop={meta?.background || meta?.poster}
                loading={loading || streamLoading || (isPartyGuest && !partyDetailState?.stream)}
                noStreams={noStreams}
                role={isPartyHost ? 'host' : 'guest'}
                onCancel={() => navigate('/watch-together')}
                onRetry={() => {
                    partyBreadcrumb('party-joining:user-retry', { partyCode });
                    window.location.reload();
                }}
            />
        );
    }

    if (loading) {
        return (
            <CenterMsg>
                <Loader2 className="vesper-spin" size={28} /> Loading metadata…
            </CenterMsg>
        );
    }

    if (err || !meta) {
        return (
            <CenterMsg>
                <div style={{ color: '#ffb5b5' }}>{err || 'Not found'}</div>
                <button
                    data-focusable="true"
                    data-focus-style="pill"
                    tabIndex={0}
                    onClick={() => navigate(-1)}
                    className="mt-6 h-12 px-5 rounded-full"
                    style={{
                        background: 'rgba(255,255,255,0.06)',
                        color: 'var(--vesper-text)',
                        border: '1px solid rgba(255,255,255,0.12)',
                    }}
                >
                    Back
                </button>
            </CenterMsg>
        );
    }

    const playable = streams.filter(
        (s) => streamMode(s) === 'direct' || streamMode(s) === 'external'
    );
    const torrentCount = streams.filter(
        (s) => streamMode(s) === 'torrent'
    ).length;

    return (
        <div
            data-testid="detail-page"
            data-kids-theme={isKidsActive() ? '1' : undefined}
            className={`relative w-screen h-[100dvh] min-h-screen overflow-hidden ${
                isKidsActive() ? 'vesper-kids-root' : ''
            }`}
            style={isKidsActive() ? { background: 'var(--vesper-bg-0)' } : undefined}
        >
            <FullscreenButton />

            {/* Backdrop — defaults to the title's own backdrop, but
                when the user is browsing an actor's filmography
                and a specific film is focused, we cross-fade to
                THAT film's backdrop so the page feels alive. */}
            <div
                className="absolute inset-0"
                style={{
                    backgroundImage: `url(${
                        focusedMovie?.backdrop ||
                        meta.background ||
                        meta.poster || ''
                    })`,
                    backgroundSize: 'cover',
                    backgroundPosition: 'center',
                    filter: 'brightness(0.85) saturate(1.1)',
                    transition: 'background-image 300ms ease',
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(180deg,
                          rgba(6,8,15,0.45) 0%,
                          rgba(6,8,15,0.2) 30%,
                          rgba(6,8,15,0.35) 70%,
                          rgba(6,8,15,0.65) 100%)`,
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(90deg,
                          rgba(6,8,15,0.92) 0%,
                          rgba(6,8,15,0.7) 30%,
                          rgba(6,8,15,0.2) 55%,
                          rgba(6,8,15,0) 75%)`,
                }}
            />

            <main
                className={`relative z-10 w-full h-full ${
                    /* Hide overflow on movies + initial TV view
                     * (where Cast row is bottom-anchored).  Allow
                     * scrolling once the user has revealed
                     * episodes so they can reach every episode
                     * in a long season. */
                    seriesEpisodesShown ? 'overflow-y-auto' : 'overflow-hidden'
                }`}
                /* `data-no-row-snap` disables the spatial-focus
                 * vertical row-pin scroll so the page stays still
                 * while the user browses the Cast row.  Once the
                 * user has REVEALED a season's episodes we want
                 * normal scroll-into-view behaviour so a long
                 * episode list is fully reachable — turn the flag
                 * off in that mode. */
                data-no-row-snap={seriesEpisodesShown ? undefined : 'true'}
                style={{ padding: '40px 80px 60px 80px' }}
            >
                <div className="flex items-center gap-3 mb-5">
                    <button
                        data-testid="back-button"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => navigate(-1)}
                        className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
                        style={{
                            background: 'rgba(17,24,39,0.6)',
                            color: 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            fontSize: 13,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        <ArrowLeft size={16} /> Back
                    </button>
                    <button
                        data-testid="home-button"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => navigate('/')}
                        className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
                        style={{
                            background: 'rgba(17,24,39,0.6)',
                            color: 'var(--vesper-text-2)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            fontSize: 13,
                            letterSpacing: '0.18em',
                            textTransform: 'uppercase',
                        }}
                    >
                        <Home size={16} /> Home
                    </button>
                    <LibraryStatusPill id={id} />
                </div>

                <div
                    className="max-w-[68vw] vesper-fade-up"
                >
                    {meta.imdb_id && (
                        <div className="vesper-eyebrow mb-3">
                            {focusedMovie ? focusedMovie.media_type : type} · {meta.imdb_id}
                        </div>
                    )}
                    <h1
                        className="vesper-display"
                        data-testid="detail-title"
                        style={{
                            fontSize: 'clamp(44px, 4.6vw, 72px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 1.05,
                            transition: 'opacity 220ms ease',
                        }}
                    >
                        {focusedMovie?.title || focusedActor?.name || meta.name}
                    </h1>

                    {focusedMovie ? (
                        <div
                            className="flex items-center gap-3 mt-4 vesper-meta flex-wrap"
                            style={{ fontSize: 18 }}
                        >
                            {focusedMovie.year && (
                                <span style={{ color: 'var(--vesper-blue)' }}>
                                    {focusedMovie.year}
                                </span>
                            )}
                            {focusedMovie.rating != null && (
                                <>
                                    <Bullet />
                                    <span>★ {focusedMovie.rating}</span>
                                </>
                            )}
                            {focusedMovie.character && (
                                <>
                                    <Bullet />
                                    <span>as {focusedMovie.character}</span>
                                </>
                            )}
                        </div>
                    ) : focusedActor ? (
                        <>
                            <div
                                className="vesper-mono mt-2"
                                style={{
                                    fontSize: 14, letterSpacing: '0.18em',
                                    color: 'var(--vesper-blue)',
                                    textTransform: 'uppercase',
                                    fontWeight: 700,
                                }}
                            >
                                {focusedActor.character
                                    ? `As ${focusedActor.character}`
                                    : 'Cast'}
                            </div>
                            {(focusedAge || focusedBirthplace) && (
                                <div
                                    className="flex items-center gap-3 mt-3 vesper-meta flex-wrap"
                                    style={{ fontSize: 16, color: 'var(--vesper-text-2)' }}
                                >
                                    {focusedAge && (
                                        <span>{focusedAge} years old</span>
                                    )}
                                    {focusedAge && focusedBirthplace && <Bullet />}
                                    {focusedBirthplace && (
                                        <span>{focusedBirthplace}</span>
                                    )}
                                </div>
                            )}
                        </>
                    ) : (
                        <div
                            className="flex items-center gap-3 mt-4 vesper-meta flex-wrap"
                            style={{ fontSize: 18 }}
                        >
                            {meta.releaseInfo && (
                                <span style={{ color: 'var(--vesper-blue)' }}>
                                    {meta.releaseInfo}
                                </span>
                            )}
                            {meta.runtime && <Bullet />}
                            {meta.runtime && <span>{meta.runtime}</span>}
                            {meta.imdbRating && <Bullet />}
                            {meta.imdbRating && <span>★ {meta.imdbRating}</span>}
                            {meta.genres?.length > 0 && <Bullet />}
                            {meta.genres?.length > 0 && (
                                <span>{meta.genres.slice(0, 3).join(' · ')}</span>
                            )}
                        </div>
                    )}

                    {/* Hero body text — three states. */}
                    {focusedMovie ? (
                        focusedMovie.overview ? (
                            <p
                                data-testid="film-overview"
                                className="mt-6 max-w-[58ch]"
                                style={{
                                    fontSize: 16,
                                    lineHeight: 1.55,
                                    color: 'var(--vesper-text-2)',
                                    display: '-webkit-box',
                                    WebkitLineClamp: 2,
                                    WebkitBoxOrient: 'vertical',
                                    overflow: 'hidden',
                                }}
                            >
                                {focusedMovie.overview}
                            </p>
                        ) : null
                    ) : focusedActor ? (
                        <p
                            data-testid="actor-bio"
                            className="mt-4 max-w-[58ch]"
                            style={{
                                fontSize: 16,
                                lineHeight: 1.5,
                                color: 'var(--vesper-text-2)',
                                display: '-webkit-box',
                                WebkitLineClamp: 2,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {focusedBio || 'Loading biography…'}
                        </p>
                    ) : meta.description ? (
                        <p
                            className="mt-6 max-w-[58ch]"
                            style={{
                                fontSize: 17,
                                lineHeight: 1.55,
                                color: 'var(--vesper-text-2)',
                                display: '-webkit-box',
                                WebkitLineClamp: 2,
                                WebkitBoxOrient: 'vertical',
                                overflow: 'hidden',
                            }}
                        >
                            {meta.description}
                        </p>
                    ) : null}

                    {/* CTA AREA — three states.
                        1. No actor focused → Play CTA + autoplay
                           caption (normal movie watching flow).
                        2. Cast mode + actor focused → hint inviting
                           the user to press OK to see the actor's
                           other work.
                        3. Filmography mode + film focused → hint
                           inviting the user to press OK to open
                           that title's page. */}
                    {focusedMovie ? (
                        <CastHint
                            line1="Press OK"
                            line2={`Open ${focusedMovie.title}`}
                        />
                    ) : focusedActor ? (
                        <CastHint
                            line1="Press OK"
                            line2={`See ${
                                focusedActor.name?.split(' ')[0] || 'their'
                            }'s filmography`}
                        />
                    ) : type === 'movie' && autoplayEnabled && (
                        <div className="mt-8 flex items-center gap-3 flex-wrap">
                            <button
                                data-testid="detail-play-autoplay"
                                data-focusable="true"
                                data-focus-style="pill"
                                data-initial-focus="true"
                                tabIndex={0}
                                onClick={triggerAutoplay}
                                disabled={streamLoading}
                                className="vesper-pulse-cta flex items-center gap-2.5 rounded-full font-sans font-semibold"
                                style={{
                                    height: 'clamp(50px, 4vw, 60px)',
                                    paddingLeft: 'clamp(24px, 1.8vw, 32px)',
                                    paddingRight: 'clamp(28px, 2.2vw, 38px)',
                                    fontSize: 'clamp(15px, 1.15vw, 18px)',
                                    background: streamLoading
                                        ? 'rgba(255,255,255,0.10)'
                                        : 'var(--vesper-blue)',
                                    color: streamLoading
                                        ? 'var(--vesper-text-2)'
                                        : 'var(--vesper-bg-0)',
                                    opacity: streamLoading ? 0.7 : 1,
                                }}
                            >
                                {streamLoading ? (
                                    <>
                                        <Loader2
                                            className="vesper-spin"
                                            size={18}
                                        />
                                        Finding stream…
                                    </>
                                ) : autoplayCandidate ? (
                                    <>
                                        <Play size={18} fill="currentColor" />
                                        Autoplay
                                    </>
                                ) : (
                                    <>
                                        <Play size={18} />
                                        No stream found
                                    </>
                                )}
                            </button>
                            {/* v2.7.25 — Choose stream is ALWAYS
                                available on movie detail when there
                                are streams, regardless of Autoplay
                                setting.  User reported the popup
                                "isn't working" because with Autoplay
                                ON they had no way to open the
                                picker.  Now it's a secondary pill
                                next to Autoplay. */}
                            {streams && streams.length > 0 && (
                                <button
                                    data-testid="detail-choose-stream"
                                    data-focusable="true"
                                    data-focus-style="pill"
                                    tabIndex={0}
                                    onClick={openStreamPicker}
                                    className="flex items-center gap-2 rounded-full font-sans font-semibold"
                                    style={{
                                        height: 'clamp(50px, 4vw, 60px)',
                                        paddingLeft: 'clamp(22px, 1.6vw, 30px)',
                                        paddingRight: 'clamp(22px, 1.6vw, 30px)',
                                        fontSize: 'clamp(14px, 1.05vw, 17px)',
                                        background: 'rgba(255,255,255,0.10)',
                                        color: 'var(--vesper-text)',
                                        border: '1px solid rgba(255,255,255,0.18)',
                                    }}
                                >
                                    Choose stream
                                    <span
                                        className="vesper-mono"
                                        style={{
                                            fontSize: 11,
                                            opacity: 0.7,
                                            letterSpacing: '0.06em',
                                            marginLeft: 4,
                                        }}
                                    >
                                        ({streams.length})
                                    </span>
                                </button>
                            )}
                            <TrailerPill
                                onClick={openTrailer}
                                loading={trailerLoading}
                            />
                        </div>
                    )}

                    {/* v2.7.20 — Movie + Autoplay OFF.  Show a
                        "Choose stream" CTA that scrolls to and
                        focuses the first stream in the picker.
                        Without this button, users with Autoplay
                        off had no obvious primary action — they
                        had to scroll down to find the streams.
                        Helps them diagnose stream-vs-player
                        issues without leaving the page. */}
                    {type === 'movie'
                        && !autoplayEnabled
                        && !focusedActor
                        && !focusedMovie && (
                        <div className="mt-8 flex items-center gap-3 flex-wrap">
                            <button
                                data-testid="detail-choose-stream"
                                data-focusable="true"
                                data-focus-style="pill"
                                data-initial-focus="true"
                                tabIndex={0}
                                onClick={openStreamPicker}
                                disabled={streamLoading}
                                className="vesper-pulse-cta flex items-center gap-2.5 rounded-full font-sans font-semibold"
                                style={{
                                    height: 'clamp(50px, 4vw, 60px)',
                                    paddingLeft: 'clamp(24px, 1.8vw, 32px)',
                                    paddingRight: 'clamp(28px, 2.2vw, 38px)',
                                    fontSize: 'clamp(15px, 1.15vw, 18px)',
                                    background: streamLoading
                                        ? 'rgba(255,255,255,0.10)'
                                        : 'var(--vesper-blue)',
                                    color: streamLoading
                                        ? 'var(--vesper-text-2)'
                                        : 'var(--vesper-bg-0)',
                                    opacity: streamLoading ? 0.7 : 1,
                                }}
                            >
                                {streamLoading ? (
                                    <>
                                        <Loader2
                                            className="vesper-spin"
                                            size={18}
                                        />
                                        Finding streams…
                                    </>
                                ) : streams && streams.length > 0 ? (
                                    <>
                                        <Play size={18} fill="currentColor" />
                                        Choose stream
                                        <span
                                            className="ml-1 vesper-mono"
                                            style={{
                                                fontSize: 12,
                                                opacity: 0.75,
                                                letterSpacing: '0.06em',
                                            }}
                                        >
                                            ({streams.length})
                                        </span>
                                    </>
                                ) : (
                                    <>
                                        <Play size={18} />
                                        No stream found
                                    </>
                                )}
                            </button>
                            <TrailerPill
                                onClick={openTrailer}
                                loading={trailerLoading}
                            />
                        </div>
                    )}

                    {/* v2.7.31 — Trailer pill for SERIES is now
                        injected as the FIRST item in the Seasons
                        pill row inside <SeriesEpisodes>, per user
                        request ("put the trailer button BESIDE first
                        pill of Seasons, NOT on top"). */}

                    {/* Stream picker (movies) / Episode browser (series).
                        Hidden when actor OR a filmography movie is
                        focused — hero is showing that info instead. */}
                    {(focusedActor || focusedMovie) ? null : type === 'series' ? (
                        <SeriesEpisodes
                            meta={meta}
                            parentId={id}
                            initialSeason={focusSeason ? Number(focusSeason) : undefined}
                            highlightEpisode={focusEpisode ? Number(focusEpisode) : undefined}
                            onEpisodesShownChange={setSeriesEpisodesShown}
                            leadingPill={
                                <TrailerPill
                                    onClick={openTrailer}
                                    loading={trailerLoading}
                                    compact
                                />
                            }
                        />
                    ) : autoplayEnabled && autoplayCandidate ? (
                        // Autoplay is on AND we have a 1080p candidate
                        // → hide the manual stream picker entirely.
                        null
                    ) : (
                    <section
                        data-testid="stream-picker"
                        className="mt-10"
                    >
                        <h3
                            className="vesper-display mb-5"
                            style={{ fontSize: 26, letterSpacing: '-0.02em' }}
                        >
                            Available streams
                            {!streamLoading && (
                                <span
                                    className="ml-3 vesper-mono"
                                    style={{
                                        fontSize: 12,
                                        color: 'var(--vesper-text-3)',
                                        letterSpacing: '0.22em',
                                        textTransform: 'uppercase',
                                    }}
                                >
                                    {streams.length > 0
                                        ? `${streams.length} found · ${playable.length} playable`
                                        : '0 found'}
                                </span>
                            )}
                        </h3>

                        {streamLoading ? (
                            <div
                                className="flex items-center gap-3"
                                style={{ color: 'var(--vesper-text-2)' }}
                            >
                                <Loader2 className="vesper-spin" size={18} />
                                Searching installed addons…
                            </div>
                        ) : streams.length === 0 ? (
                            <div className="vesper-glass rounded-2xl p-6">
                                <div
                                    style={{
                                        color: 'var(--vesper-text)',
                                        fontSize: 17,
                                        fontWeight: 500,
                                    }}
                                >
                                    No streams returned by your installed
                                    addons.
                                </div>
                                {diagnostics.length > 0 && (
                                    <ul
                                        className="mt-4 space-y-2"
                                        data-testid="stream-diagnostics"
                                    >
                                        {diagnostics.map((d) => (
                                            <li
                                                key={d.addon.id}
                                                className="flex items-center justify-between gap-4 vesper-mono"
                                                style={{
                                                    fontSize: 13,
                                                    color: 'var(--vesper-text-2)',
                                                    letterSpacing: '0.06em',
                                                }}
                                            >
                                                <span>{d.addon.name}</span>
                                                <span
                                                    style={{
                                                        color: d.error
                                                            ? '#ffb5b5'
                                                            : d.count > 0
                                                            ? 'var(--vesper-blue-bright)'
                                                            : 'var(--vesper-text-3)',
                                                    }}
                                                >
                                                    {d.error
                                                        ? `failed · ${d.error}`
                                                        : d.skipped
                                                        ? `skipped · ${d.skipped}`
                                                        : `${d.count} streams`}
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                )}
                                <div
                                    className="mt-5"
                                    style={{
                                        fontSize: 14,
                                        color: 'var(--vesper-text-3)',
                                    }}
                                >
                                    Tip: many stream addons return zero
                                    results for unreleased or very recent
                                    titles. Try a well-known catalogue movie
                                    to verify.
                                </div>
                            </div>
                        ) : (
                            <>
                                {torrentCount > 0 && (
                                    <div
                                        data-testid="torrent-banner"
                                        className="vesper-glass rounded-xl px-5 py-4 mb-4 flex items-start gap-3"
                                        style={{
                                            borderColor:
                                                'rgba(255,200,80,0.25)',
                                        }}
                                    >
                                        <Info
                                            size={18}
                                            strokeWidth={1.6}
                                            style={{
                                                color: '#ffd28a',
                                                marginTop: 3,
                                                flexShrink: 0,
                                            }}
                                        />
                                        <div
                                            style={{
                                                fontSize: 14,
                                                lineHeight: 1.55,
                                                color: 'var(--vesper-text-2)',
                                            }}
                                        >
                                            <strong
                                                style={{
                                                    color: 'var(--vesper-text)',
                                                }}
                                            >
                                                {torrentCount} torrent
                                                {torrentCount === 1 ? '' : 's'}{' '}
                                                returned.
                                            </strong>{' '}
                                            Torrent streams (info-hash) can&apos;t
                                            be played directly in this client.
                                            Use the magnet button to copy /
                                            open in an external app, or
                                            configure a debrid service
                                            (Real-Debrid, AllDebrid,
                                            Premiumize) inside your Torrentio
                                            URL. Torrentio will then return
                                            direct HTTP streams that play
                                            here.
                                        </div>
                                    </div>
                                )}

                                <ul
                                    className="flex flex-col"
                                    style={{ gap: 14 }}
                                    data-testid="stream-list"
                                >
                                    {streams.slice(0, 60).map((s, i) => {
                                        const mode = streamMode(s);
                                        const ModeIcon =
                                            mode === 'direct'
                                                ? Play
                                                : mode === 'external'
                                                ? ExternalLink
                                                : mode === 'torrent'
                                                ? Magnet
                                                : Info;
                                        const accent =
                                            mode === 'direct'
                                                ? 'var(--vesper-blue)'
                                                : mode === 'external'
                                                ? '#a8e3ff'
                                                : mode === 'torrent'
                                                ? '#ffd28a'
                                                : 'var(--vesper-text-3)';
                                        const isCopied =
                                            mode === 'torrent' &&
                                            copied === s.infoHash;
                                        const badge = qualityBadge(s);
                                        const tags = qualityTags(s);
                                        const rawLabel =
                                            s.title || s.name || s._addon_name || 'Stream';
                                        // Split Torrentio's title field:
                                        // line 1 = filename, lines 2+ = metadata
                                        // (seeders / size / source / langs).
                                        // We only want the filename in the heading
                                        // so multi-line file titles stay clean.
                                        const labelLines = rawLabel.split('\n');
                                        const titleLine = labelLines[0];
                                        const metaLines = labelLines.slice(1)
                                            .map((l) => l.trim())
                                            .filter(Boolean);
                                        // Pull seeders/size/tracker out of the
                                        // meta lines (Torrentio puts them on
                                        // line 2 as "👤 24  💾 5.38 GB  ⚙ ThePirateBay").
                                        const chips = [];
                                        const SEED = /👤\s*(\d+[\d.,]*)/u;
                                        const SIZE = /💾\s*([^\s][^⚙⚡]+?)(?=\s+[⚙⚡]|$)/u;
                                        const TRACKER = /⚙\s*([^\s][^👤💾⚡]+?)$/u;
                                        const LANG_LINE = /^([A-Z]{2}(\s*\/\s*[A-Z]{2})+)$/i;
                                        for (const ml of metaLines) {
                                            const seed = ml.match(SEED);
                                            const size = ml.match(SIZE);
                                            const trk = ml.match(TRACKER);
                                            const lang = ml.match(LANG_LINE);
                                            if (seed) chips.push({ k: 'seed', v: `${seed[1]} seeders` });
                                            if (size) chips.push({ k: 'size', v: size[1].trim() });
                                            if (trk) chips.push({ k: 'trk', v: trk[1].trim() });
                                            if (lang) chips.push({ k: 'lang', v: lang[1].toUpperCase() });
                                        }
                                        return (
                                            <li
                                                key={i}
                                                className="flex items-stretch gap-2"
                                            >
                                                <button
                                                    data-testid={`stream-${i}`}
                                                    data-focusable="true"
                                                    data-focus-style="pill"
                                                    tabIndex={0}
                                                    onClick={() =>
                                                        playStream(s)
                                                    }
                                                    className="flex-1 text-left flex items-start gap-4"
                                                    style={{
                                                        padding: '18px 22px',
                                                        borderRadius: 14,
                                                        background:
                                                            i === lastStreamIdx
                                                                ? 'rgba(var(--vesper-blue-rgb),0.10)'
                                                                : 'rgba(13,18,28,0.78)',
                                                        border:
                                                            i === lastStreamIdx
                                                                ? '1px solid var(--vesper-blue-bright)'
                                                                : '1px solid rgba(255,255,255,0.06)',
                                                        boxShadow:
                                                            '0 6px 18px rgba(0,0,0,0.28)',
                                                    }}
                                                >
                                                    <span
                                                        className="flex flex-col items-center justify-center shrink-0"
                                                        style={{
                                                            width: badge ? 64 : 44,
                                                            minHeight: 56,
                                                            borderRadius: 12,
                                                            background: badge
                                                                ? toneColors[badge.tone].bg
                                                                : `${accent.startsWith('#') ? accent : 'rgba(var(--vesper-blue-rgb),1)'}22`,
                                                            color: badge
                                                                ? toneColors[badge.tone].fg
                                                                : accent,
                                                            border: badge
                                                                ? `1px solid ${toneColors[badge.tone].border}`
                                                                : 'none',
                                                            marginTop: 2,
                                                            gap: 4,
                                                            padding: '8px 4px',
                                                        }}
                                                    >
                                                        {badge ? (
                                                            <span
                                                                style={{
                                                                    fontFamily:
                                                                        'var(--theme-font-display, "Geist", system-ui, sans-serif)',
                                                                    fontSize:
                                                                        badge.label.length <= 3 ? 18 : 13,
                                                                    fontWeight: 800,
                                                                    letterSpacing: '-0.02em',
                                                                    lineHeight: 1,
                                                                }}
                                                            >
                                                                {badge.label}
                                                            </span>
                                                        ) : (
                                                            <ModeIcon
                                                                size={18}
                                                                fill={
                                                                    mode === 'direct'
                                                                        ? 'currentColor'
                                                                        : 'none'
                                                                }
                                                            />
                                                        )}
                                                        <ModeIcon
                                                            size={badge ? 10 : 0}
                                                            style={{
                                                                opacity: badge ? 0.55 : 0,
                                                                display: badge ? 'block' : 'none',
                                                            }}
                                                            fill={mode === 'direct' ? 'currentColor' : 'none'}
                                                        />
                                                    </span>

                                                    <div className="min-w-0 flex-1">
                                                        <div
                                                            className="flex items-center gap-2 flex-wrap"
                                                        >
                                                            <div
                                                                style={{
                                                                    fontFamily:
                                                                        'var(--theme-font-body, "Geist", system-ui, sans-serif)',
                                                                    fontSize: 15,
                                                                    fontWeight: 500,
                                                                    lineHeight: 1.35,
                                                                    color: 'var(--vesper-text)',
                                                                    wordBreak: 'break-word',
                                                                    display: '-webkit-box',
                                                                    WebkitBoxOrient: 'vertical',
                                                                    WebkitLineClamp: 2,
                                                                    overflow: 'hidden',
                                                                    flex: '1 1 auto',
                                                                    minWidth: 0,
                                                                }}
                                                            >
                                                                {titleLine}
                                                            </div>
                                                            {i === lastStreamIdx && (
                                                                <span
                                                                    data-testid={`stream-${i}-current`}
                                                                    className="vesper-mono shrink-0"
                                                                    style={{
                                                                        fontSize: 10,
                                                                        fontWeight: 800,
                                                                        letterSpacing: '0.14em',
                                                                        padding: '3px 9px',
                                                                        borderRadius: 4,
                                                                        background: 'var(--vesper-blue-bright)',
                                                                        color: 'var(--vesper-bg-0)',
                                                                        whiteSpace: 'nowrap',
                                                                    }}
                                                                >
                                                                    ● CURRENT
                                                                </span>
                                                            )}
                                                        </div>

                                                        {/* Quality tag pills (HDR / DV / Atmos / REMUX) */}
                                                        {tags.length > 0 && (
                                                            <div
                                                                className="flex flex-wrap items-center"
                                                                style={{
                                                                    gap: 6,
                                                                    marginTop: 10,
                                                                }}
                                                            >
                                                                {tags.map((t, ti) => {
                                                                    const c = toneColors[t.tone];
                                                                    return (
                                                                        <span
                                                                            key={ti}
                                                                            className="vesper-mono"
                                                                            style={{
                                                                                fontSize: 10,
                                                                                fontWeight: 700,
                                                                                letterSpacing: '0.1em',
                                                                                padding: '3px 8px',
                                                                                borderRadius: 4,
                                                                                background: c.bg,
                                                                                color: c.fg,
                                                                                border: `1px solid ${c.border}`,
                                                                                whiteSpace: 'nowrap',
                                                                            }}
                                                                        >
                                                                            {t.label}
                                                                        </span>
                                                                    );
                                                                })}
                                                            </div>
                                                        )}

                                                        {/* Metadata chip row */}
                                                        {(chips.length > 0 || s._is_english) && (
                                                            <div
                                                                className="flex flex-wrap items-center"
                                                                style={{
                                                                    gap: 8,
                                                                    marginTop: 12,
                                                                }}
                                                            >
                                                                {/* v2.7.33 — 🇬🇧 English chip.
                                                                    Backend tags every kept stream
                                                                    with `_is_english:true`; we
                                                                    surface it as a prominent first
                                                                    chip so the user can confidently
                                                                    pick English audio at a glance. */}
                                                                {s._is_english && (
                                                                    <span
                                                                        data-testid={`stream-${i}-english`}
                                                                        className="vesper-mono"
                                                                        style={{
                                                                            fontSize: 11,
                                                                            fontWeight: 700,
                                                                            letterSpacing: '0.12em',
                                                                            padding: '4px 10px',
                                                                            borderRadius: 999,
                                                                            background: 'rgba(124,241,241,0.14)',
                                                                            color: '#7CF1F1',
                                                                            border: '1px solid rgba(124,241,241,0.30)',
                                                                            whiteSpace: 'nowrap',
                                                                        }}
                                                                    >
                                                                        🇬🇧 ENGLISH
                                                                    </span>
                                                                )}
                                                                {chips.map((c, ci) => (
                                                                    <span
                                                                        key={ci}
                                                                        className="vesper-mono"
                                                                        style={{
                                                                            fontSize: 11,
                                                                            letterSpacing: '0.06em',
                                                                            padding: '4px 10px',
                                                                            borderRadius: 999,
                                                                            background:
                                                                                c.k === 'seed'
                                                                                    ? 'rgba(var(--vesper-blue-rgb),0.12)'
                                                                                    : c.k === 'size'
                                                                                    ? 'rgba(255,210,138,0.12)'
                                                                                    : c.k === 'lang'
                                                                                    ? 'rgba(255,255,255,0.06)'
                                                                                    : 'rgba(255,255,255,0.05)',
                                                                            color:
                                                                                c.k === 'seed'
                                                                                    ? 'var(--vesper-blue)'
                                                                                    : c.k === 'size'
                                                                                    ? '#ffd28a'
                                                                                    : 'var(--vesper-text-2)',
                                                                            border: '1px solid rgba(255,255,255,0.05)',
                                                                            whiteSpace: 'nowrap',
                                                                        }}
                                                                    >
                                                                        {c.v}
                                                                    </span>
                                                                ))}
                                                            </div>
                                                        )}

                                                        {/* Bottom tagline */}
                                                        <div
                                                            className="vesper-mono"
                                                            style={{
                                                                fontSize: 10,
                                                                color: 'var(--vesper-text-3)',
                                                                letterSpacing: '0.18em',
                                                                textTransform: 'uppercase',
                                                                marginTop: chips.length > 0 ? 10 : 8,
                                                            }}
                                                        >
                                                            <span style={{ color: accent }}>
                                                                {mode}
                                                            </span>
                                                            {' · '}
                                                            {s._addon_name || 'addon'}
                                                            {s.behaviorHints?.bingeGroup
                                                                ? ` · ${s.behaviorHints.bingeGroup
                                                                    .replace(/torrentio\|?/i, '')
                                                                    .split('|')
                                                                    .filter(Boolean)
                                                                    .join(' · ')}`
                                                                : ''}
                                                        </div>
                                                    </div>
                                                </button>

                                                {mode === 'torrent' && (
                                                    <button
                                                        data-testid={`copy-magnet-${i}`}
                                                        data-focusable="true"
                                                        data-focus-style="quiet"
                                                        tabIndex={0}
                                                        onClick={() =>
                                                            copyMagnet(s)
                                                        }
                                                        aria-label="Copy magnet"
                                                        title="Copy magnet link"
                                                        className="shrink-0 flex items-center justify-center"
                                                        style={{
                                                            width: 52,
                                                            borderRadius: 14,
                                                            background:
                                                                'rgba(13,18,28,0.78)',
                                                            color: isCopied
                                                                ? 'var(--vesper-blue)'
                                                                : 'var(--vesper-text-2)',
                                                            border:
                                                                '1px solid rgba(255,255,255,0.06)',
                                                        }}
                                                    >
                                                        <Copy size={16} />
                                                    </button>
                                                )}
                                            </li>
                                        );
                                    })}
                                </ul>

                                {streams.length > 60 && (
                                    <div
                                        className="vesper-mono mt-4"
                                        style={{
                                            fontSize: 12,
                                            color: 'var(--vesper-text-3)',
                                            letterSpacing: '0.18em',
                                            textTransform: 'uppercase',
                                        }}
                                    >
                                        + {streams.length - 60} more streams
                                        not shown
                                    </div>
                                )}
                            </>
                        )}
                    </section>
                    )}

                </div>
            </main>

            {/* ── CAST ROW — fixed at the bottom of the page.
                 Horizontal scrolling strip of actor cards.  Hero
                 stays anchored at the top, cast row stays anchored
                 at the bottom.  Page itself never scrolls.  Solid
                 backdrop ensures the hero never bleeds through. */}
            {tmdbInfo?.tmdb_id && !seriesEpisodesShown && (
                <div
                    data-testid="detail-cast-lane"
                    style={{
                        position: 'absolute',
                        left: 0, right: 0, bottom: 0,
                        zIndex: 15,
                        padding: '0 0 32px 0',
                    }}
                >
                    {/* Subtle bottom-edge backdrop fade.  Only kicks
                        in just above the lane header so the
                        Autoplay button (sitting much higher) never
                        gets dimmed by it. */}
                    <div
                        style={{
                            position: 'absolute',
                            inset: '-40px 0 0 0',
                            background:
                                'linear-gradient(180deg, ' +
                                'rgba(6,8,15,0) 0%, ' +
                                'rgba(6,8,15,0.7) 70%, ' +
                                'rgba(6,8,15,0.9) 100%)',
                            pointerEvents: 'none',
                        }}
                    />
                    <div style={{ position: 'relative' }}>
                        <CastRow
                            tmdbId={tmdbInfo.tmdb_id}
                            mediaType={tmdbInfo.media_type}
                            onFocus={setFocusedActor}
                            onMovieFocus={setFocusedMovie}
                            onViewChange={setCastView}
                        />
                    </div>
                </div>
            )}

            {/* YouTube trailer popup (native VLC HD on Android,
                iframe fallback for desktop / preview). */}
            <TrailerModal
                youtubeKey={trailerKey}
                title={focusedMovie?.title || meta?.name}
                poster={meta?.poster || ''}
                backdrop={meta?.background || meta?.poster || ''}
                onClose={() => setTrailerKey(null)}
            />

            {/* "Coming Soon" modal — fires when the user taps Play
                on a TMDB-promoted title that no addon has streams
                for yet.  Lets them add it to the notify list so we
                can alert them the moment a stream becomes available. */}
            {showUnavailableModal && (
                <StreamUnavailableModal
                    id={id}
                    meta={meta}
                    onClose={() => setShowUnavailableModal(false)}
                />
            )}

            {/* v2.7.22 — Stream picker modal.  Opened by the
                "Choose stream" CTA on movie Detail when Autoplay
                is OFF.  Centred popup, first stream auto-focused,
                D-pad walks the list, OK plays, BACK closes. */}
            {showStreamPicker && (
                <StreamPickerModal
                    streams={streams}
                    currentIdx={lastStreamIdx}
                    onPick={handleStreamPick}
                    onClose={closeStreamPicker}
                    meta={meta}
                />
            )}
        </div>
    );
}

/* ─────────────────────── TrailerPill ─────────────────────── */
function TrailerPill({ onClick, loading, primary, compact }) {
    return (
        <button
            data-testid="detail-trailer"
            data-focusable="true"
            data-focus-style="pill"
            data-initial-focus={primary ? 'true' : undefined}
            tabIndex={0}
            onClick={onClick}
            disabled={loading}
            className="flex items-center gap-2 rounded-full font-sans font-semibold"
            style={{
                // v2.7.31 — `compact` matches the Season pill metrics
                // so the Trailer pill can sit BESIDE seasons as the
                // first item in the row instead of on its own line.
                height: compact
                    ? 'clamp(36px, 3vw, 44px)'
                    : primary
                        ? 'clamp(46px, 3.6vw, 56px)'
                        : 'clamp(44px, 3.4vw, 52px)',
                paddingLeft: compact
                    ? 'clamp(16px, 1.4vw, 22px)'
                    : 'clamp(18px, 1.4vw, 24px)',
                paddingRight: compact
                    ? 'clamp(16px, 1.4vw, 22px)'
                    : 'clamp(22px, 1.6vw, 28px)',
                fontSize: compact
                    ? 'clamp(13px, 0.95vw, 15px)'
                    : primary
                        ? 'clamp(14px, 1vw, 16px)'
                        : 'clamp(13px, 0.95vw, 15px)',
                background: primary
                    ? 'var(--vesper-blue)'
                    : 'rgba(93,200,255,0.16)',
                color: primary ? 'var(--vesper-bg-0)' : 'var(--vesper-blue-bright)',
                border: primary
                    ? '1px solid transparent'
                    : '1px solid rgba(93,200,255,0.32)',
                letterSpacing: '0.02em',
                opacity: loading ? 0.7 : 1,
                cursor: loading ? 'progress' : 'pointer',
            }}
        >
            {loading ? (
                <>
                    <Loader2 className="vesper-spin" size={compact ? 14 : 16} />
                    Loading…
                </>
            ) : (
                <>
                    <Film size={compact ? 14 : 16} />
                    Trailer
                </>
            )}
        </button>
    );
}

const Bullet = () => (
    <span
        className="inline-block w-1 h-1 rounded-full"
        style={{ background: 'rgba(255,255,255,0.32)' }}
    />
);

/**
 * <CastHint/> — soft glass-pill that appears where the Play CTA
 * normally lives, inviting the user to press OK while they're
 * browsing the cast / filmography row.  Decorative only — never
 * focusable, never takes interactivity, just a friendly nudge.
 */
function CastHint({ line1, line2 }) {
    return (
        <div
            data-testid="cast-hint"
            className="mt-8 vesper-fade-up flex items-center gap-3"
            style={{ pointerEvents: 'none' }}
        >
            <div
                style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    padding: '10px 18px 10px 14px',
                    borderRadius: 9999,
                    background: 'rgba(13,18,28,0.6)',
                    border: '1px solid rgba(93,200,255,0.22)',
                    backdropFilter: 'blur(8px)',
                    WebkitBackdropFilter: 'blur(8px)',
                    boxShadow: '0 0 24px rgba(93,200,255,0.08)',
                }}
            >
                <span
                    style={{
                        width: 32, height: 32,
                        borderRadius: '50%',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        background: 'rgba(93,200,255,0.16)',
                        color: 'var(--vesper-blue-bright)',
                        fontSize: 13,
                        fontWeight: 700,
                        letterSpacing: '0.02em',
                        fontFamily: 'inherit',
                    }}
                >
                    OK
                </span>
                <div style={{ display: 'flex', flexDirection: 'column' }}>
                    <span
                        className="vesper-mono"
                        style={{
                            fontSize: 10,
                            letterSpacing: '0.22em',
                            color: 'var(--vesper-blue-bright)',
                            textTransform: 'uppercase',
                        }}
                    >
                        {line1}
                    </span>
                    <span
                        style={{
                            fontSize: 15,
                            fontWeight: 600,
                            color: 'var(--vesper-text)',
                            letterSpacing: '-0.005em',
                            lineHeight: 1.2,
                        }}
                    >
                        {line2}
                    </span>
                </div>
            </div>
        </div>
    );
}

/**
 * "In My List" indicator pill — non-interactive status badge that
 * appears next to Back when the title is already in the library.
 * Reads / live-syncs via `vesper:library-change`.  Adding /
 * removing now happens via the long-press confirm modal — this
 * pill only confirms the current state at a glance.
 */
function LibraryStatusPill({ id }) {
    const [inList, setInList] = React.useState(() => isInLibrary(id));

    React.useEffect(() => {
        const sync = () => setInList(isInLibrary(id));
        window.addEventListener('vesper:library-change', sync);
        sync();
        return () => window.removeEventListener('vesper:library-change', sync);
    }, [id]);

    if (!inList) return null;

    return (
        <div
            data-testid="library-status"
            className="flex items-center gap-2 h-11 px-5 rounded-full vesper-mono"
            style={{
                background: 'rgba(var(--vesper-blue-rgb), 0.18)',
                color: 'var(--vesper-blue-bright)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.55)',
                fontSize: 13,
                letterSpacing: '0.18em',
                textTransform: 'uppercase',
            }}
        >
            <Check size={16} strokeWidth={2.4} />
            In My List
        </div>
    );
}

const CenterMsg = ({ children }) => (
    <div
        className="w-screen h-[100dvh] min-h-screen flex flex-col items-center justify-center"
        style={{ color: 'var(--vesper-text-2)', fontSize: 18, gap: 12 }}
    >
        {children}
    </div>
);
