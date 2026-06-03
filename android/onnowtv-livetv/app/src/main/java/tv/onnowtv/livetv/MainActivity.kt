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

    private var bundleKick: Job? = null
    @Volatile private var bundleResult: XtreamBundle? = null
    @Volatile private var bundleJson: String? = null
    @Volatile private var bundleError: String? = null

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

        startLoad()
    }

    /**
     * Triggers a bundle refresh after the fast-path handoff.  We
     * detach from this Activity's lifecycle by handing the work to
     * EpgActivity via a global flag — the next time EpgActivity's
     * onResume fires it kicks off the refresh.  Simpler than
     * GlobalScope and survives the handoff cleanly.
     */
    private fun scheduleBackgroundRefresh() {
        BundleHolder.needsBackgroundRefresh = true
    }

    private fun startLoad() {
        retry.visibility = View.GONE
        headline.text = "Connecting…"
        substatus.text = ""
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

            val priorityReady = (lastMeta?.priorityReady == true) && (lastMeta?.channelsCount ?: 0) > 0
            if (priorityReady && elapsed >= minHoldMs) break

            val haveChannels = (lastMeta?.channelsCount ?: 0) > 0 || bundleResult != null
            if (elapsed >= maxHoldMs && haveChannels) {
                Log.w("MainActivity", "safety hatch — entering EPG")
                break
            }
            delay(pollIntervalMs)
        }

        headline.text = "Finalising guide…"
        substatus.text = "Downloading bundle…"
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

/** Process-scoped holder for the freshly-fetched bundle. */
object BundleHolder {
    @Volatile var current: tv.onnowtv.livetv.data.XtreamBundle? = null
    /** Set by MainActivity when it took the fast disk-cache path
     *  and EpgActivity should refresh the bundle in the background. */
    @Volatile var needsBackgroundRefresh: Boolean = false
}
