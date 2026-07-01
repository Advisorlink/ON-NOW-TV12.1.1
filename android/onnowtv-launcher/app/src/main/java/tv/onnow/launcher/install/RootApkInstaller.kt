package tv.onnow.launcher.install

import android.content.Context
import android.util.Log
import java.io.File

/**
 * v2.10.93 — Root-mode APK installer.
 *
 * Background
 * ──────────
 * The intent-based ApkInstaller works on stock Android, but the
 * customer's TV boxes are rooted — and the standard install flow
 * keeps falling over with the opaque "Application not installed"
 * error.  Root causes (no pun intended):
 *
 *  (a) Signature mismatch between the installed copy and the new
 *      APK.  Happens whenever the GitHub Actions build accidentally
 *      uses a different keystore than the previously-shipped APK
 *      (e.g. a manually side-loaded debug build is now being
 *      replaced by a CI-signed release build).
 *
 *  (b) versionCode regressions or replays.  When the build pipeline
 *      hasn't bumped versionCode, some Android firmwares refuse the
 *      "update" outright.
 *
 *  (c) Generic package-installer race when re-installing the
 *      LAUNCHER itself (the running launcher gets SIGKILLed
 *      mid-install and Android sometimes leaves the package half-
 *      installed).
 *
 * Since the boxes are rooted, we can sidestep all of this:
 *
 *   1.  Try `pm install -r -d <apk>` (replace, allow downgrade) —
 *       handles case (b) and the common case where the signature
 *       matches.
 *   2.  If that fails, `pm uninstall <pkg>` then `pm install
 *       <apk>` — handles (a) by wiping the old package, signature
 *       and all, before laying down the new one.
 *   3.  After the install, `am start -n <pkg>/<MainActivity>` to
 *       wake the launcher back up (Android usually does this for
 *       us since we're the default home, but explicit is safer).
 *
 * The whole sequence is launched via `nohup … &` in a root shell,
 * so when `pm install` SIGKILLs the running launcher mid-update
 * (case c) the install itself keeps going as a detached init child.
 *
 * Falls back gracefully on non-rooted boxes — `isRootAvailable()`
 * is a quick probe that the home-update flow uses to pick between
 * this and the legacy intent path.
 */
object RootApkInstaller {

    private const val TAG = "RootApkInstaller"

    @Volatile private var cachedHasRoot: Boolean? = null

    /** Cheap one-time probe: tries `su -c id` and checks for uid=0. */
    fun isRootAvailable(): Boolean {
        cachedHasRoot?.let { return it }
        return try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            val hasRoot = out.contains("uid=0")
            cachedHasRoot = hasRoot
            hasRoot
        } catch (t: Throwable) {
            cachedHasRoot = false
            false
        }
    }

    /**
     * Install (or update) [apk] via a detached root shell.  Handles
     * signature conflicts + downgrades + replaces automatically.
     * Returns `true` if the root install command was launched
     * successfully (does NOT wait for the install itself to finish
     * — that runs detached, surviving us being killed).  Returns
     * `false` if root isn't available or the shell launch failed,
     * in which case the caller should fall back to the intent flow.
     *
     * @param apk         APK file already downloaded to local storage.
     * @param packageName Target package id (used by the uninstall
     *                    fallback and the post-install `am start`).
     * @param relaunch    Whether to fire `am start` to wake the
     *                    launcher after install.  Should be `true`
     *                    when updating the LAUNCHER ITSELF (so it
     *                    comes back up on its own); `false` for
     *                    side-app updates (where we don't want to
     *                    abruptly switch to the side app).
     * @param forceCleanInstall  v2.12.2 — When `true`, UNCONDITIONALLY
     *                    `pm uninstall` before `pm install`.  This is
     *                    required for updates because on some rooted
     *                    firmwares `pm install -r -d` returns Success
     *                    but silently no-ops when the versionCode is
     *                    the same as the installed one (the operator's
     *                    exact repro: "installer says installed, but
     *                    nothing changed").  A clean uninstall+install
     *                    guarantees the new APK actually lands.  Set
     *                    this to `true` whenever the target package is
     *                    already installed; leave it `false` on fresh
     *                    installs so `pm uninstall` doesn't error
     *                    on a non-existent package.
     */
    fun install(
        ctx: Context,
        apk: File,
        packageName: String,
        relaunch: Boolean = true,
        forceCleanInstall: Boolean = false,
    ): Boolean {
        if (!isRootAvailable()) {
            Log.i(TAG, "no root — caller should use intent-based install")
            return false
        }
        if (!apk.exists() || !apk.canRead()) {
            Log.w(TAG, "apk does not exist or isn't readable: ${apk.absolutePath}")
            return false
        }
        // Make the APK world-readable so `pm install` (running as
        // root in a different process) can open it after we die.
        try { apk.setReadable(true, false) } catch (_: Throwable) {}

        // Compose the one-liner.  Single-quoted as the body of
        // `nohup sh -c '…' &` so it survives our process death.
        // Quoting strategy:
        //   • Use double-quotes around paths in case any future
        //     download path contains a space.
        //   • Use $$ as the inner-shell PID for the log filename
        //     so concurrent installs don't clobber each other.
        val apkPath = apk.absolutePath
        val logPath = "/data/local/tmp/onnow_install_\$\$.log"
        val mainActivity = ".MainActivity"   // launcher activity
        val relaunchCmd = if (relaunch)
            "am start -n \"$packageName/$mainActivity\""
        else
            ":"
        val script = buildString {
            append("(")
            if (forceCleanInstall) {
                // v2.12.2 — Update path.  Nuke the old package first so
                // Android CANNOT no-op the install (see kdoc above for
                // why `pm install -r -d` alone isn't enough).  We
                // ignore the uninstall exit code because on some
                // firmwares `pm uninstall` returns non-zero even on
                // success (e.g. when the package has no data dir).
                append("pm uninstall \"$packageName\" ; ")
                append("pm install \"$apkPath\"")
            } else {
                // Fresh install path.  Try -r -d first for cases where
                // the caller was wrong about the "not installed" state
                // (race with another install), then fall back to a
                // clean uninstall+install if that fails.
                append("pm install -r -d \"$apkPath\"")
                append(" || (pm uninstall \"$packageName\" && pm install \"$apkPath\")")
            }
            // Re-launch the launcher (or no-op for side-app updates).
            append(" ; $relaunchCmd")
            // Clean up the cached APK so we don't fill the box's
            // limited internal storage over multiple updates.
            append(" ; rm -f \"$apkPath\"")
            append(") > $logPath 2>&1")
        }
        val rootCmd = "nohup sh -c '$script' >/dev/null 2>&1 &"

        return try {
            Log.i(TAG, "launching detached root install for $packageName")
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            p.outputStream.bufferedWriter().use { w ->
                w.write(rootCmd)
                w.newLine()
                w.write("exit\n")
                w.flush()
            }
            // Wait briefly for the `nohup … &` to detach, then return.
            // Don't waitFor() the whole process — pm install can take
            // 5-30s and the system will SIGKILL us before then.
            try { p.waitFor() } catch (_: InterruptedException) {}
            true
        } catch (t: Throwable) {
            Log.e(TAG, "root install failed to launch", t)
            false
        }
    }
}
