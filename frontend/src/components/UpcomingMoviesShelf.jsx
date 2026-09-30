/**
 * <UpcomingMoviesShelf/> — bottom-of-Home rail.
 *
 * Renders the next-60-day English-language new releases through the
 * standard <Shelf/> (poster covers + trailer slot + hero follow), so
 * Coming Soon behaves exactly like every other Home row.  Click opens
 * Detail; long-press opens the Notify-me modal.
 *
 * Data source: `/api/tmdb/upcoming-movies` (English-only, popularity
 * sorted, includes a TMDB-resolved YouTube trailer key when known).
 *
 * Empty / 404 / error states surface a developer-only diagnostic
 * banner when localStorage `onnowtv-dev-unlock === '1'` is set
 * (toggleable from Settings → Unlock for testing).
 */
import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import Shelf from './Shelf';
import StreamUnavailableModal from './StreamUnavailableModal';

const API = process.env.REACT_APP_BACKEND_URL;

export default function UpcomingMoviesShelf() {
    const navigate = useNavigate();
    const [items, setItems] = useState([]);
    const [loading, setLoading] = useState(true);
    const [diag, setDiag] = useState({ status: 'idle', count: 0, err: '' });
    const [unlock, setUnlock] = useState(() => {
        try { return localStorage.getItem('onnowtv-dev-unlock') === '1'; }
        catch { return false; }
    });
    // v2.7.45 — long-press → notify modal
    const [notify, setNotify] = useState(null);   // { id, meta }

    useEffect(() => {
        const sync = () => {
            try { setUnlock(localStorage.getItem('onnowtv-dev-unlock') === '1'); }
            catch { /* ignore */ }
        };
        window.addEventListener('onnowtv:dev-unlock-changed', sync);
        window.addEventListener('storage', sync);
        return () => {
            window.removeEventListener('onnowtv:dev-unlock-changed', sync);
            window.removeEventListener('storage', sync);
        };
    }, []);

    useEffect(() => {
        let cancel = false;
        (async () => {
            try {
                const r = await axios.get(
                    `${API}/api/tmdb/upcoming-movies`,
                    { params: { limit: 18, days: 60 }, timeout: 15_000 }
                );
                if (cancel) return;
                const data = Array.isArray(r?.data?.data) ? r.data.data : [];
                setDiag({ status: data.length ? 'ok' : 'empty', count: data.length, err: '' });
                setItems(data);
            } catch (e) {
                if (cancel) return;
                const status = e?.response?.status;
                setDiag({
                    status: 'error',
                    count: 0,
                    err: status ? `HTTP ${status}` : (e?.message || 'fetch failed'),
                });
            } finally {
                if (!cancel) setLoading(false);
            }
        })();
        return () => { cancel = true; };
    }, []);

    if (!loading && items.length === 0 && !unlock) return null;

    const openItem = (m) => {
        // v2.7.46 — per user spec: click ALWAYS opens the detail page.
        // The detail page has the Trailer button which is what
        // launches the full-screen trailer player.  Long-press on the
        // card opens the Notify modal directly (see longPressItem).
        if (m?.imdb_id) {
            navigate(`/title/movie/${m.imdb_id}`);
        } else if (m?.tmdb_id) {
            navigate(`/resolve/movie/${m.tmdb_id}`);
        }
    };

    const longPressItem = (m) => {
        // v2.7.45 — long press: open the "Stream Unavailable / Notify
        // me when ready" modal directly from the shelf.  These are
        // upcoming movies — by definition they aren't streamable yet,
        // so this is the right CTA.  Notify key is the IMDB id when
        // we have one, else `tmdb:<id>` fallback.
        const id = m?.imdb_id || (m?.tmdb_id ? `tmdb:${m.tmdb_id}` : null);
        if (!id) return;
        setNotify({
            id,
            meta: {
                type: 'movie',
                name: m.title || '',
                poster: m.poster || '',
                background: m.backdrop || '',
                releaseInfo: m.release_date || '',
                year: m.release_date ? String(m.release_date).slice(0, 4) : '',
            },
        });
    };

    if (loading && items.length === 0) return null;

    const shelf = {
        id: 'upcoming',
        eyebrow: 'UPCOMING · TRAILERS',
        title: 'Coming soon',
        items: items.map((m) => ({
            id: `upcoming-${m.tmdb_id}`,
            imdbId: m.imdb_id || null,
            type: 'movie',
            title: m.title,
            sub: fmtDate(m.release_date),
            poster: m.poster,
            background: m.backdrop || m.poster,
            year: m.release_date ? String(m.release_date).slice(0, 4) : null,
            rating: m.rating ? `★ ${m.rating}` : null,
            genres: Array.isArray(m.genres) ? m.genres : [],
            synopsis: m.synopsis || '',
            releaseInfo: fmtDate(m.release_date),
            routePath: `/resolve/movie/${m.tmdb_id}`,
            _raw: m,
        })),
    };

    return (
        <div data-testid="upcoming-movies-shelf">
            {items.length > 0 && (
                <Shelf
                    shelf={shelf}
                    onSelect={(it) => openItem(it._raw)}
                    onLongPress={(it) => longPressItem(it._raw)}
                />
            )}
            {!loading && items.length === 0 && unlock && (
                <div style={{ padding: '16px clamp(92px, 6.5vw, 132px)' }}>
                    <DiagBanner diag={diag} />
                </div>
            )}
            {notify && (
                <StreamUnavailableModal
                    id={notify.id}
                    meta={notify.meta}
                    onClose={() => setNotify(null)}
                />
            )}
        </div>
    );
}

function DiagBanner({ diag }) {
    return (
        <div
            data-testid="upcoming-diag"
            style={{
                padding: '16px 22px',
                background: 'rgba(255,180,60,0.08)',
                border: '1px dashed rgba(255,180,60,0.45)',
                borderRadius: 12,
                color: '#FFD8A1',
                fontFamily: 'monospace',
                fontSize: 12,
                lineHeight: 1.55,
                maxWidth: 720,
            }}
        >
            <div style={{ fontWeight: 800, marginBottom: 6, letterSpacing: '0.18em', fontSize: 10 }}>
                UNLOCK · UPCOMING-MOVIES DIAGNOSTIC
            </div>
            <div>status: <b>{diag.status}</b></div>
            <div>items returned: <b>{diag.count}</b></div>
            {diag.err && <div>error: <b>{diag.err}</b></div>}
            <div style={{ marginTop: 8, opacity: 0.8 }}>
                Endpoint: <code>{API}/api/tmdb/upcoming-movies</code>
            </div>
            {diag.err && diag.err.includes('404') && (
                <div style={{ marginTop: 8, color: '#FFB069' }}>
                    ⚠ Backend doesn't have this endpoint yet — push your latest
                    code via "Save to GitHub" and the auto-deploy workflow will
                    sync the VPS.
                </div>
            )}
        </div>
    );
}

function fmtDate(iso) {
    if (!iso) return '';
    try {
        const d = new Date(iso);
        return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
    } catch { return iso; }
}
