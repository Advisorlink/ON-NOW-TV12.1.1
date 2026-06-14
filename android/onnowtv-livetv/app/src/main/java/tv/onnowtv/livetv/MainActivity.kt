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
import tv.onnowtv.livetv.data.EpgCache
import tv.onnowtv.livetv.data.XmlTvFetcher
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

    // v2.9.8 — Shorter minimum hold.  The OLD 18-s hold made sense
    // when we were waiting for the backend's priority-EPG warm-up,
    // but the direct provider path returns the full channel list in
    // ~2 s — so a long hold just adds artificial delay.  6 s is
    // enough for the animated counter to feel alive.
    private val minHoldMs = 6_000L
    private val pollIntervalMs = 1_500L
    private val maxHoldMs = 5 * 60_000L

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

    // Rotating tips — swap every TIP_INTERVAL_MS so the user has
    // something to read during the long first-boot wait.
    private val TIPS = listOf(
        "TIP — Press OK on any guide row to set a reminder.",
        "TIP — Reminders glow YELLOW until they fire.",
        "TIP — The left rail filters channels by country and genre.",
        "TIP — The Search icon finds channels AND programmes by name.",
        "TIP — The next boot is INSTANT — your guide is cached on disk.",
        "TIP — Loading the FULL guide for UK · USA · AU Kayo · NZ Sports first.",
        "TIP — 14,000+ channels · the guide for everything else lazy-loads as you browse.",
        "TIP — D-pad UP/DOWN switches channels while you're watching.",
        "TIP — Sign out from the bottom of the left rail.",
    )
    private val TIP_INTERVAL_MS = 7_500L
    private var tipIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v2.9.5 — Xtream sign-in gate.  No saved credentials =
        // first launch (or just signed out).  Route straight to the
        // login screen; everything else assumes a valid Xtream
        // account.
        if (!tv.onnowtv.livetv.data.AuthStore.isSignedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // v2.10.54 — ALWAYS paint the loader content view BEFORE
        // doing any disk I/O so the user never sees a black screen.
        // The old code ran the entire fast path synchronously on
        // the main thread (BundleCache.loadJson → parse 14k-channel
        // JSON → EpgCache.load → walk every channel for the name
        // map).  On a weak Android-TV box that's 5-15 s of blank
        // screen before `setContentView` is even called, because
        // the Activity transition can't paint while the main thread
        // is busy.
        //
        // New layout: paint the splash NOW (in <50 ms), do the
        // entire fast path on Dispatchers.IO, and hand off to
        // EpgActivity the moment it's ready.  If the fast path
        // fails, fall through to the slow loader (`startLoad()`).
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

        // Show "Opening your guide" copy while the fast path runs.
        // If it succeeds (the common case) the user sees this for
        // ~500 ms before EpgActivity takes over — far better than
        // 10 s of black screen.
        headline.text = "Opening your guide…"
        substatus.text = "Loading your channels"
        statusCounters.text = ""
        progress.progress = 200

        startDotsAnimation()
        startBrandPulse()

        if (BundleCache.exists(this)) {
            lifecycleScope.launch {
                val merged = withContext(Dispatchers.IO) { tryFastPath() }
                if (merged != null) {
                    BundleHolder.current = merged
                    Log.i(
                        "MainActivity",
                        "fast-path: ${merged.channels.size} channels / epg=lazy-from-disk",
                    )
                    scheduleBackgroundRefresh()
                    try {
                        tv.onnowtv.livetv.data.EpgRefreshWorker
                            .schedulePeriodic(applicationContext)
                    } catch (t: Throwable) {
                        Log.w(
                            "MainActivity",
                            "fast-path epg worker enqueue failed: ${t.message}",
                        )
                    }
                    startActivity(Intent(this@MainActivity, EpgActivity::class.java))
                    overridePendingTransition(0, 0)
                    finish()
                } else {
                    // Fast path failed (cache absent / schema mismatch /
                    // I/O error).  Drop into the slow path.
                    Log.i(
                        "MainActivity",
                        "fast-path skipped or failed — falling through to slow loader",
                    )
                    startTipsRotation()
                    startLoad()
                }
            }
            return
        }

        // First-install path: no cache at all → go straight to the
        // slow loader (which paints the full counter UI + tips).
        startTipsRotation()
        startLoad()
    }

    /**
     * v2.10.54 — Disk-cache fast path, lifted out of [onCreate] so
     * it runs on a background dispatcher.  Returns the fully
     * patched + EPG-merged bundle on success, or `null` if the
     * cache was absent / corrupt / schema-mismatched, in which
     * case the caller drops into the slow loader.
     *
     * Must be called from a coroutine on `Dispatchers.IO`.
     */
    private fun tryFastPath(): XtreamBundle? {
        return try {
            val json = BundleCache.loadJson(this) ?: return null
            if (json.isBlank()) return null
            val bundle = XtreamRepository.parseBundleJson(json)
            if (bundle.channels.isEmpty()) return null

            // v2.10.14 — When EpgCache reports `null` for load() it
            // means the on-disk schema is older than the current
            // build.  Fall through to the slow loader so the XMLTV
            // preload runs and writes a v3 cache.
            //
            // v2.10.15 — Per-channel cache architecture.  load()
            // now returns an EMPTY map when a valid v3 cache exists
            // (programmes are lazy-loaded per channel from disk in
            // EpgActivity / PlayerActivity).
            val cachedEpg = EpgCache.load(this) ?: return null

            // Apply name-based id patching using the persisted XMLTV
            // display-name map.  Walks all channels but is cheap
            // (HashMap lookups + immutable List mapping).
            val nameMap = EpgCache.loadNameMap(applicationContext)
            val patchedChannels = if (nameMap.isEmpty()) {
                bundle.channels
            } else {
                var rescued = 0
                val out = bundle.channels.map { ch ->
                    val sid = ch.epgChannelId
                    if (!sid.isNullOrBlank()
                        && EpgCache.channelExists(applicationContext, sid)) {
                        return@map ch
                    }
                    val key = tv.onnowtv.livetv.data.XmlTvFetcher
                        .normaliseChannelName(ch.name)
                    if (key.isBlank()) return@map ch
                    val xmlId = nameMap[key] ?: return@map ch
                    if (!EpgCache.channelExists(applicationContext, xmlId)) {
                        return@map ch
                    }
                    rescued += 1
                    ch.copy(epgChannelId = xmlId)
                }
                Log.i(
                    "MainActivity",
                    "fast-path name-fallback: $rescued channels patched against persisted XMLTV name map",
                )
                out
            }

            bundle.copy(channels = patchedChannels, epg = cachedEpg)
        } catch (t: Throwable) {
            Log.w("MainActivity", "tryFastPath failed: ${t.message}")
            null
        }
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

    /**
     * v2.9.14 — Bounce back to LoginActivity with an error message
     * after the loader detects the saved credentials were rejected
     * by the provider.  Wipes the bad creds so the next launch
     * goes straight to the login screen and doesn't loop.
     */
    private fun sendBackToLogin(message: String) {
        Log.w("MainActivity", "sendBackToLogin: $message")
        tv.onnowtv.livetv.data.AuthStore.signOut(this)
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(LoginActivity.EXTRA_AUTH_ERROR, message),
        )
        overridePendingTransition(0, 0)
        finish()
    }

    /**
     * v2.9.11 — Surface a clear error screen with a RETRY button
     * when both backend AND direct provider paths have failed.
     * No technical detail (exception names, hostnames) is shown
     * — the user explicitly asked for nothing implementation-y
     * on the loader.  Just a friendly retry CTA + a fallback
     * "contact support" line.
     */
    private fun showFetchError(detail: String) {
        Log.w("MainActivity", "showFetchError: $detail")
        headline.text = "We can't load your guide right now"
        substatus.text = "Check your internet, then tap retry below."
        statusCounters.text = "Still stuck?  Contact ON NOW TV Support."
        progress.progress = 0
        retry.visibility = View.VISIBLE
        retry.setOnClickListener { startLoad() }
    }

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

        // v2.9.10 — Direct-first with NO head-start.  The backend
        // is currently IP-blocked by the provider so racing it
        // wastes 8 s on a guaranteed failure.  Fire the direct
        // path immediately; backend runs in the background as a
        // secondary path in case it ever comes back to life.
        bundleKick = lifecycleScope.async(Dispatchers.IO) {
            val directJob = async {
                try {
                    val text = tv.onnowtv.livetv.data.DirectProviderFetcher
                        .fetchBundleJson(applicationContext)
                    val b = XtreamRepository.parseBundleJson(text)
                    Log.i("MainActivity", "direct bundle: ${b.channels.size} channels, ${b.categories.size} categories")
                    text to b
                } catch (t: tv.onnowtv.livetv.data.DirectProviderFetcher.InvalidCredentialsException) {
                    // v2.9.14 — Provider explicitly rejected our
                    // saved creds.  Tag the error so the loader
                    // routes back to LoginActivity instead of the
                    // generic "couldn't reach support" screen.
                    Log.w("MainActivity", "direct bundle: invalid credentials")
                    bundleError = "INVALID_CREDS"
                    null
                } catch (t: Throwable) {
                    Log.w("MainActivity", "direct bundle fetch failed: ${t.javaClass.simpleName}: ${t.message}")
                    bundleError = "NETWORK"
                    null
                }
            }
            val backendJob = async {
                try {
                    val text = XtreamRepository.fetchBundleJson()
                    val b = XtreamRepository.parseBundleJson(text)
                    if (b.channels.isEmpty()) {
                        Log.w("MainActivity", "backend bundle had 0 channels — ignoring")
                        null
                    } else {
                        Log.i("MainActivity", "backend bundle: ${b.channels.size} channels, ${b.epg.size} epg buckets")
                        text to b
                    }
                } catch (t: Throwable) {
                    Log.w("MainActivity", "backend bundle fetch failed: ${t.javaClass.simpleName}: ${t.message}")
                    null
                }
            }
            // Race: whichever returns FIRST with non-null wins.
            var winner: Pair<String, XtreamBundle>? = null
            while (winner == null) {
                if (directJob.isCompleted) {
                    val r = directJob.await()
                    if (r != null) {
                        winner = r
                        backendJob.cancel()
                        break
                    }
                }
                if (backendJob.isCompleted) {
                    val r = backendJob.await()
                    if (r != null) {
                        winner = r
                        directJob.cancel()
                        break
                    }
                }
                if (directJob.isCompleted && backendJob.isCompleted) {
                    break
                }
                delay(250)
            }
            if (winner != null) {
                bundleJson = winner.first
                bundleResult = winner.second
                bundleError = null
                BundleCache.saveJson(applicationContext, winner.first)
            } else {
                if (bundleError == null) {
                    bundleError = "NETWORK"
                }
                Log.w("MainActivity", "bundle fetch failed entirely: $bundleError")
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
            //   1) Bundle has arrived (backend OR direct) — exit as
            //      soon as the minimum hold elapses.
            //   2) Popular EPG ready (backend healthy) and min hold.
            //   3) v2.9.10 — Bundle fetch fully failed and we've
            //      held the loader for a respectful 6 s.  Show
            //      a clear error screen with a Retry button instead
            //      of spinning forever on "Connecting to provider…".
            //   4) Safety hatch: any channels visible after maxHoldMs.
            val haveBundle = bundleResult != null
            val priorityReady = (lastMeta?.priorityReady == true) && (lastMeta?.channelsCount ?: 0) > 0
            if (haveBundle && elapsed >= minHoldMs) break
            if (priorityReady && elapsed >= minHoldMs) break

            val bothFailed = bundleKick?.isCompleted == true && bundleResult == null
            if (bothFailed && elapsed >= 4_000L) {
                // v2.9.14 — Wrong creds get bounced to login; only
                // genuine network/server failures get the support
                // screen.
                if (bundleError == "INVALID_CREDS") {
                    sendBackToLogin("Wrong username or password.  Please try again.")
                    return
                }
                showFetchError(bundleError ?: "NETWORK")
                return
            }

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
            // Last-chance synchronous fetch using direct provider.
            try {
                val text = tv.onnowtv.livetv.data.DirectProviderFetcher
                    .fetchBundleJson(applicationContext)
                BundleCache.saveJson(applicationContext, text)
                XtreamRepository.parseBundleJson(text)
            } catch (_: tv.onnowtv.livetv.data.DirectProviderFetcher.InvalidCredentialsException) {
                // v2.9.14 — Same bounce-to-login path as the
                // race-loop branch, just for the last-chance code
                // path that fires when the race loop somehow
                // exited without setting bundleResult.
                sendBackToLogin("Wrong username or password.  Please try again.")
                return
            }
        }

        // ─── v2.10.14 FULL EPG PREFETCH ────────────────────────────
        // User explicitly asked: "I want the FULL EPG cached for
        // ALL channels — three days of programmes, not less than
        // 24 hours.  Take a couple of minutes on the first launch
        // if you have to."
        //
        // We now pass EVERY bundle channel's `epg_channel_id` to
        // the XMLTV parser (not just the old UK/USA/AU/NZ priority
        // subset), and the parser separately captures a
        // display-name → id mapping for every <channel> element in
        // the file so we can back-fill channels whose provider-
        // supplied `epg_channel_id` is blank or doesn't line up
        // with what XMLTV actually serves.
        val wantedChannelIds = bundle.channels
            .mapNotNull { it.epgChannelId?.takeIf { id -> id.isNotBlank() } }
            .toHashSet()
        val wantedNormalisedNames = bundle.channels
            .map { XmlTvFetcher.normaliseChannelName(it.name) }
            .filter { it.isNotBlank() }
            .toHashSet()
        Log.i(
            "MainActivity",
            "full-EPG preload: ${bundle.channels.size} channels, " +
                "${wantedChannelIds.size} have an epg_channel_id from the provider, " +
                "${wantedNormalisedNames.size} unique normalised names for fallback matching",
        )

        var mergedBundle = bundle
        // v2.9.12 — Skip XMLTV preload entirely when EpgCache
        // already has data on disk.  Cache is permanent; user
        // explicitly asked to never re-fetch the EPG once it's
        // been loaded once on the foreground path (background
        // WorkManager refresh handles staleness — see
        // EpgRefreshWorker).
        val haveCachedEpg = tv.onnowtv.livetv.data.EpgCache.exists(applicationContext)
        if (haveCachedEpg) {
            val cached = tv.onnowtv.livetv.data.EpgCache.load(applicationContext)
            if (cached != null && cached.isNotEmpty()) {
                mergedBundle = bundle.copy(epg = cached)
                Log.i("MainActivity", "EPG cache hit: ${cached.size} channels — skipping XMLTV preload")
            }
        }

        if (!haveCachedEpg && wantedChannelIds.isNotEmpty()) {
            headline.text = "Loading the full 3-day guide…"
            substatus.text = "First-launch download — this only happens once."
            statusCounters.text = "Connecting…"
            progress.progress = 850

            // v2.10.15 — Stream programmes DIRECTLY to per-channel
            // disk files during parse so we never hold more than
            // ~5 MB of programme data in memory.  The previous
            // revision OOM'd on the user's 256 MB-heap box partway
            // through "Parsing 3 days of programmes…".
            val writer = EpgCache.openStreamingWriter(applicationContext)

            val parseResult = try {
                XmlTvFetcher.fetchEpgForChannels(
                    applicationContext,
                    wantedChannelIds,
                    wantedNormalisedNames,
                    writer = writer,
                ) { chSeen, progs ->
                    // Throttled inside the parser already, but
                    // marshal to the UI thread before touching views.
                    runOnUiThread {
                        substatus.text = "Parsing 3 days of programmes…"
                        statusCounters.text = "${fmt(progs)} programmes · ${fmt(chSeen)} EPG channels seen"
                        // Drift progress bar from 850 → 960 as parse
                        // works through the file.
                        val approx = (progs.toFloat() / 600_000f).coerceIn(0f, 1f)
                        progress.progress = (850 + (approx * 110).toInt()).coerceIn(850, 960)
                    }
                }
            } catch (t: Throwable) {
                Log.w("MainActivity", "XMLTV prefetch failed: ${t.message}")
                writer.abort()
                null
            }

            if (parseResult != null) {
                // ─── Name-based fallback: patch bundle channels ───
                // The parser ALREADY expanded its wanted-set in-line
                // when it saw a <channel><display-name> matching one
                // of `wantedNormalisedNames` — so programmes for
                // those XMLTV ids are already on disk.  All we have
                // to do here is rewrite the BUNDLE channel's
                // `epgChannelId` to that XMLTV id so subsequent
                // disk lookups go to the right file.
                val nameMap = parseResult.displayNameToEpgId
                val patchedChannels: List<tv.onnowtv.livetv.data.Channel>
                if (nameMap.isNotEmpty()) {
                    var rescued = 0
                    patchedChannels = bundle.channels.map { ch ->
                        val sid = ch.epgChannelId
                        if (!sid.isNullOrBlank()
                            && parseResult.channelsWritten.contains(sid)) {
                            return@map ch  // already has its own EPG
                        }
                        val key = XmlTvFetcher.normaliseChannelName(ch.name)
                        if (key.isBlank()) return@map ch
                        val xmlId = nameMap[key] ?: return@map ch
                        if (!parseResult.channelsWritten.contains(xmlId)) return@map ch
                        rescued += 1
                        // For channels with NO provider id, rewrite
                        // outright.  For channels with an id that
                        // didn't match, also rewrite to the XMLTV id
                        // — the disk lookup path needs SOMETHING
                        // that hits a file.
                        ch.copy(epgChannelId = xmlId)
                    }
                    Log.i(
                        "MainActivity",
                        "name-fallback: $rescued additional channels matched to XMLTV by display-name",
                    )
                } else {
                    patchedChannels = bundle.channels
                }

                val writeResult = writer.finish(parseResult.displayNameToEpgId)
                Log.i(
                    "MainActivity",
                    "EPG cache write committed: " +
                        "${writeResult.channelsFlushed} channels / " +
                        "${writeResult.totalProgrammes} programmes",
                )

                // Bundle keeps the patched channel list but does NOT
                // carry programmes in memory — EpgActivity reads
                // each channel's programmes lazily from disk via
                // EpgCache.loadChannel().
                mergedBundle = bundle.copy(
                    channels = patchedChannels,
                    epg = emptyMap(),
                )
            }
        }

        // v2.10.14 — Schedule the once-every-12-hours background
        // EPG refresh.  Idempotent (KEEP policy) so re-enqueuing
        // on every cold boot is a no-op once it's running.
        try {
            tv.onnowtv.livetv.data.EpgRefreshWorker.schedulePeriodic(applicationContext)
        } catch (t: Throwable) {
            Log.w("MainActivity", "background EPG refresh enqueue failed: ${t.message}")
        }

        BundleHolder.current = mergedBundle
        progress.progress = 1000

        startActivity(Intent(this@MainActivity, EpgActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * v2.10.14 — KEPT FOR ARCHAEOLOGY.  The priority filter is no
     * longer applied by the loader (we now fetch the full XMLTV
     * for every channel in the bundle), but this predicate is
     * retained as documentation of the old "warming buckets" the
     * v2.9.x release line shipped — useful when comparing log
     * output from older devices in the field.
     */
    @Suppress("unused")
    private fun isPriorityChannel(
        ch: tv.onnowtv.livetv.data.Channel,
        categories: List<tv.onnowtv.livetv.data.Category>,
    ): Boolean {
        val catName = categories.firstOrNull { it.id == ch.categoryId }?.name?.uppercase()
            ?: ch.name.uppercase()
        if (catName.contains("UKRAINE")) return false
        // UK (anywhere)
        if (catName.startsWith("UK |") || catName.contains("==== UK ") ||
            catName.startsWith("====UK") || catName == "DAZN UK" ||
            catName.contains("AMAZON UK")
        ) return true
        // USA (anywhere)
        if (catName.startsWith("USA |") || catName.startsWith("USA ") ||
            catName.contains("====USA") || catName == "DAZN USA"
        ) return true
        // AU / Kayo
        if (catName.contains("KAYO") || catName.contains("FOX/KAYO")) return true
        // NZ Sports
        if (catName.contains("SKY SPORTS (NZ)") ||
            (catName.contains("NZ") && catName.contains("SPORT"))
        ) return true
        return false
    }

    private fun applyMeta(meta: Meta?, elapsedMs: Long, lastMetaErrorAt: Long) {
        // v2.9.8 — Once the direct OR backend bundle has actually
        // returned channels to us, we KNOW how many channels the
        // user is getting — so surface that in the counter
        // immediately even when the backend's `/meta` endpoint is
        // returning channels_count=0 (which happens when the VPS
        // can't reach the provider).
        val directChannels = bundleResult?.channels?.size ?: 0
        val effectiveChannels = maxOf(meta?.channelsCount ?: 0, directChannels)
        val effectivePriorityDone = meta?.priorityDone ?: 0
        val effectivePriorityTotal = meta?.priorityTotal ?: 0
        val priorityReady = meta?.priorityReady ?: false

        if (meta == null && directChannels == 0) {
            headline.text = "Connecting to backend…"
            substatus.text = if (lastMetaErrorAt > 0) "Server unreachable — retrying…" else "Polling backend…"
            statusCounters.text = ""
            progress.progress = 40
            return
        }

        // Smoothly animate the counters so the numbers feel ALIVE
        // (creeping up) instead of jumping every 1.5 s poll.
        animateCounterTo(effectiveChannels, effectivePriorityDone, effectivePriorityTotal)
        lastPriorityTotal = effectivePriorityTotal

        when {
            effectiveChannels == 0 -> {
                headline.text = "Loading channels…"
                substatus.text = "Connecting to provider…"
                val secs = (elapsedMs / 1000).toInt()
                progress.progress = (60 + (secs * 3)).coerceAtMost(140)
            }
            !priorityReady && effectivePriorityTotal > 0 -> {
                headline.text = "Warming the guide…"
                substatus.text = "Loading UK · US · AU · Kayo channels"
                val ratio = effectivePriorityDone.toFloat() / effectivePriorityTotal.toFloat()
                progress.progress = (150 + (ratio * 700).toInt()).coerceIn(150, 850)
            }
            // Direct path already returned — backend is irrelevant.
            // Show a confident progress bar headed toward "Almost ready".
            !priorityReady && effectivePriorityTotal == 0 && directChannels > 0 -> {
                headline.text = "Found ${fmt(directChannels)} channels"
                substatus.text = "Finalising guide…"
                val pct = (elapsedMs.toFloat() / minHoldMs.toFloat()).coerceIn(0f, 1f)
                progress.progress = (300 + (pct * 600).toInt()).coerceIn(300, 900)
            }
            !priorityReady && effectivePriorityTotal == 0 -> {
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
