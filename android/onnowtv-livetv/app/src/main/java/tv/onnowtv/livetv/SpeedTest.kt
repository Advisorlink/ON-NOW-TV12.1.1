package tv.onnowtv.livetv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Internet speed test (ping + download) against Cloudflare's public speed-test edge. */
object SpeedTest {
    private const val CF = "https://speed.cloudflare.com/__down"
    private const val DURATION_MS = 8_000L
    private const val STREAMS = 4
    private const val CHUNK = 25_000_000L
    private val main = Handler(Looper.getMainLooper())
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }

    fun verdict(mbps: Double): Pair<String, Int> = when {
        mbps >= 100 -> "Your internet is very healthy" to 0xFF7CF1A1.toInt()
        mbps >= 20 -> "Your internet is healthy" to 0xFF5DC8FF.toInt()
        mbps >= 10 -> "Your internet is a bit slow" to 0xFFFFD36B.toInt()
        else -> "Your internet is struggling" to 0xFFFF8E8E.toInt()
    }

    private val tiers = listOf("720p" to 10.0, "1080p" to 20.0, "4K" to 100.0)

    private fun pingLabel(ms: Long) = when {
        ms <= 30 -> "Excellent"; ms <= 70 -> "Good"; ms <= 150 -> "Fair"; else -> "High"
    }

    fun show(activity: Activity) {
        if (activity.isFinishing) return
        fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.resources.displayMetrics).toInt()
        fun text(s: CharSequence, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = s; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val blue = 0xFF5DC8FF.toInt()
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36f), dp(26f), dp(36f), dp(24f))
            background = GradientDrawable().apply { setColor(0xF2121722.toInt()); cornerRadius = dp(22f).toFloat(); setStroke(dp(1f), 0x33FFFFFF) }
        }
        card.addView(text("SPEED TEST", 12f, blue, bold = true).apply { letterSpacing = 0.18f })
        val number = text("—", 54f, Color.WHITE, bold = true).apply { setPadding(0, dp(10f), 0, 0); tag = "speed-mbps" }
        card.addView(number)
        card.addView(text("MBPS DOWNLOAD", 12f, 0xFF9DA5B5.toInt(), bold = true).apply { letterSpacing = 0.18f })
        val status = text("Measuring ping…", 19f, blue, bold = true).apply { setPadding(0, dp(14f), 0, 0); tag = "speed-status" }
        card.addView(status)
        val ping = text("Ping —", 15f, 0xFF9DA5B5.toInt()).apply { setPadding(0, dp(6f), 0, 0); tag = "speed-ping" }
        card.addView(ping)
        val tierRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(18f), 0, 0) }
        val tierViews = tiers.map { (label, min) ->
            TextView(activity).apply {
                text = "   $label  ${min.toInt()}+"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(dp(14f), dp(7f), dp(16f), dp(7f)); tag = "speed-tier-$label"
            }
        }
        fun paintTiers(mbps: Double?) {
            tierViews.forEachIndexed { i, tv ->
                val ok = mbps?.let { it >= tiers[i].second }
                val color = when (ok) { null -> 0xFF6B7384.toInt(); true -> 0xFF7CF1A1.toInt(); else -> 0xFFFF8E8E.toInt() }
                val mark = when (ok) { null -> "○"; true -> "✓"; else -> "✕" }
                tv.text = "$mark  ${tiers[i].first}  ${tiers[i].second.toInt()}+"
                tv.setTextColor(color)
                tv.background = GradientDrawable().apply { cornerRadius = dp(30f).toFloat(); setColor((color and 0x00FFFFFF) or 0x14000000); setStroke(dp(1f), (color and 0x00FFFFFF) or 0x55000000) }
            }
        }
        paintTiers(null)
        tierViews.forEachIndexed { i, tv -> tierRow.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(10f) }) }
        card.addView(tierRow)
        val track = FrameLayout(activity).apply {
            background = GradientDrawable().apply { setColor(0x22FFFFFF); cornerRadius = dp(4f).toFloat() }
            addView(View(activity).apply { background = GradientDrawable().apply { setColor(blue); cornerRadius = dp(4f).toFloat() } }, FrameLayout.LayoutParams(0, -1))
        }
        card.addView(track, LinearLayout.LayoutParams(dp(360f), dp(6f)).apply { topMargin = dp(18f) })
        fun fill(pct: Int) { track.getChildAt(0).layoutParams = FrameLayout.LayoutParams(track.width * pct.coerceIn(0, 100) / 100, -1) }

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(22f), 0, 0) }
        fun button(label: String, primary: Boolean) = TextView(activity).apply {
            text = label; textSize = 15f; isFocusable = true; isClickable = true; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24f), dp(11f), dp(24f), dp(11f))
            fun paint(f: Boolean) {
                background = GradientDrawable().apply {
                    cornerRadius = dp(30f).toFloat()
                    setColor(if (primary) (if (f) blue else 0xFF2A7FB0.toInt()) else (if (f) 0x44FFFFFF else 0x18FFFFFF))
                    if (f) setStroke(dp(2f), Color.WHITE)
                }
                setTextColor(if (primary) 0xFF06080F.toInt() else Color.WHITE)
                scaleX = if (f) 1.05f else 1f; scaleY = scaleX
            }
            paint(false); setOnFocusChangeListener { _, f -> paint(f) }
        }
        val again = button("Test again", true).apply { visibility = View.GONE; tag = "speed-again" }
        val close = button("Cancel", false).apply { tag = "speed-close" }
        row.addView(again, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14f) })
        row.addView(close)
        card.addView(row)
        card.addView(text("Tested against Cloudflare's global speed-test network", 11f, 0xFF6B7384.toInt()).apply { setPadding(0, dp(16f), 0, 0) })

        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(card)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOwnerActivity(activity)
        }
        var current = AtomicBoolean(false)
        fun start() {
            current.set(true)
            val flag = AtomicBoolean(false)
            current = flag
            number.text = "—"; status.text = "Measuring ping…"; status.setTextColor(blue); ping.text = "Ping —"
            paintTiers(null); again.visibility = View.GONE; close.text = "Cancel"; fill(5)
            run(flag, onPing = { ms ->
                ping.text = "Ping $ms ms · ${pingLabel(ms)}"; status.text = "Measuring download speed…"
            }, onProgress = { mbps, pct ->
                number.text = fmt(mbps); fill(10 + pct * 90 / 100)
            }, onDone = { mbps ->
                val (label, color) = verdict(mbps)
                number.text = fmt(mbps); status.text = label; status.setTextColor(color); fill(100); paintTiers(mbps)
                again.visibility = View.VISIBLE; close.text = "Close"; close.requestFocus()
            }, onError = {
                status.text = "Could not reach the speed-test server. Check the internet connection and try again."
                status.setTextColor(0xFFFF8E8E.toInt()); fill(100)
                again.visibility = View.VISIBLE; close.text = "Close"; close.requestFocus()
            })
        }
        again.setOnClickListener { start() }
        close.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener { current.set(true) }
        dialog.show()
        close.requestFocus()
        card.post { start() }
    }

    private fun fmt(mbps: Double) = if (mbps >= 100) "%.0f".format(mbps) else "%.1f".format(mbps)

    private fun run(
        cancelled: AtomicBoolean,
        onPing: (Long) -> Unit,
        onProgress: (Double, Int) -> Unit,
        onDone: (Double) -> Unit,
        onError: () -> Unit,
    ) {
        Thread {
            try {
                val samples = ArrayList<Long>()
                repeat(8) {
                    if (cancelled.get()) return@Thread
                    val t0 = System.nanoTime()
                    http.newCall(Request.Builder().url("$CF?bytes=0&r=${System.nanoTime()}").build()).execute().use { it.body?.bytes() }
                    samples.add((System.nanoTime() - t0) / 1_000_000)
                }
                samples.sort()
                val pingMs = samples[samples.size / 4]
                main.post { if (!cancelled.get()) onPing(pingMs) }

                val total = AtomicLong(0)
                val start = System.nanoTime()
                val deadline = start + DURATION_MS * 1_000_000
                val workers = (1..STREAMS).map {
                    Thread {
                        val buf = ByteArray(64 * 1024)
                        while (!cancelled.get() && System.nanoTime() < deadline) {
                            try {
                                http.newCall(Request.Builder().url("$CF?bytes=$CHUNK&r=${System.nanoTime()}").build()).execute().use { res ->
                                    val input = res.body?.byteStream() ?: return@use
                                    while (!cancelled.get() && System.nanoTime() < deadline) {
                                        val n = input.read(buf); if (n < 0) break
                                        total.addAndGet(n.toLong())
                                    }
                                }
                            } catch (_: Exception) { if (cancelled.get()) return@Thread }
                        }
                    }.also { t -> t.start() }
                }
                var warmBytes = 0L; var warmT = 0L
                var lastT = start; var lastBytes = 0L
                while (!cancelled.get() && System.nanoTime() < deadline) {
                    Thread.sleep(200)
                    val now = System.nanoTime(); val bytes = total.get()
                    val elapsedMs = (now - start) / 1_000_000
                    if (warmT == 0L && elapsedMs >= 1000) { warmT = now; warmBytes = bytes }
                    val mbps = (bytes - lastBytes) * 8.0 / ((now - lastT) / 1e9) / 1e6
                    lastT = now; lastBytes = bytes
                    main.post { if (!cancelled.get()) onProgress(mbps, (elapsedMs * 100 / DURATION_MS).toInt()) }
                }
                workers.forEach { runCatching { it.join(1500) } }
                if (cancelled.get()) return@Thread
                val end = System.nanoTime(); val bytes = total.get()
                if (warmT == 0L) { warmT = start; warmBytes = 0 }
                val mbps = if (end > warmT) (bytes - warmBytes) * 8.0 / ((end - warmT) / 1e9) / 1e6 else 0.0
                if (bytes == 0L) main.post { if (!cancelled.get()) onError() }
                else main.post { if (!cancelled.get()) onDone(Math.round(mbps * 10) / 10.0) }
            } catch (_: Exception) {
                main.post { if (!cancelled.get()) onError() }
            }
        }.start()
    }
}
