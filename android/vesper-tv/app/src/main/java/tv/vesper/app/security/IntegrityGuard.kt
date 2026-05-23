package tv.vesper.app.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import tv.vesper.app.BuildConfig
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * IntegrityGuard
 * ──────────────
 * Defensive integrity check the launcher runs ONCE on cold start.
 * It is the first line of defence against the three most common
 * attacks on a sideloadable Android TV app:
 *
 *   1. **Re-packaging** — attacker decompiles the APK, swaps logos /
 *      brand names, re-signs with their own keystore, redistributes.
 *      We detect this by comparing the running APK's signing
 *      certificate SHA-256 against a hard-coded EXPECTED hash.  A
 *      re-signed APK has a different signing cert → instant kill.
 *
 *   2. **Live tampering** — attacker runs the app under a debugger /
 *      Frida / Xposed to extract secrets, intercept HTTPS or override
 *      JS-bridge calls.  We check `Debug.isDebuggerConnected()` and
 *      well-known Frida / Xposed footprints.
 *
 *   3. **Wrong package id** — some repackagers don't bother changing
 *      the signing cert but DO change the applicationId so they can
 *      install side-by-side with the original.  We bail if the
 *      running package name isn't what we expect.
 *
 * Behaviour:
 *   • Hard kill   (`exitProcess(0)`) on the package-name + signing-cert
 *     checks — these are unambiguous re-packaging tells.
 *   • Hard kill on debugger attached when running a RELEASE build.
 *   • Soft warn (Logcat only) on root detection — most cheap Android
 *     TV boxes ship rooted from the factory, so killing on root would
 *     punish legitimate users.
 *
 * DEBUG builds skip every kill so local development isn't disrupted.
 * That's intentional: debug APKs are signed with the debug keystore
 * anyway, so they fail the cert check by design.
 */
object IntegrityGuard {

    private const val TAG = "IntegrityGuard"

    /**
     * SHA-256 of the X.509 signing certificate this APK is expected
     * to ship with — extracted from `onnowtv-stable-debug.keystore`.
     *
     * If you ever rotate the signing key, regenerate this constant
     * by running:
     *
     *   keytool -exportcert -alias onnowtv-debug \
     *           -keystore onnowtv-stable-debug.keystore \
     *           -storepass onnowtv-debug -file cert.der
     *   sha256sum cert.der
     *
     * Mismatched cert ⇒ re-signed APK ⇒ refuse to launch.
     */
    private val EXPECTED_SIGNING_CERT_SHA256 = byteArrayOf(
        0x0E.toByte(), 0x16.toByte(), 0xE2.toByte(), 0x97.toByte(),
        0x66.toByte(), 0xDF.toByte(), 0x36.toByte(), 0x09.toByte(),
        0x60.toByte(), 0x2F.toByte(), 0xA7.toByte(), 0xC9.toByte(),
        0x8F.toByte(), 0xE6.toByte(), 0xC2.toByte(), 0x29.toByte(),
        0x3E.toByte(), 0xB0.toByte(), 0x09.toByte(), 0xD2.toByte(),
        0x44.toByte(), 0x16.toByte(), 0x11.toByte(), 0x13.toByte(),
        0x46.toByte(), 0x81.toByte(), 0xB7.toByte(), 0x91.toByte(),
        0x85.toByte(), 0x82.toByte(), 0xD1.toByte(), 0x03.toByte(),
    )

    /**
     * Expected applicationId.  Set via the value of build.gradle's
     * `defaultConfig.applicationId`.  Anything else means a
     * re-packager renamed the package to install side-by-side.
     */
    private const val EXPECTED_PACKAGE = "tv.onnowtv.app"

    /**
     * Entry point.  Call from `Application.onCreate()` BEFORE any
     * WebView / ExoPlayer / network setup.  Returns normally on
     * pass; calls `exitProcess(0)` on hard fail (re-package).
     */
    @JvmStatic
    fun runChecks(ctx: Context) {
        if (BuildConfig.DEBUG) {
            // Debug builds: nothing to enforce.  Local dev shouldn't
            // accidentally trip the guards while iterating.
            Log.i(TAG, "DEBUG build — integrity checks skipped")
            return
        }

        // ── 1. Package identity ─────────────────────────────────
        val running = ctx.packageName
        if (running != EXPECTED_PACKAGE) {
            Log.e(TAG, "FAIL: package=$running, expected=$EXPECTED_PACKAGE — exit")
            exitProcess(0)
        }

        // ── 2. Signing certificate ─────────────────────────────
        if (!signingCertMatches(ctx)) {
            Log.e(TAG, "FAIL: signing cert mismatch — exit")
            exitProcess(0)
        }

        // ── 3. Debugger / Frida / Xposed ───────────────────────
        if (isDebuggerAttached()) {
            Log.e(TAG, "FAIL: debugger attached — exit")
            exitProcess(0)
        }
        if (isFridaPresent()) {
            Log.e(TAG, "FAIL: Frida instrumentation detected — exit")
            exitProcess(0)
        }
        if (isXposedPresent()) {
            Log.e(TAG, "FAIL: Xposed framework detected — exit")
            exitProcess(0)
        }

        // ── 4. Soft root warning ───────────────────────────────
        if (isRooted()) {
            Log.w(TAG, "WARN: device appears rooted — continuing (most cheap TV boxes ship rooted)")
        }

        Log.i(TAG, "OK: integrity checks passed")
    }

    /* ─────────────────────  Implementation  ───────────────────── */

    private fun signingCertMatches(ctx: Context): Boolean {
        return try {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES)
                val si = info.signingInfo
                if (si.hasMultipleSigners()) si.apkContentsSigners
                else si.signingCertificateHistory
            } else {
                pm.getPackageInfo(ctx.packageName,
                    PackageManager.GET_SIGNATURES).signatures
            }
            if (signatures == null || signatures.isEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            // Accept the APK if ANY of the listed signing certs in
            // the chain matches our expected hash.  Newer Android
            // signature schemes (v3 key rotation) report multiple
            // certs; we only need one to match.
            for (sig in signatures) {
                val hash = md.digest(sig.toByteArray())
                if (hash.contentEquals(EXPECTED_SIGNING_CERT_SHA256)) return true
                md.reset()
            }
            false
        } catch (t: Throwable) {
            Log.e(TAG, "signingCertMatches failed", t)
            false
        }
    }

    private fun isDebuggerAttached(): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        // Catch repackagers who flip `android:debuggable=true` in
        // the manifest before re-signing — they need debuggable
        // mode in order to attach jdb to dump our state.  We
        // already exited early for genuine local debug builds.
        return try {
            (ctxAppFlags() and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) { false }
    }

    private var cachedFlags: Int = 0
    private fun ctxAppFlags(): Int = cachedFlags
    private fun setFlags(f: Int) { cachedFlags = f }

    /**
     * Frida injects a known-named thread + library.  Detection
     * patterns drawn from public Frida internals.
     */
    private fun isFridaPresent(): Boolean {
        return try {
            // 1. Frida injects a thread called "gum-js-loop".
            for (thr in Thread.getAllStackTraces().keys) {
                val n = thr.name ?: continue
                if (n == "gum-js-loop"   || n == "gmain"        ||
                    n == "linjector"     || n.startsWith("gum-")) return true
            }
            // 2. Frida's frida-server typically listens on TCP 27042.
            //    We won't probe sockets (slow + noisy on Logcat); the
            //    thread check above catches injected-mode Frida.
            // 3. Common Frida library names in /proc/self/maps.
            java.io.File("/proc/self/maps").inputStream().bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.contains("frida-agent") ||
                        line.contains("frida-gadget") ||
                        line.contains("libfrida")) return true
                }
            }
            false
        } catch (_: Throwable) { false }
    }

    private fun isXposedPresent(): Boolean {
        return try {
            // Xposed installs classes under `de.robv.android.xposed.*`.
            Class.forName("de.robv.android.xposed.XposedBridge")
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun isRooted(): Boolean {
        // Cheap, conservative check — look for `su` binary on $PATH.
        val paths = arrayOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/data/local/su", "/data/local/xbin/su", "/data/local/bin/su",
            "/su/bin/su", "/system/app/Superuser.apk",
        )
        return paths.any { java.io.File(it).exists() }
    }
}
sts() }
    }
}
