import { useCallback, useEffect, useRef, useState } from 'react';

/** Remember an explicit tap while sources load. A partial playable result
 * fulfils it once; leaving the title, choosing sources or a rating block
 * cancels it. No browser gesture emulation or duplicate touch handlers. */
export default function useMoviePlayIntent({ titleKey, candidate, loading, blocked, onPlay, onUnavailable }) {
    const current = useRef(null);
    current.current = { titleKey, candidate, loading, blocked, onPlay, onUnavailable };
    const queued = useRef(null);
    const lastLaunch = useRef({ key: '', at: -Infinity });
    const [queuedKey, setQueuedKey] = useState(null);

    const cancel = useCallback(() => { queued.current = null; setQueuedKey(null); }, []);
    const launch = useCallback((stream) => {
        const now = performance.now();
        const ctx = current.current;
        if (lastLaunch.current.key === ctx.titleKey && now - lastLaunch.current.at < 500) return;
        lastLaunch.current = { key: ctx.titleKey, at: now };
        ctx.onPlay(stream);
    }, []);
    const request = useCallback(() => {
        const ctx = current.current;
        if (ctx.blocked) { cancel(); return; }
        if (ctx.candidate) { cancel(); launch(ctx.candidate); return; }
        if (ctx.loading) { queued.current = ctx.titleKey; setQueuedKey(ctx.titleKey); return; }
        cancel();
        ctx.onUnavailable();
    }, [cancel, launch]);

    useEffect(() => {
        return () => { queued.current = null; };
    }, [titleKey]);

    useEffect(() => {
        if (queued.current !== titleKey) return;
        if (blocked) { cancel(); return; }
        if (candidate) {
            cancel();
            launch(candidate);
        } else if (!loading) {
            cancel();
            current.current.onUnavailable();
        }
    }, [titleKey, candidate, loading, blocked, cancel, launch]);

    return { request, cancel, pending: queuedKey === titleKey && queued.current === titleKey };
}