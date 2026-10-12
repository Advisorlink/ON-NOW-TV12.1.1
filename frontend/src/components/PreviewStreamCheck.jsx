import React, { useEffect, useState } from 'react';
import { Loader2, Film, Eye, Sparkles } from 'lucide-react';
import { API, Vesper } from '@/lib/api';
import { tileTrailerRequest } from '@/lib/trailerEngine';

/** Verdict from the live stream count (user spec): 1–20 Cam copy,
 *  21–50 Watchable cam copy, 51+ HD copy. */
export function streamVerdict(count) {
    if (count >= 51) return { label: 'HD copy', color: '#7CF1A1', bg: 'rgba(22, 92, 52, 0.82)', Icon: Sparkles };
    if (count >= 21) return { label: 'Watchable cam copy', color: '#FFD36B', bg: 'rgba(104, 74, 10, 0.82)', Icon: Eye };
    if (count >= 1) return { label: 'Cam copy', color: '#FF9B6B', bg: 'rgba(110, 40, 20, 0.82)', Icon: Film };
    return { label: 'No streams yet', color: '#C7D2E0', bg: 'rgba(30, 36, 50, 0.82)', Icon: Film };
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
    const showVerdict = state.count > 0 || !checking;
    const Icon = checking && !state.count ? Loader2 : v.Icon;
    return (
        <div
            data-testid="preview-stream-check"
            data-status={state.status}
            data-count={state.count}
            className="vesper-mono"
            style={{
                position: 'absolute',
                top: 8,
                right: 8,
                zIndex: 4,
                display: 'inline-flex',
                alignItems: 'center',
                gap: 6,
                padding: '5px 9px 5px 7px',
                borderRadius: 999,
                background: showVerdict ? v.bg : 'rgba(5, 7, 13, 0.72)',
                border: `1px solid ${showVerdict ? v.color : 'rgba(255,255,255,0.22)'}55`,
                color: showVerdict ? v.color : 'rgba(255,255,255,0.85)',
                fontSize: 'clamp(9px, 0.62vw, 11px)',
                letterSpacing: '0.14em',
                textTransform: 'uppercase',
                fontWeight: 700,
                boxShadow: '0 4px 14px rgba(0,0,0,0.45)',
                animation: 'vesper-hoverprev-in 160ms ease-out both',
                pointerEvents: 'none',
                whiteSpace: 'nowrap',
            }}
        >
            <Icon size={12} strokeWidth={2.4} className={checking && !state.count ? 'vesper-spin' : undefined} />
            <span data-testid="preview-stream-check-label">
                {showVerdict ? v.label : 'Checking streams'}
            </span>
            {state.count > 0 && (
                <span data-testid="preview-stream-check-count" style={{ opacity: 0.8 }}>
                    · {state.count}{checking ? '…' : ''}
                </span>
            )}
        </div>
    );
}
