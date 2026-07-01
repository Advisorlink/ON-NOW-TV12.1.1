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
        // v2.12.7 — CRITICAL FIX for "launcher disappears after
        // update" bug.  The downloaded APK lives in the launcher's
        // own cache dir (`/data/data/tv.onnow.launcher/cache/
        // downloads/`).  When `pm uninstall tv.onnow.launcher`
        // runs, Android wipes THE ENTIRE
        // `/data/data/tv.onnow.launcher/` tree — including our
        // cached APK.  The subsequent `pm install "$apkPath"` then
        // fails with "file not found" and the device is left with
        // no launcher at all.
        //
        // Fix: first `cp` the APK to `/data/local/tmp/` (owned by
        // shell UID 2000, NOT tied to any app's data dir → survives
        // uninstall of any package).  All pm-install commands
        // reference the tmp copy; the original cache file can be
        // wiped by pm-uninstall and we don't care.  We `rm -f` the
        // tmp copy at the end so we don't accumulate stale APKs.
        //
        // Bonus: /data/local/tmp/ is readable by the "install"
        // user Android uses to open the APK during `pm install`,
        // so we don't need to worry about SELinux label mismatch
        // on the cache-dir path (which sometimes causes install
        // permission errors on HK1 firmwares).
        val tmpApkPath = "/data/local/tmp/onnow_install_\$\$.apk"
        val logPath = "/data/local/tmp/onnow_install_\$\$.log"
        val mainActivity = ".MainActivity"   // launcher activity
        val relaunchCmd = if (relaunch)
            "am start -n \"$packageName/$mainActivity\""
        else
            ":"
        val script = buildString {
            append("(")
            // v2.12.7 — Stage the APK to /data/local/tmp/ FIRST so
            // uninstall of the target package can't nuke it.  If
            // the copy fails (disk full, permission denied), ABORT
            // the whole flow BEFORE `pm uninstall` runs — otherwise
            // we'd end up with an uninstalled launcher and no APK
            // to reinstall from.
            append("cp \"$apkPath\" \"$tmpApkPath\" && chmod 644 \"$tmpApkPath\" && ")
            append("test -s \"$tmpApkPath\" && ")
            append("(")
            if (forceCleanInstall) {
                // v2.12.2 — Update path.  Nuke the old package first so
                // Android CANNOT no-op the install (see kdoc above for
                // why `pm install -r -d` alone isn't enough).  We
                // ignore the uninstall exit code because on some
                // firmwares `pm uninstall` returns non-zero even on
                // success (e.g. when the package has no data dir).
                append("pm uninstall \"$packageName\" ; ")
                // v2.12.7 — 1-second breather so PackageManager
                // fully commits the uninstall before we try to
                // install.  Without this, some HK1 firmwares race
                // the two operations and the install returns
                // INSTALL_FAILED_ALREADY_EXISTS.
                append("sleep 1 ; ")
                append("pm install \"$tmpApkPath\"")
            } else {
                // Fresh install path.  Try -r -d first for cases where
                // the caller was wrong about the "not installed" state
                // (race with another install), then fall back to a
                // clean uninstall+install if that fails.
                append("pm install -r -d \"$tmpApkPath\"")
                append(" || (pm uninstall \"$packageName\" && sleep 1 && pm install \"$tmpApkPath\")")
            }
            // Re-launch the launcher (or no-op for side-app updates).
            append(" ; $relaunchCmd")
            // Close the inner group.
            append(")")
            // Clean up BOTH the tmp copy AND the original cache
            // file (if the original still exists — for non-launcher
            // updates the cache-dir survives, and for launcher
            // updates it was already nuked by pm-uninstall).
            append(" ; rm -f \"$tmpApkPath\" \"$apkPath\"")
            append(") > $logPath 2>&1")
        }
        // v2.12.7 — `setsid` in addition to `nohup` for belt-and-
        // braces detachment.  `setsid` creates a brand-new session
        // + process group so the child is fully divorced from the
        // launcher's process tree.  When Android sends SIGKILL to
        // every process in the launcher's UID during uninstall,
        // the setsid'd shell is in a DIFFERENT UID (root, via su)
        // AND a different session — untouchable.
        val rootCmd = "nohup setsid sh -c '$script' </dev/null >/dev/null 2>&1 &"

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
