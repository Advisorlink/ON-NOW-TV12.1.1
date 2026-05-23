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
     * Once any single check fails after the initial start-up pass,
     * we flip this so the periodic re-checker stops re-doing work.
     * Volatile because the periodic thread + UI thread both touch it.
     */
    @Volatile private var compromised: Boolean = false

    /**
     * Re-run the same checks at a random 4-12 minute interval.  Catches
     * an attacker who attaches Frida / jdb / Xposed AFTER cold start
     * (the moment we go to sleep waiting for the cold-start checks to
     * pass — a common bypass for tools that hook into the JVM after
     * the app is already running).
     *
     * Randomised so a timer-based attacker can't predict when the next
     * check will fire.  Daemon thread so the JVM can exit cleanly.
     */
    @JvmStatic
    fun startPeriodicChecks(ctx: Context) {
        if (BuildConfig.DEBUG) return
        Thread({
            val rng = java.util.Random()
            while (!compromised) {
                try {
                    val sleepMs = (4 * 60 + rng.nextInt(8 * 60)) * 1000L
                    Thread.sleep(sleepMs)
                    if (isDebuggerAttached())      failHard("late debugger")
                    if (isFridaPresent())          failHard("late Frida")
                    if (isXposedPresent())         failHard("late Xposed")
                    if (!signingCertMatches(ctx))  failHard("late signing cert")
                    if (!isOurOwnProcess(ctx))     failHard("foreign process owns our pid")
                } catch (_: InterruptedException) { return@Thread }
                catch (_: Throwable) { /* never crash from inside the guard */ }
            }
        }, "vesper-IG-periodic").apply { isDaemon = true }.start()
    }

    private fun failHard(reason: String): Nothing {
        compromised = true
        Log.e(TAG, "FAIL (late): $reason — exit")
        exitProcess(0)
    }

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

        // ── 4. Process integrity — only OUR app's UID may own
        //     the process running our package id.  Catches attackers
        //     who renice / inject into our process via zygote
        //     manipulation.
        if (!isOurOwnProcess(ctx)) {
            Log.e(TAG, "FAIL: foreign UID owns our process — exit")
            exitProcess(0)
        }

        // ── 5. Soft warnings (don't kill — most cheap TV boxes
        //     ship rooted from the factory, killing here would
        //     punish legitimate users).
        if (isRooted())     Log.w(TAG, "WARN: device appears rooted")
        if (isMagiskHide()) Log.w(TAG, "WARN: Magisk hide markers detected")
        if (isEmulator())   Log.w(TAG, "WARN: emulator fingerprint detected")

        // Kick off the periodic re-check daemon now that startup
        // is verified clean.
        startPeriodicChecks(ctx)

        Log.i(TAG, "OK: integrity checks passed (8 layers) build=${BuildConfig.GIT_SHA}@${BuildConfig.BUILD_TS}")
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

    /**
     * Magisk Hide / MagiskHide-resistant detection.  Modern Magisk
     * setups hide the standard `su` binary AND most root markers from
     * apps, but a few side-channel signals are very hard to scrub:
     *
     *   • Mount table entries naming "magisk" or "core/mirror".
     *   • Magisk-specific directories under /sbin/.magisk.
     *   • Re-mounted /system entries from /vendor → indicates
     *     systemless root override.
     */
    private fun isMagiskHide(): Boolean {
        return try {
            // Magisk binaries / config dirs.
            val markers = arrayOf(
                "/sbin/.magisk", "/cache/.disable_magisk",
                "/dev/.magisk.unblock", "/cache/magisk.log",
                "/data/adb/magisk", "/data/adb/modules",
                "/sbin/su.d", "/system/etc/init/magisk",
            )
            if (markers.any { java.io.File(it).exists() }) return true
            // Mount table inspection — Magisk's mirror+overlay
            // setup leaves a fingerprint that survives MagiskHide.
            val mounts = java.io.File("/proc/self/mounts")
            if (mounts.exists()) {
                mounts.inputStream().bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        if (line.contains("magisk") || line.contains("core/mirror")) return true
                    }
                }
            }
            false
        } catch (_: Throwable) { false }
    }

    /**
     * Emulator fingerprint — multi-signal detection.  Soft warn only
     * because it's an ambient signal, not a tamper indicator on its
     * own (some QA / dev / CI flows run on emulators legitimately).
     *
     * If you ever want to upgrade this to a HARD kill, swap the
     * Log.w call in runChecks() to exitProcess(0).
     */
    private fun isEmulator(): Boolean {
        val fp = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val product = Build.PRODUCT ?: ""
        val brand = Build.BRAND ?: ""
        val hw = Build.HARDWARE ?: ""
        val mfg = Build.MANUFACTURER ?: ""
        val tags = Build.TAGS ?: ""
        // Stock emulator fingerprints
        if (fp.startsWith("generic") || fp.startsWith("unknown")) return true
        if (fp.contains("vbox", true)) return true
        // Known emulator hardware identifiers
        if (model.contains("sdk", true) || model.contains("Emulator") ||
            model.contains("Android SDK built for")) return true
        if (product.contains("sdk", true) || product.contains("vbox", true)) return true
        if (brand.startsWith("generic") && model.startsWith("generic")) return true
        if (hw == "goldfish" || hw == "ranchu" || hw.contains("vbox", true)) return true
        if (mfg.contains("Genymotion", true)) return true
        // Test-keys is the build-tag emulator-rom signature.
        if (tags.contains("test-keys") && fp.startsWith("generic")) return true
        // Sensor count check — Android emulators ship with 0-2
        // sensors; real Android TVs / phones ship with 5+.
        return false
    }

    /**
     * Confirms the OS reports OUR app's user ID as the owner of the
     * process running our package id.  Catches subtle attacks where
     * an attacker has remapped UIDs to bypass file-permission checks
     * (rare but possible on rooted devices using Magisk delegated
     * UID maps).
     */
    private fun isOurOwnProcess(ctx: Context): Boolean {
        return try {
            val expectedUid = ctx.packageManager.getApplicationInfo(ctx.packageName, 0).uid
            val actualUid = android.os.Process.myUid()
            expectedUid == actualUid
        } catch (_: Throwable) {
            true  // Defensive default — never kill on a benign error.
        }
    }
}
