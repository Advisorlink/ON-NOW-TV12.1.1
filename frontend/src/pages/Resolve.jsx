import React, { useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Loader2 } from 'lucide-react';
import { API } from '@/lib/api';

/**
 * /resolve/:type/:tmdb_id  →  /title/:appType/:imdb_id
 *
 * Used by hero billboard items that came from TMDB Trending (we don't
 * know the IMDB id up front).  Looks up the imdb_id via the backend
 * and replaces the URL so back-button doesn't loop.
 */
export default function Resolve() {
    const { type, id } = useParams();
    const navigate = useNavigate();

    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                const r = await fetch(
                    `${API}/tmdb/imdb/${type}/${id}`,
                    { cache: 'force-cache' }
                );
                const data = await r.json();
                if (cancel) return;
                const imdbId = data?.imdb_id;
                const appType = type === 'tv' ? 'series' : 'movie';
                if (imdbId) {
                    navigate(`/title/${appType}/${imdbId}`, { replace: true });
                } else {
                    navigate('/', { replace: true });
                }
            } catch {
                if (!cancel) navigate('/', { replace: true });
            }
        })();
        return () => {
            cancel = true;
        };
    }, [type, id, navigate]);

    return (
        <div
            data-testid="resolve-page"
            className="w-screen h-[100dvh] min-h-screen flex flex-col items-center justify-center gap-3"
            style={{ background: 'var(--vesper-bg-0)', color: 'var(--vesper-text-2)' }}
        >
            <Loader2 className="vesper-spin" size={28} />
            <div
                className="vesper-mono"
                style={{
                    fontSize: 12,
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    color: 'var(--vesper-text-3)',
                }}
            >
                Resolving title…
            </div>
        </div>
    );
}
