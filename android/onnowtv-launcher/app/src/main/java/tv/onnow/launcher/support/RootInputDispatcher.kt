package tv.onnow.launcher.support

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * v2.10.84 — Remote-input dispatcher for the support session.
 *
 * Boxes are rooted (operator confirmed), so we use `su -c "input ..."`
 * which is the universally-correct way to inject events on Android
 * without the signature-protected INJECT_EVENTS permission.  No
 * AccessibilityService configuration needed by the customer.
 *
 * Supported actions
 * -----------------
 *  • tap   {x:0..1, y:0..1}         — normalised to screen size
 *  • swipe {x1,y1,x2,y2,ms}         — normalised
 *  • key   {key:"DPAD_UP"|"BACK"|…} — Android keycode names
 *  • text  {chars:"foo bar"}        — types into focused field
 *  • DEL   key for backspace
 *
 * Failures are silent (logged to logcat) — the operator side has no
 * meaningful UI response anyway, and a noisy alarm would tip off the
 * customer that something went wrong during their support session.
 */
object RootInputDispatcher {
    private const val TAG = "RootInput"

    /** Map of accepted action.key strings → Android KeyEvent codes
     *  (string form for `input keyevent`). */
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

    fun handle(ctx: Context, msg: JSONObject) {
        val action = msg.optString("action").lowercase()
        try {
            when (action) {
                "tap" -> {
                    val (sw, sh) = screenSize(ctx)
                    val x = (msg.optDouble("x") * sw).toInt().coerceIn(0, sw - 1)
                    val y = (msg.optDouble("y") * sh).toInt().coerceIn(0, sh - 1)
                    runShell("input tap $x $y")
                }
                "swipe" -> {
                    val (sw, sh) = screenSize(ctx)
                    val x1 = (msg.optDouble("x1") * sw).toInt().coerceIn(0, sw - 1)
                    val y1 = (msg.optDouble("y1") * sh).toInt().coerceIn(0, sh - 1)
                    val x2 = (msg.optDouble("x2") * sw).toInt().coerceIn(0, sw - 1)
                    val y2 = (msg.optDouble("y2") * sh).toInt().coerceIn(0, sh - 1)
                    val ms = msg.optInt("ms", 250)
                    runShell("input swipe $x1 $y1 $x2 $y2 $ms")
                }
                "key" -> {
                    val keyName = msg.optString("key")
                    val mapped = KEY_ALIAS[keyName] ?: keyName
                    runShell("input keyevent KEYCODE_$mapped")
                }
                "text" -> {
                    val raw = msg.optString("chars")
                    if (raw.isEmpty()) return
                    // Escape spaces (Android input requires %s for
                    // spaces) and shell-escape quote chars.
                    val escaped = raw
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace(" ", "%s")
                    runShell("input text \"$escaped\"")
                }
                else -> Log.w(TAG, "unknown action: $action")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "input dispatch failed for action=$action", t)
        }
    }

    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val m = ctx.resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    /** Run a command through `su` if available, falling back to plain
     *  `sh` (which works for `input` on most TV firmware regardless
     *  of root status).  Output is discarded — we're fire-and-forget. */
    private fun runShell(cmd: String) {
        // Try rooted path first
        val rooted = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor() == 0
        } catch (_: Throwable) { false }
        if (rooted) return
        // Fallback to direct sh — works for `input` on TV boxes that
        // grant it to system apps but not to user apps; safe to try.
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "shell exec failed for: $cmd", t)
        }
    }
}
