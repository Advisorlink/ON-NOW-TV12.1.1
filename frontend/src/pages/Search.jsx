import React, { useRef, useState } from 'react';
import { Loader2, Search as SearchIcon, Mic, MicOff, ArrowRight } from 'lucide-react';
import SideNav from '@/components/SideNav';
import KidsSideNav from '@/components/KidsSideNav';
import FullscreenButton from '@/components/FullscreenButton';
import PosterTile from '@/components/PosterTile';
import TVKeyboard from '@/components/TVKeyboard';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import KidsBlockedMessage from '@/components/KidsBlockedMessage';
import { API, Vesper } from '@/lib/api';
import { isKidsActive } from '@/lib/profiles';
import Host from '@/lib/host';

/**
 * Single native text input + a Search button.  We deliberately do NOT
 * ship an on-screen keyboard:
 *   • Android TV / HK1 boxes already pop the system keyboard when an
 *     <input> is focused with a D-pad OK press.
 *   • A custom OSK adds friction on devices that have native input.
 * The "blocked / no-results" message only renders AFTER the user has
 * pressed Search once, so partial typing never accuses them of being
 * naughty halfway through a perfectly valid kid-safe query.
 */
export default function Search() {
    useSpatialFocus();
    const kids = isKidsActive();
    const { addons } = useAddons();
    const [q, setQ] = useState('');
    const [results, setResults] = useState([]);
    const [busy, setBusy] = useState(false);
    const [searched, setSearched] = useState(false);
    const [lastQuery, setLastQuery] = useState('');
    const [listening, setListening] = useState(false);
    const [voiceError, setVoiceError] = useState('');
    const inputRef = useRef(null);
    const voiceAvailable = Host.isVoiceSearchAvailable();

    const searchable = addons.flatMap((a) =>
        (a.catalogs || [])
            .filter(
                (c) =>
                    Array.isArray(c.extra) &&
                    c.extra.some((e) => e?.name === 'search')
            )
            .map((c) => ({ addon: a, catalog: c }))
    );

    const doKidSearch = async (query) => {
        try {
            const { getKidsConfig } = await import('@/lib/profiles');
            const cfg = getKidsConfig();
            const params = new URLSearchParams({
                q: query,
                movie_cert: cfg.maxRatingMovie,
                tv_level: cfg.maxRatingSeries,
            }).toString();
            const r = await fetch(`${API}/tmdb/kids/search?${params}`);
            if (!r.ok) return [];
            const json = await r.json();
            const all = json?.data || [];
            const typeMask =
                cfg.contentTypes === 'movies'
                    ? 'movie'
                    : cfg.contentTypes === 'series'
                    ? 'series'
                    : null;
            return all
                .filter((it) => (typeMask ? it.type === typeMask : true))
                .map((it) => ({
                    id: `kids-search-${it.tmdb_id}`,
                    imdbId: null,
                    type: it.type,
                    title: it.title,
                    sub: [
                        it.year,
                        it.rating ? `★ ${it.rating}` : null,
                    ]
                        .filter(Boolean)
                        .join(' · '),
                    poster: it.poster,
                    routePath: `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
                }));
        } catch {
            return [];
        }
    };

    const doAddonSearch = async (query) => {
        const out = [];
        await Promise.all(
            searchable.map(async ({ addon, catalog }) => {
                try {
                    const res = await Vesper.getCatalog(
                        addon.id,
                        catalog.type,
                        catalog.id,
                        { search: query }
                    );
                    const metas = res?.data?.metas || [];
                    metas.forEach((m) =>
                        out.push({
                            id: `${addon.id}-${m.id}`,
                            imdbId: m.id,
                            type: catalog.type,
                            title: m.name,
                            sub: [
                                m.releaseInfo,
                                m.imdbRating ? `★ ${m.imdbRating}` : null,
                            ]
                                .filter(Boolean)
                                .join(' · '),
                            poster: m.poster,
                            routePath: `/title/${catalog.type}/${m.id}`,
                        })
                    );
                } catch {
                    /* skip */
                }
            })
        );
        return out;
    };

    const doSearch = async (raw) => {
        const query = (raw ?? q).trim();
        if (query.length < 2) return;
        setBusy(true);
        setSearched(true);
        setLastQuery(query);
        const out = kids
            ? await doKidSearch(query)
            : await doAddonSearch(query);
        setResults(out);
        setBusy(false);
    };

    const onInputKeyDown = (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            doSearch();
        }
    };

    const onMicClick = async () => {
        if (listening) return;
        setVoiceError('');
        setListening(true);
        try {
            const text = await Host.voiceSearch();
            const clean = (text || '').trim();
            if (!clean) {
                setVoiceError("Sorry, I didn't catch that.");
            } else {
                setQ(clean);
                // Auto-run the search the moment we have a clean phrase.
                doSearch(clean);
            }
        } catch (err) {
            const msg = err?.message || 'error';
            if (msg === 'cancelled') setVoiceError('');
            else if (msg === 'unsupported')
                setVoiceError('Voice search not available on this device.');
            else if (msg === 'empty')
                setVoiceError("Sorry, I didn't catch that.");
            else setVoiceError("Couldn't hear you — try again.");
        } finally {
            setListening(false);
        }
    };

    return (
        <div
            data-testid="search-page"
            data-kids-theme={kids ? '1' : undefined}
            className={`relative w-screen h-[100dvh] min-h-screen overflow-hidden ${
                kids ? 'vesper-kids-root' : ''
            }`}
            style={kids ? { background: 'var(--vesper-bg-0)' } : undefined}
        >
            {kids ? <KidsSideNav /> : <SideNav />}
            <FullscreenButton />

            <main
                className="absolute inset-0 overflow-y-auto"
                style={{
                    paddingLeft: 180,
                    paddingRight: 80,
                    paddingTop: 'clamp(28px, 3vw, 56px)',
                    paddingBottom: 80,
                }}
            >
                {/* Search input card — mirrors the Profile name
                    step.  Centered column, avatar-style icon at
                    top, big display title, pill input, TVKeyboard
                    below, action button at the bottom.  Only shown
                    when the user hasn't searched yet OR has no
                    results to display, so the page collapses into
                    the results grid once content arrives. */}
                {/* Hide the search hero (keyboard + input + submit)
                    in kids mode when the search came back empty —
                    that's how we signal "not allowed".  The
                    KidsBlockedMessage below is the only thing the
                    child should see, so they can't just retype the
                    blocked title.  For non-kids profiles the hero
                    stays visible so users can refine and retry. */}
                {((!searched) || (!kids && results.length === 0)) && !busy && (
                    <div
                        data-testid="search-card"
                        className="flex flex-col items-center"
                        style={{
                            width: '100%',
                            position: 'relative',
                            marginBottom: 32,
                        }}
                    >
                        {/* Faint blue glow behind the card */}
                        <div
                            style={{
                                position: 'absolute',
                                inset: '0 18% auto 18%',
                                height: '32vh',
                                background:
                                    'radial-gradient(60% 60% at 50% 0%, rgba(var(--vesper-blue-rgb),0.18) 0%, transparent 70%)',
                                pointerEvents: 'none',
                                filter: 'blur(20px)',
                            }}
                        />

                        <div
                            className="flex flex-col items-center"
                            style={{
                                maxWidth: 760,
                                width: '100%',
                                position: 'relative',
                                zIndex: 1,
                                gap: 10,
                            }}
                        >
                            {/* Big circular search icon — visually
                                matches the AvatarCircle on the
                                profile name step. */}
                            <div
                                style={{
                                    width: 84,
                                    height: 84,
                                    borderRadius: 999,
                                    background:
                                        'radial-gradient(circle at 30% 30%, rgba(var(--vesper-blue-rgb),0.35) 0%, rgba(var(--vesper-blue-rgb),0.12) 70%)',
                                    border: '2px solid rgba(var(--vesper-blue-rgb),0.55)',
                                    boxShadow:
                                        '0 12px 36px rgba(var(--vesper-blue-rgb),0.35)',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    color: 'var(--vesper-blue-bright)',
                                }}
                            >
                                <SearchIcon size={36} strokeWidth={1.8} />
                            </div>

                            <div
                                className="vesper-mono"
                                style={{
                                    fontSize: 11,
                                    letterSpacing: '0.32em',
                                    color: 'var(--vesper-blue-bright)',
                                    textTransform: 'uppercase',
                                }}
                            >
                                {kids ? 'Kid-safe search' : 'Search'}
                            </div>

                            <h1
                                className="vesper-display"
                                style={{
                                    fontSize: 'clamp(26px, 3vw, 44px)',
                                    letterSpacing: '-0.02em',
                                    lineHeight: 1.05,
                                    textAlign: 'center',
                                }}
                            >
                                {kids ? (
                                    <>
                                        What do you{' '}
                                        <span
                                            style={{
                                                color: 'var(--vesper-blue-bright)',
                                                textShadow:
                                                    '0 0 14px rgba(var(--vesper-blue-rgb),0.55)',
                                            }}
                                        >
                                            want
                                        </span>{' '}
                                        to watch?
                                    </>
                                ) : (
                                    <>
                                        What are you{' '}
                                        <span
                                            style={{
                                                color: 'var(--vesper-blue-bright)',
                                                textShadow:
                                                    '0 0 14px rgba(var(--vesper-blue-rgb),0.55)',
                                            }}
                                        >
                                            looking
                                        </span>{' '}
                                        for?
                                    </>
                                )}
                            </h1>

                            {/* Display-only query preview pill —
                                no real <input>, so the Android
                                IME stays buried.  Typing routes
                                through TVKeyboard below. */}
                            <div
                                data-testid="search-input-wrap"
                                className="flex items-center gap-3"
                                style={{
                                    width: '100%',
                                    maxWidth: 560,
                                    height: 64,
                                    padding: '0 24px',
                                    borderRadius: 999,
                                    background:
                                        'linear-gradient(180deg, rgba(255,255,255,0.07) 0%, rgba(255,255,255,0.03) 100%)',
                                    border: '1px solid rgba(var(--vesper-blue-rgb),0.35)',
                                    boxShadow:
                                        '0 10px 36px rgba(var(--vesper-blue-rgb),0.18)',
                                    marginTop: 4,
                                }}
                            >
                                <SearchIcon
                                    size={20}
                                    strokeWidth={2}
                                    color="var(--vesper-blue-bright)"
                                />
                                <div
                                    data-testid="search-input"
                                    ref={inputRef}
                                    className="vesper-display"
                                    style={{
                                        flex: 1,
                                        fontSize: 22,
                                        fontWeight: 500,
                                        letterSpacing: '-0.01em',
                                        color: q
                                            ? 'var(--vesper-text)'
                                            : 'var(--vesper-text-3)',
                                        whiteSpace: 'nowrap',
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                    }}
                                >
                                    {q ||
                                        (listening
                                            ? 'Listening…'
                                            : kids
                                            ? 'Try "Bluey" or "Mario"…'
                                            : 'Title, actor, keyword…')}
                                    <span
                                        aria-hidden="true"
                                        style={{
                                            display: 'inline-block',
                                            width: 2,
                                            height: 22,
                                            marginLeft: 4,
                                            verticalAlign: 'middle',
                                            background: 'var(--vesper-blue-bright)',
                                            animation: 'vesperPulse 1100ms infinite',
                                            borderRadius: 1,
                                        }}
                                    />
                                </div>
                                {voiceAvailable && (
                                    <button
                                        data-testid="search-mic"
                                        data-focusable="true"
                                        data-focus-style="bare"
                                        tabIndex={0}
                                        onClick={(e) => {
                                            e.stopPropagation();
                                            onMicClick();
                                        }}
                                        className="flex items-center justify-center rounded-full shrink-0"
                                        aria-label={
                                            listening ? 'Listening' : 'Voice search'
                                        }
                                        style={{
                                            width: 40,
                                            height: 40,
                                            background: listening
                                                ? 'var(--vesper-blue)'
                                                : 'rgba(255,255,255,0.08)',
                                            border: '1px solid rgba(255,255,255,0.16)',
                                            color: listening
                                                ? 'var(--vesper-bg-0)'
                                                : 'var(--vesper-text)',
                                            cursor: 'pointer',
                                        }}
                                    >
                                        <Mic
                                            size={16}
                                            strokeWidth={2.4}
                                            className={listening ? 'vesper-pulse' : ''}
                                        />
                                    </button>
                                )}
                                <span
                                    className="vesper-mono"
                                    style={{
                                        fontSize: 11,
                                        letterSpacing: '0.22em',
                                        color: 'var(--vesper-text-3)',
                                        textTransform: 'uppercase',
                                    }}
                                >
                                    {q.length}/60
                                </span>
                            </div>

                            {(listening || voiceError) && (
                                <div
                                    data-testid="search-voice-status"
                                    className="flex items-center gap-2"
                                    style={{
                                        color: listening
                                            ? 'var(--vesper-blue-bright)'
                                            : '#FCA5A5',
                                        fontSize: 13,
                                        letterSpacing: '0.02em',
                                    }}
                                >
                                    {listening ? (
                                        <>
                                            <Mic
                                                size={14}
                                                strokeWidth={2.4}
                                                className="vesper-pulse"
                                            />
                                            Listening — say a movie or show…
                                        </>
                                    ) : (
                                        <>
                                            <MicOff size={14} strokeWidth={2} />
                                            {voiceError}
                                        </>
                                    )}
                                </div>
                            )}

                            {/* Themed on-screen keyboard — same
                                component used on the profile name
                                step.  Replaces the Android IME. */}
                            <div style={{ marginTop: 4, width: '100%', maxWidth: 720 }}>
                                <TVKeyboard
                                    value={q}
                                    onChange={(v) => {
                                        setQ(v);
                                        if (searched) setSearched(false);
                                    }}
                                    onSubmit={() => {
                                        if (q.trim().length >= 2) doSearch();
                                    }}
                                    maxLength={60}
                                    variant="name"
                                />
                            </div>

                            <button
                                data-testid="search-submit"
                                data-focusable="true"
                                data-focus-style="pill"
                                tabIndex={0}
                                onClick={() => doSearch()}
                                disabled={busy || q.trim().length < 2}
                                className="flex items-center gap-2 rounded-full font-sans font-semibold"
                                style={{
                                    marginTop: 4,
                                    height: 50,
                                    padding: '0 30px',
                                    fontSize: 15,
                                    background:
                                        q.trim().length >= 2 && !busy
                                            ? 'var(--vesper-blue)'
                                            : 'rgba(var(--vesper-blue-rgb),0.25)',
                                    color: 'var(--vesper-bg-0)',
                                    border: 'none',
                                    opacity:
                                        q.trim().length >= 2 && !busy ? 1 : 0.6,
                                    cursor:
                                        q.trim().length >= 2 && !busy
                                            ? 'pointer'
                                            : 'not-allowed',
                                    boxShadow:
                                        q.trim().length >= 2 && !busy
                                            ? '0 12px 30px rgba(var(--vesper-blue-rgb),0.45)'
                                            : 'none',
                                }}
                            >
                                {busy ? (
                                    <Loader2
                                        className="vesper-spin"
                                        size={16}
                                        strokeWidth={2.5}
                                    />
                                ) : (
                                    <SearchIcon size={16} strokeWidth={2.5} />
                                )}
                                {kids ? 'Find something to watch' : 'Search'}
                                {!busy && <ArrowRight size={16} strokeWidth={2.5} />}
                            </button>

                            {!kids && !searchable.length && (
                                <p
                                    style={{
                                        color: 'var(--vesper-text-2)',
                                        fontSize: 13,
                                        marginTop: 4,
                                        textAlign: 'center',
                                    }}
                                >
                                    Install a searchable addon on Sources to
                                    enable search.
                                </p>
                            )}
                        </div>
                    </div>
                )}

                {busy ? (
                    <div
                        className="flex items-center gap-3"
                        style={{
                            color: 'var(--vesper-text-2)',
                            marginTop: 40,
                            justifyContent: 'center',
                        }}
                    >
                        <Loader2 className="vesper-spin" size={20} /> Searching…
                    </div>
                ) : searched && results.length > 0 ? (
                    <>
                        <h2
                            className="vesper-display mb-5"
                            style={{
                                fontSize: 28,
                                letterSpacing: '-0.02em',
                                marginTop: 16,
                            }}
                        >
                            {results.length} {kids ? 'kid-safe ' : ''}result
                            {results.length === 1 ? '' : 's'} for &ldquo;{lastQuery}&rdquo;
                        </h2>
                        <div className="flex flex-wrap gap-6">
                            {results.slice(0, 80).map((item) => (
                                <PosterTile key={item.id} item={item} />
                            ))}
                        </div>
                    </>
                ) : searched && results.length === 0 ? (
                    kids ? (
                        <KidsBlockedMessage
                            query={lastQuery}
                            onPick={(s) => {
                                setQ(s);
                                doSearch(s);
                            }}
                        />
                    ) : (
                        <p
                            data-testid="search-empty"
                            style={{
                                color: 'var(--vesper-text-2)',
                                textAlign: 'center',
                                marginTop: 8,
                            }}
                        >
                            No results for &ldquo;{lastQuery}&rdquo;.
                        </p>
                    )
                ) : null}
            </main>
        </div>
    );
}
