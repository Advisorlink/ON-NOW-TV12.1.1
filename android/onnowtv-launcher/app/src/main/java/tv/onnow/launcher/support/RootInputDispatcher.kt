package tv.onnow.launcher.support

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * v2.10.89 — Persistent-shell remote-input dispatcher.
 *
 * Why this exists
 * ---------------
 * The previous implementation called `Runtime.exec("su", "-c", cmd)`
 * for EVERY input event.  On Magisk/SuperSU boxes that meant a fresh
 * superuser permission prompt could flash up for each key press, and
 * the dispatched process could exit before the `input` command had
 * actually landed.  The operator's screen showed "connected" but the
 * customer's TV wasn't responding to the D-pad.
 *
 * New design: open ONE long-lived `su` shell at the start of the
 * support session.  Inputs are written to its stdin as plain shell
 * lines.  The customer sees the Magisk prompt exactly once (if at
 * all), and every subsequent command lands instantly via the same
 * pipe.  When the session ends (Activity.onDestroy) the shell is
 * closed cleanly.
 *
 * Supported actions
 * -----------------
 *  • tap   {x:0..1, y:0..1}         — normalised to screen size
 *  • swipe {x1,y1,x2,y2,ms}         — normalised
 *  • key   {key:"DPAD_UP"|"BACK"|…} — Android keycode names
 *  • text  {chars:"foo bar"}        — types into focused field
 *
 * Failures are silent (logged to logcat).
 */
object RootInputDispatcher {
    private const val TAG = "RootInput"

    private val KEY_ALIAS = mapOf(
        "DPAD_UP" to "DPAD_UP",
        "DPAD_DOWN" to "DPAD_DOWN",
        "DPAD_LEFT" to "DPAD_LEFT",
        "DPAD_RIGHT" to "DPAD_RIGHT",
        "DPAD_CENTER" to "DPAD_CENTER",
        "OK" to "DPAD_CENTER",
        "BACK" to "BACK",
        "HOME" to "HOME",
        "RECENTS" to "APP_SWITCH",
        "MENU" to "MENU",
        "VOL_UP" to "VOLUME_UP",
        "VOL_DOWN" to "VOLUME_DOWN",
        "POWER" to "POWER",
        "DEL" to "DEL",
        "ENTER" to "ENTER",
    )

    @Volatile private var shellProcess: Process? = null
    @Volatile private var shellWriter: BufferedWriter? = null
    private val shellLock = Any()

    /** Open a persistent root shell.  Idempotent — calling again
     *  when one is already running is a no-op.  Returns true if a
     *  usable shell is available afterwards. */
    fun ensureShell(): Boolean {
        synchronized(shellLock) {
            if (shellWriter != null && shellProcess?.isAlive == true) return true
            // Try `su` first (rooted box → 1 Magisk prompt, then
            // unlimited commands).  Fallback to plain `sh` for
            // firmwares that grant shell-as-system to apps anyway.
            val cmds = arrayOf(arrayOf("su"), arrayOf("sh"))
            for (cmd in cmds) {
                try {
                    val p = ProcessBuilder(*cmd)
                        .redirectErrorStream(true)
                        .start()
                    val w = BufferedWriter(OutputStreamWriter(p.outputStream))
                    // Drain stdout in the background so a chatty
                    // `input` (or a `su` welcome banner) doesn't
                    // block the pipe.
                    Thread {
                        try {
                            p.inputStream.bufferedReader().use { r ->
                                while (true) {
                                    val line = r.readLine() ?: break
                                    Log.v(TAG, "shell: $line")
                                }
                            }
                        } catch (_: Throwable) { /* */ }
                    }.also { it.isDaemon = true }.start()
                    // Sanity-check the shell is alive.
                    w.write("echo onnow_shell_ready\n")
                    w.flush()
                    if (!p.isAlive) {
                        try { p.destroy() } catch (_: Throwable) {}
                        continue
                    }
                    shellProcess = p
                    shellWriter = w
                    Log.i(TAG, "persistent shell opened: ${cmd.joinToString(" ")}")
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "failed to open shell ${cmd.joinToString(" ")}", t)
                }
            }
            return false
        }
    }

    fun handle(ctx: Context, msg: JSONObject) {
        val action = msg.optString("action").lowercase()
        if (!ensureShell()) {
            Log.w(TAG, "no shell available; dropping action=$action")
            return
        }
        try {
            when (action) {
                "tap" -> {
                    val (sw, sh) = screenSize(ctx)
                    val x = (msg.optDouble("x") * sw).toInt().coerceIn(0, sw - 1)
                    val y = (msg.optDouble("y") * sh).toInt().coerceIn(0, sh - 1)
                    writeShellLine("input tap $x $y")
                }
                "swipe" -> {
                    val (sw, sh) = screenSize(ctx)
                    val x1 = (msg.optDouble("x1") * sw).toInt().coerceIn(0, sw - 1)
                    val y1 = (msg.optDouble("y1") * sh).toInt().coerceIn(0, sh - 1)
                    val x2 = (msg.optDouble("x2") * sw).toInt().coerceIn(0, sw - 1)
                    val y2 = (msg.optDouble("y2") * sh).toInt().coerceIn(0, sh - 1)
                    val ms = msg.optInt("ms", 250)
                    writeShellLine("input swipe $x1 $y1 $x2 $y2 $ms")
                }
                "key" -> {
                    val keyName = msg.optString("key")
                    val mapped = KEY_ALIAS[keyName] ?: keyName
                    writeShellLine("input keyevent KEYCODE_$mapped")
                }
                "text" -> {
                    val raw = msg.optString("chars")
                    if (raw.isEmpty()) return
                    val escaped = raw
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace(" ", "%s")
                    writeShellLine("input text \"$escaped\"")
                }
                else -> Log.w(TAG, "unknown action: $action")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "input dispatch failed for action=$action", t)
        }
    }

    /** Close the persistent shell.  Call from Activity.onDestroy. */
    fun shutdown() {
        synchronized(shellLock) {
            try { shellWriter?.write("exit\n"); shellWriter?.flush() } catch (_: Throwable) {}
            try { shellWriter?.close() } catch (_: Throwable) {}
            try { shellProcess?.destroy() } catch (_: Throwable) {}
            shellWriter = null
            shellProcess = null
        }
    }

    /** Write one shell command line + flush.  If the pipe is broken
     *  (e.g. su daemon died) reopen and retry once. */
    private fun writeShellLine(line: String) {
        synchronized(shellLock) {
            val w = shellWriter ?: return
            try {
                w.write(line)
                w.write("\n")
                w.flush()
                return
            } catch (t: Throwable) {
                Log.w(TAG, "shell write failed, reopening: $line", t)
                shellWriter = null
                shellProcess = null
            }
            if (!ensureShell()) return
            try {
                shellWriter?.write(line)
                shellWriter?.write("\n")
                shellWriter?.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "shell write FAILED after reopen: $line", t)
            }
        }
    }

    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }
}
