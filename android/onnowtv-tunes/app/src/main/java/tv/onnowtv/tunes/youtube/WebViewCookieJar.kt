package tv.onnowtv.tunes.youtube

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp CookieJar backed by Android's process-wide
 * `android.webkit.CookieManager`.
 *
 * This is the magic that lets the signed-in YouTube session live
 * in **one** cookie store shared between:
 *
 *   • The WebView the user signs into (handles the actual login UI).
 *   • The embedded YouTube IFrame Player (inherits cookies for free).
 *   • Our `OkHttp` client doing authenticated InnerTube calls
 *     from Kotlin.
 *
 * Without this jar the OkHttp client would carry no auth, the
 * TVHTML5 InnerTube endpoint would reject us with `LOGIN_REQUIRED`,
 * and we'd be stuck with ad-laden IFrame playback again.
 */
class WebViewCookieJar : CookieJar {

    private val cm = android.webkit.CookieManager.getInstance()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = cm.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { piece ->
            try {
                Cookie.parse(url, piece.trim())
            } catch (_: Throwable) {
                null
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach {
            try { cm.setCookie(url.toString(), it.toString()) } catch (_: Throwable) {}
        }
        try { cm.flush() } catch (_: Throwable) {}
    }
}
