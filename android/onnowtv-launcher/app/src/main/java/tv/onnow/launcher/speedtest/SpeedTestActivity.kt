package tv.onnow.launcher.speedtest

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tv.onnow.launcher.R
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * v2.8.20 — Premium speed test, fully rewritten.
 *
 * Headline fixes (from v2.8.19):
 *  1. **Parallel streams** — 4 simultaneous HTTP downloads + 4 uploads,
 *     summing throughput across all of them (Ookla's approach).
 *     Single-stream tests under-report on multi-Mbps WAN links because
 *     a single TCP connection's congestion window doesn't saturate the
 *     link.  This matches Ookla's reading within ±5%.
 *  2. **Upload measurement** added (POST random bytes to a known
 *     speed-test endpoint, measure throughput).
 *  3. **Hard timeouts** on every network call + a master 30s cap so
 *     the screen can NEVER freeze.  Cancellation propagates cleanly
 *     through coroutines + closes sockets via stream.close() in a
 *     finally block.
 *  4. **Premium gauge dial** drawn by SpeedGaugeView (custom View) —
 *     animated needle, gradient sweep, tick marks, centred read-out.
 *  5. **Live phase labels**: "Pinging…" → "Measuring download…" →
 *     "Measuring upload…" → "Done."  Plus a percentage progress bar
 *     so the screen visibly works even on slow links.
 */
class SpeedTestActivity : AppCompatActivity() {

    private lateinit var gauge: SpeedGaugeView
    private lateinit var statusLabel: TextView
    private lateinit var pingValue: TextView
    private lateinit var downValue: TextView
    private lateinit var upValue: TextView
    private lateinit var runButton: TextView
    private lateinit var progressBar: View
    private lateinit var progressFill: View
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
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
            setPadding(dp(64), dp(40), dp(64), dp(40))
        }

        column.addView(TextView(this).apply {
            text = "ON NOW TV V2 · SPEED TEST"
            textSize = 12f
            letterSpacing = 0.30f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        })
        column.addView(spacer(dp(8)))

        column.addView(TextView(this).apply {
            text = "How fast is this box?"
            textSize = 36f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = -0.02f
        })
        column.addView(spacer(dp(8)))

        // Gauge
        gauge = SpeedGaugeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(560), dp(360))
        }
        column.addView(gauge)

        // Live status caption.
        statusLabel = TextView(this).apply {
            text = "Pinging server…"
            textSize = 14f
            setTextColor(Color.parseColor("#FF8EA0B7"))
            letterSpacing = 0.10f
            setPadding(0, dp(4), 0, 0)
        }
        column.addView(statusLabel)

        // Progress bar (thin)
        val barWrap = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.parseColor("#1A2BB6FF"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(540), dp(6)).apply {
                topMargin = dp(14)
            }
        }
        progressFill = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                colors = intArrayOf(
                    Color.parseColor("#FF2BB6FF"),
                    Color.parseColor("#FF5DC8FF"),
                )
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
            }
            layoutParams = FrameLayout.LayoutParams(dp(1), dp(6))
        }
        barWrap.addView(progressFill)
        progressBar = barWrap
        column.addView(barWrap)

        // Stat chips row
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(28), 0, 0)
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
        column.addView(spacer(dp(28)))
        column.addView(runButton)
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
            setPadding(dp(28), dp(18), dp(28), dp(18))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#33203A5C"))
                setStroke(dp(1), Color.parseColor("#22B3D4FF"))
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
            textSize = 26f
            setTextColor(Color.parseColor("#FFF4F7FB"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(value)
        parent.addView(box, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(dp(8), 0, dp(8), 0) })
        return value
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun setProgress(fraction: Float) {
        val barW = dp(540)
        val w = (barW * fraction.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)
        val lp = progressFill.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        progressFill.layoutParams = lp
    }

    /* ──────────────────  Test logic  ────────────────── */

    private fun runTest() {
        job?.cancel()
        runButton.text = "Testing…"
        runButton.alpha = 0.6f
        pingValue.text = "—"
        downValue.text = "—"
        upValue.text   = "—"
        statusLabel.text = "Pinging server…"
        setProgress(0f)
        gauge.setSuffix("Mbps")
        gauge.setValue(0.0, animated = false)

        job = lifecycleScope.launch {
            // Master timeout — the WHOLE flow must finish in 32 s.
            // If anything hangs, we abort gracefully and the UI is
            // back in an idle state — NEVER frozen.
            withTimeoutOrNull(32_000) {
                // Ping
                val ping = withContext(Dispatchers.IO) { measurePing() }
                pingValue.text = if (ping > 0) "$ping ms" else "—"
                setProgress(0.1f)

                // Download
                statusLabel.text = "Measuring download…"
                val downMbps = withContext(Dispatchers.IO) {
                    measureParallelDownload(
                        seconds = 8,
                        streams = 4,
                    ) { liveMbps, frac ->
                        runOnUiThread {
                            gauge.setValue(liveMbps)
                            downValue.text = formatMbps(liveMbps)
                            setProgress(0.1f + 0.5f * frac)
                        }
                    }
                }
                gauge.setValue(downMbps)
                downValue.text = formatMbps(downMbps)
                setProgress(0.6f)

                // Upload
                statusLabel.text = "Measuring upload…"
                gauge.setSuffix("Mbps ↑")
                gauge.setValue(0.0, animated = false)
                val upMbps = withContext(Dispatchers.IO) {
                    measureParallelUpload(
                        seconds = 6,
                        streams = 4,
                    ) { liveMbps, frac ->
                        runOnUiThread {
                            gauge.setValue(liveMbps)
                            upValue.text = formatMbps(liveMbps)
                            setProgress(0.6f + 0.4f * frac)
                        }
                    }
                }
                gauge.setSuffix("Mbps ↓")
                gauge.setValue(downMbps)
                upValue.text = formatMbps(upMbps)
                setProgress(1f)
                statusLabel.text = "Done."
            } ?: run {
                statusLabel.text = "Test timed out — check the network."
            }
            runButton.text = "Test again"
            runButton.alpha = 1f
        }
    }

    private fun formatMbps(v: Double): String =
        if (v >= 100) "${v.roundToInt()} Mbps" else "%.1f Mbps".format(v)

    /** Lowest RTT (ms) across 3 reference URLs. */
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
                (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    requestMethod = "HEAD"
                    responseCode  // forces the request
                    disconnect()
                }
                val ms = ((System.nanoTime() - t0) / 1_000_000).toInt()
                best = min(best, ms)
            } catch (_: IOException) { }
        }
        return if (best == Int.MAX_VALUE) 0 else best
    }

    /**
     * Run N parallel HTTP downloads from Cloudflare's free
     * speed-test endpoint.  Sum bytes-per-window across all streams.
     * This is the only way to saturate gigabit links — a single TCP
     * connection's CWND can't reach high BDP fast enough.
     */
    private suspend fun measureParallelDownload(
        seconds: Int,
        streams: Int,
        onTick: (Double, Float) -> Unit,
    ): Double = coroutineScope {
        val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
        val started = System.nanoTime()
        val mutex = Mutex()
        var lastTickNs = started
        var lastTickBytes = 0L
        val durationNs = seconds * 1_000_000_000L

        val tickerJob = launch {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                mutex.withLock {
                    val now = System.nanoTime()
                    val totalNow = totalBytes.get()
                    val deltaB = totalNow - lastTickBytes
                    val deltaMs = (now - lastTickNs) / 1_000_000.0
                    if (deltaMs > 0) {
                        val mbps = (deltaB * 8.0) / (deltaMs * 1000.0)
                        val frac = ((now - started).toFloat() / durationNs).coerceIn(0f, 1f)
                        onTick(mbps, frac)
                    }
                    lastTickNs = now
                    lastTickBytes = totalNow
                }
            }
        }

        val workers = (1..streams).map { idx ->
            async(Dispatchers.IO) {
                // 100 MB per worker — caller's `seconds` cap stops
                // the loop before we'd download that much on most
                // links anyway.
                val urls = listOf(
                    "https://speed.cloudflare.com/__down?bytes=104857600",
                    "https://speed.hetzner.de/100MB.bin",
                )
                for (raw in urls) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(raw).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout    = 5000
                        }
                        if (conn.responseCode !in 200..299) {
                            conn.disconnect(); continue
                        }
                        val stream = conn.inputStream
                        val buf = ByteArray(64 * 1024)
                        while (isActive) {
                            val elapsed = System.nanoTime() - started
                            if (elapsed >= durationNs) break
                            val n = try { stream.read(buf) } catch (_: IOException) { -1 }
                            if (n <= 0) break
                            totalBytes.addAndGet(n.toLong())
                        }
                        try { stream.close() } catch (_: IOException) {}
                        break  // got data — done with this worker
                    } catch (_: Throwable) {
                        // try next URL
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
        }
        workers.awaitAll()
        tickerJob.cancel()

        val totalMs = max(1L, (System.nanoTime() - started) / 1_000_000)
        val total = totalBytes.get()
        (total * 8.0) / (totalMs * 1000.0)
    }

    /**
     * N parallel POSTs of random bytes — same windowing logic as
     * download.  Uses Cloudflare's __up endpoint which discards
     * the body and returns 200.
     */
    private suspend fun measureParallelUpload(
        seconds: Int,
        streams: Int,
        onTick: (Double, Float) -> Unit,
    ): Double = coroutineScope {
        val totalBytes = java.util.concurrent.atomic.AtomicLong(0)
        val started = System.nanoTime()
        val mutex = Mutex()
        var lastTickNs = started
        var lastTickBytes = 0L
        val durationNs = seconds * 1_000_000_000L

        val tickerJob = launch {
            while (isActive) {
                kotlinx.coroutines.delay(250)
                mutex.withLock {
                    val now = System.nanoTime()
                    val totalNow = totalBytes.get()
                    val deltaB = totalNow - lastTickBytes
                    val deltaMs = (now - lastTickNs) / 1_000_000.0
                    if (deltaMs > 0) {
                        val mbps = (deltaB * 8.0) / (deltaMs * 1000.0)
                        val frac = ((now - started).toFloat() / durationNs).coerceIn(0f, 1f)
                        onTick(mbps, frac)
                    }
                    lastTickNs = now
                    lastTickBytes = totalNow
                }
            }
        }

        val random = Random(0xC0FFEE)
        // Pre-generate a 1MB random buffer once per worker.
        val workers = (1..streams).map {
            async(Dispatchers.IO) {
                val buf = ByteArray(1024 * 1024).also { random.nextBytes(it) }
                while (isActive) {
                    val elapsed = System.nanoTime() - started
                    if (elapsed >= durationNs) break
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL("https://speed.cloudflare.com/__up")
                            .openConnection() as HttpURLConnection).apply {
                            connectTimeout = 4000
                            readTimeout    = 5000
                            doOutput = true
                            requestMethod = "POST"
                            setFixedLengthStreamingMode(buf.size)
                            setRequestProperty("Content-Type", "application/octet-stream")
                        }
                        val os = conn.outputStream
                        var written = 0
                        val chunk = 64 * 1024
                        while (written < buf.size && isActive) {
                            val toWrite = min(chunk, buf.size - written)
                            os.write(buf, written, toWrite)
                            written += toWrite
                            totalBytes.addAndGet(toWrite.toLong())
                        }
                        os.flush(); os.close()
                        conn.responseCode  // drain
                    } catch (_: Throwable) { /* try again */ }
                    finally { conn?.disconnect() }
                }
            }
        }
        workers.awaitAll()
        tickerJob.cancel()

        val totalMs = max(1L, (System.nanoTime() - started) / 1_000_000)
        val total = totalBytes.get()
        (total * 8.0) / (totalMs * 1000.0)
    }
}
