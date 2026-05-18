package tv.vesper.app

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit

/**
 * - Locks navigation to local assets + the configured backend host
 *   (so signing into Plex / Jellyfin etc. opens in-place rather than
 *   ricocheting to the system browser).
 * - Blocks any outbound request to Emergent / PostHog hosts so the
 *   sideloaded APK can never show the "You're viewing a static
 *   preview — Resume to interact" banner that those scripts inject.
 * - Transparently bypasses CORS for Xtream Codes IPTV API calls: the
 *   IPTV server cannot be reached from the Emergent backend pod, so
 *   we call it directly from the WebView — but since the React app
 *   is served from `*.emergentagent.com`, any cross-origin fetch
 *   would be blocked by the WebView's CORS enforcement.  We solve
 *   this by intercepting requests to the /player_api.php endpoint
 *   and proxying them through an OkHttp client at the native layer,
 *   re-emitting the response with permissive `Access-Control-Allow-*`
 *   headers.
 * - On every page finish, injects a tiny JS snippet that nukes any
 *   "Made with Emergent" preview badge that may have been bundled in
 *   the build — belt-and-braces alongside the CSS rule.
 */
class VesperWebViewClient : WebViewClient() {

    // Lazily-built OkHttp client.  Long read timeout because some
    // Xtream servers stream the categories response slowly.
    private val xtreamHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val url = request?.url ?: return null
        val host = url.host?.lowercase() ?: return null

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

        // -----------------------------------------------------------
        //  Xtream Codes API proxy — only intercept the JSON API
        //  endpoint.  Stream URLs (/live/, /movie/, /series/) are
        //  played by libVLC, not fetched as JS resources, so they
        //  never hit this code path.
        // -----------------------------------------------------------
        val path = url.path ?: ""
        val looksLikeXtreamApi = path.endsWith("/player_api.php") ||
            path.endsWith("/xmltv.php") ||
            path.endsWith("/get.php")
        if (looksLikeXtreamApi) {
            return try {
                proxyXtream(url.toString(), request.method)
            } catch (e: Exception) {
                Log.w("VesperWebView", "Xtream proxy failed for $url: ${e.message}")
                null
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    /**
     * Fetch the requested Xtream URL via OkHttp and return a
     * WebResourceResponse with permissive CORS headers so the
     * WebView's `fetch()` JS call is happy to read the body.
     */
    private fun proxyXtream(url: String, method: String?): WebResourceResponse {
        val req = Request.Builder()
            .url(url)
            .method(method ?: "GET", null)
            .header("User-Agent", "VesperTV/1.0 (Android)")
            .build()
        val resp = xtreamHttp.newCall(req).execute()
        val body = resp.body
        val mime = body?.contentType()?.toString()?.split(';')?.firstOrNull()?.trim()
            ?: "application/json"
        val charset = body?.contentType()?.charset()?.name() ?: "utf-8"
        val bytes = body?.bytes() ?: ByteArray(0)
        val headers = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*",
            "Cache-Control" to "no-store",
        )
        return WebResourceResponse(
            mime,
            charset,
            resp.code,
            if (resp.message.isBlank()) "OK" else resp.message,
            headers,
            ByteArrayInputStream(bytes)
        )
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
        // Intent URLs from the YouTube iframe try to launch the
        // YouTube app — swallow them silently so the embedded
        // <video> stays in-page.  Same for any youtube://
        // / vnd.youtube:// deep links.
        if (url.startsWith("intent://") ||
            url.startsWith("youtube://") ||
            url.startsWith("vnd.youtube:") ||
            url.contains("scheme=youtube")
        ) {
            Log.d("VesperWebView", "Swallowing YouTube app intent: $url")
            return true
        }
        // Anything else (mailto:, market://, magnet:, …)
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
        // Expose the installed APK version to the React app so the
        // <UpdateGate/> can compare it against the GitHub latest tag
        // and show the forced-update screen.  Set `window.__APP_VERSION__`
        // BEFORE the React bundle parses so the first render of the
        // gate has the value available.
        view?.evaluateJavascript(
            "window.__APP_VERSION__ = '" + BuildConfig.VERSION_NAME + "';",
            null,
        )
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.evaluateJavascript(BADGE_NUKE_JS, null)
        // Re-set in case the bundle nuked it during hydration.
        view?.evaluateJavascript(
            "window.__APP_VERSION__ = '" + BuildConfig.VERSION_NAME + "';",
            null,
        )
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
