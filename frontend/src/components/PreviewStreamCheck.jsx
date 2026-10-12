import React, { useEffect, useState } from 'react';
import { Loader2, Film, Eye, Sparkles } from 'lucide-react';
import { API, Vesper } from '@/lib/api';
import { tileTrailerRequest } from '@/lib/trailerEngine';

/** Verdict from the live stream count (user spec): 1–20 Cam copy,
 *  21–35 Watchable cam copy, 36+ HD copy.  Solid colour, white text. */
export function streamVerdict(count) {
    if (count >= 36) return { label: 'HD copy', bg: '#15903f', Icon: Sparkles };
    if (count >= 21) return { label: 'Watchable cam copy', bg: '#c47a06', Icon: Eye };
    if (count >= 1) return { label: 'Cam copy', bg: '#b9401c', Icon: Film };
    return { label: 'No streams yet', bg: '#3a4356', Icon: Film };
}

async function resolveImdb(req, signal) {
    if (req.imdbId.startsWith('tt')) return req.imdbId;
    if (!req.tmdbId) return '';
    const r = await fetch(`${API}/tmdb/imdb/${req.type === 'tv' ? 'tv' : 'movie'}/${req.tmdbId}`, { signal });
    const data = r.ok ? await r.json() : null;
    const tt = data?.imdb_id || '';
    return tt.startsWith('tt') ? tt : '';
}

/** Badge in the top-right of the playing trailer card.  The stream
 *  search starts only when `active` (trailer playing) flips true and is
 *  aborted the instant the card changes; nothing is cached on purpose
 *  so each dwell re-checks and the user can watch a title go HD. */
export default function PreviewStreamCheck({ tile, active }) {
    const [state, setState] = useState(null); // {status:'checking'|'done'|'error', count}

    useEffect(() => {
        setState(null);
        if (!active || !tile) return undefined;
        const ac = new AbortController();
        const { signal } = ac;
        const req = tileTrailerRequest(tile);
        setState({ status: 'checking', count: 0 });
        (async () => {
            try {
                const imdb = await resolveImdb(req, signal);
                if (signal.aborted) return;
                if (!imdb) { setState({ status: 'error', count: 0 }); return; }
                const isSeries = req.type === 'tv';
                const type = isSeries ? 'series' : 'movie';
                const id = isSeries ? `${imdb}:1:1` : imdb;
                let latest = 0;
                const res = await Vesper.getStreams(type, id, (partial) => {
                    if (signal.aborted) return;
                    latest = partial.length;
                    setState({ status: 'checking', count: latest });
                }, { signal });
                if (signal.aborted) return;
                setState({ status: 'done', count: Math.max(latest, res?.streams?.length || 0) });
            } catch {
                if (!signal.aborted) setState((s) => ({ status: 'done', count: s?.count || 0 }));
            }
        })();
        return () => ac.abort();
    }, [tile, active]);

    if (!state) return null;
    const checking = state.status === 'checking';
    const v = streamVerdict(state.count);
    // Spinner + "Checking stream quality" for the whole search; the
    // verdict only lands once every source has answered.
    const showVerdict = !checking;
    const Icon = checking ? Loader2 : v.Icon;
    // Sit just under the poster's CINEMA tag when the tile has one.
    const hasCinema = !!tile?.querySelector('[data-testid="release-tag-badge"]');
    return (
        <div
            data-testid="preview-stream-check"
            data-status={state.status}
            data-count={state.count}
            className="vesper-mono"
            style={{
                position: 'absolute',
                top: hasCinema ? 34 : 8,
                left: 8,
                zIndex: 4,
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                padding: '3px 8px 3px 6px',
                borderRadius: 7,
                background: showVerdict ? v.bg : 'rgba(8, 12, 18, 0.72)',
                color: '#fff',
                fontSize: 9,
                lineHeight: 1,
                letterSpacing: '0.12em',
                textTransform: 'uppercase',
                fontWeight: 700,
                boxShadow: '0 2px 10px rgba(0,0,0,0.45)',
                animation: 'vesper-hoverprev-in 160ms ease-out both',
                pointerEvents: 'none',
                whiteSpace: 'nowrap',
            }}
        >
            <Icon size={10} strokeWidth={2.6} className={checking ? 'vesper-spin' : undefined} />
            <span data-testid="preview-stream-check-label">
                {showVerdict ? v.label : 'Checking stream quality'}
            </span>
        </div>
    );
}
