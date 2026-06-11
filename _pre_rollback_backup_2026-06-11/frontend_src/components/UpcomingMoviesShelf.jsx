/**
 * <UpcomingMoviesShelf/> — bottom-of-Home rail.
 *
 * Renders the next-60-day English-language new releases as wide
 * 16:9 trailer-rectangle cards (NOT poster tiles).  Each card shows
 * the movie's backdrop with a centred Play overlay; clicking a card
 * routes to Detail with `?autoplay-trailer=1` so the trailer fires
 * the moment the page loads.  Hovering on desktop / focusing on TV
 * fades in the title + release date overlay.
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
import { Play, Calendar } from 'lucide-react';
import * as img from '@/lib/img';
import StreamUnavailableModal from './StreamUnavailableModal';
import useLongPress from '@/hooks/useLongPress';

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
        // v2.10.44 — Removed full-screen nav loader per user demand.
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

    return (
        <section
            data-testid="upcoming-movies-shelf"
            className="relative w-full vesper-shelf-section"
            style={{
                paddingTop: 'clamp(14px, 1.4vw, 24px)',
                paddingBottom: 'clamp(14px, 1.4vw, 24px)',
            }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                }}
            >
                <div className="flex items-baseline gap-4 min-w-0">
                    <span className="vesper-eyebrow truncate">UPCOMING · TRAILERS</span>
                    <h2
                        className="vesper-display truncate"
                        style={{
                            fontSize: 'clamp(22px, 2.2vw, 34px)',
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        Coming soon
                    </h2>
                </div>
                <span
                    className="vesper-mono shrink-0"
                    style={{
                        color: 'var(--vesper-text-3)',
                        fontSize: 'clamp(9px, 0.62vw, 11px)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    Next 60 days · English
                </span>
            </header>

            <div
                className="vesper-shelf flex"
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    /* v2.7.16 — match Shelf.jsx left padding exactly
                     * so the first trailer card aligns vertically
                     * with the first poster of every other shelf on
                     * Home (Continue Watching, For You, Networks,
                     * Popular Movies, Popular Series). User reported
                     * the trailer row started ~50 px further left. */
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                    paddingTop: 'clamp(14px, 1.4vw, 22px)',
                    paddingBottom: 'clamp(14px, 1.4vw, 24px)',
                    transform: 'translateZ(0)',
                    willChange: 'scroll-position',
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    scrollSnapType: 'x proximity',
                    overscrollBehavior: 'contain',
                }}
            >
                {loading && items.length === 0 && (
                    Array.from({ length: 4 }).map((_, i) => (
                        <TrailerSkeleton key={i} />
                    ))
                )}
                {!loading && items.length === 0 && unlock && (
                    <DiagBanner diag={diag} />
                )}
                {items.map((m) => (
                    <TrailerCard
                        key={m.tmdb_id}
                        item={m}
                        onOpen={openItem}
                        onLongPress={longPressItem}
                    />
                ))}
            </div>
            {/* v2.7.45 — Notify modal (opens on long-press) */}
            {notify && (
                <StreamUnavailableModal
                    id={notify.id}
                    meta={notify.meta}
                    onClose={() => setNotify(null)}
                />
            )}
        </section>
    );
}

/* ── 16:9 trailer rectangle card ─────────────────────────────────
 * Big landscape art, centred Play badge on hover/focus, title +
 * release date strip at the bottom.  Matches the cinematic feel of
 * a YouTube-style trailers row.
 *
 * v2.7.45 — click = play trailer, long-press (600ms) = notify modal.
 * Works for: mouse hold, touch hold, AND D-pad center hold on the
 * HK1 remote.
 */
function TrailerCard({ item, onOpen, onLongPress }) {
    const art = img.backdrop(item.backdrop || item.poster);

    // v2.7.49 — use the shared `useLongPress` hook (same one used
    // across the rest of the app for Add-to-My-List long-presses).
    // Handles mouse, touch AND D-pad OK button hold uniformly, and
    // cooperates with the global spatial-focus engine so OK key
    // doesn't double-fire as both click + long-press.
    const press = useLongPress(
        () => onLongPress?.(item),
        () => onOpen(item),
        { duration: 600 },
    );

    return (
        <button
            data-testid={`upcoming-trailer-${item.tmdb_id}`}
            {...press}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            className="relative shrink-0 group overflow-hidden text-left"
            style={{
                width: 'clamp(280px, 22vw, 380px)',
                aspectRatio: '16 / 9',
                borderRadius: 18,
                background: '#0B1322',
                border: '1px solid rgba(255,255,255,0.06)',
                cursor: 'pointer',
                outline: 'none',
            }}
        >
            {art && (
                <img
                    src={art}
                    alt={item.title}
                    loading="lazy"
                    decoding="async"
                    fetchpriority="low"
                    className="absolute inset-0 w-full h-full object-cover"
                    style={{
                        opacity: 0.92,
                    }}
                />
            )}
            {/* Bottom gradient + text strip */}
            <div
                className="absolute inset-x-0 bottom-0"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(10,15,26,0) 0%, rgba(10,15,26,0.78) 60%, rgba(10,15,26,0.95) 100%)',
                    padding: '14px 14px 12px 14px',
                }}
            >
                <div
                    style={{
                        fontSize: 14,
                        fontWeight: 700,
                        color: '#fff',
                        lineHeight: 1.18,
                        letterSpacing: '-0.01em',
                        textShadow: '0 1px 8px rgba(0,0,0,0.55)',
                        display: '-webkit-box',
                        WebkitLineClamp: 2,
                        WebkitBoxOrient: 'vertical',
                        overflow: 'hidden',
                    }}
                >
                    {item.title}
                </div>
                <div
                    style={{
                        marginTop: 4,
                        fontSize: 10,
                        letterSpacing: '0.18em',
                        textTransform: 'uppercase',
                        color: 'rgba(220,230,255,0.78)',
                        display: 'flex',
                        alignItems: 'center',
                        gap: 6,
                        fontFamily: 'monospace',
                    }}
                >
                    <Calendar size={10} strokeWidth={2} />
                    {fmtDate(item.release_date)}
                </div>
            </div>
            {/* Play badge — visible on hover/focus */}
            <div
                aria-hidden
                className="absolute inset-0 flex items-center justify-center"
                style={{
                    background: 'rgba(0,0,0,0.10)',
                    opacity: 0,
                    transition: 'opacity 220ms',
                    pointerEvents: 'none',
                }}
                data-play-badge
            >
                <span
                    style={{
                        width: 54,
                        height: 54,
                        borderRadius: '50%',
                        background: 'var(--vesper-blue, #5DC8FF)',
                        color: '#0A0F1A',
                        display: 'inline-flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                    }}
                >
                    <Play size={22} fill="#0A0F1A" />
                </span>
            </div>

            <style>{`
                [data-testid="upcoming-trailer-${item.tmdb_id}"]:hover [data-play-badge],
                [data-testid="upcoming-trailer-${item.tmdb_id}"][data-focused="true"] [data-play-badge] {
                    opacity: 1;
                }
                /* v2.7.15 — DELIBERATELY no img scale on focus.  The
                 * global [data-focus-style=tile] already scales the
                 * whole card by 1.08; layering another img scale on
                 * top caused the chunky scroll/flicker the user
                 * reported on the upcoming-trailers rail. */
            `}</style>
        </button>
    );
}

function TrailerSkeleton() {
    return (
        <div
            aria-hidden="true"
            style={{
                width: 'clamp(280px, 22vw, 380px)',
                aspectRatio: '16 / 9',
                borderRadius: 18,
                background:
                    'linear-gradient(110deg, rgba(255,255,255,0.04) 30%, rgba(255,255,255,0.08) 50%, rgba(255,255,255,0.04) 70%)',
                backgroundSize: '200% 100%',
                animation: 'vesper-shimmer 1.6s linear infinite',
                flexShrink: 0,
            }}
        />
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
