import React, { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { API } from '@/lib/api';

/**
 * /v2ai-play?title=The%20Matrix&type=movie  →  /resolve/movie/603?autoplay=1
 *
 * v2.8.23 — Entry-point used by the launcher's V2 AI assistant
 * deep-link.  Search by free-text title, pick the top result whose
 * media_type matches the requested `type` (movie/series), then
 * redirect to /resolve/ which knows how to turn a TMDB id into an
 * IMDB id and continue into /title/... with `?autoplay=1` so
 * Detail.jsx's existing autoplay path kicks the addon stream search
 * + ExoPlayer fullscreen flow.
 *
 * If search returns nothing, we render a small "couldn't find that"
 * card and provide a button back to home.  No silent navigation
 * surprises.
 */
export default function V2AIResolve() {
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const title = (params.get('title') || '').trim();
    const requestedType = (params.get('type') || 'movie').toLowerCase();
    const [error, setError] = useState('');

    useEffect(() => {
        if (!title) {
            setError('No title provided.');
            return;
        }
        let cancelled = false;
        (async () => {
            try {
                const r = await fetch(
                    `${API}/tmdb/search?q=${encodeURIComponent(title)}`,
                    { cache: 'no-cache' },
                );
                const data = await r.json();
                if (cancelled) return;
                const items = (data?.data || []).filter(
                    (it) => it.type === 'movie' || it.type === 'series' || it.type === 'tv',
                );
                // Prefer items whose `type` matches the requested
                // intent (`movie` vs `series`), then fall back to
                // the most popular result.
                const want = requestedType === 'series' ? 'series' : 'movie';
                const exact = items.find(
                    (it) => it.type === want || (want === 'series' && it.type === 'tv'),
                );
                const pick  = exact || items[0];
                if (!pick) {
                    setError(`Couldn't find "${title}".`);
                    return;
                }
                const tmdbType = (pick.type === 'tv' || pick.type === 'series') ? 'tv' : 'movie';
                const tmdbId   = pick.tmdb_id || pick.id;
                navigate(
                    `/resolve/${tmdbType}/${tmdbId}?autoplay=1`,
                    { replace: true },
                );
            } catch {
                if (!cancelled) setError('Search failed — check Wi-Fi.');
            }
        })();
        return () => { cancelled = true; };
    }, [title, requestedType, navigate]);

    return (
        <div
            data-testid="v2ai-resolve-page"
            className="w-screen h-[100dvh] flex flex-col items-center justify-center gap-4"
            style={{ background: 'var(--vesper-bg-0)', color: 'var(--vesper-text-2)' }}
        >
            {!error ? (
                <>
                    <Loader2 className="vesper-spin" size={32} />
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 12,
                            letterSpacing: '0.22em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-text-3)',
                        }}
                    >
                        V2 AI · Loading {title}…
                    </div>
                </>
            ) : (
                <div
                    className="vesper-glass rounded-2xl"
                    style={{ padding: '24px 32px', maxWidth: 540 }}
                >
                    <div
                        className="vesper-display"
                        style={{
                            fontSize: 22,
                            color: 'var(--vesper-text)',
                            marginBottom: 8,
                        }}
                    >
                        {error}
                    </div>
                    <button
                        data-testid="v2ai-back-home"
                        data-focusable="true"
                        autoFocus
                        onClick={() => navigate('/', { replace: true })}
                        className="vesper-btn"
                        style={{ marginTop: 14 }}
                    >
                        Back to home
                    </button>
                </div>
            )}
        </div>
    );
}
