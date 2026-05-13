import React, { useRef, useState } from 'react';
import { Loader2, Search as SearchIcon } from 'lucide-react';
import SideNav from '@/components/SideNav';
import KidsSideNav from '@/components/KidsSideNav';
import FullscreenButton from '@/components/FullscreenButton';
import PosterTile from '@/components/PosterTile';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import KidsBlockedMessage from '@/components/KidsBlockedMessage';
import { API, Vesper } from '@/lib/api';
import { isKidsActive } from '@/lib/profiles';

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
    const inputRef = useRef(null);

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
                    paddingTop: 80,
                    paddingBottom: 80,
                }}
            >
                <div className="vesper-eyebrow mb-3">
                    {kids ? 'Kid-safe search' : 'Search'}
                </div>
                <h1
                    className="vesper-display mb-8"
                    style={{ fontSize: 'clamp(56px, 5.6vw, 80px)' }}
                >
                    {kids
                        ? 'What do you want to watch?'
                        : 'What are you looking for?'}
                </h1>

                <div
                    className="flex items-center gap-3 mb-12"
                    style={{ maxWidth: 760 }}
                >
                    <div
                        data-testid="search-input-wrap"
                        data-focusable="true"
                        data-focus-style="bare"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={() => inputRef.current?.focus()}
                        onKeyDown={(e) => {
                            // The wrap itself receives D-pad focus.  On
                            // OK / Enter we hand focus to the real
                            // <input> which makes Android pop the system
                            // keyboard.  This avoids slapping a ring on
                            // the input itself — the wrap stays visually
                            // calm and just changes its border tone.
                            if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault();
                                inputRef.current?.focus();
                            }
                        }}
                        className="flex items-center gap-3 flex-1"
                        style={{
                            height: 64,
                            padding: '0 22px',
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.14)',
                            transition:
                                'border-color 160ms ease, background 160ms ease',
                        }}
                    >
                        <SearchIcon
                            size={20}
                            strokeWidth={2}
                            color="var(--vesper-text-3)"
                        />
                        <input
                            ref={inputRef}
                            data-testid="search-input"
                            type="text"
                            value={q}
                            onChange={(e) => {
                                setQ(e.target.value);
                                if (searched) setSearched(false);
                            }}
                            onKeyDown={onInputKeyDown}
                            placeholder={
                                kids
                                    ? 'Try "Bluey" or "Mario"…'
                                    : 'Title, actor, keyword…'
                            }
                            className="vesper-display"
                            style={{
                                flex: 1,
                                background: 'transparent',
                                border: 'none',
                                outline: 'none',
                                fontSize: 20,
                                fontWeight: 500,
                                letterSpacing: '-0.01em',
                                color: 'var(--vesper-text)',
                                /* No focus ring on the input itself —
                                   the wrap handles the focus signal. */
                                boxShadow: 'none',
                                appearance: 'none',
                                WebkitAppearance: 'none',
                            }}
                        />
                    </div>
                    <button
                        data-testid="search-submit"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={() => doSearch()}
                        disabled={busy || q.trim().length < 2}
                        className="flex items-center gap-2 rounded-full"
                        style={{
                            height: 64,
                            padding: '0 28px',
                            background:
                                q.trim().length >= 2 && !busy
                                    ? 'var(--vesper-blue)'
                                    : 'rgba(255,255,255,0.08)',
                            color:
                                q.trim().length >= 2 && !busy
                                    ? 'var(--vesper-bg-0)'
                                    : 'var(--vesper-text-3)',
                            border: 'none',
                            fontSize: 15,
                            fontWeight: 700,
                            letterSpacing: '0.01em',
                            cursor:
                                q.trim().length >= 2 && !busy
                                    ? 'pointer'
                                    : 'not-allowed',
                        }}
                    >
                        {busy ? (
                            <Loader2
                                className="vesper-spin"
                                size={18}
                                strokeWidth={2.4}
                            />
                        ) : (
                            <SearchIcon size={18} strokeWidth={2.4} />
                        )}
                        Search
                    </button>
                </div>

                {!kids && !searchable.length && (
                    <p style={{ color: 'var(--vesper-text-2)' }}>
                        Install a searchable addon on Sources to enable search.
                    </p>
                )}

                {busy ? (
                    <div
                        className="flex items-center gap-3"
                        style={{ color: 'var(--vesper-text-2)' }}
                    >
                        <Loader2 className="vesper-spin" size={20} /> Searching…
                    </div>
                ) : searched && results.length > 0 ? (
                    <>
                        <h2
                            className="vesper-display mb-5"
                            style={{ fontSize: 28, letterSpacing: '-0.02em' }}
                        >
                            {results.length} {kids ? 'kid-safe ' : ''}result
                            {results.length === 1 ? '' : 's'}
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
                            style={{ color: 'var(--vesper-text-2)' }}
                        >
                            No results.
                        </p>
                    )
                ) : null}
            </main>
        </div>
    );
}
