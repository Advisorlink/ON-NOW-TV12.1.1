import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';

/**
 * v2.7.97 — Boot-time deep-link handler.
 *
 * The active-profile flip is done SYNCHRONOUSLY at module load in
 * `App.js` (before any React component mounts), so this component
 * only needs to:
 *   1. Strip the `profile=…` param from the URL so a refresh stays
 *      consistent and doesn't re-trigger the deep-link.
 *   2. Navigate to `/` so the user lands on the right Home (Kids or
 *      regular) instead of whatever route the WebView restored.
 *
 * Handles both:
 *   - `?profile=kids`       (launcher KIDS tile)
 *   - `?profile=exit-kids`  (launcher Movies/TV tile)
 */
export default function DeepLinkHandler() {
    const navigate = useNavigate();

    useEffect(() => {
        try {
            const params = new URLSearchParams(window.location.search);
            const profile = params.get('profile');
            if (profile !== 'kids' && profile !== 'exit-kids') return;

            // Strip the param so a refresh doesn't keep re-applying.
            params.delete('profile');
            const search = params.toString();
            const cleanUrl = window.location.pathname +
                (search ? `?${search}` : '');
            window.history.replaceState({}, '', cleanUrl);

            // Hop to the appropriate Home.  Replace so the back
            // button doesn't take the user back into the previous
            // mode immediately.
            navigate('/', { replace: true });
        } catch {
            /* swallow — a malformed URL shouldn't crash boot */
        }
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    return null;
}
