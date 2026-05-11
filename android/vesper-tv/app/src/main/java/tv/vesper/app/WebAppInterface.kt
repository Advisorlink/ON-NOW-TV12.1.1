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
        type: String?
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
}
