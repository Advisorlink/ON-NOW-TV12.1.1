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
 * Behaviour (user requirement, June 2026):
 *   - Hold for AT LEAST 60 seconds.
 *   - Block until the backend reports that popular regions
 *     (UK / US / AU / Kayo) have their EPG fully warmed
 *     (`epg_priority_ready=true`).
 *   - Show a determinate progress bar + descriptive sub-status
 *     + visible elapsed-time counter + poll counter so the user
 *     can always see the app is alive.
 *   - If meta keeps reporting `channels_count == 0` after 10 s,
 *     kick off the full bundle fetch in parallel — that endpoint
 *     forces the backend to sync-refresh channels (the scheduler
 *     can take a long time on cold starts and `/meta` never
 *     triggers a refresh on its own).
 *   - 5-minute safety hatch so we never get permanently stuck.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var headline: TextView
    private lateinit var substatus: TextView
    private lateinit var elapsedTV: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: TextView

    private val minHoldMs = 60_000L
    private val pollIntervalMs = 1_500L
    private val maxHoldMs = 5 * 60_000L
    private val bundleKickMs = 10_000L

    private var pollCount = 0
    private var bundleKick: Job? = null
    @Volatile private var bundleResult: XtreamBundle? = null
    @Volatile private var bundleError: String? = null
    @Volatile private var bundleStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        headline  = findViewById(R.id.loader_headline)
        substatus = findViewById(R.id.loader_substatus)
        elapsedTV = findViewById(R.id.loader_elapsed)
        progress  = findViewById(R.id.loader_progress)
        retry     = findViewById(R.id.loader_retry)

        startLoad()
    }

    private fun startLoad() {
        retry.visibility = View.GONE
        headline.text = "Connecting…"
        substatus.text = ""
        elapsedTV.text = "Elapsed 0s · poll 0"
        progress.progress = 0
        pollCount = 0
        bundleResult = null
        bundleError = null
        bundleStartedAt = 0L
        bundleKick?.cancel()
        bundleKick = null

        lifecycleScope.launch {
            try {
                runLoaderLoop()
            } catch (t: Throwable) {
                Log.e("MainActivity", "loader failed", t)
                headline.text = "Couldn't load guide"
                substatus.text = t.message ?: t::class.java.simpleName
                retry.visibility = View.VISIBLE
                retry.setOnClickListener { startLoad() }
            }
        }
    }

    private suspend fun runLoaderLoop() {
        val started = SystemClock.elapsedRealtime()
        var lastMeta: Meta? = null
        var lastMetaErrorAt = 0L

        // Phase: keep polling until BOTH (a) priority EPG is ready
        // AND (b) the minimum hold time has elapsed.  Drive the
        // progress bar from a blend of priority-warm progress and
        // the 60s minimum-hold clock.
        while (lifecycleScope.isActive) {
            pollCount++
            val meta = try {
                fetchMeta()
            } catch (t: Throwable) {
                lastMetaErrorAt = SystemClock.elapsedRealtime()
                Log.w("MainActivity", "meta fetch failed: ${t.message}")
                null
            }
            if (meta != null) lastMeta = meta

            val elapsed = SystemClock.elapsedRealtime() - started

            // If the backend is reporting 0 channels for too long,
            // kick off the bundle endpoint directly — it triggers
            // a sync refresh on the server even though /meta won't.
            if (lastMeta?.channelsCount == 0 && elapsed >= bundleKickMs && bundleKick == null) {
                Log.i("MainActivity", "channels=0 after ${elapsed}ms — kicking /instant-bundle")
                bundleStartedAt = SystemClock.elapsedRealtime()
                bundleKick = lifecycleScope.async(Dispatchers.IO) {
                    try {
                        val b = XtreamRepository.fetchBundle()
                        bundleResult = b
                        Log.i("MainActivity", "bundle kicked: ${b.channels.size} channels")
                    } catch (t: Throwable) {
                        bundleError = t.message ?: t::class.java.simpleName
                        Log.w("MainActivity", "bundle kick failed: $bundleError")
                    }
                }
            }

            applyMeta(lastMeta, started, lastMetaErrorAt)

            val priorityReady = (lastMeta?.priorityReady == true) && (lastMeta?.channelsCount ?: 0) > 0

            // Happy path: priority ready AND minimum hold elapsed.
            if (priorityReady && elapsed >= minHoldMs) break

            // Safety hatch: 5 minutes elapsed AND we got channels somewhere.
            val haveChannels = (lastMeta?.channelsCount ?: 0) > 0 || bundleResult != null
            if (elapsed >= maxHoldMs && haveChannels) {
                Log.w("MainActivity", "safety hatch — entering EPG (priorityReady=$priorityReady)")
                break
            }
            delay(pollIntervalMs)
        }

        // Final phase: ensure we have the bundle in memory.
        headline.text = "Finalising guide…"
        substatus.text = "Downloading bundle…"
        progress.progress = 970

        val bundle = bundleResult ?: run {
            // No bundle yet — fetch synchronously here.
            XtreamRepository.fetchBundle()
        }
        BundleHolder.current = bundle
        Log.i("MainActivity", "bundle ready: ${bundle.channels.size} channels, ${bundle.epg.size} epg buckets")
        progress.progress = 1000

        startActivity(Intent(this@MainActivity, EpgActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * Render the current meta payload into the loader UI.
     * Always updates the elapsed-time counter so the user can
     * see the app isn't frozen.
     */
    private fun applyMeta(meta: Meta?, startedMs: Long, lastMetaErrorAt: Long) {
        val elapsed = SystemClock.elapsedRealtime() - startedMs
        elapsedTV.text = buildElapsedLine(elapsed)

        if (meta == null) {
            headline.text = "Connecting to backend…"
            substatus.text = if (lastMetaErrorAt > 0) {
                "Couldn't reach server — retrying… (poll #$pollCount)"
            } else {
                "Polling backend… (poll #$pollCount)"
            }
            progress.progress = 40
            return
        }

        when {
            meta.channelsCount == 0 -> {
                headline.text = "Loading channels…"
                val bundleNote = when {
                    bundleResult != null -> "bundle fetched, waiting for meta"
                    bundleError != null  -> "bundle retry failed: $bundleError"
                    bundleStartedAt > 0  -> "asking server to refresh… (${(SystemClock.elapsedRealtime() - bundleStartedAt) / 1000}s)"
                    elapsed < bundleKickMs -> "connecting to provider… (${bundleKickMs / 1000 - elapsed / 1000}s until retry)"
                    else                 -> "connecting to provider…"
                }
                substatus.text = bundleNote
                // Show creeping progress so user sees motion.
                val secs = (elapsed / 1000).toInt()
                progress.progress = (60 + (secs * 3)).coerceAtMost(140)
            }
            !meta.priorityReady && meta.priorityTotal > 0 -> {
                headline.text = "Warming popular regions…"
                val done = meta.priorityDone.coerceAtMost(meta.priorityTotal)
                substatus.text = "${fmt(meta.channelsCount)} channels  ·  $done / ${meta.priorityTotal} popular EPG ready"
                val ratio = done.toFloat() / meta.priorityTotal.toFloat()
                progress.progress = (150 + (ratio * 700).toInt()).coerceIn(150, 850)
            }
            !meta.priorityReady && meta.priorityTotal == 0 -> {
                // XMLTV parse in progress (priority counters not yet populated)
                headline.text = "Loading EPG data…"
                substatus.text = "${fmt(meta.channelsCount)} channels  ·  parsing XMLTV…"
                progress.progress = 200
            }
            else -> {
                // Priority done. Show minimum-hold countdown.
                val pct = (elapsed.toFloat() / minHoldMs.toFloat()).coerceIn(0f, 1f)
                headline.text = "Almost ready…"
                val secsLeft = ((minHoldMs - elapsed).coerceAtLeast(0L) / 1000L).toInt()
                val warmDone = meta.warmDone
                val warmTotal = meta.warmTotal
                val warmExtras = if (warmTotal > 0) {
                    "  ·  ${fmt(warmDone)} / ${fmt(warmTotal)} total EPG channels"
                } else ""
                substatus.text = if (secsLeft > 0) {
                    "${fmt(meta.channelsCount)} channels$warmExtras  ·  finalising in ${secsLeft}s"
                } else {
                    "${fmt(meta.channelsCount)} channels$warmExtras"
                }
                progress.progress = (850 + (pct * 100).toInt()).coerceIn(850, 950)
            }
        }
    }

    private fun buildElapsedLine(elapsedMs: Long): String {
        val total = elapsedMs / 1000L
        val m = total / 60
        val s = total % 60
        return if (m > 0) "Elapsed ${m}m ${s}s · poll #$pollCount"
        else "Elapsed ${s}s · poll #$pollCount"
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
