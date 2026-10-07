package tv.vesper.app

import android.content.Intent
import android.os.SystemClock
import java.lang.ref.WeakReference
import org.json.JSONObject

/** Correlate the real Android Activity lifecycle with one web play request.
 * No URLs/credentials are sent back to the page or diagnostics. */
object NativePlaybackSession {
    const val EXTRA_REQUEST = "vesper.playback_request"
    private var owner = WeakReference<MainActivity>(null)
    private var request = ""
    private var launched = false
    // True while the player Activity is re-launching itself (engine
    // fallback cascade).  MainActivity can be resumed for a frame in
    // between, which must NOT read as the user "returning".
    private var relaunching = false
    private var startedAt = 0L
    private var seq = 0

    fun launch(activity: MainActivity, id: String, payload: String) {
        owner = WeakReference(activity)
        request = id.take(100)
        launched = false
        relaunching = false
        startedAt = SystemClock.uptimeMillis()
        seq = 0
        try {
            require(payload.length <= 500_000) { "Playback request too large" }
            val data = JSONObject(payload)
            val url = data.optString("url")
            require(url.isNotBlank()) { "No playable stream URL" }
            require(url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("magnet:", true) || url.startsWith("file://torrent/", true)) { "Unsupported stream address" }
            emit("received")
            val torrent = url.startsWith("magnet:", true) || url.startsWith("file://torrent/", true)
            val target = if (!torrent && ExoPlayerActivity.shouldUseExoPlayer(activity)) ExoPlayerActivity::class.java else VlcPlayerActivity::class.java
            val intent = Intent(activity, target).apply {
                putExtra(EXTRA_REQUEST, request)
                putExtra(VlcPlayerActivity.EXTRA_URL, url)
                val fields = mapOf(
                    "title" to VlcPlayerActivity.EXTRA_TITLE,
                    "subtitleUrl" to VlcPlayerActivity.EXTRA_SUB_URL,
                    "poster" to VlcPlayerActivity.EXTRA_POSTER,
                    "backdrop" to VlcPlayerActivity.EXTRA_BACKDROP,
                    "synopsis" to VlcPlayerActivity.EXTRA_SYNOPSIS,
                    "year" to VlcPlayerActivity.EXTRA_YEAR,
                    "rating" to VlcPlayerActivity.EXTRA_RATING,
                    "runtime" to VlcPlayerActivity.EXTRA_RUNTIME,
                    "genres" to VlcPlayerActivity.EXTRA_GENRES,
                    "type" to VlcPlayerActivity.EXTRA_TYPE,
                    "cwId" to VlcPlayerActivity.EXTRA_CW_ID,
                )
                fields.forEach { (key, extra) -> if (!data.isNull(key)) putExtra(extra, data.optString(key)) }
                putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, data.optLong("startAtMs", 0L).coerceAtLeast(0L))
                putExtra(VlcPlayerActivity.EXTRA_STREAMS_JSON, data.optString("streamsJson", "[]"))
                putExtra(VlcPlayerActivity.EXTRA_CURRENT_STREAM_IDX, data.optInt("currentStreamIdx", -1))
            }
            activity.startActivity(intent)
            launched = true
            emit("launched", target.simpleName)
        } catch (error: Throwable) {
            emit("failed", "Android could not open the player (${error.javaClass.simpleName}). Try another stream.")
        }
    }

    private fun matches(intent: Intent?): Boolean =
        request.isNotBlank() && intent?.getStringExtra(EXTRA_REQUEST) == request

    /** Free-form lifecycle breadcrumb from a player Activity. Never
     * changes the launched/relaunching state. */
    fun stage(intent: Intent?, name: String, message: String = "") {
        if (matches(intent)) emit(name, message)
    }

    /** Player Activity is restarting itself (engine fallback). */
    fun retrying(intent: Intent?, message: String) {
        if (!matches(intent)) return
        relaunching = true
        emit("retrying", message)
    }

    fun opened(intent: Intent) {
        if (!matches(intent)) return
        relaunching = false
        launched = true
        emit("opened")
    }

    fun failed(intent: Intent, message: String) {
        if (!matches(intent)) return
        launched = false
        relaunching = false
        emit("failed", message)
    }

    fun returned(activity: MainActivity) {
        if (owner.get() !== activity || !launched) return
        if (relaunching) { emit("host-resumed-during-retry"); return }
        launched = false
        emit("returned")
    }

    /** Host-side breadcrumbs while a request is in flight. */
    fun hostEvent(activity: MainActivity, name: String, message: String = "") {
        if (owner.get() === activity && request.isNotBlank() && (launched || relaunching)) emit(name, message)
    }

    private fun emit(status: String, message: String = "") {
        owner.get()?.reportNativePlayback(JSONObject()
            .put("requestId", request).put("status", status).put("message", message.take(300))
            .put("seq", ++seq).put("t", SystemClock.uptimeMillis() - startedAt)
            .put("appVersion", BuildConfig.VERSION_NAME).put("appBuild", BuildConfig.VERSION_CODE))
    }
}
