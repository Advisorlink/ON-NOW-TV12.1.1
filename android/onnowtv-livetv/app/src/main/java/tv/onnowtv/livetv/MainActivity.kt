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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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
 *     while waiting ("Loading channels…", "Warming popular
 *     regions… 1,820 / 2,400", "Almost ready — finalising guide…").
 *   - Only AFTER both conditions are met do we fetch the full
 *     gzipped bundle and hand off to EpgActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var headline: TextView
    private lateinit var substatus: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: TextView

    private val minHoldMs = 60_000L
    private val pollIntervalMs = 1_500L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        headline  = findViewById(R.id.loader_headline)
        substatus = findViewById(R.id.loader_substatus)
        progress  = findViewById(R.id.loader_progress)
        retry     = findViewById(R.id.loader_retry)

        startLoad()
    }

    private fun startLoad() {
        retry.visibility = View.GONE
        headline.text = "Connecting…"
        substatus.text = ""
        progress.progress = 0

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

        // Phase: keep polling until BOTH (a) priority EPG is ready
        // AND (b) the minimum hold time has elapsed.  Drive the
        // progress bar from a blend of priority-warm progress and
        // the 60s minimum-hold clock.
        while (lifecycleScope.isActive) {
            val meta = try {
                fetchMeta()
            } catch (t: Throwable) {
                Log.w("MainActivity", "meta fetch failed: ${t.message}")
                null
            }
            if (meta != null) lastMeta = meta
            applyMeta(lastMeta, started)

            val elapsed = SystemClock.elapsedRealtime() - started
            val priorityReady = lastMeta?.priorityReady == true && (lastMeta?.channelsCount ?: 0) > 0
            if (priorityReady && elapsed >= minHoldMs) break
            // Safety hatch: if we've been waiting for 5 min and
            // still nothing, drop into the EPG anyway with whatever
            // EPG is populated so far — the per-channel lazy fetch
            // will fill in the gaps.
            if (elapsed >= 5 * 60_000L && (lastMeta?.channelsCount ?: 0) > 0) {
                Log.w("MainActivity", "loader safety hatch — entering EPG with partial EPG warm")
                break
            }
            delay(pollIntervalMs)
        }

        // Final phase: fetch the bundle itself (it's been being
        // rebuilt server-side after every priority pass).
        headline.text = "Finalising guide…"
        substatus.text = "Downloading bundle…"
        progress.progress = 970

        val bundle = XtreamRepository.fetchBundle()
        BundleHolder.current = bundle
        Log.i("MainActivity", "bundle: ${bundle.channels.size} channels, ${bundle.epg.size} epg buckets")
        progress.progress = 1000

        startActivity(Intent(this@MainActivity, EpgActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * Render the current meta payload into the loader UI.
     * Computes a blended progress percentage:
     *   - 0..15%  → channels fetched
     *   - 15..85% → priority EPG warm progress
     *   - 85..95% → minimum-hold clock (so the bar always moves
     *               even after priority is done but we're still
     *               holding the 60-second minimum)
     *   - 95..100% reserved for the final bundle download.
     */
    private fun applyMeta(meta: Meta?, startedMs: Long) {
        if (meta == null) {
            headline.text = "Connecting to backend…"
            substatus.text = ""
            return
        }

        when {
            meta.channelsCount == 0 -> {
                headline.text = "Loading channels…"
                substatus.text = "Connecting to provider…"
                progress.progress = 80
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
                val elapsed = SystemClock.elapsedRealtime() - startedMs
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
}

/** Process-scoped holder for the freshly-fetched bundle.  Avoids
 *  serialising channels + EPG through an Intent extra (which would
 *  blow the Binder 1 MB transaction limit). */
object BundleHolder {
    @Volatile var current: tv.onnowtv.livetv.data.XtreamBundle? = null
}
