package tv.vesper.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * JavaScript ↔ Android bridge.
 *
 * The web app calls `window.OnNowTV.playVideo(url, title, mime)` when
 * the user picks a stream.  We hand the URL off to the user's
 * preferred system video player (VLC, MX Player, Kodi, ExoPlayer-based
 * players, anything that handles ACTION_VIEW for "video/*").
 *
 * Why?  The HK1 box's WebView is software-rendered for video — no
 * audio without gesture, awful frame rates on 1080p HLS.  System
 * players are hardware-accelerated, handle every codec under the sun,
 * have built-in subtitle pickers, and are what the user already knows.
 */
class WebAppInterface(private val activity: Activity) {

    @JavascriptInterface
    fun playVideo(url: String, title: String?, mime: String?) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val uri = Uri.parse(url)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime ?: guessMime(url))
                    if (!title.isNullOrBlank()) {
                        putExtra("title", title)
                        // VLC reads this:
                        putExtra("itemTitle", title)
                        // MX Player reads these:
                        putExtra("video_title", title)
                        putExtra("decode_mode", 1) // hardware
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                val chooser = Intent.createChooser(intent, "Play with…")
                activity.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "No video player installed.  Install VLC from Play Store.",
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
