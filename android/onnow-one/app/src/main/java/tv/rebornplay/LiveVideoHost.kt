package tv.rebornplay

import android.app.Activity
import android.view.LayoutInflater
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import tv.onnowtv.livetv.DeviceProfile
import tv.onnowtv.livetv.LivePreviewSession
import tv.vesper.app.R

/**
 * The Live TV picture lives in ONE view at the top of the app. On the guide it sits exactly over the preview window;
 * for full screen that same view simply grows to fill the screen (the controls open as a see-through layer above it).
 * The picture is never handed to another view, so nothing reloads or pauses. The HK1 keeps its own, already smooth, path.
 */
object LiveVideoLayer {
    val enabled: Boolean get() = !DeviceProfile.isHk1
    var rect by mutableStateOf<Rect?>(null)          // where the guide's preview window is (window pixels); null = not on the guide
    var cornerPx by mutableStateOf(0f)
    var visible by mutableStateOf(false)             // a channel is playing in the preview
    var obscured by mutableStateOf(false)            // something on the guide is covering the preview
    var grown by mutableStateOf(false)
    var overlay by mutableStateOf<(@Composable BoxScope.() -> Unit)?>(null)
    var view by mutableStateOf<PlayerView?>(null)

    /** Grow the preview to full screen. False = not possible here (caller opens the ordinary player instead). */
    fun grow(activity: Activity): Boolean {
        val host = view ?: return false
        if (!enabled || rect == null || !host.isAttachedToWindow) return false
        if (tv.onnowtv.livetv.data.PlayerPrefs.getBackend(activity) != tv.onnowtv.livetv.data.PlayerPrefs.Backend.EXO) return false
        if (host.player !== LivePreviewSession.getOrCreate(activity)) LivePreviewSession.attachTo(host)
        host.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        grown = true
        LivePreviewSession.setFullscreenHost(host)
        return true
    }

    /** Back on the guide: the same view shrinks back into the preview window. */
    fun shrink() {
        if (!grown) return
        grown = false
        LivePreviewSession.setFullscreenHost(null)
        view?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
}

@Composable
fun LiveVideoHost() {
    if (!LiveVideoLayer.enabled) return
    val r = LiveVideoLayer.rect ?: return
    val grown = LiveVideoLayer.grown
    val d = LocalDensity.current
    DisposableEffect(Unit) { onDispose { LiveVideoLayer.view = null; LiveVideoLayer.grown = false; LivePreviewSession.setFullscreenHost(null) } }
    val show = grown || (LiveVideoLayer.visible && !LiveVideoLayer.obscured)
    Box(Modifier.fillMaxSize()) {
        Box((if (grown) Modifier.fillMaxSize().background(Color.Black)
             else Modifier.offset { IntOffset(Math.round(r.left), Math.round(r.top)) }.size(with(d) { r.width.toDp() }, with(d) { r.height.toDp() }).clip(RoundedCornerShape(LiveVideoLayer.cornerPx)))
            .graphicsLayer { alpha = if (show) 1f else 0f }) {
            AndroidView(factory = { c -> (LayoutInflater.from(c).inflate(R.layout.reborn_live_texture, null) as PlayerView).also { LiveVideoLayer.view = it } }, modifier = Modifier.fillMaxSize())
            if (!grown) LiveVideoLayer.overlay?.invoke(this)
        }
    }
}
