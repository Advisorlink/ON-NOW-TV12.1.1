/**
 * Global Live TV reminder watcher — mounted at the App root so it
 * fires no matter which page the user is on.
 *
 * How it works:
 *   • Once a minute (or whenever the page becomes visible) it walks
 *     every provider's reminders list looking for entries whose
 *     `startTs` is within the next 60 s (or just-started, up to 30 s
 *     ago).
 *   • Each fired reminder is marked in a localStorage Set keyed by
 *     reminder id so it can't re-fire on the next tick.
 *   • Renders an overlay toast in the top-right with cover art (via
 *     useProgrammeBackdrop), channel name, title, and two buttons:
 *     "Watch Now" → plays the channel via Host.playVideo() / navigate
 *     "Dismiss" → just hides it.
 *
 * Performance:
 *   • One setInterval at 60 s.  Zero work between ticks.
 *   • No work at all when there are no reminders to track.
 *   • Toast only renders when there's an active fired reminder.
 *
 * Persistence:
 *   • The "already fired" set is stored in localStorage so a tick
 *     that happens to land on the same reminder twice doesn't
 *     re-show the toast.
 *   • Pruned of stale entries on every read.
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bell, Play, X } from 'lucide-react';
import { getActiveProvider } from '@/lib/xtream';
import { getReminders, pruneStale } from '@/lib/liveReminders';
import { loadChannels } from '@/lib/liveCache';
import { getStreamUrl } from '@/lib/xtream';
import useProgrammeBackdrop from '@/hooks/useProgrammeBackdrop';
import Host from '@/lib/host';

const FIRED_KEY = 'onnowtv-reminders-fired-v1';
const WINDOW_BEFORE_SEC = 30;  // can fire up to 30 s before start
const WINDOW_AFTER_SEC = 180;  // can fire up to 3 min after start (catch tabs that were closed)

function loadFired() {
    try {
        const raw = localStorage.getItem(FIRED_KEY);
        const arr = raw ? JSON.parse(raw) : [];
        return Array.isArray(arr) ? new Set(arr) : new Set();
    } catch { return new Set(); }
}
function saveFired(set) {
    try { localStorage.setItem(FIRED_KEY, JSON.stringify([...set])); } catch { /* ignore */ }
}
function pruneFired(set, validReminderIds) {
    let dirty = false;
    for (const id of set) {
        if (!validReminderIds.has(id)) { set.delete(id); dirty = true; }
    }
    return dirty;
}

export default function ReminderWatcher() {
    const [active, setActive] = useState(null); // { reminder, channel } | null

    /* Polling tick. */
    useEffect(() => {
        let cancel = false;
        const tick = () => {
            if (cancel) return;
            const provider = getActiveProvider();
            if (!provider) return;

            const reminders = pruneStale(provider.id);
            const fired = loadFired();
            const validIds = new Set(reminders.map((r) => r.id));
            if (pruneFired(fired, validIds)) saveFired(fired);

            const nowSec = Math.floor(Date.now() / 1000);
            const due = reminders.find((r) => {
                if (fired.has(r.id)) return false;
                const start = Number(r.startTs) || 0;
                return start - nowSec <= WINDOW_BEFORE_SEC && nowSec - start <= WINDOW_AFTER_SEC;
            });
            if (!due) return;

            // Resolve the channel object from the persistent cache so
            // we can play it on "Watch Now".
            const channelsByCat = loadChannels(provider.id) || {};
            let channel = null;
            for (const k in channelsByCat) {
                const list = channelsByCat[k] || [];
                channel = list.find((c) => String(c.stream_id) === String(due.streamId));
                if (channel) break;
            }

            fired.add(due.id);
            saveFired(fired);
            setActive({ reminder: due, channel, provider });
        };

        // Run immediately on mount, then every 30 s.  Also run when
        // the tab comes back to the foreground.
        tick();
        const t = setInterval(tick, 30_000);
        const onVis = () => { if (document.visibilityState === 'visible') tick(); };
        document.addEventListener('visibilitychange', onVis);
        return () => {
            cancel = true;
            clearInterval(t);
            document.removeEventListener('visibilitychange', onVis);
        };
    }, []);

    const dismiss = useCallback(() => setActive(null), []);

    if (!active) return null;
    return <ReminderToast {...active} onDismiss={dismiss} />;
}

function ReminderToast({ reminder, channel, provider, onDismiss }) {
    const navigate = useNavigate();
    const dismissRef = useRef(null);
    const watchRef = useRef(null);

    /* Auto-focus the Watch button so a quick D-pad press confirms. */
    useEffect(() => { watchRef.current?.focus(); }, []);

    /* Pull a TMDB backdrop for the reminded programme.  Same hook
     * the LiveTV hero uses — cache is shared, so if you already
     * saw this programme's hero there, the toast art is instant. */
    const tmdb = useProgrammeBackdrop(reminder.title || '', reminder.channelName || '');
    const art = tmdb?.backdrop
        ? `${process.env.REACT_APP_BACKEND_URL}/api/img-proxy?url=${encodeURIComponent(tmdb.backdrop)}&w=640&q=60`
        : '';

    const watch = useCallback(async () => {
        if (!channel || !provider) { onDismiss(); return; }
        try {
            const url = await getStreamUrl(provider, 'live', channel.stream_id, 'ts');
            if (!url) { onDismiss(); return; }
            const payload = {
                url, title: channel.name, type: 'live',
                cwId: `live:${provider.id}:${channel.stream_id}`,
            };
            onDismiss();
            if (Host.playVideo(payload)) return;
            navigate(`/play?url=${encodeURIComponent(url)}&title=${encodeURIComponent(channel.name)}&type=live`);
        } catch {
            onDismiss();
        }
    }, [channel, provider, onDismiss, navigate]);

    /* Quick keypad handling — Enter on whichever button is focused
     * is already handled by the buttons themselves; ESC dismisses. */
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

    return (
        <div
            role="alertdialog"
            aria-label="Programme starting soon"
            style={{
                position: 'fixed',
                top: 24,
                right: 24,
                zIndex: 9999,
                width: 380,
                background: '#11182A',
                border: '1px solid rgba(93,200,255,0.45)',
                borderRadius: 16,
                overflow: 'hidden',
                boxShadow: '0 18px 50px rgba(0,0,0,0.6), inset 0 1px 0 rgba(255,255,255,0.06)',
                color: '#E6EAF2',
            }}
        >
            {/* Cover art — falls back to a gradient when TMDB has nothing */}
            <div style={{
                position: 'relative',
                height: 140,
                backgroundImage: art
                    ? `linear-gradient(180deg, rgba(17,24,42,0) 0%, rgba(17,24,42,0.85) 100%), url(${art})`
                    : 'linear-gradient(135deg, rgba(93,200,255,0.18) 0%, rgba(17,24,42,1) 100%)',
                backgroundSize: 'auto, cover',
                backgroundPosition: 'center',
            }}>
                <div style={{
                    position: 'absolute', top: 12, left: 14,
                    display: 'inline-flex', alignItems: 'center', gap: 6,
                    padding: '4px 10px',
                    background: 'rgba(93,200,255,0.20)',
                    border: '1px solid rgba(93,200,255,0.55)',
                    borderRadius: 999,
                    fontFamily: 'monospace', fontSize: 9, fontWeight: 700,
                    letterSpacing: '0.18em', color: '#5DC8FF',
                }}>
                    <Bell size={10} color="#5DC8FF" fill="#5DC8FF" />
                    STARTING NOW
                </div>
            </div>
            <div style={{ padding: '14px 16px 16px 16px' }}>
                <div style={{
                    fontFamily: 'monospace', fontSize: 10, fontWeight: 700,
                    letterSpacing: '0.22em', color: '#9DA5B5', marginBottom: 6,
                }}>
                    {(reminder.channelName || '').toUpperCase()}
                </div>
                <div style={{
                    fontSize: 16, fontWeight: 700, color: '#fff',
                    lineHeight: 1.2, marginBottom: 14,
                    overflow: 'hidden',
                    display: '-webkit-box',
                    WebkitLineClamp: 2,
                    WebkitBoxOrient: 'vertical',
                    textOverflow: 'ellipsis',
                }}>
                    {reminder.title || 'Untitled programme'}
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                    <button
                        ref={watchRef}
                        onClick={watch}
                        style={{
                            flex: 1,
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 8,
                            height: 40, padding: '0 14px',
                            borderRadius: 10,
                            border: 'none',
                            background: '#5DC8FF',
                            color: '#0A0F1A',
                            fontWeight: 700, fontSize: 13,
                            cursor: 'pointer',
                        }}
                    >
                        <Play size={13} fill="#0A0F1A" /> Watch Now
                    </button>
                    <button
                        ref={dismissRef}
                        onClick={onDismiss}
                        style={{
                            display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 6,
                            height: 40, padding: '0 14px',
                            borderRadius: 10,
                            background: 'rgba(255,255,255,0.06)',
                            border: '1px solid rgba(255,255,255,0.12)',
                            color: '#E6EAF2',
                            fontWeight: 600, fontSize: 13,
                            cursor: 'pointer',
                        }}
                    >
                        <X size={13} /> Dismiss
                    </button>
                </div>
            </div>
        </div>
    );
}
