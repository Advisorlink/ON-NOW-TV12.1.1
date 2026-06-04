import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, X, Trash2 } from 'lucide-react';
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

    const removeAll = () => {
        // Confirm visually via a small inline state — we don't pop a
        // modal here because the long-press flow handles individual
        // confirms; for the bulk action a single tap == clear all.
        entries.forEach((e) => cw.remove(e.id));
        setEntries([]);
        setConfirmId(null);
    };

    const resume = async (e) => {
        const t = e.type || 'movie';
        // Pull the freshest position from native SharedPreferences
        // BEFORE firing the player.  The shelf entry in React state
        // can be up to POLL_MS stale, and the native player updates
        // its progress map every 5 s — without this refresh the
        // resume can land 5–30 seconds behind where they actually
        // stopped.
        cw.syncFromNative();
        const fresh = cw.getEntry?.(e.id) || e;
        const startPosMs = Math.max(0, Number(fresh.positionMs) || 0);
        // For series CW ids look like "tt1234:s1e3" (native path) or
        // "tt1234:1:3" (party path) — pull out the base IMDB id +
        // season + episode so we can route the WebView to the
        // episode picker BEFORE firing the native player.  This is
        // what lets the user press BACK from the player and land
        // on the episode selector for that show.
        const baseId =
            (t === 'series' && e.id?.includes(':')
                ? e.id.split(':')[0]
                : e.id) || e.id;
        let focusSeason = 0;
        let focusEpisode = 0;
        if (t === 'series' && e.id?.includes(':')) {
            const m1 = e.id.match(/:s(\d+)e(\d+)$/i);
            if (m1) { focusSeason = Number(m1[1]); focusEpisode = Number(m1[2]); }
            else {
                const m2 = e.id.match(/:(\d+):(\d+)$/);
                if (m2) { focusSeason = Number(m2[1]); focusEpisode = Number(m2[2]); }
            }
        }

        // Direct play — re-use the previously selected stream so the
        // user doesn't have to pick a source again.  Falls back to
        // the Detail page only if we don't have the stream URL.
        if ((fresh.streamUrl || e.streamUrl) && Host.playVideo) {
            // 2 s rewind so the user gets a soft "lead-in" without
            // losing actual progress.  Was previously 5 s which felt
            // like the resume was jumping noticeably backwards.
            const startAtMs = Math.max(0, startPosMs - 2_000);
            // For series, navigate the WebView to the detail page
            // FIRST with focusSeason/focusEpisode so that pressing
            // BACK from the native player lands on the episode
            // picker for that show.  No `resume=1` here — we're
            // bypassing the Detail page's stream-pick logic by
            // calling Host.playVideo directly.
            if (t === 'series' && focusSeason && focusEpisode) {
                navigate(
                    `/title/series/${baseId}` +
                    `?focusSeason=${focusSeason}&focusEpisode=${focusEpisode}`,
                );
            }
            const fired = Host.playVideo({
                url: fresh.streamUrl || e.streamUrl,
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
        // Fallback: route to the detail page for source pick.  For
        // series, include the focus hints so the episode picker
        // opens on the right episode.
        const suffix = (t === 'series' && focusSeason && focusEpisode)
            ? `&focusSeason=${focusSeason}&focusEpisode=${focusEpisode}`
            : '';
        navigate(`/title/${t}/${baseId}?resume=1${suffix}`);
    };

    return (
        <section
            data-testid="continue-watching-shelf"
            className="relative w-full vesper-shelf-section"
            style={{
                /* v2.7.01 — top padding tightened from clamp(28,3vw,56)
                 * → clamp(18,2vw,32) so the CW row sits closer to
                 * the hero on a 1080p TV (with overscan).  Combined
                 * with hero height reduced to 50vh max 540px, the
                 * full CW card — including the 4px progress bar at
                 * the bottom — now stays within the safe area on
                 * the user's projector. */
                paddingTop: 'clamp(18px, 2vw, 32px)',
                paddingBottom: 'clamp(18px, 2vw, 36px)',
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
                    paddingLeft: 'clamp(92px, 6.5vw, 132px)',
                    paddingRight: 'clamp(40px, 4.2vw, 80px)',
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
                {/* v2.8.88 — Delete-all card at the very end of the
                    Continue Watching row.  Same height/aspect as the
                    tiles so the rail stays visually balanced. */}
                <DeleteAllCard onConfirm={removeAll} />
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
    const pressStartAt = useRef(0);
    /* v2.8.89 — Long-press OK on Android TV remotes.
     *
     * The previous implementation called startPress() on every
     * keydown that wasn't `e.repeat=true`.  Problem: many Android
     * TV firmwares (notably the HK1 box's remote) **don't set
     * `e.repeat` on auto-repeated keydowns**.  So every auto-repeat
     * (~30 Hz) re-armed a fresh 700 ms timer — meaning while the
     * user held the OK button the timer was perpetually reset and
     * the confirm UI never appeared.
     *
     * The new flow uses a `pressStartAt` baseline.  We only arm the
     * timer once per physical press (`pressStartAt === 0`).  Every
     * subsequent auto-repeated keydown is ignored.  Short press
     * (released before the 700 ms threshold) falls through to
     * onResume(); long press fires onConfirm() and onKeyUp suppresses
     * the resume call.
     */
    const startPress = useCallback(() => {
        // Already armed by an earlier keydown — ignore TV-remote
        // auto-repeats so the timer isn't reset on every tick.
        if (pressStartAt.current) return;
        pressStartAt.current = Date.now();
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
        pressStartAt.current = 0;
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

    const handleClick = (e) => {
        if (confirmRemove) return; // confirm dialog is showing
        // Some Android TV WebViews convert a long-press OK into a
        // synthetic click event AFTER the timer has already fired.
        // Suppress the click if we just fired onConfirm().
        if (e && e.detail === 0 && pressStartAt.current === 0
            && !pressTimer.current) {
            // Synthetic click from a key event we already handled.
            // We rely on keyup to drive resume.
            return;
        }
        onResume();
    };
    const handleKeyDown = (e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13) {
            // Always swallow — we own the press lifecycle for this
            // tile so the parent <div>'s default Enter→click doesn't
            // fire onResume() in parallel.
            e.preventDefault();
            startPress();
        }
    };
    const handleKeyUp = (e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.keyCode === 13) {
            e.preventDefault();
            const startedAt = pressStartAt.current;
            const heldMs = startedAt ? Date.now() - startedAt : 0;
            // Timer is still pending iff we haven't crossed the
            // 700 ms threshold yet.
            const wasShortPress = !!pressTimer.current && heldMs < 700;
            cancelPress();
            if (wasShortPress && !confirmRemove) {
                onResume();
            }
            // Long press already fired onConfirm() from the timeout.
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
            {(entry.backdrop || entry.poster) ? (
                <img
                    src={img.backdrop(entry.backdrop || entry.poster)}
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

            {/* v2.7.00 — single flex stack at the bottom replaces
             * the old absolutely-positioned play-badge + title +
             * "X left" layout.  Previously the badge sat at
             * bottom: 38 and the title block at bottom: 22 with a
             * paddingLeft hack to dodge the badge — fragile and
             * the "X LEFT" mono caption would clip into the
             * progress bar at the bottom-most edge.  Now
             * everything is laid out in normal flow above the
             * progress bar with guaranteed gaps. */}
            <div
                className="absolute"
                style={{
                    left: 14,
                    right: 14,
                    bottom: 14,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 6,
                }}
            >
                <div className="flex items-center gap-3 min-w-0">
                    <span
                        style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            width: 32,
                            height: 32,
                            borderRadius: 999,
                            background: 'rgba(11,19,34,0.7)',
                            border: '1px solid rgba(255,255,255,0.18)',
                            backdropFilter: 'blur(8px)',
                            flexShrink: 0,
                        }}
                    >
                        <Play
                            size={13}
                            fill="#fff"
                            color="#fff"
                            style={{ marginLeft: 2 }}
                        />
                    </span>
                    <div
                        style={{
                            fontSize: 'clamp(13px, 0.95vw, 16px)',
                            fontWeight: 700,
                            color: '#fff',
                            textShadow: '0 1px 4px rgba(0,0,0,0.55)',
                            whiteSpace: 'nowrap',
                            overflow: 'hidden',
                            textOverflow: 'ellipsis',
                            minWidth: 0,
                            flex: 1,
                        }}
                    >
                        {entry.title}
                    </div>
                </div>
                {remaining && (
                    <div
                        className="vesper-mono"
                        style={{
                            fontSize: 10,
                            letterSpacing: '0.16em',
                            textTransform: 'uppercase',
                            color: 'var(--vesper-blue)',
                            paddingLeft: 44,
                        }}
                    >
                        {remaining} left
                    </div>
                )}
            </div>

            {/* Progress bar — flush at the very bottom edge. */}
            <div
                className="absolute"
                style={{
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 4,
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

function DeleteAllCard({ onConfirm }) {
    const [armed, setArmed] = useState(false);
    const timerRef = useRef(null);
    useEffect(() => () => timerRef.current && clearTimeout(timerRef.current), []);

    // Two-tap confirm: first OK arms, second OK clears.  Tap away
    // (focus leaves the card) cancels.
    const handleClick = () => {
        if (armed) {
            onConfirm();
            setArmed(false);
        } else {
            setArmed(true);
            timerRef.current = setTimeout(() => setArmed(false), 4000);
        }
    };

    return (
        <button
            data-testid="continue-delete-all"
            data-focusable="true"
            data-focus-style="tile"
            tabIndex={0}
            onClick={handleClick}
            onBlur={() => setArmed(false)}
            className="group relative shrink-0 overflow-hidden text-left"
            style={{
                width: 'clamp(280px, 22vw, 380px)',
                aspectRatio: '16 / 9',
                borderRadius: 18,
                background: armed
                    ? 'linear-gradient(135deg, rgba(255,80,80,0.32) 0%, rgba(120,20,20,0.65) 100%)'
                    : 'linear-gradient(135deg, rgba(28,38,58,0.85) 0%, rgba(11,19,34,0.95) 100%)',
                border: armed
                    ? '1.5px solid rgba(255,120,120,0.85)'
                    : '1.5px dashed rgba(255,255,255,0.18)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                gap: 12,
                color: armed ? '#fff' : 'rgba(255,255,255,0.78)',
                cursor: 'pointer',
                transition: 'background 200ms ease, border 200ms ease',
            }}
        >
            <span
                style={{
                    width: 56,
                    height: 56,
                    borderRadius: 999,
                    display: 'inline-flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    background: armed
                        ? 'rgba(255,255,255,0.18)'
                        : 'rgba(255,255,255,0.06)',
                    border: '1px solid rgba(255,255,255,0.18)',
                }}
            >
                <Trash2 size={22} strokeWidth={1.8} />
            </span>
            <span
                className="vesper-mono"
                style={{
                    fontSize: 11,
                    letterSpacing: '0.22em',
                    textTransform: 'uppercase',
                    fontWeight: 700,
                }}
            >
                {armed ? 'Tap again to clear' : 'Clear all'}
            </span>
            <span
                style={{
                    fontSize: 11,
                    opacity: 0.65,
                    textAlign: 'center',
                    padding: '0 16px',
                }}
            >
                {armed
                    ? 'This removes every Continue Watching entry.'
                    : 'Wipe the whole Continue Watching row.'}
            </span>
        </button>
    );
}
