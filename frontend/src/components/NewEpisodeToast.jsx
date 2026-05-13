import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Play, Bookmark, X, Sparkles } from 'lucide-react';
import { findNextNewEpisode, ackNewEpisode } from '@/lib/newEpisodes';
import {
    addToWatchLater,
    dismissEpisode,
} from '@/lib/library';

/**
 * Top-right "new episode" toast.
 *
 * Globally mounted (App.js).  Polls `findNextNewEpisode` on every
 * route change + every 5 minutes — both cheap because Cinemeta
 * results are cached.  Auto-focuses Play when it appears so a TV
 * user can hit OK on the remote without thinking.
 *
 * Visually: a 380-px-wide tile with the episode thumb at the top,
 * show name + S/E label, then two pill buttons (Play / Watch Later)
 * and a small dismiss "X".  Theme-accented border + ambient glow,
 * slides in from the right.
 */
export default function NewEpisodeToast() {
    const navigate = useNavigate();
    const [toast, setToast] = useState(null);
    const [closing, setClosing] = useState(false);

    useEffect(() => {
        let cancelled = false;
        let timer = null;

        const tick = async () => {
            if (cancelled) return;
            // Only auto-poll when nothing currently visible.
            if (!toast) {
                try {
                    const next = await findNextNewEpisode();
                    if (!cancelled && next) setToast(next);
                } catch {
                    /* ignore — network blip, retry next tick */
                }
            }
            timer = setTimeout(tick, 5 * 60 * 1000);
        };

        tick();
        const onLibChange = () => {
            // Library changed (favourite added/removed) — re-evaluate
            // sooner than the 5-min poll.
            if (timer) clearTimeout(timer);
            tick();
        };
        window.addEventListener('vesper:library-change', onLibChange);
        return () => {
            cancelled = true;
            if (timer) clearTimeout(timer);
            window.removeEventListener('vesper:library-change', onLibChange);
        };
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [toast?.showId]);

    if (!toast) return null;

    const close = () => {
        setClosing(true);
        setTimeout(() => {
            setClosing(false);
            setToast(null);
        }, 220);
    };

    const onPlay = () => {
        const { showId, episode } = toast;
        ackNewEpisode(showId);
        dismissEpisode(showId, episode.season, episode.number);
        close();
        // Stremio video ID convention: imdb_id:season:episode
        const videoId = `${showId}:${episode.season}:${episode.number}`;
        navigate(`/resolve/series/${encodeURIComponent(videoId)}`);
    };

    const onWatchLater = () => {
        const { showId, showMeta, episode } = toast;
        addToWatchLater({ id: showId, episode, showMeta });
        ackNewEpisode(showId);
        dismissEpisode(showId, episode.season, episode.number);
        close();
    };

    return (
        <ToastShell closing={closing}>
            <ToastContent
                toast={toast}
                onPlay={onPlay}
                onWatchLater={onWatchLater}
                onDismiss={() => {
                    const { showId, episode } = toast;
                    ackNewEpisode(showId);
                    dismissEpisode(showId, episode.season, episode.number);
                    close();
                }}
            />
        </ToastShell>
    );
}

function ToastShell({ closing, children }) {
    return (
        <div
            data-testid="new-episode-toast"
            className="fixed z-50 pointer-events-auto"
            style={{
                top: 24,
                right: 24,
                width: 380,
                transform: closing ? 'translateX(120%)' : 'translateX(0)',
                opacity: closing ? 0 : 1,
                transition: 'transform 220ms ease, opacity 220ms ease',
            }}
        >
            {children}
        </div>
    );
}

function ToastContent({ toast, onPlay, onWatchLater, onDismiss }) {
    const { showMeta, episode } = toast;
    const thumb = episode.thumbnail || showMeta.background || showMeta.poster;

    return (
        <div
            className="overflow-hidden"
            style={{
                background:
                    'linear-gradient(180deg, rgba(10,14,26,0.96) 0%, rgba(10,14,26,0.92) 100%)',
                border: '1px solid rgba(var(--vesper-blue-rgb), 0.45)',
                borderRadius: 18,
                boxShadow:
                    '0 24px 64px rgba(0,0,0,0.55), 0 0 0 1px rgba(var(--vesper-blue-rgb), 0.18)',
            }}
        >
            <div className="relative" style={{ height: 168, overflow: 'hidden' }}>
                {thumb ? (
                    <img
                        src={thumb}
                        alt=""
                        className="w-full h-full object-cover"
                        loading="lazy"
                    />
                ) : (
                    <div
                        className="w-full h-full"
                        style={{
                            background:
                                'linear-gradient(135deg, rgba(var(--vesper-blue-rgb),0.25) 0%, rgba(10,14,26,0.9) 100%)',
                        }}
                    />
                )}
                <div
                    className="absolute inset-0"
                    style={{
                        background:
                            'linear-gradient(180deg, rgba(10,14,26,0) 50%, rgba(10,14,26,0.92) 100%)',
                    }}
                />
                <button
                    data-testid="new-episode-dismiss"
                    data-focusable="true"
                    data-focus-style="quiet"
                    tabIndex={0}
                    onClick={onDismiss}
                    aria-label="Dismiss notification"
                    className="absolute flex items-center justify-center rounded-full"
                    style={{
                        top: 10,
                        right: 10,
                        width: 30,
                        height: 30,
                        background: 'rgba(6,8,15,0.7)',
                        border: '1px solid rgba(255,255,255,0.18)',
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    <X size={14} strokeWidth={2.2} />
                </button>

                <div
                    className="absolute"
                    style={{
                        top: 12,
                        left: 14,
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: 6,
                        padding: '5px 12px',
                        borderRadius: 999,
                        background: 'rgba(var(--vesper-blue-rgb), 0.18)',
                        border: '1px solid rgba(var(--vesper-blue-rgb), 0.55)',
                        color: 'var(--vesper-blue-bright)',
                        fontFamily: 'var(--theme-font-mono, monospace)',
                        fontSize: 10,
                        letterSpacing: '0.22em',
                        textTransform: 'uppercase',
                        fontWeight: 600,
                    }}
                >
                    <Sparkles size={11} strokeWidth={2.4} />
                    New Episode
                </div>
            </div>

            <div style={{ padding: '14px 18px 18px' }}>
                <div
                    className="vesper-display"
                    style={{
                        fontSize: 19,
                        lineHeight: 1.2,
                        letterSpacing: '-0.02em',
                        color: 'var(--vesper-text)',
                    }}
                >
                    {showMeta.name}
                </div>
                <div
                    style={{
                        marginTop: 4,
                        fontSize: 13,
                        color: 'var(--vesper-text-2)',
                    }}
                >
                    S{episode.season} · E{episode.number}
                    {episode.name && episode.name !== `S${episode.season} · E${episode.number}` && (
                        <> · {episode.name}</>
                    )}
                </div>

                <div className="flex items-center gap-2 mt-4">
                    <button
                        data-testid="new-episode-play"
                        data-focusable="true"
                        data-focus-style="pill"
                        data-initial-focus="true"
                        tabIndex={0}
                        onClick={onPlay}
                        className="flex items-center gap-2 rounded-full font-sans font-semibold"
                        style={{
                            flex: 1,
                            height: 42,
                            paddingLeft: 16,
                            paddingRight: 18,
                            fontSize: 14,
                            background: 'var(--vesper-blue)',
                            color: 'var(--vesper-bg-0)',
                            border: 'none',
                            justifyContent: 'center',
                        }}
                    >
                        <Play size={15} strokeWidth={2.4} />
                        Play
                    </button>
                    <button
                        data-testid="new-episode-watch-later"
                        data-focusable="true"
                        data-focus-style="pill"
                        tabIndex={0}
                        onClick={onWatchLater}
                        className="flex items-center gap-2 rounded-full font-sans font-medium"
                        style={{
                            flex: 1,
                            height: 42,
                            paddingLeft: 14,
                            paddingRight: 16,
                            fontSize: 13,
                            background: 'rgba(255,255,255,0.06)',
                            color: 'var(--vesper-text)',
                            border: '1px solid rgba(255,255,255,0.16)',
                            justifyContent: 'center',
                        }}
                    >
                        <Bookmark size={14} strokeWidth={2.2} />
                        Watch Later
                    </button>
                </div>
            </div>
        </div>
    );
}
