package tv.onnowtv.livetv

import android.os.Build

/** The HK1 box is the reference device — everything must look and behave there exactly as it always has. */
object DeviceProfile {
    val isHk1: Boolean by lazy {
        val tags = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.BOARD, Build.MANUFACTURER).joinToString(" ").uppercase()
        val brandedBox = listOf("Amazon", "NVIDIA", "Google").any { Build.MANUFACTURER.equals(it, ignoreCase = true) }
        val lowDensityBox = !brandedBox && android.content.res.Resources.getSystem().displayMetrics.densityDpi <= 160
        tags.contains("HK1") || lowDensityBox
    }
}
