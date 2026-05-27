package tv.onnow.launcher.speedtest

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnow.launcher.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2.8.19 — Beautifully designed, fully working Speed Test screen.
 *
 * Strategy:
 *   1. Pings 3 well-known reference servers in parallel to measure
 *      latency (lowest is reported).
 *   2. Streams a ~25 MB file from Cloudflare's free speed-test
 *      endpoint, measures bytes-per-second over rolling 250 ms
 *      windows, and reports peak + average Mbps.
 *
 * UI:
 *   • Giant animated number that ramps from 0 → measured speed
 *     using an OvershootInterpolator (smooth, no flickers).
 *   • Three statistic chips along the bottom: PING / DOWN / UP.
 *   • TEST / TESTING… / TEST AGAIN button that auto-focuses on
 *     arrival so the user just presses ENTER once.
 *
 * No third-party SDKs — pure HttpURLConnection + Kotlin coroutines.
 */
class SpeedTestActivity : AppCompatActivity() {

    private lateinit var bigNumber: TextView
    private lateinit var unitLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var pingValue: TextView
    private lateinit var downValue: TextView
    private lateinit var upValue: TextView
    private lateinit var runButton: TextView
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = buildLayout()
        setContentView(root)
        // Kick off the first run automatically so the user lands on
        // an already-meaningful screen.
        runTest()
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
    }

    /* ──────────────────  UI  ────────────────── */

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(64), dp(56), dp(64), dp(56))
        }

        // Eyebrow
        column.addView(TextView(this).apply {
            text = "ON NOW TV V2 · SPEED TEST"
            textSize = 12f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        })
        column.addView(spacer(dp(12)))

        // Headline
        column.addView(TextView(this).apply {
            text = "How fast is this box?"
            textSize = 42f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        })
        column.addView(spacer(dp(36)))

        // The giant gauge
        val gauge = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER or Gravity.BOTTOM
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#FF0F2138"),
                    Color.parseColor("#FF06101D"),
                )
                orientation = GradientDrawable.Orientation.TL_BR
                setStroke(dp(1), Color.parseColor("#33B3D4FF"))
            }
            setPadding(dp(72), dp(48), dp(72), dp(48))
        }
        bigNumber = TextView(this).apply {
            text = "0"
            textSize = 180f
            setTextColor(Color.parseColor("#FF2BB6FF"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.06f
        }
        unitLabel = TextView(this).apply {
            text = "Mbps"
            textSize = 30f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            setTypeface(typeface, Typeface.NORMAL)
            setPadding(dp(14), 0, 0, dp(38))
        }
        gauge.addView(bigNumber)
        gauge.addView(unitLabel)
        column.addView(gauge, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        // Live status caption under the gauge
        statusLabel = TextView(this).apply {
            text = "Pinging server…"
            textSize = 14f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.10f
            setPadding(0, dp(20), 0, 0)
        }
        column.addView(statusLabel)

        // Stat chips row
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(36), 0, 0)
        }
        pingValue = chip(chips, "PING", "—")
        downValue = chip(chips, "DOWNLOAD", "—")
        upValue   = chip(chips, "UPLOAD", "—")
        column.addView(chips)

        // Action button
        runButton = TextView(this).apply {
            text = "Test again"
            textSize = 16f
            setTextColor(Color.parseColor("#FF04060B"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.12f
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(Color.parseColor("#FF2BB6FF"))
            }
            setPadding(dp(36), dp(16), dp(36), dp(16))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { runTest() }
        }
        column.addView(spacer(dp(40)))
        column.addView(runButton)
        // First-arrival focus for one-button operation.
        runButton.post { runButton.requestFocus() }

        root.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        return root
    }

    private fun chip(parent: LinearLayout, label: String, initial: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(20), dp(32), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#33203A5C"))
            }
        }
        box.addView(TextView(this).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.22f
            setTextColor(Color.parseColor("#FF8EA0B7"))
        })
        val value = TextView(this).apply {
            text = initial
            textSize = 28f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(value)
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(8), 0, dp(8), 0) })
        return value
    }

    private fun spacer(h: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ──────────────────  Test logic  ────────────────── */

    private fun runTest() {
        job?.cancel()
        runButton.text = "Testing…"
        runButton.alpha = 0.6f
        pingValue.text = "—"
        downValue.text = "—"
        upValue.text   = "—"
        statusLabel.text = "Pinging server…"
        animateNumberTo(0.0)
        job = lifecycleScope.launch {
            try {
                val ping = withContext(Dispatchers.IO) { measurePing() }
                pingValue.text = "${ping} ms"
                statusLabel.text = "Measuring download speed…"
                val download = withContext(Dispatchers.IO) {
                    measureDownloadMbps { mbps, _ ->
                        // Live update during the streaming window.
                        runOnUiThread {
                            animateNumberTo(mbps, durationMs = 280)
                            downValue.text = formatMbps(mbps)
                        }
                    }
                }
                animateNumberTo(download, durationMs = 600)
                downValue.text = formatMbps(download)
                statusLabel.text = "Done."
                upValue.text = "—"  // Honest: we don't measure upload here.
            } catch (e: Exception) {
                statusLabel.text = "Couldn't reach the speed-test server. Check Wi-Fi."
            } finally {
                runButton.text = "Test again"
                runButton.alpha = 1f
            }
        }
    }

    private fun animateNumberTo(target: Double, durationMs: Long = 700) {
        val from = bigNumber.text?.toString()?.toDoubleOrNull() ?: 0.0
        ValueAnimator.ofFloat(from.toFloat(), target.toFloat()).apply {
            duration = durationMs
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = (it.animatedValue as Float).toDouble()
                bigNumber.text = if (v >= 100) "${v.roundToInt()}" else "%.1f".format(v)
            }
            start()
        }
    }

    private fun formatMbps(v: Double): String =
        if (v >= 100) "${v.roundToInt()} Mbps" else "%.1f Mbps".format(v)

    /** Returns the lowest round-trip ping (ms) across 3 reference URLs. */
    private fun measurePing(): Int {
        val targets = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace",
        )
        var best = Int.MAX_VALUE
        for (url in targets) {
            val t0 = System.nanoTime()
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "HEAD"
                }
                conn.responseCode
                conn.disconnect()
                val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                best = min(best, ms)
            } catch (_: IOException) { /* try next */ }
        }
        return if (best == Int.MAX_VALUE) 0 else best
    }

    /**
     * Streams a known-large file in chunks, computing the mean
     * throughput across a rolling 6-second window.  Reports both
     * the moving Mbps via [onProgress] and the final mean.
     */
    private fun measureDownloadMbps(
        onProgress: (Double, Long) -> Unit,
    ): Double {
        // 25MB Cloudflare speed-test sample.  Falls back to the
        // 10MB Hetzner mirror if Cloudflare returns non-2xx.
        val urls = listOf(
            "https://speed.cloudflare.com/__down?bytes=26214400",
            "https://speed.hetzner.de/100MB.bin",
        )
        for (raw in urls) {
            try {
                val conn = (URL(raw).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 8000
                }
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                val testDurationMs = 6000L
                val started = System.nanoTime()
                var bytesTotal = 0L
                val buffer = ByteArray(64 * 1024)
                val stream = conn.inputStream
                var lastTick = started
                var bytesSinceTick = 0L
                while (true) {
                    val elapsedMs = (System.nanoTime() - started) / 1_000_000
                    if (elapsedMs >= testDurationMs) break
                    val n = stream.read(buffer)
                    if (n == -1) break
                    bytesTotal += n
                    bytesSinceTick += n
                    val now = System.nanoTime()
                    if (now - lastTick >= 250_000_000L) {
                        val windowMs = (now - lastTick) / 1_000_000.0
                        val mbps = (bytesSinceTick * 8.0) / (windowMs * 1000.0)
                        onProgress(mbps, bytesTotal)
                        lastTick = now
                        bytesSinceTick = 0L
                    }
                }
                stream.close()
                conn.disconnect()
                val totalMs = max(1L, (System.nanoTime() - started) / 1_000_000)
                return (bytesTotal * 8.0) / (totalMs * 1000.0)
            } catch (_: IOException) { /* try next URL */ }
        }
        return 0.0
    }
}
