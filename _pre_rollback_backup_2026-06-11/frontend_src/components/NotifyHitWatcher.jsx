/**
 * <NotifyHitToast/> — top-right popup that fires when the notify-list
 * scanner finds streams for a previously-unavailable title on app
 * boot.  Surfaced exclusively by NotifyHitWatcher (mounted at the
 * App root) — there's a single shared queue so multiple hits during
 * one boot animate in one-after-the-other instead of stacking up.
 *
 * Behaviour:
 *   • "Watch now"     → navigate to /title/movie/{id}?autoplay=1 and
 *                       clear the notify entry.
 *   • "Watch later"   → push the title into the user's library
 *                       Watch-Later queue and clear the notify entry.
 *   • "Dismiss"       → just clear the notify entry, don't queue it.
 *
 * Visual language matches ReminderWatcher.jsx — same top-right
 * placement, same width, same cinematic backdrop + button styling —
 * so the user sees a single consistent notification motif across
 * the app.
 */
import React, { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Play, BookmarkPlus, X } from 'lucide-react';
import * as img from '@/lib/img';
import {
    removeFromNotifyList,
    addToWatchLater,
} from '@/lib/library';

const PENDING_KEY = 'onnowtv-notify-hits-pending-v1';

/* Push a hit onto the persistent queue so a refresh / route-change
 * doesn't lose pending notifications.  The watcher dequeues them
 * one-by-one. */
export function pushNotifyHit(entry) {
    try {
        const raw = localStorage.getItem(PENDING_KEY);
        const arr = raw ? JSON.parse(raw) : [];
        const list = Array.isArray(arr) ? arr : [];
        // Dedup by id so we don't show the same toast twice in one
        // boot if the scanner re-ran for any reason.
        if (!list.some((e) => e?.id === entry.id)) {
            list.push(entry);
            localStorage.setItem(PENDING_KEY, JSON.stringify(list));
        }
        window.dispatchEvent(new Event('onnowtv:notify-hit-push'));
    } catch {
        /* ignore */
    }
}

function readQueue() {
    try {
        const raw = localStorage.getItem(PENDING_KEY);
        const arr = raw ? JSON.parse(raw) : [];
        return Array.isArray(arr) ? arr : [];
    } catch { return []; }
}

function writeQueue(arr) {
    try { localStorage.setItem(PENDING_KEY, JSON.stringify(arr)); }
    catch { /* ignore */ }
}

export default function NotifyHitWatcher() {
    const [active, setActive] = useState(null);

    /* On mount + whenever a hit is pushed, dequeue the next one. */
    useEffect(() => {
        const tick = () => {
            if (active) return;
            const queue = readQueue();
            if (queue.length === 0) return;
            const [next, ...rest] = queue;
            writeQueue(rest);
            setActive(next);
        };
        // Run once on mount in case there are leftovers from a
        // previous session.
        tick();
        window.addEventListener('onnowtv:notify-hit-push', tick);
        return () => {
            window.removeEventListener('onnowtv:notify-hit-push', tick);
        };
    }, [active]);

    const handleDismiss = useCallback(() => {
        setActive(null);
        // Re-tick on the next frame so any queued items show.
        setTimeout(() => {
            window.dispatchEvent(new Event('onnowtv:notify-hit-push'));
        }, 250);
    }, []);

    if (!active) return null;
    return <NotifyHitToast entry={active} onDismiss={handleDismiss} />;
}

function NotifyHitToast({ entry, onDismiss }) {
    const navigate = useNavigate();
    const watchRef = useRef(null);
    const containerRef = useRef(null);

    useEffect(() => { watchRef.current?.focus(); }, []);

    /* v2.8.88 — Focus trap: once the toast is up, the D-pad must
     * stay inside it.  Any Tab / Arrow key that would move focus to
     * an element OUTSIDE the toast snaps back to a button inside. */
    useEffect(() => {
        const onFocus = (e) => {
            const c = containerRef.current;
            if (!c) return;
            if (c.contains(e.target)) return;
            // Focus escaped — pull it back to Watch Now.
            e.stopPropagation();
            (watchRef.current || c.querySelector('button'))?.focus();
        };
        document.addEventListener('focusin', onFocus, true);
        return () => document.removeEventListener('focusin', onFocus, true);
    }, []);

    /* Escape / Backspace dismisses */
    useEffect(() => {
        const onKey = (e) => {
            if (e.key === 'Escape' || e.key === 'Backspace') {
                e.preventDefault();
                e.stopPropagation();
                onDismiss();
            }
        };
        window.addEventListener('keydown', onKey, true);
        return () => window.removeEventListener('keydown', onKey, true);
    }, [onDismiss]);

    const meta = entry?.meta || {};
    const title = meta.name || 'Your movie';
    const art =
        (meta.background && img.backdrop(meta.background)) ||
        (meta.poster && img.poster(meta.poster)) ||
        '';

    const watch = useCallback(() => {
        removeFromNotifyList(entry.id);
        const t = entry.type || 'movie';
        // For TMDB-only entries (no IMDB id known) we don't have a
        // direct /title route — fall back to home.
        if (String(entry.id).startsWith('tt')) {
            navigate(`/title/${t}/${entry.id}?autoplay=1`);
        } else {
            navigate('/library');
        }
        onDismiss();
    }, [entry, navigate, onDismiss]);

    const watchLater = useCallback(() => {
        const t = entry.type || 'movie';
        if (t === 'movie') {
            addToWatchLater({
                id: entry.id,
                movie: {
                    name: meta.name,
                    poster: meta.poster,
                    background: meta.background,
                    year: meta.releaseInfo,
                    synopsis: meta.synopsis,
                },
            });
        }
        removeFromNotifyList(entry.id);
        onDismiss();
    }, [entry, meta, onDismiss]);

    return (
        <div
            ref={containerRef}
            role="alertdialog"
            data-testid="notify-hit-toast"
            aria-label="Title now streaming"
            style={{
                position: 'fixed',
                top: 24,
                right: 24,
                zIndex: 9999,
                width: 400,
                background: '#11182A',
                border: '1px solid rgba(var(--vesper-blue-rgb, 93,200,255),0.45)',
                borderRadius: 16,
                overflow: 'hidden',
                boxShadow: '0 18px 50px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.06)',
                color: '#E6EAF2',
                animation: 'vesper-notify-toast-in 320ms cubic-bezier(.16,1,.3,1) both',
            }}
        >
            <div style={{
                position: 'relative',
                height: 150,
                backgroundImage: art
                    ? `linear-gradient(180deg, rgba(17,24,42,0) 0%, rgba(17,24,42,0.88) 100%), url(${art})`
                    : 'linear-gradient(135deg, rgba(var(--vesper-blue-rgb, 93,200,255),0.18) 0%, rgba(17,24,42,1) 100%)',
                backgroundSize: 'auto, cover',
                backgroundPosition: 'center 30%',
            }}>
                <div style={{
                    position: 'absolute', top: 12, left: 14,
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '4px 10px',
                    background: 'rgba(var(--vesper-blue-rgb, 93,200,255),0.20)',
                    border: '1px solid rgba(var(--vesper-blue-rgb, 93,200,255),0.55)',
                    borderRadius: 999,
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    letterSpacing: '0.18em',
                    color: 'var(--vesper-blue-bright, #5DC8FF)',
                }}>
                    <Bell size={10} />
                    NOW STREAMING IN HD
                </div>
            </div>
            <div style={{ padding: '14px 16px 16px 16px' }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    letterSpacing: '0.22em', color: '#9DA5B5', marginBottom: 6,
                }}>
                    YOUR REMINDER LIST
                </div>
                <div style={{
                    fontSize: 16, fontWeight: 700, color: '#fff',
                    lineHeight: 1.2, marginBottom: 6,
                    overflow: 'hidden',
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                    textOverflow: 'ellipsis',
                }}>
                    {title}
                </div>
                <div style={{
                    fontSize: 12, color: '#A8B5C7',
                    marginBottom: 14, lineHeight: 1.4,
                }}>
                    Just came out in HD. Watch it now or save it for later?
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button
                        ref={watchRef}
                        data-testid="notify-hit-watch"
                        onClick={watch}
                        style={{
                            flex: 1,
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                            height: 40, padding: '0 14px',
                            borderRadius: 10,
                            border: 'none',
                            background: 'var(--vesper-blue, #5DC8FF)',
                            color: '#0A0F1A',
                            fontWeight: 700, fontSize: 13,
                            cursor: 'pointer',
                        }}
                    >
                        <Play size={13} fill="#0A0F1A" /> Watch now
                    </button>
                    <button
                        data-testid="notify-hit-later"
                        onClick={watchLater}
                        style={{
                            flex: 1,
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                            height: 40, padding: '0 14px',
                            borderRadius: 10,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.16)',
                            color: '#E6EAF2',
                            fontWeight: 600, fontSize: 13,
                            cursor: 'pointer',
                        }}
                    >
                        <BookmarkPlus size={13} /> Watch later
                    </button>
                    <button
                        data-testid="notify-hit-dismiss"
                        aria-label="Dismiss notification"
                        onClick={() => { removeFromNotifyList(entry.id); onDismiss(); }}
                        style={{
                            width: 40,
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
                            height: 40,
                            borderRadius: 10,
                            background: 'rgba(255,255,255,0.04)',
                            border: '1px solid rgba(255,255,255,0.10)',
                            color: '#9DA5B5',
                            cursor: 'pointer',
                        }}
                    >
                        <X size={14} />
                    </button>
                </div>
            </div>

            <style>{`
@keyframes vesper-notify-toast-in {
    from { opacity: 0; transform: translateX(24px) scale(0.96); }
    to   { opacity: 1; transform: translateX(0) scale(1); }
}
            `}</style>
        </div>
    );
}
