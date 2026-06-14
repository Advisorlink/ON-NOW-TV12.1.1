import React, { useEffect, useState } from 'react';
import { Sparkles } from 'lucide-react';
import { API } from '@/lib/api';
import { getViewingStyle } from '@/lib/viewingStyle';
import Shelf from '@/components/Shelf';

/**
 * "For You" rail — newest popular TMDB titles matching the user's
 * viewing-style genres.  Sits between Continue Watching and the
 * Networks shelf on the Home page and hides itself when:
 *   - the active profile has no viewing-style preferences set, or
 *   - the backend returns zero items.
 *
 * The user picks their genres during the profile creation wizard
 * (step 4 of 6) and can manually flag favourite titles too.  We
 * fold those manual picks in at the front of the rail so the user
 * always sees them, then continue with TMDB recommendations.
 *
 * Refresh strategy: re-fetch whenever the active profile changes
 * (the `vesper:profile-change` event) or when the viewing-style
 * record is rewritten.
 */
export default function ForYouShelf() {
    const [shelf, setShelf] = useState(null);

    useEffect(() => {
        let cancel = false;

        const refresh = async () => {
            const vs = getViewingStyle();
            const hasGenres = vs.movieGenres.length > 0 || vs.tvGenres.length > 0;
            const hasItems = vs.items.length > 0;
            if (!hasGenres && !hasItems) {
                if (!cancel) setShelf(null);
                return;
            }

            // Pull "similar to what you picked" recommendations
            // (NOT the picks themselves — user explicitly asked we
            // never surface their own selections back at them).
            // Backend dedupes against the picks and caches for 24h
            // so the rail refreshes daily.
            let similarTiles = [];
            if (hasItems) {
                try {
                    const picksParam = vs.items
                        .map((it) => `${it.type === 'series' ? 'tv' : 'movie'}:${it.tmdb_id}`)
                        .join(',');
                    const r = await fetch(
                        `${API}/tmdb/similar-to-picks?picks=${encodeURIComponent(picksParam)}&limit=30`
                    );
                    if (r.ok) {
                        const json = await r.json();
                        const list = Array.isArray(json?.data) ? json.data : [];
                        similarTiles = list.map((it) => ({
                            id: `for-you-sim-${it.type}-${it.tmdb_id}`,
                            imdbId: null,
                            // v2.10.53 — Carry TMDB metadata so the
                            // long-press "Add to My List" gesture can
                            // resolve a real IMDB id on demand.
                            // Without these two fields PosterTile's
                            // long-press handler bails because the
                            // synthetic `for-you-sim-…` id doesn't
                            // start with `tt`.
                            tmdbId: it.tmdb_id,
                            tmdbType: it.type === 'series' ? 'tv' : 'movie',
                            type: it.type,
                            title: it.title,
                            sub: [
                                it.year,
                                it.rating ? `★ ${it.rating}` : null,
                            ].filter(Boolean).join(' · '),
                            poster: it.poster,
                            background: it.backdrop,
                            routePath: `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
                        }));
                    }
                } catch { /* ignore */ }
            }

            // Genre-based recommendations as the second half of the
            // rail.  Skipped if the user only flagged individual
            // titles and no genres.
            let recTiles = [];
            if (hasGenres) {
                try {
                    const q = new URLSearchParams({
                        movie_genres: vs.movieGenres.join(','),
                        tv_genres: vs.tvGenres.join(','),
                        limit: '20',
                    }).toString();
                    const r = await fetch(`${API}/tmdb/for-you?${q}`);
                    if (r.ok) {
                        const json = await r.json();
                        const list = Array.isArray(json?.data) ? json.data : [];
                        recTiles = list.map((it) => ({
                            id: `for-you-rec-${it.type}-${it.tmdb_id}`,
                            imdbId: null,
                            // v2.10.53 — see similarTiles above.
                            tmdbId: it.tmdb_id,
                            tmdbType: it.type === 'series' ? 'tv' : 'movie',
                            type: it.type,
                            title: it.title,
                            sub: [
                                it.year,
                                it.rating ? `★ ${it.rating}` : null,
                            ].filter(Boolean).join(' · '),
                            poster: it.poster,
                            background: it.backdrop,
                            routePath: `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`,
                        }));
                    }
                } catch { /* ignore */ }
            }

            // Build the rail: similar-to-picks first (leads with
            // collaborative recs from the user's hand-picked
            // titles), then genre-based fills the tail.  Dedupe by
            // routePath AND drop anything the user explicitly
            // chose — they shouldn't see their own picks here.
            const excludedRoutes = new Set(
                vs.items.map(
                    (it) => `/resolve/${it.type === 'series' ? 'tv' : 'movie'}/${it.tmdb_id}`
                )
            );
            const seen = new Set();
            const tiles = [];
            for (const t of [...similarTiles, ...recTiles]) {
                if (excludedRoutes.has(t.routePath)) continue;
                if (seen.has(t.routePath)) continue;
                seen.add(t.routePath);
                tiles.push(t);
            }
            if (cancel) return;
            if (tiles.length === 0) {
                setShelf(null);
                return;
            }
            setShelf({
                id: 'for-you',
                title: 'For You',
                eyebrow: 'SIMILAR TO WHAT YOU LOVE',
                items: tiles,
            });
        };

        refresh();
        const onChange = () => refresh();
        window.addEventListener('vesper:profile-change', onChange);
        window.addEventListener('vesper:viewing-style-change', onChange);
        return () => {
            cancel = true;
            window.removeEventListener('vesper:profile-change', onChange);
            window.removeEventListener('vesper:viewing-style-change', onChange);
        };
    }, []);

    if (!shelf) return null;
    return (
        <div data-testid="for-you-shelf" style={{ position: 'relative' }}>
            <Shelf shelf={shelf} />
            {/* Tiny sparkle accent over the eyebrow so the rail
                visually announces itself as personalised. */}
            <div
                aria-hidden="true"
                style={{
                    position: 'absolute',
                    top: 'clamp(20px, 1.85vw, 32px)',
                    left: 'calc(clamp(92px, 6.5vw, 132px) - 22px)',
                    color: 'var(--vesper-blue-bright)',
                    pointerEvents: 'none',
                }}
            >
                <Sparkles size={14} strokeWidth={2} />
            </div>
        </div>
    );
}
