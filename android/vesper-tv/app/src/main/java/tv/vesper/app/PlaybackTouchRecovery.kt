package tv.vesper.app

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebView
import org.json.JSONObject

/** Phone/tablet-only safety net for a WebView dropping DOM activation.
 * Observe, never consume input. Activate only the SAME stationary playback
 * control if its normal handler didn't run; preserve scroll and multi-touch.
 */
internal class PlaybackTouchRecovery(private val webView: WebView) {
    private val script = webView.context.assets.open("touch-playback.js").bufferedReader().use { it.readText() }
    private val policy = PlaybackTapPolicy(
        ViewConfiguration.get(webView.context).scaledTouchSlop.toFloat(),
        ViewConfiguration.getLongPressTimeout().toLong(),
    )
    private var generation = 0
    private var before: JSONObject? = null
    private var width = 0
    private var height = 0
    private data class Pending(val token: Int, val x: Float, val y: Float, val due: Long)
    private var pending: Pending? = null

    @SuppressLint("ClickableViewAccessibility")
    fun install() {
        webView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val token = ++generation
                    val touchAt = System.currentTimeMillis()
                    val nativeHref = webView.url
                    before = null
                    pending = null
                    width = webView.width; height = webView.height
                    val tool = event.getToolType(0)
                    val physical = tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_STYLUS
                    policy.down(event.x, event.y, event.eventTime, event.pointerCount, physical)
                    if (physical && width > 0 && height > 0) {
                        webView.evaluateJavascript("($script).probe(${event.x},${event.y},$width,$height)") { value ->
                            if (generation == token && value != "null") {
                                before = try {
                                    JSONObject(value).put("touchAt", touchAt).put("nativeHref", nativeHref)
                                } catch (_: Exception) { null }
                                // A busy renderer can return the hit-test after
                                // ACTION_UP. Do not lose a valid tap in that gap.
                                recoverWhenReady()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> policy.move(event.x, event.y, event.pointerCount)
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> {
                    cancel()
                }
                MotionEvent.ACTION_UP -> {
                    if (policy.up(event.x, event.y, event.eventTime, event.pointerCount)) {
                        // Let the ordinary pointer/click path complete first.
                        pending = Pending(generation, event.x, event.y, SystemClock.uptimeMillis() + 100L)
                        webView.postDelayed({ recoverWhenReady() }, 100L)
                    } else { before = null; pending = null }
                }
            }
            false
        }
    }

    private fun recoverWhenReady() {
        val tap = pending ?: return
        val target = before ?: return
        val now = SystemClock.uptimeMillis()
        if (tap.token != generation || now > tap.due + 1500L) { pending = null; return }
        if (now < tap.due) {
            webView.postDelayed({ recoverWhenReady() }, tap.due - now)
            return
        }
        pending = null // timer + probe callback may both arrive; run once only
        before = null
        if (webView.hasWindowFocus() && webView.isAttachedToWindow &&
            webView.width == width && webView.height == height) {
            webView.evaluateJavascript("($script).activate($target,${tap.x},${tap.y},$width,$height)", null)
        }
    }

    fun cancel() { generation++; before = null; pending = null; policy.cancel() }
}