package tv.onnow.launcher.support

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * v2.14.0 — Persistent-shell remote-input dispatcher.
 *
 * One long-lived `su` shell is opened at session start (single Magisk
 * prompt); every input is a line written to its stdin.
 *
 * Key dispatch (the "nothing works on the HK1 box" fix)
 * -----------------------------------------------------
 * A previous "lag fix" switched every dispatch to `cmd input …`.  But
 * the `input` subcommand was only registered with `cmd` in Android
 * 11/12 — on Android 9 (the operator's HK1 box) `cmd input …` fails
 * silently (shell stdout is drained/discarded), so the remote showed
 * "connected" while NOTHING landed.
 *
 *   • API 31+  → `cmd input …`  (~30 ms, no JVM spawn)
 *   • API <31  → classic `input …` binary — works on every Android
 *                version.  Slightly slower (spawns app_process) but
 *                RELIABLE, which beats a dead remote.
 *
 * Trackpad (`mouse_*`)
 * --------------------
 * The air-mouse dongle enumerates a relative-pointer input node.  We
 * discover it from `/proc/bus/input/devices` (a static kernel table
 * that reads instantly with EOF — the old `getevent -pl` probe BLOCKED
 * forever polling events, so the mouse node was never found and the
 * trackpad's on-phone dot moved while the TV cursor didn't).  We then
 * inject relative `REL_X`/`REL_Y` frames with `sendevent` using SIGNED
 * decimals (the previous unsigned-0xFFFFFFFF masking corrupted the
 * deltas — `event.value` is a signed int32, so left/up = negative).
 */
object RootInputDispatcher {
    private const val TAG = "RootInput"

    private val CMD_INPUT_OK = Build.VERSION.SDK_INT >= 31

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
        "SEARCH" to "SEARCH",
        "MUTE" to "VOLUME_MUTE",
        "PAGE_UP" to "PAGE_UP",
        "PAGE_DOWN" to "PAGE_DOWN",
        "ASSIST" to "ASSIST",
        "MEDIA_PLAY_PAUSE" to "MEDIA_PLAY_PAUSE",
        "MEDIA_PLAY" to "MEDIA_PLAY",
        "MEDIA_PAUSE" to "MEDIA_PAUSE",
        "MEDIA_STOP" to "MEDIA_STOP",
        "MEDIA_FAST_FORWARD" to "MEDIA_FAST_FORWARD",
        "MEDIA_REWIND" to "MEDIA_REWIND",
        "MEDIA_NEXT" to "MEDIA_NEXT",
        "MEDIA_PREVIOUS" to "MEDIA_PREVIOUS",
    )

    @Volatile private var shellProcess: Process? = null
    @Volatile private var shellWriter: BufferedWriter? = null
    private val shellLock = Any()

    // Discovered relative-pointer node (air-mouse dongle) for the
    // trackpad.  Probed once from /proc/bus/input/devices, cached.
    @Volatile private var mouseDevice: String? = null
    @Volatile private var probed = false
    private val probeLock = Any()

    @Volatile private var cursorX: Int = -1
    @Volatile private var cursorY: Int = -1

    /** Open a persistent root shell.  Idempotent. */
    fun ensureShell(): Boolean {
        synchronized(shellLock) {
            if (shellWriter != null && shellProcess?.isAlive == true) return true
            val cmds = arrayOf(arrayOf("su"), arrayOf("sh"))
            for (cmd in cmds) {
                try {
                    val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
                    val w = BufferedWriter(OutputStreamWriter(p.outputStream))
                    Thread {
                        try {
                            p.inputStream.bufferedReader().use { r ->
                                while (true) {
                                    val line = r.readLine() ?: break
                                    Log.v(TAG, "shell: $line")
                                }
                            }
                        } catch (_: Throwable) { /* pipe closed */ }
                    }.also { it.isDaemon = true }.start()
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

    /** `cmd input …` on 31+, classic `input …` binary below. */
    private fun inputCmd(args: String): String =
        if (CMD_INPUT_OK) "cmd input $args" else "input $args"

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
                    writeShellLine(inputCmd("tap $x $y"))
                }
                "swipe" -> {
                    val (sw, sh) = screenSize(ctx)
                    val x1 = (msg.optDouble("x1") * sw).toInt().coerceIn(0, sw - 1)
                    val y1 = (msg.optDouble("y1") * sh).toInt().coerceIn(0, sh - 1)
                    val x2 = (msg.optDouble("x2") * sw).toInt().coerceIn(0, sw - 1)
                    val y2 = (msg.optDouble("y2") * sh).toInt().coerceIn(0, sh - 1)
                    val ms = msg.optInt("ms", 250)
                    writeShellLine(inputCmd("swipe $x1 $y1 $x2 $y2 $ms"))
                }
                "key" -> {
                    val mapped = KEY_ALIAS[msg.optString("key")] ?: msg.optString("key")
                    writeShellLine(inputCmd("keyevent KEYCODE_$mapped"))
                }
                "longpress" -> {
                    val mapped = KEY_ALIAS[msg.optString("key")] ?: msg.optString("key")
                    writeShellLine(inputCmd("keyevent --longpress KEYCODE_$mapped"))
                }
                "text" -> {
                    val raw = msg.optString("chars")
                    if (raw.isEmpty()) return
                    val escaped = raw
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace(" ", "%s")
                    writeShellLine(inputCmd("text \"$escaped\""))
                }
                "mouse_move" -> {
                    val (sw, sh) = screenSize(ctx)
                    initCursor(sw, sh)
                    val relDx = msg.optInt("dx", Int.MIN_VALUE)
                    val relDy = msg.optInt("dy", Int.MIN_VALUE)
                    if (relDx != Int.MIN_VALUE || relDy != Int.MIN_VALUE) {
                        val dx = if (relDx == Int.MIN_VALUE) 0 else relDx
                        val dy = if (relDy == Int.MIN_VALUE) 0 else relDy
                        cursorX = (cursorX + dx).coerceIn(0, sw - 1)
                        cursorY = (cursorY + dy).coerceIn(0, sh - 1)
                        injectMouseRel(ctx, dx, dy)
                    } else {
                        val ax = (msg.optDouble("x") * sw).toInt().coerceIn(0, sw - 1)
                        val ay = (msg.optDouble("y") * sh).toInt().coerceIn(0, sh - 1)
                        val dx = ax - cursorX
                        val dy = ay - cursorY
                        cursorX = ax
                        cursorY = ay
                        injectMouseRel(ctx, dx, dy)
                    }
                }
                "mouse_tap" -> {
                    val (sw, sh) = screenSize(ctx)
                    initCursor(sw, sh)
                    if (!injectMouseButton(ctx, true) || !injectMouseButton(ctx, false)) {
                        writeShellLine(inputCmd("tap $cursorX $cursorY"))
                    }
                }
                "mouse_longpress" -> {
                    val (sw, sh) = screenSize(ctx)
                    initCursor(sw, sh)
                    if (injectMouseButton(ctx, true)) {
                        writeShellLine("sleep 0.7")
                        injectMouseButton(ctx, false)
                    } else {
                        writeShellLine(inputCmd("swipe $cursorX $cursorY $cursorX $cursorY 700"))
                    }
                }
                else -> Log.w(TAG, "unknown action: $action")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "input dispatch failed for action=$action", t)
        }
    }

    private fun initCursor(sw: Int, sh: Int) {
        if (cursorX < 0 || cursorY < 0) {
            cursorX = sw / 2
            cursorY = sh / 2
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

    /** Write one shell command line + flush; reopen once on a broken pipe. */
    private fun writeShellLine(line: String) {
        synchronized(shellLock) {
            val w = shellWriter ?: return
            try {
                w.write(line); w.write("\n"); w.flush()
                return
            } catch (t: Throwable) {
                Log.w(TAG, "shell write failed, reopening: $line", t)
                shellWriter = null
                shellProcess = null
            }
            if (!ensureShell()) return
            try {
                shellWriter?.write(line); shellWriter?.write("\n"); shellWriter?.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "shell write FAILED after reopen: $line", t)
            }
        }
    }

    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    // ─── HID mouse discovery via /proc/bus/input/devices ───────────

    private fun readProcInputDevices(ctx: Context): String {
        // Usually world-readable — read directly, no root prompt.
        try {
            val f = File("/proc/bus/input/devices")
            if (f.canRead()) {
                val t = f.readText()
                if (t.contains("event")) return t
            }
        } catch (_: Throwable) { /* fall through to shell */ }
        return try {
            val out = File(ctx.filesDir, "input_devices.txt")
            out.delete()
            writeShellLine("cat /proc/bus/input/devices > '${out.absolutePath}' 2>&1;chmod 644 '${out.absolutePath}'")
            var text = ""
            for (i in 0 until 12) {
                Thread.sleep(200)
                if (out.exists()) {
                    text = out.readText()
                    if (text.contains("event")) break
                }
            }
            text
        } catch (t: Throwable) {
            Log.w(TAG, "proc input devices read failed", t)
            ""
        }
    }

    private fun probeMouse(ctx: Context) {
        if (probed) return
        synchronized(probeLock) {
            if (probed) return
            try {
                mouseDevice = findRelativePointerNode(readProcInputDevices(ctx))
            } catch (t: Throwable) {
                Log.w(TAG, "mouse probe failed", t)
            }
            Log.i(TAG, "HID mouse probe done: $mouseDevice")
            probed = true
        }
    }

    /**
     * Parse `/proc/bus/input/devices` blocks (separated by blank
     * lines).  A relative-pointer device declares a non-zero `B: REL=`
     * bitmask with REL_X(bit0)+REL_Y(bit1) set (mask & 3 == 3).  The
     * `H: Handlers=` line names its `eventN` node.
     */
    private fun findRelativePointerNode(dump: String): String? {
        for (block in dump.split(Regex("\\n[ \\t]*\\n"))) {
            var event: String? = null
            var isPointer = false
            for (raw in block.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("H:")) {
                    val m = Regex("event\\d+").find(line)
                    if (m != null) event = m.value
                } else if (line.startsWith("B: REL=")) {
                    val hex = line.substringAfter("B: REL=").trim().split(Regex("\\s+")).lastOrNull()
                    val mask = hex?.toLongOrNull(16) ?: 0L
                    if (mask and 0x3L == 0x3L) isPointer = true
                }
            }
            if (isPointer && event != null) return "/dev/input/$event"
        }
        return null
    }

    /**
     * Relative mouse-motion frame into the HID mouse node:
     *   EV_REL(2) REL_X(0) dx · EV_REL(2) REL_Y(1) dy · EV_SYN(0) 0 0
     * SIGNED decimals — toybox `sendevent` parses negatives natively.
     */
    private fun injectMouseRel(ctx: Context, dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        probeMouse(ctx)
        val dev = mouseDevice ?: return false
        writeShellLine("sendevent $dev 2 0 $dx;sendevent $dev 2 1 $dy;sendevent $dev 0 0 0")
        return true
    }

    /** Press/release BTN_LEFT (272) on the HID mouse node. */
    private fun injectMouseButton(ctx: Context, pressed: Boolean): Boolean {
        probeMouse(ctx)
        val dev = mouseDevice ?: return false
        val v = if (pressed) 1 else 0
        writeShellLine("sendevent $dev 1 272 $v;sendevent $dev 0 0 0")
        return true
    }
}
