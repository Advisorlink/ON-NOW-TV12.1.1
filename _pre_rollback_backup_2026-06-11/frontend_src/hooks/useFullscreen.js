import { useCallback, useEffect, useState } from 'react';

/** Toggle browser fullscreen.  F-key shortcut + button trigger. */
export default function useFullscreen() {
    const [isFs, setIsFs] = useState(
        typeof document !== 'undefined' && !!document.fullscreenElement
    );

    useEffect(() => {
        const onChange = () => setIsFs(!!document.fullscreenElement);
        document.addEventListener('fullscreenchange', onChange);
        return () =>
            document.removeEventListener('fullscreenchange', onChange);
    }, []);

    const toggle = useCallback(async () => {
        try {
            if (!document.fullscreenElement) {
                await document.documentElement.requestFullscreen({
                    navigationUI: 'hide',
                });
            } else {
                await document.exitFullscreen();
            }
        } catch (_e) {
            // some boxes / browsers reject programmatic fullscreen — silent
        }
    }, []);

    useEffect(() => {
        const onKey = (e) => {
            // F or f triggers toggle (avoid hijacking when user is typing)
            const tag = (document.activeElement?.tagName || '').toLowerCase();
            if (tag === 'input' || tag === 'textarea') return;
            if (e.key === 'f' || e.key === 'F') {
                e.preventDefault();
                toggle();
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [toggle]);

    return { isFs, toggle };
}
