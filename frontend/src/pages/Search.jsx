import React, { useState } from 'react';
import { Loader2 } from 'lucide-react';
import SideNav from '@/components/SideNav';
import KidsSideNav from '@/components/KidsSideNav';
import FullscreenButton from '@/components/FullscreenButton';
import OnScreenKeyboard from '@/components/OnScreenKeyboard';
import PosterTile from '@/components/PosterTile';
import useSpatialFocus from '@/hooks/useSpatialFocus';
import { useAddons } from '@/hooks/useAddons';
import KidsBlockedMessage from '@/components/KidsBlockedMessage';
import { API, Vesper } from '@/lib/api';
import { isKidsActive } from '@/lib/profiles';

export default function Search() {
    useSpatialFocus();
    const kids = isKidsActive();
    const { addons } = useAddons();
    const [q, setQ] = useState('');
    const [results, setResults] = useState([]);
    const [busy, setBusy] = useState(false);

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
            const all = (json?.data || []);
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
                    ].filter(Boolean).join(' · '),
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

    const doSearch = async (query) => {
        if (!query || query.trim().length < 2) return;
        setBusy(true);
        const out = kids
            ? await doKidSearch(query.trim())
            : await doAddonSearch(query.trim());
        setResults(out);
        setBusy(false);
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

                <div className="mb-12 max-w-[760px]">
                    <OnScreenKeyboard
                        value={q}
                        placeholder={
                            kids ? 'Try "Bluey" or "Mario"…' : 'Title, actor, keyword…'
                        }
                        onChange={setQ}
                        onSubmit={(v) => doSearch(v)}
                        submitLabel={busy ? 'Searching…' : 'Search'}
                    />
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
                ) : results.length > 0 ? (
                    <>
                        <h2
                            className="vesper-display mb-5"
                            style={{
                                fontSize: 28,
                                letterSpacing: '-0.02em',
                            }}
                        >
                            {results.length} {kids ? 'kid-safe ' : ''}result
                            {results.length === 1 ? '' : 's'}
                        </h2>
                        <div className="flex flex-wrap gap-6">
                            {results.slice(0, 60).map((item) => (
                                <PosterTile key={item.id} item={item} />
                            ))}
                        </div>
                    </>
                ) : q && !busy && results.length === 0 ? (
                    kids ? (
                        <KidsBlockedMessage
                            query={q}
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
