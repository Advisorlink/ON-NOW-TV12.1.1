import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, X } from 'lucide-react';
import * as cw from '@/lib/continueWatching';
import * as img from '@/lib/img';
import Host from '@/lib/host';

const POLL_MS = 5_000;

/**
 * "Continue Watching" rail — landscape 16:9 tiles with progress bar,
 * always rendered above NetworksShelf when there's at least one
 * entry.  Click → resumes from `positionMs`.  Long-press (hold Enter
 * or mouse-down for 700 ms) → confirms removal.
 */
export default function ContinueWatchingShelf() {
    const navigate = useNavigate();
    const [entries, setEntries] = useState(() => {
        cw.syncFromNative();
        return cw.getEntries();
    });
    const [confirmId, setConfirmId] = useState(null);

    // Re-poll periodically (native progress reports flow in over time)
    useEffect(() => {
        const tick = () => {
            cw.syncFromNative();
            setEntries(cw.getEntries());
        };
        const id = setInterval(tick, POLL_MS);
        const onVis = () => {
            if (document.visibilityState === 'visible') tick();
        };
        document.addEventListener('visibilitychange', onVis);
        window.addEventListener('focus', tick);
        return () => {
            clearInterval(id);
            document.removeEventListener('visibilitychange', onVis);
            window.removeEventListener('focus', tick);
        };
    }, []);

    if (!entries.length) return null;

    const resume = async (e) => {
        // Direct play — re-use the previously selected stream so the
        // user doesn't have to pick a source again.  Falls back to
        // the Detail page only if we don't have the stream URL.
        if (e.streamUrl && Host.playVideo) {
            const startAtMs = Math.max(0, (e.positionMs || 0) - 5_000);
            const fired = Host.playVideo({
                url: e.streamUrl,
                title: e.title || '',
                type: e.type || 'movie',
                subtitleUrl: e.subtitleUrl || '',
                poster: e.poster || '',
                backdrop: e.backdrop || '',
                synopsis: e.synopsis || '',
                year: e.year || '',
                rating: e.rating || '',
                runtime: e.runtime || '',
                genres: e.genres || [],
                startAtMs,
                cwId: e.id,
            });
            if (fired) return;
        }
        // Fallback: route to the detail page for source pick.
        const t = e.type || 'movie';
        // For series CW ids look like "tt1234:s1e1" — strip the suffix
        // when constructing the route so meta resolves.
        const baseId =
            (t === 'series' && e.id?.includes(':')
                ? e.id.split(':')[0]
                : e.id) || e.id;
        navigate(`/title/${t}/${baseId}?resume=1`);
    };

    return (
        <section
            data-testid="continue-watching-shelf"
            className="relative w-full vesper-shelf-section"
            style={{
                paddingTop: 'clamp(28px, 3vw, 56px)',
                paddingBottom: 0,
            }}
        >
            <header
                className="flex items-end justify-between mb-3"
                style={{
                    paddingLeft: 'clamp(124px, 9.5vw, 180px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
                }}
            >
                <div className="flex items-baseline gap-4 min-w-0">
                    <span className="vesper-eyebrow truncate">For you</span>
                    <h2
                        className="vesper-display truncate"
                        style={{
                            fontSize: 'clamp(22px, 2.2vw, 34px)',
                            letterSpacing: '-0.025em',
                            color: 'var(--vesper-text)',
                        }}
                    >
                        Continue watching
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
                    Hold OK to remove
                </span>
            </header>

            <div
                className="vesper-shelf flex"
                style={{
                    gap: 'clamp(14px, 1.25vw, 24px)',
                    paddingLeft: 'clamp(124px, 9.5vw, 180px)',
                    paddingRight: 'clamp(124px, 9.5vw, 180px)',
                    paddingTop: 'clamp(14px, 1.4vw, 22px)',
                    paddingBottom: 'clamp(14px, 1.4vw, 24px)',
                }}
            >
                {entries.map((e) => (
                    <CWTile
                        key={e.id}
                        entry={e}
                        confirmRemove={confirmId === e.id}
                        onConfirm={() => setConfirmId(e.id)}
                        onCancel={() => setConfirmId(null)}
                        onRemove={() => {
                            cw.remove(e.id);
                            setConfirmId(null);
                            setEntries(cw.getEntries());
                        }}
                        onResume={() => resume(e)}
                    />
                ))}
            </div>
        </section>
    );
}

function CWTile({
    entry,
    confirmRemove,
    onConfirm,
    onCancel,
    onRemove,
    onResume,
}) {
    const pressTimer = useRef(null);
    const startPress = useCallback(() => {
        if (pressTimer.current) clearTimeout(pressTimer.current);
        pressTimer.current = setTimeout(() => {
            pressTimer.current = null;
            onConfirm();
        }, 700);
    }, [onConfirm]);
    const cancelPress = useCallback(() => {
        if (pressTimer.current) {
            clearTimeout(pressTimer.current);
            pressTimer.current = null;
        }
    }, []);
    useEffect(() => () => cancelPress(), [cancelPress]);

    const pct =
        entry.durationMs && entry.positionMs
            ? Math.min(
                  100,
                  Math.max(2, (entry.positionMs / entry.durationMs) * 100)
              )
            : 4;

    const remaining =
        entry.durationMs && entry.positionMs
            ? formatRemaining(entry.durationMs - entry.positionMs)
            : null;

    const handleClick = () => {
        if (confirmRemove) return; // confirm dialog is showing
        onResume();
    };
    const handleKeyDown = (e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13) {
            if (!e.repeat) startPress();
        }
    };
    const handleKeyUp = (e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13) {
            const wasShortPress = !!pressTimer.current;
            cancelPress();
            if (wasShortPress && !confirmRemove) {
                e.preventDefault();
                onResume();
            }
        }
    };

    if (confirmRemove) {
        return (
            <div
                className="shrink-0 relative overflow-hidden"
                style={{
                    width: 'clamp(280px, 22vw, 380px)',
                    aspectRatio: '16 / 9',
                    borderRadius: 18,
                    background: 'rgba(11,19,34,0.92)',
                    border: '1px solid rgba(255,255,255,0.12)',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'center',
                    alignItems: 'center',
                    gap: 12,
                    padding: 16,
                }}
            >
                <div
                    style={{
                        fontSize: 14,
                        color: 'var(--vesper-text-2)',
                        textAlign: 'center',
                    }}
                >
                    Remove "{entry.title}" from Continue Watching?
                </div>
                <div className="flex gap-2">
                    <button
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onRemove}
                        style={{
                            padding: '8px 16px',
                            borderRadius: 999,
                            background: '#FF6B6B',
                            color: '#fff',
                            fontWeight: 600,
                            fontSize: 13,
                        }}
                    >
                        Remove
                    </button>
                    <button
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={onCancel}
                        style={{
                            padding: '8px 16px',
                            borderRadius: 999,
                            background: 'rgba(255,255,255,0.10)',
                            color: '#fff',
                            fontWeight: 600,
                            fontSize: 13,
                        }}
                    >
                        Cancel
                    </button>
                </div>
            </div>
        );
    }

    return (
        <button
            data-testid={`continue-${entry.id}`}
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={handleClick}
            onKeyDown={handleKeyDown}
            onKeyUp={handleKeyUp}
            onMouseDown={startPress}
            onMouseUp={cancelPress}
            onMouseLeave={cancelPress}
            className="group relative shrink-0 overflow-hidden text-left"
            style={{
                width: 'clamp(280px, 22vw, 380px)',
                aspectRatio: '16 / 9',
                borderRadius: 18,
                background: '#0B1322',
                border: '1px solid rgba(255,255,255,0.06)',
            }}
        >
            {entry.backdrop ? (
                <img
                    src={img.backdrop(entry.backdrop)}
                    alt={entry.title}
                    loading="lazy"
                    decoding="async"
                    style={{
                        position: 'absolute',
                        inset: 0,
                        width: '100%',
                        height: '100%',
                        objectFit: 'cover',
                    }}
                />
            ) : null}

            {/* Bottom gradient for legibility */}
            <div
                className="absolute inset-0 pointer-events-none"
                style={{
                    background:
                        'linear-gradient(180deg, rgba(0,0,0,0) 30%, rgba(0,0,0,0.85) 100%)',
                }}
            />

            {/* Play badge bottom-left */}
            <div
                className="absolute"
                style={{
                    left: 14,
                    bottom: 38,
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    width: 36,
                    height: 36,
                    borderRadius: 999,
                    background: 'rgba(11,19,34,0.7)',
                    border: '1px solid rgba(255,255,255,0.18)',
                    backdropFilter: 'blur(8px)',
                }}
            >
                <Play
                    size={14}
                    fill="#fff"
                    color="#fff"
                    style={{ marginLeft: 2 }}
                />
            </div>

            {/* Title + remaining */}
            <div
                className="absolute"
                style={{
                    left: 14,
                    right: 14,
                    bottom: 22,
                }}
            >
                <div
                    style={{
                        fontSize: 'clamp(14px, 1vw, 17px)',
                        fontWeight: 700,
                        color: '#fff',
                        textShadow: '0 1px 4px rgba(0,0,0,0.55)',
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        paddingLeft: 46,
                    }}
                >
                    {entry.title}
                </div>
                {remaining && (
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 11,
                            letterSpacing: '0.14em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-blue)',
                            marginTop: 4,
                            paddingLeft: 46,
                        }}
                    >
                        {remaining} left
                    </div>
                )}
            </div>

            {/* Progress bar */}
            <div
                className="absolute"
                style={{
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 6,
                    background: 'rgba(255,255,255,0.16)',
                }}
            >
                <div
                    style={{
                        width: `${pct}%`,
                        height: '100%',
                        background: 'var(--vesper-blue)',
                        boxShadow:
                            '0 0 12px var(--vesper-blue-glow)',
                    }}
                />
            </div>
        </button>
    );
}

function formatRemaining(ms) {
    const total = Math.max(0, Math.round(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    if (h > 0) return `${h}h ${m}m`;
    return `${m}m`;
}
