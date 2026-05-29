package tv.onnowtv.tunes

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.tunes.youtube.YouTubeResolver

/**
 * JS bridge exposed as `window.OnNowTV.*` inside the Tunes WebView.
 *
 * Methods are async by virtue of the callback-id pattern — every
 * JS call passes a `callbackId`, the Kotlin side runs the work on
 * a coroutine, and when it's done it invokes
 * `window.__onnowtvMusicCB(callbackId, jsonPayload)` on the
 * WebView's UI thread.
 *
 * Wired up from MainActivity:
 *
 *     web.addJavascriptInterface(OnNowTvBridge(web), "OnNowTV")
 *
 * From the React side the call looks like:
 *
 *     const url = await window.__onnowtvResolveAudio(artist, title);
 *     // ↑ defined in `lib/musicResolver.js` — wraps the bridge in
 *     //   a Promise keyed by callbackId.
 */
class OnNowTvBridge(private val webView: WebView) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Always-true flag JS can read to detect the native bridge is up. */
    @JavascriptInterface
    fun isNative(): Boolean = true

    /** Build identifier so the React side can log which APK ran. */
    @JavascriptInterface
    fun buildName(): String = BuildConfig.VERSION_NAME

    /**
     * Resolve a YouTube audio URL for the given artist + title.
     *
     * v2.8.52 — Tries the **custom InnerTubeResolver first** (direct
     * youtubei/v1 calls with the ANDROID client — no PoToken, no
     * signature deciphering, no NewPipe baggage).  Falls back to
     * NewPipeExtractor only if InnerTube returns no result.
     *
     * Both resolvers run on this box's residential IP, so the bot
     * detection that blocks our datacenter VPS doesn't apply.
     *
     * Invokes `window.__onnowtvMusicCB(callbackId, jsonPayload)` when
     * done.  `jsonPayload` follows the same shape regardless of which
     * resolver actually succeeded.
     */
    @JavascriptInterface
    fun resolveYouTubeAudio(artist: String, title: String, callbackId: String) {
        if (artist.isBlank() || title.isBlank() || callbackId.isBlank()) {
            postCallback(callbackId, errorPayload("artist/title/callbackId required"))
            return
        }
        scope.launch {
            val payload = try {
                withContext(Dispatchers.IO) {
                    // 1) InnerTube (our own resolver) — primary path.
                    val direct = tv.onnowtv.tunes.youtube.InnerTubeResolver
                        .resolve(artist, title)
                    if (direct.optBoolean("ok", false)) {
                        direct
                    } else {
                        // 2) NewPipeExtractor fallback (kept around in
                        //    case InnerTube starts requiring PoToken
                        //    in the future).  Most "ok=false" returns
                        //    from InnerTube will land here.
                        val np = YouTubeResolver.resolve(artist, title)
                        // Stash the InnerTube error on the payload so
                        // the debug overlay can show both reasons.
                        if (!np.optBoolean("ok", false)) {
                            np.put("inner_tube_error", direct.optString("error", ""))
                        }
                        np
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bridge resolve crashed", t)
                errorPayload(t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            }
            postCallback(callbackId, payload)
        }
    }

    /** Drop all in-memory NewPipe caches.  Useful after sign-in. */
    @JavascriptInterface
    fun resetResolver() {
        // Resolver cache is a private ConcurrentHashMap; the lightest
        // way to nuke it is to keep the public surface clean and just
        // reset the singleton.  Future iteration: expose a clearCache().
    }

    private fun postCallback(callbackId: String, payload: JSONObject) {
        val js = "window.__onnowtvMusicCB && window.__onnowtvMusicCB(" +
            jsString(callbackId) + ", " + payload.toString() + ");"
        webView.post {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun jsString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }

    private fun errorPayload(msg: String): JSONObject =
        JSONObject().put("ok", false).put("error", msg).put("source", "newpipe")

    @Suppress("unused")
    fun shutdown() {
        scope.coroutineContext[Job]?.cancel()
    }

    companion object {
        private const val TAG = "OnNowTvBridge"
    }
}
