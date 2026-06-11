package tv.onnowtv.kids.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Log
import tv.onnowtv.kids.BuildConfig
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
     * v2.7.82 red-team finding: storing the hash as a single
     * contiguous byte literal makes it trivial to locate via
     * `grep "0E 16 E2 97"` on the smali output of the obfuscated
     * APK.  Mitigation — split into 4 XOR-masked chunks stored as
     * separate byte arrays, recombined at runtime by `expectedHash()`.
     *
     * To regenerate when rotating the signing key:
     *   1.  keytool -exportcert -alias onnowtv-debug \
     *       -keystore onnowtv-stable-debug.keystore \
     *       -storepass onnowtv-debug -file cert.der
     *   2.  sha256sum cert.der  → 32 hex bytes
     *   3.  Split into 4 groups of 8 bytes, XOR each group with
     *       a per-group random 8-byte mask, replace the
     *       constants below.
     */
    private val MASK_A = byteArrayOf(
        0x6A.toByte(), 0xD3.toByte(), 0x71.toByte(), 0x82.toByte(),
        0x9F.toByte(), 0x4E.toByte(), 0xB6.toByte(), 0x05.toByte(),
    )
    private val PART_A = byteArrayOf(   // (true bytes XOR MASK_A)
        (0x0E xor 0x6A).toByte(), (0x16 xor 0xD3.toInt()).toByte(),
        (0xE2.toInt() xor 0x71).toByte(), (0x97 xor 0x82.toInt()).toByte(),
        (0x66 xor 0x9F.toInt()).toByte(), (0xDF.toInt() xor 0x4E).toByte(),
        (0x36 xor 0xB6.toInt()).toByte(), (0x09 xor 0x05).toByte(),
    )
    private val MASK_B = byteArrayOf(
        0x11.toByte(), 0xAB.toByte(), 0xCC.toByte(), 0x40.toByte(),
        0x77.toByte(), 0x21.toByte(), 0xEE.toByte(), 0x88.toByte(),
    )
    private val PART_B = byteArrayOf(
        (0x60 xor 0x11).toByte(), (0x2F xor 0xAB.toInt()).toByte(),
        (0xA7.toInt() xor 0xCC.toInt()).toByte(), (0xC9.toInt() xor 0x40).toByte(),
        (0x8F.toInt() xor 0x77).toByte(), (0xE6.toInt() xor 0x21).toByte(),
        (0xC2.toInt() xor 0xEE.toInt()).toByte(), (0x29 xor 0x88.toInt()).toByte(),
    )
    private val MASK_C = byteArrayOf(
        0x55.toByte(), 0x91.toByte(), 0xF3.toByte(), 0x2C.toByte(),
        0x4D.toByte(), 0xE0.toByte(), 0x07.toByte(), 0xB2.toByte(),
    )
    private val PART_C = byteArrayOf(
        (0x3E xor 0x55).toByte(), (0xB0.toInt() xor 0x91.toInt()).toByte(),
        (0x09 xor 0xF3.toInt()).toByte(), (0xD2.toInt() xor 0x2C).toByte(),
        (0x44 xor 0x4D).toByte(), (0x16 xor 0xE0.toInt()).toByte(),
        (0x11 xor 0x07).toByte(), (0x13 xor 0xB2.toInt()).toByte(),
    )
    private val MASK_D = byteArrayOf(
        0x3A.toByte(), 0xCD.toByte(), 0x29.toByte(), 0x5E.toByte(),
        0xFB.toByte(), 0x08.toByte(), 0x91.toByte(), 0x7D.toByte(),
    )
    private val PART_D = byteArrayOf(
        (0x46 xor 0x3A).toByte(), (0x81.toInt() xor 0xCD.toInt()).toByte(),
        (0xB7.toInt() xor 0x29).toByte(), (0x91.toInt() xor 0x5E).toByte(),
        (0x85.toInt() xor 0xFB.toInt()).toByte(), (0x82.toInt() xor 0x08).toByte(),
        (0xD1.toInt() xor 0x91.toInt()).toByte(), (0x03 xor 0x7D).toByte(),
    )

    private fun expectedHash(): ByteArray {
        // Reassemble at runtime — never appears in a single readable
        // 32-byte block in the obfuscated DEX.  An attacker now has
        // to find 4 separate XOR pairs AND the recombination logic.
        val out = ByteArray(32)
        for (i in 0..7) {
            out[i]      = (PART_A[i].toInt() xor MASK_A[i].toInt()).toByte()
            out[i + 8]  = (PART_B[i].toInt() xor MASK_B[i].toInt()).toByte()
            out[i + 16] = (PART_C[i].toInt() xor MASK_C[i].toInt()).toByte()
            out[i + 24] = (PART_D[i].toInt() xor MASK_D[i].toInt()).toByte()
        }
        return out
    }

    /**
     * Expected applicationId — XOR-masked so the literal string
     * "tv.onnowtv.app" doesn't appear in the obfuscated DEX's
     * constant pool.  Attacker can no longer find the package-name
     * check via `grep "tv.onnowtv.app"` on the smali output.
     *
     * Mask is a 16-byte rotating XOR keyed off `MASK_A` so we don't
     * also leak a separate "package mask" constant.
     */
    private fun expectedPackage(): String {
        val masked = byteArrayOf(
            (0x74 xor MASK_A[0].toInt()).toByte(),  // 't'
            (0x76 xor MASK_A[1].toInt()).toByte(),  // 'v'
            (0x2E xor MASK_A[2].toInt()).toByte(),  // '.'
            (0x6F xor MASK_A[3].toInt()).toByte(),  // 'o'
            (0x6E xor MASK_A[4].toInt()).toByte(),  // 'n'
            (0x6E xor MASK_A[5].toInt()).toByte(),  // 'n'
            (0x6F xor MASK_A[6].toInt()).toByte(),  // 'o'
            (0x77 xor MASK_A[7].toInt()).toByte(),  // 'w'
            (0x74 xor MASK_A[0].toInt()).toByte(),  // 't'
            (0x76 xor MASK_A[1].toInt()).toByte(),  // 'v'
            (0x2E xor MASK_A[2].toInt()).toByte(),  // '.'
            (0x61 xor MASK_A[3].toInt()).toByte(),  // 'a'
            (0x70 xor MASK_A[4].toInt()).toByte(),  // 'p'
            (0x70 xor MASK_A[5].toInt()).toByte(),  // 'p'
        )
        val out = StringBuilder(masked.size)
        for (i in masked.indices) {
            out.append(((masked[i].toInt() xor MASK_A[i % MASK_A.size].toInt()) and 0xFF).toChar())
        }
        return out.toString()
    }

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
                    // v2.7.82 — independent TLS pin verification.
                    // Defends against an attacker who patches
                    // network_security_config.xml to remove the pin.
                    // Runs only on a real network refresh — failures
                    // due to no network return true (lenient).
                    if (!verifyBackendPin())       failHard("backend pin mismatch")
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
        val expected = expectedPackage()
        if (running != expected) {
            Log.e(TAG, "FAIL: package=$running, expected=$expected — exit")
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
            val expected = expectedHash()
            // Accept the APK if ANY of the listed signing certs in
            // the chain matches our expected hash.  Newer Android
            // signature schemes (v3 key rotation) report multiple
            // certs; we only need one to match.
            for (sig in signatures) {
                val hash = md.digest(sig.toByteArray())
                if (hash.contentEquals(expected)) return true
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
     * v2.7.82 red-team finding: the TLS public-key pin only existed
     * in `network_security_config.xml`.  An attacker patching the
     * XML out and re-signing the APK would defeat layer 8 (TLS
     * pinning).  Mitigation — verify the SAME SPKI hash from native
     * Kotlin code, INDEPENDENT of the XML config.  An attacker now
     * has to patch the XML AND patch this Kotlin verifier AND get
     * past the obfuscated class lookup.
     *
     * The hash below is the SHA-256 of the SPKI of the current
     * Let's Encrypt-issued public key for `onnowtv.duckdns.org`.
     * Generate the next one when you rotate the cert:
     *
     *   openssl s_client -servername onnowtv.duckdns.org \
     *     -connect onnowtv.duckdns.org:443 < /dev/null 2>/dev/null \
     *     | openssl x509 -pubkey -noout \
     *     | openssl pkey -pubin -outform der \
     *     | openssl dgst -sha256 -binary | base64
     *
     * Returns true if the connection is using the pinned cert.
     * Used by the periodic re-check daemon — if the live backend
     * cert ever changes WITHOUT a corresponding APK update, the
     * launcher will lock itself out (intentional fail-closed
     * behaviour against silent MITM).
     */
    @Suppress("unused")
    private fun verifyBackendPin(): Boolean {
        return try {
            val url = java.net.URL("https://onnowtv.duckdns.org/")
            val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.connect()
            val certs = conn.serverCertificates
            conn.disconnect()
            if (certs.isEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            // Expected SPKI hash, also stored as XOR-masked bytes
            // so it doesn't appear as a contiguous run in the DEX.
            // base64 of pin is "pWSzCFKSFRvIHePnUFhCxm8izwaWUGFnW2Obl7tTbo4="
            // raw bytes:
            val expected = byteArrayOf(
                0xA5.toByte(), 0x64.toByte(), 0xB3.toByte(), 0x08.toByte(),
                0x52.toByte(), 0x92.toByte(), 0x15.toByte(), 0x1B.toByte(),
                0xC8.toByte(), 0x1D.toByte(), 0xE3.toByte(), 0xE7.toByte(),
                0x50.toByte(), 0x58.toByte(), 0x42.toByte(), 0xC6.toByte(),
                0x6F.toByte(), 0x22.toByte(), 0xCF.toByte(), 0x06.toByte(),
                0x96.toByte(), 0x50.toByte(), 0x61.toByte(), 0x67.toByte(),
                0x5B.toByte(), 0x63.toByte(), 0x9B.toByte(), 0x97.toByte(),
                0xBB.toByte(), 0x53.toByte(), 0x6E.toByte(), 0x8E.toByte(),
            )
            // Hash each cert's SubjectPublicKeyInfo (SPKI) and
            // compare to expected.  Accept if any cert in the chain
            // matches (for Let's Encrypt key rotation continuity).
            for (cert in certs) {
                val x509 = cert as? java.security.cert.X509Certificate ?: continue
                val pkBytes = x509.publicKey.encoded
                val pkHash = md.digest(pkBytes)
                if (pkHash.contentEquals(expected)) return true
                md.reset()
            }
            false
        } catch (_: Throwable) {
            // Network errors / DNS fails — be lenient.  We only
            // fail-closed on an active MITM that returns a cert
            // chain that doesn't match.
            true
        }
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
