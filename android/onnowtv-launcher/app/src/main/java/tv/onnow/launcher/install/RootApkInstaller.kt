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
        //   • NEVER embed literal single quotes inside the script —
        //     the outer wrapper is `sh -c '…'`, so any inner `'`
        //     would terminate the wrapper.  Use "…" for sed exprs.
        //   • Tag the staged APK with `pid_epochms` to avoid the
        //     v2.13-and-earlier `$$` collision (persistent su-shell
        //     PID was constant across every install).
        val apkPath = apk.absolutePath
        // v2.12.7 — Stage the APK to /data/local/tmp/ before any pm-
        // uninstall step.  The download originally lives in the
        // launcher's own cache dir; `pm uninstall tv.onnow.launcher`
        // wipes /data/data/tv.onnow.launcher/ including the APK, so
        // the subsequent reinstall would fail with "file not found"
        // and leave the device with no launcher.  /data/local/tmp/ is
        // owned by shell UID 2000, not tied to any app's data dir,
        // so it survives uninstall of any package.
        //
        // v2.14.0 — Use a launcher-PID + wall-clock tag so concurrent
        // installs never collide on the same tmp path.  (Previously
        // `$$` expanded to the persistent-su-shell's PID, which is
        // constant across every install → clobber risk.)
        val stageTag = "${android.os.Process.myPid()}_${System.currentTimeMillis()}"
        val tmpApkPath = "/data/local/tmp/onnow_install_$stageTag.apk"
        val logPath = "/data/local/tmp/onnow_install.log"
        val mainActivity = ".MainActivity"   // launcher activity

        // v2.14.0 — BULLETPROOF UPDATE FLOW.
        //
        // Every previous "grep for Success" gate was unreliable on
        // HK1 / RK / Amlogic Android 9 firmwares, where `pm install`
        // routinely prints `Success` to stdout AND exits 0 while
        // silently NO-OP'ing (kernel can't atomically replace a
        // package whose own process is holding open files under its
        // install path — the swap is dropped, not queued).
        //
        // The only source of truth Android itself trusts is the
        // versionCode reported by `dumpsys package`.  So we:
        //
        //   1. Snapshot `versionCode` BEFORE the install.
        //   2. Run `pm install -r -d` (in-place upgrade, keeps data).
        //   3. Poll `dumpsys package … versionCode=` for up to ~15 s.
        //      As soon as it CHANGES we know the swap really landed.
        //   4. If it never changes → REAL fallback: `pm uninstall -k`
        //      (keeps user data) then `pm install -r`, and re-verify.
        //      (`-k` preserves /data/data/… so Vesper/Tunes profiles
        //      survive; the ~2 s launcher gap is fine — HOME auto-
        //      relaunches after the fresh install lands.)
        //   5. Only after verification do we `am start` the new code.
        //      No `force-stop` — a fresh `am start` with CLEAR_TASK
        //      loads the new APK cleanly; force-stopping mid-swap
        //      was itself causing half-installed states on slow
        //      eMMC boxes.
        //
        // Everything logs to /data/local/tmp/onnow_install.log so a
        // customer report can be `adb pull`'d without extra tooling.
        val script = buildString {
            append("(")
            append("set +e ; ")
            append("echo \"=== onnow install $stageTag @ \$(date) ===\" ; ")
            append("echo \"target=$packageName apk=$apkPath\" ; ")
            // ── Stage APK to a location that survives target uninstall.
            append("cp \"$apkPath\" \"$tmpApkPath\" || { echo STAGE_COPY_FAIL ; exit 1 ; } ; ")
            append("chmod 644 \"$tmpApkPath\" ; ")
            append("test -s \"$tmpApkPath\" || { echo STAGE_EMPTY ; exit 1 ; } ; ")

            // ── Helper: portable versionCode read (toybox-safe sed).
            //     NOTE: sed uses DOUBLE quotes because the whole script
            //     is wrapped in `sh -c '…'` — nested single quotes
            //     would terminate the outer wrapper.  No `$` inside
            //     so double quotes are safe (nothing to expand).
            append("get_vc() { dumpsys package \"\$1\" 2>/dev/null ")
            append("| sed -n \"s/.*versionCode=\\([0-9][0-9]*\\).*/\\1/p\" | head -1 ; } ; ")

            // ── Snapshot BEFORE.
            append("BEFORE=\$(get_vc \"$packageName\") ; ")
            append("echo \"before_vc=[\$BEFORE]\" ; ")

            if (relaunch) append("sleep 1 ; ")

            // ── Attempt 1: in-place upgrade (keeps data).
            append("OUT1=\$(pm install -r -d \"$tmpApkPath\" 2>&1) ; ")
            append("echo \"attempt1: \$OUT1\" ; ")

            // ── Verification poll: wait up to ~15 s for vc to change.
            append("AFTER=\"\$BEFORE\" ; ")
            append("for i in 1 2 3 4 5 6 7 8 9 10 ; do ")
            append("AFTER=\$(get_vc \"$packageName\") ; ")
            append("[ -n \"\$AFTER\" ] && [ \"\$AFTER\" != \"\$BEFORE\" ] && break ; ")
            append("sleep 1 ; ")
            append("done ; ")
            append("echo \"after_attempt1_vc=[\$AFTER]\" ; ")

            // ── Attempt 2: only if vc DID NOT change → real fallback.
            //     `pm uninstall -k` keeps /data/data so profiles survive.
            //     `pm install -r` for the re-install (no -d needed on
            //     a fresh install into an empty slot).
            append("if [ -z \"\$AFTER\" ] || [ \"\$AFTER\" = \"\$BEFORE\" ] ; then ")
            append("echo \"attempt1 no-op, running fallback\" ; ")
            append("OUT_U1=\$(pm uninstall -k \"$packageName\" 2>&1) ; ")
            append("echo \"uninstall: \$OUT_U1\" ; ")
            append("sleep 2 ; ")
            append("OUT2=\$(pm install -r \"$tmpApkPath\" 2>&1) ; ")
            append("echo \"attempt2: \$OUT2\" ; ")
            append("for i in 1 2 3 4 5 6 7 8 9 10 ; do ")
            append("AFTER=\$(get_vc \"$packageName\") ; ")
            append("[ -n \"\$AFTER\" ] && [ \"\$AFTER\" != \"\$BEFORE\" ] && break ; ")
            append("sleep 1 ; ")
            append("done ; ")
            append("echo \"after_attempt2_vc=[\$AFTER]\" ; ")
            append("fi ; ")

            // ── Attempt 3: final safety net for the rare signature-
            //     mismatch case (attempts 1 + 2 both no-op because
            //     the new APK is signed with a different key).  This
            //     wipes user data — accepted trade-off vs. a bricked
            //     update in the field.
            append("if [ -z \"\$AFTER\" ] || [ \"\$AFTER\" = \"\$BEFORE\" ] ; then ")
            append("echo \"attempt2 no-op, running signature-mismatch recovery\" ; ")
            append("OUT_U2=\$(pm uninstall \"$packageName\" 2>&1) ; ")
            append("echo \"uninstall2: \$OUT_U2\" ; ")
            append("sleep 2 ; ")
            append("OUT3=\$(pm install \"$tmpApkPath\" 2>&1) ; ")
            append("echo \"attempt3: \$OUT3\" ; ")
            append("AFTER=\$(get_vc \"$packageName\") ; ")
            append("echo \"after_attempt3_vc=[\$AFTER]\" ; ")
            append("fi ; ")

            // ── Post-install: relaunch the launcher (self-update case)
            //     OR do nothing (side-app case — don't yank the user
            //     into an app they weren't looking at).
            if (relaunch) {
                append("sleep 2 ; ")
                append("OUT_R=\$(am start -n \"$packageName/$mainActivity\" 2>&1) ; ")
                append("echo \"relaunch: \$OUT_R\" ; ")
            }

            // ── Diagnostic tail: dump the last PackageManager errors
            //     from logcat so a customer report is self-contained.
            append("logcat -d -t 200 PackageManager:W PackageInstaller:W *:S 2>/dev/null | tail -50 ; ")

            append("echo \"=== done vc=[\$AFTER] ===\" ; ")
            // Cleanup — leave the log file, delete the staged APKs.
            append("rm -f \"$tmpApkPath\" \"$apkPath\" ")
            append(") >> $logPath 2>&1")
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
