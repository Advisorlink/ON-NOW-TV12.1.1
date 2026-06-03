package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
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
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.data.XtreamRepository
import java.net.HttpURLConnection
import java.net.URL

/**
 * Boot loader for the Live TV app.
 *
 * Behaviour:
 *   - Fetch the bundle FIRST (the backend gz cache is fast).
 *   - If the bundle already ships with >= FAST_SKIP_EPG_BUCKETS
 *     EPG buckets, go straight to EpgActivity — no minimum hold.
 *     This is the common case on every launch after the very
 *     first one, so the user doesn't wait 60 s on every boot.
 *   - Otherwise: enter a polling loop, hold for AT LEAST 60 s
 *     AND until popular regions' EPG (`epg_priority_ready`) is
 *     populated.  Show clean "12,094 channels · 1,820 EPG ready"
 *     counters and a determinate progress bar.
 *   - 5-minute safety hatch.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var headline: TextView
    private lateinit var substatus: TextView
    private lateinit var statusCounters: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: TextView

    private val minHoldMs = 60_000L
    private val pollIntervalMs = 1_500L
    private val maxHoldMs = 5 * 60_000L
    /** If the initial bundle already has at least this many EPG
     *  buckets we skip the loader entirely. */
    private val fastSkipEpgBuckets = 200

    private var bundleKick: Job? = null
    @Volatile private var bundleResult: XtreamBundle? = null
    @Volatile private var bundleError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        headline       = findViewById(R.id.loader_headline)
        substatus      = findViewById(R.id.loader_substatus)
        statusCounters = findViewById(R.id.loader_counters)
        progress       = findViewById(R.id.loader_progress)
        retry          = findViewById(R.id.loader_retry)

        startLoad()
    }

    private fun startLoad() {
        retry.visibility = View.GONE
        headline.text = "Connecting…"
        substatus.text = ""
        statusCounters.text = ""
        progress.progress = 0
        bundleResult = null
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
        // STEP 1 — kick off the bundle fetch immediately.  The
        // backend serves a pre-gzipped cached payload (~250 KB),
        // so this typically returns in well under a second.
        val started = SystemClock.elapsedRealtime()
        headline.text = "Loading guide…"
        substatus.text = "Downloading bundle…"
        progress.progress = 60

        bundleKick = lifecycleScope.async(Dispatchers.IO) {
            try {
                val b = XtreamRepository.fetchBundle()
                bundleResult = b
                Log.i("MainActivity", "bundle: ${b.channels.size} channels, ${b.epg.size} epg buckets")
            } catch (t: Throwable) {
                bundleError = t.message ?: t::class.java.simpleName
                Log.w("MainActivity", "bundle fetch failed: $bundleError")
            }
        }

        // STEP 2 — wait for either the bundle to land OR ~4s,
        // whichever comes first.  This lets us decide whether to
        // fast-skip the loader.
        val waitDeadline = SystemClock.elapsedRealtime() + 4_000L
        while (lifecycleScope.isActive
            && bundleResult == null
            && bundleError == null
            && SystemClock.elapsedRealtime() < waitDeadline) {
            delay(150L)
        }

        val initial = bundleResult
        if (initial != null && initial.channels.isNotEmpty() && initial.epg.size >= fastSkipEpgBuckets) {
            // Happy path: already-warm backend.  Skip the loader.
            BundleHolder.current = initial
            progress.progress = 1000
            handoff()
            return
        }

        // STEP 3 — slow path: poll meta until popular EPG is ready
        // AND minimum hold has elapsed.
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

            val priorityReady = (lastMeta?.priorityReady == true) && (lastMeta?.channelsCount ?: 0) > 0
            if (priorityReady && elapsed >= minHoldMs) break

            val haveChannels = (lastMeta?.channelsCount ?: 0) > 0 || bundleResult != null
            if (elapsed >= maxHoldMs && haveChannels) {
                Log.w("MainActivity", "safety hatch — entering EPG")
                break
            }
            delay(pollIntervalMs)
        }

        // STEP 4 — make sure we have a bundle in memory.
        val bundle = bundleResult ?: run {
            headline.text = "Finalising guide…"
            substatus.text = "Downloading bundle…"
            progress.progress = 970
            XtreamRepository.fetchBundle()
        }
        BundleHolder.current = bundle
        progress.progress = 1000
        handoff()
    }

    private fun handoff() {
        startActivity(Intent(this@MainActivity, EpgActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * Render the current meta payload.
     * Drops the poll counter — user feedback was that it felt
     * technical and noisy.  Shows clean channel + EPG counts.
     */
    private fun applyMeta(meta: Meta?, elapsedMs: Long, lastMetaErrorAt: Long) {
        if (meta == null) {
            headline.text = "Connecting to backend…"
            substatus.text = if (lastMetaErrorAt > 0) "Server unreachable — retrying…" else "Polling backend…"
            statusCounters.text = ""
            progress.progress = 40
            return
        }

        // Clean visual counters: channels loaded + EPG channels ready.
        val parts = mutableListOf<String>()
        parts.add("${fmt(meta.channelsCount)} channels loaded")
        if (meta.priorityTotal > 0) {
            val done = meta.priorityDone.coerceAtMost(meta.priorityTotal)
            parts.add("${fmt(done)} / ${fmt(meta.priorityTotal)} popular EPG ready")
        } else if (meta.warmTotal > 0) {
            parts.add("${fmt(meta.warmDone)} / ${fmt(meta.warmTotal)} EPG channels ready")
        }
        statusCounters.text = parts.joinToString("  ·  ")

        when {
            meta.channelsCount == 0 -> {
                headline.text = "Loading channels…"
                substatus.text = "Connecting to provider…"
                val secs = (elapsedMs / 1000).toInt()
                progress.progress = (60 + (secs * 3)).coerceAtMost(140)
            }
            !meta.priorityReady && meta.priorityTotal > 0 -> {
                headline.text = "Loading the guide…"
                substatus.text = "Warming UK · US · AU · Kayo channels"
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
                substatus.text = if (secsLeft > 0) "Finalising in ${secsLeft}s" else "Loading EPG…"
                progress.progress = (850 + (pct * 100).toInt()).coerceIn(850, 950)
            }
        }
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
        super.onDestroy()
    }
}

/** Process-scoped holder for the freshly-fetched bundle.  Avoids
 *  serialising channels + EPG through an Intent extra (which would
 *  blow the Binder 1 MB transaction limit). */
object BundleHolder {
    @Volatile var current: tv.onnowtv.livetv.data.XtreamBundle? = null
}
