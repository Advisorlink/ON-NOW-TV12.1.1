package tv.onnow.launcher.install

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 *   • SELF-UPDATE (the launcher updating itself, v2.12.11):
 *       IN-PLACE `pm install -r <apk>` — NO uninstall.  Keeps all
 *       data/profiles, the package is never absent (box can't drop to
 *       the stock launcher), and because signatures always match
 *       (stable committed keystore) + versionCode always increases
 *       (CI = 1 + run number), `-r` is always accepted.  Then
 *       `am force-stop` + relaunch so the NEW code loads (the on-disk
 *       APK updates instantly but the live process keeps the old code
 *       until restarted — that was the "still shows old version" bug).
 *       As the HOME app, even if this shell dies at force-stop the OS
 *       auto-relaunches HOME → the update self-heals.
 *
 *   • SIDE-APP update (Vesper / Tunes / …):
 *       `pm uninstall` then `pm install` (they aren't the running
 *       process, so uninstalling is safe and clears any signature
 *       drift from a manually side-loaded build).
 *
 *   • Fresh install: `pm install -r -d` with an uninstall+install
 *       fallback.
 *
 * The whole sequence is launched via `nohup setsid … &` in a root
 * shell, so when a force-stop / uninstall SIGKILLs the launcher the
 * install itself keeps going as a detached init child.
 *
 * Falls back gracefully on non-rooted boxes — `isRootAvailable()`
 * is a quick probe that the home-update flow uses to pick between
 * this and the legacy intent path.
 */
object RootApkInstaller {

    private const val TAG = "RootApkInstaller"

    @Volatile private var cachedHasRoot: Boolean? = null

    // v2.12.9 — ONE long-lived root shell for the whole launcher
    // process.  Previously every install spawned a FRESH `su`
    // process, so superuser managers set to "ask every time" threw
    // a grant prompt at the user on EVERY update click.  With a
    // persistent shell the prompt appears at most once per launcher
    // boot; every subsequent install rides the same pipe.  (Same
    // pattern as support/RootInputDispatcher.)
    @Volatile private var shellProcess: Process? = null
    @Volatile private var shellWriter: BufferedWriter? = null
    private val shellLock = Any()
    private val shellIsRoot = AtomicBoolean(false)

    /** Open (or reuse) the persistent root shell.  Returns true when
     *  a live `su` shell running as uid=0 is available. */
    private fun ensureShell(): Boolean {
        synchronized(shellLock) {
            if (shellWriter != null && shellProcess?.isAlive == true) return shellIsRoot.get()
            shellProcess = null
            shellWriter = null
            shellIsRoot.set(false)
            return try {
                val p = ProcessBuilder("su").redirectErrorStream(true).start()
                val w = BufferedWriter(OutputStreamWriter(p.outputStream))
                val ready = CountDownLatch(1)
                Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { line ->
                            if (line.contains("uid=0")) shellIsRoot.set(true)
                            if (line.contains("onnow_root_ready")) ready.countDown()
                            Log.v(TAG, "shell: $line")
                        }
                    } catch (_: Throwable) { /* pipe closed */ }
                }.apply { isDaemon = true }.start()
                w.write("id; echo onnow_root_ready\n")
                w.flush()
                // Generous wait — the superuser manager may be showing
                // its grant dialog right now (first use after boot).
                ready.await(25, TimeUnit.SECONDS)
                if (shellIsRoot.get() && p.isAlive) {
                    shellProcess = p
                    shellWriter = w
                    Log.i(TAG, "persistent root shell opened")
                    true
                } else {
                    try { p.destroy() } catch (_: Throwable) {}
                    false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "failed to open persistent su shell", t)
                false
            }
        }
    }

    /** Write one command line into the persistent root shell,
     *  reopening it once if the pipe has died. */
    private fun shellExec(line: String): Boolean {
        synchronized(shellLock) {
            repeat(2) { attempt ->
                if (ensureShell()) {
                    try {
                        shellWriter!!.write(line)
                        shellWriter!!.write("\n")
                        shellWriter!!.flush()
                        return true
                    } catch (t: Throwable) {
                        Log.w(TAG, "shell write failed (attempt ${attempt + 1})", t)
                        try { shellProcess?.destroy() } catch (_: Throwable) {}
                        shellProcess = null
                        shellWriter = null
                        shellIsRoot.set(false)
                    }
                }
            }
            return false
        }
    }

    /** Root probe backed by the persistent shell — triggers at most
     *  ONE superuser prompt per launcher process. */
    fun isRootAvailable(): Boolean {
        cachedHasRoot?.let { if (it) return true }
        val hasRoot = ensureShell()
        cachedHasRoot = hasRoot
        return hasRoot
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
        // v2.13.23 — UNIFIED, upgrade-in-place install with a RESULT-
        // TEXT check instead of exit-code branching.
        //
        // Root cause of the operator's "sometimes won't update" bug:
        // on most HK1 / toybox firmwares `pm install` PRINTS
        // `Failure [INSTALL_FAILED_…]` but still EXITS 0.  The old
        // `pm install -r || pm install -r -d || …` chain therefore
        // NEVER advanced to its fallback — the very first command
        // "succeeded" (exit 0) while actually having installed
        // nothing, so the box silently stayed on the old build even
        // though the UI said "done".
        //
        // New logic:
        //   1. `pm install -r -d` — IN-PLACE upgrade.  Keeps all app
        //      data, needs NO uninstall.  `-d` means a same/older
        //      versionCode is still accepted, so the update lands even
        //      if CI didn't bump the code.  This is the ONLY step in
        //      the normal case (matching signatures + monotonic code).
        //   2. Only if the output does NOT contain "Success" (i.e. a
        //      real signature-drift / incompatible failure) do we fall
        //      back to a clean uninstall + install.  Safe: the APK was
        //      staged to /data/local/tmp (survives the uninstall) and
        //      this shell is a detached setsid root child (survives the
        //      launcher being SIGKILLed).
        // For a launcher SELF-update we then force-stop the old live
        // process + relaunch so the NEW code actually loads.
        val script = buildString {
            append("(")
            // Stage to /data/local/tmp FIRST so uninstall of the target
            // package (fallback path) can't nuke the APK mid-flight.
            // Abort the whole flow if staging fails — never uninstall
            // without a staged APK to reinstall from.
            append("cp \"$apkPath\" \"$tmpApkPath\" && chmod 644 \"$tmpApkPath\" && ")
            append("test -s \"$tmpApkPath\" && ")
            append("(")
            if (relaunch) append("sleep 1 ; ")
            // 1. In-place upgrade (no uninstall, keeps data).
            append("OUT=\$(pm install -r -d \"$tmpApkPath\" 2>&1) ; ")
            append("echo \"result: \$OUT\" ; ")
            // 2. Fallback ONLY on a genuine failure (no "Success" text).
            append("echo \"\$OUT\" | grep -qi success || ")
            append("{ pm uninstall \"$packageName\" ; sleep 1 ; pm install \"$tmpApkPath\" ; } ; ")
            if (relaunch) {
                append("sleep 1 ; am force-stop \"$packageName\" ; sleep 1 ; ")
                append(relaunchCmd)
            } else {
                append(":")
            }
            append(")")
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
            // v2.12.9 — Ride the persistent root shell instead of
            // spawning a fresh `su` per install, so the superuser
            // manager doesn't prompt on every update click.  The
            // `nohup setsid … &` payload still fully detaches, so
            // the install survives the launcher being SIGKILLed
            // mid-update.
            Log.i(TAG, "launching detached root install for $packageName")
            shellExec(rootCmd)
        } catch (t: Throwable) {
            Log.e(TAG, "root install failed to launch", t)
            false
        }
    }
}
