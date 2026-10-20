package tv.vesper.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 2026-10-20 — operator spec: when a movie stalls mid-playback show a
 * pop-up ("check your internet connection or swap stream") with an
 * in-app speed test.  Ookla has no public Speedtest API, so the test
 * uses Cloudflare's public speed endpoints; "Open Speedtest" launches
 * the official Speedtest by Ookla app when it is installed.
 */
class StallHelpDialog(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val neededMbps: () -> Double?,
    private val onSwap: () -> Unit,
    private val onWait: () -> Unit,
) {
    private var dialog: Dialog? = null
    private var testJob: Job? = null
    val isShowing get() = dialog?.isShowing == true

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun show(reason: String) {
        if (isShowing || activity.isFinishing) return
        val accent = Color.rgb(0, 184, 255)
        val result = TextView(activity).apply {
            setTextColor(Color.rgb(155, 168, 194)); textSize = 14f; setPadding(0, dp(10), 0, 0)
            text = neededMbps()?.let { "This movie needs about ${"%.1f".format(it)} Mbps." } ?: ""
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        fun button(label: String, primary: Boolean, click: (Button) -> Unit) = Button(activity).apply {
            text = label; isAllCaps = false; textSize = 15f; isFocusable = true
            setTextColor(if (primary) Color.rgb(3, 6, 15) else Color.WHITE)
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), round(if (primary) Color.rgb(92, 223, 255) else Color.argb(64, 0, 184, 255), 24, accent))
                addState(intArrayOf(), round(if (primary) accent else Color.argb(26, 255, 255, 255), 24, Color.argb(46, 255, 255, 255)))
            }
            setPadding(dp(20), 0, dp(20), 0); setOnClickListener { click(this) }
        }
        val speed = button("Run speed test", true) { runTest(it, result) }
        val swap = button("Swap stream", false) { dismiss(); onSwap() }
        val wait = button("Keep waiting", false) { dismiss(); onWait() }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(22), 0, 0)
            listOf(speed, swap, wait).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(12) }) }
        }
        val ookla = ooklaIntent()?.let { intent -> button("Open Speedtest by Ookla", false) { activity.startActivity(intent) } }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(28), dp(32), dp(28))
            background = round(Color.argb(245, 10, 16, 30), 20, Color.argb(70, 0, 184, 255))
            addView(TextView(activity).apply { text = "PLAYBACK INTERRUPTED"; setTextColor(accent); textSize = 12f; letterSpacing = .28f })
            addView(TextView(activity).apply { text = "Please check your internet connection or swap stream"; setTextColor(Color.WHITE); textSize = 24f; setPadding(0, dp(8), 0, 0) })
            addView(TextView(activity).apply { text = reason; setTextColor(Color.rgb(155, 168, 194)); textSize = 14f; setPadding(0, dp(8), 0, 0) })
            addView(result); addView(row)
            ookla?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(12) }) }
        }
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card, ViewGroup.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); window?.setGravity(Gravity.CENTER)
            setOnCancelListener { testJob?.cancel(); onWait() }
            setOnShowListener { swap.requestFocus() }
            show()
        }
    }

    fun dismiss() { testJob?.cancel(); dialog?.dismiss(); dialog = null }

    private fun ooklaIntent(): Intent? = listOf("org.zwanoo.android.speedtest", "com.ookla.speedtest")
        .firstNotNullOfOrNull { activity.packageManager.getLaunchIntentForPackage(it) }

    private fun runTest(button: Button, result: TextView) {
        if (testJob?.isActive == true) return
        result.visibility = View.VISIBLE; result.setTextColor(Color.WHITE); result.text = "Testing your connection…"; button.isEnabled = false
        testJob = scope.launch {
            val (ping, mbps) = withContext(Dispatchers.IO) { measure() }
            val need = neededMbps()
            result.text = buildString {
                append(if (mbps > 0) "Download: ${"%.1f".format(mbps)} Mbps" else "Speed test failed — your connection may be down.")
                if (ping > 0) append("   ·   Ping: $ping ms")
                need?.let { append("\nThis movie needs about ${"%.1f".format(it)} Mbps — ") ; append(if (mbps >= it * 1.5) "your connection is fast enough, try swapping stream." else "your connection is too slow for it right now.") }
            }
            result.setTextColor(if (need == null || mbps >= need * 1.5) Color.rgb(122, 235, 138) else Color.rgb(255, 196, 92))
            button.isEnabled = true; button.text = "Run again"
        }
    }

    private suspend fun measure(): Pair<Long, Double> {
        val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val ping = runCatching {
            val t = System.nanoTime()
            client.newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=0").build()).execute().use { }
            (System.nanoTime() - t) / 1_000_000
        }.getOrDefault(-1L)
        val mbps = runCatching {
            client.newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=50000000").build()).execute().use { r ->
                val input = r.body!!.byteStream(); val buf = ByteArray(64 * 1024)
                val start = System.nanoTime(); var total = 0L
                while (kotlin.coroutines.coroutineContext.isActive && System.nanoTime() - start < 8_000_000_000L) {
                    val n = input.read(buf); if (n < 0) break; total += n
                }
                total * 8.0 / ((System.nanoTime() - start) / 1e9) / 1e6
            }
        }.getOrDefault(0.0)
        return ping to mbps
    }
}
