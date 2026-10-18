package tv.onnowtv.vespernative.ui

import android.content.Context
import android.content.res.Configuration
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** V2's 1920x1080 composition is authored in half-sized native units.
 * Generic TV boxes often report mdpi even at 1080p. Never mutate global resources:
 * only the browse Activity uses this coordinate system; the original player does not.
 */
object TvViewport {
    fun densityDpi(width: Int, height: Int): Int =
        (min(max(width,height)/960f, min(width,height)/540f)*160).roundToInt().coerceAtLeast(80)

    fun context(base: Context): Context {
        val metrics=base.resources.displayMetrics
        if(metrics.widthPixels<=0 || metrics.heightPixels<=0)return base
        val config=Configuration(base.resources.configuration)
        config.densityDpi=densityDpi(metrics.widthPixels,metrics.heightPixels)
        config.screenWidthDp=(metrics.widthPixels*160f/config.densityDpi).roundToInt()
        config.screenHeightDp=(metrics.heightPixels*160f/config.densityDpi).roundToInt()
        config.smallestScreenWidthDp=min(config.screenWidthDp,config.screenHeightDp)
        // fontScale is deliberately preserved. No app-wide configuration update.
        return base.createConfigurationContext(config)
    }
    fun browseColumns(widthDp:Int)=if(widthDp>=768)6 else 4
}