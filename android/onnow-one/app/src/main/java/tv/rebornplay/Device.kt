package tv.rebornplay

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Fire TV / Android TV / Google TV / Shield / generic TV boxes — anything driven by a remote on a big screen. */
object Device {
    private var tv: Boolean? = null
    fun isTvClass(ctx: Context): Boolean = tv ?: run {
        val pm = ctx.packageManager
        val ui = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(dm)
        // Cheap boxes (HK1, TX, X96 …) call themselves tablets: 160 dpi on a 720p+ picture is never a phone.
        val box = dm.densityDpi <= 160 && maxOf(dm.widthPixels, dm.heightPixels) >= 1280
        (ui || pm.hasSystemFeature("android.software.leanback") || pm.hasSystemFeature("android.software.leanback_only") || pm.hasSystemFeature("amazon.hardware.fire_tv")
            || pm.hasSystemFeature("android.hardware.type.television") || !pm.hasSystemFeature("android.hardware.touchscreen") || box).also { tv = it }
    }
}

/** Density of the 1280-wide TV canvas (what every screen except the scaled Live TV panels is drawn in). */
val LocalOuterDensity = compositionLocalOf<Density?> { null }

/**
 * Boxes report wildly different densities for the same picture (HK1 160 dpi = 1920 dp wide, Fire TV / Shield 320 dpi = 960 dp,
 * 4K UIs 640 dpi …). On TV-class devices everything is laid out on one fixed 1280-dp-wide canvas so every box shows the
 * identical layout at the identical relative size. Phones / tablets keep their own density.
 */
@Composable
fun TvCanvas(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val cfg = LocalConfiguration.current
    val real = LocalDensity.current
    if (!Device.isTvClass(ctx) || cfg.screenWidthDp <= cfg.screenHeightDp) { CompositionLocalProvider(LocalOuterDensity provides real) { content() }; return }
    val widthPx = cfg.screenWidthDp * real.density
    val d = widthPx / 1280f
    val canvas = remember(cfg, d) { Configuration(cfg).apply {
        screenWidthDp = 1280; screenHeightDp = Math.round(cfg.screenHeightDp * real.density / d); smallestScreenWidthDp = screenHeightDp; densityDpi = Math.round(d * 160f); fontScale = 1f
    } }
    val density = remember(d) { Density(d, 1f) }
    CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides canvas, LocalOuterDensity provides density) { content() }
}
