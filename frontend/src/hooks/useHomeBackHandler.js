import { useEffect } from 'react';

/**
 * Marks the WebView as being on the Home page so the native
 * MainActivity can choose the right Back-key behaviour:
 *
 *   • mode === 'home-root'   → show "Close ON NOW TV?" exit confirm
 *   • mode === 'home-filter' → fall through to web history (go back
 *                              to the bare home page from a filter
 *                              view like Movies / TV Shows)
 *   • absent / other         → Activity uses its normal goBack /
 *                              finish flow
 *
 * The Kotlin side reads `window.__vesperOnHome` via
 * webView.evaluateJavascript on every KEYCODE_BACK press.  Keeping
 * the flag in JS lets routing changes update it instantly without
 * extra IPC.
 */
export default function useHomeBackHandler(mode) {
    useEffect(() => {
        if (typeof window === 'undefined') return undefined;
        const prev = window.__vesperOnHome;
        window.__vesperOnHome = mode || '';
        return () => {
            window.__vesperOnHome = prev || '';
        };
    }, [mode]);
}
