package tv.onnowtv.livetv.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import tv.onnowtv.livetv.BuildConfig
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * IntegrityGuard (Live TV) — same defensive posture as the V2 app.
 * Runs once on cold start (release builds only) and hard-kills the
 * process if the APK has been re-signed, re-packaged, run under a
 * debugger, or hooked with Frida / Xposed.  Debug builds skip every
 * check so local development is unaffected.
 *
 * The expected signing-cert SHA-256 and the expected applicationId
 * are stored XOR-masked so neither appears as a readable literal in
 * the obfuscated DEX.
 */
object IntegrityGuard {

    private const val TAG = "IntegrityGuard"
    private const val MASK = 0x5A

    // SHA-256 of the signing cert (onnow-launcher-debug.keystore), XOR 0x5A.
    private val CERT_MASKED = byteArrayOf(
        0x81.toByte(), 0x16.toByte(), 0xDB.toByte(), 0xFE.toByte(), 0x8F.toByte(), 0x27.toByte(), 0x27.toByte(), 0x75.toByte(),
        0xE4.toByte(), 0x84.toByte(), 0x75.toByte(), 0x61.toByte(), 0x06.toByte(), 0xFA.toByte(), 0xEF.toByte(), 0x7D.toByte(),
        0x3F.toByte(), 0xC3.toByte(), 0x0A.toByte(), 0x7B.toByte(), 0xC7.toByte(), 0x75.toByte(), 0x74.toByte(), 0x9B.toByte(),
        0x02.toByte(), 0xFD.toByte(), 0xD0.toByte(), 0xE7.toByte(), 0x29.toByte(), 0xEB.toByte(), 0x06.toByte(), 0x8B.toByte(),
    )

    // "tv.onnowtv.livetv" XOR 0x5A.
    private val PKG_MASKED = byteArrayOf(
        0x2E.toByte(), 0x2C.toByte(), 0x74.toByte(), 0x35.toByte(), 0x34.toByte(), 0x34.toByte(), 0x35.toByte(), 0x2D.toByte(),
        0x2E.toByte(), 0x2C.toByte(), 0x74.toByte(), 0x36.toByte(), 0x33.toByte(), 0x2C.toByte(), 0x3F.toByte(), 0x2E.toByte(),
        0x2C.toByte(),
    )

    private fun expectedHash(): ByteArray =
        ByteArray(CERT_MASKED.size) { (CERT_MASKED[it].toInt() xor MASK).toByte() }

    private fun expectedPackage(): String {
        val sb = StringBuilder(PKG_MASKED.size)
        for (b in PKG_MASKED) sb.append(((b.toInt() xor MASK) and 0xFF).toChar())
        return sb.toString()
    }

    @Volatile private var compromised = false

    @JvmStatic
    fun runChecks(ctx: Context) {
        if (BuildConfig.DEBUG) return
        if (ctx.packageName != expectedPackage()) exitProcess(0)
        if (!signingCertMatches(ctx)) exitProcess(0)
        if (isDebuggerAttached()) exitProcess(0)
        if (isFridaPresent()) exitProcess(0)
        if (isXposedPresent()) exitProcess(0)
        if (!isOurOwnProcess(ctx)) exitProcess(0)
        startPeriodicChecks(ctx)
    }

    private fun startPeriodicChecks(ctx: Context) {
        if (BuildConfig.DEBUG) return
        Thread({
            val rng = java.util.Random()
            while (!compromised) {
                try {
                    Thread.sleep((4 * 60 + rng.nextInt(8 * 60)) * 1000L)
                    if (isDebuggerAttached() || isFridaPresent() || isXposedPresent() ||
                        !signingCertMatches(ctx) || !isOurOwnProcess(ctx)
                    ) {
                        compromised = true
                        exitProcess(0)
                    }
                } catch (_: InterruptedException) { return@Thread }
                catch (_: Throwable) { /* never crash from the guard */ }
            }
        }, "lt-ig-periodic").apply { isDaemon = true }.start()
    }

    private fun signingCertMatches(ctx: Context): Boolean {
        return try {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val si = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
                if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            } else {
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            if (signatures.isNullOrEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            val expected = expectedHash()
            for (sig in signatures) {
                if (md.digest(sig.toByteArray()).contentEquals(expected)) return true
                md.reset()
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun isDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    private fun isFridaPresent(): Boolean {
        return try {
            for (thr in Thread.getAllStackTraces().keys) {
                val n = thr.name ?: continue
                if (n == "gum-js-loop" || n == "gmain" || n == "linjector" || n.startsWith("gum-")) return true
            }
            java.io.File("/proc/self/maps").inputStream().bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.contains("frida-agent") || line.contains("frida-gadget") || line.contains("libfrida")) return true
                }
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun isXposedPresent(): Boolean = try {
        Class.forName("de.robv.android.xposed.XposedBridge"); true
    } catch (_: Throwable) { false }

    private fun isOurOwnProcess(ctx: Context): Boolean = try {
        ctx.packageManager.getApplicationInfo(ctx.packageName, 0).uid == android.os.Process.myUid()
    } catch (_: Throwable) { true }
}
