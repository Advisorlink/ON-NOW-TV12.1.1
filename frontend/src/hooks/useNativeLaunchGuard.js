import { useCallback, useEffect, useRef, useState } from 'react';

/** Android launches an Activity asynchronously. Keep one launch in flight
 * until the WebView loses/regains visibility, not just for a tap debounce.
 * If the Activity never opens, unlock with feedback instead of deadlocking.
 */
export default function useNativeLaunchGuard(scope, enabled) {
    const lock = useRef(false);
    const away = useRef(false);
    const timer = useRef(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState('');
    const release = useCallback(() => {
        clearTimeout(timer.current);
        lock.current = false;
        away.current = false;
        setBusy(false);
        setError('');
    }, []);
    const fail = useCallback(() => {
        release();
        setError('The player did not open. Tap Play to try again, or choose another stream.');
    }, [release]);
    const begin = useCallback(() => {
        if (!enabled) return true;
        if (lock.current) return false;
        lock.current = true;
        away.current = false;
        setError('');
        setBusy(true);
        timer.current = setTimeout(fail, 8000);
        return true;
    }, [enabled, fail]);

    useEffect(() => {
        release();
        const departed = () => {
            if (!lock.current) return;
            away.current = true;
            clearTimeout(timer.current);
        };
        const visibility = () => {
            if (document.hidden) departed();
            else if (away.current) release();
        };
        const focus = (event) => {
            if (event.target === window && away.current) release();
        };
        window.addEventListener('blur', departed);
        window.addEventListener('focus', focus);
        document.addEventListener('visibilitychange', visibility);
        return () => {
            clearTimeout(timer.current);
            lock.current = false;
            away.current = false;
            window.removeEventListener('blur', departed);
            window.removeEventListener('focus', focus);
            document.removeEventListener('visibilitychange', visibility);
        };
    }, [scope, release]);

    return { begin, release, fail, busy, error };
}