import { useEffect, useMemo, useRef } from 'react';

const NAMES = ['lobby', 'tick', 'whoosh', 'buzz', 'correct', 'wrong', 'fanfare', 'click'];

// Inside the APK the React build is served from /assets/web/, so the
// public/ sound files live under that prefix too.
const BASE = typeof window !== 'undefined' && window.location.hostname === 'appassets.androidplatform.net'
    ? '/assets/web'
    : '';

export default function useTriviaSounds(muted = false) {
    const bank = useRef({});
    const mutedRef = useRef(muted);
    mutedRef.current = muted;

    useEffect(() => {
        NAMES.forEach((n) => {
            try {
                const a = new Audio(`${BASE}/sounds/trivia/${n}.wav`);
                a.preload = 'auto';
                bank.current[n] = a;
            } catch { /* ignore */ }
        });
        const b = bank.current;
        return () => { Object.values(b).forEach((a) => { try { a.pause(); } catch { /* ignore */ } }); };
    }, []);

    return useMemo(() => ({
        play(name, vol = 1) {
            if (mutedRef.current) return;
            const a = bank.current[name];
            if (!a) return;
            try {
                a.loop = false;
                a.volume = vol;
                a.currentTime = 0;
                a.play().catch(() => {});
            } catch { /* ignore */ }
        },
        loop(name, vol = 0.35) {
            if (mutedRef.current) return;
            const a = bank.current[name];
            if (!a) return;
            try {
                a.loop = true;
                a.volume = vol;
                if (a.paused) a.play().catch(() => {});
            } catch { /* ignore */ }
        },
        stop(name) {
            const a = bank.current[name];
            if (!a) return;
            try { a.pause(); a.currentTime = 0; } catch { /* ignore */ }
        },
    }), []);
}
