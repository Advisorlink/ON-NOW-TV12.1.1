package tv.vesper.app

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * - Locks navigation to local assets + the configured backend host
 *   (so signing into Plex / Jellyfin etc. opens in-place rather than
 *   ricocheting to the system browser).
 * - On every page finish, injects a tiny JS snippet that nukes any
 *   "Made with Emergent" preview badge that may have been bundled in
 *   the build — belt-and-braces alongside the CSS rule.
 */
class VesperWebViewClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        // Allow our own asset URLs and any HTTPS API call to bubble
        // through the WebView itself.
        if (url.startsWith("file:///android_asset/")) return false
        if (url.startsWith("https://")) return false
        // Anything else (intent://, mailto:, market://, magnet:, …)
        // we let Android handle natively.
        return try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                request.url
            ).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            view?.context?.startActivity(intent)
            true
        } catch (_: Exception) {
            true // swallow — better than navigating to a broken URL
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(BADGE_NUKE_JS, null)
    }

    companion object {
        private val BADGE_NUKE_JS = """
            (function nuke() {
                const sel = [
                    '#emergent-badge',
                    '[id*="emergent-badge"]',
                    '[class*="emergent-badge"]',
                    'a[href*="emergent.sh"]',
                    'a[href*="emergent.com"]',
                    'iframe[src*="emergent"]'
                ];
                document.querySelectorAll(sel.join(',')).forEach(n => n.remove());
                // Re-run on DOM mutations for a few seconds in case
                // the badge is injected late.
                if (!window.__onnowtv_badge_obs) {
                    window.__onnowtv_badge_obs = new MutationObserver(() => {
                        document.querySelectorAll(sel.join(',')).forEach(n => n.remove());
                    });
                    window.__onnowtv_badge_obs.observe(document.body, {
                        childList: true, subtree: true
                    });
                    setTimeout(() => {
                        window.__onnowtv_badge_obs?.disconnect();
                        window.__onnowtv_badge_obs = null;
                    }, 12000);
                }
            })();
        """.trimIndent()
    }
}
