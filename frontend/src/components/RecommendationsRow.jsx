/**
 * <RecommendationsRow/> — "More like this" horizontal poster row on
 * the Detail page.  Uses TMDB's recommendations endpoint
 * (collaborative-filtering "users who liked X also liked Y"), with
 * a fallback to /similar (genre overlap) when recommendations are
 * empty.
 *
 * Tapping a card resolves the picked TMDB id → IMDB id, then
 * navigates to /title/{type}/{imdb_id} so the Detail page boots
 * straight into the user's chosen title.  We use the existing
 * /api/tmdb/imdb endpoint for the resolution, which is already
 * cached on the backend.
 *
 * Props:
 *   tmdbId      — TMDB id of the current title.
 *   mediaType   — 'movie' or 'tv'.
 *   testId      — optional data-testid override.
 */
import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import axios from 'axios';
import { showNavLoader, hideNavLoader } from '@/lib/navLoader';

const API = `${process.env.REACT_APP_BACKEND_URL}/api`;

export default function RecommendationsRow({ tmdbId, mediaType, onFocus, testId = 'recommendations-row' }) {
    const [items, setItems] = useState([]);
    const [busy, setBusy] = useState(true);
    const navigate = useNavigate();

    useEffect(() => {
        let cancel = false;
        if (!tmdbId || !mediaType) {
            setBusy(false);
            return undefined;
        }
        (async () => {
            try {
                const { data } = await axios.get(
                    `${API}/tmdb/recommendations/${mediaType}/${tmdbId}`,
                    { timeout: 10000 }
                );
                if (!cancel) {
                    setItems(Array.isArray(data?.results) ? data.results : []);
                    setBusy(false);
                }
            } catch {
                if (!cancel) {
                    setItems([]);
                    setBusy(false);
                }
            }
        })();
        return () => { cancel = true; };
    }, [tmdbId, mediaType]);

    const handlePick = async (item) => {
        showNavLoader();
        try {
            const { data } = await axios.get(
                `${API}/tmdb/imdb/${item.media_type}/${item.tmdb_id}`,
                { timeout: 8000 }
            );
            if (data?.imdb_id) {
                navigate(`/title/${item.media_type === 'tv' ? 'series' : 'movie'}/${data.imdb_id}`);
            } else {
                hideNavLoader();
            }
        } catch {
            hideNavLoader();
        }
    };

    if (busy || items.length === 0) return null;

    return (
        <section
            data-testid={testId}
            className="mt-10"
            style={{ width: '100%' }}
        >
            <h3
                className="vesper-display mb-5"
                style={{ fontSize: 26, letterSpacing: '-0.02em' }}
            >
                More like this
                <span
                    className="ml-3 vesper-mono"
                    style={{
                        fontSize: 12,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                    }}
                >
                    {items.length} titles
                </span>
            </h3>

            <div
                data-testid={`${testId}-strip`}
                className="vesper-shelf"
                style={{
                    display: 'flex',
                    gap: 12,
                    overflowX: 'auto',
                    overflowY: 'hidden',
                    paddingBottom: 16,
                    /* Tail padding so the LAST card's focus glow
                     * isn't clipped by the page's right edge as
                     * the user scrolls it into view.  Matches the
                     * CastRow strip exactly so visual rhythm is
                     * preserved when the lane swaps Cast ↔ Recs. */
                    paddingRight: 80,
                    scrollPaddingRight: 80,
                }}
            >
                {items.map((item) => (
                    <RecCard
                        key={`${item.media_type}-${item.tmdb_id}`}
                        item={item}
                        onPick={() => handlePick(item)}
                        onFocus={onFocus}
                    />
                ))}
            </div>
        </section>
    );
}

function RecCard({ item, onPick, onFocus: onFocusProp }) {
    const [focused, setFocused] = useState(false);
    return (
        <button
            data-testid={`recommendation-${item.media_type}-${item.tmdb_id}`}
            data-focusable="true"
            data-focus-style="poster"
            tabIndex={0}
            onClick={onPick}
            onFocus={() => { setFocused(true); onFocusProp?.(item); }}
            onBlur={() => { setFocused(false); onFocusProp?.(null); }}
            onMouseEnter={() => { setFocused(true); onFocusProp?.(item); }}
            onMouseLeave={() => { setFocused(false); onFocusProp?.(null); }}
            style={{
                flexShrink: 0,
                /* Match CastRow card width exactly so the lane has
                 * identical vertical geometry when it swaps Cast ↔
                 * Recs.  Previous 152px poster pushed the lane
                 * 70px taller than the Cast lane, overflowing the
                 * hero's reserved space. */
                width: 108,
                background: 'transparent',
                border: 'none',
                padding: 0,
                cursor: 'pointer',
                textAlign: 'left',
                color: 'inherit',
                outline: 'none',
                WebkitTapHighlightColor: 'transparent',
            }}
        >
            <div
                style={{
                    width: 108,
                    height: 162,
                    borderRadius: 12,
                    overflow: 'hidden',
                    position: 'relative',
                    background: 'rgba(255,255,255,0.05)',
                    border: focused
                        ? '3px solid var(--vesper-blue)'
                        : '1px solid rgba(255,255,255,0.08)',
                    transform: focused ? 'translateY(-4px)' : 'translateY(0)',
                    boxShadow: focused
                        ? '0 18px 36px rgba(93,200,255,0.35), 0 4px 12px rgba(0,0,0,0.5)'
                        : '0 2px 8px rgba(0,0,0,0.3)',
                    transition: 'transform 160ms ease, box-shadow 160ms ease, border 120ms ease',
                }}
            >
                {item.poster ? (
                    <img
                        src={item.poster}
                        alt={item.title}
                        loading="lazy"
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    />
                ) : (
                    <div
                        style={{
                            width: '100%', height: '100%',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            color: 'var(--vesper-text-3)', fontSize: 28, fontWeight: 700,
                            padding: '0 12px', textAlign: 'center',
                        }}
                    >
                        {item.title}
                    </div>
                )}
                {item.rating != null && (
                    <span
                        className="vesper-mono"
                        style={{
                            position: 'absolute',
                            top: 8, right: 8,
                            padding: '3px 8px', borderRadius: 999,
                            background: 'rgba(6,8,15,0.85)',
                            color: '#FFD773',
                            fontSize: 10, letterSpacing: '0.06em',
                            border: '1px solid rgba(255,215,115,0.35)',
                            fontWeight: 700,
                        }}
                    >
                        ★ {item.rating}
                    </span>
                )}
            </div>
            <div
                style={{
                    marginTop: 8,
                    fontSize: 13,
                    fontWeight: 600,
                    color: focused ? 'var(--vesper-text)' : 'var(--vesper-text-2)',
                    lineHeight: 1.25,
                    overflow: 'hidden',
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                }}
            >
                {item.title}
            </div>
            {item.year && (
                <div
                    className="vesper-mono"
                    style={{
                        marginTop: 2,
                        fontSize: 10,
                        color: 'var(--vesper-text-3)',
                        letterSpacing: '0.16em',
                    }}
                >
                    {item.year}
                </div>
            )}
        </button>
    );
}
