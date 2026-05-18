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
} from 'lucide-react';
import FullscreenButton from '@/components/FullscreenButton';
import SeriesEpisodes from '@/components/SeriesEpisodes';
import PartyJoiningScreen from '@/components/PartyJoiningScreen';
import Host from '@/lib/host';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { API, Vesper } from '@/lib/api';
import { qualityBadge, qualityTags, toneColors, is1080p, is4K } from '@/lib/streamMeta';
import { getAutoplay1080p } from '@/lib/prefs';
import { isKidsActive } from '@/lib/profiles';
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

    /* Resolve the IMDB id → TMDB id removed — no longer needed
       now that Cast row / Recommendations row are gone.  We
       kept the rest of the streaming flow intact. */

    useEffect(() => {
        if (type === 'series') {
            // Series streams are fetched per-episode inside <SeriesEpisodes>
            setStreamLoading(false);
            return;
        }
        let cancel = false;
        (async () => {
            setStreamLoading(true);
            if (partyCode) partyBreadcrumb('streams:fetch-start', { type, id });
            try {
                const s = await Vesper.getStreams(type, id);
                if (!cancel) {
                    setStreams(s?.streams || []);
                    setDiagnostics(s?.diagnostics || []);
                    if (partyCode) {
                        partyBreadcrumb('streams:fetch-done', {
                            count: (s?.streams || []).length,
                        });
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
    }, [type, id, partyCode]);

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
        if (autoplayCandidate) playStream(autoplayCandidate);
    };

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
    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!partyCode) return;
        if (!autoplayRequested) return;
        if (type === 'series') return; // series still uses per-episode flow
        if (streamLoading) return;
        if (!streams || streams.length === 0) return;
        // Filter out 4K — the user explicitly asked us never to
        // autoplay 4K (bandwidth + TV-decoder constraints on HK1).
        // Falls back to streams[0] only if EVERY stream is 4K.
        const non4k = streams.filter((s) => !is4K(s));
        const pool = non4k.length > 0 ? non4k : streams;
        // Pick the best stream: prefer 1080p direct → any 1080p →
        // first direct → first torrent → first anything.
        const pick =
            pool.find((s) => streamMode(s) === 'direct' && is1080p(s)) ||
            pool.find((s) => is1080p(s)) ||
            pool.find((s) => streamMode(s) === 'direct') ||
            pool.find((s) => streamMode(s) === 'torrent') ||
            pool[0];
        if (!pick) return;
        partyBreadcrumb('party-autoplay:fire', { partyCode, mode: streamMode(pick), name: pick.name });
        autoplayFiredRef.current = true;
        setAutoplayFired(true);
        // Defer one tick so React has time to commit the streams list
        // before we navigate / launch the native player.  Use a
        // window timer (not a cleanup-tracked timeout) so a state
        // change triggered by setAutoplayFired can't cancel it.
        window.setTimeout(() => playStream(pick), 30);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [partyCode, autoplayRequested, streams, streamLoading, type]);

    // ---------- PARTY AUTOPLAY WATCHDOG ----------
    // Safety net: if for ANY reason the partyAutoplay useEffect above
    // didn't run within 5 seconds of streams loading (React batching
    // edge case, stale-closure, hot-reload, etc.), re-attempt the
    // pick + playStream here.  This is the difference between "guest
    // sat on the picker waiting" and "guest's player launched".
    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!partyCode) return;
        if (!autoplayRequested) return;
        if (type === 'series') return;
        if (streamLoading) return;
        if (!streams || streams.length === 0) return;
        const watchdog = setTimeout(() => {
            if (autoplayFiredRef.current) return;
            const non4k = streams.filter((s) => !is4K(s));
            const pool = non4k.length > 0 ? non4k : streams;
            const pick =
                pool.find((s) => streamMode(s) === 'direct' && is1080p(s)) ||
                pool.find((s) => is1080p(s)) ||
                pool.find((s) => streamMode(s) === 'direct') ||
                pool.find((s) => streamMode(s) === 'torrent') ||
                pool[0];
            if (!pick) return;
            partyBreadcrumb('party-autoplay:watchdog-fire', { partyCode });
            autoplayFiredRef.current = true;
            setAutoplayFired(true);
            playStream(pick);
        }, 5000);
        return () => clearTimeout(watchdog);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [partyCode, autoplayRequested, streams, streamLoading, type]);

    useEffect(() => {
        if (autoplayFiredRef.current) return;
        if (!autoplayRequested) return;
        if (type === 'series') return; // series uses per-episode flow
        if (streamLoading) return;
        if (partyCode) return; // handled by the dedicated party useEffect above
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
                    return;
                }
                const non4k = list.filter((s) => !is4K(s));
                const pool = non4k.length > 0 ? non4k : list;
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
            const partyRole = partyCode
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
                    role: partyRole || '(none)',
                    memberId: partyMemberId ? 'present' : 'missing',
                    wsUrl: partyWsUrl ? 'present' : 'missing',
                });
            }
            if (
                Host.playVideo({
                    url: playUrl,
                    title: playTitle,
                    type: type,
                    subtitleUrl,
                    poster: meta?.poster || '',
                    backdrop: meta?.background || meta?.poster || '',
                    synopsis: meta?.description || '',
                    year: meta?.releaseInfo || meta?.year || '',
                    rating: meta?.imdbRating || '',
                    runtime: meta?.runtime || '',
                    genres: meta?.genres || [],
                    startAtMs: partyCode
                        ? Number(partyPositionMs) || 0
                        : startAtMs,
                    cwId: playId,
                    partyCode: partyCode || undefined,
                    partyRole: partyRole || undefined,
                    partyMemberId: partyMemberId || undefined,
                    partyWsUrl: partyWsUrl || undefined,
                })
            ) {
                if (partyCode) partyBreadcrumb('playStream:native-launched', {});
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
    if (partyCode && !autoplayFired) {
        const seriesLabel = (type === 'series' && partySeason && partyEpisode)
            ? ` · S${String(partySeason).padStart(2, '0')}E${String(partyEpisode).padStart(2, '0')}`
            : '';
        const noStreams = !streamLoading && streams.length === 0 && type !== 'series';
        return (
            <PartyJoiningScreen
                title={meta ? (meta.name || '') + seriesLabel : 'Your watch party is starting'}
                poster={meta?.poster}
                backdrop={meta?.background || meta?.poster}
                loading={loading || streamLoading}
                noStreams={noStreams}
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

            {/* Backdrop — the title's own backdrop fills the hero.
                Simple darkened image with a vertical gradient so
                the title + synopsis read cleanly on top. */}
            <div
                className="absolute inset-0"
                style={{
                    backgroundImage: `url(${meta.background || meta.poster || ''})`,
                    backgroundSize: 'cover',
                    backgroundPosition: 'center',
                    filter: 'brightness(0.6) saturate(1.1)',
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(180deg,
                          rgba(6,8,15,0.55) 0%,
                          rgba(6,8,15,0.4) 30%,
                          rgba(6,8,15,0.85) 70%,
                          var(--vesper-bg-0) 100%)`,
                }}
            />
            <div
                className="absolute inset-0"
                style={{
                    background: `linear-gradient(90deg,
                          rgba(6,8,15,0.92) 0%,
                          rgba(6,8,15,0.65) 35%,
                          rgba(6,8,15,0.1) 70%,
                          rgba(6,8,15,0) 100%)`,
                }}
            />

            <main
                className="relative z-10 w-full h-full overflow-y-auto"
                data-no-row-snap="true"
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
                    <LibraryStatusPill id={id} />
                </div>

                <div
                    className="max-w-[68vw] vesper-fade-up"
                >
                    {meta.imdb_id && (
                        <div className="vesper-eyebrow mb-3">
                            {type} · {meta.imdb_id}
                        </div>
                    )}
                    <h1
                        className="vesper-display"
                        data-testid="detail-title"
                        style={{
                            fontSize: 'clamp(44px, 4.6vw, 72px)',
                            letterSpacing: '-0.035em',
                            lineHeight: 1.05,
                        }}
                    >
                        {meta.name}
                    </h1>

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

                    {meta.description ? (
                        <p
                            className="mt-6 max-w-[58ch]"
                            style={{
                                fontSize: 17,
                                lineHeight: 1.55,
                                color: 'var(--vesper-text-2)',
                            }}
                        >
                            {meta.description}
                        </p>
                    ) : null}

                    {/* AUTOPLAY MODE — when on, show a big Play
                        button that fires the same ?autoplay=1 flow
                        (auto-pick first 1080p direct stream). */}
                    {type === 'movie' && autoplayEnabled && (
                        <div className="mt-8 flex items-center gap-3 flex-wrap">
                            <button
                                data-testid="detail-play-autoplay"
                                data-focusable="true"
                                data-focus-style="pill"
                                data-initial-focus="true"
                                tabIndex={0}
                                onClick={triggerAutoplay}
                                disabled={
                                    streamLoading || autoplayCandidate === null
                                }
                                className="vesper-pulse-cta flex items-center gap-2.5 rounded-full font-sans font-semibold"
                                style={{
                                    height: 'clamp(50px, 4vw, 60px)',
                                    paddingLeft: 'clamp(24px, 1.8vw, 32px)',
                                    paddingRight: 'clamp(28px, 2.2vw, 38px)',
                                    fontSize: 'clamp(15px, 1.15vw, 18px)',
                                    background:
                                        streamLoading ||
                                        autoplayCandidate === null
                                            ? 'rgba(255,255,255,0.10)'
                                            : 'var(--vesper-blue)',
                                    color:
                                        streamLoading ||
                                        autoplayCandidate === null
                                            ? 'var(--vesper-text-2)'
                                            : 'var(--vesper-bg-0)',
                                    opacity:
                                        streamLoading ||
                                        autoplayCandidate === null
                                            ? 0.7
                                            : 1,
                                }}
                            >
                                {streamLoading ? (
                                    <>
                                        <Loader2
                                            className="vesper-spin"
                                            size={18}
                                        />
                                        Finding 1080p…
                                    </>
                                ) : autoplayCandidate ? (
                                    <>
                                        <Play size={18} fill="currentColor" />
                                        Play 1080p
                                    </>
                                ) : (
                                    <>
                                        <Play size={18} />
                                        No 1080p stream found
                                    </>
                                )}
                            </button>
                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 11,
                                    color: 'var(--vesper-text-3)',
                                    letterSpacing: '0.18em',
                                    textTransform: 'uppercase',
                                    paddingLeft: 8,
                                }}
                            >
                                Autoplay ON · turn off in side menu for picker
                            </div>
                        </div>
                    )}

                    {/* Stream picker (movies) / Episode browser (series). */}
                    {type === 'series' ? (
                        <SeriesEpisodes
                            meta={meta}
                            parentId={id}
                            initialSeason={focusSeason ? Number(focusSeason) : undefined}
                            highlightEpisode={focusEpisode ? Number(focusEpisode) : undefined}
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
                                                            'rgba(13,18,28,0.78)',
                                                        border:
                                                            '1px solid rgba(255,255,255,0.06)',
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
                                                            }}
                                                        >
                                                            {titleLine}
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
                                                        {chips.length > 0 && (
                                                            <div
                                                                className="flex flex-wrap items-center"
                                                                style={{
                                                                    gap: 8,
                                                                    marginTop: 12,
                                                                }}
                                                            >
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
        </div>
    );
}

const Bullet = () => (
    <span
        className="inline-block w-1 h-1 rounded-full"
        style={{ background: 'rgba(255,255,255,0.32)' }}
    />
);

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
