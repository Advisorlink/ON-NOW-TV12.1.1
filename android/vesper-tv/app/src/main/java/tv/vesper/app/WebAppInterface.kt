package tv.vesper.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * JavaScript ↔ Android bridge.
 *
 * The web app calls window.OnNowTV.playVideo(url, title, mime) when
 * the user picks a stream.  We hand the URL off to the user's
 * preferred system video player (VLC, MX Player, Kodi, ExoPlayer-based
 * players, anything that handles ACTION_VIEW for video MIME types).
 *
 * Why?  The HK1 box's WebView is software-rendered for video — no
 * audio without gesture, awful frame rates on 1080p HLS.  System
 * players are hardware-accelerated, handle every codec under the sun,
 * have built-in subtitle pickers, and are what the user already knows.
 */
class WebAppInterface(private val activity: Activity) {

    /**
     * Return the SharedPreferences-backed progress map as JSON so
     * the web app can populate its Continue Watching shelf with
     * accurate positions.  Shape:
     *
     *   { "<cwId>": { "positionMs": 12345, "durationMs": 67890,
     *                  "updatedAt": 1700000000000 }, ... }
     */
    @JavascriptInterface
    fun getProgressMap(): String {
        val prefs = activity.getSharedPreferences(
            "onnowtv_progress", android.content.Context.MODE_PRIVATE
        )
        val out = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (v is String) {
                try {
                    out.put(k, org.json.JSONObject(v))
                } catch (_: Exception) { /* ignore malformed */ }
            }
        }
        return out.toString()
    }

    @JavascriptInterface
    fun playVideo(url: String, title: String?, mime: String?) {
        // Legacy bridge — kept for backwards compat with v1.1.x APKs.
        // Routes to the internal libVLC player.
        playInternal(url, title, null)
    }

    @JavascriptInterface
    fun playInternal(url: String, title: String?, subtitleUrl: String?) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VlcPlayerActivity::class.java).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, subtitleUrl)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Rich variant — used by the web layer to pass the full cinematic
     * preview meta (poster / backdrop / synopsis / year / rating /
     * runtime / genres) so the native player can render a Stremio-
     * style loading screen instead of a bare spinner.
     */
    @JavascriptInterface
    fun playInternalRich(
        url: String,
        title: String?,
        subtitleUrl: String?,
        poster: String?,
        backdrop: String?,
        synopsis: String?,
        year: String?,
        rating: String?,
        runtime: String?,
        genres: String?,
        type: String?,
        startAtMs: Long,
        cwId: String?
    ) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VlcPlayerActivity::class.java).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, subtitleUrl)
                    putExtra(VlcPlayerActivity.EXTRA_POSTER, poster)
                    putExtra(VlcPlayerActivity.EXTRA_BACKDROP, backdrop)
                    putExtra(VlcPlayerActivity.EXTRA_SYNOPSIS, synopsis)
                    putExtra(VlcPlayerActivity.EXTRA_YEAR, year)
                    putExtra(VlcPlayerActivity.EXTRA_RATING, rating)
                    putExtra(VlcPlayerActivity.EXTRA_RUNTIME, runtime)
                    putExtra(VlcPlayerActivity.EXTRA_GENRES, genres)
                    putExtra(VlcPlayerActivity.EXTRA_TYPE, type)
                    putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, startAtMs)
                    putExtra(VlcPlayerActivity.EXTRA_CW_ID, cwId)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Watch-Together variant — same payload as playInternalRich but
     * also passes the party code + role + member id + ws url so the
     * VlcPlayerActivity can open a sync WebSocket and emit/apply
     * play/pause/seek events.
     */
    @JavascriptInterface
    fun playInternalParty(
        url: String,
        title: String?,
        subtitleUrl: String?,
        poster: String?,
        backdrop: String?,
        synopsis: String?,
        year: String?,
        rating: String?,
        runtime: String?,
        genres: String?,
        type: String?,
        startAtMs: Long,
        cwId: String?,
        partyCode: String,
        partyRole: String,
        partyMemberId: String?,
        partyWsUrl: String
    ) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VlcPlayerActivity::class.java).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, subtitleUrl)
                    putExtra(VlcPlayerActivity.EXTRA_POSTER, poster)
                    putExtra(VlcPlayerActivity.EXTRA_BACKDROP, backdrop)
                    putExtra(VlcPlayerActivity.EXTRA_SYNOPSIS, synopsis)
                    putExtra(VlcPlayerActivity.EXTRA_YEAR, year)
                    putExtra(VlcPlayerActivity.EXTRA_RATING, rating)
                    putExtra(VlcPlayerActivity.EXTRA_RUNTIME, runtime)
                    putExtra(VlcPlayerActivity.EXTRA_GENRES, genres)
                    putExtra(VlcPlayerActivity.EXTRA_TYPE, type)
                    putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, startAtMs)
                    putExtra(VlcPlayerActivity.EXTRA_CW_ID, cwId)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_CODE, partyCode)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_ROLE, partyRole)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_MEMBER_ID, partyMemberId)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_WS_URL, partyWsUrl)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start party player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun playExternal(url: String, title: String?, mime: String?) {
        // Opt-in path: hand to system video player (VLC stand-alone,
        // MX Player, Kodi, etc.).  Used by power users from a button
        // inside our own player.
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val uri = Uri.parse(url)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime ?: guessMime(url))
                    if (!title.isNullOrBlank()) {
                        putExtra("title", title)
                        putExtra("itemTitle", title)
                        putExtra("video_title", title)
                        putExtra("decode_mode", 1)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                val chooser = Intent.createChooser(intent, "Play with…")
                activity.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "No external video player installed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun isAndroidHost(): Boolean = true

    /**
     * Dev-mode network override.  Persists a URL in
     * SharedPreferences("onnowtv-dev") that MainActivity.onCreate
     * uses instead of the bundled file:// URL on every launch.
     * Pass `null` or "" to clear and return to the bundled SPA.
     * After setting, we reload the WebView so the change takes
     * effect immediately without a force-close.
     */
    @JavascriptInterface
    fun setDevUrl(url: String?) {
        val prefs = activity.getSharedPreferences("onnowtv-dev", android.content.Context.MODE_PRIVATE)
        if (url.isNullOrBlank()) {
            prefs.edit().remove("dev_url").apply()
        } else {
            prefs.edit().putString("dev_url", url).apply()
        }
        // Reload the WebView (must run on UI thread).
        activity.runOnUiThread {
            val target = if (url.isNullOrBlank()) "file:///android_asset/web/index.html" else url
            try {
                val mainAct = activity as? MainActivity
                mainAct?.findViewById<android.webkit.WebView>(android.R.id.content)
                // Simpler: pull the webview field directly via the
                // bridge — but the WebAppInterface only has access
                // through the activity.  We trigger a reload via JS.
            } catch (_: Exception) {}
            activity.recreate()
        }
    }

    /** Current dev URL, or "" if unset. */
    @JavascriptInterface
    fun getDevUrl(): String {
        val prefs = activity.getSharedPreferences("onnowtv-dev", android.content.Context.MODE_PRIVATE)
        return prefs.getString("dev_url", "") ?: ""
    }

    /**
     * Launch the system speech recognizer (Google Voice / OEM STT)
     * and route the recognized text back to the React side.  React
     * stashes a Promise resolver in `window.__voiceSearch[callbackId]`
     * before calling this; the Activity's onActivityResult fires
     * `window.__voiceSearchResult(id, text, error)` once done.
     */
    @JavascriptInterface
    fun startVoiceSearch(callbackId: String) {
        activity.runOnUiThread {
            if (activity is MainActivity) {
                (activity as MainActivity).startVoiceRecognition(callbackId)
            } else {
                activity.runOnUiThread {
                    activity.window?.decorView?.post {
                        // Should never happen, but emit a graceful error.
                        val esc = callbackId.replace("\\", "\\\\").replace("'", "\\'")
                        val js = "window.__voiceSearchResult && " +
                            "window.__voiceSearchResult('$esc','','no-host')"
                        // No webView handle here — best-effort no-op.
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun deviceClass(): String {
        // Crude low-end heuristic — most cheap HK1/RK boxes have <=2 GB RAM
        // and weak GPUs.  The web app uses this to disable expensive
        // backdrop-blurs, ken-burns, and grain overlays.
        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return if (maxMb < 256) "low" else "normal"
    }

    private fun guessMime(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "application/x-mpegurl"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".ts") -> "video/mp2t"
            else -> "video/*"
        }
    }

    /**
     * Native HTTP GET that returns the JSON response body as a
     * string.  Used by the React side to call stream addons
     * (notably Torrentio) which block calls from datacenter IPs —
     * the HK1 box has a residential IP, so calls fired from Kotlin
     * succeed where the backend proxy fails.
     *
     * Returns a JSON object like:
     *   {"ok": true, "status": 200, "body": "{...}"}
     *   {"ok": false, "status": 0,   "error": "timeout"}
     *
     * Blocking call from Kotlin's perspective; JS treats it as
     * synchronous because @JavascriptInterface methods are invoked
     * on a private binder thread.  Hard cap timeout of 20 s.
     */
    @JavascriptInterface
    fun fetchUrl(url: String, timeoutMs: Int): String {
        return try {
            val u = java.net.URL(url)
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs.coerceIn(1000, 30000)
            conn.readTimeout = timeoutMs.coerceIn(1000, 30000)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 11; HK1) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0 Mobile Safari/537.36 OnNowTV/1.0"
            )
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val stream =
                if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            org.json.JSONObject().apply {
                put("ok", code in 200..299)
                put("status", code)
                put("body", body)
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("ok", false)
                put("status", 0)
                put("error", e.message ?: e.javaClass.simpleName)
            }.toString()
        }
    }
}
