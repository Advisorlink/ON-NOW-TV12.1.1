package tv.vesper.app

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/**
 * - Locks navigation to local assets + the configured backend host
 *   (so signing into Plex / Jellyfin etc. opens in-place rather than
 *   ricocheting to the system browser).
 * - Blocks any outbound request to Emergent / PostHog hosts so the
 *   sideloaded APK can never show the "You're viewing a static
 *   preview — Resume to interact" banner that those scripts inject.
 * - On every page finish, injects a tiny JS snippet that nukes any
 *   "Made with Emergent" preview badge that may have been bundled in
 *   the build — belt-and-braces alongside the CSS rule.
 */
class VesperWebViewClient : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val host = request?.url?.host?.lowercase() ?: return null
        val blocked = BLOCKED_HOSTS.any { suffix -> host.endsWith(suffix) }
        if (blocked) {
            // Return an empty 200 so the WebView doesn't render an
            // ugly net-error banner — the script just silently no-ops.
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                ByteArrayInputStream(ByteArray(0))
            )
        }
        return super.shouldInterceptRequest(view, request)
    }

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

    override fun onPageStarted(
        view: WebView?,
        url: String?,
        favicon: android.graphics.Bitmap?
    ) {
        super.onPageStarted(view, url, favicon)
        // Inject the killer stylesheet as early as possible so the
        // badge never gets a chance to flash on screen.
        view?.evaluateJavascript(BADGE_NUKE_JS, null)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(BADGE_NUKE_JS, null)
    }

    companion object {
        // Hosts whose scripts inject the "You're viewing a static
        // preview" banner / "Made with Emergent" badge / PostHog
        // session recording.  Blocked at network level so they
        // never reach the WebView.
        private val BLOCKED_HOSTS = listOf(
            "assets.emergent.sh",
            "app.emergent.sh",
            "emergent.sh",
            "us.i.posthog.com",
            "i.posthog.com",
            "posthog.com",
        )

        private val BADGE_NUKE_JS = """
            (function nuke() {
                const cssRule = `
                    #emergent-badge,
                    [id*="emergent-badge"],
                    [class*="emergent-badge"],
                    a[href*="emergent.sh"],
                    a[href*="emergent.com"],
                    a[href*="app.emergent"],
                    iframe[src*="emergent"],
                    div[class*="MadeWith"],
                    div[class*="madewith"] {
                        display: none !important;
                        visibility: hidden !important;
                        opacity: 0 !important;
                        pointer-events: none !important;
                        height: 0 !important;
                        width: 0 !important;
                        position: absolute !important;
                        left: -99999px !important;
                    }
                `;
                // 1) Inject a permanent stylesheet so the badge can
                //    never re-render visibly even if it's re-mounted.
                if (!document.getElementById('onnowtv-killcss')) {
                    const s = document.createElement('style');
                    s.id = 'onnowtv-killcss';
                    s.textContent = cssRule;
                    (document.head || document.documentElement).appendChild(s);
                }

                const sel = [
                    '#emergent-badge',
                    '[id*="emergent-badge"]',
                    '[class*="emergent-badge"]',
                    'a[href*="emergent.sh"]',
                    'a[href*="emergent.com"]',
                    'a[href*="app.emergent"]',
                    'iframe[src*="emergent"]',
                    'div[class*="MadeWith"]',
                    'div[class*="madewith"]'
                ].join(',');

                const sweep = () => {
                    document.querySelectorAll(sel).forEach(n => n.remove());
                    // Also nuke any element whose visible text is
                    // "Made with Emergent" — handles cases where the
                    // badge has no distinctive id/class.
                    document.querySelectorAll('a, div, span, button').forEach(el => {
                        if (el.children.length === 0) {
                            const t = (el.textContent || '').trim().toLowerCase();
                            if (t === 'made with emergent' || t === 'powered by emergent') {
                                let target = el;
                                // Walk up to the nearest positioned ancestor
                                for (let i = 0; i < 4 && target.parentElement; i++) {
                                    const cs = getComputedStyle(target.parentElement);
                                    if (cs.position === 'fixed' || cs.position === 'absolute') {
                                        target = target.parentElement;
                                        break;
                                    }
                                    target = target.parentElement;
                                }
                                target.remove();
                            }
                        }
                    });
                };
                sweep();

                // 2) Permanent MutationObserver — runs for the lifetime
                //    of the page.  Adds ~negligible CPU cost.
                if (!window.__onnowtv_badge_obs) {
                    window.__onnowtv_badge_obs = new MutationObserver(sweep);
                    window.__onnowtv_badge_obs.observe(document.documentElement, {
                        childList: true, subtree: true
                    });
                }
            })();
        """.trimIndent()
    }
}
