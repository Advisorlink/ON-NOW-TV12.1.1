package tv.onnowtv.livetv

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnowtv.livetv.data.BundleCache
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.data.XtreamRepository
import java.net.HttpURLConnection
import java.net.URL

/**
 * Boot loader for the Live TV app.
 *
 * **Disk-cache fast path (the common case):**
 *   If `BundleCache` already has a bundle on disk we parse it
 *   IMMEDIATELY (no network, no waiting) and hand off to
 *   EpgActivity before the loader screen even paints.  A
 *   background refresh fires from `LiveTVApp` to keep the cache
 *   fresh, so the next launch already has the latest data.
 *
 * **First-ever boot (no cache):**
 *   Show the descriptive loader.  Wait for the backend to report
 *   `epg_priority_ready` (UK / US / AU / Kayo) AND a minimum of
 *   60 seconds.  Persist the bundle to disk on success so the
 *   next launch skips the loader.
 *
 * The loader has three "alive" indicators so it never feels stuck
 * to the user (~5 minute first-boot wait):
 *   1) Three cyan dots that pulse from left to right ("typing").
 *   2) Counters that smoothly animate up as data flows in.
 *   3) A rotating "Did you know…" tip that swaps every 4s.
 *   4) A subtle pulsing glow on the V2 wordmark.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var headline: TextView
    private lateinit var substatus: TextView
    private lateinit var statusCounters: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: TextView
    private lateinit var tip: TextView
    private lateinit var brandV2: TextView
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var dot3: View
    private lateinit var countdown: TextView

    private val minHoldMs = 18_000L
    private val pollIntervalMs = 1_500L
    private val maxHoldMs = 5 * 60_000L
    /** Wall-clock cap shown to the user — "first load can take up
     *  to 5 minutes".  The countdown ticker uses this. */
    private val firstLoadBudgetMs = 5 * 60_000L

    private var bundleKick: Job? = null
    @Volatile private var bundleResult: XtreamBundle? = null
    @Volatile private var bundleJson: String? = null
    @Volatile private var bundleError: String? = null

    // Smoothly-animated counter values so the visible number creeps
    // up to the latest backend value instead of jumping in big chunks.
    private var animatedChannels = 0
    private var animatedPriorityDone = 0
    private var lastPriorityTotal = 0
    private var counterAnimator: ValueAnimator? = null
    private val tipHandler = Handler(Looper.getMainLooper())
    private val dotsHandler = Handler(Looper.getMainLooper())
    private val brandHandler = Handler(Looper.getMainLooper())
    private val countdownHandler = Handler(Looper.getMainLooper())

    // Rotating tips — swap every TIP_INTERVAL_MS so the user has
    // something to read during the long first-boot wait.
    private val TIPS = listOf(
        "TIP — Press OK on any guide row to set a reminder.",
        "TIP — Reminders glow YELLOW until they fire.",
        "TIP — The left rail filters channels by country and genre.",
        "TIP — The Search icon finds channels AND programmes by name.",
        "TIP — The next boot is INSTANT — your guide is cached on disk.",
        "TIP — We're pre-warming UK, US, AU and Kayo EPG first so the channels you watch are ready.",
        "TIP — 12,000+ channels, refreshed automatically in the background.",
        "TIP — D-pad UP/DOWN switches channels while you're watching.",
    )
    private val TIP_INTERVAL_MS = 7_500L
    private var tipIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FAST PATH — try the disk cache before we even inflate the loader.
        if (BundleCache.exists(this)) {
            try {
                val json = BundleCache.loadJson(this)
                if (!json.isNullOrBlank()) {
                    val bundle = XtreamRepository.parseBundleJson(json)
                    if (bundle.channels.isNotEmpty()) {
                        BundleHolder.current = bundle
                        Log.i("MainActivity", "fast-path: loaded ${bundle.channels.size} channels / ${bundle.epg.size} epg buckets from disk (age=${BundleCache.ageMs(this) / 1000}s)")
                        scheduleBackgroundRefresh()
                        startActivity(Intent(this, EpgActivity::class.java))
                        overridePendingTransition(0, 0)
                        finish()
                        return
                    }
                }
            } catch (t: Throwable) {
                Log.w("MainActivity", "fast-path failed, falling back to loader: ${t.message}")
            }
        }

        // SLOW PATH — first install (or corrupted cache).  Show loader.
        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        headline       = findViewById(R.id.loader_headline)
        substatus      = findViewById(R.id.loader_substatus)
        statusCounters = findViewById(R.id.loader_counters)
        progress       = findViewById(R.id.loader_progress)
        retry          = findViewById(R.id.loader_retry)
        tip            = findViewById(R.id.loader_tip)
        brandV2        = findViewById(R.id.loader_brand_v2)
        dot1           = findViewById(R.id.loader_dot_1)
        dot2           = findViewById(R.id.loader_dot_2)
        dot3           = findViewById(R.id.loader_dot_3)
        countdown      = findViewById(R.id.loader_countdown)

        startDotsAnimation()
        startTipsRotation()
        startBrandPulse()
        startCountdown()

        startLoad()
    }

    /**
     * Render a "5:00" → "0:00" countdown ticking once per second
     * in the loader footer.  Once it hits zero we hold at "0:00" —
     * the loader's safety hatch will have already kicked in by then.
     */
    private fun startCountdown() {
        val deadline = SystemClock.elapsedRealtime() + firstLoadBudgetMs
        countdownHandler.post(object : Runnable {
            override fun run() {
                val remainingMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                val mins = remainingMs / 60_000L
                val secs = (remainingMs % 60_000L) / 1_000L
                countdown.text = "%d:%02d".format(mins, secs)
                if (remainingMs > 0) {
                    countdownHandler.postDelayed(this, 1_000L)
                }
            }
        })
    }

    /**
     * Triggers a bundle refresh after the fast-path handoff.  We
     * detach from this Activity's lifecycle by handing the work to
     * EpgActivity via a global flag — the next time EpgActivity's
     * onResume fires it kicks off the refresh.
     */
    private fun scheduleBackgroundRefresh() {
        BundleHolder.needsBackgroundRefresh = true
    }

    /* ───────────── Loader "alive" indicators ───────────── */

    /**
     * Pulse the three loader dots from left to right.  Each dot
     * scales 1→1.4→1 with a slight alpha shift on the same 900 ms
     * cycle, staggered 150 ms apart.  Looks like typing dots that
     * never stop — clear proof to the user we're still working.
     */
    private fun startDotsAnimation() {
        val dots = listOf(dot1, dot2, dot3)
        val cycleMs = 900L
        val staggerMs = 150L
        dots.forEachIndexed { i, dot ->
            dot.alpha = 0.35f
            val pulse = Runnable {
                dot.animate()
                    .scaleX(1.35f).scaleY(1.35f).alpha(1f)
                    .setDuration(cycleMs / 2)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        dot.animate()
                            .scaleX(1f).scaleY(1f).alpha(0.35f)
                            .setDuration(cycleMs / 2)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                    .start()
            }
            val ticker = object : Runnable {
                override fun run() {
                    pulse.run()
                    dotsHandler.postDelayed(this, cycleMs)
                }
            }
            dotsHandler.postDelayed(ticker, i * staggerMs)
        }
    }

    /** Subtle 2-second pulse on the red V2 glow so the brand
     *  feels alive instead of static. */
    private fun startBrandPulse() {
        val cycleMs = 2_000L
        val ticker = object : Runnable {
            override fun run() {
                brandV2.animate()
                    .scaleX(1.04f).scaleY(1.04f)
                    .setDuration(cycleMs / 2)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        brandV2.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(cycleMs / 2)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                    .start()
                brandHandler.postDelayed(this, cycleMs)
            }
        }
        brandHandler.post(ticker)
    }

    /** Rotate through helpful tips every 4 s with a cross-fade. */
    private fun startTipsRotation() {
        // Seed the first tip immediately so the row isn't blank.
        tip.text = TIPS[tipIndex]
        tip.alpha = 0f
        tip.animate().alpha(1f).setDuration(400).start()
        val ticker = object : Runnable {
            override fun run() {
                tipIndex = (tipIndex + 1) % TIPS.size
                tip.animate()
                    .alpha(0f).setDuration(280)
                    .withEndAction {
                        tip.text = TIPS[tipIndex]
                        tip.animate().alpha(1f).setDuration(280).start()
                    }
                    .start()
                tipHandler.postDelayed(this, TIP_INTERVAL_MS)
            }
        }
        tipHandler.postDelayed(ticker, TIP_INTERVAL_MS)
    }

    /* ───────────── Loader state machine ───────────── */

    private fun startLoad() {
        retry.visibility = View.GONE
        headline.text = "Connecting…"
        substatus.text = "Reaching the backend…"
        statusCounters.text = ""
        progress.progress = 0
        bundleResult = null
        bundleJson = null
        bundleError = null
        bundleKick?.cancel()
        bundleKick = null

        lifecycleScope.launch {
            try {
                runLoader()
            } catch (t: Throwable) {
                Log.e("MainActivity", "loader failed", t)
                headline.text = "Couldn't load guide"
                substatus.text = t.message ?: t::class.java.simpleName
                retry.visibility = View.VISIBLE
                retry.setOnClickListener { startLoad() }
            }
        }
    }

    private suspend fun runLoader() {
        val started = SystemClock.elapsedRealtime()
        headline.text = "Loading guide…"
        substatus.text = "Downloading bundle…"
        progress.progress = 60

        // Kick off the bundle fetch as JSON so we can both parse
        // it AND save the raw text to BundleCache for next time.
        bundleKick = lifecycleScope.async(Dispatchers.IO) {
            try {
                val text = XtreamRepository.fetchBundleJson()
                bundleJson = text
                val b = XtreamRepository.parseBundleJson(text)
                bundleResult = b
                BundleCache.saveJson(applicationContext, text)
                Log.i("MainActivity", "bundle: ${b.channels.size} channels, ${b.epg.size} epg buckets (cached)")
            } catch (t: Throwable) {
                bundleError = t.message ?: t::class.java.simpleName
                Log.w("MainActivity", "bundle fetch failed: $bundleError")
            }
        }

        var lastMeta: Meta? = null
        var lastMetaErrorAt = 0L

        while (lifecycleScope.isActive) {
            val meta = try {
                fetchMeta()
            } catch (t: Throwable) {
                lastMetaErrorAt = SystemClock.elapsedRealtime()
                Log.w("MainActivity", "meta fetch failed: ${t.message}")
                null
            }
            if (meta != null) lastMeta = meta

            val elapsed = SystemClock.elapsedRealtime() - started
            applyMeta(lastMeta, elapsed, lastMetaErrorAt)

            // EXIT — most-important conditions first:
            //   1) Bundle has arrived AND we've held for the minimum.
            //   2) Popular EPG ready AND minimum hold met.
            //   3) Safety hatch: any channels visible after maxHoldMs.
            val haveBundle = bundleResult != null
            val priorityReady = (lastMeta?.priorityReady == true) && (lastMeta?.channelsCount ?: 0) > 0
            if (haveBundle && elapsed >= minHoldMs) break
            if (priorityReady && elapsed >= minHoldMs) break

            val haveChannels = (lastMeta?.channelsCount ?: 0) > 0 || haveBundle
            if (elapsed >= maxHoldMs && haveChannels) {
                Log.w("MainActivity", "safety hatch — entering EPG")
                break
            }
            delay(pollIntervalMs)
        }

        headline.text = "Finalising guide…"
        substatus.text = "Almost there…"
        progress.progress = 970

        val bundle = bundleResult ?: run {
            val text = XtreamRepository.fetchBundleJson()
            BundleCache.saveJson(applicationContext, text)
            XtreamRepository.parseBundleJson(text)
        }
        BundleHolder.current = bundle
        progress.progress = 1000

        startActivity(Intent(this@MainActivity, EpgActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun applyMeta(meta: Meta?, elapsedMs: Long, lastMetaErrorAt: Long) {
        if (meta == null) {
            headline.text = "Connecting to backend…"
            substatus.text = if (lastMetaErrorAt > 0) "Server unreachable — retrying…" else "Polling backend…"
            statusCounters.text = ""
            progress.progress = 40
            return
        }

        // Smoothly animate the counters so the numbers feel ALIVE
        // (creeping up) instead of jumping every 1.5 s poll.
        animateCounterTo(meta.channelsCount, meta.priorityDone, meta.priorityTotal)
        lastPriorityTotal = meta.priorityTotal

        when {
            meta.channelsCount == 0 -> {
                headline.text = "Loading channels…"
                substatus.text = "Connecting to provider…"
                val secs = (elapsedMs / 1000).toInt()
                progress.progress = (60 + (secs * 3)).coerceAtMost(140)
            }
            !meta.priorityReady && meta.priorityTotal > 0 -> {
                headline.text = "Warming the guide…"
                substatus.text = "Loading UK · US · AU · Kayo channels"
                val ratio = meta.priorityDone.toFloat() / meta.priorityTotal.toFloat()
                progress.progress = (150 + (ratio * 700).toInt()).coerceIn(150, 850)
            }
            !meta.priorityReady && meta.priorityTotal == 0 -> {
                headline.text = "Loading EPG data…"
                substatus.text = "Parsing programme guide…"
                progress.progress = 220
            }
            else -> {
                val pct = (elapsedMs.toFloat() / minHoldMs.toFloat()).coerceIn(0f, 1f)
                headline.text = "Almost ready…"
                val secsLeft = ((minHoldMs - elapsedMs).coerceAtLeast(0L) / 1000L).toInt()
                substatus.text = if (secsLeft > 0) "Finalising in ${secsLeft}s" else "Wrapping up…"
                progress.progress = (850 + (pct * 100).toInt()).coerceIn(850, 950)
            }
        }
    }

    /**
     * Smoothly tween the visible channel + EPG counters from their
     * current values to the latest backend values over 1.2 s.  We
     * cancel any in-flight animator first so a fresh poll re-targets
     * the latest data without weird mid-flight pauses.
     */
    private fun animateCounterTo(targetChannels: Int, targetPriorityDone: Int, priorityTotal: Int) {
        counterAnimator?.cancel()
        val startChannels = animatedChannels
        val startPriority = animatedPriorityDone
        val deltaChannels = targetChannels - startChannels
        val deltaPriority = targetPriorityDone - startPriority
        if (deltaChannels == 0 && deltaPriority == 0) {
            renderCounters(startChannels, startPriority, priorityTotal)
            return
        }
        counterAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                animatedChannels    = (startChannels + (deltaChannels * f)).toInt()
                animatedPriorityDone = (startPriority + (deltaPriority * f)).toInt()
                renderCounters(animatedChannels, animatedPriorityDone, priorityTotal)
            }
            start()
        }
    }

    private fun renderCounters(channels: Int, priorityDone: Int, priorityTotal: Int) {
        val parts = mutableListOf<String>()
        if (channels > 0) parts.add("${fmt(channels)} channels loaded")
        if (priorityTotal > 0) {
            val done = priorityDone.coerceAtMost(priorityTotal)
            parts.add("${fmt(done)} / ${fmt(priorityTotal)} popular EPG ready")
        }
        statusCounters.text = parts.joinToString("  ·  ")
    }

    private data class Meta(
        val channelsCount: Int,
        val categoriesCount: Int,
        val priorityReady: Boolean,
        val priorityTotal: Int,
        val priorityDone: Int,
        val warmTotal: Int,
        val warmDone: Int,
        val phase: String,
    )

    private suspend fun fetchMeta(): Meta = withContext(Dispatchers.IO) {
        val url = URL(XtreamRepository.BACKEND_BASE.trimEnd('/') + "/api/xtream/instant-bundle/meta")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("Meta HTTP $code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(text)
            Meta(
                channelsCount   = obj.optInt("channels_count", 0),
                categoriesCount = obj.optInt("categories_count", 0),
                priorityReady   = obj.optBoolean("epg_priority_ready", false),
                priorityTotal   = obj.optInt("epg_priority_total", 0),
                priorityDone    = obj.optInt("epg_priority_done", 0),
                warmTotal       = obj.optInt("epg_warm_total", 0),
                warmDone        = obj.optInt("epg_warm_done", 0),
                phase           = obj.optString("epg_phase", "boot"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fmt(n: Int): String = "%,d".format(n)

    override fun onDestroy() {
        bundleKick?.cancel()
        tipHandler.removeCallbacksAndMessages(null)
        dotsHandler.removeCallbacksAndMessages(null)
        brandHandler.removeCallbacksAndMessages(null)
        countdownHandler.removeCallbacksAndMessages(null)
        counterAnimator?.cancel()
        super.onDestroy()
    }
}

/** Process-scoped holder for the freshly-fetched bundle. */
object BundleHolder {
    @Volatile var current: tv.onnowtv.livetv.data.XtreamBundle? = null
    /** Set by MainActivity when it took the fast disk-cache path
     *  and EpgActivity should refresh the bundle in the background. */
    @Volatile var needsBackgroundRefresh: Boolean = false
}
