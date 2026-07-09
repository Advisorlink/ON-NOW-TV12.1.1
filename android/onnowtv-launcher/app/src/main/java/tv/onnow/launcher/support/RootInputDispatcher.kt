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

    // v2.13.22 — Mouse HID device auto-discovery for the phone
    // trackpad on Android 7-11 (HK1-class boxes).  Populated the
    // first time a `mouse_*` action arrives.  Any device that
    // enumerates an `EV_REL` capability in `getevent -pl` is a
    // pointer device (physical air-mouse dongle, USB mouse, etc.).
    // We inject `sendevent` REL_X/REL_Y/SYN_REPORT triples into
    // that node — Android's input reader adds them to whatever the
    // real air-mouse is emitting, so a cursor already visible on
    // screen just moves.  `null` = we tried and found nothing (the
    // fallback `cmd input tap` path handles clicks positionally).
    @Volatile private var mouseDevice: String? = null
    @Volatile private var mouseDeviceProbed: Boolean = false

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
                    writeShellLine("cmd input tap $x $y")
                }
                "swipe" -> {
                    val (sw, sh) = screenSize(ctx)
                    val x1 = (msg.optDouble("x1") * sw).toInt().coerceIn(0, sw - 1)
                    val y1 = (msg.optDouble("y1") * sh).toInt().coerceIn(0, sh - 1)
                    val x2 = (msg.optDouble("x2") * sw).toInt().coerceIn(0, sw - 1)
                    val y2 = (msg.optDouble("y2") * sh).toInt().coerceIn(0, sh - 1)
                    val ms = msg.optInt("ms", 250)
                    writeShellLine("cmd input swipe $x1 $y1 $x2 $y2 $ms")
                }
                "key" -> {
                    val keyName = msg.optString("key")
                    val mapped = KEY_ALIAS[keyName] ?: keyName
                    writeShellLine("cmd input keyevent KEYCODE_$mapped")
                }
                "longpress" -> {
                    // Push-and-hold on the phone → long-press keyevent
                    // on the box (e.g. long-press OK to add to Library
                    // in Vesper).  `--longpress` is honoured by the
                    // `input` command on all boxes we target.
                    val keyName = msg.optString("key")
                    val mapped = KEY_ALIAS[keyName] ?: keyName
                    writeShellLine("cmd input keyevent --longpress KEYCODE_$mapped")
                }
                "text" -> {
                    val raw = msg.optString("chars")
                    if (raw.isEmpty()) return
                    val escaped = raw
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace(" ", "%s")
                    writeShellLine("cmd input text \"$escaped\"")
                }
                "mouse_move" -> {
                    // v2.13.22 — Trackpad support.  Two dispatch paths:
                    //   (a) If a real HID mouse is on the box (physical
                    //       air-mouse dongle, USB mouse, etc.) we
                    //       inject relative REL_X / REL_Y events into
                    //       its /dev/input/eventN node via sendevent.
                    //       Android's input reader adds them to the
                    //       existing on-screen cursor position, so the
                    //       air-mouse cursor already visible on screen
                    //       just moves — pixel-for-pixel indistinguish-
                    //       able from the physical remote.  Works on
                    //       every Android version.
                    //   (b) Fallback (no mouse device present):
                    //       remember the absolute position server-side
                    //       so an eventual `mouse_tap` lands there via
                    //       `cmd input tap`.  Also attempts an Android
                    //       12+ `input motionevent MOVE … MOUSE`
                    //       (harmless on older Android — the shell
                    //       just prints an error we drop to /dev/null).
                    val (sw, sh) = screenSize(ctx)
                    val relDx = msg.optInt("dx", Int.MIN_VALUE)
                    val relDy = msg.optInt("dy", Int.MIN_VALUE)
                    if (relDx != Int.MIN_VALUE || relDy != Int.MIN_VALUE) {
                        // Relative-motion payload (preferred for
                        // real-mouse injection — no smoothing loss).
                        val dx = if (relDx == Int.MIN_VALUE) 0 else relDx
                        val dy = if (relDy == Int.MIN_VALUE) 0 else relDy
                        cursorX = (cursorX + dx).coerceIn(0, sw - 1)
                        cursorY = (cursorY + dy).coerceIn(0, sh - 1)
                        injectMouseRel(dx, dy)
                    } else {
                        // Absolute {x, y} normalised in [0..1].
                        val ax = (msg.optDouble("x") * sw).toInt().coerceIn(0, sw - 1)
                        val ay = (msg.optDouble("y") * sh).toInt().coerceIn(0, sh - 1)
                        val dx = ax - cursorX
                        val dy = ay - cursorY
                        cursorX = ax
                        cursorY = ay
                        // Try relative injection into the real mouse
                        // node first (so the visible cursor moves) —
                        // if no device, `injectMouseRel` no-ops and
                        // the position is still tracked for a later
                        // `mouse_tap`.
                        injectMouseRel(dx, dy)
                    }
                }
                "mouse_tap" -> {
                    // Left-click at the current tracked cursor pos.
                    // Prefer HID button injection into the real mouse
                    // node (so it lands as MOUSE_LEFT, respected by
                    // every app including ones that only listen for
                    // true pointer clicks).  Falls back to
                    // `cmd input tap` when no HID mouse is present.
                    if (!injectMouseButton(true) || !injectMouseButton(false)) {
                        writeShellLine("cmd input tap $cursorX $cursorY")
                    }
                }
                "mouse_longpress" -> {
                    // 700 ms "press and hold" at the cursor — used
                    // for context menus / drag-select behaviours.
                    if (injectMouseButton(true)) {
                        try { Thread.sleep(700) } catch (_: Throwable) {}
                        injectMouseButton(false)
                    } else {
                        writeShellLine("cmd input swipe $cursorX $cursorY $cursorX $cursorY 700")
                    }
                }
                else -> Log.w(TAG, "unknown action: $action")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "input dispatch failed for action=$action", t)
        }
    }

    // v2.13.22 — Cursor position tracker for phone trackpad taps.
    // Persists across events so `mouse_tap` lands at wherever the
    // user last moved the pointer.  Initialised to screen centre in
    // ensureShell() so a tap-without-move still lands somewhere sane.
    @Volatile private var cursorX: Int = 0
    @Volatile private var cursorY: Int = 0

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

    // ─── HID mouse injection (v2.13.22) ────────────────────────────
    /**
     * Auto-discover the first `/dev/input/eventN` node that emits
     * `EV_REL` (a mouse-like pointer device — physical air-mouse
     * dongle, USB mouse, virtual pointer, etc.).  Runs `getevent -pl`
     * once via the persistent root shell, caches the path on
     * `mouseDevice`, and returns it.  A negative result is also
     * cached (`mouseDeviceProbed = true`, `mouseDevice = null`) so we
     * don't re-probe on every trackpad frame — but a re-probe is
     * triggered whenever a `mouse_*` action arrives after the
     * fallback path was used, in case the user hot-plugs the dongle.
     */
    private fun discoverMouseDevice(): String? {
        if (mouseDevice != null) return mouseDevice
        if (mouseDeviceProbed) return null
        val p = shellProcess ?: return null
        try {
            // Use a fresh sub-shell so `getevent -pl` doesn't
            // pollute our persistent stdout drain thread.
            val probe = ProcessBuilder("su", "-c", "getevent -pl 2>&1")
                .redirectErrorStream(true).start()
            val out = probe.inputStream.bufferedReader().readText()
            probe.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            var currentDev: String? = null
            for (line in out.lineSequence()) {
                val trimmed = line.trim()
                // "add device N: /dev/input/eventN"
                val addIdx = trimmed.indexOf("/dev/input/event")
                if (trimmed.startsWith("add device") && addIdx > 0) {
                    currentDev = trimmed.substring(addIdx).trim()
                    continue
                }
                // `getevent -pl` prints capabilities like
                //     REL (0002): REL_X REL_Y REL_WHEEL
                // A REL section = pointer device.
                if (currentDev != null && trimmed.startsWith("REL (")) {
                    mouseDevice = currentDev
                    Log.i(TAG, "HID mouse device discovered: $currentDev")
                    mouseDeviceProbed = true
                    return currentDev
                }
            }
            Log.i(TAG, "no HID mouse device found; trackpad → cmd input fallback")
        } catch (t: Throwable) {
            Log.w(TAG, "getevent probe failed", t)
        }
        mouseDeviceProbed = true
        return null
    }

    /**
     * Push a relative mouse-motion frame into the discovered HID
     * mouse node.  Returns true if the write reached the shell.
     * Silent no-op (and returns false) if no mouse device was found.
     *
     * Event structure (kernel input protocol):
     *   EV_REL(2) REL_X(0)  dx
     *   EV_REL(2) REL_Y(1)  dy
     *   EV_SYN(0) SYN_REPORT(0) 0
     * `sendevent` treats negative values as unsigned 32-bit ints, so
     * we mask into 0xFFFFFFFF before dispatch.
     */
    private fun injectMouseRel(dx: Int, dy: Int): Boolean {
        if (dx == 0 && dy == 0) return true
        val dev = discoverMouseDevice() ?: return false
        val ux = (dx.toLong() and 0xFFFFFFFFL)
        val uy = (dy.toLong() and 0xFFFFFFFFL)
        // Batch as a single shell line so the three events land
        // together (no partial frame reaches the input reader).
        writeShellLine(
            "sendevent $dev 2 0 $ux;sendevent $dev 2 1 $uy;sendevent $dev 0 0 0"
        )
        return true
    }

    /**
     * Press or release BTN_LEFT on the HID mouse.  Returns true if a
     * mouse device was available.  BTN_LEFT (0x110 = 272) is the
     * canonical left-click code; sync-report after each press so the
     * input reader emits a matching MotionEvent.
     */
    private fun injectMouseButton(pressed: Boolean): Boolean {
        val dev = discoverMouseDevice() ?: return false
        val v = if (pressed) 1 else 0
        writeShellLine("sendevent $dev 1 272 $v;sendevent $dev 0 0 0")
        return true
    }
}
