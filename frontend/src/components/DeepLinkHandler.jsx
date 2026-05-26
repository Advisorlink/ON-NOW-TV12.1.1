import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { setActiveProfile } from '@/lib/profiles';

/**
 * v2.7.95 — Boot-time deep-link handler.
 *
 * Currently handles ONE thing: the `?profile=kids` URL param emitted
 * by the ON NOW launcher's Kids tile.  When seen, this component:
 *
 *   1. Flips the active profile to Kids (sandboxed mode)
 *   2. Strips the `profile` param from the URL
 *   3. Navigates to "/" so the user lands on the Kids home — not on
 *      whatever route the WebView restored from its last session
 *
 * Lives INSIDE the Router (unlike the old App-level useEffect) so it
 * can call `useNavigate` to actually move the user to the kids home
 * route, instead of just toggling profile state and leaving them on
 * the previous page.
 */
export default function DeepLinkHandler() {
    const navigate = useNavigate();

    useEffect(() => {
        try {
            const params = new URLSearchParams(window.location.search);
            if (params.get('profile') !== 'kids') return;

            // 1. Switch profile + strip the param from the URL so a
            //    reload doesn't keep reapplying it.
            setActiveProfile('kids');
            params.delete('profile');
            const search = params.toString();
            const cleanUrl = window.location.pathname +
                (search ? `?${search}` : '');
            window.history.replaceState({}, '', cleanUrl);

            // 2. Hop to the Kids home.  Replace so the back button
            //    doesn't take the user out of Kids mode immediately.
            navigate('/', { replace: true });
        } catch {
            /* swallow — a malformed URL shouldn't crash boot */
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return null;
}
