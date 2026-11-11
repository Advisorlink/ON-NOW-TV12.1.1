package tv.rebornplay

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/**
 * Second, independent tamper check for Reborn Play (the Application-level guard runs first).
 * A re-signed / re-branded / re-packaged / debuggable copy closes itself. Release builds only.
 */
object Lockdown {
    private val a = longArrayOf(0x5b43b7c2338a635cL, 0x357af29cdab3977cL, 0x6be55c8711434446L, 0x13d4e2c4d0d78456L)
    private const val M = 0x5555555555555555L
    private val n = intArrayOf(0x17, 0x20, 0x27, 0x2A, 0x37, 0x2B, 0x65, 0x15, 0x29, 0x24, 0x3C)          // label ^ 0x45
    private val p = intArrayOf(0x31, 0x33, 0x6B, 0x2A, 0x2B, 0x2B, 0x2A, 0x32, 0x31, 0x33, 0x6B, 0x2A, 0x2B, 0x20)   // package ^ 0x45
    internal fun label() = s(n)
    internal fun pkg() = s(p)
    private fun s(x: IntArray) = String(CharArray(x.size) { (x[it] xor 0x45).toChar() })
    @Volatile private var last = 0L

    fun enforce(activity: Activity) {
        if (tv.vesper.app.BuildConfig.DEBUG) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (last != 0L && now - last < 180_000L) return
        last = now
        val ok = runCatching { genuine(activity) }.getOrDefault(false)
        if (!ok) { runCatching { activity.finishAffinity() }; kotlin.system.exitProcess(0) }
    }

    private fun genuine(act: Activity): Boolean {
        val pm = act.packageManager
        if (act.packageName != s(p)) return false
        val ai = act.applicationInfo
        if (ai.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) return false
        if (ai.loadLabel(pm).toString() != s(n)) return false
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = pm.getPackageInfo(act.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return false
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else pm.getPackageInfo(act.packageName, PackageManager.GET_SIGNATURES).signatures
        if (sigs == null || sigs.isEmpty()) return false
        return sigs.any { sig -> matches(MessageDigest.getInstance("SHA-256").digest(sig.toByteArray())) }
    }

    internal fun matches(h: ByteArray): Boolean {
        if (h.size != 32) return false
        var diff = 0L
        for (i in 0 until 4) {
            var v = 0L
            for (j in 0 until 8) v = (v shl 8) or (h[i * 8 + j].toLong() and 0xFF)
            diff = diff or (v xor a[i] xor M)
        }
        return diff == 0L
    }
}
