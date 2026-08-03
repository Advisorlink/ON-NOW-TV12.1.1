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

    /** v2.18.0 — Now-playing reporting for the phone Companion app.
     *  The React music engine calls `OnNowTV.nowPlaying(json)` on
     *  track / play-state changes; we re-broadcast it on the same
     *  intent the launcher's RemoteControlService already listens
     *  to for Vesper, tagged `source=tunes`. */
    @JavascriptInterface
    fun nowPlaying(json: String) {
        try {
            val ctx = webView.context.applicationContext
            val o = JSONObject(json)
            val intent = android.content.Intent("tv.onnow.remote.NOW_PLAYING")
            if (o.optBoolean("cleared", false)) {
                intent.putExtra("cleared", true)
            } else {
                intent.putExtra("title", o.optString("title"))
                intent.putExtra("artist", o.optString("artist"))
                intent.putExtra("poster", o.optString("poster"))
                intent.putExtra("backdrop", o.optString("poster"))
                intent.putExtra("position_ms", o.optLong("position_ms", 0L))
                intent.putExtra("duration_ms", o.optLong("duration_ms", 0L))
                intent.putExtra("playing", o.optBoolean("playing", true))
                intent.putExtra("live", o.optBoolean("live", false))
                intent.putExtra("source", "tunes")
            }
            ctx.sendBroadcast(intent)
        } catch (t: Throwable) {
            Log.w("OnNowTvBridge", "nowPlaying broadcast failed: ${t.message}")
        }
    }

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
     * v2.12.0 — NewPipeExtractor is now the ONLY resolver.  It
     * runs anonymously from this box's residential IP, requires no
     * cookies, no YouTube sign-in, and returns direct
     * googlevideo.com CDN URLs the HTML5 `<audio>` element streams
     * with zero VPS involvement.
     *
     * Invokes `window.__onnowtvMusicCB(callbackId, jsonPayload)` when
     * done.
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
                    YouTubeResolver.resolve(artist, title)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "bridge resolve crashed", t)
                errorPayload(t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
            }
            postCallback(callbackId, payload)
        }
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
