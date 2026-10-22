package tv.vesper.app

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.provider.Settings
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
    private val beforeExternalApp: () -> Unit = {},
    private val onTestStart: () -> Unit = {},
    private val onClosedAfterTest: () -> Unit = {},
) {
    private var testRan = false
    private var dialog: Dialog? = null
    private var testJob: Job? = null
    val isShowing get() = dialog?.isShowing == true

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    private fun round(color: Int, r: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun showSpeedTest() = show("Checking how fast this box's internet connection is right now.", "CONNECTION CHECK", "Speed test", autoRun = true)

    fun show(reason: String, eyebrow: String = "PLAYBACK INTERRUPTED", title: String = "Please check your internet connection or swap stream", autoRun: Boolean = false) {
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
        val gauge = SpeedGaugeView(activity).apply { visibility = View.GONE }
        val network = button("Check network connection", true) { beforeExternalApp(); openNetworkSettings() }
            .apply { visibility = View.GONE }
        val speed = button("Run speed test", true) { runTest(it, result, gauge, network) }
        val swap = button("Swap stream", false) { dismiss(); onSwap() }
        val wait = button(if (autoRun) "Close" else "Keep waiting", false) { dismiss(); if (!autoRun) onWait() }
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(22), 0, 0)
            listOf(speed, swap, wait).forEach { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { marginEnd = dp(12) }) }
        }
        val ookla = ooklaIntent()?.let { intent -> button("Open Speedtest by Ookla", false) { beforeExternalApp(); activity.startActivity(intent) } }
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(32), dp(28), dp(32), dp(28))
            background = round(Color.argb(245, 10, 16, 30), 20, Color.argb(70, 0, 184, 255))
            addView(TextView(activity).apply { text = eyebrow; setTextColor(accent); textSize = 12f; letterSpacing = .28f })
            addView(TextView(activity).apply { text = title; setTextColor(Color.WHITE); textSize = 24f; setPadding(0, dp(8), 0, 0) })
            addView(TextView(activity).apply { text = reason; setTextColor(Color.rgb(155, 168, 194)); textSize = 14f; setPadding(0, dp(8), 0, 0) })
            addView(gauge, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(14) })
            addView(result)
            addView(network, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply { topMargin = dp(14) })
            addView(row)
            ookla?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { topMargin = dp(12) }) }
        }
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(android.widget.ScrollView(activity).apply { isFillViewport = true; addView(card) }, ViewGroup.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT))
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); window?.setGravity(Gravity.CENTER)
            setOnCancelListener { testJob?.cancel(); if (!testRan) onWait() }
            setOnDismissListener { testJob?.cancel(); if (testRan) { testRan = false; onClosedAfterTest() } }
            setOnShowListener { if (autoRun) { speed.requestFocus(); runTest(speed, result, gauge, network) } else swap.requestFocus() }
            show()
        }
    }

    fun dismiss() { testJob?.cancel(); dialog?.dismiss(); dialog = null }

    private fun ooklaIntent(): Intent? = listOf("org.zwanoo.android.speedtest", "com.ookla.speedtest")
        .firstNotNullOfOrNull { activity.packageManager.getLaunchIntentForPackage(it) }

    private fun openNetworkSettings() {
        val pm = activity.packageManager
        listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)
            .map { Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            .firstOrNull { it.resolveActivity(pm) != null }
            ?.let { runCatching { activity.startActivity(it) } }
    }

    private fun runTest(button: Button, result: TextView, gauge: SpeedGaugeView, network: Button) {
        if (testJob?.isActive == true) return
        if (!testRan) { testRan = true; onTestStart() }
        gauge.visibility = View.VISIBLE; gauge.alpha = 0f; gauge.animate().alpha(1f).setDuration(250).start(); gauge.ping()
        result.visibility = View.VISIBLE; result.setTextColor(Color.WHITE); result.text = "Checking your connection…"
        button.isEnabled = false; button.text = "Testing…"; network.visibility = View.GONE
        testJob = scope.launch {
            val ping = withContext(Dispatchers.IO) { measurePing() }
            gauge.pinged(ping)
            result.text = if (ping > 0) "Ping $ping ms · measuring download speed…" else "Measuring download speed…"
            kotlinx.coroutines.delay(600)
            gauge.download(0.0)
            val mbps = withContext(Dispatchers.IO) { measureDownload { live -> scope.launch { gauge.download(live) } } }
            val need = neededMbps(); val ok = need == null || mbps >= need * 1.5
            gauge.done(mbps, mbps > 0 && ok)
            result.text = buildString {
                append(if (mbps > 0) "Download: ${"%.1f".format(mbps)} Mbps" else "Speed test failed — your connection may be down.")
                if (ping > 0) append("   ·   Ping: $ping ms")
                need?.let { append("\nThis movie needs about ${"%.1f".format(it)} Mbps — ") ; append(if (mbps >= it * 1.5) "your connection is fast enough, try swapping stream." else "your connection is too slow for it right now.") }
            }
            result.setTextColor(if (mbps > 0 && ok) Color.rgb(122, 235, 138) else Color.rgb(255, 196, 92))
            if (mbps < SLOW_MBPS) {
                result.append("\n\n$SLOW_NOTICE")
                network.visibility = View.VISIBLE
                network.requestFocus()
            }
            button.isEnabled = true; button.text = "Run again"
        }
    }

    private companion object {
        const val SLOW_MBPS = 5.0
        const val SLOW_NOTICE = "There could be an issue with your internet speed. Please check your internet and try again.\n\n" +
            "Troubleshooting things to try first:\n" +
            "1. Check your box is still connected to the internet.\n" +
            "2. See how many devices may be connected to your internet.\n" +
            "3. Restart the box.\n" +
            "4. Unplug the router for a few minutes to clear the unused stored data, then try again.\n\n" +
            "If it is still not working after trying these in order, contact On Now TV Support."
    }

    private val client by lazy { OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build() }

    private fun measurePing(): Long = runCatching {
        (0 until 3).minOf {
            val t = System.nanoTime()
            client.newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=0").build()).execute().use { }
            (System.nanoTime() - t) / 1_000_000
        }
    }.getOrDefault(-1L)

    // Cloudflare refuses single requests over ~50 MB (HTTP 403), so the
    // 8-second test streams back-to-back 25 MB chunks.
    private suspend fun measureDownload(live: (Double) -> Unit): Double = runCatching {
        val buf = ByteArray(64 * 1024)
        val start = System.nanoTime(); var total = 0L; var lastTick = start
        val ctx = kotlin.coroutines.coroutineContext
        fun elapsed() = System.nanoTime() - start
        while (ctx.isActive && elapsed() < 8_000_000_000L) {
            client.newCall(Request.Builder().url("https://speed.cloudflare.com/__down?bytes=25000000").build()).execute().use { r ->
                check(r.isSuccessful) { "speed test HTTP ${r.code}" }
                val input = r.body!!.byteStream()
                while (ctx.isActive && elapsed() < 8_000_000_000L) {
                    val n = input.read(buf); if (n < 0) break; total += n
                    val now = System.nanoTime()
                    if (now - lastTick > 150_000_000L) { lastTick = now; live(total * 8.0 / ((now - start) / 1e9) / 1e6) }
                }
            }
        }
        total * 8.0 / (elapsed() / 1e9) / 1e6
    }.getOrDefault(0.0)
}
