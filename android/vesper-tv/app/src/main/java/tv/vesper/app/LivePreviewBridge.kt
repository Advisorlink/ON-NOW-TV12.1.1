package tv.vesper.app

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.math.roundToInt

/**
 * Live TV preview window for the in-app (web) channel guide.
 * The React guide reports the CSS rect of its preview box; we float an
 * ExoPlayer TextureView over the WebView at the same spot.  OK in the
 * guide hands off to the fullscreen player (playVideo), which hides this.
 */
@UnstableApi
class LivePreviewBridge(
    private val activity: Activity,
    private val root: FrameLayout,
    private val webView: WebView,
) {
    private var container: FrameLayout? = null
    private var texture: TextureView? = null
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var onStateJs: ((String) -> Unit)? = null

    fun setStateListener(cb: (String) -> Unit) { onStateJs = cb }

    private fun scale(cssWidth: Float): Float {
        if (cssWidth <= 0f) return webView.resources.displayMetrics.density
        return webView.width.toFloat() / cssWidth
    }

    private fun ensureViews(): FrameLayout {
        container?.let { return it }
        val c = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isFocusable = false
            isClickable = false
        }
        val t = TextureView(activity).apply { isOpaque = true }
        c.addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        root.addView(c, FrameLayout.LayoutParams(1, 1))
        container = c
        texture = t
        return c
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("RebornPlay/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_500, 15_000, 800, 1_500)
            .build()
        val p = ExoPlayer.Builder(activity, DefaultRenderersFactory(activity).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                onStateJs?.invoke(
                    when (state) {
                        Player.STATE_BUFFERING -> "buffering"
                        Player.STATE_READY -> "playing"
                        Player.STATE_ENDED -> "ended"
                        else -> "idle"
                    },
                )
            }
            override fun onPlayerError(error: PlaybackException) {
                onStateJs?.invoke("error")
                // One soft retry: live edges stall / 404 briefly on channel hops.
                val url = currentUrl ?: return
                container?.postDelayed({ if (currentUrl == url) restart(url) }, 1_500)
            }
        })
        texture?.let { p.setVideoTextureView(it) }
        player = p
        return p
    }

    private fun restart(url: String) {
        val p = player ?: return
        try {
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            p.playWhenReady = true
        } catch (_: Exception) { }
    }

    fun show(url: String, x: Float, y: Float, w: Float, h: Float, cssWidth: Float) {
        activity.runOnUiThread {
            val c = ensureViews()
            move(x, y, w, h, cssWidth)
            c.visibility = View.VISIBLE
            c.bringToFront()
            val p = ensurePlayer()
            if (currentUrl != url || p.playbackState == Player.STATE_IDLE) {
                currentUrl = url
                restart(url)
            } else {
                p.playWhenReady = true
            }
        }
    }

    fun move(x: Float, y: Float, w: Float, h: Float, cssWidth: Float) {
        val c = container ?: return
        val s = scale(cssWidth)
        val lp = (c.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(1, 1)
        lp.width = (w * s).roundToInt().coerceAtLeast(1)
        lp.height = (h * s).roundToInt().coerceAtLeast(1)
        lp.leftMargin = (x * s).roundToInt()
        lp.topMargin = (y * s).roundToInt()
        c.layoutParams = lp
    }

    fun hide() {
        activity.runOnUiThread {
            container?.visibility = View.GONE
            currentUrl = null
            player?.let { it.playWhenReady = false; it.stop() }
        }
    }

    fun release() {
        activity.runOnUiThread {
            currentUrl = null
            player?.release()
            player = null
            container?.let { root.removeView(it) }
            container = null
            texture = null
        }
    }

    fun isShowing(): Boolean = container?.visibility == View.VISIBLE
}
