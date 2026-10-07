import { useCallback, useEffect, useRef, useState } from 'react';

/** Android launches an Activity asynchronously. Keep one launch in flight
 * until the WebView loses/regains visibility, not just for a tap debounce.
 * If the Activity never opens, unlock with feedback instead of deadlocking.
 */
export default function useNativeLaunchGuard(scope, enabled) {
    const lock = useRef(false);
    const away = useRef(false);
    const timer = useRef(null);
    const requestId = useRef(null);
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
        requestId.current = null;
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
        const native = (event) => {
            const detail = event.detail || {};
            if (detail.status === 'dispatching' && lock.current) {
                requestId.current = detail.requestId;
                return;
            }
            if (!requestId.current || detail.requestId !== requestId.current) return;
            if (detail.status === 'opened') {
                away.current = true;
                clearTimeout(timer.current);
            } else if (detail.status === 'returned') {
                requestId.current = null;
                release();
            } else if (detail.status === 'failed') {
                requestId.current = null;
                release();
                setError(detail.message || 'Android could not open the player. Please try again.');
            }
        };
        window.addEventListener('blur', departed);
        window.addEventListener('focus', focus);
        document.addEventListener('visibilitychange', visibility);
        window.addEventListener('vesper:native-playback', native);
        return () => {
            clearTimeout(timer.current);
            lock.current = false;
            away.current = false;
            requestId.current = null;
            window.removeEventListener('blur', departed);
            window.removeEventListener('focus', focus);
            document.removeEventListener('visibilitychange', visibility);
            window.removeEventListener('vesper:native-playback', native);
        };
    }, [scope, release]);

    return { begin, release, fail, busy, error };
}